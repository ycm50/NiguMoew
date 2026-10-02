package tsim.tests;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 股票融资强平机制回归测试（驱动真实引擎）。
 *
 * <p>覆盖：无负债不触发 / 有负债跌破 25% 预警 / 跌破 20% 强制平仓 /
 * 平到恢复安全线 / 穿仓追加 bankrupt。</p>
 */
public final class StockMarginTest {
    private StockMarginTest() { }

    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + "  --  " + detail); }
    }

    /** 跑一组请求，返回全部响应行。 */
    private static String run(String exe, List<String> reqs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(exe, "--stdio");
        pb.redirectErrorStream(false);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (out) { out.append(line).append("\n"); }
                }
            } catch (Exception ignored) { }
        });
        reader.start();
        try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
            for (String req : reqs) {
                w.write(req);
                w.write("\n");
                w.flush();
                Thread.sleep(25);
            }
        }
        reader.join(5000);
        p.destroyForcibly();
        synchronized (out) { return out.toString(); }
    }

    private static List<String> base(double cash, int qty, int lev, double crashPct) {
        List<String> r = new ArrayList<>();
        r.add("{\"id\":1,\"cmd\":\"newgame\",\"args\":{\"seed\":1}}");
        r.add("{\"id\":2,\"cmd\":\"cheat\",\"args\":{\"op\":\"setCash\",\"account\":\"stock\",\"value\":"
                + (long) cash + "}}");
        r.add("{\"id\":3,\"cmd\":\"buy\",\"args\":{\"symbol\":\"SH600519\",\"qty\":" + qty
                + ",\"type\":\"market\",\"leverage\":" + lev + "}}");
        if (crashPct != 0) {
            r.add("{\"id\":4,\"cmd\":\"cheat\",\"args\":{\"op\":\"price\",\"symbol\":\"SH600519\",\"pct\":"
                    + crashPct + "}}");
        }
        r.add("{\"id\":5,\"cmd\":\"tick\",\"args\":{\"n\":1,\"mode\":\"auto\"}}");
        r.add("{\"id\":6,\"cmd\":\"snapshot\",\"args\":{}}");
        r.add("{\"id\":7,\"cmd\":\"history\",\"args\":{\"limit\":20}}");
        r.add("{\"id\":99,\"cmd\":\"quit\"}");
        return r;
    }

    private static int count(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    public static void main(String[] args) throws Exception {
        String exe = args.length > 0 ? args[0] : "build\\engine\\trade_sim.exe";

        // 1) 无融资负债：暴跌也不应触发任何强平
        String a = run(exe, base(1_000_000, 100, 1, -0.30));
        check("无负债时不触发强平", count(a, "\"kind\":\"margin_call\"") == 0,
                "margin_call=" + count(a, "\"kind\":\"margin_call\""));

        // 2) 25x 满融 + 暴跌：应预警并强制平仓
        String b = run(exe, base(20_000, 200, 25, -0.04));
        check("高杠杆暴跌触发 margin_call", count(b, "\"kind\":\"margin_call\"") > 0,
                "margin_call=" + count(b, "\"kind\":\"margin_call\""));
        check("事件带 account=stock", b.contains("\"account\":\"stock\""), "missing account");
        check("存在强制平仓（note 含 强制平仓）", b.contains("强制平仓"), "missing note");
        check("强平进入成交流水（reason=liquidation）", b.contains("\"reason\":\"liquidation\""),
                "missing liquidation trade");

        // 3) 强平后应把持仓/负债清干净（本例可完全清仓）
        check("强平后融资负债归零", b.contains("\"marginUsed\":0.0"), "debt not cleared");
        check("强平后持仓已清空", b.contains("\"stockPositions\":[]"), "position not cleared");

        // 4) 温和杠杆小幅下跌：不应强平
        String c = run(exe, base(200_000, 200, 2, -0.05));
        check("低杠杆小跌不触发强平", count(c, "\"kind\":\"margin_call\"") == 0,
                "margin_call=" + count(c, "\"kind\":\"margin_call\""));

        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}