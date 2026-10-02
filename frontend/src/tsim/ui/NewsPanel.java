package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

import tsim.json.JsonDeserializer.NewsInfo;

/**
 * 新闻面板：列表（未读加粗）+ 双击查看详情。
 */
public final class NewsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 刷新回调。 */
    public interface RefreshHandler {
        /** 请求重新拉取新闻。 */
        void onRefresh(boolean unreadOnly);
    }

    private static final String[] COLS = {"", "时间", "范围", "影响", "标题"};

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextArea detail = new JTextArea();
    private final JCheckBox unreadOnly = new JCheckBox("仅未读", false);
    private final JLabel summary = new JLabel(" ");
    private RefreshHandler handler;
    private List<NewsInfo> all = new ArrayList<>();

    /** 构造新闻面板。 */
    public NewsPanel() {
        super(new BorderLayout());
        setBackground(UITheme.PANEL);

        table.setFont(UITheme.SMALL_FONT);
        table.setForeground(UITheme.TEXT);
        table.setBackground(UITheme.PANEL);
        table.setGridColor(UITheme.GRID);
        table.setRowHeight(24);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new java.awt.Dimension(6, 1));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(UITheme.SELECT);
        table.setSelectionForeground(UITheme.TEXT);
        table.setFillsViewportHeight(true);
        JTableHeader h = table.getTableHeader();
        h.setFont(UITheme.SMALL_FONT);
        h.setBackground(UITheme.PANEL_DARK);
        h.setForeground(UITheme.TEXT_DIM);
        h.setReorderingAllowed(false);
        for (int i = 0; i < COLS.length; i++) {
            table.getColumnModel().getColumn(i).setHeaderValue(COLS[i]);
            table.getColumnModel().getColumn(i).setCellRenderer(new Renderer(i));
        }
        table.getColumnModel().getColumn(0).setMaxWidth(26);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    showDetail();
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                previewDetail();
            }
        });

        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setFont(UITheme.UI_FONT);
        detail.setForeground(UITheme.TEXT);
        detail.setBackground(UITheme.PANEL_DARK);
        detail.setCaretColor(UITheme.TEXT);
        detail.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        detail.setText("双击左侧新闻查看详情。\n\n提示：新闻仅用于模拟行情驱动，均属程序生成的虚构内容。");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(0.62);
        split.setBorder(null);
        split.setDividerSize(6);
        add(split, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        summary.setFont(UITheme.SMALL_FONT);
        summary.setForeground(UITheme.TEXT_DIM);
        south.add(summary, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        unreadOnly.setFont(UITheme.SMALL_FONT);
        unreadOnly.setOpaque(false);
        unreadOnly.setForeground(UITheme.TEXT_DIM);
        unreadOnly.addActionListener(e -> {
            if (handler != null) {
                handler.onRefresh(unreadOnly.isSelected());
            }
        });
        JButton refresh = new JButton(new AbstractAction("刷新") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (handler != null) {
                    handler.onRefresh(unreadOnly.isSelected());
                }
            }
        });
        refresh.setFont(UITheme.SMALL_FONT);
        JButton all = new JButton(new AbstractAction("全部标为已读（本地）") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                model.markAllRead();
            }
        });
        all.setFont(UITheme.SMALL_FONT);
        right.add(unreadOnly);
        right.add(all);
        right.add(refresh);
        south.add(right, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    /** 设置刷新回调。 */
    public void setRefreshHandler(RefreshHandler h) {
        this.handler = h;
    }

    /** 是否勾选了仅未读。 */
    public boolean isUnreadOnly() {
        return unreadOnly.isSelected();
    }

    /** 刷新新闻列表（保留已读状态合并）。 */
    public void update(List<NewsInfo> news) {
        all = news == null ? new ArrayList<>() : news;
        model.setRows(all);
        int unread = 0;
        for (NewsInfo n : all) {
            if (!n.read) {
                unread++;
            }
        }
        summary.setText("共 " + all.size() + " 条，未读 " + unread + " 条");
        summary.setForeground(unread > 0 ? UITheme.WARN : UITheme.TEXT_DIM);
    }

    private NewsInfo selected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        return model.at(table.convertRowIndexToModel(row));
    }

    private void previewDetail() {
        NewsInfo n = selected();
        if (n == null) {
            return;
        }
        detail.setText(format(n));
        detail.setCaretPosition(0);
    }

    private void showDetail() {
        NewsInfo n = selected();
        if (n == null) {
            return;
        }
        model.markRead(n);
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(n.scopeText()).append("】").append(n.time).append('\n');
        sb.append("影响：").append(UITheme.pct3(n.impact)).append('\n');
        if (!n.symbols.isEmpty()) {
            sb.append("相关：");
            for (Object s : n.symbols) {
                sb.append(s).append(' ');
            }
            sb.append('\n');
        }
        sb.append("────────────────────\n");
        sb.append(n.body.isEmpty() ? "（本条新闻无正文）" : n.body);
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
    }

    private static String format(NewsInfo n) {
        return "【" + n.scopeText() + "】" + n.time + "\n影响 " + UITheme.pct3(n.impact)
                + "   相关 " + n.symbols + "\n\n" + n.body;
    }

    private static final class Model extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private List<NewsInfo> rows = new ArrayList<>();
        private final List<Integer> localRead = new ArrayList<>();

        void setRows(List<NewsInfo> r) {
            rows = r == null ? new ArrayList<>() : r;
            fireTableDataChanged();
        }

        NewsInfo at(int i) {
            return i >= 0 && i < rows.size() ? rows.get(i) : null;
        }

        boolean isRead(int i) {
            NewsInfo n = at(i);
            if (n == null) {
                return true;
            }
            return n.read || localRead.contains(Integer.valueOf(n.id));
        }

        void markRead(NewsInfo n) {
            if (n != null && !n.read && !localRead.contains(Integer.valueOf(n.id))) {
                localRead.add(Integer.valueOf(n.id));
                fireTableDataChanged();
            }
        }

        void markAllRead() {
            localRead.clear();
            for (NewsInfo n : rows) {
                if (!n.read && !localRead.contains(Integer.valueOf(n.id))) {
                    localRead.add(Integer.valueOf(n.id));
                }
            }
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
            NewsInfo n = rows.get(r);
            switch (c) {
                case 0:
                    return isRead(r) ? "" : "●";
                case 1:
                    return n.time.toString();
                case 2:
                    return n.scopeText();
                case 3:
                    return UITheme.pct3(n.impact);
                case 4:
                    return n.title.isEmpty() ? "(无标题)" : n.title;
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
            setHorizontalAlignment(col == 4 ? SwingConstants.LEFT : SwingConstants.CENTER);
            if (table.getModel() instanceof Model) {
                Model m = (Model) table.getModel();
                int mr = row < m.getRowCount() ? row : -1;
                boolean unread = mr >= 0 && !m.isRead(mr);
                if (col == 0 && unread) {
                    setForeground(UITheme.ACCENT);
                    setFont(UITheme.font(Font.BOLD, 14));
                }
                if (col == 4) {
                    setFont(unread ? UITheme.font(Font.BOLD, 12) : UITheme.SMALL_FONT);
                    setForeground(unread ? UITheme.TEXT : UITheme.TEXT_DIM);
                }
                if (col == 3) {
                    try {
                        double d = Double.parseDouble(String.valueOf(value).replace("+", "").replace("%", ""));
                        setForeground(UITheme.pnlColor(d));
                    } catch (RuntimeException ignored) {
                        // 保底
                    }
                }
            }
            return c;
        }
    }
}
