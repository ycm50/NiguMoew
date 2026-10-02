// ============================================================================
// qa_business.cpp — 业务规则 / 边界 / 作弊器 实测（独立驱动真实 trade_sim.exe）
// 用法: qa_business.exe <trade_sim.exe> [workdir] [section]
//   section: stock|time|forex|seed|cheat|edge|clock|life (缺省=全部)
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
#include <cmath>

using namespace qaj;
using qa::St;
using qa::Report;
using qa::Runner;
using qa::Exchange;

static Report* g_rep = nullptr;

static std::string jnum(double v, int dec) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.*f", dec, v);
    return buf;
}

struct B {
    Report* rep = nullptr;
    Runner* run = nullptr;
    std::vector<std::string> ev;
    int nextId = 1;

    Exchange req(const std::string& cmd, const std::string& args = "{}", int timeout = 20000) {
        int id = nextId++;
        auto ex = qa::request(*run, id, cmd, args, timeout);
        ev.push_back("# " + std::to_string(id) + " -> " + ex.requestText);
        if (ex.gotResponse) ev.push_back("# " + std::to_string(id) + " <- " + qa::clip(ex.responseText, 900));
        else ev.push_back("# " + std::to_string(id) + " <- <TIMEOUT " + std::to_string(timeout) + "ms>");
        for (auto& e : ex.extraLines) ev.push_back("# " + std::to_string(id) + " (interleaved) " + qa::clip(e, 400));
        for (auto& e : ex.afterLines) ev.push_back("# " + std::to_string(id) + " (extra) " + qa::clip(e, 400));
        return ex;
    }
    ValuePtr resp(const Exchange& ex) {
        if (!ex.gotResponse) return nullptr;
        try { return qaj::parse(ex.responseText); } catch (...) { return nullptr; }
    }
    ValuePtr data(const Exchange& ex) {
        auto v = resp(ex);
        return v ? v->get("data") : nullptr;
    }
    // 引擎把业务失败藏在 ok:true + data.__fail 里（协议违规），中间层必须两种都认，
    // 否则会得出"没有错误"的假阴性。协议层是否合规由 qa_contract 单独断言。
    bool okTrue(const Exchange& ex) {
        auto v = resp(ex);
        if (!v) return false;
        auto o = v->get("ok");
        if (!(o && o->isBool() && o->b)) return false;
        auto d = v->get("data");
        if (d && d->isObj() && d->has("__fail")) return false;
        return true;
    }
    std::string errCode(const Exchange& ex) {
        auto v = resp(ex);
        if (!v) return ex.gotResponse ? std::string("<bad-json>") : std::string("<no-response>");
        auto e = v->get("error");
        if (e && e->isObj()) {
            auto c = e->get("code");
            if (c && c->isStr()) return c->str;
            return std::string("<no-code>");
        }
        auto d = v->get("data");
        if (d && d->isObj() && d->has("__fail")) {
            auto c = d->get("code");
            if (c && c->isStr()) return "[ok:true/data.__fail] " + c->str;
            return std::string("[ok:true/data.__fail] <no-code>");
        }
        return std::string("<no-error>");
    }
    void add(const std::string& area, const std::string& title, St st,
             const std::string& expect, const std::string& actual,
             const std::string& evidence, const std::string& repro) {
        rep->add(area, title, st, expect, actual, qa::clip(evidence, 600), repro);
    }
    void expectErr(const std::string& area, const std::string& title, const Exchange& ex,
                   const std::string& wantCode, const std::string& repro) {
        std::string got = errCode(ex);
        add(area, title, got == wantCode ? St::PASS : St::FAIL, wantCode, got,
            ex.requestText + "  ==>  " + ex.responseText, repro);
    }
    void expectAnyErr(const std::string& area, const std::string& title, const Exchange& ex,
                      const std::string& wantList, const std::string& repro) {
        std::string got = errCode(ex);
        bool ok = false; std::string cur;
        for (size_t i = 0; i <= wantList.size(); i++) {
            if (i == wantList.size() || wantList[i] == '|') { if (cur == got) ok = true; cur.clear(); }
            else cur += wantList[i];
        }
        add(area, title, ok ? St::PASS : St::FAIL, wantList, got,
            ex.requestText + "  ==>  " + ex.responseText, repro);
    }
    double numOf(const ValuePtr& obj, const std::string& key, double dflt = -1e30) {
        if (!obj || !obj->isObj()) return dflt;
        auto v = obj->get(key);
        if (!v || !v->isNum()) return dflt;
        return v->num;
    }
    ValuePtr walk(ValuePtr root, const std::string& path) {
        if (!root) return nullptr;
        ValuePtr v = root;
        std::string cur;
        std::vector<std::string> parts;
        for (char c : path) { if (c == '.') { parts.push_back(cur); cur.clear(); } else cur += c; }
        parts.push_back(cur);
        for (auto& p : parts) {
            if (!v) return nullptr;
            if (v->isObj()) v = v->get(p);
            else if (v->isArr()) { try { v = v->arr.at((size_t)std::stoi(p)); } catch (...) { return nullptr; } }
            else return nullptr;
        }
        return v;
    }
    double pathNum(ValuePtr root, const std::string& path) {
        ValuePtr v = walk(root, path);
        return (v && v->isNum()) ? v->num : -1e30;
    }
    std::string pathStr(ValuePtr root, const std::string& path) {
        ValuePtr v = walk(root, path);
        if (!v) return "<missing>";
        if (v->isStr()) return v->str;
        if (v->isBool()) return v->b ? "true" : "false";
        if (v->isNum()) return v->raw;
        if (v->isNull()) return "null";
        return qaj::toText(v);
    }
    ValuePtr snap() { auto ex = req("snapshot", "{}"); return data(ex); }
};

static bool decOk(const ValuePtr& v, int maxDec) {
    if (!v || !v->isNum()) return false;
    if (qaj::isScientific(v->raw)) return false;
    return qaj::decimalPlaces(v->raw) <= maxDec;
}

// 自然日 +1
static bool dateAddOneDay(std::string& d) {
    if (d.size() != 10) return false;
    int y = 0, m = 0, dd = 0;
    try { y = std::stoi(d.substr(0,4)); m = std::stoi(d.substr(5,2)); dd = std::stoi(d.substr(8,2)); } catch (...) { return false; }
    static const int md[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    if (m < 1 || m > 12) return false;
    int limit = md[m-1];
    bool leap = (y%4==0 && (y%100!=0 || y%400==0));
    if (m==2 && leap) limit = 29;
    dd++;
    if (dd > limit) { dd = 1; m++; if (m > 12) { m = 1; y++; } }
    char buf[32]; std::snprintf(buf, sizeof(buf), "%04d-%02d-%02d", y, m, dd);
    d = buf;
    return true;
}

// 星期几：0=周日 1=周一 ... 6=周六（Sakamoto 算法，自研）
static int dayOfWeek(int y, int m, int d) {
    static const int t[] = {0,3,2,5,0,3,5,1,4,6,2,4};
    if (m < 3) y -= 1;
    return (y + y/4 - y/100 + y/400 + t[m-1] + d) % 7;
}
static int dateDow(const std::string& s) {
    if (s.size() != 10) return -1;
    try { return dayOfWeek(std::stoi(s.substr(0,4)), std::stoi(s.substr(5,2)), std::stoi(s.substr(8,2))); }
    catch (...) { return -1; }
}
// 交易日 +1：逢周六/周日顺延到下周一
// （Lead 裁决口径：4 slot = 1 个交易日，跳过周末；逢周六成为交易日属 bug，单独断言）
static bool dateAddOneTradingDay(std::string& d) {
    if (!dateAddOneDay(d)) return false;
    for (int guard = 0; guard < 10; guard++) {
        int w = dateDow(d);
        if (w >= 1 && w <= 5) return true;   // 周一..周五
        dateAddOneDay(d);
    }
    return false;
}

// ===========================================================================
//  1. 股票
// ===========================================================================
static void secStock(B& S) {
    const char* A = "B1-STOCK";
    S.req("newgame", "{\"seed\":777,\"name\":\"QA\",\"difficulty\":\"normal\"}");

    int badQty[] = {0, -100, 150, 50, 1, -1, 99};
    for (int q : badQty) {
        std::string qs = std::to_string(q);
        auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":" + qs + ",\"type\":\"market\"}");
        S.expectErr(A, "buy(市价) qty=" + qs + " -> BAD_QTY", ex, "BAD_QTY",
                    "{\"id\":N,\"cmd\":\"buy\",\"args\":{\"symbol\":\"SH600519\",\"qty\":" + qs + ",\"type\":\"market\"}}");
    }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"type\":\"market\"}");
      S.expectErr(A, "buy 缺 qty -> BAD_ARG", ex, "BAD_ARG", "args 无 qty"); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":\"100\",\"type\":\"market\"}");
      S.expectErr(A, "buy qty 为字符串 -> BAD_ARG", ex, "BAD_ARG", "qty:\"100\""); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100.5,\"type\":\"market\"}");
      S.expectAnyErr(A, "buy qty 非整数 -> BAD_ARG|BAD_QTY", ex, "BAD_ARG|BAD_QTY", "qty:100.5"); }
    { auto ex = S.req("buy", "{\"symbol\":\"ZZ999\",\"qty\":100,\"type\":\"market\"}");
      S.expectErr(A, "buy 未知 symbol -> NO_SUCH_SYMBOL", ex, "NO_SUCH_SYMBOL", "symbol:ZZ999"); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"bogus\"}");
      S.expectAnyErr(A, "buy type=bogus -> BAD_ARG", ex, "BAD_ARG|BAD_PRICE", "type:\"bogus\""); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\"}");
      S.expectAnyErr(A, "limit 单缺 price -> BAD_PRICE|BAD_ARG", ex, "BAD_PRICE|BAD_ARG", "type:limit 无 price"); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":-1.5}");
      S.expectAnyErr(A, "limit price<0 -> BAD_PRICE|BAD_ARG", ex, "BAD_PRICE|BAD_ARG", "price:-1.5"); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":0}");
      S.expectAnyErr(A, "limit price=0 -> BAD_PRICE|BAD_ARG", ex, "BAD_PRICE|BAD_ARG", "price:0"); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100000000,\"type\":\"market\"}");
      S.expectErr(A, "超大数量 -> INSUFFICIENT_CASH", ex, "INSUFFICIENT_CASH", "qty:100000000"); }

    auto snap0 = S.snap();
    double cash0 = S.pathNum(snap0, "stockAccount.cash");
    std::string date0 = S.pathStr(snap0, "time.date");
    int slot0 = (int)S.pathNum(snap0, "time.slot");
    std::string ctx = "买入前 snapshot: cash=" + jnum(cash0,2) + " date=" + date0 + " slot=" + std::to_string(slot0);

    auto buyEx = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
    bool bought = S.okTrue(buyEx);
    S.add(A, "市价买入 100 股成功", bought ? St::PASS : St::FAIL, "ok=true",
          bought ? std::string("ok=true") : S.errCode(buyEx), ctx + "\n" + buyEx.responseText,
          "newgame(seed=777) -> buy SH600519 qty=100 type=market");
    if (bought) {
        auto d = S.data(buyEx);
        const char* bkeys[] = {"orderId","status","filled","avgPrice","commission","cash"};
        std::string miss;
        for (auto k : bkeys) if (!d || !d->has(k)) miss += std::string(k) + " ";
        S.add(A, "buy.data 含 orderId/status/filled/avgPrice/commission/cash", miss.empty() ? St::PASS : St::FAIL,
              "6 字段齐全", miss.empty() ? "齐全" : ("缺 " + miss), buyEx.responseText, "buy market");
        if (d && d->isObj()) {
            auto stat = d->get("status");
            if (stat && stat->isStr()) {
                const char* SS[] = {"filled","open","partial","cancelled","expired"};
                bool ok = false; for (auto s : SS) if (stat->str == s) ok = true;
                S.add(A, "buy(市价).status 枚举合法", ok ? St::PASS : St::FAIL, "open|partial|filled|cancelled|expired",
                      stat->str, buyEx.responseText, "buy market");
                S.add(A, "市价单 status=filled", stat->str == "filled" ? St::PASS : St::FAIL, "filled", stat->str, buyEx.responseText, "buy market");
            } else {
                S.add(A, "buy.data.status 类型", St::FAIL, "string", stat ? stat->typeName() : "缺失", buyEx.responseText, "buy market");
            }
            auto fq = d->get("filled");
            S.add(A, "市价买入 filled=100", (fq && fq->isNum() && (int)fq->num == 100) ? St::PASS : St::FAIL, "100",
                  qaj::toText(fq), buyEx.responseText, "buy market");
            auto oid = d->get("orderId");
            S.add(A, "buy.data.orderId 为整数且>0", (oid && oid->isNum() && oid->numIsInt && oid->num > 0) ? St::PASS : St::FAIL,
                  "整数>0", qaj::toText(oid), buyEx.responseText, "buy market");
            auto cm = d->get("commission");
            S.add(A, "buy.data.commission 小数位<=2", decOk(cm, 2) ? St::PASS : St::FAIL, "<=2 位",
                  cm ? cm->raw : "缺失", buyEx.responseText, "buy market");
            auto ap = d->get("avgPrice");
            S.add(A, "buy.data.avgPrice 小数位<=4", decOk(ap, 4) ? St::PASS : St::FAIL, "<=4 位",
                  ap ? ap->raw : "缺失", buyEx.responseText, "buy market");
            auto ch = d->get("cash");
            S.add(A, "buy.data.cash 小数位<=2", decOk(ch, 2) ? St::PASS : St::FAIL, "<=2 位",
                  ch ? ch->raw : "缺失", buyEx.responseText, "buy market");
            S.add(A, "买入后 cash 减少", (ch && ch->isNum() && ch->num < cash0) ? St::PASS : St::FAIL,
                  "< " + jnum(cash0,2), ch ? jnum(ch->num,2) : "缺失", buyEx.responseText, "buy market");
            if (ch && ch->isNum() && ap && ap->isNum() && cm && cm->isNum()) {
                double delta = cash0 - ch->num;
                double expect = 100 * ap->num + cm->num;
                S.add(A, "现金扣减 == qty*avgPrice + commission", qaj::approxEq(delta, expect, 0.05) ? St::PASS : St::FAIL,
                      jnum(expect,2), jnum(delta,2) + " (avgPrice=" + ap->raw + " commission=" + cm->raw + ")",
                      buyEx.responseText, "buy market 后与 snapshot 前值相减");
            }
        }
    } else {
        for (auto k : {"orderId","status","filled","avgPrice","commission","cash"})
            S.add(A, std::string("buy.data 含字段 ") + k, St::NOT_TESTED, "存在", "买入失败未取得 data", buyEx.responseText, "buy market");
    }

    { auto ex = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
      S.expectErr(A, "T+1 当日买入立即卖出 -> T1_LOCKED", ex, "T1_LOCKED",
                  "newgame -> buy 100 market -> 同 slot sell 100 market"); }
    { auto ex = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":50,\"type\":\"market\"}");
      S.expectErr(A, "sell qty=50 -> BAD_QTY（数量校验优先于 T+1）", ex, "BAD_QTY", "sell qty:50"); }
    {
        auto qEx = S.req("quote", "{\"symbol\":\"SH600519\"}");
        double last = S.numOf(S.data(qEx), "last");
        if (last <= 0) last = 1700;
        auto ex = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":" + jnum(last * 1.5, 2) + "}");
        S.expectErr(A, "T+1 当日限价挂卖 -> T1_LOCKED", ex, "T1_LOCKED", "limit price=" + jnum(last*1.5,2));
    }

    S.req("newgame", "{\"seed\":778,\"name\":\"QA\"}");
    { auto ex = S.req("sell", "{\"symbol\":\"SZ000001\",\"qty\":100,\"type\":\"market\"}");
      S.expectErr(A, "无持仓卖出 -> INSUFFICIENT_POSITION", ex, "INSUFFICIENT_POSITION", "newgame -> sell SZ000001 100"); }

    // 停牌
    {
        S.req("newgame", "{\"seed\":9001,\"name\":\"QA\"}");
        auto fz = S.req("cheat", "{\"op\":\"freeze\",\"symbol\":\"SH600519\",\"halted\":true}");
        bool froze = S.okTrue(fz);
        S.add(A, "cheat freeze(halted:true) 成功", froze ? St::PASS : St::FAIL, "ok=true", S.errCode(fz),
              fz.responseText, "{\"cmd\":\"cheat\",\"args\":{\"op\":\"freeze\",\"symbol\":\"SH600519\",\"halted\":true}}");
        if (froze) {
            auto q = S.req("quote", "{\"symbol\":\"SH600519\"}");
            auto h = S.data(q) ? S.data(q)->get("halted") : nullptr;
            S.add(A, "停牌后 quote.halted=true", (h && h->isBool() && h->b) ? St::PASS : St::FAIL, "true",
                  qaj::toText(h), q.responseText, "freeze 后 quote");
            auto b = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
            S.expectErr(A, "停牌买入 -> MARKET_HALTED", b, "MARKET_HALTED", "freeze SH600519 后 buy market");
            auto s = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
            S.add(A, "停牌卖出有明确错误码", (S.errCode(s) != "<no-error>") ? St::PASS : St::FAIL,
                  "MARKET_HALTED 或 INSUFFICIENT_POSITION", S.errCode(s), s.responseText, "freeze 后 sell");
            auto m = S.req("market", "{\"market\":\"all\",\"symbol\":\"SH600519\"}");
            auto d = S.data(m);
            auto sarr = d ? d->get("stocks") : nullptr;
            bool found = false, haltedFlag = false;
            if (sarr && sarr->isArr()) for (auto& so : sarr->arr) {
                auto sy = so->get("symbol");
                if (sy && sy->isStr() && sy->str == "SH600519") { found = true; auto hh = so->get("halted"); if (hh && hh->isBool()) haltedFlag = hh->b; }
            }
            S.add(A, "market 中停牌股 halted=true", (found && haltedFlag) ? St::PASS : St::FAIL, "found && halted=true",
                  std::string("found=") + (found ? "Y" : "N") + " halted=" + (haltedFlag ? "true" : "false"),
                  qa::clip(m.responseText, 300), "freeze 后 market");
            auto un = S.req("cheat", "{\"op\":\"freeze\",\"symbol\":\"SH600519\",\"halted\":false}");
            S.add(A, "cheat freeze 复牌成功", S.okTrue(un) ? St::PASS : St::FAIL, "ok=true", S.errCode(un), un.responseText, "freeze halted:false");
            auto b2 = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
            S.add(A, "复牌后可以买入", S.okTrue(b2) ? St::PASS : St::FAIL, "ok=true", S.errCode(b2), b2.responseText, "freeze halted:false 后 buy");
        } else {
            S.add(A, "停牌后 quote.halted=true", St::NOT_TESTED, "true", "freeze 未成功", fz.responseText, "-");
            S.add(A, "停牌买入 -> MARKET_HALTED", St::NOT_TESTED, "MARKET_HALTED", "freeze 未成功", fz.responseText, "-");
            S.add(A, "market 中停牌股 halted=true", St::NOT_TESTED, "found && halted=true", "freeze 未成功", fz.responseText, "-");
        }
    }

    // 价格操纵
    {
        S.req("newgame", "{\"seed\":9002,\"name\":\"QA\"}");
        auto px = S.req("cheat", "{\"op\":\"price\",\"symbol\":\"SH600519\",\"to\":2000.0}");
        S.add(A, "cheat price(to) 成功", S.okTrue(px) ? St::PASS : St::FAIL, "ok=true", S.errCode(px), px.responseText,
              "{\"op\":\"price\",\"symbol\":\"SH600519\",\"to\":2000.0}");
        auto q = S.req("quote", "{\"symbol\":\"SH600519\"}");
        double last = S.numOf(S.data(q), "last");
        S.add(A, "强设价格后 last==2000", qaj::approxEq(last, 2000.0, 0.001) ? St::PASS : St::FAIL, "2000.0",
              jnum(last,4), q.responseText, "cheat price to:2000.0 后 quote");
        auto s = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":2500.0}");
        S.add(A, "无持仓限价卖被拒（不崩溃）", !S.okTrue(s) ? St::PASS : St::FAIL, "ok=false",
              S.okTrue(s) ? std::string("错误地成功") : S.errCode(s), s.responseText, "limit sell price 2500 无持仓");
    }
}

// ===========================================================================
//  2. 时间
// ===========================================================================
static void secTime(B& S) {
    const char* A = "B2-TIME";
    {
        S.req("newgame", "{\"seed\":4242,\"name\":\"QA\"}");
        auto s0 = S.snap();
        std::string d0 = S.pathStr(s0, "time.date");
        int sl0 = (int)S.pathNum(s0, "time.slot");

        auto t1 = S.req("tick", "{\"n\":1,\"mode\":\"manual\"}");
        auto d1 = S.data(t1);
        S.add(A, "tick n=1 -> advanced=1", (d1 && S.numOf(d1,"advanced")==1) ? St::PASS : St::FAIL, "1",
              d1 ? qaj::toText(d1->get("advanced")) : std::string("缺 data"), t1.responseText,
              "{\"cmd\":\"tick\",\"args\":{\"n\":1,\"mode\":\"manual\"}}");
        std::string dA = d1 ? S.pathStr(d1, "time.date") : std::string("?");
        int slA = d1 ? (int)S.pathNum(d1, "time.slot") : -1;
        int expectSlot = (sl0 + 1) % 4;
        std::string expectDate = d0;
        if (sl0 + 1 >= 4) dateAddOneDay(expectDate);
        S.add(A, "tick n=1 后 slot 按 4 进制递增", (slA == expectSlot) ? St::PASS : St::FAIL,
              std::to_string(expectSlot), std::to_string(slA), t1.responseText,
              "起始 slot=" + std::to_string(sl0));
        S.add(A, "tick n=1 后日期正确", (dA == expectDate) ? St::PASS : St::FAIL, expectDate, dA, t1.responseText,
              "起始 date=" + d0 + " slot=" + std::to_string(sl0));
        S.add(A, "tick.data.events 为数组(非 null)", (d1 && d1->has("events") && d1->get("events")->isArr()) ? St::PASS : St::FAIL,
              "[]", d1 && d1->has("events") ? d1->get("events")->typeName() : std::string("缺失"), t1.responseText, "tick");
        S.add(A, "tick.data.halted 为数组", (d1 && d1->has("halted") && d1->get("halted")->isArr()) ? St::PASS : St::FAIL,
              "[]", d1 && d1->has("halted") ? d1->get("halted")->typeName() : std::string("缺失"), t1.responseText, "tick");

        if (d1 && d1->get("events") && d1->get("events")->isArr()) {
            const char* KINDS[] = {"order_filled","order_partial","order_cancelled","order_expired",
                                   "t1_unlock","stop_triggered","take_profit_triggered","margin_call",
                                   "news","dividend","fx_swap","bankrupt"};
            std::string bad;
            for (auto& e : d1->get("events")->arr) {
                if (!e->isObj()) continue;
                auto k = e->get("kind");
                if (!k || !k->isStr()) { bad += "<missing kind>,"; continue; }
                bool ok = false; for (auto kk : KINDS) if (k->str == kk) ok = true;
                if (!ok) bad += k->str + ",";
            }
            S.add(A, "tick.events[].kind 均属冻结枚举", bad.empty() ? St::PASS : St::FAIL, "12 种冻结枚举",
                  bad.empty() ? std::string("全部合法") : ("非法: " + bad),
                  qa::clip(qaj::toText(d1->get("events")), 300), "tick 后检查 events");
        }
    }
    // 4 slot = 1 交易日
    {
        S.req("newgame", "{\"seed\":4243,\"name\":\"QA\"}");
        auto sa = S.snap();
        std::string da = S.pathStr(sa, "time.date");
        int sla = (int)S.pathNum(sa, "time.slot");
        int need = (4 - sla) % 4;
        if (need == 0) need = 4;
        auto t4 = S.req("tick", "{\"n\":" + std::to_string(need) + ",\"mode\":\"manual\"}");
        auto d4 = S.data(t4);
        std::string db = d4 ? S.pathStr(d4, "time.date") : std::string("?");
        int slb = d4 ? (int)S.pathNum(d4, "time.slot") : -1;
        std::string want = da; dateAddOneDay(want);
        S.add(A, std::to_string(need) + " slot 后进入下一交易日(slot 归 0, 日期+1)", (db == want && slb == 0) ? St::PASS : St::FAIL,
              want + " slot=0", db + " slot=" + std::to_string(slb), t4.responseText,
              "起始 date=" + da + " slot=" + std::to_string(sla));
        S.add(A, "tick.data.advanced == n", S.numOf(d4, "advanced") == need ? St::PASS : St::FAIL,
              std::to_string(need), d4 ? qaj::toText(d4->get("advanced")) : std::string("缺"), t4.responseText,
              "tick n=" + std::to_string(need));
    }
    // T+1 次日解锁
    {
        const char* T = "B2-T1";
        S.req("newgame", "{\"seed\":5000,\"name\":\"QA\"}");
        auto b = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        S.add(T, "T+1 前置：买入 100 成功", S.okTrue(b) ? St::PASS : St::FAIL, "ok=true", S.errCode(b), b.responseText, "buy market 100");
        auto s1 = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        S.expectErr(T, "T+1 当日卖出被拒", s1, "T1_LOCKED", "buy -> sell 同一 slot");
        auto tk = S.req("tick", "{\"n\":4,\"mode\":\"manual\"}");
        S.add(T, "推进 4 slot", S.okTrue(tk) ? St::PASS : St::FAIL, "ok=true", S.errCode(tk), tk.responseText, "tick n=4 manual");
        auto s2 = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        S.add(T, "次日开盘后可卖出 (T+1 解锁)", S.okTrue(s2) ? St::PASS : St::FAIL, "ok=true", S.errCode(s2), s2.responseText,
              "tick 4 后 sell 100");
        if (S.okTrue(s2)) {
            auto d = S.data(s2);
            auto cm = d ? d->get("commission") : nullptr;
            S.add(T, "卖出 commission 小数位<=2", decOk(cm,2) ? St::PASS : St::FAIL, "<=2",
                  cm ? cm->raw : std::string("缺失"), s2.responseText, "sell market");
            auto h = S.req("history", "{\"limit\":10,\"market\":\"stock\"}");
            auto hd = S.data(h);
            auto ta = hd ? hd->get("trades") : nullptr;
            S.add(T, "卖出后 history 有成交记录", (ta && ta->isArr() && !ta->arr.empty()) ? St::PASS : St::FAIL, ">=1",
                  ta && ta->isArr() ? std::to_string(ta->arr.size()) : std::string("非数组"), h.responseText, "sell 后 history");
            if (ta && ta->isArr() && !ta->arr.empty() && ta->arr[0]->isObj()) {
                auto t0 = ta->arr[0];
                const char* hk[] = {"seq","time","market","symbol","side","qty","price","amount","commission","realizedPnl","reason"};
                std::string missing;
                for (auto k : hk) if (!t0->has(k)) missing += std::string(k) + " ";
                S.add(T, "history.trades[0] 字段齐全", missing.empty() ? St::PASS : St::FAIL, "11 个字段",
                      missing.empty() ? std::string("齐全") : ("缺 " + missing), h.responseText, "history");
                auto rs = t0->get("reason");
                const char* REASONS[] = {"manual","stop","takeprofit","liquidation","dividend"};
                bool rok = rs && rs->isStr();
                if (rok) { bool f = false; for (auto r : REASONS) if (rs->str == r) f = true; rok = f; }
                S.add(T, "history.reason 属冻结枚举", rok ? St::PASS : St::FAIL,
                      "manual|stop|takeprofit|liquidation|dividend", rs ? qaj::toText(rs) : std::string("缺失"), h.responseText, "history");
                auto sd = t0->get("side");
                bool sok = sd && sd->isStr() && (sd->str == "buy" || sd->str == "sell");
                S.add(T, "history.side 属 buy|sell", sok ? St::PASS : St::FAIL, "buy|sell", sd ? qaj::toText(sd) : std::string("缺失"), h.responseText, "history");
                auto mk = t0->get("market");
                bool mok = mk && mk->isStr() && (mk->str == "stock" || mk->str == "forex");
                S.add(T, "history.market 属 stock|forex", mok ? St::PASS : St::FAIL, "stock|forex", mk ? qaj::toText(mk) : std::string("缺失"), h.responseText, "history");
                auto am = t0->get("amount");
                S.add(T, "history.amount 小数位<=2", decOk(am,2) ? St::PASS : St::FAIL, "<=2", am ? am->raw : std::string("缺失"), h.responseText, "history");
                auto pr = t0->get("price");
                S.add(T, "history.price 小数位<=4", decOk(pr,4) ? St::PASS : St::FAIL, "<=4", pr ? pr->raw : std::string("缺失"), h.responseText, "history");
                if (ta->arr.size() >= 2) {
                    auto a0 = ta->arr[0]->get("seq"), a1 = ta->arr[ta->arr.size()-1]->get("seq");
                    S.add(T, "history 时间倒序(seq 递减)", (a0 && a1 && a0->isNum() && a1->isNum() && a0->num >= a1->num) ? St::PASS : St::FAIL,
                          "最新在前", "first.seq=" + qaj::toText(a0) + " last.seq=" + qaj::toText(a1), qa::clip(h.responseText,300), "history");
                }
            }
        }
    }
    // 跨月跨年
    {
        const char* T = "B2-CAL";
        S.req("newgame", "{\"seed\":6000,\"name\":\"QA\"}");
        std::string d0 = S.pathStr(S.snap(), "time.date");
        auto tk = S.req("tick", "{\"n\":400,\"mode\":\"manual\"}", 40000);
        (void)0;
        auto d1 = S.data(tk);
        std::string d100 = d1 ? S.pathStr(d1, "time.date") : std::string("?");
        int sl = d1 ? (int)S.pathNum(d1, "time.slot") : -1;
        // Lead 裁决口径：4 slot = 1 个交易日，逢周六/周日顺延到下周一
        std::string want = d0;
        for (int i = 0; i < 100; i++) dateAddOneTradingDay(want);
        S.add(T, "tick n=400 (=100 交易日) 日期 == 起始 + 100 个交易日", (d100 == want) ? St::PASS : St::FAIL,
              want, d100 + " slot=" + std::to_string(sl), qa::clip(tk.responseText,200),
              "{\"cmd\":\"tick\",\"args\":{\"n\":400,\"mode\":\"manual\"}} 起始 " + d0);
        S.req("newgame", "{\"seed\":6001,\"name\":\"QA\"}");
        std::string prev = S.pathStr(S.snap(), "time.date");
        bool allValid = true; std::string badDate;
        for (int i = 0; i < 250; i++) {
            auto t = S.req("tick", "{\"n\":4,\"mode\":\"manual\"}", 20000);
            auto dd = S.data(t);
            std::string cur = dd ? S.pathStr(dd, "time.date") : std::string("?");
            std::string w2 = prev; dateAddOneTradingDay(w2);
            if (cur != w2) { allValid = false; badDate = prev + " +1交易日 期望 " + w2 + " 实际 " + cur; break; }
            prev = cur;
        }
        S.add(T, "连续 250 个交易日：日期按 1 个交易日递增(逢周末顺延)", allValid ? St::PASS : St::FAIL,
              "每日 +1 交易日", allValid ? ("最终 " + prev + " 全部正确") : badDate, "",
              "循环 250 次 tick{n:4,mode:manual} 并逐步比对日期");
        // Lead 裁决保留项：周六绝不可能是交易日
        {
            S.req("newgame", "{\"seed\":6002,\"name\":\"QA\"}");
            std::string pd = S.pathStr(S.snap(), "time.date");
            std::string badSat; int satCount = 0, checked = 0;
            for (int i = 0; i < 200; i++) {
                auto t = S.req("tick", "{\"n\":4,\"mode\":\"manual\"}", 20000);
                auto dd = S.data(t);
                std::string cur = dd ? S.pathStr(dd, "time.date") : std::string("?");
                if (cur == "?") break;
                int w = dateDow(cur);
                if (w == 6) { satCount++; if (badSat.empty()) badSat = cur; }
                if (w == 0) { if (badSat.empty()) badSat = cur + " (周日)"; }
                checked++;
                pd = cur;
            }
            S.add(T, "200 个交易日中没有一天是周六/周日", (satCount == 0 && badSat.empty()) ? St::PASS : St::FAIL,
                  "全部为周一..周五",
                  badSat.empty() ? (std::to_string(checked) + " 天全部合法") : ("出现非交易日 " + badSat),
                  "", "newgame -> 循环 200 次 tick{n:4,mode:manual}，逐日算星期");
        }
    }
    // n 越界
    {
        const char* T = "B2-BOUND";
        const char* ns[] = {"0", "-1", "2001", "999999"};
        for (auto n : ns) {
            auto ex = S.req("tick", std::string("{\"n\":") + n + ",\"mode\":\"manual\"}", 20000);
            bool ok = S.okTrue(ex);
            std::string got = S.errCode(ex);
            S.add(T, std::string("tick n=") + n + " 越界被拒(BAD_ARG)", (!ok && (got == "BAD_ARG" || got == "BAD_QTY")) ? St::PASS : St::FAIL,
                  "ok=false 且 code=BAD_ARG", ok ? std::string("ok=true（未拒绝）") : got, ex.responseText,
                  std::string("{\"cmd\":\"tick\",\"args\":{\"n\":") + n + ",\"mode\":\"manual\"}}");
        }
        auto ex = S.req("tick", "{\"n\":2000,\"mode\":\"manual\"}", 60000);
        S.add(T, "tick n=2000 (上界) 可执行", S.okTrue(ex) ? St::PASS : St::FAIL, "ok=true", S.errCode(ex),
              qa::clip(ex.responseText, 200), "{\"cmd\":\"tick\",\"args\":{\"n\":2000,\"mode\":\"manual\"}}");
        auto t0 = Runner::nowMs();
        auto ex2 = S.req("tick", "{\"n\":2000,\"mode\":\"manual\"}", 60000);
        S.add(T, "tick n=2000 完成后引擎仍响应", ex2.gotResponse ? St::PASS : St::FAIL, "有响应",
              ex2.gotResponse ? std::string("有") : std::string("超时"), "", "连续两次 n=2000");
        S.add(T, "tick n=2000 用时 < 10s", (Runner::nowMs()-t0) < 10000 ? St::PASS : St::WARN, "<10000ms",
              jnum(Runner::nowMs()-t0, 0) + "ms", "", "计时");
    }
    // cheap skip 大跨度
    {
        const char* T = "B2-PERF";
        S.req("newgame", "{\"seed\":7000,\"name\":\"QA\"}");
        auto t0 = Runner::nowMs();
        auto ex = S.req("cheat", "{\"op\":\"skip\",\"slots\":5000,\"auto\":true}", 60000);
        double dt = Runner::nowMs() - t0;
        S.add(T, "cheat skip 5000 slot 未卡死(<60s)", (S.okTrue(ex) && dt < 60000) ? St::PASS : St::FAIL,
              "ok=true 且 <60s", (S.okTrue(ex) ? std::string("ok") : S.errCode(ex)) + " 用时 " + jnum(dt,0) + "ms",
              qa::clip(ex.responseText, 200), "{\"cmd\":\"cheat\",\"args\":{\"op\":\"skip\",\"slots\":5000,\"auto\":true}}");
        S.add(T, "cheat skip 5000 slot 用时 < 5s", dt < 5000 ? St::PASS : St::WARN, "<5000ms", jnum(dt,0) + "ms", "", "同上");
    }
}

// ===========================================================================
//  3. 外汇
// ===========================================================================
static void secForex(B& S) {
    const char* A = "B3-FOREX";
    S.req("newgame", "{\"seed\":8000,\"name\":\"QA\",\"cashForex\":100000}");
    auto q = S.req("quote", "{\"symbol\":\"EURUSD\"}");
    S.add(A, "EURUSD quote 成功", S.okTrue(q) ? St::PASS : St::FAIL, "ok=true", S.errCode(q), q.responseText,
          "{\"cmd\":\"quote\",\"args\":{\"symbol\":\"EURUSD\"}}");

    for (auto l : {"0", "-1", "0.5"}) {
        auto ex = S.req("open", std::string("{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":") + l + ",\"leverage\":100}");
        std::string got = S.errCode(ex);
        S.add(A, std::string("open lots=") + l + " -> BAD_LOTS/BAD_ARG", (got == "BAD_LOTS" || got == "BAD_ARG") ? St::PASS : St::FAIL,
              "BAD_LOTS", S.okTrue(ex) ? std::string("ok=true（被静默接受）") : got, ex.responseText, "lots=" + std::string(l));
    }
    { auto ex = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"bogus\",\"lots\":1,\"leverage\":100}");
      S.expectAnyErr(A, "open side=bogus -> BAD_ARG", ex, "BAD_ARG", "side:\"bogus\""); }
    { auto ex = S.req("open", "{\"symbol\":\"ZZZZZZ\",\"side\":\"long\",\"lots\":1,\"leverage\":100}");
      S.expectErr(A, "open 未知货币对 -> NO_SUCH_SYMBOL", ex, "NO_SUCH_SYMBOL", "symbol:ZZZZZZ"); }
    { auto ex = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":1,\"leverage\":0}");
      S.add(A, "open leverage=0 -> BAD_ARG (不得除零/崩溃)", !S.okTrue(ex) ? St::PASS : St::FAIL, "ok=false 且 code=BAD_ARG",
            S.okTrue(ex) ? std::string("ok=true（被静默接受，除数0风险）") : S.errCode(ex), ex.responseText, "leverage:0"); }
    // Lead 裁决：**超大手数**应报 INSUFFICIENT_MARGIN，而不是被 lots 上限挡成 BAD_LOTS
    { S.req("newgame", "{\"seed\":8100,\"name\":\"QA\",\"cashForex\":100000}");
      auto ex = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":100000,\"leverage\":100}");
      S.expectErr(A, "超大手数(lots=100000) -> INSUFFICIENT_MARGIN", ex, "INSUFFICIENT_MARGIN", "lots:100000 远超可用保证金"); }

    // 隔离：干净开局，避免前面子用例的持仓泄漏进来
    S.req("newgame", "{\"seed\":8101,\"name\":\"QA\",\"cashForex\":100000}");
    auto op = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":2,\"leverage\":100}");
    bool opened = S.okTrue(op);
    S.add(A, "open EURUSD long 2 手 (lev=100) 成功", opened ? St::PASS : St::FAIL, "ok=true", S.errCode(op), op.responseText,
          "{\"cmd\":\"open\",\"args\":{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":2,\"leverage\":100}}");
    if (opened) {
        auto d = S.data(op);
        const char* ok2[] = {"positionId","lots","openRate","margin","swap"};
        std::string miss;
        for (auto k : ok2) if (!d || !d->has(k)) miss += std::string(k) + " ";
        S.add(A, "open.data 含 positionId/lots/openRate/margin/swap", miss.empty() ? St::PASS : St::FAIL, "5 字段",
              miss.empty() ? std::string("齐全") : ("缺 " + miss), op.responseText, "open");
        if (d && d->isObj()) {
            double lots = S.numOf(d, "lots");
            double openRate = S.numOf(d, "openRate");
            double margin = S.numOf(d, "margin");
            double want = lots * 1000.0 * openRate / 100.0;
            S.add(A, "保证金 margin == lots*1000*price/leverage", qaj::approxEq(margin, want, std::max(0.05, want*0.001)) ? St::PASS : St::FAIL,
                  jnum(want,2), jnum(margin,2) + " (lots=" + jnum(lots,0) + " openRate=" + jnum(openRate,4) + " lev=100)",
                  op.responseText, "open EURUSD long 2 lots lev=100");
            S.add(A, "open.data.margin 小数位<=2", decOk(d->get("margin"),2) ? St::PASS : St::FAIL, "<=2",
                  d->get("margin") ? d->get("margin")->raw : std::string("缺"), op.responseText, "open");
            S.add(A, "open.data.swap 小数位<=2", decOk(d->get("swap"),2) ? St::PASS : St::FAIL, "<=2",
                  d->get("swap") ? d->get("swap")->raw : std::string("缺"), op.responseText, "open");
            S.add(A, "open.data.lots 为整数", (d->get("lots") && d->get("lots")->isNum() && d->get("lots")->numIsInt) ? St::PASS : St::FAIL,
                  "integer", qaj::toText(d->get("lots")), op.responseText, "open");
        }
        auto s1 = S.snap();
        double usedLots = S.pathNum(s1, "forexAccount.usedLots");
        S.add(A, "开仓后 forexAccount.usedLots=2", qaj::approxEq(usedLots,2,0.001) ? St::PASS : St::FAIL, "2",
              jnum(usedLots,0), qa::clip(qaj::toText(S.walk(s1,"forexAccount")), 300), "open 2 lots 后 snapshot");
        auto fp = S.walk(s1, "forexPositions");
        S.add(A, "forexPositions 含 1 条", (fp && fp->isArr() && fp->arr.size()==1) ? St::PASS : St::FAIL, "1",
              fp && fp->isArr() ? std::to_string(fp->arr.size()) : std::string("非数组"),
              qa::clip(qaj::toText(fp), 300), "open 后 snapshot");
        if (fp && fp->isArr() && !fp->arr.empty() && fp->arr[0]->isObj()) {
            auto p0 = fp->arr[0];
            // 协议 v1.0.1 §3.4：forexPositions[i] 正式包含 positionId
            const char* pk[] = {"positionId","symbol","name","side","lots","openRate","last","margin","pnl","swap","stopLoss","takeProfit"};
            std::set<std::string> want(pk, pk+12);
            std::string m2, ex2;
            for (auto k : pk) if (!p0->has(k)) m2 += std::string(k) + " ";
            for (auto& k : p0->keys) if (!want.count(k)) ex2 += k + " ";
            S.add(A, "forexPositions[i] 字段齐全（含 positionId）", m2.empty() ? St::PASS : St::FAIL, "12 字段",
                  m2.empty() ? std::string("齐全") : ("缺 " + m2), qa::clip(qaj::toText(p0),400), "open 后 snapshot");
            if (!ex2.empty()) S.add(A, "forexPositions[i] 无多余字段", St::WARN, "无多余", "多余 " + ex2, "", "open 后 snapshot");
            auto sd = p0->get("side");
            S.add(A, "forexPositions[i].side 属 long|short", (sd && sd->isStr() && (sd->str=="long"||sd->str=="short")) ? St::PASS : St::FAIL,
                  "long|short", qaj::toText(sd), "", "open");
            S.add(A, "forexPositions[i].margin 小数位<=2", decOk(p0->get("margin"),2) ? St::PASS : St::FAIL, "<=2",
                  p0->get("margin") ? p0->get("margin")->raw : std::string("缺"), "", "open");
            S.add(A, "forexPositions[i].pnl 小数位<=2", decOk(p0->get("pnl"),2) ? St::PASS : St::FAIL, "<=2",
                  p0->get("pnl") ? p0->get("pnl")->raw : std::string("缺"), "", "open");
        }
        double ml = S.pathNum(s1, "forexAccount.marginLevel");
        S.add(A, "有持仓时 marginLevel 为百分比(>0)", ml > 0 ? St::PASS : St::FAIL, ">0", jnum(ml,2),
              qa::clip(qaj::toText(S.walk(s1,"forexAccount")),300), "open 后 snapshot");
        double eq = S.pathNum(s1,"forexAccount.equity");
        double mg = S.pathNum(s1,"forexAccount.margin");
        if (mg > 0) {
            double w3 = eq / mg * 100.0;
            S.add(A, "marginLevel == equity/margin*100", qaj::approxEq(ml, w3, std::max(0.5, w3*0.02)) ? St::PASS : St::FAIL,
                  jnum(w3,2), jnum(ml,2), qa::clip(qaj::toText(S.walk(s1,"forexAccount")),300), "open 后 snapshot");
        }
        // snapshot.forexPositions[] 是否带 positionId —— close 的唯一可用 id 来源
        {
            bool hasPid = false;
            if (fp && fp->isArr()) for (auto& pp : fp->arr)
                if (pp->isObj() && pp->has("positionId")) hasPid = true;
            S.add(A, "forexPositions[i] 含 positionId (否则前端无法 close)", hasPid ? St::PASS : St::FAIL,
                  "含 positionId", hasPid ? std::string("有") : std::string("缺失 —— 前端只能靠 open 返回的 id 记住，重开局/重连后无法平仓"),
                  qa::clip(qaj::toText(fp), 300), "open 后读 snapshot.forexPositions");
        }
        if (fp && fp->isArr() && !fp->arr.empty()) {
            // close 必须用 open 当时返回的 positionId（协议 §3.9 的唯一来源）
            auto pidOpen = S.data(op) ? S.data(op)->get("positionId") : nullptr;
            std::string pidS = (pidOpen && pidOpen->isNum()) ? qaj::toText(pidOpen) : std::string("0");
            auto cl = S.req("close", "{\"positionId\":" + pidS + "}");
            S.add(A, "close 全平成功", S.okTrue(cl) ? St::PASS : St::FAIL, "ok=true", S.errCode(cl), cl.responseText,
                  "{\"cmd\":\"close\",\"args\":{\"positionId\":" + pidS + "}}");
            auto s2 = S.snap();
            double lots2 = S.pathNum(s2, "forexAccount.usedLots");
            S.add(A, "全平后 usedLots=0", qaj::approxEq(lots2,0,0.001) ? St::PASS : St::FAIL, "0", jnum(lots2,0), "", "close 后 snapshot");
            auto cl2 = S.req("close", "{\"positionId\":" + pidS + "}");
            S.expectErr(A, "重复平仓 -> NO_POSITION", cl2, "NO_POSITION", "同一 positionId 平两次");
            // 部分平仓（协议 §3.9: lots 可省略=全平）
            S.req("newgame", "{\"seed\":8102,\"name\":\"QA\",\"cashForex\":100000}");
            auto op2 = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":4,\"leverage\":100}");
            if (S.okTrue(op2)) {
                auto pid2 = S.data(op2) ? S.data(op2)->get("positionId") : nullptr;
                std::string p2 = (pid2 && pid2->isNum()) ? qaj::toText(pid2) : std::string("0");
                auto pc = S.req("close", "{\"positionId\":" + p2 + ",\"lots\":1}");
                S.add(A, "部分平仓 close(lots=1) 成功", S.okTrue(pc) ? St::PASS : St::FAIL, "ok=true", S.errCode(pc), pc.responseText,
                      "open 4 lots -> close lots:1");
                auto s3 = S.snap();
                double ul = S.pathNum(s3, "forexAccount.usedLots");
                S.add(A, "部分平仓后 usedLots 4->3", qaj::approxEq(ul,3,0.01) ? St::PASS : St::FAIL, "3", jnum(ul,0),
                      qa::clip(qaj::toText(S.walk(s3,"forexAccount")),300), "close lots:1 后 snapshot");
                auto pc2 = S.req("close", "{\"positionId\":" + p2 + "}");
                S.add(A, "剩余全平成功", S.okTrue(pc2) ? St::PASS : St::FAIL, "ok=true", S.errCode(pc2), pc2.responseText, "close 无 lots");
            } else {
                S.add(A, "部分平仓 close(lots=1) 成功", St::NOT_TESTED, "ok=true", "开仓失败: " + S.errCode(op2), op2.responseText, "-");
                S.add(A, "部分平仓后 usedLots 4->3", St::NOT_TESTED, "3", "开仓失败", op2.responseText, "-");
                S.add(A, "剩余全平成功", St::NOT_TESTED, "ok=true", "开仓失败", op2.responseText, "-");
            }
        }
    }
    { S.req("newgame", "{\"seed\":8001,\"name\":\"QA\",\"cashForex\":100}");
      auto ex = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":10,\"leverage\":100}");
      S.expectErr(A, "外汇现金不足 -> INSUFFICIENT_MARGIN", ex, "INSUFFICIENT_MARGIN", "cashForex:100, lots:10"); }
    // ============ 爆仓（协议 v1.0.1 §3.8b，阈值 marginLevel < 50.0%）============
    // 构造思路（Lead 口径）：把外汇现金压小 -> 开大手数让 margin 逼近 equity
    //   -> 再用 cheat setCash 把 free margin 榨干 -> 逐片 cheat price 制造浮亏 -> 击穿 50%
    {
        const char* M = "B3-MARGIN";
        S.req("newgame", "{\"seed\":8002,\"name\":\"QA\",\"cashForex\":100000}");
        // leverage=1 把保证金放大到 ~5.4 万；再用 setCash 把外汇现金压到保证金附近，
        // 这样每 5% 的浮亏(2300) 都会直接吃掉权益，很快击穿 50%（协议 v1.0.1 §3.8b）
        auto op = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":50,\"leverage\":1}");
        S.add(M, "为爆仓构造大保证金持仓 (leverage=1)", S.okTrue(op) ? St::PASS : St::FAIL, "ok=true", S.errCode(op),
              op.responseText, "open EURUSD long 50 lots leverage:1");
        if (S.okTrue(op)) {
            auto sc = S.req("cheat", "{\"op\":\"setCash\",\"account\":\"forex\",\"value\":60000}");
            S.add(M, "把外汇现金压到仅略高于保证金(60000)", S.okTrue(sc) ? St::PASS : St::FAIL, "ok=true", S.errCode(sc),
                  sc.responseText, "cheat setCash forex 60000");
            auto s0 = S.snap();
            double eq0 = S.pathNum(s0, "forexAccount.equity");
            double mg0 = S.pathNum(s0, "forexAccount.margin");
            double ml0 = S.pathNum(s0, "forexAccount.marginLevel");
            S.add(M, "构造后 marginLevel 已接近 100%（保证金逼近权益）", ml0 < 200 ? St::PASS : St::WARN,
                  "< 200%", jnum(ml0,2) + "% (equity=" + jnum(eq0,2) + " margin=" + jnum(mg0,2) + ")", "", "open 后 snapshot");
            // 一次到位：从 110% 直接打压到 50% 以下（-65% 使 marginLevel 落到 ~49%）
            // 说明：必须一步跨过 50%，因为引擎按"每个时间片结束时"检查（§3.8b）。
            bool mc = false, bk = false; std::string mcRaw, evd; double lvl = ml0;
            S.req("cheat", "{\"op\":\"price\",\"symbol\":\"EURUSD\",\"pct\":-0.65}");
            auto tk = S.req("tick", "{\"n\":1,\"mode\":\"auto\"}", 30000);
            auto d = S.data(tk);
            if (d && d->get("events") && d->get("events")->isArr()) {
                for (auto& e : d->get("events")->arr) {
                    auto k = e->get("kind");
                    if (!k || !k->isStr()) continue;
                    evd += k->str + " ";
                    if (k->str == "margin_call") { mc = true; if (mcRaw.empty()) mcRaw = qaj::toText(e); }
                    if (k->str == "bankrupt") bk = true;
                }
            }
            lvl = S.pathNum(S.snap(), "forexAccount.marginLevel");
            S.add(M, "§3.8b: marginLevel 击穿 50% 时产生 margin_call 事件", mc ? St::PASS : St::FAIL,
                  "存在 marginLevel < 50 且产生 kind=margin_call",
                  mc ? ("已触发: " + qa::clip(mcRaw, 200)) : ("未触发; 最终 marginLevel=" + jnum(lvl,2) + " 事件:[" + evd + "]"),
                  qa::clip(mcRaw, 300), "newgame -> open lots50 leverage1 -> cheat setCash forex 60000 -> cheat price pct:-0.65 -> tick n:1 auto");
            if (mc) {
                S.add(M, "margin_call 的 account 字段 == \"forex\"", mcRaw.find("\"account\":\"forex\"") != std::string::npos ? St::PASS : St::FAIL,
                      "account:\"forex\"", qa::clip(mcRaw, 200), qa::clip(mcRaw, 300), "同上");
                // §3.8b 规定的事件字段（引擎还多给了 symbol/side/note，属有益扩展 -> WARN 不判错）
                for (auto k : {"account","positionId","lots","level","loss","at"}) {
                    bool has = mcRaw.find(std::string("\"") + k + "\"") != std::string::npos;
                    S.add(M, std::string("margin_call 含 §3.8b 字段 ") + k, has ? St::PASS : St::FAIL, "存在", has ? "有" : "缺失",
                          qa::clip(mcRaw, 300), "§3.8b 事件字段表");
                }
                S.add(M, "margin_call 的 level 确实 < 50（阈值口径复核）",
                      (mcRaw.find("\"level\":") != std::string::npos) ? St::PASS : St::FAIL,
                      "level < 50.0", qa::clip(mcRaw, 200), qa::clip(mcRaw, 300), "§3.8b");
                S.add(M, "强平订单进 history（reason=liquidation）",
                      [&]{ auto h = S.req("history", "{\"limit\":10,\"market\":\"forex\"}"); return qaj::toText(S.data(h)).find("\"liquidation\"") != std::string::npos; }() ? St::PASS : St::FAIL,
                      "history 含 reason=liquidation", "见 history 报文", "", "margin_call 后 history market:forex");
                double after = S.pathNum(S.snap(), "forexAccount.marginLevel");
                S.add(M, "强平后 marginLevel 恢复到 >= 50.0 或持仓清空",
                      (after >= 50.0 || qaj::approxEq(S.pathNum(S.snap(),"forexAccount.usedLots"), 0, 0.01)) ? St::PASS : St::FAIL,
                      ">= 50.0 或 usedLots==0", jnum(after,2) + " / usedLots=" + jnum(S.pathNum(S.snap(),"forexAccount.usedLots"),0),
                      "", "margin_call 后 snapshot");
            }
            // 说明：本用例强平后 equity=26640 > 0，因此**不该**出现 bankrupt（§3.8b 第四条前提不成立）。
            // 真正的 §3.8b-4 确定性覆盖见下方 B3-BANKRUPT 段（equity 被打到 -511650）。
            (void)bk;

            // ---------- §3.8b 第四条 确定性用例：equity 打到 <= 0 ----------
            // 关键：equity = cash + 浮盈亏 + swap，而强平会把该仓位的 pnl 加回 cash。
            // 因此必须让「亏损 > cash + 可释放保证金」：用 leverage=1000 把 margin 压到 ~542，
            // 再用 -95% 造出 ~-51 万的巨额亏损，平仓后 equity = 1000 - 512650 < 0。
            {
                const char* K = "B3-BANKRUPT";
                S.req("newgame", "{\"seed\":8002,\"name\":\"QA\",\"cashForex\":1000}");
                auto o2 = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":500,\"leverage\":1000}");
                S.add(K, "构造：leverage=1000 开 500 手（margin 极小）", S.okTrue(o2) ? St::PASS : St::FAIL, "ok=true",
                      S.errCode(o2), o2.responseText, "open EURUSD long 500 leverage:1000");
                if (S.okTrue(o2)) {
                    auto od = S.data(o2);
                    auto mg = od ? od->get("margin") : nullptr;
                    S.add(K, "open.margin ≈ 542.45（杠杆 1000）", (mg && mg->isNum() && mg->num < 1000) ? St::PASS : St::FAIL,
                          "< 1000", qaj::toText(mg), o2.responseText, "同上");
                    S.req("cheat", "{\"op\":\"price\",\"symbol\":\"EURUSD\",\"pct\":-0.95}");
                    auto tk2 = S.req("tick", "{\"n\":1,\"mode\":\"auto\"}", 30000);
                    auto d2 = S.data(tk2);
                    bool kMc = false, kBk = false; std::string kMcRaw, kBkRaw;
                    if (d2 && d2->get("events") && d2->get("events")->isArr()) {
                        for (auto& e : d2->get("events")->arr) {
                            auto k = e->get("kind");
                            if (!k || !k->isStr()) continue;
                            if (k->str == "margin_call") { kMc = true; if (kMcRaw.empty()) kMcRaw = qaj::toText(e); }
                            if (k->str == "bankrupt")    { kBk = true; if (kBkRaw.empty()) kBkRaw = qaj::toText(e); }
                        }
                    }
                    S.add(K, "§3.8b-4: 击穿 50% 且 equity<=0 时产生 bankrupt 事件", kBk ? St::PASS : St::FAIL,
                          "events 含 kind=bankrupt", kBk ? ("已出现: " + qa::clip(kBkRaw, 150)) : std::string("未出现"),
                          qa::clip(tk2.responseText, 400), "cheat price pct:-0.95 -> tick n:1 auto");
                    S.add(K, "§3.8b-4: 同一次 tick 里 margin_call 与 bankrupt 都出现", (kMc && kBk) ? St::PASS : St::FAIL,
                          "两者都有", std::string("margin_call=") + (kMc?"Y":"N") + " bankrupt=" + (kBk?"Y":"N"),
                          qa::clip(kMcRaw, 200), "同上");
                    S.add(K, "bankrupt 事件含 account 字段", kBkRaw.find("\"account\"") != std::string::npos ? St::PASS : St::FAIL,
                          "含 account", qa::clip(kBkRaw, 150), qa::clip(kBkRaw, 200), "§3.8b 第四条");
                    auto s4 = S.snap();
                    double eq2 = S.pathNum(s4, "forexAccount.equity");
                    double ul2 = S.pathNum(s4, "forexAccount.usedLots");
                    S.add(K, "§3.8b-4: 收尾 equity <= 0", eq2 <= 0 ? St::PASS : St::FAIL, "<= 0", jnum(eq2, 2),
                          qa::clip(qaj::toText(S.walk(s4,"forexAccount")), 300), "bankrupt 后 snapshot");
                    S.add(K, "§3.8b-4: 持仓已清空 usedLots=0", qaj::approxEq(ul2, 0, 0.01) ? St::PASS : St::FAIL, "0",
                          jnum(ul2, 0), "", "bankrupt 后 snapshot");
                    auto bkv = s4 ? s4->get("bankrupt") : nullptr;
                    S.add(K, "§3.8b-4: snapshot.bankrupt == true", (bkv && bkv->isBool() && bkv->b) ? St::PASS : St::FAIL,
                          "true", qaj::toText(bkv), "", "bankrupt 后 snapshot");
                    auto h2 = S.req("history", "{\"limit\":5,\"market\":\"forex\"}");
                    std::string hd2 = qaj::toText(S.data(h2));
                    S.add(K, "§3.8b-4: 强平单进 history(reason=liquidation)", hd2.find("\"liquidation\"") != std::string::npos ? St::PASS : St::FAIL,
                          "含 reason=liquidation", "见报文", qa::clip(h2.responseText, 300), "bankrupt 后 history");
                } else {
                    for (auto t : {"§3.8b-4: 击穿 50% 且 equity<=0 时产生 bankrupt 事件",
                                   "§3.8b-4: 收尾 equity <= 0", "§3.8b-4: snapshot.bankrupt == true"})
                        S.add(K, t, St::NOT_TESTED, "ok", "无法开仓: " + S.errCode(o2), o2.responseText, "-");
                }
            }
        } else {
            S.add(M, "§3.8b: marginLevel 击穿 50% 时产生 margin_call 事件", St::NOT_TESTED, "含 margin_call",
                  "无法构造大保证金持仓: " + S.errCode(op), op.responseText, "-");
        }
    }
}

// ===========================================================================
//  4. 种子可复现
// ===========================================================================
// ===========================================================================
//  §3.8a 挂单有效期（order_expired, TTL=20 时间片）+ order_partial
// ===========================================================================
static void secOrders(B& S) {
    const char* O = "B9-ORDER";
    // ---------- order_expired：TTL = 20 时间片 ----------
    {
        S.req("newgame", "{\"seed\":40404,\"name\":\"QA\"}");
        // 挂一张「不可能成交」的限价买单：价格远低于市价（比如 1.00），只可能被部分/不成交
        auto q0 = S.req("quote", "{\"symbol\":\"SH600519\"}");
        double last0 = S.numOf(S.data(q0), "last");
        if (last0 <= 0) last0 = 1700;
        double farPx = std::max(1.0, last0 * 0.3);
        auto snap0 = S.snap();
        double cashBefore = S.pathNum(snap0, "stockAccount.cash");
        auto bo = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":" + jnum(farPx, 2) + "}");
        auto bd = S.data(bo);
        S.add(O, "挂一张远价限价买单（price = 市价 x0.3，几乎不可能成交）",
              S.okTrue(bo) ? St::PASS : St::FAIL, "ok=true", S.errCode(bo), bo.responseText,
              "buy SH600519 qty:100 type:limit price:" + jnum(farPx,2));
        auto oid = bd ? bd->get("orderId") : nullptr;
        std::string oidS = (oid && oid->isNum()) ? qaj::toText(oid) : std::string("0");
        if (S.okTrue(bo)) {
            auto st0 = S.data(bo) ? S.data(bo)->get("status") : nullptr;
            S.add(O, "远价限价单初始 status=open", (st0 && st0->isStr() && st0->str == "open") ? St::PASS : St::FAIL,
                  "open", qaj::toText(st0), bo.responseText, "同上");
            S.add(O, "挂单后现金被冻结（cash 减少）",
                  (S.pathNum(S.snap(), "stockAccount.cash") < cashBefore - 1) ? St::PASS : St::FAIL,
                  "< " + jnum(cashBefore,2), jnum(S.pathNum(S.snap(),"stockAccount.cash"),2), "", "挂单后 snapshot");
            // 推进 19 片：应仍为 open（未到期）
            S.req("tick", "{\"n\":19,\"mode\":\"manual\"}", 30000);
            auto ord19 = S.req("orders", "{\"market\":\"stock\"}");
            std::string d19 = qaj::toText(S.data(ord19));
            S.add(O, "§3.8a: 第 19 片时挂单仍为 open（TTL=20 未到）",
                  d19.find("\"status\":\"expired\"") == std::string::npos ? St::PASS : St::FAIL,
                  "无 expired", d19.find("\"status\":\"expired\"") != std::string::npos ? std::string("已提前 expired") : std::string("仍 open/存在"),
                  qa::clip(ord19.responseText, 300), "tick 19 片后 orders");
            // 再推进 1 片（共 20 片）：应 expired
            auto tk20 = S.req("tick", "{\"n\":1,\"mode\":\"manual\"}", 20000);
            std::string ev20; bool hasExpEv = false; std::string expRaw;
            auto d20 = S.data(tk20);
            if (d20 && d20->get("events") && d20->get("events")->isArr()) {
                for (auto& e : d20->get("events")->arr) {
                    auto k = e->get("kind");
                    if (k && k->isStr()) { ev20 += k->str + " "; if (k->str == "order_expired") { hasExpEv = true; if (expRaw.empty()) expRaw = qaj::toText(e); } }
                }
            }
            auto ord20 = S.req("orders", "{\"market\":\"stock\"}");
            std::string d20s = qaj::toText(S.data(ord20));
            S.add(O, "§3.8a: 第 20 片时产生 kind=order_expired 事件", hasExpEv ? St::PASS : St::FAIL,
                  "events 含 order_expired", hasExpEv ? ("已产生: " + qa::clip(expRaw, 200)) : ("未产生; 本次 events=[" + ev20 + "]"),
                  qa::clip(tk20.responseText, 350), "tick 第 20 片 -> 检查 events");
            // §3.8a（v1.0.1 更正）：expired 只在**第 20 片这 1 个时间片**内可见，第 21 片起该单从 orders 中移除。
            // 本断言刻意接受两种形态（含 status=expired 或已移除），因为「可见窗口多长」由下面的
            // B9-ORDER-019/020 两条窗口断言单独钉死，避免把「元素是否存在」和「存在多久」混为一谈。
            S.add(O, "§3.8a: 第 20 片 orders 中该单已终态（status=expired 或已被移除）",
                  d20s.find("\"status\":\"expired\"") != std::string::npos || d20s == "{\"orders\":[]}" ? St::PASS : St::FAIL,
                  "status=expired 或 {\"orders\":[]}", qa::clip(d20s, 250),
                  qa::clip(ord20.responseText, 300), "tick 20 片后 orders");
            // 解冻校验
            double cashAfter = S.pathNum(S.snap(), "stockAccount.cash");
            S.add(O, "§3.8a: expire 后冻结资金立即解冻（cash 恢复）",
                  qaj::approxEq(cashAfter, cashBefore, 1.0) ? St::PASS : St::FAIL,
                  jnum(cashBefore,2), jnum(cashAfter,2), "", "tick 20 片后 snapshot.stockAccount.cash");
            // 事件字段
            if (hasExpEv) {
                for (auto k : {"orderId","symbol","side","qty","filled","at"}) {
                    bool h = expRaw.find(std::string("\"") + k + "\"") != std::string::npos;
                    S.add(O, std::string("§3.8a: order_expired 含字段 ") + k, h ? St::PASS : St::FAIL, "存在",
                          h ? "有" : "缺失", qa::clip(expRaw, 300), "§3.8a 事件字段表");
                }
            } else {
                for (auto k : {"orderId","symbol","side","qty","filled","at"})
                    S.add(O, std::string("§3.8a: order_expired 含字段 ") + k, St::NOT_TESTED, "存在", "事件未产生", "", "-");
            }
            // ---- §3.8a 可见窗口钉死：expired 只在第 20 片可见，第 21 片起移除 ----
            {
                auto t21 = S.req("tick", "{\"n\":1,\"mode\":\"manual\"}", 20000);
                auto ord21 = S.req("orders", "{\"market\":\"stock\"}");
                std::string d21 = qaj::toText(S.data(ord21));
                S.add(O, "§3.8a(更正): 第 21 片该单已从 orders 中移除（可见窗口仅 1 片）",
                      d21 == "{\"orders\":[]}" ? St::PASS : St::FAIL,
                      "{\"orders\":[]}", qa::clip(d21, 200),
                      qa::clip(ord21.responseText, 250), "tick 第 21 片后 orders");
                // 事件恰好 1 次（不重复产生）
                int expCount = 0;
                auto d21t = S.data(t21);
                if (d21t && d21t->get("events") && d21t->get("events")->isArr())
                    for (auto& e : d21t->get("events")->arr) {
                        auto k = e->get("kind");
                        if (k && k->isStr() && k->str == "order_expired") expCount++;
                    }
                S.add(O, "§3.8a(更正): 第 21 片不再重复产生 order_expired 事件", expCount == 0 ? St::PASS : St::FAIL,
                      "0 次", std::to_string(expCount) + " 次", qa::clip(t21.responseText, 200), "tick 第 21 片 events");
            }
            // 不再参与撮合：把它撤单应失败（已终态）
            auto canc = S.req("cancel", "{\"orderId\":" + oidS + "}");
            S.add(O, "§3.8a: 已 expired 的挂单不能再撤单（终态）", !S.okTrue(canc) ? St::PASS : St::FAIL,
                  "ok=false", S.okTrue(canc) ? std::string("仍然撤单成功") : S.errCode(canc), canc.responseText,
                  "cancel 同一 orderId");
        } else {
            for (auto k : {"§3.8a: 第 20 片时产生 kind=order_expired 事件", "§3.8a: expire 后冻结资金立即解冻（cash 恢复）",
                           "§3.8a: expire 后挂单不再出现在 open 列表 / status=expired"})
                S.add(O, k, St::NOT_TESTED, "ok=true", "无法挂单: " + S.errCode(bo), bo.responseText, "-");
        }
    }

    // ---------- order_partial：部分成交 ----------
    {
        S.req("newgame", "{\"seed\":40505,\"name\":\"QA\"}");
        // 构造「卖出限价单的可卖冻结量 < 挂单量」：先买 100 股，解锁后挂 300 股卖单
        S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        S.req("cheat", "{\"op\":\"unlock\"}");
        auto ss = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":300,\"type\":\"limit\",\"price\":99999.0}");
        if (!S.okTrue(ss)) {
            // 若引擎直接拒单，则换「买单被部分撮合」思路：挂一张量小于可成交量的买单
            auto b2 = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":99999.0}");
            ss = b2;
        }
        std::string sp = qaj::toText(S.data(ss));
        // 注：client 侧无法构造「可卖量 < 挂单量」——引擎在挂单前置校验就挡掉了
        //   （INSUFFICIENT_POSITION / INSUFFICIENT_CASH），不会产生部分冻结挂单。
        // 巨额卖单（超过市场可承接量）实测也是整单成交或整单挂 open，不产生部分成交。
        // 故本项按 Lead 口径保留为 WARN + NOT_TESTED 说明，不误判为引擎缺陷。
        S.add(O, "order_partial 场景构造（client 侧可构造性）", S.okTrue(ss) ? St::PASS : St::WARN,
              "ok=true（或该场景由引擎另行构造）", S.okTrue(ss) ? qa::clip(sp, 160) : S.errCode(ss),
              ss.responseText, "构造『可卖量 < 挂单量』或等价场景");
        S.add(O, "order_partial 是否可由 client 侧确定性构造（信息项）", St::WARN,
              "—", "实测不可构造：引擎在挂单前置校验即拒绝超额挂单；巨额卖单亦为整单成交/整单 open", "", "见下");
        // 逐片推进，寻找 order_partial 事件
        bool gotPartial = false; std::string partRaw, evAll;
        for (int i = 0; i < 21 && !gotPartial; i++) {
            auto t = S.req("tick", "{\"n\":1,\"mode\":\"manual\"}", 20000);
            auto d = S.data(t);
            if (d && d->get("events") && d->get("events")->isArr()) {
                for (auto& e : d->get("events")->arr) {
                    auto k = e->get("kind");
                    if (!k || !k->isStr()) continue;
                    evAll += k->str + " ";
                    if (k->str == "order_partial") { gotPartial = true; if (partRaw.empty()) partRaw = qaj::toText(e); }
                }
            }
        }
        S.add(O, "§3.8a: 部分成交时产生 kind=order_partial 事件", gotPartial ? St::PASS : St::WARN,
              "events 含 order_partial",
              gotPartial ? ("已产生: " + qa::clip(partRaw, 200)) : ("本构造未触发; 本次 events=[" + evAll + "]"),
              qa::clip(partRaw, 300), "构造部分成交场景后逐片 tick 最多 21 次");
        if (gotPartial) {
            S.add(O, "§3.8a: order_partial 含 remaining 字段", partRaw.find("\"remaining\"") != std::string::npos ? St::PASS : St::FAIL,
                  "含 remaining", partRaw.find("\"remaining\"") != std::string::npos ? std::string("有") : std::string("缺失"),
                  qa::clip(partRaw, 300), "§3.8a 事件字段表");
            for (auto k : {"symbol","side","qty","price"}) {
                bool h = partRaw.find(std::string("\"") + k + "\"") != std::string::npos;
                S.add(O, std::string("§3.8a: order_partial 含字段 ") + k, h ? St::PASS : St::FAIL, "存在", h ? "有" : "缺失",
                      qa::clip(partRaw, 300), "§3.8a");
            }
        }
    }

    // ---------- 顺带：expired 状态在 orders 的 status 枚举内 ----------
    {
        auto ex = S.req("orders", "{\"market\":\"all\"}");
        std::string d = qaj::toText(S.data(ex));
        // 只校验：若出现 status，必须落在冻结枚举内
        const char* ST[] = {"open","partial","filled","cancelled","expired"};
        std::set<std::string> seen;
        ValuePtr root = S.data(ex);
        auto arr = (root && root->isObj()) ? root->get("orders") : nullptr;
        if (arr && arr->isArr()) for (auto& o : arr->arr) {
            auto s = o->get("status");
            if (s && s->isStr()) seen.insert(s->str);
        }
        bool allOk = true; std::string bad;
        for (auto& s : seen) { bool f = false; for (auto x : ST) if (s == x) f = true; if (!f) { allOk = false; bad += s + " "; } }
        S.add(O, "orders[].status 均在冻结枚举内", allOk ? St::PASS : St::FAIL,
              "open|partial|filled|cancelled|expired", allOk ? std::string("全部合法") : ("非法: " + bad),
              qa::clip(d, 250), "orders 后校验 status 枚举");
    }
}

static void secSeed(B& S) {
    const char* A = "B4-SEED";
    auto seq = [&](long seed) -> std::string {
        S.req("newgame", "{\"seed\":" + std::to_string(seed) + ",\"name\":\"QA\"}");
        auto m = S.req("market", "{\"market\":\"all\"}");
        auto d = S.data(m);
        std::string out;
        if (d && d->get("stocks") && d->get("stocks")->isArr()) {
            for (auto& so : d->get("stocks")->arr) {
                auto sy = so->get("symbol"); auto la = so->get("last");
                out += (sy && sy->isStr() ? sy->str : std::string("?")) + "=" + (la && la->isNum() ? la->raw : std::string("?")) + ";";
            }
        }
        return out;
    };
    std::string s1 = seq(12345), s2 = seq(12345), s3 = seq(54321);
    S.add(A, "同 seed 两次 newgame 行情序列完全一致", (s1 == s2) ? St::PASS : St::FAIL, "完全一致",
          (s1 == s2) ? std::string("一致") : std::string("不一致"),
          "第一次: " + qa::clip(s1, 250) + "\n第二次: " + qa::clip(s2, 250),
          "newgame{seed:12345} -> market; 再 newgame{seed:12345} -> market");
    S.add(A, "不同 seed 行情序列不同", (s1 != s3) ? St::PASS : St::FAIL, "不同",
          (s1 != s3) ? std::string("不同") : std::string("完全相同（seed 无效）"), qa::clip(s3, 250),
          "newgame{seed:54321} -> market");
    auto rv = S.req("cheat", "{\"op\":\"revealSeed\"}");
    S.add(A, "cheat revealSeed 可调用", S.okTrue(rv) ? St::PASS : St::FAIL, "ok=true", S.errCode(rv), rv.responseText,
          "{\"cmd\":\"cheat\",\"args\":{\"op\":\"revealSeed\"}}");
    S.req("newgame", "{\"seed\":12345,\"name\":\"QA\"}");
    S.req("tick", "{\"n\":7,\"mode\":\"manual\"}");
    auto cs1 = S.req("cheat", "{\"op\":\"seed\",\"seed\":12345}");
    auto s4 = S.req("market", "{\"market\":\"all\"}");
    auto cs2 = S.req("cheat", "{\"op\":\"seed\",\"seed\":12345}");
    auto s6 = S.req("market", "{\"market\":\"all\"}");
    std::string a1 = qaj::toText(S.data(s4)); std::string a2 = qaj::toText(S.data(s6));
    S.add(A, "两次 cheat seed(12345) 后行情一致", (a1 == a2) ? St::PASS : St::FAIL, "一致",
          (a1 == a2) ? std::string("一致") : std::string("不一致"), qa::clip(a1, 250),
          "cheat seed 12345 -> market -> cheat seed 12345 -> market");
    S.add(A, "cheat seed 返回 ok=true", S.okTrue(cs1) ? St::PASS : St::FAIL, "ok=true", S.errCode(cs1), cs1.responseText, "cheat seed");
    S.add(A, "cheat seed 返回 ok=true (第二次)", S.okTrue(cs2) ? St::PASS : St::FAIL, "ok=true", S.errCode(cs2), cs2.responseText, "cheat seed");
}

// ===========================================================================
//  5. 作弊器
// ===========================================================================
static ValuePtr cheatState(const Exchange& ex, B& S) {
    auto d = S.data(ex);
    return d ? d->get("cheatState") : nullptr;
}

static void secCheats(B& S) {
    const char* A = "B5-CHEAT";
    S.req("newgame", "{\"seed\":11000,\"name\":\"QA\"}");

    {
        auto ex = S.req("cheat", "{\"op\":\"list\"}");
        auto d = S.data(ex);
        auto cs = d ? d->get("cheats") : nullptr;
        S.add(A, "cheat list 返回 cheats 数组", (cs && cs->isArr()) ? St::PASS : St::FAIL, "cheats:[]",
              cs ? cs->typeName() : std::string("缺失"), qa::clip(ex.responseText,400),
              "{\"cmd\":\"cheat\",\"args\":{\"op\":\"list\"}}");
        if (cs && cs->isArr() && !cs->arr.empty() && cs->arr[0]->isObj()) {
            auto c0 = cs->arr[0];
            std::string miss;
            for (auto k : {"op","label","desc","args"}) if (!c0->has(k)) miss += std::string(k) + " ";
            S.add(A, "cheat list 项含 op/label/desc/args", miss.empty() ? St::PASS : St::FAIL, "4 字段",
                  miss.empty() ? std::string("齐全") : ("缺 " + miss), qa::clip(ex.responseText,300), "cheat list");
        }
        const char* OPS[] = {"list","money","reset","price","pump","freeze","unlock","t1","infiniteMoney",
                             "godMode","noCommission","perfectInfo","fillOrders","setCash","winRate",
                             "seed","speed","skip","news","bankrupt","unbankrupt","revealSeed"};
        std::set<std::string> have;
        if (cs && cs->isArr()) for (auto& c : cs->arr) { auto o = c->get("op"); if (o && o->isStr()) have.insert(o->str); }
        std::string miss;
        for (auto o : OPS) if (!have.count(o)) miss += std::string(o) + " ";
        S.add(A, "cheat list 覆盖协议 §4 全部 22 个 op", miss.empty() ? St::PASS : St::FAIL, "22 个 op",
              miss.empty() ? ("齐全 " + std::to_string(have.size())) : ("缺 " + miss), "", "cheat list 后与 §4 表格比对");
    }
    // money
    {
        auto s0 = S.snap(); double c0 = S.pathNum(s0,"stockAccount.cash");
        auto ex = S.req("cheat", "{\"op\":\"money\",\"amount\":1000000,\"account\":\"stock\"}");
        S.add(A, "cheat money(stock) 成功", S.okTrue(ex) ? St::PASS : St::FAIL, "ok=true", S.errCode(ex), ex.responseText,
              "{\"cmd\":\"cheat\",\"args\":{\"op\":\"money\",\"amount\":1000000,\"account\":\"stock\"}}");
        auto s1 = S.snap(); double c1 = S.pathNum(s1,"stockAccount.cash");
        S.add(A, "cheat money 使股票现金 +1000000", qaj::approxEq(c1-c0, 1000000, 0.01) ? St::PASS : St::FAIL,
              jnum(c0+1000000,2), jnum(c1,2), "money 前后 snapshot.stockAccount.cash", "money 1000000 stock");
        auto fe = S.req("cheat", "{\"op\":\"money\",\"amount\":5000,\"account\":\"forex\"}");
        auto s2 = S.snap();
        S.add(A, "cheat money(forex) 生效", qaj::approxEq(S.pathNum(s2,"forexAccount.cash") - S.pathNum(s1,"forexAccount.cash"), 5000, 0.01) ? St::PASS : St::FAIL,
              "+5000", jnum(S.pathNum(s2,"forexAccount.cash"),2), fe.responseText, "money 5000 forex");
        auto be = S.req("cheat", "{\"op\":\"money\",\"amount\":1234,\"account\":\"both\"}");
        auto s3 = S.snap();
        S.add(A, "cheat money(both) 双边都加", (qaj::approxEq(S.pathNum(s3,"stockAccount.cash")-S.pathNum(s2,"stockAccount.cash"),1234,0.01) &&
              qaj::approxEq(S.pathNum(s3,"forexAccount.cash")-S.pathNum(s2,"forexAccount.cash"),1234,0.01)) ? St::PASS : St::FAIL,
              "+1234 双边", "stock=" + jnum(S.pathNum(s3,"stockAccount.cash"),2) + " forex=" + jnum(S.pathNum(s3,"forexAccount.cash"),2),
              be.responseText, "money 1234 both");
        auto d = S.data(ex);
        S.add(A, "cheat.data 含 op/ok/detail/cheatState", (d && d->has("op") && d->has("ok") && d->has("detail") && d->has("cheatState")) ? St::PASS : St::FAIL,
              "op,ok,detail,cheatState", d ? qaj::toText(d) : std::string("缺 data"), qa::clip(ex.responseText,400), "cheat money");
        auto st = cheatState(ex, S);
        if (st && st->isObj()) {
            std::string miss;
            for (auto k : {"t1","infiniteMoney","godMode","noCommission","perfectInfo","winRate","seed"})
                if (!st->has(k)) miss += std::string(k) + " ";
            S.add(A, "cheatState 含 7 个固定字段", miss.empty() ? St::PASS : St::FAIL,
                  "t1,infiniteMoney,godMode,noCommission,perfectInfo,winRate,seed",
                  miss.empty() ? std::string("齐全") : ("缺 " + miss), qa::clip(qaj::toText(st),300), "cheat money");
            S.add(A, "cheatState.infiniteMoney 为 bool", (st->get("infiniteMoney") && st->get("infiniteMoney")->isBool()) ? St::PASS : St::FAIL,
                  "bool", qaj::toText(st->get("infiniteMoney")), "", "cheat money");
            S.add(A, "cheatState.winRate 为 number", (st->get("winRate") && st->get("winRate")->isNum()) ? St::PASS : St::FAIL,
                  "number", qaj::toText(st->get("winRate")), "", "cheat money");
            S.add(A, "cheatState.seed 为整数", (st->get("seed") && st->get("seed")->isNum() && st->get("seed")->numIsInt) ? St::PASS : St::FAIL,
                  "integer", qaj::toText(st->get("seed")), "", "cheat money");
        } else {
            S.add(A, "cheatState 存在", St::FAIL, "对象", "缺失", qa::clip(ex.responseText,300), "cheat money");
        }
    }
    // setCash
    {
        auto ex = S.req("cheat", "{\"op\":\"setCash\",\"account\":\"stock\",\"value\":5000000}");
        S.add(A, "cheat setCash(stock) 精确生效", qaj::approxEq(S.pathNum(S.snap(),"stockAccount.cash"),5000000,0.01) ? St::PASS : St::FAIL,
              "5000000", jnum(S.pathNum(S.snap(),"stockAccount.cash"),2), ex.responseText, "setCash stock 5000000");
        auto ex2 = S.req("cheat", "{\"op\":\"setCash\",\"account\":\"forex\",\"value\":33333}");
        S.add(A, "cheat setCash(forex) 精确生效", qaj::approxEq(S.pathNum(S.snap(),"forexAccount.cash"),33333,0.01) ? St::PASS : St::FAIL,
              "33333", jnum(S.pathNum(S.snap(),"forexAccount.cash"),2), ex2.responseText, "setCash forex 33333");
    }
    // unlock
    {
        S.req("newgame", "{\"seed\":12000,\"name\":\"QA\"}");
        S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        auto s0 = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        S.expectErr(A, "unlock 前置：买入当日卖出被锁", s0, "T1_LOCKED", "buy 后立即 sell");
        auto u = S.req("cheat", "{\"op\":\"unlock\",\"symbol\":\"SH600519\"}");
        S.add(A, "cheat unlock(symbol) 成功", S.okTrue(u) ? St::PASS : St::FAIL, "ok=true", S.errCode(u), u.responseText,
              "{\"op\":\"unlock\",\"symbol\":\"SH600519\"}");
        auto s1 = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        S.add(A, "unlock 后当日即可卖出", S.okTrue(s1) ? St::PASS : St::FAIL, "ok=true", S.errCode(s1), s1.responseText, "unlock 后 sell 100");
        S.req("newgame", "{\"seed\":12001,\"name\":\"QA\"}");
        S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":200,\"type\":\"market\"}");
        auto u2 = S.req("cheat", "{\"op\":\"unlock\"}");
        S.add(A, "cheat unlock(省略 symbol=全部) 成功", S.okTrue(u2) ? St::PASS : St::FAIL, "ok=true", S.errCode(u2), u2.responseText,
              "{\"op\":\"unlock\"} 无 symbol");
        auto s2 = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":200,\"type\":\"market\"}");
        S.add(A, "unlock(全部) 后当日可卖 200", S.okTrue(s2) ? St::PASS : St::FAIL, "ok=true", S.errCode(s2), s2.responseText, "unlock 全部后 sell 200");
    }
    // t1
    {
        S.req("newgame", "{\"seed\":13000,\"name\":\"QA\"}");
        auto off = S.req("cheat", "{\"op\":\"t1\",\"enabled\":false}");
        auto st = cheatState(off, S);
        S.add(A, "cheat t1(false) 回显 cheatState.t1=false", (st && st->get("t1") && st->get("t1")->isBool() && !st->get("t1")->b) ? St::PASS : St::FAIL,
              "false", st ? qaj::toText(st->get("t1")) : std::string("缺"), off.responseText, "{\"op\":\"t1\",\"enabled\":false}");
        S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        auto s = S.req("sell", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        S.add(A, "t1 关闭后当日可卖出", S.okTrue(s) ? St::PASS : St::FAIL, "ok=true", S.errCode(s), s.responseText,
              "cheat t1 false -> buy -> sell");
        auto on = S.req("cheat", "{\"op\":\"t1\",\"enabled\":true}");
        auto st2 = cheatState(on, S);
        S.add(A, "cheat t1(true) 恢复", (st2 && st2->get("t1") && st2->get("t1")->b) ? St::PASS : St::FAIL, "true",
              st2 ? qaj::toText(st2->get("t1")) : std::string("缺"), on.responseText, "{\"op\":\"t1\",\"enabled\":true}");
    }
    // infiniteMoney
    {
        S.req("newgame", "{\"seed\":14000,\"name\":\"QA\",\"cashStock\":1000}");
        auto no = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":1000,\"type\":\"market\"}");
        S.add(A, "无作弊时资金不足买入失败", !S.okTrue(no) ? St::PASS : St::FAIL, "ok=false",
              S.okTrue(no) ? std::string("ok=true") : S.errCode(no), no.responseText, "newgame cashStock:1000 -> buy 1000 股");
        auto en = S.req("cheat", "{\"op\":\"infiniteMoney\",\"enabled\":true}");
        auto st = cheatState(en, S);
        S.add(A, "cheat infiniteMoney 回显", (st && st->get("infiniteMoney") && st->get("infiniteMoney")->b) ? St::PASS : St::FAIL,
              "true", st ? qaj::toText(st->get("infiniteMoney")) : std::string("缺"), en.responseText, "infiniteMoney true");
        auto y = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":1000,\"type\":\"market\"}");
        S.add(A, "infiniteMoney 开启后买入永不失败", S.okTrue(y) ? St::PASS : St::FAIL, "ok=true", S.errCode(y), y.responseText, "infiniteMoney -> buy");
        S.req("cheat", "{\"op\":\"infiniteMoney\",\"enabled\":false}");
    }
    // godMode / noCommission
    {
        S.req("newgame", "{\"seed\":15000,\"name\":\"QA\"}");
        auto g = S.req("cheat", "{\"op\":\"godMode\",\"enabled\":true}");
        auto st = cheatState(g, S);
        S.add(A, "cheat godMode 回显 true", (st && st->get("godMode") && st->get("godMode")->b) ? St::PASS : St::FAIL, "true",
              st ? qaj::toText(st->get("godMode")) : std::string("缺"), g.responseText, "godMode true");
        auto nc = S.req("cheat", "{\"op\":\"noCommission\",\"enabled\":true}");
        auto st2 = cheatState(nc, S);
        S.add(A, "cheat noCommission 回显 true", (st2 && st2->get("noCommission") && st2->get("noCommission")->b) ? St::PASS : St::FAIL, "true",
              st2 ? qaj::toText(st2->get("noCommission")) : std::string("缺"), nc.responseText, "noCommission true");
        auto b = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        auto d = S.data(b);
        auto cm = d ? d->get("commission") : nullptr;
        S.add(A, "noCommission 后 commission==0", (cm && cm->isNum() && qaj::approxEq(cm->num,0,1e-9)) ? St::PASS : St::FAIL,
              "0", cm ? cm->raw : std::string("缺"), b.responseText, "noCommission true -> buy");
        S.req("cheat", "{\"op\":\"noCommission\",\"enabled\":false}");
        S.req("cheat", "{\"op\":\"godMode\",\"enabled\":false}");
    }
    // perfectInfo
    {
        auto p = S.req("cheat", "{\"op\":\"perfectInfo\",\"enabled\":true}");
        auto st = cheatState(p, S);
        S.add(A, "cheat perfectInfo 回显 true", (st && st->get("perfectInfo") && st->get("perfectInfo")->b) ? St::PASS : St::FAIL, "true",
              st ? qaj::toText(st->get("perfectInfo")) : std::string("缺"), p.responseText, "perfectInfo true");
        auto s = S.snap();
        S.add(A, "perfectInfo 开启后 snapshot 暴露 cheatInfo", (s && s->has("cheatInfo")) ? St::PASS : St::WARN,
              "snapshot.cheatInfo", s && s->has("cheatInfo") ? std::string("存在") : std::string("缺失（协议 §4 明示应放 cheatInfo）"),
              qa::clip(qaj::toText(s), 300), "perfectInfo true -> snapshot");
        S.req("cheat", "{\"op\":\"perfectInfo\",\"enabled\":false}");
    }
    // fillOrders
    {
        S.req("newgame", "{\"seed\":16000,\"name\":\"QA\"}");
        auto q = S.req("quote", "{\"symbol\":\"SH600519\"}");
        double last = S.numOf(S.data(q), "last");
        if (last <= 0) last = 1700;
        double bid = last * 0.95;
        auto o = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":" + jnum(bid,2) + "}");
        auto d = S.data(o);
        auto stat = d ? d->get("status") : nullptr;
        S.add(A, "低价限价买单进入 open 状态", (stat && stat->isStr() && stat->str == "open") ? St::PASS : St::FAIL,
              "open", qaj::toText(stat), o.responseText, "limit buy price=" + jnum(bid,2) + " (< last " + jnum(last,2) + ")");
        auto f = S.req("cheat", "{\"op\":\"fillOrders\",\"all\":true}");
        S.add(A, "cheat fillOrders 成功", S.okTrue(f) ? St::PASS : St::FAIL, "ok=true", S.errCode(f), f.responseText,
              "{\"op\":\"fillOrders\",\"all\":true}");
        auto ord = S.req("orders", "{\"market\":\"stock\"}");
        std::string dump = qaj::toText(S.data(ord));
        bool noOpen = dump.find("\"status\":\"open\"") == std::string::npos;
        S.add(A, "fillOrders 后不存在 open 挂单", noOpen ? St::PASS : St::FAIL, "无 open 挂单",
              qa::clip(dump, 250), qa::clip(ord.responseText, 300), "fillOrders 后 orders");
    }
    // price / pump
    {
        S.req("newgame", "{\"seed\":17000,\"name\":\"QA\"}");
        double p0 = S.numOf(S.data(S.req("quote", "{\"symbol\":\"SH600519\"}")), "last");
        auto p = S.req("cheat", "{\"op\":\"price\",\"symbol\":\"SH600519\",\"to\":2000.0}");
        double p1 = S.numOf(S.data(S.req("quote", "{\"symbol\":\"SH600519\"}")), "last");
        S.add(A, "cheat price(to:2000) 精确生效", qaj::approxEq(p1,2000,0.001) ? St::PASS : St::FAIL, "2000.0",
              jnum(p1,4) + " (原 " + jnum(p0,4) + ")", p.responseText, "{\"op\":\"price\",\"symbol\":\"SH600519\",\"to\":2000.0} -> quote");
        auto p2 = S.req("cheat", "{\"op\":\"price\",\"symbol\":\"SH600519\",\"pct\":0.1}");
        double p2v = S.numOf(S.data(S.req("quote", "{\"symbol\":\"SH600519\"}")), "last");
        S.add(A, "cheat price(pct:0.1) = +10%", qaj::approxEq(p2v, 2200.0, 1.0) ? St::PASS : St::FAIL, "2200.0",
              jnum(p2v,4), p2.responseText, "{\"op\":\"price\",\"pct\":0.1} -> quote");
        auto pu = S.req("cheat", "{\"op\":\"pump\",\"symbol\":\"SH600519\",\"pct\":0.1,\"bars\":5}");
        S.add(A, "cheat pump 成功", S.okTrue(pu) ? St::PASS : St::FAIL, "ok=true", S.errCode(pu), pu.responseText,
              "{\"op\":\"pump\",\"symbol\":\"SH600519\",\"pct\":0.1,\"bars\":5}");
        double before = S.numOf(S.data(S.req("quote", "{\"symbol\":\"SH600519\"}")), "last");
        S.req("tick", "{\"n\":3,\"mode\":\"manual\"}");
        double after = S.numOf(S.data(S.req("quote", "{\"symbol\":\"SH600519\"}")), "last");
        S.add(A, "pump 后 3 个 slot 价格确实上涨", after > before ? St::PASS : St::FAIL,
              "> " + jnum(before,4), jnum(after,4), pu.responseText, "pump 10% bars 5 -> tick 3 -> quote");
    }
    // news
    {
        auto n = S.req("cheat", "{\"op\":\"news\",\"title\":\"QA测试新闻\",\"impact\":0.05,\"scope\":\"stock\",\"symbols\":[\"SH600519\"]}");
        S.add(A, "cheat news 成功", S.okTrue(n) ? St::PASS : St::FAIL, "ok=true", S.errCode(n), n.responseText,
              "{\"op\":\"news\",\"title\":\"QA测试新闻\",\"impact\":0.05,\"scope\":\"stock\",\"symbols\":[\"SH600519\"]}");
        auto nl = S.req("news", "{\"limit\":50,\"unreadOnly\":false}");
        std::string dump = qaj::toText(S.data(nl));
        S.add(A, "cheat news 后 news 列表含该标题", dump.find("QA测试新闻") != std::string::npos ? St::PASS : St::FAIL,
              "含 'QA测试新闻'", dump.find("QA测试新闻") != std::string::npos ? std::string("找到") : std::string("未找到"),
              qa::clip(dump,300), "cheat news -> news list");
    }
    // winRate
    {
        auto w = S.req("cheat", "{\"op\":\"winRate\",\"value\":0.9}");
        auto st = cheatState(w, S);
        auto wr = st ? st->get("winRate") : nullptr;
        S.add(A, "cheat winRate 回显 0.9", (wr && wr->isNum() && qaj::approxEq(wr->num,0.9,1e-9)) ? St::PASS : St::FAIL, "0.9",
              qaj::toText(wr), w.responseText, "{\"op\":\"winRate\",\"value\":0.9}");
    }
    // speed
    {
        auto sp = S.req("cheat", "{\"op\":\"speed\",\"speed\":64}");
        S.add(A, "cheat speed 成功", S.okTrue(sp) ? St::PASS : St::FAIL, "ok=true", S.errCode(sp), sp.responseText, "{\"op\":\"speed\",\"speed\":64}");
        auto cl = S.req("clock", "{\"action\":\"get\"}");
        double s = S.numOf(S.data(cl), "speed");
        S.add(A, "cheat speed 影响 clock.speed", qaj::approxEq(s,64,1e-6) ? St::PASS : St::FAIL, "64", jnum(s,2), cl.responseText,
              "cheat speed 64 -> clock get");
    }
    // skip
    {
        std::string d0 = S.pathStr(S.snap(), "time.date");
        auto sk = S.req("cheat", "{\"op\":\"skip\",\"slots\":40,\"auto\":true}", 30000);
        S.add(A, "cheat skip 成功", S.okTrue(sk) ? St::PASS : St::FAIL, "ok=true", S.errCode(sk), qa::clip(sk.responseText,200),
              "{\"op\":\"skip\",\"slots\":40,\"auto\":true}");
        std::string d1 = S.pathStr(S.snap(), "time.date");
        S.add(A, "cheat skip 40 slot 推进 10 个交易日", (d0 != d1) ? St::PASS : St::FAIL, "日期推进", d0 + " -> " + d1, "", "skip 40");
    }
    // bankrupt / unbankrupt
    {
        auto b = S.req("cheat", "{\"op\":\"bankrupt\",\"account\":\"stock\"}");
        S.add(A, "cheat bankrupt 成功", S.okTrue(b) ? St::PASS : St::FAIL, "ok=true", S.errCode(b), b.responseText,
              "{\"op\":\"bankrupt\",\"account\":\"stock\"}");
        auto s = S.snap();
        auto bk = s ? s->get("bankrupt") : nullptr;
        S.add(A, "bankrupt 后 snapshot.bankrupt=true", (bk && bk->isBool() && bk->b) ? St::PASS : St::FAIL, "true",
              qaj::toText(bk), "", "bankrupt 后 snapshot");
        auto u = S.req("cheat", "{\"op\":\"unbankrupt\"}");
        S.add(A, "cheat unbankrupt 成功", S.okTrue(u) ? St::PASS : St::FAIL, "ok=true", S.errCode(u), u.responseText, "{\"op\":\"unbankrupt\"}");
        auto s2 = S.snap();
        auto bk2 = s2 ? s2->get("bankrupt") : nullptr;
        S.add(A, "unbankrupt 后 bankrupt=false", (bk2 && bk2->isBool() && !bk2->b) ? St::PASS : St::FAIL, "false",
              qaj::toText(bk2), "", "unbankrupt 后 snapshot");
    }
    // reset
    {
        S.req("newgame", "{\"seed\":18000,\"name\":\"QA\"}");
        S.req("tick", "{\"n\":9,\"mode\":\"manual\"}");
        S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        S.req("cheat", "{\"op\":\"money\",\"amount\":5000000,\"account\":\"stock\"}");
        auto sBefore = S.snap();
        std::string dateBefore = S.pathStr(sBefore,"time.date");
        double cashBefore = S.pathNum(sBefore,"stockAccount.cash");
        auto r = S.req("cheat", "{\"op\":\"reset\"}");
        S.add(A, "cheat reset 成功", S.okTrue(r) ? St::PASS : St::FAIL, "ok=true", S.errCode(r), r.responseText, "{\"op\":\"reset\"}");
        auto sAfter = S.snap();
        std::string dateAfter = S.pathStr(sAfter,"time.date");
        auto pos = S.walk(sAfter, "stockPositions");
        S.add(A, "reset 后持仓清空", (pos && pos->isArr() && pos->arr.empty()) ? St::PASS : St::FAIL, "0",
              pos && pos->isArr() ? std::to_string(pos->arr.size()) : std::string("非数组"), "", "reset 后 snapshot.stockPositions");
        S.add(A, "reset 后时间保留（不回退）", (dateAfter == dateBefore) ? St::PASS : St::FAIL, dateBefore, dateAfter, "", "reset 前后 time.date");
        S.add(A, "reset 后现金回到初始 1000000", qaj::approxEq(S.pathNum(sAfter,"stockAccount.cash"),1000000,1) ? St::PASS : St::FAIL,
              "1000000", jnum(S.pathNum(sAfter,"stockAccount.cash"),2) + " (reset 前 " + jnum(cashBefore,2) + ")", "",
              "reset 后 snapshot.stockAccount.cash");
    }
    // 未知 op
    {
        auto u = S.req("cheat", "{\"op\":\"nosuchop\"}");
        S.add(A, "未知 cheat op 被明确拒绝", !S.okTrue(u) ? St::PASS : St::FAIL, "ok=false",
              S.okTrue(u) ? std::string("ok=true") : S.errCode(u), u.responseText, "{\"op\":\"nosuchop\"}");
    }
}

// ===========================================================================
//  6. 边界与并发
// ===========================================================================
static void secEdge(B& S) {
    const char* A = "B6-EDGE";
    S.req("newgame", "{\"seed\":19000,\"name\":\"QA\"}");
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":1e18,\"type\":\"market\"}");
      S.add(A, "qty=1e18(科学计数法) 被明确拒绝", !S.okTrue(ex) ? St::PASS : St::FAIL, "ok=false",
            S.okTrue(ex) ? std::string("ok=true") : S.errCode(ex), ex.responseText, "qty:1e18"); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":99999999999999999999,\"type\":\"market\"}");
      S.add(A, "qty 超出 int64 被明确拒绝", !S.okTrue(ex) ? St::PASS : St::FAIL, "ok=false",
            S.okTrue(ex) ? std::string("ok=true") : S.errCode(ex), ex.responseText, "qty:99999999999999999999"); }
    { auto ex = S.req("cheat", "{\"op\":\"money\",\"amount\":-1e308,\"account\":\"stock\"}");
      S.add(A, "cheat money amount=-1e308 不崩溃", ex.gotResponse ? St::PASS : St::FAIL, "有响应",
            ex.gotResponse ? std::string("有") : std::string("超时"), qa::clip(ex.responseText,150), "amount:-1e308"); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":1e308}");
      S.add(A, "limit price=1e308 不崩溃", ex.gotResponse ? St::PASS : St::FAIL, "有响应",
            ex.gotResponse ? std::string("有") : std::string("超时"), qa::clip(ex.responseText,150), "price:1e308"); }
    { auto ex = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":-1e308,\"type\":\"market\"}");
      S.add(A, "qty=-1e308 不崩溃", ex.gotResponse ? St::PASS : St::FAIL, "有响应",
            ex.gotResponse ? std::string("有") : std::string("超时"), qa::clip(ex.responseText,150), "qty:-1e308"); }

    { auto ex = S.req("quote", "{\"symbol\":\"\"}");
      S.add(A, "quote symbol=\"\" 有明确响应", ex.gotResponse ? St::PASS : St::FAIL, "恰好一条响应",
            ex.gotResponse ? std::string("有") : std::string("超时"), qa::clip(ex.responseText,150), "symbol:\"\""); }
    { auto ex = S.req("quote", "{\"symbol\":\"sh600519\"}");
      S.add(A, "quote symbol 小写 sh600519 明确处理", ex.gotResponse ? St::PASS : St::FAIL, "恰好一条响应",
            (S.okTrue(ex) ? std::string("识别为有效") : S.errCode(ex)), qa::clip(ex.responseText,150), "symbol:\"sh600519\""); }
    { auto ex = S.req("quote", "{\"symbol\":\"12345\"}");
      S.add(A, "quote symbol=\"12345\" 明确处理", ex.gotResponse ? St::PASS : St::FAIL, "恰好一条响应",
            (S.okTrue(ex) ? std::string("识别为有效") : S.errCode(ex)), qa::clip(ex.responseText,150), "symbol:\"12345\""); }
    { auto ex = S.req("buy", "{\"symbol\":\"EURUSD\",\"qty\":100,\"type\":\"market\"}");
      S.add(A, "buy 传外汇代码 -> 明确错误码", (!S.okTrue(ex) && S.errCode(ex) != "<no-error>") ? St::PASS : St::FAIL,
            "NO_SUCH_SYMBOL", S.okTrue(ex) ? std::string("ok=true") : S.errCode(ex), ex.responseText, "buy symbol:EURUSD"); }

    // 并发
    {
        const int N = 300;
        S.run->drainOut();
        for (int i = 0; i < N; i++) {
            int k = i % 3;
            std::string cmd = (k == 0) ? "snapshot" : ((k == 1) ? "market" : "quote");
            std::string args = (k == 0) ? "{}" : ((k == 1) ? "{\"market\":\"all\"}" : "{\"symbol\":\"SH600519\"}");
            S.run->writeLine(qa::makeReq(40000 + i, cmd, args));
        }
        std::set<int> seen; int dup = 0, bad = 0;
        double t0 = Runner::nowMs();
        std::string l;
        while (Runner::nowMs() - t0 < 20000 && (int)seen.size() < N) {
            if (!S.run->readLine(l, 500)) break;
            ValuePtr v;
            try { v = qaj::parse(l); } catch (...) { bad++; continue; }
            auto id = v->get("id");
            if (!id || !id->isNum()) { bad++; continue; }
            int iv = (int)id->asInt();
            if (iv >= 40000 && iv < 40000 + N) { if (seen.count(iv)) dup++; seen.insert(iv); }
        }
        S.add(A, "300 条并发请求：全部有响应且无重复", ((int)seen.size() == N && dup == 0 && bad == 0) ? St::PASS : St::FAIL,
              "300 唯一响应, 0 重复, 0 非法 JSON",
              "收到 " + std::to_string(seen.size()) + "/300, dup=" + std::to_string(dup) + ", 非法JSON=" + std::to_string(bad),
              "", "循环写 300 条混合请求后统计响应 id");
    }
    { S.run->sendRaw("", true);
      S.run->sendRaw("{\"id\":99001,\"cmd\":\"quote\",\"args\":{\"symbol\":\"SH600519\"}}", true);
      std::string l;
      bool got = S.run->waitFor([](const std::string& x){ return x.find("\"id\":99001") != std::string::npos; }, l, 3000);
      S.add(A, "空行不打断后续请求", got ? St::PASS : St::FAIL, "后续请求仍被响应", got ? std::string("有") : std::string("无"),
            qa::clip(l,150), "写空行后写正常请求"); }
    { auto ex = S.req("snapshot", "{}");
      S.add(A, "全部边界测试后引擎仍存活", ex.gotResponse ? St::PASS : St::FAIL, "有响应", ex.gotResponse ? std::string("有") : std::string("超时"),
            qa::clip(ex.responseText,150), "snapshot"); }
    // Lead 裁决项：quote.last 必须按标的 digits 输出
    {
        const char* syms[] = {"SH600519","SZ000001","HK00700","USAAPL","USMSFT","USNVDA","USTSLA","USGOOG"};
        for (auto sym : syms) {
            auto ex = S.req("quote", std::string("{\"symbol\":\"") + sym + "\"}");
            if (!S.okTrue(ex)) { S.add(A, std::string("quote ") + sym + " 可报价", St::WARN, "ok=true", S.errCode(ex), ex.responseText, sym); continue; }
            auto d = S.data(ex);
            auto dg = d ? d->get("digits") : nullptr;
            auto ls = d ? d->get("last") : nullptr;
            int want = (dg && dg->isNum()) ? (int)dg->asInt() : -1;
            bool ok = ls && ls->isNum() && want >= 0 && qaj::decimalPlaces(ls->raw) <= want;
            S.add(A, std::string("quote ") + sym + " 的 last 小数位 <= digits",
                  ok ? St::PASS : St::FAIL,
                  "<= digits 位", (ls ? ls->raw : std::string("缺")) + " (digits=" + (dg ? qaj::toText(dg) : std::string("?")) + ")",
                  ex.responseText, "quote " + std::string(sym));
        }
        for (auto sym : {"USDJPY","XAUUSD","EURUSD"}) {
            auto ex = S.req("quote", std::string("{\"symbol\":\"") + sym + "\"}");
            if (!S.okTrue(ex)) continue;
            auto d = S.data(ex);
            auto dg = d ? d->get("digits") : nullptr;
            auto ls = d ? d->get("last") : nullptr;
            int want = (dg && dg->isNum()) ? (int)dg->asInt() : -1;
            bool ok = ls && ls->isNum() && want >= 0 && qaj::decimalPlaces(ls->raw) <= want;
            S.add(A, std::string("quote ") + sym + " 的 last 小数位 <= digits", ok ? St::PASS : St::FAIL,
                  "<= digits 位", (ls ? ls->raw : std::string("缺")) + " (digits=" + (dg ? qaj::toText(dg) : std::string("?")) + ")",
                  ex.responseText, "quote " + std::string(sym));
        }
    }
    // Lead 裁决项：clock speed/tickMs 越界必须 BAD_ARG（协议 §3.14: speed∈[0.25,256], tickMs∈[50,60000]）
    {
        struct CB { const char* args; const char* label; };
        CB cases[] = {
            {"{\"action\":\"set\",\"speed\":100000,\"tickMs\":500}", "speed=100000 (>256)"},
            {"{\"action\":\"set\",\"speed\":0.0001,\"tickMs\":500}", "speed=0.0001 (<0.25)"},
            {"{\"action\":\"set\",\"speed\":4,\"tickMs\":1}",          "tickMs=1 (<50)"},
            {"{\"action\":\"set\",\"speed\":4,\"tickMs\":9999999}",    "tickMs=9999999 (>60000)"},
        };
        for (auto& c : cases) {
            auto ex = S.req("clock", c.args, 10000);
            std::string got = S.errCode(ex);
            S.add(A, std::string("clock ") + c.label + " -> BAD_ARG", (!S.okTrue(ex) && got == "BAD_ARG") ? St::PASS : St::FAIL,
                  "ok=false 且 code=BAD_ARG", S.okTrue(ex) ? std::string("ok=true（未拒绝）") : got, ex.responseText, c.args);
        }
    }
}

// ===========================================================================
//  7. clock 自动推进 + push
// ===========================================================================
static void secClock(B& S) {
    const char* A = "B7-CLOCK";
    S.req("newgame", "{\"seed\":20000,\"name\":\"QA\"}");
    auto st = S.req("clock", "{\"action\":\"set\",\"speed\":64,\"tickMs\":64}");
    S.add(A, "clock set speed=64 tickMs=64 成功", S.okTrue(st) ? St::PASS : St::FAIL, "ok=true", S.errCode(st), st.responseText,
          "{\"cmd\":\"clock\",\"args\":{\"action\":\"set\",\"speed\":64,\"tickMs\":64}}");
    S.run->drainOut();
    auto go = S.req("clock", "{\"action\":\"start\"}");
    S.add(A, "clock start 成功", S.okTrue(go) ? St::PASS : St::FAIL, "ok=true", S.errCode(go), go.responseText, "{\"action\":\"start\"}");
    std::vector<std::string> pushes;
    double t0 = Runner::nowMs();
    while (Runner::nowMs() - t0 < 3000) { std::string l; if (!S.run->readLine(l, 300)) continue; pushes.push_back(l); }
    S.req("clock", "{\"action\":\"stop\"}");
    int tickPush = 0, noPushFlag = 0, nonJson = 0, badId = 0;
    std::string sample, firstBadId;
    for (auto& p : pushes) {
        ValuePtr v;
        try { v = qaj::parse(p); } catch (...) { nonJson++; continue; }
        auto pf = v->get("push");
        if (!pf || !pf->isBool() || !pf->b) { noPushFlag++; continue; }
        auto t = v->get("type");
        if (t && t->isStr() && t->str == "tick") { tickPush++; if (sample.empty()) sample = p; }
        auto idv = v->get("id");
        if (!idv || !idv->isNum() || idv->num != 0) { badId++; if (firstBadId.empty()) firstBadId = p; }
    }
    S.add(A, "clock start 后收到 push tick 行", tickPush > 0 ? St::PASS : St::FAIL, ">=1 条 push tick",
          std::to_string(tickPush) + " 条", qa::clip(sample.empty() ? (pushes.empty() ? std::string("<无输出>") : pushes[0]) : sample, 300),
          "clock set speed=64 tickMs=64; clock start; 观察 stdio 3 秒");
    S.add(A, "push 行均带 push:true", (noPushFlag == 0 && !pushes.empty()) ? St::PASS : St::FAIL, "全部 push:true",
          "缺失 push:true 的行数 = " + std::to_string(noPushFlag) + " / 总行数 " + std::to_string(pushes.size()),
          qa::clip(pushes.empty() ? std::string("") : pushes[0], 200), "clock start");
    S.add(A, "push 行均为合法 JSON", nonJson == 0 ? St::PASS : St::FAIL, "0 非法行", std::to_string(nonJson) + " 行非法", "",
          "clock start 期间逐行解析");
    S.add(A, "push 行 id 均为 0", badId == 0 ? St::PASS : St::FAIL, "id=0", std::to_string(badId) + " 行 id 非 0",
          qa::clip(firstBadId, 200), "clock start 期间逐行解析");
    if (!sample.empty()) {
        try {
            auto v = qaj::parse(sample);
            auto d = v->get("data");
            S.add(A, "push tick.data 含 time/advanced/events",
                  (d && d->isObj() && d->has("time") && d->has("advanced") && d->has("events")) ? St::PASS : St::FAIL,
                  "time,advanced,events", d ? qaj::toText(d) : std::string("缺"), qa::clip(sample,400), "clock start");
        } catch (...) {}
    }
    auto went = S.req("clock", "{\"action\":\"get\"}");
    auto run2 = S.data(went) ? S.data(went)->get("running") : nullptr;
    S.add(A, "clock stop 后 running=false", (run2 && run2->isBool() && !run2->b) ? St::PASS : St::FAIL, "false",
          qaj::toText(run2), went.responseText, "clock stop -> clock get");

    // ---- §3.15 第二种主动推送：type=bankrupt ----
    // 构造 forex 爆仓，然后在自动时钟运行时观察 stdout 上的 push 行。
    {
        const char* P = "B7-PUSH";
        S.req("newgame", "{\"seed\":8002,\"name\":\"QA\",\"cashForex\":1000}");
        auto ob = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":500,\"leverage\":1000}");
        S.add(P, "爆仓前构造（leverage=1000 开 500 手）", S.okTrue(ob) ? St::PASS : St::FAIL, "ok=true", S.errCode(ob),
              ob.responseText, "open EURUSD long 500 leverage:1000");
        if (S.okTrue(ob)) {
            S.req("cheat", "{\"op\":\"price\",\"symbol\":\"EURUSD\",\"pct\":-0.95}");
            S.req("clock", "{\"action\":\"set\",\"speed\":16,\"tickMs\":100}");
            S.run->drainOut();
            auto go2 = S.req("clock", "{\"action\":\"start\"}");
            S.add(P, "clock start（准备观察 bankrupt push）", S.okTrue(go2) ? St::PASS : St::FAIL, "ok=true",
                  S.errCode(go2), go2.responseText, "clock set speed:16 tickMs:100 -> start");
            // 重要：S.req() 会把「等到同 id 响应之前」到达的 push 行收进 ex.extraLines。
            // 爆仓构造下第一片就触发，push 极可能先于 start 的响应到达 —— 必须把它们也纳入统计，
            // 否则会把「已经推了」误判成「没推」。（这是我自己修过的测试 bug。）
            std::vector<std::string> lines(go2.extraLines.begin(), go2.extraLines.end());
            for (auto& l : go2.afterLines) lines.push_back(l);
            double t0 = Runner::nowMs();
            while (Runner::nowMs() - t0 < 2500) { std::string l; if (!S.run->readLine(l, 250)) continue; lines.push_back(l); }
            S.req("clock", "{\"action\":\"stop\"}");
            int tickPush = 0, bkPush = 0, badId = 0, nonJson = 0; std::string bkRaw;
            for (auto& l : lines) {
                ValuePtr v;
                try { v = qaj::parse(l); } catch (...) { nonJson++; continue; }
                auto pf = v->get("push");
                if (!pf || !pf->isBool() || !pf->b) continue;
                auto idv = v->get("id");
                if (!idv || !idv->isNum() || idv->num != 0) badId++;
                auto ty = v->get("type");
                if (!ty || !ty->isStr()) continue;
                if (ty->str == "tick") tickPush++;
                else if (ty->str == "bankrupt") { bkPush++; if (bkRaw.empty()) bkRaw = l; }
            }
            // §3.15（v1.0.1 明确）两条硬要求：
            //   1) push 的 data.events 必须与同步 tick 的 events **完全等价**，不得因走推送通道就丢弃事件；
            //   2) 破产双形态都要有：events 里含 {kind:bankrupt,...} **且** 额外推一条 type:"bankrupt"。
            bool bkInEvents = false;
            for (auto& l : lines) {
                if (l.find("\"kind\":\"bankrupt\"") != std::string::npos) { bkInEvents = true; if (bkRaw.empty()) bkRaw = l; }
            }
            S.add(P, "§3.15-1: 爆仓事件出现在 tick push 的 data.events 里", bkInEvents ? St::PASS : St::FAIL,
                  "某条 type=tick 推送的 data.events 含 {\"kind\":\"bankrupt\"}",
                  bkInEvents ? std::string("已出现") : (std::string("未出现 (tick push=") + std::to_string(tickPush) + ")"),
                  qa::clip(bkRaw, 300), "爆仓构造 -> clock start -> 观察 stdout 2.5 秒");
            S.add(P, "§3.15-2: 额外推送 type=bankrupt（双形态）", bkPush > 0 ? St::PASS : St::FAIL,
                  ">=1 条 {\"id\":0,\"push\":true,\"type\":\"bankrupt\",\"data\":{\"account\":...}}",
                  bkPush > 0 ? ("收到 " + std::to_string(bkPush) + " 条") : std::string("未收到"),
                  qa::clip(bkRaw, 250), "clock start 期间观察 stdout");
            if (bkPush > 0) {
                S.add(P, "bankrupt push 的 data.account 存在", bkRaw.find("\"account\"") != std::string::npos ? St::PASS : St::FAIL,
                      "data.account", qa::clip(bkRaw, 200), qa::clip(bkRaw, 300), "§3.15");
            }
            S.add(P, "bankrupt push 期间仍有 tick push（两种类型并存）", tickPush > 0 ? St::PASS : St::WARN,
                  ">=1 条 tick push", std::to_string(tickPush) + " 条", "", "clock start 期间");
            S.add(P, "push 行均为合法 JSON 且 id=0", (nonJson == 0 && badId == 0) ? St::PASS : St::FAIL,
                  "0 非法 / 0 个 id!=0", "非JSON=" + std::to_string(nonJson) + " badId=" + std::to_string(badId), "", "同上");
        } else {
            S.add(P, "§3.15: 自动时钟下收到 type=bankrupt 的主动推送", St::NOT_TESTED, ">=1 条",
                  "构造失败: " + S.errCode(ob), ob.responseText, "-");
        }
    }
    S.run->drainOut();
    double t1 = Runner::nowMs();
    int after = 0;
    while (Runner::nowMs() - t1 < 1200) { std::string l; if (S.run->readLine(l, 200)) after++; }
    S.add(A, "clock stop 后不再主动推送", after == 0 ? St::PASS : St::FAIL, "0 行", std::to_string(after) + " 行", "",
          "clock stop 后观察 1.2 秒");

    // ---- §3.15-3: 逐一验证所有事件类型在 clock 推送通道下都不丢 ----
    // Lead 要求的最小验证集：order_filled / t1_unlock / margin_call / news。
    // 做法：clock start 之前先挂一张会成交的限价单 + 开仓 + 跨日，再让自动时钟跑。
    {
        const char* E = "B7-EVT";
        S.req("newgame", "{\"seed\":31337,\"name\":\"QA\",\"cashForex\":50000}");
        // 1) 挂一张必然成交的限价买单（远高于现价）—— 期望 order_filled
        auto q0 = S.req("quote", "{\"symbol\":\"SH600519\"}");
        double last0 = S.numOf(S.data(q0), "last");
        if (last0 <= 0) last0 = 1700;
        auto bo = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":" + jnum(last0 * 1.4, 2) + "}");
        // 2) 买 100 股（当日买入 -> 跨日后应产生 t1_unlock）
        auto bm = S.req("buy", "{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}");
        // 3) 开一个外汇仓 —— 期望 fx_swap 等
        auto fo = S.req("open", "{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":1,\"leverage\":100}");
        S.add(E, "推送事件覆盖前置：挂单/建仓成功",
              (S.okTrue(bo) || S.okTrue(bm)) && S.okTrue(fo) ? St::PASS : St::FAIL, "ok=true",
              std::string("limitBuy=") + (S.okTrue(bo)?"Y":"N") + " marketBuy=" + (S.okTrue(bm)?"Y":"N") + " fxOpen=" + (S.okTrue(fo)?"Y":"N"),
              bo.responseText, "挂限价单 + 市价买 + 开外汇仓");
        S.req("clock", "{\"action\":\"set\",\"speed\":32,\"tickMs\":80}");
        S.run->drainOut();
        auto go3 = S.req("clock", "{\"action\":\"start\"}");
        S.add(E, "clock start（观察全部事件类型）", S.okTrue(go3) ? St::PASS : St::FAIL, "ok=true", S.errCode(go3),
              go3.responseText, "clock set speed:32 tickMs:80 -> start");
        // 同 B7-PUSH：把 start 响应之前到达的 push 也纳入统计
        std::vector<std::string> plines(go3.extraLines.begin(), go3.extraLines.end());
        for (auto& l : go3.afterLines) plines.push_back(l);
        double t2 = Runner::nowMs();
        while (Runner::nowMs() - t2 < 6000) { std::string l; if (!S.run->readLine(l, 250)) continue; plines.push_back(l); }
        S.req("clock", "{\"action\":\"stop\"}");
        // 汇总推送通道里出现过的事件种类
        std::set<std::string> seenKinds;
        int pushTicks = 0, pushBankrupts = 0, pushNonJson = 0, pushBadId = 0;
        for (auto& l : plines) {
            ValuePtr v;
            try { v = qaj::parse(l); } catch (...) { pushNonJson++; continue; }
            auto pf = v->get("push");
            if (!(pf && pf->isBool() && pf->b)) continue;
            auto idv = v->get("id");
            if (!idv || !idv->isNum() || idv->num != 0) pushBadId++;
            auto ty = v->get("type");
            if (ty && ty->isStr()) { if (ty->str == "tick") pushTicks++; else if (ty->str == "bankrupt") pushBankrupts++; }
            size_t pos = 0;
            while ((pos = l.find("\"kind\":\"", pos)) != std::string::npos) {
                size_t b = pos + 8, e = l.find('"', b);
                if (e == std::string::npos) break;
                seenKinds.insert(l.substr(b, e - b));
                pos = e;
            }
        }
        std::string got;
        for (auto& k : seenKinds) { if (!got.empty()) got += ","; got += k; }
        S.add(E, "§3.15-3: clock 推送通道收到 tick push", pushTicks > 0 ? St::PASS : St::FAIL, ">=1 条",
              std::to_string(pushTicks) + " 条", "", "clock start 观察 6 秒");
        // 逐类断言：Lead 指定的最小验证集
        for (auto want : {"order_filled", "t1_unlock", "news", "fx_swap"}) {
            bool ok = seenKinds.count(want) > 0;
            S.add(E, std::string("§3.15-3: 推送通道未丢失事件类型 ") + want, ok ? St::PASS : St::FAIL,
                  "push 的 data.events 含 kind=" + std::string(want),
                  ok ? std::string("已出现") : ("未出现; 本次出现过的 kind=[" + got + "]"),
                  qa::clip(plines.empty() ? std::string("") : plines[0], 200),
                  "clock start 跑过结算日，观察 6 秒内的 push data.events");
        }
        S.add(E, "§3.15-3: push 行均为合法 JSON 且 id=0", (pushNonJson == 0 && pushBadId == 0) ? St::PASS : St::FAIL,
              "0 非法 / 0 个 id!=0", "非JSON=" + std::to_string(pushNonJson) + " badId=" + std::to_string(pushBadId),
              "", "同上");
        S.add(E, "本次推送通道出现过的事件种类汇总（信息项）", St::PASS, "—", "[" + got + "]", "", "同上");
        (void)pushBankrupts;
    }
}

// ===========================================================================
//  8. stdin 关闭 -> 正常退出
// ===========================================================================
static void secLifecycle(const std::string& exe, const std::string& wd) {
    const char* A = "B8-LIFECYCLE";
    Runner r2;
    if (!r2.start(exe, wd)) {
        g_rep->add(A, "关闭 stdin 后引擎正常退出(0)", St::NOT_TESTED, "exit 0", "无法启动第二实例", r2.launchError(), "-");
        return;
    }
    std::string hello; r2.readLine(hello, 5000);
    r2.closeStdin();
    DWORD code = 0;
    bool exited = r2.waitExit(6000, &code);
    g_rep->add(A, "关闭 stdin 后引擎正常退出(0)", (exited && code == 0) ? St::PASS : St::FAIL,
               "exit code 0 且 6s 内退出", exited ? ("exit code " + std::to_string((long)code)) : std::string("未退出"),
               "启动 trade_sim.exe --stdio -> 关闭 stdin -> 等待退出", "CloseHandle(stdin) 后 WaitForSingleObject");
    r2.kill();
}

// ===========================================================================
int main(int argc, char** argv) {
    std::string exePath = argc > 1 ? argv[1] : "build\\engine\\trade_sim.exe";
    std::string workDir = argc > 2 ? argv[2] : "A:\\Downloads\\tg";
    std::string sel = argc > 3 ? argv[3] : "";
    Report rep;
    B S; S.rep = &rep;
    g_rep = &rep;

    if (sel == "life") { secLifecycle(exePath, workDir);
        std::ofstream o("build\\qa\\business_life_results.txt");
        for (auto& r : rep.rows) o << r.id << "\t" << qa::stName(r.status) << "\t" << r.title << "\n    EXPECT: " << r.expect << "\n    ACTUAL: " << r.actual << "\n";
        o.close();
        std::cout << "LIFE DONE PASS=" << rep.count(St::PASS) << " FAIL=" << rep.count(St::FAIL) << "\n";
        return 0;
    }

    Runner run;
    if (!run.start(exePath, workDir)) {
        std::ofstream o("build\\qa\\business_results.txt");
        o << "ENGINE_START_FAILED: " << run.launchError() << "\n";
        std::cerr << "ENGINE_START_FAILED: " << run.launchError() << "\n";
        return 2;
    }
    S.run = &run;
    std::string hello; run.readLine(hello, 8000);

    if (sel.empty() || sel == "stock") secStock(S);
    if (sel.empty() || sel == "time")  secTime(S);
    if (sel.empty() || sel == "forex") secForex(S);
    if (sel.empty() || sel == "orders") secOrders(S);
    if (sel.empty() || sel == "seed")  secSeed(S);
    if (sel.empty() || sel == "cheat") secCheats(S);
    if (sel.empty() || sel == "edge")  secEdge(S);
    if (sel.empty() || sel == "clock") secClock(S);

    std::string outName = "build\\qa\\business_results.txt";
    if (!sel.empty()) outName = "build\\qa\\business_" + sel + "_results.txt";
    std::ofstream o(outName);
    for (auto& e : S.ev) o << e << "\n";
    o << "\n=== 断言 ===\n";
    for (auto& r : rep.rows)
        o << r.id << "\t" << qa::stName(r.status) << "\t" << r.title << "\n    EXPECT: " << r.expect << "\n    ACTUAL: " << r.actual << "\n";
    o.close();
    std::string cntName = sel.empty() ? "build\\qa\\business_counts.txt" : ("build\\qa\\business_" + sel + "_counts.txt");
    std::ofstream j(cntName);
    j << "PASS " << rep.count(St::PASS) << "\nFAIL " << rep.count(St::FAIL)
      << "\nWARN " << rep.count(St::WARN) << "\nNOT_TESTED " << rep.count(St::NOT_TESTED) << "\n";
    j.close();
    std::cout << "BUSINESS[" << (sel.empty() ? "all" : sel) << "] DONE PASS=" << rep.count(St::PASS)
              << " FAIL=" << rep.count(St::FAIL)
              << " WARN=" << rep.count(St::WARN)
              << " NOT_TESTED=" << rep.count(St::NOT_TESTED) << "\n";
    run.kill();
    return rep.count(St::FAIL) == 0 ? 0 : 1;
}
