// engine.hpp - 总装：Engine 类 + 命令分发 exec(cmd,args)
#pragma once

#include "json.hpp"
#include "jsonutil.hpp"
#include "util.hpp"
#include "market.hpp"
#include "news.hpp"
#include "account.hpp"
#include "order.hpp"

#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <map>
#include <random>
#include <chrono>
#include <atomic>

namespace tsim {

struct Settings {
    double speed = 1.0;
    bool t1 = true;
    bool autoRenew = false;
    bool autoStop = false;
    double commission = 0.00025;
    double slippage = 0.0005;
    double tickMs = 0.0;
    double stopLossPct = 0.05;
    double takeProfitPct = 0.10;
    std::string difficulty = "normal";

    Json toJson() const {
        Json j = Json::obj();
        j["speed"] = Json::dec(speed);
        j["t1"] = Json(t1);
        j["autoRenew"] = Json(autoRenew);
        j["autoStop"] = Json(autoStop);
        j["commission"] = Json::dec(round6(commission));
        j["slippage"] = Json::dec(round6(slippage));
        if (tickMs == std::floor(tickMs)) j["tickMs"] = Json(static_cast<double>(static_cast<long long>(tickMs)));
        else j["tickMs"] = Json::dec(tickMs);
        j["stopLossPct"] = Json::dec(round6(stopLossPct));
        j["takeProfitPct"] = Json::dec(round6(takeProfitPct));
        j["difficulty"] = Json(difficulty);
        return j;
    }
};

struct CheatState {
    bool t1 = true;
    bool infiniteMoney = false;
    bool godMode = false;
    bool noCommission = false;
    bool perfectInfo = false;
    double winRate = 0.5;
    uint64_t seed = 12345;

    Json toJson() const {
        Json j = Json::obj();
        j["t1"] = Json(t1);
        j["infiniteMoney"] = Json(infiniteMoney);
        j["godMode"] = Json(godMode);
        j["noCommission"] = Json(noCommission);
        j["perfectInfo"] = Json(perfectInfo);
        j["winRate"] = Json::dec(round6(winRate));
        j["seed"] = Json(static_cast<double>(seed));
        return j;
    }
};

struct TradeRecord {
    long long seq = 0;
    GameTime time;
    std::string market;
    std::string symbol;
    std::string side;
    long long qty = 0;
    double price = 0.0;
    double amount = 0.0;
    double commission = 0.0;
    double realizedPnl = 0.0;
    std::string reason;

    Json toJson() const {
        Json j = Json::obj();
        j["seq"] = Json(static_cast<double>(seq));
        j["time"] = time.toJson();
        j["market"] = Json(market);
        j["symbol"] = Json(symbol);
        j["side"] = Json(side);
        j["qty"] = Json(static_cast<double>(qty));
        j["price"] = Json::dec(round4(price));
        j["amount"] = Json::dec(round2(amount));
        j["commission"] = Json::dec(round2(commission));
        j["realizedPnl"] = Json::dec(round2(realizedPnl));
        j["reason"] = Json(reason);
        return j;
    }
};

struct AutoConfig {
    bool enabled = false;
    bool autoRenew = false;
    bool autoStop = false;
    double stopLossPct = 0.05;
    double takeProfitPct = 0.10;
};

// 自动重挂单模板
struct RenewSpec {
    std::string market;
    std::string symbol;
    std::string side;
    long long qty = 0;
    double price = 0.0;
    double lots = 0.0;
    double stopLoss = 0.0;
    double takeProfit = 0.0;
};

// 自动止盈止损参数（按 symbol+side 记忆）
struct StopSpec {
    std::string symbol;
    std::string side;
    double stopLoss = 0.0;
    double takeProfit = 0.0;
};

class Engine {
public:
    bool started = false;
    GameTime time;
    Market market;
    NewsEngine news;
    StockAccount stockAcc;
    ForexAccount forexAcc;
    OrderBook book;
    Settings settings;
    CheatState cheat;
    Rng rng{12345};

    std::string playerName = "玩家";
    std::string difficulty = "normal";
    long long tradeSeq = 0;
    std::vector<TradeRecord> trades;
    double totalCommission = 0.0;
    double realizedPnl = 0.0;
    double startEquity = 0.0;
    int winCount = 0;
    int tradeCount = 0;
    // 破产标志：时钟线程与 stdin 线程都会读写；用 std::atomic 避免数据竞争
    std::atomic<bool> bankruptStock{false};
    std::atomic<bool> bankruptForex{false};
    double dividendTotal = 0.0;
    double sessionDayStartEquity = 0.0;
    double lastKnownStockEquity = 0.0;

    AutoConfig autoCfg;
    std::vector<RenewSpec> renewQueue;
    std::vector<StopSpec> stopSpecs;
    bool autoRenewQueued = false;
    bool autoT1Queued = false;
    long long lastAutoRenewSlot = -999999;
    std::atomic<bool> bankruptPushed{false};

    Engine() {
        market.init();
        news.seedTemplates();
    }

    // ---------- 初始化 ----------
    void resetAllData() {
        market.init();
        news.clear();
        book.clear();
        stockAcc = StockAccount();
        forexAcc = ForexAccount();
        trades.clear();
        tradeSeq = 0;
        totalCommission = 0.0;
        realizedPnl = 0.0;
        winCount = 0;
        tradeCount = 0;
        bankruptStock = false;
        bankruptForex = false;
        bankruptPushed = false;
        dividendTotal = 0.0;
        renewQueue.clear();
        stopSpecs.clear();
        autoRenewQueued = false;
        autoT1Queued = false;
        time = GameTime();
        sessionDayStartEquity = 0.0;
        lastKnownStockEquity = 0.0;
    }

    static double defaultStockCash(const std::string& diff) {
        if (diff == "easy") return 5000000.0;
        if (diff == "hard") return 200000.0;
        return 1000000.0;
    }
    static double defaultForexCash(const std::string& diff) {
        if (diff == "easy") return 50000.0;
        if (diff == "hard") return 2000.0;
        return 10000.0;
    }

    void newGame(const Json& a) {
        resetAllData();
        long long seed = 12345;
        if (jhasNum(a, "seed")) seed = jgetInt(a, "seed", 12345);
        if (seed <= 0) seed = 12345;
        cheat.seed = static_cast<uint64_t>(seed);
        rng.reseed(cheat.seed);
        reseedMarketForSeed();

        difficulty = jgetStr(a, "difficulty", "normal");
        if (difficulty != "easy" && difficulty != "normal" && difficulty != "hard") difficulty = "normal";
        settings.difficulty = difficulty;

        playerName = jgetStr(a, "name", "玩家");
        if (playerName.empty()) playerName = "玩家";

        double cashStock = jhasNum(a, "cashStock") ? jgetNum(a, "cashStock", 1000000.0) : defaultStockCash(difficulty);
        double cashForex = jhasNum(a, "cashForex") ? jgetNum(a, "cashForex", 10000.0) : defaultForexCash(difficulty);
        if (cashStock < 0) cashStock = 0;
        if (cashForex < 0) cashForex = 0;

        stockAcc.initialCash = cashStock;
        stockAcc.cash = cashStock;
        forexAcc.initialCash = cashForex;
        forexAcc.cash = cashForex;

        cheat = CheatState();
        cheat.t1 = settings.t1;
        cheat.seed = static_cast<uint64_t>(seed);
        cheat.winRate = 0.5;

        autoCfg = AutoConfig();
        autoCfg.enabled = settings.t1;
        autoCfg.autoRenew = settings.autoRenew;
        autoCfg.autoStop = settings.autoStop;
        autoCfg.stopLossPct = settings.stopLossPct;
        autoCfg.takeProfitPct = settings.takeProfitPct;

        time = GameTime();          // P0-6: newgame 必须从 slot 0 开始
        book.nextId = 1;
        started = true;
        startEquity = totalEquity();
        sessionDayStartEquity = startEquity;
        lastKnownStockEquity = stockEquity();
    }


    // ---------- 命令分发 ----------
    struct Result {
        bool ok = true;
        Json data = Json::obj();
        Json err = Json::obj();
    };

    // args 中可直接带 "expr": "snapshot" 之类的内联表达式（tick 也能带）
    std::string inlineExpr(const Json& o) const {
        if (o.isStr()) return o.asStr();
        return jgetStr(o, "expr", "");
    }


    // 命令实现可能返回带 __fail 的对象（内部错误表示）；这里统一转成 ok=false + error
    Result normalize(Result r) {
        if (!r.ok) return r;
        if (r.data.isObj() && r.data.find("__fail") != nullptr) {
            std::string code = jgetStr(r.data, "code", err::INTERNAL);
            std::string msg = jgetStr(r.data, "message", "引擎内部错误");
            r.ok = false;
            r.err = errData(code, msg);
            r.data = Json::obj();
        }
        return r;
    }

    Result exec(const std::string& cmdRaw, const Json& argsIn) {
        Result r;
        Json empty = Json::obj();
        const Json& a = argsIn.isObj() ? argsIn : empty;
        const std::string cmd = cmdRaw;
        try {
            // 允许 args 内嵌 "expr"（例如 tick 的 args:{"expr":"snapshot"}）
            std::string sub = inlineExpr(a);
            if (!sub.empty() && sub != cmd) {
                Result inner = execInline(sub);
                if (!inner.ok) return inner;
                inner.data["expr"] = Json(sub);
                inner.data["exprResult"] = Json(true);
                std::string subCmd = sub;
                subCmd = subCmd.substr(0, subCmd.find(' '));
                if (subCmd == "snapshot") { r.data = inner.data; return r; }
                if (subCmd == "market") { r.data = inner.data["market"]; return r; }
                if (subCmd == "news") { r.data = inner.data["news"]; return r; }
                if (subCmd == "orders") { r.data = inner.data["orders"]; return r; }
                if (subCmd == "history") { r.data = inner.data["history"]; return r; }
                if (subCmd == "tick" || subCmd == "clock" || subCmd == "settings" || subCmd == "cheat") {
                    r.data = inner.data; return r;
                }
                r.data = inner.data;
                return r;
            }

            if (cmd == "hello") { r.data = helloData(); return r; }
            if (cmd == "ping") { r.data = Json::obj(); r.data["pong"] = Json(true); return r; }
            if (cmd == "newgame" || cmd == "reset") {
                newGame(a);
                r.data = snapshotData();
                return r;
            }
            if (cmd == "status") {
                Json st = Json::obj();
                st["started"] = Json(started);
                st["time"] = time.toJson();
                st["name"] = Json(playerName);
                r.data = st;
                return r;
            }
            const bool known = (cmd == "hello" || cmd == "ping" || cmd == "newgame" || cmd == "reset" ||
                                cmd == "status" || cmd == "tick" || cmd == "snapshot" || cmd == "market" ||
                                cmd == "quote" || cmd == "buy" || cmd == "sell" || cmd == "cancel" ||
                                cmd == "forex" || cmd == "fx" || cmd == "open" || cmd == "close" ||
                                cmd == "openposition" || cmd == "closeposition" ||
                                cmd == "orders" || cmd == "history" ||
                                cmd == "news" || cmd == "settings" || cmd == "clock" || cmd == "cheat");
            if (!known) {
                r.ok = false;
                r.err = errData(err::UNKNOWN_CMD, "引擎不支持该命令: " + cmdRaw);
                return r;
            }
            if (!started) {
                r.ok = false;
                r.err = errData(err::NO_GAME, "请先开始新游戏");
                return r;
            }
            if (cmd == "tick")     { r.data = cmdTick(a);     return normalize(r); }
            if (cmd == "snapshot") { r.data = snapshotData(); return normalize(r); }
            if (cmd == "market")   { r.data = cmdMarket(a);   return normalize(r); }
            if (cmd == "quote")    { r.data = cmdQuote(a);    return normalize(r); }
            if (cmd == "buy")      { r.data = stockOrder(a, "buy");  return normalize(r); }
            if (cmd == "sell")     { r.data = stockOrder(a, "sell"); return normalize(r); }
            if (cmd == "cancel")   { r.data = cmdCancel(a);   return normalize(r); }
            if (cmd == "forex" || cmd == "fx" || cmd == "trade_forex") { r.data = cmdForex(a); return normalize(r); }
            if (cmd == "open" || cmd == "openposition")  { Json fa = a; fa["op"] = Json("open");  r.data = cmdForex(fa); return normalize(r); }
            if (cmd == "close" || cmd == "closeposition") { Json fa = a; fa["op"] = Json("close"); r.data = cmdForex(fa); return normalize(r); }
            if (cmd == "orders")   { r.data = cmdOrders(a);   return normalize(r); }
            if (cmd == "history")  { r.data = cmdHistory(a);  return normalize(r); }
            if (cmd == "news")     { r.data = cmdNews(a);     return normalize(r); }
            if (cmd == "settings") { r.data = cmdSettings(a); return normalize(r); }
            if (cmd == "clock")    { r.data = cmdClock(a);    return normalize(r); }
            if (cmd == "cheat")    { r.data = cmdCheat(a);    return normalize(r); }
            r.ok = false;
            r.err = errData(err::UNKNOWN_CMD, "引擎不支持该命令: " + cmdRaw);
            return r;
        } catch (const std::exception& ex) {
            r.ok = false;
            r.data = Json::obj();
            r.err = errData(err::INTERNAL, std::string("引擎内部错误: ") + ex.what());
            return r;
        } catch (...) {
            r.ok = false;
            r.data = Json::obj();
            r.err = errData(err::INTERNAL, "引擎内部错误");
            return r;
        }
    }

    // ---------- 内联表达式（tick args 里可带 "expr":"snapshot" 等） ----------
    Result execInline(const std::string& expr) {
        std::string s = expr;
        while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) s.erase(s.begin());
        while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) s.pop_back();
        std::string cmd = s, rest;
        size_t sp = s.find(' ');
        if (sp != std::string::npos) { cmd = s.substr(0, sp); rest = s.substr(sp + 1); }
        Json args = Json::obj();
        if (!rest.empty()) {
            if (!jsonParse(rest, args)) args = Json::obj();
        }
        if (args.isObj() && args.find("expr") != nullptr) args.eraseKey("expr");
        return exec(cmd, args);
    }

    // ---------- 估值 ----------
    double stockMarketValue() const { return round2(stockAcc.marketValue(market)); }
    double forexFloatPnl() const { return round2(forexAcc.floatPnl()); }
    double forexEquity() const {
        return round2(forexAcc.cash + forexAcc.floatPnl() + forexAcc.swapSum());
    }
    double stockEquity() const {
        return round2(stockAcc.cash + stockAcc.frozen + stockMarketValue());
    }
    double totalEquity() const { return round2(stockEquity() + forexEquity()); }

    const Stock* constStock(const std::string& sym) const {
        for (const Stock& s : market.stocks) if (s.def.symbol == sym) return &s;
        return nullptr;
    }

    double t1FrozenCash() const {
        double v = 0.0;
        for (const StockPosition& p : stockAcc.positions) v += static_cast<double>(p.todayBought) * p.avgCost;
        return round2(v);
    }
    double dailyPnlStock() const {
        double mv = 0.0, prev = 0.0;
        for (const StockPosition& p : stockAcc.positions) {
            const Stock* s = constStock(p.symbol);
            if (!s) continue;
            mv += static_cast<double>(p.qty) * s->last;
            prev += static_cast<double>(p.qty) * s->prevClose;
        }
        return round2(mv - prev);
    }
    double dailyPnlForex() const {
        return round2(forexAcc.floatPnl() + forexAcc.swapSum());
    }
    long long totalUsedLots() const {
        double l = 0.0;
        for (const ForexPosition& p : forexAcc.positions) l += p.lots;
        return static_cast<long long>(std::llround(l));
    }

    Json ordersArray(const std::string& marketFilter) const {
        Json arr = Json::arr();
        for (const Order& o : book.orders) {
            if (marketFilter == "stock" && o.market != "stock") continue;
            if (marketFilter == "forex" && o.market != "forex") continue;
            arr.push_back(o.toJson());
        }
        return arr;
    }

    // ---------- 数据构造 ----------
    Json helloData() const {
        Json j = Json::obj();
        j["type"] = Json("hello");
        j["protocol"] = Json(1);
        j["engine"] = Json("TradeSim");
        j["version"] = Json("1.0.0");
        return j;
    }

    Json snapshotData() const {
        Json j = Json::obj();
        j["time"] = time.toJson();

        Json sa = Json::obj();
        sa["cash"] = Json::dec(round2(stockAcc.cash));
        sa["frozen"] = Json::dec(round2(stockAcc.frozen));
        sa["equity"] = Json::dec(stockEquity());
        sa["marketValue"] = Json::dec(stockMarketValue());
        sa["pnlDay"] = Json::dec(round2(dailyPnlStock()));
        sa["pnlTotal"] = Json::dec(round2(stockEquity() - startEquity));
        sa["marginUsed"] = Json::dec(0.0);
        sa["buyingPower"] = Json::dec(cheat.infiniteMoney ? 1e15 : round2(stockAcc.cash));
        sa["t1FrozenCash"] = Json::dec(t1FrozenCash());
        j["stockAccount"] = sa;

        double eq = forexEquity();
        double margin = round2(forexAcc.usedMargin());
        Json fa = Json::obj();
        fa["cash"] = Json::dec(round2(forexAcc.cash));
        fa["margin"] = Json::dec(margin);
        fa["equity"] = Json::dec(eq);
        fa["freeMargin"] = Json::dec(round2(eq - margin));
        fa["marginLevel"] = Json::dec(margin > 1e-9 ? round2(eq / margin * 100.0) : 0.0);
        fa["pnlFloat"] = Json::dec(forexFloatPnl());
        fa["pnlTotal"] = Json::dec(round2(forexAcc.realized + forexAcc.floatPnl() + forexAcc.swapSum()));
        fa["usedLots"] = Json(static_cast<double>(totalUsedLots()));
        fa["currency"] = Json(forexAcc.currency);
        j["forexAccount"] = fa;

        Json sps = Json::arr();
        for (const StockPosition& p : stockAcc.positions) {
            const Stock* s = constStock(p.symbol);
            double last = s ? s->last : p.avgCost;
            double mv = static_cast<double>(p.qty) * last;
            Json sp = Json::obj();
            sp["symbol"] = Json(p.symbol);
            sp["name"] = Json(p.name);
            sp["qty"] = Json(static_cast<double>(p.qty));
            sp["frozenQty"] = Json(static_cast<double>(p.frozenQty));
            sp["avgCost"] = Json::dec(round4(p.avgCost));
            sp["last"] = Json::dec(round4(last));
            sp["marketValue"] = Json::dec(round2(mv));
            sp["pnl"] = Json::dec(round2(mv - static_cast<double>(p.qty) * p.avgCost));
            sp["pnlPct"] = Json::dec(round6(p.avgCost > 1e-9 ? (last - p.avgCost) / p.avgCost : 0.0));
            sp["todayBoughtQty"] = Json(static_cast<double>(p.todayBought));
            sps.push_back(sp);
        }
        j["stockPositions"] = sps;

        Json fps = Json::arr();
        for (const ForexPosition& p : forexAcc.positions) {
            Json fp = Json::obj();
            fp["positionId"] = Json(p.id);
            fp["symbol"] = Json(p.symbol);
            fp["name"] = Json(p.name);
            fp["side"] = Json(p.side);
            fp["lots"] = Json(static_cast<double>(p.lots));
            fp["openRate"] = Json::dec(Json::roundTo(p.openRate, 6));
            fp["last"] = Json::dec(Json::roundTo(p.last, 6));
            fp["margin"] = Json::dec(round2(p.margin));
            fp["pnl"] = Json::dec(round2(p.pnlAt(p.last)));
            fp["swap"] = Json::dec(round2(p.swap));
            fp["stopLoss"] = Json::dec(Json::roundTo(p.stopLoss, 6));
            fp["takeProfit"] = Json::dec(Json::roundTo(p.takeProfit, 6));
            fps.push_back(fp);
        }
        j["forexPositions"] = fps;

        j["orders"] = ordersArray("all");

        Json at = Json::obj();
        at["enabled"] = Json(autoCfg.enabled);
        at["autoRenew"] = Json(autoCfg.autoRenew);
        at["autoStop"] = Json(autoCfg.autoStop);
        j["autoT1"] = at;

        Json st = Json::obj();
        st["tradeCount"] = Json(static_cast<double>(tradeCount));
        st["winCount"] = Json(static_cast<double>(winCount));
        st["realizedPnl"] = Json::dec(round2(realizedPnl));
        st["totalCommission"] = Json::dec(round2(totalCommission));
        st["startEquity"] = Json::dec(round2(startEquity));
        j["stat"] = st;

        j["bankrupt"] = Json(bankruptStock.load() || bankruptForex.load());

        Json ex = Json::obj();
        ex["name"] = Json(playerName);
        ex["difficulty"] = Json(difficulty);
        ex["forexRealized"] = Json::dec(round2(forexAcc.realized));
        ex["dividendTotal"] = Json::dec(round2(dividendTotal));
        ex["stocks"] = Json(static_cast<double>(market.stocks.size()));
        ex["forexPairs"] = Json(static_cast<double>(market.forex.size()));
        j["extra"] = ex;

        if (cheat.perfectInfo) j["cheatInfo"] = cheatInfo();
        return j;
    }

    Json cheatInfo() const {
        Json ci = Json::obj();
        ci["perfectInfo"] = Json(true);
        ci["seed"] = Json(static_cast<double>(cheat.seed));
        Json unread = Json::arr();
        for (auto it = news.items.rbegin(); it != news.items.rend(); ++it) {
            if (it->read) continue;
            Json n = Json::obj();
            n["id"] = Json(it->id);
            n["title"] = Json(it->title);
            n["scope"] = Json(it->scope);
            n["impact"] = Json::dec(round6(it->impact));
            unread.push_back(n);
            if (unread.size() >= 20) break;
        }
        ci["unreadNews"] = unread;
        Json pumps = Json::arr();
        for (const Stock& s : market.stocks) {
            if (s.pumpBars <= 0) continue;
            Json p = Json::obj();
            p["symbol"] = Json(s.def.symbol);
            p["bars"] = Json(s.pumpBars);
            p["pct"] = Json::dec(round6(s.pumpPct));
            pumps.push_back(p);
        }
        for (const Forex& f : market.forex) {
            if (f.pumpBars <= 0) continue;
            Json p = Json::obj();
            p["symbol"] = Json(f.def.symbol);
            p["bars"] = Json(f.pumpBars);
            p["pct"] = Json::dec(round6(f.pumpPct));
            pumps.push_back(p);
        }
        ci["pumps"] = pumps;
        ci["cheatState"] = cheat.toJson();
        Json rq = Json::arr();
        for (const RenewSpec& rs : renewQueue) {
            Json x = Json::obj();
            x["market"] = Json(rs.market);
            x["symbol"] = Json(rs.symbol);
            x["side"] = Json(rs.side);
            x["qty"] = Json(static_cast<double>(rs.qty));
            x["price"] = Json::dec(round4(rs.price));
            rq.push_back(x);
        }
        ci["renewQueue"] = rq;
        ci["newsCount"] = Json(static_cast<double>(news.items.size()));
        return ci;
    }

    // 失败响应（以 data 形式返回，exec 会转换）
    Json failJson(const std::string& code, const std::string& msg) const {
        Json j = Json::obj();
        j["__fail"] = Json(true);
        j["code"] = Json(code);
        j["message"] = Json(msg);
        return j;
    }

    // ================= 行情命令 =================
    Json cmdMarket(const Json& a) {
        std::string mk = jgetStr(a, "market", "all");
        std::string sym = jgetStr(a, "symbol", "");
        Json out = Json::obj();
        out["time"] = time.toJson();
        Json sarr = Json::arr();
        Json farr = Json::arr();
        bool wantStock = (mk == "stock" || mk == "all");
        bool wantForex = (mk == "forex" || mk == "all");
        if (!sym.empty()) {
            if (market.isForexSymbol(sym)) { wantStock = false; wantForex = true; }
            else if (market.findStock(sym) != nullptr) { wantStock = true; wantForex = false; }
        }
        if (wantStock) {
            const std::string cs = Market::canonical(sym);
            for (const Stock& s : market.stocks) {
                if (!sym.empty() && s.def.symbol != cs) continue;
                sarr.push_back(stockJson(s));
            }
        }
        if (wantForex) {
            const std::string us = toUpper(sym);
            for (const Forex& f : market.forex) {
                if (!sym.empty() && f.def.symbol != us) continue;
                farr.push_back(f.toJson());
            }
        }
        out["stocks"] = sarr;
        out["forex"] = farr;
        return out;
    }

    Json stockJson(const Stock& s) const {
        Json j = Json::obj();
        j["symbol"] = Json(s.def.symbol);
        j["name"] = Json(s.def.name);
        j["last"] = Json::dec(round4(s.last));
        j["prevClose"] = Json::dec(round4(s.prevClose));
        j["open"] = Json::dec(round4(s.open));
        j["high"] = Json::dec(round4(s.high));
        j["low"] = Json::dec(round4(s.low));
        j["changePct"] = Json::dec(round6(s.prevClose > 1e-9 ? (s.last - s.prevClose) / s.prevClose : 0.0));
        j["volume"] = Json(static_cast<double>(s.volume));
        j["bid"] = Json::dec(round4(s.bid));
        j["ask"] = Json::dec(round4(s.ask));
        j["halted"] = Json(s.halted);
        j["pe"] = Json::dec(round2(s.pe));
        Json h = Json::arr();
        for (const Bar& b : s.hist) h.push_back(b.toJson());
        j["hist"] = h;
        j["currency"] = Json(s.def.currency);
        return j;
    }

    Json cmdQuote(const Json& a) {
        std::string sym = jgetStr(a, "symbol", "");
        if (sym.empty()) return failJson(err::BAD_ARG, "参数错误: 缺少 symbol");
        const Stock* s = constStock(Market::canonical(sym));
        if (s) {
            Json j = Json::obj();
            j["symbol"] = Json(s->def.symbol);
            j["name"] = Json(s->def.name);
            j["market"] = Json("stock");
            // 股票按标的 digits(2) 输出，协议 §2 要求价格最多 4 位
            j["last"] = Json::dec(round2(s->last));
            j["bid"] = Json::dec(round2(s->bid));
            j["ask"] = Json::dec(round2(s->ask));
            j["prevClose"] = Json::dec(round2(s->prevClose));
            j["changePct"] = Json::dec(round6(s->prevClose > 1e-9 ? (s->last - s->prevClose) / s->prevClose : 0.0));
            j["halted"] = Json(s->halted);
            j["digits"] = Json(2);
            return j;
        }
        Forex* f = market.findForex(sym);
        if (f) {
            Json j = Json::obj();
            j["symbol"] = Json(f->def.symbol);
            j["name"] = Json(f->def.name);
            j["market"] = Json("forex");
            j["last"] = Json::dec(Json::roundTo(f->last, f->def.digits));
            j["bid"] = Json::dec(Json::roundTo(f->last - f->def.pip, f->def.digits));
            j["ask"] = Json::dec(Json::roundTo(f->last + f->def.pip, f->def.digits));
            j["prevClose"] = Json::dec(Json::roundTo(f->prevClose, f->def.digits));
            j["changePct"] = Json::dec(round6(f->prevClose > 1e-9 ? (f->last - f->prevClose) / f->prevClose : 0.0));
            j["halted"] = Json(false);
            j["digits"] = Json(f->def.digits);
            return j;
        }
        return failJson(err::NO_SUCH_SYMBOL, "未知代码 " + sym);
    }

    Json cmdOrders(const Json& a) {
        std::string mk = jgetStr(a, "market", "all");
        Json out = Json::obj();
        out["orders"] = ordersArray((mk == "stock" || mk == "forex") ? mk : "all");
        return out;
    }

    Json cmdHistory(const Json& a) {
        int limit = static_cast<int>(jgetInt(a, "limit", 50));
        if (limit <= 0) limit = 50;
        if (limit > 2000) limit = 2000;
        std::string mk = jgetStr(a, "market", "all");
        Json arr = Json::arr();
        int cnt = 0;
        for (auto it = trades.rbegin(); it != trades.rend() && cnt < limit; ++it) {
            if (mk == "stock" && it->market != "stock") continue;
            if (mk == "forex" && it->market != "forex") continue;
            arr.push_back(it->toJson());
            ++cnt;
        }
        Json out = Json::obj();
        out["trades"] = arr;
        return out;
    }

    Json cmdNews(const Json& a) {
        int limit = static_cast<int>(jgetInt(a, "limit", 20));
        if (limit <= 0) limit = 20;
        if (limit > 200) limit = 200;
        bool unreadOnly = jgetBool(a, "unreadOnly", false);
        Json out = news.listJson(limit, unreadOnly);
        if (jgetBool(a, "markRead", false)) news.setAllRead();
        return out;
    }

    Json cmdSettings(const Json& a) {
        if (jhasNum(a, "speed")) settings.speed = clampD(jgetNum(a, "speed", settings.speed), 0.25, 256.0);
        if (jhasBool(a, "t1")) { settings.t1 = jgetBool(a, "t1", settings.t1); cheat.t1 = settings.t1; autoCfg.enabled = settings.t1; }
        if (jhasBool(a, "autoRenew")) { settings.autoRenew = jgetBool(a, "autoRenew", settings.autoRenew); autoCfg.autoRenew = settings.autoRenew; }
        if (jhasBool(a, "autoStop")) { settings.autoStop = jgetBool(a, "autoStop", settings.autoStop); autoCfg.autoStop = settings.autoStop; }
        if (jhasNum(a, "commission")) settings.commission = clampD(jgetNum(a, "commission", settings.commission), 0.0, 0.05);
        if (jhasNum(a, "slippage")) settings.slippage = clampD(jgetNum(a, "slippage", settings.slippage), 0.0, 0.05);
        if (jhasNum(a, "tickMs")) settings.tickMs = clampD(jgetNum(a, "tickMs", settings.tickMs), 0.0, 60000.0);
        if (jhasNum(a, "stopLossPct")) autoCfg.stopLossPct = clampD(jgetNum(a, "stopLossPct", autoCfg.stopLossPct), 0.0, 0.99);
        if (jhasNum(a, "takeProfitPct")) autoCfg.takeProfitPct = clampD(jgetNum(a, "takeProfitPct", autoCfg.takeProfitPct), 0.0, 10.0);
        if (jhasStr(a, "difficulty")) {
            std::string d = jgetStr(a, "difficulty", difficulty);
            if (d == "easy" || d == "normal" || d == "hard") { difficulty = d; }
        }
        settings.difficulty = difficulty;
        settings.stopLossPct = autoCfg.stopLossPct;
        settings.takeProfitPct = autoCfg.takeProfitPct;
        return settings.toJson();
    }

    static double clampD(double v, double lo, double hi) {
        if (std::isnan(v) || std::isinf(v)) return lo;
        return std::min(hi, std::max(lo, v));
    }

    // ================= 交易：股票 =================
    double commissionRate() const {
        if (cheat.noCommission || cheat.godMode) return 0.0;
        return settings.commission;
    }
    double commissionOf(double amount) const { return round2(amount * commissionRate()); }

    Json cmdBuy(const Json& a) { return stockOrder(a, "buy"); }
    Json cmdSell(const Json& a) { return stockOrder(a, "sell"); }

    Json stockOrder(const Json& a, const std::string& side) {
        std::string sym = jgetStr(a, "symbol", "");
        if (sym.empty()) return failJson(err::BAD_ARG, "参数错误: 缺少 symbol");
        Stock* s = market.findStock(sym);
        if (!s) {
            if (market.isForexSymbol(sym))
                return failJson(err::BAD_ARG, "外汇请使用 forex open/close 命令");
            return failJson(err::NO_SUCH_SYMBOL, "未知代码 " + sym);
        }
        const std::string S = s->def.symbol;
        if (s->halted) return failJson(err::MARKET_HALTED, "该标的已停牌: " + S);
        if (s->last <= 0.0) return failJson(err::NO_MARKET_DATA, "行情数据缺失: " + S);
        if (!jhas(a, "qty")) return failJson(err::BAD_ARG, "参数错误: 缺少 qty");
        if (!jhasNum(a, "qty")) return failJson(err::BAD_ARG, "参数错误: qty 必须是数字");
        {
            double qd = jgetNum(a, "qty", 0.0);
            if (std::isnan(qd) || std::isinf(qd)) return failJson(err::BAD_ARG, "参数错误: qty 非法");
            if (std::fabs(qd) > 9.0e15) return failJson(err::BAD_QTY, "股票数量超出范围");
            if (std::fabs(qd - std::round(qd)) > 1e-9) return failJson(err::BAD_QTY, "股票数量必须是整数股");
        }
        long long qty = jgetInt(a, "qty", 0);
        if (qty <= 0 || qty % 100 != 0)
            return failJson(err::BAD_QTY, "股票数量必须是100的整数倍");
        std::string type = jgetStr(a, "type", "market");
        if (type != "market" && type != "limit")
            return failJson(err::BAD_ARG, "参数错误: type 必须为 market 或 limit（当前 " + type + "）");
        double price = 0.0;
        if (type == "limit") {
            if (!jhasNum(a, "price")) return failJson(err::BAD_ARG, "参数错误: 限价单缺少 price");
            price = jgetNum(a, "price", 0.0);
            if (!(price > 0.0) || std::isnan(price) || std::isinf(price))
                return failJson(err::BAD_PRICE, "价格非法: " + fmtNum(price));
            price = round4(price);
        }

        double execPrice = (type == "market") ? marketBuySellPrice(*s, side, qty)
                                              : (side == "buy" ? price : price);
        if (type == "market" && execPrice <= 0.0) return failJson(err::NO_MARKET_DATA, "行情数据缺失: " + S);

        if (side == "buy") {
            double need = round2(execPrice * static_cast<double>(qty));
            double fee = commissionOf(need);
            double total = round2(need + fee);
            double available = stockAcc.cash;
            if (cheat.infiniteMoney) {
                if (available < total) {
                    stockAcc.cash = total;
                    available = total;
                }
            }
            if (available + 1e-9 < total) {
                return failJson(err::INSUFFICIENT_CASH,
                                "可用资金不足: 需要 " + fmtMoney(total) + "，可用 " + fmtMoney(available));
            }
            stockAcc.cash = round2(stockAcc.cash - total);
            if (type == "limit") {
                double frozenUse = cheat.godMode ? 0.0 : total;
                stockAcc.frozen = round2(stockAcc.frozen + frozenUse);
                return limitResult(side, S, qty, price, stockAcc.cash);
            }
            recordBuy(S, qty, execPrice, need, fee);
            return marketResult(side, S, qty, execPrice, fee, stockAcc.cash);
        } else {
            long long sellable = stockAcc.sellable(S);
            const StockPosition* pos = stockAcc.find(S);
            long long todayBought = pos ? pos->todayBought : 0;
            if (type == "limit") {
                const StockPosition* p2 = stockAcc.find(S);
                long long locked = (p2 ? p2->frozenQty : 0) + todayBought;
                sellable = (p2 ? p2->qty : 0) - locked;
                if (sellable < 0) sellable = 0;
            }
            if (qty > sellable) {
                if (todayBought > 0 && qty <= (pos ? pos->qty - pos->frozenQty : 0)) {
                    return failJson(err::T1_LOCKED,
                                    "T+1 锁定：当日买入 " + std::to_string(todayBought) +
                                    " 股需次日开盘后可卖");
                }
                return failJson(err::INSUFFICIENT_POSITION,
                                "可卖持仓不足: 需要 " + std::to_string(qty) + " 股，可卖 " + std::to_string(sellable) + " 股");
            }
            if (type == "limit") {
                StockPosition& p = stockAcc.ensure(S, s->def.name);
                p.frozenQty += qty;
                return limitResult(side, S, qty, price, stockAcc.cash);
            }
            double gross = round2(execPrice * static_cast<double>(qty));
            double fee = commissionOf(gross);
            recordSell(S, qty, execPrice, gross, fee, "manual");
            stockAcc.cash = round2(stockAcc.cash + gross - fee);
            return marketResult(side, S, qty, execPrice, fee, stockAcc.cash);
        }
    }

    double marketBuySellPrice(const Stock& s, const std::string& side, long long qty) const {
        double slip = settings.slippage * (1.0 + std::min(1.0, static_cast<double>(qty) / 10000.0));
        double px = (side == "buy") ? s.ask * (1.0 + slip) : s.bid * (1.0 - slip);
        return round4(px);
    }

    // 市价单即时成交：不留在订单簿（快照 orders 只含挂单/部分成交）
    Json marketResult(const std::string& side, const std::string& sym, long long qty,
                      double avgPrice, double commission, double cash) {
        Json j = Json::obj();
        j["orderId"] = Json(book.nextId++);
        j["status"] = Json("filled");
        j["filled"] = Json(static_cast<double>(qty));
        j["avgPrice"] = Json::dec(round4(avgPrice));
        j["commission"] = Json::dec(round2(commission));
        j["cash"] = Json::dec(round2(cash));
        (void)side;
        (void)sym;
        return j;
    }

    Json limitResult(const std::string& side, const std::string& sym, long long qty,
                     double price, double cash) {
        Json j = Json::obj();
        Order o;
        o.symbol = sym;
        o.market = "stock";
        o.side = side;
        o.type = "limit";
        o.qty = qty;
        o.filled = 0;
        o.price = price;
        o.status = "open";
        o.created = time;
        o.ttlSlots = limitTtlSlots;          // 协议 §3.8a：限价单 TTL = 20 个时间片
        o.expireAtAbsSlot = time.absSlot() + limitTtlSlots;
        Order& added = book.add(o);
        j["orderId"] = Json(added.id);
        j["status"] = Json("open");
        j["filled"] = Json(0.0);
        j["avgPrice"] = Json::dec(round4(price));
        j["commission"] = Json::dec(0.0);
        j["cash"] = Json::dec(round2(cash));
        return j;
    }

    // 限价成交用：按含费成本/股记账
    void recordBuyAvg(const std::string& sym, long long qty, double costInclFee, double lastPrice) {
        if (qty <= 0) return;
        StockPosition& p = stockAcc.ensure(sym, nameOf(sym));
        double oldCost = p.avgCost * static_cast<double>(p.qty);
        p.qty += qty;
        p.avgCost = p.qty > 0 ? round4((oldCost + costInclFee) / static_cast<double>(p.qty)) : 0.0;
        if (settings.t1) p.todayBought += qty;
        double gross = round2(lastPrice * static_cast<double>(qty));
        double fee = round2(costInclFee - gross);
        totalCommission = round2(totalCommission + fee);
        addTrade("stock", sym, "buy", qty, lastPrice, gross, fee, 0.0, "manual", false);
    }

    void recordBuy(const std::string& sym, long long qty, double price, double amount, double fee) {
        StockPosition& p = stockAcc.ensure(sym, nameOf(sym));
        double oldCost = p.avgCost * static_cast<double>(p.qty);
        double addCost = amount + fee;
        p.qty += qty;
        p.avgCost = p.qty > 0 ? round4((oldCost + addCost) / static_cast<double>(p.qty)) : 0.0;
        if (settings.t1) p.todayBought += qty;
        totalCommission = round2(totalCommission + fee);
        addTrade("stock", sym, "buy", qty, price, amount, fee, 0.0, "manual", false);
    }

    double recordSell(const std::string& sym, long long qty, double price, double amount, double fee,
                      const std::string& reason) {
        StockPosition* p = stockAcc.find(sym);
        if (!p) return 0.0;
        double costBasis = p->avgCost * static_cast<double>(qty);
        double realized = round2(amount - fee - costBasis);
        p->qty -= qty;
        if (p->qty < 0) p->qty = 0;
        if (p->todayBought > p->qty) p->todayBought = p->qty;
        realizedPnl = round2(realizedPnl + realized);
        totalCommission = round2(totalCommission + fee);
        if (realized > 0) ++winCount;
        addTrade("stock", sym, "sell", qty, price, amount, fee, realized, reason, true);
        stockAcc.dropEmpty();
        return realized;
    }

    std::string nameOf(const std::string& sym) const {
        const Stock* s = constStock(sym);
        if (s) return s->def.name;
        Forex* f = nullptr;
        for (const Forex& x : market.forex) if (x.def.symbol == sym) { f = const_cast<Forex*>(&x); break; }
        if (f) return f->def.name;
        return sym;
    }

    void addTrade(const std::string& mkt, const std::string& sym, const std::string& side, long long qty,
                  double price, double amount, double fee, double realized, const std::string& reason,
                  bool counted) {
        TradeRecord t;
        t.seq = ++tradeSeq;
        t.time = time;
        t.market = mkt;
        t.symbol = sym;
        t.side = side;
        t.qty = qty;
        t.price = price;
        t.amount = amount;
        t.commission = fee;
        t.realizedPnl = realized;
        t.reason = reason;
        trades.push_back(t);
        if (counted) ++tradeCount;
        if (trades.size() > 4000) trades.erase(trades.begin(), trades.begin() + 1000);
    }

    // ================= 撤单 =================
    Json cmdCancel(const Json& a) {
        if (!jhasNum(a, "orderId")) return failJson(err::BAD_ARG, "参数错误: 缺少 orderId");
        int id = static_cast<int>(jgetInt(a, "orderId", 0));
        Order* o = book.find(id);
        if (!o || !o->isActive()) return failJson(err::BAD_ARG, "订单不存在或已终态: " + std::to_string(id));
        double refund = 0.0;
        if (o->market == "stock") {
            if (o->side == "buy") {
                double unit = cheat.godMode ? 0.0 : o->price;
                double amt = round2(unit * static_cast<double>(o->remaining()));
                double fee = cheat.godMode ? 0.0 : commissionOf(amt);
                refund = round2(amt + fee);
                stockAcc.frozen = round2(stockAcc.frozen - refund);
                if (stockAcc.frozen < 0) stockAcc.frozen = 0;
                stockAcc.cash = round2(stockAcc.cash + refund);
            } else {
                StockPosition* p = stockAcc.find(o->symbol);
                if (p) {
                    p->frozenQty -= o->remaining();
                    if (p->frozenQty < 0) p->frozenQty = 0;
                }
            }
        }
        o->status = "cancelled";
        addEventOrderCancelled(*o);
        Json j = Json::obj();
        j["orderId"] = Json(id);
        j["cancelled"] = Json(true);
        j["refundedCash"] = Json::dec(round2(refund));
        return j;
    }

    std::vector<Json> pendingEvents;

    void addEventOrderCancelled(const Order& o) {
        Json e = Json::obj();
        e["kind"] = Json("order_cancelled");
        e["symbol"] = Json(o.symbol);
        e["side"] = Json(o.side);
        e["qty"] = Json(static_cast<double>(o.remaining()));
        e["price"] = Json::dec(round4(o.price));
        e["note"] = Json("订单已撤销");
        e["at"] = time.toJson();
        pendingEvents.push_back(e);
    }

    // ================= 外汇 =================
    Json cmdForex(const Json& a) {
        std::string op = toLower(jgetStr(a, "op", jgetStr(a, "action", "")));
        // 协议 §3.9 冻结 open/close；同时接受常见别名，避免前端调用失败
        if (op == "long" || op == "buy" || op == "openPosition" || op == "openposition") op = "open";
        if (op == "short" || op == "sell") op = "open";
        if (op == "closeposition" || op == "flat" || op == "closeall") op = "close";
        if (op.empty()) return failJson(err::BAD_ARG, "参数错误: 缺少 op(open/close)");
        if (op == "open") return forexOpen(a);
        if (op == "close") return forexClose(a);
        if (op == "list") {
            Json out = Json::obj();
            Json arr = Json::arr();
            for (const ForexPosition& p : forexAcc.positions) {
                Json fp = Json::obj();
                fp["positionId"] = Json(p.id);
                fp["symbol"] = Json(p.symbol);
                fp["name"] = Json(p.name);
                fp["side"] = Json(p.side);
                fp["lots"] = Json(static_cast<double>(p.lots));
                fp["openRate"] = Json::dec(Json::roundTo(p.openRate, 6));
                fp["last"] = Json::dec(Json::roundTo(p.last, 6));
                fp["margin"] = Json::dec(round2(p.margin));
                fp["pnl"] = Json::dec(round2(p.pnlAt(p.last)));
                fp["swap"] = Json::dec(round2(p.swap));
                fp["stopLoss"] = Json::dec(Json::roundTo(p.stopLoss, 6));
                fp["takeProfit"] = Json::dec(Json::roundTo(p.takeProfit, 6));
                arr.push_back(fp);
            }
            out["positions"] = arr;
            return out;
        }
        return failJson(err::BAD_ARG, "参数错误: 未知 op " + op);
    }

    Json forexOpen(const Json& a) {
        std::string sym = jgetStr(a, "symbol", "");
        if (sym.empty()) return failJson(err::BAD_ARG, "参数错误: 缺少 symbol");
        Forex* f = market.findForex(sym);
        if (!f) return failJson(err::NO_SUCH_SYMBOL, "未知代码 " + sym);
        std::string side = toLower(jgetStr(a, "side", "long"));
        if (side != "long" && side != "short") return failJson(err::BAD_ARG, "参数错误: side 必须为 long 或 short");
        double lots = jgetNum(a, "lots", 0.0);
        if (!(lots > 0.0) || std::isnan(lots) || std::isinf(lots)) return failJson(err::BAD_LOTS, "手数非法");
        if (std::fabs(lots - std::round(lots)) > 1e-9)
            return failJson(err::BAD_LOTS, "手数非法: 必须是正整数手数（当前 " + fmtNum(lots) + "）");
        // 超大手数归为保证金不足（协议 §3.9），不归 BAD_LOTS
        if (lots > 10000.0)
            return failJson(err::INSUFFICIENT_MARGIN,
                            "保证金不足: 手数 " + fmtNum(lots) + " 超出可承受范围");
        double lev = jgetNum(a, "leverage", 100.0);
        if (jhas(a, "leverage")) {
            if (!(lev >= 1.0) || lev > 1000.0 || std::isnan(lev) || std::isinf(lev))
                return failJson(err::BAD_ARG, "参数错误: leverage 必须在 1..1000 之间（当前 " + fmtNum(lev) + "）");
        } else {
            lev = 100.0;
        }
        double sl = jgetNum(a, "stopLoss", 0.0);
        double tp = jgetNum(a, "takeProfit", 0.0);
        if (!(sl >= 0.0)) sl = 0.0;
        if (!(tp >= 0.0)) tp = 0.0;

        double px = f->last;
        if (px <= 0.0) return failJson(err::NO_MARKET_DATA, "行情数据缺失: " + f->def.symbol);
        double margin = round2(lots * 1000.0 * px / lev);
        double fee = commissionOf(margin * 0.0 + lots * 1000.0 * px * 0.00002);
        if (cheat.noCommission || cheat.godMode) fee = 0.0;

        if (!cheat.godMode) {
            double freeM = forexEquity() - forexAcc.usedMargin();
            if (freeM + 1e-9 < margin + fee) {
                return failJson(err::INSUFFICIENT_MARGIN,
                                "保证金不足: 需要 " + fmtMoney(margin + fee) + "，可用 " + fmtMoney(freeM));
            }
        }
        forexAcc.cash = round2(forexAcc.cash - fee);
        totalCommission = round2(totalCommission + fee);

        ForexPosition p;
        p.id = forexAcc.nextPositionId++;
        p.symbol = f->def.symbol;
        p.name = f->def.name;
        p.side = side;
        p.lots = lots;
        p.openRate = f->last;
        p.last = f->last;
        p.margin = margin;
        p.swap = 0.0;
        p.stopLoss = sl;
        p.takeProfit = tp;
        forexAcc.positions.push_back(p);

        addTrade("forex", f->def.symbol, side, static_cast<long long>(std::llround(lots * 1000.0)),
                 f->last, round2(lots * 1000.0 * f->last), fee, 0.0, "manual", false);

        if (sl > 0.0 || tp > 0.0) rememberStop(f->def.symbol, side, sl, tp);

        Json j = Json::obj();
        j["positionId"] = Json(p.id);
        j["lots"] = Json(static_cast<double>(lots));
        j["openRate"] = Json::dec(Json::roundTo(f->last, f->def.digits));
        j["margin"] = Json::dec(margin);
        j["swap"] = Json::dec(0.0);
        return j;
    }

    Json forexClose(const Json& a) {
        bool hasId = jhasNum(a, "positionId");
        if (!hasId) return failJson(err::BAD_ARG, "参数错误: 缺少 positionId");
        int id = static_cast<int>(jgetInt(a, "positionId", -1));
        ForexPosition* p = forexAcc.find(id);
        if (!p) return failJson(err::NO_POSITION, "没有该持仓: " + std::to_string(id));
        double lots = p->lots;
        if (jhasNum(a, "lots")) {
            double l = jgetNum(a, "lots", 0.0);
            if (!(l > 0.0)) return failJson(err::BAD_LOTS, "手数非法");
            if (l < lots - 1e-9) lots = l;
        }
        Forex* f = market.findForex(p->symbol);
        if (!f) return failJson(err::NO_SUCH_SYMBOL, "未知代码 " + p->symbol);
        double px = f->last;
        double pnlPortion = p->pnlAt(px) * (lots / p->lots);
        double swapPortion = p->swap * (lots / p->lots);
        double marginPortion = p->margin * (lots / p->lots);
        (void)marginPortion;
        double fee = lots * 1000.0 * px * 0.00002;
        if (cheat.noCommission || cheat.godMode) fee = 0.0;
        double net = round2(pnlPortion + swapPortion - fee);
        forexAcc.cash = round2(forexAcc.cash + net);
        forexAcc.realized = round2(forexAcc.realized + net);
        realizedPnl = round2(realizedPnl + net);
        totalCommission = round2(totalCommission + fee);
        if (net > 0) ++winCount;

        std::string sideForRecord = p->side;
        std::string symForRecord = p->symbol;
        p->lots = round4(p->lots - lots);
        if (p->lots <= 1e-9) {
            p->lots = 0.0;
            p->margin = 0.0;
            forexAcc.dropEmpty();
        } else {
            p->margin = round2(p->margin * (p->lots / (p->lots + lots)));
            p->swap = round4(p->swap * (p->lots / (p->lots + lots)));
        }
        addTrade("forex", symForRecord, sideForRecord, static_cast<long long>(std::llround(lots * 1000.0)),
                 px, round2(lots * 1000.0 * px), fee, net, "manual", true);

        Json j = Json::obj();
        j["positionId"] = Json(id);
        j["lots"] = Json(static_cast<double>(lots));
        j["closeRate"] = Json(Json::roundTo(px, f->def.digits));
        j["pnl"] = Json::dec(round2(pnlPortion + swapPortion));
        j["commission"] = Json::dec(round2(fee));
        j["cash"] = Json::dec(round2(forexAcc.cash));
        return j;
    }

    void rememberStop(const std::string& sym, const std::string& side, double sl, double tp) {
        for (StopSpec& s : stopSpecs) {
            if (s.symbol == sym && s.side == side) { s.stopLoss = sl; s.takeProfit = tp; return; }
        }
        StopSpec s;
        s.symbol = sym;
        s.side = side;
        s.stopLoss = sl;
        s.takeProfit = tp;
        stopSpecs.push_back(s);
    }
    StopSpec* findStop(const std::string& sym, const std::string& side) {
        for (StopSpec& s : stopSpecs) if (s.symbol == sym && s.side == side) return &s;
        return nullptr;
    }

    // ================= 时间推进 =================
    void setTimeAbs(long long a) {
        time.setAbs(a);
    }

    long long absSlot() const { return time.absSlot(); }

    // 生成一个 slot 的行情
    void generateBar() {
        for (Stock& s : market.stocks) {
            if (s.halted) {
                Bar b;
                b.date = time.dateStr();
                b.slot = time.slot;
                b.open = b.high = b.low = b.close = s.last;
                b.volume = 0;
                pushBar(s.hist, b);
                continue;
            }
            double prev = s.last;
            // 波动率聚集：vol 向长期均值回复，并受最近冲击影响
            double longVol = s.def.baseVol;
            s.vol = s.vol * 0.94 + longVol * 0.06 + std::fabs(rng.normal()) * longVol * 0.05;
            s.vol = clampD(s.vol, longVol * 0.35, longVol * 4.0);
            double drift = -0.15 * std::log(std::max(1e-6, prev / (s.def.basePrice * s.fair)));
            double shock = 0.0;
            if (s.pumpBars > 0) {
                shock += s.pumpPct / static_cast<double>(std::max(1, s.pumpBars));
                if (--s.pumpBars <= 0) { s.pumpBars = 0; s.pumpPct = 0.0; }
            }
            double r = drift + s.vol * rng.normal() + shock;
            if (r > 0.30) r = 0.30;
            if (r < -0.30) r = -0.30;
            double np = prev * (1.0 + r);
            if (np < 0.01) np = 0.01;
            s.last = round4(np);
            Bar b;
            b.date = time.dateStr();
            b.slot = time.slot;
            b.open = round4(prev);
            b.close = s.last;
            double hi = std::max(b.open, b.close) * (1.0 + std::fabs(rng.normal()) * s.vol * 0.4);
            double lo = std::min(b.open, b.close) * (1.0 - std::fabs(rng.normal()) * s.vol * 0.4);
            b.high = round4(std::max(hi, std::max(b.open, b.close)));
            b.low = round4(std::max(0.01, std::min(lo, std::min(b.open, b.close))));
            b.volume = static_cast<long long>(10000 + std::fabs(r) * 5.0e6 / std::max(0.5, s.def.basePrice) * rng.range(0.4, 1.6));
            s.volume += b.volume;
            if (s.last > s.high) s.high = s.last;
            if (s.last < s.low) s.low = s.last;
            s.refreshSpread();
            pushBar(s.hist, b);
        }
        for (Forex& f : market.forex) {
            double prev = f.last;
            double longVol = f.def.basePrice * 0.0007;
            f.vol = f.vol * 0.93 + longVol * 0.07 + std::fabs(rng.normal()) * longVol * 0.06;
            f.vol = clampD(f.vol, f.def.basePrice * 0.0002, f.def.basePrice * 0.006);
            double drift = -0.1 * std::log(std::max(1e-6, prev / (f.def.basePrice * f.fair)));
            double shock = 0.0;
            if (f.pumpBars > 0) {
                shock += f.pumpPct / static_cast<double>(std::max(1, f.pumpBars));
                if (--f.pumpBars <= 0) { f.pumpBars = 0; f.pumpPct = 0.0; }
            }
            double r = drift + f.vol * rng.normal() + shock;
            if (r > 0.10) r = 0.10;
            if (r < -0.10) r = -0.10;
            double np = prev * (1.0 + r);
            if (np <= 0.000001) np = 0.000001;
            f.last = Json::roundTo(np, f.def.digits);
            Bar b;
            b.date = time.dateStr();
            b.slot = time.slot;
            b.open = Json::roundTo(prev, f.def.digits);
            b.close = f.last;
            double hi = std::max(b.open, b.close) * (1.0 + std::fabs(rng.normal()) * f.vol * 0.4);
            double lo = std::min(b.open, b.close) * (1.0 - std::fabs(rng.normal()) * f.vol * 0.4);
            b.high = Json::roundTo(std::max(hi, std::max(b.open, b.close)), f.def.digits);
            b.low = Json::roundTo(std::max(0.000001, std::min(lo, std::min(b.open, b.close))), f.def.digits);
            b.volume = static_cast<long long>(50000 + std::fabs(r) * 2.0e7 * rng.range(0.4, 1.6));
            if (f.last > f.high) f.high = f.last;
            if (f.last < f.low) f.low = f.last;
            pushBar(f.hist, b);
        }
        // 外汇浮动盈亏随最新价刷新
        for (ForexPosition& p : forexAcc.positions) {
            Forex* f = market.findForex(p.symbol);
            if (f) p.last = f->last;
        }
    }

    static void pushBar(std::vector<Bar>& hist, const Bar& b) {
        hist.push_back(b);
        while (hist.size() > 60) hist.erase(hist.begin());
    }

    Json cmdTick(const Json& a) {
        if (jhas(a, "n") && !jhasNum(a, "n"))
            return failJson(err::BAD_ARG, "参数错误: n 必须是数字");
        long long n = jgetInt(a, "n", 1);
        if (n < 1 || n > 2000)
            return failJson(err::BAD_ARG, "参数错误: n 必须在 1..2000 之间（当前 " + std::to_string(n) + "）");
        Json modeJ = a.find("mode") ? a["mode"] : Json("auto");
        bool autoMode = true;
        if (modeJ.isStr()) autoMode = (modeJ.asStr() == "auto");
        std::vector<Json> events;
        advanceSlots(static_cast<int>(n), autoMode, events);
        Json out = Json::obj();
        out["time"] = time.toJson();
        out["advanced"] = Json(static_cast<double>(n));
        Json ev = Json::arr();
        for (const Json& e : events) ev.push_back(e);
        out["events"] = ev;
        Json ha = Json::arr();
        for (const Stock& s : market.stocks) if (s.halted) ha.push_back(Json(s.def.symbol));
        out["halted"] = ha;
        return out;
    }

    // 推进 n 个 slot
    void advanceSlots(int n, bool autoMode, std::vector<Json>& events) {
        if (n < 1) return;
        if (n > 5000) n = 5000;
        for (int i = 0; i < n; ++i) {
            stepOneSlot(autoMode, events);
        }
    }

    void stepOneSlot(bool autoMode, std::vector<Json>& events) {
        // 1) 结算日结束（当前 slot==3）先做收盘处理
        if (time.slot == 3) {
            settleDay(events);
        }
        // 2) 推进时间
        long long a = absSlot() + 1;
        setTimeAbs(a);
        if (time.slot == 0) {
            onDayOpen(autoMode, events);
        }
        // 3) 生成新 slot 行情
        generateBar();
        // 4) 撮合挂单
        matchOrders(events);
        // 5) 自动策略
        if (autoMode) runAutoAtSlot(events);
        // 6) 新闻
        maybeNews(events);
        // 7) 外汇强平检查
        checkMargin(events);
        // 8) 协议 §3.8a：限价挂单 TTL 在每个时间片结束时递减
        expireOrders(events);
    }

    void onDayOpen(bool autoMode, std::vector<Json>& events) {
        (void)autoMode;
        for (Stock& s : market.stocks) {
            s.prevClose = s.last;
            s.open = s.last;
            s.high = s.last;
            s.low = s.last;
            s.volume = 0;
        }
        for (Forex& f : market.forex) {
            f.prevClose = f.last;
            f.open = f.last;
            f.high = f.last;
            f.low = f.last;
        }
        sessionDayStartEquity = totalEquity();
        // 分红（确定性伪随机：按日期，约 8% 的交易日）
        double r = rng.uniform();
        if (r < 0.08 && !stockAcc.positions.empty()) {
            for (StockPosition& p : stockAcc.positions) {
                if (p.qty <= 0) continue;
                const Stock* s = constStock(p.symbol);
                if (!s) continue;
                double dps = round4(s->last * 0.01);
                double div = round2(dps * static_cast<double>(p.qty));
                if (div <= 0) continue;
                stockAcc.cash = round2(stockAcc.cash + div);
                dividendTotal = round2(dividendTotal + div);
                realizedPnl = round2(realizedPnl + div);
                Json e = Json::obj();
                e["kind"] = Json("dividend");
                e["symbol"] = Json(p.symbol);
                e["qty"] = Json(static_cast<double>(p.qty));
                e["amount"] = Json::dec(div);
                e["note"] = Json("每10股派息 " + fixedStr(dps * 10.0, 2) + " 元");
                e["at"] = time.toJson();
                events.push_back(e);
                addTrade("stock", p.symbol, "buy", 0, s->last, div, 0.0, div, "dividend", false);
            }
        }
        if (settings.t1) {
            bool anyLocked = false;
            for (const StockPosition& p : stockAcc.positions) if (p.todayBought > 0) anyLocked = true;
            if (anyLocked) {
                for (const StockPosition& p : stockAcc.positions) {
                    if (p.todayBought <= 0) continue;
                    Json ev = Json::obj();
                    ev["kind"] = Json("t1_unlock");
                    ev["symbol"] = Json(p.symbol);
                    ev["qty"] = Json(static_cast<double>(p.todayBought));
                    ev["note"] = Json("T+1 解锁：当日买入的 " + std::to_string(p.todayBought) + " 股今日可卖");
                    ev["at"] = time.toJson();
                    events.push_back(ev);
                }
                stockAcc.unlockT1();
            }
        }
    }

    // 协议 §3.8a：限价挂单 TTL（默认 20 个时间片 = 5 个交易日）
    // 每个时间片结束时递减剩余寿命；到期仍未完全成交 -> status="expired"、
    // 解冻剩余资金/持仓、发 order_expired 事件、不再参与撮合。
    static const long long kLimitTtlSlots = 20;
    long long limitTtlSlots = kLimitTtlSlots;

    void expireOrders(std::vector<Json>& events) {
        for (Order& o : book.orders) {
            if (!o.isActive()) continue;
            if (o.ttlSlots <= 0) continue;         // 0 = 不过期
            if (--o.ttlSlots > 0) continue;        // 本时间片结束仍未到期
            long long rem = o.remaining();
            // 记录事件（字段按 §3.8a：orderId/symbol/side/qty/filled/at）
            Json e = Json::obj();
            e["kind"] = Json("order_expired");
            e["orderId"] = Json(o.id);
            e["symbol"] = Json(o.symbol);
            e["side"] = Json(o.side);
            e["qty"] = Json(static_cast<double>(o.qty));
            e["filled"] = Json(static_cast<double>(o.filled));
            e["remaining"] = Json(static_cast<double>(rem));
            e["price"] = Json::dec(round4(o.price));
            e["note"] = Json("挂单超过 " + std::to_string(kLimitTtlSlots) + " 个时间片未成交，已过期");
            e["at"] = time.toJson();
            events.push_back(e);
            // 解冻
            if (o.market == "stock" && o.side == "buy") {
                double unit = cheat.godMode ? 0.0 : o.price;
                double amt = round2(unit * static_cast<double>(rem));
                double fee = cheat.godMode ? 0.0 : commissionOf(amt);
                double refund = round2(amt + fee);
                stockAcc.frozen = round2(stockAcc.frozen - refund);
                if (stockAcc.frozen < 0) stockAcc.frozen = 0;
                stockAcc.cash = round2(stockAcc.cash + refund);
            } else if (o.market == "stock" && o.side == "sell") {
                StockPosition* sp = stockAcc.find(o.symbol);
                if (sp) {
                    sp->frozenQty -= rem;
                    if (sp->frozenQty < 0) sp->frozenQty = 0;
                }
            }
            o.status = "expired";
        }
    }

    void settleDay(std::vector<Json>& events) {
        // 外汇隔夜利息
        for (ForexPosition& p : forexAcc.positions) {
            double daily = p.margin * 0.0002 * (p.side == "long" ? 1.0 : -1.0);
            if (cheat.godMode) daily = 0.0;
            p.swap = round4(p.swap + daily);
        }
        if (!forexAcc.positions.empty()) {
            Json e = Json::obj();
            e["kind"] = Json("fx_swap");
            e["account"] = Json("forex");
            e["amount"] = Json::dec(round2(forexAcc.swapSum()));
            e["note"] = Json("外汇隔夜利息结算");
            e["at"] = time.toJson();
            events.push_back(e);
        }
        book.purgeDone(time);
        stockAcc.dropEmpty();
        forexAcc.dropEmpty();
    }

    void maybeNews(std::vector<Json>& events) {
        double p = time.isSettleSlot() ? 0.5 : 0.12;
        if (rng.uniform() >= p) return;
        Json at = time.toJson();
        news.makeRandom(time, rng, market, cheat.winRate, true, &events, at);
    }

    // 协议 §3.8b：外汇爆仓检查
    // marginLevel = equity / margin * 100（无持仓 0.0）；< 50 触发强平；
    // 从亏损最大的持仓开始平，直到 marginLevel >= 50 或持仓清空；
    // 击穿 50% 且 equity <= 0 -> 追加 bankrupt 事件。
    void checkMargin(std::vector<Json>& events) {
        if (cheat.godMode) return;
        if (forexAcc.positions.empty()) return;
        double margin = forexAcc.usedMargin();
        if (margin <= 1e-9) return;
        if (forexEquity() / margin * 100.0 >= 50.0) return;

        std::vector<ForexPosition> pos = forexAcc.positions;
        std::sort(pos.begin(), pos.end(), [](const ForexPosition& a, const ForexPosition& b) {
            return a.pnlAt(a.last) < b.pnlAt(b.last);
        });
        for (const ForexPosition& pp : pos) {
            ForexPosition* p = forexAcc.find(pp.id);
            if (!p) continue;
            Forex* f = market.findForex(p->symbol);
            if (!f) continue;
            double net = round2(p->pnlAt(f->last) + p->swap);
            double lvlBefore = forexEquity() / std::max(1e-9, forexAcc.usedMargin()) * 100.0;
            int pid = p->id;
            double lots = p->lots;
            std::string sym = p->symbol;
            std::string side = p->side;
            forexAcc.cash = round2(forexAcc.cash + net);
            forexAcc.realized = round2(forexAcc.realized + net);
            realizedPnl = round2(realizedPnl + net);

            Json ev = Json::obj();
            ev["kind"] = Json("margin_call");
            ev["account"] = Json("forex");
            ev["positionId"] = Json(pid);
            ev["lots"] = Json(static_cast<double>(lots));
            ev["level"] = Json::dec(round2(lvlBefore));
            ev["loss"] = Json::dec(net);
            ev["symbol"] = Json(sym);
            ev["side"] = Json(side);
            ev["note"] = Json("保证金率低于 50%，强制平仓 " + sym);
            ev["at"] = time.toJson();
            events.push_back(ev);

            addTrade("forex", sym, side, static_cast<long long>(std::llround(lots * 1000.0)),
                     f->last, round2(lots * 1000.0 * f->last), 0.0, net, "liquidation", true);
            p->lots = 0.0;
            forexAcc.dropMargin();
            forexAcc.dropEmpty();

            double m2 = forexAcc.usedMargin();
            if (m2 <= 1e-9) break;
            if (forexEquity() / m2 * 100.0 >= 50.0) break;
        }
        if (forexEquity() <= 0.0 && !bankruptForex.load()) {
            bankruptForex = true;
            Json ev = Json::obj();
            ev["kind"] = Json("bankrupt");
            ev["account"] = Json("forex");
            ev["note"] = Json("外汇账户爆仓");
            ev["at"] = time.toJson();
            events.push_back(ev);
        }
    }

    void matchOrders(std::vector<Json>& events) {
        std::vector<int> ids = book.matchOrderCandidates();
        for (int id : ids) {
            Order* o = book.find(id);
            if (!o || !o->isActive()) continue;
            if (o->market != "stock") continue;
            Stock* s = market.findStock(o->symbol);
            if (!s || s->halted) continue;
            long long rem = o->remaining();
            if (rem <= 0) continue;
            // 市价单：立即按对手价成交
            if (o->type == "market") {
                double px = marketBuySellPrice(*s, o->side, rem);
                processStockFill(*o, s, rem, px, events);
                continue;
            }
            // 限价单：价格优先撮合
            bool can = false;
            double px = o->price;
            if (o->side == "buy" && s->low <= o->price + 1e-9) {
                can = true;
                px = std::min(o->price, s->ask);
                if (px > o->price) px = o->price;
            } else if (o->side == "sell" && s->high >= o->price - 1e-9) {
                can = true;
                px = std::max(o->price, s->bid);
                if (px < o->price) px = o->price;
            }
            if (!can) continue;
            long long fillQty = rem;
            if (o->side == "sell") {
                StockPosition* p = stockAcc.find(o->symbol);
                long long avail = p ? p->frozenQty : 0;
                if (fillQty > avail) fillQty = avail;
            }
            if (fillQty <= 0) {
                o->status = "cancelled";
                continue;
            }
            processStockFill(*o, s, fillQty, px, events);
        }
        book.purgeDone(time);
    }

    void processStockFill(Order& o, Stock* s, long long qty, double px, std::vector<Json>& events) {
        if (qty <= 0 || !s) return;
        px = round4(px);
        if (o.side == "buy") {
            // 释放冻结，按实际成交价结算
            double frozenUnit = cheat.godMode ? 0.0 : o.price;
            double releaseAmt = round2(frozenUnit * static_cast<double>(qty));
            double releaseFee = cheat.godMode ? 0.0 : commissionOf(releaseAmt);
            stockAcc.frozen = round2(stockAcc.frozen - releaseAmt - releaseFee);
            if (stockAcc.frozen < 0) stockAcc.frozen = 0;
            stockAcc.cash = round2(stockAcc.cash + releaseAmt + releaseFee);
            double amount = round2(px * static_cast<double>(qty));
            double fee = commissionOf(amount);
            stockAcc.cash = round2(stockAcc.cash - amount - fee);
            recordBuyAvg(o.symbol, qty, amount + fee, px);
            o.filled += qty;
            o.status = (o.filled >= o.qty) ? "filled" : "partial";
            Json e = Json::obj();
            e["kind"] = Json(o.status == "filled" ? "order_filled" : "order_partial");
            e["orderId"] = Json(o.id);
            e["symbol"] = Json(o.symbol);
            e["side"] = Json("buy");
            e["qty"] = Json(static_cast<double>(qty));
            e["filled"] = Json(static_cast<double>(o.filled));
            e["remaining"] = Json(static_cast<double>(o.remaining()));
            e["price"] = Json::dec(px);
            e["note"] = Json(o.status == "filled" ? "限价买单全部成交" : "限价买单部分成交");
            e["at"] = time.toJson();
            events.push_back(e);
            if (o.status == "filled") queueRenewForOrder(o);
        } else {
            StockPosition* p = stockAcc.find(o.symbol);
            if (!p || p->frozenQty < qty) {
                long long avail = p ? p->frozenQty : 0;
                if (avail <= 0) { o.status = "cancelled"; return; }
                qty = avail;
            }
            p->frozenQty -= qty;
            if (p->frozenQty < 0) p->frozenQty = 0;
            double amount = round2(px * static_cast<double>(qty));
            double fee = commissionOf(amount);
            recordSell(o.symbol, qty, px, amount, fee, "manual");
            stockAcc.cash = round2(stockAcc.cash + amount - fee);
            o.filled += qty;
            o.status = (o.filled >= o.qty) ? "filled" : "partial";
            Json e = Json::obj();
            e["kind"] = Json(o.status == "filled" ? "order_filled" : "order_partial");
            e["orderId"] = Json(o.id);
            e["symbol"] = Json(o.symbol);
            e["side"] = Json("sell");
            e["qty"] = Json(static_cast<double>(qty));
            e["filled"] = Json(static_cast<double>(o.filled));
            e["remaining"] = Json(static_cast<double>(o.remaining()));
            e["price"] = Json::dec(px);
            e["note"] = Json(o.status == "filled" ? "限价卖单全部成交" : "限价卖单部分成交");
            e["at"] = time.toJson();
            events.push_back(e);
            if (o.status == "filled") queueRenewForOrder(o);
        }
    }

    void queueRenewForOrder(const Order& o) {
        if (!autoCfg.autoRenew) return;
        if (time.absSlot() == lastAutoRenewSlot) return;
        RenewSpec rs;
        rs.market = "stock";
        rs.symbol = o.symbol;
        rs.side = o.side;
        rs.qty = o.qty;
        rs.price = o.price;
        renewQueue.push_back(rs);
        autoRenewQueued = true;
    }

    // ================= 自动策略（auto 模式） =================
    void runAutoAtSlot(std::vector<Json>& events) {
        if (autoCfg.autoStop) {
            autoStopLossTakeProfit(events);
        }
        if (autoCfg.autoRenew && time.isSettleSlot()) {
            if (!renewQueue.empty()) {
                if (time.absSlot() != lastAutoRenewSlot) {
                    lastAutoRenewSlot = time.absSlot();
                    std::vector<RenewSpec> pend = renewQueue;
                    renewQueue.clear();
                    for (const RenewSpec& rs : pend) {
                        rematch(rs, events);
                    }
                    Json e = Json::obj();
                    e["kind"] = Json("order_filled");
                    e["symbol"] = Json(pend.size() > 0 ? pend[0].symbol : std::string(""));
                    e["side"] = Json(pend.size() > 0 ? pend[0].side : std::string("buy"));
                    e["qty"] = Json(0.0);
                    e["price"] = Json::dec(0.0);
                    e["note"] = Json("自动重挂单：" + std::to_string(pend.size()) + " 笔已按原价重新挂出");
                    e["at"] = time.toJson();
                    events.push_back(e);
                    autoRenewQueued = false;
                }
            }
        }
    }

    void rematch(const RenewSpec& rs, std::vector<Json>& events) {
        if (rs.market == "stock") {
            Stock* s = market.findStock(rs.symbol);
            if (!s || s->halted) return;
            Json args = Json::obj();
            args["symbol"] = Json(rs.symbol);
            args["qty"] = Json(static_cast<double>(rs.qty));
            args["type"] = Json("limit");
            args["price"] = Json::dec(rs.price);
            Json res = (rs.side == "buy") ? stockOrder(args, "buy") : stockOrder(args, "sell");
            if (res.find("__fail") != nullptr) {
                Json e = Json::obj();
                e["kind"] = Json("order_cancelled");
                e["symbol"] = Json(rs.symbol);
                e["side"] = Json(rs.side);
                e["qty"] = Json(static_cast<double>(rs.qty));
                e["price"] = Json::dec(round4(rs.price));
                e["note"] = Json("自动重挂单失败：" + jgetStr(res, "message", ""));
                e["at"] = time.toJson();
                events.push_back(e);
            }
        }
    }

    int findStopSeq(const std::string& sym, const std::string& side) {
        for (size_t i = 0; i < stopSpecs.size(); ++i)
            if (stopSpecs[i].symbol == sym && stopSpecs[i].side == side) return static_cast<int>(i);
        return -1;
    }

    // 自动止盈止损（股票按持仓成本百分比，外汇按 SL/TP 价格）
    void autoStopLossTakeProfit(std::vector<Json>& events) {
        // 股票
        for (size_t i = 0; i < stockAcc.positions.size(); ++i) {
            StockPosition& p = stockAcc.positions[i];
            if (p.qty <= 0) continue;
            Stock* s = market.findStock(p.symbol);
            if (!s || s->halted) continue;
            long long avail = p.qty - p.frozenQty;
            if (avail <= 0) continue;
            double cost = p.avgCost;
            if (cost <= 1e-9) continue;
            double chg = (s->last - cost) / cost;
            // 平仓数量必须是 100 的整数倍，且不超过可卖量
            long long unit = (avail >= 100) ? (avail / 100) * 100 : avail;
            if (unit <= 0) continue;
            bool triggerSL = autoCfg.stopLossPct > 0.0 && chg <= -autoCfg.stopLossPct;
            bool triggerTP = autoCfg.takeProfitPct > 0.0 && chg >= autoCfg.takeProfitPct;
            if (!triggerSL && !triggerTP) continue;
            double px = marketBuySellPrice(*s, "sell", unit);
            double amount = round2(px * static_cast<double>(unit));
            double fee = commissionOf(amount);
            recordSell(p.symbol, unit, px, amount, fee, triggerSL ? "stop" : "takeprofit");
            stockAcc.cash = round2(stockAcc.cash + amount - fee);
            Json e = Json::obj();
            e["kind"] = Json(triggerSL ? "stop_triggered" : "take_profit_triggered");
            e["symbol"] = Json(p.symbol);
            e["side"] = Json("sell");
            e["qty"] = Json(static_cast<double>(unit));
            e["price"] = Json::dec(round4(px));
            e["note"] = Json(triggerSL ? "触发自动止损，已卖出 " + std::to_string(unit) + " 股"
                                       : "触发自动止盈，已卖出 " + std::to_string(unit) + " 股");
            e["at"] = time.toJson();
            events.push_back(e);
        }
        stockAcc.dropEmpty();
        // 外汇
        for (size_t i = 0; i < forexAcc.positions.size();) {
            ForexPosition& p = forexAcc.positions[i];
            Forex* f = market.findForex(p.symbol);
            if (!f) { ++i; continue; }
            p.last = f->last;
            bool hitSL = p.stopLoss > 0.0 && ((p.side == "long" && f->last <= p.stopLoss) ||
                                              (p.side == "short" && f->last >= p.stopLoss));
            bool hitTP = p.takeProfit > 0.0 && ((p.side == "long" && f->last >= p.takeProfit) ||
                                                (p.side == "short" && f->last <= p.takeProfit));
            if (!hitSL && !hitTP) { ++i; continue; }
            int pid = p.id;
            double pnl = p.pnlAt(f->last);
            double net = round2(pnl + p.swap);
            forexAcc.cash = round2(forexAcc.cash + net);
            forexAcc.realized = round2(forexAcc.realized + net);
            realizedPnl = round2(realizedPnl + net);
            if (net > 0) ++winCount;
            Json e = Json::obj();
            e["kind"] = Json(hitSL ? "stop_triggered" : "take_profit_triggered");
            e["symbol"] = Json(p.symbol);
            e["side"] = Json(p.side);
            e["qty"] = Json(static_cast<double>(p.lots));
            e["price"] = Json::dec(Json::roundTo(f->last, f->def.digits));
            e["note"] = Json(hitSL ? "触发外汇止损，已平仓 " + p.symbol : "触发外汇止盈，已平仓 " + p.symbol);
            e["at"] = time.toJson();
            events.push_back(e);
            addTrade("forex", p.symbol, p.side, static_cast<long long>(std::llround(p.lots * 1000.0)),
                     f->last, round2(p.lots * 1000.0 * f->last), 0.0, net,
                     hitSL ? "stop" : "takeprofit", true);
            (void)pid;
            p.lots = 0.0;
            forexAcc.dropEmpty();
        }
    }

    // ================= 作弊器 =================
    Json cmdCheat(const Json& a) {
        std::string op = jgetStr(a, "op", "");
        if (op.empty()) return failJson(err::BAD_ARG, "参数错误: 缺少 op");
        Json out = Json::obj();
        out["op"] = Json(op);
        out["ok"] = Json(true);
        std::string detail;

        static const char* kOps[] = {"list", "money", "reset", "price", "pump", "freeze", "unlock",
                                     "t1", "infiniteMoney", "godMode", "noCommission", "perfectInfo",
                                     "fillOrders", "setCash", "winRate", "seed", "speed", "skip",
                                     "news", "bankrupt", "unbankrupt", "revealSeed"};
        bool opKnown = false;
        for (const char* k : kOps) if (op == k) { opKnown = true; break; }
        if (!opKnown) return failJson(err::BAD_ARG, "未知作弊项: " + op);

        if (op == "list") {
            return cheatList();
        } else if (op == "money") {
            double amount = jgetNum(a, "amount", 0.0);
            std::string acc = jgetStr(a, "account", "stock");
            if (amount == 0.0) amount = 1000000.0;
            if (acc == "forex") {
                forexAcc.cash = round2(forexAcc.cash + amount);
                detail = "已注入 " + fmtMoney(amount) + " 到外汇账户";
            } else if (acc == "both") {
                stockAcc.cash = round2(stockAcc.cash + amount);
                forexAcc.cash = round2(forexAcc.cash + amount);
                detail = "已注入 " + fmtMoney(amount) + " 到股票与外汇账户";
            } else {
                stockAcc.cash = round2(stockAcc.cash + amount);
                detail = "已注入 " + fmtMoney(amount) + " 到股票账户";
            }
        } else if (op == "reset") {
            double c1 = stockAcc.initialCash, c2 = forexAcc.initialCash;
            std::string n1 = playerName, d1 = difficulty;
            resetAllDataKeepTime(c1, c2, n1, d1);
            detail = "已重置资金/持仓/订单（时间与新闻保留）";
            out["data"] = snapshotData();
        } else if (op == "price") {
            std::string sym = jgetStr(a, "symbol", "");
            if (sym.empty()) return failJson(err::BAD_ARG, "参数错误: 缺少 symbol");
            bool ok = false;
            Stock* s = market.findStock(sym);
            if (s) {
                if (jhasNum(a, "to")) {
                    double to = jgetNum(a, "to", s->last);
                    if (to <= 0.0) return failJson(err::BAD_PRICE, "价格非法");
                    s->last = round4(to);
                    ok = true;
                } else if (jhasNum(a, "pct")) {
                    double pct = jgetNum(a, "pct", 0.0);
                    s->last = round4(std::max(0.01, s->last * (1.0 + pct)));
                    ok = true;
                }
                if (ok) {
                    s->high = std::max(s->high, s->last);
                    s->low = std::min(s->low, s->last);
                    s->refreshSpread();
                    detail = "已将 " + s->def.symbol + " 价格设为 " + fixedStr(s->last, 4);
                }
            } else {
                Forex* f = market.findForex(sym);
                if (f) {
                    if (jhasNum(a, "to")) {
                        double to = jgetNum(a, "to", f->last);
                        if (to <= 0.0) return failJson(err::BAD_PRICE, "价格非法");
                        f->last = Json::roundTo(to, f->def.digits);
                        ok = true;
                    } else if (jhasNum(a, "pct")) {
                        double pct = jgetNum(a, "pct", 0.0);
                        f->last = Json::roundTo(std::max(0.000001, f->last * (1.0 + pct)), f->def.digits);
                        ok = true;
                    }
                    if (ok) {
                        f->high = std::max(f->high, f->last);
                        f->low = std::min(f->low, f->last);
                        for (ForexPosition& p : forexAcc.positions) if (p.symbol == f->def.symbol) p.last = f->last;
                        detail = "已将 " + f->def.symbol + " 价格设为 " + fixedStr(f->last, f->def.digits);
                    }
                }
            }
            if (!ok) return failJson(err::NO_SUCH_SYMBOL, "未知代码 " + sym);
        } else if (op == "pump") {
            std::string sym = jgetStr(a, "symbol", "");
            double pct = jgetNum(a, "pct", 0.1);
            int bars = static_cast<int>(jgetInt(a, "bars", 5));
            if (bars < 1) bars = 1;
            if (bars > 400) bars = 400;
            Stock* s = market.findStock(sym);
            if (s) {
                s->pumpPct = pct;
                s->pumpBars = bars;
                detail = "已设置 " + s->def.symbol + " 未来 " + std::to_string(bars) + " 个时间片累计变动 " +
                         fixedStr(pct * 100.0, 2) + "%";
            } else {
                Forex* f = market.findForex(sym);
                if (!f) return failJson(err::NO_SUCH_SYMBOL, "未知代码 " + sym);
                f->pumpPct = pct;
                f->pumpBars = bars;
                detail = "已设置 " + f->def.symbol + " 未来 " + std::to_string(bars) + " 个时间片累计变动 " +
                         fixedStr(pct * 100.0, 2) + "%";
            }
        } else if (op == "freeze") {
            std::string sym = jgetStr(a, "symbol", "");
            if (sym.empty()) return failJson(err::BAD_ARG, "参数错误: 缺少 symbol");
            bool halted = jgetBool(a, "halted", true);
            Stock* s = market.findStock(sym);
            if (!s) return failJson(err::NO_SUCH_SYMBOL, "未知代码 " + sym);
            s->halted = halted;
            detail = s->def.symbol + (halted ? " 已停牌" : " 已复牌");
        } else if (op == "unlock") {
            std::string sym = jgetStr(a, "symbol", "");
            int n = 0;
            for (StockPosition& p : stockAcc.positions) {
                if (!sym.empty() && p.symbol != Market::canonical(sym)) continue;
                if (p.todayBought > 0) { p.todayBought = 0; ++n; }
            }
            detail = "已解除 T+1 锁定（" + std::to_string(n) + " 个标的）";
        } else if (op == "t1") {
            bool en = jgetBool(a, "enabled", true);
            cheat.t1 = en;
            settings.t1 = en;
            autoCfg.enabled = en;
            detail = en ? "T+1 规则已开启" : "T+1 规则已关闭";
        } else if (op == "infiniteMoney") {
            bool en = jgetBool(a, "enabled", true);
            cheat.infiniteMoney = en;
            detail = en ? "无限资金已开启（买入永不失败）" : "无限资金已关闭";
        } else if (op == "godMode") {
            bool en = jgetBool(a, "enabled", true);
            cheat.godMode = en;
            detail = en ? "上帝模式已开启（免保证金/免手续费/永不爆仓）" : "上帝模式已关闭";
        } else if (op == "noCommission") {
            bool en = jgetBool(a, "enabled", true);
            cheat.noCommission = en;
            detail = en ? "手续费已归零" : "手续费已恢复";
        } else if (op == "perfectInfo") {
            bool en = jgetBool(a, "enabled", true);
            cheat.perfectInfo = en;
            detail = en ? "透视模式已开启" : "透视模式已关闭";
        } else if (op == "fillOrders") {
            int n = fillAllOrders();
            detail = "已立即成交 " + std::to_string(n) + " 笔挂单";
        } else if (op == "setCash") {
            std::string acc = jgetStr(a, "account", "stock");
            double v = jgetNum(a, "value", 0.0);
            if (v < 0) v = 0;
            if (acc == "forex") {
                forexAcc.cash = round2(v);
                detail = "外汇账户现金已设为 " + fmtMoney(v);
            } else if (acc == "both") {
                stockAcc.cash = round2(v);
                forexAcc.cash = round2(v);
                detail = "股票与外汇账户现金已设为 " + fmtMoney(v);
            } else {
                stockAcc.cash = round2(v);
                detail = "股票账户现金已设为 " + fmtMoney(v);
            }
        } else if (op == "winRate") {
            double v = jgetNum(a, "value", cheat.winRate);
            cheat.winRate = clampD(v, 0.0, 1.0);
            detail = "有利行情概率已设为 " + fixedStr(cheat.winRate * 100.0, 1) + "%";
        } else if (op == "seed") {
            long long sd = jgetInt(a, "seed", static_cast<long long>(cheat.seed));
            if (sd <= 0) sd = 12345;
            cheat.seed = static_cast<uint64_t>(sd);
            rng.reseed(cheat.seed);
            reseedMarketForSeed();
            detail = "已重置随机种子 " + std::to_string(sd) + " 并重新生成行情";
        } else if (op == "speed") {
            double sp = clampD(jgetNum(a, "speed", settings.speed), 0.25, 256.0);
            settings.speed = sp;
            clockSpeedPending = sp;
            detail = "时钟倍速已设为 " + fixedStr(sp, 2);
        } else if (op == "skip") {
            long long slots = jgetInt(a, "slots", 40);
            if (slots < 1) slots = 1;
            if (slots > 5000) slots = 5000;
            bool aut = jgetBool(a, "auto", true);
            std::vector<Json> events;
            advanceSlots(static_cast<int>(slots), aut, events);
            eventsSinceSkip.clear();
            for (Json& e : events) eventsSinceSkip.push_back(e);
            detail = "已快进 " + std::to_string(slots) + " 个时间片（" +
                     std::to_string(slots / 4) + " 个交易日），产生 " +
                     std::to_string(events.size()) + " 个事件";
            out["advanced"] = Json(static_cast<double>(slots));
            out["time"] = time.toJson();
            Json ev = Json::arr();
            for (const Json& e : events) ev.push_back(e);
            out["events"] = ev;
            Json ha = Json::arr();
            for (const Stock& s : market.stocks) if (s.halted) ha.push_back(Json(s.def.symbol));
            out["halted"] = ha;
        } else if (op == "news") {
            std::string title = jgetStr(a, "title", "手动新闻");
            std::string body = jgetStr(a, "body", "");
            std::string scope = jgetStr(a, "scope", "stock");
            double impact = jgetNum(a, "impact", 0.05);
            std::vector<std::string> syms = jgetStrArray(a, "symbols");
            std::vector<Json> events;
            Json at = time.toJson();
            NewsItem n = news.makeCustom(time, title, body, scope, impact, syms, market, &events, at);
            for (Json& e : events) pendingEvents.push_back(e);
            detail = "已生成新闻 #" + std::to_string(n.id) + " " + n.title;
        } else if (op == "bankrupt") {
            std::string acc = jgetStr(a, "account", "stock");
            if (acc == "forex") { forexAcc.cash = 0.0; bankruptForex = true; }
            else { stockAcc.cash = 0.0; bankruptStock = true; }
            detail = "已强制破产（" + acc + "）";
            Json ev = Json::obj();
            ev["kind"] = Json("bankrupt");
            ev["account"] = Json(acc);
            ev["at"] = time.toJson();
            pendingEvents.push_back(ev);
        } else if (op == "unbankrupt") {
            bankruptStock = false;
            bankruptForex = false;
            bankruptPushed = false;
            detail = "已解除破产状态";
        } else if (op == "revealSeed") {
            Json rs = Json::obj();
            rs["seed"] = Json(static_cast<double>(cheat.seed));
            rs["rngSeed"] = Json(static_cast<double>(rng.seed()));
            rs["absSlot"] = Json(static_cast<double>(absSlot()));
            rs["stocks"] = Json(static_cast<double>(market.stocks.size()));
            rs["forexPairs"] = Json(static_cast<double>(market.forex.size()));
            rs["newsCount"] = Json(static_cast<double>(news.items.size()));
            rs["tradeCount"] = Json(static_cast<double>(tradeCount));
            out["seedInfo"] = rs;
            detail = "当前种子 " + std::to_string(cheat.seed);
        } else {
            return failJson(err::BAD_ARG, "未知作弊项: " + op);
        }

        out["detail"] = Json(detail);
        out["cheatState"] = cheat.toJson();
        return out;
    }

    double clockSpeedPending = -1.0;
    std::vector<Json> eventsSinceSkip;

    void resetAllDataKeepTime(double c1, double c2, const std::string& nm, const std::string& diff) {
        GameTime saved = time;
        int newsNext = news.nextId;
        std::vector<NewsItem> savedNews = news.items;
        resetAllData();
        time = saved;
        news.items = savedNews;
        news.nextId = newsNext;
        stockAcc.initialCash = c1;
        stockAcc.cash = c1;
        forexAcc.initialCash = c2;
        forexAcc.cash = c2;
        playerName = nm;
        difficulty = diff;
        settings.difficulty = diff;
        startEquity = totalEquity();
        sessionDayStartEquity = startEquity;
        lastKnownStockEquity = stockEquity();
    }

    // 用当前种子重排行情序列：不同 seed 得到完全不同的行情
    void reseedMarketForSeed() {
        uint64_t s = cheat.seed;
        for (size_t i = 0; i < market.stocks.size(); ++i) {
            Stock& st = market.stocks[i];
            st.def.basePrice = st.def.basePrice;  // 基准价不变
            Rng hr(s * 1000003ULL + static_cast<uint64_t>(i) * 7919ULL + 11ULL);
            double px = st.def.basePrice;
            double cur = px;
            std::vector<double> closes;
            closes.reserve(60);
            for (int k = 0; k < 60; ++k) {
                double r = st.def.baseVol * hr.normal() * 0.9;
                if (r > 0.12) r = 0.12;
                if (r < -0.12) r = -0.12;
                cur *= (1.0 + r);
                if (cur < 0.05) cur = 0.05;
                closes.push_back(cur);
            }
            double scale = px / closes.back();
            for (size_t k = 0; k < closes.size(); ++k) closes[k] *= scale;
            st.hist.clear();
            for (int k = 0; k < 60; ++k) {
                double close = (k == 59) ? px : closes[static_cast<size_t>(k)];
                double op = (k == 0) ? px : closes[static_cast<size_t>(k - 1)];
                Bar b;
                b.date = GameTime::dateFromIndex(-1 + static_cast<long long>(k) / 4);
                b.slot = k % 4;
                b.open = round4(op);
                b.close = round4(close);
                double amp = std::fabs(hr.normal()) * st.def.baseVol * 0.5;
                b.high = round4(std::max(b.open, b.close) * (1.0 + amp));
                b.low = round4(std::max(0.01, std::min(b.open, b.close) * (1.0 - amp)));
                b.volume = static_cast<long long>(8000.0 + std::fabs(hr.normal()) * 40000.0);
                st.hist.push_back(b);
            }
            st.prevClose = (st.hist.size() >= 2) ? st.hist[st.hist.size() - 2].close : px;
            st.open = st.hist.back().open;
            st.high = st.hist.back().high;
            st.low = st.hist.back().low;
            st.last = round4(px * (1.0 + (hr.normal() * 0.004)));
            st.volume = st.hist.back().volume;
            st.halted = false;
            st.pumpBars = 0;
            st.pumpPct = 0.0;
            st.refreshSpread();
        }
        for (size_t i = 0; i < market.forex.size(); ++i) {
            Forex& fx = market.forex[i];
            Rng hr(s * 2000003ULL + static_cast<uint64_t>(i) * 104729ULL + 29ULL);
            double px = fx.def.basePrice;
            double vol = px * 0.0007;
            double cur = px;
            std::vector<double> closes;
            closes.reserve(60);
            for (int k = 0; k < 60; ++k) {
                double r = (vol / px) * hr.normal() * 0.9;
                if (r > 0.03) r = 0.03;
                if (r < -0.03) r = -0.03;
                cur *= (1.0 + r);
                if (cur < px * 0.2) cur = px * 0.2;
                closes.push_back(cur);
            }
            double scale = px / closes.back();
            for (size_t k = 0; k < closes.size(); ++k) closes[k] *= scale;
            fx.hist.clear();
            for (int k = 0; k < 60; ++k) {
                double close = (k == 59) ? px : closes[static_cast<size_t>(k)];
                double op = (k == 0) ? px : closes[static_cast<size_t>(k - 1)];
                Bar b;
                b.date = GameTime::dateFromIndex(-1 + static_cast<long long>(k) / 4);
                b.slot = k % 4;
                b.open = Json::roundTo(op, fx.def.digits);
                b.close = Json::roundTo(close, fx.def.digits);
                double amp = std::fabs(hr.normal()) * vol * 0.4 + fx.def.pip * 0.5;
                b.high = Json::roundTo(std::max(b.open, b.close) + amp, fx.def.digits);
                b.low = Json::roundTo(std::max(0.000001, std::min(b.open, b.close) - amp), fx.def.digits);
                b.volume = static_cast<long long>(40000.0 + std::fabs(hr.normal()) * 200000.0);
                fx.hist.push_back(b);
            }
            fx.prevClose = (fx.hist.size() >= 2) ? fx.hist[fx.hist.size() - 2].close : px;
            fx.open = fx.hist.back().open;
            fx.high = fx.hist.back().high;
            fx.low = fx.hist.back().low;
            fx.last = Json::roundTo(px * (1.0 + hr.normal() * 0.001), fx.def.digits);
            fx.pumpBars = 0;
            fx.pumpPct = 0.0;
        }
    }

    // seed 作弊项：保留时间/仓位，只重生成行情
    void reseedPrices() {
        for (Stock& s : market.stocks) {
            s.last = s.def.basePrice;
            s.open = s.def.basePrice;
            s.prevClose = s.def.basePrice;
            s.high = s.def.basePrice;
            s.low = s.def.basePrice;
            s.vol = s.def.baseVol;
            s.halted = false;
            s.pumpBars = 0;
            s.pumpPct = 0.0;
            s.hist.clear();
            for (int i = 0; i < 60; ++i) {
                Bar b;
                b.date = time.dateStr();
                b.slot = 0;
                b.open = b.high = b.low = b.close = s.def.basePrice;
                b.volume = 0;
                s.hist.push_back(b);
            }
            s.refreshSpread();
        }
        for (Forex& f : market.forex) {
            f.last = f.def.basePrice;
            f.open = f.def.basePrice;
            f.prevClose = f.def.basePrice;
            f.high = f.def.basePrice;
            f.low = f.def.basePrice;
            f.hist.clear();
            f.pumpBars = 0;
            f.pumpPct = 0.0;
            for (int i = 0; i < 60; ++i) {
                Bar b;
                b.date = time.dateStr();
                b.slot = 0;
                b.open = b.high = b.low = b.close = f.def.basePrice;
                b.volume = 0;
                f.hist.push_back(b);
            }
        }
        // 重新生成 40 个 slot 的历史，让 K 线看起来是活的
        for (int i = 0; i < 40; ++i) {
            GameTime saved = time;
            time.setAbs(saved.absSlot() - 40 + i);
            Bar dummy;
            (void)dummy;
            generateBar();
            time = saved;
        }
    }

    int fillAllOrders() {
        int n = 0;
        std::vector<Json> events;
        std::vector<int> ids = book.matchOrderCandidates();
        for (int id : ids) {
            Order* o = book.find(id);
            if (!o || !o->isActive()) continue;
            if (o->market != "stock") continue;
            Stock* s = market.findStock(o->symbol);
            if (!s) continue;
            long long qty = o->remaining();
            if (qty <= 0) continue;
            double px = (o->type == "limit" && o->price > 0.0) ? o->price : marketBuySellPrice(*s, o->side, qty);
            processStockFill(*o, s, qty, px, events);
            ++n;
        }
        book.purgeDone(time);
        for (Json& e : events) pendingEvents.push_back(e);
        return n;
    }

    Json cheatList() const {
        Json arr = Json::arr();
        auto mk = [](const char* op, const char* label, const char* desc, std::initializer_list<const char*> args) {
            Json j = Json::obj();
            j["op"] = Json(op);
            j["label"] = Json(label);
            j["desc"] = Json(desc);
            Json a = Json::arr();
            for (const char* s : args) a.push_back(Json(s));
            j["args"] = a;
            return j;
        };
        arr.push_back(mk("money", "注入资金", "直接给股票/外汇账户加现金（纯模拟）", {"amount", "account"}));
        arr.push_back(mk("setCash", "设置现金", "把现金直接设成指定值", {"account", "value"}));
        arr.push_back(mk("reset", "重置进度", "资金/持仓/订单回到初始状态，时间与新闻保留", {}));
        arr.push_back(mk("price", "强制价格", "强制设定价格或按比例改动价格", {"symbol", "to", "pct"}));
        arr.push_back(mk("pump", "拉盘", "未来 N 个时间片持续上涨/下跌", {"symbol", "pct", "bars"}));
        arr.push_back(mk("freeze", "停牌/复牌", "让标的停牌或恢复交易", {"symbol", "halted"}));
        arr.push_back(mk("unlock", "解锁 T+1", "立即解除当日买入的 T+1 锁定", {"symbol"}));
        arr.push_back(mk("t1", "T+1 开关", "开关 T+1 规则", {"enabled"}));
        arr.push_back(mk("infiniteMoney", "无限资金", "现金不足时自动补足，买入永不失败", {"enabled"}));
        arr.push_back(mk("godMode", "上帝模式", "免保证金、免手续费、永不爆仓", {"enabled"}));
        arr.push_back(mk("noCommission", "免手续费", "手续费归零", {"enabled"}));
        arr.push_back(mk("perfectInfo", "透视模式", "在 snapshot.cheatInfo 中显示未读新闻与拉盘计划", {"enabled"}));
        arr.push_back(mk("fillOrders", "立即成交", "所有未成交挂单立刻成交", {"all"}));
        arr.push_back(mk("winRate", "有利概率", "随机行情对你有利的概率 0..1", {"value"}));
        arr.push_back(mk("seed", "重设种子", "重置随机种子并重新生成行情（时间/仓位保留）", {"seed"}));
        arr.push_back(mk("speed", "倍速", "设置时钟倍速（等价 clock set speed）", {"speed"}));
        arr.push_back(mk("skip", "快进", "快进 N 个时间片", {"slots", "auto"}));
        arr.push_back(mk("news", "手动新闻", "生成一条新闻并立刻影响行情", {"title", "impact", "scope", "symbols"}));
        arr.push_back(mk("bankrupt", "强制破产", "测试用：强制账户破产", {"account"}));
        arr.push_back(mk("unbankrupt", "解除破产", "解除破产状态", {}));
        arr.push_back(mk("revealSeed", "查看种子", "返回当前种子与行情内部状态", {}));
        arr.push_back(mk("list", "作弊项列表", "返回所有可用作弊项", {}));
        Json out = Json::obj();
        out["cheats"] = arr;
        return out;
    }

    // ================= 时钟 =================
    Json clockData(const std::string& advanceUnit) const {
        Json j = Json::obj();
        j["running"] = Json(clockRunning);
        j["speed"] = Json::dec(settings.speed);
        if (settings.tickMs == std::floor(settings.tickMs)) j["tickMs"] = Json(static_cast<double>(static_cast<long long>(settings.tickMs)));
    else j["tickMs"] = Json::dec(settings.tickMs);
        double ti = tickIntervalMs();
        if (ti == std::floor(ti)) j["tickIntervalMs"] = Json(ti);
        else j["tickIntervalMs"] = Json::dec(ti);
        j["time"] = time.toJson();
        j["advanceUnit"] = Json(advanceUnit);
        return j;
    }

    double tickIntervalMs() const {
        double sp = settings.speed > 0.0 ? settings.speed : 1.0;
        double tm = settings.tickMs > 0.0 ? settings.tickMs : 500.0;
        return round2(tm / sp);
    }

    Json cmdClock(const Json& a) {
        std::string action = jgetStr(a, "action", "get");
        if (action == "get") {
            return clockData("slot");
        }
        if (action == "set" || action == "start") {
            if (jhasNum(a, "speed")) {
                double sp = jgetNum(a, "speed", settings.speed);
                if (!(sp >= 0.25 && sp <= 256.0))
                    return failJson(err::BAD_ARG, "参数错误: speed 必须在 0.25..256 之间（当前 " + fmtNum(sp) + "）");
                settings.speed = sp;
            }
            if (jhasNum(a, "tickMs")) {
                double tm = jgetNum(a, "tickMs", settings.tickMs);
                if (!(tm >= 50.0 && tm <= 60000.0))
                    return failJson(err::BAD_ARG, "参数错误: tickMs 必须在 50..60000 之间（当前 " + fmtNum(tm) + "）");
                settings.tickMs = tm;
            }
            if (action == "start") {
                if (settings.tickMs <= 0.0) settings.tickMs = 500.0;
                clockRunning = true;
            }
            return clockData("slot");
        }

        if (action == "stop") {
            clockRunning = false;
            return clockData("slot");
        }
        return failJson(err::BAD_ARG, "参数错误: action 必须为 start/stop/set/get");
    }

    // 时钟状态（由 main/clock 驱动读写；单线程 stdio 模式下由 stdio 循环查询）
    bool clockRunning = false;

    // 自动推进一次（时钟线程调用）；返回 push 行内容
    Json clockStepPush() {
        std::vector<Json> events;
        // 收集上一次命令产生的挂起事件
        stepOneSlot(true, events);
        for (Json& e : pendingEvents) events.insert(events.begin(), e);
        pendingEvents.clear();
        Json data = Json::obj();
        data["time"] = time.toJson();
        data["advanced"] = Json(1);
        Json ev = Json::arr();
        for (const Json& e : events) ev.push_back(e);
        data["events"] = ev;
        return data;
    }

    // ================= 自检辅助 =================
    int selfTestCount = 0;
    int selfTestFail = 0;
};

}  // namespace tsim
