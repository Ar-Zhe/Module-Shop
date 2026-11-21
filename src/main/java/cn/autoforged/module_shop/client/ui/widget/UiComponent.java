package cn.autoforged.module_shop.client.ui.widget;

import io.github.humbleui.skija.Canvas;

/**
 * 绝对坐标组件基类（改编自 AtomChat {@code com.atom.chat.ui.UiComponent}，MIT License，
 * Copyright (c) 2026 E33EPUS）。
 *
 * <p>坐标单位为 GUI 像素，与 {@code Screen} 的鼠标坐标一致。布局由所在页面每帧通过
 * {@link #bounds} 设置；组件只负责自身的绘制、悬停动画与输入。
 */
public abstract class UiComponent {
    protected float x;
    protected float y;
    protected float width;
    protected float height;
    protected boolean visible = true;
    protected boolean enabled = true;

    /** 每帧调用：先更新动画（dtMs 为本帧毫秒数，mouse 为 GUI 坐标），再绘制。 */
    public abstract void render(Canvas canvas, float mouseX, float mouseY, float dtMs);

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public UiComponent bounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    public UiComponent visible(boolean visible) {
        this.visible = visible;
        return this;
    }

    public UiComponent enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
