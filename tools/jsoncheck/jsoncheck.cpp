// jsoncheck.cpp - standalone line-wise JSON validator for 拟股喵喵 protocol output.
//
// Purpose: machine-verify that every line the engine writes to stdout is
//   * a single line of text (this tool reads by line, so any embedded newline
//     simply shows up as a second line that fails to parse),
//   * valid UTF-8,
//   * parseable JSON in full (no trailing garbage after the value),
//   * and, unless -any is given, a top-level JSON object.
//
// It deliberately has zero dependencies: no engine/src headers, no STL JSON,
// no third-party libraries.  C++17, compiles with g++ (MSYS2 ucrt64) and MSVC.
//
// Usage:
//   jsoncheck [options] < input.txt
//   jsoncheck [options] file1.jsonl file2.jsonl
//
// Options:
//   -h, --help      show help
//   -q, --quiet     print nothing on success (still prints FAIL lines)
//   -v, --verbose   print one "OK <n> ..." line per input line
//   -max N          allow at most N FAIL lines before stopping (default 20)
//   -any            accept any top-level JSON value (not only objects)
//   -allow-empty    treat blank lines as OK (skip them, do not count)
//   --summary       print only the final summary line
//
// Exit codes: 0 = every line OK, 1 = at least one FAIL, 2 = usage/IO error.
// Output is ASCII only, so it is safe under any console code page.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

// ---------------------------------------------------------------- UTF-8 ----

// Returns nullptr when valid; otherwise a static description of the problem.
const char* utf8_validate(const unsigned char* p, size_t n, size_t* badOffset) {
    size_t i = 0;
    while (i < n) {
        unsigned char c = p[i];
        if (c < 0x80) { ++i; continue; }
        size_t need = 0;
        unsigned int cp = 0;
        if ((c & 0xE0) == 0xC0) { need = 1; cp = c & 0x1Fu; }
        else if ((c & 0xF0) == 0xE0) { need = 2; cp = c & 0x0Fu; }
        else if ((c & 0xF8) == 0xF0) { need = 3; cp = c & 0x07u; }
        else { if (badOffset) *badOffset = i; return "invalid UTF-8 lead byte"; }
        if (i + need >= n + 0 && i + need > n - 1) {
            if (badOffset) *badOffset = i;
            return "truncated UTF-8 sequence";
        }
        for (size_t k = 1; k <= need; ++k) {
            unsigned char cc = p[i + k];
            if ((cc & 0xC0) != 0x80) {
                if (badOffset) *badOffset = i;
                return "invalid UTF-8 continuation byte";
            }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        if (need == 1 && cp < 0x80) { if (badOffset) *badOffset = i; return "overlong UTF-8 sequence"; }
        if (need == 2 && cp < 0x800) { if (badOffset) *badOffset = i; return "overlong UTF-8 sequence"; }
        if (need == 3 && cp < 0x10000) { if (badOffset) *badOffset = i; return "overlong UTF-8 sequence"; }
        if (cp > 0x10FFFF) { if (badOffset) *badOffset = i; return "UTF-8 code point out of range"; }
        if (cp >= 0xD800 && cp <= 0xDFFF) { if (badOffset) *badOffset = i; return "UTF-8 surrogate code point"; }
        i += need + 1;
    }
    return nullptr;
}

// ------------------------------------------------------------- parser -----

struct Parser {
    const char* s;
    size_t n;
    size_t i;
    std::string err;
    bool anyTop;          // accept any top-level value
    int depth;
    int maxDepth;

    Parser(const char* text, size_t len, bool acceptAny)
        : s(text), n(len), i(0), anyTop(acceptAny), depth(0), maxDepth(0) {}

    bool eof() const { return i >= n; }
    char cur() const { return i < n ? s[i] : '\0'; }
    bool fail(const std::string& msg) {
        if (err.empty()) {
            char buf[64];
            std::snprintf(buf, sizeof(buf), " at byte %llu", (unsigned long long)i);
            err = msg + buf;
        }
        return false;
    }
    void skipWs() {
        while (i < n) {
            char c = s[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') ++i;
            else break;
        }
    }
    bool parseValue() {
        if (++depth > 64) return fail("nesting too deep");
        bool r = parseValueInner();
        --depth;
        return r;
    }
    bool parseValueInner() {
        skipWs();
        if (eof()) return fail("unexpected end of input");
        char c = cur();
        switch (c) {
            case '{': return parseObject();
            case '[': return parseArray();
            case '"': { std::string t; return parseString(t); }
            case 't': return literal("true");
            case 'f': return literal("false");
            case 'n': return literal("null");
            default:  return parseNumber();
        }
    }
    bool literal(const char* lit) {
        size_t len = std::strlen(lit);
        if (n - i < len || std::memcmp(s + i, lit, len) != 0) return fail("bad literal");
        i += len;
        return true;
    }
    bool parseObject() {
        ++i;  // '{'
        skipWs();
        if (cur() == '}') { ++i; return true; }
        for (;;) {
            skipWs();
            if (cur() != '"') return fail("object key must be a string");
            std::string key;
            if (!parseString(key)) return false;
            skipWs();
            if (cur() != ':') return fail("expected ':' after object key");
            ++i;
            if (!parseValue()) return false;
            skipWs();
            if (cur() == ',') { ++i; continue; }
            if (cur() == '}') { ++i; return true; }
            return fail("expected ',' or '}' in object");
        }
    }
    bool parseArray() {
        ++i;  // '['
        skipWs();
        if (cur() == ']') { ++i; return true; }
        for (;;) {
            if (!parseValue()) return false;
            skipWs();
            if (cur() == ',') { ++i; continue; }
            if (cur() == ']') { ++i; return true; }
            return fail("expected ',' or ']' in array");
        }
    }
    bool parseHex4(unsigned& out) {
        if (n - i < 4) return fail("short \\u escape");
        out = 0;
        for (int k = 0; k < 4; ++k) {
            char c = s[i + k];
            unsigned d;
            if (c >= '0' && c <= '9') d = (unsigned)(c - '0');
            else if (c >= 'a' && c <= 'f') d = (unsigned)(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') d = (unsigned)(c - 'A' + 10);
            else return fail("bad hex digit in \\u escape");
            out = (out << 4) | d;
        }
        i += 4;
        return true;
    }
    bool parseString(std::string& out) {
        ++i;  // '"'
        out.clear();
        for (;;) {
            if (eof()) return fail("unterminated string");
            unsigned char c = (unsigned char)s[i];
            if (c == '"') { ++i; return true; }
            if (c < 0x20) return fail("raw control character in string");
            if (c == '\\') {
                ++i;
                if (eof()) return fail("unterminated escape");
                char e = s[i++];
                switch (e) {
                    case '"': out += '"'; break;
                    case '\\': out += '\\'; break;
                    case '/': out += '/'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'n': out += '\n'; break;
                    case 'r': out += '\r'; break;
                    case 't': out += '\t'; break;
                    case 'u': {
                        unsigned u = 0;
                        if (!parseHex4(u)) return false;
                        out += (char)0;  // placeholder, value irrelevant for validation
                        break;
                    }
                    default: return fail("invalid escape sequence");
                }
                continue;
            }
            out += (char)c;
            ++i;
        }
    }
    bool parseNumber() {
        size_t start = i;
        if (cur() == '-') ++i;
        if (eof()) return fail("truncated number");
        if (cur() == '0') {
            ++i;
        } else if (cur() >= '1' && cur() <= '9') {
            while (!eof() && cur() >= '0' && cur() <= '9') ++i;
        } else {
            return fail("invalid number");
        }
        if (!eof() && cur() == '.') {
            ++i;
            if (eof() || cur() < '0' || cur() > '9') return fail("digit expected after '.'");
            while (!eof() && cur() >= '0' && cur() <= '9') ++i;
        }
        if (!eof() && (cur() == 'e' || cur() == 'E')) {
            ++i;
            if (!eof() && (cur() == '+' || cur() == '-')) ++i;
            if (eof() || cur() < '0' || cur() > '9') return fail("digit expected in exponent");
            while (!eof() && cur() >= '0' && cur() <= '9') ++i;
        }
        if (i == start) return fail("invalid number");
        return true;
    }
};

struct CheckResult {
    bool ok;
    std::string reason;
    char topType;  // '{' '[' '"' 'n' 't' 'f' '0'
};

CheckResult checkLine(const std::string& line, bool anyTop) {
    CheckResult r;
    r.ok = false;
    r.topType = 0;
    if (line.empty()) {
        r.reason = "empty line";
        return r;
    }
    size_t badOff = 0;
    const char* uerr = utf8_validate((const unsigned char*)line.data(), line.size(), &badOff);
    if (uerr) {
        char buf[128];
        std::snprintf(buf, sizeof(buf), "%s (byte %llu)", uerr, (unsigned long long)badOff);
        r.reason = buf;
        return r;
    }
    // Reject a UTF-8 BOM: protocol lines must not carry one.
    if (line.size() >= 3 && (unsigned char)line[0] == 0xEF &&
        (unsigned char)line[1] == 0xBB && (unsigned char)line[2] == 0xBF) {
        r.reason = "UTF-8 BOM at start of line";
        return r;
    }
    Parser p(line.data(), line.size(), anyTop);
    if (!p.parseValue()) { r.reason = p.err; return r; }
    p.skipWs();
    if (!p.eof()) { r.reason = "trailing data after JSON value"; return r; }
    // record top-level type
    size_t j = 0;
    while (j < line.size() && (line[j] == ' ' || line[j] == '\t')) ++j;
    char c = j < line.size() ? line[j] : 0;
    r.topType = c;
    if (!anyTop && c != '{') {
        r.reason = "top-level value is not a JSON object";
        return r;
    }
    r.ok = true;
    return r;
}

void usage(std::FILE* f) {
    std::fprintf(f,
        "jsoncheck - line-wise JSON/UTF-8 validator (拟股喵喵)\n"
        "\n"
        "usage: jsoncheck [-q] [-v] [-any] [-allow-empty] [--summary] [-max N] [file...]\n"
        "  no file arguments: read from stdin, one protocol line per line\n"
        "  -q            quiet on success (FAIL lines are always printed)\n"
        "  -v            verbose: one line per input line\n"
        "  -any          accept any top-level value, not only objects\n"
        "  -allow-empty  skip blank lines instead of failing them\n"
        "  --summary     print only the final summary\n"
        "  -max N        stop printing after N failures (default 20)\n"
        "exit: 0 all OK, 1 at least one FAIL, 2 usage/IO error\n");
}

}  // namespace

int main(int argc, char** argv) {
    bool quiet = false, verbose = false, anyTop = false, allowEmpty = false, summaryOnly = false;
    long maxFail = 20;
    std::vector<std::string> files;

    for (int a = 1; a < argc; ++a) {
        std::string arg = argv[a];
        if (arg == "-h" || arg == "--help") { usage(stdout); return 0; }
        else if (arg == "-q" || arg == "--quiet") quiet = true;
        else if (arg == "-v" || arg == "--verbose") verbose = true;
        else if (arg == "-any" || arg == "--any") anyTop = true;
        else if (arg == "-allow-empty" || arg == "--allow-empty") allowEmpty = true;
        else if (arg == "--summary") summaryOnly = true;
        else if (arg == "-max" || arg == "--max") {
            if (a + 1 >= argc) { usage(stderr); return 2; }
            maxFail = std::strtol(argv[++a], nullptr, 10);
            if (maxFail < 0) maxFail = 0;
        } else if (!arg.empty() && arg[0] == '-') {
            std::fprintf(stderr, "jsoncheck: unknown option '%s'\n", arg.c_str());
            usage(stderr);
            return 2;
        } else {
            files.push_back(arg);
        }
    }

    long total = 0, okCount = 0, failCount = 0, skipped = 0, printedFail = 0;
    const char* sourceName = "<stdin>";

    auto handleLine = [&](const std::string& line, bool fromFile) {
        (void)fromFile;
        if (allowEmpty) {
            bool blank = true;
            for (char c : line) if (c != ' ' && c != '\t' && c != '\r') { blank = false; break; }
            if (blank) { ++skipped; return; }
        }
        ++total;
        CheckResult r = checkLine(line, anyTop);
        if (r.ok) {
            ++okCount;
            if (verbose && !summaryOnly) {
                std::printf("OK %ld (%s)\n", total, sourceName);
            }
        } else {
            ++failCount;
            if (printedFail < maxFail && !summaryOnly) {
                ++printedFail;
                std::string shown = line;
                if (shown.size() > 160) shown = shown.substr(0, 157) + "...";
                std::printf("FAIL %ld: %s\n", total, r.reason.c_str());
                std::printf("     | %s\n", shown.c_str());
            } else if (printedFail == maxFail && !summaryOnly) {
                ++printedFail;
                std::printf("... further FAIL lines suppressed (use -max N)\n");
            }
        }
    };

    auto runStream = [&](std::FILE* f, const char* name) {
        sourceName = name;
        std::string line;
        int ch;
        while ((ch = std::fgetc(f)) != EOF) {
            if (ch == '\n') { handleLine(line, true); line.clear(); }
            else if (ch == '\r') { /* swallow; CRLF handled */ }
            else line += (char)ch;
        }
        if (!line.empty()) handleLine(line, true);  // final line without newline
    };

    if (files.empty()) {
        runStream(stdin, "<stdin>");
    } else {
        for (const std::string& path : files) {
            std::FILE* f = std::fopen(path.c_str(), "rb");
            if (!f) {
                std::fprintf(stderr, "jsoncheck: cannot open '%s'\n", path.c_str());
                return 2;
            }
            sourceName = path.c_str();
            runStream(f, path.c_str());
            std::fclose(f);
        }
    }

    if (!quiet || failCount > 0) {
        std::printf("jsoncheck: lines=%ld ok=%ld fail=%ld skipped=%ld topType=object-only=%s\n",
                    total, okCount, failCount, skipped, anyTop ? "no" : "yes");
    }
    if (failCount == 0) std::printf("OK %ld\n", okCount);
    else std::printf("FAIL %ld line(s) out of %ld\n", failCount, total);
    return failCount == 0 ? 0 : 1;
}
