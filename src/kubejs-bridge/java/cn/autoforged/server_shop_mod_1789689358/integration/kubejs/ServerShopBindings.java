package cn.autoforged.server_shop_mod_1789689358.integration.kubejs;

import cn.autoforged.server_shop_mod_1789689358.api.ApiResult;
import cn.autoforged.server_shop_mod_1789689358.api.ServerShopApi;
import cn.autoforged.server_shop_mod_1789689358.api.view.BalanceView;
import cn.autoforged.server_shop_mod_1789689358.api.view.MailInfo;
import cn.autoforged.server_shop_mod_1789689358.api.view.ShopItemView;
import cn.autoforged.server_shop_mod_1789689358.api.view.TransactionInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 脚本绑定对象：KubeJS 里的 {@code ServerShop} 全局。
 *
 * <p>所有方法都是公共 API 的**薄包装**（不复制经济规则），返回值统一转成脚本友好的形式：
 * <ul>
 *     <li>查询类直接返回数字 / 布尔 / 数组；</li>
 *     <li>写操作返回 {@code true} 或失败原因字符串（{@code "FAIL:CODE 说明"}）。</li>
 * </ul>
 *
 * <p>脚本里可以用 {@code Item.of('minecraft:diamond', 3)} 或纯 id 字符串作为物品参数。
 */
public class ServerShopBindings {
    // ---------------------------------------------------------------- 查询

    /** 版本字符串，例如 {@code "1.0.0"}。 */
    public String version() {
        return cn.autoforged.server_shop_mod_1789689358.api.ApiVersion.API_VERSION;
    }

    /** 余额。 */
    public long balance(UUID playerId) {
        return ServerShopApi.economy().balance(playerId);
    }

    /** 余额上限（0 = 不限）。 */
    public long maxBalance() {
        return ServerShopApi.economy().maxBalance();
    }

    /** 余额字符串（带货币名），脚本里打日志方便。 */
    public String balanceText(UUID playerId) {
        BalanceView view = ServerShopApi.economy().balanceView(playerId);
        if (view == null) {
            return "0";
        }
        return view.balance() + " " + view.currencyName();
    }

    /** 未领取邮件数。 */
    public int unclaimedMails(UUID playerId) {
        return ServerShopApi.mail().unclaimed(playerId);
    }

    /** 流水条数（最多 limit 条）。 */
    public int historySize(UUID playerId, int limit) {
        return ServerShopApi.history().history(playerId, limit).size();
    }

    /** 最近一条流水的可读文本（没有则空串）。 */
    public String lastTransaction(UUID playerId) {
        List<TransactionInfo> list = ServerShopApi.history().history(playerId, 1);
        if (list.isEmpty()) {
            return "";
        }
        TransactionInfo t = list.get(0);
        return t.type() + " " + t.amount() + " -> " + t.balanceAfter() + " (" + t.note() + ")";
    }

    /** 今日商店商品数量。 */
    public int dailyShopSize(UUID playerId) {
        return ServerShopApi.shop().dailyShop(playerId).size();
    }

    /** 购物城商品数量。 */
    public int mallSize() {
        return ServerShopApi.shop().mall().size();
    }

    /** 签到状态一句话（脚本日志用）。 */
    public String signInText(UUID playerId) {
        var view = ServerShopApi.daily().signIn(playerId);
        if (view == null) {
            return "unknown";
        }
        return "signed=" + view.signedToday() + " streak=" + view.streak() + " best=" + view.bestStreak();
    }

    /** 邮件摘要列表（每行一封）。 */
    public List<String> mailboxLines(UUID playerId) {
        List<String> out = new ArrayList<>();
        for (MailInfo mail : ServerShopApi.mail().mailbox(playerId)) {
            out.add(mail.title() + " | money=" + mail.money() + " | items=" + mail.items().size()
                    + " | claimed=" + mail.claimed());
        }
        return out;
    }

    /** 商品摘要列表（每行一件）。 */
    public List<String> shopLines(boolean mall, UUID playerId) {
        List<String> out = new ArrayList<>();
        List<ShopItemView> items = mall ? ServerShopApi.shop().mall()
                : ServerShopApi.shop().dailyShop(playerId);
        for (ShopItemView item : items) {
            out.add(item.id() + " | " + item.stack().getHoverName().getString() + " | price=" + item.price()
                    + " | stock=" + item.stock());
        }
        return out;
    }

    /** 这个 UUID 是否有账户。 */
    public boolean known(UUID playerId) {
        return ServerShopApi.economy().balanceView(playerId) != null;
    }

    // ---------------------------------------------------------------- 写操作（返回 true 或 "FAIL:CODE msg"）

    /** 加钱（受上限；溢出转金钱邮件）。 */
    public Object add(UUID playerId, long amount, String reason) {
        return describe(ServerShopApi.economy().add(playerId, amount, reason));
    }

    /** 扣钱（余额不足整笔失败）。 */
    public Object take(UUID playerId, long amount, String reason) {
        return describe(ServerShopApi.economy().take(playerId, amount, reason));
    }

    /** 发一件物品（在线进背包/满进邮箱，离线进邮箱）。 */
    public Object give(UUID playerId, ItemStack stack) {
        return describe(ServerShopApi.items().give(playerId, stack));
    }

    /** 幂等发奖：同一个 requestId 对同一玩家只成功一次。 */
    public Object reward(UUID playerId, String requestId, long money, List<ItemStack> items, String reason) {
        return describe(ServerShopApi.reward().grantOnce(playerId, requestId, items, money, reason));
    }

    /** 发一封物品邮件。 */
    public Object mail(UUID playerId, String title, String body, List<ItemStack> items) {
        return describe(ServerShopApi.mail().sendItemMail(playerId, title, body, items));
    }

    /** 只记账（不动余额），用于把外部收支写进玩家账单。 */
    public Object record(UUID playerId, String type, String counterParty, long amount, String note) {
        return describe(ServerShopApi.history().record(playerId, type, counterParty, amount, note));
    }

    /** 购买每日商店第 index 项。 */
    public Object buyDaily(UUID playerId, int index, int count, long expectedPrice) {
        return describe(ServerShopApi.shop().buyDaily(playerId, index, count, expectedPrice));
    }

    /** 购买购物城第 index 项。 */
    public Object buyMall(UUID playerId, int index, int count, long expectedPrice) {
        return describe(ServerShopApi.shop().buyMall(playerId, index, count, expectedPrice));
    }

    /** 卖给每日收购第 index 项。 */
    public Object sellBuyback(UUID playerId, int index, int count) {
        return describe(ServerShopApi.shop().sellBuyback(playerId, index, count));
    }

    /** 统一把 ApiResult 转成脚本值：成功 true，失败 "FAIL:CODE 说明"。 */
    private static Object describe(ApiResult<?> result) {
        if (result == null) {
            return "FAIL:INTERNAL_ERROR null result";
        }
        return result.isOk() ? Boolean.TRUE : ("FAIL:" + result.code() + " " + result.message());
    }
}