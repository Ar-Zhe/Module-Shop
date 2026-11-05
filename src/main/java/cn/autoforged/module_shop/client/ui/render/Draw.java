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
