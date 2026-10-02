package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;

/**
 * 杠杆滑杆（进度条式）。
 *
 * <p>股票与外汇共用，上限不同：股票 1~25，外汇 1~250（用户要求）。
 * 内部用线性刻度，拖动即时回调。</p>
 */
public final class LeverageSlider extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 杠杆变化回调。 */
    interface Listener {
        /**
         * @param leverage 当前杠杆（整数倍）
         */
        void onLeverage(int leverage);
    }

    private final JSlider slider;
    private final JLabel valueLabel = new JLabel("1x");
    private Listener listener;
    private boolean suppress;

    /**
     * @param min 最小杠杆（含）
     * @param max 最大杠杆（含）
     */
    public LeverageSlider(int min, int max) {
        super(new BorderLayout(6, 2));
        setOpaque(false);
        slider = new JSlider(min, max, min);
        slider.setOpaque(false);
        slider.setPaintTicks(true);
        slider.setPaintLabels(false);
        slider.setMajorTickSpacing(Math.max(1, (max - min) / 5));
        slider.setMinorTickSpacing(1);
        slider.setToolTipText("杠杆 " + min + "x ~ " + max + "x（最右端 = 最高 " + max + "x）");
        slider.addChangeListener(e -> {
            if (!suppress) {
                refresh();
                if (listener != null) {
                    listener.onLeverage(leverage());
                }
            }
        });

        JLabel cap = new JLabel("杠杆");
        cap.setFont(UITheme.SMALL_FONT);
        cap.setForeground(UITheme.TEXT_DIM);
        valueLabel.setFont(UITheme.font(java.awt.Font.BOLD, 13));
        valueLabel.setForeground(UITheme.ACCENT);
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.add(cap, BorderLayout.WEST);
        top.add(valueLabel, BorderLayout.EAST);

        JPanel marks = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        marks.setOpaque(false);
        marks.add(mark("1x", min));
        marks.add(mark("5x", Math.min(max, 5)));
        marks.add(mark("10x", Math.min(max, 10)));
        marks.add(mark("25x", Math.min(max, 25)));
        if (max >= 250) {
            marks.add(mark("100x", 100));
            marks.add(mark("250x", 250));
        }

        add(top, BorderLayout.NORTH);
        add(slider, BorderLayout.CENTER);
        add(marks, BorderLayout.SOUTH);
        refresh();
    }

    private JLabel mark(String text, int value) {
        JLabel l = new JLabel("<html><u>" + text + "</u></html>");
        l.setFont(UITheme.font(java.awt.Font.PLAIN, 10));
        l.setForeground(UITheme.TEXT_DIM);
        l.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        l.setToolTipText("设为 " + text + " 杠杆");
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                setLeverage(value);
                if (listener != null) {
                    listener.onLeverage(leverage());
                }
            }
        });
        return l;
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    /** 当前杠杆（整数）。 */
    public int leverage() {
        return slider.getValue();
    }

    /** 设置杠杆（不触发回调）。 */
    public void setLeverage(int v) {
        suppress = true;
        try {
            slider.setValue(Math.max(slider.getMinimum(), Math.min(slider.getMaximum(), v)));
        } finally {
            suppress = false;
        }
        refresh();
    }

    private void refresh() {
        valueLabel.setText(leverage() + "x");
    }

    /** 上限（测试用）。 */
    public int maximum() {
        return slider.getMaximum();
    }

    /** 滑杆本体（测试用）。 */
    JSlider sliderForTest() {
        return slider;
    }
}