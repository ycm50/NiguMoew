package tsim.tests;

import java.util.LinkedHashMap;
import java.util.Map;
import tsim.json.JsonDeserializer.EventInfo;

/**
 * 股票 margin_call 事件字段回归测试。
 *
 * <p>锁定缺陷：股票融资/融券的 margin_call 缺 loss 字段，
 * 前端无条件打印 "亏损 0.0"，让用户以为没亏钱。</p>
 */
public final class MarginEventTest {
    private MarginEventTest() { }

    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + "  --  " + detail); }
    }

    private static Map<String, Object> ev(String kind, double level, double loss) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("level", Double.valueOf(level));
        if (loss != 0 || kind != null) { m.put("loss", Double.valueOf(loss)); }
        return m;
    }

    public static void main(String[] args) {
        // 1) 融资预警（warn）：应显示"需补充"，不是"亏损 0.0"
        Map<String, Object> warn = new LinkedHashMap<>();
        warn.put("kind", "margin_call");
        warn.put("account", "stock");
        warn.put("mode", "warn");
        warn.put("level", Double.valueOf(19.42));
        warn.put("loss", Double.valueOf(1234567.89));
        warn.put("floatPnl", Double.valueOf(-3210000.5));
        EventInfo e1 = EventInfo.of(warn);
        check("mode=warn 被解析", "warn".equals(e1.mode), "mode=" + e1.mode);
        check("loss 被解析", e1.loss == 1234567.89, "loss=" + e1.loss);
        check("floatPnl 被解析", e1.floatPnl == -3210000.5, "floatPnl=" + e1.floatPnl);
        String t1 = e1.describe();
        check("文本含 需补充 而非 亏损 0.0", t1.contains("需补充") && !t1.contains("亏损 0.0"), t1);

        // 2) 股票强平（liquidate）：应显示本笔亏损（负数）
        Map<String, Object> liq = new LinkedHashMap<>();
        liq.put("kind", "margin_call");
        liq.put("account", "stock");
        liq.put("mode", "liquidate");
        liq.put("symbol", "SH600519");
        liq.put("side", "sell");
        liq.put("qty", Long.valueOf(100));
        liq.put("level", Double.valueOf(19.42));
        liq.put("loss", Double.valueOf(-2160917.34));
        EventInfo e2 = EventInfo.of(liq);
        String t2 = e2.describe();
        check("强平文本含标的", t2.contains("SH600519"), t2);
        check("强平文本含股数而非手数", t2.contains("100 股"), t2);
        check("强平文本含本次亏损", t2.contains("本次亏损"), t2);

        // 3) 关键回归：不再出现无条件 "亏损 0.0"
        Map<String, Object> noLoss = new LinkedHashMap<>();
        noLoss.put("kind", "margin_call");
        noLoss.put("account", "stock");
        noLoss.put("mode", "liquidate");
        noLoss.put("level", Double.valueOf(30.0));
        String t3 = EventInfo.of(noLoss).describe();
        check("loss 缺失时不误报 0.0", !t3.contains("亏损 0.0"), t3);

        // 4) qtyL 与外汇 lots 不混淆
        check("qtyL 解析为股数", e2.qtyL == 100, "qtyL=" + e2.qtyL);

        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}