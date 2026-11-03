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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端 **MOD 总线**事件（只在客户端加载）。
 *
 * <p>为什么单独一个类：Forge 1.20.1 的 {@code @Mod.EventBusSubscriber} 默认订阅**游戏总线**
 * （FORGE），而 {@link RegisterKeyMappingsEvent} 和 {@link FMLClientSetupEvent} 都是 **MOD 总线**事件。
 * 混在一起会导致「按键根本没注册」（tick 里取到 null → 客户端崩）和
 * 「容器界面没注册」（打开发放/放入物品等界面时是空白）—— 1.1.0 第一版就踩了这个坑。
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

    /** 注册容器界面（1.20.1 没有 {@code RegisterMenuScreensEvent}，只能在客户端初始化里注册）。 */
    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.DEPOSIT.get(), DepositScreen::new);
            MenuScreens.register(ModMenus.GRANT.get(), GrantScreen::new);
            MenuScreens.register(ModMenus.SCHEDULE_REWARD.get(), ScheduleRewardItemsScreen::new);
            MenuScreens.register(ModMenus.REDEEM_REWARD.get(), RedeemRewardEditorScreen::new);
            MenuScreens.register(ModMenus.SHIPPING.get(), ShippingBinScreen::new);
            MenuScreens.register(ModMenus.BATCH_ADD.get(), BatchAddScreen::new);
        });
    }
}
