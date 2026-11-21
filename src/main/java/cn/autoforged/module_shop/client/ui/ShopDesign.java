package cn.autoforged.module_shop.client.ui;

import cn.autoforged.module_shop.client.screen.IOStyle;
import com.atom.lib.design.atomchat.DesignHost;

/**
 * 旧版 AtomLib {@code DesignHost} 转发实现。
 *
 * <p>已不再注入全局 {@code Design.host(...)}（{@code AtomUi} 不再调用它），
 * 以避免覆盖同进程 {@code AtomChat} 的磨砂玻璃卡片、圆角与主题配色。保留此类仅作向后兼容。
 */
@Deprecated
public final class ShopDesign implements DesignHost {
    public static final ShopDesign INSTANCE = new ShopDesign();

    private ShopDesign() {
    }

    @Override
    public float cornerRadius() {
        return IOStyle.PANEL_R;
    }

    @Override
    public boolean halfScreenMode() {
        return false;
    }

    @Override
    public int accentColor() {
        return IOStyle.BLUE;
    }

    @Override
    public int textSecondaryColor() {
        return IOStyle.TEXT_2;
    }

    @Override
    public float cardTint() {
        return 1.0F;
    }

    @Override
    public int cardColor() {
        return IOStyle.CARD;
    }

    @Override
    public int panelBackgroundColor() {
        return IOStyle.SHEET;
    }
}
