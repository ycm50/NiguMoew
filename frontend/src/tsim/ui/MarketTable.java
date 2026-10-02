package tsim.ui;

import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

import tsim.json.JsonDeserializer.ForexQuote;
import tsim.json.JsonDeserializer.MarketData;
import tsim.json.JsonDeserializer.StockQuote;

/**
 * 行情表：股票 / 外汇两张表，涨跌着色，点击行联动图表与交易面板。
 */
public final class MarketTable extends javax.swing.JPanel {

    private static final long serialVersionUID = 1L;

    /** 选中标的时的回调。 */
    public interface SelectionListener {
        /**
         * 用户（或程序）选中了一个标的。
         *
         * @param market stock / forex
         * @param symbol 代码
         */
        void onSelect(String market, String symbol);
    }

    private static final String[] STOCK_COLS = {"代码", "名称", "最新", "涨跌幅", "涨跌", "今开", "最高", "最低", "成交量", "买一", "卖一", "市盈"};
    private static final String[] FOREX_COLS = {"代码", "名称", "最新", "涨跌幅", "今开", "最高", "最低", "买价", "卖价", "点差", "小数位"};

    private final StockModel stockModel = new StockModel();
    private final ForexModel forexModel = new ForexModel();
    private final JTable stockTable = new JTable(stockModel);
    private final JTable forexTable = new JTable(forexModel);
    private final JTable[] tables = {stockTable, forexTable};
    private final List<SelectionListener> listeners = new ArrayList<>();

    private String selectedMarket = "stock";
    private String selectedSymbol = "";

    /** 构造行情表（内部为 Tab：股票 / 外汇）。 */
    public MarketTable() {
        setLayout(new java.awt.BorderLayout());
        styleTable(stockTable, STOCK_COLS);
        styleTable(forexTable, FOREX_COLS);

        for (int i = 0; i < tables.length; i++) {
            final String mk = i == 0 ? "stock" : "forex";
            JTable t = tables[i];
            t.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) {
                    return;
                }
                int row = t.getSelectedRow();
                if (row < 0) {
                    return;
                }
                int modelRow = t.convertRowIndexToModel(row);
                String sym = mk.equals("stock") ? stockModel.symbolAt(modelRow) : forexModel.symbolAt(modelRow);
                if (sym == null || sym.isEmpty()) {
                    return;
                }
                selectedMarket = mk;
                selectedSymbol = sym;
                fireSelect(mk, sym);
            });
            t.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() >= 1 && t.getSelectedRow() >= 0) {
                        int modelRow = t.convertRowIndexToModel(t.getSelectedRow());
                        String sym = mk.equals("stock") ? stockModel.symbolAt(modelRow) : forexModel.symbolAt(modelRow);
                        if (sym != null && !sym.isEmpty()) {
                            selectedMarket = mk;
                            selectedSymbol = sym;
                            fireSelect(mk, sym);
                        }
                    }
                }
            });
        }

        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.setFont(UITheme.SMALL_FONT);
        tabs.addTab("股票", new JScrollPane(stockTable));
        tabs.addTab("外汇", new JScrollPane(forexTable));
        tabs.addChangeListener(e -> {
            int idx = tabs.getSelectedIndex();
            if (idx == 0) {
                selectedMarket = "stock";
            } else if (idx == 1) {
                selectedMarket = "forex";
            }
        });
        add(tabs, java.awt.BorderLayout.CENTER);
    }

    private void styleTable(JTable t, String[] cols) {
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
        t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        JTableHeader head = t.getTableHeader();
        head.setFont(UITheme.SMALL_FONT);
        head.setBackground(UITheme.PANEL_DARK);
        head.setForeground(UITheme.TEXT_DIM);
        head.setReorderingAllowed(false);
        for (int i = 0; i < cols.length && i < t.getColumnModel().getColumnCount(); i++) {
            javax.swing.table.TableColumn col = t.getColumnModel().getColumn(i);
            col.setHeaderValue(cols[i]);
            col.setCellRenderer(new QuoteRenderer(i));
            // 代码/名称列给足宽度，避免出现 "S..." "模..." 这种省略号
            if (i == 0) {
                col.setPreferredWidth(92);
                col.setMinWidth(84);
            } else if (i == 1) {
                col.setPreferredWidth(110);
                col.setMinWidth(96);
            } else {
                col.setPreferredWidth(72);
                col.setMinWidth(56);
            }
        }
    }

    /** 注册选中回调。 */
    public void addSelectionListener(SelectionListener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    /** 当前选中市场。 */
    public String selectedMarket() {
        return selectedMarket;
    }

    /** 当前选中代码。 */
    public String selectedSymbol() {
        return selectedSymbol;
    }

    /** 程序化选中（不发事件）。 */
    public void selectQuietly(String market, String symbol) {
        selectedMarket = market;
        selectedSymbol = symbol;
    }

    private void fireSelect(String market, String symbol) {
        for (SelectionListener l : listeners) {
            l.onSelect(market, symbol);
        }
    }

    /** 用行情数据刷新两张表（保持选中行）。 */
    public void update(MarketData md) {
        if (md == null) {
            return;
        }
        stockModel.setRows(md.stocks);
        forexModel.setRows(md.forex);
        restoreSelection();
    }

    private void restoreSelection() {
        if ("stock".equals(selectedMarket)) {
            int idx = stockModel.indexOf(selectedSymbol);
            if (idx >= 0) {
                stockTable.getSelectionModel().setSelectionInterval(idx, idx);
            }
        } else {
            int idx = forexModel.indexOf(selectedSymbol);
            if (idx >= 0) {
                forexTable.getSelectionModel().setSelectionInterval(idx, idx);
            }
        }
    }

    /** 股票表（供交易面板取引用）。 */
    public JTable stockTable() {
        return stockTable;
    }

    /** 外汇表。 */
    public JTable forexTable() {
        return forexTable;
    }

    // ------------------------------------------------------------ 表格模型

    /** 股票行情模型。 */
    private static final class StockModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private List<StockQuote> rows = new ArrayList<>();

        void setRows(List<StockQuote> r) {
            rows = r == null ? new ArrayList<>() : r;
            fireTableDataChanged();
        }

        String symbolAt(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row).symbol : "";
        }

        int indexOf(String symbol) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).symbol.equals(symbol)) {
                    return i;
                }
            }
            return -1;
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
        public String getColumnName(int column) {
            return STOCK_COLS[column];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        @Override
        public Object getValueAt(int r, int c) {
            StockQuote q = rows.get(r);
            switch (c) {
                case 0:
                    return q.symbol;
                case 1:
                    return q.name;
                case 2:
                    return UITheme.price(q.last, 2);
                case 3:
                    return UITheme.pct(q.changePct);
                case 4:
                    return UITheme.moneySigned(q.last - q.prevClose);
                case 5:
                    return UITheme.price(q.open, 2);
                case 6:
                    return UITheme.price(q.high, 2);
                case 7:
                    return UITheme.price(q.low, 2);
                case 8:
                    return UITheme.volume(q.volume);
                case 9:
                    return UITheme.price(q.bid, 2);
                case 10:
                    return UITheme.price(q.ask, 2);
                case 11:
                    return String.format(java.util.Locale.ROOT, "%.2f", q.pe);
                default:
                    return "";
            }
        }
    }

    /** 外汇行情模型。 */
    private static final class ForexModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private List<ForexQuote> rows = new ArrayList<>();

        void setRows(List<ForexQuote> r) {
            rows = r == null ? new ArrayList<>() : r;
            fireTableDataChanged();
        }

        String symbolAt(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row).symbol : "";
        }

        int indexOf(String symbol) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).symbol.equals(symbol)) {
                    return i;
                }
            }
            return -1;
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
        public String getColumnName(int column) {
            return FOREX_COLS[column];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        @Override
        public Object getValueAt(int r, int c) {
            ForexQuote q = rows.get(r);
            switch (c) {
                case 0:
                    return q.symbol;
                case 1:
                    return q.name;
                case 2:
                    return UITheme.price(q.last, q.digits);
                case 3:
                    return UITheme.pct3(q.changePct);
                case 4:
                    return UITheme.price(q.open, q.digits);
                case 5:
                    return UITheme.price(q.high, q.digits);
                case 6:
                    return UITheme.price(q.low, q.digits);
                case 7:
                    return UITheme.price(q.bid, q.digits);
                case 8:
                    return UITheme.price(q.ask, q.digits);
                case 9:
                    return UITheme.price(q.spread, q.digits + 1);
                case 10:
                    return String.valueOf(q.digits);
                default:
                    return "";
            }
        }
    }

    /** 涨跌着色渲染器；成交/涨跌幅列按正负着色。 */
    private static final class QuoteRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        private final int col;

        QuoteRenderer(int col) {
            this.col = col;
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setFont(UITheme.SMALL_FONT);
            if (!isSelected) {
                setBackground(row % 2 == 0 ? UITheme.PANEL : UITheme.BG);
                setForeground(col <= 1 ? UITheme.TEXT : UITheme.TEXT_DIM);
            }
            if (col == 1) {
                setHorizontalAlignment(SwingConstants.LEFT);
            } else if (col == 0) {
                setHorizontalAlignment(SwingConstants.LEFT);
            } else {
                setHorizontalAlignment(SwingConstants.RIGHT);
            }
            if (col == 3 || col == 4) {
                String txt = value == null ? "" : value.toString();
                try {
                    double d = Double.parseDouble(txt.replace("+", "").replace(",", "").replace("%", ""));
                    setForeground(UITheme.pnlColor(d));
                    setFont(UITheme.font(Font.BOLD, 12));
                } catch (NumberFormatException ignored) {
                    // 保留默认颜色
                }
            }
            return c;
        }
    }
}