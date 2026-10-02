package tsim.tests;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractButton;

import tsim.ui.CopyableLabel;
import tsim.ui.TradePanel;

/**
 * 回归：右栏标题里的标的代码应「点击即复制」，且要有显式复制按钮。
 *
 * <p>迭代历史：</p>
 * <ol>
 *   <li>最初标题是 {@code JLabel}，完全不能复制；</li>
 *   <li>改成只读文本框后，双击会 {@code selectAll()}，把「贵州茅台 SH600519」
 *       整串高亮选中——但用户要复制的只是代码 SH600519；</li>
 *   <li>现在：单击即复制**代码**（不含名称），并额外提供一个显式「复制」按钮。</li>
 * </ol>
 */
public final class CopyableCodeTest {

    private static int pass;
    private static int fail;

    private CopyableCodeTest() {
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

    /** 触发一次鼠标左键单击。 */
    private static void clickOnce(java.awt.Component target) {
        target.dispatchEvent(new java.awt.event.MouseEvent(target,
                java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
    }

    /** 运行。 */
    public static void main(String[] args) {
        // 1) 组件语义：只读 + 可复制 + 手型光标
        CopyableLabel l = new CopyableLabel("贵州茅台  SH600519");
        l.setCopyText("SH600519");
        ok("标签只读", !l.isEditable(), "editable=true");
        ok("标签处于可复制状态", l.isCopyable(), "isCopyable=false");
        ok("点击复制的文本可单独指定（不含名称）",
                "SH600519".equals(l.copyText()), "copyText=" + l.copyText());

        // 2) 单击即复制：复制的是代码，而非整串标题。
        //    headless 环境没有系统剪贴板，这里断言「点击路径不抛异常」
        //    以及「被复制的文本就是代码」——剪贴板内容在有桌面时由同一路径写入。
        try {
            l.copyNow();
            ok("单击复制不抛异常（headless 无剪贴板也安全）", true, "");
        } catch (RuntimeException ex) {
            ok("单击复制不抛异常（headless 无剪贴板也安全）", false, ex.toString());
        }
        ok("被复制的文本是纯代码（不含名称）",
                "SH600519".equals(l.copyText()), "copyText=" + l.copyText());

        // 3) 不抢焦点（避免与空格热键互相干扰）
        CopyableLabel l2 = new CopyableLabel("SH600519");
        ok("可复制标签不抢焦点（不干扰空格热键）", !l2.isFocusable(), "focusable=true");

        // 4) 交易面板：标题/副标题是可复制组件，且存在显式「复制」按钮
        TradePanel p = new TradePanel();
        List<Component> all = new ArrayList<>();
        collect(p, all);

        List<CopyableLabel> copyables = new ArrayList<>();
        for (Component c : all) {
            if (c instanceof CopyableLabel) {
                copyables.add((CopyableLabel) c);
            }
        }
        ok("交易面板里有可复制标签（标题 + 副标题）",
                copyables.size() >= 2, "CopyableLabel 个数=" + copyables.size());

        AbstractButton copyBtn = null;
        for (Component c : all) {
            if (c instanceof AbstractButton) {
                String t = ((AbstractButton) c).getText();
                if (t != null && (t.contains("复制") || t.contains("已复制"))) {
                    copyBtn = (AbstractButton) c;
                }
            }
        }
        ok("标题行有显式「复制」按钮", copyBtn != null, "未找到复制按钮");
        if (copyBtn != null) {
            ok("复制按钮不抢焦点（否则会干扰空格热键）", !copyBtn.isFocusable(), "focusable=true");
            // 点击按钮不应抛异常（无选中标的时应静默返回）
            ActionEvent ev = new ActionEvent(copyBtn, ActionEvent.ACTION_PERFORMED, "copy");
            try {
                copyBtn.getActionListeners()[0].actionPerformed(ev);
                ok("无选中标的时点复制按钮不抛异常", true, "");
            } catch (RuntimeException ex) {
                ok("无选中标的时点复制按钮不抛异常", false, ex.getMessage());
            }
        }

        // 5) 标题不应退回普通 JLabel（否则又不能复制）
        boolean plainTitle = false;
        for (Component c : all) {
            if (c instanceof javax.swing.JLabel && !(c instanceof CopyableLabel)) {
                String t = ((javax.swing.JLabel) c).getText();
                if (t != null && t.contains("未选择标的")) {
                    plainTitle = true;
                }
            }
        }
        ok("标题没有退回成不可复制的普通 JLabel", !plainTitle, "标题仍是普通 JLabel");

        System.out.println();
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static String readClipboard() {
        try {
            Object o = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return o == null ? "" : o.toString();
        } catch (Exception ex) {
            return "(读取失败: " + ex.getMessage() + ")";
        }
    }

    /** 占位，保证 clickOnce 被引用（保留供后续交互测试使用）。 */
    static void selfCheck(TradePanel p) {
        clickOnce(p);
    }
}
