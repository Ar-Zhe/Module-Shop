package cn.autoforged.module_shop.client.ui.widget;

import cn.autoforged.module_shop.client.screen.IOStyle;
import com.atom.lib.design.atomchat.SkiaDraw;
import io.github.humbleui.skija.Canvas;

/**
 * 可滚动列表的状态机（改编自 AtomChat {@code com.atom.chat.ui.ScrollController}，MIT License，
 * Copyright (c) 2026 E33EPUS）。
 *
 * <p>与原版的区别：
 * <ul>
 *     <li>尺寸单位改为 GUI 像素；去掉了对 AtomChat UiTokens/UiLayout 的依赖；</li>
 *     <li>默认顶部锚定（商店列表从顶部开始），仅 {@code bottomAnchor=true} 时跟随底部；</li>
 *     <li>滚动条的绘制与命中测试合并在本类，页面只需传入列表矩形。</li>
 * </ul>
 * 每个列表一个实例，互不影响——侧边栏和内容区可以同时独立滚动。
 */
public final class ScrollController {
    private static final float WHEEL_STEP = 24.0F;
    private static final long WHEEL_MS = 140L;
    private static final long SNAP_MS = 220L;
    private static final float BOTTOM_TOLERANCE = 2.0F;
    private static final float BAR_WIDTH = 3.0F;
    private static final float BAR_WIDTH_HOVER = 5.0F;
    private static final float BAR_INSET = 2.0F;
    private static final float THUMB_MIN = 14.0F;
    private static final float HOT_ZONE = 8.0F;

    private final boolean bottomAnchor;

    private float scrollY;
    private float maxScroll;
    private float viewportHeight;
    private float scrollTarget;
    private boolean followBottom;
    private boolean firstFrameSnap;

    private boolean animActive;
    private float animFrom;
    private float animTo;
    private long animStart;
    private long animMs;

    private boolean dragging;
    private float dragStartY;
    private float dragStartScroll;

    private float barAlpha;
    private float barEmphasis;

    public ScrollController() {
        this(false);
    }

    /** @param bottomAnchor true：首帧贴底并跟随新内容（日志类）；false：从顶部开始（商店列表）。 */
    public ScrollController(boolean bottomAnchor) {
        this.bottomAnchor = bottomAnchor;
        this.followBottom = bottomAnchor;
        this.firstFrameSnap = bottomAnchor;
    }

    /** 每帧布局后调用：告诉控制器内容总高与可视高度。 */
    public void setContent(float contentHeight, float viewportHeight) {
        this.viewportHeight = Math.max(0.0F, viewportHeight);
        maxScroll = Math.max(0.0F, contentHeight - this.viewportHeight);
        scrollY = clamp(scrollY);
        scrollTarget = clamp(scrollTarget);
        if (animActive) {
            animTo = clamp(animTo);
        }
    }

    /** 每帧调用一次，推进平滑滚动。 */
    public void update() {
        long now = System.currentTimeMillis();
        if (firstFrameSnap) {
            scrollY = scrollTarget = maxScroll;
            firstFrameSnap = false;
            return;
        }
        if (bottomAnchor && followBottom && scrollTarget < maxScroll) {
            animateTo(maxScroll, SNAP_MS, now);
        }
        if (animActive) {
            float t = Math.min(1.0F, (now - animStart) / (float) animMs);
            scrollY = animFrom + (animTo - animFrom) * Motion.easeOutCubic(t);
            if (t >= 1.0F) {
                scrollY = animTo;
                animActive = false;
            }
        }
    }

    public float offset() {
        return scrollY;
    }

    public float maxScroll() {
        return maxScroll;
    }

    public boolean canScroll() {
        return maxScroll > 0.0F;
    }

    public boolean hasMoreBelow() {
        return scrollTarget < maxScroll - BOTTOM_TOLERANCE;
    }

    /** 鼠标滚轮：amount 为 Minecraft 的 scrollY（正数 = 向上）。 */
    public void wheel(double amount) {
        if (maxScroll <= 0.0F) {
            return;
        }
        float target = clamp(scrollTarget - (float) amount * WHEEL_STEP);
        followBottom = bottomAnchor && target >= maxScroll - BOTTOM_TOLERANCE;
        animateTo(target, WHEEL_MS, System.currentTimeMillis());
    }

    /** 跳到指定位置（例如把选中项滚入视野）。 */
    public void scrollTo(float y, boolean animate) {
        firstFrameSnap = false;
        float target = clamp(y);
        followBottom = bottomAnchor && target >= maxScroll - BOTTOM_TOLERANCE;
        if (animate) {
            animateTo(target, SNAP_MS, System.currentTimeMillis());
        } else {
            scrollY = scrollTarget = target;
            animActive = false;
        }
    }

    /** 保证 [top, bottom]（内容坐标）完整可见。 */
    public void ensureVisible(float top, float bottom) {
        if (top < scrollTarget) {
            scrollTo(top, true);
        } else if (bottom > scrollTarget + viewportHeight) {
            scrollTo(bottom - viewportHeight, true);
        }
    }

    public void reset() {
        scrollY = scrollTarget = 0.0F;
        animActive = false;
        dragging = false;
        followBottom = bottomAnchor;
        firstFrameSnap = bottomAnchor;
    }

    // ------------------------------------------------------------------ 滚动条

    /** 鼠标是否在滚动条的可拖拽区域（列表矩形为 x,y,w,h）。 */
    public boolean overBar(double mouseX, double mouseY, float x, float y, float w, float h) {
        if (maxScroll <= 0.0F) {
            return false;
        }
        float trackX = x + w - BAR_WIDTH_HOVER - BAR_INSET;
        return mouseX >= trackX - HOT_ZONE / 2 && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    /** 在滚动条上按下：点在滑块上则开始拖拽，点在轨道上则跳页。返回是否消费。 */
    public boolean pressBar(double mouseX, double mouseY, float x, float y, float w, float h) {
        if (!overBar(mouseX, mouseY, x, y, w, h)) {
            return false;
        }
        float thumbH = thumbHeight(h);
        float thumbY = y + (h - thumbH) * (maxScroll <= 0 ? 0 : scrollY / maxScroll);
        if (mouseY < thumbY) {
            scrollTo(scrollTarget - h * 0.9F, true);
        } else if (mouseY > thumbY + thumbH) {
            scrollTo(scrollTarget + h * 0.9F, true);
        } else {
            dragging = true;
            dragStartY = (float) mouseY;
            dragStartScroll = scrollY;
        }
        return true;
    }

    public boolean dragBar(double mouseY, float h) {
        if (!dragging) {
            return false;
        }
        float travel = h - thumbHeight(h);
        float delta = ((float) mouseY - dragStartY) * (travel > 0.0F ? maxScroll / travel : 0.0F);
        scrollY = scrollTarget = clamp(dragStartScroll + delta);
        animActive = false;
        followBottom = bottomAnchor && scrollTarget >= maxScroll - BOTTOM_TOLERANCE;
        return true;
    }

    public boolean releaseBar() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    public boolean isDragging() {
        return dragging;
    }

    /** 绘制滚动条：鼠标靠近列表右侧或拖拽时淡入、变粗，不需要时淡出。 */
    public void drawBar(Canvas canvas, float x, float y, float w, float h, float mouseX, float mouseY, float dtMs) {
        boolean inside = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        boolean near = overBar(mouseX, mouseY, x, y, w, h);
        barAlpha = Motion.approach(barAlpha, maxScroll > 0.0F && (inside || dragging || animActive) ? 1.0F : 0.0F,
                dtMs, 90.0F);
        barEmphasis = Motion.approach(barEmphasis, near || dragging ? 1.0F : 0.0F, dtMs, Motion.HOVER_MS);
        if (barAlpha <= 0.01F || maxScroll <= 0.0F) {
            return;
        }
        float barW = BAR_WIDTH + (BAR_WIDTH_HOVER - BAR_WIDTH) * barEmphasis;
        float trackX = x + w - barW - BAR_INSET;
        float thumbH = thumbHeight(h);
        float thumbY = y + (h - thumbH) * (scrollY / maxScroll);
        int alpha = Math.round(255 * barAlpha);
        int track = withAlpha(IOStyle.HAIR, Math.round(alpha * 0.6F * barEmphasis));
        int thumb = withAlpha(SkiaDraw.lerpColor(IOStyle.TEXT_3, IOStyle.TEXT_2, barEmphasis), alpha);
        if (barEmphasis > 0.01F) {
            SkiaDraw.drawRoundedRect(canvas, trackX, y, barW, h, barW / 2, track);
        }
        SkiaDraw.drawRoundedRect(canvas, trackX, thumbY, barW, thumbH, barW / 2, thumb);
    }

    private float thumbHeight(float h) {
        float ratio = Math.min(1.0F, h / (h + maxScroll));
        return Math.max(THUMB_MIN, h * ratio);
    }

    private void animateTo(float target, long ms, long now) {
        scrollTarget = target;
        if (Math.abs(scrollY - target) <= 0.5F) {
            scrollY = target;
            animActive = false;
            return;
        }
        if (animActive && animTo == target) {
            return;
        }
        animFrom = scrollY;
        animTo = target;
        animStart = now;
        animMs = ms;
        animActive = true;
    }

    private float clamp(float v) {
        return Math.max(0.0F, Math.min(v, maxScroll));
    }

    private static int withAlpha(int argb, int alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * (Math.max(0, Math.min(255, alpha)) / 255.0F));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
