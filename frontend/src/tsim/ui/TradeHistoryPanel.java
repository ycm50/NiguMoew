package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

import tsim.json.JsonDeserializer.TradeInfo;

/**
 * 成交明细面板：含已实现盈亏，最新在前。
 */
public final class TradeHistoryPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 刷新回调。 */
    public interface RefreshHandler {
        /** 请求重新拉取成交流水。 */
        void onRefresh();
    }

    private static final String[] COLS = {"序号", "时间", "市场", "代码", "方向", "数量", "成交价", "成交额", "手续费", "已实现盈亏", "原因"};

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel(" ");
    private final javax.swing.JComboBox<String> marketFilter =
            new javax.swing.JComboBox<>(new String[]{"全部", "股票", "外汇"});
    private RefreshHandler handler;
    private List<TradeInfo> all = new ArrayList<>();

    /** 构造成交面板。 */
    public TradeHistoryPanel() {
        super(new BorderLayout());
        setBackground(UITheme.PANEL);
        table.setFont(UITheme.SMALL_FONT);
        table.setForeground(UITheme.TEXT);
        table.setBackground(UITheme.PANEL);
        table.setGridColor(UITheme.GRID);
        table.setRowHeight(22);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new java.awt.Dimension(6, 1));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(UITheme.SELECT);
        table.setSelectionForeground(UITheme.TEXT);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        JTableHeader h = table.getTableHeader();
        h.setFont(UITheme.SMALL_FONT);
        h.setBackground(UITheme.PANEL_DARK);
        h.setForeground(UITheme.TEXT_DIM);
        h.setReorderingAllowed(false);
        for (int i = 0; i < COLS.length; i++) {
            table.getColumnModel().getColumn(i).setHeaderValue(COLS[i]);
            table.getColumnModel().getColumn(i).setCellRenderer(new Renderer(i));
        }
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        summary.setFont(UITheme.SMALL_FONT);
        summary.setForeground(UITheme.TEXT_DIM);
        south.add(summary, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        marketFilter.setFont(UITheme.SMALL_FONT);
        marketFilter.addActionListener(e -> applyFilter());
        JButton refresh = new JButton(new AbstractAction("刷新") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (handler != null) {
                    handler.onRefresh();
                }
            }
        });
        refresh.setFont(UITheme.SMALL_FONT);
        right.add(new JLabel("市场"));
        right.add(marketFilter);
        right.add(refresh);
        south.add(right, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    /** 设置刷新回调。 */
    public void setRefreshHandler(RefreshHandler h) {
        this.handler = h;
    }

    /** 用最新成交流水刷新。 */
    public void update(List<TradeInfo> trades) {
        all = trades == null ? new ArrayList<>() : trades;
        applyFilter();
    }

    private void applyFilter() {
        int idx = marketFilter.getSelectedIndex();
        List<TradeInfo> shown = new ArrayList<>();
        double realized = 0;
        double commission = 0;
        for (TradeInfo t : all) {
            if (idx == 1 && !"stock".equals(t.market)) {
                continue;
            }
            if (idx == 2 && !"forex".equals(t.market)) {
                continue;
            }
            shown.add(t);
            realized += t.realizedPnl;
            commission += t.commission;
        }
        model.setRows(shown);
        summary.setText(String.format(java.util.Locale.ROOT, "共 %d 笔 · 已实现盈亏 %s · 累计手续费 %s",
                shown.size(), UITheme.moneySigned(realized), UITheme.money(commission)));
        summary.setForeground(UITheme.pnlColor(realized));
    }

    private static final class Model extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private List<TradeInfo> rows = new ArrayList<>();

        void setRows(List<TradeInfo> r) {
            rows = r == null ? new ArrayList<>() : r;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        @Override
        public Object getValueAt(int r, int c) {
            TradeInfo t = rows.get(r);
            switch (c) {
                case 0:
                    return t.seq;
                case 1:
                    return t.time.toString();
                case 2:
                    return "forex".equals(t.market) ? "外汇" : "股票";
                case 3:
                    return t.symbol;
                case 4:
                    return t.sideText();
                case 5:
                    return UITheme.qty(t.qty);
                case 6:
                    return UITheme.price(t.price, 4);
                case 7:
                    return UITheme.money(t.amount);
                case 8:
                    return UITheme.money(t.commission);
                case 9:
                    return UITheme.moneySigned(t.realizedPnl);
                case 10:
                    return t.reasonText();
                default:
                    return "";
            }
        }
    }

    private static final class Renderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        private final int col;

        Renderer(int col) {
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
            setHorizontalAlignment(col == 3 || col == 4 || col == 10 ? SwingConstants.LEFT : SwingConstants.RIGHT);
            if (col == 4) {
                String t = value == null ? "" : value.toString();
                setForeground(t.contains("买") || t.contains("多") ? UITheme.upColor() : UITheme.downColor());
            }
            if (col == 9) {
                try {
                    double d = Double.parseDouble(String.valueOf(value).replace("+", "").replace(",", ""));
                    setForeground(UITheme.pnlColor(d));
                } catch (RuntimeException ignored) {
                    // 保留默认色
                }
            }
            return c;
        }
    }
}
