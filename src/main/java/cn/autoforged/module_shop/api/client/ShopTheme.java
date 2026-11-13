package cn.autoforged.module_shop.api.client;

import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import com.atom.lib.font.FontId;

/**
 * 商店的配色与字体（附属页面用它和内置模块保持一致）。
 *
 * <p>颜色是**方法**而不是常量：玩家切换皮肤（日间 / 夜间 / 樱糖 / 薄荷……）后立即跟随，每帧取值即可。
 * API 1.2 起另有渐变端点、柔光色与几个“和主体一样”的绘制方法（{@link #dialogPanel} 等）。
 */
public final class ShopTheme {
    private ShopTheme() {
    }

    /** AtomLib 是否可用（第一次调用会尝试初始化，需在渲染线程）。 */
    public static boolean atomLibAvailable() {
        return AtomUi.available();
    }

    public static FontId regular() {
        return AtomUi.REGULAR;
    }

    public static FontId bold() {
        return AtomUi.BOLD;
    }

    public static boolean dark() {
        return IOStyle.dark;
    }

    /** 主文字 / 次要文字 / 弱化文字。 */
    public static int text() {
        return IOStyle.TEXT;
    }

    public static int text2() {
        return IOStyle.TEXT_2;
    }

    public static int text3() {
        return IOStyle.TEXT_3;
    }

    /** 内容区底色 / 卡片底色 / 行悬停色。 */
    public static int content() {
        return IOStyle.CONTENT;
    }

    public static int card() {
        return IOStyle.CARD;
    }

    public static int rowHover() {
        return IOStyle.ROW_HOVER;
    }

    public static int border() {
        return IOStyle.BORDER;
    }

    public static int borderDim() {
        return IOStyle.BORDER_DIM;
    }

    /** 强调色（蓝）及其淡底。 */
    public static int accent() {
        return IOStyle.BLUE;
    }

    public static int accentTint() {
        return IOStyle.BLUE_TINT;
    }

    public static int gold() {
        return IOStyle.ORANGE;
    }

    public static int green() {
        return IOStyle.GREEN;
    }

    public static int red() {
        return IOStyle.RED;
    }

    public static int redTint() {
        return IOStyle.RED_TINT;
    }

    // ------------------------------------------------------------------ API 1.2：皮肤

    /**
     * 当前皮肤 id：{@code light / dark / rose / sage / haze / oat / dusk}。以后可能增加新皮肤，
     * 遇到不认识的 id 时按 {@link #dark()} 区分深浅即可。
     *
     * @since API 1.2
     */
    public static String skin() {
        return IOStyle.currentSkin();
    }

    /**
     * 当前“明度”：0 = 深色底，1 = 浅色底；切换皮肤的过渡中为中间值。
     * 需要自己按深浅插值颜色时用它（比 {@link #dark()} 平滑）。
     *
     * @since API 1.2
     */
    public static float lightness() {
        return IOStyle.themeProgress();
    }

    /** 弹窗底色（上）与渐变终点（下）。@since API 1.2 */
    public static int sheet() {
        return IOStyle.SHEET;
    }

    /** @since API 1.2 */
    public static int sheet2() {
        return IOStyle.SHEET_2;
    }

    /** 侧栏底色（上）与渐变终点（下）。@since API 1.2 */
    public static int sidebar() {
        return IOStyle.SIDEBAR;
    }

    /** @since API 1.2 */
    public static int sidebar2() {
        return IOStyle.SIDEBAR_2;
    }

    /** 内容区渐变终点（左上 {@link #content()} → 右下 content2）。@since API 1.2 */
    public static int content2() {
        return IOStyle.CONTENT_2;
    }

    /** 强调色悬停色、强调色渐变终点（左 {@link #accent()} → 右 accent2）。@since API 1.2 */
    public static int accentHover() {
        return IOStyle.BLUE_HOVER;
    }

    /** @since API 1.2 */
    public static int accent2() {
        return IOStyle.ACCENT_2;
    }

    /** 普通（灰）按钮底色与悬停色。@since API 1.2 */
    public static int grayButton() {
        return IOStyle.GRAY_BTN;
    }

    /** @since API 1.2 */
    public static int grayButtonHover() {
        return IOStyle.GRAY_BTN_HOVER;
    }

    /** 弹窗 / 面板投影色（带透明度）。@since API 1.2 */
    public static int shadow() {
        return IOStyle.SHADOW_TINT;
    }

    /** 面板里的两团柔光（径向渐变中心色，带透明度；日间 / 夜间为全透明）。@since API 1.2 */
    public static int glow1() {
        return IOStyle.GLOW_1;
    }

    /** @since API 1.2 */
    public static int glow2() {
        return IOStyle.GLOW_2;
    }

    // ------------------------------------------------------------------ API 1.2：与主体一致的绘制

    /**
     * 画一块和商店弹窗完全一样的底板：外投影 + 半透明竖向渐变 + 右上角柔光 + 顶部高光 + 1px 边框。
     * 附属自己的弹窗（例如红包详情）用它当背景，就能和主体弹窗、当前皮肤保持一致。
     * 也会推进皮肤切换的过渡，弹窗里每帧调用即可。
     *
     * @since API 1.2
     */
    public static void dialogPanel(io.github.humbleui.skija.Canvas canvas, float x, float y, float w, float h) {
        IOStyle.tickTheme();
        cn.autoforged.module_shop.client.ui.render.Draw.glass(canvas, x, y, w, h, IOStyle.SHEET, IOStyle.SHEET_2,
                IOStyle.GLASS_DIALOG, IOStyle.BORDER, IOStyle.INNER_LIGHT, IOStyle.GLOW_1, IOStyle.SHADOW_TINT,
                10.0F, 3.0F);
    }

    /**
     * 强调色水平渐变填充（{@link #accent()} → {@link #accent2()}），主按钮、选中块用的就是它。
     * 日间 / 夜间两端相同，等于纯色。
     *
     * @since API 1.2
     */
    public static void accentFill(io.github.humbleui.skija.Canvas canvas, float x, float y, float w, float h,
                                  float radius) {
        cn.autoforged.module_shop.client.ui.render.Draw.gradientH(canvas, x, y, w, h, radius, IOStyle.BLUE,
                IOStyle.ACCENT_2);
    }

    /**
     * 内容区底色的对角渐变填充（{@link #content()} → {@link #content2()}），opacity 为整体不透明度（0..1）。
     *
     * @since API 1.2
     */
    public static void contentFill(io.github.humbleui.skija.Canvas canvas, float x, float y, float w, float h,
                                   float radius, float opacity) {
        cn.autoforged.module_shop.client.ui.render.Draw.gradientD(canvas, x, y, w, h, radius,
                cn.autoforged.module_shop.client.ui.render.Draw.alpha(IOStyle.CONTENT, opacity),
                cn.autoforged.module_shop.client.ui.render.Draw.alpha(IOStyle.CONTENT_2, opacity));
    }
}
