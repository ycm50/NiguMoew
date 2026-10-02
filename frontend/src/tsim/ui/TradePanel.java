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
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JViewport;
import javax.swing.JTextArea;
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
         * @param side     buy / sell
         * @param type     market / limit
         * @param qty      数量（100 的整数倍）
         * @param price    限价（市价时忽略）
         * @param leverage 融资杠杆（1 = 不用杠杆；卖出恒为 1）
         */
        void onStockOrder(String side, String type, int qty, double price, int leverage);

        /**
         * 股票做空 / 平空。
         *
         * @param action   short（开空）/ cover（平空）
         * @param type     market / limit
         * @param qty      数量（100 的整数倍）
         * @param price    限价（市价时忽略）
         * @param leverage 做空杠杆 1..5（平空恒为 1）
         */
        void onStockShort(String action, String type, int qty, double price, int leverage);

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

        /**
         * 外汇平仓（持仓行的「平仓」按钮）。
         *
         * @param positionId 持仓 id
         * @param lots       要平掉的手数
         */
        void onForexClose(int positionId, int lots);
    }

    /** 持仓行上的外汇平仓回调（可选，未设置时按钮不生效）。 */
    public interface ForexRowHandler {
        /** 平掉指定持仓。
         *
         * @param positionId 持仓 id
         * @param lots       手数
         */
        void closeForex(int positionId, long lots);
    }

    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private String currentCard = CARD_STOCK;
    private final JLabel titleLabel = new JLabel("未选择标的");
    private final JLabel priceLabel = new JLabel("--");
    private final JLabel extraLabel = new JLabel(" ");

    private OrderHandler handler;
    private ForexRowHandler forexHandler;

    // ---- 股票控件
    private final JComboBox<String> stockType = new JComboBox<>(new String[]{"市价", "限价"});
    private final JTextField stockPrice = new JTextField(9);
    private final JSpinner stockQty = new JSpinner(new SpinnerNumberModel(100, 100, 100_000_000, 100));
    private final JCheckBox qtyAutoFill = new JCheckBox("自动填最大", true);
    private final JLabel qtyHint = new JLabel(" ");
    private final PositionSlider stockSizer = new PositionSlider();
    private final JLabel stockLevInfo = new JLabel(" ");
    /** 承载可滚动内容的滚动面板。 */
    private JScrollPane scrollHost;
    private final JLabel stockInfo = new JLabel(" ");
    /** 持仓与盈亏（用户要求：右侧面板显示盈亏）。 */
    private final JLabel pnlInfo = new JLabel(" ");
    /** 下半区：未成交委托与盈亏（用户要求）。 */
    /** 持仓行容器（多头 + 空头，按浮动盈亏排序）。 */
    private final JPanel rowsPanel = new JPanel();
    private double stockLast;
    private double stockBuyingPower;
    private int stockSellable;
    /** 限价模式下用户是否已手工改过价格框（改过就不再被行情覆盖）。 */
    private boolean priceEditedByUser;
    /** 正在由滑杆写入数量，避免回调互相触发。 */
    private boolean syncingQty;
    // 当前标的的持仓上下文（由 MainFrame 注入）
    private int posQty;
    private double posAvgCost;
    private double posPnl;
    private double posPnlPct;
    private int posTodayBought;

    // ---- 外汇控件
    /** 外汇杠杆：1 ~ 250（用户要求）。 */
    private final LeverageSlider forexLeverage = new LeverageSlider(1, 250);
    /** 股票杠杆（融资）：1 ~ 25（用户要求）。 */
    private final LeverageSlider stockLeverage = new LeverageSlider(1, 25);
    /** 做空杠杆（融券）：1 ~ 5（比融资保守，真实市场保证金比例更高）。 */
    private final LeverageSlider shortLeverage = new LeverageSlider(1, 5);
    /** 交易按钮引用（供上下文切换时改文案/可用性）。 */
    private JButton buyButtonRef;
    private JButton shortButtonRef;
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
        // 宽度需容纳内容 + 垂直滚动条（约 15px），否则会被裁掉右侧
        setPreferredSize(new Dimension(340, 520));
        setMinimumSize(new Dimension(300, 200));

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

        // 内容整体放入滚动面板：窗口变小 / 内容变多时可滚轮滚动，不会显示不全。
        // head 保持固定在顶部（标的名与现价始终可见），其余部分可滚动。
        add(head, BorderLayout.NORTH);

        // 自定义 JPanel：实现 Scrollable，让宽度跟随视口、高度取自然值
        ScrollablePanel column = new ScrollablePanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        body.setAlignmentX(LEFT_ALIGNMENT);
        column.add(body);

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        south.setAlignmentX(LEFT_ALIGNMENT);
        south.add(buildOpenOrdersArea(), BorderLayout.CENTER);
        south.add(notice, BorderLayout.SOUTH);
        column.add(south);

        // 让内容宽度跟随视口，避免横向被裁；高度自然伸展由滚动条接管
        column.setPreferredSize(null);
        JScrollPane scroller = new JScrollPane(column,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        scroller.getVerticalScrollBar().setUnitIncrement(16);   // 滚轮一格 16px
        scroller.getVerticalScrollBar().setBlockIncrement(80);
        installWheelScrolling(scroller);
        scrollHost = scroller;
        add(scroller, BorderLayout.CENTER);

        bindListeners();
        setStockContext(0, 0, 0, 2);
    }

    /**
     * 让滚轮在整块面板上都能滚动。
     *
     * <p>JScrollPane 放在 JSplitPane 里时，滚轮事件常被外层吃掉；这里：
     * 1) 给滚动面板自身加滚轮处理；
     * 2) 递归给所有子组件也加上（滑杆等不消费滚轮事件的组件本来就会冒泡，但文本框/下拉框会吃掉）。</p>
     */
    private void installWheelScrolling(JScrollPane scroller) {
        java.awt.event.MouseWheelListener wheel = e -> {
            javax.swing.JScrollBar bar = scroller.getVerticalScrollBar();
            // 把"滚动量"换算成像素：precise 设备给的是小数
            int amount = (int) (e.getPreciseWheelRotation() * bar.getUnitIncrement() * 2);
            if (amount == 0) {
                amount = e.getWheelRotation() * bar.getUnitIncrement() * 2;
            }
            bar.setValue(bar.getValue() + amount);
            e.consume();
        };
        scroller.addMouseWheelListener(wheel);
        scroller.getViewport().addMouseWheelListener(wheel);
        installWheelRecursively(scroller.getViewport(), wheel);
    }

    /** 递归挂载滚轮监听（组件加入后调用；不覆盖已有监听）。 */
    private void installWheelRecursively(java.awt.Container c,
                                         java.awt.event.MouseWheelListener wheel) {
        for (java.awt.Component child : c.getComponents()) {
            child.addMouseWheelListener(wheel);
            if (child instanceof java.awt.Container) {
                installWheelRecursively((java.awt.Container) child, wheel);
            }
        }
    }

    /** 注入持仓行上的外汇平仓回调。 */
    public void setForexRowHandler(ForexRowHandler h) {
        this.forexHandler = h;
    }

    /** 设置下单回调。 */
    public void setOrderHandler(OrderHandler h) {
        this.handler = h;
    }

    // ------------------------------------------------------------ 未成交委托

    /** 构建"未成交委托 + 盈亏"区域。 */
    private JPanel buildOpenOrdersArea() {
        JPanel wrap = new JPanel(new BorderLayout(0, 3));
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.WIDGET),
                BorderFactory.createEmptyBorder(6, 0, 0, 0)));

        JLabel cap = new JLabel("当前标的持仓（按浮动盈亏排序）");
        cap.setFont(UITheme.SMALL_FONT);
        cap.setForeground(UITheme.TEXT_DIM);
        wrap.add(cap, BorderLayout.NORTH);

        rowsPanel.setOpaque(false);
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));

        ScrollablePanel holder = new ScrollablePanel();
        holder.setOpaque(false);
        holder.setLayout(new BoxLayout(holder, BoxLayout.Y_AXIS));
        holder.add(rowsPanel);
        holder.add(Box.createVerticalGlue());

        JScrollPane sc = new JScrollPane(holder);
        sc.setBorder(BorderFactory.createEmptyBorder());
        sc.setPreferredSize(new Dimension(300, 190));
        sc.getViewport().setBackground(UITheme.PANEL_DARK);
        sc.setOpaque(false);
        sc.getViewport().setOpaque(true);
        sc.getViewport().setBackground(UITheme.PANEL_DARK);
        sc.getVerticalScrollBar().setUnitIncrement(16);
        installWheelScrolling(sc);
        wrap.add(sc, BorderLayout.CENTER);
        rebuildRows();
        return wrap;
    }

    /** 一条持仓行的数据。 */
    private static final class PosRow {
        final String kind;          // long | short
        final String symbol;
        final String name;
        final long qty;
        final double avgPrice;
        final double pnl;
        final double pnlPct;
        final long todayLocked;     // 当日新开（T+1 锁定）
        final boolean forex;        // 外汇行：单位是「手」而非「股」，且无 T+1
        final int positionId;       // 外汇平仓需要持仓 id（股票为 0）
        PosRow(String kind, String symbol, String name, long qty, double avgPrice,
               double pnl, double pnlPct, long todayLocked) {
            this(kind, symbol, name, qty, avgPrice, pnl, pnlPct, todayLocked, false);
        }
        PosRow(String kind, String symbol, String name, long qty, double avgPrice,
               double pnl, double pnlPct, long todayLocked, boolean forex) {
            this.kind = kind;
            this.symbol = symbol;
            this.name = name;
            this.qty = qty;
            this.avgPrice = avgPrice;
            this.pnl = pnl;
            this.pnlPct = pnlPct;
            this.todayLocked = todayLocked;
            this.forex = forex;
            this.positionId = 0;
        }
        PosRow(String kind, String symbol, String name, long qty, double avgPrice,
               double pnl, double pnlPct, long todayLocked, boolean forex, int positionId) {
            this.kind = kind;
            this.symbol = symbol;
            this.name = name;
            this.qty = qty;
            this.avgPrice = avgPrice;
            this.pnl = pnl;
            this.pnlPct = pnlPct;
            this.todayLocked = todayLocked;
            this.forex = forex;
            this.positionId = positionId;
        }
    }

    private final List<PosRow> rows = new ArrayList<>();

    /**
     * 更新当前标的的持仓行（多头 + 空头），并按**浮动盈亏从高到低**排序。
     *
     * @param longQty    多头股数（0 表示无）
     * @param longAvg    多头均价
     * @param longPnl    多头浮动盈亏
     * @param longPnlPct 多头收益率（小数）
     * @param longToday  当日买入（T+1 锁定）
     * @param shortQty   空头股数（0 表示无）
     * @param shortAvg   做空均价
     * @param shortPnl   空头浮动盈亏
     * @param shortToday 当日做空（T+1 锁定）
     */
    public void setPositionRows(long longQty, double longAvg, double longPnl, double longPnlPct, long longToday,
                                long shortQty, double shortAvg, double shortPnl, long shortToday) {
        rows.clear();
        if (longQty > 0) {
            rows.add(new PosRow("long", symbol, name, longQty, longAvg, longPnl, longPnlPct, longToday));
        }
        if (shortQty > 0) {
            double pct = shortAvg > 1e-9 ? (shortAvg - stockLast) / shortAvg : 0.0;
            rows.add(new PosRow("short", symbol, name, shortQty, shortAvg, shortPnl, pct, shortToday));
        }
        // 按浮动盈亏降序：赚得多的排前面
        rows.sort((a, b) -> Double.compare(b.pnl, a.pnl));
        rebuildRows();
    }

    /**
     * 更新当前货币对的持仓行（多单 / 空单），按浮动盈亏从高到低排序。
     *
     * <p>原缺陷：外汇分支只调用了 {@code setForexPositionContext()} 设一句文字，
     * 从不刷新下半区持仓行，于是右栏顶部写着「多单 1 手 · 均价 …」，
     * 下方却仍是上一次股票残留的「暂无持仓」，看起来像「汇市订单完全不显示」。</p>
     *
     * <p>外汇没有挂单概念（{@code forexOpen} 即时市价成交，引擎不产生 Order），
     * 所以这里展示的是**成交后的持仓**。</p>
     */
    public void setForexRows(List<ForexRow> fxRows) {
        rows.clear();
        if (fxRows != null) {
            for (ForexRow r : fxRows) {
                rows.add(new PosRow(r.side, r.symbol, r.name, r.lots, r.openRate,
                        r.pnl, r.pnlPct, 0, true, r.positionId));
            }
        }
        rows.sort((a, b) -> Double.compare(b.pnl, a.pnl));
        rebuildRows();
    }

    /** 一条外汇持仓行（手数而非股数）。 */
    public static final class ForexRow {
        final String side;
        final String symbol;
        final String name;
        final long lots;
        final double openRate;
        final double pnl;
        final double pnlPct;
        final int positionId;
        ForexRow(String side, String symbol, String name, long lots,
                 double openRate, double pnl, double pnlPct, int positionId) {
            this.side = side;
            this.symbol = symbol;
            this.name = name;
            this.lots = lots;
            this.openRate = openRate;
            this.pnl = pnl;
            this.pnlPct = pnlPct;
            this.positionId = positionId;
        }

        /** 构造一条外汇持仓行（供 MainFrame 与测试使用）。 */
        public static ForexRow of(String side, String symbol, String name, long lots,
                                  double openRate, double pnl, double pnlPct, int positionId) {
            return new ForexRow(side, symbol, name, lots, openRate, pnl, pnlPct, positionId);
        }
    }

    /** 当前持仓行数（测试用）。 */
    public int positionRowCount() {
        return rows.size();
    }

    /** 第 i 行的浮动盈亏（测试用）。 */
    public double positionRowPnl(int i) {
        return rows.get(i).pnl;
    }

    /** 第 i 行是否是外汇行（测试用）。 */
    public boolean positionRowIsForex(int i) {
        return rows.get(i).forex;
    }

    /** 重建行视图。 */
    private void rebuildRows() {
        rowsPanel.removeAll();
        if (rows.isEmpty()) {
            JLabel empty = new JLabel("暂无持仓");
            empty.setFont(UITheme.SMALL_FONT);
            empty.setForeground(UITheme.TEXT_DIM);
            empty.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
            rowsPanel.add(empty);
        } else {
            for (PosRow row : rows) {
                rowsPanel.add(buildRowView(row));
            }
        }
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    /** 构建单行：描述 + 盈亏 + 操作按钮。 */
    private JPanel buildRowView(PosRow row) {
        boolean isLong = "long".equals(row.kind);
        JPanel line = new JPanel(new BorderLayout(6, 0));
        line.setOpaque(false);
        line.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.GRID),
                BorderFactory.createEmptyBorder(3, 2, 3, 2)));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

        String tag = isLong ? "做多" : "做空";
        // 外汇行：单位是「手」，没有 T+1 锁定；价格按该品种小数位显示
        String unit = row.forex ? " 手 @ " : " 股 @ ";
        int unitDigits = row.forex ? 4 : 2;
        String lock = (!row.forex && row.todayLocked > 0)
                ? "  <font color=#F0B429>(T+1 " + row.todayLocked + ")</font>" : "";
        String cur = row.forex ? plainPrice(forexLast, 4) : plainPrice(stockLast, 2);
        JLabel desc = new JLabel("<html>"
                + "<b>" + tag + "</b> " + row.qty + unit + plainPrice(row.avgPrice, unitDigits)
                + lock
                + "<br><font color=#9AA3B2>" + (isLong ? "买入价" : "开空价")
                + " · 现价 " + cur + "</font></html>");
        desc.setFont(UITheme.SMALL_FONT);
        desc.setForeground(UITheme.TEXT);

        String sign = row.pnl > 0 ? "+" : "";
        JLabel pnl = new JLabel("<html><div style='text-align:right'>"
                + "<b>" + sign + UITheme.money(row.pnl) + "</b><br>"
                + "<font color=#9AA3B2>" + sign + UITheme.pct(row.pnlPct) + "</font></div></html>");
        pnl.setFont(UITheme.SMALL_FONT);
        pnl.setForeground(UITheme.pnlColor(row.pnl));

        // 操作按钮：股票 -> 卖出/平仓；外汇 -> 按手数平掉该持仓
        String btnText = row.forex ? "平仓" : (isLong ? "卖出" : "平仓");
        JButton act = new JButton(new AbstractAction(btnText) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (row.forex) {
                    if (forexHandler != null) {
                        forexHandler.closeForex(row.positionId, row.qty);
                    }
                    return;
                }
                if (handler == null) {
                    return;
                }
                if (isLong) {
                    // 卖出多头：把该持仓数量填进数量框，由用户确认后下单
                    stockQty.setValue(Integer.valueOf((int) row.qty));
                    submitStock("sell");
                } else {
                    stockQty.setValue(Integer.valueOf((int) row.qty));
                    submitStock("cover");
                }
            }
        });
        act.setFont(UITheme.font(Font.PLAIN, 10));
        act.setMargin(new Insets(1, 6, 1, 6));
        act.setForeground(isLong ? UITheme.downColor() : UITheme.upColor());
        act.setToolTipText(isLong ? "卖出全部多头持仓" : "买入归还，平掉全部空头");

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(pnl);
        right.add(act);

        line.add(desc, BorderLayout.CENTER);
        line.add(right, BorderLayout.EAST);
        return line;
    }

    private static String signed(double v) {
        return (v > 0 ? "+" : "") + UITheme.money(v);
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

        // 仓位滑杆：最右端 = 全仓（全部资金）
        stockSizer.setListener(pct -> applyStockSizing(pct));
        g.gridy++;
        p.add(stockSizer, g);
        g.gridy++;

        // 股票融资杠杆：1 ~ 25 倍（用户要求）
        g.gridy++;
        p.add(stockLeverage, g);
        g.gridy++;

        JPanel sellQuick = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        sellQuick.setOpaque(false);
        sellQuick.add(quickButton("全平可卖", this::fillSellable));
        sellQuick.add(quickButton("清空", () -> stockQty.setValue(Integer.valueOf(100))));
        sellQuick.add(quickButton("全仓可卖", () -> applySellSizing(1.0)));
        qtyAutoFill.setFont(UITheme.SMALL_FONT);
        qtyAutoFill.setOpaque(false);
        qtyAutoFill.setForeground(UITheme.TEXT_DIM);
        qtyAutoFill.setVisible(false);
        g.gridy++;
        p.add(sellQuick, g);
        g.gridy++;

        qtyHint.setFont(UITheme.SMALL_FONT);
        qtyHint.setForeground(UITheme.TEXT_DIM);
        g.gridy++;
        p.add(qtyHint, g);
        g.gridy++;

        stockLevInfo.setFont(UITheme.SMALL_FONT);
        stockLevInfo.setForeground(UITheme.WARN);
        g.gridy++;
        p.add(stockLevInfo, g);
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
        // 做多买入 / 做空卖出（不再用"买入/卖出"这组含糊的叫法）
        JButton buy = new JButton(new AbstractAction("做多买入") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                submitStock("buy");
            }
        });
        buy.setToolTipText("买入（可加融资杠杆），建立/增加多头持仓");
        styleBig(buy, UITheme.upColor());
        JButton sell = new JButton(new AbstractAction("做空卖出") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                submitStock("short");
            }
        });
        sell.setToolTipText("融券做空，借股票卖出；下跌盈利，需保证金");
        styleBig(sell, UITheme.downColor());
        buttons.add(buy);
        buttons.add(sell);
        buyButtonRef = buy;
        shortButtonRef = sell;
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

        forexLeverage.setListener(lev -> refreshForexInfo());
        style(forexStop);
        style(forexTake);
        styleSpinner(forexLots);

        p.add(forexLeverage, g);
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
        if (!isLimitSelected()) {
            setStockPriceText(plainPrice(q.last, digits <= 0 ? 2 : digits));
        }
        stockInfo.setText("可用资金 " + UITheme.money(buyingPower) + "  可卖 " + sellable + " 股");
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
            forexStop.setText(plainPrice(q.last * 0.99, q.digits));
        }
        if (forexTake.getText().trim().isEmpty()) {
            forexTake.setText(plainPrice(q.last * 1.02, q.digits));
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
            // 市价单：价格框只是展示最新价，跟随刷新。
            // 限价单：**用户填的价格是委托价，不能被行情覆盖**，否则刚输入的价会被刷掉。
            //   仅在「用户还没填过」时给一次最新价做初值（见 priceEditedByUser）。
            if (!isLimitSelected()) {
                // 市价单：价格框仅展示最新价
                setStockPriceText(plainPrice(last, digits <= 0 ? 2 : digits));
            } else if (!priceEditedByUser) {
                // 限价单且用户尚未填过：给一次最新价做初值；填过之后永不覆盖
                setStockPriceText(plainPrice(last, digits <= 0 ? 2 : digits));
            }
        }
        extraLabel.setText(symbol.isEmpty() ? "请选择标的" : symbol);
        stockInfo.setText("可用资金 " + UITheme.money(buyingPower) + "  可卖 " + sellable + " 股");
        // 最大可买变化后，刷新滑杆说明（比例本身不动，避免打断用户已选的仓位）
        updateSizerDetail();
        validateQty();
    }

    /** 刷新股票杠杆说明（自有资金 / 融资额）。 */
    private void refreshStockLeverageInfo() {
        int lev = stockLeverageValue();
        if (lev <= 1 || stockLast <= 0) {
            stockLevInfo.setText(" ");
        } else {
            stockLevInfo.setText(lev + "x：只需 " + UITheme.pct(1.0 / lev)
                    + " 自有资金，其余为融资（卖出时自动偿还）");
        }
        updateSizerDetail();
    }

    /** 刷新仓位滑杆下方的"≈ N 股 · 金额"说明。 */
    private void updateSizerDetail() {
        int max = maxQtyFor(1.0);
        if (max <= 0) {
            stockSizer.setDetail(stockLast <= 0 ? "请先选择标的" : "资金不足，至少需 100 股");
            return;
        }
        int q = maxQtyFor(stockSizer.pct());
        stockSizer.setDetail(q > 0 ? "≈ " + q + " 股 · " + UITheme.money(q * stockLast) : " ");
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
            boolean limit = isLimitSelected();
            stockPrice.setEnabled(limit);
            stockPrice.setBackground(limit ? UITheme.PANEL_DARK : UITheme.BG);
            if (limit) {
                // 切到限价时，把当前最新价填进去做初值，之后由用户自己决定
                if (stockLast > 0) {
                    setStockPriceText(plainPrice(stockLast, 2));
                }
                priceEditedByUser = false;
                stockPrice.requestFocusInWindow();
                stockPrice.selectAll();
            }
        });
        stockPrice.setEnabled(false);
        stockPrice.setBackground(UITheme.BG);
        stockQty.addChangeListener(e -> {
            validateQty();
            if (!syncingQty) {
                syncSliderToQty();
            }
        });
        stockPrice.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onPriceEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onPriceEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onPriceEdited();
            }
        });
        forexLots.addChangeListener(e -> refreshForexInfo());
        stockLeverage.setListener(lev -> refreshStockLeverageInfo());
    }

    /**
     * 解析用户输入的数字，容忍千分位逗号、空格与全角字符。
     *
     * <p>必须容忍千分位：`UITheme.price()` 这类展示用格式化会产出 `1,698.73`，
     * 一旦写回输入框，`Double.parseDouble` 就会抛 NumberFormatException，
     * 于是出现"明明填的是数字却提示不是数字"。</p>
     *
     * @param raw 原始文本
     * @return 解析出的数值
     * @throws NumberFormatException 输入为空或不含任何数字时
     */
    static double parseUserNumber(String raw) {
        if (raw == null) {
            throw new NumberFormatException("null");
        }
        String s = raw.trim()
                .replace(",", "")      // 千分位
                .replace("，", "")     // 全角逗号
                .replace(" ", "")      // 内部空格
                .replace("\u00A0", ""); // 不换行空格
        if (s.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        return Double.parseDouble(s);
    }

    /** 输入框专用的价格文本：**不带千分位**，保证可直接被 parseUserNumber 解析。 */
    static String plainPrice(double v, int digits) {
        int d = Math.max(0, Math.min(4, digits <= 0 ? 2 : digits));
        return java.math.BigDecimal.valueOf(v)
                .setScale(d, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** 价格框内容变化：标记为"用户已编辑"，并刷新提示。 */
    private void onPriceEdited() {
        if (!settingPriceProgrammatically) {
            priceEditedByUser = true;
        }
        validateQty();
    }

    /** 当前是否选中「限价」。 */
    private boolean isLimitSelected() {
        return stockType.getSelectedIndex() == 1;
    }

    /** 程序化写入价格框时置位，避免把自动填充误判成用户编辑。 */
    private boolean settingPriceProgrammatically;

    /** 安全地程序化设置价格框（不触发"用户已编辑"标记）。 */
    private void setStockPriceText(String text) {
        settingPriceProgrammatically = true;
        try {
            stockPrice.setText(text);
        } finally {
            settingPriceProgrammatically = false;
        }
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

    /** 外汇杠杆（1..250）。 */
    private int leverage() {
        return forexLeverage.leverage();
    }

    /** 股票融资杠杆（1..25，1 = 不用杠杆）。 */
    private int stockLeverageValue() {
        return stockLeverage.leverage();
    }

    /** 做空杠杆（1..5）。 */
    private int shortLeverageValue() {
        return shortLeverage.leverage();
    }

    private void clearForexStops() {
        forexStop.setText("0");
        forexTake.setText("0");
    }

    private void fillQty(double ratio) {
        int maxQty = maxQtyFor(ratio);
        if (maxQty < 100) {
            maxQty = 100;
        }
        stockQty.setValue(Integer.valueOf(maxQty));
    }

    /** 按仓位比例算出可买股数（100 的整数倍，向下取整）。 */
    private int maxQtyFor(double ratio) {
        if (stockLast <= 0 || stockBuyingPower <= 0) {
            return 0;
        }
        return (int) Math.floor(stockBuyingPower * ratio / stockLast / 100.0) * 100;
    }

    /**
     * 仓位滑杆回调：把比例换算成 100 整数倍的股数填入数量框，并刷新滑杆下方的说明。
     *
     * @param pct 0.0 ~ 1.0
     */
    private void applyStockSizing(double pct) {
        int qty = maxQtyFor(pct);
        if (qty <= 0) {
            stockSizer.setDetail(stockLast <= 0 ? "请先选择标的" : "资金不足，至少需 100 股");
            validateQty();
            return;
        }
        stockQty.setValue(Integer.valueOf(qty));
        stockSizer.setDetail("≈ " + qty + " 股 · " + UITheme.money(qty * stockLast));
        validateQty();
    }

    /** 数量框变化时让滑杆位置与之一致（手动改数量后仍然自洽）。 */
    private void syncSliderToQty() {
        int max = maxQtyFor(1.0);
        if (max <= 0) {
            stockSizer.setDetail(" ");
            return;
        }
        int q = -1;
        try {
            q = qtyValue();
        } catch (RuntimeException ignored) {
            // 输入中，忽略
        }
        if (q > 0) {
            stockSizer.setPct(Math.min(1.0, (double) q / max));
            stockSizer.setDetail("≈ " + q + " 股 · " + UITheme.money(q * stockLast));
        }
    }

    private void fillSellable() {
        int q = stockSellable < 100 ? 100 : (stockSellable / 100) * 100;
        stockQty.setValue(Integer.valueOf(q));
    }

    /** 按比例卖出：把可卖量的 pct 部分填入数量框。 */
    private void applySellSizing(double pct) {
        int q = (int) Math.floor(stockSellable * pct / 100.0) * 100;
        if (q < 100) {
            q = stockSellable >= 100 ? 100 : stockSellable;
        }
        stockQty.setValue(Integer.valueOf(Math.max(q, 0)));
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
                price = parseUserNumber(stockPrice.getText());
            } catch (NumberFormatException ex) {
                Dialogs.showMessage(this,
                        "限价必须是数字（当前输入：\"" + stockPrice.getText() + "\"）",
                        "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (price <= 0) {
                Dialogs.showMessage(this, "限价必须大于 0", "参数错误", javax.swing.JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        if ("short".equals(side) || "cover".equals(side)) {
            // 做空/平空：交给专用回调
            int slev = "short".equals(side) ? shortLeverageValue() : 1;
            handler.onStockShort(side, type, qty, price, slev);
            return;
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
        // 杠杆仅用于买入（融资）；卖出不传
        int lev = "buy".equals(side) ? stockLeverageValue() : 1;
        handler.onStockOrder(side, type, qty, price, lev);
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

    /** 解析可选数值输入（止损/止盈）；容忍千分位，空或非法返回 0。 */
    private static double parseOrZero(String s) {
        try {
            return parseUserNumber(s);
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