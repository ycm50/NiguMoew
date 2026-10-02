package tsim.ui;

import java.awt.Component;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * 统一的对话框工具：中文按钮文本 + 主题字体。
 *
 * <p>所有方法都可以从任意线程调用（内部切到 EDT 执行）。</p>
 */
public final class Dialogs {

    private Dialogs() {
    }

    private static void run(Component parent, Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** 普通信息提示。 */
    public static void info(Component parent, String message) {
        show(parent, message, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 成功提示。 */
    public static void success(Component parent, String message) {
        show(parent, message, "成功", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 警告提示。 */
    public static void warn(Component parent, String message) {
        show(parent, message, "注意", JOptionPane.WARNING_MESSAGE);
    }

    /** 错误提示。 */
    public static void error(Component parent, String message) {
        show(parent, message, "错误", JOptionPane.ERROR_MESSAGE);
    }

    /** 通用提示（内部使用，标题可自定义）。 */
    public static void showMessage(Component parent, String message, String title, int type) {
        show(parent, message, title, type);
    }

    private static void show(Component parent, String message, String title, int type) {
        String text = message == null ? "" : message;
        // 太长时折行，避免对话框超出屏幕
        String shown = text.length() > 900 ? text.substring(0, 900) + " ..." : text;
        run(parent, () -> {
            JOptionPane pane = new JOptionPane(shown, type, JOptionPane.DEFAULT_OPTION);
            java.awt.Font f = UITheme.UI_FONT.deriveFont(13f);
            if (pane.getComponentCount() > 0 && pane.getComponent(0) instanceof javax.swing.JLabel) {
                javax.swing.JLabel label = (javax.swing.JLabel) pane.getComponent(0);
                label.setFont(f);
                label.setForeground(UITheme.TEXT);
            }
            pane.setBackground(UITheme.PANEL);
            javax.swing.JDialog dlg = pane.createDialog(parent, title);
            try {
                dlg.setIconImage(null);
            } catch (RuntimeException ignored) {
                // 某些 LAF 不支持设置图标，忽略
            }
            dlg.setVisible(true);
            dlg.dispose();
        });
    }
}
