package cn.autoforged.module_shop.event;


import net.minecraftforge.fml.common.Mod;
import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.command.ShopCommands;
import cn.autoforged.module_shop.shop.NotificationManager;
import cn.autoforged.module_shop.shop.ShopManager;
import cn.autoforged.module_shop.shop.TradeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;

@Mod.EventBusSubscriber(modid = ModuleShopMod.MODID)
public final class ShopServerEvents {
    private static int tickCounter;

    private ShopServerEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        cn.autoforged.module_shop.shop.http.ShopHttpApi.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        // 签到活动日：给「补发给离线玩家」的活动日补发（每个现实日期只执行一次）。
        ShopManager.tickActivityDayOffline(server);
        tickCounter++;
        ShopManager.tickAuctions(server);
        // 出货箱：每天早上 6:00 起的窗口内结算一次（内部有"当天已结算"的短路）。
        ShopManager.tickShipping(server);
        // 购物城：到「补货设置」里的时刻就把卖掉的库存补回目标数量（没开启时立刻返回）。
        ShopManager.tickMallRestock(server);
        TradeManager.tick(server);
        NotificationManager.tick(server);
        if (tickCounter % 20 == 0) {
            ShopManager.tickDaily(server);
            // 摊位：到「下架时间」就把到期商品撤下（永久 / 时长 0 时立刻返回）。
            ShopManager.tickStallExpire(server);
            // 求购：到「下架时间」就撤下并退还托管的悬赏。
            ShopManager.tickDemandExpire(server);
            // 购物城：按「库存设置」的周期整店刷新库存（off 时立刻返回）。
            ShopManager.tickMallRefresh(server);
        }
        // 放在最后：把本 tick 里所有 broadcastSnapshots 请求合并成一次下发。
        ShopManager.flushBroadcastSnapshots(server);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ShopManager.onLogin(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ShopManager.onLogout(player);
            cn.autoforged.module_shop.network.ModNetwork.forgetPlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ShopCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // 可选 HTTP API：关服时停掉监听
        cn.autoforged.module_shop.shop.http.ShopHttpApi.stop();
        TradeManager.shutdown(event.getServer());
    }
}
