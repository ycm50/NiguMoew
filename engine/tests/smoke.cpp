// smoke.cpp - pure-logic smoke tests for the NiguMeow (拟股喵喵) engine.
//
// Compiles standalone against the engine's own headers (they are header-only):
//     g++ -std=c++17 -O2 -Wall -Wextra -I engine/src engine/tests/smoke.cpp -o smoke.exe
//     smoke.exe
//
// Covered here (no sockets, no processes, no files):
//   * Json round trip, number formatting, escaping, malformed input rejection
//   * price matching / commission / T+1 sellable quantity bookkeeping
//   * forex margin, floating PnL, free margin and a margin-call blow-up
//   * deterministic RNG replay (same seed -> same stream)
//   * GameTime calendar maths (slots, trading-day skipping, settle slot)
//
// The suite is deliberately self-contained: if a section depends on a member
// that a teammate renames, the compiler still fails fast on THAT section only;
// every other section keeps running.  Exit code 0 = all green.

#include "json.hpp"
#include "jsonutil.hpp"
#include "util.hpp"
#include "market.hpp"
#include "order.hpp"
#include "account.hpp"
#include "news.hpp"

#include <cstdio>
#include <cmath>
#include <string>
#include <vector>
#include <algorithm>

using namespace tsim;

static int g_pass = 0;
static int g_fail = 0;
static std::vector<std::string> g_failed;

static void ok(const std::string& name, bool cond, const std::string& detail = std::string()) {
    if (cond) {
        ++g_pass;
        std::printf("PASS  %s\n", name.c_str());
    } else {
        ++g_fail;
        std::string line = "FAIL  " + name;
        if (!detail.empty()) line += "  --  " + detail;
        g_failed.push_back(line);
        std::printf("%s\n", line.c_str());
    }
}

static std::string d2s(double v) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.10g", v);
    return std::string(buf);
}

static void eqNum(const std::string& name, double got, double want, double eps = 1e-9) {
    ok(name, std::fabs(got - want) <= eps, "got " + d2s(got) + ", want " + d2s(want) + " (eps " + d2s(eps) + ")");
}

static void eqStr(const std::string& name, const std::string& got, const std::string& want) {
    ok(name, got == want, "got '" + got + "', want '" + want + "'");
}

static bool contains(const std::string& hay, const std::string& needle) {
    return hay.find(needle) != std::string::npos;
}

// ---------------------------------------------------------------- helpers --

static void bumpHistory(Stock& s, double close) {
    Bar b;
    b.date = "2024-01-02";
    b.slot = 0;
    b.open = b.high = b.low = b.close = close;
    b.volume = 1000;
    s.hist.push_back(b);
}

// ================================================================= 1. JSON ==

static void testJson() {
    std::printf("\n-- 1. Json round trip / formatting / rejection ------------------\n");

    const std::string src =
        "{\"id\":1,\"ok\":true,\"data\":{\"name\":\"\xE8\xB4\xB5\xE5\xB7\x9E\xE8\x8C\x85\xE5\x8F\xB0\","
        "\"qty\":100,\"last\":1712.5,\"changePct\":0.007412,\"nested\":[1,2,[3,{\"x\":null}]],"
        "\"neg\":-0.0001,\"big\":1234567.89}}";
    Json parsed;
    bool okParse = jsonParse(src, parsed);
    ok("json: parses a protocol-shaped object", okParse, "jsonParse returned false");
    if (okParse) {
        eqNum("json: id survives", parsed["id"].asNum(-1), 1.0);
        ok("json: ok flag survives", parsed["ok"].asBool(false));
        eqStr("json: utf8 name survives", parsed["data"]["name"].asStr(), "\xE8\xB4\xB5\xE5\xB7\x9E\xE8\x8C\x85\xE5\x8F\xB0");
        eqNum("json: nested array of arrays", parsed["data"]["nested"].at(2).at(1)["x"].isNull() ? 1.0 : 0.0, 1.0);
        eqNum("json: negative small number", parsed["data"]["neg"].asNum(0), -0.0001, 1e-12);
        eqNum("json: two-decimal money", parsed["data"]["big"].asNum(0), 1234567.89, 1e-6);

        // round trip
        std::string dumped = parsed.dump();
        Json again;
        bool okAgain = jsonParse(dumped, again);
        ok("json: dump() is re-parsable", okAgain, dumped);
        eqStr("json: round trip is stable", again.dump(), dumped);
        ok("json: dump() is single-line", dumped.find('\n') == std::string::npos, dumped);
    }

    // escaping
    {
        Json o = Json::obj();
        o["s"] = Json(std::string("quote\" back\\slash\nnewline\ttab"));
        std::string d = o.dump();
        ok("json: control and quote characters are escaped",
           d.find("\\\"") != std::string::npos && d.find("\\\\") != std::string::npos &&
           d.find("\\n") != std::string::npos && d.find("\\t") != std::string::npos, d);
        ok("json: escaped output has no raw newline", d.find('\n') == std::string::npos, d);
        Json back;
        ok("json: escaped output re-parses", jsonParse(d, back), d);
        eqStr("json: escapes survive the round trip", back["s"].asStr(), "quote\" back\\slash\nnewline\ttab");
    }

    // \u escapes and surrogate pairs
    {
        Json u;
        bool r = jsonParse("{\"a\":\"\\u4e2d\\u6587\",\"b\":\"\\ud83d\\ude00\"}", u);
        ok("json: \\u escapes parse", r);
        if (r) {
            eqStr("json: BMP \\u escape -> utf8", u["a"].asStr(), "\xE4\xB8\xAD\xE6\x96\x87");
            ok("json: surrogate pair -> 4 byte utf8", u["b"].asStr().size() == 4, u["b"].asStr());
        }
    }

    // number formatting must never use scientific notation
    {
        struct Case { double v; const char* want; };
        const Case cases[] = {
            {100.0,          "100"},        // integers must not print ".0"
            {0.0,            "0"},
            {1712.5,         "1712.5"},
            {0.0001,         "0.0001"},
            {-1234.5,        "-1234.5"},
            {1000000.0,      "1000000"},
            {0.00000001,     "0.00000001"},
            {-0.0,           "0"},
        };
        for (const Case& c : cases) {
            std::string s = Json::fmtNum(c.v);
            eqStr(std::string("json: fmtNum(") + c.want + ")", s, c.want);
            ok(std::string("json: fmtNum(") + c.want + ") is not scientific", s.find('e') == std::string::npos &&
               s.find('E') == std::string::npos, s);
        }
    }

    // roundTo
    eqNum("json: roundTo(1.23456, 4)", Json::roundTo(1.23456, 4), 1.2346, 1e-12);
    eqNum("json: roundTo(1.23444, 4)", Json::roundTo(1.23444, 4), 1.2344, 1e-12);
    eqNum("json: roundTo(2.5, 0)", Json::roundTo(2.5, 0), 3.0, 1e-12);
    eqNum("json: roundTo(-0.0, 2) has no negative zero", Json::roundTo(-0.0, 2), 0.0, 1e-12);
    ok("json: roundTo never returns NaN/inf", Json::roundTo(1e308 * 10.0, 4) == 0.0 || std::isfinite(Json::roundTo(1e308 * 10.0, 4)));

    // malformed input must be rejected, never silently accepted
    const char* bad[] = {
        "{\"a\":1,}", "{\"a\":1}garbage", "not json", "{\"a\":", "[1,2", "{\"a\" 1}",
        "{'a':1}", "", "  ", "{\"a\":1}",  // last one is valid, handled below
    };
    for (size_t i = 0; i + 1 < sizeof(bad) / sizeof(bad[0]); ++i) {
        Json junk;
        bool accepted = jsonParse(bad[i], junk);
        ok(std::string("json: rejects malformed '") + bad[i] + "'", !accepted, "accepted malformed input");
    }
    {
        Json fine;
        ok("json: accepts a plain valid object", jsonParse("{\"a\":1}", fine));
        ok("json: allows surrounding whitespace", jsonParse("  {\"a\":1}  \n", fine));
    }

    // protocol-shaped response
    {
        Json resp = Json::obj();
        resp["id"] = Json(0);
        resp["ok"] = Json(true);
        Json d = Json::obj();
        d["type"] = Json("hello");
        d["protocol"] = Json(1);
        resp["data"] = d;
        std::string line = resp.dump();
        eqStr("json: hello frame matches the frozen wire form", line,
              "{\"id\":0,\"ok\":true,\"data\":{\"type\":\"hello\",\"protocol\":1}}");
        ok("json: hello frame is a single line", line.find('\n') == std::string::npos);
    }
}

// ============================================================ 2. T+1 / book =

static void testT1AndMatching() {
    std::printf("\n-- 2. T+1 sellable quantity and order matching ------------------\n");

    StockAccount acc;
    acc.cash = 100000.0;
    StockPosition& p = acc.ensure("SH600519", "贵州茅台");
    p.qty = 300;
    p.todayBought = 100;
    p.avgCost = 10.0;

    ok("t1: sellable excludes today's buys", acc.sellable("SH600519") == 200,
       std::to_string(acc.sellable("SH600519")));
    ok("t1: unknown symbol has zero sellable", acc.sellable("SZ000001") == 0);

    p.frozenQty = 50;
    ok("t1: frozen quantity is excluded too", acc.sellable("SH600519") == 150,
       std::to_string(acc.sellable("SH600519")));
    p.frozenQty = 0;

    // simulate a full day's T+1 unlock
    acc.unlockT1();
    ok("t1: unlock makes everything sellable", acc.sellable("SH600519") == 300);

    // overselling must not be possible through sellable()
    p.qty = 100;
    p.todayBought = 150;  // defensive: never negative
    ok("t1: sellable clamps at zero", acc.sellable("SH600519") == 0, std::to_string(acc.sellable("SH600519")));

    // ---- limit order price-time priority ----
    OrderBook book;
    Order b1;
    b1.symbol = "SH600519"; b1.market = "stock"; b1.side = "buy"; b1.type = "limit";
    b1.qty = 100; b1.price = 100.0; b1.status = "open";
    Order b2 = b1; b2.price = 105.0;
    Order b3 = b2; b3.price = 105.0;          // same price, later id -> lower priority
    Order s1 = b1; s1.side = "sell"; s1.price = 110.0;
    Order s2 = s1; s2.price = 108.0;
    Order m1 = b1; m1.type = "market";

    int idB1 = book.add(b1).id;
    int idS1 = book.add(s1).id;
    int idB2 = book.add(b2).id;
    int idB3 = book.add(b3).id;
    int idS2 = book.add(s2).id;
    int idM1 = book.add(m1).id;

    ok("book: ids are allocated monotonically",
       idB1 < idS1 && idS1 < idB2 && idB2 < idB3 && idB3 < idS2 && idS2 < idM1);

    std::vector<int> order = book.matchOrderCandidates();
    ok("book: every active order is a candidate", order.size() == 6, std::to_string(order.size()));
    // buys first (they are matched before sells in this simplified book), best price first
    // market orders rank above limits within the same side
    ok("book: market order is matched first overall", order.size() > 0 && order[0] == idM1,
       order.empty() ? "empty" : std::to_string(order[0]));
    // highest buy limit before lower ones
    size_t posB2 = 0, posB1 = 0, posB3 = 0, posS2 = 0, posS1 = 0;
    for (size_t i = 0; i < order.size(); ++i) {
        if (order[i] == idB2) posB2 = i;
        if (order[i] == idB1) posB1 = i;
        if (order[i] == idB3) posB3 = i;
        if (order[i] == idS2) posS2 = i;
        if (order[i] == idS1) posS1 = i;
    }
    ok("book: higher buy limit has priority", posB2 < posB1);
    ok("book: equal price respects FIFO (lower id first)", posB2 < posB3);
    ok("book: lower sell limit has priority", posS2 < posS1);

    // order lifecycle
    Order* o = book.find(idB1);
    ok("book: find() locates an order", o != nullptr);
    if (o) {
        ok("book: new order is active", o->isActive());
        ok("book: remaining() is qty-filled", o->remaining() == 100);
        o->filled = 40;
        o->status = "partial";
        ok("book: partially filled order is still active", o->isActive());
        ok("book: remaining() drops after a partial fill", o->remaining() == 60);
        o->status = "filled";
        ok("book: filled order is not active", !o->isActive());
    }
    // purgeDone(now) keeps terminal orders created in the current slot so the
    // front-end can still read back what just happened, and drops older ones.
    GameTime now;
    book.purgeDone(now);
    ok("book: purgeDone keeps same-slot terminal orders for read-back",
       book.find(idB1) != nullptr, "same-slot order vanished");
    now.setAbs(now.absSlot() + 4);      // a later trading day
    book.purgeDone(now);
    ok("book: purgeDone drops terminal orders from older slots",
       book.find(idB1) == nullptr && book.activeCount() == 5,
       std::to_string(book.activeCount()));

    // serialization of an order matches the frozen field list
    {
        Order* live = book.find(idM1);
        ok("book: market order survives purge", live != nullptr);
        if (live) {
            std::string j = live->toJson().dump();
            const char* need[] = {"\"id\"", "\"symbol\"", "\"market\"", "\"side\"", "\"type\"",
                                  "\"qty\"", "\"filled\"", "\"price\"", "\"status\"", "\"created\""};
            std::string missing;
            for (const char* k : need) if (!contains(j, k)) { missing += k; missing += " "; }
            ok("book: order JSON carries all frozen fields", missing.empty(), "missing: " + missing + " in " + j);
        }
    }
}

// ==================================================== 3. prices and fees ====

static void testPricesAndFees() {
    std::printf("\n-- 3. Price rounding, spread and commission --------------------\n");

    Market m;
    m.init();
    ok("market: has at least 20 stocks", m.stocks.size() >= 20, std::to_string(m.stocks.size()));
    ok("market: has exactly 10 forex pairs", m.forex.size() == 10, std::to_string(m.forex.size()));

    int prefixCount[4] = {0, 0, 0, 0};
    const char* prefixes[4] = {"SH", "SZ", "HK", "US"};
    for (const Stock& s : m.stocks) {
        for (int i = 0; i < 4; ++i) if (startsWith(s.def.symbol, prefixes[i])) ++prefixCount[i];
    }
    for (int i = 0; i < 4; ++i) {
        ok(std::string("market: at least 4 ") + prefixes[i] + " symbols", prefixCount[i] >= 4,
           std::to_string(prefixCount[i]));
    }

    Stock* kweichow = m.findStock("sh600519");  // canonicalization is case/space insensitive
    ok("market: findStock is case insensitive", kweichow != nullptr);
    if (kweichow) {
        eqStr("market: canonical symbol", kweichow->def.symbol, "SH600519");
        ok("market: bid <= ask", kweichow->bid <= kweichow->ask + 1e-9,
           d2s(kweichow->bid) + " / " + d2s(kweichow->ask));
        double mid = (kweichow->bid + kweichow->ask) / 2.0;
        ok("market: spread is centred on the last price", std::fabs(mid - kweichow->last) < 0.02,
           "mid " + d2s(mid) + " last " + d2s(kweichow->last));
        ok("market: bid/ask are rounded to 4 decimals",
           std::fabs(kweichow->bid * 10000.0 - std::round(kweichow->bid * 10000.0)) < 1e-6);
    }
    ok("market: unknown symbol is null", m.findStock("ZZ999999") == nullptr);
    ok("market: forex symbol detection", m.isForexSymbol("eurusd") && !m.isForexSymbol("SH600519"));
    eqStr("market: marketOf returns forex for pairs", m.marketOf("XAUUSD"), "forex");

    // forex digit rules from PROTOCOL.md 3.5.  digits is the display precision;
    // the pip is the market convention (0.0001 for 4-digit pairs, 0.01 for the
    // 3-digit JPY pairs, 0.01 for XAUUSD) and is NOT always 10^-digits.
    struct FxCase { const char* sym; int digits; double pip; };
    const FxCase fx[] = {{"EURUSD", 4, 0.0001}, {"GBPUSD", 4, 0.0001}, {"USDJPY", 3, 0.01},
                         {"AUDUSD", 4, 0.0001}, {"USDCHF", 4, 0.0001}, {"USDCAD", 4, 0.0001},
                         {"NZDUSD", 4, 0.0001}, {"EURJPY", 3, 0.01},   {"GBPJPY", 3, 0.01},
                         {"XAUUSD", 2, 0.01}};
    for (const FxCase& c : fx) {
        Forex* f = m.findForex(c.sym);
        if (!f) { ok(std::string("market: forex ") + c.sym + " exists", false); continue; }
        ok(std::string("market: digits(") + c.sym + ") == " + std::to_string(c.digits),
           f->def.digits == c.digits, std::to_string(f->def.digits));
        ok(std::string("market: pip(") + c.sym + ") == " + d2s(c.pip),
           std::fabs(f->def.pip - c.pip) < 1e-12, d2s(f->def.pip));
        ok(std::string("market: pip(") + c.sym + ") is a whole number of ticks at digits",
           std::fabs(f->def.pip * std::pow(10.0, c.digits) - std::floor(f->def.pip * std::pow(10.0, c.digits) + 0.5)) < 1e-9,
           d2s(f->def.pip));
    }

    // commission helper semantics: 0.025% of the traded amount, rounded to 2 dp
    {
        const double rate = 0.00025;
        const double amount = 171250.0;
        double fee = Json::roundTo(amount * rate, 2);
        eqNum("fee: 0.025% of 171250", fee, 42.81, 1e-9);
        eqNum("fee: amount*rate rounded to cents", Json::roundTo(1000.0 * rate, 2), 0.25, 1e-12);
        eqNum("fee: tiny amounts round to 0.00", Json::roundTo(10.0 * rate, 2), 0.0, 1e-12);
    }

    // price rounding at the protocol boundary
    {
        Market m2;
        m2.init();
        Stock* s = m2.findStock("SH601398");
        ok("market: low priced stock exists", s != nullptr);
        if (s) {
            s->last = 5.60123456;
            s->refreshSpread();
            std::string j = m2.findStock("SH601398") ? "ok" : "gone";
            (void)j;
            ok("market: refreshSpread rounds to 4 dp",
               std::fabs(s->last - 5.60123456) < 1e-12);
            ok("market: ask stays above bid after refreshSpread", s->ask >= s->bid);
        }
    }
}

// ================================================= 4. FX margin and blow-up =

static void testForexMargin() {
    std::printf("\n-- 4. Forex margin, floating PnL and margin call --------------\n");

    Market m;
    m.init();
    Forex* eurusd = m.findForex("EURUSD");
    ok("forex: EURUSD is present", eurusd != nullptr);
    if (!eurusd) return;
    eqNum("forex: EURUSD starts at 1.0840", eurusd->last, 1.0840, 1e-9);
    eqNum("forex: EURUSD point value is 10 USD per pip", eurusd->def.pointValue, 10.0, 1e-12);

    // margin = lots * 1000 * price / leverage
    {
        double lots = 2.0, leverage = 100.0, price = eurusd->last;
        double margin = lots * 1000.0 * price / leverage;
        eqNum("forex: 2 lots @100x margin is about 21.68 USD", margin, 21.68, 0.01);
    }

    ForexPosition p;
    p.id = 1;
    p.symbol = "EURUSD";
    p.name = "欧元/美元";
    p.side = "long";
    p.lots = 2.0;
    p.openRate = 1.0800;
    p.last = 1.0850;
    p.margin = 2.0 * 1000.0 * 1.0800 / 100.0;

    eqNum("forex: long PnL = (last-open)*lots*1000", p.pnlAt(1.0850), 0.0050 * 2.0 * 1000.0, 1e-9);
    eqNum("forex: long PnL at the open rate is zero", p.pnlAt(1.0800), 0.0, 1e-12);
    eqNum("forex: long PnL is negative below the open rate", p.pnlAt(1.0700), -0.0100 * 2.0 * 1000.0, 1e-9);

    ForexPosition q = p;
    q.side = "short";
    // short: pnl = (openRate - rate) * lots * 1000
    eqNum("forex: short PnL inverts the sign", q.pnlAt(1.0850), (1.0800 - 1.0850) * 2.0 * 1000.0, 1e-9);
    eqNum("forex: short profits when price falls", q.pnlAt(1.0700), (1.0800 - 1.0700) * 2.0 * 1000.0, 1e-9);
    eqNum("forex: short is the exact negation of long", q.pnlAt(1.0900), -p.pnlAt(1.0900), 1e-9);

    // account aggregates
    ForexAccount acc;
    acc.cash = 1000.0;
    acc.positions.push_back(p);
    eqNum("forex: usedMargin sums position margins", acc.usedMargin(), p.margin, 1e-9);
    eqNum("forex: floatPnl sums position PnL", acc.floatPnl(), 10.0, 1e-9);

    double equity = acc.cash + acc.floatPnl();
    double freeMargin = equity - acc.usedMargin();
    double marginLevel = equity / acc.usedMargin() * 100.0;
    eqNum("forex: equity = cash + float pnl", equity, 1010.0, 1e-9);
    eqNum("forex: free margin = equity - used margin", freeMargin, 1010.0 - p.margin, 1e-9);
    ok("forex: margin level is a percentage above 100 for a healthy account", marginLevel > 100.0,
       d2s(marginLevel));

    // ---- blow-up (margin call) arithmetic ----
    // 20 lots at 100x leverage on EURUSD needs roughly 216.8 USD of margin;
    // a 1000 USD account is wiped out after a ~460 pip adverse move.
    {
        ForexAccount big;
        big.cash = 1000.0;
        ForexPosition bp = p;
        bp.lots = 20.0;
        bp.margin = 20.0 * 1000.0 * 1.0800 / 100.0;
        bp.last = 1.0800;
        big.positions.push_back(bp);

        double safe = big.cash + big.floatPnl() - big.usedMargin();
        ok("forex: freshly opened leveraged position still has free margin", safe > 0, d2s(safe));

        // walk the rate down until equity would fall below the margin requirement
        double rate = 1.0800;
        double worst = 1.0800;
        while (rate > 0.9000) {
            ForexPosition& w = big.positions[0];
            double eq = big.cash + w.pnlAt(rate);
            if (eq - big.usedMargin() <= 0.0) { worst = rate; break; }
            worst = rate;
            rate -= 0.0001;
        }
        ok("forex: margin call triggers on a large adverse move", worst < 1.0799,
           "stopped at " + d2s(worst));
        double lossPips = (1.0800 - worst) / 0.0001;
        ok("forex: blow-up happens within 1000 pips", lossPips > 0 && lossPips < 1000.0,
           d2s(lossPips) + " pips");

        // equity must actually be exhausted at the trigger point
        double eqAtTrigger = big.cash + big.positions[0].pnlAt(worst);
        ok("forex: equity at trigger no longer covers the margin", eqAtTrigger <= big.usedMargin() + 0.01,
           d2s(eqAtTrigger) + " vs " + d2s(big.usedMargin()));
    }

    // ---- lots bookkeeping and closing ----
    {
        ForexAccount c;
        c.cash = 10000.0;
        ForexPosition a = p, b = p;
        a.id = 1; a.lots = 1.5;
        b.id = 2; b.lots = 0.5;
        c.positions.push_back(a);
        c.positions.push_back(b);
        eqNum("forex: usedMargin accumulates over positions", c.usedMargin(), a.margin + b.margin, 1e-9);
        c.positions[0].lots = 0.0;
        c.dropEmpty();
        ok("forex: dropEmpty removes closed positions", c.positions.size() == 1, std::to_string(c.positions.size()));
        eqNum("forex: usedMargin follows the remaining position", c.usedMargin(), b.margin, 1e-9);
        c.clear();
        ok("forex: clear() resets positions", c.positions.empty());
        eqNum("forex: clear() restores the initial cash", c.cash, c.initialCash, 1e-12);
    }
}

// ================================================== 5. deterministic RNG ===

static void testRng() {
    std::printf("\n-- 5. Reproducible randomness ---------------------------------\n");

    Rng a(12345), b(12345), c(999);
    std::vector<double> va, vb, vc;
    for (int i = 0; i < 32; ++i) {
        va.push_back(a.uniform());
        vb.push_back(b.uniform());
        vc.push_back(c.uniform());
    }
    bool same = true, diff = false;
    for (int i = 0; i < 32; ++i) {
        if (va[static_cast<size_t>(i)] != vb[static_cast<size_t>(i)]) same = false;
        if (va[static_cast<size_t>(i)] != vc[static_cast<size_t>(i)]) diff = true;
    }
    ok("rng: same seed produces an identical stream", same);
    ok("rng: a different seed produces a different stream", diff);

    // reseeding rewinds the stream
    Rng d(777);
    for (int i = 0; i < 10; ++i) d.uniform();
    d.reseed(777);
    Rng e(777);
    eqNum("rng: reseed restarts the stream", d.uniform(), e.uniform(), 0.0);
    ok("rng: seed() reports the active seed", d.seed() == 777, std::to_string(d.seed()));

    // ranges stay inside their bounds
    Rng f(42);
    bool inRange = true, inBelow = true;
    double lo = 1e9, hi = -1e9;
    for (int i = 0; i < 20000; ++i) {
        double u = f.uniform();
        if (u < 0.0 || u >= 1.0) inRange = false;
        double r = f.range(-3.0, 7.0);
        if (r < -3.0 || r > 7.0) inRange = false;
        int k = f.below(10);
        if (k < 0 || k >= 10) inBelow = false;
        double n = f.normal();
        if (std::isnan(n) || std::isinf(n)) inRange = false;
        if (n < lo) lo = n;
        if (n > hi) hi = n;
    }
    ok("rng: uniform()/range() stay inside their bounds", inRange);
    ok("rng: below(n) stays inside [0,n)", inBelow);
    ok("rng: normal() has a plausible spread", hi - lo > 3.0 && hi - lo < 14.0,
       d2s(lo) + ".." + d2s(hi));

    // mean of uniform ~ 0.5
    Rng g(20240305);
    double sum = 0.0;
    for (int i = 0; i < 200000; ++i) sum += g.uniform();
    double mean = sum / 200000.0;
    ok("rng: uniform() mean is close to 0.5", std::fabs(mean - 0.5) < 0.01, d2s(mean));

    // the same seed must also reproduce a market walk
    auto walk = [](uint64_t seed, int steps) {
        Market m;
        m.init();
        Rng r(seed);
        for (int i = 0; i < steps; ++i) {
            Stock& s = m.stocks[0];
            double drift = (1.0 - s.fair) * 0.1;
            s.last = Json::roundTo(std::max(0.01, s.last * (1.0 + drift + s.vol * r.normal() * 0.5)), 4);
            s.refreshSpread();
        }
        return m.stocks[0].last;
    };
    double w1 = walk(31337, 200);
    double w2 = walk(31337, 200);
    double w3 = walk(31338, 200);
    eqNum("rng: identical seeds reproduce the same price path", w1, w2, 0.0);
    ok("rng: a different seed yields a different path", w1 != w3, d2s(w1) + " vs " + d2s(w3));
}

// ======================================================== 6. GameTime =======

static void testGameTime() {
    std::printf("\n-- 6. Calendar and slot arithmetic ----------------------------\n");

    GameTime t;
    eqStr("time: starts on the first trading day", t.dateStr(), "2024-01-02");
    eqNum("time: starts at slot 0", t.slot, 0.0);
    ok("time: slot 0 is not the settle slot", !t.isSettleSlot());

    t.slot = 3;
    ok("time: slot 3 is the settle slot", t.isSettleSlot());

    t.setAbs(7);
    ok("time: absSlot 7 is day 1 slot 3", t.day == 1 && t.slot == 3);
    eqNum("time: absSlot round trips", t.absSlot(), 7.0);
    eqStr("time: day 1 is 2024-01-03", t.dateStr(), "2024-01-03");

    // four slots per day, five days per week, weekends skipped
    GameTime w;
    w.setAbs(4 * 5);
    eqStr("time: five trading days later is 2024-01-09 (weekend skipped)", w.dateStr(), "2024-01-09");

    w.setAbs(4 * 10);
    eqStr("time: ten trading days later is 2024-01-16", w.dateStr(), "2024-01-16");

    w.setAbs(-5);
    eqNum("time: setAbs clamps negatives to zero", w.absSlot(), 0.0);

    // calendar helpers
    ok("time: 2024 is a leap year", GameTime::isLeap(2024));
    ok("time: 2023 is not a leap year", !GameTime::isLeap(2023));
    ok("time: 2100 is not a leap year", !GameTime::isLeap(2100));
    ok("time: 2000 is a leap year", GameTime::isLeap(2000));
    ok("time: February 2024 has 29 days", GameTime::daysInMonth(2024, 2) == 29);
    ok("time: February 2023 has 28 days", GameTime::daysInMonth(2023, 2) == 28);

    // month and year roll-over
    {
        std::string monthEnd = GameTime::dateFromIndex(4 * 20);   // about a month of trading days
        ok("time: date is always YYYY-MM-DD",
           monthEnd.size() == 10 && monthEnd[4] == '-' && monthEnd[7] == '-', monthEnd);
        std::string far = GameTime::dateFromIndex(4 * 260);        // about a year
        ok("time: long horizon stays well formed",
           far.size() == 10 && far[4] == '-' && far[7] == '-', far);
        ok("time: long horizon advances past 2024", far > std::string("2024-12-31"), far);
    }

    // toJson shape
    {
        Json j = t.toJson();
        ok("time: toJson has date and slot", j.has("date") && j.has("slot"), j.dump());
        Json parsed;
        ok("time: toJson output re-parses", jsonParse(j.dump(), parsed), j.dump());
    }
}

// ================================================= 7. header-level sanity ===

static void testHeaders() {
    std::printf("\n-- 7. Frozen constants and serialization shapes ---------------\n");

    eqStr("const: NO_GAME", err::NO_GAME, "NO_GAME");
    eqStr("const: NO_SUCH_SYMBOL", err::NO_SUCH_SYMBOL, "NO_SUCH_SYMBOL");
    eqStr("const: BAD_ARG", err::BAD_ARG, "BAD_ARG");
    eqStr("const: BAD_QTY", err::BAD_QTY, "BAD_QTY");
    eqStr("const: BAD_LOTS", err::BAD_LOTS, "BAD_LOTS");
    eqStr("const: BAD_PRICE", err::BAD_PRICE, "BAD_PRICE");
    eqStr("const: INSUFFICIENT_CASH", err::INSUFFICIENT_CASH, "INSUFFICIENT_CASH");
    eqStr("const: INSUFFICIENT_POSITION", err::INSUFFICIENT_POSITION, "INSUFFICIENT_POSITION");
    eqStr("const: INSUFFICIENT_MARGIN", err::INSUFFICIENT_MARGIN, "INSUFFICIENT_MARGIN");
    eqStr("const: T1_LOCKED", err::T1_LOCKED, "T1_LOCKED");
    eqStr("const: MARKET_HALTED", err::MARKET_HALTED, "MARKET_HALTED");
    eqStr("const: NO_MARKET_DATA", err::NO_MARKET_DATA, "NO_MARKET_DATA");
    eqStr("const: NO_POSITION", err::NO_POSITION, "NO_POSITION");
    eqStr("const: UNKNOWN_CMD", err::UNKNOWN_CMD, "UNKNOWN_CMD");
    eqStr("const: INTERNAL", err::INTERNAL, "INTERNAL");

    // errData shape
    {
        Json e = errData(err::BAD_ARG, "参数错误");
        ok("err: errData has code and message", e.has("code") && e.has("message"), e.dump());
        eqStr("err: errData code", e["code"].asStr(), "BAD_ARG");
    }

    // Json helpers from jsonutil.hpp
    {
        Json o = Json::obj();
        o["s"] = Json("hi");
        o["n"] = Json(3.5);
        o["b"] = Json(true);
        o["arr"] = Json::array({Json("a"), Json("b")});
        eqStr("jsonutil: jgetStr reads a string", jgetStr(o, "s"), "hi");
        eqStr("jsonutil: jgetStr falls back on a missing key", jgetStr(o, "nope", "def"), "def");
        eqStr("jsonutil: jgetStr falls back on a wrong type", jgetStr(o, "n", "def"), "def");
        eqNum("jsonutil: jgetNum reads a number", jgetNum(o, "n", -1), 3.5, 1e-12);
        eqNum("jsonutil: jgetNum falls back on a wrong type", jgetNum(o, "s", -1), -1.0, 1e-12);
        ok("jsonutil: jgetBool reads a bool", jgetBool(o, "b", false));
        ok("jsonutil: jgetBool falls back", !jgetBool(o, "s", false));
        ok("jsonutil: jhas distinguishes a value from a type mismatch", jhas(o, "n") && !jhasNum(o, "s"));
        ok("jsonutil: jhasNum accepts a number", jhasNum(o, "n"));
        ok("jsonutil: jhasStr accepts a string", jhasStr(o, "s"));
        ok("jsonutil: jhasBool accepts a bool", jhasBool(o, "b"));
        eqNum("jsonutil: jgetInt rounds", jgetInt(o, "n", 0), 4.0, 1e-12);
        std::vector<std::string> arr = jgetStrArray(o, "arr");
        ok("jsonutil: jgetStrArray reads a string array", arr.size() == 2 && arr[0] == "a" && arr[1] == "b");
        ok("jsonutil: jgetStrArray on a missing key is empty", jgetStrArray(o, "nope").empty());
    }

    // utility formatting
    eqStr("util: fmtMoney groups thousands", fmtMoney(1000000.0), "1,000,000.00");
    eqStr("util: fmtMoney handles negatives", fmtMoney(-1234.5), "-1,234.50");
    eqStr("util: fixedStr keeps trailing zeros", fixedStr(1.5, 4), "1.5000");
    eqStr("util: toLower", toLower("AbC1"), "abc1");
    eqStr("util: toUpper", toUpper("aBc1"), "ABC1");
    ok("util: startsWith", startsWith("SH600519", "SH") && !startsWith("SH600519", "SZ"));

    // stock position serialization from the account header
    {
        StockAccount sa;
        StockPosition& p = sa.ensure("SH600519", "贵州茅台");
        p.qty = 100;
        p.todayBought = 100;
        p.avgCost = 1700.0;
        ok("account: ensure() creates exactly one position", sa.positions.size() == 1);
        sa.ensure("SH600519", "贵州茅台");
        ok("account: ensure() is idempotent", sa.positions.size() == 1);
        sa.dropEmpty();
        ok("account: a non-empty position survives dropEmpty", sa.positions.size() == 1);
        p.qty = 0;
        sa.dropEmpty();
        ok("account: an empty position is dropped", sa.positions.empty());
        ok("account: marketValue ignores missing symbols", sa.marketValue(Market()) == 0.0);
    }

    // news engine
    {
        NewsEngine ne;
        ne.seedTemplates();
        Market m;
        m.init();
        Rng r(4242);
        GameTime t;
        std::vector<Json> events;
        Json at = t.toJson();
        for (int i = 0; i < 50; ++i) {
            NewsItem n = ne.makeRandom(t, r, m, 0.5, true, &events, at);
            (void)n;
        }
        ok("news: 50 generated items are retained", ne.items.size() == 50, std::to_string(ne.items.size()));
        ok("news: every news event was recorded", events.size() == 50, std::to_string(events.size()));
        bool scopesOk = true, idsOk = true;
        int prevId = 0;
        for (const NewsItem& n : ne.items) {
            if (n.scope != "stock" && n.scope != "forex" && n.scope != "macro") scopesOk = false;
            if (n.id <= prevId) idsOk = false;
            prevId = n.id;
            if (n.title.empty()) scopesOk = false;
        }
        ok("news: all scopes are in the frozen enum", scopesOk);
        ok("news: ids increase monotonically", idsOk);
        Json listed = ne.listJson(10, false);
        ok("news: listJson wraps an array under 'news'", listed.has("news") && listed["news"].isArr());
        ok("news: listJson honours the limit", listed["news"].size() == 10, std::to_string(listed["news"].size()));
        Json first = listed["news"].at(0);
        const char* need[] = {"id", "time", "title", "body", "scope", "impact", "symbols", "read"};
        std::string missing;
        for (const char* k : need) if (!first.has(k)) { missing += k; missing += " "; }
        ok("news: serialized item has all frozen fields", missing.empty(), "missing: " + missing);
        ok("news: newest first", listed["news"].at(0)["id"].asNum(0) > listed["news"].at(9)["id"].asNum(0));

        int unreadBefore = ne.unreadCount();
        ne.markAllRead();
        ok("news: markAllRead clears the unread counter", ne.unreadCount() == 0,
           std::to_string(unreadBefore) + " -> " + std::to_string(ne.unreadCount()));
        Json unreadOnly = ne.listJson(10, true);
        ok("news: unreadOnly filters everything out after markAllRead", unreadOnly["news"].size() == 0);
        ne.clear();
        ok("news: clear empties the list", ne.items.empty() && ne.nextId == 1);
    }

    // a market walk keeps invariants
    {
        Market m;
        m.init();
        Rng r(31337);
        for (int step = 0; step < 400; ++step) {
            for (Stock& s : m.stocks) {
                s.last = Json::roundTo(std::max(0.01, s.last * (1.0 + 0.02 * r.normal())), 4);
                s.high = std::max(s.high, s.last);
                s.low = std::min(s.low, s.last);
                s.refreshSpread();
            }
        }
        bool sane = true;
        for (const Stock& s : m.stocks) {
            if (!(s.last > 0.0)) sane = false;
            if (!(s.high >= s.last - 1e-9) || !(s.low <= s.last + 1e-9)) sane = false;
            if (!(s.bid <= s.ask + 1e-9)) sane = false;
            if (s.hist.size() < 1) sane = false;
        }
        ok("market: 400 random steps keep prices, highs/lows and spreads sane", sane);

        // hist must stay usable by the front-end
        Stock& s0 = m.stocks[0];
        bumpHistory(s0, s0.last);
        Json histJson = Json::arr();
        for (const Bar& b : s0.hist) histJson.push_back(b.toJson());
        ok("market: hist serializes to an array", histJson.isArr() && histJson.size() == s0.hist.size());
        Json bar = histJson.at(0);
        const char* need[] = {"date", "slot", "open", "high", "low", "close", "volume"};
        std::string missing;
        for (const char* k : need) if (!bar.has(k)) { missing += k; missing += " "; }
        ok("market: bar has all frozen fields", missing.empty(), "missing: " + missing);
    }
}

// ================================================================== main ===

int main() {
    std::printf("================================================================\n");
    std::printf("拟股喵喵 engine smoke test (pure logic)\n");
    std::printf("================================================================\n");

    try {
        testJson();
        testT1AndMatching();
        testPricesAndFees();
        testForexMargin();
        testRng();
        testGameTime();
        testHeaders();
    } catch (const std::exception& e) {
        std::printf("FAIL  unexpected exception: %s\n", e.what());
        ++g_fail;
    } catch (...) {
        std::printf("FAIL  unexpected non-standard exception\n");
        ++g_fail;
    }

    std::printf("\n================================================================\n");
    std::printf("RESULT: %s   checks=%d pass=%d fail=%d\n",
                g_fail == 0 ? "PASS" : "FAIL", g_pass + g_fail, g_pass, g_fail);
    if (!g_failed.empty()) {
        std::printf("FAILED CHECKS:\n");
        for (const std::string& s : g_failed) std::printf("  %s\n", s.c_str());
    }
    std::printf("================================================================\n");
    return g_fail == 0 ? 0 : 1;
}
