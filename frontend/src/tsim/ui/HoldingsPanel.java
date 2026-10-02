package tsim.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import tsim.json.JsonDeserializer.ForexAccount;
import tsim.json.JsonDeserializer.Snapshot;
import tsim.json.JsonDeserializer.StatInfo;
import tsim.json.JsonDeserializer.StockAccount;

/**
 * 资产总览：总资产曲线（自绘，本地记录历史权益）+ 股票/外汇账户卡片 + 统计。
 */
public final class HoldingsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int MAX_POINTS = 2400;

    private final EquityChart chart = new EquityChart();
    private final AccountCard stockCard = new AccountCard("股票账户", UITheme.ACCENT);
    private final AccountCard forexCard = new AccountCard("外汇账户", UITheme.OK);
    private final AccountCard statCard = new AccountCard("交易统计", UITheme.WARN);
    private final JLabel total = new JLabel("总资产 -");

    /** 构造资产总览。 */
    public HoldingsPanel() {
        super(new BorderLayout(8, 8));
        setBackground(UITheme.PANEL);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        total.setFont(UITheme.font(Font.BOLD, 18));
        total.setForeground(UITheme.TEXT);
        total.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));

        JPanel cards = new JPanel(new GridLayout(1, 3, 8, 0));
        cards.setOpaque(false);
        cards.add(stockCard);
        cards.add(forexCard);
        cards.add(statCard);
        cards.setPreferredSize(new Dimension(600, 190));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.setOpaque(false);
        top.add(total);

        add(top, BorderLayout.NORTH);
        add(chart, BorderLayout.CENTER);
        add(cards, BorderLayout.SOUTH);
    }

    /** 用快照刷新。 */
    public void update(Snapshot s) {
        if (s == null) {
            return;
        }
        StockAccount sa = s.stockAccount;
        ForexAccount fa = s.forexAccount;
        StatInfo st = s.stat;
        double eq = s.totalEquity();
        chart.addPoint(eq);
        total.setText(String.format(java.util.Locale.ROOT, "总资产 %s    起始 %s    盈亏 %s（%s）",
                UITheme.money(eq), UITheme.money(st.startEquity),
                UITheme.moneySigned(eq - st.startEquity),
                st.startEquity > 0 ? UITheme.pct((eq - st.startEquity) / st.startEquity) : "-"));
        total.setForeground(UITheme.pnlColor(eq - st.startEquity));

        stockCard.setRows(new String[][]{
            {"现金", UITheme.money(sa.cash)},
            {"冻结资金", UITheme.money(sa.frozen)},
            {"T+1 冻结现金", UITheme.money(sa.t1FrozenCash)},
            {"持仓市值", UITheme.money(sa.marketValue)},
            {"总权益", UITheme.money(sa.equity)},
            {"当日盈亏", UITheme.moneySigned(sa.pnlDay)},
            {"累计盈亏", UITheme.moneySigned(sa.pnlTotal)},
            {"可买额度", UITheme.money(sa.buyingPower)},
        }, sa.pnlTotal);

        forexCard.setRows(new String[][]{
            {"现金", UITheme.money(fa.cash)},
            {"占用保证金", UITheme.money(fa.margin)},
            {"净  值", UITheme.money(fa.equity)},
            {"可用保证金", UITheme.money(fa.freeMargin)},
            {"保证金水平", UITheme.marginLevelText(fa.marginLevel)},
            {"浮动盈亏", UITheme.moneySigned(fa.pnlFloat)},
            {"累计盈亏", UITheme.moneySigned(fa.pnlTotal)},
            {"已用手数", UITheme.qty(fa.usedLots) + " 手 " + fa.currency},
        }, fa.pnlTotal);

        // 协议 §3.8b：marginLevel < 100% 警戒（黄）、< 50% 危险（红）
        forexCard.highlightRow(4, UITheme.marginLevelColor(fa.marginLevel));

        statCard.setRows(new String[][]{
            {"成交笔数", String.valueOf(st.tradeCount)},
            {"盈利笔数", String.valueOf(st.winCount)},
            {"胜  率", UITheme.pct(st.winRate())},
            {"已实现盈亏", UITheme.moneySigned(st.realizedPnl)},
            {"累计手续费", UITheme.money(st.totalCommission)},
            {"起始权益", UITheme.money(st.startEquity)},
            {"游戏时间", s.time.toString()},
            {"破产状态", s.bankrupt ? "已破产" : "正常"},
        }, st.realizedPnl);

        chart.setBaseline(st.startEquity);
    }

    /** 清空权益曲线（新游戏时调用）。 */
    public void reset() {
        chart.clear();
        total.setText("总资产 -");
    }

    /** 曲线已记录的点数（测试用）。 */
    public int pointCount() {
        return chart.points.size();
    }

    // ------------------------------------------------------------ 账户卡片

    private static final class AccountCard extends JPanel {

        private static final long serialVersionUID = 1L;

        private final JLabel title;
        private final JPanel body = new JPanel();
        private JLabel[] keys = new JLabel[0];
        private JLabel[] vals = new JLabel[0];

        AccountCard(String name, Color accent) {
            super(new BorderLayout());
            setBackground(UITheme.PANEL_DARK);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UITheme.WIDGET),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)));
            title = new JLabel(name);
            title.setFont(UITheme.font(Font.BOLD, 14));
            title.setForeground(accent);
            title.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.WIDGET));
            add(title, BorderLayout.NORTH);
            body.setOpaque(false);
            body.setLayout(new GridLayout(1, 1));
            add(body, BorderLayout.CENTER);
        }

        /** 给指定行单独上色（用于保证金水平警戒/危险色）。 */
        void highlightRow(int index, Color color) {
            if (vals != null && index >= 0 && index < vals.length && vals[index] != null && color != null) {
                vals[index].setForeground(color);
                vals[index].setText(vals[index].getText());
            }
        }

        void setRows(String[][] rows, double highlight) {
            body.removeAll();
            body.setLayout(new GridLayout(rows.length, 2, 6, 1));
            keys = new JLabel[rows.length];
            vals = new JLabel[rows.length];
            for (int i = 0; i < rows.length; i++) {
                JLabel k = new JLabel(rows[i][0]);
                k.setFont(UITheme.SMALL_FONT);
                k.setForeground(UITheme.TEXT_DIM);
                JLabel v = new JLabel(rows[i][1], SwingConstants.RIGHT);
                v.setFont(UITheme.SMALL_FONT);
                v.setForeground(UITheme.TEXT);
                if (i == rows.length - 1) {
                    v.setForeground(UITheme.pnlColor(highlight));
                    v.setFont(UITheme.font(Font.BOLD, 12));
                }
                keys[i] = k;
                vals[i] = v;
                body.add(k);
                body.add(v);
            }
            body.revalidate();
            body.repaint();
        }
    }

    // ------------------------------------------------------------ 权益曲线

    private static final class EquityChart extends JPanel {

        private static final long serialVersionUID = 1L;

        private final List<Double> points = new ArrayList<>();
        private double baseline;

        EquityChart() {
            setBackground(UITheme.PANEL_DARK);
            setBorder(BorderFactory.createLineBorder(UITheme.WIDGET));
            setPreferredSize(new Dimension(600, 220));
        }

        void addPoint(double equity) {
            if (equity <= 0 && points.isEmpty()) {
                return;
            }
            points.add(Double.valueOf(equity));
            if (points.size() > MAX_POINTS) {
                points.remove(0);
            }
            repaint();
        }

        void clear() {
            points.clear();
            repaint();
        }

        void setBaseline(double b) {
            if (b > 0) {
                baseline = b;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(UITheme.PANEL_DARK);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setFont(UITheme.SMALL_FONT);

            if (points.size() < 2) {
                g2.setColor(UITheme.TEXT_DIM);
                String msg = "总资产曲线：推进时间后开始记录";
                g2.drawString(msg, 10, getHeight() / 2);
                g2.dispose();
                return;
            }
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            for (Double d : points) {
                lo = Math.min(lo, d.doubleValue());
                hi = Math.max(hi, d.doubleValue());
            }
            if (baseline > 0) {
                lo = Math.min(lo, baseline);
                hi = Math.max(hi, baseline);
            }
            double pad = Math.max((hi - lo) * 0.1, Math.abs(hi) * 0.001 + 1);
            lo -= pad;
            hi += pad;

            int left = 10;
            int right = Math.max(left + 10, getWidth() - 78);
            int top = 12;
            int bot = Math.max(top + 10, getHeight() - 16);

            g2.setColor(UITheme.GRID);
            for (int i = 0; i <= 4; i++) {
                int y = top + (bot - top) * i / 4;
                g2.drawLine(left, y, right, y);
                double v = hi - (hi - lo) * i / 4;
                g2.setColor(UITheme.TEXT_DIM);
                g2.drawString(UITheme.money0(v), right + 4, y + 4);
                g2.setColor(UITheme.GRID);
            }
            if (baseline > 0 && baseline >= lo && baseline <= hi) {
                int y = (int) Math.round(bot - (baseline - lo) / (hi - lo) * (bot - top));
                g2.setColor(UITheme.alpha(UITheme.WARN, 140));
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{5f, 4f}, 0f));
                g2.drawLine(left, y, right, y);
                g2.setStroke(new BasicStroke(1f));
            }

            int n = points.size();
            double last = points.get(n - 1).doubleValue();
            boolean up = last >= (baseline > 0 ? baseline : points.get(0).doubleValue());
            Color line = up ? UITheme.upColor() : UITheme.downColor();

            java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
            for (int i = 0; i < n; i++) {
                double x = left + (right - left) * (i / (double) (n - 1));
                double y = bot - (points.get(i).doubleValue() - lo) / (hi - lo) * (bot - top);
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            java.awt.geom.Path2D fill = (java.awt.geom.Path2D) path.clone();
            fill.lineTo(right, bot);
            fill.lineTo(left, bot);
            fill.closePath();
            g2.setPaint(new GradientPaint(0, top, UITheme.alpha(line, 110), 0, bot, UITheme.alpha(line, 0)));
            g2.fill(fill);
            g2.setColor(line);
            g2.setStroke(new BasicStroke(1.8f));
            g2.draw(path);
            g2.setStroke(new BasicStroke(1f));

            g2.setColor(UITheme.TEXT);
            g2.setFont(UITheme.font(Font.BOLD, 12));
            g2.drawString("总资产 " + UITheme.money(last), left + 2, top + 12);
            g2.dispose();
        }
    }
}