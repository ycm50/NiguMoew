// util.hpp - 时间 / 格式化 / 四舍五入 / 随机数
#pragma once

#include "json.hpp"

#include <string>
#include <cmath>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <random>
#include <algorithm>
#include <vector>

namespace tsim {


// ---------------- 时间 ----------------
// 1 个时间片 = 1 个 slot；4 slot = 1 个交易日；起始日期 2024-01-02（周二）。
struct GameTime {
    long long day = 0;  // 从起始日算起的第几个交易日，0 起
    int slot = 0;       // 0..3

    Json toJson() const {
        Json j = Json::obj();
        j["date"] = Json(dateStr());
        j["slot"] = Json(slot);
        return j;
    }

    std::string dateStr() const { return dateFromIndex(day); }
    bool isSettleSlot() const { return slot == 3; }

    long long absSlot() const { return day * 4 + slot; }
    void setAbs(long long a) {
        if (a < 0) a = 0;
        day = a / 4;
        slot = static_cast<int>(a % 4);
    }

    static bool isLeap(int y) { return (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0); }
    static int daysInMonth(int y, int m) {
        static const int md[12] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if (m == 2 && isLeap(y)) return 29;
        return md[m - 1];
    }

    // 从 2024-01-02（周二）起算第 idx 个交易日（跳过周六周日）的日期
    static std::string dateFromIndex(long long idx) {
        if (idx < 0) idx = 0;
        int year = 2024, month = 1, day = 2;
        int dow = 2;  // 2024-01-02 是周二：0=周日,1=周一,...,6=周六
        long long left = idx;
        while (left > 0) {
            int dim = daysInMonth(year, month);
            if (day < dim) {
                ++day;
            } else {
                day = 1;
                if (++month > 12) { month = 1; ++year; }
            }
            dow = (dow + 1) % 7;
            if (dow == 6 || dow == 0) continue;  // 周六/周日不是交易日
            --left;
        }
        char buf[32];
        std::snprintf(buf, sizeof(buf), "%04d-%02d-%02d", year, month, day);
        return std::string(buf);
    }
};

// ---------------- 四舍五入（协议小数位） ----------------
inline double round4(double v) { return Json::roundTo(v, 4); }   // 价格
inline double round2(double v) { return Json::roundTo(v, 2); }   // 金额
inline double round6(double v) { return Json::roundTo(v, 6); }   // 比率

// ---------------- 数字/字符串格式化 ----------------
inline std::string fmtNum(double v) { return Json::fmtNum(v); }

// 千分位金额："1,000,000.00"
inline std::string fmtMoney(double v) {
    double r = round2(v);
    bool neg = r < 0;
    if (neg) r = -r;
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.2f", r);
    std::string s(buf);
    size_t dot = s.find('.');
    std::string ip = (dot == std::string::npos) ? s : s.substr(0, dot);
    std::string fp = (dot == std::string::npos) ? std::string("00") : s.substr(dot + 1);
    std::string grouped;
    int cnt = 0;
    for (size_t i = ip.size(); i-- > 0;) {
        grouped.push_back(ip[i]);
        if (++cnt == 3 && i != 0) { grouped.push_back(','); cnt = 0; }
    }
    std::reverse(grouped.begin(), grouped.end());
    return (neg ? std::string("-") : std::string()) + grouped + "." + fp;
}

// 定点输出到 n 位小数（不走科学计数法）
inline std::string fixedStr(double v, int n) {
    if (std::isnan(v) || std::isinf(v)) v = 0.0;
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.*f", n, v);
    return std::string(buf);
}

inline std::string toLower(std::string s) {
    for (char& c : s) if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    return s;
}
inline std::string toUpper(std::string s) {
    for (char& c : s) if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');
    return s;
}
inline bool startsWith(const std::string& s, const std::string& p) {
    return s.size() >= p.size() && s.compare(0, p.size(), p) == 0;
}

// ---------------- 随机数 ----------------
class Rng {
public:
    Rng() : seed_(20240305ULL) { eng_.seed(seed_); }
    explicit Rng(uint64_t seed) : seed_(seed) { if (seed_ == 0) seed_ = 88172645463325252ULL; eng_.seed(seed_); }

    void reseed(uint64_t seed) {
        seed_ = seed ? seed : 88172645463325252ULL;
        eng_.seed(seed_);
        normCache_ = 0.0;
        hasNormCache_ = false;
    }
    uint64_t seed() const { return seed_; }

    double uniform() { return std::uniform_real_distribution<double>(0.0, 1.0)(eng_); }
    double range(double a, double b) { return a + (b - a) * uniform(); }
    int below(int n) { return n <= 0 ? 0 : static_cast<int>(std::uniform_int_distribution<int>(0, n - 1)(eng_)); }

    // 标准正态（Box-Muller，缓存第二个样本）
    double normal() {
        if (hasNormCache_) { hasNormCache_ = false; return normCache_; }
        double u1 = uniform();
        double u2 = uniform();
        if (u1 < 1e-12) u1 = 1e-12;
        double r = std::sqrt(-2.0 * std::log(u1));
        double th = 2.0 * 3.14159265358979323846 * u2;
        normCache_ = r * std::sin(th);
        hasNormCache_ = true;
        return r * std::cos(th);
    }

private:
    uint64_t seed_;
    std::mt19937_64 eng_;
    double normCache_ = 0.0;
    bool hasNormCache_ = false;
};

inline uint64_t nowUnixMs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
}

}  // namespace tsim
