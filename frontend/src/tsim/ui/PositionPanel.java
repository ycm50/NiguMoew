package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

import tsim.json.JsonDeserializer.ForexPosition;
import tsim.json.JsonDeserializer.StockPosition;

/**
 * 持仓表：股票持仓（含 T+1 冻结列）+ 外汇持仓，盈亏着色，可一键平仓。
 */
public final class PositionPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 平仓回调。 */
    public interface CloseHandler {
        /**
         * 请求平仓。
         *
         * @param market     stock / forex
         * @param positionId 外汇持仓号（stock 时为 0）
         * @param symbol     代码
         * @param qty        股票数量 / 外汇手数
         */
        void onClose(String market, int positionId, String symbol, int qty);
    }

    private static final String[] STOCK_COLS =
            {"代码", "名称", "持仓", "可卖", "T+1冻结", "成本价", "现价", "市值", "浮动盈亏", "盈亏比例"};
    private static final String[] FOREX_COLS =
            {"持仓号", "代码", "名称", "方向", "手数", "开仓价", "现价", "保证金", "浮动盈亏", "隔夜利息", "止损", "止盈"};

    private final StockPosModel stockModel = new StockPosModel();
    private final ForexPosModel forexModel = new ForexPosModel();
    private final JTable stockTable = new JTable(stockModel);
    private final JTable forexTable = new JTable(forexModel);
    private final JLabel summary = new JLabel(" ");

    private CloseHandler handler;

    /** 构造持仓面板。 */
    public PositionPanel() {
        super(new BorderLayout());
        setBackground(UITheme.PANEL);
        style(stockTable, STOCK_COLS);
        style(forexTable, FOREX_COLS);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(UITheme.SMALL_FONT);
        tabs.addTab("股票持仓", new JScrollPane(stockTable));
        tabs.addTab("外汇持仓", new JScrollPane(forexTable));
        add(tabs, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        summary.setFont(UITheme.SMALL_FONT);
        summary.setForeground(UITheme.TEXT_DIM);
        south.add(summary, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        JButton closeStock = new JButton(new AbstractAction("平仓（卖出选中股票）") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int row = stockTable.getSelectedRow();
                if (row < 0) {
                    Dialogs.warn(PositionPanel.this, "请先在股票持仓表中选择一行");
                    return;
                }
                int mr = stockTable.convertRowIndexToModel(row);
                StockPosition p = stockModel.at(mr);
                if (p == null || handler == null) {
                    return;
                }
                // 可卖 >0 时按可卖量下单；否则仍按持仓量下给引擎，由引擎给出 T1_LOCKED 才是权威结论
                int q = p.sellableQty() > 0
                        ? Math.max(100, (p.sellableQty() / 100) * 100)
                        : Math.max(100, (p.qty / 100) * 100);
                if (p.sellableQty() <= 0) {
                    Dialogs.warn(PositionPanel.this, "T+1 锁定：当日买入的 " + p.todayBoughtQty
                            + " 股需次日开盘后才可卖出。\n仍会向引擎提交平仓委托，以引擎返回为准。");
                }
                handler.onClose("stock", 0, p.symbol, q);
            }
        });
        closeStock.setFont(UITheme.SMALL_FONT);
        JButton closeForex = new JButton(new AbstractAction("平仓（选中外汇持仓全平）") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int row = forexTable.getSelectedRow();
                if (row < 0) {
                    Dialogs.warn(PositionPanel.this, "请先在外汇持仓表中选择一行");
                    return;
                }
                int mr = forexTable.convertRowIndexToModel(row);
                ForexPosition p = forexModel.at(mr);
                if (p == null || handler == null) {
                    return;
                }
                handler.onClose("forex", p.positionId, p.symbol, p.lots);
            }
        });
        closeForex.setFont(UITheme.SMALL_FONT);
        buttons.add(closeStock);
        buttons.add(closeForex);
        south.add(buttons, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    /** 设置平仓回调。 */
    public void setCloseHandler(CloseHandler h) {
        this.handler = h;
    }

    private static void style(JTable t, String[] cols) {
        t.setFont(UITheme.SMALL_FONT);
        t.setForeground(UITheme.TEXT);
        t.setBackground(UITheme.PANEL);
        t.setGridColor(UITheme.GRID);
        t.setRowHeight(22);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new java.awt.Dimension(6, 1));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setSelectionBackground(UITheme.SELECT);
        t.setSelectionForeground(UITheme.TEXT);
        t.setAutoCreateRowSorter(true);
        t.setFillsViewportHeight(true);
        JTableHeader h = t.getTableHeader();
        h.setFont(UITheme.SMALL_FONT);
        h.setBackground(UITheme.PANEL_DARK);
        h.setForeground(UITheme.TEXT_DIM);
        h.setReorderingAllowed(false);
        for (int i = 0; i < cols.length && i < t.getColumnModel().getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setHeaderValue(cols[i]);
            t.getColumnModel().getColumn(i).setCellRenderer(new PosRenderer(i));
        }
    }

    /** 刷新持仓。 */
    public void update(List<StockPosition> stocks, List<ForexPosition> forex) {
        stockModel.setRows(stocks);
        forexModel.setRows(forex);
        double sp = 0;
        double sf = 0;
        double frozen = 0;
        for (StockPosition p : stocks) {
            sp += p.pnl;
            sf += p.marketValue;
            frozen += p.todayBoughtQty;
        }
        for (ForexPosition p : forex) {
            sp += p.pnl + p.swap;
        }
        summary.setText(String.format(java.util.Locale.ROOT,
                "股票 %d 只（市值 %s，T+1 冻结 %s 股） · 外汇 %d 笔 · 合计浮动盈亏 %s",
                stocks.size(), UITheme.money(sf), UITheme.qty((long) frozen), forex.size(),
                UITheme.moneySigned(sp)));
        summary.setForeground(UITheme.pnlColor(sp));
    }

    /** 股票持仓列表（供交易面板读取）。 */
    public List<StockPosition> stockPositions() {
        return stockModel.rows;
    }

    // ------------------------------------------------------------ 模型

    private static final class StockPosModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private List<StockPosition> rows = new ArrayList<>();

        void setRows(List<StockPosition> r) {
            rows = r == null ? new ArrayList<>() : r;
            fireTableDataChanged();
        }

        StockPosition at(int i) {
            return i >= 0 && i < rows.size() ? rows.get(i) : null;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return STOCK_COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return STOCK_COLS[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        @Override
        public Object getValueAt(int r, int c) {
            StockPosition p = rows.get(r);
            switch (c) {
                case 0:
                    return p.symbol;
                case 1:
                    return p.name;
                case 2:
                    return UITheme.qty(p.qty);
                case 3:
                    return UITheme.qty(p.sellableQty());
                case 4:
                    return UITheme.qty(p.todayBoughtQty);
                case 5:
                    return UITheme.price(p.avgCost, 2);
                case 6:
                    return UITheme.price(p.last, 2);
                case 7:
                    return UITheme.money(p.marketValue);
                case 8:
                    return UITheme.moneySigned(p.pnl);
                case 9:
                    return UITheme.pct(p.pnlPct);
                default:
                    return "";
            }
        }
    }

    private static final class ForexPosModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private List<ForexPosition> rows = new ArrayList<>();

        void setRows(List<ForexPosition> r) {
            rows = r == null ? new ArrayList<>() : r;
            fireTableDataChanged();
        }

        ForexPosition at(int i) {
            return i >= 0 && i < rows.size() ? rows.get(i) : null;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return FOREX_COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return FOREX_COLS[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        @Override
        public Object getValueAt(int r, int c) {
            ForexPosition p = rows.get(r);
            switch (c) {
                case 0:
                    return p.positionId;
                case 1:
                    return p.symbol;
                case 2:
                    return p.name;
                case 3:
                    return p.sideText();
                case 4:
                    return p.lots;
                case 5:
                    return UITheme.price(p.openRate, 4);
                case 6:
                    return UITheme.price(p.last, 4);
                case 7:
                    return UITheme.money(p.margin);
                case 8:
                    return UITheme.moneySigned(p.pnl);
                case 9:
                    return UITheme.moneySigned(p.swap);
                case 10:
                    return p.stopLoss > 0 ? UITheme.price(p.stopLoss, 4) : "-";
                case 11:
                    return p.takeProfit > 0 ? UITheme.price(p.takeProfit, 4) : "-";
                default:
                    return "";
            }
        }
    }

    private static final class PosRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        private final int col;

        PosRenderer(int col) {
            this.col = col;
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setFont(UITheme.SMALL_FONT);
            if (!isSelected) {
                setBackground(row % 2 == 0 ? UITheme.PANEL : UITheme.BG);
                setForeground(UITheme.TEXT_DIM);
            }
            setHorizontalAlignment(col <= 2 ? SwingConstants.LEFT : SwingConstants.RIGHT);
            String cls = table.getModel().getColumnName(col);
            boolean pnlCol = cls.contains("盈亏") || cls.contains("利息");
            if (pnlCol) {
                String txt = value == null ? "" : value.toString();
                double d = parse(txt);
                setForeground(UITheme.pnlColor(d));
                setFont(UITheme.font(Font.BOLD, 12));
            } else if (col == 4 && table.getModel() instanceof StockPosModel) {
                setForeground(UITheme.WARN);
            }
            return c;
        }

        private static double parse(String s) {
            try {
                return Double.parseDouble(s.replace("+", "").replace(",", "").replace("%", ""));
            } catch (RuntimeException ex) {
                return 0;
            }
        }
    }
}