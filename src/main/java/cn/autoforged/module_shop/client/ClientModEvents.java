package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.client.screen.BatchAddScreen;
import cn.autoforged.module_shop.client.screen.DepositScreen;
import cn.autoforged.module_shop.client.screen.GrantScreen;
import cn.autoforged.module_shop.client.screen.RedeemRewardEditorScreen;
import cn.autoforged.module_shop.client.screen.ScheduleRewardItemsScreen;
import cn.autoforged.module_shop.client.screen.ShippingBinScreen;
import cn.autoforged.module_shop.menu.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端 **MOD 总线**事件（只在客户端加载）。
 *
 * <p>Forge 1.20.1 的 {@code @Mod.EventBusSubscriber} 默认订阅游戏总线（FORGE），
 * 而 {@link RegisterKeyMappingsEvent}、{@link FMLClientSetupEvent} 和
 * {@link RegisterClientReloadListenersEvent} 都是 MOD 总线事件，必须拆分到此文件。
 */
@Mod.EventBusSubscriber(modid = ModuleShopMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    /** 注册「打开商店」按键。 */
    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        ClientEvents.OPEN_SHOP = ClientEvents.createOpenShopKey();
        event.register(ClientEvents.OPEN_SHOP);
    }

    /** 注册容器界面（1.20.1 在客户端初始化里通过 MenuScreens.register 注册）。 */
    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.DEPOSIT.get(), DepositScreen::new);
            MenuScreens.register(ModMenus.GRANT.get(), GrantScreen::new);
            MenuScreens.register(ModMenus.SCHEDULE_REWARD.get(), ScheduleRewardItemsScreen::new);
            MenuScreens.register(ModMenus.REDEEM_REWARD.get(), RedeemRewardEditorScreen::new);
            // 出货箱（新增模块）：每个玩家自己的容器界面
            MenuScreens.register(ModMenus.SHIPPING.get(), ShippingBinScreen::new);
            // 管理页「添加条目」的批量界面（27 个幽灵槽）
            MenuScreens.register(ModMenus.BATCH_ADD.get(), BatchAddScreen::new);
        });
    }

    /** 注册模组中文名包资源重载监听器（切语言 / F3+T 资源包重载时自动刷新）。 */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) ModNames::reload);
    }
}
