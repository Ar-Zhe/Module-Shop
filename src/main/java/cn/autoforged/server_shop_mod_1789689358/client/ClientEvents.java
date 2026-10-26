package cn.autoforged.server_shop_mod_1789689358.client;

import cn.autoforged.server_shop_mod_1789689358.ServerShopMod;
import cn.autoforged.server_shop_mod_1789689358.client.screen.BatchAddScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.DepositScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.GrantScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.RedeemRewardEditorScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.ScheduleRewardItemsScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.ShippingBinScreen;
import cn.autoforged.server_shop_mod_1789689358.menu.ModMenus;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.util.Lazy;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = ServerShopMod.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    public static final String CATEGORY = "key.categories." + ServerShopMod.MODID;
    public static final Lazy<KeyMapping> OPEN_SHOP = Lazy.of(() -> new KeyMapping(
            "key." + ServerShopMod.MODID + ".open_shop",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY));

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.DEPOSIT.get(), DepositScreen::new);
        event.register(ModMenus.GRANT.get(), GrantScreen::new);
        event.register(ModMenus.SCHEDULE_REWARD.get(), ScheduleRewardItemsScreen::new);
        event.register(ModMenus.REDEEM_REWARD.get(), RedeemRewardEditorScreen::new);
        // 出货箱（新增模块）：每个玩家自己的容器界面
        event.register(ModMenus.SHIPPING.get(), ShippingBinScreen::new);
        // 管理页「添加条目」的批量界面（27 个幽灵槽）
        event.register(ModMenus.BATCH_ADD.get(), BatchAddScreen::new);
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SHOP.get());
    }

    /**
     * JEI 联动：鼠标悬停在商店界面里的物品图标上时，按 JEI 的「查看配方 / 查看用途」键
     * （默认 R / U，认玩家自己的按键绑定）直接打开 JEI 对应的界面。
     * <p>
     * 没装 JEI 时 {@link cn.autoforged.server_shop_mod_1789689358.client.screen.JeiBridge} 里没有回调，
     * 这里直接返回 false，按键照常往下传，不会影响任何原有操作。
     */
    @SubscribeEvent
    public static void onScreenKeyPressed(
            net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (cn.autoforged.server_shop_mod_1789689358.client.screen.JeiBridge
                .handleKey(event.getScreen(), event.getKeyCode())) {
            event.setCanceled(true);
        }
    }

    /** 退出世界时清空界面层级与「已关闭界面」记录（返回栈是纯客户端状态，不能跨存档残留）。 */
    @SubscribeEvent
    public static void onClientLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        cn.autoforged.server_shop_mod_1789689358.client.screen.Nav.clear();
    }
}
