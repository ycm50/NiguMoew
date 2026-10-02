package tsim;

import java.awt.EventQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.FontUIResource;

import tsim.ui.Dialogs;
import tsim.ui.EngineClient;
import tsim.ui.LogPanel;
import tsim.ui.MainFrame;
import tsim.ui.SplashWindow;
import tsim.ui.UITheme;

/**
 * 拟股喵喵 前端入口。
 *
 * <p>启动流程：Nimbus 外观 → 启动画面 → 拉起 C++ 引擎子进程 → 新建游戏 → 显示主窗口。
 * 全程 UI 不阻塞：所有引擎调用都通过 {@link EngineClient} 的异步接口。</p>
 *
 * <p><b>本游戏为纯模拟，不涉及任何真实资金。</b></p>
 */
public final class Main {

    private Main() {
    }

    /** 程序入口。 */
    public static void main(String[] args) {
        installLookAndFeel();
        EventQueue.invokeLater(() -> start(args));
    }

    private static void start(String[] args) {
        SplashWindow splash = new SplashWindow();
        splash.showSplash();
        splash.setProgress("正在初始化界面 ...", 10);

        // 引擎定位优先级：
        //   1) 命令行 --engine=<path>（测试/调试，最高优先）
        //   2) EngineClient.discoverEnginePath()：-Dtsim.engine → jar/classes 目录及上 4 级 → cwd 及上 4 级
        //      （每级都下钻 engine\trade_sim.exe，因此 build\frontend\classes → 仓库根 → build\engine 都能命中）
        Path exe = EngineClient.discoverEnginePath();
        String playerName = "玩家";
        for (String a : args) {
            if (a != null && a.startsWith("--engine=")) {
                exe = Paths.get(a.substring("--engine=".length()));
            } else if (a != null && a.startsWith("--name=")) {
                playerName = a.substring("--name=".length());
            }
        }

        final Path finalExe = exe.toAbsolutePath();
        final String finalName = playerName;
        System.out.println("[拟股喵喵] 引擎路径解析为: " + finalExe);
        if (!Files.isRegularFile(finalExe)) {
            splash.close();
            // 无人值守/脚本化场景下对话框会阻塞，先把原因打到 stderr，便于定位
            System.err.println(buildMissingEngineMessage(finalExe));
            Dialogs.error(null, buildMissingEngineMessage(finalExe));
            return;
        }

        EngineClient engine = new EngineClient(finalExe);
        LogPanel bootstrapLog = new LogPanel();
        engine.addLogListener(bootstrapLog::append);

        splash.setProgress("正在启动引擎 ...", 35);
        try {
            engine.start();
        } catch (EngineClient.EngineError ex) {
            splash.close();
            Dialogs.error(null, "引擎启动失败：\n\n" + ex.getMessage()
                    + "\n\n可执行文件：" + finalExe);
            return;
        }

        splash.setProgress("等待引擎握手 ...", 55);
        engine.hello().orTimeout(EngineClient.START_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((d, ex) -> {
                    if (ex != null) {
                        EventQueue.invokeLater(() -> {
                            splash.close();
                            Dialogs.error(null, "引擎握手失败（10 秒超时或无响应）：\n\n"
                                    + MainFrame.errorMessage(ex)
                                    + "\n\n请确认 engine\\trade_sim.exe 已正确构建并可独立运行。");
                        });
                        return;
                    }
                    EventQueue.invokeLater(() -> {
                        splash.setProgress("正在新建游戏（纯模拟）...", 75);
                        MainFrame frame = new MainFrame(engine);
                        frame.setVisible(true);
                        EventQueue.invokeLater(() -> {
                            frame.startNewGame(finalName);
                            splash.setProgress("就绪", 100);
                            splash.close();
                        });
                    });
                });
        splash.setProgress("引擎已启动，正在准备游戏 ...", 70);
    }

    private static String buildMissingEngineMessage(Path exe) {
        StringBuilder sb = new StringBuilder();
        sb.append("未找到引擎程序：\n").append(exe).append("\n\n");
        sb.append("请先构建引擎：\n");
        sb.append("    scripts\\build.cmd\n\n");
        sb.append("然后运行：\n");
        sb.append("    scripts\\run.cmd\n\n");
        sb.append("（工作时目录：").append(System.getProperty("user.dir")).append("）\n\n");
        sb.append(UITheme.SIM_NOTICE).append("。");
        return sb.toString();
    }

    /** 安装 Nimbus 外观，并把默认字体换成中文字体。 */
    private static void installLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                 | UnsupportedLookAndFeelException ex) {
            // 外观设置失败不影响功能，退回默认 LAF
            System.err.println("[拟股喵喵] Nimbus 外观不可用，使用默认外观：" + ex.getMessage());
        }
        try {
            UIManager.put("Label.font", new FontUIResource(UITheme.UI_FONT));
        } catch (RuntimeException ex) {
            System.err.println("[拟股喵喵] 字体设置失败：" + ex.getMessage());
        }
        tuneUIManagerDefaults();
    }

    /** 统一把 UIManager 里的深色主题色值调成我们的配色。 */
    private static void tuneUIManagerDefaults() {
        Object[][] kv = {
            {"control", UITheme.PANEL},
            {"info", UITheme.PANEL},
            {"nimbusBase", UITheme.PANEL_DARK},
            {"nimbusBlueGrey", UITheme.WIDGET},
            {"nimbusLightBackground", UITheme.PANEL},
            {"text", UITheme.TEXT},
            {"nimbusSelectionBackground", UITheme.ACCENT},
            {"nimbusSelectedText", UITheme.TEXT},
            {"nimbusFocus", UITheme.ACCENT},
            {"OptionPane.background", UITheme.PANEL},
            {"Panel.background", UITheme.PANEL},
            {"Button.background", UITheme.PANEL_DARK},
            {"Button.foreground", UITheme.TEXT},
            {"Label.foreground", UITheme.TEXT},
            {"MenuBar.background", UITheme.PANEL_DARK},
            {"Menu.background", UITheme.PANEL_DARK},
            {"Menu.foreground", UITheme.TEXT},
            {"MenuItem.background", UITheme.PANEL_DARK},
            {"MenuItem.foreground", UITheme.TEXT},
            {"ToolBar.background", UITheme.PANEL},
            {"TabbedPane.background", UITheme.PANEL},
            {"TabbedPane.foreground", UITheme.TEXT},
            {"Table.background", UITheme.PANEL},
            {"Table.foreground", UITheme.TEXT},
            {"Table.gridColor", UITheme.GRID},
            {"TableHeader.background", UITheme.PANEL_DARK},
            {"TableHeader.foreground", UITheme.TEXT_DIM},
            {"TextField.background", UITheme.PANEL_DARK},
            {"TextField.foreground", UITheme.TEXT},
            {"TextField.caretForeground", UITheme.TEXT},
            {"ComboBox.background", UITheme.PANEL_DARK},
            {"ComboBox.foreground", UITheme.TEXT},
            {"CheckBox.foreground", UITheme.TEXT},
            {"Slider.background", UITheme.PANEL},
            {"SplitPane.background", UITheme.BG},
            {"SplitPane.dividerSize", Integer.valueOf(6)},
            {"ScrollPane.background", UITheme.PANEL},
            {"Viewport.background", UITheme.PANEL},
            {"ToolTip.background", UITheme.PANEL_DARK},
            {"ToolTip.foreground", UITheme.TEXT},
        };
        Properties p = new Properties();
        for (Object[] kvp : kv) {
            try {
                UIManager.put(String.valueOf(kvp[0]), kvp[1]);
            } catch (RuntimeException ex) {
                p.setProperty("warn." + kvp[0], String.valueOf(ex.getMessage()));
            }
        }
        if (!p.isEmpty()) {
            System.err.println("[拟股喵喵] 部分 UI 默认值设置失败：" + p);
        }
    }

    /** 便捷入口：直接用 java -cp ... tsim.Main 时打印工作目录信息。 */
    static void printBanner() {
        System.out.println("拟股喵喵 前端 v1.0  (工作目录: " + System.getProperty("user.dir") + ")");
        try {
            System.out.println("引擎路径: " + EngineClient.discoverEnginePath());
        } catch (RuntimeException ex) {
            System.out.println("引擎路径不可用：" + ex.getMessage());
        }
    }

    /** 供外部诊断：能否找到引擎。 */
    public static boolean enginePresent() {
        try {
            return Files.isRegularFile(EngineClient.discoverEnginePath());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 不在使用但保留的入口：无界面自检（打印环境信息）。 */
    static void selfCheck() throws IOException {
        printBanner();
        System.out.println("中文字体: " + UITheme.FONT_FAMILY);
        System.out.println("引擎存在: " + enginePresent());
    }
}