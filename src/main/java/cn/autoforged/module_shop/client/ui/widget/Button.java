package cn.autoforged.module_shop.client.ui.widget;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import io.github.humbleui.skija.Canvas;
import java.util.function.Supplier;

/**
 * 按钮：扁平底色 + 1px 边框，悬停时边框点亮、底色变亮，按下轻微缩放。
 * 配色全部取自 {@link IOStyle}。
 *
 * <p>激活规则：左键在按钮内按下、并在按钮内松开才触发（拖出取消），与原版按钮一致。
 */
public final class Button extends UiComponent {
    public enum Kind {
        /** 普通灰按钮。 */
        NORMAL,
        /** 主要操作（强调色）。 */
        ACCENT,
        /** 危险操作（红色）。 */
        DANGER,
        /** 页签：选中时显示强调色底与左侧指示条。 */
        TAB
    }

    private Kind kind;
    private Supplier<String> label;
    private Runnable onClick;
    private boolean selected;
    private float radius = 3.0F;
    private float fontSize = Text.BODY;

    private float hoverT;
    private float pressT;
    private float selectT;
    private boolean pressed;

    public Button(Kind kind, String label, Runnable onClick) {
        this(kind, () -> label, onClick);
    }

    public Button(Kind kind, Supplier<String> label, Runnable onClick) {
        this.kind = kind;
        this.label = label;
        this.onClick = onClick;
    }

    /** 切换样式（例如“可刷新”时用强调色、否则用普通色）。 */
    public Button kind(Kind kind) {
        this.kind = kind;
        return this;
    }

    public Button selected(boolean selected) {
        this.selected = selected;
        return this;
    }

    public boolean isSelected() {
        return selected;
    }

    public Button radius(float radius) {
        this.radius = radius;
        return this;
    }

    public Button fontSize(float size) {
        this.fontSize = size;
        return this;
    }

    public Button label(String label) {
        this.label = () -> label;
        return this;
    }

    public Button onClick(Runnable onClick) {
        this.onClick = onClick;
        return this;
    }

    @Override
    public void render(Canvas canvas, float mouseX, float mouseY, float dtMs) {
        if (!visible) {
            return;
        }
        boolean hover = enabled && isMouseOver(mouseX, mouseY);
        hoverT = Motion.approach(hoverT, hover ? 1.0F : 0.0F, dtMs, Motion.HOVER_MS);
        pressT = Motion.approach(pressT, pressed && hover ? 1.0F : 0.0F, dtMs, Motion.PRESS_MS);
        selectT = Motion.approach(selectT, selected ? 1.0F : 0.0F, dtMs, Motion.PRESS_MS);

        int fill;
        int border;
        int textColor;
        if (!enabled) {
            fill = IOStyle.DISABLED_BG;
            border = IOStyle.BORDER_DIM;
            textColor = IOStyle.DISABLED_TEXT;
        } else {
            switch (kind) {
                case ACCENT -> {
                    fill = Draw.lerp(IOStyle.BLUE, IOStyle.BLUE_HOVER, hoverT);
                    border = Draw.lerp(IOStyle.BLUE, IOStyle.BLUE_HOVER, hoverT);
                    textColor = 0xFFFFFFFF;
                }
                case DANGER -> {
                    fill = Draw.lerp(IOStyle.RED_TINT, IOStyle.RED_TINT_HOVER, hoverT);
                    border = Draw.lerp(IOStyle.RED_TINT_HOVER, IOStyle.RED, hoverT);
                    textColor = IOStyle.RED;
                }
                case TAB -> {
                    int base = Draw.lerp(IOStyle.TAB_BG, IOStyle.TAB_BG_HOVER, hoverT);
                    fill = Draw.lerp(base, IOStyle.BLUE_TINT, selectT);
                    border = Draw.lerp(Draw.lerp(IOStyle.TAB_BORDER, IOStyle.TAB_BORDER_HOVER, hoverT),
                            IOStyle.TAB_SEL_BORDER, selectT);
                    textColor = Draw.lerp(IOStyle.TEXT_2, IOStyle.TEXT, Math.max(hoverT, selectT));
                }
                default -> {
                    fill = Draw.lerp(IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER, hoverT);
                    border = Draw.lerp(IOStyle.BORDER, IOStyle.BORDER_HOVER, hoverT);
                    textColor = IOStyle.TEXT;
                }
            }
        }

        // 按下缩放 0.97（以按钮中心为原点）
        float scale = 1.0F - 0.03F * pressT;
        int saved = canvas.save();
        try {
            float cx = x + width / 2.0F;
            float cy = y + height / 2.0F;
            canvas.translate(cx, cy);
            canvas.scale(scale, scale);
            canvas.translate(-cx, -cy);

            Draw.fill(canvas, x, y, width, height, radius, fill);
            Draw.stroke(canvas, x, y, width, height, radius, 1.0F, border);
            if (kind == Kind.TAB && selectT > 0.01F) {
                float barH = (height - 6.0F) * selectT;
                Draw.fill(canvas, x + 2.0F, y + (height - barH) / 2.0F, 2.0F, barH, 1.0F,
                        Draw.alpha(IOStyle.BLUE, selectT));
            }
            float pad = kind == Kind.TAB ? 8.0F : 4.0F;
            Text.drawLine(canvas, label.get(), AtomUi.REGULAR, fontSize, textColor,
                    x + pad, y + height / 2.0F, width - pad * 2.0F,
                    kind == Kind.TAB ? Text.Align.LEFT : Text.Align.CENTER);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !enabled || button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        pressed = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0 || !pressed) {
            return false;
        }
        pressed = false;
        if (enabled && isMouseOver(mouseX, mouseY)) {
            ClickSound.play();
            if (onClick != null) {
                onClick.run();
            }
        }
        return true;
    }

    /** 界面失焦 / 关闭时取消未完成的按下。 */
    public void cancelPress() {
        pressed = false;
    }
}
