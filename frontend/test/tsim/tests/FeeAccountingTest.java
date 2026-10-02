package tsim.tests;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计费系统回归测试（驱动真实引擎）。
 *
 * <p>锁定三个已修缺陷：
 * 1) 分红文案与实付一致（每10股派息 X 元，实付必须 = X/10 × 股数）；
 * 2) 做空/平空手续费计入 totalCommission；
 * 3) 做空开仓写入成交流水（side=short）。</p>
 */
public final class FeeAccountingTest {
    private FeeAccountingTest() { }

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
            for (String r : reqs) { w.write(r + "\n"); w.flush(); Thread.sleep(20); }
        }
        t.join(15000);
        p.destroyForcibly();
        synchronized (out) { return out.toString(); }
    }

    private static double last(String s, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.]+)").matcher(s);
        double v = 0;
        while (m.find()) { v = Double.parseDouble(m.group(1)); }
        return v;
    }

    /** 股票往返：开空 -> 平空，返回全部输出。 */
    private static String shortRoundTrip(String exe) throws Exception {
        List<String> r = new ArrayList<>();
        r.add("{\"id\":1,\"cmd\":\"newgame\",\"args\":{\"seed\":1}}");
        r.add("{\"id\":2,\"cmd\":\"cheat\",\"args\":{\"op\":\"setCash\",\"account\":\"stock\",\"value\":1000000}}");
        r.add("{\"id\":3,\"cmd\":\"short\",\"args\":{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}}");
        r.add("{\"id\":4,\"cmd\":\"tick\",\"args\":{\"n\":4,\"mode\":\"auto\"}}");
        r.add("{\"id\":5,\"cmd\":\"cover\",\"args\":{\"symbol\":\"SH600519\"}}");
        r.add("{\"id\":6,\"cmd\":\"snapshot\",\"args\":{}}");
        r.add("{\"id\":7,\"cmd\":\"history\",\"args\":{\"limit\":20}}");
        r.add("{\"id\":99,\"cmd\":\"quit\"}");
        return run(exe, r);
    }

    /** 持有并跨过分红日，返回全部输出。 */
    private static String dividendRun(String exe, String dir) throws Exception {
        List<String> r = new ArrayList<>();
        r.add("{\"id\":1,\"cmd\":\"newgame\",\"args\":{\"seed\":7}}");
        r.add("{\"id\":2,\"cmd\":\"cheat\",\"args\":{\"op\":\"setCash\",\"account\":\"stock\",\"value\":5000000}}");
        if ("short".equals(dir)) {
            r.add("{\"id\":3,\"cmd\":\"short\",\"args\":{\"symbol\":\"SH600519\",\"qty\":1000,\"type\":\"market\"}}");
        } else {
            r.add("{\"id\":3,\"cmd\":\"buy\",\"args\":{\"symbol\":\"SH600519\",\"qty\":1000,\"type\":\"market\"}}");
        }
        r.add("{\"id\":4,\"cmd\":\"tick\",\"args\":{\"n\":240,\"mode\":\"auto\"}}");
        r.add("{\"id\":99,\"cmd\":\"quit\"}");
        return run(exe, r);
    }

    public static void main(String[] args) throws Exception {
        String exe = args.length > 0 ? args[0] : "build\\engine\\trade_sim.exe";

        // ---- Bug 2 + 3：做空记账 ----
        String s = shortRoundTrip(exe);
        double shortFee = 0;
        Matcher m3 = Pattern.compile("\"id\":3,[^\n]*\"commission\":([0-9.]+)").matcher(s);
        if (m3.find()) { shortFee = Double.parseDouble(m3.group(1)); }
        check("做空开仓收取手续费", shortFee > 0, "commission=" + shortFee);
        double totalComm = last(s, "totalCommission");
        check("做空手续费计入 totalCommission", totalComm > shortFee,
                "totalCommission=" + totalComm + " 应大于开空单笔 " + shortFee);
        check("平空也计入 totalCommission（合计 > 开空单笔）", totalComm >= shortFee * 1.5,
                "totalCommission=" + totalComm);
        check("成交流水含 side=short（开空有记录）", s.contains("\"side\":\"short\""),
                "missing short trade");
        check("成交流水含 side=cover（平空有记录）", s.contains("\"side\":\"cover\""),
                "missing cover trade");

        // ---- Bug 1：分红文案与实付一致 ----
        String d = dividendRun(exe, "long");
        Matcher m = Pattern.compile(
                "\\{\"kind\":\"dividend\"[^}]*\"qty\":([0-9.]+)[^}]*\"amount\":(-?[0-9.]+)[^}]*\"note\":\"每10股派息 ([0-9.]+)")
                .matcher(d);
        int checked = 0;
        boolean allMatch = true;
        String detail = "";
        while (m.find()) {
            double qty = Double.parseDouble(m.group(1));
            double amount = Double.parseDouble(m.group(2));
            double per10 = Double.parseDouble(m.group(3));
            double expected = per10 / 10.0 * qty;
            checked++;
            if (Math.abs(amount - expected) > 0.01) {
                allMatch = false;
                detail = "qty=" + qty + " amount=" + amount + " 文案推算=" + expected;
            }
        }
        check("样本中出现分红事件", checked > 0, "checked=" + checked);
        check("分红实付 = 每10股派息/10 × 股数（文案可验算）", allMatch, detail);

        // ---- 做空方须支付股息 ----
        String sd = dividendRun(exe, "short");
        check("做空期间分红为负（倒贴股息）",
                Pattern.compile("\"kind\":\"dividend\"[^\n]*\"amount\":-").matcher(sd).find(),
                "no negative dividend for short");

        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}