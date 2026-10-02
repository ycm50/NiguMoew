package tsim.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import tsim.ui.EngineClient;

/** 验证引擎探测：模拟 lead 的交付布局。 */
public final class DiscoveryCheck {
    private DiscoveryCheck() {
    }

    /** 运行。 */
    public static void main(String[] args) throws Exception {
        Path repo = Paths.get(args[0]).toAbsolutePath();
        Path classes = repo.resolve("build/frontend/classes");
        // 造出 build/engine/trade_sim.exe 与 <repo>/engine/trade_sim.exe 两个候选
        Path builtEngine = repo.resolve("build/engine");
        Path rootEngine = repo.resolve("engine");
        Files.createDirectories(builtEngine);
        Files.createDirectories(rootEngine);
        Path builtExe = builtEngine.resolve("trade_sim.exe");
        Path rootExe = rootEngine.resolve("trade_sim.exe");
        boolean createdBuilt = !Files.exists(builtExe);
        boolean createdRoot = !Files.exists(rootExe);
        if (createdBuilt) {
            Files.write(builtExe, new byte[]{(byte) 'M', (byte) 'Z'});
        }
        if (createdRoot) {
            Files.write(rootExe, new byte[]{(byte) 'M', (byte) 'Z'});
        }
        try {
            Path found = EngineClient.discoverEnginePath();
            System.out.println("classes dir      = " + classes);
            System.out.println("found engine     = " + found);
            boolean hit = found.equals(builtExe.normalize()) || found.equals(rootExe.normalize());
            System.out.println(hit ? "[PASS] 探测命中交付布局" : "[FAIL] 未命中，found=" + found);
            if (!hit) {
                System.exit(1);
            }
        } finally {
            if (createdBuilt) {
                Files.deleteIfExists(builtExe);
                Files.deleteIfExists(builtEngine);
            }
            if (createdRoot) {
                Files.deleteIfExists(rootExe);
                Files.deleteIfExists(rootEngine);
            }
        }
    }
}
