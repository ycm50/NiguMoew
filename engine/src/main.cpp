// main.cpp - 入口：argv 解析 + stdio 主循环 + --selftest + --repl + 顶层 try/catch
#include "json.hpp"
#include "jsonutil.hpp"
#include "util.hpp"
#include "engine.hpp"
#include "clock.hpp"

#include <iostream>
#include <string>
#include <mutex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <vector>
#include <chrono>
#include <cmath>
#include <thread>

namespace {

std::mutex g_outMutex;

// stdout 只允许出现协议 JSON 行：单行、UTF-8、无内嵌换行
// stdout 唯一出口：只允许协议 JSON 行（单行、无内嵌换行）。
// 日志一律走 stderr；绝不做 freopen/dup2 之类把 stderr 并到 stdout 的操作。
void writeLine(const std::string& line) {
    std::string clean;
    clean.reserve(line.size() + 1);
    for (char c : line) {
        if (c == '\n' || c == '\r') continue;
        clean.push_back(c);
    }
    std::lock_guard<std::mutex> lk(g_outMutex);
    std::fwrite(clean.data(), 1, clean.size(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
    std::cout.flush();
}

void logLine(const std::string& s) {
    std::fputs(s.c_str(), stderr);
    std::fputc('\n', stderr);
    std::fflush(stderr);
}

// 说明：引擎不调用任何控制台 API（AllocConsole/AttachConsole/SetConsoleCP/
//       CreatePseudoConsole 等一律不用），避免 GUI 启动时弹出黑窗口。
//       源文件本身是 UTF-8，stdin/stdout 按字节读写，前端按 UTF-8 解码。
void setupConsoleUtf8() {}

std::string trimCr(const std::string& s) {
    std::string r = s;
    while (!r.empty() && (r.back() == '\r' || r.back() == '\n')) r.pop_back();
    return r;
}

std::string respond(const std::string& idRaw, tsim::Engine::Result& r) {
    std::string s;
    s += "{\"id\":";
    s += idRaw.empty() ? "0" : idRaw;
    s += ",\"ok\":";
    s += (r.ok ? "true" : "false");
    s += ",";
    if (r.ok) {
        s += "\"data\":";
        s += r.data.dump();
    } else {
        s += "\"error\":";
        s += r.err.dump();
    }
    s += "}";
    return s;
}

bool handleLine(tsim::Engine& eng, const std::string& line, bool* quitFlag) {
    std::string L = trimCr(line);
    // 空行/纯空白行直接忽略：不产生任何响应（协议要求"每个请求恰好一个响应"）
    {
        bool blank = true;
        for (char c : L) {
            if (c != ' ' && c != '\t') { blank = false; break; }
        }
        if (blank) return false;
    }

    tsim::Json req;
    if (!tsim::jsonParse(L, req)) {
        tsim::Engine::Result r;
        r.ok = false;
        r.err = tsim::errData(tsim::err::BAD_ARG, "无法解析的 JSON 请求");
        writeLine(respond("0", r));
        return false;
    }
    if (!req.isObj()) {
        tsim::Engine::Result r;
        r.ok = false;
        r.err = tsim::errData(tsim::err::BAD_ARG, "请求必须是 JSON 对象");
        writeLine(respond("0", r));
        return false;
    }

    std::string idRaw;
    const tsim::Json* idJ = req.find("id");
    if (idJ && idJ->isNum()) {
        double d = idJ->asNum(0.0);
        if (d > 0.0) idRaw = tsim::Json::fmtNum(std::floor(d + 0.5));
    }
    if (idRaw.empty()) idRaw = "0";

    std::string cmd = tsim::jgetStr(req, "cmd", "");
    tsim::Json args = tsim::Json::obj();
    const tsim::Json* a = req.find("args");
    if (a && a->isObj()) args = *a;

    if (cmd == "quit") {
        tsim::Engine::Result r;
        r.ok = true;
        r.data = tsim::Json::obj();
        writeLine(respond(idRaw, r));
        // 显式 flush：stdout 是管道时，必须确保响应在被读出前完整落盘（协议 §1）
        std::fflush(stdout);
        std::cout.flush();
        if (quitFlag) *quitFlag = true;
        return true;
    }

    tsim::Engine::Result r = eng.exec(cmd, args);
    writeLine(respond(idRaw, r));
    return false;
}

int runStdio(tsim::Engine& eng) {
    tsim::Clock clock;
    clock.setPushFn([](const std::string& line) { writeLine(line); });

    tsim::Engine::Result hr;
    hr.ok = true;
    hr.data = eng.helloData();
    writeLine(respond("0", hr));
    // READY 标记（协议 §0）：只在 stderr 上写，绝不写 stdout。
    logLine("TRADE_SIM ready");

    std::string line;
    bool quit = false;
    while (!quit && std::getline(std::cin, line)) {
        tsim::Json req;
        bool isClockCmd = false;
        if (tsim::jsonParse(trimCr(line), req)) {
            std::string c = tsim::jgetStr(req, "cmd", "");
            if (c == "clock") isClockCmd = true;
        }
        bool isQuit = false;
        {
            std::string L = trimCr(line);
            if (!L.empty() && tsim::jsonParse(L, req)) {
                if (tsim::jgetStr(req, "cmd", "") == "quit") isQuit = true;
            }
        }
        if (!isQuit && !isClockCmd) {
            // 普通命令：先处理
            handleLine(eng, line, &quit);
            continue;
        }
        if (isQuit) {
            handleLine(eng, line, &quit);
            continue;
        }
        // clock 命令：先停表，改完再按状态重启
        bool wasRunning = clock.running();
        if (wasRunning) {
            eng.clockRunning = false;
            clock.stop();
        }
        handleLine(eng, line, &quit);
        if (eng.clockRunning && !clock.running()) clock.start(eng);
    }
    clock.stop();
    // 退出前把 stdout 彻底刷干，避免管道路径下最后一行被截断
    std::fflush(stdout);
    std::cout.flush();
    return 0;
}

int runRepl(tsim::Engine& eng) {
    tsim::Engine::Result hr;
    hr.ok = true;
    hr.data = eng.helloData();
    writeLine(respond("0", hr));
    std::string line;
    bool quit = false;
    while (!quit && std::getline(std::cin, line)) {
        handleLine(eng, line, &quit);
    }
    return 0;
}

// ===================== 内置自检 =====================
int selftest() {
    int pass = 0, fail = 0;
    auto check = [&](const char* name, bool ok) {
        if (ok) { ++pass; std::printf("PASS %s\n", name); std::fflush(stdout); }
        else { ++fail; std::printf("FAIL %s\n", name); std::fflush(stdout); }
    };
    auto approx = [](double a, double b, double eps) { return std::fabs(a - b) <= eps; };
    using tsim::Json;

    // --- JSON ---
    {
        Json j;
        bool ok = tsim::jsonParse("{\"a\":1,\"b\":[1,2.5,\"x\"],\"c\":true,\"d\":null,\"e\":\"\\u4e2d\"}", j);
        check("json.parse.basic", ok && j["a"].asNum() == 1.0 && j["b"].size() == 3 && j["c"].asBool() && j["d"].isNull());
        check("json.parse.unicode", j["e"].asStr() == "\xe4\xb8\xad");
        Json bad;
        check("json.parse.reject", !tsim::jsonParse("{\"a\":}", bad) && !tsim::jsonParse("[1,2", bad) && !tsim::jsonParse("", bad));
    }
    check("json.dump.int", Json(3.0).dump() == "3");
    check("json.dump.noScientific", Json(0.0000001).dump().find('e') == std::string::npos);
    check("json.dump.escape", Json("a\"b\n").dump() == "\"a\\\"b\\n\"");

    // --- 时间 ---
    {
        tsim::GameTime t;
        check("time.start", t.dateStr() == "2024-01-02" && t.slot == 0);
        tsim::GameTime t2; t2.day = 1;
        check("time.nextday", t2.dateStr() == "2024-01-03");
        tsim::GameTime t3; t3.day = 4;
        check("time.skipweekend", t3.dateStr() == "2024-01-08");
        tsim::GameTime t5; t5.day = 5;
        check("time.afterweekend", t5.dateStr() == "2024-01-09");
        tsim::GameTime t4; t4.setAbs(7);
        check("time.absslot", t4.day == 1 && t4.slot == 3);
    }
    // --- 格式化 ---
    check("fmt.money", tsim::fmtMoney(1000000.0) == "1,000,000.00");
    check("fmt.noSci", tsim::fixedStr(0.0000001, 6) == "0.000000");

    // --- 引擎 ---
    tsim::Engine eng;
    {
        Json a;
        tsim::jsonParse("{\"seed\":42,\"name\":\"测试玩家\"}", a);
        eng.newGame(a);
        Json snap = eng.snapshotData();
        check("engine.newgame.cash", snap["stockAccount"]["cash"].asNum() == 1000000.0);
        const char* need[] = {"time", "stockAccount", "forexAccount", "stockPositions", "forexPositions",
                              "orders", "autoT1", "stat", "bankrupt"};
        bool all = true;
        for (const char* k : need) if (!snap.find(k)) all = false;
        check("engine.snapshot.fields", all);
        check("engine.marginLevel0", snap["forexAccount"]["marginLevel"].asNum() == 0.0);
        // 字段类型
        check("engine.snapshot.types",
              snap["stockPositions"].isArr() && snap["forexPositions"].isArr() && snap["orders"].isArr() &&
              snap["bankrupt"].isBool() && snap["autoT1"]["enabled"].isBool() &&
              snap["stat"]["tradeCount"].isNum());
    }
    {
        Json a; tsim::jsonParse("{\"market\":\"all\"}", a);
        Json mk = eng.cmdMarket(a);
        check("market.stocks>=16", mk["stocks"].size() >= 16);
        check("market.forex==10", mk["forex"].size() == 10);
        bool histOk = true;
        for (const Json& s : mk["stocks"].items()) if (s["hist"].size() < 60) histOk = false;
        for (const Json& f : mk["forex"].items()) if (f["hist"].size() < 60) histOk = false;
        check("market.hist>=60", histOk);
        bool digitsOk = true;
        for (const Json& f : mk["forex"].items()) {
            std::string sym = f["symbol"].asStr();
            int dg = static_cast<int>(f["digits"].asNum());
            if ((sym == "USDJPY" || sym == "EURJPY" || sym == "GBPJPY") && dg != 3) digitsOk = false;
            if (sym == "XAUUSD" && dg != 2) digitsOk = false;
            if (sym != "USDJPY" && sym != "EURJPY" && sym != "GBPJPY" && sym != "XAUUSD" && dg != 4) digitsOk = false;
        }
        check("market.digits", digitsOk);
        int prefixes = 0;
        const char* pfx[4] = {"SH", "SZ", "HK", "US"};
        for (const char* p : pfx) {
            int c = 0;
            for (const Json& s : mk["stocks"].items()) {
                std::string sy = s["symbol"].asStr();
                if (sy.rfind(p, 0) == 0) ++c;
            }
            if (c >= 4) ++prefixes;
        }
        check("market.prefixes4", prefixes == 4);
        check("market.stock.fields",
              mk["stocks"].at(0).find("name") && mk["stocks"].at(0).find("changePct") &&
              mk["stocks"].at(0).find("halted") && mk["stocks"].at(0).find("pe") &&
              mk["stocks"].at(0).find("currency") && mk["forex"].at(0).find("spread") &&
              mk["forex"].at(0).find("pointValue") && mk["forex"].at(0).find("pip"));
    }
    // 买入 + T+1
    {
        Json a; tsim::jsonParse("{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}", a);
        Json r = eng.cmdBuy(a);
        check("buy.market.filled", r["status"].asStr() == "filled" && r["filled"].asNum() == 100 && r["orderId"].asNum() > 0);
        const tsim::StockPosition* p = eng.stockAcc.find("SH600519");
        check("buy.position", p && p->qty == 100 && p->todayBought == 100);
        Json s = eng.cmdSell(a);
        check("t1.locked", s.find("__fail") != nullptr && tsim::jgetStr(s, "code", "") == "T1_LOCKED");
        Json u; tsim::jsonParse("{\"op\":\"unlock\",\"symbol\":\"SH600519\"}", u);
        eng.cmdCheat(u);
        Json s2 = eng.cmdSell(a);
        check("cheat.unlock.then.sell", s2.find("__fail") == nullptr && s2["status"].asStr() == "filled");
    }
    {
        Json a; tsim::jsonParse("{\"symbol\":\"SH600519\",\"qty\":150,\"type\":\"market\"}", a);
        Json r = eng.cmdBuy(a);
        check("buy.badqty", r.find("__fail") != nullptr && tsim::jgetStr(r, "code", "") == "BAD_QTY");
        Json a2; tsim::jsonParse("{\"symbol\":\"XX1234\",\"qty\":100}", a2);
        Json r2 = eng.cmdBuy(a2);
        check("buy.nosymbol", r2.find("__fail") != nullptr && tsim::jgetStr(r2, "code", "") == "NO_SUCH_SYMBOL");
        Json a3; tsim::jsonParse("{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\"}", a3);
        Json r3 = eng.cmdBuy(a3);
        check("buy.limit.noprice", r3.find("__fail") != nullptr && tsim::jgetStr(r3, "code", "") == "BAD_ARG");
    }
    // 限价单跨 slot 撮合
    {
        Json a; tsim::jsonParse("{\"symbol\":\"SZ000001\",\"qty\":200,\"type\":\"limit\",\"price\":1.0}", a);
        Json r = eng.cmdBuy(a);
        check("order.limit.open", r["status"].asStr() == "open" && r["orderId"].asNum() > 0);
        for (tsim::Stock& s : eng.market.stocks) if (s.def.symbol == "SZ000001") {
            s.last = 0.5; s.low = 0.5; s.high = 0.6; s.bid = 0.4999; s.ask = 0.5001;
        }
        std::vector<Json> ev;
        eng.matchOrders(ev);
        const tsim::Order* o = eng.book.find(static_cast<int>(r["orderId"].asNum()));
        check("order.limit.filled", o && o->status == "filled" && o->filled == 200);
        check("order.limit.event", !ev.empty() && ev.at(0)["kind"].asStr() == "order_filled");
    }
    // 停牌
    {
        Json f; tsim::jsonParse("{\"op\":\"freeze\",\"symbol\":\"SZ000001\",\"halted\":true}", f);
        eng.cmdCheat(f);
        Json a; tsim::jsonParse("{\"symbol\":\"SZ000001\",\"qty\":100}", a);
        Json r = eng.cmdBuy(a);
        check("halted.buy", r.find("__fail") != nullptr && tsim::jgetStr(r, "code", "") == "MARKET_HALTED");
        Json f2; tsim::jsonParse("{\"op\":\"freeze\",\"symbol\":\"SZ000001\",\"halted\":false}", f2);
        eng.cmdCheat(f2);
    }
    // 撤单
    {
        Json a; tsim::jsonParse("{\"symbol\":\"SZ000858\",\"qty\":100,\"type\":\"limit\",\"price\":1.0}", a);
        Json r = eng.cmdBuy(a);
        check("order.limit.open2", r["status"].asStr() == "open");
        double frozenBefore = eng.stockAcc.frozen;
        Json c; c["orderId"] = Json(r["orderId"].asNum());
        Json cr = eng.cmdCancel(c);
        check("order.cancel", cr["cancelled"].asBool() && cr["refundedCash"].asNum() > 0 &&
                              eng.stockAcc.frozen < frozenBefore);
        Json c2; c2["orderId"] = Json(999999.0);
        Json cr2 = eng.cmdCancel(c2);
        check("order.cancel.missing", cr2.find("__fail") != nullptr);
    }
    // tick
    {
        long long before = eng.absSlot();
        Json a; tsim::jsonParse("{\"n\":5,\"mode\":\"auto\"}", a);
        Json r = eng.cmdTick(a);
        check("tick.advance", eng.absSlot() == before + 5);
        check("tick.data", r["time"].isObj() && r["events"].isArr() && r["halted"].isArr() && r["advanced"].asNum() == 5.0);
        Json a2; tsim::jsonParse("{\"n\":8,\"mode\":\"manual\"}", a2);
        Json r2 = eng.cmdTick(a2);
        check("tick.manual", r2["advanced"].asNum() == 8.0 && r2["events"].isArr());
    }
    // 外汇
    {
        Json a; tsim::jsonParse("{\"op\":\"open\",\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":2}", a);
        Json r = eng.cmdForex(a);
        check("forex.open", r.find("__fail") == nullptr && r["lots"].asNum() == 2.0 && r["margin"].asNum() > 0 &&
                            r.find("positionId") != nullptr && r.find("openRate") != nullptr && r.find("swap") != nullptr);
        int pid = static_cast<int>(r["positionId"].asNum());
        Json c; tsim::jsonParse("{\"op\":\"close\"}", c);
        c["positionId"] = Json(static_cast<double>(pid));
        Json cr = eng.cmdForex(c);
        check("forex.close", cr.find("__fail") == nullptr && cr.find("pnl") != nullptr);
        Json c2; tsim::jsonParse("{\"op\":\"close\",\"positionId\":999999}", c2);
        Json cr2 = eng.cmdForex(c2);
        check("forex.noposition", cr2.find("__fail") != nullptr && tsim::jgetStr(cr2, "code", "") == "NO_POSITION");
        Json a2; tsim::jsonParse("{\"op\":\"open\",\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":0}", a2);
        Json r2 = eng.cmdForex(a2);
        check("forex.badlots", r2.find("__fail") != nullptr && tsim::jgetStr(r2, "code", "") == "BAD_LOTS");
        Json a3; tsim::jsonParse("{\"op\":\"open\",\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":100000}", a3);
        Json r3 = eng.cmdForex(a3);
        check("forex.badlots.max", r3.find("__fail") != nullptr);
        Json a4; tsim::jsonParse("{\"op\":\"open\",\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":9999}", a4);
        Json r4 = eng.cmdForex(a4);
        check("forex.insufficientmargin", r4.find("__fail") != nullptr && tsim::jgetStr(r4, "code", "") == "INSUFFICIENT_MARGIN");
    }
    // 强平
    {
        Json a; tsim::jsonParse("{\"op\":\"open\",\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":300}", a);
        Json openR = eng.cmdForex(a);
        check("forex.open.big", openR.find("__fail") == nullptr);
        for (tsim::Forex& f : eng.market.forex) if (f.def.symbol == "EURUSD") f.last = 0.90;
        for (tsim::Forex& f : eng.market.forex) if (f.def.symbol == "EURUSD") f.last = 0.90;
        for (tsim::ForexPosition& p : eng.forexAcc.positions) p.last = 0.90;
        std::vector<Json> ev;
        eng.checkMargin(ev);
        bool mc = false;
        for (const Json& e : ev) if (e["kind"].asStr() == "margin_call") mc = true;
        check("forex.margin_call", mc);
    }
    // 作弊器
    {
        Json a; tsim::jsonParse("{\"op\":\"money\",\"amount\":1000000,\"account\":\"stock\"}", a);
        double before = eng.stockAcc.cash;
        Json r = eng.cmdCheat(a);
        check("cheat.money", eng.stockAcc.cash > before);
        check("cheat.result.fields", r.find("op") != nullptr && r.find("ok") != nullptr &&
                                     r.find("detail") != nullptr && r.find("cheatState") != nullptr);
        const char* cs[] = {"t1", "infiniteMoney", "godMode", "noCommission", "perfectInfo", "winRate", "seed"};
        bool csOk = true;
        for (const char* k : cs) if (!r["cheatState"].find(k)) csOk = false;
        check("cheat.cheatState.fields", csOk);
        Json l; tsim::jsonParse("{\"op\":\"list\"}", l);
        Json lr = eng.cmdCheat(l);
        check("cheat.list>=20", lr["cheats"].size() >= 20);
        bool listOk = true;
        for (const Json& c : lr["cheats"].items())
            if (!c.find("op") || !c.find("label") || !c.find("desc") || !c.find("args")) listOk = false;
        check("cheat.list.fields", listOk);
        Json p; tsim::jsonParse("{\"op\":\"price\",\"symbol\":\"SH601398\",\"to\":9.99}", p);
        eng.cmdCheat(p);
        const tsim::Stock* st = nullptr;
        for (const tsim::Stock& x : eng.market.stocks) if (x.def.symbol == "SH601398") st = &x;
        check("cheat.price.to", st && approx(st->last, 9.99, 1e-6));
        Json p2; tsim::jsonParse("{\"op\":\"price\",\"symbol\":\"SH601398\",\"pct\":0.1}", p2);
        eng.cmdCheat(p2);
        const tsim::Stock* st2 = nullptr;
        for (const tsim::Stock& x : eng.market.stocks) if (x.def.symbol == "SH601398") st2 = &x;
        check("cheat.price.pct", st2 && st2->last > 9.99);
        Json pm; tsim::jsonParse("{\"op\":\"pump\",\"symbol\":\"SH601398\",\"pct\":0.2,\"bars\":3}", pm);
        Json pmr = eng.cmdCheat(pm);
        check("cheat.pump", pmr.find("cheatState") != nullptr && st2 && st2->pumpBars == 3);
        Json sk; tsim::jsonParse("{\"op\":\"skip\",\"slots\":8,\"auto\":true}", sk);
        Json skr = eng.cmdCheat(sk);
        check("cheat.skip", skr.find("advanced") != nullptr && skr["advanced"].asNum() == 8.0);
        Json pi; tsim::jsonParse("{\"op\":\"perfectInfo\",\"enabled\":true}", pi);
        eng.cmdCheat(pi);
        Json snap = eng.snapshotData();
        check("cheat.cheatInfo", snap.find("cheatInfo") != nullptr && snap["cheatInfo"].find("unreadNews") != nullptr &&
                                 snap["cheatInfo"].find("pumps") != nullptr);
        Json rs; tsim::jsonParse("{\"op\":\"revealSeed\"}", rs);
        Json rsr = eng.cmdCheat(rs);
        check("cheat.revealSeed", rsr.find("seedInfo") != nullptr);
        Json t1o; tsim::jsonParse("{\"op\":\"t1\",\"enabled\":false}", t1o);
        eng.cmdCheat(t1o);
        check("cheat.t1.off", !eng.cheat.t1);
        Json t1n; tsim::jsonParse("{\"op\":\"t1\",\"enabled\":true}", t1n);
        eng.cmdCheat(t1n);
        Json im; tsim::jsonParse("{\"op\":\"infiniteMoney\",\"enabled\":true}", im);
        eng.cmdCheat(im);
        check("cheat.infiniteMoney", eng.cheat.infiniteMoney);
        Json im2; tsim::jsonParse("{\"op\":\"infiniteMoney\",\"enabled\":false}", im2);
        eng.cmdCheat(im2);
        Json gm; tsim::jsonParse("{\"op\":\"godMode\",\"enabled\":true}", gm);
        eng.cmdCheat(gm);
        Json nc; tsim::jsonParse("{\"op\":\"noCommission\",\"enabled\":true}", nc);
        eng.cmdCheat(nc);
        check("cheat.godmode.nofee", eng.commissionRate() == 0.0);
        Json gm2; tsim::jsonParse("{\"op\":\"godMode\",\"enabled\":false}", gm2);
        eng.cmdCheat(gm2);
        Json nc2; tsim::jsonParse("{\"op\":\"noCommission\",\"enabled\":false}", nc2);
        eng.cmdCheat(nc2);
        Json wr; tsim::jsonParse("{\"op\":\"winRate\",\"value\":0.9}", wr);
        Json wrr = eng.cmdCheat(wr);
        check("cheat.winRate", approx(eng.cheat.winRate, 0.9, 1e-9) && wrr["cheatState"]["winRate"].asNum() == 0.9);
        Json sc; tsim::jsonParse("{\"op\":\"setCash\",\"account\":\"stock\",\"value\":5000000}", sc);
        eng.cmdCheat(sc);
        check("cheat.setCash", approx(eng.stockAcc.cash, 5000000.0, 1e-6));
        Json sd; tsim::jsonParse("{\"op\":\"seed\",\"seed\":12345}", sd);
        Json sdr = eng.cmdCheat(sd);
        check("cheat.seed", sdr["cheatState"]["seed"].asNum() == 12345.0);
        Json sp; tsim::jsonParse("{\"op\":\"speed\",\"speed\":64}", sp);
        eng.cmdCheat(sp);
        check("cheat.speed", approx(eng.settings.speed, 64.0, 1e-9));
        Json nw; tsim::jsonParse("{\"op\":\"news\",\"title\":\"测试新闻\",\"impact\":0.05,\"scope\":\"stock\",\"symbols\":[\"SH600036\"]}", nw);
        Json nwr = eng.cmdCheat(nw);
        check("cheat.news", nwr.find("detail") != nullptr && eng.news.items.size() > 0);
        Json ro; tsim::jsonParse("{\"op\":\"reset\"}", ro);
        Json ror_ = eng.cmdCheat(ro);
        check("cheat.reset", ror_.find("data") != nullptr && eng.stockAcc.positions.empty());
        Json bk; tsim::jsonParse("{\"op\":\"bankrupt\",\"account\":\"stock\"}", bk);
        eng.cmdCheat(bk);
        check("cheat.bankrupt", eng.bankruptStock);
        Json ub; tsim::jsonParse("{\"op\":\"unbankrupt\"}", ub);
        eng.cmdCheat(ub);
        check("cheat.unbankrupt", !eng.bankruptStock);
        Json fx; tsim::jsonParse("{\"op\":\"fillOrders\",\"all\":true}", fx);
        Json fxr = eng.cmdCheat(fx);
        check("cheat.fillOrders", fxr.find("detail") != nullptr);
        Json bad; tsim::jsonParse("{\"op\":\"nosuchop\"}", bad);
        Json badr = eng.cmdCheat(bad);
        check("cheat.unknownop", badr.find("__fail") != nullptr);
        Json unl; tsim::jsonParse("{\"op\":\"unlock\"}", unl);
        Json unlr = eng.cmdCheat(unl);
        check("cheat.unlock.all", unlr.find("detail") != nullptr);
    }
    // 协议 §3.8a：挂单 TTL 过期 + order_partial
    {
        tsim::Engine e4;
        Json ng; tsim::jsonParse("{\"seed\":7,\"cashStock\":100000000}", ng);
        e4.newGame(ng);
        Json lo; tsim::jsonParse("{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":1.0}", lo);
        Json lr = e4.stockOrder(lo, "buy");
        check("order.ttl.set", lr["status"].asStr() == "open");
        tsim::Order* ord = e4.book.find(static_cast<int>(lr["orderId"].asNum()));
        check("order.ttl.value20", ord && ord->ttlSlots == 20);
        double frozenBefore = e4.stockAcc.frozen;
        bool sawExpired = false;
        for (int i = 0; i < 25 && !sawExpired; ++i) {
            Json t; tsim::jsonParse("{\"n\":1,\"mode\":\"auto\"}", t);
            Json tr = e4.cmdTick(t);
            for (const Json& ev : tr["events"].items())
                if (ev["kind"].asStr() == "order_expired") sawExpired = true;
        }
        check("order.expired.at.ttl", sawExpired);
        check("order.expired.unfrozen", e4.stockAcc.frozen < frozenBefore);
        check("order.expired.status", e4.book.find(static_cast<int>(lr["orderId"].asNum()))->status == "expired");

        // order_partial：部分成交必须带 remaining 字段
        tsim::Engine e5;
        tsim::Json ng5; tsim::jsonParse("{\"seed\":7,\"cashStock\":100000000}", ng5);
        e5.newGame(ng5);
        Json lb; tsim::jsonParse("{\"symbol\":\"SH600519\",\"qty\":300,\"type\":\"limit\",\"price\":1.0}", lb);
        Json lbr = e5.stockOrder(lb, "buy");
        int oid5 = static_cast<int>(lbr["orderId"].asNum());
        tsim::Stock* st5 = e5.market.findStock("SH600519");
        tsim::Order* o5 = e5.book.find(oid5);
        std::vector<Json> ev5;
        e5.processStockFill(*o5, st5, 100, 1.0, ev5);
        check("order.partial.status", o5->status == "partial");
        check("order.partial.event",
              !ev5.empty() && ev5.at(0)["kind"].asStr() == "order_partial" &&
              ev5.at(0)["remaining"].asNum() == 200.0 && ev5.at(0)["filled"].asNum() == 100.0 &&
              ev5.at(0).find("orderId") != nullptr);
    }
    // 时钟
    {
        Json a; tsim::jsonParse("{\"action\":\"set\",\"speed\":4,\"tickMs\":500}", a);
        Json r = eng.cmdClock(a);
        check("clock.set", approx(r["speed"].asNum(), 4.0, 1e-9) && approx(r["tickMs"].asNum(), 500.0, 1e-9) &&
                           approx(r["tickIntervalMs"].asNum(), 125.0, 1e-9) && r["advanceUnit"].asStr() == "slot");
        Json s; tsim::jsonParse("{\"action\":\"start\"}", s);
        Json sr = eng.cmdClock(s);
        check("clock.start", sr["running"].asBool());
        Json st; tsim::jsonParse("{\"action\":\"stop\"}", st);
        Json str = eng.cmdClock(st);
        check("clock.stop", !str["running"].asBool());
        Json g; tsim::jsonParse("{\"action\":\"get\"}", g);
        Json gr = eng.cmdClock(g);
        check("clock.get", !gr["running"].asBool() && gr.find("time") != nullptr);
        Json b; tsim::jsonParse("{\"action\":\"bogus\"}", b);
        Json br = eng.cmdClock(b);
        check("clock.badarg", br.find("__fail") != nullptr);
        // 自动推进一步
        eng.clockRunning = false;
        Json p = eng.clockStepPush();
        check("clock.push.data", p.find("time") != nullptr && p.find("events") != nullptr && p["advanced"].asNum() == 1.0);
    }
    // settings
    {
        Json a; tsim::jsonParse("{\"commission\":0.001,\"t1\":false,\"autoRenew\":true,\"autoStop\":true}", a);
        Json r = eng.cmdSettings(a);
        const char* keys[] = {"speed", "t1", "autoRenew", "autoStop", "commission", "slippage",
                              "tickMs", "stopLossPct", "takeProfitPct", "difficulty"};
        bool all = true;
        for (const char* k : keys) if (!r.find(k)) all = false;
        check("settings.echo.all", all);
        check("settings.types", r["t1"].isBool() && r["autoRenew"].isBool() && r["commission"].isNum());
        Json a2; tsim::jsonParse("{\"t1\":true}", a2);
        eng.cmdSettings(a2);
    }
    // 自动 T+1 / 自动重挂 / 止盈止损（auto）
    {
        tsim::Engine e2;
        Json ng; tsim::jsonParse("{\"seed\":7,\"cashStock\":10000000}", ng);
        e2.newGame(ng);
        Json st; tsim::jsonParse("{\"autoRenew\":true,\"autoStop\":true,\"stopLossPct\":0.02,\"takeProfitPct\":0.02}", st);
        e2.cmdSettings(st);
        Json b; tsim::jsonParse("{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}", b);
        e2.cmdBuy(b);
        bool hadUnlock = false;
        for (int i = 0; i < 6; ++i) {
            Json t; tsim::jsonParse("{\"n\":1,\"mode\":\"auto\"}", t);
            Json r = e2.cmdTick(t);
            for (const Json& ev : r["events"].items()) if (ev["kind"].asStr() == "t1_unlock") hadUnlock = true;
        }
        bool anyLocked = false;
        for (const tsim::StockPosition& p : e2.stockAcc.positions) if (p.todayBought > 0) anyLocked = true;
        check("auto.t1_unlock", hadUnlock && !anyLocked);
    }
    // 新闻 / 历史 / 订单 / quote
    {
        Json a; tsim::jsonParse("{\"limit\":10}", a);
        Json r = eng.cmdNews(a);
        check("news.list", r["news"].isArr());
        Json a2; tsim::jsonParse("{\"limit\":5,\"unreadOnly\":true}", a2);
        Json r2 = eng.cmdNews(a2);
        check("news.unreadonly", r2["news"].isArr());
        Json h; tsim::jsonParse("{\"limit\":5,\"market\":\"all\"}", h);
        Json hr = eng.cmdHistory(h);
        check("history.list", hr["trades"].isArr());
        if (hr["trades"].size() > 0) {
            const Json& t0 = hr["trades"].at(0);
            check("history.fields", t0.find("seq") && t0.find("time") && t0.find("market") && t0.find("symbol") &&
                                    t0.find("side") && t0.find("qty") && t0.find("price") && t0.find("amount") &&
                                    t0.find("commission") && t0.find("realizedPnl") && t0.find("reason"));
        } else {
            check("history.fields", true);
        }
        Json o; tsim::jsonParse("{\"market\":\"all\"}", o);
        Json orr = eng.cmdOrders(o);
        check("orders.list", orr["orders"].isArr());
        Json q; tsim::jsonParse("{\"symbol\":\"SH600519\"}", q);
        Json qr = eng.cmdQuote(q);
        check("quote.stock", qr["symbol"].asStr() == "SH600519" && qr["market"].asStr() == "stock" &&
                             qr.find("bid") && qr.find("ask") && qr.find("digits"));
        Json q2; tsim::jsonParse("{\"symbol\":\"EURUSD\"}", q2);
        Json qr2 = eng.cmdQuote(q2);
        check("quote.forex", qr2["market"].asStr() == "forex" && qr2["digits"].asNum() == 4);
        Json q3; tsim::jsonParse("{\"symbol\":\"NOPE\"}", q3);
        Json qr3 = eng.cmdQuote(q3);
        check("quote.nosymbol", qr3.find("__fail") != nullptr);
    }
    // 错误码
    {
        tsim::Engine e3;
        tsim::Engine::Result r = e3.exec("snapshot", Json::obj());
        check("err.nogame", !r.ok && tsim::jgetStr(r.err, "code", "") == "NO_GAME");
        tsim::Engine::Result r2 = e3.exec("nope", Json::obj());
        check("err.unknowncmd", !r2.ok && tsim::jgetStr(r2.err, "code", "") == "UNKNOWN_CMD");
        Json a; tsim::jsonParse("{\"seed\":1}", a);
        tsim::Engine::Result r3 = e3.exec("newgame", a);
        check("exec.newgame", r3.ok && r3.data.find("stockAccount") != nullptr);
        tsim::Engine::Result r4 = e3.exec("hello", Json::obj());
        check("exec.hello", r4.ok && r4.data["protocol"].asNum() == 1.0 && r4.data["engine"].asStr() == "TradeSim");
    }
    // 序列化
    {
        Json snap = eng.snapshotData();
        std::string s = snap.dump();
        Json back;
        bool ok = tsim::jsonParse(s, back);
        check("json.roundtrip.snapshot", ok && back.find("stockAccount") != nullptr);
        check("json.no.scientific", s.find("e-") == std::string::npos && s.find("e+") == std::string::npos);
        check("json.no.newline", s.find('\n') == std::string::npos);
        // 小数位
        Json n1 = Json(0.000001);
        check("json.6dp", n1.dump() == "0.000001");
        Json n2 = Json(1234567.0);
        check("json.int.plain", n2.dump() == "1234567");
    }
    // 未知命令 / 参数
    {
        Json empty;
        tsim::Engine::Result r = eng.exec("", empty);
        check("exec.unbalanced", !r.ok && tsim::jgetStr(r.err, "code", "") == "UNKNOWN_CMD");
        tsim::Engine::Result r2 = eng.exec("buy", Json::obj());
        check("exec.buy.noargs", !r2.ok && tsim::jgetStr(r2.err, "code", "") == "BAD_ARG");
    }
    std::printf("SELFTEST total=%d pass=%d fail=%d\n", pass + fail, pass, fail);
    std::fflush(stdout);
    return fail == 0 ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
    setupConsoleUtf8();
    std::string mode;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i] ? argv[i] : "";
        if (!a.empty()) mode = a;
    }
    if (mode.empty()) mode = "--stdio";

    if (mode == "--selftest") {
        try {
            return selftest();
        } catch (const std::exception& e) {
            std::printf("FAIL selftest.exception %s\n", e.what());
            return 1;
        } catch (...) {
            std::printf("FAIL selftest.exception unknown\n");
            return 1;
        }
    }

    try {
        tsim::Engine engine;
        if (mode == "--repl") return runRepl(engine);
        return runStdio(engine);
    } catch (const std::exception& e) {
        logLine(std::string("fatal: ") + e.what());
        return 1;
    } catch (...) {
        logLine("fatal: unknown exception");
        return 1;
    }
}
