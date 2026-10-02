// market.hpp - 标的定义 + 行情随机游走（几何布朗 + 均值回归 + 波动率聚集）
#pragma once

#include "json.hpp"
#include "util.hpp"

#include <string>
#include <vector>
#include <cmath>
#include <algorithm>

namespace tsim {

// 一根 K 线（hist 元素）
struct Bar {
    std::string date;
    int slot = 0;
    double open = 0.0, high = 0.0, low = 0.0, close = 0.0;
    long long volume = 0;

    Json toJson() const {
        Json j = Json::obj();
        j["date"] = Json(date);
        j["slot"] = Json(slot);
        j["open"] = Json::dec(round4(open));
        j["high"] = Json::dec(round4(high));
        j["low"] = Json::dec(round4(low));
        j["close"] = Json::dec(round4(close));
        j["volume"] = Json(static_cast<double>(volume));
        return j;
    }
};

struct StockDef {
    std::string symbol;
    std::string name;
    std::string market;    // SH / SZ / HK / US
    std::string currency;  // CNY / HKD / USD
    double basePrice = 10.0;
    double baseVol = 0.02;
    double pe = 15.0;
};

struct Stock {
    StockDef def;
    double prevClose = 0.0;
    double open = 0.0;
    double last = 0.0;
    double high = 0.0;
    double low = 0.0;
    long long volume = 0;
    double bid = 0.0;
    double ask = 0.0;
    bool halted = false;
    double vol = 0.02;
    double fair = 1.0;
    double pe = 15.0;
    std::vector<Bar> hist;
    int pumpBars = 0;
    double pumpPct = 0.0;

    void refreshSpread() {
        double sp = std::max(last * 0.0004, 0.01);
        bid = round4(last - sp / 2.0);
        ask = round4(last + sp / 2.0);
        if (ask < bid) ask = bid;
    }
};

struct ForexDef {
    std::string symbol;
    std::string name;
    double basePrice = 1.0;
    int digits = 4;
    double pip = 0.0001;
    double pointValue = 10.0;
    bool usdBase = true;
};

struct Forex {
    ForexDef def;
    double prevClose = 0.0;
    double open = 0.0;
    double last = 0.0;
    double high = 0.0;
    double low = 0.0;
    double vol = 0.006;
    double fair = 1.0;
    std::vector<Bar> hist;
    int pumpBars = 0;
    double pumpPct = 0.0;

    Json toJson() const {
        Json j = Json::obj();
        int dg = def.digits;
        double sp = 2.0 * def.pip;
        j["symbol"] = Json(def.symbol);
        j["name"] = Json(def.name);
        j["last"] = Json::dec(Json::roundTo(last, dg));
        j["prevClose"] = Json::dec(Json::roundTo(prevClose, dg));
        j["open"] = Json::dec(Json::roundTo(open, dg));
        j["high"] = Json::dec(Json::roundTo(high, dg));
        j["low"] = Json::dec(Json::roundTo(low, dg));
        j["changePct"] = Json::dec(round6(prevClose > 0 ? (last - prevClose) / prevClose : 0.0));
        j["bid"] = Json::dec(Json::roundTo(last - sp / 2.0, dg));
        j["ask"] = Json::dec(Json::roundTo(last + sp / 2.0, dg));
        j["spread"] = Json::dec(Json::roundTo(sp, dg));
        j["digits"] = Json(dg);
        j["pip"] = Json(def.pip);
        j["pointValue"] = Json(def.pointValue);
        Json h = Json::arr();
        for (const Bar& b : hist) h.push_back(b.toJson());
        j["hist"] = h;
        return j;
    }
};

class Market {
public:
    std::vector<Stock> stocks;
    std::vector<Forex> forex;

    void init() {
        stocks.clear();
        forex.clear();
        addStock("SH600519", "贵州茅台", "SH", "CNY", 1700.0, 0.011, 30.0);
        addStock("SH601398", "工商银行", "SH", "CNY", 5.60, 0.008, 5.2);
        addStock("SH601318", "中国平安", "SH", "CNY", 42.50, 0.012, 8.1);
        addStock("SH600036", "招商银行", "SH", "CNY", 33.80, 0.011, 6.4);
        addStock("SH600030", "中信证券", "SH", "CNY", 21.30, 0.014, 17.0);
        addStock("SZ000001", "平安银行", "SZ", "CNY", 10.20, 0.011, 5.0);
        addStock("SZ000858", "五粮液", "SZ", "CNY", 148.00, 0.013, 18.5);
        addStock("SZ300750", "宁德时代", "SZ", "CNY", 185.00, 0.018, 21.0);
        addStock("SZ002594", "比亚迪", "SZ", "CNY", 245.00, 0.017, 22.7);
        addStock("SZ000333", "美的集团", "SZ", "CNY", 62.40, 0.012, 12.1);
        addStock("HK00700", "腾讯控股", "HK", "HKD", 305.0, 0.015, 14.2);
        addStock("HK09988", "阿里巴巴", "HK", "HKD", 72.50, 0.017, 11.6);
        addStock("HK00939", "建设银行", "HK", "HKD", 5.10, 0.009, 4.1);
        addStock("HK03690", "美团", "HK", "HKD", 88.00, 0.019, 25.3);
        addStock("HK01810", "小米集团", "HK", "HKD", 15.60, 0.018, 19.8);
        addStock("USAAPL", "苹果", "US", "USD", 182.5, 0.012, 28.4);
        addStock("USMSFT", "微软", "US", "USD", 415.0, 0.011, 33.2);
        addStock("USNVDA", "英伟达", "US", "USD", 88.5, 0.021, 55.7);
        addStock("USTSLA", "特斯拉", "US", "USD", 245.0, 0.022, 42.5);
        addStock("USGOOG", "谷歌", "US", "USD", 152.0, 0.012, 26.1);

        addForex("EURUSD", "欧元/美元", 1.0840, 4, 0.0001, 10.0, 0.0050);
        addForex("GBPUSD", "英镑/美元", 1.2650, 4, 0.0001, 10.0, 0.0055);
        addForex("USDJPY", "美元/日元", 151.20, 3, 0.01, 10.0, 0.0055);
        addForex("AUDUSD", "澳元/美元", 0.6580, 4, 0.0001, 10.0, 0.0060);
        addForex("USDCHF", "美元/瑞郎", 0.9020, 4, 0.0001, 10.0, 0.0048);
        addForex("USDCAD", "美元/加元", 1.3560, 4, 0.0001, 10.0, 0.0048);
        addForex("NZDUSD", "纽元/美元", 0.6020, 4, 0.0001, 10.0, 0.0062);
        addForex("EURJPY", "欧元/日元", 163.80, 3, 0.01, 10.0, 0.0058);
        addForex("GBPJPY", "英镑/日元", 191.50, 3, 0.01, 10.0, 0.0065);
        addForex("XAUUSD", "黄金/美元", 2180.0, 2, 0.01, 10.0, 0.0070);
    }

    Stock* findStock(const std::string& symRaw) {
        const std::string sym = canonical(symRaw);
        for (Stock& s : stocks) if (s.def.symbol == sym) return &s;
        return nullptr;
    }
    Forex* findForex(const std::string& symRaw) {
        const std::string sym = toUpper(symRaw);
        for (Forex& f : forex) if (f.def.symbol == sym) return &f;
        return nullptr;
    }
    bool isForexSymbol(const std::string& sym) const {
        const std::string u = toUpper(sym);
        for (const Forex& f : forex) if (f.def.symbol == u) return true;
        return false;
    }
    std::string marketOf(const std::string& sym) const {
        return isForexSymbol(sym) ? "forex" : "stock";
    }

    static std::string canonical(const std::string& raw) {
        std::string s;
        s.reserve(raw.size());
        for (char c : raw) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            s.push_back(c);
        }
        return toUpper(s);
    }

    // 回填 60 根历史 K 线：确定性随机游走，保证开局 K 线就有波动
    static void seedHistory(Stock& s) {
        Rng hr(0x9E3779B97F4A7C15ULL ^ static_cast<uint64_t>(
            static_cast<unsigned>(s.def.symbol.size()) * 131U + static_cast<unsigned>(s.def.symbol[0]) * 7U));
        double px = s.def.basePrice;
        double cur = px;
        std::vector<double> closes;
        closes.reserve(60);
        for (int i = 0; i < 60; ++i) {
            double r = s.def.baseVol * hr.normal() * 0.9;
            if (r > 0.12) r = 0.12;
            if (r < -0.12) r = -0.12;
            cur *= (1.0 + r);
            if (cur < 0.05) cur = 0.05;
            closes.push_back(cur);
        }
        double scale = px / closes.back();
        for (int i = 0; i < 60; ++i) closes[static_cast<size_t>(i)] *= scale;
        s.hist.clear();
        for (int i = 0; i < 60; ++i) {
            double close = (i == 59) ? px : closes[static_cast<size_t>(i)];
            double op = (i == 0) ? px : closes[static_cast<size_t>(i - 1)];
            Bar b;
            long long dayIdx = -1 + static_cast<long long>(i) / 4;
            b.date = GameTime::dateFromIndex(dayIdx);
            b.slot = i % 4;
            b.open = round4(op);
            b.close = round4(close);
            double amp = std::fabs(hr.normal()) * s.def.baseVol * 0.5;
            b.high = round4(std::max(b.open, b.close) * (1.0 + amp));
            b.low = round4(std::max(0.01, std::min(b.open, b.close) * (1.0 - amp)));
            b.volume = static_cast<long long>(8000.0 + std::fabs(hr.normal()) * 40000.0);
            s.hist.push_back(b);
        }
        s.prevClose = (s.hist.size() >= 2) ? s.hist[s.hist.size() - 2].close : px;
        s.open = s.hist.back().open;
        s.high = s.hist.back().high;
        s.low = s.hist.back().low;
        s.last = px;
        s.volume = s.hist.back().volume;
        s.refreshSpread();
    }

    static void seedHistoryForex(Forex& f) {
        Rng hr(0xC2B2AE3D27D4EB4FULL ^ static_cast<uint64_t>(
            static_cast<unsigned>(f.def.symbol.size()) * 97U + static_cast<unsigned>(f.def.symbol[0]) * 13U));
        double px = f.def.basePrice;
        double vol = px * 0.0007;
        double cur = px;
        std::vector<double> closes;
        closes.reserve(60);
        for (int i = 0; i < 60; ++i) {
            double r = (vol / px) * hr.normal() * 0.9;
            if (r > 0.03) r = 0.03;
            if (r < -0.03) r = -0.03;
            cur *= (1.0 + r);
            if (cur < px * 0.2) cur = px * 0.2;
            closes.push_back(cur);
        }
        double scale = px / closes.back();
        for (int i = 0; i < 60; ++i) closes[static_cast<size_t>(i)] *= scale;
        f.hist.clear();
        for (int i = 0; i < 60; ++i) {
            double close = (i == 59) ? px : closes[static_cast<size_t>(i)];
            double op = (i == 0) ? px : closes[static_cast<size_t>(i - 1)];
            Bar b;
            long long dayIdx = -1 + static_cast<long long>(i) / 4;
            b.date = GameTime::dateFromIndex(dayIdx);
            b.slot = i % 4;
            b.open = Json::roundTo(op, f.def.digits);
            b.close = Json::roundTo(close, f.def.digits);
            double amp = std::fabs(hr.normal()) * vol * 0.4 + f.def.pip * 0.5;
            b.high = Json::roundTo(std::max(b.open, b.close) + amp, f.def.digits);
            b.low = Json::roundTo(std::max(0.000001, std::min(b.open, b.close) - amp), f.def.digits);
            b.volume = static_cast<long long>(40000.0 + std::fabs(hr.normal()) * 200000.0);
            f.hist.push_back(b);
        }
        f.prevClose = (f.hist.size() >= 2) ? f.hist[f.hist.size() - 2].close : px;
        f.open = f.hist.back().open;
        f.high = f.hist.back().high;
        f.low = f.hist.back().low;
        f.last = px;
    }

private:
    void addStock(const std::string& sym, const std::string& name, const std::string& mkt,
                  const std::string& cur, double px, double vol, double pe) {
        Stock s;
        s.def.symbol = sym;
        s.def.name = name;
        s.def.market = mkt;
        s.def.currency = cur;
        s.def.basePrice = px;
        s.def.baseVol = vol;
        s.def.pe = pe;
        s.prevClose = px;
        s.open = px;
        s.last = px;
        s.high = px;
        s.low = px;
        s.vol = vol;
        s.pe = pe;
        seedHistory(s);
        stocks.push_back(s);
    }

    void addForex(const std::string& sym, const std::string& name, double px, int digits,
                  double pip, double pointValue, double vol) {
        Forex f;
        f.def.symbol = sym;
        f.def.name = name;
        f.def.basePrice = px;
        f.def.digits = digits;
        f.def.pip = pip;
        f.def.pointValue = pointValue;
        f.def.usdBase = startsWith(sym, "USD");
        f.prevClose = px;
        f.open = px;
        f.last = px;
        f.high = px;
        f.low = px;
        f.vol = vol;
        seedHistoryForex(f);
        forex.push_back(f);
    }
};

}  // namespace tsim
