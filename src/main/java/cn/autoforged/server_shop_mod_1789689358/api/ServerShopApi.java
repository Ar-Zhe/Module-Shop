package cn.autoforged.server_shop_mod_1789689358.api;

import cn.autoforged.server_shop_mod_1789689358.api.internal.ApiImpl;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 公共 API 门面。
 *
 * <pre>{@code
 * EconomyApi eco = ServerShopApi.economy();
 * long bal = eco.balance(player.getUUID());
 * ApiResult<Long> r = eco.add(player.getUUID(), 100, "每日任务");
 * }</pre>
 *
 * <p>版本：{@link ApiVersion#API_VERSION}；所有写操作要求在**服务端主线程**执行。
 */
public final class ServerShopApi {
    /** 事件总线：只发布"业务已成功之后"的通知型事件（不可取消）。 */
    private static final IEventBus BUS = BusBuilder.builder().build();

    private static final EconomyApi ECONOMY = new ApiImpl.Economy();
    private static final ItemDeliveryApi ITEMS = new ApiImpl.Items();
    private static final MailApi MAIL = new ApiImpl.Mail();
    private static final HistoryApi HISTORY = new ApiImpl.History();
    private static final DailyApi DAILY = new ApiImpl.Daily();
    private static final ShopApi SHOP = new ApiImpl.Shop();
    private static final RewardApi REWARD = new ApiImpl.Reward();

    private ServerShopApi() {
    }

    public static EconomyApi economy() {
        return ECONOMY;
    }

    public static ItemDeliveryApi items() {
        return ITEMS;
    }

    public static MailApi mail() {
        return MAIL;
    }

    public static HistoryApi history() {
        return HISTORY;
    }

    public static DailyApi daily() {
        return DAILY;
    }

    public static RewardApi reward() {
        return REWARD;
    }

    /** 商店只读查询（商品/价格/库存）。 */
    public static ShopApi shop() {
        return SHOP;
    }

    /** 对外事件总线（业务成功之后发布；监听器异常不影响已完成的事务）。 */
    public static IEventBus events() {
        return BUS;
    }

    /** 当前服务端实例（未启动时为 {@code null}）。 */
    @Nullable
    public static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    /** 是否在服务端主线程。 */
    public static boolean onServerThread(MinecraftServer server) {
        return server != null && server.isSameThread();
    }
}