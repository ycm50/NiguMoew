package tsim.tests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tsim.json.JsonDeserializer.Bar;
import tsim.json.JsonDeserializer.ForexQuote;
import tsim.ui.KLineChart;
import tsim.ui.TickChart;

/**
 * 回归：汇市左侧 K 线不动。
 *
 * <p>原缺陷：{@code MainFrame.updateCharts()} 只把股票喂给 K 线图、只把外汇喂给分时图，
 * 于是选中外汇后 K 线图仍停留在上一只股票的数据上，表现为「K 线不动」。</p>
 */
public final class ForexKLineTest {

    private static int pass;
    private static int fail;

    private ForexKLineTest() {
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

    private static Map<String, Object> barMap(double base, int i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", "2024-03-05");
        m.put("slot", (long) (i % 4));
        m.put("open", base + i);
        m.put("close", base + i + 0.5);
        m.put("high", base + i + 1.0);
        m.put("low", base + i - 1.0);
        m.put("volume", 1000L + i);
        return m;
    }

    private static List<Bar> bars(double base, int n) {
        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Bar.of(barMap(base, i)));
        }
        return out;
    }

    /** 运行。 */
    public static void main(String[] args) {
        KLineChart k = new KLineChart();

        // 1) 先看股票：K 线图接股票 hist
        k.setBars("SH600519", "贵州茅台", bars(1700, 30), 1690, 2);
        ok("K线图渲染股票 hist", k.barCount() == 30, "count=" + k.barCount());
        ok("K线图标的是股票代码", "SH600519".equals(k.symbol()), k.symbol());

        // 2) 关键回归：切到外汇后，K 线图必须换成外汇（修复前它一直停在股票上）
        Map<String, Object> fqMap = new LinkedHashMap<>();
        fqMap.put("symbol", "USDJPY");
        fqMap.put("name", "美元/日元");
        fqMap.put("last", 150.123);
        fqMap.put("prevClose", 150.0);
        fqMap.put("digits", 3L);
        List<Object> fhist = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            fhist.add(barMap(150.0, i));
        }
        fqMap.put("hist", fhist);
        ForexQuote fq = ForexQuote.of(fqMap);

        k.setForexQuote(fq);
        ok("切到外汇后 K线图不再显示股票代码",
                !"SH600519".equals(k.symbol()), "symbol=" + k.symbol());
        ok("切到外汇后 K线图显示外汇代码",
                "USDJPY".equals(k.symbol()), "symbol=" + k.symbol());
        ok("切到外汇后 K线图渲染外汇 hist 根数",
                k.barCount() == 25, "count=" + k.barCount());

        // 3) 分时图同样应能显示外汇（原有路径不能被破坏）
        TickChart t = new TickChart();
        t.setBars("USDJPY", "美元/日元", bars(150, 25), 149.5, 3);
        ok("分时图渲染外汇序列", t.pointCount() == 25, "count=" + t.pointCount());

        // 4) 切回股票，K 线图必须再回到股票
        k.setBars("SZ000001", "平安银行", bars(12, 22), 12.1, 2);
        ok("切回股票后 K线图跟着切回",
                "SZ000001".equals(k.symbol()) && k.barCount() == 22, k.symbol());

        System.out.println();
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
