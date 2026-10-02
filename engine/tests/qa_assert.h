// ============================================================================
// qa_assert.h — QA 断言与结果收集框架（独立实现）
// 每条断言 = 一个编号 + 结论(PASS/FAIL/WARN) + 说明 + 证据片段
// ============================================================================
#pragma once
#include <string>
#include <vector>
#include <map>
#include <sstream>
#include <iostream>
#include <functional>
#include <algorithm>

namespace qa {

enum class St { PASS, FAIL, WARN, NOT_TESTED };

inline const char* stName(St s) {
    switch (s) {
        case St::PASS: return "PASS";
        case St::FAIL: return "FAIL";
        case St::WARN: return "WARN";
        case St::NOT_TESTED: return "NOT_TESTED";
    }
    return "?";
}

struct Row {
    std::string id;
    std::string area;
    std::string title;
    St status = St::PASS;
    std::string expect;
    std::string actual;
    std::string evidence;   // 真实命令/输出片段
    std::string repro;
};

class Report {
public:
    std::vector<Row> rows;
    // 运行期错误（编译/启动失败等）
    std::vector<std::string> notes;
    std::string title = "TradeTower 独立验证报告 (qa-verify)";
    std::string enginePath;
    std::string engineSha;
    std::string generatedAt;

    size_t counter = 0;

    Row& add(const std::string& area, const std::string& title, St st,
             const std::string& expect, const std::string& actual,
             const std::string& evidence = "", const std::string& repro = "",
             const std::string& forcedId = "") {
        Row r;
        if (!forcedId.empty()) { r.id = forcedId; }
        else {
            counter++;
            char buf[32];
            std::snprintf(buf, sizeof(buf), "%s-%03d", area.c_str(), (int)counter);
            r.id = buf;
        }
        r.area = area;
        r.title = title;
        r.status = st;
        r.expect = expect;
        r.actual = actual;
        r.evidence = evidence;
        r.repro = repro;
        rows.push_back(r);
        if (st == St::FAIL) std::cerr << "[FAIL] " << r.id << " " << title << " :: " << actual << "\n";
        return rows.back();
    }

    size_t count(St s) const {
        size_t n = 0;
        for (auto& r : rows) if (r.status == s) n++;
        return n;
    }

    const char* mark(bool ok) { return ok ? "PASS" : "FAIL"; }

    // 写 markdown
    void writeMarkdown(const std::string& path) const;
};

// ---------------------------------------------------------------- 辅助
inline std::string esc(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '|') o += "\\|";
        else if (c == '\n') o += "<br>";
        else if (c == '\r') {}
        else o += c;
    }
    return o;
}

// 截断长文本用于证据
inline std::string clip(const std::string& s, size_t n = 400) {
    if (s.size() <= n) return s;
    return s.substr(0, n) + "...<truncated " + std::to_string(s.size() - n) + " chars>";
}

} // namespace qa
