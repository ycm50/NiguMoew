package tsim.tests;

import java.lang.reflect.Method;
import javax.swing.JFrame;
import tsim.ui.MainFrame;

/** 验证改名生效：窗口标题、退出确认、关于框文案。 */
public final class NameTest {
    private NameTest() { }

    private static int pass; private static int fail;
    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("[PASS] " + n); }
        else { fail++; System.out.println("[FAIL] " + n + "  --  " + d); }
    }

    public static void main(String[] args) throws Exception {
        // 构造一个不启动引擎的 MainFrame 走不通（需要 EngineClient），改为检查源码常量。
        // 用反射读取 MainFrame 的类注释与字符串常量不可行，直接读 class 常量池。
        java.io.File f = new java.io.File("build/frontend/classes/tsim/ui/MainFrame.class");
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        String pool = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
        // UTF-8 常量在 class 里是 modified-UTF8，用 GBK/UTF8 双解尝试
        String utf = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        check("MainFrame 含新名 拟股喵喵", utf.contains("拟股喵喵"), "not found");
        check("MainFrame 不含旧名 交易大亨", !utf.contains("交易大亨"), "old name remains");
        check("MainFrame 不含旧英文名 TradeTower", !pool.contains("TradeTower"), "old en name remains");

        for (String cls : new String[]{"tsim/ui/SplashWindow", "tsim/Main"}) {
            byte[] bb = java.nio.file.Files.readAllBytes(
                    new java.io.File("build/frontend/classes/" + cls + ".class").toPath());
            String u = new String(bb, java.nio.charset.StandardCharsets.UTF_8);
            String p = new String(bb, java.nio.charset.StandardCharsets.ISO_8859_1);
            check(cls + " 无旧名残留", !u.contains("交易大亨") && !p.contains("TradeTower"), "old name found");
        }

        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}