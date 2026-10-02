package tsim.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import tsim.json.Json;
import tsim.json.JsonDeserializer.ClockState;
import tsim.json.JsonDeserializer.ForexQuote;
import tsim.json.JsonDeserializer.MarketData;
import tsim.json.JsonDeserializer.NewsInfo;
import tsim.json.JsonDeserializer.Snapshot;
import tsim.json.JsonDeserializer.StockQuote;
import tsim.json.JsonDeserializer.TickResult;
import tsim.json.JsonDeserializer.TradeInfo;
import tsim.json.JsonDeserializer.TradeResult;
import tsim.ui.EngineClient;

/**
 * 【最终验收项】真实引擎 + 真实数据的程序化证据（headless，无需图形会话）。
 *
 * <p>从仓库根运行：</p>
 * <pre>
 * java -cp "build\frontend\classes;build\frontend\selftest-classes" tsim.tests.RealEngineAcceptance
 * </pre>
 *
 * <p>断言：spawn build\engine\trade_sim.exe → hello → newgame → 真实行情进入 K 线数据 →
 * 买入 → 当日卖出被 T+1 拦截 → 推进到次日卖出成功 → 外汇开仓 → 作弊器生效 → 时钟倍速。</p>
 */
public final class RealEngineAcceptance {

    private static int pass;
    private static int fail;

    private RealEngineAcceptance() {
    }

    private static void ok(String what, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what + "  -> " + detail);
        }
    }

    /** 运行。 */
    public static void main(String[] args) throws Exception {
        System.out.println("==== TradeTower 真实引擎端到端验收（headless）====");
        System.out.println("工作目录: " + Paths.get("").toAbsolutePath());
        Path exe = EngineClient.discoverEnginePath();
        System.out.println("探测到引擎: " + exe);
        ok("探测到 build/engine/trade_sim.exe（lead 的交付布局）",
                Files.isRegularFile(exe) && exe.toString().replace('\\', '/')
                        .endsWith("build/engine/trade_sim.exe"),
                exe.toString());
        if (!Files.isRegularFile(exe)) {
            System.out.println("结果: 引擎不存在，终止");
            System.exit(1);
        }

        EngineClient c = new EngineClient(exe);
        StringBuilder stderrLog = new StringBuilder();
        c.addLogListener(s -> {
            stderrLog.append(s).append('\n');
            System.out.println("  [引擎日志] " + s);
        });
        c.start();
        ok("引擎进程已 spawn 且存活", c.isAlive(), "未存活");

        // 协议 §0：stderr 就绪标记（可选信号，不阻塞）
        for (int i = 0; i < 40 && !c.readySignalSeen(); i++) {
            Thread.sleep(50);
        }
        ok("stderr 收到可选就绪标记 TRADE_SIM ready", c.readySignalSeen(),
                "日志: " + stderrLog);

        // hello = 真正的就绪判定
        Map<String, Object> hello = c.hello().get(10, TimeUnit.SECONDS);
        ok("hello 握手 protocol=1", Long.valueOf(1).equals(hello.get("protocol")),
                String.valueOf(hello));
        System.out.println("     引擎: " + Json.str(hello, "engine") + " " + Json.str(hello, "version"));

        // newgame
        Snapshot s0 = c.newGame(System.currentTimeMillis(), "验收玩家", 1_000_000, 10_000, "normal")
                .thenApply(Snapshot::of).get(20, TimeUnit.SECONDS);
        ok("newgame 成功，起始现金 1,000,000",
                Math.abs(s0.stockAccount.cash - 1_000_000) < 1.0,
                "cash=" + s0.stockAccount.cash);
        System.out.println("     游戏时间: " + s0.time + "  股票权益: " + s0.stockAccount.equity
                + "  外汇净值: " + s0.forexAccount.equity);

        // 真实行情
        MarketData md = c.marketModel("all").get(20, TimeUnit.SECONDS);
        ok("真实行情：股票 >= 4 只", md.stocks.size() >= 4, "stocks=" + md.stocks.size());
        ok("真实行情：外汇 >= 5 对", md.forex.size() >= 5, "forex=" + md.forex.size());
        StockQuote q = md.stocks.get(0);
        ok("股票字段完整（代码/名称/最新价/昨收）",
                !q.symbol.isEmpty() && !q.name.isEmpty() && q.last > 0 && q.prevClose > 0,
                Json.write(Json.obj("s", q.symbol, "n", q.name, "last", q.last, "prev", q.prevClose)));
        ok("真实 K 线 hist 有数据（>=20 根）供自绘 K 线图",
                q.hist.size() >= 20, "hist=" + q.hist.size());
        ForexQuote fq = null;
        for (ForexQuote f : md.forex) {
            if ("USDJPY".equals(f.symbol)) {
                fq = f;
            }
        }
        ok("USDJPY digits=3（协议 §3.5）", fq != null && fq.digits == 3,
                fq == null ? "缺失" : String.valueOf(fq.digits));
        System.out.println("     样例: " + q.symbol + " " + q.name + " 最新 " + q.last
                + " 昨收 " + q.prevClose + " K线 " + q.hist.size() + " 根");
        System.out.println("     样例: " + md.forex.get(0).symbol + " digits=" + md.forex.get(0).digits);

        // 买入
        TradeResult buy = c.tradeModel("buy", q.symbol, 100, "market", 0)
                .get(20, TimeUnit.SECONDS);
        ok("真实引擎成交买入 100 股", buy.filled == 100 && "filled".equals(buy.status),
                Json.write(Json.obj("st", buy.status, "filled", buy.filled, "avg", buy.avgPrice)));
        System.out.println("     买入成交价 " + buy.avgPrice + " 手续费 " + buy.commission);

        // 当日卖出 -> T+1
        try {
            c.tradeModel("sell", q.symbol, 100, "market", 0).get(20, TimeUnit.SECONDS);
            ok("当日卖出被 T+1 拦截", false, "竟然成功");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            boolean t1 = cause instanceof EngineClient.EngineError
                    && ((EngineClient.EngineError) cause).isT1Locked();
            ok("当日卖出被 T+1 拦截（T1_LOCKED）", t1, String.valueOf(cause));
            System.out.println("     引擎 message: " + cause.getMessage());
        }

        // 持仓里的 T+1 冻结字段
        Snapshot s1 = c.snapshotModel().get(20, TimeUnit.SECONDS);
        ok("快照含持仓且 todayBoughtQty=100（T+1 冻结列数据源）",
                !s1.stockPositions.isEmpty() && s1.stockPositions.get(0).todayBoughtQty == 100,
                Json.write(Json.array(Json.obj(), "x")));

        // 推进到次日
        TickResult tick = c.tickModel(4, "auto").get(30, TimeUnit.SECONDS);
        ok("tick 推进 4 片 = 1 个交易日", tick.advanced == 4, "advanced=" + tick.advanced);
        boolean unlocked = false;
        for (Object o : tick.events) {
            if (o instanceof tsim.json.JsonDeserializer.EventInfo
                    && "t1_unlock".equals(((tsim.json.JsonDeserializer.EventInfo) o).kind)) {
                unlocked = true;
            }
        }
        ok("次日收到 t1_unlock 事件", unlocked, tick.events.toString());
        System.out.println("     推进后时间: " + tick.time);
        for (tsim.json.JsonDeserializer.EventInfo e : tick.events) {
            System.out.println("     事件: " + e.describe());
        }

        TradeResult sell = c.tradeModel("sell", q.symbol, 100, "market", 0).get(20, TimeUnit.SECONDS);
        ok("次日卖出成功", sell.filled == 100, Json.write(Json.obj("filled", sell.filled)));
        System.out.println("     卖出成交价 " + sell.avgPrice + " 手续费 " + sell.commission);

        // 外汇
        Map<String, Object> op = c.forexOpen("EURUSD", "long", 1, 100, 0, 0).get(20, TimeUnit.SECONDS);
        ok("外汇开仓成功（1 手 / 100 倍杠杆）", op.get("positionId") != null,
                Json.write(op));
        System.out.println("     持仓号 " + op.get("positionId") + " 开仓价 " + op.get("openRate")
                + " 保证金 " + op.get("margin"));

        // 成交流水 + 新闻
        List<TradeInfo> trades = c.historyModel(50).get(20, TimeUnit.SECONDS);
        ok("成交流水有记录", !trades.isEmpty(), "trades=" + trades.size());
        // 新闻在推进时间时随机产生，新开一局可能为 0 条；先推进两个交易日再取
        c.tickModel(8, "auto").get(30, TimeUnit.SECONDS);
        List<NewsInfo> news = c.newsModel(20, false).get(20, TimeUnit.SECONDS);
        ok("新闻接口可用且字段可解析", news != null, "news=null");
        System.out.println("     新闻条数: " + news.size()
                + (news.isEmpty() ? "（本局随机未产生，属正常）" : ""));
        if (!news.isEmpty()) {
            System.out.println("     新闻: [" + news.get(0).scopeText() + "] " + news.get(0).title
                    + "  影响 " + news.get(0).impact);
        }

        // 作弊器
        Map<String, Object> money = new java.util.LinkedHashMap<>();
        money.put("op", "money");
        money.put("amount", Double.valueOf(1_000_000));
        money.put("account", "stock");
        Map<String, Object> moneyRes = c.cheat(money).get(20, TimeUnit.SECONDS);
        ok("作弊器注入资金成功（纯模拟）", Json.bool(moneyRes, "ok"),
                Json.write(moneyRes));
        System.out.println("     " + Json.str(moneyRes, "detail"));
        Map<String, Object> cs = Json.object(moneyRes, "cheatState");
        ok("作弊回复带 cheatState（协议 §4）", !cs.isEmpty(), Json.write(cs));

        // 时钟
        ClockState clk = c.clock("start", Double.valueOf(8.0), Integer.valueOf(500))
                .thenApply(ClockState::of).get(20, TimeUnit.SECONDS);
        ok("时钟自动推进已启动", clk.running, "running=" + clk.running);
        ok("引擎回显 tickIntervalMs（权威值，不自行重算）", clk.tickIntervalMs > 0,
                "interval=" + clk.tickIntervalMs);
        System.out.println(String.format(java.util.Locale.ROOT,
                "     时钟: speed=%.2f tickMs=%d 实际片间隔=%.2fms → %.2f 天/秒",
                clk.speed, clk.tickMs, clk.tickIntervalMs, clk.daysPerSecond()));
        c.clock("stop", null, null).get(20, TimeUnit.SECONDS);

        // 越界校验（协议 §3.14 范围）
        try {
            c.clock("set", Double.valueOf(9999.0), Integer.valueOf(10)).get(20, TimeUnit.SECONDS);
            System.out.println("     [INFO] 引擎接受了越界值（未报错）");
        } catch (java.util.concurrent.ExecutionException e) {
            System.out.println("     [INFO] 越界被引擎拒绝: " + e.getCause().getMessage());
        }

        c.shutdown();
        long deadline = System.currentTimeMillis() + 5000;
        while (c.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        ok("引擎正常退出", !c.isAlive(), "仍存活");

        System.out.println();
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) {
            System.exit(1);
        }
    }
}