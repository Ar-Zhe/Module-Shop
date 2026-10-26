package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * 界面素材统一入口。
 * <p>
 * 面板与物品槽现在统一走 {@link IOStyle}（原版 iOS 风格圆角 + 日间/暗色双主题），
 * 这里保留积木纹理常量给仍在使用它们的界面（如发放界面的玩家背包格）。
 */
public final class Bricks {
    public static final ResourceLocation SLOT = tex("slot_default");
    public static final ResourceLocation PLAYER_SLOTS = tex("player_slots_9x4");
    public static final ResourceLocation FILL = tex("fill_white");
    public static final ResourceLocation SPLIT = tex("split_bar");

    private Bricks() {
    }

    public static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("autoforge_bricks", "textures/gui/" + name + ".png");
    }

    /** 窗口背景：原版 iOS 风格圆角面板（带阴影与描边），配色随日间/暗色主题。 */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        IOStyle.panel(g, x, y, w, h);
    }

    /** 物品槽：圆角内凹槽位。 */
    public static void slot(GuiGraphics g, int x, int y) {
        IOStyle.slot(g, x, y);
    }

    /** 界面点击音效：具体播放哪一款由客户端设置决定（默认模组自带的 sounds/fy.ogg）。 */
    public static void click() {
        cn.autoforged.module_shop.client.ClickSound.play();
    }
}
