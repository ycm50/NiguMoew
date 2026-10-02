// account.hpp - 股票账户 + 外汇账户（保证金/浮动盈亏/爆仓）
#pragma once

#include "json.hpp"
#include "util.hpp"
#include "market.hpp"

#include <string>
#include <vector>
#include <cmath>
#include <algorithm>

namespace tsim {

// ---------------- 股票持仓 ----------------
struct StockPosition {
    std::string symbol;
    std::string name;
    long long qty = 0;         // 总持仓
    long long frozenQty = 0;   // 挂单占用（属于 qty 的一部分）
    long long todayBought = 0; // 当日买入（T+1 锁定）
    double avgCost = 0.0;
    // 融资买入产生的负债（杠杆 > 1 时为正）。卖出时按比例偿还。
    double marginDebt = 0.0;

    // ---- 融券做空（协议 v1.0.4）----
    // 空头持仓用独立的字段记录：借入股票的市值 = shortQty * 开仓价。
    long long shortQty = 0;        // 当前空头股数（>0 表示有做空）
    double shortAvgPrice = 0.0;    // 做空均价
    double shortMargin = 0.0;      // 已冻结的做空保证金
    long long todayShorted = 0;    // 当日新开空（T+1 锁定，当日不可平）

    /** 是否为纯空头（无多头持仓）。 */
    bool isShortOnly() const { return qty <= 0 && shortQty > 0; }
    /** 是否多空双向都有。 */
    bool isMixed() const { return qty > 0 && shortQty > 0; }
};

// ---------------- 外汇持仓 ----------------
struct ForexPosition {
    int id = 0;
    std::string symbol;
    std::string name;
    std::string side;   // long | short
    double lots = 0.0;
    double openRate = 0.0;
    double last = 0.0;
    double margin = 0.0;
    double swap = 0.0;
    double stopLoss = 0.0;
    double takeProfit = 0.0;

    // 以 rate 计算浮动盈亏（USD）
    double pnlAt(double rate) const {
        double diff = (side == "long") ? (rate - openRate) : (openRate - rate);
        return diff * lots * 1000.0;
    }
};

// ---------------- 股票账户 ----------------
class StockAccount {
public:
    double cash = 1000000.0;
    double frozen = 0.0;
    double initialCash = 1000000.0;
    std::vector<StockPosition> positions;

    StockPosition* find(const std::string& sym) {
        for (StockPosition& p : positions) if (p.symbol == sym) return &p;
        return nullptr;
    }
    const StockPosition* find(const std::string& sym) const {
        for (const StockPosition& p : positions) if (p.symbol == sym) return &p;
        return nullptr;
    }
    StockPosition& ensure(const std::string& sym, const std::string& name) {
        StockPosition* p = find(sym);
        if (p) return *p;
        StockPosition np;
        np.symbol = sym;
        np.name = name;
        positions.push_back(np);
        return positions.back();
    }
    void dropEmpty() {
        for (size_t i = positions.size(); i-- > 0;) {
            const StockPosition& p = positions[i];
            // 注意：必须把做空（shortQty）也算作"非空"，否则开空后会被误删，
            // 导致 cover 永远报 NO_POSITION。
            if (p.qty <= 0 && p.frozenQty <= 0 && p.shortQty <= 0) {
                positions.erase(positions.begin() + static_cast<long>(i));
            }
        }
    }
    long long sellable(const std::string& sym) const {
        const StockPosition* p = find(sym);
        if (!p) return 0;
        long long v = p->qty - p->todayBought - p->frozenQty;
        return v > 0 ? v : 0;
    }
    void unlockT1() {
        for (StockPosition& p : positions) {
            p.todayBought = 0;
            p.todayShorted = 0;   // 做空同样适用 T+1
        }
    }
    double marketValue(const Market& m) const {
        double v = 0.0;
        for (const StockPosition& p : positions) {
            for (const Stock& x : m.stocks) {
                if (x.def.symbol == p.symbol) { v += static_cast<double>(p.qty) * x.last; break; }
            }
        }
        return v;
    }
    void clear() {
        positions.clear();
        cash = initialCash;
        frozen = 0.0;
    }
};

// ---------------- 外汇账户 ----------------
class ForexAccount {
public:
    double cash = 10000.0;
    double initialCash = 10000.0;
    double realized = 0.0;
    std::string currency = "USD";
    std::vector<ForexPosition> positions;
    int nextPositionId = 1;

    ForexPosition* find(int id) {
        for (ForexPosition& p : positions) if (p.id == id) return &p;
        return nullptr;
    }
    double usedMargin() const {
        double m = 0.0;
        for (const ForexPosition& p : positions) m += p.margin;
        return m;
    }
    double floatPnl() const {
        double v = 0.0;
        for (const ForexPosition& p : positions) v += p.pnlAt(p.last);
        return v;
    }
    double swapSum() const {
        double v = 0.0;
        for (const ForexPosition& p : positions) v += p.swap;
        return v;
    }
    void dropEmpty() {
        for (size_t i = positions.size(); i-- > 0;) {
            if (positions[i].lots <= 1e-9) positions.erase(positions.begin() + static_cast<long>(i));
        }
    }
    // 强制平仓：移除仓位（保证金随之释放，因为 usedMargin 是按现存仓位累加的）
    void dropMargin() { dropEmpty(); }
    void clear() {
        positions.clear();
        cash = initialCash;
        realized = 0.0;
        nextPositionId = 1;
    }
};

}  // namespace tsim
