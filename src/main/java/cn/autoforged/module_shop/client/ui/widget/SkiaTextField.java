package cn.autoforged.module_shop.client.ui.widget;

import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.input.ImePosition;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import com.atom.lib.geometry.Rect;
import com.atom.lib.text.TextLayout;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Skija 绘制的单行输入框（思路参考 AtomChat 的 ComposerInput / ComposerPointer，MIT）。
 *
 * <p><b>分工</b>：真正承载文字的是一个<b>不绘制</b>的原版 {@link EditBox}——键盘输入、删除、
 * 复制粘贴、Ctrl+A、输入法、长度上限、非法字符过滤全部由它完成；本类只负责用 Skija 画出
 * 背景、文字、选区、光标与占位提示，并把鼠标点击/拖拽换算成光标位置交给 EditBox。
 *
 * <p><b>接线</b>（由所在 Screen 完成）：
 * <ol>
 *     <li>在 {@code init()} 里 {@code addWidget(field.editBox())}（注意不是 addRenderableWidget）；</li>
 *     <li>鼠标按下/拖拽/松开转发给本组件；键盘与字符事件由 Screen 默认的焦点机制交给 EditBox。</li>
 * </ol>
 * EditBox 的位置每帧同步到本组件的位置，输入法候选窗会出现在输入框附近。
 */
public final class SkiaTextField extends UiComponent {
    private static final float PAD_X = 5.0F;
    private static final long BLINK_MS = 530L;

    private final Screen screen;
    private final EditBox box;
    private String placeholder = "";
    private float fontSize = Text.BODY;

    private float scrollX;
    private float focusT;
    private float hoverT;
    private boolean selecting;
    private long caretReset = System.currentTimeMillis();
    private int lastCursor = -1;

    public SkiaTextField(Screen screen, Font font, int maxLength) {
        this.screen = screen;
        this.box = new EditBox(font, 0, 0, 10, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setCanLoseFocus(true);
        // 原版文字与光标完全不画，只保留输入能力
        box.setTextColor(0x00FFFFFF);
        box.setTextColorUneditable(0x00FFFFFF);
        // 第一次渲染前不可见：否则它停在 (0,0) 仍能被原版点击逻辑点中、悄悄抢走焦点
        box.setVisible(false);
    }

    /**
     * 隐藏时同步隐藏背后的 EditBox 并让它失焦：页面不渲染这个输入框时（切到别的页签 / 别的视图），
     * 它不能还留在屏幕上被原版点击逻辑点中、接收键盘输入。
     */
    @Override
    public SkiaTextField visible(boolean visible) {
        super.visible(visible);
        if (!visible) {
            if (box.isFocused()) {
                blur();
            }
            box.setVisible(false);
        }
        return this;
    }

    /** 给 Screen 的 {@code addWidget(...)} 用。 */
    public EditBox editBox() {
        return box;
    }

    public SkiaTextField placeholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        return this;
    }

    public SkiaTextField fontSize(float size) {
        this.fontSize = size;
        return this;
    }

    public SkiaTextField onChange(Consumer<String> responder) {
        box.setResponder(responder);
        return this;
    }

    public String value() {
        return box.getValue();
    }

    public void setValue(String value) {
        box.setValue(value == null ? "" : value);
    }

    public boolean isFocused() {
        return box.isFocused();
    }

    public void focus() {
        screen.setFocused(box);
        box.setFocused(true);
        caretReset = System.currentTimeMillis();
    }

    public void blur() {
        ImePosition.reset();
        box.setFocused(false);
        if (screen.getFocused() == box) {
            screen.setFocused(null);
        }
        selecting = false;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, float mouseX, float mouseY, float dtMs) {
        if (!visible) {
            box.setVisible(false);
            return;
        }
        syncEditBox();
        boolean focused = box.isFocused();
        focusT = Motion.approach(focusT, focused ? 1.0F : 0.0F, dtMs, Motion.PRESS_MS);
        hoverT = Motion.approach(hoverT, isMouseOver(mouseX, mouseY) ? 1.0F : 0.0F, dtMs, Motion.HOVER_MS);

        // 背景与边框：聚焦时边框变强调色，外圈一层淡淡的焦点环
        if (focusT > 0.01F) {
            Draw.stroke(canvas, x - 1.5F, y - 1.5F, width + 3.0F, height + 3.0F, 4.5F, 1.5F,
                    Draw.alpha(IOStyle.FOCUS, focusT));
        }
        Draw.fill(canvas, x, y, width, height, 3.0F, IOStyle.FIELD_BG);
        int border = Draw.lerp(Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER, hoverT), IOStyle.BLUE, focusT);
        Draw.stroke(canvas, x, y, width, height, 3.0F, 1.0F, border);

        float innerX = x + PAD_X;
        float innerW = width - PAD_X * 2.0F;
        String value = box.getValue();
        TextLayout layout = Text.line(value, AtomUi.REGULAR, fontSize, IOStyle.TEXT, 0);
        float textH = layout.size().height();
        float textY = y + (height - textH) / 2.0F;

        if (value.isEmpty()) {
            // 聚焦后隐藏占位提示：输入法的拼音预览会画在这个位置，两者重叠会很乱
            if (!placeholder.isEmpty() && !focused) {
                Text.drawLine(canvas, placeholder, AtomUi.REGULAR, fontSize, IOStyle.TEXT_3,
                        innerX, y + height / 2.0F, innerW, Text.Align.LEFT);
            }
            scrollX = 0.0F;
        }

        int cursor = clampOffset(box.getCursorPosition(), value);
        if (cursor != lastCursor) {
            lastCursor = cursor;
            caretReset = System.currentTimeMillis(); // 光标移动时重新开始闪烁，保证可见
        }
        float caretX = caretX(layout, cursor, value);
        // 水平滚动：保证光标始终在可见范围内
        float textW = layout.size().width();
        if (caretX - scrollX > innerW - 1.0F) {
            scrollX = caretX - innerW + 1.0F;
        } else if (caretX - scrollX < 0.0F) {
            scrollX = caretX;
        }
        scrollX = Math.max(0.0F, Math.min(scrollX, Math.max(0.0F, textW - innerW + 1.0F)));

        int saved = canvas.save();
        try {
            Draw.clip(canvas, innerX - 1.0F, y + 1.0F, innerW + 2.0F, height - 2.0F);
            float originX = innerX - scrollX;

            // 选区高亮
            int anchor = clampOffset(box.highlightPos, value);
            if (focused && anchor != cursor && !value.isEmpty()) {
                List<Rect> rects = layout.selection(Math.min(anchor, cursor), Math.max(anchor, cursor));
                for (Rect r : rects) {
                    Draw.fill(canvas, originX + r.x(), textY + r.y(), r.width(), r.height(), 1.0F,
                            Draw.alpha(IOStyle.BLUE, 0.45F));
                }
            }
            if (!value.isEmpty()) {
                Text.draw(canvas, layout, originX, textY);
            }
            if (focused) {
                float cx = Math.round((originX + caretX) * 2.0F) / 2.0F;
                float caretH = Math.max(textH, fontSize + 2.0F);
                float caretTop = y + (height - caretH) / 2.0F;
                // 光标（1px 竖线，闪烁）
                if (((System.currentTimeMillis() - caretReset) / BLINK_MS) % 2 == 0) {
                    Draw.fill(canvas, cx, caretTop, 1.0F, caretH, 0.0F, IOStyle.TEXT);
                }
                // 输入法：拼音组字窗与本行文字对齐、字号一致；候选窗整体放在输入框下方
                ImePosition.place(cx, textY, fontSize, x, y, width, height);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    /** 把隐藏 EditBox 的位置与本组件对齐（输入法候选窗据此定位）。 */
    private void syncEditBox() {
        // 画布单位可能不等于 GUI 坐标（UI v2 自有缩放）：换算后再放原版 EditBox，保证点击与画面一致
        float k = cn.autoforged.module_shop.client.ui.render.SkiaFrame.unitsToGui();
        box.setX(Math.round((x + PAD_X) * k));
        box.setY(Math.round((y + (height - 8.0F) / 2.0F) * k));
        box.setWidth(Math.max(1, Math.round((width - PAD_X * 2.0F) * k)));
        box.setHeight(Math.max(1, Math.round(height * k)));
        box.setVisible(visible && enabled);
        box.setEditable(enabled);
    }

    private static float caretX(TextLayout layout, int offset, String value) {
        if (layout.lines().isEmpty() || value.isEmpty()) {
            return 0.0F;
        }
        float best = 0.0F;
        for (TextLayout.Caret caret : layout.lines().get(0).carets()) {
            if (caret.offset() <= offset) {
                best = caret.x();
            } else {
                break;
            }
        }
        return best;
    }

    private static int clampOffset(int offset, String value) {
        return Math.max(0, Math.min(offset, value.length()));
    }

    // ------------------------------------------------------------------ 鼠标

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !enabled) {
            return false;
        }
        if (!isMouseOver(mouseX, mouseY)) {
            if (box.isFocused()) {
                blur(); // 点到外面失去焦点，但不吞掉这次点击
            }
            return false;
        }
        if (button != 0) {
            return true;
        }
        boolean extend = box.isFocused() && Screen.hasShiftDown();
        focus();
        box.moveCursorTo(offsetAt(mouseX), extend);
        selecting = true;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!selecting || button != 0) {
            return false;
        }
        box.moveCursorTo(offsetAt(mouseX), true);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!selecting || button != 0) {
            return false;
        }
        selecting = false;
        return true;
    }

    private int offsetAt(double mouseX) {
        String value = box.getValue();
        if (value.isEmpty()) {
            return 0;
        }
        TextLayout layout = Text.line(value, AtomUi.REGULAR, fontSize, IOStyle.TEXT, 0);
        float localX = (float) (mouseX - (x + PAD_X - scrollX));
        return clampOffset(layout.hitTest(localX, 0.0F), value);
    }
}
