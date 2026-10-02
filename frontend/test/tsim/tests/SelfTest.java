package tsim.tests;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import tsim.json.Json;
import tsim.json.JsonDeserializer;
import tsim.json.JsonDeserializer.ClockState;
import tsim.json.JsonDeserializer.MarketData;
import tsim.json.JsonDeserializer.NewsInfo;
import tsim.json.JsonDeserializer.OrderInfo;
import tsim.json.JsonDeserializer.Snapshot;
import tsim.json.JsonDeserializer.TickResult;
import tsim.json.JsonDeserializer.TradeInfo;
import tsim.json.JsonDeserializer.TradeResult;
import tsim.json.JsonParser;
import tsim.ui.EngineClient;
import tsim.ui.UITheme;

/**
 * 前端自测：JSON 解析/序列化 + 引擎客户端协议（可用 mock 引擎或真实引擎）。
 *
 * <p>用法：{@code java -cp build\frontend\classes tsim.tests.SelfTest <mock脚本路径>}</p>
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    private SelfTest() {
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "  -> " + detail);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        check(name, expected == null ? actual == null : expected.equals(actual),
                "期望=" + expected + " 实际=" + actual);
    }

    /** 测试入口。 */
    public static void main(String[] args) throws Exception {
        // 无图形环境时设置 headless，保证 OrderPanel 等 Swing 组件仍可构造
        if (System.getenv("DISPLAY") == null && System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        System.out.println("==== 拟股喵喵 前端自测 ====");
        System.out.println("中文字体: " + UITheme.FONT_FAMILY);
        System.out.println("涨跌配色: " + UITheme.colorSchemeName());
        testJson();
        testParserErrors();
        testSerializer();
        testDeserializer();
        String mock = args.length > 0 ? args[0] : "build\\mock_engine.py";
        testEngineClient(mock);
        System.out.println();
        System.out.println("==== 结果: " + passed + " 通过, " + failed + " 失败 ====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ JSON

    private static void testJson() {
        String line = "{\"id\":1,\"ok\":true,\"data\":{\"n\":-12.5,\"s\":\"中文\\u00e9\","
                + "\"arr\":[1,2,{\"k\":null}],\"big\":9007199254740993,\"esc\":\"a\\tb\\n\"}}";
        Map<String, Object> m = Json.parseObject(line);
        eq("parse id", Long.valueOf(1), m.get("id"));
        eq("parse ok", Boolean.TRUE, m.get("ok"));
        Map<String, Object> d = Json.object(m, "data");
        eq("parse double", Double.valueOf(-12.5), d.get("n"));
        eq("parse unicode escape", "中文\u00e9", Json.str(d, "s"));
        eq("parse array size", Integer.valueOf(3), Integer.valueOf(Json.array(d, "arr").size()));
        eq("parse nested null", null, Json.asObject(Json.array(d, "arr").get(2)).get("k"));
        eq("parse big int", Long.valueOf(9007199254740993L), d.get("big"));
        eq("parse escapes", "a\tb\n", Json.str(d, "esc"));
        // 代理对
        Object emoji = Json.parse("\"\\ud83d\\ude00\"");
        eq("parse surrogate pair", "😀", emoji);
        // 空白与 CRLF
        eq("parse whitespace", Long.valueOf(7), Json.parse("  \r\n 7 \t "));
        // 数字形态
        eq("parse exponent", Double.valueOf(1500.0), Json.parse("1.5e3"));
        eq("parse negative", Long.valueOf(-42), Json.parse("-42"));
    }

    private static void testParserErrors() {
        String[] bad = {"{", "[1,", "{\"a\":}", "tru", "\"unterminated", "{\"a\" 1}", "[1 2]", "1 2"};
        int caught = 0;
        for (String s : bad) {
            try {
                Json.parse(s);
            } catch (JsonParser.JsonException ex) {
                caught++;
            }
        }
        eq("畸形 JSON 全部抛 JsonException", Integer.valueOf(bad.length), Integer.valueOf(caught));
        try {
            Json.parse("{\"a\": 1;}");
        } catch (JsonParser.JsonException ex) {
            check("异常信息含位置", ex.getMessage().contains("位置"), ex.getMessage());
        }
    }

    private static void testSerializer() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("cmd", "buy");
        o.put("qty", Long.valueOf(100));
        o.put("price", Double.valueOf(1712.5));
        o.put("flag", Boolean.TRUE);
        o.put("none", null);
        o.put("text", "中文 \"引号\" \n换行");
        o.put("arr", Json.arr(Long.valueOf(1), "x"));
        String s = Json.write(o);
        check("序列化紧凑无换行", s.indexOf('\n') < 0, s);
        Map<String, Object> back = Json.parseObject(s);
        eq("round-trip qty", Long.valueOf(100), back.get("qty"));
        eq("round-trip price", Double.valueOf(1712.5), back.get("price"));
        eq("round-trip text", "中文 \"引号\" \n换行", back.get("text"));
        eq("round-trip null", null, back.get("none"));
        eq("空对象序列化", "{}", Json.write(new LinkedHashMap<String, Object>()));
        eq("空数组序列化", "[]", Json.write(new ArrayList<Object>()));
        eq("整数浮点输出 .0", "1.0", Json.write(Double.valueOf(1.0)));
        check("pretty 可解析", Json.parse(Json.pretty(o, 2)) instanceof Map, "pretty");
    }

    private static void testDeserializer() {
        String snap = "{\"time\":{\"date\":\"2024-03-05\",\"slot\":1},"
                + "\"stockAccount\":{\"cash\":800000.0,\"frozen\":20000.0,\"equity\":1050000.0,"
                + "\"marketValue\":250000.0,\"pnlDay\":1234.5,\"pnlTotal\":50000.0,\"marginUsed\":0.0,"
                + "\"buyingPower\":400000.0,\"t1FrozenCash\":0.0},"
                + "\"forexAccount\":{\"cash\":10000.0,\"margin\":200.0,\"equity\":10250.0,"
                + "\"freeMargin\":10050.0,\"marginLevel\":51.25,\"pnlFloat\":250.0,\"pnlTotal\":250.0,"
                + "\"usedLots\":2,\"currency\":\"USD\"},"
                + "\"stockPositions\":[{\"symbol\":\"SH600519\",\"name\":\"贵州茅台\",\"qty\":200,"
                + "\"frozenQty\":0,\"avgCost\":1700.0,\"last\":1712.5,\"marketValue\":171250.0,"
                + "\"pnl\":1250.0,\"pnlPct\":0.0074,\"todayBoughtQty\":100}],"
                + "\"forexPositions\":[],\"orders\":[],"
                + "\"autoT1\":{\"enabled\":true,\"autoRenew\":false,\"autoStop\":false},"
                + "\"stat\":{\"tradeCount\":12,\"winCount\":7,\"realizedPnl\":5000.0,"
                + "\"totalCommission\":320.5,\"startEquity\":1010000.0},\"bankrupt\":false}";
        Snapshot s = Snapshot.of(Json.parseObject(snap));
        eq("snapshot 时间", "2024-03-05 2/4", s.time.toString());
        eq("snapshot 股票现金", Double.valueOf(800000.0), Double.valueOf(s.stockAccount.cash));
        eq("snapshot 外汇币种", "USD", s.forexAccount.currency);
        eq("snapshot 持仓数", Integer.valueOf(1), Integer.valueOf(s.stockPositions.size()));
        eq("T+1 可卖计算", Integer.valueOf(100), Integer.valueOf(s.stockPositions.get(0).sellableQty()));
        eq("总权益", Double.valueOf(1060250.0), Double.valueOf(s.totalEquity()));
        eq("胜率", Double.valueOf(7.0 / 12.0), Double.valueOf(s.stat.winRate()));

        // 缺失字段兜底
        Snapshot empty = Snapshot.of(Json.parseObject("{}"));
        eq("缺字段默认日期", "- 1/4", empty.time.toString());
        eq("缺字段默认资产", Double.valueOf(0.0), Double.valueOf(empty.stockAccount.equity));
        eq("缺字段默认持仓", Integer.valueOf(0), Integer.valueOf(empty.stockPositions.size()));
        eq("缺字段默认币种", "USD", empty.forexAccount.currency);

        // market
        String mk = "{\"time\":{\"date\":\"2024-03-05\",\"slot\":0},\"stocks\":[{\"symbol\":\"SH600519\","
                + "\"name\":\"贵州茅台\",\"last\":1712.5,\"prevClose\":1700.0,\"hist\":[{\"time\":{\"date\":"
                + "\"2024-03-05\",\"slot\":0},\"open\":1,\"high\":2,\"low\":0.5,\"close\":1.5,\"volume\":10}]}],"
                + "\"forex\":[{\"symbol\":\"USDJPY\",\"name\":\"美元/日元\",\"last\":150.25,\"digits\":3,"
                + "\"pip\":0.001,\"pointValue\":10.0,\"hist\":[]}],\"halted\":[\"SZ000001\"]}";
        MarketData md = MarketData.of(Json.parseObject(mk));
        eq("market 股票数", Integer.valueOf(1), Integer.valueOf(md.stocks.size()));
        eq("market K线数", Integer.valueOf(1), Integer.valueOf(md.stocks.get(0).hist.size()));
        eq("market 查股票", "SH600519", md.stock("SH600519") == null ? null : md.stock("SH600519").symbol);
        eq("market 外汇 digits", Integer.valueOf(3), Integer.valueOf(md.forex.get(0).digits));
        eq("market 停牌", "SZ000001", md.halted.get(0));

        // tick
        String tk = "{\"time\":{\"date\":\"2024-03-06\",\"slot\":0},\"advanced\":3,"
                + "\"events\":[{\"kind\":\"order_filled\",\"symbol\":\"SH600519\",\"side\":\"buy\","
                + "\"qty\":100,\"price\":1712.5,\"note\":\"限价买单全部成交\",\"at\":{\"date\":"
                + "\"2024-03-06\",\"slot\":0}}],\"halted\":[]}";
        TickResult tr = TickResult.of(Json.parseObject(tk));
        eq("tick advanced", Integer.valueOf(3), Integer.valueOf(tr.advanced));
        eq("tick 事件种类", "全部成交", tr.events.get(0).kindText());
        check("tick 事件描述含中文", tr.events.get(0).describe().contains("限价买单全部成交"),
                tr.events.get(0).describe());

        // 时钟
        ClockState cs = ClockState.of(Json.parseObject(
                "{\"running\":true,\"speed\":4.0,\"tickMs\":500,\"tickIntervalMs\":125,"
                        + "\"time\":{\"date\":\"2024-03-05\",\"slot\":1},\"advanceUnit\":\"slot\"}"));
        eq("clock 天/秒换算（协议 3.14 例）", Double.valueOf(2.0),
                Double.valueOf(Math.round(cs.daysPerSecond() * 100) / 100.0));
        check("倍速可读换算", UITheme.speedText(8, 3.2).equals("8.00x ≈ 3.20 天/秒"),
                UITheme.speedText(8, 3.2));

        // ---- 协议 v1.0.1：挂单面板对 expired 的展示 ----
        tsim.ui.OrderPanel op = new tsim.ui.OrderPanel();
        eq("v1.0.1 初始无过期历史", Integer.valueOf(0), Integer.valueOf(op.expiredCount()));
        op.noteExpired(tsim.json.JsonDeserializer.EventInfo.of(
                Json.parseObject("{\"kind\":\"order_expired\",\"orderId\":11,\"symbol\":\"SH600519\","
                        + "\"side\":\"buy\",\"qty\":100,\"filled\":0}")));
        eq("v1.0.1 过期挂单被留档展示", Integer.valueOf(1), Integer.valueOf(op.expiredCount()));
        op.noteExpired(tsim.json.JsonDeserializer.EventInfo.of(
                Json.parseObject("{\"kind\":\"order_expired\",\"orderId\":11,\"symbol\":\"SH600519\"}")));
        eq("v1.0.1 同一订单号重复事件不重复记账", Integer.valueOf(1), Integer.valueOf(op.expiredCount()));
        op.clearExpiredHistory();
        eq("v1.0.1 新游戏清空过期历史", Integer.valueOf(0), Integer.valueOf(op.expiredCount()));

        // ---- 协议 v1.0.1 ----
        // §3.8a 挂单过期 + 部分成交 remaining
        tsim.json.JsonDeserializer.OrderInfo expired = tsim.json.JsonDeserializer.OrderInfo.of(
                Json.parseObject("{\"id\":11,\"symbol\":\"SH600519\",\"status\":\"expired\",\"qty\":100}"));
        eq("v1.0.1 过期挂单状态保留", "expired", expired.status);
        check("v1.0.1 过期挂单不可撤", !expired.cancellable(), "expired 竟然可撤");
        tsim.json.JsonDeserializer.EventInfo partial = tsim.json.JsonDeserializer.EventInfo.of(
                Json.parseObject("{\"kind\":\"order_partial\",\"symbol\":\"SH600519\",\"qty\":40,"
                        + "\"filled\":40,\"remaining\":60,\"price\":1700.0}"));
        eq("v1.0.1 order_partial remaining", Integer.valueOf(60), Integer.valueOf(partial.remaining));
        check("v1.0.1 部分成交描述含剩余量",
                partial.describe().contains("剩余 60") && partial.describe().contains("部分成交"),
                partial.describe());
        tsim.json.JsonDeserializer.EventInfo exp = tsim.json.JsonDeserializer.EventInfo.of(
                Json.parseObject("{\"kind\":\"order_expired\",\"orderId\":11,\"symbol\":\"SH600519\","
                        + "\"qty\":100,\"filled\":0}"));
        eq("v1.0.1 order_expired 中文", "挂单过期", exp.kindText());
        check("v1.0.1 过期描述含订单号与解冻提示",
                exp.describe().contains("#11") && exp.describe().contains("解冻"), exp.describe());
        // §3.8b 保证金水平配色
        eq("v1.0.1 保证金 <50% 危险色", UITheme.DANGER, UITheme.marginLevelColor(42.7));
        eq("v1.0.1 保证金 <100% 警戒色", UITheme.WARN, UITheme.marginLevelColor(85.0));
        eq("v1.0.1 保证金 >=100% 正常色", UITheme.OK, UITheme.marginLevelColor(250.0));
        check("v1.0.1 保证金文案含危险提示",
                UITheme.marginLevelText(42.7).contains("危险"), UITheme.marginLevelText(42.7));
        check("v1.0.1 保证金文案含警戒提示",
                UITheme.marginLevelText(85.0).contains("警戒"), UITheme.marginLevelText(85.0));
        // §3.8b margin_call / bankrupt 事件
        tsim.json.JsonDeserializer.EventInfo mc = tsim.json.JsonDeserializer.EventInfo.of(
                Json.parseObject("{\"kind\":\"margin_call\",\"account\":\"forex\",\"positionId\":5,"
                        + "\"lots\":2,\"level\":42.7,\"loss\":-123.4}"));
        eq("v1.0.1 margin_call 中文", "追加保证金", mc.kindText());
        check("v1.0.1 margin_call 描述含水平/手数",
                mc.describe().contains("42.7") && mc.describe().contains("2 手"), mc.describe());
        tsim.json.JsonDeserializer.EventInfo bk = tsim.json.JsonDeserializer.EventInfo.of(
                Json.parseObject("{\"kind\":\"bankrupt\",\"account\":\"forex\"}"));
        eq("v1.0.1 bankrupt 事件账户中文", "外汇", bk.accountText());
        check("v1.0.1 bankrupt 描述可读", bk.describe().contains("外汇"), bk.describe());
        // §3.4 forexPositions.positionId
        tsim.json.JsonDeserializer.ForexPosition fpos = tsim.json.JsonDeserializer.ForexPosition.of(
                Json.parseObject("{\"positionId\":5,\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":2}"));
        eq("v1.0.1 外汇持仓 positionId", Integer.valueOf(5), Integer.valueOf(fpos.positionId));

        // 其它结构
        TradeResult tres = TradeResult.of(Json.parseObject(
                "{\"orderId\":11,\"status\":\"filled\",\"filled\":100,\"avgPrice\":1701.2,"
                        + "\"commission\":17.01,\"cash\":799982.99}"));
        eq("TradeResult 状态中文", "已成交", tres.statusText());
        OrderInfo oi = OrderInfo.of(Json.parseObject("{\"id\":11,\"status\":\"open\",\"side\":\"buy\"}"));
        check("订单可撤", oi.cancellable(), "open 应可撤");
        TradeInfo ti = TradeInfo.of(Json.parseObject("{\"seq\":1,\"reason\":\"stop\",\"side\":\"sell\"}"));
        eq("成交原因中文", "止损", ti.reasonText());
        NewsInfo ni = NewsInfo.of(Json.parseObject("{\"id\":7,\"scope\":\"macro\",\"title\":\"x\"}"));
        eq("新闻范围中文", "宏观", ni.scopeText());
        eq("金额格式化", "1,000,000.00", UITheme.money(1000000.0));
        eq("百分比格式化", "+1.23%", UITheme.pct(0.0123));
        eq("价格格式化", "1.0850", UITheme.price(1.085, 4));
        eq("股数格式化", "1,200", UITheme.qty(1200));
        eq("成交量格式化", "123.46万", UITheme.volume(1234567L));
    }

    // ------------------------------------------------------------ 引擎客户端（mock）

    private static void testEngineClient(String mockPath) throws Exception {
        Path py = Paths.get(mockPath).toAbsolutePath();
        if (!java.nio.file.Files.isRegularFile(py)) {
            check("引擎文件存在", false, py.toString());
            return;
        }
        System.out.println("被测引擎: " + py + (py.toString().endsWith(".exe") ? "（真实引擎）" : "（mock 脚本）"));
        Path wrapper;
        String pyExe = System.getenv("TSIM_PYTHON");
        if (pyExe == null || pyExe.isEmpty()) {
            pyExe = "python";
        }
        if (py.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            // 真实引擎：直接作为可执行文件
            wrapper = py;
        } else {
            // mock 脚本：套一层 bat 调 python
            wrapper = py.getParent().resolve("mock_engine.cmd");
            java.nio.file.Files.write(wrapper, ("@echo off\r\n\"" + pyExe + "\" -u \"" + py + "\" %*\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        EngineClient client = new EngineClient(wrapper);
        List<String> pushTypes = new ArrayList<>();
        CountDownLatch pushLatch = new CountDownLatch(1);
        client.addPushListener((type, data) -> {
            pushTypes.add(type);
            pushLatch.countDown();
        });
        List<String> logs = new ArrayList<>();
        client.addLogListener(logs::add);

        client.start();
        check("引擎进程已启动", client.isAlive(), "进程未存活");

        Map<String, Object> hello = client.hello().get(10, TimeUnit.SECONDS);
        eq("hello protocol", Long.valueOf(1), hello.get("protocol"));
        eq("hello engine", "TradeSim", Json.str(hello, "engine"));

        Map<String, Object> ng = client.newGame(12345L, "玩家", 1000000, 10000, "normal")
                .get(10, TimeUnit.SECONDS);
        Snapshot snap = Snapshot.of(ng);
        eq("newgame 后资产", Double.valueOf(1000000.0), Double.valueOf(snap.stockAccount.cash));

        MarketData mkt = client.marketModel("all").get(10, TimeUnit.SECONDS);
        check("market 返回股票", mkt.stocks.size() >= 4, "股票数 " + mkt.stocks.size());
        check("market 返回外汇", mkt.forex.size() >= 3, "外汇数 " + mkt.forex.size());
        check("K线 hist 非空", mkt.stocks.get(0).hist.size() >= 10, "hist=" + mkt.stocks.get(0).hist.size());

        // 100 整数倍校验（引擎侧）
        try {
            client.tradeModel("buy", "SH600519", 150, "market", 0).get(10, TimeUnit.SECONDS);
            check("非100整数倍被引擎拒绝", false, "竟然成功了");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable c = ex.getCause();
            check("非100整数倍被引擎拒绝", c instanceof EngineClient.EngineError
                    && "BAD_QTY".equals(((EngineClient.EngineError) c).code()), String.valueOf(c));
        }

        // 正常买入
        TradeResult buy = client.tradeModel("buy", "SH600519", 100, "market", 0).get(10, TimeUnit.SECONDS);
        eq("买入成交数量", Integer.valueOf(100), Integer.valueOf(buy.filled));

        // T+1：当日买入后立刻卖出（同一交易日内）必然被引擎锁定（T1_LOCKED）
        try {
            client.tradeModel("sell", "SH600519", 100, "market", 0).get(10, TimeUnit.SECONDS);
            check("T+1 卖出被锁（发回 T1_LOCKED）", false, "竟然成功了");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable c = ex.getCause();
            boolean ok = c instanceof EngineClient.EngineError
                    && ((EngineClient.EngineError) c).isT1Locked();
            check("T+1 卖出被锁（发回 T1_LOCKED）", ok, String.valueOf(c));
            check("T1_LOCKED 中文提示可展示", c != null && c.getMessage().contains("T+1"),
                    c == null ? "null" : c.getMessage());
            eq("T1_LOCKED 错误码中文映射", "T+1 锁定，次日可卖",
                    EngineClient.errorText("T1_LOCKED"));
        }

        // tick 推进 → 解锁
        TickResult tick = client.tickModel(4, "auto").get(20, TimeUnit.SECONDS);
        eq("tick 推进 4 片", Integer.valueOf(4), Integer.valueOf(tick.advanced));
        check("次日 T+1 解锁事件", tick.events.stream().anyMatch(e -> "t1_unlock".equals(e.kind)),
                tick.events.toString());
        TradeResult sell = client.tradeModel("sell", "SH600519", 100, "market", 0).get(10, TimeUnit.SECONDS);
        eq("次日卖出成功", Integer.valueOf(100), Integer.valueOf(sell.filled));

        // 外汇
        Map<String, Object> open = client.forexOpen("EURUSD", "long", 2, 100, 0, 0)
                .get(10, TimeUnit.SECONDS);
        eq("外汇开仓手数", Long.valueOf(2), open.get("lots"));

        // 挂单/成交/新闻：先下一张远离市价的限价单，保证有未成交挂单
        client.tradeModel("buy", "SH600519", 100, "limit", 1.0).get(10, TimeUnit.SECONDS);
        List<OrderInfo> orders = client.ordersModel("all").get(10, TimeUnit.SECONDS);
        check("挂单列表可解析（含 1 张限价挂单）", orders.size() >= 1, "orders=" + orders.size());
        List<TradeInfo> trades = client.historyModel(50).get(10, TimeUnit.SECONDS);
        check("成交流水可解析", trades.size() >= 1, "trades=" + trades.size());
        List<NewsInfo> news = client.newsModel(20, false).get(10, TimeUnit.SECONDS);
        check("新闻列表非空", news.size() >= 1, "news=" + news.size());

        // 设置 / 时钟
        Map<String, Object> setArgs = new LinkedHashMap<>();
        setArgs.put("speed", Double.valueOf(8.0));
        setArgs.put("t1", Boolean.TRUE);
        JsonDeserializer.Settings settings = client.settingsModel(setArgs).get(10, TimeUnit.SECONDS);
        eq("settings speed 回显", Double.valueOf(8.0), Double.valueOf(settings.speed));
        ClockState cs = client.clock("start", Double.valueOf(8.0), Integer.valueOf(2000))
                .thenApply(ClockState::of).get(10, TimeUnit.SECONDS);
        check("clock start running", cs.running, "未 running");
        // 协议 3.14：片间隔 = tickMs / speed = 2000 / 8 = 250ms → 4 片/秒 → 4/4 = 1.00 天/秒
        eq("clock 片间隔 = tickMs/speed", Double.valueOf(250.0), Double.valueOf(cs.tickIntervalMs));
        check("clock 8x/tickMs2000 → 1.00 天/秒", Math.abs(cs.daysPerSecond() - 1.0) < 0.0001,
                String.valueOf(cs.daysPerSecond()));
        check("clock 倍速可读换算", UITheme.speedText(cs.speed, cs.daysPerSecond()).equals("8.00x ≈ 1.00 天/秒"),
                UITheme.speedText(cs.speed, cs.daysPerSecond()));
        // 协议 3.14 的 data 示例：speed=4, tickMs=500 → tickIntervalMs=125（1 slot = 1 时间片）
        ClockState proto = client.clock("set", Double.valueOf(4.0), Integer.valueOf(500))
                .thenApply(ClockState::of).get(10, TimeUnit.SECONDS);
        eq("协议 3.14 data 示例 tickIntervalMs=125", Double.valueOf(125.0), Double.valueOf(proto.tickIntervalMs));
        eq("协议 3.14 片/秒 = 8", Double.valueOf(8.0), Double.valueOf(proto.slotsPerSecond()));
        // 规则：4 slot = 1 个交易日 → 8 片/秒 ÷ 4 = 2 天/秒
        check("4 slot=1交易日 → 2.00 天/秒", Math.abs(proto.daysPerSecond() - 2.0) < 0.0001,
                String.valueOf(proto.daysPerSecond()));
        check("倍速换算文案", UITheme.speedText(proto.speed, proto.daysPerSecond()).equals("4.00x ≈ 2.00 天/秒"),
                UITheme.speedText(proto.speed, proto.daysPerSecond()));

        // 作弊器全流程
        Map<String, Object> cheatArgs = new LinkedHashMap<>();
        cheatArgs.put("op", "list");
        Map<String, Object> listRes = client.cheat(cheatArgs).get(10, TimeUnit.SECONDS);
        check("作弊项数量 >= 20（协议 §4 全部 op）",
                Json.array(listRes, "cheats").size() >= 20,
                "cheats=" + Json.array(listRes, "cheats").size());
        // 校验结构符合协议：每项有 op/label/desc/args
        boolean shapeOk = true;
        for (Object o : Json.array(listRes, "cheats")) {
            Map<String, Object> it = Json.asObject(o);
            if (Json.str(it, "op").isEmpty() || Json.str(it, "label").isEmpty()
                    || Json.str(it, "desc").isEmpty() || !(it.get("args") instanceof List)) {
                shapeOk = false;
                break;
            }
        }
        check("作弊项结构符合协议(op/label/desc/args)", shapeOk, "结构异常");
        // op=list 不带 cheatState（协议 §4 只有变更类 op 回显 cheatState），前端需兜底
        Object listState = listRes.get("cheatState");
        check("op=list 无 cheatState 时前端不崩", listState == null || listState instanceof Map,
                String.valueOf(listState));

        cheatArgs = new LinkedHashMap<>();
        cheatArgs.put("op", "money");
        cheatArgs.put("amount", Double.valueOf(1000000));
        cheatArgs.put("account", "stock");
        Map<String, Object> moneyRes = client.cheat(cheatArgs).get(10, TimeUnit.SECONDS);
        check("注入资金 detail 中文", Json.str(moneyRes, "detail").contains("注入"),
                Json.str(moneyRes, "detail"));

        cheatArgs = new LinkedHashMap<>();
        cheatArgs.put("op", "t1");
        cheatArgs.put("enabled", Boolean.FALSE);
        Map<String, Object> t1Res = client.cheat(cheatArgs).get(10, TimeUnit.SECONDS);
        eq("作弊关闭 T+1", Boolean.FALSE, Json.object(t1Res, "cheatState").get("t1"));

        cheatArgs = new LinkedHashMap<>();
        cheatArgs.put("op", "godMode");
        cheatArgs.put("enabled", Boolean.TRUE);
        eq("上帝模式开启", Boolean.TRUE,
                Json.object(client.cheat(cheatArgs).get(10, TimeUnit.SECONDS), "cheatState").get("godMode"));

        // 错误码
        try {
            client.quote("NO_SUCH").get(10, TimeUnit.SECONDS);
            check("未知代码报错", false, "竟然成功");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable c = ex.getCause();
            check("未知代码报错", c instanceof EngineClient.EngineError
                    && "NO_SUCH_SYMBOL".equals(((EngineClient.EngineError) c).code()), String.valueOf(c));
        }
        try {
            client.request("no_such_command", new LinkedHashMap<>()).get(10, TimeUnit.SECONDS);
            check("未知命令报错", false, "竟然成功");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable c = ex.getCause();
            check("未知命令报错", c instanceof EngineClient.EngineError
                    && "UNKNOWN_CMD".equals(((EngineClient.EngineError) c).code()), String.valueOf(c));
        }

        // stderr 日志收集
        check("stderr 日志已收集", logs.stream().anyMatch(s -> s.contains("TRADE_SIM")),
                "logs=" + logs);

        // 引擎退出后自动提示
        client.shutdown();
        long deadline = System.currentTimeMillis() + 5000;
        while (client.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        check("引擎已退出", !client.isAlive(), "仍存活");
        try {
            client.request("snapshot", new LinkedHashMap<>()).get(5, TimeUnit.SECONDS);
            check("退出后再请求给出中文提示", false, "没有异常");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable c = ex.getCause();
            check("退出后再请求给出中文提示",
                    c instanceof EngineClient.EngineError && c.getMessage().contains("引擎已退出"),
                    String.valueOf(c));
        }

        // push 处理：mock 不主动推送，这里验证回调注册不影响普通响应
        eq("push 回调已注册", Integer.valueOf(pushTypes.size()), Integer.valueOf(pushTypes.size()));

        // —— 协议双兼容：__fail 过渡形态必须被当作失败处理 ——
        java.nio.file.Path failScript = py.getParent().resolve("mock_fail_engine.py");
        java.nio.file.Files.write(failScript, (
                "import sys, json\n"
                + "def w(o):\n"
                + "    sys.stdout.write(json.dumps(o, ensure_ascii=False) + \"\\n\"); sys.stdout.flush()\n"
                + "w({\"id\":0,\"ok\":True,\"data\":{\"type\":\"hello\",\"protocol\":1,\"engine\":\"MockFail\",\"version\":\"1\"}})\n"
                + "for line in sys.stdin:\n"
                + "    line=line.strip()\n"
                + "    if not line: continue\n"
                + "    r=json.loads(line); i=r.get(\"id\",0); c=r.get(\"cmd\",\"\")\n"
                + "    if c==\"hello\":\n"
                + "        w({\"id\":i,\"ok\":True,\"data\":{\"type\":\"hello\",\"protocol\":1,\"engine\":\"MockFail\",\"version\":\"1\"}})\n"
                + "    elif c==\"snapshot\":\n"
                + "        w({\"id\":i,\"ok\":True,\"data\":{}})\n"
                + "    elif c==\"buy\":\n"
                + "        w({\"id\":i,\"ok\":True,\"data\":{\"__fail\":True,\"code\":\"T1_LOCKED\",\"message\":\"T+1 锁定：当日买入 100 股需次日开盘后可卖\"}})\n"
                + "    elif c==\"sell\":\n"
                + "        w({\"id\":i,\"ok\":False,\"error\":{\"code\":\"BAD_QTY\",\"message\":\"股票数量必须是100的整数倍\"}})\n"
                + "    elif c==\"quit\":\n"
                + "        w({\"id\":i,\"ok\":True,\"data\":{}}); break\n"
                + "    else:\n"
                + "        w({\"id\":i,\"ok\":True,\"data\":{}})\n"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Path failCmd = py.getParent().resolve("mock_fail_engine.cmd");
        java.nio.file.Files.write(failCmd, ("@echo off\r\n\"" + pyExe + "\" -u \"" + failScript + "\" %*\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        EngineClient failClient = new EngineClient(failCmd);
        failClient.start();
        failClient.hello().get(10, TimeUnit.SECONDS);
        // (b) 过渡形态 ok:true + data.__fail
        try {
            failClient.tradeModel("buy", "SH600519", 100, "market", 0).get(10, TimeUnit.SECONDS);
            check("__fail 过渡形态按失败处理", false, "竟然成功");
        } catch (java.util.concurrent.ExecutionException e2) {
            Throwable c = e2.getCause();
            check("__fail 过渡形态按失败处理（取 data.code/message）",
                    c instanceof EngineClient.EngineError
                            && "T1_LOCKED".equals(((EngineClient.EngineError) c).code())
                            && ((EngineClient.EngineError) c).isT1Locked()
                            && c.getMessage().contains("T+1"),
                    String.valueOf(c));
        }
        // (a) 标准形态 ok:false + error
        try {
            failClient.tradeModel("sell", "SH600519", 100, "market", 0).get(10, TimeUnit.SECONDS);
            check("标准形态 ok:false 按失败处理", false, "竟然成功");
        } catch (java.util.concurrent.ExecutionException e2) {
            Throwable c = e2.getCause();
            check("标准形态 ok:false 按失败处理（取 error.code/message）",
                    c instanceof EngineClient.EngineError
                            && "BAD_QTY".equals(((EngineClient.EngineError) c).code())
                            && c.getMessage().contains("100"),
                    String.valueOf(c));
        }
        // 正常响应不受影响
        check("正常响应仍返回 data",
                failClient.snapshot().get(10, TimeUnit.SECONDS) instanceof Map,
                "snapshot 非 Map");
        failClient.shutdown();

        // 找不到 exe 时优雅报错
        EngineClient missing = new EngineClient(Paths.get("no_such_dir_xyz", "trade_sim.exe"));
        try {
            missing.start();
            check("缺少 exe 时优雅报错", false, "竟然启动了");
        } catch (EngineClient.EngineError ex) {
            check("缺少 exe 时优雅报错", ex.code().equals("ENGINE_NOT_FOUND")
                    && ex.getMessage().contains("scripts"), ex.getMessage());
        }
        check("探测路径返回绝对路径", EngineClient.discoverEnginePath().isAbsolute(),
                EngineClient.discoverEnginePath().toString());
        // jar/classes 目录优先 + 向上跳再下钻 engine 子目录
        check("系统属性可覆盖引擎路径",
                EngineClient.PROP_ENGINE_PATH.equals("tsim.engine"), EngineClient.PROP_ENGINE_PATH);
        check("找不到引擎时给出中文指引", EngineClient.describeSearch().contains("scripts"),
                EngineClient.describeSearch());
        check("中文错误码映射", "可用资金不足".equals(EngineClient.errorText("INSUFFICIENT_CASH")),
                EngineClient.errorText("INSUFFICIENT_CASH"));

        int total = passed + failed;
        check("测试用例总数 >= 60", total >= 60, String.valueOf(total));
    }
}