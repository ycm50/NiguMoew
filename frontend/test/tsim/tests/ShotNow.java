package tsim.tests;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import tsim.ui.EngineClient;
import tsim.ui.MainFrame;

/** 截取主窗口（用于人工核对界面）。 */
public final class ShotNow {
    private ShotNow() { }

    public static void main(String[] args) throws Exception {
        String exe = args[0];
        String out = args[1];
        for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
            if ("Nimbus".equals(info.getName())) {
                UIManager.setLookAndFeel(info.getClassName());
                break;
            }
        }
        EngineClient engine = new EngineClient(java.nio.file.Paths.get(exe));
        engine.start();
        engine.hello().get(10, java.util.concurrent.TimeUnit.SECONDS);
        final MainFrame[] ref = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame f = new MainFrame(engine);
            f.setSize(1500, 940);
            f.setVisible(true);
            ref[0] = f;
            f.startNewGame("shot");
        });
        Thread.sleep(4000);
        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame f = ref[0];
            BufferedImage b = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = b.createGraphics();
            f.paint(g);
            g.dispose();
            img[0] = b;
        });
        ImageIO.write(img[0], "png", new File(out));
        System.out.println("saved " + out);
        SwingUtilities.invokeAndWait(() -> ref[0].dispose());
        engine.kill();
        System.exit(0);
    }
}