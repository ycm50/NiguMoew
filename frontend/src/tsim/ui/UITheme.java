package tsim.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 全局视觉主题：颜色、字体与数字格式化。
 *
 * <p>中文字体通过 {@link GraphicsEnvironment#getAvailableFontFamilyNames()} 选择第一个可用项，
 * 优先级：微软雅黑 &gt; 微软雅黑 Light &gt; 等线 &gt; 宋体 &gt; 黑体 &gt; 其它含中文的字体。</p>
 *
 * <p>涨跌配色遵循 A 股习惯（涨红跌绿），可通过 {@link #setColorScheme(int)} 切换为
 * 欧美习惯（涨绿跌红）。</p>
 */
public final class UITheme {

    private UITheme() {
    }

    // ---------------------------------------------------------------- 配色

    /** 深色主题：窗口背景。 */
    public static final Color BG = new Color(0x1B1E24);
    /** 深色主题：面板背景。 */
    public static final Color PANEL = new Color(0x23272F);
    /** 深色主题：更深一层（表头/状态栏）。 */
    public static final Color PANEL_DARK = new Color(0x191C22);
    /** 深色主题：分隔线。 */
    public static final Color WIDGET = new Color(0x3A4150);
    /** 主文字。 */
    public static final Color TEXT = new Color(0xE6E8EC);
    /** 次要文字。 */
    public static final Color TEXT_DIM = new Color(0x9AA3B2);
    /** 强调色（科技蓝）。 */
    public static final Color ACCENT = new Color(0x3D8BFD);
    /** 强调色的浅色版。 */
    public static final Color ACCENT_SOFT = new Color(0x2A4E86);
    /** 选中行背景。 */
    public static final Color SELECT = new Color(0x2E3A4F);
    /** 表格网格线。 */
    public static final Color GRID = new Color(0x2C313A);
    /** 警示黄。 */
    public static final Color WARN = new Color(0xF0B429);
    /** 错误红。 */
    public static final Color DANGER = new Color(0xE5484D);
    /** 成功绿。 */
    public static final Color OK = new Color(0x30A46C);

    /** 涨（A 股习惯：红）。 */
    public static final Color UP_COLOR_DEFAULT = new Color(0xEF4444);
    /** 跌（A 股习惯：绿）。 */
    public static final Color DOWN_COLOR_DEFAULT = new Color(0x22C55E);

    /** 图表蜡烛空心边色（上涨时为空心）。 */
    public static final Color CANDLE_UP_HOLLOW = new Color(0xFF6B6B);
    /** MA5 均线色。 */
    public static final Color MA5 = new Color(0xFFFFFF);
    /** MA10 均线色。 */
    public static final Color MA10 = new Color(0xF0B429);
    /** 成交量柱色（复用涨跌色，透明度在绘图中调整）。 */
    public static final Color VOLUME = new Color(0x5A6474);

    /** 股票和外汇共用的一套涨跌色（可切换）。 */
    private static volatile Color upColor = UP_COLOR_DEFAULT;
    private static volatile Color downColor = DOWN_COLOR_DEFAULT;
    private static volatile int colorScheme = 0;

    // ---------------------------------------------------------------- 字体

    /** UI 主字体（微软雅黑回退）；所有 Swing 组件统一使用它。 */
    public static final Font UI_FONT;
    /** 小号字体（表格正文）。 */
    public static final Font SMALL_FONT;
    /** 等宽/数字字体（价格、金额列，使用表格数字对齐）。 */
    public static final Font MONO_FONT;
    /** 标题字体。 */
    public static final Font TITLE_FONT;

    /** 实际选中的中文字体族名。 */
    public static final String FONT_FAMILY;

    private static final String[] FONT_PREFERENCE = {
        "Microsoft YaHei UI", "微软雅黑", "Microsoft YaHei", "微软雅黑 Light",
        "DengXian", "等线", "SimSun", "宋体", "SimHei", "黑体", "NSimSun",
        "Microsoft JhengHei", "PingFang SC", "Noto Sans CJK SC", "SansSerif",
    };

    static {
        FONT_FAMILY = pickFontFamily();
        UI_FONT = new Font(FONT_FAMILY, Font.PLAIN, 13);
        SMALL_FONT = new Font(FONT_FAMILY, Font.PLAIN, 12);
        MONO_FONT = new Font(FONT_FAMILY, Font.PLAIN, 13);
        TITLE_FONT = new Font(FONT_FAMILY, Font.BOLD, 16);
    }

    private static String pickFontFamily() {
        Set<String> available = new HashSet<>();
        try {
            available.addAll(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames(java.util.Locale.ROOT)));
        } catch (RuntimeException ex) {
            available.clear();
        }
        for (String want : FONT_PREFERENCE) {
            for (String have : available) {
                if (have.equalsIgnoreCase(want)) {
                    return have;
                }
            }
        }
        // 兜底：找一个名字里带常见中文字体关键字的
        String[] hints = {"YaHei", "Hei", "Song", "Kai", "Ming", "CJK", "Noto", "雅黑", "黑", "宋"};
        for (String have : available) {
            for (String h : hints) {
                if (have.contains(h)) {
                    return have;
                }
            }
        }
        return Font.SANS_SERIF;
    }

    /** 返回指定样式/字号的 UI 字体。 */
    public static Font font(int style, int size) {
        return new Font(FONT_FAMILY, style, size);
    }

    // ---------------------------------------------------------------- 涨跌色

    /** A 股习惯：涨红跌绿（默认）。 */
    public static void setAShareScheme() {
        colorScheme = 0;
        upColor = UP_COLOR_DEFAULT;
        downColor = DOWN_COLOR_DEFAULT;
    }

    /** 欧美习惯：涨绿跌红。 */
    public static void setWesternScheme() {
        colorScheme = 1;
        upColor = DOWN_COLOR_DEFAULT;
        downColor = UP_COLOR_DEFAULT;
    }

    /** 切换配色方案；返回切换后的方案名。 */
    public static String toggleColorScheme() {
        if (colorScheme == 0) {
            setWesternScheme();
        } else {
            setAShareScheme();
        }
        return colorSchemeName();
    }

    /** 当前配色方案名（中文）。 */
    public static String colorSchemeName() {
        return colorScheme == 0 ? "A股习惯（涨红跌绿）" : "欧美习惯（涨绿跌红）";
    }

    /** 当前"涨"的颜色。 */
    public static Color upColor() {
        return upColor;
    }

    /** 当前"跌"的颜色。 */
    public static Color downColor() {
        return downColor;
    }

    /** 按涨跌方向取色（0 用普通文字色）。 */
    public static Color pnlColor(double v) {
        if (v > 0) {
            return upColor;
        }
        if (v < 0) {
            return downColor;
        }
        return TEXT_DIM;
    }

    // ---------------------------------------------------------------- 数字格式化

    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(
            () -> new DecimalFormat("#,##0.00"));
    private static final ThreadLocal<DecimalFormat> MONEY0 = ThreadLocal.withInitial(
            () -> new DecimalFormat("#,##0"));
    private static final ThreadLocal<DecimalFormat> QTY = ThreadLocal.withInitial(
            () -> new DecimalFormat("#,##0"));
    private static final ThreadLocal<DecimalFormat> PCT = ThreadLocal.withInitial(
            () -> new DecimalFormat("+0.00%;-0.00%"));
    private static final ThreadLocal<DecimalFormat> PCT3 = ThreadLocal.withInitial(
            () -> new DecimalFormat("+0.000%;-0.000%"));
    private static final ThreadLocal<DecimalFormat> VOLUME_FMT = ThreadLocal.withInitial(
            () -> new DecimalFormat("#,##0"));
    private static final SimpleDateFormat DATETIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static double round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** 金额：千分位 + 2 位小数。 */
    public static String money(double v) {
        return MONEY.get().format(round(v, 2));
    }

    /** 金额（无小数）：千分位。 */
    public static String money0(double v) {
        return MONEY0.get().format(Math.round(v));
    }

    /** 带正负号的金额。 */
    public static String moneySigned(double v) {
        String s = money(Math.abs(v));
        if (v > 0) {
            return "+" + s;
        }
        if (v < 0) {
            return "-" + s;
        }
        return s;
    }

    /** 价格：按 digits 位小数（4 位以上截到协议上限 4 位）。 */
    public static String price(double v, int digits) {
        int d = digits;
        if (d < 0) {
            d = 2;
        }
        if (d > 4) {
            d = 4;
        }
        if (d > 0 && Math.abs(v) > 0 && Math.abs(v) < Math.pow(10, -d)) {
            d = Math.max(d, 4);
        }
        DecimalFormat f = new DecimalFormat("#,##0." + "0".repeat(Math.max(0, d)));
        return f.format(round(v, d));
    }

    /** 股数：整数 + 千分位。 */
    public static String qty(long v) {
        return QTY.get().format(v);
    }

    /** 比率 -> 百分比字符串（带符号，2 位小数）。 */
    public static String pct(double v) {
        return PCT.get().format(v);
    }

    /** 比率 -> 百分比字符串（带符号，3 位小数，外汇用）。 */
    public static String pct3(double v) {
        return PCT3.get().format(v);
    }

    /** 成交量：大于 1 万时用"万"、大于 1 亿时用"亿"。 */
    public static String volume(long v) {
        if (v >= 100_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.2f亿", v / 100_000_000.0);
        }
        if (v >= 10_000L) {
            return String.format(java.util.Locale.ROOT, "%.2f万", v / 10_000.0);
        }
        return VOLUME_FMT.get().format(v);
    }

    /** 点钟字符串。 */
    public static String nowTime() {
        return DATETIME.format(new Date());
    }

    /** 倍速可读换算："8.0x ≈ 3.2 天/秒"。 */
    public static String speedText(double speed, double daysPerSecond) {
        return String.format(java.util.Locale.ROOT, "%.2fx ≈ %.2f 天/秒", speed, daysPerSecond);
    }

    /** 秒数 -> "1小时02分03秒"。 */
    public static String duration(long seconds) {
        long s = Math.max(0, seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format(java.util.Locale.ROOT, "%d小时%02d分%02d秒", h, m, sec);
        }
        if (m > 0) {
            return String.format(java.util.Locale.ROOT, "%d分%02d秒", m, sec);
        }
        return sec + "秒";
    }

    // ---------------------------------------------------------------- 保证金水平

    /** 协议 §3.8b 警戒线：保证金水平低于 100% 为警戒。 */
    public static final double MARGIN_WARN_LEVEL = 100.0;

    /** 协议 §3.8b 爆仓线：保证金水平低于 50% 会被强平。 */
    public static final double MARGIN_DANGER_LEVEL = 50.0;

    /**
     * 保证金水平配色（协议 §3.8b）：&lt;50% 红色危险，&lt;100% 黄色警戒，其余正常。
     *
     * @param marginLevel 百分比（无持仓时为 0，按正常色处理）
     */
    public static Color marginLevelColor(double marginLevel) {
        if (marginLevel <= 0) {
            return TEXT_DIM;
        }
        if (marginLevel < MARGIN_DANGER_LEVEL) {
            return DANGER;
        }
        if (marginLevel < MARGIN_WARN_LEVEL) {
            return WARN;
        }
        return OK;
    }

    /** 保证金水平文案：附带警戒/危险/爆仓提示。 */
    public static String marginLevelText(double marginLevel) {
        if (marginLevel <= 0) {
            return "无持仓";
        }
        String tag = "";
        if (marginLevel < MARGIN_DANGER_LEVEL) {
            tag = "  危险（低于爆仓线 50%）";
        } else if (marginLevel < MARGIN_WARN_LEVEL) {
            tag = "  警戒";
        }
        return String.format(java.util.Locale.ROOT, "%.2f%%%s", marginLevel, tag);
    }

    // ---------------------------------------------------------------- 文字提示

    /** 全局合规提示（纯模拟）。 */
    public static final String SIM_NOTICE = "本游戏为纯模拟，不涉及任何真实资金";

    /** 作弊器横幅。 */
    public static final String CHEAT_BANNER = "⚠ 作弊器：纯模拟功能，不涉及任何真实资金";

    /** 浅色化一个颜色（用于 hover/高亮）。 */
    public static Color lighten(Color c, double factor) {
        int r = Math.min(255, (int) (c.getRed() + (255 - c.getRed()) * factor));
        int g = Math.min(255, (int) (c.getGreen() + (255 - c.getGreen()) * factor));
        int b = Math.min(255, (int) (c.getBlue() + (255 - c.getBlue()) * factor));
        return new Color(r, g, b);
    }

    /** 带 alpha 的颜色。 */
    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** 用主题字体递归应用到整个组件树。 */
    public static void applyTo(java.awt.Component c) {
        if (c == null) {
            return;
        }
        if (c.getFont() != null) {
            Font f = c.getFont();
            c.setFont(new Font(FONT_FAMILY, f.getStyle(), f.getSize()));
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                applyTo(child);
            }
        }
    }
}