package cn.autoforged.module_shop.api.client;

import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import com.atom.lib.font.FontId;

/**
 * 商店的配色与字体（附属页面用它和内置模块保持一致）。
 *
 * <p>颜色是**方法**而不是常量：玩家切换日间 / 夜间主题后立即跟随，每帧取值即可。
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
}
