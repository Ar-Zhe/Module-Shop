package cn.autoforged.module_shop.client.ui;

import cn.autoforged.module_shop.client.screen.IOStyle;
import com.atom.lib.design.atomchat.DesignHost;

/**
 * 把 Module-Shop 现有调色板（{@link IOStyle}，OneConfig 深色风格、唯一强调色 #2B4BFF）
 * 接到 AtomLib 设计包上：设计包里的卡片、悬停色等派生色都会跟随本模组的配色。
 *
 * <p>颜色仍以 {@link IOStyle} 为唯一来源，这里只做转发，不复制常量。
 */
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
