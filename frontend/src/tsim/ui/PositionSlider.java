package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;

/**
 * 仓位分配滑杆：拖动选择占「最大可买/可卖」的百分比。
 *
 * <p>最右端 = 100% = 全部资金（或全部可卖持仓）。标尺上给出 1/4、1/3、1/2、全部 四个常用刻度，
 * 点一下即可吸附过去。数值变化时通过 {@link Listener} 回调给宿主面板。</p>
 */
public final class PositionSlider extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 百分比变化回调。 */
    interface Listener {
        /**
         * @param pct 0.0 ~ 1.0 的仓位比例
         */
        void onPct(double pct);
    }

    private final JSlider slider = new JSlider(0, 1000, 0);
    private final JLabel valueLabel = new JLabel("0%");
    private final JLabel detailLabel = new JLabel(" ");
    private Listener listener;
    private boolean suppress;

    PositionSlider() {
        super(new BorderLayout(6, 2));
        setOpaque(false);

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        JLabel cap = new JLabel("仓位");
        cap.setFont(UITheme.SMALL_FONT);
        cap.setForeground(UITheme.TEXT_DIM);
        valueLabel.setFont(UITheme.font(java.awt.Font.BOLD, 13));
        valueLabel.setForeground(UITheme.ACCENT);
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        top.add(cap, BorderLayout.WEST);
        top.add(valueLabel, BorderLayout.EAST);

        slider.setOpaque(false);
        slider.setPaintTicks(true);
        slider.setPaintLabels(false);
        slider.setMajorTickSpacing(250);
        slider.setMinorTickSpacing(50);
        slider.setToolTipText("拖动选择仓位；最右端 = 全部资金");
        slider.addChangeListener(e -> {
            if (!suppress) {
                refresh();
                if (listener != null) {
                    listener.onPct(pct());
                }
            }
        });

        // 快捷刻度：1/4、1/3、1/2、全部
        JPanel marks = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        marks.setOpaque(false);
        marks.add(mark("1/4", 0.25));
        marks.add(mark("1/3", 1.0 / 3.0));
        marks.add(mark("1/2", 0.5));
        marks.add(mark("全部", 1.0));
        detailLabel.setFont(UITheme.SMALL_FONT);
        detailLabel.setForeground(UITheme.TEXT_DIM);
        marks.add(detailLabel);

        add(top, BorderLayout.NORTH);
        add(slider, BorderLayout.CENTER);
        add(marks, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(280, 62));
    }

    private JLabel mark(String text, double pct) {
        JLabel l = new JLabel("<html><u>" + text + "</u></html>");
        l.setFont(UITheme.font(java.awt.Font.PLAIN, 10));
        l.setForeground(UITheme.TEXT_DIM);
        l.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        l.setToolTipText("设为 " + text + " 仓位");
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                setPct(pct);
                if (listener != null) {
                    listener.onPct(pct());
                }
            }
        });
        return l;
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    /** 当前比例 0.0~1.0。 */
    public double pct() {
        return slider.getValue() / 1000.0;
    }

    /** 设置比例（不触发回调）。 */
    public void setPct(double pct) {
        suppress = true;
        try {
            slider.setValue((int) Math.round(Math.max(0, Math.min(1, pct)) * 1000));
        } finally {
            suppress = false;
        }
        refresh();
    }

    /** 设置滑杆下方的说明文字（如 "≈ 600 股 · 1,020,000.00"）。 */
    void setDetail(String text) {
        detailLabel.setText(text == null ? " " : text);
    }

    private void refresh() {
        valueLabel.setText(Math.round(pct() * 100) + "%");
    }

    /** 滑杆本体（测试用）。 */
    JSlider sliderForTest() {
        return slider;
    }
}