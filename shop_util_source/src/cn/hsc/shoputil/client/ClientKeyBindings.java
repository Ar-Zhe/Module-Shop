package cn.hsc.shoputil.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键：
 * - G：切换商店守卫解锁/锁定
 * - K：导出当前存档配置到参考文件
 * - P：打开定价文件选择界面
 */
public final class ClientKeyBindings {

    public static final KeyMapping TOGGLE_GUARD = new KeyMapping(
            "key.shop_util.toggle_guard", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
            "key.categories.shop_util");

    public static final KeyMapping EXPORT_CONFIG = new KeyMapping(
            "key.shop_util.export_config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
            "key.categories.shop_util");

    public static final KeyMapping OPEN_PRICING_GUI = new KeyMapping(
            "key.shop_util.pricing_gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P,
            "key.categories.shop_util");

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> onTick());
    }

    private static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        while (TOGGLE_GUARD.consumeClick()) {
            if (GuardState.isUnlocked()) {
                GuardState.unlocked = false;
                mc.player.displayClientMessage(Component.literal("§c[商店守卫] 已锁定管理员功能"), false);
                mc.setScreen(null);
            } else {
                mc.setScreen(new PasswordScreen(mc.screen));
            }
        }

        while (EXPORT_CONFIG.consumeClick()) {
            mc.player.connection.sendCommand("shoputil export");
        }

        while (OPEN_PRICING_GUI.consumeClick()) {
            mc.setScreen(new ConfigManageScreen(mc.screen));
        }
    }
}
