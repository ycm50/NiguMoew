package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;

import tsim.json.Json;
import tsim.json.JsonDeserializer.CheatItem;
import tsim.json.JsonDeserializer.CheatState;

/**
 * 作弊器面板：每个作弊项一个控件（按钮 / 输入框 / 勾选框），顶部为红色合规横幅。
 *
 * <p>所有作弊项都是纯模拟功能，不涉及任何真实资金。</p>
 */
public final class CheatPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 作弊请求回调。 */
    public interface CheatHandler {
        /**
         * 执行作弊操作。
         *
         * @param args 至少包含 op 的参数字段
         */
        void onCheat(Map<String, Object> args);
    }

    private CheatHandler handler;

    // 注入/设置资金
    private final JTextField moneyAmount = new JTextField("1000000", 10);
    private final JComboBox<String> moneyAccount = new JComboBox<>(new String[]{"股票", "外汇", "两者"});
    private final JTextField setCashValue = new JTextField("5000000", 10);
    private final JComboBox<String> setCashAccount = new JComboBox<>(new String[]{"股票", "外汇"});
    // 价格
    /**
     * 「② 行情控制」整段共用的代码框（改价 / 拉砸 / 停牌 / 按比例涨跌都用它）。
     *
     * <p>原先每个功能各有一个「代码」框，四个框要重复填同一个代码，很啰嗦；
     * 而且 Swing 组件只能有一个父容器，一旦某个实例被 add 进两行，
     * 第二次 add 会把它从第一行移走（曾导致「强制改价到」一行输入框消失）。
     * 现在收成一个共享字段，放在该段标题下面，只填一次。</p> */
    private final JTextField marketSymbol = new JTextField(9);
    private final JTextField priceTo = new JTextField(8);
    private final JTextField pricePct = new JTextField(8);
    private final JTextField pumpPct = new JTextField("0.1", 6);
    private final JTextField pumpBars = new JTextField("5", 4);
    // 停牌（代码框用上面的共享 marketSymbol）
    private final JCheckBox freezeOn = new JCheckBox("停牌（取消勾选=复牌）", true);
    // T+1
    private final JTextField unlockSymbol = new JTextField(9);
    private final JCheckBox t1Enabled = new JCheckBox("启用 T+1 规则", true);
    // 开关
    private final JCheckBox infiniteMoney = new JCheckBox("无限资金（买入永不因资金失败）");
    private final JCheckBox godMode = new JCheckBox("上帝模式（免保证金/免手续费/永不爆仓）");
    private final JCheckBox noCommission = new JCheckBox("免手续费");
    private final JCheckBox perfectInfo = new JCheckBox("透视（显示隐藏信息/未来计划）");
    private final JCheckBox fillAll = new JCheckBox("全部成交（立即吃掉所有挂单）");
    // 胜率 / 种子 / 快进 / 新闻
    private final JSpinner winRate = new JSpinner(new SpinnerNumberModel(50, 0, 100, 5));
    private final JTextField seedField = new JTextField("12345", 10);
    private final JSpinner skipSlots = new JSpinner(new SpinnerNumberModel(40, 1, 20000, 10));
    private final JCheckBox skipAuto = new JCheckBox("自动模式", true);
    private final JTextField newsTitle = new JTextField(16);
    private final JTextField newsImpact = new JTextField("0.05", 6);
    private final JComboBox<String> newsScope = new JComboBox<>(new String[]{"股票", "外汇", "宏观"});
    private final JTextField newsSymbols = new JTextField(12);
    private final JComboBox<String> bankruptAccount = new JComboBox<>(new String[]{"股票", "外汇"});
    private final JTextArea stateArea = new JTextArea(4, 30);
    private final JPanel dynamicList = new JPanel(new GridBagLayout());

    /** 构造作弊器面板。 */
    public CheatPanel() {
        super(new BorderLayout());
        setBackground(UITheme.PANEL);

        JLabel banner = new JLabel(UITheme.CHEAT_BANNER, SwingConstants.CENTER);
        banner.setFont(UITheme.font(Font.BOLD, 15));
        banner.setOpaque(true);
        banner.setBackground(UITheme.DANGER);
        banner.setForeground(Color.WHITE);
        banner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(banner, BorderLayout.NORTH);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(UITheme.PANEL);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 6, 3, 6);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.gridwidth = 2;

        content.add(section("① 资金"), g);
        g.gridy++;
        content.add(line("注入资金", moneyAmount, new JLabel("账户"), moneyAccount, button("执行注入", this::doMoney)), g);
        g.gridy++;
        content.add(line("设置资金为", setCashValue, new JLabel("账户"), setCashAccount, button("执行设置", this::doSetCash)), g);
        g.gridy++;
        content.add(checks(infiniteMoney, godMode, noCommission, perfectInfo), g);
        g.gridy++;
        content.add(line(button("立即应用上方勾选", this::applyToggles), button("重置资金/持仓", this::doReset)), g);

        g.gridy++;
        content.add(section("② 行情控制"), g);
        g.gridy++;
        // 整段共用一个代码框：下面的改价 / 拉砸 / 停牌 / 按比例涨跌都作用于它
        content.add(line("代码（以下操作都作用于它）", marketSymbol,
                new JLabel("留空=全部（停牌/涨跌比例支持留空）")), g);
        g.gridy++;
        content.add(line("强制改价到", priceTo, button("改价", this::doPriceTo)), g);
        g.gridy++;
        content.add(line("涨跌幅", pumpPct, "持续片数", pumpBars, button("拉盘/砸盘", this::doPump)), g);
        g.gridy++;
        content.add(line("", freezeOn, button("应用停牌/复牌", this::doFreeze)), g);
        g.gridy++;
        content.add(line("涨跌比例", pricePct, button("按比例涨跌", this::doPricePct)), g);

        g.gridy++;
        content.add(section("③ T+1"), g);
        g.gridy++;
        content.add(line("代码", unlockSymbol, new JLabel("留空=解锁全部"), button("解锁 T+1", this::doUnlock)), g);
        // 注：T+1 解锁的代码框与 ② 段分开（解锁对象常与改价对象不同），保持独立。
        g.gridy++;
        content.add(line(t1Enabled, button("应用 T+1 开关", this::applyToggles)), g);

        g.gridy++;
        content.add(section("④ 交易辅助"), g);
        g.gridy++;
        content.add(line(new JLabel("胜率 %"), winRate, fillAll, button("应用", this::applyToggles)), g);
        g.gridy++;
        content.add(line("种子", seedField, button("重置行情种子", this::doSeed), button("查看当前种子", this::doRevealSeed)), g);

        g.gridy++;
        content.add(section("⑤ 快进"), g);
        g.gridy++;
        content.add(line(new JLabel("快进片数"), skipSlots, skipAuto, button("开始快进", this::doSkip)), g);

        g.gridy++;
        content.add(section("⑥ 新闻与破产"), g);
        g.gridy++;
        content.add(line("标题", newsTitle, "影响", newsImpact, new JLabel("范围"), newsScope), g);
        g.gridy++;
        content.add(line("相关代码(逗号分隔)", newsSymbols, button("发布新闻", this::doNews)), g);
        g.gridy++;
        content.add(line(new JLabel("破产账户"), bankruptAccount, button("强制破产", this::doBankrupt),
                button("解除破产", this::doUnbankrupt)), g);

        g.gridy++;
        content.add(section("⑦ 引擎返回的全部作弊项"), g);
        g.gridy++;
        content.add(line(button("列出全部作弊项", this::doList), button("刷新状态", this::doState)), g);
        g.gridy++;
        dynamicList.setBackground(UITheme.PANEL);
        content.add(dynamicList, g);

        g.gridy++;
        g.weighty = 1;
        content.add(javax.swing.Box.createVerticalGlue(), g);

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        add(sp, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        stateArea.setEditable(false);
        stateArea.setFont(UITheme.SMALL_FONT);
        stateArea.setBackground(UITheme.PANEL_DARK);
        stateArea.setForeground(UITheme.TEXT_DIM);
        stateArea.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        stateArea.setText("作弊状态：尚未获取（点「刷新状态」读取 cheatState）");
        south.add(new JScrollPane(stateArea), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        // 初始勾选与实际状态无关，仅作 UI 默认值；点"应用"才生效
        t1Enabled.setSelected(true);
        t1Enabled.setOpaque(false);
        t1Enabled.setFont(UITheme.SMALL_FONT);
        t1Enabled.setForeground(UITheme.TEXT);
        styleChecks();
    }

    private void styleChecks() {
        for (JCheckBox c : new JCheckBox[]{freezeOn, infiniteMoney, godMode, noCommission, perfectInfo,
                fillAll, skipAuto}) {
            c.setFont(UITheme.SMALL_FONT);
            c.setOpaque(false);
            c.setForeground(UITheme.TEXT);
        }
        for (JTextField f : new JTextField[]{moneyAmount, setCashValue, marketSymbol, priceTo, pricePct,
                pumpPct, pumpBars, unlockSymbol, seedField, newsTitle, newsImpact, newsSymbols}) {
            f.setFont(UITheme.SMALL_FONT);
            f.setBackground(UITheme.PANEL_DARK);
            f.setForeground(UITheme.TEXT);
            f.setCaretColor(UITheme.TEXT);
            f.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UITheme.WIDGET),
                    BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        }
        for (JComboBox<?> c : new JComboBox<?>[]{moneyAccount, setCashAccount, newsScope, bankruptAccount}) {
            c.setFont(UITheme.SMALL_FONT);
            c.setBackground(UITheme.PANEL_DARK);
            c.setForeground(UITheme.TEXT);
        }
        winRate.setFont(UITheme.SMALL_FONT);
        skipSlots.setFont(UITheme.SMALL_FONT);
    }

    private JLabel section(String text) {
        JLabel l = new JLabel(text);
        l.setFont(UITheme.font(Font.BOLD, 14));
        l.setForeground(UITheme.ACCENT);
        l.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.WIDGET));
        return l;
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

    private JPanel line(Object... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        for (Object o : items) {
            if (o instanceof String) {
                JLabel l = new JLabel((String) o);
                l.setFont(UITheme.SMALL_FONT);
                l.setForeground(UITheme.TEXT_DIM);
                p.add(l);
            } else if (o instanceof java.awt.Component) {
                p.add((java.awt.Component) o);
            }
        }
        return p;
    }

    private JPanel checks(JCheckBox... boxes) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        for (JCheckBox b : boxes) {
            p.add(b);
        }
        return p;
    }

    /** 设置作弊回调。 */
    public void setCheatHandler(CheatHandler h) {
        this.handler = h;
    }

    // ------------------------------------------------------------ 操作

    private void send(Map<String, Object> args) {
        if (handler != null) {
            handler.onCheat(args);
        }
    }

    private void doMoney() {
        send(Json.obj("op", "money", "amount", parseDouble(moneyAmount.getText(), 1_000_000),
                "account", accountCode(moneyAccount.getSelectedIndex(), true)));
    }

    private void doSetCash() {
        send(Json.obj("op", "setCash", "value", parseDouble(setCashValue.getText(), 1_000_000),
                "account", accountCode(setCashAccount.getSelectedIndex(), false)));
    }

    private void doReset() {
        send(Json.obj("op", "reset"));
    }

    /**
     * 取共享代码框里的代码；为空时给出明确提示并返回 null。
     *
     * <p>这四项操作（改价 / 按比例涨跌 / 拉砸 / 停牌）引擎都**必须**要有 symbol，
     * 为空会直接回 {@code BAD_ARG: 参数错误: 缺少 symbol}。此前没有本地校验，
     * 用户漏填就会看到一个引擎报错弹窗（体验上像程序出错）。
     * 现在在客户端先拦一次，提示该填什么。只有「解锁 T+1」允许留空。</p>
     */
    private String requireSymbol(String what) {
        String sym = marketSymbol.getText() == null ? "" : marketSymbol.getText().trim();
        if (sym.isEmpty()) {
            Dialogs.warn(this, "请先在「② 行情控制」顶部填写代码，再执行「" + what + "」。\n\n"
                    + "例如 SH600519（股票）或 USDJPY（外汇）。\n"
                    + "（只有「解锁 T+1」可以留空，表示解锁全部）");
            marketSymbol.requestFocusInWindow();
            return null;
        }
        return sym;
    }

    /**
     * 由外部（导航：行情表选中某标的时）同步共享代码框。
     *
     * <p>只在该框为空或内容等于上一次自动填入值时覆盖，避免把用户手输的代码冲掉。</p>
     */
    public void setMarketSymbol(String symbol) {
        String s = symbol == null ? "" : symbol.trim();
        if (s.isEmpty()) {
            return;
        }
        String cur = marketSymbol.getText() == null ? "" : marketSymbol.getText().trim();
        if (cur.isEmpty() || cur.equals(autoFilledSymbol)) {
            marketSymbol.setText(s);
            marketSymbol.setCaretPosition(0);
        }
        autoFilledSymbol = s;
    }

    /** 上一次由选中标的自动填入的代码（用于判断能否安全覆盖）。 */
    private String autoFilledSymbol = "";

    private void doPriceTo() {
        String sym = requireSymbol("强制改价");
        if (sym == null) {
            return;
        }
        send(Json.obj("op", "price", "symbol", sym,
                "to", parseDouble(priceTo.getText(), 0)));
    }

    private void doPricePct() {
        String sym = requireSymbol("按比例涨跌");
        if (sym == null) {
            return;
        }
        send(Json.obj("op", "price", "symbol", sym,
                "pct", parseDouble(pricePct.getText(), 0)));
    }

    private void doPump() {
        String sym = requireSymbol("拉盘/砸盘");
        if (sym == null) {
            return;
        }
        send(Json.obj("op", "pump", "symbol", sym,
                "pct", parseDouble(pumpPct.getText(), 0.1),
                "bars", Math.round(parseDouble(pumpBars.getText(), 5))));
    }

    private void doFreeze() {
        String sym = requireSymbol("停牌/复牌");
        if (sym == null) {
            return;
        }
        send(Json.obj("op", "freeze", "symbol", sym,
                "halted", Boolean.valueOf(freezeOn.isSelected())));
    }

    private void doUnlock() {
        Map<String, Object> m = Json.obj("op", "unlock");
        String sym = unlockSymbol.getText().trim();
        if (!sym.isEmpty()) {
            m.put("symbol", sym);
        }
        send(m);
    }

    private void applyToggles() {
        Map<String, Object> m = Json.obj("op", "t1", "enabled", Boolean.valueOf(t1Enabled.isSelected()));
        send(m);
        send(Json.obj("op", "infiniteMoney", "enabled", Boolean.valueOf(infiniteMoney.isSelected())));
        send(Json.obj("op", "godMode", "enabled", Boolean.valueOf(godMode.isSelected())));
        send(Json.obj("op", "noCommission", "enabled", Boolean.valueOf(noCommission.isSelected())));
        send(Json.obj("op", "perfectInfo", "enabled", Boolean.valueOf(perfectInfo.isSelected())));
        send(Json.obj("op", "winRate", "value", ((Number) winRate.getValue()).doubleValue() / 100.0));
        if (fillAll.isSelected()) {
            send(Json.obj("op", "fillOrders", "all", Boolean.TRUE));
        }
    }

    private void doSeed() {
        send(Json.obj("op", "seed", "seed", Math.round(parseDouble(seedField.getText(), 12345))));
    }

    private void doRevealSeed() {
        send(Json.obj("op", "revealSeed"));
    }

    private void doSkip() {
        send(Json.obj("op", "skip", "slots", ((Number) skipSlots.getValue()).longValue(),
                "auto", Boolean.valueOf(skipAuto.isSelected())));
    }

    private void doNews() {
        List<Object> symbols = new ArrayList<>();
        for (String s : newsSymbols.getText().split("[,，\\s]+")) {
            if (!s.trim().isEmpty()) {
                symbols.add(s.trim());
            }
        }
        Map<String, Object> m = Json.obj("op", "news",
                "title", newsTitle.getText().trim(),
                "impact", parseDouble(newsImpact.getText(), 0.05),
                "scope", scopeCode(newsScope.getSelectedIndex()));
        if (!symbols.isEmpty()) {
            m.put("symbols", symbols);
        }
        send(m);
    }

    private void doBankrupt() {
        send(Json.obj("op", "bankrupt", "account", accountCode(bankruptAccount.getSelectedIndex(), false)));
    }

    private void doUnbankrupt() {
        send(Json.obj("op", "unbankrupt"));
    }

    private void doList() {
        send(Json.obj("op", "list"));
    }

    private void doState() {
        send(Json.obj("op", "list"));
    }

    /** 显示 engine 返回的全部作弊项（op=list 的结果）。 */
    public void setCheatItems(List<CheatItem> items) {
        dynamicList.removeAll();
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0;
        g.weightx = 1;
        if (items == null || items.isEmpty()) {
            JLabel l = new JLabel("（引擎未返回作弊项列表，或引擎不支持 op=list）");
            l.setFont(UITheme.SMALL_FONT);
            l.setForeground(UITheme.WARN);
            dynamicList.add(l, g);
        } else {
            int row = 0;
            for (CheatItem it : items) {
                JLabel l = new JLabel((row + 1) + ". " + it.label + "（op=" + it.op + "）  " + it.desc
                        + (it.args.isEmpty() ? "" : "  参数: " + it.args));
                l.setFont(UITheme.SMALL_FONT);
                l.setForeground(UITheme.TEXT_DIM);
                l.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
                g.gridy = row++;
                dynamicList.add(l, g);
            }
        }
        dynamicList.revalidate();
        dynamicList.repaint();
        setPreferredSize(getPreferredSize());
    }

    /** 显示当前作弊状态（cheatState）。 */
    public void setState(CheatState s) {
        if (s == null) {
            return;
        }
        stateArea.setText(String.format(java.util.Locale.ROOT,
                "作弊状态  T+1=%s  无限资金=%s  上帝模式=%s  免手续费=%s  透视=%s  胜率=%.0f%%  种子=%d",
                s.t1 ? "开" : "关", s.infiniteMoney ? "开" : "关", s.godMode ? "开" : "关",
                s.noCommission ? "开" : "关", s.perfectInfo ? "开" : "关", s.winRate * 100, s.seed));
        // 回写控件（保持界面与实际一致）
        t1Enabled.setSelected(s.t1);
        infiniteMoney.setSelected(s.infiniteMoney);
        godMode.setSelected(s.godMode);
        noCommission.setSelected(s.noCommission);
        perfectInfo.setSelected(s.perfectInfo);
        winRate.setValue(Integer.valueOf((int) Math.round(s.winRate * 100)));
        seedField.setText(String.valueOf(s.seed));
    }

    /** 显示一行结果文本（引擎返回的 detail）。 */
    public void setDetail(String text) {
        stateArea.setText(text == null ? "" : text);
    }

    /** 显示 cheatState 原始 JSON（引擎只回 cheatState 时用）。 */
    public void setCheatStateRaw(Map<String, Object> cheatState) {
        setState(CheatState.of(cheatState));
    }

    /** 显示动态作弊项（由 MainFrame 从 op=list 的 data 中解析）。 */
    public void setCheatItemsRaw(List<Object> raw) {
        List<CheatItem> items = new ArrayList<>();
        for (Object o : raw) {
            items.add(CheatItem.of(Json.asObject(o)));
        }
        setCheatItems(items);
    }

    /** 当前所有作弊项控件的数量（测试用）。 */
    public int controlCount() {
        return getComponentCount() + dynamicList.getComponentCount();
    }

    /** 作弊器横幅文本（测试用）。 */
    public static String bannerText() {
        return UITheme.CHEAT_BANNER;
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static String accountCode(int idx, boolean allowBoth) {
        if (idx == 1) {
            return "forex";
        }
        if (idx == 2 && allowBoth) {
            return "both";
        }
        return "stock";
    }

    private static String scopeCode(int idx) {
        switch (idx) {
            case 1:
                return "forex";
            case 2:
                return "macro";
            default:
                return "stock";
        }
    }

    /** 未使用的便捷方法：把 Map 转成一行中文（调试用）。 */
    static String oneLine(Map<String, Object> m) {
        return new LinkedHashMap<>(m).toString();
    }

    /** 组件尺寸建议。 */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(560, d.width), Math.max(300, d.height));
    }
}
