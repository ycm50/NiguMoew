// jsonutil.hpp - Json 取值助手 + 统一错误码构造
#pragma once

#include "json.hpp"

#include <string>
#include <cmath>

namespace tsim {

// ---------------- 错误码（PROTOCOL.md 第 5 节，冻结） ----------------
namespace err {
inline const char* NO_GAME              = "NO_GAME";
inline const char* NO_SUCH_SYMBOL       = "NO_SUCH_SYMBOL";
inline const char* BAD_ARG              = "BAD_ARG";
inline const char* BAD_QTY              = "BAD_QTY";
inline const char* BAD_LOTS             = "BAD_LOTS";
inline const char* BAD_PRICE            = "BAD_PRICE";
inline const char* INSUFFICIENT_CASH    = "INSUFFICIENT_CASH";
inline const char* INSUFFICIENT_POSITION= "INSUFFICIENT_POSITION";
inline const char* INSUFFICIENT_MARGIN  = "INSUFFICIENT_MARGIN";
inline const char* T1_LOCKED            = "T1_LOCKED";
inline const char* MARKET_HALTED        = "MARKET_HALTED";
inline const char* NO_MARKET_DATA       = "NO_MARKET_DATA";
inline const char* NO_POSITION          = "NO_POSITION";
inline const char* UNKNOWN_CMD          = "UNKNOWN_CMD";
inline const char* INTERNAL             = "INTERNAL";
}  // namespace err

// 引擎内部错误对象：仅在库内传播，绝不逃逸到 main 之外
struct EngineError {
    std::string code;
    std::string message;
    EngineError() : code(err::INTERNAL), message("引擎内部错误") {}
    EngineError(const std::string& c, const std::string& m) : code(c), message(m) {}
};

inline Json errData(const std::string& code, const std::string& message) {
    Json e = Json::obj();
    e["code"] = Json(code);
    e["message"] = Json(message);
    return e;
}

// ---------------- 取值助手 ----------------
// 带默认值的字符串取值：字段缺失或类型不是字符串 -> 返回默认值
inline std::string jgetStr(const Json& o, const std::string& key, const std::string& def = std::string()) {
    const Json* p = o.find(key);
    if (!p || !p->isStr()) return def;
    return p->asStr(def);
}

inline std::string jstr(const Json& o, const std::string& key, const std::string& def = std::string()) {
    return jgetStr(o, key, def);
}

// 带默认值的数字取值：字段缺失或非数字 -> 返回默认值
inline double jgetNum(const Json& o, const std::string& key, double def = 0.0) {
    const Json* p = o.find(key);
    if (!p || !p->isNum()) return def;
    return p->asNum(def);
}

inline bool jgetBool(const Json& o, const std::string& key, bool def = false) {
    const Json* p = o.find(key);
    if (!p || !p->isBool()) return def;
    return p->asBool(def);
}

// 字段是否存在且为指定类型（用于区分 "缺失" 和 "显式提供"）
inline bool jhas(const Json& o, const std::string& key) { return o.find(key) != nullptr; }
inline bool jhasNum(const Json& o, const std::string& key) {
    const Json* p = o.find(key);
    return p && p->isNum();
}
inline bool jhasStr(const Json& o, const std::string& key) {
    const Json* p = o.find(key);
    return p && p->isStr();
}
inline bool jhasBool(const Json& o, const std::string& key) {
    const Json* p = o.find(key);
    return p && p->isBool();
}

// 取整（四舍五入），越界钳制
inline long long jgetInt(const Json& o, const std::string& key, long long def = 0) {
    const Json* p = o.find(key);
    if (!p || !p->isNum()) return def;
    double v = p->asNum(static_cast<double>(def));
    if (std::isnan(v) || std::isinf(v)) return def;
    if (v > 9.0e15) return 9000000000000000LL;
    if (v < -9.0e15) return -9000000000000000LL;
    return static_cast<long long>(std::llround(v));
}

// 字符串数组取值
inline std::vector<std::string> jgetStrArray(const Json& o, const std::string& key) {
    std::vector<std::string> out;
    const Json* p = o.find(key);
    if (!p || !p->isArr()) return out;
    for (const Json& v : p->items()) {
        if (v.isStr()) out.push_back(v.asStr());
    }
    return out;
}

}  // namespace tsim
