package tsim.tests;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JTextField;

import tsim.ui.CheatPanel;

/**
 * 回归：作弊器「② 行情控制」的代码框。
 *
 * <p>历史缺陷一：「强制改价到」一行的代码输入框凭空消失 —— 同一个 {@link JTextField}
 * 被 add 进两行，Swing 组件只能有一个父容器，第二次 add 会把它从第一行移走。</p>
 *
 * <p>历史缺陷二：改价 / 拉砸 / 停牌 / 按比例涨跌**各有一个「代码」框**，
 * 同一段里要重复填四遍同一个代码。现已收敛为整段共用一个代码框。</p>
 *
 * <p>本测试断言：整段只有一个代码框，且四条操作行都不再自带代码框。</p>
 */
public final class CheatFieldTest {

    private static int pass;
    private static int fail;

    private CheatFieldTest() {
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

    private static void collect(Component c, List<Component> out) {
        out.add(c);
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                collect(k, out);
            }
        }
    }

    private static List<Component> flatten(Container root) {
        List<Component> all = new ArrayList<>();
        collect(root, all);
        return all;
    }

    /** 取某行里的第一个输入框。 */
    private static JTextField fieldOf(Container root, String label) {
        Container row = findRow(root, label);
        if (row == null) {
            return null;
        }
        for (Component c : flatten(row)) {
            if (c instanceof JTextField) {
                return (JTextField) c;
            }
        }
        return null;
    }

    private static String fieldTextOf(Container root, String label) {
        JTextField f = fieldOf(root, label);
        return f == null ? "(null)" : f.getText();
    }

    /** 直接子组件里含该标签的最内层行容器。 */
    private static Container findRow(Container root, String label) {
        Container best = null;
        for (Component c : flatten(root)) {
            if (!(c instanceof Container)) {
                continue;
            }
            Container con = (Container) c;
            for (Component k : con.getComponents()) {
                if (k instanceof JLabel && label.equals(((JLabel) k).getText())) {
                    int n = countFields(con);
                    if (best == null || n < countFields(best)) {
                        best = con;
                    }
                }
            }
        }
        return best;
    }

    private static int countFields(Container row) {
        int n = 0;
        for (Component c : flatten(row)) {
            if (c instanceof JTextField) {
                n++;
            }
        }
        return n;
    }

    /** 直接子组件里含指定复选框文本的最内层行容器。 */
    private static Container findRowByCheckBox(Container root, String text) {
        Container best = null;
        for (Component c : flatten(root)) {
            if (!(c instanceof Container)) {
                continue;
            }
            Container con = (Container) c;
            for (Component k : con.getComponents()) {
                if (k instanceof javax.swing.JCheckBox
                        && text.equals(((javax.swing.JCheckBox) k).getText())) {
                    int n = countFields(con);
                    if (best == null || n < countFields(best)) {
                        best = con;
                    }
                }
            }
        }
        return best;
    }

    /** 运行。 */
    public static void main(String[] args) {
        CheatPanel p = new CheatPanel();

        // 1) 共享代码框存在且是「代码（以下操作都作用于它）」那一行里的字段
        Container codeRow = findRow(p, "代码（以下操作都作用于它）");
        ok("存在整段共用的代码框行", codeRow != null, "未找到该行");
        if (codeRow != null) {
            int f = countFields(codeRow);
            ok("共用代码框行含 1 个输入框", f == 1, "该行 JTextField 数=" + f);
        }

        // 2) 四条操作行都不再自带代码框（否则就是又变回每个功能一个框）
        Container priceRow = findRow(p, "强制改价到");
        ok("「强制改价到」一行只有 1 个输入框（价格），不再自带代码框",
                priceRow != null && countFields(priceRow) == 1,
                priceRow == null ? "未找到该行" : "该行 JTextField 数=" + countFields(priceRow));

        Container pumpRow = findRow(p, "涨跌幅");
        ok("「涨跌幅」一行只有 2 个输入框（幅度+片数），不再自带代码框",
                pumpRow != null && countFields(pumpRow) == 2,
                pumpRow == null ? "未找到该行" : "该行 JTextField 数=" + countFields(pumpRow));

        Container pctRow = findRow(p, "涨跌比例");
        ok("「涨跌比例」一行只有 1 个输入框（比例），不再自带代码框",
                pctRow != null && countFields(pctRow) == 1,
                pctRow == null ? "未找到该行" : "该行 JTextField 数=" + countFields(pctRow));

        // 3) 全树里不该再出现第二个「代码」标签（说明每行自带的代码框已收掉）
        int codeLabels = 0;
        for (Component c : flatten(p)) {
            if (c instanceof JLabel) {
                String t = ((JLabel) c).getText();
                if (t != null && t.startsWith("代码")) {
                    codeLabels++;
                }
            }
        }
        // 只数「以『代码』二字开头」的行情代码框标签：
        //   ② 共享代码框（「代码（以下操作都作用于它）」）
        //   ③ T+1 独立代码框（「代码」）
        // ④ 新闻那个是「相关代码(逗号分隔)」，属于另一类语义，不计入。
        // 修复前 ② 段一个功能一个「代码」框（共 4 个），现在只剩 1 个。
        ok("行情代码框标签只剩 2 个（② 共享 1 + ③ T+1 1），不再是每个功能一个",
                codeLabels == 2, "代码框标签数=" + codeLabels);

        // 4) 停牌行仍在，且不再自带代码框
        Container freezeRow = findRowByCheckBox(p, "停牌（取消勾选=复牌）");
        ok("停牌行不再自带代码框",
                freezeRow != null && countFields(freezeRow) == 0,
                freezeRow == null ? "未找到该行" : "该行 JTextField 数=" + countFields(freezeRow));

        // 5) 所有输入框仍是各自独立实例（杜绝组件被重复挂载的老问题）
        List<JTextField> tfs = new ArrayList<>();
        for (Component c : flatten(p)) {
            if (c instanceof JTextField) {
                tfs.add((JTextField) c);
            }
        }
        boolean distinct = true;
        for (int i = 0; i < tfs.size(); i++) {
            for (int k = i + 1; k < tfs.size(); k++) {
                if (tfs.get(i) == tfs.get(k)) {
                    distinct = false;
                }
            }
        }
        ok("所有输入框都是独立实例（无共用）", distinct, "存在共用实例");

        // 6) 自动填入：导航到某个标的后，共享框应被填上该代码
        p.setMarketSymbol("USDJPY");
        ok("setMarketSymbol 能把选中标的填进共享代码框",
                "USDJPY".equals(fieldTextOf(p, "代码（以下操作都作用于它）")),
                "实际=" + fieldTextOf(p, "代码（以下操作都作用于它）"));

        // 7) 用户在共享框里手输的代码，不应被下一次自动填入冲掉
        JTextField shared = fieldOf(p, "代码（以下操作都作用于它）");
        if (shared != null) {
            shared.setText("SH600519");
            p.setMarketSymbol("EURUSD");
            ok("用户手输的代码不会被自动填入覆盖",
                    "SH600519".equals(shared.getText()), "实际=" + shared.getText());
        } else {
            ok("用户手输的代码不会被自动填入覆盖", false, "找不到共享代码框");
        }

        System.out.println();
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
