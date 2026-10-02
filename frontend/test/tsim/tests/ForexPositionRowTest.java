package tsim.tests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tsim.json.JsonDeserializer.ForexPosition;
import tsim.ui.TradePanel;

/**
 * 回归：汇市持仓从不显示在右侧面板下半区。
 *
 * <p>原缺陷：{@code MainFrame.updateTradePanelContext()} 的外汇分支只调用了
 * {@code setForexPositionContext()}（一句文字），从不刷新下半区持仓行。
 * 于是右栏顶部写着「多单 1 手 · 均价 0.9010」，下方却仍是上一次股票残留的
 * 「暂无持仓」，用户看到的就是「汇市订单完全不显示」。</p>
 */
public final class ForexPositionRowTest {

    private static int pass;
    private static int fail;

    private ForexPositionRowTest() {
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

    private static ForexPosition fx(String side, int lots, double openRate, double margin, double pnl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("positionId", 7L);
        m.put("symbol", "USDCHF");
        m.put("name", "美元/瑞郎");
        m.put("side", side);
        m.put("lots", (long) lots);
        m.put("openRate", openRate);
        m.put("last", openRate);
        m.put("margin", margin);
        m.put("pnl", pnl);
        m.put("swap", 0.0);
        m.put("stopLoss", 0.0);
        m.put("takeProfit", 0.0);
        return ForexPosition.of(m);
    }

    /** 运行。 */
    public static void main(String[] args) {
        TradePanel p = new TradePanel();

        // 1) 初始：无持仓 -> 空行
        p.setForexRows(new ArrayList<>());
        ok("无外汇持仓时行数为 0", p.positionRowCount() == 0, "rows=" + p.positionRowCount());

        // 2) 开一笔多单：必须出现在持仓行里（修复前这里恒为 0）
        List<TradePanel.ForexRow> rows = new ArrayList<>();
        ForexPosition pos = fx("long", 1, 0.9010, 3.61, 1.84);
        double pct = pos.margin > 1e-9 ? (pos.pnl + pos.swap) / pos.margin : 0.0;
        rows.add(TradePanel.ForexRow.of(pos.side, pos.symbol, pos.name,
                pos.lots, pos.openRate, pos.pnl + pos.swap, pct, pos.positionId));
        p.setForexRows(rows);
        ok("外汇多单能进入持仓行", p.positionRowCount() == 1, "rows=" + p.positionRowCount());

        // 3) 多空各一笔 -> 两行，且按浮动盈亏降序
        List<TradePanel.ForexRow> two = new ArrayList<>();
        two.add(TradePanel.ForexRow.of("long", "USDCHF", "美元/瑞郎", 1, 0.9010, -5.0, -0.01, 1));
        two.add(TradePanel.ForexRow.of("short", "USDCHF", "美元/瑞郎", 2, 0.9100, 12.0, 0.02, 2));
        p.setForexRows(two);
        ok("多空两笔 -> 两行", p.positionRowCount() == 2, "rows=" + p.positionRowCount());
        ok("按浮动盈亏降序（赚得多在前）",
                p.positionRowPnl(0) >= p.positionRowPnl(1),
                p.positionRowPnl(0) + " vs " + p.positionRowPnl(1));

        // 4) 切回空（模拟切到别的货币对）
        p.setForexRows(new ArrayList<>());
        ok("切到无持仓货币对后行数归零", p.positionRowCount() == 0, "rows=" + p.positionRowCount());

        System.out.println();
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
