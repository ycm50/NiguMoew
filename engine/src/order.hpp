// order.hpp - 订单（限价单按价格优先跨 slot 撮合 / 市价单即时成交 / 部分成交 / 过期）
#pragma once

#include "json.hpp"
#include "util.hpp"
#include "market.hpp"

#include <string>
#include <vector>
#include <cmath>
#include <algorithm>

namespace tsim {

struct Order {
    int id = 0;
    std::string symbol;
    std::string market;  // stock | forex
    std::string side;    // buy | sell
    std::string type;    // market | limit
    long long qty = 0;
    long long filled = 0;
    double price = 0.0;  // 限价
    std::string status;// open | partial | filled | cancelled | expired
    GameTime created;
    long long expireAtAbsSlot = -1;  // <0 表示不过期（绝对 slot）
    long long ttlSlots = 0;          // 协议 §3.8a：限价单 TTL（时间片数），0=不过期
    double forexLots = 0.0;          // 外汇用（市价即时成交，保留字段以便前端展示）
    std::string slTPKey;             // 关联的止盈止损参数（引擎内部）

    long long remaining() const { return qty - filled; }
    bool isActive() const { return status == "open" || status == "partial"; }

    Json toJson() const {
        Json j = Json::obj();
        j["id"] = Json(id);
        j["symbol"] = Json(symbol);
        j["market"] = Json(market);
        j["side"] = Json(side);
        j["type"] = Json(type);
        j["qty"] = Json(static_cast<double>(qty));
        j["filled"] = Json(static_cast<double>(filled));
        j["price"] = Json::dec(round4(price));
        j["status"] = Json(status);
        j["created"] = created.toJson();
        return j;
    }
};

class OrderBook {
public:
    std::vector<Order> orders;
    int nextId = 1;

    Order* find(int id) {
        for (Order& o : orders) if (o.id == id) return &o;
        return nullptr;
    }
    Order& add(const Order& o) {
        Order copy = o;
        copy.id = nextId++;
        orders.push_back(copy);
        return orders.back();
    }
    // 清理已终态订单：同一时间片内刚成交/撤销的订单保留，便于响应回显与前端刷新
    void purgeDone(const GameTime& now) {
        for (size_t i = orders.size(); i-- > 0;) {
            const Order& o = orders[i];
            if (o.isActive()) continue;
            if (o.created.absSlot() == now.absSlot()) continue;
            orders.erase(orders.begin() + static_cast<long>(i));
        }
    }
    void purgeAll() { orders.clear(); }
    int activeCount() const {
        int c = 0;
        for (const Order& o : orders) if (o.isActive()) ++c;
        return c;
    }
    void clear() { orders.clear(); nextId = 1; }

    // 收集当前所有 active 订单（按价格优先 + 先来先服务排序）
    // side=buy: 价格从高到低；side=sell: 价格从低到高；市价单最优先。
    std::vector<int> matchOrderCandidates() const {
        std::vector<int> ids;
        for (const Order& o : orders) if (o.isActive()) ids.push_back(o.id);
        std::stable_sort(ids.begin(), ids.end(), [this](int a, int b) {
            const Order* oa = nullptr;
            const Order* ob = nullptr;
            for (const Order& o : orders) {
                if (o.id == a) oa = &o;
                if (o.id == b) ob = &o;
            }
            if (!oa || !ob) return false;
            if (oa->side != ob->side) return oa->side == "buy";  // 买单先撮合，简化实现
            if (oa->type != ob->type) return oa->type == "market";
            bool buy = (oa->side == "buy");
            if (std::fabs(oa->price - ob->price) > 1e-9)
                return buy ? (oa->price > ob->price) : (oa->price < ob->price);
            return oa->id < ob->id;
        });
        return ids;
    }
};

}  // namespace tsim
