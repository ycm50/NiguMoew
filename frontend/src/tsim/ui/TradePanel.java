package tsim.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import tsim.json.JsonDeserializer.ForexQuote;
import tsim.json.JsonDeserializer.StockQuote;

/**
 * 下单面板：股票（买/卖、100 整数倍校验、市价/限价、快捷仓位）与
 * 外汇（做多/做空、手数、杠杆、止损止盈）两套界面，用 {@link CardLayout} 切换。
 */
public final class TradePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String CARD_STOCK = "stock";
    private static final String CARD_FOREX = "forex";

    /** 下单请求回调（由 MainFrame 实现，负责调用引擎）。 */
    public interface OrderHandler {
        /**
         * 股票下单。
         *
         * @param side  buy / sell
         * @param type  market / limit
         * @param qty   数量（100 的整数倍）
         * @param price 限价（市价时忽略）
         */
        void onStockOrder(String side, String type, int qty, double price);

        /**
         * 外汇开仓。
         *
         * @param side       long / short
         * @param lots       手数
         * @param leverage   杠杆
         * @param stopLoss   止损价（0=不设）
         * @param takeProfit 止盈价（0=不设）
         */
        void onForexOpen(String side, int lots, int leverage, double stopLoss, double takeProfit);
    }

    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private String currentCard = CARD_STOCK;
    private final JLabel titleLabel = new JLabel("未选择标的");
    private final JLabel priceLabel = new JLabel("--");
    private final JLabel extraLabel = new JLabel(" ");

    private OrderHandler handler;

    // ---- 股票控件
    private final JComboBox<String> stockType = new JComboBox<>(new String[]{"市价", "限价"});
    private final JTextField stockPrice = new JTextField(9);
    private final JSpinner stockQty = new JSpinner(new SpinnerNumberModel(100, 100, 100_000_000, 100));
    private final JCheckBox qtyAutoFill = new JCheckBox("自动填最大", true);
    private final JLabel qtyHint = new JLabel(" ");
    private final JLabel stockInfo = new JLabel(" ");
    /** 持仓与盈亏（用户要求：右侧面板显示盈亏）。 */
    private final JLabel pnlInfo = new JLabel(" ");
    private double stockLast;
    private double stockBuyingPower;
    private int stockSellable;
    // 当前标的的持仓上下文（由 MainFrame 注入）
    private int posQty;
    private double posAvgCost;
    private double posPnl;
    private double posPnlPct;
    private int posTodayBought;

    // ---- 外汇控件
    private final JComboBox<String> forexLeverage = new JComboBox<>(new String[]{"10", "20", "50", "100", "200", "500"});
    private final JSpinner forexLots = new JSpinner(new SpinnerNumberModel(1, 1, 10_000, 1));
    private final JTextField forexStop = new JTextField(8);
    private final JTextField forexTake = new JTextField(8);
    private final JLabel forexInfo = new JLabel(" ");
    private double forexLast;
    private double forexFreeMargin;

    private String symbol = "";
    private String name = "";

    /** 供 MainFrame 查询当前是否选中某个标的。 */
    public String currentSymbol() {
        return symbol;
    }

    /** 构造下单面板。 */
    public TradePanel() {
        super(new BorderLayout());
        setBackground(UITheme.PANEL);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, UITheme.WIDGET),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        setPreferredSize(new Dimension(320, 520));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        titleLabel.setFont(UITheme.font(Font.BOLD, 15));
        titleLabel.setForeground(UITheme.TEXT);
        priceLabel.setFont(UITheme.font(Font.BOLD, 20));
        priceLabel.setForeground(UITheme.TEXT);
        priceLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        head.add(titleLabel, BorderLayout.WEST);
        head.add(priceLabel, BorderLayout.EAST);
        extraLabel.setFont(UITheme.SMALL_FONT);
        extraLabel.setForeground(UITheme.TEXT_DIM);
        head.add(extraLabel, BorderLayout.SOUTH);

        body.setOpaque(false);
        body.add(buildStockCard(), CARD_STOCK);
        body.add(buildForexCard(), CARD_FOREX);

        JLabel notice = new JLabel(UITheme.SIM_NOTICE);
        notice.setFont(UITheme.SMALL_FONT);
        notice.setForeground(UITheme.WARN);
        notice.setHorizontalAlignment(SwingConstants.CENTER);
        notice.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        add(head, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(notice, BorderLayout.SOUTH);

        bindListeners();
        setStockContext(0, 0, 0, 2);
    }

    /** 设置回调。 */
    public void setOrderHandler(OrderHandler h) {
        this.handler = h;
    }

    // ------------------------------------------------------------ 卡片构建

    private JPanel buildStockCard() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 2, 4, 2);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0;
        g.gridy = 0;
        g.gridwidth = 2;

        style(stockType);
        style(stockPrice);
        styleSpinner(stockQty);

        p.add(row("类型", stockType), g);
        g.gridy++;
        p.add(row("价格", stockPrice), g);
        g.gridy++;
        p.add(row("数量", stockQty), g);
        g.gridy++;

        JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        quick.setOpaque(false);
        quick.add(quickButton("1/4仓", () -> fillQty(0.25)));
        quick.add(quickButton("1/3仓", () -> fillQty(1.0 / 3.0)));
        quick.add(quickButton("1/2仓", () -> fillQty(0.5)));
        quick.add(quickButton("全仓", () -> fillQty(1.0)));
        g.gridy++;
        p.add(quick, g);
        g.gridy++;

        JPanel sellQuick = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        sellQuick.setOpaque(false);
        sellQuick.add(quickButton("全平可卖", this::fillSellable));
        sellQuick.add(quickButton("清空", () -> stockQty.setValue(Integer.valueOf(100))));
        qtyAutoFill.setFont(UITheme.SMALL_FONT);
        qtyAutoFill.setOpaque(false);
        qtyAutoFill.setForeground(UITheme.TEXT_DIM);
        sellQuick.add(qtyAutoFill);
        g.gridy++;
        p.add(sellQuick, g);
        g.gridy++;

        qtyHint.setFont(UITheme.SMALL_FONT);
        qtyHint.setForeground(UITheme.TEXT_DIM);
        g.gridy++;
        p.add(qtyHint, g);
        g.gridy++;

        stockInfo.setFont(UITheme.SMALL_FONT);
        stockInfo.setForeground(UITheme.TEXT_DIM);
        g.gridy++;
        p.add(stockInfo, g);
        g.gridy++;

        // 持仓 / 盈亏（用户要求：右侧显示盈亏）
        pnlInfo.setFont(UITheme.SMALL_FONT);
        pnlInfo.setForeground(UITheme.TEXT_DIM);
        pnlInfo.setToolTipText("当前标的的持仓数量、持仓均价与浮动盈亏（含 T+1 冻结提示）");
        g.gridy++;
        p.add(pnlInfo, g);
        g.gridy++;

        g.gridy++;
        p.add(Box.createVerticalStrut(10), g);
        g.gridy++;

        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        buttons.setOpaque(false);
        JButton buy = new JButton(new AbstractAction("买 入") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                submitStock("buy");
            }
        });
        styleBig(buy, UITheme.upColor());
        JButton sell = new JButton(new AbstractAction("卖 出") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                submitStock("sell");
            }
        });
        styleBig(sell, UITheme.downColor());
        buttons.add(buy);
        buttons.add(sell);
        g.gridy++;
        p.add(buttons, g);

        g.gridy++;
        g.weighty = 1.0;
        p.add(Box.createVerticalGlue(), g);
        return p;
    }

    private JPanel buildForexCard() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 2, 4, 2);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0;
        g.gridy = 0;
        g.gridwidth = 2;

        style(forexLeverage);
        style(forexStop);
        style(forexTake);
        styleSpinner(forexLots);

        p.add(row("杠杆", forexLeverage), g);
        g.gridy++;
        p.add(row("手数", forexLots), g);
        g.gridy++;
        p.add(row("止损价", forexStop), g);
        g.gridy++;
        p.add(row("止盈价", forexTake), g);
        g.gridy++;

        JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        quick.setOpaque(false);
        quick.add(quickButton("1 手", () -> forexLots.setValue(Integer.valueOf(1))));
        quick.add(quickButton("5 手", () -> forexLots.setValue(Integer.valueOf(5))));
        quick.add(quickButton("10 手", () -> forexLots.setValue(Integer.valueOf(10))));
        quick.add(quickButton("清空止损止盈", this::clearForexStops));
        g.gridy++;
        p.add(quick, g);
        g.gridy++;

        forexInfo.setFont(UITheme.SMALL_FONT);
        forexInfo.setForeground(UITheme.TEXT_DIM);
        g.gridy++;
        p.add(forexInfo, g);
        g.gridy++;

        // 持仓 / 浮动盈亏（用户要求：右侧显示盈亏）
        forexPnlInfo.setFont(UITheme.SMALL_FONT);
        forexPnlInfo.setForeground(UITheme.TEXT_DIM);
        forexPnlInfo.setToolTipText("当前货币对的持仓手数、开仓均价与浮动盈亏");
        g.gridy++;
        p.add(forexPnlInfo, g);
        g.gridy++;

        g.gridy++;
        p.add(Box.createVerticalStrut(10), g);
        g.gridy++;

        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        buttons.setOpaque(false);
        JButton longBtn = new JButton(new AbstractAction("做 多") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                submitForex("long");
            }
        });
        styleBig(longBtn, UITheme.upColor());
        JButton shortBtn = new JButton(new AbstractAction("做 空") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                submitForex("short");
            }
        });
        styleBig(shortBtn, UITheme.downColor());
        buttons.add(longBtn);
        buttons.add(shortBtn);
        g.gridy++;
        p.add(buttons, g);

        g.gridy++;
        g.weighty = 1.0;
        p.add(Box.createVerticalGlue(), g);
        return p;
    }

    private JPanel row(String label, java.awt.Component field) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(UITheme.UI_FONT);
        l.setForeground(UITheme.TEXT_DIM);
        l.setPreferredSize(new Dimension(58, 24));
        p.add(l, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private JButton quickButton(String text, Runnable action) {
        JButton b = new JButton(new AbstractAction(text) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
        b.setFont(UITheme.SMALL_FONT);
        b.setMargin(new Insets(1, 6, 1, 6));
        return b;
    }

    private static void style(JComboBox<String> box) {
        box.setFont(UITheme.UI_FONT);
        box.setBackground(UITheme.PANEL_DARK);
        box.setForeground(UITheme.TEXT);
    }

    private static void style(JTextField f) {
        f.setFont(UITheme.UI_FONT);
        f.setBackground(UITheme.PANEL_DARK);
        f.setForeground(UITheme.TEXT);
        f.setCaretColor(UITheme.TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.WIDGET),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
    }

    private static void styleSpinner(JSpinner s) {
        s.setFont(UITheme.UI_FONT);
        JTextField tf = ((JSpinner.NumberEditor) s.getEditor()).getTextField();
        style(tf);
    }

    private static void styleBig(JButton b, Color bg) {
        b.setFont(UITheme.font(Font.BOLD, 15));
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(120, 40));
    }

    // ------------------------------------------------------------ 上下文

    /** 切换到股票卡片并给出上下文。 */
    public void showStock(StockQuote q, double buyingPower, int sellable, int digits) {
        if (q == null) {
            return;
        }
        this.symbol = q.symbol;
        this.name = q.name;
        this.stockLast = q.last;
        this.stockBuyingPower = buyingPower;
        this.stockSellable = sellable;
        currentCard = CARD_STOCK;
        cards.show(body, CARD_STOCK);
        refreshHeader();
        extraLabel.setText(q.halted ? "【停牌】无法下单" : "股票 · " + q.symbol);
        stockPrice.setText(UITheme.price(q.last, digits <= 0 ? 2 : digits));
        stockInfo.setText("可用资金 " + UITheme.money(buyingPower) + "  可卖 " + sellable + " 股");
        if (qtyAutoFill.isSelected()) {
            fillQty(0.25);
        }
        validateQty();
    }

    /** 切换到外汇卡片并给出上下文。 */
    public void showForex(ForexQuote q, double freeMargin) {
        if (q == null) {
            return;
        }
        this.symbol = q.symbol;
        this.name = q.name;
        this.forexLast = q.last;
        this.forexFreeMargin = freeMargin;
        currentCard = CARD_FOREX;
        cards.show(body, CARD_FOREX);
        refreshHeader();
        extraLabel.setText("外汇 · " + q.symbol + "  点值 " + UITheme.price(q.pointValue, 2));
        forexInfo.setText("可用保证金 " + UITheme.money(freeMargin) + "  1 手 = 1000 基础货币");
        if (forexStop.getText().trim().isEmpty()) {
            forexStop.setText(UITheme.price(q.last * 0.99, q.digits));
        }
        if (forexTake.getText().trim().isEmpty()) {
            forexTake.setText(UITheme.price(q.last * 1.02, q.digits));
        }
    }

    /**
     * 注入当前标的的持仓与盈亏（由 MainFrame 在每轮快照后调用）。
     *
     * @param qty          持仓数量（0 = 无持仓）
     * @param avgCost      持仓均价
     * @param pnl          浮动盈亏金额
     * @param pnlPct       浮动盈亏比例（小数，0.0123 = +1.23%）
     * @param todayBought  当日买入数量（T+1 冻结）
     */
    public void setPositionContext(int qty, double avgCost, double pnl, double pnlPct, int todayBought) {
        this.posQty = qty;
        this.posAvgCost = avgCost;
        this.posPnl = pnl;
        this.posPnlPct = pnlPct;
        this.posTodayBought = todayBought;
        refreshPnlLine();
    }

    /** 外汇持仓与浮动盈亏（由 MainFrame 注入）。 */
    private final JLabel forexPnlInfo = new JLabel(" ");
    private String forexPosText = "";
    private double forexPosPnl;

    /**
     * 注入当前货币对的持仓与浮动盈亏。
     *
     * @param posText 形如 "持有多单 2 手 · 均价 1.0812"
     * @param pnl     浮动盈亏金额（0 = 无持仓，显示 --）
     */
    public void setForexPositionContext(String posText, double pnl) {
        this.forexPosText = posText == null ? "" : posText;
        this.forexPosPnl = pnl;
        refreshForexPnlLine();
    }

    /** 刷新外汇"持仓 / 盈亏"一行。 */
    private void refreshForexPnlLine() {
        if (forexPosText.isEmpty()) {
            forexPnlInfo.setText("持仓 无 · 浮动盈亏 --");
            forexPnlInfo.setForeground(UITheme.TEXT_DIM);
            return;
        }
        String sign = forexPosPnl > 0 ? "+" : "";
        forexPnlInfo.setText(forexPosText + " · 浮动盈亏 " + sign + UITheme.money(forexPosPnl));
        forexPnlInfo.setForeground(UITheme.pnlColor(forexPosPnl));
    }

    /** 刷新"持仓 / 盈亏"一行。 */
    private void refreshPnlLine() {
        if (posQty <= 0) {
            pnlInfo.setText("持仓 0 股 · 盈亏 --");
            pnlInfo.setForeground(UITheme.TEXT_DIM);
            return;
        }
        String sign = posPnl > 0 ? "+" : "";
        String text = "持仓 " + posQty + " 股 · 均价 " + UITheme.price(posAvgCost, 2)
                + " · 盈亏 " + sign + UITheme.money(posPnl)
                + " (" + sign + UITheme.pct(posPnlPct) + ")";
        if (posTodayBought > 0) {
            text += " · 今日买入 " + posTodayBought + " 股(T+1)";
        }
        pnlInfo.setText(text);
        pnlInfo.setForeground(UITheme.pnlColor(posPnl));
    }

    /** 设置股票上下文（无行情时也调用，用于同步可卖量/可用资金）。 */
    public void setStockContext(double last, double buyingPower, int sellable, int digits) {
        this.stockLast = last;
        this.stockBuyingPower = buyingPower;
        this.stockSellable = sellable;
        if (last > 0) {
            priceLabel.setText(UITheme.price(last, digits <= 0 ? 2 : digits));
            priceLabel.setForeground(UITheme.TEXT);
            // 限价输入框跟随最新价刷新（用户仍可手工改写）
            stockPrice.setText(UITheme.price(last, digits <= 0 ? 2 : digits));
        }
        extraLabel.setText(symbol.isEmpty() ? "请选择标的" : symbol);
        stockInfo.setText("可用资金 " + UITheme.money(buyingPower) + "  可卖 " + sellable + " 股");
        if (qtyAutoFill.isSelected()) {
            fillQty(0.25);
        }
        validateQty();
    }

    private void refreshHeader() {
        titleLabel.setText(name.isEmpty() ? symbol : name + "  " + symbol);
    }

    /** 直接设定价格标签（行情推送时调用）。 */
    public void updatePrice(double last, double prevClose) {
        if (last <= 0) {
            return;
        }
        if (CARD_STOCK.equals(currentCard)) {
            stockLast = last;
        } else {
            forexLast = last;
        }
        priceLabel.setText(UITheme.price(last, CARD_STOCK.equals(currentCard) ? 2 : 4));
        priceLabel.setForeground(last >= prevClose ? UITheme.upColor() : UITheme.downColor());
    }

    /** 当前卡片名。 */
    public String currentCard() {
        return currentCard;
    }

    /** 当前标的代码。 */
    public String symbol() {
        return symbol;
    }

    // ------------------------------------------------------------ 校验与提交

    private void bindListeners() {
        stockType.addActionListener(e -> {
            boolean limit = stockType.getSelectedIndex() == 1;
            stockPrice.setEnabled(limit);
            stockPrice.setBackground(limit ? UITheme.PANEL_DARK : UITheme.BG);
        });
        stockPrice.setEnabled(false);
        stockPrice.setBackground(UITheme.BG);
        stockQty.addChangeListener(e -> validateQty());
        stockPrice.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                validateQty();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateQty();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateQty();
            }
        });
        forexLots.addChangeListener(e -> refreshForexInfo());
        forexLeverage.addActionListener(e -> refreshForexInfo());
    }

    private void refreshForexInfo() {
        int lots = lotsValue();
        int lev = leverage();
        double margin = forexLast > 0 ? lots * 1000.0 * forexLast / Math.max(1, lev) : 0;
        forexInfo.setText("<html>可用保证金 " + UITheme.money(forexFreeMargin)
                + "<br>预计占用保证金 " + UITheme.money(margin) + "（" + lots + " 手 / " + lev + " 倍）</html>");
    }

    private int lotsValue() {
        Object v = forexLots.getValue();
        return v instanceof Number ? ((Number) v).intValue() : 1;
    }

    private int leverage() {
        Object v = forexLeverage.getSelectedItem();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return 100;
        }
    }

    private void clearForexStops() {
        forexStop.setText("0");
        forexTake.setText("0");
    }

    private void fillQty(double ratio) {
        if (stockLast <= 0) {
            return;
        }
        int maxQty = (int) Math.floor(stockBuyingPower * ratio / stockLast / 100.0) * 100;
        if (maxQty < 100) {
            maxQty = 100;
        }
        stockQty.setValue(Integer.valueOf(maxQty));
    }

    private void fillSellable() {
        int q = stockSellable < 100 ? 100 : (stockSellable / 100) * 100;
        stockQty.setValue(Integer.valueOf(q));
    }

    private void validateQty() {
        int q;
        try {
            q = qtyValue();
        } catch (RuntimeException ex) {
            qtyHint.setText("数量必须是数字");
            qtyHint.setForeground(UITheme.DANGER);
            return;
        }
        if (q <= 0) {
            qtyHint.setText("数量必须大于 0");
            qtyHint.setForeground(UITheme.DANGER);
        } else if (q % 100 != 0) {
            qtyHint.setText("股票数量必须是 100 的整数倍");
            qtyHint.setForeground(UITheme.DANGER);
        } else {
            qtyHint.setText("数量 " + q + " 股（合法）");
            qtyHint.setForeground(UITheme.TEXT_DIM);
        }
    }

    private int qtyValue() {
        Object v = stockQty.getValue();
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return Integer.parseInt(String.valueOf(v).trim());
    }

    private void submitStock(String side) {
        if (handler == null) {
            return;
        }
        if (symbol.isEmpty()) {
            Dialogs.showMessage(this, "请先在行情表中选择一个标的", "提示",
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        int qty;
        try {
            qty = qtyValue();
        } catch (RuntimeException ex) {
            Dialogs.showMessage(this, "数量必须是数字", "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (qty <= 0 || qty % 100 != 0) {
            Dialogs.showMessage(this, "股票数量必须是 100 的整数倍（当前 " + qty + "）",
                    "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        String type = stockType.getSelectedIndex() == 1 ? "limit" : "market";
        double price = 0;
        if ("limit".equals(type)) {
            try {
                price = Double.parseDouble(stockPrice.getText().trim());
            } catch (NumberFormatException ex) {
                Dialogs.showMessage(this, "限价必须是数字", "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (price <= 0) {
                Dialogs.showMessage(this, "限价必须大于 0", "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        if ("sell".equals(side) && qty > stockSellable) {
            // 仅作前置提示，不拦截下单：引擎才是判定 T+1 的唯一权威，
            // 引擎返回的 T1_LOCKED message 会由 MainFrame 原样展示。
            StringBuilder sb = new StringBuilder();
            sb.append("T+1 锁定：当日买入的股票需次日开盘后才可卖出。\n");
            sb.append("当前可卖 ").append(stockSellable).append(" 股，本次委托 ").append(qty).append(" 股。\n\n");
            sb.append("仍将把委托发送给引擎，以引擎返回结果为准。");
            Dialogs.showMessage(this, sb.toString(), "T+1 提示", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
        handler.onStockOrder(side, type, qty, price);
    }

    private void submitForex(String side) {
        if (handler == null) {
            return;
        }
        if (symbol.isEmpty()) {
            Dialogs.showMessage(this, "请先在外汇行情表中选择一个货币对", "提示",
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        int lots = lotsValue();
        if (lots <= 0) {
            Dialogs.showMessage(this, "手数必须大于 0", "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        double sl = parseOrZero(forexStop.getText());
        double tp = parseOrZero(forexTake.getText());
        if (sl < 0 || tp < 0) {
            Dialogs.showMessage(this, "止损/止盈价格不能为负（0 表示不设置）", "参数错误",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (sl > 0 && "long".equals(side) && sl >= forexLast) {
            Dialogs.showMessage(this, "做多的止损价必须低于当前价 " + UITheme.price(forexLast, 4),
                    "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (sl > 0 && "short".equals(side) && sl <= forexLast) {
            Dialogs.showMessage(this, "做空的止损价必须高于当前价 " + UITheme.price(forexLast, 4),
                    "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        handler.onForexOpen(side, lots, leverage(), sl, tp);
    }

    private static double parseOrZero(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** 在 EDT 上刷新（供外部调用）。 */
    public void refreshLater() {
        if (SwingUtilities.isEventDispatchThread()) {
            refreshForexInfo();
        } else {
            SwingUtilities.invokeLater(this::refreshForexInfo);
        }
    }
}