// json.hpp - 自实现的 JSON（解析 + 序列化），仅依赖 C++17 标准库。
// 规则：数字统一用 double 存储；序列化整数不输出 .0；小数用定点，绝不出科学计数法。
#pragma once

#include <string>
#include <vector>
#include <map>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <algorithm>
#include <initializer_list>
#include <utility>

namespace tsim {

class Json {
public:
    enum class Type { Null, Bool, Num, Str, Arr, Obj };

    Json() : t_(Type::Null), b_(false), n_(0.0) {}
    Json(std::nullptr_t) : t_(Type::Null), b_(false), n_(0.0) {}
    Json(bool v) : t_(Type::Bool), b_(v), n_(0.0) {}
    Json(double v) : t_(Type::Num), b_(false), n_(v) {}
    // 与 Json(double) 相同，但序列化时即使整数也保留至少一位小数（金额/价格等协议字段）
    static Json dec(double v) { Json x(v); x.forceDecimal_ = true; return x; }
    Json(int v) : t_(Type::Num), b_(false), n_(static_cast<double>(v)) {}
    Json(long long v) : t_(Type::Num), b_(false), n_(static_cast<double>(v)) {}
    Json(const char* s) : t_(Type::Str), b_(false), n_(0.0), s_(s ? s : "") {}
    Json(std::string s) : t_(Type::Str), b_(false), n_(0.0), s_(std::move(s)) {}

    static Json arr() { Json j; j.t_ = Type::Arr; return j; }
    static Json obj() { Json j; j.t_ = Type::Obj; return j; }
    static Json array(std::initializer_list<Json> xs) {
        Json j; j.t_ = Type::Arr;
        for (const Json& x : xs) j.a_.push_back(x);
        return j;
    }
    static Json object(std::initializer_list<std::pair<const char*, Json>> xs) {
        Json j; j.t_ = Type::Obj;
        for (const auto& kv : xs) j.o_.push_back({std::string(kv.first), kv.second});
        return j;
    }

    Type type() const { return t_; }
    bool isNull() const { return t_ == Type::Null; }
    bool isBool() const { return t_ == Type::Bool; }
    bool isNum() const { return t_ == Type::Num; }
    bool isStr() const { return t_ == Type::Str; }
    bool isArr() const { return t_ == Type::Arr; }
    bool isObj() const { return t_ == Type::Obj; }

    bool asBool(bool d = false) const { return t_ == Type::Bool ? b_ : d; }
    double asNum(double d = 0.0) const { return t_ == Type::Num ? n_ : d; }
    const std::string& asStr(const std::string& d = emptyStr()) const { return t_ == Type::Str ? s_ : d; }

    // ---- 数组 ----
    const std::vector<Json>& items() const { return a_; }
    std::vector<Json>& items() { return a_; }
    Json& push_back(const Json& v) { if (t_ != Type::Arr) { t_ = Type::Arr; } a_.push_back(v); return *this; }
    size_t size() const { return t_ == Type::Arr ? a_.size() : 0; }
    const Json& at(size_t i) const {
        static const Json nul;
        return (t_ == Type::Arr && i < a_.size()) ? a_[i] : nul;
    }

    // ---- 对象（保持插入顺序，序列化时按插入顺序输出，便于人读） ----
    bool has(const std::string& k) const { return find(k) != nullptr; }
    Json& operator[](const std::string& k) {
        if (t_ != Type::Obj) { t_ = Type::Obj; o_.clear(); }
        for (auto& kv : o_) if (kv.first == k) return kv.second;
        o_.push_back({k, Json()});
        return o_.back().second;
    }
    const Json& operator[](const std::string& k) const {
        const Json* p = find(k);
        static const Json nul;
        return p ? *p : nul;
    }
    const Json* find(const std::string& k) const {
        if (t_ != Type::Obj) return nullptr;
        for (const auto& kv : o_) if (kv.first == k) return &kv.second;
        return nullptr;
    }
    const std::vector<std::pair<std::string, Json>>& fields() const { return o_; }
    void eraseKey(const std::string& k) {
        if (t_ != Type::Obj) return;
        for (size_t i = 0; i < o_.size(); ++i)
            if (o_[i].first == k) { o_.erase(o_.begin() + static_cast<long>(i)); return; }
    }

    // ---- 序列化 ----
    std::string dump() const {
        std::string out;
        out.reserve(256);
        write(out);
        return out;
    }

    // ---- 数字格式化 ----
    // 规则：
    //   · 绝不输出科学计数法（不能出现 'e'/'E'）
    //   · 整数不带 .0
    //   · 一般小数按 6 位定点后裁剪尾零，消除二进制浮点噪声（如 1002418.1899999999）
    //   · |v| 极小时（< 1e-6）不能截成 0，要如实展开（如 1e-8 -> "0.00000001"）
    static std::string fmtNum(double v) {
        if (std::isnan(v) || std::isinf(v)) return "0";
        if (v == 0.0) return "0";

        // 极小值：按小数位展开到最后一个有效数字，仍不使用科学计数法
        double av = std::fabs(v);
        if (av < 1e-6) {
            char buf[64];
            // %.*f 的精度按需要的有效位推导：至少到第 12 位，足够覆盖一般极小值
            int prec = 12;
            // 用 b 位可表示范围，最多展开到 17 位有效数字
            for (int p = 7; p <= 17; ++p) {
                std::snprintf(buf, sizeof(buf), "%.*f", p, v);
                double back = std::strtod(buf, nullptr);
                if (back == v) { prec = p; break; }
                prec = p;
            }
            std::snprintf(buf, sizeof(buf), "%.*f", prec, v);
            std::string s(buf);
            if (s.find('.') != std::string::npos) {
                size_t last = s.find_last_not_of('0');
                if (last != std::string::npos && s[last] == '.') --last;
                s.erase(last + 1);
            }
            if (s.empty() || s == "-0") s = "0";
            return s;
        }

        double r = std::round(v);
        if (std::fabs(v - r) < 1e-9 && std::fabs(v) < 1e15) {
            char buf[32];
            std::snprintf(buf, sizeof(buf), "%.0f", r);
            return std::string(buf);
        }
        // 协议小数位：价格<=4、金额<=2、比率<=6。统一按 6 位定点输出再裁剪尾零。
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%.6f", v);
        std::string s(buf);
        if (s.find('.') != std::string::npos) {
            size_t last = s.find_last_not_of('0');
            if (last != std::string::npos && s[last] == '.') --last;
            s.erase(last + 1);
        }
        if (s.empty() || s == "-0") s = "0";
        return s;
    }

    // 定点序列化：整数也保留 1 位小数（1000000 -> 1000000.0）
    static std::string fmtNumDec(double v) {
        if (std::isnan(v) || std::isinf(v)) return "0.0";
        std::string s = fmtNum(v);
        if (s.find('.') == std::string::npos) s += ".0";
        return s;
    }

    // 四舍五入到 n 位小数（返回 double；序列化交给 dump/setNum）
    static double roundTo(double v, int n) {
        if (std::isnan(v) || std::isinf(v)) return 0.0;
        double p = std::pow(10.0, static_cast<double>(n));
        double x = v * p;
        if (std::fabs(x) > 4.0e17) return v;
        double r = std::round(x) / p;
        if (r == 0.0) r = 0.0;  // 消除 -0
        return r;
    }

private:
    static const std::string& emptyStr() { static const std::string e; return e; }

    Type t_;
    bool b_;
    double n_;
    bool forceDecimal_ = false;
    std::string s_;
    std::vector<Json> a_;
    std::vector<std::pair<std::string, Json>> o_;

    static void writeString(std::string& out, const std::string& s) {
        out.push_back('"');
        for (unsigned char c : s) {
            switch (c) {
                case '"':  out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n";  break;
                case '\r': out += "\\r";  break;
                case '\t': out += "\\t";  break;
                case '\b': out += "\\b";  break;
                case '\f': out += "\\f";  break;
                default:
                    if (c < 0x20) {
                        char buf[8];
                        std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(c));
                        out += buf;
                    } else {
                        out.push_back(static_cast<char>(c));
                    }
            }
        }
        out.push_back('"');
    }

    void write(std::string& out) const {
        switch (t_) {
            case Type::Null: out += "null"; break;
            case Type::Bool: out += (b_ ? "true" : "false"); break;
            case Type::Num:  out += forceDecimal_ ? fmtNumDec(n_) : fmtNum(n_); break;
            case Type::Str:  writeString(out, s_); break;
            case Type::Arr: {
                out.push_back('[');
                for (size_t i = 0; i < a_.size(); ++i) {
                    if (i) out.push_back(',');
                    a_[i].write(out);
                }
                out.push_back(']');
                break;
            }
            case Type::Obj: {
                out.push_back('{');
                bool first = true;
                for (const auto& kv : o_) {
                    if (!first) out.push_back(',');
                    first = false;
                    writeString(out, kv.first);
                    out.push_back(':');
                    kv.second.write(out);
                }
                out.push_back('}');
                break;
            }
        }
    }
};

// ============================ 解析器 ============================
class JsonParser {
public:
    JsonParser(const std::string& s) : s_(s), i_(0) {}

    // 解析整个字符串，成功返回 true；允许首尾空白，不允许尾随垃圾。
    bool parse(Json& out) {
        skipWs();
        if (!parseValue(out, 0)) return false;
        skipWs();
        return i_ >= s_.size();
    }

private:
    const std::string& s_;
    size_t i_;

    void skipWs() {
        while (i_ < s_.size()) {
            char c = s_[i_];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') ++i_;
            else break;
        }
    }

    bool parseValue(Json& out, int depth) {
        if (depth > 64) return false;
        skipWs();
        if (i_ >= s_.size()) return false;
        char c = s_[i_];
        if (c == '{') return parseObject(out, depth);
        if (c == '[') return parseArray(out, depth);
        if (c == '"') { std::string v; if (!parseString(v)) return false; out = Json(v); return true; }
        if (c == 't') { if (s_.compare(i_, 4, "true") == 0) { i_ += 4; out = Json(true); return true; } return false; }
        if (c == 'f') { if (s_.compare(i_, 5, "false") == 0) { i_ += 5; out = Json(false); return true; } return false; }
        if (c == 'n') { if (s_.compare(i_, 4, "null") == 0) { i_ += 4; out = Json(); return true; } return false; }
        return parseNumber(out);
    }

    bool parseObject(Json& out, int depth) {
        ++i_;  // '{'
        out = Json::obj();
        skipWs();
        if (i_ < s_.size() && s_[i_] == '}') { ++i_; return true; }
        while (true) {
            skipWs();
            std::string key;
            if (i_ >= s_.size() || s_[i_] != '"') return false;
            if (!parseString(key)) return false;
            skipWs();
            if (i_ >= s_.size() || s_[i_] != ':') return false;
            ++i_;
            Json v;
            if (!parseValue(v, depth + 1)) return false;
            out[key] = v;
            skipWs();
            if (i_ < s_.size() && s_[i_] == ',') { ++i_; continue; }
            if (i_ < s_.size() && s_[i_] == '}') { ++i_; return true; }
            return false;
        }
    }

    bool parseArray(Json& out, int depth) {
        ++i_;  // '['
        out = Json::arr();
        skipWs();
        if (i_ < s_.size() && s_[i_] == ']') { ++i_; return true; }
        while (true) {
            Json v;
            if (!parseValue(v, depth + 1)) return false;
            out.push_back(v);
            skipWs();
            if (i_ < s_.size() && s_[i_] == ',') { ++i_; continue; }
            if (i_ < s_.size() && s_[i_] == ']') { ++i_; return true; }
            return false;
        }
    }

    static void utf8Append(std::string& out, unsigned cp) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    bool parseHex4(unsigned& v) {
        if (i_ + 4 > s_.size()) return false;
        v = 0;
        for (int k = 0; k < 4; ++k) {
            char c = s_[i_ + static_cast<size_t>(k)];
            v <<= 4;
            if (c >= '0' && c <= '9') v |= static_cast<unsigned>(c - '0');
            else if (c >= 'a' && c <= 'f') v |= static_cast<unsigned>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') v |= static_cast<unsigned>(c - 'A' + 10);
            else return false;
        }
        i_ += 4;
        return true;
    }

    bool parseString(std::string& out) {
        if (i_ >= s_.size() || s_[i_] != '"') return false;
        ++i_;
        out.clear();
        while (i_ < s_.size()) {
            unsigned char c = static_cast<unsigned char>(s_[i_++]);
            if (c == '"') return true;
            if (c == '\\') {
                if (i_ >= s_.size()) return false;
                char e = s_[i_++];
                switch (e) {
                    case '"':  out.push_back('"');  break;
                    case '\\': out.push_back('\\'); break;
                    case '/':  out.push_back('/');  break;
                    case 'b':  out.push_back('\b'); break;
                    case 'f':  out.push_back('\f'); break;
                    case 'n':  out.push_back('\n'); break;
                    case 'r':  out.push_back('\r'); break;
                    case 't':  out.push_back('\t'); break;
                    case 'u': {
                        unsigned cp = 0;
                        if (!parseHex4(cp)) return false;
                        if (cp >= 0xD800 && cp <= 0xDBFF && i_ + 1 < s_.size() && s_[i_] == '\\' && s_[i_ + 1] == 'u') {
                            size_t save = i_;
                            i_ += 2;
                            unsigned lo = 0;
                            if (parseHex4(lo) && lo >= 0xDC00 && lo <= 0xDFFF) {
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                            } else {
                                i_ = save;
                            }
                        }
                        utf8Append(out, cp);
                        break;
                    }
                    default: return false;
                }
            } else if (c < 0x20) {
                return false;
            } else {
                out.push_back(static_cast<char>(c));
            }
        }
        return false;
    }

    bool parseNumber(Json& out) {
        size_t start = i_;
        if (i_ < s_.size() && (s_[i_] == '-' || s_[i_] == '+')) ++i_;
        size_t digits = 0;
        while (i_ < s_.size() && s_[i_] >= '0' && s_[i_] <= '9') { ++i_; ++digits; }
        if (i_ < s_.size() && s_[i_] == '.') {
            ++i_;
            while (i_ < s_.size() && s_[i_] >= '0' && s_[i_] <= '9') { ++i_; ++digits; }
        }
        if (digits == 0) return false;
        if (i_ < s_.size() && (s_[i_] == 'e' || s_[i_] == 'E')) {
            ++i_;
            if (i_ < s_.size() && (s_[i_] == '-' || s_[i_] == '+')) ++i_;
            size_t ed = 0;
            while (i_ < s_.size() && s_[i_] >= '0' && s_[i_] <= '9') { ++i_; ++ed; }
            if (ed == 0) return false;
        }
        std::string num = s_.substr(start, i_ - start);
        char* endp = nullptr;
        double v = std::strtod(num.c_str(), &endp);
        if (endp == num.c_str()) return false;
        if (std::isnan(v) || std::isinf(v)) return false;
        out = Json(v);
        return true;
    }
};

// 便捷：解析一行；失败返回 false
inline bool jsonParse(const std::string& text, Json& out) {
    JsonParser p(text);
    return p.parse(out);
}

}  // namespace tsim
