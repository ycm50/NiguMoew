package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
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

    private final JLabel speedLabel = new JLabel(" ");
    private final JLabel timeLabel = new JLabel("游戏时间 -");
    private final JLabel rateLabel = new JLabel(" ");
    private final JLabel uptimeLabel = new JLabel("已运行 0秒");
    /** 目标速率输入框（天/秒）。 */
    private JTextField rateField;
    private final JLabel stateLabel = new JLabel("状态：已暂停");
    private final javax.swing.JComboBox<Integer> tickMsBox =
            new javax.swing.JComboBox<>(new Integer[]{50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000, 60000});

    /** 开始/暂停合并按钮（红绿切换）。 */
    private JButton startPauseButton;

    private ClockHandler handler;
    private boolean running;
    private long uptimeSeconds;
    /** 默认基准片间隔 1000ms（用户要求）。 */
    private int tickMs = 1000;
    private double speed = 1.0;
    private int lastAdvancedSlots;
    private double slotsPerSecond;
    /** 引擎回显的实际片间隔（ms），权威值。 */
    private double effectiveIntervalMs = 1000;

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

        // 倍速唯一的入口是右侧的「倍速输入框 + 应用」。
        // 早期版本还有一个对数滑杆，滑杆的 ChangeListener 会在 setValue() 时回写 speed，
        // 与输入框互相覆盖（输入 1 却显示 0.85x），故已移除，避免出现两个真相来源。
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
        JLabel hint = new JLabel("倍速：在右侧输入框填写后按回车或点「应用」");
        hint.setFont(UITheme.SMALL_FONT);
        hint.setForeground(UITheme.TEXT_DIM);
        left.add(hint, g);
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

        // 倍速输入框：直接填倍速（x），按回车或点「应用」生效（用户要求统一成倍速）
        rateField = new JTextField(6);
        rateField.setFont(UITheme.SMALL_FONT);
        rateField.setToolTipText("输入倍速（" + SPEED_MIN + " ~ " + SPEED_MAX + "），回车或点「应用」生效");
        rateField.addActionListener(e -> applyRateInput());
        buttons.add(rateField);

        JLabel unit = new JLabel("x");
        unit.setFont(UITheme.SMALL_FONT);
        unit.setForeground(UITheme.TEXT);
        buttons.add(unit);

        buttons.add(button("应用", this::applyRateInput));
        buttons.setPreferredSize(new Dimension(720, 60));

        add(left, BorderLayout.CENTER);
        add(buttons, BorderLayout.EAST);
        installSpaceKey(this);
        refreshStartPauseButton();
        rateField.setText(formatSpeedForInput(speed));
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
     * 应用倍速输入框：用户填的就是**倍速本身**（x），直接下发，不做单位换算。
     *
     * <p>早期的"天/秒"输入需要 `倍速 = 天/秒 × 4 × tickMs / 1000` 的换算，
     * 而换算结果一旦低于协议下限 `0.25x` 就会被夹住 —— 用户填 0.5 却得到 1.25，
     * 看起来像 bug。现在统一以**倍速**为唯一单位，填多少就是多少，不会再有这种事。</p>
     *
     * <p>倍速与"天/秒"的关系（协议 §3.14）仍然展示在读数行里供参考：
     * `天/秒 = (1000 / (tickMs / 倍速)) / 4`。</p>
     */
    private void applyRateInput() {
        String raw = rateField.getText() == null ? "" : rateField.getText().trim();
        if (raw.isEmpty()) {
            Dialogs.warn(this, "请输入倍速，例如 0.5 或 64。");
            rateField.setText(formatSpeedForInput(speed));
            return;
        }
        // 允许用户连"x"一起输入，如 "0.5x" / "64X"
        if (raw.endsWith("x") || raw.endsWith("X")) {
            raw = raw.substring(0, raw.length() - 1).trim();
        }
        double want;
        try {
            want = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            Dialogs.warn(this, "\"" + rateField.getText() + "\" 不是合法数字。请输入如 0.5 或 64 的倍速。");
            rateField.setText(formatSpeedForInput(speed));
            return;
        }
        if (!(want > 0) || Double.isInfinite(want) || Double.isNaN(want)) {
            Dialogs.warn(this, "倍速必须大于 0。");
            rateField.setText(formatSpeedForInput(speed));
            return;
        }

        // 超出协议区间时夹住并如实提示（区间来自 PROTOCOL.md §3.14）
        double target = clampSpeed(want);
        speed = target;
        refreshSpeedLabels();
        rateField.setText(formatSpeedForInput(target));
        if (handler != null) {
            handler.onSetSpeed(speed, tickMs);
        }

        if (Math.abs(target - want) > 1e-9) {
            Dialogs.info(this,
                    "协议允许的倍速区间是 [" + SPEED_MIN + ", " + SPEED_MAX + "]x，"
                            + UITheme.price(want, 4) + "x 已超出。\n\n"
                            + "已按 " + formatSpeedForInput(target) + " 应用（此时 "
                            + rateLabelText(target) + "）。");
        }
    }

    /** 输入框显示用：简洁的倍速文本（不带 x 后缀，单位在框外）。 */
    static String formatSpeedForInput(double s) {
        if (s == Math.rint(s)) {
            return String.valueOf((long) s);
        }
        return String.format(java.util.Locale.ROOT, "%s", trimZeros(UITheme.price(s, 3)));
    }

    /** 去掉小数末尾多余的 0，如 "0.500" -> "0.5"。 */
    private static String trimZeros(String s) {
        if (s.indexOf('.') < 0) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    /** 形如 "片间隔 50.00 ms → 20.00 片/秒 → 5.00 天/秒"。 */
    private String rateLabelText(double spd) {
        double interval = tickMs / Math.max(0.0001, spd);
        double sps = interval <= 0 ? 0 : 1000.0 / interval;
        return String.format(java.util.Locale.ROOT,
                "片间隔 %.2f ms → %.2f 片/秒 → %.2f 天/秒", interval, sps, sps / 4.0);
    }

    /** 给定倍速与基准片间隔时，每秒推进多少个时间片。 */
    static double slotsPerSecondFor(double speed, double baseMs) {
        if (baseMs <= 0) {
            return 0;
        }
        return speed * 1000.0 / baseMs;
    }

    /**
     * 为指定"天/秒"推荐一个基准片间隔，使所需倍速落在协议区间 [0.25, 256] 内。
     *
     * <p>`speed = 天/秒 × 4 × tickMs / 1000`，令其 &gt;= 0.25 得 `tickMs &gt;= 62.5 / 天/秒`。</p>
     */
    static int suggestedTickMs(double daysPerSecond) {
        int[] allowed = {50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000, 60000};
        double need = Math.ceil(62.5 / Math.max(daysPerSecond, 1e-9));
        for (int a : allowed) {
            if (a >= need) {
                return a;
            }
        }
        return 60000;
    }

    /**
     * 把"天/秒"换算成引擎倍速。
     *
     * <pre>倍速 = 天/秒 × 4 × tickMs / 1000</pre>
     *
     * @param daysPerSecond 目标推进速度（天/秒）
     * @param baseMs        基准片间隔（毫秒）
     * @return 引擎倍速（未做区间收敛）
     */
    static double daysPerSecondToSpeed(double daysPerSecond, double baseMs) {
        return daysPerSecond * 4.0 * baseMs / 1000.0;
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
        seedRateFieldIfEmpty(speed);
    }

    /** 输入框为空时，用当前倍速做占位提示（不覆盖用户已输入的值）。 */
    private void seedRateFieldIfEmpty(double spd) {
        if (rateField == null) {
            return;
        }
        if (rateField.getText() == null || rateField.getText().isBlank()) {
            rateField.setText(formatSpeedForInput(spd));
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