package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import tsim.json.Json;
import tsim.json.JsonDeserializer;
import tsim.json.JsonDeserializer.ClockState;
import tsim.json.JsonDeserializer.ForexPosition;
import tsim.json.JsonDeserializer.MarketData;
import tsim.json.JsonDeserializer.NewsInfo;
import tsim.json.JsonDeserializer.OrderInfo;
import tsim.json.JsonDeserializer.Settings;
import tsim.json.JsonDeserializer.Snapshot;
import tsim.json.JsonDeserializer.StockPosition;
import tsim.json.JsonDeserializer.TickResult;
import tsim.json.JsonDeserializer.TradeInfo;
import tsim.json.JsonDeserializer.TradeResult;

/**
 * 主窗口总装：菜单栏 + 工具条 + 中央 Tab（行情/持仓/订单/成交/新闻/作弊器）
 * + 右侧交易面板 + 时钟面板 + 底部状态栏 + 资产总览/日志次级 Tab。
 */
public final class MainFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final EngineClient engine;

    private final StatusBar statusBar = new StatusBar();
    private final MarketTable marketTable = new MarketTable();
    private final KLineChart kline = new KLineChart();
    private final TickChart tickChart = new TickChart();
    /** 左侧图表页签（K 线图 / 分时图）；切市场时需要跟着切页。 */
    private final javax.swing.JTabbedPane chartTabs = new javax.swing.JTabbedPane();
    private final TradePanel tradePanel = new TradePanel();
    private final PositionPanel positionPanel = new PositionPanel();
    private final OrderPanel orderPanel = new OrderPanel();
    private final TradeHistoryPanel historyPanel = new TradeHistoryPanel();
    private final NewsPanel newsPanel = new NewsPanel();
    private final CheatPanel cheatPanel = new CheatPanel();
    private final ClockPanel clockPanel = new ClockPanel();
    private final HoldingsPanel holdingsPanel = new HoldingsPanel();
    private final LogPanel logPanel = new LogPanel();
    private final JLabel noticeLabel = new JLabel();

    private final JTabbedPane mainTabs = new JTabbedPane();
    private final Timer refreshTimer;

    private String selectedMarket = "stock";
    private String selectedSymbol = "";
    private MarketData lastMarket;
    private Snapshot lastSnapshot;
    private long startedAtMs = System.currentTimeMillis();
    private volatile boolean busy;

    /** 构造主窗口（不启动引擎）。 */
    public MainFrame(EngineClient engine) {
        super("拟股喵喵  —  股票 / 外汇 模拟交易仿真器");
        this.engine = engine;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 720));
        setSize(1440, 900);
        setLocationRelativeTo(null);

        setJMenuBar(buildMenuBar());
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UITheme.BG);
        root.add(buildToolBar(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);
        setContentPane(root);

        bindHandlers();
        wireEngine();

        refreshTimer = new Timer(3000, e -> refreshAll());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmExit();
            }
        });
    }

    // ------------------------------------------------------------ 布局

    private JTabbedPane buildCenter() {
        mainTabs.setFont(UITheme.UI_FONT);
        mainTabs.setBackground(UITheme.PANEL);
        mainTabs.setForeground(UITheme.TEXT);

        mainTabs.addTab("行情", buildMarketTab());
        mainTabs.addTab("资产总览", holdingsPanel);
        mainTabs.addTab("持仓", positionPanel);
        mainTabs.addTab("订单", orderPanel);
        mainTabs.addTab("成交", historyPanel);
        mainTabs.addTab("新闻", newsPanel);
        mainTabs.addTab("作弊器", cheatPanel);
        mainTabs.addTab("引擎日志", logPanel);
        return mainTabs;
    }

    private JPanel buildMarketTab() {
        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(UITheme.PANEL);
        left.add(marketTable, BorderLayout.CENTER);

        chartTabs.setFont(UITheme.SMALL_FONT);
        chartTabs.addTab("K 线图", kline);
        chartTabs.addTab("分时图", tickChart);
        chartTabs.setBackground(UITheme.PANEL);
        chartTabs.setForeground(UITheme.TEXT);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, chartTabs);
        split.setResizeWeight(0.46);
        split.setDividerSize(6);
        split.setBorder(null);

        JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, split, tradePanel);
        outer.setResizeWeight(0.82);
        outer.setDividerSize(6);
        outer.setBorder(null);

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(UITheme.BG);
        p.add(outer, BorderLayout.CENTER);
        return p;
    }

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBackground(UITheme.PANEL);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.WIDGET));
        bar.add(tool("新游戏", this::actionNewGame));
        bar.add(tool("买 入", () -> quickTrade("buy")));
        bar.add(tool("卖 出", () -> quickTrade("sell")));
        bar.addSeparator();
        bar.add(tool("刷新", this::refreshAll));
        bar.add(tool("推进 1 片", () -> stepSlots(1)));
        bar.add(tool("推进 1 天", () -> stepSlots(4)));
        bar.add(tool("推进 1 周", () -> stepSlots(20)));
        bar.addSeparator();
        bar.add(tool("作弊器", () -> mainTabs.setSelectedIndex(6)));
        bar.add(Box.createHorizontalGlue());

        noticeLabel.setText("  ⚠ " + UITheme.SIM_NOTICE + "  ");
        noticeLabel.setFont(UITheme.font(Font.BOLD, 12));
        noticeLabel.setForeground(UITheme.WARN);
        bar.add(noticeLabel);
        return bar;
    }

    private JPanel buildSouth() {
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(UITheme.PANEL);
        south.add(clockPanel, BorderLayout.CENTER);
        south.add(statusBar, BorderLayout.SOUTH);
        return south;
    }

    private JButton tool(String text, Runnable action) {
        JButton b = new JButton(new AbstractAction(text) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
        b.setFont(UITheme.SMALL_FONT);
        b.setFocusPainted(false);
        b.setMargin(new java.awt.Insets(3, 10, 3, 10));
        return b;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        bar.setFont(UITheme.UI_FONT);

        JMenu game = new JMenu("游戏");
        game.setFont(UITheme.UI_FONT);
        game.add(item("新游戏...", KeyStroke.getKeyStroke("control N"), this::actionNewGame));
        game.add(item("重新加载行情", KeyStroke.getKeyStroke("F5"), this::refreshAll));
        game.addSeparator();
        game.add(item("退出", KeyStroke.getKeyStroke("alt X"), this::confirmExit));

        JMenu time = new JMenu("时间");
        time.setFont(UITheme.UI_FONT);
        time.add(item("开始自动推进", KeyStroke.getKeyStroke("control S"), clockPanel::startPublic));
        time.add(item("暂停", KeyStroke.getKeyStroke("control P"), clockPanel::stopPublic));
        time.add(item("单步 1 片", KeyStroke.getKeyStroke("F8"), () -> stepSlots(1)));
        time.add(item("单步 1 天", KeyStroke.getKeyStroke("F9"), () -> stepSlots(4)));

        JMenu view = new JMenu("视图");
        view.setFont(UITheme.UI_FONT);
        JCheckBoxMenuItem asShare = new JCheckBoxMenuItem("涨红跌绿（A股习惯）", true);
        JCheckBoxMenuItem western = new JCheckBoxMenuItem("涨绿跌红（欧美习惯）", false);
        asShare.addActionListener(e -> {
            UITheme.setAShareScheme();
            western.setSelected(false);
            asShare.setSelected(true);
            repaintAll();
        });
        western.addActionListener(e -> {
            UITheme.setWesternScheme();
            asShare.setSelected(false);
            western.setSelected(true);
            repaintAll();
        });
        view.add(asShare);
        view.add(western);
        view.addSeparator();
        view.add(item("刷新全部面板", KeyStroke.getKeyStroke("F5"), this::refreshAll));

        JMenu help = new JMenu("帮助");
        help.setFont(UITheme.UI_FONT);
        help.add(item("操作提示", null, MainFrame::showHelp));
        help.add(item("关于 / 合规声明", null, MainFrame::showAbout));

        bar.add(game);
        bar.add(time);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    private JMenuItem item(String text, KeyStroke accel, Runnable action) {
        JMenuItem mi = new JMenuItem(new AbstractAction(text) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
        mi.setFont(UITheme.UI_FONT);
        if (accel != null) {
            mi.setAccelerator(accel);
        }
        return mi;
    }

    // ------------------------------------------------------------ 事件接线

    private void bindHandlers() {
        marketTable.addSelectionListener((market, symbol) -> {
            boolean marketChanged = !market.equals(selectedMarket);
            selectedMarket = market;
            selectedSymbol = symbol;
            if (marketChanged) {
                syncChartTabToMarket();
            }
            updateCharts();
            updateTradePanel();
        });
        tradePanel.setOrderHandler(new TradePanel.OrderHandler() {
            @Override
            public void onStockOrder(String side, String type, int qty, double price, int leverage) {
                doStockOrder(side, type, qty, price, leverage);
            }

            @Override
            public void onStockShort(String action, String type, int qty, double price, int leverage) {
                doStockShort(action, type, qty, price, leverage);
            }

            @Override
            public void onForexOpen(String side, int lots, int leverage, double stopLoss, double takeProfit) {
                doForexOpen(side, lots, leverage, stopLoss, takeProfit);
            }

            @Override
            public void onForexClose(int positionId, int lots) {
                doForexClose(positionId, lots);
            }
        });
        // 持仓行上的「平仓」按钮：直接调引擎平掉该持仓
        tradePanel.setForexRowHandler((positionId, lots) ->
                doForexClose(positionId, (int) lots));
        positionPanel.setCloseHandler((market, positionId, symbol, qty) -> {
            if ("forex".equals(market)) {
                doForexClose(positionId, qty);
            } else {
                doStockOrder("sell", "market", qty, 0, 1);
            }
        });
        orderPanel.setCancelHandler(this::doCancel);
        historyPanel.setRefreshHandler(this::refreshHistory);
        newsPanel.setRefreshHandler(this::refreshNews);
        cheatPanel.setCheatHandler(this::doCheat);
        clockPanel.setClockHandler(new ClockPanel.ClockHandler() {
            @Override
            public void onSetSpeed(double speed, int tickMs) {
                applyClockSpeed(speed, tickMs, false);
            }

            @Override
            public void onStart(double speed, int tickMs) {
                applyClockSpeed(speed, tickMs, true);
            }

            @Override
            public void onStop() {
                engine.clock("stop", null, null).whenComplete((d, ex) -> {
                    if (ex != null) {
                        async(() -> Dialogs.warn(MainFrame.this, "暂停时钟失败：" + errorMessage(ex)));
                    } else {
                        async(() -> statusBar.setSpeed("时钟 已暂停"));
                    }
                });
            }

            @Override
            public void onStep(int slots) {
                stepSlots(slots);
            }
        });
    }

    private void wireEngine() {
        engine.addLogListener(logPanel::append);
        engine.addPushListener((type, data) -> {
            if ("tick".equals(type)) {
                onTick(TickResult.of(data));
            } else if ("bankrupt".equals(type)) {
                // 协议 §3.15：破产主动推送 data.account = "stock" | "forex"
                onBankrupt(Json.str(data, "account"));
            }
        });
        engine.addExitListener((code, expected) -> SwingUtilities.invokeLater(() -> {
            statusBar.setEngineStatus("引擎已退出（exit=" + code + "）", false);
            refreshTimer.stop();
            if (!expected) {
                Dialogs.error(this, "引擎已退出，请重启。\n\n退出码：" + code
                        + "\n请重新运行 scripts\\run.cmd 启动前端。");
            }
        }));
    }

    // ------------------------------------------------------------ 启动

    /** 新游戏：seed=当前时间戳，玩家名"玩家"，难度 normal。 */
    public void startNewGame(String playerName) {
        long seed = System.currentTimeMillis();
        setBusy(true);
        statusBar.setEngineStatus("正在新建游戏 ...", true);
        engine.newGame(seed, playerName == null || playerName.isEmpty() ? "玩家" : playerName,
                        1_000_000, 10_000, "normal")
                .thenApply(Snapshot::of)
                .whenComplete((snap, ex) -> SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    if (ex != null) {
                        statusBar.setEngineStatus("新建游戏失败", false);
                        Dialogs.error(this, "新建游戏失败：\n" + errorMessage(ex));
                        return;
                    }
                    startedAtMs = System.currentTimeMillis();
                    holdingsPanel.reset();
                    orderPanel.clearExpiredHistory();
                    onSnapshot(snap);
                    statusBar.setEngineStatus("引擎运行中", true);
                    refreshAll();
                    refreshTimer.start();
                    engine.clockState().whenComplete((st, e2) -> {
                        if (e2 == null) {
                            SwingUtilities.invokeLater(() -> applyClock(st));
                        }
                    });
                }));
    }

    private void setBusy(boolean b) {
        busy = b;
    }

    /** 是否需要先新游戏。 */
    public void refreshAll() {
        if (!engine.isAlive()) {
            statusBar.setEngineStatus("引擎未运行", false);
            return;
        }
        engine.snapshotModel().whenComplete((s, ex) -> {
            if (ex != null) {
                handleAsyncError("读取快照失败", ex);
                return;
            }
            SwingUtilities.invokeLater(() -> onSnapshot(s));
        });
        engine.marketModel("all").whenComplete((md, ex) -> {
            if (ex == null) {
                SwingUtilities.invokeLater(() -> onMarket(md));
            }
        });
        refreshHistory();
        refreshOrders();
        refreshNews(newsPanel.isUnreadOnly());
    }

    private void refreshHistory() {
        engine.historyModel(200).whenComplete((list, ex) -> {
            if (ex == null) {
                SwingUtilities.invokeLater(() -> historyPanel.update(list));
            }
        });
    }

    private void refreshOrders() {
        engine.ordersModel("all").whenComplete((list, ex) -> {
            if (ex == null) {
                SwingUtilities.invokeLater(() -> orderPanel.update(list));
            }
        });
    }

    private void refreshNews(boolean unreadOnly) {
        engine.newsModel(50, unreadOnly).whenComplete((list, ex) -> {
            if (ex == null) {
                SwingUtilities.invokeLater(() -> newsPanel.update(list));
            }
        });
    }

    private void onSnapshot(Snapshot s) {
        if (s == null) {
            return;
        }
        lastSnapshot = s;
        statusBar.setSnapshot(s);
        holdingsPanel.update(s);
        positionPanel.update(s.stockPositions, s.forexPositions);
        orderPanel.update(s.orders);
        allOrders = s.orders;
        stockPositions = s.stockPositions;
        forexPositions = s.forexPositions;
        if (s.bankrupt) {
            showBankruptOnce();
        }
        updateTradePanelContext();
    }

    private List<OrderInfo> allOrders = new ArrayList<>();
    private List<StockPosition> stockPositions = new ArrayList<>();
    private List<ForexPosition> forexPositions = new ArrayList<>();

    private void onMarket(MarketData md) {
        lastMarket = md;
        marketTable.update(md);
        statusBar.setGameTime(md.time.toString());
        clockPanel.setGameTime(md.time.toString());
        if (selectedSymbol.isEmpty() && !md.stocks.isEmpty()) {
            selectedSymbol = md.stocks.get(0).symbol;
            selectedMarket = "stock";
            marketTable.selectQuietly("stock", selectedSymbol);
        }
        updateCharts();
        updateTradePanelContext();
        if (!"stock".equals(selectedMarket) && !md.forex.isEmpty()) {
            tradePanel.refreshLater();
        }
    }

    private void onTick(TickResult tr) {
        if (tr == null) {
            return;
        }
        clockPanel.setGameTime(tr.time.toString());
        clockPanel.noteAdvanced(tr.advanced);
        statusBar.setGameTime(tr.time.toString());
        if (tr.events != null && !tr.events.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonDeserializer.EventInfo e : tr.events) {
                sb.append("[").append(tr.time).append("] ").append(e.describe()).append('\n');
                // 协议 §3.15：events[].kind=="bankrupt" 记流水（弹窗由 type:"bankrupt" 推送负责）
                if ("bankrupt".equals(e.kind)) {
                    bankruptAccounts.add(e.accountText());
                    statusBar.setEngineStatus(e.accountText() + "已破产", false);
                }
                // 协议 §3.8a：过期挂单引擎会移出 orders，这里留档以便面板灰显"已过期"
                if ("order_expired".equals(e.kind)) {
                    orderPanel.noteExpired(e);
                }
                // 协议 §3.8b：强平事件给出预警流水
                if ("margin_call".equals(e.kind)) {
                    statusBar.setEngineStatus("外汇强平中（保证金水平 "
                            + String.format(java.util.Locale.ROOT, "%.1f%%", e.level) + "）", false);
                }
            }
            logPanel.append(sb.toString().trim());
        }
        updateChartsFromEngine();
        engine.snapshotModel().whenComplete((s, ex) -> {
            if (ex == null) {
                SwingUtilities.invokeLater(() -> onSnapshot(s));
            }
        });
        engine.ordersModel("all").whenComplete((list, ex) -> {
            if (ex == null) {
                SwingUtilities.invokeLater(() -> orderPanel.update(list));
            }
        });
    }

    /** 按账户给出区分的破产提示（协议 §3.15：account = stock | forex）。 */
    private void onBankrupt(String account) {
        String acct = "forex".equals(account) ? "外汇账户" : ("stock".equals(account) ? "股票账户" : "账户");
        String extra = "forex".equals(account)
                ? "\n\n外汇爆仓线为保证金水平 50%（协议 §3.8b），强平按亏损最大持仓优先。"
                : "";
        SwingUtilities.invokeLater(() -> {
            statusBar.setEngineStatus(acct + "已破产", false);
            logPanel.append("[破产] " + acct + " 进入破产状态（引擎主动推送）");
            Dialogs.warn(this, "💥 你的" + acct + "已破产！" + extra
                    + "\n\n可以继续操作（时间照常推进），"
                    + "或使用「作弊器」注入资金 / 解除破产后继续游戏。\n\n"
                    + "本游戏为纯模拟，不涉及任何真实资金。");
        });
    }

    private final AtomicBoolean bankruptShown = new AtomicBoolean();

    /** 已破产账户（来自 events[].kind=="bankrupt" 流水）。 */
    private final java.util.Set<String> bankruptAccounts = new java.util.LinkedHashSet<>();

    private void showBankruptOnce() {
        if (bankruptShown.compareAndSet(false, true)) {
            onBankrupt("");
        }
    }

    /** 破产状态解除后允许再次提示。 */
    private void resetBankruptFlag() {
        bankruptShown.set(false);
    }

    // ------------------------------------------------------------ 图表/交易面板联动

    private void updateCharts() {
        if (lastMarket == null) {
            return;
        }
        if ("stock".equals(selectedMarket)) {
            JsonDeserializer.StockQuote q = lastMarket.stock(selectedSymbol);
            if (q != null) {
                kline.setQuote(q, 2);
                // 分时图对股票同样喂数据，避免切页后看到上一只标的的旧图
                tickChart.setBars(q.symbol, q.name, q.hist, q.prevClose, 2);
            } else {
                kline.setSymbol(selectedSymbol, "");
                tickChart.setSymbol(selectedSymbol, "");
            }
        } else {
            JsonDeserializer.ForexQuote q = lastMarket.forex(selectedSymbol);
            if (q != null) {
                tickChart.setQuote(q);
                // 外汇也有 hist：K 线图必须一并喂，否则停在上一只股票的图上（K 线不动）
                kline.setForexQuote(q);
            } else {
                tickChart.setSymbol(selectedSymbol, "");
                kline.setSymbol(selectedSymbol, "");
            }
        }
    }

    /** 切换市场时同步切换左侧图表页：股票看 K 线，外汇看分时。
     *
     * <p>只在市场真的变化时切页，避免用户手动选页后被每秒的 tick 强行拉回。</p> */
    private void syncChartTabToMarket() {
        int want = "stock".equals(selectedMarket) ? 0 : 1;
        if (chartTabs.getSelectedIndex() != want) {
            chartTabs.setSelectedIndex(want);
        }
    }

    private void updateTradePanel() {
        if (lastMarket == null) {
            return;
        }
        updateTradePanelContext();
        if ("stock".equals(selectedMarket)) {
            JsonDeserializer.StockQuote q = lastMarket.stock(selectedSymbol);
            double power = lastSnapshot == null ? 0 : lastSnapshot.stockAccount.buyingPower;
            tradePanel.showStock(q, power, sellableOf(selectedSymbol), 2);
        } else {
            JsonDeserializer.ForexQuote q = lastMarket.forex(selectedSymbol);
            double free = lastSnapshot == null ? 0 : lastSnapshot.forexAccount.freeMargin;
            tradePanel.showForex(q, free);
        }
    }

    private void updateTradePanelContext() {
        if (lastSnapshot == null) {
            return;
        }
        if ("stock".equals(selectedMarket)) {
            double last = 0;
            if (lastMarket != null) {
                JsonDeserializer.StockQuote q = lastMarket.stock(selectedSymbol);
                if (q != null) {
                    last = q.last;
                }
            }
            tradePanel.setStockContext(last, lastSnapshot.stockAccount.buyingPower,
                    sellableOf(selectedSymbol), 2);
            // 注入持仓与浮动盈亏（右侧面板显示盈亏）
            JsonDeserializer.StockPosition pos = positionOf(selectedSymbol);
            if (pos == null) {
                tradePanel.setPositionContext(0, 0, 0, 0, 0);
            } else {
                tradePanel.setPositionContext(pos.qty, pos.avgCost, pos.pnl, pos.pnlPct, pos.todayBoughtQty);
            }
            // 下半区：多头 + 空头持仓行（按浮动盈亏排序，带卖出/平仓按钮）
            if (pos == null) {
                tradePanel.setPositionRows(0, 0, 0, 0, 0, 0, 0, 0, 0);
            } else {
                tradePanel.setPositionRows(pos.qty, pos.avgCost, pos.pnl, pos.pnlPct, pos.todayBoughtQty,
                        pos.shortQty, pos.shortAvgPrice, pos.shortPnl, pos.todayShortedQty);
            }
        } else {
            // 外汇卡片：显示该货币对的持仓手数与浮动盈亏
            tradePanel.setForexPositionContext(forexPositionText(selectedSymbol),
                    forexPositionPnl(selectedSymbol));
            // 下半区持仓行同样要刷成外汇，否则会留着上一次股票的「暂无持仓」，
            // 与上方「多单 1 手 · 均价 …」自相矛盾（用户反馈「汇市订单完全不显示」）。
            tradePanel.setForexRows(forexRowsOf(selectedSymbol));
        }
    }

    private int sellableOf(String symbol) {
        for (StockPosition p : stockPositions) {
            if (p.symbol.equals(symbol)) {
                return p.sellableQty();
            }
        }
        return 0;
    }

    /** 查找某只股票的持仓（无则 null）。 */
    private StockPosition positionOf(String symbol) {
        for (StockPosition p : stockPositions) {
            if (p.symbol.equals(symbol)) {
                return p;
            }
        }
        return null;
    }

    /** 汇总某个货币对的持仓描述，如 "多单 2 手 · 均价 1.0812"。 */
    private String forexPositionText(String symbol) {
        int longLots = 0;
        int shortLots = 0;
        double wAvg = 0;
        int total = 0;
        for (ForexPosition p : forexPositions) {
            if (!p.symbol.equals(symbol)) {
                continue;
            }
            if ("long".equals(p.side)) {
                longLots += p.lots;
            } else {
                shortLots += p.lots;
            }
            wAvg += p.openRate * p.lots;
            total += p.lots;
        }
        if (total == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (longLots > 0) {
            sb.append("多单 ").append(longLots).append(" 手");
        }
        if (shortLots > 0) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append("空单 ").append(shortLots).append(" 手");
        }
        sb.append(" · 均价 ").append(UITheme.price(total > 0 ? wAvg / total : 0, 4));
        return sb.toString();
    }

    /** 某个货币对的持仓行（多单 / 空单各一条，按持仓聚合成行）。 */
    private java.util.List<TradePanel.ForexRow> forexRowsOf(String symbol) {
        java.util.List<TradePanel.ForexRow> out = new java.util.ArrayList<>();
        for (ForexPosition p : forexPositions) {
            if (!p.symbol.equals(symbol)) {
                continue;
            }
            double pnl = p.pnl + p.swap;
            // 保证金收益率：以占用保证金为分母（外汇没有「股数」，用保证金口径最直观）
            double pct = p.margin > 1e-9 ? pnl / p.margin : 0.0;
            out.add(TradePanel.ForexRow.of(p.side, p.symbol, p.name,
                    p.lots, p.openRate, pnl, pct, p.positionId));
        }
        return out;
    }

    /** 某个货币对的浮动盈亏合计（含隔夜利息）。 */
    private double forexPositionPnl(String symbol) {
        double pnl = 0;
        boolean any = false;
        for (ForexPosition p : forexPositions) {
            if (p.symbol.equals(symbol)) {
                pnl += p.pnl + p.swap;
                any = true;
            }
        }
        return any ? pnl : 0;
    }

    private void updateChartsFromEngine() {
        engine.marketModel("all").whenComplete((md, ex) -> {
            if (ex == null) {
                SwingUtilities.invokeLater(() -> onMarket(md));
            }
        });
    }

    // ------------------------------------------------------------ 交易动作

    private void quickTrade(String side) {
        String sym = marketTable.selectedSymbol();
        if (sym == null || sym.isEmpty()) {
            Dialogs.warn(this, "请先在行情表中选择一个标的");
            return;
        }
        if ("stock".equals(marketTable.selectedMarket())) {
            Dialogs.info(this, "已选中 " + sym + "，请在右侧交易面板填写数量后下单。");
        } else {
            Dialogs.info(this, "已选中外汇 " + sym + "，请在右侧交易面板选择方向与手数。");
        }
    }

    private void doStockOrder(String side, String type, int qty, double price, int leverage) {
        if (qty % 100 != 0 || qty <= 0) {
            Dialogs.error(this, "股票数量必须是 100 的整数倍（当前 " + qty + "）");
            return;
        }
        String sym = selectedSymbol;
        if (sym == null || sym.isEmpty()) {
            Dialogs.warn(this, "请先选择标的");
            return;
        }
        statusBar.setEngineStatus("正在下单 ...", true);
        engine.tradeModel(side, sym, qty, type, price, leverage).whenComplete((tr, ex) -> SwingUtilities.invokeLater(() -> {
            statusBar.setEngineStatus("引擎运行中", true);
            if (ex != null) {
                showTradeError(side, ex);
                return;
            }
            Dialogs.success(this, String.format(java.util.Locale.ROOT,
                    "%s %s %s\n状态：%s\n成交：%d 股 @ %s\n手续费：%s\n剩余现金：%s",
                    "buy".equals(side) ? "买入" : "卖出", sym,
                    "limit".equals(type) ? "限价" : "市价",
                    tr.statusText(), tr.filled, UITheme.price(tr.avgPrice, 2),
                    UITheme.money(tr.commission), UITheme.money(tr.cash)));
            afterTrade();
        }));
    }

    /** 股票做空 / 平空。 */
    private void doStockShort(String action, String type, int qty, double price, int leverage) {
        if (qty <= 0 || qty % 100 != 0) {
            Dialogs.error(this, "股票数量必须是 100 的整数倍（当前 " + qty + "）");
            return;
        }
        String sym = selectedSymbol;
        if (sym == null || sym.isEmpty()) {
            Dialogs.warn(this, "请先选择标的");
            return;
        }
        statusBar.setEngineStatus("正在下单 ...", true);
        engine.stockShortModel(action, sym, qty, type, price, leverage)
                .whenComplete((res, ex) -> SwingUtilities.invokeLater(() -> {
                    statusBar.setEngineStatus("引擎运行中", true);
                    if (ex != null) {
                        showTradeError(action, ex);
                        return;
                    }
                    boolean isShort = "short".equals(action);
                    Dialogs.success(this, String.format(java.util.Locale.ROOT,
                            "%s %s\n成交：%d 股 @ %s\n手续费：%s\n%s：%s",
                            isShort ? "做空卖出" : "平空买入", sym,
                            Json.lng(res, "filled"), UITheme.price(Json.num(res, "avgPrice"), 2),
                            UITheme.money(Json.num(res, "commission")),
                            isShort ? "已冻结保证金" : "已实现盈亏",
                            isShort ? UITheme.money(Json.num(res, "shortMargin"))
                                    : UITheme.money(Json.num(res, "realizedPnl"))));
                    afterTrade();
                }));
    }

    private void doForexOpen(String side, int lots, int leverage, double stopLoss, double takeProfit) {
        String sym = selectedSymbol;
        engine.forexOpen(sym, side, lots, leverage, stopLoss, takeProfit)
                .whenComplete((d, ex) -> SwingUtilities.invokeLater(() -> {
                    if (ex != null) {
                        showTradeError(side, ex);
                        return;
                    }
                    Dialogs.success(this, String.format(java.util.Locale.ROOT,
                            "%s %s %d 手 @ %s\n持仓号：%d\n占用保证金：%s",
                            "long".equals(side) ? "做多" : "做空", sym, lots,
                            UITheme.price(Json.num(d, "openRate"), 4),
                            Json.lng(d, "positionId"), UITheme.money(Json.num(d, "margin"))));
                    afterTrade();
                }));
    }

    private void doForexClose(int positionId, int lots) {
        engine.forexClose(positionId, lots).whenComplete((d, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                Dialogs.error(this, "平仓失败：" + errorMessage(ex));
                return;
            }
            Dialogs.success(this, "持仓 " + positionId + " 已平仓 " + lots + " 手");
            afterTrade();
        }));
    }

    private void doCancel(int orderId) {
        engine.cancel(orderId).whenComplete((d, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                Dialogs.error(this, "撤单失败：" + errorMessage(ex));
                return;
            }
            Dialogs.success(this, "订单 " + orderId + " 已撤销，退回资金 "
                    + UITheme.money(Json.num(d, "refundedCash")));
            afterTrade();
        }));
    }

    private void afterTrade() {
        engine.snapshotModel().whenComplete((s, ex) -> {
            if (ex == null) {
                SwingUtilities.invokeLater(() -> onSnapshot(s));
            }
        });
        refreshHistory();
        refreshOrders();
        updateChartsFromEngine();
    }

    private void showTradeError(String side, Throwable ex) {
        String msg = errorMessage(ex);
        boolean t1 = ex instanceof EngineClient.EngineError
                && ((EngineClient.EngineError) ex).isT1Locked();
        boolean sell = "sell".equals(side) || "short".equals(side);
        if (t1 || (sell && msg.contains("T+1"))) {
            Dialogs.warn(this, "T+1 锁定：当日买入的股票需次日开盘后才可卖出。\n\n引擎返回：" + msg
                    + "\n\n提示：推进到次日后即可卖出，或在「作弊器 → 解锁 T+1」中立即解除。");
        } else {
            Dialogs.error(this, "下单失败：\n" + msg);
        }
    }

    // ------------------------------------------------------------ 时间推进

    private void stepSlots(int slots) {
        if (!engine.isAlive()) {
            Dialogs.error(this, "引擎已退出，请重启");
            return;
        }
        clockPanel.noteAdvanced(slots);
        engine.tickModel(slots, "auto").whenComplete((tr, ex) -> {
            if (ex != null) {
                async(() -> Dialogs.error(this, "推进时间失败：" + errorMessage(ex)));
                return;
            }
            SwingUtilities.invokeLater(() -> onTick(tr));
        });
    }

    private void applyClockSpeed(double speed, int tickMs, boolean start) {
        if (start) {
            engine.clock("start", Double.valueOf(speed), Integer.valueOf(tickMs)).whenComplete((d, ex) -> {
                if (ex != null) {
                    async(() -> Dialogs.error(this, "启动时钟失败：" + errorMessage(ex)));
                    return;
                }
                SwingUtilities.invokeLater(() -> applyClock(ClockState.of(d)));
            });
        } else {
            engine.clock("set", Double.valueOf(speed), Integer.valueOf(tickMs)).whenComplete((d, ex) -> {
                if (ex == null) {
                    SwingUtilities.invokeLater(() -> {
                        applyClock(ClockState.of(d));
                        if (!clockPanel.isRunning()) {
                            statusBar.setSpeed("时钟 " + UITheme.speedText(speed,
                                    ClockPanel.daysPerSecond(speed, tickMs)));
                        }
                    });
                }
            });
        }
    }

    private void applyClock(ClockState st) {
        if (st == null) {
            return;
        }
        clockPanel.update(st);
        statusBar.setSpeed("时钟 " + (st.running ? "自动 " : "暂停 ")
                + UITheme.speedText(st.speed, st.daysPerSecond()));
    }

    // ------------------------------------------------------------ 作弊器

    private void doCheat(Map<String, Object> args) {
        String op = String.valueOf(args.get("op"));
        engine.cheat(args).whenComplete((d, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                Dialogs.error(this, "作弊操作失败（" + op + "）：" + errorMessage(ex));
                return;
            }
            if ("list".equals(op)) {
                cheatPanel.setCheatItemsRaw(Json.array(d, "cheats"));
                cheatPanel.setDetail("引擎返回全部作弊项 " + Json.array(d, "cheats").size() + " 项");
            } else {
                String detail = Json.str(d, "detail");
                cheatPanel.setDetail(detail.isEmpty() ? ("作弊操作完成：" + op) : detail);
                logPanel.append("[作弊:" + op + "] " + detail);
            }
            Map<String, Object> cs = Json.object(d, "cheatState");
            if (!cs.isEmpty()) {
                cheatPanel.setCheatStateRaw(cs);
            }
            resetBankruptFlag();
            afterTrade();
        }));
    }

    // ------------------------------------------------------------ 菜单帮助

    private void actionNewGame() {
        String name = JOptionPane.showInputDialog(this, "请输入玩家名称：", "玩家");
        if (name == null) {
            return;
        }
        startNewGame(name.trim());
    }

    private void confirmExit() {
        int r = JOptionPane.showConfirmDialog(this, "确定要退出拟股喵喵吗？", "退出确认",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r == JOptionPane.OK_OPTION) {
            refreshTimer.stop();
            engine.shutdown();
            dispose();
            System.exit(0);
        }
    }

    private static void showHelp() {
        Dialogs.info(null, "操作提示：\n"
                + "1. 左侧「行情」表点击一行 → 右侧交易面板自动联动。\n"
                + "2. 股票买入数量必须是 100 的整数倍；快捷按钮可按 1/4、1/3、1/2、全仓自动填量。\n"
                + "3. 当日买入的股票 T+1 冻结，次日开盘后才可卖出（界面会先给出提示，引擎也会返回 T1_LOCKED）。\n"
                + "4. 外汇支持做多/做空、杠杆与止损止盈；1 手 = 1000 基础货币单位。\n"
                + "5. 底部时钟面板可调节倍速（0.25x~256x），或点「单步 1 片 / 单步 1 天」手动推进。\n"
                + "6. 「作弊器」用于测试各种极端行情与资金情况，全部为纯模拟功能。");
    }

    private static void showAbout() {
        Dialogs.info(null, "拟股喵喵  v1.0\n"
                + "C++ 引擎 + Java Swing 前端，本地单机运行。\n\n"
                + UITheme.SIM_NOTICE + "。\n"
                + "所有行情、新闻、账户均为程序生成的虚构数据，\n"
                + "本软件不含任何充值 / 提现 / 支付功能，也不接入任何真实市场。");
    }

    // ------------------------------------------------------------ 工具

    private void async(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    private void handleAsyncError(String what, Throwable ex) {
        async(() -> Dialogs.warn(this, what + "：" + errorMessage(ex)));
    }

    /** 把异常转成中文可读消息。 */
    public static String errorMessage(Throwable ex) {
        Throwable t = ex;
        while (t != null && !(t instanceof EngineClient.EngineError) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof EngineClient.EngineError) {
            EngineClient.EngineError e = (EngineClient.EngineError) t;
            return e.code().isEmpty() ? e.getMessage() : (e.getMessage() + "（" + e.code() + "）");
        }
        return t == null ? "未知错误" : String.valueOf(t.getMessage());
    }

    /** 主窗口内的引擎客户端（测试用）。 */
    public EngineClient engine() {
        return engine;
    }

    /** 主动刷新（供外部调用）。 */
    public void poke() {
        refreshAll();
    }

    /** 全局重绘（切换涨跌配色后调用）。 */
    private void repaintAll() {
        UITheme.applyTo(getContentPane());
        marketTable.repaint();
        kline.repaint();
        tickChart.repaint();
        positionPanel.repaint();
        orderPanel.repaint();
        historyPanel.repaint();
        holdingsPanel.repaint();
        mainTabs.repaint();
        repaint();
    }

    /** 关闭前释放资源。 */
    public void disposeAll() {
        refreshTimer.stop();
    }
}