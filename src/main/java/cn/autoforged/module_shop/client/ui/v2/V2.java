package cn.autoforged.module_shop.client.ui.v2;

import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import io.github.humbleui.skija.Canvas;

/**
 * UI v2 的绘制套件：统一列表视觉层的尺寸、字号与常用小部件（按钮、标签、分段、勾选框、表头、图标）。
 *
 * <p>单位是 UI v2 的画布单位（见 {@code ShellV2}：1 单位 ≈ 窗口宽 / 800）。颜色全部取 {@link IOStyle}，
 * 所以 7 套皮肤自动适配。所有方法只画，不处理输入；命中判定由调用方用同一份几何完成。
 */
public final class V2 {
    private V2() {
    }

    // ------------------------------------------------------------------ 尺寸与字号
    /** 表格行高 / 表头高 / 页内工具条高 / 底栏高。 */
    public static final float ROW = 15.0F;
    public static final float HEAD = 15.0F;
    public static final float BAR = 24.0F;
    public static final float FOOT = 17.0F;
    /** 行内物品图标边长。 */
    public static final float ICON = 12.0F;
    /** 正文 / 小字 / 注释 / 页标题。 */
    public static final float FS = 7.0F;
    public static final float FS_S = 6.0F;
    public static final float FS_XS = 5.25F;
    public static final float FS_T = 9.5F;
    /** 控件圆角。 */
    public static final float R = 3.5F;
    /** 控件高（按钮 / 输入框 / 分段）。 */
    public static final float CTRL = 15.0F;

    // ------------------------------------------------------------------ 文字
    public static float text(Canvas c, String s, float size, int color, float x, float cy, float w, Text.Align a) {
        return Text.drawLine(c, s, AtomUi.REGULAR, size, color, x, cy, w, a);
    }

    public static float bold(Canvas c, String s, float size, int color, float x, float cy, float w, Text.Align a) {
        return Text.drawLine(c, s, AtomUi.BOLD, size, color, x, cy, w, a);
    }

    public static float width(String s, float size, boolean bold) {
        return Text.width(s, bold ? AtomUi.BOLD : AtomUi.REGULAR, size);
    }

    public static String money(long v) {
        return String.format("%,d", v);
    }

    public static String signed(long v) {
        return (v >= 0 ? "+" : "−") + String.format("%,d", Math.abs(v));
    }

    // ------------------------------------------------------------------ 小部件

    /** 按钮的几种样式。 */
    public enum Btn { NORMAL, PRIMARY, DANGER, GHOST, DISABLED }

    /** 画按钮（hover 0..1），返回按钮宽度。 */
    public static void button(Canvas c, String label, Btn kind, float x, float y, float w, float h, float hover) {
        switch (kind) {
            case PRIMARY -> {
                Draw.gradientH(c, x, y, w, h, R, Draw.lerp(IOStyle.BLUE, IOStyle.BLUE_HOVER, hover), IOStyle.ACCENT_2);
                bold(c, label, FS_S, 0xFFFFFFFF, x, y + h / 2, w, Text.Align.CENTER);
            }
            case DANGER -> {
                Draw.fill(c, x, y, w, h, R, Draw.lerp(IOStyle.GRAY_BTN, IOStyle.RED_TINT, hover));
                Draw.stroke(c, x, y, w, h, R, 0.75F, Draw.alpha(IOStyle.RED, 0.55F + 0.45F * hover));
                bold(c, label, FS_S, IOStyle.RED, x, y + h / 2, w, Text.Align.CENTER);
            }
            case GHOST -> {
                if (hover > 0.01F) {
                    Draw.fill(c, x, y, w, h, R, Draw.alpha(IOStyle.ROW_HOVER, hover));
                }
                text(c, label, FS_S, Draw.lerp(IOStyle.TEXT_2, IOStyle.TEXT, hover), x, y + h / 2, w, Text.Align.CENTER);
            }
            case DISABLED -> {
                Draw.stroke(c, x, y, w, h, R, 0.5F, IOStyle.BORDER_DIM);
                text(c, label, FS_S, IOStyle.TEXT_3, x, y + h / 2, w, Text.Align.CENTER);
            }
            default -> {
                Draw.fill(c, x, y, w, h, R, Draw.lerp(IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER, hover));
                Draw.stroke(c, x, y, w, h, R, 0.5F, Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, hover));
                bold(c, label, FS_S, IOStyle.TEXT, x, y + h / 2, w, Text.Align.CENTER);
            }
        }
    }

    /** 按文字算按钮宽度。 */
    public static float buttonWidth(String label) {
        return width(label, FS_S, true) + 14.0F;
    }

    /** 彩色小标签（淡底 + 同色字），返回占用宽度（含右侧 3 单位间距）。 */
    public static float tag(Canvas c, String s, int color, float x, float cy) {
        float w = width(s, FS_XS, true) + 6;
        Draw.fill(c, x, cy - 4.5F, w, 9, 2, Draw.alpha(color, 0.18F));
        bold(c, s, FS_XS, color, x, cy, w, Text.Align.CENTER);
        return w + 3;
    }

    public static float tagWidth(String s) {
        return width(s, FS_XS, true) + 9;
    }

    /** 红色数字角标（圆形 / 胶囊），中心在 (cx, cy)。 */
    public static void badge(Canvas c, int count, float cx, float cy) {
        if (count <= 0) {
            return;
        }
        String s = count > 99 ? "99+" : String.valueOf(count);
        float w = Math.max(9, width(s, 5.0F, true) + 5);
        Draw.fill(c, cx - w / 2, cy - 4.5F, w, 9, 4.5F, IOStyle.RED);
        bold(c, s, 5.0F, 0xFFFFFFFF, cx - w / 2, cy, w, Text.Align.CENTER);
    }

    /** 分段选择器。返回每段的 [x, w]（命中判定用）。 */
    public static float[][] segmented(Canvas c, String[] labels, int selected, float x, float y, float[] hovers) {
        float[][] out = new float[labels.length][2];
        float total = 4;
        float[] ws = new float[labels.length];
        for (int i = 0; i < labels.length; i++) {
            ws[i] = width(labels[i], FS_S, i == selected) + 14;
            total += ws[i];
        }
        Draw.fill(c, x, y, total, CTRL, R, IOStyle.CARD);
        Draw.stroke(c, x, y, total, CTRL, R, 0.5F, IOStyle.BORDER_DIM);
        float ox = x + 2;
        for (int i = 0; i < labels.length; i++) {
            float hv = hovers == null || i >= hovers.length ? 0 : hovers[i];
            if (i == selected) {
                Draw.fill(c, ox, y + 2, ws[i], CTRL - 4, 2.5F, IOStyle.ROW_HOVER);
            } else if (hv > 0.01F) {
                Draw.fill(c, ox, y + 2, ws[i], CTRL - 4, 2.5F, Draw.alpha(IOStyle.ROW_HOVER, hv * 0.6F));
            }
            if (i == selected) {
                bold(c, labels[i], FS_S, IOStyle.TEXT, ox, y + CTRL / 2, ws[i], Text.Align.CENTER);
            } else {
                text(c, labels[i], FS_S, IOStyle.TEXT_2, ox, y + CTRL / 2, ws[i], Text.Align.CENTER);
            }
            out[i][0] = ox;
            out[i][1] = ws[i];
            ox += ws[i];
        }
        return out;
    }

    /** 勾选框 + 文字（行高 13）。 */
    public static void check(Canvas c, String label, boolean on, float x, float cy, float w, String count) {
        if (on) {
            Draw.fill(c, x, cy - 3.5F, 7, 7, 1.5F, IOStyle.BLUE);
            Draw.line(c, x + 1.6F, cy, x + 3.0F, cy + 1.6F, 1.0F, 0xFFFFFFFF);
            Draw.line(c, x + 3.0F, cy + 1.6F, x + 5.6F, cy - 1.8F, 1.0F, 0xFFFFFFFF);
        } else {
            Draw.stroke(c, x, cy - 3.5F, 7, 7, 1.5F, 0.6F, IOStyle.BORDER);
        }
        text(c, label, FS_S + 0.25F, on ? IOStyle.TEXT : IOStyle.TEXT_2, x + 11, cy, w - 11, Text.Align.LEFT);
        if (count != null) {
            text(c, count, FS_XS, IOStyle.TEXT_3, x, cy, w, Text.Align.RIGHT);
        }
    }

    /** 左侧筛选栏的一行（选中时淡强调底）。 */
    public static void filterItem(Canvas c, String label, String count, boolean on, float hover, float x, float y, float w) {
        if (on) {
            Draw.fill(c, x, y, w, 13, 3, Draw.alpha(IOStyle.BLUE, 0.18F));
        } else if (hover > 0.01F) {
            Draw.fill(c, x, y, w, 13, 3, Draw.alpha(IOStyle.ROW_HOVER, hover));
        }
        if (on) {
            bold(c, label, FS_S + 0.25F, IOStyle.TEXT, x + 6, y + 6.5F, w - 30, Text.Align.LEFT);
        } else {
            text(c, label, FS_S + 0.25F, IOStyle.TEXT_2, x + 6, y + 6.5F, w - 30, Text.Align.LEFT);
        }
        if (count != null) {
            text(c, count, FS_XS, IOStyle.TEXT_3, x + 6, y + 6.5F, w - 12, Text.Align.RIGHT);
        }
    }

    /** 小节标题（筛选栏里的「模组分类」等）。 */
    public static void section(Canvas c, String label, float x, float cy, float w) {
        text(c, label, FS_XS, IOStyle.TEXT_3, x, cy, w, Text.Align.LEFT);
    }

    /** 输入框外观（真正的输入用 SkiaTextField；这里只给静态展示用）。 */
    public static void fieldBox(Canvas c, float x, float y, float w, float h, boolean focused) {
        Draw.fill(c, x, y, w, h, R, IOStyle.FIELD_BG);
        Draw.stroke(c, x, y, w, h, R, focused ? 0.9F : 0.5F, focused ? IOStyle.BLUE : IOStyle.BORDER_DIM);
    }

    /** 细进度条（右对齐时 x 为左边）。 */
    public static void bar(Canvas c, float x, float cy, float w, float pct, int color) {
        Draw.fill(c, x, cy - 1, w, 2, 1, IOStyle.BORDER_DIM);
        if (pct > 0) {
            Draw.fill(c, x, cy - 1, w * Math.min(1, pct), 2, 1, color);
        }
    }

    /** 余量配色：低于 20% 红、低于 50% 金，其余绿。 */
    public static int levelColor(float pct) {
        return pct < 0.2F ? IOStyle.RED : pct < 0.5F ? IOStyle.ORANGE : IOStyle.GREEN;
    }

    // ------------------------------------------------------------------ 表格

    /** 表头：labels 与 cols（每列 [x, w]）一一对应；align 为每列对齐；sortCol 高亮（-1 = 无）。 */
    public static void tableHead(Canvas c, float x, float y, float w, float[][] cols, String[] labels,
                                 Text.Align[] align, int sortCol, boolean sortDesc) {
        Draw.fill(c, x, y, w, HEAD, 0, IOStyle.SIDEBAR);
        Draw.hairline(c, x, y + HEAD - 0.5F, w, IOStyle.BORDER_DIM);
        for (int i = 0; i < labels.length && i < cols.length; i++) {
            if (labels[i] == null || labels[i].isEmpty()) {
                continue;
            }
            boolean s = i == sortCol;
            String label = s ? labels[i] + (sortDesc ? " ▼" : " ▲") : labels[i];
            if (s) {
                bold(c, label, FS_XS + 0.25F, IOStyle.TEXT, cols[i][0], y + HEAD / 2, cols[i][1], align[i]);
            } else {
                text(c, label, FS_XS + 0.25F, IOStyle.TEXT_3, cols[i][0], y + HEAD / 2, cols[i][1], align[i]);
            }
        }
    }

    /** 行底：斑马纹 / 悬停 / 选中（左侧 1.5 单位强调条）。 */
    public static void rowBg(Canvas c, float x, float y, float w, float h, int index, float hover, boolean selected) {
        if (selected) {
            Draw.fill(c, x, y, w, h, 0, Draw.alpha(IOStyle.BLUE, 0.16F));
            Draw.fill(c, x, y, 1.5F, h, 0, IOStyle.BLUE);
        } else if (hover > 0.01F) {
            Draw.fill(c, x, y, w, h, 0, Draw.alpha(IOStyle.ROW_HOVER, hover));
            Draw.fill(c, x, y, 1.5F, h, 0, Draw.alpha(IOStyle.BLUE, hover));
        } else if (index % 2 == 1) {
            Draw.fill(c, x, y, w, h, 0, Draw.alpha(IOStyle.CARD, 0.55F));
        }
        Draw.hairline(c, x, y + h - 0.5F, w, Draw.alpha(IOStyle.BORDER_DIM, 0.55F));
    }

    /**
     * 按列宽规格排列列：spec 里 0 表示弹性列（平分剩余宽度），其余为固定宽。返回每列 [x, w]。
     */
    public static float[][] columns(float x, float w, float pad, float gap, float... spec) {
        float fixed = 0;
        int flex = 0;
        for (float f : spec) {
            if (f <= 0) {
                flex++;
            } else {
                fixed += f;
            }
        }
        float rest = w - pad * 2 - fixed - gap * (spec.length - 1);
        // 窗口窄时：固定列按比例收窄（最多到 60%），保证弹性列（名称）至少 90 单位宽
        float shrink = 1.0F;
        float minFlex = 90.0F * flex;
        if (flex > 0 && rest < minFlex && fixed > 0) {
            shrink = Math.max(0.6F, (w - pad * 2 - gap * (spec.length - 1) - minFlex) / fixed);
            rest = w - pad * 2 - fixed * shrink - gap * (spec.length - 1);
        }
        float flexW = flex == 0 ? 0 : Math.max(30, rest / flex);
        float[][] out = new float[spec.length][2];
        float ox = x + pad;
        for (int i = 0; i < spec.length; i++) {
            float cw = spec[i] <= 0 ? flexW : spec[i] * shrink;
            out[i][0] = ox;
            out[i][1] = cw;
            ox += cw + gap;
        }
        return out;
    }

    /** 空列表提示。 */
    public static void empty(Canvas c, String s, float x, float y, float w, float h) {
        text(c, s, FS, IOStyle.TEXT_3, x, y + h / 2, w, Text.Align.CENTER);
    }

    /** 底栏（左说明 + 右提示）。 */
    public static void foot(Canvas c, float x, float y, float w, String left, String right) {
        Draw.fill(c, x, y, w, FOOT, 0, IOStyle.SIDEBAR);
        Draw.hairline(c, x, y, w, IOStyle.BORDER_DIM);
        if (left != null) {
            text(c, left, FS_S, IOStyle.TEXT_2, x + 8, y + FOOT / 2, w * 0.5F, Text.Align.LEFT);
        }
        if (right != null) {
            float lw = left == null ? 0 : Math.min(w * 0.5F, width(left, FS_S, false)) + 16;
            text(c, right, FS_XS, IOStyle.TEXT_3, x + lw, y + FOOT / 2, w - lw - 8, Text.Align.RIGHT);
        }
    }

    /** 竖向滚动条（总行数 / 可见行数 / 滚动位置，单位行）。 */
    public static void scrollbar(Canvas c, float x, float y, float h, float total, float visible, float pos) {
        if (total <= visible) {
            return;
        }
        float th = Math.max(12, h * visible / total);
        float ty = y + (h - th) * Math.max(0, Math.min(1, pos / Math.max(1, total - visible)));
        Draw.fill(c, x, ty, 2, th, 1, IOStyle.BORDER);
    }

    // ------------------------------------------------------------------ 图标（线条绘制，不依赖字体字形）

    public enum Icon { CLOSE, BELL, BILL, SKIN, MAIL, GEAR, SEARCH, LAYOUT, VIEW_LIST, VIEW_GRID }

    public static void icon(Canvas c, Icon icon, float cx, float cy, int color) {
        switch (icon) {
            case VIEW_LIST -> {
                for (int i = -1; i <= 1; i++) {
                    float ry = cy + i * 2.4F;
                    Draw.fill(c, cx - 3.4F, ry - 0.55F, 1.1F, 1.1F, 0.4F, color);
                    Draw.line(c, cx - 1.4F, ry, cx + 3.4F, ry, 0.9F, color);
                }
            }
            case VIEW_GRID -> {
                float s = 2.8F;
                float g = 0.8F;
                Draw.fill(c, cx - g / 2 - s, cy - g / 2 - s, s, s, 0.6F, color);
                Draw.fill(c, cx + g / 2, cy - g / 2 - s, s, s, 0.6F, color);
                Draw.fill(c, cx - g / 2 - s, cy + g / 2, s, s, 0.6F, color);
                Draw.fill(c, cx + g / 2, cy + g / 2, s, s, 0.6F, color);
            }
            case CLOSE -> Draw.cross(c, cx, cy, 2.6F, 1.0F, color);
            case BELL -> {
                Draw.stroke(c, cx - 2.6F, cy - 3.2F, 5.2F, 5.4F, 2.4F, 0.9F, color);
                Draw.line(c, cx - 3.6F, cy + 2.2F, cx + 3.6F, cy + 2.2F, 0.9F, color);
                Draw.fill(c, cx - 0.8F, cy + 3.0F, 1.6F, 1.2F, 0.6F, color);
            }
            case BILL -> {
                Draw.stroke(c, cx - 2.8F, cy - 3.6F, 5.6F, 7.2F, 0.8F, 0.9F, color);
                Draw.line(c, cx - 1.4F, cy - 1.2F, cx + 1.4F, cy - 1.2F, 0.8F, color);
                Draw.line(c, cx - 1.4F, cy + 0.8F, cx + 1.4F, cy + 0.8F, 0.8F, color);
            }
            case SKIN -> {
                Draw.stroke(c, cx - 3.4F, cy - 3.4F, 6.8F, 6.8F, 3.4F, 0.9F, color);
                c.save();
                Draw.clip(c, cx - 3.4F, cy - 3.4F, 3.4F, 6.8F);
                Draw.fill(c, cx - 3.4F, cy - 3.4F, 6.8F, 6.8F, 3.4F, color);
                c.restore();
            }
            case MAIL -> {
                Draw.stroke(c, cx - 3.6F, cy - 2.6F, 7.2F, 5.2F, 0.8F, 0.9F, color);
                Draw.line(c, cx - 3.0F, cy - 1.8F, cx, cy + 0.4F, 0.8F, color);
                Draw.line(c, cx, cy + 0.4F, cx + 3.0F, cy - 1.8F, 0.8F, color);
            }
            case GEAR -> {
                Draw.stroke(c, cx - 2.0F, cy - 2.0F, 4.0F, 4.0F, 2.0F, 0.9F, color);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float x1 = cx + (float) Math.cos(a) * 2.8F;
                    float y1 = cy + (float) Math.sin(a) * 2.8F;
                    float x2 = cx + (float) Math.cos(a) * 3.8F;
                    float y2 = cy + (float) Math.sin(a) * 3.8F;
                    Draw.line(c, x1, y1, x2, y2, 0.9F, color);
                }
            }
            case SEARCH -> {
                Draw.stroke(c, cx - 3.0F, cy - 3.0F, 4.8F, 4.8F, 2.4F, 0.9F, color);
                Draw.line(c, cx + 1.2F, cy + 1.2F, cx + 3.2F, cy + 3.2F, 0.9F, color);
            }
            case LAYOUT -> {
                Draw.stroke(c, cx - 3.4F, cy - 3.0F, 6.8F, 6.0F, 0.8F, 0.9F, color);
                Draw.line(c, cx - 1.0F, cy - 3.0F, cx - 1.0F, cy + 3.0F, 0.8F, color);
            }
            default -> {
            }
        }
    }

    /** 15×15 的图标按钮（hover 0..1）。 */
    public static void iconButton(Canvas c, Icon icon, float x, float y, float hover, boolean active) {
        Draw.fill(c, x, y, CTRL, CTRL, R, active ? Draw.alpha(IOStyle.BLUE, 0.2F)
                : Draw.lerp(IOStyle.CARD, IOStyle.GRAY_BTN_HOVER, hover));
        Draw.stroke(c, x, y, CTRL, CTRL, R, 0.5F, Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, hover));
        int color = icon == Icon.CLOSE ? Draw.lerp(IOStyle.TEXT_2, IOStyle.RED, hover)
                : Draw.lerp(IOStyle.TEXT_2, IOStyle.TEXT, Math.max(hover, active ? 1 : 0));
        icon(c, icon, x + CTRL / 2, y + CTRL / 2, color);
    }
}
