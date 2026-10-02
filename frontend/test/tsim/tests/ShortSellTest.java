package tsim.tests;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 股票做空（融券）回归测试，驱动真实引擎。 */
public final class ShortSellTest {
    private ShortSellTest() { }

    private static int pass;
    private static int fail;
    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + "  --  " + detail); }
    }

    private static String run(String exe, List<String> reqs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(exe, "--stdio");
        pb.redirectErrorStream(false);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (out) { out.append(line).append("\n"); }
                }
            } catch (Exception ignored) { }
        });
        t.start();
        try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
            for (String r : reqs) { w.write(r + "\n"); w.flush(); Thread.sleep(25); }
        }
        t.join(6000);
        p.destroyForcibly();
        synchronized (out) { return out.toString(); }
    }

    public static void main(String[] args) throws Exception {
        String exe = args.length > 0 ? args[0] : "build\\engine\\trade_sim.exe";

        // 1) 开空成功 + 字段完整
        List<String> a = new ArrayList<>();
        a.add("{\"id\":1,\"cmd\":\"newgame\",\"args\":{\"seed\":1}}");
        a.add("{\"id\":2,\"cmd\":\"short\",\"args\":{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}}");
        a.add("{\"id\":3,\"cmd\":\"snapshot\",\"args\":{}}");
        a.add("{\"id\":99,\"cmd\":\"quit\"}");
        String r1 = run(exe, a);
        check("做空开仓成功", r1.contains("\"shortQty\":100"), "no shortQty");
        check("做空冻结保证金", r1.contains("\"shortMargin\":") && !r1.contains("\"shortMargin\":0.0"), "no margin");
        check("快照含 shortAvgPrice", r1.contains("\"shortAvgPrice\":"), "missing");
        check("快照含 shortPnl", r1.contains("\"shortPnl\":"), "missing");
        check("快照含 todayShortedQty", r1.contains("\"todayShortedQty\":100"), "missing");

        // 2) 下跌 -> 做空盈利（shortPnl 为正）
        List<String> b = new ArrayList<>();
        b.add("{\"id\":1,\"cmd\":\"newgame\",\"args\":{\"seed\":1}}");
        b.add("{\"id\":2,\"cmd\":\"short\",\"args\":{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}}");
        b.add("{\"id\":3,\"cmd\":\"cheat\",\"args\":{\"op\":\"price\",\"symbol\":\"SH600519\",\"pct\":-0.05}}");
        b.add("{\"id\":4,\"cmd\":\"snapshot\",\"args\":{}}");
        b.add("{\"id\":99,\"cmd\":\"quit\"}");
        String r2 = run(exe, b);
        boolean profited = false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"shortPnl\":([-0-9.]+)").matcher(r2);
        while (m.find()) { if (Double.parseDouble(m.group(1)) > 0) { profited = true; } }
        check("下跌时做空盈利（shortPnl > 0）", profited, "shortPnl not positive");

        // 3) T+1：当日不可平仓
        List<String> c = new ArrayList<>(b);
        c.add(c.size() - 1, "{\"id\":5,\"cmd\":\"cover\",\"args\":{\"symbol\":\"SH600519\"}}");
        String r3 = run(exe, c);
        check("当日平空被 T+1 拒绝", r3.contains("T1_LOCKED"), "expected T1_LOCKED");

        // 4) 次日可平空
        List<String> d = new ArrayList<>();
        d.add("{\"id\":1,\"cmd\":\"newgame\",\"args\":{\"seed\":1}}");
        d.add("{\"id\":2,\"cmd\":\"short\",\"args\":{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}}");
        d.add("{\"id\":3,\"cmd\":\"tick\",\"args\":{\"n\":4,\"mode\":\"auto\"}}");
        d.add("{\"id\":4,\"cmd\":\"cover\",\"args\":{\"symbol\":\"SH600519\"}}");
        d.add("{\"id\":5,\"cmd\":\"history\",\"args\":{\"limit\":5}}");
        d.add("{\"id\":99,\"cmd\":\"quit\"}");
        String r4 = run(exe, d);
        check("次日平空成功", r4.contains("\"shortQty\":0"), "cover failed");
        check("平空进入成交流水（side=cover）", r4.contains("\"side\":\"cover\""), "no cover trade");
        check("平空后仓位清空", r4.contains("\"stockPositions\":[]"), "position remains");

        // 5) 校验：非 100 整数倍 / 杠杆越界
        List<String> e = new ArrayList<>();
        e.add("{\"id\":1,\"cmd\":\"newgame\",\"args\":{\"seed\":1}}");
        e.add("{\"id\":2,\"cmd\":\"short\",\"args\":{\"symbol\":\"SH600519\",\"qty\":150}}");
        e.add("{\"id\":3,\"cmd\":\"short\",\"args\":{\"symbol\":\"SH600519\",\"qty\":100,\"leverage\":9}}");
        e.add("{\"id\":4,\"cmd\":\"cover\",\"args\":{\"symbol\":\"SH600519\"}}");
        e.add("{\"id\":99,\"cmd\":\"quit\"}");
        String r5 = run(exe, e);
        check("非 100 整数倍被拒", r5.contains("BAD_QTY"), "expected BAD_QTY");
        check("做空杠杆 >5 被拒", r5.contains("做空杠杆必须在 1..5"), "expected leverage guard");
        check("无空头时平仓报 NO_POSITION", r5.contains("NO_POSITION"), "expected NO_POSITION");

        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}