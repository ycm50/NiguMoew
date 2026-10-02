package tsim.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * 只读、点击即复制的「标签」。
 *
 * <p>普通 {@code JLabel} 无法选中文本，用户想复制标的代码（如 {@code SH600519}）只能手打。
 * 这里用无边框、背景透明的只读 {@link JTextField} 代替：外观与 JLabel 一致，</p>
 *
 * <ul>
 *   <li><b>单击 = 复制</b>（复制 {@link #setCopyText 指定的文本}，默认取自身文本）；</li>
 *   <li>拖选 + Ctrl+C 仍可用，便于手动选取片段；</li>
 *   <li>鼠标移上去变手型并显示提示，明确指出会复制什么。</li>
 * </ul>
 *
 * <p>注意：不复用「双击全选」那种交互——双击会把整个标题高亮选中（含名称），
 * 而用户真正想复制的往往只是代码本身。</p>
 */
public final class CopyableLabel extends JTextField {

    private static final long serialVersionUID = 1L;

    /** 点击时实际复制到剪贴板的文本（默认与显示文本一致）。 */
    private String copyText;

    /** 复制成功后的回调（用于弹一个轻提示，可不设）。 */
    private Runnable onCopied;

    /** 构造一个初始文本为 text 的可复制标签。 */
    public CopyableLabel(String text) {
        super(text == null ? "" : text);
        this.copyText = getText();
        setEditable(false);
        // 不抢焦点：点一下就复制，不该把焦点从别处拽走（也避免和空格热键互相干扰）
        setFocusable(false);
        setBorder(null);
        setOpaque(false);
        setMargin(new Insets(0, 0, 0, 0));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("单击复制代码");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    copyNow();
                }
            }
        });
    }

    /**
     * 指定点击时要复制的文本。
     *
     * <p>标题显示的是「名称 + 代码」，而用户想复制的是代码，所以两者要能分开。</p>
     */
    public void setCopyText(String text) {
        this.copyText = text == null ? "" : text;
    }

    /** 当前点击会复制的文本。 */
    public String copyText() {
        return copyText == null || copyText.isEmpty() ? getText() : copyText;
    }

    /** 复制成功回调（例如刷新状态栏提示）。 */
    public void setOnCopied(Runnable r) {
        this.onCopied = r;
    }

    /** 执行复制；文本为空则什么也不做。 */
    public void copyNow() {
        String t = copyText();
        if (t == null || t.isEmpty()) {
            return;
        }
        // 无图形环境（headless / 无剪贴板）时不应抛异常，静默跳过即可
        try {
            StringSelection sel = new StringSelection(t);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
        } catch (RuntimeException ex) {
            return;
        }
        // 给一个短暂的视觉反馈：反选一下，让用户知道点中了
        selectAll();
        SwingUtilities.invokeLater(() -> {
            if (onCopied != null) {
                onCopied.run();
            }
        });
    }

    /** 兼容旧调用点。 */
    public void copyToClipboard() {
        copyNow();
    }

    /** 设置字体。 */
    @Override
    public void setFont(Font f) {
        super.setFont(f);
        setSelectionColor(UITheme.ACCENT);
        setSelectedTextColor(Color.WHITE);
    }

    /** 便于测试：是否处于「可点击复制」状态。 */
    public boolean isCopyable() {
        return !isEditable() && isEnabled();
    }
}
