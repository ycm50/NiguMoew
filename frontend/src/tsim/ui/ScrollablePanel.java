package tsim.ui;

import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/**
 * 可滚动容器：宽度跟随视口、高度取自然值。
 *
 * <p>放进 {@code JScrollPane} 后，内容会随视口宽度自适应（不会被横向裁掉），
 * 而高度按内容自然伸展，超出视口时由垂直滚动条接管。</p>
 */
public final class ScrollablePanel extends JPanel implements Scrollable {

    private static final long serialVersionUID = 1L;

    @Override
    public java.awt.Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(16, visible.height - 32);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}