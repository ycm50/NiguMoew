package tsim.tests;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import tsim.ui.EngineClient;

/**
 * 入口类端到端回归测试。
 *
 * <p><b>为什么需要它</b>：{@code RealEngineAcceptance} 直接用 {@link EngineClient} 的探测路径，
 * GUI 冒烟只验证 MainFrame 能否构造 —— **两条路径都绕过了 {@code tsim.Main} 自己的引擎路径解析**。
 * 曾经因此漏掉一个 P0：Main 用的是 {@code Paths.get(RELATIVE_EXE)}（相对 cwd），
 * 解析成不存在的 {@code <repo>/engine/trade_sim.exe}，导致 GUI 能起来但引擎永远拉不起来。</p>
 *
 * <p>本测试**以独立进程真实启动 {@code tsim.Main}**（与 run.cmd 完全相同的 classpath 与工作目录），
 * 然后断言引擎子进程确实被拉起。</p>
 *
 * <p>用法：{@code java -cp "build\frontend\classes;build\selftest-classes" tsim.tests.EntryPointTest}
 * 需在仓库根运行；Windows 平台专用（用 tasklist 检测子进程）。</p>
 */
public final class EntryPointTest {

    private static int pass;
    private static int fail;

    private EntryPointTest() {
    }

    private static void ok(String what, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what + "  -> " + detail);
        }
    }

    /** 运行。 */
    public static void main(String[] args) throws Exception {
        System.out.println("==== 入口类端到端回归测试（tsim.Main 真实启动）====");
        Path root = Paths.get("").toAbsolutePath();
        System.out.println("工作目录: " + root);

        // 前置：确认磁盘布局就是 lead 的交付布局（exe 在 build\engine，repo\engine 下没有）
        Path builtExe = root.resolve("build/engine/trade_sim.exe");
        Path repoExe = root.resolve("engine/trade_sim.exe");
        System.out.println("  build\\engine\\trade_sim.exe 存在 = " + Files.isRegularFile(builtExe));
        System.out.println("  engine\\trade_sim.exe 存在       = " + Files.isRegularFile(repoExe));
        ok("引擎位于 build/engine（交付布局）", Files.isRegularFile(builtExe), builtExe.toString());
        if (!Files.isRegularFile(builtExe)) {
            System.out.println("==== 结果: " + pass + " 通过, " + (fail + 1) + " 失败 ====");
            System.exit(1);
        }

        // 1) 纯逻辑：Main 用到的解析入口必须指向 build/engine
        Path resolved = EngineClient.discoverEnginePath();
        ok("discoverEnginePath() 指向 build/engine/trade_sim.exe",
                resolved.equals(builtExe.toAbsolutePath().normalize()),
                "resolved=" + resolved);

        // 2) 关键：真实启动 tsim.Main（独立进程），classpath **沿用本测试自身的 classpath**，
        //    这样如果被测的 Main 有 bug，本测试才能真的失败（否则会指向另一份好的构建而漏报）。
        String cp = System.getProperty("java.class.path");
        System.out.println("  子进程 classpath = " + cp);
        ok("测试自身 classpath 非空", cp != null && !cp.isEmpty(), String.valueOf(cp));

        int before = countEngineProcesses();
        System.out.println("  启动前 trade_sim 进程数 = " + before);

        Path javaExe = Paths.get(System.getProperty("java.home"), "bin", "java.exe");
        Path outFile = root.resolve("build/entrypoint.out");
        Path errFile = root.resolve("build/entrypoint.err");
        ProcessBuilder pb = new ProcessBuilder(javaExe.toString(), "-cp", cp, "tsim.Main");
        pb.directory(root.toFile());
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());
        Process gui = pb.start();
        System.out.println("  已启动 tsim.Main，PID = " + gui.pid());

        boolean spawned = false;
        int peak = before;
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            int n = countEngineProcesses();
            if (n > peak) {
                peak = n;
            }
            if (n > before) {
                spawned = true;
                System.out.println("  引擎子进程已出现（" + n + " 个，用时约 "
                        + (15_000L - (deadline - System.currentTimeMillis())) + "ms）");
                break;
            }
        }
        ok("启动 tsim.Main 后引擎子进程被拉起（P0 回归）", spawned,
                "15s 内未观察到 trade_sim 进程；stdout=" + readIfExists(outFile));
        ok("GUI 进程在握手期间存活", gui.isAlive(), "GUI 已退出");

        if (Files.isRegularFile(outFile)) {
            String so = new String(Files.readAllBytes(outFile),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (so.contains("引擎路径解析为")) {
                for (String line : so.split("\\R")) {
                    if (line.contains("引擎路径解析为")) {
                        System.out.println("  " + line);
                        ok("启动日志显示解析到 build/engine 下的引擎",
                                line.contains("build") && line.contains("engine"), line);
                    }
                }
            }
        }
        if (Files.isRegularFile(errFile)) {
            String se = new String(Files.readAllBytes(errFile),
                    java.nio.charset.StandardCharsets.UTF_8);
            ok("stderr 未出现“未找到引擎”报错", !se.contains("未找到引擎程序"), se);
        }

        // 清理
        try {
            gui.destroy();
            if (!gui.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                gui.destroyForcibly();
            }
        } catch (RuntimeException ignored) {
            gui.destroyForcibly();
        }
        Thread.sleep(800);
        killEngineProcesses();
        System.out.println("  清理后 trade_sim 进程数 = " + countEngineProcesses());

        System.out.println();
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 用 tasklist 统计 trade_sim.exe 进程数（Windows）。 */
    private static int countEngineProcesses() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq trade_sim.exe", "/NH");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            int n = 0;
            for (String line : sb.toString().split("\\R")) {
                if (line.toLowerCase(java.util.Locale.ROOT).contains("trade_sim.exe")) {
                    n++;
                }
            }
            return n;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static void killEngineProcesses() {
        try {
            new ProcessBuilder("taskkill", "/F", "/IM", "trade_sim.exe")
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {
            // 清理失败不影响测试结论
        }
    }

    private static String readIfExists(Path p) {
        try {
            return Files.isRegularFile(p)
                    ? new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8)
                    : "(无)";
        } catch (Exception ex) {
            return "(读取失败)";
        }
    }

    /** 避免未使用告警。 */
    static List<String> unused() {
        return new ArrayList<>();
    }
}