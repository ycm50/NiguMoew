package tsim.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import tsim.json.JsonDeserializer.Bar;
import tsim.json.JsonDeserializer.ForexQuote;
import tsim.json.JsonDeserializer.StockQuote;

/**
 * 自绘 K 线图：蜡烛 + MA5/MA10 + 成交量柱 + 十字光标提示。
 *
 * <p>数据来自 {@code market} 命令返回的 {@code hist} 数组；坐标按可视区自动缩放。</p>
 */
public final class KLineChart extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int RIGHT_AXIS_W = 66;
    private static final int BOTTOM_AXIS_H = 20;
    private static final int VOLUME_RATIO = 24;   // 成交量区占高度百分比
    private static final int LEFT_PAD = 6;
    private static final int TOP_PAD = 16;

    private String symbol = "";
    private String name = "";
    private List<Bar> bars = new ArrayList<>();
    private double prevClose;
    private int digits = 2;
    private boolean halted;

    private int hoverIndex = -1;
    private Point mouse;

    /** 构造 K 线面板。 */
    public KLineChart() {
        setBackground(UITheme.PANEL_DARK);
        setPreferredSize(new Dimension(640, 360));
        setOpaque(true);
        setToolTipText("");
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mouse = e.getPoint();
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
                mouse = null;
                repaint();
            }
        });
    }

    /** 设置要显示的标的（清空图形）。 */
    public void setSymbol(String symbol, String name) {
        this.symbol = symbol == null ? "" : symbol;
        this.name = name == null ? "" : name;
        this.bars = new ArrayList<>();
        repaint();
    }

    /** 直接喂数据（来自 MarketData 中的一条股票行情）。 */
    public void setQuote(StockQuote q, int digits) {
        if (q == null) {
            this.bars = new ArrayList<>();
            repaint();
            return;
        }
        this.symbol = q.symbol;
        this.name = q.name;
        this.prevClose = q.prevClose;
        this.halted = q.halted;
        this.digits = digits <= 0 ? 2 : digits;
        this.bars = new ArrayList<>(q.hist);
        if (hoverIndex >= bars.size()) {
            hoverIndex = -1;
        }
        repaint();
    }

    /** 直接喂数据（来自 MarketData 中的一条外汇行情）。
     *
     * <p>外汇同样有 {@code hist} K 线序列，此前 K 线图只接了股票，
     * 导致切到外汇时 K 线图完全不刷新（用户反馈「汇市左侧 K 线不动」）。</p> */
    public void setForexQuote(ForexQuote q) {
        if (q == null) {
            this.bars = new ArrayList<>();
            repaint();
            return;
        }
        this.symbol = q.symbol;
        this.name = q.name;
        this.prevClose = q.prevClose;
        this.halted = false;
        this.digits = q.digits <= 0 ? 4 : q.digits;
        this.bars = new ArrayList<>(q.hist);
        if (hoverIndex >= bars.size()) {
            hoverIndex = -1;
        }
        repaint();
    }

    /** 设置数据（K 线数组 + 昨收）。 */
    public void setBars(String symbol, String name, List<Bar> bars, double prevClose, int digits) {
        this.symbol = symbol == null ? "" : symbol;
        this.name = name == null ? "" : name;
        this.bars = bars == null ? new ArrayList<>() : new ArrayList<>(bars);
        this.prevClose = prevClose;
        this.digits = digits <= 0 ? 2 : digits;
        repaint();
    }

    /** 当前显示的 K 线根数。 */
    public int barCount() {
        return bars.size();
    }

    /** 当前变化的标的代码。 */
    public String symbol() {
        return symbol;
    }

    // ------------------------------------------------------------ 几何

    private int plotLeft() {
        return LEFT_PAD;
    }

    private int plotRight() {
        return Math.max(plotLeft() + 40, getWidth() - RIGHT_AXIS_W);
    }

    private int plotBottom() {
        return Math.max(TOP_PAD + 40, getHeight() - BOTTOM_AXIS_H);
    }

    private int volumeTop() {
        int plotH = plotBottom() - TOP_PAD;
        return plotBottom() - Math.max(30, plotH * VOLUME_RATIO / 100);
    }

    private int priceBottom() {
        return volumeTop() - 4;
    }

    private double minLow() {
        double v = Double.MAX_VALUE;
        for (Bar b : bars) {
            if (b.low > 0) {
                v = Math.min(v, b.low);
            }
        }
        return v == Double.MAX_VALUE ? 0 : v;
    }

    private double maxHigh() {
        double v = -Double.MAX_VALUE;
        for (Bar b : bars) {
            v = Math.max(v, b.high);
        }
        return v == -Double.MAX_VALUE ? 1 : v;
    }

    private long maxVolume() {
        long v = 1;
        for (Bar b : bars) {
            v = Math.max(v, b.volume);
        }
        return v;
    }

    private double barWidth() {
        int n = Math.max(1, bars.size());
        double w = (plotRight() - plotLeft()) / (double) n;
        return Math.max(1.0, Math.min(w, 26.0));
    }

    private double xOf(int index) {
        double w = barWidth();
        return plotLeft() + w * (index + 0.5);
    }

    private int indexAt(int x) {
        if (bars.isEmpty()) {
            return -1;
        }
        double w = barWidth();
        int idx = (int) ((x - plotLeft()) / w);
        if (idx < 0 || idx >= bars.size()) {
            return -1;
        }
        return idx;
    }

    private double priceMin() {
        double lo = minLow();
        double hi = maxHigh();
        double pad = Math.max((hi - lo) * 0.06, hi * 0.0015);
        return Math.max(0, lo - pad);
    }

    private double priceMax() {
        double lo = minLow();
        double hi = maxHigh();
        double pad = Math.max((hi - lo) * 0.06, hi * 0.0015);
        return hi + pad;
    }

    private int yOf(double price) {
        double lo = priceMin();
        double hi = priceMax();
        int top = TOP_PAD;
        int bot = priceBottom();
        if (hi <= lo) {
            return (top + bot) / 2;
        }
        double t = (price - lo) / (hi - lo);
        return (int) Math.round(bot - t * (bot - top));
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(UITheme.SMALL_FONT);

        int w = getWidth();
        int h = getHeight();
        g2.setColor(UITheme.PANEL_DARK);
        g2.fillRect(0, 0, w, h);

        if (bars.isEmpty()) {
            g2.setColor(UITheme.TEXT_DIM);
            g2.setFont(UITheme.UI_FONT);
            String msg = symbol.isEmpty() ? "请选择左侧行情表中的标的" : "正在加载 " + symbol + " 的 K 线数据 ...";
            int tw = g2.getFontMetrics().stringWidth(msg);
            g2.drawString(msg, (w - tw) / 2, h / 2);
            g2.dispose();
            return;
        }

        drawGrid(g2);
        drawCandles(g2);
        drawVolume(g2);
        drawAverages(g2);
        drawAxis(g2);
        drawHeader(g2);
        drawCrosshair(g2);
        g2.dispose();
    }

    private void drawGrid(Graphics2D g2) {
        int left = plotLeft();
        int right = plotRight();
        int top = TOP_PAD;
        int bottom = plotBottom();
        g2.setColor(UITheme.GRID);
        int lines = 4;
        for (int i = 0; i <= lines; i++) {
            int y = top + (bottom - volumeTop()) * 0 / 1 + (priceBottom() - top) * i / lines;
            g2.drawLine(left, y, right, y);
        }
        g2.drawLine(left, volumeTop(), right, volumeTop());
    }

    private void drawCandles(Graphics2D g2) {
        double bw = barWidth();
        int bodyW = Math.max(1, (int) Math.floor(bw * 0.68));
        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            boolean up = b.close >= b.open;
            Color c = up ? UITheme.upColor() : UITheme.downColor();
            int x = (int) Math.round(xOf(i));
            int yHigh = yOf(b.high);
            int yLow = yOf(b.low);
            int yOpen = yOf(b.open);
            int yClose = yOf(b.close);
            g2.setColor(c);
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(x, yHigh, x, yLow);
            int top = Math.min(yOpen, yClose);
            int hg = Math.max(1, Math.abs(yClose - yOpen));
            if (bw < 3.5) {
                g2.fillRect(x - bodyW / 2, top, Math.max(1, bodyW), hg);
            } else if (up) {
                g2.drawRect(x - bodyW / 2, top, Math.max(1, bodyW), hg);
            } else {
                g2.fillRect(x - bodyW / 2, top, Math.max(1, bodyW), hg);
            }
        }
    }

    private void drawVolume(Graphics2D g2) {
        double bw = barWidth();
        int bodyW = Math.max(1, (int) Math.floor(bw * 0.68));
        int vTop = volumeTop();
        int vBot = plotBottom();
        long maxV = maxVolume();
        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            int x = (int) Math.round(xOf(i));
            int barH = (int) Math.round((vBot - vTop) * (b.volume / (double) maxV));
            if (barH <= 0) {
                barH = 1;
            }
            boolean up = b.close >= b.open;
            Color c = up ? UITheme.upColor() : UITheme.downColor();
            g2.setColor(UITheme.alpha(c, 150));
            g2.fillRect(x - bodyW / 2, vBot - barH, Math.max(1, bodyW), barH);
        }
        g2.setColor(UITheme.GRID);
        g2.drawLine(plotLeft(), vBot, plotRight(), vBot);
    }

    private void drawAverages(Graphics2D g2) {
        drawMa(g2, 5, UITheme.MA5);
        drawMa(g2, 10, UITheme.MA10);
    }

    private void drawMa(Graphics2D g2, int period, Color color) {
        if (bars.size() < period) {
            return;
        }
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.4f));
        int prevX = -1;
        int prevY = -1;
        for (int i = period - 1; i < bars.size(); i++) {
            double sum = 0;
            for (int k = i - period + 1; k <= i; k++) {
                sum += bars.get(k).close;
            }
            double ma = sum / period;
            int x = (int) Math.round(xOf(i));
            int y = yOf(ma);
            if (prevX >= 0) {
                g2.drawLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawAxis(Graphics2D g2) {
        int right = plotRight();
        int left = plotLeft();
        int top = TOP_PAD;
        int bot = plotBottom();
        g2.setColor(UITheme.WIDGET);
        g2.drawLine(right, top, right, bot);
        g2.setColor(UITheme.TEXT_DIM);
        double lo = priceMin();
        double hi = priceMax();
        int lines = 4;
        for (int i = 0; i <= lines; i++) {
            double p = lo + (hi - lo) * i / lines;
            int y = yOf(p);
            String s = UITheme.price(p, digits);
            g2.drawString(s, right + 4, y + 4);
        }
        // 昨收基准虚线
        if (prevClose > 0 && prevClose >= lo && prevClose <= hi) {
            int y = yOf(prevClose);
            g2.setColor(UITheme.alpha(UITheme.WARN, 120));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 4f}, 0f));
            g2.drawLine(left, y, right, y);
            g2.setStroke(new BasicStroke(1f));
        }
        // 日期刻度
        int step = Math.max(1, bars.size() / 6);
        for (int i = 0; i < bars.size(); i += step) {
            Bar b = bars.get(i);
            String d = b.time.date;
            if (d.length() >= 10) {
                d = d.substring(5);
            }
            int x = (int) Math.round(xOf(i));
            g2.setColor(UITheme.TEXT_DIM);
            int tw = g2.getFontMetrics().stringWidth(d);
            g2.drawString(d, Math.max(left, x - tw / 2), getHeight() - 6);
        }
        g2.setColor(UITheme.GRID);
        g2.drawLine(left, bot, right, bot);
    }

    private void drawHeader(Graphics2D g2) {
        Bar last = bars.get(bars.size() - 1);
        boolean up = last.close >= prevClose;
        g2.setFont(UITheme.font(Font.BOLD, 13));
        g2.setColor(UITheme.TEXT);
        String head = symbol + " " + name;
        g2.drawString(head, plotLeft() + 2, 13);
        int x = plotLeft() + 6 + g2.getFontMetrics().stringWidth(head);
        g2.setColor(up ? UITheme.upColor() : UITheme.downColor());
        g2.setFont(UITheme.SMALL_FONT);
        String px = UITheme.price(last.close, digits);
        double chg = prevClose > 0 ? (last.close - prevClose) / prevClose : 0;
        g2.drawString("  " + px + "  " + UITheme.pct(chg), x, 13);
        g2.setColor(UITheme.MA5);
        int x2 = plotRight() - 190;
        g2.drawString("MA5", x2, 13);
        g2.setColor(UITheme.MA10);
        g2.drawString("MA10", x2 + 46, 13);
        if (halted) {
            g2.setColor(UITheme.WARN);
            g2.drawString("【停牌】", x2 + 100, 13);
        }
    }

    private void drawCrosshair(Graphics2D g2) {
        if (hoverIndex < 0 || hoverIndex >= bars.size()) {
            return;
        }
        Bar b = bars.get(hoverIndex);
        int x = (int) Math.round(xOf(hoverIndex));
        int left = plotLeft();
        int right = plotRight();
        int top = TOP_PAD;
        int bot = plotBottom();
        g2.setColor(UITheme.alpha(UITheme.TEXT, 70));
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{3f, 3f}, 0f));
        g2.drawLine(x, top, x, bot);
        if (mouse != null && mouse.y >= top && mouse.y <= priceBottom()) {
            g2.drawLine(left, mouse.y, right, mouse.y);
            double lo = priceMin();
            double hi = priceMax();
            double p = hi - (mouse.y - top) / (double) Math.max(1, priceBottom() - top) * (hi - lo);
            g2.setColor(UITheme.BG);
            g2.fillRect(right + 1, mouse.y - 8, RIGHT_AXIS_W - 2, 16);
            g2.setColor(UITheme.TEXT);
            g2.drawString(UITheme.price(p, digits), right + 4, mouse.y + 4);
        }
        g2.setStroke(new BasicStroke(1f));

        // 提示框
        String[] rows = {
            "时间 " + b.time,
            "开 " + UITheme.price(b.open, digits),
            "高 " + UITheme.price(b.high, digits),
            "低 " + UITheme.price(b.low, digits),
            "收 " + UITheme.price(b.close, digits),
            "量 " + UITheme.volume(b.volume),
        };
        int wBox = 132;
        int hBox = rows.length * 16 + 10;
        int bx = x + 12;
        if (bx + wBox > right) {
            bx = Math.max(left, x - wBox - 12);
        }
        int by = top + 6;
        g2.setColor(UITheme.alpha(UITheme.BG, 232));
        g2.fillRoundRect(bx, by, wBox, hBox, 8, 8);
        g2.setColor(UITheme.WIDGET);
        g2.drawRoundRect(bx, by, wBox, hBox, 8, 8);
        boolean up = b.close >= b.open;
        Color c = up ? UITheme.upColor() : UITheme.downColor();
        for (int i = 0; i < rows.length; i++) {
            g2.setColor(i == 0 ? UITheme.TEXT_DIM : c);
            if (i == 1 || i == 2 || i == 3 || i == 4) {
                g2.setColor(c);
            }
            g2.drawString(rows[i], bx + 8, by + 16 + i * 16);
        }

        // 底部时间
        g2.setColor(UITheme.TEXT);
        String d = b.time.toString();
        int tw = g2.getFontMetrics().stringWidth(d);
        int tx = Math.min(right - tw, Math.max(left, x - tw / 2));
        g2.setColor(UITheme.BG);
        g2.fillRect(tx - 3, getHeight() - BOTTOM_AXIS_H, tw + 6, BOTTOM_AXIS_H - 3);
        g2.setColor(UITheme.TEXT);
        g2.drawString(d, tx, getHeight() - 6);
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int idx = indexAt(event.getX());
        if (idx < 0) {
            return null;
        }
        Bar b = bars.get(idx);
        return "<html>" + b.time + "<br>开 " + UITheme.price(b.open, digits)
                + "  高 " + UITheme.price(b.high, digits)
                + "  低 " + UITheme.price(b.low, digits)
                + "  收 " + UITheme.price(b.close, digits)
                + "<br>量 " + UITheme.volume(b.volume) + "</html>";
    }

    /** 强制重绘（线程安全）。 */
    public void refresh() {
        if (SwingUtilities.isEventDispatchThread()) {
            repaint();
        } else {
            SwingUtilities.invokeLater(this::repaint);
        }
    }
}
