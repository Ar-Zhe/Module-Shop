package cn.autoforged.module_shop.api.internal;

import cn.autoforged.module_shop.Config;
import net.minecraft.nbt.CompoundTag;
import cn.autoforged.module_shop.api.ApiErrorCode;
import cn.autoforged.module_shop.api.ApiResult;
import cn.autoforged.module_shop.api.DailyApi;
import cn.autoforged.module_shop.api.EconomyApi;
import cn.autoforged.module_shop.api.HistoryApi;
import cn.autoforged.module_shop.api.ItemDeliveryApi;
import cn.autoforged.module_shop.api.MailApi;
import cn.autoforged.module_shop.api.RewardApi;
import cn.autoforged.module_shop.api.ShopApi;
import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop.api.view.BalanceView;
import cn.autoforged.module_shop.api.view.MailInfo;
import cn.autoforged.module_shop.api.view.ShopItemView;
import cn.autoforged.module_shop.api.view.SignInView;
import cn.autoforged.module_shop.api.view.TransactionInfo;
import cn.autoforged.module_shop.data.DailyBuybackEntry;
import cn.autoforged.module_shop.data.DailyShopEntry;
import cn.autoforged.module_shop.data.MailEntry;
import cn.autoforged.module_shop.data.MallItem;
import cn.autoforged.module_shop.data.PlayerAccount;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.data.SignInData;
import cn.autoforged.module_shop.data.TransactionEntry;
import cn.autoforged.module_shop.shop.mail.MailTemplates;
import cn.autoforged.module_shop.shop.ShopManager;
import cn.autoforged.module_shop.shop.TransactionLog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API 的内部实现：**只组合既有业务能力**（credit / addItemOrMail / addMailDraft / TransactionLog），
 * 不复制任何经济规则；所有写操作都做「主线程断言 + 参数校验 + setDirty」。
 *
 * <p>这个类**不是**公共 API，外部不要直接引用。
 */
public final class ApiImpl {
    /** 外部系统调用记账时使用的来源标记。 */
    public static final String SOURCE_API = "api";
    private static final Logger LOG = LoggerFactory.getLogger("module_shop_api");

    private ApiImpl() {
    }

    // ---------------------------------------------------------------- 公共工具

    private static MinecraftServer serverOrNull() {
        return ModuleShopApi.server();
    }

    private static ApiResult<Void> checkThread(MinecraftServer server) {
        if (server == null) {
            return ApiResult.fail(ApiErrorCode.INTERNAL_ERROR, "服务端尚未启动");
        }
        if (!server.isSameThread()) {
            return ApiResult.fail(ApiErrorCode.WRONG_THREAD, "必须在服务端主线程调用");
        }
        return null;
    }

    private static ShopSavedData data(MinecraftServer server) {
        return ShopManager.data(server);
    }

    /** 找一个已存在的账户（离线也可以）；不存在返回 null。 */
    private static PlayerAccount account(MinecraftServer server, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return data(server).accounts().get(uuid);
    }

    private static void notifyOnline(MinecraftServer server, UUID uuid, Component message) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null && message != null) {
            online.sendSystemMessage(message);
        }
    }

    // ---------------------------------------------------------------- 经济

    public static final class Economy implements EconomyApi {
        @Override
        public long balance(UUID target) {
            MinecraftServer server = serverOrNull();
            if (server == null) {
                return 0L;
            }
            PlayerAccount account = account(server, target);
            return account == null ? 0L : account.balance;
        }

        @Override
        public BalanceView balanceView(UUID target) {
            MinecraftServer server = serverOrNull();
            if (server == null) {
                return null;
            }
            PlayerAccount account = account(server, target);
            if (account == null) {
                return null;
            }
            long max = Config.maxBalanceLimit();
            return new BalanceView(account.balance, max == Long.MAX_VALUE ? 0L : max, ShopManager.currencyName());
        }

        @Override
        public long maxBalance() {
            long max = Config.maxBalanceLimit();
            return max == Long.MAX_VALUE ? 0L : max;
        }

        @Override
        public ApiResult<Long> add(UUID target, long amount, String reason) {
            MinecraftServer server = serverOrNull();
            ApiResult<Void> guard = checkThread(server);
            if (guard != null) {
                return ApiResult.fail(guard.code(), guard.message());
            }
            if (target == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "target 不能为空");
            }
            if (amount <= 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "amount 必须 > 0（支出请用 take）");
            }
            PlayerAccount account = account(server, target);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            long limit = Config.maxBalanceLimit();
            if (account.balance >= limit) {
                return ApiResult.fail(ApiErrorCode.LIMIT_EXCEEDED, "余额已达上限");
            }
            long credited = ShopManager.credit(account, amount, limit);
            if (credited <= 0L) {
                return ApiResult.fail(ApiErrorCode.LIMIT_EXCEEDED, "余额已达上限");
            }
            long rest = amount - credited;
            if (rest > 0L) {
                // 装不下的部分转金钱邮件，绝不丢弃
                ShopManager.addMoneyMail(server, target, rest, ItemStack.EMPTY, SOURCE_API, "系统", null);
            }
            TransactionLog.record(server, target, TransactionLog.EXTERNAL, "API",
                    credited, account.balance, safeReason(reason));
            data(server).setDirty();
            notifyOnline(server, target, Component.translatable("msg.module_shop.received_money", credited));
            return ApiResult.ok(credited);
        }

        @Override
        public ApiResult<Long> take(UUID target, long amount, String reason) {
            MinecraftServer server = serverOrNull();
            ApiResult<Void> guard = checkThread(server);
            if (guard != null) {
                return ApiResult.fail(guard.code(), guard.message());
            }
            if (target == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "target 不能为空");
            }
            if (amount <= 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "amount 必须 > 0（收入请用 add）");
            }
            PlayerAccount account = account(server, target);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            if (account.balance < amount) {
                // 语义：余额不足整笔失败，绝不扣到 0
                return ApiResult.fail(ApiErrorCode.INSUFFICIENT_FUNDS,
                        "余额不足：需要 " + amount + "，现有 " + account.balance);
            }
            long beforeBalance = account.balance;
            account.balance -= amount;
            TransactionLog.record(server, target, TransactionLog.EXTERNAL, "API",
                    -amount, account.balance, safeReason(reason));
            // 事件：余额变化（扣钱方向）
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.BalanceChangedEvent(
                            target, beforeBalance, account.balance, -amount, safeReason(reason)));
            data(server).setDirty();
            return ApiResult.ok(amount);
        }
    }

    // ---------------------------------------------------------------- 物品投递

    public static final class Items implements ItemDeliveryApi {
        @Override
        public ApiResult<Void> give(UUID target, ItemStack stack) {
            List<ItemStack> one = new ArrayList<>();
            if (stack != null && !stack.isEmpty()) {
                one.add(stack);
            }
            return giveAll(target, one);
        }

        @Override
        public ApiResult<Void> giveAll(UUID target, List<ItemStack> stacks) {
            MinecraftServer server = serverOrNull();
            ApiResult<Void> guard = checkThread(server);
            if (guard != null) {
                return guard;
            }
            if (target == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "target 不能为空");
            }
            List<ItemStack> clean = new ArrayList<>();
            if (stacks != null) {
                for (ItemStack stack : stacks) {
                    if (stack != null && !stack.isEmpty() && stack.getCount() > 0) {
                        clean.add(stack.copy());
                    }
                }
            }
            if (clean.isEmpty()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "没有可发放的物品");
            }
            if (account(server, target) == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            ServerPlayer online = server.getPlayerList().getPlayer(target);
            if (online != null) {
                // 在线：逐件走既有兜底（背包 → 满则邮箱）
                for (ItemStack stack : clean) {
                    ShopManager.addItemOrMail(online, stack, SOURCE_API);
                }
                return ApiResult.ok();
            }
            // 离线：一封多附件邮件
            MailTemplates.DraftMail draft = MailTemplates.build(
                    MailTemplates.Template.API_MAIL, MailTemplates.map(
                            "player", ShopManager.nameOf(server, target)))
                    .title("奖励发放")
                    .content("您有一条来自系统的物品奖励，已附在附件中，请查收。")
                    .source(SOURCE_API);
            for (ItemStack stack : clean) {
                draft.addItem(stack);
            }
            ShopManager.addMailDraft(server, target, draft);
            return ApiResult.ok();
        }
    }

    // ---------------------------------------------------------------- 邮箱

    public static final class Mail implements MailApi {
        @Override
        public ApiResult<String> sendItemMail(UUID target, String title, String body, List<ItemStack> items) {
            MinecraftServer server = serverOrNull();
            ApiResult<Void> guard = checkThread(server);
            if (guard != null) {
                return ApiResult.fail(guard.code(), guard.message());
            }
            if (target == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "target 不能为空");
            }
            if (account(server, target) == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            MailTemplates.DraftMail draft = MailTemplates.build(
                    MailTemplates.Template.API_MAIL, MailTemplates.map(
                            "player", ShopManager.nameOf(server, target)))
                    .title(title == null || title.isBlank() ? "系统邮件" : title)
                    .content(body == null ? "" : body)
                    .source(SOURCE_API);
            if (items != null) {
                for (ItemStack stack : items) {
                    if (stack != null && !stack.isEmpty()) {
                        draft.addItem(stack.copy());
                    }
                }
            }
            ShopManager.addMailDraft(server, target, draft);
            return ApiResult.ok("api-" + System.nanoTime());
        }

        @Override
        public ApiResult<String> sendMoneyMail(UUID target, long amount, String title, String body) {
            MinecraftServer server = serverOrNull();
            ApiResult<Void> guard = checkThread(server);
            if (guard != null) {
                return ApiResult.fail(guard.code(), guard.message());
            }
            if (target == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "target 不能为空");
            }
            if (amount <= 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "amount 必须 > 0");
            }
            if (account(server, target) == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            MailTemplates.DraftMail draft = MailTemplates.build(
                    MailTemplates.Template.API_MAIL, MailTemplates.map(
                            "player", ShopManager.nameOf(server, target)))
                    .title(title == null || title.isBlank() ? "金额邮件" : title)
                    .content(body == null ? "" : body)
                    .money(amount)
                    .source(SOURCE_API);
            ShopManager.addMailDraft(server, target, draft);
            return ApiResult.ok("api-" + System.nanoTime());
        }

        @Override
        public List<MailInfo> mailbox(UUID target) {
            List<MailInfo> out = new ArrayList<>();
            MinecraftServer server = serverOrNull();
            if (server == null || target == null) {
                return out;
            }
            PlayerAccount account = account(server, target);
            if (account == null) {
                return out;
            }
            List<MailEntry> mails = new ArrayList<>(account.mailbox);
            mails.sort((a, b) -> Long.compare(b.sentAt, a.sentAt));
            for (MailEntry entry : mails) {
                List<ItemStack> items = new ArrayList<>();
                if (entry.stack != null && !entry.stack.isEmpty()) {
                    items.add(entry.stack.copy());
                }
                for (ItemStack extra : entry.stacks) {
                    if (extra != null && !extra.isEmpty()) {
                        items.add(extra.copy());
                    }
                }
                out.add(new MailInfo(entry.mailId, entry.title, entry.sender, entry.currencyAmount,
                        items, entry.isClaimed, entry.isViewed, entry.sentAt, entry.expireAt));
            }
            return out;
        }

        @Override
        public int unclaimed(UUID target) {
            int count = 0;
            MinecraftServer server = serverOrNull();
            if (server == null || target == null) {
                return 0;
            }
            PlayerAccount account = account(server, target);
            if (account == null) {
                return 0;
            }
            for (MailEntry entry : account.mailbox) {
                if (!entry.isClaimed && (entry.currencyAmount > 0L
                        || (entry.stack != null && !entry.stack.isEmpty())
                        || !entry.stacks.isEmpty())) {
                    count++;
                }
            }
            return count;
        }
    }

    // ---------------------------------------------------------------- 流水

    public static final class History implements HistoryApi {
        @Override
        public List<TransactionInfo> history(UUID target, int limit) {
            List<TransactionInfo> out = new ArrayList<>();
            MinecraftServer server = serverOrNull();
            if (server == null || target == null) {
                return out;
            }
            PlayerAccount account = account(server, target);
            if (account == null) {
                return out;
            }
            int max = limit <= 0 ? 50 : Math.min(limit, 200);
            List<TransactionEntry> list = account.transactions;
            for (int i = list.size() - 1; i >= 0 && out.size() < max; i--) {
                TransactionEntry entry = list.get(i);
                out.add(new TransactionInfo(entry.time, entry.type, entry.counterParty, entry.amount,
                        entry.balanceAfter, entry.note));
            }
            return out;
        }

        @Override
        public ApiResult<Void> record(UUID target, String type, String counterParty, long amount, String note) {
            MinecraftServer server = serverOrNull();
            ApiResult<Void> guard = checkThread(server);
            if (guard != null) {
                return guard;
            }
            if (target == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "target 不能为空");
            }
            if (amount == 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "amount 不能为 0");
            }
            PlayerAccount account = account(server, target);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            TransactionLog.record(server, target,
                    type == null || type.isBlank() ? TransactionLog.EXTERNAL : type,
                    counterParty == null ? "" : counterParty, amount, account.balance, note);
            data(server).setDirty();
            return ApiResult.ok();
        }
    }

    // ---------------------------------------------------------------- 签到（只读）

    public static final class Daily implements DailyApi {
        @Override
        public SignInView signIn(UUID target) {
            MinecraftServer server = serverOrNull();
            if (server == null || target == null) {
                return null;
            }
            if (account(server, target) == null) {
                return null;
            }
            ShopSavedData data = data(server);
            SignInData signIn = data.signIns.get(target);
            if (signIn == null) {
                return new SignInView(false, 0, 0, 0L, data.signInBaseDaily);
            }
            long today = ShopManager.realDay();
            return new SignInView(signIn.signedOn(today), signIn.streak, signIn.bestStreak,
                    signIn.lastSignDay, data.signInBaseDaily);
        }

        @Override
        public boolean canSignToday(UUID target) {
            MinecraftServer server = serverOrNull();
            if (server == null || target == null) {
                return false;
            }
            ShopSavedData data = data(server);
            if (!data.signInEnabled) {
                return false;
            }
            SignInData signIn = data.signIns.get(target);
            return signIn == null || !signIn.signedOn(ShopManager.realDay());
        }

        @Override
        public List<cn.autoforged.module_shop.api.view.ActivityView> activities() {
            List<cn.autoforged.module_shop.api.view.ActivityView> out = new ArrayList<>();
            MinecraftServer server = serverOrNull();
            if (server == null) {
                return out;
            }
            ShopSavedData data = data(server);
            List<cn.autoforged.module_shop.data.ActivityDay> list =
                    new ArrayList<>(data.activityDays.values());
            list.sort((a, b) -> Long.compare(a.day, b.day));
            for (cn.autoforged.module_shop.data.ActivityDay act : list) {
                if (act == null) {
                    continue;
                }
                int rewardCount = act.rewards == null ? 0 : act.rewards.size();
                if (act.stack != null && !act.stack.isEmpty()) {
                    rewardCount++;
                }
                if (act.gift != null && !act.gift.isEmpty()) {
                    rewardCount++;
                }
                out.add(new cn.autoforged.module_shop.api.view.ActivityView(
                        act.day, act.title, act.note, act.money, rewardCount, act.hour));
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- 商店（只读）

    public static final class Shop implements cn.autoforged.module_shop.api.ShopApi {
        @Override
        public List<ShopItemView> dailyShop(UUID playerId) {
            List<ShopItemView> out = new ArrayList<>();
            MinecraftServer server = serverOrNull();
            if (server == null || playerId == null) {
                return out;
            }
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return out;
            }
            int index = 0;
            for (DailyShopEntry entry : account.dailyShop) {
                if (entry == null || entry.stack == null || entry.stack.isEmpty()) {
                    index++;
                    continue;
                }
                out.add(view("daily-" + index, entry.stack, entry.price, entry.remaining(),
                        entry.limit <= 0));
                index++;
            }
            return out;
        }

        @Override
        public List<ShopItemView> mall() {
            List<ShopItemView> out = new ArrayList<>();
            MinecraftServer server = serverOrNull();
            if (server == null) {
                return out;
            }
            List<MallItem> pool = new ArrayList<>(data(server).mallPool);
            int index = 0;
            for (MallItem item : pool) {
                if (item == null || item.stack == null || item.stack.isEmpty()) {
                    index++;
                    continue;
                }
                out.add(view("mall-" + index, item.stack, item.price, item.available(), item.unlimited()));
                index++;
            }
            return out;
        }

        @Override
        public List<ShopItemView> buyback(UUID playerId) {
            List<ShopItemView> out = new ArrayList<>();
            MinecraftServer server = serverOrNull();
            if (server == null || playerId == null) {
                return out;
            }
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return out;
            }
            int index = 0;
            for (DailyBuybackEntry entry : account.dailyBuyback) {
                if (entry == null || entry.stack == null || entry.stack.isEmpty()) {
                    index++;
                    continue;
                }
                out.add(view("buyback-" + index, entry.stack, entry.price, entry.remaining(), false));
                index++;
            }
            return out;
        }

        private static ShopItemView view(String id, ItemStack stack, long price, int stock, boolean unlimited) {
            return new ShopItemView(id, stack.copy(), price, stock, unlimited);
        }

        // ------------------------------------------------------------ 执行类（带校验）

        @Override
        public ApiResult<Void> buyDaily(UUID playerId, int index, int count, long expectedPrice) {
            ApiResult<Void> guard = checkThread(serverOrNull());
            if (guard != null) {
                return guard;
            }
            MinecraftServer server = serverOrNull();
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            List<DailyShopEntry> offers = account.dailyShop;
            if (index < 0 || index >= offers.size()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "商品下标越界：" + index);
            }
            DailyShopEntry offer = offers.get(index);
            if (offer == null || offer.stack == null || offer.stack.isEmpty()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "该商品不存在");
            }
            ApiResult<Void> countGuard = checkCount(count);
            if (countGuard != null) {
                return countGuard;
            }
            if (offer.remaining() <= 0) {
                return ApiResult.fail(ApiErrorCode.SOLD_OUT, "该商品今天已买满");
            }
            int buyCount = Math.min(count, offer.remaining());
            long total = priceTimes(offer.price, buyCount);
            if (total <= 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "价格非法");
            }
            ApiResult<Void> priceGuard = checkPrice(expectedPrice, offer.price);
            if (priceGuard != null) {
                return priceGuard;
            }
            ServerPlayer online = onlineOf(server, playerId);
            if (online == null) {
                return ApiResult.fail(ApiErrorCode.PLAYER_OFFLINE, "购买需要玩家在线");
            }
            if (account.balance < total) {
                return ApiResult.fail(ApiErrorCode.INSUFFICIENT_FUNDS,
                        "余额不足：需要 " + total + "，现有 " + account.balance);
            }
            long before = account.balance;
            String itemId = "daily-" + index;
            ItemStack bought = offer.stack.copy();
            int boughtCount = buyCount;
            ShopManager.buyShop(online, index, count);
            long paid = before - account.balance;
            if (paid <= 0L) {
                // 内部方法没有成交（可能刚被买满/价格变动）
                return ApiResult.fail(ApiErrorCode.SOLD_OUT, "未成交（可能刚被买满）");
            }
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.PurchaseCompletedEvent(
                            playerId, "daily", itemId, bought, boughtCount, paid));
            return ApiResult.ok();
        }

        @Override
        public ApiResult<Void> buyMall(UUID playerId, int index, int count, long expectedPrice) {
            ApiResult<Void> guard = checkThread(serverOrNull());
            if (guard != null) {
                return guard;
            }
            MinecraftServer server = serverOrNull();
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            List<MallItem> pool = data(server).mallPool;
            if (index < 0 || index >= pool.size()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "商品下标越界：" + index);
            }
            MallItem item = pool.get(index);
            if (item == null || item.stack == null || item.stack.isEmpty()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "该商品不存在");
            }
            ApiResult<Void> countGuard = checkCount(count);
            if (countGuard != null) {
                return countGuard;
            }
            if (!item.unlimited() && item.available() <= 0) {
                return ApiResult.fail(ApiErrorCode.SOLD_OUT, "该商品已售罄");
            }
            int buyCount = item.unlimited() ? count : Math.min(count, item.available());
            long total = priceTimes(item.price, buyCount);
            if (total <= 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "价格非法");
            }
            ApiResult<Void> priceGuard = checkPrice(expectedPrice, item.price);
            if (priceGuard != null) {
                return priceGuard;
            }
            ServerPlayer online = onlineOf(server, playerId);
            if (online == null) {
                return ApiResult.fail(ApiErrorCode.PLAYER_OFFLINE, "购买需要玩家在线");
            }
            if (account.balance < total) {
                return ApiResult.fail(ApiErrorCode.INSUFFICIENT_FUNDS,
                        "余额不足：需要 " + total + "，现有 " + account.balance);
            }
            long before = account.balance;
            String itemId = "mall-" + index;
            ItemStack bought = item.stack.copy();
            ShopManager.buyMall(online, index, count);
            long paid = before - account.balance;
            if (paid <= 0L) {
                return ApiResult.fail(ApiErrorCode.SOLD_OUT, "未成交（可能刚被买空）");
            }
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.PurchaseCompletedEvent(
                            playerId, "mall", itemId, bought, buyCount, paid));
            return ApiResult.ok();
        }

        @Override
        public ApiResult<Void> sellBuyback(UUID playerId, int index, int count) {
            ApiResult<Void> guard = checkThread(serverOrNull());
            if (guard != null) {
                return guard;
            }
            MinecraftServer server = serverOrNull();
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            List<DailyBuybackEntry> offers = account.dailyBuyback;
            if (index < 0 || index >= offers.size()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "收购项下标越界：" + index);
            }
            DailyBuybackEntry offer = offers.get(index);
            if (offer == null || offer.stack == null || offer.stack.isEmpty()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "该收购项不存在");
            }
            ApiResult<Void> countGuard = checkCount(count);
            if (countGuard != null) {
                return countGuard;
            }
            if (offer.remaining() <= 0) {
                return ApiResult.fail(ApiErrorCode.SOLD_OUT, "今天的收购份额已满");
            }
            ServerPlayer online = onlineOf(server, playerId);
            if (online == null) {
                return ApiResult.fail(ApiErrorCode.PLAYER_OFFLINE, "出售需要玩家在线");
            }
            long before = account.balance;
            String itemId = "buyback-" + index;
            ItemStack sold = offer.stack.copy();
            ShopManager.sellBuyback(online, index, count);
            long earned = account.balance - before;
            if (earned <= 0L) {
                return ApiResult.fail(ApiErrorCode.SOLD_OUT, "未成交（可能背包里没有该物品）");
            }
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.SellCompletedEvent(
                            playerId, "buyback", itemId, sold, count, earned));
            return ApiResult.ok();
        }

        /** 在线玩家；不存在返回 null。 */
        private static ServerPlayer onlineOf(MinecraftServer server, UUID id) {
            if (server == null || id == null) {
                return null;
            }
            return server.getPlayerList().getPlayer(id);
        }

        /** 数量校验：1..64。 */
        private static ApiResult<Void> checkCount(int count) {
            if (count < 1 || count > 64) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "count 必须在 1..64 之间");
            }
            return null;
        }

        /** 价格校验：expectedPrice <= 0 表示不校验。 */
        private static ApiResult<Void> checkPrice(long expectedPrice, long actualPrice) {
            if (expectedPrice > 0L && expectedPrice != actualPrice) {
                return ApiResult.fail(ApiErrorCode.PRICE_CHANGED,
                        "价格已变化：你看到 " + expectedPrice + "，当前 " + actualPrice);
            }
            return null;
        }

        // ------------------------------------------------------------ 拍卖 / 求购 / 摊位（第五批）

        @Override
        public List<cn.autoforged.module_shop.api.view.AuctionView> auctions() {
            List<cn.autoforged.module_shop.api.view.AuctionView> out = new ArrayList<>();
            MinecraftServer server = serverOrNull();
            if (server == null) {
                return out;
            }
            long now = System.currentTimeMillis();
            for (cn.autoforged.module_shop.data.AuctionEntry auction
                    : new ArrayList<>(data(server).auctions)) {
                if (auction == null || auction.stack == null || auction.stack.isEmpty()) {
                    continue;
                }
                out.add(new cn.autoforged.module_shop.api.view.AuctionView(
                        auction.id, auction.seller, auction.sellerName, auction.stack.copy(),
                        auction.startPrice, auction.currentBid, auction.topBidder, auction.endTime,
                        now >= auction.endTime));
            }
            return out;
        }

        @Override
        public ApiResult<Void> bid(UUID playerId, long auctionId, long amount) {
            ApiResult<Void> guard = checkThread(serverOrNull());
            if (guard != null) {
                return guard;
            }
            MinecraftServer server = serverOrNull();
            if (playerId == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "playerId 不能为空");
            }
            if (amount <= 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "出价必须 > 0");
            }
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            cn.autoforged.module_shop.data.AuctionEntry target = null;
            for (cn.autoforged.module_shop.data.AuctionEntry entry : data(server).auctions) {
                if (entry.id == auctionId) {
                    target = entry;
                    break;
                }
            }
            if (target == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "拍卖不存在：" + auctionId);
            }
            if (System.currentTimeMillis() >= target.endTime) {
                return ApiResult.fail(ApiErrorCode.SOLD_OUT, "拍卖已结束");
            }
            if (playerId.equals(target.seller)) {
                return ApiResult.fail(ApiErrorCode.NO_PERMISSION, "不能给自己的拍卖出价");
            }
            long floor = Math.max(target.startPrice, target.currentBid + 1L);
            if (amount < floor) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT,
                        "出价过低：至少要 " + floor + "，当前 " + target.currentBid);
            }
            if (account.balance < amount) {
                return ApiResult.fail(ApiErrorCode.INSUFFICIENT_FUNDS,
                        "余额不足：需要 " + amount + "，现有 " + account.balance);
            }
            ServerPlayer online = onlineOf(server, playerId);
            if (online == null) {
                return ApiResult.fail(ApiErrorCode.PLAYER_OFFLINE, "出价需要玩家在线");
            }
            long before = account.balance;
            CompoundTag tag = new CompoundTag();
            tag.putLong("id", auctionId);
            tag.putString("amount", Long.toString(amount));
            ShopManager.placeBid(online, tag);
            long paid = before - account.balance;
            if (paid <= 0L) {
                return ApiResult.fail(ApiErrorCode.INTERNAL_ERROR, "出价未成交（服务端拒绝）");
            }
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.AuctionBidPlacedEvent(
                            playerId, auctionId, paid));
            return ApiResult.ok();
        }

        @Override
        public ApiResult<String> createDemand(UUID playerId, List<ItemStack> wanted, long rewardMoney) {
            ApiResult<Void> guard = checkThread(serverOrNull());
            if (guard != null) {
                return ApiResult.fail(guard.code(), guard.message());
            }
            MinecraftServer server = serverOrNull();
            if (!Config.demandEnabled()) {
                return ApiResult.fail(ApiErrorCode.DISABLED, "求购功能未启用");
            }
            if (playerId == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "playerId 不能为空");
            }
            if (rewardMoney < 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "悬赏不能为负");
            }
            List<ItemStack> clean = new ArrayList<>();
            if (wanted != null) {
                for (ItemStack stack : wanted) {
                    if (stack != null && !stack.isEmpty() && stack.getCount() > 0) {
                        clean.add(stack.copy());
                    }
                }
            }
            if (clean.isEmpty()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "想要清单不能为空");
            }
            if (clean.size() > 12) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "最多 12 种想要物品");
            }
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            ServerPlayer online = onlineOf(server, playerId);
            if (online == null) {
                return ApiResult.fail(ApiErrorCode.PLAYER_OFFLINE, "发布求购需要玩家在线");
            }
            int countBefore = data(server).demands.size();
            long moneyBefore = account.balance;
            CompoundTag tag = new CompoundTag();
            tag.putString("action", "demand_create");
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (ItemStack stack : clean) {
                CompoundTag line = new CompoundTag();
                line.put("item", stack.saveOptional(server.registryAccess()));
                line.putInt("count", Math.min(64, Math.max(1, stack.getCount())));
                list.add(line);
            }
            tag.put("wants", list);
            tag.putString("reward_money", Long.toString(rewardMoney));
            ShopManager.demandCreate(online, tag);
            int countAfter = data(server).demands.size();
            if (countAfter <= countBefore) {
                // 内部校验失败（余额不够 / 超上限 / 配置关闭…）：无副作用
                return ApiResult.fail(ApiErrorCode.INTERNAL_ERROR,
                        "求购未创建（检查余额 / 每人条数上限 / 功能开关）");
            }
            String newId = "";
            for (cn.autoforged.module_shop.data.DemandEntry entry : data(server).demands) {
                if (playerId.equals(entry.requester)) {
                    newId = entry.id;
                }
            }
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.DemandCreatedEvent(
                            playerId, newId, Math.max(0L, moneyBefore - account.balance), clean.size()));
            return ApiResult.ok(newId);
        }

        @Override
        public ApiResult<Void> cancelDemand(UUID playerId, String demandId) {
            ApiResult<Void> guard = checkThread(serverOrNull());
            if (guard != null) {
                return guard;
            }
            MinecraftServer server = serverOrNull();
            if (playerId == null || demandId == null || demandId.isBlank()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "参数不能为空");
            }
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            boolean exists = false;
            for (cn.autoforged.module_shop.data.DemandEntry entry : data(server).demands) {
                if (demandId.equals(entry.id)) {
                    exists = playerId.equals(entry.requester);
                    if (!exists) {
                        return ApiResult.fail(ApiErrorCode.NO_PERMISSION, "只能撤销自己的求购");
                    }
                    break;
                }
            }
            if (!exists) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "求购不存在：" + demandId);
            }
            ServerPlayer online = onlineOf(server, playerId);
            if (online == null) {
                return ApiResult.fail(ApiErrorCode.PLAYER_OFFLINE, "撤销求购需要玩家在线");
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("id", demandId);
            ShopManager.demandCancel(online, tag);
            for (cn.autoforged.module_shop.data.DemandEntry entry : data(server).demands) {
                if (demandId.equals(entry.id)) {
                    return ApiResult.fail(ApiErrorCode.INTERNAL_ERROR, "撤销未生效（服务端拒绝）");
                }
            }
            return ApiResult.ok();
        }

        @Override
        public ApiResult<Void> stallList(UUID playerId, int stallIndex, int slot, int count, long price) {
            ApiResult<Void> guard = checkThread(serverOrNull());
            if (guard != null) {
                return guard;
            }
            MinecraftServer server = serverOrNull();
            if (playerId == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "playerId 不能为空");
            }
            if (stallIndex < 0 || slot < 0 || slot > 40) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "stallIndex/slot 越界");
            }
            ApiResult<Void> countGuard = checkCount(count);
            if (countGuard != null) {
                return countGuard;
            }
            if (price <= 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "价格必须 > 0");
            }
            PlayerAccount account = account(server, playerId);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            ServerPlayer online = onlineOf(server, playerId);
            if (online == null) {
                return ApiResult.fail(ApiErrorCode.PLAYER_OFFLINE, "上架需要玩家在线");
            }
            ItemStack inSlot = online.getInventory().getItem(slot);
            if (inSlot.isEmpty() || inSlot.getCount() < count) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT,
                        "背包第 " + slot + " 槽没有足够的物品");
            }
            ItemStack listed = inSlot.copy();
            listed.setCount(count);
            int before = account.stall.size();
            CompoundTag tag = new CompoundTag();
            tag.putInt("index", stallIndex);
            tag.putInt("slot", slot);
            tag.putInt("count", count);
            tag.putString("price", Long.toString(price));
            ShopManager.stallSetPriceNew(online, tag);
            if (account.stall.size() <= before) {
                return ApiResult.fail(ApiErrorCode.INTERNAL_ERROR, "上架未生效（服务端拒绝）");
            }
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.StallListedEvent(
                            playerId, stallIndex, listed, count, price));
            return ApiResult.ok();
        }

        /** 溢出安全乘法（与内部实现一致）。 */
        private static long priceTimes(long price, int count) {
            if (price <= 0L || count <= 0) {
                return 0L;
            }
            long result;
            try {
                result = Math.multiplyExact(price, (long) count);
            } catch (ArithmeticException ex) {
                return 0L;
            }
            return result <= 0L ? 0L : result;
        }
    }

    // ---------------------------------------------------------------- 奖励（幂等）

    public static final class Reward implements RewardApi {
        @Override
        public ApiResult<Void> grantOnce(UUID target, String requestId, List<ItemStack> items, long money,
                String reason) {
            MinecraftServer server = serverOrNull();
            ApiResult<Void> guard = checkThread(server);
            if (guard != null) {
                return guard;
            }
            if (target == null) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "target 不能为空");
            }
            if (requestId == null || requestId.isBlank()) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "requestId 不能为空（幂等键）");
            }
            if (money < 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "money 不能为负");
            }
            List<ItemStack> clean = new ArrayList<>();
            if (items != null) {
                for (ItemStack stack : items) {
                    if (stack != null && !stack.isEmpty() && stack.getCount() > 0) {
                        clean.add(stack.copy());
                    }
                }
            }
            if (clean.isEmpty() && money <= 0L) {
                return ApiResult.fail(ApiErrorCode.INVALID_ARGUMENT, "奖励内容为空");
            }
            PlayerAccount account = account(server, target);
            if (account == null) {
                return ApiResult.fail(ApiErrorCode.UNKNOWN_PLAYER, "找不到该玩家的账户");
            }
            String key = target + "|" + requestId.trim();
            ShopSavedData data = data(server);
            if (data.apiRequests.contains(key)) {
                return ApiResult.fail(ApiErrorCode.DUPLICATE_REQUEST, "该奖励已经发放过：" + requestId);
            }
            // 先记幂等键再发放：同主线程内不会并发，避免"发放成功但键没记上"导致的重复。
            data.apiRequests.add(key);
            data.trimApiRequests();

            // 金币（受上限，溢出转金钱邮件）
            if (money > 0L) {
                long limit = Config.maxBalanceLimit();
                long credited = ShopManager.credit(account, money, limit);
                long rest = money - credited;
                if (rest > 0L) {
                    ShopManager.addMoneyMail(server, target, rest, ItemStack.EMPTY, SOURCE_API, "系统", null);
                }
                TransactionLog.record(server, target, TransactionLog.EXTERNAL, "奖励",
                        credited, account.balance, safeReason(reason));
            }
            // 物品（在线背包 / 离线邮件）
            if (!clean.isEmpty()) {
                ServerPlayer online = server.getPlayerList().getPlayer(target);
                if (online != null) {
                    for (ItemStack stack : clean) {
                        ShopManager.addItemOrMail(online, stack, SOURCE_API);
                    }
                } else {
                    MailTemplates.DraftMail draft = MailTemplates.build(
                            MailTemplates.Template.API_MAIL, MailTemplates.map(
                                    "player", ShopManager.nameOf(server, target)))
                            .title("奖励发放")
                            .content(safeReason(reason))
                            .source(SOURCE_API);
                    for (ItemStack stack : clean) {
                        draft.addItem(stack);
                    }
                    ShopManager.addMailDraft(server, target, draft);
                }
            }
            data.setDirty();
            // 事件：奖励已发放（成功后发布）
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.RewardGrantedEvent(
                            target, requestId, Math.min(money, Math.max(0L, money)), clean.size(),
                            safeReason(reason)));
            notifyOnline(server, target, Component.translatable("msg.module_shop.api_reward",
                    safeReason(reason), money));
            return ApiResult.ok();
        }
    }

    /** 兜底：reason 为空时给一个通用说明（流水/邮件的可读性）。 */
    private static String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "系统奖励" : reason.trim();
    }

    /** 统一日志入口（内部排错用）。 */
    public static void logError(String message, Throwable error) {
        LOG.error(message, error);
    }
}