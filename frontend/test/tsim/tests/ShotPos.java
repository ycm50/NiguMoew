package tsim.tests;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import tsim.ui.EngineClient;
import tsim.ui.MainFrame;

/** 建仓后截图：验证多空持仓行与排序。 */
public final class ShotPos {
    private ShotPos() { }

    public static void main(String[] args) throws Exception {
        for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
            if ("Nimbus".equals(info.getName())) { UIManager.setLookAndFeel(info.getClassName()); break; }
        }
        EngineClient engine = new EngineClient(java.nio.file.Paths.get(args[0]));
        engine.start();
        engine.hello().get(10, java.util.concurrent.TimeUnit.SECONDS);
        final MainFrame[] ref = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame f = new MainFrame(engine);
            f.setSize(1500, 940);
            f.setVisible(true);
            ref[0] = f;
            f.startNewGame("pos");
        });
        Thread.sleep(2500);
        // 建多头 + 空头
        engine.buySell("buy", "SH600519", 100, "market", 0, 1).get(8, java.util.concurrent.TimeUnit.SECONDS);
        engine.stockShortModel("short", "SH600519", 200, "market", 0, 1).get(8, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(3000);
        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame f = ref[0];
            BufferedImage b = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = b.createGraphics();
            f.paint(g);
            g.dispose();
            img[0] = b;
        });
        ImageIO.write(img[0], "png", new File(args[1]));
        System.out.println("saved " + args[1]);
        SwingUtilities.invokeAndWait(() -> ref[0].dispose());
        engine.kill();
        System.exit(0);
    }
}