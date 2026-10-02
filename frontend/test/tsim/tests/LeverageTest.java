package tsim.tests;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import tsim.ui.LeverageSlider;
import tsim.ui.TradePanel;

/** 杠杆滑杆 + 引擎融资杠杆的回归测试。 */
public final class LeverageTest {
    private LeverageTest() { }

    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + "  --  " + detail); }
    }

    public static void main(String[] args) throws Exception {
        // --- 滑杆范围（用户要求：股票 <=25，外汇 <=250） ---
        LeverageSlider stock = new LeverageSlider(1, 25);
        LeverageSlider forex = new LeverageSlider(1, 250);
        check("股票杠杆上限 = 25", stock.maximum() == 25, "got=" + stock.maximum());
        check("外汇杠杆上限 = 250", forex.maximum() == 250, "got=" + forex.maximum());

        stock.setLeverage(25);
        check("股票杠杆可设到 25", stock.leverage() == 25, "got=" + stock.leverage());
        stock.setLeverage(100);
        check("股票杠杆越界被夹到 25", stock.leverage() == 25, "got=" + stock.leverage());
        forex.setLeverage(250);
        check("外汇杠杆可设到 250", forex.leverage() == 250, "got=" + forex.leverage());
        forex.setLeverage(9999);
        check("外汇杠杆越界被夹到 250", forex.leverage() == 250, "got=" + forex.leverage());
        stock.setLeverage(0);
        check("杠杆下限为 1", stock.leverage() == 1, "got=" + stock.leverage());

        // --- TradePanel 同时持有两个滑杆 ---
        TradePanel tp = new TradePanel();
        Field sf = TradePanel.class.getDeclaredField("stockLeverage");
        sf.setAccessible(true);
        Field ff = TradePanel.class.getDeclaredField("forexLeverage");
        ff.setAccessible(true);
        check("面板含股票杠杆滑杆", sf.get(tp) instanceof LeverageSlider, "missing");
        check("面板含外汇杠杆滑杆", ff.get(tp) instanceof LeverageSlider, "missing");

        // --- 仓位滑杆（进度条式，最右 = 全仓） ---
        Field sizer = TradePanel.class.getDeclaredField("stockSizer");
        sizer.setAccessible(true);
        Object ps = sizer.get(tp);
        Method setPct = ps.getClass().getDeclaredMethod("setPct", double.class);
        setPct.setAccessible(true);
        Method pct = ps.getClass().getDeclaredMethod("pct");
        pct.setAccessible(true);
        setPct.invoke(ps, 1.0);
        check("仓位滑杆最右端 = 100%（全仓）", Math.abs(((Double) pct.invoke(ps)) - 1.0) < 1e-9,
                "got=" + pct.invoke(ps));
        setPct.invoke(ps, 0.25);
        check("仓位滑杆 1/4 档", Math.abs(((Double) pct.invoke(ps)) - 0.25) < 1e-9, "got=" + pct.invoke(ps));

        // --- 面板必须能承载持仓行（多头 + 空头，带操作按钮） ---
        Method setRows = TradePanel.class.getDeclaredMethod("setPositionRows",
                long.class, double.class, double.class, double.class, long.class,
                long.class, double.class, double.class, long.class);
        check("面板提供 setPositionRows（多空持仓行）", setRows != null, "missing");
        // 做空杠杆滑杆（1..5）
        Field shf = TradePanel.class.getDeclaredField("shortLeverage");
        shf.setAccessible(true);
        LeverageSlider sh = (LeverageSlider) shf.get(tp);
        check("面板含做空杠杆滑杆，上限 5", sh.maximum() == 5, "max=" + sh.maximum());

        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}