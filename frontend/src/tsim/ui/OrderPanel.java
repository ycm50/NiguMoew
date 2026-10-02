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

import tsim.json.JsonDeserializer.OrderInfo;

/**
 * 未成交挂单面板：列出 open/partial 挂单，支持撤单。
 */
public final class OrderPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 撤单回调。 */
    public interface CancelHandler {
        /** 请求撤销指定订单。 */
        void onCancel(int orderId);
    }

    private static final String[] COLS = {"订单号", "市场", "代码", "方向", "类型", "委托价", "数量", "已成交", "状态", "委托时间"};

    private final Model model = new Model();
    /** 引擎已在 orders 里移除、但前端仍要展示为"已过期"的历史挂单（按订单号去重）。 */
    private final java.util.Map<Integer, OrderInfo> expiredHistory = new java.util.LinkedHashMap<>();
    private static final int EXPIRED_KEEP = 50;
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel(" ");
    private CancelHandler handler;

    /** 构造挂单面板。 */
    public OrderPanel() {
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
        JButton cancel = new JButton(new AbstractAction("撤单") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row < 0) {
                    Dialogs.warn(OrderPanel.this, "请先选择一条挂单");
                    return;
                }
                OrderInfo o = model.at(table.convertRowIndexToModel(row));
                if (o == null || handler == null) {
                    return;
                }
                if (!o.cancellable()) {
                    Dialogs.warn(OrderPanel.this, "该订单状态为「" + o.status + "」，无法撤销");
                    return;
                }
                handler.onCancel(o.id);
            }
        });
        cancel.setFont(UITheme.SMALL_FONT);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);
        btns.add(cancel);
        south.add(btns, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    /** 设置撤单回调。 */
    public void setCancelHandler(CancelHandler h) {
        this.handler = h;
    }

    /**
     * 记录一条"已过期"挂单（协议 §3.8a）。
     *
     * <p>引擎在挂单过期后会把它从 {@code orders}/{@code snapshot.orders} 中移除，
     * 因此仅靠刷新列表用户会看到委托"凭空消失"。这里把 {@code order_expired} 事件里的
     * 信息留在面板上，状态显示为灰色的"已过期"。</p>
     */
    public void noteExpired(tsim.json.JsonDeserializer.EventInfo e) {
        if (e == null || e.orderId <= 0) {
            return;
        }
        // 由事件字段合成一条 expired 记录，供表格展示
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", Long.valueOf(e.orderId));
        m.put("symbol", e.symbol);
        m.put("market", "stock");
        m.put("side", e.side);
        m.put("type", "limit");
        m.put("qty", Long.valueOf(e.qty));
        m.put("filled", Integer.valueOf(0));
        m.put("price", Double.valueOf(e.price));
        m.put("status", "expired");
        m.put("created", e.at == null ? null : tsim.json.Json.obj("date", e.at.date, "slot", Long.valueOf(e.at.slot)));
        expiredHistory.put(Integer.valueOf(e.orderId), OrderInfo.of(m));
        while (expiredHistory.size() > EXPIRED_KEEP) {
            Integer first = expiredHistory.keySet().iterator().next();
            expiredHistory.remove(first);
        }
        render();
    }

    /** 清空过期历史（新游戏时调用）。 */
    public void clearExpiredHistory() {
        expiredHistory.clear();
        render();
    }

    /** 当前保留的过期挂单条数（测试用）。 */
    public int expiredCount() {
        return expiredHistory.size();
    }

    /**
     * 刷新挂单列表。
     *
     * <p>协议 §3.8a：限价挂单 TTL = 20 个时间片，过期后 status 变 {@code expired} 并解冻。
     * 过期单不再可撤，但**仍要显示**（灰色 + "已过期"），用户才知道委托为什么消失了。</p>
     */
    public void update(List<OrderInfo> orders) {
        live = new ArrayList<>();
        if (orders != null) {
            for (OrderInfo o : orders) {
                if ("cancelled".equals(o.status)) {
                    continue;
                }
                live.add(o);
                // 引擎若仍保留该单（部分引擎实现），过期后就不再需要本地补记
                if (o.cancellable() || "expired".equals(o.status)) {
                    expiredHistory.remove(Integer.valueOf(o.id));
                }
            }
        }
        render();
    }

    private List<OrderInfo> live = new ArrayList<>();

    /** 合并"在挂单"与"已过期历史"后刷新表格。 */
    private void render() {
        List<OrderInfo> rows = new ArrayList<>(live);
        int openCount = 0;
        for (OrderInfo o : rows) {
            if (o.cancellable()) {
                openCount++;
            }
        }
        for (OrderInfo o : expiredHistory.values()) {
            rows.add(o);
        }
        model.setRows(rows);
        summary.setText("挂单 " + rows.size() + " 条（可撤 " + openCount + " 条，已过期 "
                + expiredHistory.size() + " 条——引擎已移出列表，此处保留展示）");
    }

    private static final class Model extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private List<OrderInfo> rows = new ArrayList<>();

        void setRows(List<OrderInfo> r) {
            rows = r == null ? new ArrayList<>() : r;
            fireTableDataChanged();
        }

        OrderInfo at(int i) {
            return i >= 0 && i < rows.size() ? rows.get(i) : null;
        }

        /** 该行是否为"已结束"状态（过期/成交/撤销）——用于灰化。 */
        boolean isInactive(int i) {
            OrderInfo x = at(i);
            if (x == null) {
                return true;
            }
            return "expired".equals(x.status) || "cancelled".equals(x.status)
                    || "filled".equals(x.status);
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
            OrderInfo o = rows.get(r);
            switch (c) {
                case 0:
                    return o.id;
                case 1:
                    return "forex".equals(o.market) ? "外汇" : "股票";
                case 2:
                    return o.symbol;
                case 3:
                    return o.sideText();
                case 4:
                    return "limit".equals(o.type) ? "限价" : "市价";
                case 5:
                    return UITheme.price(o.price, 2);
                case 6:
                    return UITheme.qty(o.qty);
                case 7:
                    return UITheme.qty(o.filled);
                case 8:
                    return statusText(o.status);
                case 9:
                    return o.created.toString();
                default:
                    return "";
            }
        }

        private static String statusText(String s) {
            switch (s) {
                case "open":
                    return "挂单中";
                case "partial":
                    return "部分成交";
                case "filled":
                    return "已成交";
                case "cancelled":
                    return "已撤销";
                case "expired":
                    return "已过期";
                default:
                    return s;
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
            // 过期/撤销的整行做灰化处理
            boolean dimRow = table.getModel() instanceof Model
                    && ((Model) table.getModel()).isInactive(row);
            if (dimRow && !isSelected) {
                setForeground(UITheme.alpha(UITheme.TEXT_DIM, 140));
            }
            setHorizontalAlignment(col == 2 || col == 3 ? SwingConstants.LEFT : SwingConstants.RIGHT);
            if (col == 3) {
                String t = value == null ? "" : value.toString();
                setForeground(t.contains("买") || t.contains("多") ? UITheme.upColor() : UITheme.downColor());
            }
            if (col == 8) {
                String st = value == null ? "" : value.toString();
                if ("已过期".equals(st)) {
                    // 协议 §3.8a：过期挂单灰色显示
                    setForeground(UITheme.TEXT_DIM);
                } else if ("已成交".equals(st)) {
                    setForeground(UITheme.OK);
                } else if ("已撤销".equals(st)) {
                    setForeground(UITheme.TEXT_DIM);
                } else {
                    setForeground(UITheme.WARN);
                }
            }
            return c;
        }
    }
}