package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.client.screen.BatchAddScreen;
import cn.autoforged.module_shop.client.screen.DepositScreen;
import cn.autoforged.module_shop.client.screen.GrantScreen;
import cn.autoforged.module_shop.client.screen.RedeemRewardEditorScreen;
import cn.autoforged.module_shop.client.screen.ScheduleRewardItemsScreen;
import cn.autoforged.module_shop.client.screen.ShippingBinScreen;
import cn.autoforged.module_shop.menu.ModMenus;
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

@EventBusSubscriber(modid = ModuleShopMod.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    public static final String CATEGORY = "key.categories." + ModuleShopMod.MODID;
    public static final Lazy<KeyMapping> OPEN_SHOP = Lazy.of(() -> new KeyMapping(
            "key." + ModuleShopMod.MODID + ".open_shop",
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
     * 没装 JEI 时 {@link cn.autoforged.module_shop.client.screen.JeiBridge} 里没有回调，
     * 这里直接返回 false，按键照常往下传，不会影响任何原有操作。
     */
    @SubscribeEvent
    public static void onScreenKeyPressed(
            net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (cn.autoforged.module_shop.client.screen.JeiBridge
                .handleKey(event.getScreen(), event.getKeyCode())) {
            event.setCanceled(true);
        }
    }

    /**
     * 兜底：聊天里的默认货币名换成当前货币名。
     *
     * <p>正常情况下 {@link CurrencyNames} 已经把语言表里的「星币」换掉了，这里不会再触发。
     * 但语言表替换依赖运行时内部结构（语言表被资源重载换掉等情况下可能没生效），
     * 而聊天是玩家最能看见的地方，所以在消息到达时再做一次同样的替换 ——
     * 只有在「解析出来的整句里仍然含默认货币名」时才动手，不影响任何已经正确的消息。
     */
    @SubscribeEvent
    public static void onChatReceived(net.neoforged.neoforge.client.event.ClientChatReceivedEvent event) {
        String name = ClientShopData.currencyName();
        if (name == null || name.isEmpty() || ClientShopData.DEFAULT_CURRENCY.equals(name)) {
            return;
        }
        net.minecraft.network.chat.Component message = event.getMessage();
        // 只碰本模组的消息（其它模组 / 玩家聊天一概不动）。
        if (!(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable)
                || !translatable.getKey().startsWith("msg.module_shop.")) {
            return;
        }
        String plain = message.getString();
        if (!plain.contains(ClientShopData.DEFAULT_CURRENCY)) {
            return;
        }
        event.setMessage(net.minecraft.network.chat.Component
                .literal(plain.replace(ClientShopData.DEFAULT_CURRENCY, name))
                .withStyle(message.getStyle()));
    }

    /**
     * 每 tick 兜一次：语言表在切语言 / 资源重载时会被整个换掉（Neo 会重新 inject），
     * 换掉之后我们那层包装就没了。这里每帧检查一次，被换掉时立刻重新包上（代价只是两次类型判断）。
     */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        cn.autoforged.module_shop.client.CurrencyNames.apply(ClientShopData.moneySuffix);
    }

    /** 退出世界时清空界面层级与「已关闭界面」记录（返回栈是纯客户端状态，不能跨存档残留）。 */
    @SubscribeEvent
    public static void onClientLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        cn.autoforged.module_shop.client.screen.Nav.clear();
    }
}
