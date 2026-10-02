package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

/**
 * 引擎 stderr 日志查看器。线程安全：{@link #append(String)} 可在任意线程调用。
 */
public final class LogPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int MAX_CHARS = 400_000;

    private final JTextArea area = new JTextArea();
    private final JCheckBox autoScroll = new JCheckBox("自动滚动", true);
    private final JCheckBox wrap = new JCheckBox("自动换行", false);

    /** 构造日志面板。 */
    public LogPanel() {
        super(new BorderLayout());
        area.setEditable(false);
        area.setFont(UITheme.font(java.awt.Font.PLAIN, 12));
        area.setForeground(UITheme.TEXT);
        area.setBackground(UITheme.PANEL_DARK);
        area.setCaretColor(UITheme.TEXT);
        area.setLineWrap(false);
        area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(BorderFactory.createLineBorder(UITheme.WIDGET));
        sp.getViewport().setBackground(UITheme.PANEL_DARK);

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBackground(UITheme.PANEL);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.WIDGET));
        JButton clear = new JButton(new AbstractAction("清空") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                area.setText("");
            }
        });
        clear.setFont(UITheme.SMALL_FONT);
        JButton copy = new JButton(new AbstractAction("复制全部") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                area.selectAll();
                area.copy();
                area.setCaretPosition(area.getDocument().getLength());
            }
        });
        copy.setFont(UITheme.SMALL_FONT);
        autoScroll.setFont(UITheme.SMALL_FONT);
        autoScroll.setOpaque(false);
        autoScroll.setForeground(UITheme.TEXT_DIM);
        wrap.setFont(UITheme.SMALL_FONT);
        wrap.setOpaque(false);
        wrap.setForeground(UITheme.TEXT_DIM);
        wrap.addActionListener(e -> area.setLineWrap(wrap.isSelected()));

        bar.add(clear);
        bar.add(copy);
        bar.addSeparator();
        bar.add(autoScroll);
        bar.add(wrap);
        bar.add(javax.swing.Box.createHorizontalGlue());

        add(bar, BorderLayout.NORTH);
        add(sp, BorderLayout.CENTER);
        setPreferredSize(new Dimension(600, 240));
    }

    /** 追加一行（可跨线程调用）。 */
    public void append(final String line) {
        if (line == null) {
            return;
        }
        Runnable r = () -> {
            area.append(line);
            area.append("\n");
            if (area.getDocument().getLength() > MAX_CHARS) {
                try {
                    area.getDocument().remove(0, MAX_CHARS / 4);
                } catch (javax.swing.text.BadLocationException ignored) {
                    // 截断失败不影响使用
                }
            }
            if (autoScroll.isSelected()) {
                area.setCaretPosition(area.getDocument().getLength());
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** 批量替换内容。 */
    public void setLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        SwingUtilities.invokeLater(() -> area.setText(sb.toString()));
    }

    /** 当前文本（测试用）。 */
    public String text() {
        return area.getText();
    }
}
