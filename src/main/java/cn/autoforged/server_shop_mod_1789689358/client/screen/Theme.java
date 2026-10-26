package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.client.UITheme;

/**
 * 界面取色的统一入口（薄封装）。
 * <p>
 * 配色来自原版 {@link IOStyle}（iOS 风格日间 / 暗色两套），主题开关由 {@link UITheme} 持有并持久化。
 * 保留这层封装是为了让商店主界面等处的取色写法保持简洁；新代码建议直接用 {@link IOStyle} 的常量。
 */
public final class Theme {
    private Theme() {
    }

    /** 当前是否暗色主题。 */
    public static boolean dark() {
        return UITheme.isDark();
    }

    public static void toggle() {
        UITheme.toggle();
    }

    // ---------------------------------------------------------- 文字

    public static int text() {
        return IOStyle.TEXT;
    }

    public static int muted() {
        return IOStyle.TEXT_2;
    }

    public static int gold() {
        return IOStyle.ORANGE;
    }

    public static int danger() {
        return IOStyle.RED;
    }

    public static int good() {
        return IOStyle.GREEN;
    }

    public static int info() {
        return IOStyle.BLUE;
    }

    // ---------------------------------------------------------- 面板 / 列表

    public static int panelFill() {
        return IOStyle.SHEET;
    }

    public static int card() {
        return IOStyle.CARD;
    }

    public static int row() {
        return IOStyle.CARD;
    }

    public static int rowOpen() {
        return IOStyle.BLUE_TINT;
    }

    public static int rowHover() {
        return IOStyle.ROW_HOVER;
    }

    public static int slot() {
        return IOStyle.SLOT_INNER;
    }

    public static int separator() {
        return IOStyle.HAIR;
    }

    public static int avatarFrame() {
        return IOStyle.CARD;
    }

    // ---------------------------------------------------------- 按钮 / 页签

    public static int buttonBg() {
        return IOStyle.GRAY_BTN;
    }

    public static int buttonTop() {
        return IOStyle.GRAY_BTN_HOVER;
    }

    public static int buttonSel() {
        return IOStyle.BLUE_TINT;
    }

    public static int accentBg() {
        return IOStyle.BLUE_TINT;
    }

    public static int accentTop() {
        return IOStyle.BLUE_TINT_HOVER;
    }

    public static int dangerBg() {
        return IOStyle.RED_TINT;
    }

    public static int dangerTop() {
        return IOStyle.RED_TINT_HOVER;
    }

    public static int tabBg(boolean selected) {
        return selected ? IOStyle.BLUE_TINT : IOStyle.TAB_BG;
    }

    public static int tabText() {
        return IOStyle.TEXT;
    }
}
