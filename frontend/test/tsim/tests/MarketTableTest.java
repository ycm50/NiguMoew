package tsim.tests;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import tsim.json.JsonDeserializer;
import tsim.json.JsonDeserializer.MarketData;
import tsim.ui.MarketTable;

/**
 * 行情表"选中行不跟手"的回归测试。
 *
 * <p>场景：用户点表头按涨跌幅排序后选一支标的，随后行情刷新；
 * 选中行必须还是那一支，而不是按模型行号错位跳到别的标的上。</p>
 */
public final class MarketTableTest {
    private MarketTableTest() { }

    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + "  --  " + detail); }
    }

    /** 造一条行情（字段名与协议一致）。 */
    private static Map<String, Object> stock(String sym, String name, double last, double chg) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("symbol", sym);
        m.put("name", name);
        m.put("last", last);
        m.put("prevClose", last - chg);
        m.put("open", last);
        m.put("high", last);
        m.put("low", last);
        m.put("changePct", chg);
        m.put("volume", 1000L);
        m.put("bid", last);
        m.put("ask", last);
        m.put("halted", Boolean.FALSE);
        return m;
    }

    private static MarketData market(String[][] rows) throws Exception {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> stocks = new ArrayList<>();
        for (String[] r : rows) {
            stocks.add(stock(r[0], r[1], Double.parseDouble(r[2]), Double.parseDouble(r[3])));
        }
        List<Map<String, Object>> forex = new ArrayList<>();
        forex.add(stock("EURUSD", "欧元/美元", 1.08, 0.001));
        root.put("stocks", stocks);
        root.put("forex", forex);
        root.put("time", new java.util.LinkedHashMap<String, Object>());
        return MarketData.of(root);
    }

    public static void main(String[] args) throws Exception {
        MarketTable mt = new MarketTable();
        Field tf = MarketTable.class.getDeclaredField("stockTable");
        tf.setAccessible(true);
        JTable table = (JTable) tf.get(mt);

        // 模型顺序固定为 A,B,C,D；但涨跌幅不同
        MarketData md = market(new String[][]{
            {"AAA", "甲", "10.0", "0.05"},
            {"BBB", "乙", "20.0", "0.01"},
            {"CCC", "丙", "30.0", "-0.02"},
            {"DDD", "丁", "40.0", "0.03"},
        });
        mt.update(md);
        mt.setSize(600, 200);
        table.setSize(600, 200);
        mt.doLayout();

        // 用户点击第 3 行（视图行）选中 CCC（走真实选择模型，触发监听）
        table.getSelectionModel().setSelectionInterval(2, 2);
        String picked = mt.selectedSymbol();
        check("点击 3 行后 selectedSymbol = CCC", "CCC".equals(picked), "got=" + picked);

        // 用户按涨跌幅排序（视图顺序彻底改变）
        RowSorter<?> sorter = table.getRowSorter();
        List<RowSorter.SortKey> keys = new ArrayList<>();
        keys.add(new RowSorter.SortKey(3, SortOrder.DESCENDING));   // 涨跌幅列降序
        sorter.setSortKeys(keys);

        // 行情刷新若干次 —— 选中必须始终落在 CCC 那一行
        boolean alwaysCcc = true;
        String lastSym = "";
        for (int i = 0; i < 5; i++) {
            mt.update(market(new String[][]{
                {"AAA", "甲", "10.0", "0.05"},
                {"BBB", "乙", "20.0", "0.01"},
                {"CCC", "丙", "30.0", "-0.02"},
                {"DDD", "丁", "40.0", "0.03"},
            }));
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) { alwaysCcc = false; lastSym = "(无选中)"; break; }
            int modelRow = table.convertRowIndexToModel(viewRow);
            Field smF = MarketTable.class.getDeclaredField("stockModel");
            smF.setAccessible(true);
            Object sm = smF.get(mt);
            Method symAt = sm.getClass().getDeclaredMethod("symbolAt", int.class);
            symAt.setAccessible(true);
            String sym = (String) symAt.invoke(sm, modelRow);
            lastSym = sym;
            if (!"CCC".equals(sym)) { alwaysCcc = false; break; }
        }
        check("排序后刷新行情，选中行仍是 CCC", alwaysCcc, "got=" + lastSym);

        // 关键不变量：视图行号必须等于 CCC 在【当前排序下】的视图行号。
        // 若代码把模型行号当视图行号用（旧 bug），这里就会不相等。
        int cccModel = 2;
        int expectedView = table.convertRowIndexToView(cccModel);
        check("视图行号 = CCC 在当前排序下的视图行号",
                table.getSelectedRow() == expectedView,
                "selected=" + table.getSelectedRow() + " expected=" + expectedView);
        check("排序确实改变了视图顺序（否则本测试无意义）",
                expectedView != cccModel || table.getRowSorter().getSortKeys().isEmpty() == false,
                "expectedView=" + expectedView);

        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}