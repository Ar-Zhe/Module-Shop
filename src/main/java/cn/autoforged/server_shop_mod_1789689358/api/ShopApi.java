package cn.autoforged.server_shop_mod_1789689358.api;

import cn.autoforged.server_shop_mod_1789689358.api.view.ShopItemView;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 商店**只读**查询 API：商品、价格、库存。
 *
 * <p>⚠ 本批**不开放执行类操作**（购买/出售/兑换）：现有实现依赖客户端界面下标与动作包，
 * 直接暴露会让外部调用绕过界面校验与价格重算。执行类接口会在统一重构后再开放。
 *
 * <p>线程：任意线程可读（内部只读快照），但强烈建议在主线程调用。
 */
public interface ShopApi {
    /** 某玩家**今天的**每日商店商品（含个人限购剩余量）。 */
    List<ShopItemView> dailyShop(UUID playerId);

    /** 服务器常驻购物城商品（全服共享库存）。 */
    List<ShopItemView> mall();

    /** 某玩家**今天的**每日收购项（含个人剩余收购量）。 */
    List<ShopItemView> buyback(UUID playerId);

    // ---------------------------------------------------------------- 执行类（第四批开放）

    /**
     * 购买每日商店第 {@code index} 项。
     *
     * @param expectedPrice 你查询到的单价；服务端会复核，**不一致直接返回 {@link ApiErrorCode#PRICE_CHANGED}**
     *                      （传 {@code -1} 表示不校验，不建议）
     * @return 成功 {@code ok()}；失败原因见 {@link ApiErrorCode}（玩家必须在线）
     */
    ApiResult<Void> buyDaily(UUID playerId, int index, int count, long expectedPrice);

    /** 购买购物城第 {@code index} 项（语义同 {@link #buyDaily}）。 */
    ApiResult<Void> buyMall(UUID playerId, int index, int count, long expectedPrice);

    /** 把背包里的东西卖给每日收购第 {@code index} 项（玩家必须在线）。 */
    ApiResult<Void> sellBuyback(UUID playerId, int index, int count);

    // ---------------------------------------------------------------- 拍卖 / 求购（第五批开放）

    /** 拍卖列表快照（只读）。 */
    List<cn.autoforged.server_shop_mod_1789689358.api.view.AuctionView> auctions();

    /**
     * 对拍卖 {@code auctionId} 出价（玩家必须在线）。
     *
     * @param amount 出价金额；必须高于当前价与起拍价（服务端复核）
     */
    ApiResult<Void> bid(UUID playerId, long auctionId, long amount);

    /**
     * 发起求购（悬赏）：会**托管**金币并把副手物品作为悬赏物一起收走。
     *
     * @param wanted 想要的物品（数量在 {@code stack.getCount()}，最多 12 条，每条 1~64）
     * @param rewardMoney 悬赏金币（含手续费，0 表示只给物品）
     */
    ApiResult<String> createDemand(UUID playerId, List<ItemStack> wanted, long rewardMoney);

    /** 撤销自己发布的求购（托管物与悬赏会退回）。 */
    ApiResult<Void> cancelDemand(UUID playerId, String demandId);

    /**
     * 摊位改价/上架：把 {@code playerId} 背包里**指定槽位**的物品挂到摊位第 {@code stallIndex} 个位置。
     *
     * @param slot 背包槽位（0~40，含快捷栏/主背包）
     * @param price 单价
     * @param count 数量（1~64）
     */
    ApiResult<Void> stallList(UUID playerId, int stallIndex, int slot, int count, long price);
}