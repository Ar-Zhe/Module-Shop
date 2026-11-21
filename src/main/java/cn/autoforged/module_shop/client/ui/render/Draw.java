package cn.autoforged.module_shop.client.ui.render;

import com.atom.lib.design.atomchat.SkiaDraw;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * 设计包 {@link SkiaDraw} 之外本模组需要的少量基础图元（描边、线段、透明度换算）。
 * 共享一个 Paint：只在渲染线程使用。
 */
public final class Draw {
    private static final Paint STROKE = new Paint().setMode(PaintMode.STROKE).setAntiAlias(true);
    private static final Paint LINE = new Paint().setMode(PaintMode.STROKE).setAntiAlias(true)
            .setStrokeCap(PaintStrokeCap.ROUND);

    private Draw() {
    }

    /** 填充圆角矩形（radius ≤ 0 为直角）。 */
    public static void fill(Canvas canvas, float x, float y, float w, float h, float radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        SkiaDraw.drawRoundedRect(canvas, x, y, w, h, Math.max(0.0F, radius), color);
    }

    /** 内描边：描边完全落在矩形内部，与原版 1px 边框的视觉位置一致。 */
    public static void stroke(Canvas canvas, float x, float y, float w, float h, float radius, float width, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        float half = width / 2.0F;
        STROKE.setColor(color).setStrokeWidth(width);
        canvas.drawRRect(RRect.makeXYWH(x + half, y + half, w - width, h - width, Math.max(0.0F, radius - half)), STROKE);
    }

    private static final Paint SHADOW = new Paint().setAntiAlias(true);
    private static final java.util.Map<Integer, io.github.humbleui.skija.MaskFilter> BLURS = new java.util.HashMap<>();

    /**
     * 柔和投影：在 (x, y + dy) 处画一块模糊过的圆角矩形。blur 为模糊半径（GUI 像素）。
     * 调用方要在面板本体之前画。
     */
    public static void shadow(Canvas canvas, float x, float y, float w, float h, float radius, float blur, float dy,
                              int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        int key = Math.max(1, Math.round(blur * 4.0F));
        io.github.humbleui.skija.MaskFilter mf = BLURS.computeIfAbsent(key, k ->
                io.github.humbleui.skija.MaskFilter.makeBlur(io.github.humbleui.skija.FilterBlurMode.NORMAL, k / 8.0F));
        SHADOW.setColor(color).setMaskFilter(mf);
        canvas.drawRRect(RRect.makeXYWH(x, y + dy, w, h, Math.max(0.0F, radius)), SHADOW);
        SHADOW.setMaskFilter(null);
    }

    /** 只画在矩形外面的投影：面板半透明时，投影不会从面板底下透出来把面板弄脏。 */
    public static void outerShadow(Canvas canvas, float x, float y, float w, float h, float radius, float blur,
                                   float dy, int color) {
        int saved = canvas.save();
        canvas.clipRRect(RRect.makeXYWH(x, y, w, h, Math.max(0.0F, radius)),
                io.github.humbleui.skija.ClipMode.DIFFERENCE, true);
        shadow(canvas, x, y, w, h, radius, blur, dy, color);
        canvas.restoreToCount(saved);
    }

    /**
     * 半透明“玻璃”面板：外投影 + 半透明底色 + 顶部一道高光 + 1px 边框。
     * 背后是原版的模糊背景，透一点出来显得轻。
     */
    public static void glass(Canvas canvas, float x, float y, float w, float h, int color, int color2, float alpha,
                             int border, int highlight, int glowColor, int shadowColor, float blur, float dy) {
        outerShadow(canvas, x, y, w, h, 0.0F, blur, dy, shadowColor);
        gradientV(canvas, x, y, w, h, 0.0F, alpha(color, alpha), alpha(color2, alpha));
        if ((glowColor >>> 24) != 0) {
            int saved = canvas.save();
            clip(canvas, x, y, w, h);
            glow(canvas, x + w * 0.85F, y + h * 0.1F, Math.max(w, h) * 0.55F, glowColor);
            canvas.restoreToCount(saved);
        }
        fill(canvas, x + 1.0F, y + 1.0F, w - 2.0F, 1.0F, 0.0F, highlight);
        stroke(canvas, x, y, w, h, 0.0F, 1.0F, border);
    }

    private static final Paint GRAD = new Paint().setAntiAlias(true);

    /**
     * 线性渐变填充：从 (x0, y0) 的 from 过渡到 (x1, y1) 的 to（画布坐标）。两色相同时退化为纯色填充。
     */
    public static void gradient(Canvas canvas, float x, float y, float w, float h, float radius, int from, int to,
                                float x0, float y0, float x1, float y1) {
        if (w <= 0 || h <= 0 || ((from >>> 24) == 0 && (to >>> 24) == 0)) {
            return;
        }
        if (from == to) {
            fill(canvas, x, y, w, h, radius, from);
            return;
        }
        try (io.github.humbleui.skija.Shader shader = io.github.humbleui.skija.Shader.makeLinearGradient(
                x0, y0, x1, y1, new int[]{from, to})) {
            GRAD.setShader(shader);
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, Math.max(0.0F, radius)), GRAD);
            GRAD.setShader(null);
        }
    }

    /** 竖直渐变（上 from → 下 to）。 */
    public static void gradientV(Canvas canvas, float x, float y, float w, float h, float radius, int from, int to) {
        gradient(canvas, x, y, w, h, radius, from, to, x, y, x, y + h);
    }

    /** 水平渐变（左 from → 右 to）。 */
    public static void gradientH(Canvas canvas, float x, float y, float w, float h, float radius, int from, int to) {
        gradient(canvas, x, y, w, h, radius, from, to, x, y, x + w, y);
    }

    /** 对角渐变（左上 from → 右下 to）。 */
    public static void gradientD(Canvas canvas, float x, float y, float w, float h, float radius, int from, int to) {
        gradient(canvas, x, y, w, h, radius, from, to, x, y, x + w, y + h);
    }

    /** 柔光：以 (cx, cy) 为圆心、r 为半径的径向渐变（中心 color → 边缘全透明）。调用方负责裁剪。 */
    public static void glow(Canvas canvas, float cx, float cy, float r, int color) {
        if ((color >>> 24) == 0 || r <= 0) {
            return;
        }
        try (io.github.humbleui.skija.Shader shader = io.github.humbleui.skija.Shader.makeRadialGradient(
                cx, cy, r, new int[]{color, alpha(color, 0.45F), color & 0x00FFFFFF},
                new float[]{0.0F, 0.45F, 1.0F})) {
            GRAD.setShader(shader);
            canvas.drawCircle(cx, cy, r, GRAD);
            GRAD.setShader(null);
        }
    }

    public static void line(Canvas canvas, float x1, float y1, float x2, float y2, float width, int color) {
        LINE.setColor(color).setStrokeWidth(width);
        canvas.drawLine(x1, y1, x2, y2, LINE);
    }

    /** 关闭图标（×），中心 (cx, cy)，半边长 r。 */
    public static void cross(Canvas canvas, float cx, float cy, float r, float width, int color) {
        line(canvas, cx - r, cy - r, cx + r, cy + r, width, color);
        line(canvas, cx - r, cy + r, cx + r, cy - r, width, color);
    }

    /** 1px 水平分隔线。 */
    public static void hairline(Canvas canvas, float x, float y, float w, int color) {
        fill(canvas, x, y, w, 1.0F, 0, color);
    }

    /** 裁剪到矩形（调用方负责 save/restore）。 */
    public static void clip(Canvas canvas, float x, float y, float w, float h) {
        canvas.clipRect(Rect.makeXYWH(x, y, w, h));
    }

    /**
     * 开始一个“整体透明度”图层：之后画的所有内容（底色、文字、按钮……）作为一个整体淡入淡出，
     * 不会出现各部分透明度不同步、或重叠处透出底下内容的问题。
     * 用 {@code canvas.restoreToCount(返回值)} 结束。alpha ≥ 1 时不建图层（零开销）。
     */
    public static int beginOpacity(Canvas canvas, float alpha) {
        int saved = canvas.getSaveCount();
        if (alpha < 0.999F) {
            try (Paint paint = new Paint().setAlphaf(Math.max(0.0F, alpha))) {
                canvas.saveLayer(null, paint);
            }
        } else {
            canvas.save();
        }
        return saved;
    }

    /** 颜色乘以透明度（0..1）。 */
    public static int alpha(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    public static int lerp(int from, int to, float t) {
        return SkiaDraw.lerpColor(from, to, Math.max(0.0F, Math.min(1.0F, t)));
    }
}
