package tsim.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import tsim.json.JsonDeserializer.Bar;
import tsim.json.JsonDeserializer.ForexQuote;

/**
 * 外汇分时走势图：折线 + 渐变填充 + 昨收基准线（+ 十字光标提示）。
 */
public final class TickChart extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int RIGHT_AXIS_W = 74;
    private static final int BOTTOM_AXIS_H = 20;
    private static final int TOP_PAD = 18;
    private static final int LEFT_PAD = 6;

    private String symbol = "";
    private String name = "";
    private List<Bar> bars = new ArrayList<>();
    private double prevClose;
    private int digits = 4;
    private double last;

    private int hoverIndex = -1;

    /** 构造分时图。 */
    public TickChart() {
        setBackground(UITheme.PANEL_DARK);
        setPreferredSize(new Dimension(640, 260));
        setOpaque(true);
        setToolTipText("");
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int idx = indexAt(e.getX());
                if (idx != hoverIndex) {
                    hoverIndex = idx;
                    repaint();
                }
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                hoverIndex = -1;
                repaint();
            }
        });
    }

    /** 设置标的（清空）。 */
    public void setSymbol(String symbol, String name) {
        this.symbol = symbol == null ? "" : symbol;
        this.name = name == null ? "" : name;
        this.bars = new ArrayList<>();
        repaint();
    }

    /** 喂入外汇行情。 */
    public void setQuote(ForexQuote q) {
        if (q == null) {
            this.bars = new ArrayList<>();
            repaint();
            return;
        }
        this.symbol = q.symbol;
        this.name = q.name;
        this.prevClose = q.prevClose;
        this.digits = q.digits;
        this.last = q.last;
        this.bars = new ArrayList<>(q.hist);
        repaint();
    }

    /** 直接设置序列。 */
    public void setBars(String symbol, String name, List<Bar> bars, double prevClose, int digits) {
        this.symbol = symbol == null ? "" : symbol;
        this.name = name == null ? "" : name;
        this.bars = bars == null ? new ArrayList<>() : new ArrayList<>(bars);
        this.prevClose = prevClose;
        this.digits = digits <= 0 ? 4 : digits;
        if (!this.bars.isEmpty()) {
            this.last = this.bars.get(this.bars.size() - 1).close;
        }
        repaint();
    }

    /** 当前显示点位数。 */
    public int pointCount() {
        return bars.size();
    }

    // ------------------------------------------------------------ 几何

    private int plotLeft() {
        return LEFT_PAD;
    }

    private int plotRight() {
        return Math.max(plotLeft() + 40, getWidth() - RIGHT_AXIS_W);
    }

    private int plotTop() {
        return TOP_PAD;
    }

    private int plotBottom() {
        return Math.max(TOP_PAD + 30, getHeight() - BOTTOM_AXIS_H);
    }

    private double minVal() {
        double v = Double.MAX_VALUE;
        for (Bar b : bars) {
            v = Math.min(v, b.low > 0 ? b.low : b.close);
        }
        return v == Double.MAX_VALUE ? 0 : v;
    }

    private double maxVal() {
        double v = -Double.MAX_VALUE;
        for (Bar b : bars) {
            v = Math.max(v, b.high > 0 ? b.high : b.close);
        }
        return v == -Double.MAX_VALUE ? 1 : v;
    }

    private double loBound() {
        double lo = Math.min(minVal(), prevClose > 0 ? prevClose : minVal());
        double hi = Math.max(maxVal(), prevClose > 0 ? prevClose : maxVal());
        double pad = Math.max((hi - lo) * 0.08, Math.abs(hi) * 0.0002 + 1e-9);
        return lo - pad;
    }

    private double hiBound() {
        double lo = Math.min(minVal(), prevClose > 0 ? prevClose : minVal());
        double hi = Math.max(maxVal(), prevClose > 0 ? prevClose : maxVal());
        double pad = Math.max((hi - lo) * 0.08, Math.abs(hi) * 0.0002 + 1e-9);
        return hi + pad;
    }

    private int xOf(int i) {
        int n = Math.max(2, bars.size());
        double w = (plotRight() - plotLeft()) / (double) (n - 1);
        return (int) Math.round(plotLeft() + i * w);
    }

    private int indexAt(int x) {
        if (bars.size() < 2) {
            return -1;
        }
        double w = (plotRight() - plotLeft()) / (double) (bars.size() - 1);
        int idx = (int) Math.round((x - plotLeft()) / w);
        return idx < 0 || idx >= bars.size() ? -1 : idx;
    }

    private int yOf(double v) {
        double lo = loBound();
        double hi = hiBound();
        int top = plotTop();
        int bot = plotBottom();
        if (hi <= lo) {
            return (top + bot) / 2;
        }
        return (int) Math.round(bot - (v - lo) / (hi - lo) * (bot - top));
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(UITheme.SMALL_FONT);

        g2.setColor(UITheme.PANEL_DARK);
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (bars.isEmpty()) {
            g2.setColor(UITheme.TEXT_DIM);
            g2.setFont(UITheme.UI_FONT);
            String msg = symbol.isEmpty() ? "请选择外汇标的" : "正在加载 " + symbol + " 分时数据 ...";
            g2.drawString(msg, (getWidth() - g2.getFontMetrics().stringWidth(msg)) / 2, getHeight() / 2);
            g2.dispose();
            return;
        }

        drawGrid(g2);
        drawBaseline(g2);
        drawSeries(g2);
        drawAxis(g2);
        drawHeader(g2);
        drawCrosshair(g2);
        g2.dispose();
    }

    private void drawGrid(Graphics2D g2) {
        g2.setColor(UITheme.GRID);
        int top = plotTop();
        int bot = plotBottom();
        for (int i = 0; i <= 4; i++) {
            int y = top + (bot - top) * i / 4;
            g2.drawLine(plotLeft(), y, plotRight(), y);
        }
    }

    private void drawBaseline(Graphics2D g2) {
        if (prevClose <= 0) {
            return;
        }
        int y = yOf(prevClose);
        if (y < plotTop() || y > plotBottom()) {
            return;
        }
        g2.setColor(UITheme.alpha(UITheme.WARN, 150));
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{5f, 4f}, 0f));
        g2.drawLine(plotLeft(), y, plotRight(), y);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(UITheme.WARN);
        g2.drawString("昨收 " + UITheme.price(prevClose, digits), plotLeft() + 4, y - 4);
    }

    private void drawSeries(Graphics2D g2) {
        int n = bars.size();
        if (n < 2) {
            return;
        }
        boolean up = last >= prevClose;
        Color line = up ? UITheme.upColor() : UITheme.downColor();

        java.awt.geom.Path2D fill = new java.awt.geom.Path2D.Double();
        fill.moveTo(xOf(0), plotBottom());
        for (int i = 0; i < n; i++) {
            fill.lineTo(xOf(i), yOf(bars.get(i).close));
        }
        fill.lineTo(xOf(n - 1), plotBottom());
        fill.closePath();
        g2.setPaint(new GradientPaint(0, plotTop(), UITheme.alpha(line, 110),
                0, plotBottom(), UITheme.alpha(line, 0)));
        g2.fill(fill);

        g2.setColor(line);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int px = xOf(0);
        int py = yOf(bars.get(0).close);
        for (int i = 1; i < n; i++) {
            int x = xOf(i);
            int y = yOf(bars.get(i).close);
            g2.drawLine(px, py, x, y);
            px = x;
            py = y;
        }
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(line);
        g2.fillOval(px - 3, py - 3, 6, 6);
    }

    private void drawAxis(Graphics2D g2) {
        int right = plotRight();
        g2.setColor(UITheme.WIDGET);
        g2.drawLine(right, plotTop(), right, plotBottom());
        g2.setColor(UITheme.TEXT_DIM);
        double lo = loBound();
        double hi = hiBound();
        for (int i = 0; i <= 4; i++) {
            double v = lo + (hi - lo) * i / 4;
            int y = yOf(v);
            g2.drawString(UITheme.price(v, digits), right + 4, y + 4);
        }
        g2.setColor(UITheme.GRID);
        g2.drawLine(plotLeft(), plotBottom(), right, plotBottom());
    }

    private void drawHeader(Graphics2D g2) {
        boolean up = last >= prevClose;
        Color c = up ? UITheme.upColor() : UITheme.downColor();
        g2.setFont(UITheme.font(Font.BOLD, 13));
        g2.setColor(UITheme.TEXT);
        String head = symbol + " " + name;
        g2.drawString(head, plotLeft() + 2, 13);
        int x = plotLeft() + 6 + g2.getFontMetrics().stringWidth(head);
        g2.setFont(UITheme.SMALL_FONT);
        g2.setColor(c);
        double chg = prevClose > 0 ? (last - prevClose) / prevClose : 0;
        g2.drawString("  " + UITheme.price(last, digits) + "  " + UITheme.pct3(chg), x, 13);
    }

    private void drawCrosshair(Graphics2D g2) {
        if (hoverIndex < 0 || hoverIndex >= bars.size()) {
            return;
        }
        Bar b = bars.get(hoverIndex);
        int x = xOf(hoverIndex);
        int y = yOf(b.close);
        g2.setColor(UITheme.alpha(UITheme.TEXT, 80));
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{3f, 3f}, 0f));
        g2.drawLine(x, plotTop(), x, plotBottom());
        g2.drawLine(plotLeft(), y, plotRight(), y);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(UITheme.TEXT);
        g2.fillOval(x - 3, y - 3, 6, 6);

        String[] rows = {
            "时间 " + b.time,
            "价格 " + UITheme.price(b.close, digits),
            "高 " + UITheme.price(b.high, digits),
            "低 " + UITheme.price(b.low, digits),
        };
        int wBox = 130;
        int hBox = rows.length * 16 + 10;
        int bx = x + 12;
        if (bx + wBox > plotRight()) {
            bx = Math.max(plotLeft(), x - wBox - 12);
        }
        int by = plotTop() + 4;
        g2.setColor(UITheme.alpha(UITheme.BG, 232));
        g2.fillRoundRect(bx, by, wBox, hBox, 8, 8);
        g2.setColor(UITheme.WIDGET);
        g2.drawRoundRect(bx, by, wBox, hBox, 8, 8);
        for (int i = 0; i < rows.length; i++) {
            g2.setColor(i == 0 ? UITheme.TEXT_DIM : UITheme.TEXT);
            g2.drawString(rows[i], bx + 8, by + 16 + i * 16);
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int idx = indexAt(event.getX());
        if (idx < 0) {
            return null;
        }
        Bar b = bars.get(idx);
        return "<html>" + b.time + "<br>价 " + UITheme.price(b.close, digits)
                + "  高 " + UITheme.price(b.high, digits)
                + "  低 " + UITheme.price(b.low, digits) + "</html>";
    }

    /** 悬停点（测试用）。 */
    Point hoverPoint() {
        return hoverIndex < 0 ? null : new Point(xOf(hoverIndex), yOf(bars.get(hoverIndex).close));
    }
}
