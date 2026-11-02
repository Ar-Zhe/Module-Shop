package cn.autoforged.module_shop.client.screen;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * 在纯 Screen 中绘制玩家头像的公共工具（摊位 / 名片显示对应玩家头像）。
 *
 * <p>1.20.1 里皮肤仍然是一个 {@link ResourceLocation} 纹理（没有 1.20.2 之后才引入的
 * {@code PlayerSkin} 记录类型），所以这里直接拿纹理路径交给 {@link PlayerFaceRenderer}。
 */
public final class Skins {
    private Skins() {
    }

    public static void drawHead(GuiGraphics g, @Nullable UUID uuid, int x, int y, int size) {
        PlayerFaceRenderer.draw(g, textureOf(uuid), x, y, size);
    }

    /** 取某个玩家（或在线的自己）的皮肤纹理；拿不到就退回默认皮肤。 */
    private static ResourceLocation textureOf(@Nullable UUID uuid) {
        if (uuid != null) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            PlayerInfo info = connection == null ? null : connection.getPlayerInfo(uuid);
            if (info != null) {
                return info.getSkinLocation();
            }
            return DefaultPlayerSkin.getDefaultSkin(uuid);
        }
        var player = Minecraft.getInstance().player;
        return player != null ? player.getSkinTextureLocation()
                : DefaultPlayerSkin.getDefaultSkin(new UUID(0L, 0L));
    }
}