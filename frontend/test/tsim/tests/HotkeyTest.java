package tsim.tests;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

import tsim.ui.ClockPanel;

/**
 * 回归：空格强绑定开始/暂停，且成交回执不再弹模态框。
 *
 * <p>要求：</p>
 * <ol>
 *   <li>空格 = 开始/暂停，**任何位置**（含倍速输入框内）都生效；</li>
 *   <li>回车交给 Swing 默认行为（激活当前焦点按钮 / 触发文本框 Action），不额外占用。</li>
 * </ol>
 */
public final class HotkeyTest {

    private static int pass;
    private static int fail;

    private HotkeyTest() {
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

    /** 运行。 */
    public static void main(String[] args) {
        ClockPanel p = new ClockPanel();

        // 1) 窗口级：面板上按空格能触发「tsim-toggle-clock」
        Object winBound = p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke("SPACE"));
        ok("窗口级空格已绑定", winBound != null, "绑定=" + winBound);
        ok("窗口级空格绑定到开始/暂停动作",
                "tsim-toggle-clock".equals(String.valueOf(winBound)), "动作=" + winBound);
        ok("该动作确实存在于 ActionMap",
                p.getActionMap().get("tsim-toggle-clock") != null, "ActionMap 里没有该动作");

        // 2) 文本框级覆盖：焦点在倍速框里按空格也要生效（否则会被当空格字符吃掉）
        List<Component> all = new ArrayList<>();
        collect(p, all);
        JTextField rate = null;
        for (Component c : all) {
            if (c instanceof JTextField) {
                // 倍速框是唯一一个在 buttons 区里的文本框；按 ToolTip 定位最稳
                String tip = ((JTextField) c).getToolTipText();
                if (tip != null && tip.contains("倍速")) {
                    rate = (JTextField) c;
                }
            }
        }
        ok("找到倍速输入框", rate != null, "未找到");
        if (rate != null) {
            Object local = rate.getInputMap(JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke("SPACE"));
            ok("倍速框内空格已覆盖为开始/暂停（不会被当字符输入）",
                    local != null, "框内空格绑定=null");
            // 回车仍应触发该框自己的 Action（应用倍速），不被空格动作抢走
            Object enter = rate.getInputMap(JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke("ENTER"));
            ok("倍速框的回车绑定未被空格动作影响",
                    enter == null || !"tsim-toggle-clock-local".equals(String.valueOf(enter)),
                    "回车绑定=" + enter);
        }

        // 3) 面板里存在可点击按钮（回车默认行为 = 激活焦点按钮）
        int buttons = 0;
        for (Component c : all) {
            if (c instanceof AbstractButton) {
                buttons++;
            }
        }
        ok("时钟面板含多个按钮（回车可激活焦点按钮）", buttons >= 4, "按钮数=" + buttons);

        // 4) 关键回归：获得焦点的按钮不得再抢走空格
        //    场景 = 用户在界面上点过某个按钮（焦点留在它上面），随后按空格，
        //    期望是「开始/暂停」，而不是把刚才那个按钮又点一次。
        javax.swing.JButton focusable = null;
        for (Component c : all) {
            if (c instanceof javax.swing.JButton) {
                focusable = (javax.swing.JButton) c;
            }
        }
        ok("时钟面板里找到按钮", focusable != null, "未找到按钮");
        if (focusable != null) {
            javax.swing.KeyStroke sp = javax.swing.KeyStroke.getKeyStroke("SPACE");
            Object ownSpace = focusable.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(sp);
            // 关键：按钮的空格不能再是 Swing 默认的 "pressed"（那会「再点一次本按钮」），
            // 必须被我们改指到开始/暂停动作。
            ok("时钟面板按钮的空格已改指到开始/暂停（不再是 pressed）",
                    ownSpace != null && !"pressed".equals(String.valueOf(ownSpace)),
                    "该按钮的空格绑定=" + ownSpace);
            ok("该按钮的空格动作确实存在于其 ActionMap",
                    ownSpace != null && focusable.getActionMap().get(ownSpace) != null,
                    "ActionMap 里没有 " + ownSpace);
            // 回车不应被我们占用（保持 Swing 默认的「激活按钮」）
            Object enter = focusable.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(javax.swing.KeyStroke.getKeyStroke("ENTER"));
            ok("按钮的回车绑定未被空格动作污染",
                    enter == null || !String.valueOf(enter).startsWith("tsim-"),
                    "回车绑定=" + enter);
        }

        System.out.println();
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
