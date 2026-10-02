// news.hpp - 新闻生成 + 对行情的即时冲击 + 批量报道
#pragma once

#include "json.hpp"
#include "util.hpp"
#include "market.hpp"

#include <string>
#include <vector>
#include <cmath>
#include <algorithm>

namespace tsim {

struct NewsItem {
    int id = 0;
    GameTime time;
    std::string title;
    std::string body;
    std::string scope;   // stock | forex | macro
    double impact = 0.0; // 小数形式
    std::vector<std::string> symbols;
    bool read = false;

    Json toJson() const {
        Json j = Json::obj();
        j["id"] = Json(id);
        j["time"] = time.toJson();
        j["title"] = Json(title);
        j["body"] = Json(body);
        j["scope"] = Json(scope);
        j["impact"] = Json::dec(round6(impact));
        Json ss = Json::arr();
        for (const std::string& s : symbols) ss.push_back(Json(s));
        j["symbols"] = ss;
        j["read"] = Json(read);
        return j;
    }
};

struct NewsTemplate {
    const char* title;
    const char* body;
    const char* scope;  // empty = 随机
};

class NewsEngine {
public:
    std::vector<NewsItem> items;
    int nextId = 1;

    // 商店里的新闻模板（宏观 / 个股 / 外汇）
    void seedTemplates() {
        macro_ = {
            {"央行意外降准 0.5 个百分点", "央行宣布下调存款准备金率 0.5 个百分点，释放长期资金约 1 万亿元，市场流动性预期改善，A 股银行、地产板块领涨。", "macro"},
            {"美联储维持利率不变 释放鹰派信号", "美联储议息会议维持联邦基金利率不变，点阵图显示年内降息预期下调，美元指数走强，非美货币普遍承压。", "macro"},
            {"国常会部署稳增长措施", "国务院常务会议部署进一步扩大内需、稳定外贸的政策组合，基建与消费相关行业受到提振。", "macro"},
            {"隔夜美股大幅波动 科技股分化", "隔夜纳斯达克指数震荡收跌，大型科技股走势分化，市场关注即将公布的财报季数据。", "macro"},
            {"欧洲央行官员暗示可能提前降息", "欧洲央行多位管委表示通胀回落速度快于预期，市场对年内降息押注升温，欧元短线走弱。", "macro"},
            {"国际油价走高 通胀预期升温", "受地缘局势影响，布伦特原油上涨逾 2%，市场担忧输入性通胀压力回升。", "macro"},
        };
        stock_ = {
            {"%s 发布年度业绩预告 净利同比增长", "%s 发布年度业绩预告，预计归母净利润同比增长 20%-35%，主要受益于主营业务量价齐升。", "stock"},
            {"%s 获多家机构上调评级", "多家券商研报将 %s 评级上调至“买入”，认为其估值仍处历史低位，中长期成长确定性较强。", "stock"},
            {"%s 大股东宣布增持计划", "%s 公告称控股股东拟在未来 6 个月内增持公司股份，金额不低于 5 亿元，彰显对长期发展的信心。", "stock"},
            {"%s 遭监管问询 股价承压", "%s 收到监管问询函，要求说明近期业绩波动及关联交易情况，短期市场情绪偏谨慎。", "stock"},
            {"%s 新项目投产 产能爬坡顺利", "%s 宣布新建产线正式投产，产能爬坡进度超预期，预计将为下半年贡献增量收入。", "stock"},
            {"%s 遭机构下调目标价", "有机构下调 %s 目标价，认为行业竞争加剧，毛利率或面临一定压力。", "stock"},
        };
        forex_ = {
            {"美国非农就业数据超预期", "美国最新非农就业人数远超市场预期，美元指数短线拉升，非美货币普遍走低。", "forex"},
            {"欧元区通胀数据低于预期", "欧元区 CPI 同比涨幅低于预期，市场加大对欧洲央行降息的押注，欧元承压。", "forex"},
            {"日本央行干预汇市传闻升温", "市场传闻日本财务省可能入市干预，日元短线快速波动，美元兑日元回吐涨幅。", "forex"},
            {"英国零售销售意外下滑", "英国零售销售环比意外下滑，英镑兑美元走弱，市场关注英国央行政策路径。", "forex"},
            {"避险情绪升温 黄金刷新阶段高位", "地缘政治风险上升，避险资金涌入黄金，金价刷新阶段高位，美元与黄金同涨。", "forex"},
        };
    }

    // impact 数值的语义强度
    static double scaleImpact(double impact, bool good) {
        double a = std::fabs(impact);
        return good ? a : -a;
    }

    // 生成一条随机新闻；applyImpact=true 时立即冲击行情
    NewsItem makeRandom(const GameTime& t, Rng& rng, Market& mkt, double winRate, bool applyImpact,
                        std::vector<Json>* events, const Json& atTime) {
        NewsItem n;
        n.id = nextId++;
        n.time = t;

        int roll = rng.below(100);
        std::string scope;
        if (roll < 45) scope = "stock";
        else if (roll < 75) scope = "forex";
        else scope = "macro";

        // 选择受影响标的
        if (scope == "stock" && !mkt.stocks.empty()) {
            const NewsTemplate& tpl = stock_[static_cast<size_t>(rng.below(static_cast<int>(stock_.size())))];
            // 选一个标的
            int idx = rng.below(static_cast<int>(mkt.stocks.size()));
            Stock& s = mkt.stocks[static_cast<size_t>(idx)];
            n.symbols.push_back(s.def.symbol);
            n.scope = "stock";
            n.title = formatTemplate(tpl.title, s.def.name, s.def.symbol);
            n.body = formatTemplate(tpl.body, s.def.name, s.def.symbol);
            bool good = rng.uniform() < winRate;
            double mag = rng.range(0.01, 0.06);
            if (titleHas(tpl.title, "遭") || titleHas(tpl.title, "下调")) good = !good;
            double imp = good ? mag : -mag;
            n.impact = round6(imp);
            if (applyImpact) {
                s.last = std::max(0.01, round4(s.last * (1.0 + imp)));
                s.high = std::max(s.high, s.last);
                s.low = std::min(s.low, s.last);
                s.volume += static_cast<long long>(s.volume / 20 + 1000);
                s.refreshSpread();
            }
        } else if (scope == "forex" && !mkt.forex.empty()) {
            const char* t3 = forex_[static_cast<size_t>(rng.below(static_cast<int>(forex_.size())))].title;
            const char* b3 = forex_[static_cast<size_t>(rng.below(static_cast<int>(forex_.size())))].body;
            int idx = rng.below(static_cast<int>(mkt.forex.size()));
            Forex& f = mkt.forex[static_cast<size_t>(idx)];
            n.symbols.push_back(f.def.symbol);
            n.scope = "forex";
            n.title = t3;
            n.body = b3;
            bool good = rng.uniform() < winRate;
            double mag = rng.range(0.002, 0.012);
            double imp = (startsWith(n.title, "美国") || startsWith(n.title, "英国")) ? (good ? mag : -mag)
                                                                                     : (good ? -mag : mag);
            if (rng.uniform() < 0.5) imp = -imp;
            n.impact = round6(imp);
            if (applyImpact) {
                f.last = std::max(0.0001, Json::roundTo(f.last * (1.0 + imp), f.def.digits));
                f.high = std::max(f.high, f.last);
                f.low = std::min(f.low, f.last);
            }
        } else {
            const NewsTemplate& tpl = macro_[static_cast<size_t>(rng.below(static_cast<int>(macro_.size())))];
            n.scope = "macro";
            n.title = tpl.title;
            n.body = tpl.body;
            bool good = rng.uniform() < winRate;
            double mag = rng.range(0.005, 0.025);
            double imp = good ? mag : -mag;
            n.impact = round6(imp);
            if (applyImpact) {
                for (Stock& s : mkt.stocks) {
                    s.last = std::max(0.01, round4(s.last * (1.0 + imp * (0.5 + rng.uniform()))));
                    s.high = std::max(s.high, s.last);
                    s.low = std::min(s.low, s.last);
                    s.refreshSpread();
                }
                for (Forex& f : mkt.forex) {
                    f.last = std::max(0.0001, Json::roundTo(f.last * (1.0 + imp * 0.3), f.def.digits));
                    f.high = std::max(f.high, f.last);
                    f.low = std::min(f.low, f.last);
                }
            }
        }

        items.push_back(n);
        if (items.size() > 500) items.erase(items.begin(), items.begin() + 100);
        if (events) {
            Json e = Json::obj();
            e["kind"] = Json("news");
            e["newsId"] = Json(n.id);
            e["title"] = Json(n.title);
            e["at"] = atTime;
            events->push_back(e);
        }
        return n;
    }

    NewsItem makeCustom(const GameTime& t, const std::string& title, const std::string& body,
                        const std::string& scopeIn, double impact, const std::vector<std::string>& symbols,
                        Market& mkt, std::vector<Json>* events, const Json& atTime) {
        NewsItem n;
        n.id = nextId++;
        n.time = t;
        n.title = title.empty() ? std::string("手动新闻") : title;
        n.body = body;
        n.scope = (scopeIn == "stock" || scopeIn == "forex" || scopeIn == "macro") ? scopeIn : "macro";
        n.impact = round6(impact);
        n.symbols = symbols;

        // 立即影响行情
        if (n.scope == "stock" || n.scope == "macro") {
            if (!n.symbols.empty()) {
                for (const std::string& sym : n.symbols) {
                    Stock* s = mkt.findStock(sym);
                    if (s) {
                        s->last = std::max(0.01, round4(s->last * (1.0 + impact)));
                        s->high = std::max(s->high, s->last);
                        s->low = std::min(s->low, s->last);
                        s->refreshSpread();
                    }
                }
            } else if (n.scope == "macro") {
                for (Stock& s : mkt.stocks) {
                    s.last = std::max(0.01, round4(s.last * (1.0 + impact)));
                    s.high = std::max(s.high, s.last);
                    s.low = std::min(s.low, s.last);
                    s.refreshSpread();
                }
                for (Forex& f : mkt.forex) {
                    f.last = std::max(0.0001, Json::roundTo(f.last * (1.0 + impact * 0.5), f.def.digits));
                    f.high = std::max(f.high, f.last);
                    f.low = std::min(f.low, f.last);
                }
            }
        } else if (n.scope == "forex") {
            if (!n.symbols.empty()) {
                for (const std::string& sym : n.symbols) {
                    Forex* f = mkt.findForex(sym);
                    if (f) {
                        f->last = std::max(0.0001, Json::roundTo(f->last * (1.0 + impact), f->def.digits));
                        f->high = std::max(f->high, f->last);
                        f->low = std::min(f->low, f->last);
                    }
                }
            } else {
                for (Forex& f : mkt.forex) {
                    f.last = std::max(0.0001, Json::roundTo(f.last * (1.0 + impact), f.def.digits));
                    f.high = std::max(f.high, f.last);
                    f.low = std::min(f.low, f.last);
                }
            }
        }

        items.push_back(n);
        if (events) {
            Json e = Json::obj();
            e["kind"] = Json("news");
            e["newsId"] = Json(n.id);
            e["title"] = Json(n.title);
            e["at"] = atTime;
            events->push_back(e);
        }
        return n;
    }

    void markAllRead() { for (NewsItem& n : items) n.read = true; }
    int unreadCount() const { int c = 0; for (const NewsItem& n : items) if (!n.read) ++c; return c; }

    Json listJson(int limit, bool unreadOnly) const {
        Json out = Json::arr();
        int cnt = 0;
        for (auto it = items.rbegin(); it != items.rend() && cnt < limit; ++it) {
            if (unreadOnly && it->read) continue;
            out.push_back(it->toJson());
            ++cnt;
        }
        Json wrap = Json::obj();
        wrap["news"] = out;
        return wrap;
    }

    void setRead(int id) {
        for (NewsItem& n : items) if (n.id == id) n.read = true;
    }
    void setAllRead() { markAllRead(); }

    void clear() { items.clear(); nextId = 1; }

    // 剔除"所述"模板里奇怪的引号，保持中文可读
    static std::string formatTemplate(const std::string& tpl, const std::string& name, const std::string& sym) {
        std::string out;
        out.reserve(tpl.size() + 32);
        for (size_t i = 0; i < tpl.size(); ++i) {
            if (tpl[i] == '%' && i + 1 < tpl.size() && tpl[i + 1] == 's') {
                out += name;
                ++i;
            } else {
                out.push_back(tpl[i]);
            }
        }
        (void)sym;
        return out;
    }

private:
    static bool titleHas(const std::string& t, const char* sub) {
        return t.find(sub) != std::string::npos;
    }

    std::vector<NewsTemplate> macro_;
    std::vector<NewsTemplate> stock_;
    std::vector<NewsTemplate> forex_;
};

}  // namespace tsim
