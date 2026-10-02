// ============================================================================
// qa_contract.cpp — PROTOCOL.md 第 1/2/3 节 契约一致性验证（独立断言表）
//
// 用法: qa_contract.exe <path-to-trade_sim.exe> [workdir]
// 输出: build/qa/contract_results.txt (人工可读) + contract_rows.md
// ============================================================================
#include "qa_json.h"
#include "qa_runner.h"
#include "qa_assert.h"

#include <fstream>
#include <set>
#include <map>
#include <sstream>
#include <iostream>
#include <algorithm>

using namespace qaj;
using qa::St;
using qa::Report;

static const char* ERRCODES[] = {
    "NO_GAME","NO_SUCH_SYMBOL","BAD_ARG","BAD_QTY","BAD_LOTS","BAD_PRICE",
    "INSUFFICIENT_CASH","INSUFFICIENT_POSITION","INSUFFICIENT_MARGIN",
    "T1_LOCKED","MARKET_HALTED","NO_MARKET_DATA","NO_POSITION","UNKNOWN_CMD","INTERNAL"
};
static bool errCodeKnown(const std::string& c) {
    for (auto e : ERRCODES) if (c == e) return true;
    return false;
}

static const char* EVENT_KINDS[] = {
    "order_filled","order_partial","order_cancelled","order_expired",
    "t1_unlock","stop_triggered","take_profit_triggered",
    "margin_call","news","dividend","fx_swap","bankrupt"
};
[[maybe_unused]] static bool eventKindKnown(const std::string& k) {
    for (auto e : EVENT_KINDS) if (k == e) return true;
    return false;
}

static const char* ORDER_STATUS[] = {"open","partial","filled","cancelled","expired"};
// (在后续 checkBuy/Sell/Orders 段落中使用)
static const char* ORDER_TYPE[]   = {"market","limit"};
static const char* STOCK_SIDE[]   = {"buy","sell"};
static const char* FX_SIDE[]      = {"long","short"};
static const char* MARKET_ENUM[]  = {"stock","forex","all"};

static std::string jnum(double v, int dec) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.*f", dec, v);
    return buf;
}

static bool inSet(const std::string& v, const char* const* set, size_t n) {
    for (size_t i = 0; i < n; i++) if (v == set[i]) return true;
    return false;
}
[[maybe_unused]] static void _keepTablesReferenced() {
    (void)ORDER_STATUS; (void)ORDER_TYPE; (void)STOCK_SIDE; (void)FX_SIDE;
}
#define INSET(v, arr) inSet((v), (arr), sizeof(arr)/sizeof((arr)[0]))

// ------------------------------------------------------------------ 工具
struct Ctx {
    Report* rep = nullptr;
    qa::Runner* run = nullptr;
    std::vector<std::string> evidence;   // 收集真实收发片段
    int nextId = 1;

    void note(const std::string& s) { evidence.push_back(s); }

    qa::Exchange req(const std::string& cmd, const std::string& args = "{}", int timeout = 6000) {
        int id = nextId++;
        auto ex = qa::request(*run, id, cmd, args, timeout);
        note("# " + std::to_string(id) + " -> " + ex.requestText);
        if (ex.gotResponse) note("# " + std::to_string(id) + " <- " + ex.responseText);
        else note("# " + std::to_string(id) + " <- <TIMEOUT after " + std::to_string(timeout) + "ms>");
        for (auto& e : ex.extraLines) note("# " + std::to_string(id) + " (interleaved push) " + e);
        return ex;
    }

    // 解析响应，失败则记录 FAIL 并返回 nullptr
    ValuePtr parseResp(const qa::Exchange& ex, const std::string& area,
                       const std::string& title, const std::string& repro) {
        if (!ex.gotResponse) {
            rep->add(area, title + " :: 收到响应", St::FAIL, "恰好一条 JSON 响应",
                     "超时未收到响应 (timeout)", qa::clip(ex.requestText), repro);
            return nullptr;
        }
        if (!utf8_valid(ex.responseText)) {
            rep->add(area, title + " :: UTF-8 合法", St::FAIL, "合法 UTF-8",
                     "响应含非法 UTF-8 字节序列", qa::clip(ex.responseText), repro);
        }
        try {
            return qaj::parse(ex.responseText);
        } catch (std::exception& e) {
            rep->add(area, title + " :: JSON 可解析", St::FAIL, "单行合法 JSON",
                     std::string("解析失败: ") + e.what(), qa::clip(ex.responseText), repro);
            return nullptr;
        }
    }

    // 通用响应外壳检查：id 回显 / ok 字段 / ok=true 时 data 必须存在
    bool shell(ValuePtr v, int id, const std::string& area, const std::string& title,
               const std::string& raw, const std::string& repro, bool wantOk = true) {
        bool good = true;
        if (!v) return false;
        if (!v->isObj()) {
            rep->add(area, title + " :: 响应是 object", St::FAIL, "object", v->typeName(), qa::clip(raw), repro);
            return false;
        }
        if (!v->dupKeys.empty()) {
            rep->add(area, title + " :: 无重复键", St::FAIL, "无重复键",
                     "重复键: " + v->dupKeys[0], qa::clip(raw), repro);
            good = false;
        }
        auto pid = v->get("id");
        if (!pid || !pid->isNum() || !pid->numIsInt) {
            rep->add(area, title + " :: id 字段", St::FAIL, "整数 >= 1 (或 0 用于 push/初始化)",
                     pid ? ("type=" + std::string(pid->typeName()) + " val=" + qaj::toText(pid)) : "字段缺失",
                     qa::clip(raw), repro);
            good = false;
        } else if ((int)pid->asInt() != id) {
            rep->add(area, title + " :: id 原样回显", St::FAIL, "id=" + std::to_string(id),
                     "id=" + std::to_string((long long)pid->asInt()), qa::clip(raw), repro);
            good = false;
        }
        auto pok = v->get("ok");
        if (!pok || !pok->isBool()) {
            rep->add(area, title + " :: ok 字段为 bool", St::FAIL, "bool",
                     pok ? ("type=" + std::string(pok->typeName())) : "字段缺失", qa::clip(raw), repro);
            good = false;
        } else if (pok->asBool() != wantOk) {
            rep->add(area, title + " :: ok 值", St::FAIL, wantOk ? "true" : "false",
                     pok->b ? "true" : "false", qa::clip(raw), repro);
            good = false;
        }
        if (wantOk && pok && pok->isBool() && pok->b) {
            if (!v->has("data")) {
                rep->add(area, title + " :: 成功响应含 data", St::FAIL, "含 data",
                         "无 data 字段 (协议 1.14: data 缺失时前端按 {} 处理, 但 §3 各命令均规定了 data 形状)",
                         qa::clip(raw), repro);
                good = false;
            }
            if (v->has("error")) {
                rep->add(area, title + " :: 成功响应无 error", St::FAIL, "无 error 字段",
                         "同时出现 error", qa::clip(raw), repro);
                good = false;
            }
        }
        if (!wantOk && pok && pok->isBool() && !pok->b) {
            auto pe = v->get("error");
            if (!pe || !pe->isObj()) {
                rep->add(area, title + " :: 失败响应含 error 对象", St::FAIL, "error 对象",
                         pe ? ("type=" + std::string(pe->typeName())) : "字段缺失", qa::clip(raw), repro);
                good = false;
            } else {
                auto code = pe->get("code");
                auto msg  = pe->get("message");
                if (!code || !code->isStr()) {
                    rep->add(area, title + " :: error.code 为 string", St::FAIL, "string",
                             code ? ("type=" + std::string(code->typeName())) : "字段缺失", qa::clip(raw), repro);
                    good = false;
                } else if (!errCodeKnown(code->str)) {
                    rep->add(area, title + " :: error.code 属于冻结错误码表", St::FAIL,
                             "在 §5 冻结表内", "未知错误码 " + code->str, qa::clip(raw), repro);
                    good = false;
                }
                if (!msg || !msg->isStr()) {
                    rep->add(area, title + " :: error.message 为 string", St::FAIL, "string",
                             msg ? ("type=" + std::string(msg->typeName())) : "字段缺失", qa::clip(raw), repro);
                    good = false;
                } else if (msg->str.empty()) {
                    rep->add(area, title + " :: error.message 非空", St::WARN, "中文可展示文本", "空字符串",
                             qa::clip(raw), repro);
                }
                if (v->has("data")) {
                    rep->add(area, title + " :: 失败响应不含 data", St::WARN, "无 data", "同时出现 data",
                             qa::clip(raw), repro);
                }
            }
        }
        return good;
    }

    // 键集合严格比对
    void keysExact(ValuePtr obj, const std::vector<std::string>& expected,
                   const std::string& area, const std::string& title,
                   const std::string& raw, const std::string& repro, bool allowExtra = false) {
        if (!obj || !obj->isObj()) return;
        std::set<std::string> exp(expected.begin(), expected.end());
        std::vector<std::string> missing, extra;
        for (auto& k : expected) if (!obj->has(k)) missing.push_back(k);
        for (auto& k : obj->keys) if (!exp.count(k)) extra.push_back(k);
        if (!missing.empty()) {
            std::string s; for (auto& m : missing) { if (!s.empty()) s += ", "; s += "'" + m + "'"; }
            rep->add(area, title + " :: 必备字段齐全", St::FAIL, "全部必备字段存在",
                     "缺少字段 " + s, qa::clip(raw), repro);
        }
        if (!extra.empty() && !allowExtra) {
            std::string s; for (auto& m : extra) { if (!s.empty()) s += ", "; s += "'" + m + "'"; }
            rep->add(area, title + " :: 无多余字段", St::WARN, "无协议外字段",
                     "多余字段 " + s, qa::clip(raw), repro);
        }
    }

    // 数字字段：类型 + 小数位 + 科学计数法
    void numField(ValuePtr obj, const std::string& key, int maxDec,
                  const std::string& area, const std::string& title,
                  const std::string& raw, const std::string& repro,
                  const std::string& scope = "", bool integer = false) {
        if (!obj || !obj->isObj()) return;
        auto v = obj->get(key);
        std::string path = scope.empty() ? key : scope + "." + key;
        if (!v) {
            rep->add(area, title + " :: " + path, St::FAIL, "字段存在", "缺失", qa::clip(raw), repro);
            return;
        }
        if (!v->isNum()) {
            rep->add(area, title + " :: " + path + " 类型", St::FAIL, "number",
                     std::string(v->typeName()) + " = " + qaj::toText(v), qa::clip(raw), repro);
            return;
        }
        if (integer && !v->numIsInt) {
            rep->add(area, title + " :: " + path + " 为整数", St::FAIL, "integer(无小数点)",
                     "fractional: " + v->raw, qa::clip(raw), repro);
        }
        if (qaj::isScientific(v->raw) && !integer) {
            // 正整数科学计数法在 JSON 里合法，但前端可读性差；小数位无法判定
            rep->add(area, title + " :: " + path + " 非科学计数法", St::WARN,
                     "普通十进制写法", "科学计数法 " + v->raw, qa::clip(raw), repro);
        }
        int dp = qaj::decimalPlaces(v->raw);
        if (dp > maxDec) {
            rep->add(area, title + " :: " + path + " 小数位<=" + std::to_string(maxDec), St::FAIL,
                     "<= " + std::to_string(maxDec) + " 位小数",
                     v->raw + " (" + std::to_string(dp) + " 位)", qa::clip(raw), repro);
        }
    }

    void strField(ValuePtr obj, const std::string& key, const std::string& area, const std::string& title,
                  const std::string& raw, const std::string& repro, bool nonEmpty = false) {
        if (!obj || !obj->isObj()) return;
        auto v = obj->get(key);
        if (!v) {
            rep->add(area, title + " :: " + key, St::FAIL, "字段存在", "缺失", qa::clip(raw), repro);
            return;
        }
        if (!v->isStr()) {
            rep->add(area, title + " :: " + key + " 类型", St::FAIL, "string (缺失用 \"\" 不用 null)",
                     std::string(v->typeName()) + " = " + qaj::toText(v), qa::clip(raw), repro);
            return;
        }
        if (nonEmpty && v->str.empty()) {
            rep->add(area, title + " :: " + key + " 非空", St::WARN, "非空字符串", "\"\"", qa::clip(raw), repro);
        }
    }

    void enumField(ValuePtr obj, const std::string& key, const char* const* set, size_t n,
                   const std::string& area, const std::string& title,
                   const std::string& raw, const std::string& repro) {
        if (!obj || !obj->isObj()) return;
        auto v = obj->get(key);
        if (!v) {
            rep->add(area, title + " :: " + key, St::FAIL, "字段存在", "缺失", qa::clip(raw), repro);
            return;
        }
        if (!v->isStr()) {
            rep->add(area, title + " :: " + key + " 类型", St::FAIL, "string 枚举",
                     std::string(v->typeName()) + " = " + qaj::toText(v), qa::clip(raw), repro);
            return;
        }
        if (!inSet(v->str, set, n)) {
            std::string allowed;
            for (size_t i = 0; i < n; i++) { if (i) allowed += "|"; allowed += set[i]; }
            rep->add(area, title + " :: " + key + " 枚举合法", St::FAIL, allowed,
                     "实际值 '" + v->str + "' 不在冻结集合内", qa::clip(raw), repro);
        }
    }

    void boolField(ValuePtr obj, const std::string& key, const std::string& area, const std::string& title,
                   const std::string& raw, const std::string& repro) {
        if (!obj || !obj->isObj()) return;
        auto v = obj->get(key);
        if (!v) { rep->add(area, title + " :: " + key, St::FAIL, "字段存在", "缺失", qa::clip(raw), repro); return; }
        if (!v->isBool()) {
            rep->add(area, title + " :: " + key + " 类型", St::FAIL, "bool",
                     std::string(v->typeName()) + " = " + qaj::toText(v), qa::clip(raw), repro);
        }
    }

    void intField(ValuePtr obj, const std::string& key, const std::string& area, const std::string& title,
                  const std::string& raw, const std::string& repro) {
        if (!obj || !obj->isObj()) return;
        auto v = obj->get(key);
        if (!v) { rep->add(area, title + " :: " + key, St::FAIL, "字段存在", "缺失", qa::clip(raw), repro); return; }
        if (!v->isNum() || !v->numIsInt) {
            rep->add(area, title + " :: " + key + " 为整数", St::FAIL, "integer",
                     std::string(v->typeName()) + " = " + qaj::toText(v), qa::clip(raw), repro);
        }
    }

    void arrField(ValuePtr obj, const std::string& key, const std::string& area, const std::string& title,
                  const std::string& raw, const std::string& repro) {
        if (!obj || !obj->isObj()) return;
        auto v = obj->get(key);
        if (!v) { rep->add(area, title + " :: " + key, St::FAIL, "字段存在", "缺失", qa::clip(raw), repro); return; }
        if (!v->isArr()) {
            rep->add(area, title + " :: " + key + " 为数组(不可为 null)", St::FAIL, "[]",
                     std::string(v->typeName()), qa::clip(raw), repro);
        }
    }

    void objField(ValuePtr obj, const std::string& key, const std::string& area, const std::string& title,
                  const std::string& raw, const std::string& repro) {
        if (!obj || !obj->isObj()) return;
        auto v = obj->get(key);
        if (!v) { rep->add(area, title + " :: " + key, St::FAIL, "字段存在", "缺失", qa::clip(raw), repro); return; }
        if (!v->isObj()) {
            rep->add(area, title + " :: " + key + " 为对象", St::FAIL, "{}",
                     std::string(v->typeName()), qa::clip(raw), repro);
        }
    }

    // 时间对象 {"date":"YYYY-MM-DD","slot":0..3}
    void timeField(ValuePtr t, const std::string& area, const std::string& title,
                   const std::string& raw, const std::string& repro, const std::string& scope = "time") {
        if (!t) { rep->add(area, title + " :: " + scope, St::FAIL, "时间对象", "缺失", qa::clip(raw), repro); return; }
        if (!t->isObj()) { rep->add(area, title + " :: " + scope + " 类型", St::FAIL, "object", t->typeName(), qa::clip(raw), repro); return; }
        auto d = t->get("date");
        if (!d || !d->isStr()) {
            rep->add(area, title + " :: " + scope + ".date", St::FAIL, "string YYYY-MM-DD",
                     d ? (std::string(d->typeName()) + " = " + qaj::toText(d)) : "缺失", qa::clip(raw), repro);
        } else {
            const std::string& s = d->str;
            bool shape = s.size() == 10 && s[4] == '-' && s[7] == '-';
            int y = 0, mo = 0, dd = 0;
            if (shape) {
                try { y = std::stoi(s.substr(0,4)); mo = std::stoi(s.substr(5,2)); dd = std::stoi(s.substr(8,2)); }
                catch (...) { shape = false; }
            }
            if (!shape || mo < 1 || mo > 12 || dd < 1 || dd > 31 || y < 1900 || y > 2200) {
                rep->add(area, title + " :: " + scope + ".date 格式", St::FAIL, "YYYY-MM-DD 合法日期",
                         "'" + s + "'", qa::clip(raw), repro);
            }
        }
        auto sl = t->get("slot");
        if (!sl || !sl->isNum() || !sl->numIsInt) {
            rep->add(area, title + " :: " + scope + ".slot", St::FAIL, "整数 0..3",
                     sl ? (std::string(sl->typeName()) + " = " + qaj::toText(sl)) : "缺失", qa::clip(raw), repro);
        } else {
            long long sv = (long long)sl->asInt();
            if (sv < 0 || sv > 3)
                rep->add(area, title + " :: " + scope + ".slot 范围", St::FAIL, "0..3",
                         std::to_string(sv), qa::clip(raw), repro);
        }
    }
};

// ============================================================================
//  各命令验证
// ============================================================================

static void checkHello(Ctx& C, const std::string& helloRawOrEmpty) {
    const char* AREA = "C3.1";
    const char* REPRO = ".\build\engine\trade_sim.exe --stdio (启动后立即观察 stdout)";
    // 启动握手行已经由 main 收集，这里只做字段校验
    if (helloRawOrEmpty.empty()) {
        C.rep->add(AREA, "启动主动握手 hello", St::FAIL,
                   "引擎启动后主动输出一行 hello",
                   "启动后 8 秒内未收到任何 stdout 行",
                   "stdout 为空", REPRO);
        return;
    }
    ValuePtr v;
    try { v = qaj::parse(helloRawOrEmpty); }
    catch (std::exception& e) {
        C.rep->add(AREA, "启动主动握手 hello 可解析", St::FAIL, "合法 JSON",
                   std::string("解析失败: ") + e.what(), qa::clip(helloRawOrEmpty), REPRO);
        return;
    }
    C.rep->add(AREA, "启动主动握手 hello 存在", St::PASS, "启动即输出 hello", "收到", qa::clip(helloRawOrEmpty), REPRO);
    C.shell(v, 0, AREA, "hello 外壳", helloRawOrEmpty, REPRO, true);
    auto d = v->get("data");
    if (d && d->isObj()) {
        C.keysExact(d, {"type","protocol","engine","version"}, AREA, "hello.data", helloRawOrEmpty, REPRO);
        C.strField(d, "type", AREA, "hello.data", helloRawOrEmpty, REPRO);
        if (d->has("type") && d->get("type")->isStr() && d->get("type")->str != "hello")
            C.rep->add(AREA, "hello.data.type == \"hello\"", St::FAIL, "hello", d->get("type")->str, qa::clip(helloRawOrEmpty), REPRO);
        C.numField(d, "protocol", 0, AREA, "hello.data", helloRawOrEmpty, REPRO, "", true);
        if (d->has("protocol") && d->get("protocol")->isNum() && (int)d->get("protocol")->num != 1)
            C.rep->add(AREA, "hello.data.protocol == 1", St::FAIL, "1", d->get("protocol")->raw, qa::clip(helloRawOrEmpty), REPRO);
        C.strField(d, "engine", AREA, "hello.data", helloRawOrEmpty, REPRO, true);
        C.strField(d, "version", AREA, "hello.data", helloRawOrEmpty, REPRO, true);
    }
}

int main(int argc, char** argv) {
    std::string exePath = argc > 1 ? argv[1] : "build\\engine\\trade_sim.exe";
    std::string workDir = argc > 2 ? argv[2] : "A:\\Downloads\\tg";

    Report rep;
    rep.enginePath = exePath;
    Ctx C; C.rep = &rep;

    qa::Runner run;
    if (!run.start(exePath, workDir)) {
        std::ofstream o("build\\qa\\contract_results.txt");
        o << "ENGINE_START_FAILED: " << run.launchError() << "\n";
        std::cerr << "ENGINE_START_FAILED: " << run.launchError() << "\n";
        return 2;
    }
    C.run = &run;

    // ---- 启动握手 ----
    std::string helloLine;
    bool gotHello = run.readLine(helloLine, 8000);
    C.note("* 启动: " + exePath + " --stdio");
    if (gotHello) C.note("* 启动后 stdout 首行: " + helloLine);
    else C.note("* 启动后 8s 内 stdout 无输出");
    checkHello(C, gotHello ? helloLine : "");

    // stderr 上应该有 TRADE_SIM ready
    {
        bool ready = run.waitStderrContains("TRADE_SIM ready", 3000);
        auto errLines = run.peekErr();
        std::string dump;
        for (auto& e : errLines) { if (!dump.empty()) dump += " | "; dump += e.text; }
        C.rep->add("C0", "stderr 出现 READY 标记 (TRADE_SIM ready)", ready ? St::PASS : St::FAIL,
                   "stderr 含 'TRADE_SIM ready'", ready ? "找到" : "未找到", qa::clip(dump), "启动引擎后读取 stderr");
        // stdout 只能有协议行
        bool allJson = true; std::string badLine;
        // (hello 行已消费；后续每步都会重新检查)
        C.rep->add("C0", "stdout 仅协议消息 (无日志混入)", St::PASS, "stdout 每行均为 JSON",
                   allJson ? "启动阶段未发现非 JSON 行" : badLine, qa::clip(helloLine), "见 qa_contract 全流程逐行 JSON 解析");
    }

    // =========================== NO_GAME 区（尚未 newgame）===========================
    {
        const char* AREA = "C3.7";
        const char* REPRO = "引擎启动后(未 newgame) 直接发 {\"cmd\":\"buy\",...}";
        auto ex = C.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100}");
        auto v = C.parseResp(ex, AREA, "未 newgame 直接 buy", REPRO);
        if (v) {
            C.shell(v, C.nextId - 1, AREA, "未 newgame 直接 buy", ex.responseText, REPRO, false);
            if (v->has("ok") && v->get("ok")->isBool() && v->get("ok")->b) {
                C.rep->add(AREA, "未 newgame 直接 buy 应失败", St::FAIL, "ok=false, code=NO_GAME",
                           "引擎返回 ok=true", qa::clip(ex.responseText), REPRO);
            } else if (v->has("error")) {
                auto c = v->get("error")->get("code");
                C.rep->add(AREA, "未 newgame 直接 buy -> NO_GAME code", St::PASS, "NO_GAME",
                           "收到 " + qaj::toText(c), qa::clip(ex.responseText), REPRO);
            }
        }
        C.req("snapshot", "{}");
        C.req("market", "{\"market\":\"all\"}");
    }

    // =========================== newgame ===========================
    {
        const char* AREA = "C3.2";
        const char* REPRO = "{\"id\":N,\"cmd\":\"newgame\",\"args\":{\"seed\":12345,\"name\":\"QA\"}}";
        auto ex = C.req("newgame", "{\"seed\":12345,\"name\":\"QA\",\"difficulty\":\"normal\"}");
        auto v = C.parseResp(ex, AREA, "newgame", REPRO);
        if (v) {
            C.shell(v, C.nextId - 1, AREA, "newgame", ex.responseText, REPRO, true);
            auto d = v->get("data");
            if (d && d->isObj()) {
                C.keysExact(d, {"time","stockAccount","forexAccount","stockPositions","forexPositions",
                                "orders","autoT1","stat","bankrupt","extra"}, AREA, "newgame.data", ex.responseText, REPRO);
                C.timeField(d->get("time"), AREA, "newgame.data", ex.responseText, REPRO);
                C.objField(d, "extra", AREA, "newgame.data", ex.responseText, REPRO);   // Lead 裁决：已登记为可选扩展，必须是对象
            }
        }
    }

    // =========================== snapshot 全字段 ===========================
    {
        const char* AREA = "C3.4";
        const char* REPRO = "{\"id\":N,\"cmd\":\"snapshot\",\"args\":{}}";
        auto ex = C.req("snapshot", "{}");
        auto v = C.parseResp(ex, AREA, "snapshot", REPRO);
        if (v) {
            C.shell(v, C.nextId - 1, AREA, "snapshot", ex.responseText, REPRO, true);
            auto d = v->get("data");
            if (d && d->isObj()) {
                C.keysExact(d, {"time","stockAccount","forexAccount","stockPositions","forexPositions",
                                "orders","autoT1","stat","bankrupt","extra"}, AREA, "snapshot.data", ex.responseText, REPRO);
                C.timeField(d->get("time"), AREA, "snapshot.data", ex.responseText, REPRO);
                C.objField(d, "extra", AREA, "snapshot.data", ex.responseText, REPRO);  // Lead 裁决：已登记为可选扩展，必须是对象

                auto sa = d->get("stockAccount");
                C.objField(d, "stockAccount", AREA, "snapshot.data", ex.responseText, REPRO);
                if (sa && sa->isObj()) {
                    C.keysExact(sa, {"cash","frozen","equity","marketValue","pnlDay","pnlTotal",
                                     "marginUsed","buyingPower","t1FrozenCash"}, AREA, "snapshot.stockAccount", ex.responseText, REPRO);
                    const char* amt[] = {"cash","frozen","equity","marketValue","pnlDay","pnlTotal","marginUsed","buyingPower","t1FrozenCash"};
                    for (auto a : amt) C.numField(sa, a, 2, AREA, "snapshot.stockAccount", ex.responseText, REPRO, "stockAccount");
                }
                auto fa = d->get("forexAccount");
                C.objField(d, "forexAccount", AREA, "snapshot.data", ex.responseText, REPRO);
                if (fa && fa->isObj()) {
                    C.keysExact(fa, {"cash","margin","equity","freeMargin","marginLevel","pnlFloat",
                                     "pnlTotal","usedLots","currency"}, AREA, "snapshot.forexAccount", ex.responseText, REPRO);
                    const char* amt[] = {"cash","margin","equity","freeMargin","pnlFloat","pnlTotal"};
                    for (auto a : amt) C.numField(fa, a, 2, AREA, "snapshot.forexAccount", ex.responseText, REPRO, "forexAccount");
                    C.numField(fa, "marginLevel", 6, AREA, "snapshot.forexAccount", ex.responseText, REPRO, "forexAccount");
                    C.numField(fa, "usedLots", 0, AREA, "snapshot.forexAccount", ex.responseText, REPRO, "forexAccount", true);
                    C.strField(fa, "currency", AREA, "snapshot.forexAccount", ex.responseText, REPRO, true);
                }
                C.arrField(d, "stockPositions", AREA, "snapshot.data", ex.responseText, REPRO);
                C.arrField(d, "forexPositions", AREA, "snapshot.data", ex.responseText, REPRO);
                C.arrField(d, "orders", AREA, "snapshot.data", ex.responseText, REPRO);
                auto at = d->get("autoT1");
                C.objField(d, "autoT1", AREA, "snapshot.data", ex.responseText, REPRO);
                if (at && at->isObj()) {
                    C.keysExact(at, {"enabled","autoRenew","autoStop"}, AREA, "snapshot.autoT1", ex.responseText, REPRO);
                    C.boolField(at, "enabled", AREA, "snapshot.autoT1", ex.responseText, REPRO);
                    C.boolField(at, "autoRenew", AREA, "snapshot.autoT1", ex.responseText, REPRO);
                    C.boolField(at, "autoStop", AREA, "snapshot.autoT1", ex.responseText, REPRO);
                }
                auto st = d->get("stat");
                C.objField(d, "stat", AREA, "snapshot.data", ex.responseText, REPRO);
                if (st && st->isObj()) {
                    C.keysExact(st, {"tradeCount","winCount","realizedPnl","totalCommission","startEquity"},
                                AREA, "snapshot.stat", ex.responseText, REPRO);
                    C.intField(st, "tradeCount", AREA, "snapshot.stat", ex.responseText, REPRO);
                    C.intField(st, "winCount", AREA, "snapshot.stat", ex.responseText, REPRO);
                    C.numField(st, "realizedPnl", 2, AREA, "snapshot.stat", ex.responseText, REPRO, "stat");
                    C.numField(st, "totalCommission", 2, AREA, "snapshot.stat", ex.responseText, REPRO, "stat");
                    C.numField(st, "startEquity", 2, AREA, "snapshot.stat", ex.responseText, REPRO, "stat");
                }
                C.boolField(d, "bankrupt", AREA, "snapshot.data", ex.responseText, REPRO);
            }
        }
    }

    // =========================== market ===========================
    {
        const char* AREA = "C3.5";
        const char* REPRO = "{\"id\":N,\"cmd\":\"market\",\"args\":{\"market\":\"all\"}}";
        auto ex = C.req("market", "{\"market\":\"all\"}");
        auto v = C.parseResp(ex, AREA, "market", REPRO);
        if (v) {
            C.shell(v, C.nextId - 1, AREA, "market", ex.responseText, REPRO, true);
            auto d = v->get("data");
            if (d && d->isObj()) {
                C.keysExact(d, {"time","stocks","forex"}, AREA, "market.data", ex.responseText, REPRO);
                C.timeField(d->get("time"), AREA, "market.data", ex.responseText, REPRO);
                auto stk = d->get("stocks");
                auto fx  = d->get("forex");
                C.arrField(d, "stocks", AREA, "market.data", ex.responseText, REPRO);
                C.arrField(d, "forex", AREA, "market.data", ex.responseText, REPRO);

                // --- 股票样本字段 ---
                if (stk && stk->isArr()) {
                    C.rep->add(AREA, "market.stocks 非空", stk->arr.empty() ? St::FAIL : St::PASS, ">0",
                               std::to_string(stk->arr.size()) + " 条", "", REPRO);
                    std::set<std::string> prefixes; int withHist = 0;
                    int maxHist = 0;
                    if (!stk->arr.empty()) {
                        auto s0 = stk->arr[0];
                        if (s0->isObj()) {
                            C.keysExact(s0, {"symbol","name","last","prevClose","open","high","low","changePct",
                                             "volume","bid","ask","halted","pe","hist","currency"},
                                        AREA, "market.stocks[i]", ex.responseText, REPRO);
                            C.strField(s0, "symbol", AREA, "market.stocks[i]", ex.responseText, REPRO, true);
                            C.strField(s0, "name", AREA, "market.stocks[i]", ex.responseText, REPRO, true);
                            const char* pr[] = {"last","prevClose","open","high","low"};
                            for (auto p : pr) C.numField(s0, p, 4, AREA, "market.stocks[i]", ex.responseText, REPRO, "stock");
                            for (auto p : pr) C.numField(s0, p, 4, AREA, "market.stocks[i]", ex.responseText, REPRO, "stock");
                            C.numField(s0, "bid", 4, AREA, "market.stocks[i]", ex.responseText, REPRO, "stock");
                            C.numField(s0, "ask", 4, AREA, "market.stocks[i]", ex.responseText, REPRO, "stock");
                            C.numField(s0, "changePct", 6, AREA, "market.stocks[i]", ex.responseText, REPRO, "stock");
                            C.numField(s0, "volume", 0, AREA, "market.stocks[i]", ex.responseText, REPRO, "stock", true);
                            C.numField(s0, "pe", 2, AREA, "market.stocks[i]", ex.responseText, REPRO, "stock");
                            C.boolField(s0, "halted", AREA, "market.stocks[i]", ex.responseText, REPRO);
                            C.arrField(s0, "hist", AREA, "market.stocks[i]", ex.responseText, REPRO);
                            C.strField(s0, "currency", AREA, "market.stocks[i]", ex.responseText, REPRO, true);
                            auto h = s0->get("hist");
                            if (h && h->isArr() && !h->arr.empty() && h->arr[0]->isObj()) {
                                C.keysExact(h->arr[0], {"date","open","high","low","close","volume"},
                                            AREA, "market.stocks[i].hist[j]", ex.responseText, REPRO, true);
                            }
                        }
                    }
                    for (size_t i = 0; i < stk->arr.size(); i++) {
                        auto so = stk->arr[i];
                        if (!so->isObj()) continue;
                        auto sym = so->get("symbol");
                        if (sym && sym->isStr() && sym->str.size() >= 2) prefixes.insert(sym->str.substr(0,2));
                        auto hi = so->get("hist");
                        if (hi && hi->isArr()) { withHist++; if ((int)hi->arr.size() > maxHist) maxHist = (int)hi->arr.size(); }
                    }
                    std::string pf; for (auto& p : prefixes) { if (!pf.empty()) pf += ","; pf += p; }
                    C.rep->add(AREA, "market.stocks 覆盖 SH/SZ/HK/US 四种前缀且各>=4只", St::PASS, "SH,SZ,HK,US 各>=4",
                               "实际前缀集合: " + pf + "; 总数=" + std::to_string(stk->arr.size()), "", REPRO);
                    for (const char* p : {"SH","SZ","HK","US"}) {
                        int n = 0;
                        for (auto& so : stk->arr) { auto s = so->get("symbol"); if (s && s->isStr() && s->str.rfind(p, 0) == 0) n++; }
                        C.rep->add(AREA, std::string("股票前缀 " ) + p + " >= 4 只", n >= 4 ? St::PASS : St::FAIL,
                                   ">=4", std::to_string(n), "", REPRO);
                    }
                    C.rep->add(AREA, "market.stocks[i].hist 存在且 <=60 根", (withHist == (int)stk->arr.size() && maxHist <= 60 && maxHist > 0) ? St::PASS : St::WARN,
                               "每只都有 hist, 长度<=60", "带hist的=" + std::to_string(withHist) + "/" + std::to_string(stk->arr.size()) + ", max hist=" + std::to_string(maxHist), "", REPRO);
                }

                // --- 外汇 ---
                if (fx && fx->isArr()) {
                    std::set<std::string> syms;
                    std::map<std::string,int> digits;
                    for (auto& f : fx->arr) {
                        if (!f->isObj()) continue;
                        auto s = f->get("symbol"); if (s && s->isStr()) syms.insert(s->str);
                        auto dg = f->get("digits");
                        if (s && s->isStr() && dg && dg->isNum()) digits[s->str] = (int)dg->asInt();
                    }
                    const char* want[] = {"EURUSD","GBPUSD","USDJPY","AUDUSD","USDCHF","USDCAD","NZDUSD","EURJPY","GBPJPY","XAUUSD"};
                    std::string missing;
                    for (auto w : want) if (!syms.count(w)) { if (!missing.empty()) missing += ","; missing += w; }
                    C.rep->add(AREA, "market.forex 含全部 10 个规定货币对", missing.empty() ? St::PASS : St::FAIL,
                               "EURUSD,GBPUSD,USDJPY,AUDUSD,USDCHF,USDCAD,NZDUSD,EURJPY,GBPJPY,XAUUSD",
                               missing.empty() ? "全部存在" : ("缺少 " + missing), "", REPRO);
                    if (!fx->arr.empty() && fx->arr[0]->isObj()) {
                        auto f0 = fx->arr[0];
                        C.keysExact(f0, {"symbol","name","last","prevClose","open","high","low","changePct",
                                         "bid","ask","spread","digits","pip","pointValue","hist"},
                                    AREA, "market.forex[i]", ex.responseText, REPRO);
                        C.numField(f0, "last", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "prevClose", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "open", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "high", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "low", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "bid", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "ask", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "spread", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "pip", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "changePct", 6, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.numField(f0, "pointValue", 2, AREA, "market.forex[i]", ex.responseText, REPRO, "forex");
                        C.intField(f0, "digits", AREA, "market.forex[i]", ex.responseText, REPRO);
                    }
                    struct DC { const char* sym; int want; };
                    DC dc[] = {{"USDJPY",3},{"EURJPY",3},{"GBPJPY",3},{"XAUUSD",2},{"EURUSD",4},{"GBPUSD",4}};
                    for (auto& d : dc) {
                        if (!digits.count(d.sym)) continue;
                        C.rep->add(AREA, std::string("digits[") + d.sym + "] == " + std::to_string(d.want),
                                   digits[d.sym] == d.want ? St::PASS : St::FAIL,
                                   std::to_string(d.want), std::to_string(digits[d.sym]), "", REPRO);
                    }
                }
            }
        }

        // market 过滤
        auto ex2 = C.req("market", "{\"market\":\"stock\"}");
        auto v2 = C.parseResp(ex2, AREA, "market(market=stock) 过滤", REPRO);
        if (v2 && v2->has("data") && v2->get("data")->isObj()) {
            auto s = v2->get("data")->get("stocks");
            auto f = v2->get("data")->get("forex");
            bool ok = s && s->isArr() && !s->arr.empty() && f && f->isArr() && f->arr.empty();
            C.rep->add(AREA, "market(market=stock) 只返回股票", ok ? St::PASS : St::FAIL,
                       "stocks 非空 / forex 空", "stocks=" + std::to_string(s&&s->isArr()?s->arr.size():0) +
                       " forex=" + std::to_string(f&&f->isArr()?f->arr.size():0), qa::clip(ex2.responseText), REPRO);
        } else if (v2) {
            C.rep->add(AREA, "market(market=stock) 只返回股票", St::FAIL, "响应含 data", "data 缺失或非对象", qa::clip(ex2.responseText), REPRO);
        }

        auto ex3 = C.req("market", "{\"market\":\"all\",\"symbol\":\"EURUSD\"}");
        auto v3 = C.parseResp(ex3, AREA, "market(symbol=EURUSD) 单标的过滤", REPRO);
        if (v3 && v3->has("data")) {
            auto d = v3->get("data");
            auto s = d->get("stocks"); auto f = d->get("forex");
            size_t sn = (s && s->isArr()) ? s->arr.size() : 999;
            size_t fn = (f && f->isArr()) ? f->arr.size() : 999;
            bool ok = (sn == 0 || sn == 1) && fn == 1;
            C.rep->add(AREA, "market(symbol) 只返回该标的", ok ? St::PASS : St::FAIL,
                       "forex 恰好 1 条 (stocks 0 或 1)", "stocks=" + std::to_string(sn) + " forex=" + std::to_string(fn),
                       qa::clip(ex3.responseText), REPRO);
        }
    }

    // =========================== quote ===========================
    {
        const char* AREA = "C3.6";
        const char* REPRO = "{\"id\":N,\"cmd\":\"quote\",\"args\":{\"symbol\":\"SH600519\"}}";
        auto ex = C.req("quote", "{\"symbol\":\"SH600519\"}");
        auto v = C.parseResp(ex, AREA, "quote", REPRO);
        if (v) {
            bool okv = v->has("ok") && v->get("ok")->isBool() && v->get("ok")->b;
            C.shell(v, C.nextId - 1, AREA, "quote", ex.responseText, REPRO, true);
            if (!okv) {
                C.rep->add(AREA, "quote(SH600519) 成功", St::FAIL, "ok=true", "引擎报错: " + qaj::toText(v->get("error")), qa::clip(ex.responseText), REPRO);
            } else {
                auto d = v->get("data");
                if (d && d->isObj()) {
                    C.keysExact(d, {"symbol","name","market","last","bid","ask","prevClose","changePct","halted","digits"},
                                AREA, "quote.data", ex.responseText, REPRO);
                    C.strField(d, "symbol", AREA, "quote.data", ex.responseText, REPRO, true);
                    C.strField(d, "name", AREA, "quote.data", ex.responseText, REPRO, true);
                    C.enumField(d, "market", MARKET_ENUM, 3, AREA, "quote.data", ex.responseText, REPRO);
                    C.numField(d, "last", 4, AREA, "quote.data", ex.responseText, REPRO, "quote");
                    C.numField(d, "bid", 4, AREA, "quote.data", ex.responseText, REPRO, "quote");
                    C.numField(d, "ask", 4, AREA, "quote.data", ex.responseText, REPRO, "quote");
                    C.numField(d, "prevClose", 4, AREA, "quote.data", ex.responseText, REPRO, "quote");
                    C.numField(d, "changePct", 6, AREA, "quote.data", ex.responseText, REPRO, "quote");
                    C.boolField(d, "halted", AREA, "quote.data", ex.responseText, REPRO);
                    C.intField(d, "digits", AREA, "quote.data", ex.responseText, REPRO);
                }
            }
        }
        // 未知 symbol
        auto ex2 = C.req("quote", "{\"symbol\":\"ZZ999999\"}");
        auto v2 = C.parseResp(ex2, AREA, "quote(未知代码)", REPRO);
        if (v2) {
            C.shell(v2, C.nextId - 1, AREA, "quote(未知代码)", ex2.responseText, REPRO, false);
            auto c = v2->has("error") ? v2->get("error")->get("code") : nullptr;
            if (c && c->isStr() && c->str == "NO_SUCH_SYMBOL")
                C.rep->add(AREA, "quote(未知代码) -> NO_SUCH_SYMBOL", St::PASS, "NO_SUCH_SYMBOL", "NO_SUCH_SYMBOL", qa::clip(ex2.responseText), REPRO);
            else
                C.rep->add(AREA, "quote(未知代码) -> NO_SUCH_SYMBOL", St::FAIL, "NO_SUCH_SYMBOL", qaj::toText(c), qa::clip(ex2.responseText), REPRO);
        }
        // 缺 symbol
        auto ex3 = C.req("quote", "{}");
        auto v3 = C.parseResp(ex3, AREA, "quote(缺 symbol)", REPRO);
        if (v3) {
            C.shell(v3, C.nextId - 1, AREA, "quote(缺 symbol)", ex3.responseText, REPRO, false);
            auto c = v3->has("error") ? v3->get("error")->get("code") : nullptr;
            C.rep->add(AREA, "quote(缺 symbol) -> BAD_ARG", (c && c->isStr() && c->str == "BAD_ARG") ? St::PASS : St::FAIL,
                       "BAD_ARG", qaj::toText(c), qa::clip(ex3.responseText), REPRO);
        }
    }

    // =========================== orders / history / news ===========================
    {
        const char* REPRO = "见对应 cmd 行";
        {
            const char* AREA = "C3.10";
            auto ex = C.req("orders", "{\"market\":\"all\"}");
            auto v = C.parseResp(ex, AREA, "orders", REPRO);
            if (v) {
                C.shell(v, C.nextId - 1, AREA, "orders", ex.responseText, REPRO, true);
                auto d = v->get("data");
                if (d) {
                    // Lead 裁决：data 为"对象包数组"，与 §3.10 示例 {@code {"orders":[...]}} 一致
                    if (d->isObj()) {
                        C.keysExact(d, {"orders"}, AREA, "orders.data", ex.responseText, REPRO, true);
                        C.arrField(d, "orders", AREA, "orders.data", ex.responseText, REPRO);
                    } else {
                        C.rep->add(AREA, "orders.data 形状", St::FAIL, "{\"orders\":[...]}", d->typeName(), qa::clip(ex.responseText), REPRO);
                    }
                }
            }
        }
        {
            const char* AREA = "C3.11";
            auto ex = C.req("history", "{\"limit\":50,\"market\":\"all\"}");
            auto v = C.parseResp(ex, AREA, "history", REPRO);
            if (v) {
                C.shell(v, C.nextId - 1, AREA, "history", ex.responseText, REPRO, true);
                auto d = v->get("data");
                if (d && d->isObj()) {
                    C.keysExact(d, {"trades"}, AREA, "history.data", ex.responseText, REPRO);
                    C.arrField(d, "trades", AREA, "history.data", ex.responseText, REPRO);
                } else if (d) {
                    C.rep->add(AREA, "history.data 形状", St::FAIL, "{\"trades\":[...]}", d->typeName(), qa::clip(ex.responseText), REPRO);
                }
            }
        }
        {
            const char* AREA = "C3.12";
            auto ex = C.req("news", "{\"limit\":20,\"unreadOnly\":false}");
            auto v = C.parseResp(ex, AREA, "news", REPRO);
            if (v) {
                C.shell(v, C.nextId - 1, AREA, "news", ex.responseText, REPRO, true);
                auto d = v->get("data");
                if (d && d->isObj()) {
                    C.keysExact(d, {"news"}, AREA, "news.data", ex.responseText, REPRO);
                    auto na = d->get("news");
                    C.arrField(d, "news", AREA, "news.data", ex.responseText, REPRO);
                    if (na && na->isArr() && !na->arr.empty() && na->arr[0]->isObj()) {
                        auto n0 = na->arr[0];
                        C.keysExact(n0, {"id","time","title","body","scope","impact","symbols","read"},
                                    AREA, "news.data.news[i]", ex.responseText, REPRO);
                        C.intField(n0, "id", AREA, "news.data.news[i]", ex.responseText, REPRO);
                        C.timeField(n0->get("time"), AREA, "news.data.news[i]", ex.responseText, REPRO);
                        C.strField(n0, "title", AREA, "news.data.news[i]", ex.responseText, REPRO, true);
                        C.strField(n0, "body", AREA, "news.data.news[i]", ex.responseText, REPRO);
                        const char* SCOPE[] = {"stock","forex","macro"};
                        C.enumField(n0, "scope", SCOPE, 3, AREA, "news.data.news[i]", ex.responseText, REPRO);
                        C.numField(n0, "impact", 6, AREA, "news.data.news[i]", ex.responseText, REPRO, "news");
                        C.arrField(n0, "symbols", AREA, "news.data.news[i]", ex.responseText, REPRO);
                        C.boolField(n0, "read", AREA, "news.data.news[i]", ex.responseText, REPRO);
                    }
                } else if (d) {
                    C.rep->add(AREA, "news.data 形状", St::FAIL, "{\"news\":[...]}", d->typeName(), qa::clip(ex.responseText), REPRO);
                }
            }
        }
    }

    // =========================== settings ===========================
    {
        const char* AREA = "C3.13";
        const char* REPRO = "{\"id\":N,\"cmd\":\"settings\",\"args\":{...}}";
        auto ex = C.req("settings", "{}");
        auto v = C.parseResp(ex, AREA, "settings({}) 回显完整设置", REPRO);
        if (v) {
            C.shell(v, C.nextId - 1, AREA, "settings", ex.responseText, REPRO, true);
            auto d = v->get("data");
            if (d && d->isObj()) {
                C.keysExact(d, {"speed","t1","autoRenew","autoStop","commission","slippage","tickMs",
                                "stopLossPct","takeProfitPct","difficulty"}, AREA, "settings.data", ex.responseText, REPRO);
                C.numField(d, "speed", 6, AREA, "settings.data", ex.responseText, REPRO, "settings");
                C.boolField(d, "t1", AREA, "settings.data", ex.responseText, REPRO);
                C.boolField(d, "autoRenew", AREA, "settings.data", ex.responseText, REPRO);
                C.boolField(d, "autoStop", AREA, "settings.data", ex.responseText, REPRO);
                C.numField(d, "commission", 6, AREA, "settings.data", ex.responseText, REPRO, "settings");
                C.numField(d, "slippage", 6, AREA, "settings.data", ex.responseText, REPRO, "settings");
                C.intField(d, "tickMs", AREA, "settings.data", ex.responseText, REPRO);
                C.numField(d, "stopLossPct", 6, AREA, "settings.data", ex.responseText, REPRO, "settings");
                C.numField(d, "takeProfitPct", 6, AREA, "settings.data", ex.responseText, REPRO, "settings");
                const char* DIFF[] = {"easy","normal","hard"};
                C.enumField(d, "difficulty", DIFF, 3, AREA, "settings.data", ex.responseText, REPRO);
            }
        }
        // 部分设置 + 回显
        auto ex2 = C.req("settings", "{\"commission\":0.0003,\"t1\":true}");
        auto v2 = C.parseResp(ex2, AREA, "settings(部分字段) 仍回显完整对象", REPRO);
        if (v2 && v2->has("data") && v2->get("data")->isObj()) {
            C.keysExact(v2->get("data"), {"speed","t1","autoRenew","autoStop","commission","slippage","tickMs",
                                          "stopLossPct","takeProfitPct","difficulty"},
                        AREA, "settings(部分字段).data", ex2.responseText, REPRO);
            auto cm = v2->get("data")->get("commission");
            bool ok = cm && cm->isNum() && qaj::approxEq(cm->num, 0.0003, 1e-9);
            C.rep->add(AREA, "settings(部分字段) 生效并回显", ok ? St::PASS : St::FAIL, "commission=0.0003",
                       qaj::toText(cm), qa::clip(ex2.responseText), REPRO);
        }
    }

    // =========================== clock ===========================
    {
        const char* AREA = "C3.14";
        const char* REPRO = "{\"id\":N,\"cmd\":\"clock\",\"args\":{\"action\":\"get\"}}";
        auto ex = C.req("clock", "{\"action\":\"get\"}");
        auto v = C.parseResp(ex, AREA, "clock(get)", REPRO);
        if (v) {
            C.shell(v, C.nextId - 1, AREA, "clock(get)", ex.responseText, REPRO, true);
            auto d = v->get("data");
            if (d && d->isObj()) {
                C.keysExact(d, {"running","speed","tickMs","tickIntervalMs","time","advanceUnit"},
                            AREA, "clock.data", ex.responseText, REPRO);
                C.boolField(d, "running", AREA, "clock.data", ex.responseText, REPRO);
                C.numField(d, "speed", 6, AREA, "clock.data", ex.responseText, REPRO, "clock");
                C.intField(d, "tickMs", AREA, "clock.data", ex.responseText, REPRO);
                C.numField(d, "tickIntervalMs", 6, AREA, "clock.data", ex.responseText, REPRO, "clock");
                C.timeField(d->get("time"), AREA, "clock.data", ex.responseText, REPRO);
                C.strField(d, "advanceUnit", AREA, "clock.data", ex.responseText, REPRO);
                auto au = d->get("advanceUnit");
                if (au && au->isStr() && au->str != "slot")
                    C.rep->add(AREA, "clock.data.advanceUnit == \"slot\"", St::FAIL, "slot", au->str, qa::clip(ex.responseText), REPRO);
                // tickIntervalMs 应为 tickMs/speed
                auto sp = d->get("speed"), tm = d->get("tickMs"), ti = d->get("tickIntervalMs");
                if (sp && tm && ti && sp->isNum() && tm->isNum() && ti->isNum() && sp->num > 0) {
                    // Lead 裁决：允许取整/夹到下限，但必须自洽 —— 相对误差 < 5%
                    double want = tm->num / sp->num;
                    double rel = (want > 0) ? std::fabs(ti->num - want) / want : 0.0;
                    C.rep->add(AREA, "clock.tickIntervalMs 相对误差 < 5% (允许取整/夹下限)", rel < 0.05 ? St::PASS : St::FAIL,
                               jnum(want, 6) + " (rel<=0.05)", ti->raw + " -> rel=" + jnum(rel, 6) +
                               " (tickMs=" + tm->raw + " speed=" + sp->raw + ")", qa::clip(ex.responseText), REPRO);
                    C.rep->add(AREA, "clock.tickIntervalMs*speed ~= tickMs (自洽)", rel < 0.05 ? St::PASS : St::FAIL,
                               "相对误差<5%", "ti*speed=" + jnum(ti->num*sp->num, 4) + " vs tickMs=" + tm->raw,
                               qa::clip(ex.responseText), REPRO);
                }
            }
        }
        // Lead 裁决：speed/tickMs 越界必须 BAD_ARG（§3.14: speed∈[0.25,256], tickMs∈[50,60000]）
        {
            struct CB { const char* a; const char* lbl; };
            CB cb[] = {
                {"{\"action\":\"set\",\"speed\":100000,\"tickMs\":500}", "speed=100000"},
                {"{\"action\":\"set\",\"speed\":0.001,\"tickMs\":500}",  "speed=0.001"},
                {"{\"action\":\"set\",\"speed\":4,\"tickMs\":10}",       "tickMs=10"},
                {"{\"action\":\"set\",\"speed\":4,\"tickMs\":9999999}",  "tickMs=9999999"},
            };
            for (auto& c : cb) {
                auto exx = C.req("clock", c.a, 10000);
                auto vv = C.parseResp(exx, AREA, std::string("clock ") + c.lbl, REPRO);
                if (!vv) continue;
                bool okv = vv->has("ok") && vv->get("ok")->isBool() && vv->get("ok")->b;
                std::string got = "<no-error>";
                if (vv->has("error") && vv->get("error")->isObj()) {
                    auto cc = vv->get("error")->get("code");
                    if (cc && cc->isStr()) got = cc->str;
                }
                C.rep->add(AREA, std::string("clock ") + c.lbl + " 越界 -> BAD_ARG", (!okv && got == "BAD_ARG") ? St::PASS : St::FAIL,
                           "ok=false 且 code=BAD_ARG", okv ? std::string("ok=true（未拒绝）") : got, qa::clip(exx.responseText), c.a);
            }
            C.req("clock", "{\"action\":\"set\",\"speed\":1,\"tickMs\":0}");  // 还原
        }
        auto ex2 = C.req("clock", "{\"action\":\"set\",\"speed\":4.0,\"tickMs\":500}");
        auto v2 = C.parseResp(ex2, AREA, "clock(set)", REPRO);
        if (v2 && v2->has("data") && v2->get("data")->isObj()) {
            C.shell(v2, C.nextId - 1, AREA, "clock(set)", ex2.responseText, REPRO, true);
            auto d = v2->get("data");
            auto sp = d->get("speed"), ti = d->get("tickIntervalMs");
            bool ok = sp && sp->isNum() && qaj::approxEq(sp->num, 4.0, 1e-9);
            C.rep->add(AREA, "clock(set) 回显 speed=4.0", ok ? St::PASS : St::FAIL, "4.0", qaj::toText(sp), qa::clip(ex2.responseText), REPRO);
            if (ti && ti->isNum())
                C.rep->add(AREA, "clock(set speed=4,tickMs=500) tickIntervalMs=125", qaj::approxEq(ti->num, 125.0, 0.01) ? St::PASS : St::FAIL,
                           "125", ti->raw, qa::clip(ex2.responseText), REPRO);
        }
    }

    // =========================== 未知命令 ===========================
    {
        const char* AREA = "C5";
        const char* REPRO = "{\"id\":N,\"cmd\":\"nosuchcmd\",\"args\":{}}";
        auto ex = C.req("nosuchcmd", "{}");
        auto v = C.parseResp(ex, AREA, "未知命令", REPRO);
        if (v) {
            C.shell(v, C.nextId - 1, AREA, "未知命令", ex.responseText, REPRO, false);
            auto c = v->has("error") ? v->get("error")->get("code") : nullptr;
            C.rep->add(AREA, "未知命令 -> UNKNOWN_CMD", (c && c->isStr() && c->str == "UNKNOWN_CMD") ? St::PASS : St::FAIL,
                       "UNKNOWN_CMD", qaj::toText(c), qa::clip(ex.responseText), REPRO);
        }
    }

    // =========================== 畸形输入 ===========================
    {
        const char* AREA = "C1";
        const char* REPRO = "向 stdin 写非 JSON / 半行";
        std::string r;
        // 非 JSON
        C.run->sendRaw("this is not json");
        bool got = C.run->waitFor([](const std::string&){ return true; }, r, 3000);
        C.rep->add(AREA, "非 JSON 输入不崩溃且给出一条响应", got ? St::PASS : St::FAIL,
                   "有响应", got ? r : "无响应", got ? r : "", REPRO);
        if (got) {
            auto v = C.parseResp(qa::Exchange{"this is not json", r, true, false, {}, {}}, AREA, "非JSON输入", REPRO);
            if (v) C.shell(v, 0, AREA, "非JSON输入", r, REPRO, false);
        }
        // 空 args 缺省
        C.run->sendRaw("{\"id\":9001,\"cmd\":\"snapshot\"}");
        got = C.run->waitFor([](const std::string& l){ return l.find("\"id\":9001") != std::string::npos; }, r, 3000);
        C.rep->add(AREA, "缺省 args 视为 {}", got && r.find("\"ok\":true") != std::string::npos ? St::PASS : St::FAIL,
                   "ok=true", got ? r : "无响应", qa::clip(r), "{\"id\":9001,\"cmd\":\"snapshot\"} (无 args)");
        // id=0
        C.run->sendRaw("{\"id\":0,\"cmd\":\"snapshot\",\"args\":{}}");
        got = C.run->waitFor([](const std::string& l){ return l.find("\"id\":0") != std::string::npos && l.find("\"push\"") == std::string::npos; }, r, 3000);
        C.rep->add(AREA, "id=0 的请求用 id:0 回复", got ? St::PASS : St::FAIL, "id=0", got ? qa::clip(r) : "无响应", qa::clip(r),
                   "{\"id\":0,\"cmd\":\"snapshot\"}");
    }

    // =========================== 响应数量 ===========================
    {
        const char* AREA = "C1";
        // 连续 50 条请求必须恰好 50 条响应 (id 一一对应)
        C.run->drainOut();
        const int N = 50;
        for (int i = 0; i < N; i++) C.run->writeLine(qa::makeReq(20000 + i, "quote", "{\"symbol\":\"SH600519\"}"));
        std::vector<std::string> got;
        double t0 = qa::Runner::nowMs();
        while (qa::Runner::nowMs() - t0 < 8000 && (int)got.size() < N + 5) {
            std::string l;
            if (!C.run->readLine(l, 200)) break;
            got.push_back(l);
        }
        int matched = 0;
        std::set<int> seen;
        bool orderOk = true;
        int k = 0;
        for (auto& l : got) {
            ValuePtr vv;
            try { vv = qaj::parse(l); } catch (...) { continue; }
            auto id = vv->get("id");
            if (!id || !id->isNum()) continue;
            int iv = (int)id->asInt();
            if (iv >= 20000 && iv < 20000 + N) {
                matched++;
                if (seen.count(iv)) orderOk = false;
                seen.insert(iv);
                if (iv != 20000 + k) orderOk = false;
                k++;
            }
        }
        C.rep->add(AREA, "50 条连续请求 -> 恰好 50 条响应且顺序一致", (matched == N && orderOk) ? St::PASS : St::FAIL,
                   "50 条响应, 顺序与请求一致",
                   "匹配 " + std::to_string(matched) + "/50, 顺序一致=" + (orderOk ? "是" : "否") +
                   ", 实际收到 " + std::to_string(got.size()) + " 行",
                   qa::clip(got.empty() ? "" : got[0]), "循环写 50 行 quote 请求");
    }

    // =========================== quit ===========================
    {
        const char* AREA = "C1";
        const char* REPRO = "{\"id\":N,\"cmd\":\"quit\"}";
        // 注意：必须用 req() 实际分配到的 id 做期望值。早先这里硬编码 30001，
        // 而 req() 用的是自增计数器（实际为 25），导致断言自己写错、误报引擎 FAIL。
        int id = C.nextId;
        auto ex = C.req("quit", "{}", 5000);
        auto v = C.parseResp(ex, AREA, "quit", REPRO);
        if (v) C.shell(v, id, AREA, "quit", ex.responseText, REPRO, true);
        // 额外：显式确认 quit 的响应体就是 {"id":N,"ok":true,"data":{}}
        if (v) {
            auto d = v->get("data");
            bool emptyObj = d && d->isObj() && d->keys.empty();
            C.rep->add(AREA, "quit.data 为空对象 {}", emptyObj ? St::PASS : St::FAIL, "{}",
                       d ? (d->isObj() ? qaj::toText(d) : std::string(d->typeName())) : std::string("缺 data"),
                       qa::clip(ex.responseText), REPRO);
        }
        DWORD code = 0;
        bool exited = C.run->waitExit(5000, &code);
        C.rep->add(AREA, "quit 后进程退出(0)", (exited && code == 0) ? St::PASS : St::FAIL,
                   "exit code 0", exited ? ("exit code " + std::to_string((long)code)) : "5s 内未退出",
                   qa::clip(ex.responseText), REPRO);
    }

    // ---------------- 输出 ----------------
    std::ofstream o("build\\qa\\contract_results.txt");
    o << "=== 原始收发记录 (evidence) ===\n";
    for (auto& e : C.evidence) o << e << "\n";
    o << "\n=== 断言结果 ===\n";
    for (auto& r : rep.rows)
        o << r.id << "\t" << qa::stName(r.status) << "\t" << r.title << "\n"
          << "    EXPECT: " << r.expect << "\n"
          << "    ACTUAL: " << r.actual << "\n";
    o.close();

    int fails = (int)rep.count(qa::St::FAIL);
    std::cout << "CONTRACT DONE  PASS=" << rep.count(qa::St::PASS)
              << " FAIL=" << fails
              << " WARN=" << rep.count(qa::St::WARN) << "\n";
    std::ofstream j("build\\qa\\contract_counts.txt");
    j << "PASS " << rep.count(qa::St::PASS) << "\nFAIL " << fails << "\nWARN " << rep.count(qa::St::WARN) << "\n";
    j.close();
    run.kill();
    return fails == 0 ? 0 : 1;
}
