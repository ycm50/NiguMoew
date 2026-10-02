package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.util.Hashtable;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import tsim.json.JsonDeserializer.ClockState;

/**
 * 时间倍率面板：对数刻度滑杆（0.25x..256x）+ 开始/暂停/单步（1 片）/单步（1 天）
 * + 实时显示当前时间、每秒推进多少天、已运行时长。
 *
 * <p>滑杆即时调用 {@code clock set}；自动模式调用 {@code clock start}，
 * 界面刷新依赖引擎的 push tick 事件。</p>
 */
public final class ClockPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 协议 §3.14：speed ∈ [0.25, 256]。 */
    private static final double SPEED_MIN = 0.25;
    private static final double SPEED_MAX = 256.0;
    /** 协议 §3.14：tickMs ∈ [50, 60000]。 */
    private static final int TICK_MS_MIN = 50;
    private static final int TICK_MS_MAX = 60000;

    /** 时钟控制回调。 */
    public interface ClockHandler {
        /**
         * 设置倍速（即时生效）。
         *
         * @param speed   0.25..256
         * @param tickMs  基准片间隔（毫秒）
         */
        void onSetSpeed(double speed, int tickMs);

        /** 开始自动推进。 */
        void onStart(double speed, int tickMs);

        /** 停止自动推进。 */
        void onStop();

        /** 手动单步推进 n 个时间片。 */
        void onStep(int slots);
    }

    private final JSlider slider = new JSlider(0, 1000, speedToSlider(1.0));
    private final JLabel speedLabel = new JLabel(" ");
    private final JLabel timeLabel = new JLabel("游戏时间 -");
    private final JLabel rateLabel = new JLabel(" ");
    private final JLabel uptimeLabel = new JLabel("已运行 0秒");
    private final JLabel stateLabel = new JLabel("状态：已暂停");
    private final javax.swing.JComboBox<Integer> tickMsBox =
            new javax.swing.JComboBox<>(new Integer[]{50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000, 60000});

    /** 开始/暂停合并按钮（红绿切换）。 */
    private JButton startPauseButton;

    private ClockHandler handler;
    private boolean running;
    private long uptimeSeconds;
    private int tickMs = 500;
    private double speed = 1.0;
    private int lastAdvancedSlots;
    private double slotsPerSecond;
    /** 引擎回显的实际片间隔（ms），权威值。 */
    private double effectiveIntervalMs = 500;

    private final Timer uptimeTimer = new Timer(1000, e -> {
        if (running) {
            uptimeSeconds++;
            refreshUptime();
        }
    });

    /** 构造时钟面板。 */
    public ClockPanel() {
        super(new BorderLayout(10, 0));
        setBackground(UITheme.PANEL);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.WIDGET),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        slider.setBackground(UITheme.PANEL);
        slider.setForeground(UITheme.TEXT);
        slider.setMajorTickSpacing(125);
        slider.setMinorTickSpacing(25);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setFont(UITheme.SMALL_FONT);
        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        for (double s : new double[]{0.25, 0.5, 1, 2, 4, 8, 16, 32, 64, 128, 256}) {
            int pos = speedToSlider(s);
            JLabel l = new JLabel(formatSpeed(s));
            l.setFont(UITheme.font(Font.PLAIN, 9));
            l.setForeground(UITheme.TEXT_DIM);
            labels.put(Integer.valueOf(pos), l);
        }
        slider.setLabelTable(labels);
        slider.setToolTipText("拖动即时改变时钟倍速（对数刻度）");
        slider.addChangeListener(e -> {
            // 滑动即时生效：立刻下发 clock set（越界由 clampSpeed 收敛到协议区间）
            speed = clampSpeed(sliderToSpeed(slider.getValue()));
            refreshSpeedLabels();
            if (handler != null) {
                handler.onSetSpeed(speed, tickMs);
            }
        });

        tickMsBox.setFont(UITheme.SMALL_FONT);
        tickMsBox.setSelectedItem(Integer.valueOf(tickMs));
        tickMsBox.setToolTipText("引擎基准片间隔（毫秒）；实际片间隔 = tickMs / speed");
        tickMsBox.addActionListener(e -> {
            Object v = tickMsBox.getSelectedItem();
            if (v instanceof Integer) {
                tickMs = clampTickMs(((Integer) v).intValue());
                refreshSpeedLabels();
                if (handler != null) {
                    handler.onSetSpeed(speed, tickMs);
                }
            }
        });

        JPanel left = new JPanel(new GridBagLayout());
        left.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(1, 2, 1, 2);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.gridwidth = 2;
        left.add(slider, g);
        g.gridy++;
        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        info.setOpaque(false);
        speedLabel.setFont(UITheme.font(Font.BOLD, 14));
        speedLabel.setForeground(UITheme.ACCENT);
        rateLabel.setFont(UITheme.SMALL_FONT);
        rateLabel.setForeground(UITheme.TEXT_DIM);
        timeLabel.setFont(UITheme.UI_FONT);
        timeLabel.setForeground(UITheme.TEXT);
        stateLabel.setFont(UITheme.SMALL_FONT);
        stateLabel.setForeground(UITheme.WARN);
        info.add(speedLabel);
        info.add(rateLabel);
        info.add(timeLabel);
        info.add(stateLabel);
        g.gridy++;
        left.add(info, g);
        g.gridy++;
        JPanel second = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        second.setOpaque(false);
        JLabel base = new JLabel("基准片间隔");
        base.setFont(UITheme.SMALL_FONT);
        base.setForeground(UITheme.TEXT_DIM);
        second.add(base);
        second.add(tickMsBox);
        uptimeLabel.setFont(UITheme.SMALL_FONT);
        uptimeLabel.setForeground(UITheme.TEXT_DIM);
        second.add(uptimeLabel);
        g.gridy++;
        left.add(second, g);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        buttons.setOpaque(false);
        // 开始/暂停合并为一个按钮：暂停时显示绿色"▶ 开始"，运行中显示红色"⏸ 暂停"。
        // 同时支持空格键切换（在窗口任意位置按下均可）。
        startPauseButton = new JButton(new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                toggleStartPause();
            }
        });
        startPauseButton.setFont(UITheme.SMALL_FONT);
        startPauseButton.setMargin(new Insets(2, 10, 2, 10));
        startPauseButton.setToolTipText("开始 / 暂停自动推进（快捷键：空格）");
        startPauseButton.setPreferredSize(new Dimension(96, 28));
        buttons.add(startPauseButton);

        buttons.add(button("单步 1 片", () -> step(1)));
        buttons.add(button("单步 1 天", () -> step(4)));
        buttons.add(button("快进 1 周", () -> step(20)));

        // 低速预设：0.5 天/秒 等（用户要求）
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(presetButton("0.25天/秒", 0.2));
        buttons.add(presetButton("0.5天/秒", 0.4));
        buttons.add(presetButton("1天/秒", 0.8));
        buttons.add(presetButton("2天/秒", 1.6));
        buttons.add(Box.createHorizontalStrut(6));
        buttons.setPreferredSize(new Dimension(700, 60));

        add(left, BorderLayout.CENTER);
        add(buttons, BorderLayout.EAST);
        installSpaceKey(this);
        refreshStartPauseButton();
        refreshSpeedLabels();
        uptimeTimer.start();
    }

    /** 设置时钟回调。 */
    public void setClockHandler(ClockHandler h) {
        this.handler = h;
    }

    /** 开始/暂停切换（合并按钮 + 空格键共用）。 */
    private void toggleStartPause() {
        if (running) {
            stop();
        } else {
            start();
        }
    }

    /**
     * 低速预设按钮：按"天/秒"设定倍速。
     *
     * <p>1 个交易日 = 4 个时间片，故 `天/秒 = 片/秒 ÷ 4`，即 `倍速 = 天/秒 × 4`。</p>
     *
     * @param label   按钮文字
     * @param daysPerSecond 目标推进速度（天/秒）
     */
    private JButton presetButton(String label, double daysPerSecond) {
        JButton b = button(label, () -> {
            double target = clampSpeed(daysPerSecond * 4.0);
            // 设置滑杆即可；其 ChangeListener 会立即下发 clock set（协议 §3.14）
            slider.setValue(speedToSlider(target));
        });
        b.setToolTipText("将倍速设为约 " + label + "（≈ " + String.format("%.2f", daysPerSecond * 4.0) + "x）");
        return b;
    }

    /** 刷新开始/暂停按钮的文案与颜色（运行中 = 红，暂停 = 绿）。 */
    private void refreshStartPauseButton() {
        if (startPauseButton == null) {
            return;
        }
        if (running) {
            startPauseButton.setText("\u23F8 暂停");
            startPauseButton.setForeground(UITheme.DANGER);
            startPauseButton.setToolTipText("暂停自动推进（快捷键：空格）");
        } else {
            startPauseButton.setText("\u25B6 开始");
            startPauseButton.setForeground(UITheme.OK);
            startPauseButton.setToolTipText("开始自动推进（快捷键：空格）");
        }
    }

    /** 在窗口级别绑定空格键 = 开始/暂停。 */
    private void installSpaceKey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("SPACE"), "tsim-toggle-clock");
        target.getActionMap().put("tsim-toggle-clock", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                toggleStartPause();
            }
        });
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(new AbstractAction(text) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
        b.setFont(UITheme.SMALL_FONT);
        b.setMargin(new Insets(2, 8, 2, 8));
        return b;
    }

    /** 供菜单调用：开始自动推进。 */
    public void startPublic() {
        start();
    }

    /** 供菜单调用：暂停。 */
    public void stopPublic() {
        stop();
    }

    private void start() {
        speed = clampSpeed(speed);
        tickMs = clampTickMs(tickMs);
        running = true;
        stateLabel.setText("状态：自动推进中");
        stateLabel.setForeground(UITheme.OK);
        refreshStartPauseButton();
        if (handler != null) {
            handler.onStart(speed, tickMs);
        }
    }

    private void stop() {
        running = false;
        stateLabel.setText("状态：已暂停");
        stateLabel.setForeground(UITheme.WARN);
        refreshStartPauseButton();
        if (handler != null) {
            handler.onStop();
        }
    }

    private void step(int slots) {
        if (handler != null) {
            handler.onStep(slots);
        }
    }

    // ------------------------------------------------------------ 显示

    /** 更新当前时钟状态。 */
    public void update(ClockState st) {
        if (st == null) {
            return;
        }
        this.running = st.running;
        refreshStartPauseButton();
        this.speed = clampSpeed(st.speed);
        this.tickMs = clampTickMs(st.tickMs);
        // 引擎回显的 tickIntervalMs 才是"实际生效值"（可能被 clamp/取整），绝不用 tickMs/speed 重算
        this.effectiveIntervalMs = st.tickIntervalMs;
        this.slotsPerSecond = st.slotsPerSecond();
        slider.setValue(speedToSlider(this.speed));
        Object sel = tickMsBox.getSelectedItem();
        if (!(sel instanceof Integer) || ((Integer) sel).intValue() != this.tickMs) {
            tickMsBox.setSelectedItem(Integer.valueOf(this.tickMs));
        }
        timeLabel.setText("游戏时间 " + st.time);
        stateLabel.setText("状态：" + (st.running ? "自动推进中" : "已暂停"));
        stateLabel.setForeground(st.running ? UITheme.OK : UITheme.WARN);
        rateLabel.setText(String.format(java.util.Locale.ROOT,
                "片间隔 %.2f ms → %.2f 片/秒 → %.2f 天/秒（引擎实际生效值）",
                st.tickIntervalMs, st.slotsPerSecond(), st.daysPerSecond()));
        refreshSpeedLabels();
    }

    /** 由 tick/push 刷新游戏时间。 */
    public void setGameTime(String text) {
        timeLabel.setText("游戏时间 " + text);
    }

    /** 记录最近一次推进的片数（显示在速率行）。 */
    public void noteAdvanced(int slots) {
        lastAdvancedSlots = slots;
        refreshSpeedLabels();
    }

    /** 是否处于自动推进状态。 */
    public boolean isRunning() {
        return running;
    }

    /** 当前倍速。 */
    public double speed() {
        return speed;
    }

    /** 当前基准片间隔。 */
    public int tickMs() {
        return tickMs;
    }

    /** 滑杆当前值（测试用）。 */
    public int sliderValue() {
        return slider.getValue();
    }

    /** 已运行秒数（测试用）。 */
    public long uptime() {
        return uptimeSeconds;
    }

    private void refreshSpeedLabels() {
        speedLabel.setText("倍速 " + formatSpeed(speed));
        // 本地预估（下发前的即时反馈）；引擎回显到达后由 update() 用 effectiveIntervalMs 覆盖
        double est = effectiveIntervalMs > 0 ? effectiveIntervalMs : (tickMs / Math.max(0.0001, speed));
        double sps = est <= 0 ? 0 : 1000.0 / est;
        rateLabel.setText(String.format(java.util.Locale.ROOT,
                "片间隔 %.2f ms → %.2f 片/秒 → %.2f 天/秒", est, sps, sps / 4.0));
        if (lastAdvancedSlots > 0) {
            timeLabel.setToolTipText("上次推进 " + lastAdvancedSlots + " 片");
        }
    }

    private void refreshUptime() {
        uptimeLabel.setText("已运行 " + UITheme.duration(uptimeSeconds));
    }

    private static String formatSpeed(double s) {
        if (s == Math.rint(s)) {
            return String.valueOf((long) s) + "x";
        }
        return String.format(java.util.Locale.ROOT, "%.2fx", s);
    }

    /** 把倍速收敛到协议区间 [0.25, 256]。 */
    static double clampSpeed(double s) {
        if (Double.isNaN(s) || s <= 0) {
            return 1.0;
        }
        return Math.max(SPEED_MIN, Math.min(SPEED_MAX, s));
    }

    /** 把片间隔收敛到协议区间 [50, 60000] 毫秒。 */
    static int clampTickMs(int ms) {
        return Math.max(TICK_MS_MIN, Math.min(TICK_MS_MAX, ms));
    }

    /** 协议下限倍速。 */
    static double speedMin() {
        return SPEED_MIN;
    }

    /** 协议上限倍速。 */
    static double speedMax() {
        return SPEED_MAX;
    }

    /** 协议片间隔下限。 */
    static int tickMsMin() {
        return TICK_MS_MIN;
    }

    /** 协议片间隔上限。 */
    static int tickMsMax() {
        return TICK_MS_MAX;
    }

    /** 引擎回显的实际片间隔（ms）。 */
    double effectiveIntervalMs() {
        return effectiveIntervalMs;
    }

    /** 对数刻度：0..1000 -> 0.25..256。 */
    static int speedToSlider(double speed) {
        double s = Math.max(SPEED_MIN, Math.min(SPEED_MAX, speed));
        double t = (Math.log(s) - Math.log(SPEED_MIN)) / (Math.log(SPEED_MAX) - Math.log(SPEED_MIN));
        return (int) Math.round(t * 1000);
    }

    /** 对数刻度：0..1000 -> 0.25..256。 */
    static double sliderToSpeed(int value) {
        double t = Math.max(0, Math.min(1000, value)) / 1000.0;
        double s = Math.exp(Math.log(SPEED_MIN) + t * (Math.log(SPEED_MAX) - Math.log(SPEED_MIN)));
        return roundNice(s);
    }

    /** 吸附到易读的档位（0.05 精度，>=10 取整）。 */
    private static double roundNice(double s) {
        if (s >= 10) {
            return Math.round(s);
        }
        return Math.round(s * 20.0) / 20.0;
    }

    /**
     * 由片间隔算"天/秒"：天/秒 = (1000 / tickIntervalMs) / 4（协议 §3.14：4 slot = 1 交易日）。
     *
     * <p>仅当拿不到引擎回显的 tickIntervalMs 时，才用 tickMs/speed 做本地预估。</p>
     */
    public static double daysPerSecond(double speed, int tickMs) {
        double interval = tickMs / Math.max(0.0001, clampSpeed(speed));
        return interval <= 0 ? 0 : (1000.0 / interval) / 4.0;
    }

    /** 由引擎回显的实际片间隔算"天/秒"（权威口径）。 */
    public static double daysPerSecondFromInterval(double tickIntervalMs) {
        return tickIntervalMs <= 0 ? 0 : (1000.0 / tickIntervalMs) / 4.0;
    }

    /** 状态标签（测试用）。 */
    JLabel stateLabelForTest() {
        return stateLabel;
    }

    /** 倍速标签（测试用）。 */
    JLabel speedLabelForTest() {
        return speedLabel;
    }

    /** 面板默认尺寸。 */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, Math.max(96, d.height));
    }

    /** 居中态文本。 */
    @Override
    public String toString() {
        return "ClockPanel[" + speedLabel.getText() + "]";
    }

    /** 避免未使用告警：面板对齐常量。 */
    static int alignment() {
        return SwingConstants.LEFT;
    }
}