package tsim.tests;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import tsim.ui.EngineClient;
import tsim.ui.TradePanel;

/** 限价模式下价格框不能被行情覆盖（回归测试）。 */
public final class LimitPriceTest {
    private LimitPriceTest() { }

    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + "  --  " + detail); }
    }

    public static void main(String[] args) throws Exception {
        TradePanel tp = new TradePanel();
        Field typeF = TradePanel.class.getDeclaredField("stockType");
        typeF.setAccessible(true);
        JComboBox<?> type = (JComboBox<?>) typeF.get(tp);
        Field priceF = TradePanel.class.getDeclaredField("stockPrice");
        priceF.setAccessible(true);
        JTextField price = (JTextField) priceF.get(tp);
        Method ctx = TradePanel.class.getMethod("setStockContext", double.class, double.class, int.class, int.class);

        // 1) 市价：价格框跟随行情
        type.setSelectedIndex(0);
        ctx.invoke(tp, 100.0, 1000000.0, 0, 2);
        check("市价：价格框跟随最新价", "100.00".equals(price.getText()), "got=" + price.getText());
        ctx.invoke(tp, 101.0, 1000000.0, 0, 2);
        check("市价：行情变化后价格框刷新", "101.00".equals(price.getText()), "got=" + price.getText());

        // 2) 切到限价：填入初值
        type.setSelectedIndex(1);
        check("限价：切换时填入最新价做初值", "101.00".equals(price.getText()), "got=" + price.getText());

        // 3) 用户手工改价
        price.setText("95.50");

        // 4) 行情变化 —— 这里是原来的 bug：价格被刷回最新价
        ctx.invoke(tp, 108.25, 1000000.0, 0, 2);
        check("限价：用户填的委托价不被行情覆盖", "95.50".equals(price.getText()), "got=" + price.getText());

        // 5) 再推几次行情，仍然保持
        ctx.invoke(tp, 120.0, 1000000.0, 0, 2);
        check("限价：多次行情后仍保持委托价", "95.50".equals(price.getText()), "got=" + price.getText());

        // 6) 切回市价再切限价 -> 允许重新给初值
        type.setSelectedIndex(0);
        ctx.invoke(tp, 130.0, 1000000.0, 0, 2);
        type.setSelectedIndex(1);
        check("限价：重新切入时给新初值", "130.00".equals(price.getText()), "got=" + price.getText());

        // --- 千分位分隔符回归（"1.67 不是数字" 的根因） ---
        Method parseUser = TradePanel.class.getDeclaredMethod("parseUserNumber", String.class);
        parseUser.setAccessible(true);
        check("解析：普通数字 1.67", ((Double) parseUser.invoke(null, "1.67")) == 1.67, "fail");
        check("解析：带千分位 1,698.73", ((Double) parseUser.invoke(null, "1,698.73")) == 1698.73, "fail");
        check("解析：全角逗号", ((Double) parseUser.invoke(null, "1，698.73")) == 1698.73, "fail");
        check("解析：带空格", ((Double) parseUser.invoke(null, " 1 698.73 ")) == 1698.73, "fail");
        boolean threw = false;
        try { parseUser.invoke(null, ""); } catch (Exception ex) { threw = true; }
        check("解析：空串抛异常", threw, "未抛异常");

        Method plain = TradePanel.class.getDeclaredMethod("plainPrice", double.class, int.class);
        plain.setAccessible(true);
        check("格式化：1698.73 -> 无千分位", "1698.73".equals(plain.invoke(null, 1698.73, 2)), "got=" + plain.invoke(null, 1698.73, 2));
        check("格式化：1234567.5 -> 无千分位", "1234567.50".equals(plain.invoke(null, 1234567.5, 2)), "got=" + plain.invoke(null, 1234567.5, 2));

        // 关键回归：价格框里写入的值必须能被自己解析回来
        type.setSelectedIndex(0);
        ctx.invoke(tp, 1698.73, 1000000.0, 0, 2);
        double back = (Double) parseUser.invoke(null, price.getText());
        check("闭环：价格框内容可被解析", back == 1698.73, "框内=" + price.getText());
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}