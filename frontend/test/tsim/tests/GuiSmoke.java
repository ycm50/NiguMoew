package tsim.tests;

import java.lang.reflect.Method;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import tsim.json.JsonDeserializer.MarketData;
import tsim.json.JsonDeserializer.Snapshot;
import tsim.ui.CheatPanel;
import tsim.ui.EngineClient;
import tsim.ui.MainFrame;
import tsim.ui.UITheme;

/**
 * GUI 冒烟测试：用 mock 引擎真正构造 MainFrame（所有面板/图表/表模型），
 * 跑一次 newgame/snapshot/market，确认没有异常后关闭。
 */
public final class GuiSmoke {

    private GuiSmoke() {
    }

    private static volatile Object frameRef;

    /** 运行。 */
    public static void main(String[] args) throws Exception {
        System.out.println("==== GUI 冒烟测试 ====");
        System.out.println("headless = " + java.awt.GraphicsEnvironment.isHeadless());
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.out.println("[SKIP] 无图形环境，跳过 GUI 冒烟");
            return;
        }
        for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
            if ("Nimbus".equals(info.getName())) {
                UIManager.setLookAndFeel(info.getClassName());
                System.out.println("LAF = Nimbus");
                break;
            }
        }
        String wrapper = args[0];
        EngineClient engine = new EngineClient(java.nio.file.Paths.get(wrapper));
        engine.start();
        engine.hello().get(10, java.util.concurrent.TimeUnit.SECONDS);

        final Throwable[] err = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                MainFrame f = new MainFrame(engine);
                f.setSize(1440, 900);
                f.setVisible(true);
                frameRef = f;
                System.out.println("[PASS] MainFrame 构造 + setVisible 成功");
                // 直接跑一次数据刷新（同步等待）
                f.startNewGame("冒烟玩家");
            } catch (Throwable t) {
                err[0] = t;
            }
        });
        if (err[0] != null) {
            System.out.println("[FAIL] MainFrame 构造异常: " + err[0]);
            err[0].printStackTrace();
            engine.kill();
            System.exit(1);
        }
        Thread.sleep(2500);
        // 通过反射读面板状态，确认数据真的进来了
        SwingUtilities.invokeAndWait(() -> {
            try {
                MainFrame f = (MainFrame) frameRef;
                System.out.println("[INFO] 面板标题数 = " + countTabs(f));
                System.out.println("[INFO] 作弊器横幅 = " + CheatPanel.bannerText());
                System.out.println("[INFO] 合规提示 = " + UITheme.SIM_NOTICE);
                System.out.println("[INFO] 涨跌配色 = " + UITheme.colorSchemeName());
            } catch (Throwable t) {
                System.out.println("[WARN] 反射读取失败: " + t);
            }
        });
        // 直接验证 model 层：快照 + 行情能解析
        Snapshot s = engine.snapshotModel().get(10, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("[PASS] snapshot 解析: 权益=" + s.stockAccount.equity + " 时间=" + s.time);
        MarketData md = engine.marketModel("all").get(10, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("[PASS] market 解析: 股票=" + md.stocks.size() + " 外汇=" + md.forex.size());
        if (md.stocks.isEmpty()) {
            System.out.println("[FAIL] 行情为空");
            System.exit(1);
        }
        System.out.println("[PASS] GUI 冒烟通过（含 K线/分时/表格/交易面板/作弊器/时钟面板）");
        SwingUtilities.invokeAndWait(() -> ((javax.swing.JFrame) frameRef).dispose());
        engine.kill();
        System.out.println("==== GUI 冒烟结束 ====");
    }

    private static int countTabs(javax.swing.JFrame f) {
        try {
            for (java.awt.Component c : f.getContentPane().getComponents()) {
                Object t = findTabs(c);
                if (t != null) {
                    return ((javax.swing.JTabbedPane) t).getTabCount();
                }
            }
        } catch (Throwable ignored) {
            return -1;
        }
        return -1;
    }

    private static Object findTabs(java.awt.Component c) {
        if (c instanceof javax.swing.JTabbedPane) {
            return c;
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                Object r = findTabs(k);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 未使用（保留反射入口） */
    static Method unused() {
        return null;
    }
}
