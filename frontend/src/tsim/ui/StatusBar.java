package tsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import tsim.json.JsonDeserializer.ForexAccount;
import tsim.json.JsonDeserializer.Snapshot;
import tsim.json.JsonDeserializer.StockAccount;

/**
 * 底部状态栏：系统时钟 / 游戏时间 / 时钟倍速 / 股票账户 / 外汇账户 / 引擎状态。
 *
 * <p>所有 setter 都可以从任意线程调用（内部切到 EDT）。</p>
 */
public final class StatusBar extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel clockLabel = cell("--:--:--", 150);
    private final JLabel gameTimeLabel = cell("游戏时间 -", 170);
    private final JLabel speedLabel = cell("时钟 已暂停", 260);
    private final JLabel stockLabel = cell("股票账户 -", 380);
    private final JLabel forexLabel = cell("外汇账户 -", 300);
    private final JLabel engineLabel = cell("引擎：未连接", 220);

    private static JLabel cell(String text, int width) {
        JLabel l = new JLabel(" " + text);
        l.setFont(UITheme.SMALL_FONT);
        l.setForeground(UITheme.TEXT_DIM);
        l.setPreferredSize(new Dimension(width, 22));
        l.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UITheme.WIDGET));
        return l;
    }

    /** 构造状态栏。 */
    public StatusBar() {
        super(new BorderLayout());
        setBackground(UITheme.PANEL_DARK);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.WIDGET));

        JPanel left = new JPanel(new GridLayout(1, 6, 0, 0));
        left.setOpaque(false);
        left.add(clockLabel);
        left.add(gameTimeLabel);
        left.add(speedLabel);
        left.add(stockLabel);
        left.add(forexLabel);

        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.setPreferredSize(new Dimension(260, 22));
        engineLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        engineLabel.setBorder(null);
        right.add(engineLabel, BorderLayout.CENTER);

        add(left, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** 更新系统时钟。 */
    public void setClock(String text) {
        onEdt(() -> clockLabel.setText(" 系统 " + text));
    }

    /** 更新游戏时间。 */
    public void setGameTime(String text) {
        onEdt(() -> gameTimeLabel.setText(" 游戏 " + text));
    }

    /** 更新时钟倍速描述。 */
    public void setSpeed(String text) {
        onEdt(() -> speedLabel.setText(" " + text));
    }

    /** 更新股票账户摘要。 */
    public void setStockAccount(StockAccount a) {
        if (a == null) {
            return;
        }
        String v = "股票 权益 " + UITheme.money(a.equity)
                + "  可用 " + UITheme.money(a.cash)
                + "  当日 " + UITheme.moneySigned(a.pnlDay);
        onEdt(() -> {
            stockLabel.setText(" " + v);
            stockLabel.setForeground(a.pnlDay >= 0 ? UITheme.upColor() : UITheme.downColor());
        });
    }

    /** 更新外汇账户摘要。 */
    public void setForexAccount(ForexAccount a) {
        if (a == null) {
            return;
        }
        String v = "外汇 净值 " + UITheme.money(a.equity)
                + "  可用保证金 " + UITheme.money(a.freeMargin);
        if (a.usedLots > 0) {
            v += "  保证金水平 " + String.format(java.util.Locale.ROOT, "%.1f%%", a.marginLevel);
        }
        final String vf = v + "  浮盈 " + UITheme.moneySigned(a.pnlFloat) + "  已用 " + a.usedLots + " 手";
        onEdt(() -> {
            forexLabel.setText(" " + vf);
            // 协议 §3.8b：保证金水平告警优先于盈亏色
            if (a.usedLots > 0 && a.marginLevel > 0 && a.marginLevel < UITheme.MARGIN_WARN_LEVEL) {
                forexLabel.setForeground(UITheme.marginLevelColor(a.marginLevel));
            } else {
                forexLabel.setForeground(a.pnlFloat >= 0 ? UITheme.upColor() : UITheme.downColor());
            }
            forexLabel.setToolTipText("保证金水平 " + UITheme.marginLevelText(a.marginLevel)
                    + "（<100% 警戒 / <50% 爆仓线，协议 §3.8b）");
        });
    }

    /** 由快照整体刷新账户区。 */
    public void setSnapshot(Snapshot s) {
        if (s == null) {
            return;
        }
        setStockAccount(s.stockAccount);
        setForexAccount(s.forexAccount);
        setGameTime(s.time.toString());
        if (s.bankrupt) {
            onEdt(() -> engineLabel.setText(" 已破产！可用作弊器救回 "));
        }
    }

    /** 引擎状态文字；{@code ok=false} 时显示红色。 */
    public void setEngineStatus(String text, boolean ok) {
        onEdt(() -> {
            engineLabel.setText(" " + text + " ");
            engineLabel.setForeground(ok ? UITheme.OK : UITheme.DANGER);
        });
    }
}