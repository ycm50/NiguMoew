package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * 启动画面：显示进度文字（引擎启动中 / 正在新建游戏 ...）。
 *
 * <p>显示在屏幕中央，带一点自绘装饰。所有方法线程安全。</p>
 */
public final class SplashWindow extends JWindow {

    private static final long serialVersionUID = 1L;

    private final JLabel message = new JLabel("正在启动引擎 ...", SwingConstants.CENTER);
    private final JLabel hint = new JLabel(UITheme.SIM_NOTICE, SwingConstants.CENTER);
    private final ProgressBar bar = new ProgressBar();

    /** 自绘进度条。 */
    private static final class ProgressBar extends JPanel {

        private static final long serialVersionUID = 1L;

        private int progress = 0;

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(UITheme.WIDGET);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
            g2.setColor(UITheme.ACCENT);
            int w = Math.max(8, (int) (getWidth() * (progress / 100.0)));
            g2.fillRoundRect(0, 0, w, getHeight(), 6, 6);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(420, 6);
        }

        void setProgress(int p) {
            progress = Math.max(0, Math.min(100, p));
            repaint();
        }
    }

    /** 构造并居中显示启动画面。 */
    public SplashWindow() {
        super();
        setAlwaysOnTop(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UITheme.BG);
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.WIDGET),
                BorderFactory.createEmptyBorder(18, 22, 16, 22)));

        JLabel title = new JLabel("拟股喵喵");
        title.setFont(UITheme.font(Font.BOLD, 22));
        title.setForeground(UITheme.TEXT);
        title.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel subtitle = new JLabel("股票 / 外汇 模拟交易仿真器   v1.0");
        subtitle.setFont(UITheme.SMALL_FONT);
        subtitle.setForeground(UITheme.TEXT_DIM);
        subtitle.setHorizontalAlignment(SwingConstants.CENTER);

        message.setFont(UITheme.UI_FONT);
        message.setForeground(UITheme.ACCENT);
        hint.setFont(UITheme.SMALL_FONT);
        hint.setForeground(UITheme.WARN);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        title.setAlignmentX(CENTER_ALIGNMENT);
        subtitle.setAlignmentX(CENTER_ALIGNMENT);
        center.add(title);
        center.add(Box.createVerticalStrut(4));
        center.add(subtitle);
        center.add(Box.createVerticalStrut(18));
        bar.setAlignmentX(CENTER_ALIGNMENT);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        center.add(bar);
        center.add(Box.createVerticalStrut(12));
        message.setAlignmentX(CENTER_ALIGNMENT);
        center.add(message);
        center.add(Box.createVerticalStrut(10));
        hint.setAlignmentX(CENTER_ALIGNMENT);
        center.add(hint);

        root.add(center, BorderLayout.CENTER);
        setContentPane(root);
        setSize(520, 210);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation((screen.width - getWidth()) / 2, (screen.height - getHeight()) / 3);
        pack();
        setSize(getWidth() < 520 ? 520 : getWidth(), getHeight());
        setLocation((screen.width - getWidth()) / 2, (screen.height - getHeight()) / 3);
    }

    /** 更新进度文字（0..100）。 */
    public void setProgress(final String text, final int progress) {
        Runnable r = () -> {
            message.setText(text);
            bar.setProgress(progress);
            bar.repaint();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** 显示启动画面。 */
    public void showSplash() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }

    /** 关闭启动画面。 */
    public void close() {
        SwingUtilities.invokeLater(() -> {
            setVisible(false);
            dispose();
        });
    }

    /** 装饰性边框颜色（供测试识别）。 */
    public Color accentColor() {
        return UITheme.ACCENT;
    }
}