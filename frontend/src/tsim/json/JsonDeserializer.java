package tsim.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把引擎返回的 {@code data} JSON 反序列化成强类型对象。
 *
 * <p>所有字段缺失时都退化为安全的默认值（0 / "" / false / 空列表），
 * 保证某个字段暂时缺失时前端不会崩溃。</p>
 */
public final class JsonDeserializer {

    private JsonDeserializer() {
    }

    // ---------------------------------------------------------------- 基础类型

    /** 协议时间 {@code {"date":"...","slot":n}}。 */
    public static final class TimeInfo {
        /** 日期，形如 2024-03-05；缺失为 ""。 */
        public final String date;
        /** 时间片 0..3；缺失为 0。 */
        public final int slot;

        /** 构造时间信息。 */
        public TimeInfo(String date, int slot) {
            this.date = date == null ? "" : date;
            this.slot = slot;
        }

        /** 解析时间对象。 */
        public static TimeInfo of(Map<String, Object> o) {
            if (o == null) {
                return new TimeInfo("", 0);
            }
            return new TimeInfo(Json.str(o, "date"), (int) Json.lng(o, "slot"));
        }

        /** 解析附在实体上的 time 字段。 */
        public static TimeInfo ofParent(Map<String, Object> parent) {
            return of(Json.object(parent, "time"));
        }

        /** "2024-03-05 3/4"。 */
        @Override
        public String toString() {
            return (date.isEmpty() ? "-" : date) + " " + (slot + 1) + "/4";
        }
    }

    /** 股票账户。 */
    public static final class StockAccount {
        /** 现金。 */
        public final double cash;
        /** 冻结资金。 */
        public final double frozen;
        /** 总权益。 */
        public final double equity;
        /** 持仓市值。 */
        public final double marketValue;
        /** 当日盈亏。 */
        public final double pnlDay;
        /** 累计盈亏。 */
        public final double pnlTotal;
        /** 已用保证金（股票账户一般为 0）。 */
        public final double marginUsed;
        /** 可用购买力。 */
        public final double buyingPower;
        /** T+1 冻结现金。 */
        public final double t1FrozenCash;

        private StockAccount(Map<String, Object> o) {
            cash = Json.num(o, "cash");
            frozen = Json.num(o, "frozen");
            equity = Json.num(o, "equity");
            marketValue = Json.num(o, "marketValue");
            pnlDay = Json.num(o, "pnlDay");
            pnlTotal = Json.num(o, "pnlTotal");
            marginUsed = Json.num(o, "marginUsed");
            buyingPower = Json.num(o, "buyingPower");
            t1FrozenCash = Json.num(o, "t1FrozenCash");
        }

        /** 解析。 */
        public static StockAccount of(Map<String, Object> o) {
            return new StockAccount(o == null ? new LinkedHashMap<>() : o);
        }
    }

    /** 外汇账户。 */
    public static final class ForexAccount {
        /** 现金。 */
        public final double cash;
        /** 占用保证金。 */
        public final double margin;
        /** 净值。 */
        public final double equity;
        /** 可用保证金。 */
        public final double freeMargin;
        /** 保证金水平（百分比）。 */
        public final double marginLevel;
        /** 浮动盈亏。 */
        public final double pnlFloat;
        /** 累计盈亏。 */
        public final double pnlTotal;
        /** 已用手数。 */
        public final int usedLots;
        /** 计价货币，默认 USD。 */
        public final String currency;

        private ForexAccount(Map<String, Object> o) {
            cash = Json.num(o, "cash");
            margin = Json.num(o, "margin");
            equity = Json.num(o, "equity");
            freeMargin = Json.num(o, "freeMargin");
            marginLevel = Json.num(o, "marginLevel");
            pnlFloat = Json.num(o, "pnlFloat");
            pnlTotal = Json.num(o, "pnlTotal");
            usedLots = (int) Json.lng(o, "usedLots");
            String c = Json.str(o, "currency");
            currency = c.isEmpty() ? "USD" : c;
        }

        /** 解析。 */
        public static ForexAccount of(Map<String, Object> o) {
            return new ForexAccount(o == null ? new LinkedHashMap<>() : o);
        }
    }

    /** 股票持仓。 */
    public static final class StockPosition {
        /** 股票代码。 */
        public final String symbol;
        /** 股票名称。 */
        public final String name;
        /** 总持仓。 */
        public final int qty;
        /** 冻结数量。 */
        public final int frozenQty;
        /** 成本价。 */
        public final double avgCost;
        /** 最新价。 */
        public final double last;
        /** 市值。 */
        public final double marketValue;
        /** 浮动盈亏。 */
        public final double pnl;
        /** 盈亏比例（小数）。 */
        public final double pnlPct;
        /** 当日买入（T+1 锁定）数量。 */
        public final int todayBoughtQty;
        /** 做空：空头股数（0 = 无空头）。 */
        public final long shortQty;
        /** 做空：开空均价。 */
        public final double shortAvgPrice;
        /** 做空：已冻结保证金。 */
        public final double shortMargin;
        /** 做空：浮动盈亏。 */
        public final double shortPnl;
        /** 做空：当日开空（T+1 锁定）。 */
        public final long todayShortedQty;

        private StockPosition(Map<String, Object> o) {
            symbol = Json.str(o, "symbol");
            name = Json.str(o, "name");
            qty = (int) Json.lng(o, "qty");
            frozenQty = (int) Json.lng(o, "frozenQty");
            avgCost = Json.num(o, "avgCost");
            last = Json.num(o, "last");
            marketValue = Json.num(o, "marketValue");
            pnl = Json.num(o, "pnl");
            pnlPct = Json.num(o, "pnlPct");
            todayBoughtQty = (int) Json.lng(o, "todayBoughtQty");
            shortQty = Json.lng(o, "shortQty");
            shortAvgPrice = Json.num(o, "shortAvgPrice");
            shortMargin = Json.num(o, "shortMargin");
            shortPnl = Json.num(o, "shortPnl");
            todayShortedQty = Json.lng(o, "todayShortedQty");
        }

        /** 解析。 */
        public static StockPosition of(Map<String, Object> o) {
            return new StockPosition(o == null ? new LinkedHashMap<>() : o);
        }

        /** 可卖数量 = 持仓 - 冻结 - 当日买入（T+1）。 */
        public int sellableQty() {
            int sellable = qty - frozenQty - todayBoughtQty;
            return Math.max(0, sellable);
        }
    }

    /** 外汇持仓。 */
    public static final class ForexPosition {
        /** 持仓号。 */
        public final int positionId;
        /** 货币对。 */
        public final String symbol;
        /** 名称。 */
        public final String name;
        /** long / short。 */
        public final String side;
        /** 手数。 */
        public final int lots;
        /** 开仓价。 */
        public final double openRate;
        /** 最新价。 */
        public final double last;
        /** 占用保证金。 */
        public final double margin;
        /** 浮动盈亏。 */
        public final double pnl;
        /** 隔夜利息。 */
        public final double swap;
        /** 止损价。 */
        public final double stopLoss;
        /** 止盈价。 */
        public final double takeProfit;

        private ForexPosition(Map<String, Object> o) {
            positionId = (int) Json.lng(o, "positionId");
            symbol = Json.str(o, "symbol");
            name = Json.str(o, "name");
            side = Json.str(o, "side");
            lots = (int) Json.lng(o, "lots");
            openRate = Json.num(o, "openRate");
            last = Json.num(o, "last");
            margin = Json.num(o, "margin");
            pnl = Json.num(o, "pnl");
            swap = Json.num(o, "swap");
            stopLoss = Json.num(o, "stopLoss");
            takeProfit = Json.num(o, "takeProfit");
        }

        /** 解析。 */
        public static ForexPosition of(Map<String, Object> o) {
            return new ForexPosition(o == null ? new LinkedHashMap<>() : o);
        }

        /** 是否多单。 */
        public boolean isLong() {
            return "long".equals(side);
        }

        /** 方向中文。 */
        public String sideText() {
            return isLong() ? "做多" : "做空";
        }
    }

    /** 挂单。 */
    public static final class OrderInfo {
        /** 订单号。 */
        public final int id;
        /** 代码。 */
        public final String symbol;
        /** stock / forex。 */
        public final String market;
        /** buy/sell/long/short。 */
        public final String side;
        /** market / limit。 */
        public final String type;
        /** 委托数量。 */
        public final int qty;
        /** 已成交数量。 */
        public final int filled;
        /** 委托价。 */
        public final double price;
        /** open/partial/filled/cancelled/expired。 */
        public final String status;
        /** 下单时间。 */
        public final TimeInfo created;

        private OrderInfo(Map<String, Object> o) {
            id = (int) Json.lng(o, "id");
            symbol = Json.str(o, "symbol");
            market = Json.str(o, "market");
            side = Json.str(o, "side");
            type = Json.str(o, "type");
            qty = (int) Json.lng(o, "qty");
            filled = (int) Json.lng(o, "filled");
            price = Json.num(o, "price");
            status = Json.str(o, "status");
            created = TimeInfo.ofParent(o);
        }

        /** 解析。 */
        public static OrderInfo of(Map<String, Object> o) {
            return new OrderInfo(o == null ? new LinkedHashMap<>() : o);
        }

        /** 是否可撤（open/partial）。 */
        public boolean cancellable() {
            return "open".equals(status) || "partial".equals(status);
        }

        /** 方向中文。 */
        public String sideText() {
            if ("buy".equals(side)) {
                return "买入";
            }
            if ("sell".equals(side)) {
                return "卖出";
            }
            if ("long".equals(side)) {
                return "做多";
            }
            if ("short".equals(side)) {
                return "做空";
            }
            return side;
        }
    }

    /** 成交明细。 */
    public static final class TradeInfo {
        /** 序号。 */
        public final int seq;
        /** 成交时间。 */
        public final TimeInfo time;
        /** 市场。 */
        public final String market;
        /** 代码。 */
        public final String symbol;
        /** 方向。 */
        public final String side;
        /** 数量。 */
        public final int qty;
        /** 成交价。 */
        public final double price;
        /** 成交额。 */
        public final double amount;
        /** 手续费。 */
        public final double commission;
        /** 已实现盈亏。 */
        public final double realizedPnl;
        /** manual|stop|takeprofit|liquidation|dividend。 */
        public final String reason;

        private TradeInfo(Map<String, Object> o) {
            seq = (int) Json.lng(o, "seq");
            time = TimeInfo.ofParent(o);
            market = Json.str(o, "market");
            symbol = Json.str(o, "symbol");
            side = Json.str(o, "side");
            qty = (int) Json.lng(o, "qty");
            price = Json.num(o, "price");
            amount = Json.num(o, "amount");
            commission = Json.num(o, "commission");
            realizedPnl = Json.num(o, "realizedPnl");
            reason = Json.str(o, "reason");
        }

        /** 解析。 */
        public static TradeInfo of(Map<String, Object> o) {
            return new TradeInfo(o == null ? new LinkedHashMap<>() : o);
        }

        /** 平仓原因中文。 */
        public String reasonText() {
            switch (reason) {
                case "manual":
                    return "手工";
                case "stop":
                    return "止损";
                case "takeprofit":
                    return "止盈";
                case "liquidation":
                    return "强制平仓";
                case "dividend":
                    return "分红";
                default:
                    return reason.isEmpty() ? "-" : reason;
            }
        }

        /** 方向中文。 */
        public String sideText() {
            if ("buy".equals(side)) {
                return "买入";
            }
            if ("sell".equals(side)) {
                return "卖出";
            }
            if ("long".equals(side)) {
                return "做多";
            }
            if ("short".equals(side)) {
                return "做空";
            }
            return side;
        }
    }

    /** 新闻。 */
    public static final class NewsInfo {
        /** 新闻号。 */
        public final int id;
        /** 时间。 */
        public final TimeInfo time;
        /** 标题。 */
        public final String title;
        /** 正文。 */
        public final String body;
        /** stock|forex|macro。 */
        public final String scope;
        /** 影响力（小数）。 */
        public final double impact;
        /** 相关代码。 */
        public final List<Object> symbols;
        /** 是否已读。 */
        public final boolean read;

        private NewsInfo(Map<String, Object> o) {
            id = (int) Json.lng(o, "id");
            time = TimeInfo.ofParent(o);
            title = Json.str(o, "title");
            body = Json.str(o, "body");
            scope = Json.str(o, "scope");
            impact = Json.num(o, "impact");
            symbols = Json.array(o, "symbols");
            read = Json.bool(o, "read");
        }

        /** 解析。 */
        public static NewsInfo of(Map<String, Object> o) {
            return new NewsInfo(o == null ? new LinkedHashMap<>() : o);
        }

        /** 范围中文。 */
        public String scopeText() {
            switch (scope) {
                case "stock":
                    return "股票";
                case "forex":
                    return "外汇";
                case "macro":
                    return "宏观";
                default:
                    return scope.isEmpty() ? "-" : scope;
            }
        }
    }

    /** 统计。 */
    public static final class StatInfo {
        /** 成交笔数。 */
        public final int tradeCount;
        /** 盈利笔数。 */
        public final int winCount;
        /** 已实现盈亏。 */
        public final double realizedPnl;
        /** 累计手续费。 */
        public final double totalCommission;
        /** 起始权益。 */
        public final double startEquity;

        private StatInfo(Map<String, Object> o) {
            tradeCount = (int) Json.lng(o, "tradeCount");
            winCount = (int) Json.lng(o, "winCount");
            realizedPnl = Json.num(o, "realizedPnl");
            totalCommission = Json.num(o, "totalCommission");
            startEquity = Json.num(o, "startEquity");
        }

        /** 解析。 */
        public static StatInfo of(Map<String, Object> o) {
            return new StatInfo(o == null ? new LinkedHashMap<>() : o);
        }

        /** 胜率（0..1）；无成交返回 0。 */
        public double winRate() {
            return tradeCount <= 0 ? 0.0 : (double) winCount / (double) tradeCount;
        }
    }

    /** 自动 T+1 策略开关。 */
    public static final class AutoT1 {
        /** 是否启用 T+1 规则。 */
        public final boolean enabled;
        /** 是否自动续约挂单。 */
        public final boolean autoRenew;
        /** 是否自动止损止盈。 */
        public final boolean autoStop;

        private AutoT1(Map<String, Object> o) {
            enabled = Json.bool(o, "enabled", true);
            autoRenew = Json.bool(o, "autoRenew", false);
            autoStop = Json.bool(o, "autoStop", false);
        }

        /** 解析。 */
        public static AutoT1 of(Map<String, Object> o) {
            return new AutoT1(o == null ? new LinkedHashMap<>() : o);
        }
    }

    // ---------------------------------------------------------------- 组合体

    /** 快照。 */
    public static final class Snapshot {
        /** 当前时间。 */
        public final TimeInfo time;
        /** 股票账户。 */
        public final StockAccount stockAccount;
        /** 外汇账户。 */
        public final ForexAccount forexAccount;
        /** 股票持仓。 */
        public final List<StockPosition> stockPositions;
        /** 外汇持仓。 */
        public final List<ForexPosition> forexPositions;
        /** 挂单。 */
        public final List<OrderInfo> orders;
        /** 自动策略。 */
        public final AutoT1 autoT1;
        /** 统计。 */
        public final StatInfo stat;
        /** 是否破产。 */
        public final boolean bankrupt;
        /** 透视信息（作弊项 perfectInfo 打开时可能有）。 */
        public final Map<String, Object> cheatInfo;

        private Snapshot(Map<String, Object> d) {
            time = TimeInfo.ofParent(d);
            stockAccount = StockAccount.of(Json.object(d, "stockAccount"));
            forexAccount = ForexAccount.of(Json.object(d, "forexAccount"));
            stockPositions = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "stockPositions")) {
                stockPositions.add(StockPosition.of(Json.asObject(o)));
            }
            forexPositions = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "forexPositions")) {
                forexPositions.add(ForexPosition.of(Json.asObject(o)));
            }
            orders = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "orders")) {
                orders.add(OrderInfo.of(Json.asObject(o)));
            }
            autoT1 = AutoT1.of(Json.object(d, "autoT1"));
            stat = StatInfo.of(Json.object(d, "stat"));
            bankrupt = Json.bool(d, "bankrupt");
            cheatInfo = Json.object(d, "cheatInfo");
        }

        /** 解析。 */
        public static Snapshot of(Map<String, Object> d) {
            return new Snapshot(d == null ? new LinkedHashMap<>() : d);
        }

        /** 总权益（股票 + 外汇）。 */
        public double totalEquity() {
            return stockAccount.equity + forexAccount.equity;
        }
    }

    /** 行情里的单根 K 线。 */
    public static final class Bar {
        /** 时间。 */
        public final TimeInfo time;
        /** 开盘。 */
        public final double open;
        /** 最高。 */
        public final double high;
        /** 最低。 */
        public final double low;
        /** 收盘。 */
        public final double close;
        /** 成交量。 */
        public final long volume;

        private Bar(Map<String, Object> o) {
            time = TimeInfo.ofParent(o);
            open = Json.num(o, "open");
            high = Json.num(o, "high");
            low = Json.num(o, "low");
            close = Json.num(o, "close");
            volume = Json.lng(o, "volume");
        }

        /** 解析。 */
        public static Bar of(Map<String, Object> o) {
            return new Bar(o == null ? new LinkedHashMap<>() : o);
        }
    }

    /** 股票行情。 */
    public static final class StockQuote {
        /** 代码。 */
        public final String symbol;
        /** 名称。 */
        public final String name;
        /** 最新价。 */
        public final double last;
        /** 昨收。 */
        public final double prevClose;
        /** 今开。 */
        public final double open;
        /** 最高。 */
        public final double high;
        /** 最低。 */
        public final double low;
        /** 涨跌幅（小数）。 */
        public final double changePct;
        /** 成交量。 */
        public final long volume;
        /** 买一。 */
        public final double bid;
        /** 卖一。 */
        public final double ask;
        /** 是否停牌。 */
        public final boolean halted;
        /** 市盈率。 */
        public final double pe;
        /** 历史 K 线。 */
        public final List<Bar> hist;
        /** 计价货币。 */
        public final String currency;

        private StockQuote(Map<String, Object> o) {
            symbol = Json.str(o, "symbol");
            name = Json.str(o, "name");
            last = Json.num(o, "last");
            prevClose = Json.num(o, "prevClose");
            open = Json.num(o, "open");
            high = Json.num(o, "high");
            low = Json.num(o, "low");
            changePct = Json.num(o, "changePct");
            volume = Json.lng(o, "volume");
            bid = Json.num(o, "bid");
            ask = Json.num(o, "ask");
            halted = Json.bool(o, "halted");
            pe = Json.num(o, "pe");
            currency = Json.str(o, "currency");
            hist = new java.util.ArrayList<>();
            for (Object b : Json.array(o, "hist")) {
                hist.add(Bar.of(Json.asObject(b)));
            }
        }

        /** 解析。 */
        public static StockQuote of(Map<String, Object> o) {
            return new StockQuote(o == null ? new LinkedHashMap<>() : o);
        }
    }

    /** 外汇行情。 */
    public static final class ForexQuote {
        /** 代码。 */
        public final String symbol;
        /** 名称。 */
        public final String name;
        /** 最新价。 */
        public final double last;
        /** 昨收。 */
        public final double prevClose;
        /** 今开。 */
        public final double open;
        /** 最高。 */
        public final double high;
        /** 最低。 */
        public final double low;
        /** 涨跌幅（小数）。 */
        public final double changePct;
        /** 买价。 */
        public final double bid;
        /** 卖价。 */
        public final double ask;
        /** 点差。 */
        public final double spread;
        /** 小数位。 */
        public final int digits;
        /** 一个 pip。 */
        public final double pip;
        /** 每点价值。 */
        public final double pointValue;
        /** 历史分时。 */
        public final List<Bar> hist;

        private ForexQuote(Map<String, Object> o) {
            symbol = Json.str(o, "symbol");
            name = Json.str(o, "name");
            last = Json.num(o, "last");
            prevClose = Json.num(o, "prevClose");
            open = Json.num(o, "open");
            high = Json.num(o, "high");
            low = Json.num(o, "low");
            changePct = Json.num(o, "changePct");
            bid = Json.num(o, "bid");
            ask = Json.num(o, "ask");
            spread = Json.num(o, "spread");
            int dg = (int) Json.lng(o, "digits", 4);
            digits = dg <= 0 ? 4 : dg;
            double p = Json.num(o, "pip");
            pip = p > 0 ? p : Math.pow(10, -digits);
            pointValue = Json.num(o, "pointValue", 10.0);
            hist = new java.util.ArrayList<>();
            for (Object b : Json.array(o, "hist")) {
                hist.add(Bar.of(Json.asObject(b)));
            }
        }

        /** 解析。 */
        public static ForexQuote of(Map<String, Object> o) {
            return new ForexQuote(o == null ? new LinkedHashMap<>() : o);
        }
    }

    /** 行情快照。 */
    public static final class MarketData {
        /** 时间。 */
        public final TimeInfo time;
        /** 股票列表。 */
        public final List<StockQuote> stocks;
        /** 外汇列表。 */
        public final List<ForexQuote> forex;
        /** 停牌列表。 */
        public final List<String> halted;

        private MarketData(Map<String, Object> d) {
            time = TimeInfo.ofParent(d);
            stocks = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "stocks")) {
                stocks.add(StockQuote.of(Json.asObject(o)));
            }
            forex = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "forex")) {
                forex.add(ForexQuote.of(Json.asObject(o)));
            }
            halted = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "halted")) {
                halted.add(Json.asString(o));
            }
        }

        /** 解析。 */
        public static MarketData of(Map<String, Object> d) {
            return new MarketData(d == null ? new LinkedHashMap<>() : d);
        }

        /** 按代码查找股票。 */
        public StockQuote stock(String symbol) {
            for (StockQuote q : stocks) {
                if (q.symbol.equals(symbol)) {
                    return q;
                }
            }
            return null;
        }

        /** 按代码查找外汇。 */
        public ForexQuote forex(String symbol) {
            for (ForexQuote q : forex) {
                if (q.symbol.equals(symbol)) {
                    return q;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- 其它小结构

    /** 单条行情事件（tick / push 里的事件）。 */
    public static final class EventInfo {
        /** 事件种类（协议 3.3 冻结枚举）。 */
        public final String kind;
        /** 代码。 */
        public final String symbol;
        /** 方向。 */
        public final String side;
        /** 数量 / 手数。 */
        public final int qty;
        /** 价格。 */
        public final double price;
        /** 说明。 */
        public final double amount;
        /** 备注（中文）。 */
        public final String note;
        /** 新闻号。 */
        public final int newsId;
        /** 新闻标题。 */
        public final String title;
        /** 事件时间。 */
        public final TimeInfo at;
        /** 部分成交时的未成交数量（协议 §3.8a：order_partial 专有）。 */
        public final int remaining;
        /** 强平/爆仓事件里的保证金水平（百分比，协议 §3.8b）。 */
        public final double level;
        /** 强平/爆仓事件里的亏损金额（协议 §3.8b）。 */
        public final double loss;
        /** 订单号（order_expired 等事件携带）。 */
        public final int orderId;
        /** 账户（bankrupt/margin_call 事件携带）。 */
        public final String account;
        /** 强平事件子类型：warn（仅预警）/ liquidate（已强平）。协议 v1.0.5。 */
        public final String mode;
        /** 强平数量（股票强平携带，单位：股；与外汇 lots 区分）。 */
        public final long qtyL;
        /** 浮动盈亏（预警事件携带，负数表示浮亏）。 */
        public final double floatPnl;
        /** 事件是否真的携带 loss 字段（区分"没有该字段"与"loss 恰为 0"）。 */
        public final boolean hasLoss;
        /** 事件是否真的携带 floatPnl 字段。 */
        public final boolean hasFloatPnl;
        /** 原始 JSON。 */
        public final Map<String, Object> raw;

        private EventInfo(Map<String, Object> o) {
            raw = o;
            kind = Json.str(o, "kind");
            symbol = Json.str(o, "symbol");
            side = Json.str(o, "side");
            qty = (int) Json.lng(o, "qty");
            price = Json.num(o, "price");
            amount = Json.num(o, "amount");
            note = Json.str(o, "note");
            newsId = (int) Json.lng(o, "newsId");
            title = Json.str(o, "title");
            at = TimeInfo.ofParent(o);
            remaining = (int) Json.lng(o, "remaining");
            level = Json.num(o, "level");
            loss = Json.num(o, "loss");
            orderId = (int) Json.lng(o, "orderId");
            account = Json.str(o, "account");
            mode = Json.str(o, "mode");
            qtyL = Json.lng(o, "qty");
            hasLoss = o != null && o.containsKey("loss");
            hasFloatPnl = o != null && o.containsKey("floatPnl");
            floatPnl = Json.num(o, "floatPnl");
        }

        /** 解析。 */
        public static EventInfo of(Map<String, Object> o) {
            return new EventInfo(o == null ? new LinkedHashMap<>() : o);
        }

        /** 事件种类中文名。 */
        public String kindText() {
            switch (kind) {
                case "order_filled":
                    return "全部成交";
                case "order_partial":
                    return "部分成交";
                case "order_cancelled":
                    return "挂单撤销";
                case "order_expired":
                    return "挂单过期";
                case "t1_unlock":
                    return "T+1 解锁";
                case "stop_triggered":
                    return "止损触发";
                case "take_profit_triggered":
                    return "止盈触发";
                case "margin_call":
                    return "追加保证金";
                case "news":
                    return "新闻";
                case "dividend":
                    return "分红派息";
                case "fx_swap":
                    return "隔夜利息";
                case "bankrupt":
                    return "破产";
                default:
                    return kind.isEmpty() ? "事件" : kind;
            }
        }

        /** 一行中文描述（按事件种类给出可读明细）。 */
        public String describe() {
            StringBuilder sb = new StringBuilder(kindText());
            if (!symbol.isEmpty()) {
                sb.append("  ").append(symbol);
            }
            switch (kind) {
                case "order_partial":
                    // 协议 §3.8a：部分成交需显示 "部分成交 100/剩余 X"
                    sb.append("  成交 ").append(filledQty()).append("/剩余 ").append(remaining);
                    if (price > 0) {
                        sb.append("  价格 ").append(price);
                    }
                    break;
                case "order_expired":
                    if (orderId > 0) {
                        sb.append("  订单 #").append(orderId);
                    }
                    if (qty > 0) {
                        sb.append("  数量 ").append(qty).append("（未成交，已解冻）");
                    }
                    break;
                case "margin_call":
                    sb.append("  保证金水平 ").append(String.format(java.util.Locale.ROOT, "%.2f%%", level));
                    if (symbol != null && !symbol.isEmpty()) {
                        sb.append("  ").append(symbol);
                    }
                    if (lots() > 0) {
                        sb.append("  强平 ").append(lots()).append(" 手");
                    } else if (qtyL > 0) {
                        sb.append("  强平 ").append(qtyL).append(" 股");
                    }
                    // loss 是"保证金缺口"（预警，恒非负）或"本笔实际亏损"（强平，负数为亏）。
                    // 以前无条件打印 "亏损 0.0" —— 字段缺失时也照打，误导用户以为没亏钱。
                    // 现在只在事件确实携带 loss 时才显示。
                    if (hasLoss) {
                        if ("warn".equals(mode)) {
                            sb.append("  需补充 ").append(fmtMoney(loss));
                        } else {
                            sb.append("  本次亏损 ").append(fmtMoney(loss));
                        }
                    }
                    if (hasFloatPnl) {
                        sb.append("  浮动盈亏 ").append(fmtMoney(floatPnl));
                    }
                    break;
                case "bankrupt":
                    sb.append("  账户 ").append(accountText());
                    break;
                default:
                    if (qty > 0) {
                        sb.append("  ").append(qty);
                    }
                    if (price > 0) {
                        sb.append("  价格 ").append(price);
                    }
                    break;
            }
            if (!note.isEmpty()) {
                sb.append("  (").append(note).append(')');
            }
            if (!title.isEmpty()) {
                sb.append("  「").append(title).append('】');
            }
            return sb.toString();
        }

        /** 金额格式化（千分位 + 2 位小数，超长自动折行不适用，这里只做数值展示）。 */
        private static String fmtMoney(double v) {
            return String.format(java.util.Locale.ROOT, "%,.2f", v);
        }

        /** 部分成交事件的已成交数量：qty 字段在 order_partial 里表示本次成交量。 */
        private int filledQty() {
            int f = (int) Json.lng(raw, "filled");
            return f > 0 ? f : qty;
        }

        /** 强平手数（margin_call 事件用 lots 字段）。 */
        private int lots() {
            return (int) Json.lng(raw, "lots");
        }

        /** 账户中文名。 */
        public String accountText() {
            if ("forex".equals(account)) {
                return "外汇";
            }
            if ("stock".equals(account)) {
                return "股票";
            }
            return account.isEmpty() ? "账户" : account;
        }
    }

    /** tick 返回。 */
    public static final class TickResult {
        /** 推进后的时间。 */
        public final TimeInfo time;
        /** 实际推进的时间片数。 */
        public final int advanced;
        /** 事件列表。 */
        public final List<EventInfo> events;
        /** 停牌列表。 */
        public final List<String> halted;

        private TickResult(Map<String, Object> d) {
            time = TimeInfo.ofParent(d);
            advanced = (int) Json.lng(d, "advanced");
            events = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "events")) {
                events.add(EventInfo.of(Json.asObject(o)));
            }
            halted = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "halted")) {
                halted.add(Json.asString(o));
            }
        }

        /** 解析。 */
        public static TickResult of(Map<String, Object> d) {
            return new TickResult(d == null ? new LinkedHashMap<>() : d);
        }
    }

    /** 下单返回。 */
    public static final class TradeResult {
        /** 订单号。 */
        public final int orderId;
        /** filled / open / partial。 */
        public final String status;
        /** 成交数量。 */
        public final int filled;
        /** 成交均价。 */
        public final double avgPrice;
        /** 手续费。 */
        public final double commission;
        /** 成交后现金。 */
        public final double cash;

        private TradeResult(Map<String, Object> d) {
            orderId = (int) Json.lng(d, "orderId");
            status = Json.str(d, "status");
            filled = (int) Json.lng(d, "filled");
            avgPrice = Json.num(d, "avgPrice");
            commission = Json.num(d, "commission");
            cash = Json.num(d, "cash");
        }

        /** 解析。 */
        public static TradeResult of(Map<String, Object> d) {
            return new TradeResult(d == null ? new LinkedHashMap<>() : d);
        }

        /** 状态中文。 */
        public String statusText() {
            switch (status) {
                case "filled":
                    return "已成交";
                case "open":
                    return "挂单中";
                case "partial":
                    return "部分成交";
                case "cancelled":
                    return "已撤销";
                case "expired":
                    return "已过期";
                default:
                    return status.isEmpty() ? "-" : status;
            }
        }
    }

    /** 时钟状态。 */
    public static final class ClockState {
        /** 是否自动推进中。 */
        public final boolean running;
        /** 倍速。 */
        public final double speed;
        /** 基准毫秒。 */
        public final int tickMs;
        /** 实际片间隔毫秒。 */
        public final double tickIntervalMs;
        /** 当前时间。 */
        public final TimeInfo time;
        /** 推进单位。 */
        public final String advanceUnit;

        private ClockState(Map<String, Object> d) {
            running = Json.bool(d, "running");
            speed = Json.num(d, "speed", 1.0);
            tickMs = (int) Json.lng(d, "tickMs", 500);
            tickIntervalMs = Json.num(d, "tickIntervalMs", tickMs);
            time = TimeInfo.ofParent(d);
            String u = Json.str(d, "advanceUnit");
            advanceUnit = u.isEmpty() ? "slot" : u;
        }

        /** 解析。 */
        public static ClockState of(Map<String, Object> d) {
            return new ClockState(d == null ? new LinkedHashMap<>() : d);
        }

        /** 每秒推进多少个时间片。 */
        public double slotsPerSecond() {
            return tickIntervalMs <= 0 ? 0 : 1000.0 / tickIntervalMs;
        }

        /** 每秒推进多少天。 */
        public double daysPerSecond() {
            return slotsPerSecond() / 4.0;
        }
    }

    /** 引擎设置。 */
    public static final class Settings {
        /** 倍速。 */
        public final double speed;
        /** T+1 开关。 */
        public final boolean t1;
        /** 自动续约。 */
        public final boolean autoRenew;
        /** 自动止损止盈。 */
        public final boolean autoStop;
        /** 手续费率。 */
        public final double commission;
        /** 滑点。 */
        public final double slippage;
        /** 引擎侧片间隔。 */
        public final int tickMs;
        /** 默认止损比例。 */
        public final double stopLossPct;
        /** 默认止盈比例。 */
        public final double takeProfitPct;
        /** 难度。 */
        public final String difficulty;

        private Settings(Map<String, Object> d) {
            speed = Json.num(d, "speed", 1.0);
            t1 = Json.bool(d, "t1", true);
            autoRenew = Json.bool(d, "autoRenew");
            autoStop = Json.bool(d, "autoStop");
            commission = Json.num(d, "commission");
            slippage = Json.num(d, "slippage");
            tickMs = (int) Json.lng(d, "tickMs");
            stopLossPct = Json.num(d, "stopLossPct");
            takeProfitPct = Json.num(d, "takeProfitPct");
            String df = Json.str(d, "difficulty");
            difficulty = df.isEmpty() ? "normal" : df;
        }

        /** 解析。 */
        public static Settings of(Map<String, Object> d) {
            return new Settings(d == null ? new LinkedHashMap<>() : d);
        }
    }

    /** 作弊状态。 */
    public static final class CheatState {
        /** T+1 是否开启。 */
        public final boolean t1;
        /** 无限资金。 */
        public final boolean infiniteMoney;
        /** 上帝模式。 */
        public final boolean godMode;
        /** 免手续费。 */
        public final boolean noCommission;
        /** 透视。 */
        public final boolean perfectInfo;
        /** 胜率。 */
        public final double winRate;
        /** 当前种子。 */
        public final long seed;

        private CheatState(Map<String, Object> d) {
            t1 = Json.bool(d, "t1", true);
            infiniteMoney = Json.bool(d, "infiniteMoney");
            godMode = Json.bool(d, "godMode");
            noCommission = Json.bool(d, "noCommission");
            perfectInfo = Json.bool(d, "perfectInfo");
            winRate = Json.num(d, "winRate", 0.5);
            seed = Json.lng(d, "seed");
        }

        /** 解析。 */
        public static CheatState of(Map<String, Object> d) {
            return new CheatState(d == null ? new LinkedHashMap<>() : d);
        }
    }

    /** 作弊项描述。 */
    public static final class CheatItem {
        /** op 名。 */
        public final String op;
        /** 中文标签。 */
        public final String label;
        /** 中文说明。 */
        public final String desc;
        /** 参数字段名。 */
        public final List<String> args;

        private CheatItem(Map<String, Object> d) {
            op = Json.str(d, "op");
            label = Json.str(d, "label");
            desc = Json.str(d, "desc");
            args = new java.util.ArrayList<>();
            for (Object o : Json.array(d, "args")) {
                args.add(Json.asString(o));
            }
        }

        /** 解析。 */
        public static CheatItem of(Map<String, Object> d) {
            return new CheatItem(d == null ? new LinkedHashMap<>() : d);
        }
    }
}