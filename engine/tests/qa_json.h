// ============================================================================
// qa_json.h  —  独立实现的 JSON 解析器（QA 专用）
// 作者: qa-verify。**不包含任何被测代码**，纯从零实现 RFC8259 子集。
// 目的: 用与引擎完全不同的实现来解析引擎输出，避免"同源bug互相掩盖"。
//
// 设计要点（刻意与被测代码不同）:
//   * 保留数字的**原始词法文本** raw，以便检测科学计数法 / 前导零 / 尾随点
//   * 保留对象的**键顺序**与**重复键计数**（重复键视为协议违规）
//   * 区分 int / double（按词法判断是否含 '.' / 'e'）
//   * 严格 UTF-8 校验（拒绝非法字节序列）
//   * 严格拒绝 NaN/Infinity/单引号/尾随逗号/注释
// ============================================================================
#pragma once
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <stdexcept>
#include <cmath>
#include <cstdint>
#include <cstdio>

namespace qaj {

// ---------------------------------------------------------------- UTF-8 校验
// 返回 true 表示 s 是合法 UTF-8（不接受 overlong / 代理区 / >U+10FFFF）
inline bool utf8_valid(const std::string& s, size_t* badPos = nullptr) {
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = (unsigned char)s[i];
        size_t extra = 0;
        unsigned int cp = 0;
        if (c < 0x80) { i++; continue; }
        else if ((c & 0xE0) == 0xC0) { extra = 1; cp = c & 0x1F; }
        else if ((c & 0xF0) == 0xE0) { extra = 2; cp = c & 0x0F; }
        else if ((c & 0xF8) == 0xF0) { extra = 3; cp = c & 0x07; }
        else { if (badPos) *badPos = i; return false; }
        if (i + extra >= n) { if (badPos) *badPos = i; return false; }
        for (size_t k = 1; k <= extra; k++) {
            unsigned char cc = (unsigned char)s[i + k];
            if ((cc & 0xC0) != 0x80) { if (badPos) *badPos = i + k; return false; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        // overlong / 范围检查
        if (extra == 1 && cp < 0x80) { if (badPos) *badPos = i; return false; }
        if (extra == 2 && cp < 0x800) { if (badPos) *badPos = i; return false; }
        if (extra == 3 && cp < 0x10000) { if (badPos) *badPos = i; return false; }
        if (cp > 0x10FFFF) { if (badPos) *badPos = i; return false; }
        if (cp >= 0xD800 && cp <= 0xDFFF) { if (badPos) *badPos = i; return false; }
        i += extra + 1;
    }
    return true;
}

struct Value;
using ValuePtr = std::shared_ptr<Value>;

enum class Type { Null, Bool, Num, Str, Arr, Obj };

struct Value {
    Type type = Type::Null;
    bool b = false;
    double num = 0;
    bool numIsInt = false;          // 词法上是整数
    std::string raw;                // 数字的原始文本（重要：检测科学计数法）
    std::string str;
    std::vector<ValuePtr> arr;
    std::vector<std::string> keys;  // 保持出现顺序
    std::map<std::string, ValuePtr> obj;
    std::vector<std::string> dupKeys; // 重复出现的键

    bool isNull()  const { return type == Type::Null; }
    bool isBool()  const { return type == Type::Bool; }
    bool isNum()   const { return type == Type::Num; }
    bool isStr()   const { return type == Type::Str; }
    bool isArr()   const { return type == Type::Arr; }
    bool isObj()   const { return type == Type::Obj; }

    // 取成员（不存在返回 nullptr）
    ValuePtr get(const std::string& k) const {
        auto it = obj.find(k);
        return it == obj.end() ? nullptr : it->second;
    }
    bool has(const std::string& k) const { return obj.count(k) != 0; }
    size_t size() const { return type == Type::Arr ? arr.size() : obj.size(); }
    // 所有键（有序）
    std::vector<std::string> allKeys() const { return keys; }

    // ---- 宽松取值（用于打印诊断）----
    std::string asStr() const {
        if (type == Type::Str) return str;
        if (type == Type::Num) return raw;
        if (type == Type::Bool) return b ? "true" : "false";
        if (type == Type::Null) return "null";
        return type == Type::Arr ? "[]" : "{}";
    }
    // ---- 严格取值：类型不符时抛 ----
    double asNum() const {
        if (type != Type::Num) throw std::runtime_error(std::string("type: expected number, got ") + typeName());
        return num;
    }
    int64_t asInt() const {
        if (type != Type::Num) throw std::runtime_error(std::string("type: expected integer, got ") + typeName());
        if (!numIsInt) throw std::runtime_error(std::string("type: expected integer, got fractional"));
        return (int64_t)llround(num);
    }
    bool asBool() const {
        if (type != Type::Bool) throw std::runtime_error(std::string("type: expected bool, got ") + typeName());
        return b;
    }
    std::string asString() const {
        if (type != Type::Str) throw std::runtime_error(std::string("type: expected string, got ") + typeName());
        return str;
    }
    const char* typeName() const {
        switch (type) {
            case Type::Null: return "null";
            case Type::Bool: return "bool";
            case Type::Num:  return "number";
            case Type::Str:  return "string";
            case Type::Arr:  return "array";
            case Type::Obj:  return "object";
        }
        return "?";
    }
};

struct ParseError : std::runtime_error {
    size_t pos;
    ParseError(const std::string& m, size_t p) : std::runtime_error(m), pos(p) {}
};

class Parser {
public:
    explicit Parser(const std::string& s) : s_(s) {}

    ValuePtr parseWhole() {
        skipWs();
        ValuePtr v = parseValue(0);
        skipWs();
        if (i_ != s_.size()) fail("trailing garbage after JSON value");
        return v;
    }

private:
    const std::string& s_;
    size_t i_ = 0;

    [[noreturn]] void fail(const std::string& m) { throw ParseError(m, i_); }

    void skipWs() {
        while (i_ < s_.size()) {
            char c = s_[i_];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i_++;
            else break;
        }
    }
    char peek() { return i_ < s_.size() ? s_[i_] : '\0'; }

    ValuePtr parseValue(int depth) {
        if (depth > 200) fail("nesting too deep");
        skipWs();
        if (i_ >= s_.size()) fail("unexpected end of input");
        char c = s_[i_];
        switch (c) {
            case '{': return parseObject(depth);
            case '[': return parseArray(depth);
            case '"': { auto v = std::make_shared<Value>(); v->type = Type::Str; v->str = parseString(); return v; }
            case 't': expect("true");  { auto v = std::make_shared<Value>(); v->type = Type::Bool; v->b = true;  return v; }
            case 'f': expect("false"); { auto v = std::make_shared<Value>(); v->type = Type::Bool; v->b = false; return v; }
            case 'n': expect("null");  { auto v = std::make_shared<Value>(); v->type = Type::Null; return v; }
            case 'N': case 'I': fail("NaN/Infinity not allowed in JSON");
            default:  return parseNumber();
        }
    }

    void expect(const char* lit) {
        size_t n = std::char_traits<char>::length(lit);
        if (s_.compare(i_, n, lit) != 0) fail(std::string("expected '") + lit + "'");
        i_ += n;
    }

    ValuePtr parseObject(int depth) {
        auto v = std::make_shared<Value>();
        v->type = Type::Obj;
        i_++; // {
        skipWs();
        if (peek() == '}') { i_++; return v; }
        for (;;) {
            skipWs();
            if (peek() != '"') fail("expected object key string");
            std::string k = parseString();
            skipWs();
            if (peek() != ':') fail("expected ':' after object key");
            i_++;
            ValuePtr val = parseValue(depth + 1);
            if (v->obj.count(k)) v->dupKeys.push_back(k);
            else v->keys.push_back(k);
            v->obj[k] = val;
            skipWs();
            char c = peek();
            if (c == ',') { i_++; continue; }
            if (c == '}') { i_++; return v; }
            fail("expected ',' or '}' in object");
        }
    }

    ValuePtr parseArray(int depth) {
        auto v = std::make_shared<Value>();
        v->type = Type::Arr;
        i_++; // [
        skipWs();
        if (peek() == ']') { i_++; return v; }
        for (;;) {
            v->arr.push_back(parseValue(depth + 1));
            skipWs();
            char c = peek();
            if (c == ',') { i_++; continue; }
            if (c == ']') { i_++; return v; }
            fail("expected ',' or ']' in array");
        }
    }

    std::string parseString() {
        i_++; // opening quote
        std::string out;
        for (;;) {
            if (i_ >= s_.size()) fail("unterminated string");
            unsigned char c = (unsigned char)s_[i_];
            if (c == '"') { i_++; break; }
            if (c == '\\') {
                i_++;
                if (i_ >= s_.size()) fail("unterminated escape");
                char e = s_[i_++];
                switch (e) {
                    case '"':  out += '"';  break;
                    case '\\': out += '\\'; break;
                    case '/':  out += '/';  break;
                    case 'b':  out += '\b'; break;
                    case 'f':  out += '\f'; break;
                    case 'n':  out += '\n'; break;
                    case 'r':  out += '\r'; break;
                    case 't':  out += '\t'; break;
                    case 'u': {
                        unsigned int cp = parseHex4();
                        if (cp >= 0xD800 && cp <= 0xDBFF) {
                            // 需要低位代理
                            if (i_ + 1 < s_.size() && s_[i_] == '\\' && s_[i_+1] == 'u') {
                                i_ += 2;
                                unsigned int lo = parseHex4();
                                if (lo < 0xDC00 || lo > 0xDFFF) fail("bad low surrogate");
                                unsigned int full = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                                appendUtf8(out, full);
                            } else {
                                fail("lone high surrogate");
                            }
                        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                            fail("lone low surrogate");
                        } else {
                            appendUtf8(out, cp);
                        }
                        break;
                    }
                    default: fail("invalid escape character");
                }
            } else if (c < 0x20) {
                fail("raw control character in string");
            } else {
                out += (char)c;
                i_++;
            }
        }
        return out;
    }

    unsigned int parseHex4() {
        if (i_ + 4 > s_.size()) fail("truncated \\u escape");
        unsigned int v = 0;
        for (int k = 0; k < 4; k++) {
            char c = s_[i_++];
            unsigned int d;
            if (c >= '0' && c <= '9') d = (unsigned)(c - '0');
            else if (c >= 'a' && c <= 'f') d = (unsigned)(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') d = (unsigned)(c - 'A' + 10);
            else fail("bad hex digit in \\u escape");
            v = (v << 4) | d;
        }
        return v;
    }

    static void appendUtf8(std::string& out, unsigned int cp) {
        if (cp < 0x80) out += (char)cp;
        else if (cp < 0x800) { out += (char)(0xC0 | (cp >> 6)); out += (char)(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += (char)(0xE0 | (cp >> 12)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
        else { out += (char)(0xF0 | (cp >> 18)); out += (char)(0x80 | ((cp >> 12) & 0x3F)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
    }

    ValuePtr parseNumber() {
        size_t start = i_;
        if (peek() == '-') i_++;
        if (i_ >= s_.size()) fail("truncated number");
        // 整数部分
        if (peek() == '0') {
            i_++;
            if (i_ < s_.size() && s_[i_] >= '0' && s_[i_] <= '9') {
                // RFC8259 禁止前导零；但很多实现会输出 0.5 之类，这里 00 / 01 才非法
                fail("leading zero in number");
            }
        } else if (peek() >= '1' && peek() <= '9') {
            while (i_ < s_.size() && s_[i_] >= '0' && s_[i_] <= '9') i_++;
        } else {
            fail("invalid number");
        }
        bool isInt = true;
        if (i_ < s_.size() && s_[i_] == '.') {
            isInt = false;
            i_++;
            if (i_ >= s_.size() || s_[i_] < '0' || s_[i_] > '9') fail("digit expected after '.'");
            while (i_ < s_.size() && s_[i_] >= '0' && s_[i_] <= '9') i_++;
        }
        if (i_ < s_.size() && (s_[i_] == 'e' || s_[i_] == 'E')) {
            isInt = false;
            i_++;
            if (i_ < s_.size() && (s_[i_] == '+' || s_[i_] == '-')) i_++;
            if (i_ >= s_.size() || s_[i_] < '0' || s_[i_] > '9') fail("digit expected in exponent");
            while (i_ < s_.size() && s_[i_] >= '0' && s_[i_] <= '9') i_++;
        }
        auto v = std::make_shared<Value>();
        v->type = Type::Num;
        v->raw = s_.substr(start, i_ - start);
        v->num = std::strtod(v->raw.c_str(), nullptr);
        v->numIsInt = isInt;
        return v;
    }
};

inline ValuePtr parse(const std::string& line) {
    Parser p(line);
    return p.parseWhole();
}

// ---------------------------------------------------------------- 小数位检测
// 返回数字原始文本中的小数位数（不含科学计数法展开；含 'e' 时返回 -1）
inline int decimalPlaces(const std::string& raw) {
    if (raw.find('e') != std::string::npos || raw.find('E') != std::string::npos) return -1;
    size_t dot = raw.find('.');
    if (dot == std::string::npos) return 0;
    return (int)(raw.size() - dot - 1);
}

// 判断 raw 是否使用了科学计数法
inline bool isScientific(const std::string& raw) {
    return raw.find('e') != std::string::npos || raw.find('E') != std::string::npos;
}

// 相对误差比较
inline bool approxEq(double a, double b, double eps) {
    double d = std::fabs(a - b);
    if (d <= eps) return true;
    double scale = std::max(std::fabs(a), std::fabs(b));
    return d <= eps * std::max(1.0, scale);
}

// 把值序列化成紧凑文本（用于失败信息里展示实际拿到的东西）
inline std::string toText(const ValuePtr& v) {
    if (!v) return "<missing>";
    switch (v->type) {
        case Type::Null: return "null";
        case Type::Bool: return v->b ? "true" : "false";
        case Type::Num:  return v->raw;
        case Type::Str:  return "\"" + v->str + "\"";
        case Type::Arr: {
            std::string o = "[";
            for (size_t k = 0; k < v->arr.size(); k++) { if (k) o += ","; o += toText(v->arr[k]); }
            return o + "]";
        }
        case Type::Obj: {
            std::string o = "{";
            for (size_t k = 0; k < v->keys.size(); k++) {
                if (k) o += ",";
                o += "\"" + v->keys[k] + "\":" + toText(v->get(v->keys[k]));
            }
            return o + "}";
        }
    }
    return "?";
}

// 收集对象的所有键（递归第一层）用于"多字段/少字段"比对
inline std::vector<std::string> keySet(const ValuePtr& obj) {
    std::vector<std::string> r;
    if (obj && obj->isObj()) for (auto& k : obj->keys) r.push_back(k);
    return r;
}

} // namespace qaj
