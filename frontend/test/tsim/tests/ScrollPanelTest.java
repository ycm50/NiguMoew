package tsim.tests;

import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Field;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import tsim.ui.TradePanel;

/** 右侧面板的滚动行为回归测试（用户：显示不全，加滚轮）。 */
public final class ScrollPanelTest {
    private ScrollPanelTest() { }

    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + "  --  " + detail); }
    }

    private static JScrollPane scrollerOf(TradePanel tp) throws Exception {
        Field f = TradePanel.class.getDeclaredField("scrollHost");
        f.setAccessible(true);
        return (JScrollPane) f.get(tp);
    }

    public static void main(String[] args) throws Exception {
        TradePanel tp = new TradePanel();
        JScrollPane sc = scrollerOf(tp);
        check("面板内含滚动面板", sc != null, "missing");
        check("垂直滚动条按需出现", sc.getVerticalScrollBarPolicy()
                == JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, "policy=" + sc.getVerticalScrollBarPolicy());
        check("水平滚动条禁用", sc.getHorizontalScrollBarPolicy()
                == JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, "policy=" + sc.getHorizontalScrollBarPolicy());
        check("滚轮单位增量已设置", sc.getVerticalScrollBar().getUnitIncrement() > 0,
                "unit=" + sc.getVerticalScrollBar().getUnitIncrement());

        // 小尺寸布局 -> 内容应超出视口，滚动条范围 > 0
        tp.setSize(320, 260);
        tp.doLayout();
        sc.setSize(320, 220);
        sc.doLayout();
        JViewport vp = sc.getViewport();
        vp.doLayout();
        if (vp.getView() != null) {
            vp.getView().setSize(vp.getWidth(), vp.getView().getPreferredSize().height);
        }
        int extent = vp.getExtentSize().height;
        int viewH = vp.getViewSize().height;
        check("内容高于视口（确实需要滚动）", viewH > extent, "view=" + viewH + " extent=" + extent);
        check("滚动范围 > 0", sc.getVerticalScrollBar().getMaximum()
                - sc.getVerticalScrollBar().getVisibleAmount() > 0,
                "max=" + sc.getVerticalScrollBar().getMaximum());

        // 模拟滚轮：位置应变化
        sc.getVerticalScrollBar().setValue(0);
        MouseWheelEvent ev = new MouseWheelEvent(sc, MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 10, 10, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1);
        for (java.awt.event.MouseWheelListener l : sc.getMouseWheelListeners()) {
            l.mouseWheelMoved(ev);
        }
        check("滚轮向下后位置前进", sc.getVerticalScrollBar().getValue() > 0,
                "value=" + sc.getVerticalScrollBar().getValue());
        int after = sc.getVerticalScrollBar().getValue();
        MouseWheelEvent up = new MouseWheelEvent(sc, MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 10, 10, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1);
        for (java.awt.event.MouseWheelListener l : sc.getMouseWheelListeners()) {
            l.mouseWheelMoved(up);
        }
        check("滚轮向上后位置回退", sc.getVerticalScrollBar().getValue() < after,
                "value=" + sc.getVerticalScrollBar().getValue());

        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}