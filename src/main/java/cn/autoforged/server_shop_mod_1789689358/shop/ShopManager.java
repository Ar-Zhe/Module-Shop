package cn.autoforged.server_shop_mod_1789689358.shop;

import cn.autoforged.server_shop_mod_1789689358.Config;
import cn.autoforged.server_shop_mod_1789689358.ServerShopMod;
import cn.autoforged.server_shop_mod_1789689358.admin.AdminEditType;
import cn.autoforged.server_shop_mod_1789689358.data.ActivityDay;
import cn.autoforged.server_shop_mod_1789689358.data.AuctionEntry;
import cn.autoforged.server_shop_mod_1789689358.data.ConfiguredOffer;
import cn.autoforged.server_shop_mod_1789689358.data.DailyBuybackEntry;
import cn.autoforged.server_shop_mod_1789689358.data.DemandEntry;
import cn.autoforged.server_shop_mod_1789689358.data.DailyShopEntry;
import cn.autoforged.server_shop_mod_1789689358.data.ExchangeEntry;
import cn.autoforged.server_shop_mod_1789689358.data.MailEntry;
import cn.autoforged.server_shop_mod_1789689358.data.MailType;
import cn.autoforged.server_shop_mod_1789689358.data.MallItem;
import cn.autoforged.server_shop_mod_1789689358.data.MallPurchase;
import cn.autoforged.server_shop_mod_1789689358.data.ShippingBin;
import cn.autoforged.server_shop_mod_1789689358.data.NotificationType;
import cn.autoforged.server_shop_mod_1789689358.data.PlayerAccount;
import cn.autoforged.server_shop_mod_1789689358.data.Rarity;
import cn.autoforged.server_shop_mod_1789689358.data.RedeemCode;
import cn.autoforged.server_shop_mod_1789689358.data.ShopNbt;
import cn.autoforged.server_shop_mod_1789689358.data.ShopSavedData;
import cn.autoforged.server_shop_mod_1789689358.data.SidebarLayout;
import cn.autoforged.server_shop_mod_1789689358.data.SignInData;
import cn.autoforged.server_shop_mod_1789689358.data.StallEntry;
import cn.autoforged.server_shop_mod_1789689358.data.TransactionEntry;
import cn.autoforged.server_shop_mod_1789689358.menu.BatchAddMenu;
import cn.autoforged.server_shop_mod_1789689358.menu.DepositMenu;
import cn.autoforged.server_shop_mod_1789689358.menu.GrantMenu;
import cn.autoforged.server_shop_mod_1789689358.menu.ShippingBinMenu;
import cn.autoforged.server_shop_mod_1789689358.shop.mail.MailTemplates;
import cn.autoforged.server_shop_mod_1789689358.network.payload.ClientboundShopSyncPayload;
import cn.autoforged.server_shop_mod_1789689358.sound.ModSounds;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import javax.annotation.Nullable;

/** 服务端权威的商店逻辑。所有校验都在这里完成，客户端只负责显示与发起请求。 */
public final class ShopManager {
    public static final String SOURCE_AUCTION_UNSOLD = "auction_unsold";
    public static final String SOURCE_AUCTION_WON = "auction_won";
    public static final String SOURCE_STALL_UNSOLD = "stall_unsold";
    /** 摊位到期自动下架，剩余库存退回。 */
    public static final String SOURCE_STALL_EXPIRED = "stall_expired";
    public static final String SOURCE_ADMIN = "admin";
    /** 需求：拍卖成交后给卖家的金额类邮件来源。 */
    public static final String SOURCE_AUCTION_SOLD = "auction_sold";
    /** 需求：摊位售出后给卖家的金额类邮件来源。 */
    public static final String SOURCE_STALL_SOLD = "stall_sold";
    /** 买家在摊位购买后，因背包放不下而进邮箱的物品来源（区别于“卖家上架未售出退还”）。 */
    public static final String SOURCE_STALL_BOUGHT = "stall_bought";
    /** 被超价后，退款余额装不下而改发金额邮件的来源。 */
    public static final String SOURCE_AUCTION_REFUND = "auction_refund";
    /** 货币取款时背包放不下，改发邮件（或兑换找零退回）的物品来源。 */
    public static final String SOURCE_EXCHANGE = "exchange";
    /** 出货箱结算时余额装不下、差额转成金额邮件的来源。 */
    public static final String SOURCE_SHIPPING_UNPAID = "shipping_unpaid";

    private static final Map<UUID, String> DEPOSIT_CONTEXT = new HashMap<>();
    private static final Map<UUID, ItemStack> PENDING_ITEM = new HashMap<>();
    /**
     * 需求 11：管理员通过幽灵槽放入的“模板物品”。
     * 幽灵槽会把原物品返还给管理员，因此这里只保存模板副本；模板绝不作为真实物品返还，
     * 避免“幽灵槽返还原物 + 悬空副本再返还”造成的刷物品漏洞。
     */
    private static final Map<UUID, ItemStack> ADMIN_TEMPLATE = new HashMap<>();

    /**
     * 需求 8/9：所有涉及余额、库存、竞拍的变更都在这把锁内执行，
     * 防止多名玩家同时购买最后一件商品/竞拍同一物品导致数据不一致。
     */
    /** 第七轮：全服物品发放邮件的有效期（天）。 */
    private static final int GRANT_EXPIRE_DAYS = 30;
    public static final Object LOCK = new Object();

    /** 需求 11：服务端记录管理员当前正在编辑的模块（每日商店 / 每日收购）。 */
    private static final Map<UUID, AdminEditType> ADMIN_EDIT_TYPE = new HashMap<>();
    /** 购物城"最近购买"下发给客户端的条数（存档里最多留 200 条）。 */
    private static final int MALL_RECENT_SEND = 60;
    /** 一次「整模组导入」最多导入多少件（防止误点把几万件物品塞进快照 / 存档）。 */
    private static final int MALL_IMPORT_MAX = 2000;

    private ShopManager() {
    }

    public static ShopSavedData data(MinecraftServer server) {
        return ShopSavedData.get(server);
    }

    /**
     * 现实时间日：需求明确“每日0点”指现实时间，因此以服务器本地日历日（LocalDate）为准，
     * 在本地时间午夜切换，而不是按游戏内天数（24000 tick）。
     */
    private static long currentDay(net.minecraft.world.level.Level level) {
        return LocalDate.now().toEpochDay();
    }

    public static PlayerAccount account(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        PlayerAccount account = data.account(player.getUUID());
        String name = player.getGameProfile().getName();
        if (!name.equals(account.name)) {
            account.name = name;
            data.setDirty();
        }
        return account;
    }

    // =========================================================== 每日刷新

    public static void ensureDaily(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        long day = currentDay(player.level());
        boolean dirty = false;
        boolean shopRefreshed = false;
        if (account.dailyShopDay != day) {
            account.dailyShopDay = day;
            account.dailyShop.clear();
            account.dailyFlipped.clear();
            // 新的一天：付费刷新次数重新计。
            account.dailyShopRefreshUsed = 0;
            generateDailyShop(player, account, data);
            dirty = true;
            shopRefreshed = true;
        }
        if (account.dailyBuybackDay != day) {
            account.dailyBuybackDay = day;
            account.dailyBuyback.clear();
            // 新的一天：全清奖励可以再拿一次，付费刷新次数重新计。
            account.dailyBuybackBonusDone = false;
            account.dailyBuybackRefreshUsed = 0;
            generateDailyBuyback(player, account, data);
            dirty = true;
            shopRefreshed = true;
        }
        if (dirty) {
            data.setDirty();
        }
        // 需求 10：每日刷新时间到了给玩家一个商店提示红点。
        if (shopRefreshed) {
            NotificationManager.notify(player.server, player.getUUID(), NotificationType.SHOP);
        }
    }

    private static void generateDailyShop(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        account.dailyFlipped.clear();
        List<Rarity> available = new ArrayList<>();
        for (Rarity rarity : Rarity.values()) {
            if (hasRarity(data.shopPool, rarity)) {
                available.add(rarity);
            }
        }
        if (available.isEmpty()) {
            return;
        }
        RandomSource random = player.getRandom();
        Set<Item> chosen = new HashSet<>();
        // 各品质已出现的数量：普通档位有上限（默认最多 3 个），抽到满额时从可选品质里剔除。
        Map<Rarity, Integer> usedByRarity = new HashMap<>();
        int commonLimit = Config.INSTANCE.dailyShopCommonLimit.get();
        int slots = Math.max(1, Math.min(12, data.shopSlotCount));
        for (int i = 0; i < slots; i++) {
            List<Rarity> usable = new ArrayList<>();
            for (Rarity rarity : available) {
                if (rarity == Rarity.COMMON && commonLimit > 0
                        && usedByRarity.getOrDefault(rarity, 0) >= commonLimit) {
                    continue;
                }
                usable.add(rarity);
            }
            // 理论上 available 里至少有一个可用档位；全满时退回原集合，保证槽位仍然填满。
            Rarity rarity = rollRarity(random, usable.isEmpty() ? available : usable, data.shopWeights);
            List<ConfiguredOffer> candidates = new ArrayList<>();
            for (ConfiguredOffer offer : data.shopPool) {
                if (offer.rarity == rarity && !offer.stack.isEmpty()) {
                    candidates.add(offer);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            List<ConfiguredOffer> fresh = new ArrayList<>();
            for (ConfiguredOffer offer : candidates) {
                if (!chosen.contains(offer.stack.getItem())) {
                    fresh.add(offer);
                }
            }
            ConfiguredOffer picked = pick(random, fresh.isEmpty() ? candidates : fresh);
            if (picked == null) {
                continue;
            }
            chosen.add(picked.stack.getItem());
            usedByRarity.merge(picked.rarity, 1, Integer::sum);
            DailyShopEntry entry = new DailyShopEntry(picked.stack.copy(), picked.price, picked.rarity);
            // 需求 7/11：每个物品各自的购买上限来自管理员配置的数量。
            entry.limit = Math.max(1, picked.amountOr(picked.rarity.purchaseLimit()));
            account.dailyShop.add(entry);
        }
        // 需求 8：保底品质不足时补足（优先替换普通项）。
        applyShopGuarantee(random, account.dailyShop, data.shopPool, data.shopGuarantee);
    }

    /** 按指定权重（传说/史诗/稀有/普通）抽品质；权重全为 0 时退回配置权重。 */
    private static Rarity rollRarity(RandomSource random, List<Rarity> available, int[] weights) {
        int total = 0;
        for (Rarity rarity : available) {
            total += Math.max(0, weightOf(rarity, weights));
        }
        if (total <= 0) {
            return rollRarity(random, available);
        }
        int roll = random.nextInt(total);
        for (Rarity rarity : available) {
            roll -= Math.max(0, weightOf(rarity, weights));
            if (roll < 0) {
                return rarity;
            }
        }
        return available.get(0);
    }

    private static int weightOf(Rarity rarity, int[] weights) {
        if (weights == null || weights.length < 4) {
            return 1;
        }
        return switch (rarity) {
            case LEGENDARY -> weights[0];
            case EPIC -> weights[1];
            case RARE -> weights[2];
            case COMMON -> weights[3];
        };
    }

    /** 每日收购用：池子里出现过的品质列表。 */
    private static List<Rarity> raritiesOf(List<ConfiguredOffer> pool) {
        List<Rarity> list = new ArrayList<>();
        for (ConfiguredOffer offer : pool) {
            if (!list.contains(offer.rarity)) {
                list.add(offer.rarity);
            }
        }
        return list;
    }

    private static DailyShopEntry shopEntryOf(ConfiguredOffer picked) {
        DailyShopEntry entry = new DailyShopEntry(picked.stack.copy(), picked.price, picked.rarity);
        entry.limit = Math.max(1, picked.amountOr(picked.rarity.purchaseLimit()));
        return entry;
    }

    /** 需求 8 保底：把不足的品质补足（优先替换普通项，其次追加，最多 12 张）。 */
    private static void applyShopGuarantee(RandomSource random, List<DailyShopEntry> entries,
            List<ConfiguredOffer> pool, int[] guarantee) {
        Rarity[] order = {Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY};
        for (int i = 0; i < order.length && i < guarantee.length; i++) {
            int need = Math.max(0, guarantee[i]);
            if (need <= 0) {
                continue;
            }
            Rarity rarity = order[i];
            long have = 0L;
            for (DailyShopEntry entry : entries) {
                if (entry.rarity == rarity) {
                    have++;
                }
            }
            while (have < need) {
                List<ConfiguredOffer> candidates = new ArrayList<>();
                for (ConfiguredOffer offer : pool) {
                    if (offer.rarity == rarity && !offer.stack.isEmpty()) {
                        candidates.add(offer);
                    }
                }
                if (candidates.isEmpty()) {
                    break;
                }
                ConfiguredOffer picked = pick(random, candidates);
                if (picked == null) {
                    break;
                }
                boolean replaced = false;
                for (int k = entries.size() - 1; k >= 0; k--) {
                    if (entries.get(k).rarity == Rarity.COMMON) {
                        entries.set(k, shopEntryOf(picked));
                        replaced = true;
                        break;
                    }
                }
                if (!replaced && entries.size() < 12) {
                    entries.add(shopEntryOf(picked));
                }
                have++;
            }
        }
    }

    private static void generateDailyBuyback(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        if (data.buybackPool.isEmpty()) {
            return;
        }
        List<ConfiguredOffer> pool = new ArrayList<>(data.buybackPool);
        RandomSource random = player.getRandom();
        // 每日最多随机 6 项收购需求：界面只渲染 6 行（没有翻页），生成端也按 6 夹取。
        int slots = Math.min(12, Math.min(Math.max(1, data.buybackSlotCount), pool.size()));
        for (int i = 0; i < slots; i++) {
            // 需求 8：先按品质权重抽一个品质，再在该品质里随机取条目。
            List<Rarity> buybackRarities = raritiesOf(pool);
            Rarity want = rollRarity(random, buybackRarities.isEmpty() ? List.of(Rarity.COMMON) : buybackRarities,
                    data.buybackWeights);
            List<ConfiguredOffer> ofRarity = new ArrayList<>();
            for (ConfiguredOffer offer : pool) {
                if (offer.rarity == want) {
                    ofRarity.add(offer);
                }
            }
            ConfiguredOffer picked = pick(random, ofRarity.isEmpty() ? pool : ofRarity);
            if (picked == null) {
                break;
            }
            pool.remove(picked);
            DailyBuybackEntry entry = new DailyBuybackEntry(picked.stack.copy(), picked.price);
            // 需求 6/9：收购品质与可收购数量来自管理员配置。
            entry.rarity = picked.rarity;
            entry.limit = Math.max(1, picked.amountOr(Config.INSTANCE.buybackCapacity.get()));
            account.dailyBuyback.add(entry);
        }
    }

    private static boolean hasRarity(List<ConfiguredOffer> pool, Rarity rarity) {
        for (ConfiguredOffer offer : pool) {
            if (offer.rarity == rarity && !offer.stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Rarity rollRarity(RandomSource random, List<Rarity> available) {
        int total = 0;
        for (Rarity rarity : available) {
            total += Math.max(0, rarity.weight());
        }
        if (total <= 0) {
            return available.get(0);
        }
        int roll = random.nextInt(total);
        int cursor = 0;
        for (Rarity rarity : available) {
            cursor += Math.max(0, rarity.weight());
            if (roll < cursor) {
                return rarity;
            }
        }
        return available.get(available.size() - 1);
    }

    @Nullable
    private static ConfiguredOffer pick(RandomSource random, List<ConfiguredOffer> list) {
        if (list.isEmpty()) {
            return null;
        }
        return list.get(random.nextInt(list.size()));
    }

    /** 全局每日清理：摊位到期，未售出物品经邮箱退还。 */
    /**
     * 第七轮：按单封邮件的到期时间清理（未领取也删，与鸣潮邮箱一致）。
     * <p>
     * 全局兜底 {@link Config#mailExpireDays} 仍由旧逻辑处理「已领取/已查看」的邮件，互不冲突。
     */
    public static void purgeExpiredMails(MinecraftServer server) {
        ShopSavedData data = data(server);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (PlayerAccount account : data.accounts().values()) {
            if (account.mailbox.isEmpty()) {
                continue;
            }
            int before = account.mailbox.size();
            account.mailbox.removeIf(mail -> mail.expireAt > 0L && mail.expireAt <= now && !mail.starred);
            if (account.mailbox.size() != before) {
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
        }
    }

    /**
     * 第七轮：用模板草稿发信（标题/正文/类型/到期/星标 + 附件一并入库）。
     * 旧调用点继续走 addMail/addMoneyMail，行为不变。
     */
    public static void addMailDraft(MinecraftServer server, @Nullable UUID owner, MailTemplates.DraftMail draft) {
        if (server == null || owner == null || draft == null || !draft.valid()) {
            return;
        }
        HolderLookup.Provider regs = server.registryAccess();
        long receivedDay = System.currentTimeMillis() / 86_400_000L;
        // 第八轮：一次发放只发**一封**邮件（所有物品装在同一封里），并在 storeMail 里与同渠道未领取的邮件继续合并，
        // 避免"一件物品一封邮件"把邮箱塞满。
        List<ItemStack> draftItems = draft.items();
        if (!draftItems.isEmpty()) {
            MailEntry entry = MailEntry.item(draftItems.get(0).copy(), draft.source(), draft.sender(), null, receivedDay);
            for (int i = 1; i < draftItems.size(); i++) {
                entry.addStack(draftItems.get(i).copy());
            }
            applyDraft(entry, draft, regs);
            storeMail(server, owner, entry);
            // 事件：邮件已创建（成功后发布；监听器异常不影响发信）
            cn.autoforged.server_shop_mod_1789689358.api.event.ApiEvents.post(
                    new cn.autoforged.server_shop_mod_1789689358.api.event.MailCreatedEvent(
                            owner, draft.source(), draft.money(),
                            draft.items() == null ? 0 : draft.items().size(), draft.title()));
        }
        if (draft.money() > 0L) {
            MailEntry entry = MailEntry.money(draft.money(), draft.moneyDisplayStack(), draft.source(),
                    draft.sender(), null, receivedDay);
            applyDraft(entry, draft, regs);
            storeMail(server, owner, entry);
            // 事件：邮件已创建（成功后发布；监听器异常不影响发信）
            cn.autoforged.server_shop_mod_1789689358.api.event.ApiEvents.post(
                    new cn.autoforged.server_shop_mod_1789689358.api.event.MailCreatedEvent(
                            owner, draft.source(), draft.money(),
                            draft.items() == null ? 0 : draft.items().size(), draft.title()));
        }
    }

    private static void applyDraft(MailEntry entry, MailTemplates.DraftMail draft, HolderLookup.Provider regs) {
        entry.title = draft.title();
        entry.content = draft.content();
        entry.kind = draft.kind().name().toLowerCase(java.util.Locale.ROOT);
        entry.expireAt = draft.expireAt();
        entry.starred = draft.starred();
    }

    /**
     * 第八轮：同一渠道的邮件合并 —— 来源 / 标题 / 正文 / 类型 / 业务类型完全相同、
     * 且还**没领取**的邮件，新的物品或金额直接并进那一封，不再新开一封。
     *
     * @return 被合并进去的那封邮件；没有可合并对象时返回 null。
     */
    @Nullable
    private static MailEntry mergeInto(PlayerAccount account, MailEntry entry) {
        if (entry.type == MailType.ITEM && !entry.hasItems()) {
            return null;
        }
        if (entry.type == MailType.MONEY && entry.currencyAmount <= 0L) {
            return null;
        }
        for (MailEntry mail : account.mailbox) {
            if (mail.type != entry.type || !sameText(mail.source, entry.source) || !sameText(mail.kind, entry.kind)
                    || !sameText(mail.title, entry.title) || !sameText(mail.content, entry.content)) {
                continue;
            }
            boolean settled = mail.type == MailType.MONEY ? mail.isViewed : mail.isClaimed;
            if (settled) {
                continue;
            }
            if (mail.type == MailType.ITEM) {
                for (ItemStack stack : entry.itemStacks()) {
                    mail.addStack(stack.copy());
                }
            } else {
                long sum = mail.currencyAmount + entry.currencyAmount;
                mail.currencyAmount = sum < 0L ? Long.MAX_VALUE : sum;
            }
            return mail;
        }
        return null;
    }

    private static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void storeMail(MinecraftServer server, UUID owner, MailEntry entry) {
        PlayerAccount account = data(server).account(owner);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        // 同一渠道且未领取 → 合并进已有邮件（合并不占新格子，所以放在容量检查之前）。
        MailEntry merged = mergeInto(account, entry);
        if (merged != null) {
            data(server).setDirty();
            if (online != null) {
                notifyPlayer(online, Component.translatable("msg.server_shop_mod.mail_merged", merged.displayName())
                        .withStyle(ChatFormatting.GREEN));
                sendSnapshot(online, "mail");
            }
            return;
        }
        int cap = Config.INSTANCE.maxMailboxSize.get();
        if (account.mailbox.size() >= cap && !ensureMailRoom(server, account, online)) {
            // 邮箱满且没有已读邮件可清理：金额直接到账（装不下的留在邮箱），物品塞背包、再不行掉落。
            if (entry.type == MailType.MONEY) {
                long credited = credit(account, entry.currencyAmount, Config.maxBalanceLimit());
                long rest = entry.currencyAmount - credited;
                if (rest > 0L) {
                    entry.currencyAmount = rest;
                    account.mailbox.add(entry);
                }
            } else if (online != null) {
                for (ItemStack stack : entry.itemStacks()) {
                    ItemStack remaining = stack.copy();
                    online.getInventory().add(remaining);
                    if (!remaining.isEmpty()) {
                        online.drop(remaining, false);
                    }
                }
            }
            data(server).setDirty();
            return;
        }
        account.mailbox.add(entry);
        data(server).setDirty();
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.server_shop_mod.mail_received", entry.displayName()));
            sendSnapshot(online, "mail");
        }
    }

    /**
     * 第七轮：邮箱满时先腾位置 —— 自动删除「最早的一封已读邮件」（金额看 isViewed、物品看 isClaimed）。
     * 返回 true 表示已有空间（或本来就够）。没有任何已读邮件时返回 false，并提示玩家。
     */
    private static boolean ensureMailRoom(MinecraftServer server, PlayerAccount account, ServerPlayer online) {
        int cap = Config.INSTANCE.maxMailboxSize.get();
        if (account.mailbox.size() < cap) {
            return true;
        }
        MailEntry oldest = null;
        for (MailEntry mail : account.mailbox) {
            boolean read = mail.type == MailType.MONEY ? mail.isViewed : mail.isClaimed;
            // 星标邮件不参与自动清理（玩家手动标记的重要邮件优先保留）。
            if (!read || mail.starred) {
                continue;
            }
            if (oldest == null || mail.sentAt < oldest.sentAt) {
                oldest = mail;
            }
        }
        if (oldest == null) {
            if (online != null) {
                notifyPlayer(online, Component.translatable("msg.server_shop_mod.mailbox_full_no_read", cap)
                        .withStyle(ChatFormatting.RED));
            }
            return false;
        }
        account.mailbox.remove(oldest);
        data(server).setDirty();
        if (online != null) {
            String name = oldest.displayName();
            notifyPlayer(online, Component.translatable("msg.server_shop_mod.mailbox_full_auto_delete", name)
                    .withStyle(ChatFormatting.YELLOW));
            sendSnapshot(online, "mail");
        }
        return true;
    }

    /** 第七轮：按单封邮件的到期时间清理（占位注释，保留方法顺序便于阅读）。 */
    public static void tickDaily(MinecraftServer server) {
        purgeExpiredMails(server);
        ShopSavedData data = data(server);
        long day = currentDay(server.overworld());
        if (data.lastStallClearDay == Long.MIN_VALUE) {
            data.lastStallClearDay = day;
            return;
        }
        if (day == data.lastStallClearDay) {
            return;
        }
        data.lastStallClearDay = day;
        int expireDays = Config.INSTANCE.mailExpireDays.get();
        for (Map.Entry<UUID, PlayerAccount> entry : new ArrayList<>(data.accounts().entrySet())) {
            PlayerAccount account = entry.getValue();
            if (!account.stall.isEmpty()) {
                for (StallEntry stall : account.stall) {
                    // 需求 6：到期清摊时退还全部剩余库存，而不是 1 件。
                    if (stall.stock > 0) {
                        ItemStack refund = stall.stack.copy();
                        refund.setCount(stall.stock);
                        addMailDraft(server, entry.getKey(), MailTemplates.build(MailTemplates.Template.STALL_EXPIRED,
                        MailTemplates.map("player", nameOf(server, entry.getKey()),
                                "item", MailTemplates.itemText(refund, false)))
                .source(SOURCE_STALL_UNSOLD)
                .addItem(refund));
                    }
                }
                account.stall.clear();
            }
            if (expireDays > 0) {
                // 只清理“已经领取/查看过”的邮件：未领取的物品、未查看的货款不能因为过期直接蒸发。
                // receivedDay<=0 表示旧存档缺这个字段，按“刚收到”处理，避免一开过期功能就清空历史邮件。
                account.mailbox.removeIf(mail -> !mail.pending()
                        && mail.receivedDay > 0
                        && day - mail.receivedDay >= expireDays);
            }
        }
        data.setDirty();
    }

    /** 拍卖结算。 */
    public static void tickAuctions(MinecraftServer server) {
        ShopSavedData data = data(server);
        if (data.auctions.isEmpty()) {
            return;
        }
        // 现实时间：拍卖到期时间以毫秒墙钟为准
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<AuctionEntry> iterator = data.auctions.iterator();
        while (iterator.hasNext()) {
            AuctionEntry auction = iterator.next();
            if (now < auction.endTime) {
                continue;
            }
            iterator.remove();
            changed = true;
            if (auction.hasBid()) {
                if (auction.stack.isEmpty()) {
                    // 存档里的物品解析失败（例如物品所属模组被移除）：不能成交，把买家的出价退回。
                    PlayerAccount winnerAccount = data.account(auction.topBidder);
                    long refundedAmount = credit(winnerAccount, auction.currentBid,
                            Config.maxBalanceLimit());
                    if (refundedAmount < auction.currentBid) {
                        addMoneyMail(server, auction.topBidder, auction.currentBid - refundedAmount,
                                ItemStack.EMPTY, SOURCE_AUCTION_REFUND, "系统", null);
                    }
                    TransactionLog.record(server, auction.topBidder, TransactionLog.AUCTION_REFUND, "拍卖",
                            refundedAmount, winnerAccount.balance, "拍卖物品缺失，已退款");
                    ServerPlayer refundedPlayer = server.getPlayerList().getPlayer(auction.topBidder);
                    if (refundedPlayer != null) {
                        notifyPlayer(refundedPlayer, Component.translatable("msg.server_shop_mod.auction_broken_refund",
                                auction.currentBid).withStyle(ChatFormatting.GOLD));
                    }
                    data.setDirty();
                    continue;
                }
                addMailDraft(server, auction.topBidder, MailTemplates.build(MailTemplates.Template.AUCTION_WON,
                        MailTemplates.map("player", nameOf(server, auction.topBidder),
                                "item", MailTemplates.itemText(auction.stack, false),
                                "price", Long.toString(auction.currentBid)))
                .source(SOURCE_AUCTION_WON)
                .addItem(auction.stack));
                // 需求：拍卖成交所得以金额类邮件发放给卖家，玩家在邮箱中查看领取。
                long fee = auctionFee(auction.currentBid);
                long net = auction.currentBid - fee;
                addMailDraft(server, auction.seller, MailTemplates.build(MailTemplates.Template.AUCTION_SOLD,
                            MailTemplates.map("player", nameOf(server, auction.seller),
                                    "item", MailTemplates.itemText(auction.stack, false),
                                    "price", Long.toString(auction.currentBid),
                                    "fee", Long.toString(Math.max(0L, auction.currentBid - net)),
                                    "income", Long.toString(net)))
                    .source(SOURCE_AUCTION_SOLD)
                    .sender(auction.topBidderName)
                    .money(net)
                    .moneyDisplay(auction.stack));
                data.setDirty();
                // 流水：买家拿到物品（钱在出价时已扣），卖家货款进邮箱。
                TransactionLog.record(server, auction.topBidder, TransactionLog.AUCTION_WIN, "拍卖", 0L,
                        balanceOf(data, auction.topBidder), auction.stack.getHoverName().getString());
                TransactionLog.record(server, auction.seller, TransactionLog.AUCTION_SELL,
                        auction.topBidderName == null ? "" : auction.topBidderName, net,
                        balanceOf(data, auction.seller),
                        auction.stack.getHoverName().getString() + "（货款存邮箱）");
                // 需求 10：卖家物品卖出、买家竞拍成功都要有提示红点（邮件红点由 addMail 负责）。
                NotificationManager.notify(server, auction.seller, NotificationType.AUCTION);
                NotificationManager.notify(server, auction.topBidder, NotificationType.AUCTION);
                ServerPlayer sellerPlayer = server.getPlayerList().getPlayer(auction.seller);
                if (sellerPlayer != null) {
                    notifyPlayer(sellerPlayer, Component.translatable("msg.server_shop_mod.auction_sold_fee",
                            auction.stack.getHoverName(), auction.currentBid, fee, net)
                            .withStyle(ChatFormatting.GREEN));
                }
                ServerPlayer winner = server.getPlayerList().getPlayer(auction.topBidder);
                if (winner != null) {
                    notifyPlayer(winner, Component.translatable("msg.server_shop_mod.auction_won",
                            auction.stack.getHoverName()).withStyle(ChatFormatting.GREEN));
                }
            } else {
                addMailDraft(server, auction.seller, MailTemplates.build(MailTemplates.Template.AUCTION_UNSOLD,
                        MailTemplates.map("player", nameOf(server, auction.seller),
                                "item", MailTemplates.itemText(auction.stack, false)))
                .source(SOURCE_AUCTION_UNSOLD)
                .addItem(auction.stack));
                NotificationManager.notify(server, auction.seller, NotificationType.AUCTION);
                ServerPlayer sellerPlayer = server.getPlayerList().getPlayer(auction.seller);
                if (sellerPlayer != null) {
                    notifyPlayer(sellerPlayer, Component.translatable("msg.server_shop_mod.auction_expired",
                            auction.stack.getHoverName()).withStyle(ChatFormatting.YELLOW));
                }
            }
        }
        if (changed) {
            data.setDirty();
        }
    }

    // =========================================================== 快照与输入界面

    public static void sendSnapshot(ServerPlayer player, @Nullable String tab) {
        // ⚠ 默认**不自动打开界面**：以前是"客户端没开界面就弹出来"，于是关掉商店后，
        // 任何一次数据推送（午夜刷新、邮件合并、自动签到发奖…）都会把界面又弹回来，玩家会以为"关不掉"。
        sendSnapshot(player, tab, false, false);
    }

    /** 只有**玩家自己主动请求**打开界面时才用它（/shop 等指令）——会真的把界面弹出来。 */
    public static void sendSnapshotOpen(ServerPlayer player, @Nullable String tab) {
        sendSnapshot(player, tab, false, true);
    }

    /**
     * 下发快照。silent=true 时客户端只更新缓存；open=true 才会在客户端没开界面时自动弹出商店。
     */
    public static void sendSnapshot(ServerPlayer player, @Nullable String tab, boolean silent) {
        sendSnapshot(player, tab, silent, false);
    }

    private static void sendSnapshot(ServerPlayer player, @Nullable String tab, boolean silent, boolean open) {
        ConfigMigration.runOnce();
        logBalanceCapOnce();
        PlayerAccount account = account(player);
        ShopSavedData data = data(player.server);
        ensureDaily(player, account, data);
        // 需求 3：价格始终以服务端商品池为权威，下发前用最新池数据刷新每日报价。
        refreshDailyPrices(account, data);
        HolderLookup.Provider regs = player.level().registryAccess();

        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "snapshot");
        tag.putBoolean("silent", silent);
        // open=true 才允许客户端"自动弹出商店界面"（默认 false：只更新数据，绝不再抢夺玩家的界面）
        tag.putBoolean("open", open);
        tag.putLong("balance", account.balance);
        // 第九项：把余额上限也下发，客户端余额到顶时可显示「已满」。
        tag.putLong("maxBalance", Config.maxBalanceForClient());
        tag.putString("currency", currencyName());
        tag.putString("symbol", Config.INSTANCE.currencySymbol.get());
        // 需求 6：下发统一金钱后缀（如“星币”），客户端所有金额显示都用它。
        tag.putString("moneySuffix", currencyName());
        boolean snapshotAdmin = player.hasPermissions(2);
        tag.putBoolean("canAdmin", snapshotAdmin);
        // 需求 11：下发服务端当前记录的编辑模块，界面据此高亮“每日商店/每日收购”并显示当前模式。
        tag.putString("adminEditType",
                ADMIN_EDIT_TYPE.getOrDefault(player.getUUID(), AdminEditType.DAILY_SHOP).id());
        tag.putString("defaultTab", tab == null ? "daily" : tab);
        // 主界面左上角需要显示玩家名称
        tag.putString("playerName", player.getGameProfile().getName());
        // 需求 4：交易界面需要展示在线玩家名片
        ListTag online = new ListTag();
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            CompoundTag c = new CompoundTag();
            c.putString("uuid", other.getUUID().toString());
            c.putString("name", other.getGameProfile().getName());
            online.add(c);
        }
        tag.put("onlinePlayers", online);

        ListTag daily = new ListTag();
        for (DailyShopEntry entry : account.dailyShop) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            c.putLong("price", entry.price);
            c.putString("rarity", entry.rarity.id());
            c.putInt("bought", entry.bought);
            // 需求 11：上限按物品下发，不再使用品质公用上限。
            c.putInt("limit", entry.limit);
            daily.add(c);
        }
        tag.put("dailyShop", daily);

        // 付费刷新每日商店：refreshCost = -1 表示今日次数已用完（按钮变灰）。
        tag.putInt("refreshUsed", account.dailyShopRefreshUsed);
        tag.putInt("refreshMax", Config.INSTANCE.dailyRefreshCosts.get().size());
        tag.putLong("refreshCost", nextRefreshCost(account, true));
        // 付费刷新每日收购：与商店**完全分开**的次数与价格。
        tag.putInt("buybackRefreshUsed", account.dailyBuybackRefreshUsed);
        tag.putInt("buybackRefreshMax", Config.INSTANCE.buybackRefreshCosts.get().size());
        tag.putLong("buybackRefreshCost", nextRefreshCost(account, false));

        int[] flipped = new int[account.dailyFlipped.size()];
        for (int i = 0; i < flipped.length; i++) {
            flipped[i] = account.dailyFlipped.get(i);
        }
        tag.putIntArray("dailyFlipped", flipped);

        ListTag buyback = new ListTag();
        for (DailyBuybackEntry entry : account.dailyBuyback) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            c.putLong("price", entry.price);
            c.putString("rarity", entry.rarity.id());
            c.putInt("sold", entry.sold);
            // 需求 6/9：按物品下发可收购数量。
            c.putInt("limit", entry.limit);
            buyback.add(c);
        }
        tag.put("dailyBuyback", buyback);

        // 收购全清奖励：界面据此显示「全部完成可再得 X星币（还剩 N 项）」。
        tag.putLong("buybackBonus", buybackBonusAmount(data));
        tag.putBoolean("buybackBonusEnabled", data.buybackBonusEnabled);
        tag.putBoolean("buybackBonusDone", account.dailyBuybackBonusDone);
        int buybackLeft = 0;
        for (DailyBuybackEntry entry : account.dailyBuyback) {
            if (entry.remaining() > 0) {
                buybackLeft++;
            }
        }
        tag.putInt("buybackLeft", buybackLeft);

        ListTag auctions = new ListTag();
        long now = System.currentTimeMillis();
        for (AuctionEntry auction : data.auctions) {
            CompoundTag c = ShopNbt.saveStack(auction.stack, regs);
            c.putLong("id", auction.id);
            c.putString("seller", auction.sellerName);
            c.putLong("startPrice", auction.startPrice);
            c.putLong("currentBid", auction.currentBid);
            // 需求 3：下发上一次出价，竞拍界面把它划掉展示（0 表示尚无人出价，显示起拍价）。
            c.putLong("lastBid", auction.lastBid);
            c.putString("topBidder", auction.topBidderName);
            c.putLong("remaining", Math.max(0, auction.endTime - now));
            c.putBoolean("own", auction.seller != null && auction.seller.equals(player.getUUID()));
            // 让界面能显示“最高：你”，玩家一眼看出自己的出价是否还在冻结中。
            c.putBoolean("topMe", auction.topBidder != null && auction.topBidder.equals(player.getUUID()));
            auctions.add(c);
        }
        tag.put("auctions", auctions);

        ListTag stall = new ListTag();
        for (StallEntry entry : account.stall) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            c.putLong("price", entry.price);
            // 需求 6：管理界面需要显示剩余库存/已售空。
            c.putInt("stock", entry.stock);
            // 需求 9：按商品统计的累计已售出件数。
            c.putInt("sold", entry.sold);
            stall.add(c);
        }
        tag.put("stall", stall);

        ListTag stallPlayers = new ListTag();
        List<Map.Entry<UUID, PlayerAccount>> stalls = new ArrayList<>();
        for (Map.Entry<UUID, PlayerAccount> entry : data.accounts().entrySet()) {
            if (!entry.getValue().stall.isEmpty()) {
                stalls.add(entry);
            }
        }
        stalls.sort(Comparator.comparingLong((Map.Entry<UUID, PlayerAccount> e) -> e.getValue().stall.get(0).order));
        for (Map.Entry<UUID, PlayerAccount> entry : stalls) {
            CompoundTag c = new CompoundTag();
            c.putString("uuid", entry.getKey().toString());
            c.putString("name", entry.getValue().name.isEmpty() ? "Unknown" : entry.getValue().name);
            c.putInt("count", entry.getValue().stall.size());
            stallPlayers.add(c);
        }
        tag.put("stallPlayers", stallPlayers);

        ListTag mail = new ListTag();
        // 需求：按时间戳倒序，最新的邮件排在最前。
        List<MailEntry> mails = new ArrayList<>(account.mailbox);
        mails.sort(Comparator.comparingLong((MailEntry m) -> m.sentAt).reversed());
        for (MailEntry entry : mails) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            c.putString("mailId", entry.mailId);
            c.putString("type", entry.type.id());
            c.putString("source", entry.source);
            // 需求 5：邮件需要显示发送者与时间戳。
            c.putString("sender", entry.sender);
            if (entry.senderUUID != null) {
                c.putUUID("senderUUID", entry.senderUUID);
            }
            c.putLong("currencyAmount", entry.currencyAmount);
            c.putBoolean("isClaimed", entry.isClaimed);
            c.putBoolean("isViewed", entry.isViewed);
            c.putLong("sentAt", entry.sentAt);
            c.putString("title", entry.title);
            c.putString("content", entry.content);
            c.putString("kind", entry.kind);
            c.putLong("expireAt", entry.expireAt);
            c.putBoolean("starred", entry.starred);
            // 第八轮：附件列表必须一起下发，否则客户端只看到第一件（旧的手写序列化漏了它）。
            ListTag stackList = new ListTag();
            for (ItemStack stack : entry.stacks) {
                if (stack != null && !stack.isEmpty()) {
                    stackList.add(ShopNbt.saveStack(stack, regs));
                }
            }
            c.put("stacks", stackList);
            mail.add(c);
        }
        tag.put("mail", mail);

        // 管理端列表只有管理员需要 —— 普通玩家不发（管理页他们打不开），
        // 顺带把快照体积砍掉一大半（购物城整池物品在 mall 与 adminMall 里本来是重复下发的）。
        if (snapshotAdmin) {
            tag.put("adminShop", offerList(data.shopPool, regs));
            tag.put("adminBuyback", offerList(data.buybackPool, regs));
        }

        // ---- 购物城（新增模块）：常驻货架 + 管理端同结构列表 + 最近购买记录 ----
        ListTag mallList = new ListTag();
        for (int i = 0; i < data.mallPool.size(); i++) {
            MallItem item = data.mallPool.get(i);
            CompoundTag c = item.save(regs);
            c.putInt("index", i);
            mallList.add(c);
        }
        tag.put("mall", mallList);
        // 管理页复用 ConfiguredOffer 的结构（amount 当库存用，-1 = 无限），前端一套行渲染即可。
        if (snapshotAdmin) {
            ListTag adminMallList = new ListTag();
            for (MallItem item : data.mallPool) {
                CompoundTag c = ShopNbt.saveStack(item.stack, regs);
                c.putLong("price", item.price);
                c.putString("rarity", item.rarity.id());
                c.putInt("amount", item.stock);
                adminMallList.add(c);
            }
            tag.put("adminMall", adminMallList);
        }
        // 管理页的「出货箱」模块：把**兑换规则**按条目下发（代表物品 + 单价 + 每日份数），
        // 格式错误的那几行也在列表里（物品为空 + 原文与原因），界面把它们标红，右键即可删掉。
        ListTag adminShipList = new ListTag();
        ListTag adminShipRaw = new ListTag();
        ListTag adminShipErrors = new ListTag();
        List<ShippingRules.Entry> shipEntries = ShippingRules.entries();
        List<ItemStack> shipStacks = ShippingRules.ruleStacks();
        for (int i = 0; i < shipEntries.size(); i++) {
            ShippingRules.Entry entry = shipEntries.get(i);
            ItemStack stack = i < shipStacks.size() ? shipStacks.get(i) : ItemStack.EMPTY;
            CompoundTag c = ShopNbt.saveStack(stack, regs);
            c.putLong("price", entry.rule() == null ? 0L : entry.rule().price());
            c.putString("rarity", Rarity.COMMON.id());
            c.putInt("amount", entry.rule() == null ? 0 : entry.rule().dailyMax());
            c.putString("rule", entry.raw());
            adminShipList.add(c);
            adminShipRaw.add(StringTag.valueOf(entry.raw()));
            adminShipErrors.add(StringTag.valueOf(entry.error() == null ? "" : entry.error()));
        }
        tag.put("adminShipping", adminShipList);
        tag.put("adminShippingRaw", adminShipRaw);
        tag.put("adminShippingErrors", adminShipErrors);
        // 最近购买：最新在前，最多下发 MALL_RECENT_SEND 条（存档最多留 200 条）。
        ListTag recentList = new ListTag();
        int from = Math.max(0, data.mallRecent.size() - MALL_RECENT_SEND);
        for (int i = data.mallRecent.size() - 1; i >= from; i--) {
            recentList.add(data.mallRecent.get(i).save(regs));
        }
        tag.put("mallRecent", recentList);

        // ---- 出货箱（新增模块）：等级 / 格数 / 本次结算金额 / 今日额度 / 规则状态 ----
        sendShippingInfo(player, tag, data);

        // 货币兑换：所有玩家都能看到汇率、图标与自己的持有量，仅管理员能改配置。
        ListTag exchange = new ListTag();
        for (ExchangeEntry entry : data.exchangePool) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            c.putLong("value", entry.value);
            c.putInt("owned", countMatching(player, entry.stack));
            exchange.add(c);
        }
        tag.put("exchange", exchange);

        // 签到（需求 7）：玩家自己的签到状态 + 全局设置（界面据此画进度条/日历）。
        SignInData sign = data.signIn(player.getUUID());
        long realDay = realDay();
        if (sign.signedDays.isEmpty() && sign.lastSignDay <= 0L) {
            sign.lastSignDay = 0L;
        }
        sign.trim();
        tag.putBoolean("signInEnabled", data.signInEnabled);
        tag.putBoolean("signInAuto", data.signInAuto);
        tag.putBoolean("signInCumulative", data.signInCumulative);
        tag.putLong("signInBase", data.signInBaseDaily);
        tag.putLong("signInMakeupCost", data.signInMakeupCost);
        tag.putInt("signInMakeupDays", data.signInMakeupDays);
        tag.putLong("signInToday", realDay);
        tag.putBoolean("signInDone", sign.signedOn(realDay));
        tag.putInt("signInStreak", sign.streak);
        tag.putInt("signInBest", sign.bestStreak);
        tag.putLongArray("signInDays", sign.recentDays(realDay));
        tag.putIntArray("signInTierDays", data.signInTierDays);
        tag.putLongArray("signInTierBonus", data.signInTierBonus);
        tag.putIntArray("signInMileDays", data.signInMileDays);
        tag.putLongArray("signInMileMoney", data.signInMileMoney);
        ListTag actList = new ListTag();
        for (ActivityDay act : data.activityDays.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("day", act.day);
            c.putString("title", act.title);
            c.putString("note", act.note);
            c.putLong("money", act.money);
            c.putBoolean("forOffline", act.forOffline);
            c.putBoolean("granted", act.granted.contains(player.getUUID()));
            c.putBoolean("catchUp", act.catchUp);
            // 第八轮：奖励物品改成列表，item 字段直接下发「全部奖励物品」的文本（界面显示用），
            // itemIds 下发可解析的 id 列表，客户端用来画物品图标。
            c.putString("item", MailTemplates.itemsText(act.rewardStacks()));
            c.putString("itemIds", rewardIdsText(act.rewardStacks()));
            c.putInt("color", act.color);
            c.putInt("hour", act.hour);
            c.putString("gift", "");
            actList.add(c);
        }
        tag.put("activityDays", actList);
        // 侧栏布局：管理员拖过之后的所有玩家都用这套坐标（服务端权威 + 热同步）。
        tag.put("sidebarLayout", data.sidebarLayout.save());
        // 需求 8：下发刷新参数（管理员设置界面据此回填）。
        tag.putIntArray("shopWeights", data.shopWeights);
        tag.putIntArray("shopGuarantee", data.shopGuarantee);
        tag.putInt("shopSlotCount", data.shopSlotCount);
        tag.putIntArray("buybackWeights", data.buybackWeights);
        tag.putIntArray("buybackGuarantee", data.buybackGuarantee);
        tag.putInt("buybackSlotCount", data.buybackSlotCount);
        // 购物城定时补货设置（管理页「补货设置」弹窗据此回填；普通玩家用来显示"每日补货"字样）。
        tag.putBoolean("mallRestockOn", Config.mallRestockEnabled());
        tag.putString("mallRestockMode", Config.mallRestockGameTime() ? "game" : "real");
        tag.putString("mallRestockTime", Config.mallRestockTime());
        tag.putInt("mallRestockAmount", Config.mallRestockAmount());
        tag.putString("mallRefreshMode", Config.mallRefreshMode());
        tag.putInt("mallRefreshAmount", Config.mallRefreshAmount());
        // 摊位下架时间（客户端据此显示每个商品的剩余时间 + 管理弹窗回填）
        tag.putString("stallExpireMode", Config.stallExpireMode());
        tag.putInt("stallExpireAmount", Config.stallExpireAmount());
        // 玩家求购：配置 + 全部求购单（列表 / 详情 / 倒计时 / 管理页都用它）
        tag.putBoolean("demandEnabled", Config.demandEnabled());
        tag.putInt("demandSlots", Config.demandSlots());
        tag.putDouble("demandFeePercent", Config.demandFeePercent());
        tag.putLong("demandMaxReward", Config.demandMaxReward());
        tag.putString("demandExpireMode", Config.demandExpireMode());
        tag.putInt("demandExpireAmount", Config.demandExpireAmount());
        ListTag demandList = new ListTag();
        for (DemandEntry entry : data.demands) {
            demandList.add(entry.save(regs));
        }
        tag.put("demands", demandList);

        ClientboundShopSyncPayload.send(player, tag);
    }

    private static ListTag offerList(List<ConfiguredOffer> pool, HolderLookup.Provider regs) {
        ListTag list = new ListTag();
        for (ConfiguredOffer offer : pool) {
            CompoundTag c = ShopNbt.saveStack(offer.stack, regs);
            c.putLong("price", offer.price);
            c.putString("rarity", offer.rarity.id());
            // 需求 6/7：管理员配置界面上要能看到并修改数量/品质/价值。
            c.putInt("amount", offer.amount);
            list.add(c);
        }
        return list;
    }

    /**
     * 需求 1/3：每日商店与收购的价格、品质、数量始终跟随管理员配置的服务器商品池，
     * 管理员在界面改完（每日商店同样可自定义）后无需重启即可同步。
     */
    private static void refreshDailyPrices(PlayerAccount account, ShopSavedData data) {
        for (DailyShopEntry entry : account.dailyShop) {
            ConfiguredOffer match = findOffer(data.shopPool, entry.stack);
            if (match != null) {
                entry.price = match.price;
                entry.rarity = match.rarity;
                entry.limit = Math.max(1, match.amountOr(match.rarity.purchaseLimit()));
            }
        }
        for (DailyBuybackEntry entry : account.dailyBuyback) {
            ConfiguredOffer match = findOffer(data.buybackPool, entry.stack);
            if (match != null) {
                entry.price = match.price;
                entry.rarity = match.rarity;
                entry.limit = Math.max(1, match.amountOr(Config.INSTANCE.buybackCapacity.get()));
            }
        }
    }

    @Nullable
    private static ConfiguredOffer findOffer(List<ConfiguredOffer> pool, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (ConfiguredOffer offer : pool) {
            if (!offer.stack.isEmpty() && offer.stack.getItem() == stack.getItem()) {
                return offer;
            }
        }
        return null;
    }

    /**
     * 需求 2：管理员在本地改完商品后执行 /shop reload，即可让服务器与所有在线玩家同步，
     * 无需重启。会按最新商品池重新生成每日列表并静默推送快照（不打断玩家当前界面）。
     */
    public static void reload(MinecraftServer server) {
        ShopSavedData data = data(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerAccount account = account(player);
            account.dailyShopDay = Long.MIN_VALUE;
            account.dailyBuybackDay = Long.MIN_VALUE;
            account.dailyFlipped.clear();
        }
        data.setDirty();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSnapshot(player, "daily", true);
        }
    }

    /** 静默地把最新快照推送给所有在线玩家，保证摊位等展示数据实时同步。 */
    public static void broadcastSnapshots(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSnapshot(player, null, true);
        }
    }

    public static void openInput(ServerPlayer player, String action, String titleKey, @Nullable CompoundTag extra, String... fields) {
        openInput(player, action, titleKey, extra, null, fields);
    }

    /**
     * 打开输入界面（可带标题参数）。
     * titleArgs 会作为 {@code Component.translatable(titleKey, args...)} 的参数在客户端本地化，
     * 例如「取款数量（星币 → 银币）」里的货币名就用它传。
     */
    public static void openInput(ServerPlayer player, String action, String titleKey, @Nullable CompoundTag extra,
            @Nullable String[] titleArgs, String... fields) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "input");
        tag.putString("action", action);
        tag.putString("title", titleKey);
        ListTag fieldList = new ListTag();
        for (String field : fields) {
            fieldList.add(StringTag.valueOf(field));
        }
        tag.put("fields", fieldList);
        if (titleArgs != null && titleArgs.length > 0) {
            ListTag argList = new ListTag();
            for (String arg : titleArgs) {
                argList.add(StringTag.valueOf(arg == null ? "" : arg));
            }
            tag.put("title_args", argList);
        }
        if (extra != null) {
            tag.merge(extra);
        }
        ClientboundShopSyncPayload.send(player, tag);
    }

    private static void openDeposit(ServerPlayer player, String context) {
        openDeposit(player, context, false);
    }

    private static void openDeposit(ServerPlayer player, String context, boolean ghost) {
        DEPOSIT_CONTEXT.put(player.getUUID(), context);
        // 需求 2：交易槽位已满时锁定放入槽位，防止网络延迟下进入界面后仍能塞入物品。
        boolean locked = "trade".equals(context) && TradeManager.itemsFull(player);
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new DepositMenu(id, inventory, new SimpleContainer(1), ghost, locked),
                Component.translatable("gui.server_shop_mod.deposit")));
    }

    /**
     * 管理页「添加条目」：打开**批量**幽灵槽界面（3×9 = 最多 27 种物品）。
     * 与单件添加一样，槽里只是模板副本，关闭时会原样退还。
     */
    private static void openBatchAdd(ServerPlayer player) {
        DEPOSIT_CONTEXT.put(player.getUUID(), "admin");
        AdminEditType type = ADMIN_EDIT_TYPE.getOrDefault(player.getUUID(), AdminEditType.DAILY_SHOP);
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new BatchAddMenu(id, inventory,
                        new SimpleContainer(BatchAddMenu.GHOST_SLOTS)),
                Component.translatable("gui.server_shop_mod.batch.title",
                        Component.translatable(adminTypeNameKey(type)))));
    }

    /** 管理模块的显示名 lang key（与管理页下拉里的名字一致）。 */
    private static String adminTypeNameKey(AdminEditType type) {
        return switch (type) {
            case DAILY_SHOP -> "gui.server_shop_mod.admin_shop";
            case BUYBACK -> "gui.server_shop_mod.admin_buyback";
            case MALL -> "gui.server_shop_mod.admin_mall";
            case SHIPPING -> "gui.server_shop_mod.admin_shipping";
        };
    }

    /**
     * 批量添加第一步：把幽灵槽里的物品**复制成模板**存起来，然后关掉容器
     * （关掉时 {@link BatchAddMenu#removed} 会把原物退还），再打开统一设置价格 / 品质 / 数量的输入窗口。
     * <p>
     * 之所以先把模板存下来、而不是等保存时再读容器：打开输入窗口会换掉当前界面、容器随时可能被关掉，
     * 存成模板后就不依赖它是否还开着（与单件添加的 ADMIN_TEMPLATE 同一套思路）。
     */
    private static void adminBatchPrepare(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            return;
        }
        if (!(player.containerMenu instanceof BatchAddMenu menu)) {
            return;
        }
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < BatchAddMenu.GHOST_SLOTS; i++) {
            ItemStack ghost = menu.ghosts().getItem(i);
            if (!ghost.isEmpty()) {
                templates.add(ghost.copyWithCount(1));
            }
        }
        if (templates.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.batch_none")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        BATCH_TEMPLATE.put(player.getUUID(), templates);
        // 关容器：幽灵槽里的原物由此退回玩家背包
        player.closeContainer();
        AdminEditType type = editType(player, null);
        CompoundTag extra = new CompoundTag();
        extra.putString("mode", type.id());
        if (type == AdminEditType.SHIPPING) {
            openInput(player, "admin_batch_apply", "gui.server_shop_mod.input.shipping_rule_new", extra, "price");
        } else {
            openInput(player, "admin_batch_apply", "gui.server_shop_mod.input.admin_offer", extra,
                    "price", "rarity", "quantity");
        }
    }

    /** 批量添加的模板（第一步存下，第二步统一设置数值时取用）。 */
    private static final Map<UUID, List<ItemStack>> BATCH_TEMPLATE = new HashMap<>();

    /**
     * 批量添加第二步：把模板里的每种物品按**统一的价格 / 品质 / 数量**加进当前模块的清单。
     * 出货箱只认价格（它没有品质与数量）。
     */
    private static void adminBatchApply(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        List<ItemStack> templates = BATCH_TEMPLATE.remove(player.getUUID());
        if (templates == null || templates.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.batch_none")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        AdminEditType type = editType(player, tag);
        long price = parseAdminPrice(player, tag.getString("price"));
        if (price <= 0L) {
            return;
        }
        Rarity rarity = parseRarity(tag.getString("rarity"), price);
        int quantity = parseInt(tag.getString("quantity"));
        ShopSavedData data = data(player.server);
        List<ItemStack> shippingRules = new ArrayList<>();
        int added = 0;
        for (ItemStack template : templates) {
            if (template.isEmpty()) {
                continue;
            }
            switch (type) {
                case MALL -> {
                    MallItem item = new MallItem(template, price, rarity, parseMallStock(tag.getString("quantity")));
                    item.sort = data.mallPool.size();
                    data.mallPool.add(item);
                }
                case SHIPPING -> shippingRules.add(template);
                case DAILY_SHOP -> {
                    ConfiguredOffer offer = new ConfiguredOffer(template, price, rarity);
                    offer.amount = quantity;
                    data.shopPool.add(offer);
                }
                case BUYBACK -> {
                    ConfiguredOffer offer = new ConfiguredOffer(template, price, rarity);
                    offer.amount = quantity;
                    data.buybackPool.add(offer);
                }
            }
            added++;
        }
        if (type == AdminEditType.SHIPPING && !shippingRules.isEmpty()) {
            // 规则一次写完再落盘（避免 27 次重写配置文件）
            ShippingRules.addRules(shippingRules, price);
        }
        if (added == 0) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.batch_none")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        data.setDirty();
        sendSnapshot(player, type == AdminEditType.MALL ? "mall" : "admin");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.batch_added", added)
                .withStyle(ChatFormatting.GREEN));
    }

    // =========================================================== 动作分发

    public static void handleAction(ServerPlayer player, CompoundTag tag) {
        String action = tag.getString("action");
        // 服务端权威：管理操作必须校验权限，客户端不再可信（canAdmin 只是显示开关）。
        if (action.startsWith("admin_") && !player.hasPermissions(2)) {
            return;
        }
        switch (action) {
            case "request_data" -> {
                returnPending(player);
                sendSnapshot(player, tag.getString("tab"));
            }
            case "create_auction" -> openDeposit(player, "auction");
            case "create_stall" -> openDeposit(player, "stall");
            case "deposit_confirm" -> handleDepositConfirm(player);
            case "buy_shop" -> buyShop(player, tag.getInt("index"), parseInt(tag.getString("quantity")));
            // ---- 购物城（新增模块）----
            case "buy_mall" -> buyMall(player, tag.getInt("index"), parseInt(tag.getString("quantity")));
            case "admin_mall_import" -> adminMallImport(player, tag);
            case "admin_mall_remove_mod" -> adminMallRemoveMod(player, tag);
            case "admin_mall_restock" -> adminMallRestock(player, tag);
            // 商店设置 → 购物城：库存周期刷新（多久整店刷新一次）
            case "admin_mall_refresh" -> adminMallRefresh(player, tag);
            // ---- 出货箱（新增模块，参考星露谷售货箱）----
            case "shipping_open" -> openShippingBin(player);
            case "shipping_upgrade" -> upgradeShippingBin(player);
            case "admin_shipping_limits" -> adminShippingLimits(player, tag);
            case "flip_shop" -> flipShop(player, tag.getInt("index"));
            // 付费刷新每日商店（价格与次数见 Config.dailyRefreshCosts）。
            case "refresh_daily_shop" -> refreshDailyShopPaid(player);
            case "refresh_daily_buyback" -> refreshDailyBuybackPaid(player);
            // 账单：玩家点主界面余额打开账单界面时请求一次明细。
            case "bill_request" -> sendBill(player);
            // 侧栏布局：管理员拖拽排序后保存顺序 / 恢复默认。
            case "admin_sidebar_order" -> adminSidebarOrder(player, tag);
            case "admin_sidebar_reset" -> adminSidebarReset(player);
            // 编辑布局里右键页签 → 重命名模块（只改显示名，id 不变）
            case "admin_sidebar_rename" -> adminSidebarRename(player, tag);
            // 商店设置 → 摊位：自动下架时间（永久 / 游戏时间 / 现实时间）
            case "admin_stall_expire" -> adminStallExpire(player, tag);
            // ---- 玩家求购（悬赏）----
            case "demand_create" -> demandCreate(player, tag);
            case "demand_cancel" -> demandCancel(player, tag);
            case "demand_fulfill" -> demandFulfill(player, tag);
            // 商店设置 → 求购：基本设置 / 下架时间 / 强制撤销某一条
            case "admin_demand_set" -> adminDemandSet(player, tag);
            case "admin_demand_expire" -> adminDemandExpire(player, tag);
            case "admin_demand_cancel" -> demandCancel(player, tag);
            case "sell_buyback" -> sellBuyback(player, tag.getInt("index"), parseInt(tag.getString("quantity")));
            case "bid" -> placeBid(player, tag);
            case "claim_mail" -> claimMail(player, tag.getString("mail_id"));
            // 第七轮：星标（置顶显示）
            case "mail_star" -> toggleMailStar(player, tag.getString("mail_id"));
            // 第七轮：兑换码（玩家）
            case "redeem_code" -> redeemCode(player, tag.getString("code"));
            // 第七轮：兑换码生成器（管理员）
            case "admin_redeem_create" -> adminRedeemCreate(player, tag);
            case "admin_redeem_delete" -> adminRedeemDelete(player, tag);
            case "admin_redeem_list" -> sendRedeemList(player);
            // 第八轮：兑换码奖励配置（复用物品发放容器，结果写进服务端草稿或直接改已有兑换码）。
            case "admin_redeem_reward_open" -> adminRedeemRewardOpen(player, tag);
            case "admin_redeem_reward_save" -> adminRedeemRewardSave(player, tag);
            case "admin_redeem_reward_cancel" -> adminRedeemRewardCancel(player);
            case "admin_redeem_draft_request" -> sendRedeemDraft(player);
            case "admin_redeem_update" -> adminRedeemUpdate(player, tag);
            case "admin_redeem_claimed" -> sendRedeemClaimed(player, tag);
            case "admin_redeem_preview" -> adminRedeemPreview(player, tag);
            case "view_mail" -> viewMail(player, tag.getString("mail_id"));
            case "claim_all_mail" -> claimAllMail(player);
            case "delete_mail" -> deleteMail(player, tag.getString("mail_id"), tag.getBoolean("confirmed"));
            // 需求四：一键清理已经领取/查看过的邮件。
            case "delete_read_mail" -> deleteReadMail(player);
            case "do_transfer" -> doTransfer(player, tag);
            case "open_stall" -> openStallBrowse(player, tag.getString("owner"));
            case "stall_buy" -> stallBuy(player, tag);
            case "stall_apply_price" -> stallApplyPrice(player, tag);
            case "remove_stall" -> removeStall(player, tag.getInt("index"));
            // 需求五：已取消补货，售完的商品只能下架后重新上架。
            // 需求 10：客户端打开对应界面后请求清除该类型红点（服务端权威清零并回传）。
            case "notif_clear" -> NotificationManager.clear(player, NotificationType.byId(tag.getString("type")));
            case "auction_set_start" -> auctionSetStart(player, tag);
            case "stall_set_price_new" -> stallSetPriceNew(player, tag);
            // ---- 玩家交易 ----
            case "trade_request" -> TradeManager.request(player, tag.getString("target"));
            case "trade_add_item" -> openDeposit(player, "trade");
            // 需求 3：从自己的交易区域删除已放入的物品并返还背包/邮箱。
            case "trade_remove_item" -> TradeManager.removeItem(player, tag.getInt("index"));
            case "trade_money_gui" -> {
                // 需求 3：交易金额界面显示玩家余额（服务端权威数值随输入界面下发）。
                CompoundTag moneyExtra = new CompoundTag();
                moneyExtra.putLong("balance", account(player).balance);
                moneyExtra.putBoolean("show_balance", true);
                openInput(player, "trade_set_money", "gui.server_shop_mod.input.trade_money", moneyExtra, "amount");
            }
            case "trade_set_money" -> TradeManager.setMoney(player, parsePositive(tag.getString("amount")));
            case "trade_agree" -> TradeManager.toggleAgree(player);
            case "trade_confirm" -> TradeManager.confirm(player);
            case "trade_cancel" -> TradeManager.cancel(player, true);
            // 需求 1/2：交易请求列表与接受/拒绝。
            case "trade_requests_open" -> TradeManager.sendRequestList(player, true);
            case "trade_accept" -> TradeManager.acceptRequest(player, tag.getLong("id"));
            case "trade_decline" -> TradeManager.declineRequest(player, tag.getLong("id"));
            // 需求 11：服务端记录管理员当前编辑的模块，物品放入时据此归属列表。
            case "admin_set_edit_type" -> setAdminEditType(player, tag.getString("type"));
            // 需求 11：管理员添加物品必须使用幽灵槽位，防止刷物品。
            // （现在是**批量**界面：一次最多放 27 种，确认后统一设置价格 / 品质 / 数量。）
            case "admin_add" -> openBatchAdd(player);
            case "admin_batch_prepare" -> adminBatchPrepare(player);
            case "admin_batch_apply" -> adminBatchApply(player, tag);
            case "admin_apply_price" -> adminApplyPrice(player, tag);
            case "admin_remove" -> adminRemove(player, tag);
            case "admin_set_price_new" -> adminSetPriceNew(player, tag);
            // ---- 管理员发放（发钱 / 发物品）----
            case "admin_grant_open" -> GrantManager.open(player);
            case "admin_grant_request" -> GrantManager.sendTargets(player);
            case "admin_grant_send" -> GrantManager.send(player, tag);
            // ---- 管理员：查看某玩家交易流水（设置页“交易记录”按钮）----
            case "admin_history" -> sendHistory(player, tag.getString("target"), tag.getInt("page"));
            // ---- 货币兑换（所有玩家可用；配置仅管理员可改）----
            case "exchange_deposit_gui" -> exchangeDepositGui(player, tag.getInt("index"));
            case "exchange_deposit" -> exchangeDeposit(player, tag);
            case "exchange_withdraw_gui" -> exchangeWithdrawGui(player, tag.getInt("index"));
            case "exchange_withdraw" -> exchangeWithdraw(player, tag);
            case "admin_exchange_add" -> openDeposit(player, "exchange", true);
            case "admin_exchange_set" -> adminExchangeSet(player, tag);
            case "admin_exchange_edit" -> adminExchangeEdit(player, tag);
            case "admin_exchange_remove" -> adminExchangeRemove(player, tag);
            // ---- 收购全清奖励（开关 + 改金额，仅管理员）----
            case "admin_buyback_bonus_toggle" -> adminBuybackBonusToggle(player);
            case "admin_buyback_bonus_set" -> adminBuybackBonusSet(player, tag);
            // ---- 签到（需求 7）----
            case "sign_in" -> doSignIn(player);
            case "sign_makeup" -> doMakeupSign(player, tag.getLong("day"));
            case "admin_signin_auto_toggle" -> adminSignInAutoToggle(player);
            case "admin_signin_cumulative_toggle" -> adminSignInCumulativeToggle(player);
            case "admin_signin_set" -> adminSignInSet(player, tag);
            case "admin_signin_day_set" -> adminActivityDaySet(player, tag);
            case "admin_signin_day_clear" -> adminActivityDayClear(player, tag);
            case "admin_signin_reward_open" -> adminActivityRewardOpen(player, tag);
            case "admin_signin_reward_save" -> adminActivityRewardSave(player, tag);
            case "admin_signin_reward_cancel" -> adminActivityRewardCancel(player);
            case "admin_shop_gen_set" -> adminShopGenSet(player, tag);
            default -> {
            }
        }
    }

    private static void handleDepositConfirm(ServerPlayer player) {
        if (!(player.containerMenu instanceof DepositMenu menu)) {
            return;
        }
        ItemStack stack = menu.deposit().getItem(0);
        if (stack.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.deposit_empty").withStyle(ChatFormatting.RED));
            return;
        }
        ItemStack taken = stack.copy();
        String context = DEPOSIT_CONTEXT.getOrDefault(player.getUUID(), "");
        // 需求 11：幽灵槽不真正取走物品，关闭界面时会把原物品返还给管理员。
        if (!menu.isGhost()) {
            menu.deposit().setItem(0, ItemStack.EMPTY);
        }
        player.closeContainer();
        returnPending(player);
        // 交易放入的物品直接进入交易会话，不能走 PENDING_ITEM（否则会悬空）。
        if ("trade".equals(context)) {
            TradeManager.addItem(player, taken);
            return;
        }
        // 需求 11：管理员用幽灵槽，模板单独保存，不进入 PENDING_ITEM，避免与返还的原物重复。
        if ("exchange".equals(context)) {
            ADMIN_TEMPLATE.put(player.getUUID(), taken);
            openInput(player, "admin_exchange_set", "gui.server_shop_mod.input.exchange_value", null, "value");
            return;
        }
        if ("admin".equals(context)) {
            ADMIN_TEMPLATE.put(player.getUUID(), taken);
            AdminEditType type = ADMIN_EDIT_TYPE.getOrDefault(player.getUUID(), AdminEditType.DAILY_SHOP);
            CompoundTag extra = new CompoundTag();
            extra.putString("mode", type.id());
            // 需求 6/7：价值、品质、数量一次配置完成。
            // 出货箱的「兑换规则」只需要物品 + 单价（每日份数由各等级的「每日额度」统一管），所以少两个字段。
            if (type == AdminEditType.SHIPPING) {
                openInput(player, "admin_set_price_new", "gui.server_shop_mod.input.shipping_rule_new", extra,
                        "price");
            } else {
                openInput(player, "admin_set_price_new", "gui.server_shop_mod.input.admin_offer", extra,
                        "price", "rarity", "quantity");
            }
            return;
        }
        PENDING_ITEM.put(player.getUUID(), taken);
        switch (context) {
            case "auction" -> openInput(player, "auction_set_start", "gui.server_shop_mod.input.start_price",
                    null, "price");
            case "stall" -> {
                // 需求 4：上架时同时输入单件价格与库存数量，默认带出放入的物品数量（上限 64）。
                CompoundTag extra = new CompoundTag();
                extra.putString("default_quantity", Integer.toString(Math.max(1, Math.min(64, taken.getCount()))));
                openInput(player, "stall_set_price_new", "gui.server_shop_mod.input.stall_price", extra,
                        "price", "quantity");
            }
            default -> returnPending(player);
        }
    }

    /** 需求 11：记录管理员当前编辑的模块。 */
    public static void setAdminEditType(ServerPlayer player, String type) {
        ADMIN_EDIT_TYPE.put(player.getUUID(), AdminEditType.byId(type));
    }

    /** 优先使用服务端记录的编辑模块，缺省时退回消息里携带的 mode。 */
    private static boolean isShopMode(ServerPlayer player, CompoundTag tag) {
        AdminEditType stored = ADMIN_EDIT_TYPE.get(player.getUUID());
        if (stored != null) {
            return stored == AdminEditType.DAILY_SHOP;
        }
        return !"buyback".equals(tag.getString("mode"));
    }

    /**
     * 管理员当前编辑的模块（购物城也走同一套）：服务端有记录就用记录，否则看报文里的 mode。
     */
    private static AdminEditType editType(ServerPlayer player, CompoundTag tag) {
        AdminEditType stored = ADMIN_EDIT_TYPE.get(player.getUUID());
        if (stored != null) {
            return stored;
        }
        return AdminEditType.byId(tag == null ? "" : tag.getString("mode"));
    }

    /** 把悬而未决的上架物品安全退还给玩家，避免取消输入界面导致物品丢失。 */
    private static void returnPending(ServerPlayer player) {
        ItemStack pending = PENDING_ITEM.remove(player.getUUID());
        if (pending != null && !pending.isEmpty()) {
            addItemOrMail(player, pending, SOURCE_ADMIN);
        }
        // 幽灵槽模板不再作为真实物品返还（原物已由幽灵槽退回），直接丢弃防止刷物品。
        ADMIN_TEMPLATE.remove(player.getUUID());
    }

    // =========================================================== 每日商店 / 收购

    /** 需求 11：翻开每日商店卡牌，持久化翻开状态并按稀有度播放对应音效。 */
    private static void flipShop(ServerPlayer player, int index) {
        PlayerAccount account = account(player);
        ShopSavedData data = data(player.server);
        ensureDaily(player, account, data);
        if (index < 0 || index >= account.dailyShop.size() || account.dailyFlipped.contains(index)) {
            return;
        }
        account.dailyFlipped.add(index);
        data.setDirty();
        DailyShopEntry entry = account.dailyShop.get(index);
        player.playNotifySound(ModSounds.forRarity(entry.rarity), SoundSource.PLAYERS, 1.0F, 1.0F);
        sendSnapshot(player, "daily");
    }

    /**
     * 第 N 次付费刷新的花费；返回 -1 表示次数已用完。
     * {@code shop=true} 用每日商店的价格表与次数，{@code false} 用每日收购的（两者完全独立）。
     */
    public static long nextRefreshCost(PlayerAccount account, boolean shop) {
        List<? extends String> costs = shop
                ? Config.INSTANCE.dailyRefreshCosts.get() : Config.INSTANCE.buybackRefreshCosts.get();
        int used = Math.max(0, shop ? account.dailyShopRefreshUsed : account.dailyBuybackRefreshUsed);
        if (used >= costs.size()) {
            return -1L;
        }
        try {
            return Math.max(1L, Long.parseLong(String.valueOf(costs.get(used)).trim()));
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /**
     * 付费刷新**每日商店**（只重抽卡牌，不动每日收购）：按价格表顺序扣费（默认第一次 200、第二次 500），
     * 每日次数由价格表长度决定（默认 2 次），扣完即不可再刷新。
     */
    private static void refreshDailyShopPaid(ServerPlayer player) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            ensureDaily(player, account, data);
            long cost = nextRefreshCost(account, true);
            if (cost < 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.refresh_no_uses")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (data.shopPool.isEmpty()) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.refresh_empty")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (account.balance < cost) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (debit(account, cost, "purchase") < 0L) { return; }
            account.dailyShopRefreshUsed++;
            account.dailyShop.clear();
            account.dailyFlipped.clear();
            generateDailyShop(player, account, data);
            refreshDailyPrices(account, data);
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SHOP_REFRESH, "模块商店",
                    -cost, account.balance, "商店刷新");
            long next = nextRefreshCost(account, true);
            if (next < 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.refresh_ok_last", cost)
                        .withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.refresh_ok", cost, next)
                        .withStyle(ChatFormatting.GREEN));
            }
            sendSnapshot(player, "daily");
        }
    }

    /**
     * 付费刷新**每日收购**（只重抽收购清单，不动每日商店卡牌）：
     * 价格与每日次数走 {@link Config#buybackRefreshCosts}，与商店分开计数。
     */
    private static void refreshDailyBuybackPaid(ServerPlayer player) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            ensureDaily(player, account, data);
            long cost = nextRefreshCost(account, false);
            if (cost < 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.refresh_no_uses")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (data.buybackPool.isEmpty()) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.buyback_refresh_empty")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (account.balance < cost) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (debit(account, cost, "purchase") < 0L) { return; }
            account.dailyBuybackRefreshUsed++;
            account.dailyBuyback.clear();
            generateDailyBuyback(player, account, data);
            refreshDailyPrices(account, data);
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SHOP_REFRESH, "模块商店",
                    -cost, account.balance, "收购刷新");
            long next = nextRefreshCost(account, false);
            if (next < 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.buyback_refresh_ok_last", cost)
                        .withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.buyback_refresh_ok", cost, next)
                        .withStyle(ChatFormatting.GREEN));
            }
            sendSnapshot(player, "buyback");
        }
    }

    /** 当日收购清单全部卖完的一次性奖励（每天一次，Config.buybackAllBonus）。 */
    private static void maybeGrantBuybackBonus(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        long bonus = buybackBonusAmount(data);
        if (bonus <= 0L || account.dailyBuybackBonusDone || account.dailyBuyback.isEmpty()) {
            return;
        }
        for (DailyBuybackEntry entry : account.dailyBuyback) {
            if (entry.remaining() > 0) {
                return;
            }
        }
        account.dailyBuybackBonusDone = true;
        long credited = credit(account, bonus, Config.maxBalanceLimit());
        data.setDirty();
        TransactionLog.record(player.server, player.getUUID(), TransactionLog.BUYBACK_BONUS, "模块商店",
                credited, account.balance, "");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.buyback_all_bonus", credited)
                .withStyle(ChatFormatting.GOLD));
    }

    /** 需求 8：每日商店支持一次购买多件（数量由客户端弹窗选择，服务端按限购/余额夹取）。 */
    public static void buyShop(ServerPlayer player, int index, int requested) {
        // 需求 8：限购、余额、发货在同一把锁内完成，防止并发抢购最后一件。
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            ensureDaily(player, account, data);
            if (index < 0 || index >= account.dailyShop.size()) {
                return;
            }
            DailyShopEntry entry = account.dailyShop.get(index);
            // 需求 11：上限按物品计算，而不是同一品质的所有物品共用一个上限。
            int remaining = entry.limit - entry.bought;
            if (remaining <= 0) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.limit_reached").withStyle(ChatFormatting.RED));
                return;
            }
            int buyCount = Math.min(Math.max(1, requested), remaining);
            // 价格×数量必须做溢出保护：极端价格下裸乘会溢出成负数，
            // 于是“余额不足”判定恒通过、扣款反而变成给买家加钱。
            long total = safeMultiply(entry.price, buyCount);
            if (total <= 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }
            // 需求 9：价格重新从服务端条目读取，绝不使用客户端传来的价格。
            if (account.balance < total) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money").withStyle(ChatFormatting.RED));
                return;
            }
            // 需求 10：保留附魔/自定义名/NBT；数量超过单栈上限时按份交付。
            Component rewardName = entry.stack.getHoverName();
            if (debit(account, total, "purchase") < 0L) { return; }
            entry.bought += buyCount;
            data.setDirty();
            // 背包放不下的部分转邮箱，避免因买多件而丢物品。
            // 这里不再用“没有空格就拒绝购买”的预检：那会把“没有空槽但同类还能堆叠”误判成背包已满。
            deliver(player, entry.stack, buyCount, SOURCE_ADMIN);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.BUY, "模块商店",
                    -total, account.balance, entry.stack.getHoverName().getString() + "×" + buyCount);
            // 需求 6：摘要统一为“购买物品X，花费100星币”。
            // 需求一：购买模板「花费 金额 星币 购买 物品名」，金额在前、物品名在后，物品名可为纯数字。
            if (buyCount <= 1) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.bought",
                        total, entry.stack.getHoverName()).withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.bought_count",
                        total, entry.stack.getHoverName(), buyCount).withStyle(ChatFormatting.GREEN));
            }
            sendSnapshot(player, "daily");
        }
    }

    // =========================================================== 购物城（新增模块）
    //
    // 常驻货架：每件商品有 价格 / 品质 / 库存（-1 = 无限），购买即扣库存并记一条"最近购买"。
    // 管理端支持三种录入方式：幽灵槽单件添加、管理页逐条编辑（价格/品质/库存）、整模组批量导入。

    /** 购物城：购买货架上的第 index 件商品（库存不够时按剩余数量成交）。 */
    public static void buyMall(ServerPlayer player, int index, int requested) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            if (index < 0 || index >= data.mallPool.size()) {
                return;
            }
            MallItem item = data.mallPool.get(index);
            int available = item.available();
            if (available <= 0) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_sold_out",
                        item.stack.getHoverName()).withStyle(ChatFormatting.RED));
                return;
            }
            int buyCount = Math.min(Math.max(1, requested), available);
            // 与每日商店一样做溢出保护：极端价格下裸乘会溢出成负数，反而变成给买家加钱。
            long total = safeMultiply(item.price, buyCount);
            if (total <= 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (account.balance < total) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (debit(account, total, "purchase") < 0L) { return; }
            if (!item.unlimited()) {
                item.stock = Math.max(0, item.stock - buyCount);
            }
            data.addMallRecent(new MallPurchase(player.getGameProfile().getName(), item.stack, buyCount,
                    item.price, System.currentTimeMillis()));
            data.setDirty();
            deliver(player, item.stack, buyCount, SOURCE_ADMIN);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.BUY, "购物城",
                    -total, account.balance, item.stack.getHoverName().getString() + "×" + buyCount);
            if (buyCount <= 1) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.bought",
                        total, item.stack.getHoverName()).withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.bought_count",
                        total, item.stack.getHoverName(), buyCount).withStyle(ChatFormatting.GREEN));
            }
            sendSnapshot(player, "mall");
        }
    }

    /**
     * 管理员：把某个模组的**全部物品**一次性导入购物城（统一价格 / 库存 / 品质）。
     * 已经在货架上的物品按注册名去重跳过，重复导入不会出现两行一样的商品。
     */
    private static void adminMallImport(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        String namespace = tag.getString("mod").trim();
        if (namespace.isEmpty()) {
            return;
        }
        long price = parseAdminPrice(player, tag.getString("price"));
        if (price <= 0) {
            return;
        }
        Rarity rarity = parseRarity(tag.getString("rarity"), price);
        int stock = parseMallStock(tag.getString("quantity"));
        ShopSavedData data = data(player.server);
        Set<ResourceLocation> existing = new HashSet<>();
        for (MallItem item : data.mallPool) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item.stack.getItem());
            if (id != null) {
                existing.add(id);
            }
        }
        int added = 0;
        int skipped = 0;
        int unnamed = 0;
        boolean truncated = false;
        // 客户端用「保留哪几件」的位掩码过滤掉没有显示名的技术物品（模组内部用的图标 / 属性占位物品）。
        // 两端都按**注册表顺序**遍历同一命名空间的物品，所以第 i 位对应的就是同一件物品。
        byte[] mask = tag.getByteArray("mask");
        int maskBits = tag.getInt("mask_bits");
        int slotIndex = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || !namespace.equals(id.getNamespace()) || item == Items.AIR) {
                continue;
            }
            boolean keep = mask.length == 0 || slotIndex >= maskBits
                    || (mask[slotIndex >> 3] & (1 << (slotIndex & 7))) != 0;
            slotIndex++;
            if (!keep) {
                unnamed++;
                continue;
            }
            if (existing.contains(id)) {
                skipped++;
                continue;
            }
            if (data.mallPool.size() >= MALL_IMPORT_MAX) {
                truncated = true;
                break;
            }
            // 用注册名的默认 ItemStack（含默认组件），保证和玩家实际拿到的物品一致。
            MallItem mallItem = new MallItem(new ItemStack(item), price, rarity, stock);
            mallItem.sort = data.mallPool.size();
            data.mallPool.add(mallItem);
            existing.add(id);
            added++;
        }
        data.setDirty();
        // 导完直接回购物城页：管理员马上能看到整批新上架的商品。
        sendSnapshot(player, "mall");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_import_done",
                namespace, added, skipped, unnamed).withStyle(ChatFormatting.GREEN));
        if (truncated) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_import_capped", MALL_IMPORT_MAX)
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    // =========================================================== 出货箱（新增模块）
    //
    // 参考星露谷的售货箱：每个玩家一个**独立**空间（类似末影箱），放入的物品会在**第二天早上 6:00**
    // （游戏刻 0，窗口见配置 shippingSettleWindow）按「兑换规则」自动结算 —— 直接换成星币到账，
    // 箱子随之清空。每天能结算的**金额**有上限（按等级配置，现实时间 0 点刷新），
    // 超出上限的那部分物品**留在箱子里**，第二天继续结算（不会凭空消失）。

    /** 出货箱：结算窗口内判定用的每服务器状态（避免每 tick 遍历）。 */
    private static long shippingLoggedDay = Long.MIN_VALUE;

    /** 出货箱：放不进去的提醒节流（玩家 UUID → 上次提醒时刻）。 */
    private static final Map<UUID, Long> SHIPPING_REJECT_WARNED = new HashMap<>();

    /**
     * 玩家往出货箱里塞了「兑换规则」没写的物品：给一条说明（2 秒内只提醒一次，避免刷屏）。
     * 由容器菜单在槽位拒绝放置时调用。
     */
    public static void notifyShippingRejected(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = SHIPPING_REJECT_WARNED.get(player.getUUID());
        if (last != null && now - last < 2000L) {
            return;
        }
        SHIPPING_REJECT_WARNED.put(player.getUUID(), now);
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_reject")
                .withStyle(ChatFormatting.RED));
    }

    /** 玩家：打开自己的出货箱。 */
    private static void openShippingBin(ServerPlayer player) {
        if (!Config.INSTANCE.shippingEnabled.get()) {
            return;
        }
        PlayerAccount account = account(player);
        ShippingBin bin = account.shipping;
        int slots = Config.shippingSlotsFor(bin.level);
        bin.resize(slots);
        // 规则写错时先在聊天栏提醒一次（格式错的规则不会生效，对应物品放不进去）。
        warnShippingRules(player);
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new ShippingBinMenu(id, inventory,
                        new ShippingBinContainer(player, slots), slots),
                Component.translatable("gui.server_shop_mod.shipping.title", bin.level, slots)),
                buf -> buf.writeVarInt(slots));
    }

    /** 规则有错时给玩家一条说明（同一条错误 30 秒内只提醒一次）。 */
    private static final Map<UUID, Long> SHIPPING_RULE_WARNED = new HashMap<>();

    private static void warnShippingRules(ServerPlayer player) {
        List<String> errors = ShippingRules.errors();
        if (errors.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = SHIPPING_RULE_WARNED.get(player.getUUID());
        if (last != null && now - last < 30_000L) {
            return;
        }
        SHIPPING_RULE_WARNED.put(player.getUUID(), now);
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_rule_bad",
                errors.size()).withStyle(ChatFormatting.RED));
        for (int i = 0; i < Math.min(3, errors.size()); i++) {
            notifyPlayer(player, Component.literal("· " + errors.get(i)).withStyle(ChatFormatting.GRAY));
        }
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_rule_bad_hint")
                .withStyle(ChatFormatting.GRAY));
    }

    /** 出货箱：升级（花星币，最高到配置的最大等级）。 */
    private static void upgradeShippingBin(ServerPlayer player) {
        if (!Config.INSTANCE.shippingEnabled.get()) {
            return;
        }
        PlayerAccount account = account(player);
        ShippingBin bin = account.shipping;
        int maxLevel = Config.shippingLevels();
        if (bin.level >= maxLevel) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_max_level", maxLevel)
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        long cost = Config.shippingUpgradeCost(bin.level);
        if (cost < 0L) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_no_upgrade")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (account.balance < cost) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (debit(account, cost, "purchase") < 0L) { return; }
        bin.level += 1;
        int slots = Config.shippingSlotsFor(bin.level);
        bin.resize(slots);
        data(player.server).setDirty();
        TransactionLog.record(player.server, player.getUUID(), TransactionLog.SHOP_REFRESH, "出货箱升级",
                -cost, account.balance, "出货箱 → Lv." + bin.level + "（" + slots + " 格）");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_upgraded",
                bin.level, slots, cost).withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "shipping");
    }

    /**
     * 出货箱：容器与存档之间的桥。
     * <p>
     * 每次槽位变化立刻写回玩家存档（{@link #setChanged()}），所以掉线 / 崩服都不会丢物品；
     * 关闭界面时不需要额外处理。
     *
     * <p>⚠ <b>初始化 / 刷新期间必须挡住回写</b>（{@link #suppressWriteBack}）：
     * {@code SimpleContainer.setItem()} 内部会调用 {@code setChanged()}，而这里的 {@code setChanged()}
     * 是把**整个容器**写回存档 —— 构造时如果要「第 0 格填完就把还没填的格子（全空）整体写回」，
     * 箱子里第 1 格之后的物品会当场被清空。玩家反馈的「放进去、重进箱子就没了」就是这个原因。
     */
    private static final class ShippingBinContainer extends SimpleContainer {
        private final ServerPlayer player;
        /** true = 正在从存档往容器里填，此期间禁止把容器写回存档。 */
        private boolean suppressWriteBack;

        ShippingBinContainer(ServerPlayer player, int size) {
            super(size);
            this.player = player;
            this.suppressWriteBack = true;
            try {
                PlayerAccount account = account(player);
                ShippingBin bin = account.shipping;
                bin.resize(size);
                for (int i = 0; i < size; i++) {
                    ItemStack stack = bin.slots.get(i);
                    setItem(i, stack == null ? ItemStack.EMPTY : stack.copy());
                }
            } finally {
                this.suppressWriteBack = false;
            }
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (this.suppressWriteBack) {
                // 正在装载存档内容：这一刻容器还是半满的，回写会毁掉剩下的物品。
                return;
            }
            PlayerAccount account = account(this.player);
            ShippingBin bin = account.shipping;
            bin.resize(getContainerSize());
            for (int i = 0; i < getContainerSize(); i++) {
                bin.slots.set(i, getItem(i).copy());
            }
            data(this.player.server).setDirty();
        }

        /**
         * 按存档刷新容器内容（结算卖掉东西之后调用）。
         *
         * <p>方向是**存档 → 界面**，靠 {@link #suppressWriteBack} 挡住回写：
         * 否则就会把界面里那份旧内容又写回存档，等于把刚卖掉的东西变回来。
         */
        void reloadFromBin() {
            PlayerAccount account = account(this.player);
            ShippingBin bin = account.shipping;
            this.suppressWriteBack = true;
            try {
                bin.resize(getContainerSize());
                for (int i = 0; i < getContainerSize(); i++) {
                    ItemStack stack = bin.slots.get(i);
                    setItem(i, stack == null ? ItemStack.EMPTY : stack.copy());
                }
            } finally {
                this.suppressWriteBack = false;
            }
        }
    }

    /**
     * 出货箱结算：**每天早上 6:00（游戏刻 0）起的窗口内**，对所有玩家（含离线）的箱子结算一次。
     * 结算按槽位顺序卖到「今日剩余额度」用完为止，剩下的物品留在箱子里等第二天。
     */
    public static void tickShipping(MinecraftServer server) {
        if (!Config.INSTANCE.shippingEnabled.get()) {
            return;
        }
        long dayTime = server.overworld().getDayTime();
        long gameDay = dayTime / 24000L;
        long timeOfDay = dayTime % 24000L;
        long start = Config.INSTANCE.shippingSettleTick.get();
        long window = Config.INSTANCE.shippingSettleWindow.get();
        if (shippingLoggedDay == gameDay) {
            return;
        }
        if (timeOfDay < start || timeOfDay > start + window) {
            return;
        }
        shippingLoggedDay = gameDay;
        ShopSavedData data = data(server);
        // 出货箱的「今日额度」按配置的现实重置时间归属（默认现实 0 点）
        long realDay = Config.shippingQuotaDay();
        for (Map.Entry<UUID, PlayerAccount> entry : new ArrayList<>(data.accounts().entrySet())) {
            settleShippingBin(server, data, entry.getKey(), entry.getValue(), gameDay, realDay);
        }
        data.setDirty();
    }

    /**
     * 购物城定时补货：按「补货设置」里的时刻，把货架上**卖掉的库存补回目标数量**。
     *
     * <p>两种时间口径（管理员在游戏内选，也可写配置）：
     * <ul>
     *   <li>{@code real}（默认）：按**现实时间**的 HH:mm 分界 —— 和「每日额度重置」同一套算法；</li>
     *   <li>{@code game}：按**游戏内时间**的 HH:mm —— 和出货箱结算一样，06:00 = 游戏 0 刻。</li>
     * </ul>
     * 每个「补货日」只补一次（存档里记 {@code lastMallRestockDay / lastMallRestockGameDay}），
     * 服务器重启也不会重复补；无限库存（stock &lt; 0）的商品永远不参与补货。
     */
    /**
     * 购物城**库存周期刷新**：按「商店设置 → 购物城 → 库存设置」的周期，把全店库存补回各自的「原量」。
     *
     * <p>与「补货设置」的区别：补货是**每天固定时刻**补一次；这里是**每隔 N 小时 / N 游戏天**整店刷新一次库存
     * （从上次刷新时刻算起）。第一次运行只记录基准时刻（不会一开服就刷）。
     */
    public static void tickMallRefresh(MinecraftServer server) {
        if (server == null || !Config.mallRefreshActive()) {
            return;
        }
        ShopSavedData data = data(server);
        boolean byGame = "game".equals(Config.mallRefreshMode());
        long interval = byGame ? Config.mallRefreshGameTicks() : Config.mallRefreshRealMillis();
        long now = byGame ? server.overworld().getGameTime() : System.currentTimeMillis();
        long last = byGame ? data.mallRefreshLastGame : data.mallRefreshLastReal;
        if (last <= 0L) {
            // 第一次运行：只记录基准，不立刻刷新（否则玩家一开服就白白拿到整店补货）。
            if (byGame) {
                data.mallRefreshLastGame = now;
            } else {
                data.mallRefreshLastReal = now;
            }
            data.setDirty();
            return;
        }
        if (now - last < interval) {
            return;
        }
        int restored = restockMall(data);
        if (byGame) {
            data.mallRefreshLastGame = now;
        } else {
            data.mallRefreshLastReal = now;
        }
        // 另一套时间戳也顺手对齐，避免玩家切换方式后立刻触发一次。
        data.mallRefreshLastReal = System.currentTimeMillis();
        data.mallRefreshLastGame = server.overworld().getGameTime();
        data.setDirty();
        broadcastSnapshots(server);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            notifyPlayer(online, Component.translatable("msg.server_shop_mod.mall_refreshed", restored)
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /** 管理员：购物城库存周期刷新设置（关闭 / 现实小时 / 游戏天数）。 */
    private static void adminMallRefresh(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        String modeText = tag.getString("refresh_mode").trim().toLowerCase(java.util.Locale.ROOT);
        String mode = ("real".equals(modeText) || "game".equals(modeText)) ? modeText : "off";
        int amount;
        try {
            amount = Integer.parseInt(tag.getString("refresh_amount").trim());
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_refresh_bad")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (amount < 1 || amount > 100000) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_refresh_bad")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.mallRefreshMode.set(mode);
        Config.INSTANCE.mallRefreshAmount.set(amount);
        Config.SPEC.save();
        Component desc = "off".equals(mode)
                ? Component.translatable("gui.server_shop_mod.choice.refresh_mode.off")
                : Component.translatable("gui.server_shop_mod.mall.refresh_value",
                        Component.translatable("gui.server_shop_mod.choice.refresh_mode." + mode), amount);
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_refresh_saved", desc)
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }
    public static void tickMallRestock(MinecraftServer server) {
        if (!Config.mallRestockEnabled() || server == null) {
            return;
        }
        ShopSavedData data = data(server);
        long timeOfDay = server.overworld().getDayTime() % 24000L;
        long gameDay = server.overworld().getDayTime() / 24000L;
        boolean restock;
        if (Config.mallRestockGameTime()) {
            long start = Config.mallRestockTick();
            if (timeOfDay < start) {
                return;   // 还没到补货时刻
            }
            if (data.lastMallRestockGameDay == Long.MIN_VALUE) {
                // 第一次运行只记录基准日（还没到点的话记「昨天」，这样当天的补货时刻仍会生效）
                data.lastMallRestockGameDay = timeOfDay >= start ? gameDay : gameDay - 1L;
                data.setDirty();
                return;
            }
            if (gameDay <= data.lastMallRestockGameDay) {
                return;   // 这个游戏日已经补过了
            }
            data.lastMallRestockGameDay = gameDay;
            restock = true;
        } else {
            long day = Config.mallRestockRealDay();
            if (data.lastMallRestockDay == Long.MIN_VALUE) {
                data.lastMallRestockDay = day;   // 第一次运行只记录基准日，不立刻补
                data.setDirty();
                return;
            }
            if (day <= data.lastMallRestockDay) {
                return;
            }
            data.lastMallRestockDay = day;
            restock = true;
        }
        if (restock) {
            int restored = restockMall(data);
            data.setDirty();
            if (restored > 0) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("msg.server_shop_mod.mall_restocked", restored)
                                .withStyle(ChatFormatting.GREEN), false);
            }
        }
    }

    /** 把货架上卖掉的库存补回目标数量，返回补过的商品件数（{@link #tickMallRestock} 用）。 */
    private static int restockMall(ShopSavedData data) {
        int unified = Config.mallRestockAmount();
        int restored = 0;
        for (MallItem item : data.mallPool) {
            // 0 = 补回各自原量（补货目标）；>0 = 全部统一补到该数量。
            int target = unified > 0 ? unified : item.stockMax;
            if (target < 0 || item.stock >= target) {
                continue;
            }
            item.stock = target;
            restored++;
        }
        return restored;
    }

    /** 结算单个玩家的出货箱（详见 {@link #tickShipping}）。 */
    private static void settleShippingBin(MinecraftServer server, ShopSavedData data, UUID owner,
            PlayerAccount account, long gameDay, long realDay) {
        ShippingBin bin = account.shipping;
        bin.resize(Config.shippingSlotsFor(bin.level));
        // 现实日期变了 → 今日额度与「每条规则的当日份数」全部归零（服务器 0 点刷新）。
        if (bin.soldDay != realDay) {
            bin.soldDay = realDay;
            bin.soldToday = 0L;
            bin.ruleUsed.clear();
        }
        // 这个游戏日已经结算过了就不再结算（用存档里的标记，服务器在窗口内重启也不会重复结算）。
        if (bin.lastSettleGameDay >= gameDay) {
            return;
        }
        bin.lastSettleGameDay = gameDay;
        if (bin.isEmpty()) {
            return;
        }
        long limit = Config.shippingDailyLimit(bin.level);
        long remaining = limit == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, limit - bin.soldToday);
        // ⚠ 余额上限也必须当成一种"额度"：credit() 只会加余额装得下的部分，
        // 而物品是在循环里先被扣掉的 —— 以前余额满时物品被销毁、钱一分没到账（玩家反馈的"吞东西"）。
        long maxBalance = Config.maxBalanceLimit();
        boolean bankFull = false;
        if (maxBalance != Long.MAX_VALUE) {
            long room = Math.max(0L, maxBalance - account.balance);
            if (room < remaining) {
                bankFull = true;
                remaining = room;
            }
        }
        long payout = 0L;
        int sold = 0;
        List<String> soldNames = new ArrayList<>();
        for (int i = 0; i < bin.slots.size(); i++) {
            ItemStack stack = bin.slots.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ShippingRules.Rule rule = ShippingRules.match(stack);
            if (rule == null) {
                // 规则被改过、原物品不再可出售：留在箱子里（下次仍会尝试），不销毁。
                continue;
            }
            int perDay = rule.dailyMax();
            int usedCount = bin.ruleUsed.getOrDefault(rule.line(), 0);
            int allowed = perDay <= 0 ? stack.getCount() : Math.max(0, perDay - usedCount);
            if (allowed <= 0) {
                continue;
            }
            int count = Math.min(stack.getCount(), allowed);
            if (remaining != Long.MAX_VALUE) {
                long afford = rule.price() <= 0L ? 0L : remaining / rule.price();
                if (afford <= 0L) {
                    continue;
                }
                count = (int) Math.min(count, afford);
            }
            if (count <= 0) {
                continue;
            }
            long gain = safeMultiply(rule.price(), count);
            if (gain <= 0L) {
                continue;
            }
            ItemStack soldStack = stack.copyWithCount(count);
            payout += gain;
            sold += count;
            soldNames.add(soldStack.getHoverName().getString() + "×" + count);
            if (perDay > 0) {
                bin.ruleUsed.put(rule.line(), usedCount + count);
            }
            if (remaining != Long.MAX_VALUE) {
                remaining -= gain;
            }
            bin.slots.set(i, stack.getCount() > count ? stack.copyWithCount(stack.getCount() - count) : ItemStack.EMPTY);
        }
        if (payout <= 0L) {
            // 余额满到一分钱都装不下：一件都不卖（物品原样留在箱子里，绝不会"吞掉"）。
            ServerPlayer blocked = server.getPlayerList().getPlayer(owner);
            if (blocked != null && bankFull && !bin.isEmpty()) {
                notifyPlayer(blocked, Component.translatable("msg.server_shop_mod.shipping_bank_full")
                        .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }
        long credited = credit(account, payout, maxBalance);
        if (credited < payout) {
            // 理论上到不了这里（上面已按余额空间夹取，且 maxBalance 是本次读到的同一个值）；
            // 真出现说明配置在结算途中被改小：差额转成金额邮件，绝不静默销毁。
            long rest = payout - credited;
            org.slf4j.LoggerFactory.getLogger("server_shop")
                    .warn("[模块商店] 出货箱结算入账不足：应 {} 实 {}，差额 {} 转邮件", payout, credited, rest);
            addMoneyMail(server, owner, rest, ItemStack.EMPTY, SOURCE_SHIPPING_UNPAID, "", null);
        }
        bin.soldToday += credited;
        bin.lastPayout = credited;
        bin.lastCount = sold;
        TransactionLog.record(server, owner, TransactionLog.SELL, "出货箱", credited, account.balance,
                String.join("、", soldNames.size() > 6 ? soldNames.subList(0, 6) : soldNames)
                        + (soldNames.size() > 6 ? " 等" : ""));
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.server_shop_mod.shipping_sold",
                    sold, credited).withStyle(ChatFormatting.GREEN));
            if (!bin.isEmpty()) {
                if (remaining != Long.MAX_VALUE && remaining <= 0L) {
                    notifyPlayer(online, Component.translatable(bankFull
                                    ? "msg.server_shop_mod.shipping_bank_full"
                                    : "msg.server_shop_mod.shipping_capped")
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
            syncOpenBin(online);
            sendSnapshot(online, "shipping");
        } else {
            // 离线结算：登录后补一条提示，别让人不知道这笔钱是哪来的。
            bin.pendingNotice = true;
        }
    }

    /**
     * 结算之后把玩家**正开着的**出货箱界面同步成最新内容。
     *
     * <p>结算改的是存档里的 {@code bin.slots}，而打开着的容器持有的是**另一份拷贝** ——
     * 玩家此时随便动一下格子，{@code ShippingBinContainer.setChanged()} 就会把那份旧内容整体写回存档，
     * 于是已经卖掉（钱也到账）的格子又活了（相当于复制物品）。这里先按存档刷一遍界面，堵掉这条重复刷物路径。
     */
    private static void syncOpenBin(ServerPlayer player) {
        if (!(player.containerMenu instanceof cn.autoforged.server_shop_mod_1789689358.menu.ShippingBinMenu menu)) {
            return;
        }
        if (!(menu.bin() instanceof ShippingBinContainer container)) {
            return;
        }
        container.reloadFromBin();
        menu.broadcastChanges();
    }

    /** 出货箱当前能拿到多少钱（界面展示：所有可出售物品按规则计价，再按今日剩余额度截断）。 */
    private static long shippingPendingValue(PlayerAccount account, long realDay) {
        ShippingBin bin = account.shipping;
        long limit = Config.shippingDailyLimit(bin.level);
        long soldToday = bin.soldDay == realDay ? bin.soldToday : 0L;
        long remaining = limit == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, limit - soldToday);
        long total = 0L;
        Map<Integer, Integer> counted = new HashMap<>();
        for (ItemStack stack : bin.slots) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ShippingRules.Rule rule = ShippingRules.match(stack);
            if (rule == null) {
                continue;
            }
            int perDay = rule.dailyMax();
            int used = bin.soldDay == realDay ? bin.ruleUsed.getOrDefault(rule.line(), 0) : 0;
            int allowed = perDay <= 0 ? stack.getCount() : Math.max(0, perDay - used - counted.getOrDefault(rule.line(), 0));
            int count = Math.min(stack.getCount(), Math.max(0, allowed));
            if (count <= 0) {
                continue;
            }
            if (perDay > 0) {
                counted.merge(rule.line(), count, Integer::sum);
            }
            long gain = safeMultiply(rule.price(), count);
            total += gain;
        }
        if (remaining != Long.MAX_VALUE && total > remaining) {
            return remaining;
        }
        return total;
    }

    private static void sendShippingInfo(ServerPlayer player, CompoundTag tag, ShopSavedData data) {
        PlayerAccount account = account(player);
        ShippingBin bin = account.shipping;
        int slots = Config.shippingSlotsFor(bin.level);
        bin.resize(slots);
        long realDay = Config.shippingQuotaDay();
        long limit = Config.shippingDailyLimit(bin.level);
        tag.putBoolean("shipEnabled", Config.INSTANCE.shippingEnabled.get());
        tag.putInt("shipLevel", bin.level);
        tag.putInt("shipMaxLevel", Config.shippingLevels());
        tag.putInt("shipSlots", slots);
        tag.putInt("shipItems", bin.itemCount());
        tag.putLong("shipValue", shippingPendingValue(account, realDay));
        tag.putLong("shipSoldToday", bin.soldDay == realDay ? bin.soldToday : 0L);
        tag.putLong("shipLimit", limit == Long.MAX_VALUE ? 0L : limit);
        tag.putLong("shipUpgradeCost", Config.shippingUpgradeCost(bin.level));
        tag.putInt("shipSettleTick", Config.INSTANCE.shippingSettleTick.get());
        tag.putString("shipResetTime", Config.INSTANCE.shippingResetTime.get());
        List<ShippingRules.Rule> rules = ShippingRules.rules();
        List<String> errors = ShippingRules.errors();
        tag.putInt("shipRules", rules.size());
        tag.putInt("shipRuleErrors", errors.size());
        tag.putString("shipRuleErrorText", errors.isEmpty() ? "" : errors.get(0));
        // 把生效的规则原文一起下发：出货箱容器界面据此**边放边算**本次结算金额（不用每次开箱都往返服务端）。
        StringBuilder ruleText = new StringBuilder();
        for (String line : Config.INSTANCE.shippingRules.get()) {
            ruleText.append(line).append('\n');
        }
        tag.putString("shipRulesText", ruleText.toString());
        // 每个等级的每日上限（管理页「每日额度」弹窗回填）
        long[] limits = new long[Config.shippingLevels()];
        for (int i = 0; i < limits.length; i++) {
            long value = Config.shippingDailyLimit(i + 1);
            limits[i] = value == Long.MAX_VALUE ? 0L : value;
        }
        tag.putLongArray("shipDailyLimits", limits);
    }

    /**
     * 管理员：修改「每个等级的每日结算额度」+ **结算时间（游戏时间）** + **额度重置时间（现实时间）**
     * （出货箱管理页 → 「每日额度」按钮）。直接写回配置文件；额度 0 = 不限，时间格式 HH:mm。
     */
    private static void adminShippingLimits(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        List<String> limits = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String key = "limit" + i;
            if (!tag.contains(key)) {
                break;
            }
            long value = Math.max(0L, parsePositive(tag.getString(key)));
            limits.add(Long.toString(value));
        }
        if (limits.isEmpty()) {
            return;
        }
        // 时间字段：格式不对就明确报错并且**不保存**（避免把配置写坏）
        String settleText = tag.getString("settle_time").trim();
        int settleTick = -1;
        if (!settleText.isEmpty()) {
            settleTick = Config.clockToDayTick(settleText);
            if (settleTick < 0) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_time_bad", settleText)
                        .withStyle(ChatFormatting.RED));
                return;
            }
        }
        String resetText = tag.getString("reset_time").trim();
        if (!resetText.isEmpty() && Config.parseClock(resetText) < 0) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_time_bad", resetText)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.shippingDailyLimits.set(List.copyOf(limits));
        if (settleTick >= 0) {
            Config.INSTANCE.shippingSettleTick.set(settleTick);
        }
        if (!resetText.isEmpty()) {
            Config.INSTANCE.shippingResetTime.set(resetText);
        }
        Config.SPEC.save();
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < limits.size(); i++) {
            if (i > 0) {
                shown.append(" / ");
            }
            shown.append("Lv.").append(i + 1).append(' ').append(
                    "0".equals(limits.get(i)) ? "不限" : limits.get(i));
        }
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_limits_saved",
                shown.toString(), Config.dayTickToClock(Config.INSTANCE.shippingSettleTick.get()),
                Config.INSTANCE.shippingResetTime.get()).withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "admin");
    }

    /**
     * 管理员：购物城「补货设置」—— 是否定时补货 / 时间口径（现实 or 游戏）/ 补货时刻 / 补货数量。
     * 直接写回配置文件，格式不对明确报错并且**不保存**（避免把配置写坏）。
     */
    private static void adminMallRestock(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        boolean enabled = "1".equals(tag.getString("restock_on").trim())
                || "true".equalsIgnoreCase(tag.getString("restock_on").trim());
        String modeText = tag.getString("restock_mode").trim();
        String mode = "game".equalsIgnoreCase(modeText) ? "game" : "real";
        String timeText = tag.getString("restock_time").trim();
        if (!timeText.isEmpty() && Config.parseClock(timeText) < 0) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_time_bad", timeText)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(tag.getString("restock_amount").trim());
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_restock_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (amount < 0 || amount > 1000000) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_restock_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.mallRestockEnabled.set(enabled);
        Config.INSTANCE.mallRestockMode.set(mode);
        if (!timeText.isEmpty()) {
            Config.INSTANCE.mallRestockTime.set(timeText);
        }
        Config.INSTANCE.mallRestockAmount.set(amount);
        Config.SPEC.save();
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_restock_saved",
                Component.translatable(enabled ? "gui.server_shop_mod.choice.restock_on.1"
                        : "gui.server_shop_mod.choice.restock_on.0"),
                Component.translatable("gui.server_shop_mod.choice.restock_mode." + mode),
                Config.mallRestockTime(),
                amount <= 0 ? Component.translatable("gui.server_shop_mod.mall.restock_keep_each")
                        : Component.literal(Integer.toString(amount)))
                .withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "admin");
    }

    /** 管理员：一键把某个模组在购物城里的**全部物品**下架（配合「导入模组」的误操作回滚）。 */
    private static void adminMallRemoveMod(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        String namespace = tag.getString("mod").trim();
        if (namespace.isEmpty()) {
            return;
        }
        ShopSavedData data = data(player.server);
        int before = data.mallPool.size();
        data.mallPool.removeIf(item -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item.stack.getItem());
            return id != null && namespace.equals(id.getNamespace());
        });
        int removed = before - data.mallPool.size();
        data.setDirty();
        sendSnapshot(player, "admin");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.mall_remove_done", namespace, removed)
                .withStyle(ChatFormatting.GREEN));
    }

    /** 需求 9：玩家可自行选择卖出数量，上限为该物品剩余的收购数量。 */
    public static void sellBuyback(ServerPlayer player, int index, int requested) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            ensureDaily(player, account, data);
            if (index < 0 || index >= account.dailyBuyback.size()) {
                return;
            }
            DailyBuybackEntry entry = account.dailyBuyback.get(index);
            if (entry.remaining() <= 0) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.buyback_full").withStyle(ChatFormatting.RED));
                return;
            }
            // 余额剩余空间不足时提前夹取/拒绝，避免“物品已被收走、钱却因上限被吞掉”。
            long max = Config.maxBalanceLimit();
            long room = Math.max(0L, max - account.balance);
            if (entry.price <= 0L || room < entry.price) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }
            int affordable = (int) Math.min(Integer.MAX_VALUE, room / entry.price);
            int wanted = Math.min(requested <= 0 ? 1 : requested, Math.min(entry.remaining(), affordable));
            int taken = takeMatching(player, entry.stack.getItem(), wanted);
            if (taken <= 0) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.not_enough_items").withStyle(ChatFormatting.RED));
                return;
            }
            long total = safeMultiply(entry.price, taken);
            if (total <= 0L) {
                // 收购价异常（溢出/非正数）：把已经取走的物品原样还给玩家，不做任何结算。
                deliver(player, entry.stack, taken, SOURCE_ADMIN);
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }
            entry.sold += taken;
            account.balance = Math.min(max, account.balance + total);
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SELL, "模块商店",
                    total, account.balance, entry.stack.getHoverName().getString() + "×" + taken);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.sold_count", entry.stack.getHoverName(), taken, total)
                    .withStyle(ChatFormatting.GREEN));
            // 当日收购清单全部卖完 → 一次性奖励（在推送快照前结算，界面立刻能看到）。
            maybeGrantBuybackBonus(player, account, data);
            sendSnapshot(player, "buyback");
        }
    }

    /** 从背包中按数量取走匹配物品，返回实际取走的数量。 */
    private static int takeMatching(ServerPlayer player, Item item, int max) {
        Inventory inventory = player.getInventory();
        int taken = 0;
        for (int i = 0; i < inventory.getContainerSize() && taken < max; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int take = Math.min(max - taken, stack.getCount());
            stack.shrink(take);
            taken += take;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    // =========================================================== 拍卖

    private static void auctionSetStart(ServerPlayer player, CompoundTag tag) {
        ItemStack stack = PENDING_ITEM.remove(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            return;
        }
        long price = parsePrice(tag.getString("price"));
        if (price <= 0) {
            addItemOrMail(player, stack, SOURCE_ADMIN);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price").withStyle(ChatFormatting.RED));
            return;
        }
        ShopSavedData data = data(player.server);
        if (data.auctionsOf(player.getUUID()).size() >= Config.INSTANCE.maxConcurrentAuctions.get()) {
            addItemOrMail(player, stack, SOURCE_ADMIN);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.auction_limit").withStyle(ChatFormatting.RED));
            return;
        }
        AuctionEntry auction = new AuctionEntry();
        auction.id = data.nextAuctionId++;
        auction.seller = player.getUUID();
        auction.sellerName = player.getGameProfile().getName();
        auction.stack = stack;
        auction.startPrice = price;
        auction.currentBid = 0;
        // 现实时间：持续 auctionDurationSeconds 秒（默认 2 小时）
        auction.endTime = System.currentTimeMillis() + Config.INSTANCE.auctionDurationSeconds.get() * 1000L;
        data.auctions.add(auction);
        data.setDirty();
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.auction_created", stack.getHoverName(), price)
                .withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "auction");
    }

    public static void placeBid(ServerPlayer player, CompoundTag tag) {
        // 需求 8：竞拍校验与扣款/退款必须在同一把锁内，避免同时出价导致余额错乱。
        synchronized (LOCK) {
        ShopSavedData data = data(player.server);
        long id = tag.getLong("id");
        long amount = parsePositive(tag.getString("amount"));
        AuctionEntry auction = null;
        for (AuctionEntry entry : data.auctions) {
            if (entry.id == id) {
                auction = entry;
                break;
            }
        }
        if (auction == null) {
            return;
        }
        if (System.currentTimeMillis() >= auction.endTime) {
            // 已经到期但还没被结算的挂单不再接受出价，避免“截止后再出价也算成交”。
            return;
        }
        if (auction.seller != null && auction.seller.equals(player.getUUID())) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.bid_own").withStyle(ChatFormatting.RED));
            return;
        }
        long minimum = auction.hasBid()
                ? auction.currentBid + Config.INSTANCE.auctionMinIncrement.get()
                : auction.startPrice;
        if (amount < minimum) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.bid_too_low", minimum)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        PlayerAccount bidder = account(player);
        if (bidder.balance < amount) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money").withStyle(ChatFormatting.RED));
            return;
        }
        // 退还上一位出价者
        if (auction.hasBid()) {
            // 需求三：被超价日志同时附带旧价格（上一次最高价）与新价格（本次出价）。
            long previousPrice = auction.currentBid;
            PlayerAccount previous = data.account(auction.topBidder);
            // 退款同样走饱和入账：余额装不下的部分改成金额邮件退回，绝不静默销毁。
            long refunded = credit(previous, previousPrice, Config.maxBalanceLimit());
            if (refunded < previousPrice) {
                addMoneyMail(player.server, auction.topBidder, previousPrice - refunded, ItemStack.EMPTY,
                        SOURCE_AUCTION_REFUND, "系统", null);
            }
            TransactionLog.record(player.server, auction.topBidder, TransactionLog.AUCTION_REFUND, "拍卖",
                    refunded, previous.balance, auction.stack.getHoverName().getString());
            ServerPlayer previousPlayer = player.server.getPlayerList().getPlayer(auction.topBidder);
            if (previousPlayer != null) {
                notifyPlayer(previousPlayer, Component.translatable("msg.server_shop_mod.outbid",
                        auction.stack.getHoverName(), previousPrice, amount).withStyle(ChatFormatting.YELLOW));
                // 钱已经退回（余额或邮箱），立刻刷新他的界面余额，避免显示还在冻结中。
                sendSnapshot(previousPlayer, "auction", true);
            }
            // 需求 10：被超价的一方收到拍卖提示红点（离线也持久化）。
            NotificationManager.notify(player.server, auction.topBidder, NotificationType.AUCTION);
        }
        if (debit(bidder, amount, "auction_bid") < 0L) { return; }
        TransactionLog.record(player.server, player.getUUID(), TransactionLog.AUCTION_BID,
                auction.sellerName == null ? "" : auction.sellerName, -amount, bidder.balance,
                auction.stack.getHoverName().getString());
        // 需求 3：记录上一次出价，供竞拍界面把被超过的价格划掉展示。
        auction.lastBid = auction.currentBid;
        auction.currentBid = amount;
        auction.topBidder = player.getUUID();
        auction.topBidderName = player.getGameProfile().getName();
        if (Config.INSTANCE.antiSnipe.get()) {
            long remaining = auction.endTime - System.currentTimeMillis();
            long threshold = Config.INSTANCE.antiSnipeSeconds.get() * 1000L;
            long extension = Config.INSTANCE.antiSnipeExtensionSeconds.get() * 1000L;
            if (extension > 0 && remaining < threshold) {
                auction.endTime += extension;
            }
        }
        data.setDirty();
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.bid_placed", amount, auction.stack.getHoverName())
                .withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "auction");
        }
    }

    // =========================================================== 摊位

    public static void stallSetPriceNew(ServerPlayer player, CompoundTag tag) {
        ItemStack stack = PENDING_ITEM.remove(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            return;
        }
        long price = parsePrice(tag.getString("price"));
        // 需求 4：上架时可设置库存数量（单件价格），最大 64，且不能超过实际持有量。
        int requested = parseInt(tag.getString("quantity"));
        int total = Math.min(64, requested <= 0 ? Math.max(1, stack.getCount()) : requested);
        total = Math.min(total, Math.max(1, stack.getCount()));
        PlayerAccount account = account(player);
        ShopSavedData data = data(player.server);
        if (price <= 0) {
            addItemOrMail(player, stack, SOURCE_ADMIN);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price").withStyle(ChatFormatting.RED));
            return;
        }
        if (account.stall.size() >= Config.INSTANCE.stallSlots.get()) {
            addItemOrMail(player, stack, SOURCE_ADMIN);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.stall_full").withStyle(ChatFormatting.RED));
            return;
        }
        double feePercent = Config.INSTANCE.stallListingFeePercent.get();
        long fee = (long) Math.ceil(price * feePercent / 100.0D);
        if (fee > 0 && account.balance < fee) {
            addItemOrMail(player, stack, SOURCE_ADMIN);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money").withStyle(ChatFormatting.RED));
            return;
        }
        if (debit(account, fee, "fee") < 0L) { return; }
        // 超出上架数量的部分原路退还，避免物品丢失。
        if (total < stack.getCount()) {
            ItemStack leftover = stack.copy();
            leftover.setCount(stack.getCount() - total);
            addItemOrMail(player, leftover, SOURCE_STALL_UNSOLD);
        }
        ItemStack unit = stack.copy();
        unit.setCount(1);
        StallEntry fresh = new StallEntry(unit, price, data.listingCounter++, total);
        // 「商店设置 → 摊位下架时间」：记下上架时刻（游戏刻 + 现实毫秒各一份），限时到期由 tickStallExpire 下架。
        fresh.listedGame = player.server.overworld().getGameTime();
        fresh.listedReal = System.currentTimeMillis();
        account.stall.add(fresh);
        data.setDirty();
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.stall_listed", unit.getHoverName(), price, total)
                .withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "stall");
        broadcastSnapshots(player.server);
    }

    // =========================================================== 玩家求购（悬赏）

    /** 求购单托管退款的物品来源。 */
    public static final String SOURCE_DEMAND_REFUND = "demand_refund";
    /** 求购成交后付给交付者的物品来源。 */
    public static final String SOURCE_DEMAND_REWARD = "demand_reward";
    /** 求购交付的物品投递给发布者（背包 / 邮箱）。 */
    public static final String SOURCE_DEMAND_DELIVER = "demand_deliver";

    /** 按短 id 找求购单。 */
    private static DemandEntry demandOf(ShopSavedData data, String id) {
        if (data == null || id == null || id.isBlank()) {
            return null;
        }
        for (DemandEntry entry : data.demands) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    /** 某玩家当前发布了几条求购。 */
    private static int demandCountOf(ShopSavedData data, UUID who) {
        int count = 0;
        for (DemandEntry entry : data.demands) {
            if (who != null && who.equals(entry.requester)) {
                count++;
            }
        }
        return count;
    }

    /** 生成 8 位十六进制的短单号。 */
    private static String nextDemandId(ShopSavedData data) {
        long value = data.demandCounter++;
        return String.format("%08X", value & 0xFFFFFFFFL);
    }

    /**
     * 把一条求购的**托管悬赏**退还给发布者：金币进余额（装不下的转金钱邮件），
     * 物品进背包（装不下 / 离线则进邮箱）。绝不销毁。
     */
    private static void refundDemand(MinecraftServer server, DemandEntry entry, String sender) {
        if (server == null || entry == null) {
            return;
        }
        if (entry.requester == null) {
            return;
        }
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(entry.requester);
        if (entry.rewardMoney > 0L) {
            long credited = credit(account, entry.rewardMoney, Config.maxBalanceLimit());
            if (credited > 0L) {
                TransactionLog.record(server, entry.requester, TransactionLog.TRANSFER_IN,
                        "求购退款", credited, account.balance, "求购托管退回");
            }
            long rest = entry.rewardMoney - credited;
            if (rest > 0L) {
                addMoneyMail(server, entry.requester, rest, entry.rewardItem.copy(), SOURCE_DEMAND_REFUND,
                        sender, null);
            }
        }
        if (entry.rewardItem != null && !entry.rewardItem.isEmpty()) {
            ItemStack refund = entry.rewardItem.copy();
            ServerPlayer online = server.getPlayerList().getPlayer(entry.requester);
            if (online != null) {
                addItemOrMail(online, refund, SOURCE_DEMAND_REFUND);
            } else {
                addMail(server, entry.requester, refund, SOURCE_DEMAND_REFUND, sender);
            }
        }
        data.setDirty();
    }

    /**
     * 发布求购（悬赏）。
     *
     * <p>取物品方式**不需要额外的选物界面**：玩家**主手**拿「想要的物品」、**副手**放「愿意付出的物品」（可选），
     * 弹窗里填「要几个」「悬赏多少金币」。发布时立刻把悬赏**托管**掉：
     * 金币从余额扣、副手整叠收走 —— 这样交付的人一定拿得到报酬。
     */
    public static void demandCreate(ServerPlayer player, CompoundTag tag) {
        if (!Config.demandEnabled()) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_disabled")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // 想要清单：客户端从「物品搜索界面」发来（最多 12 条，每条 {item:{...}, count}）。
        List<DemandEntry.WantLine> wants = new ArrayList<>();
        ListTag wantTag = tag.getList("wants", 10);
        for (int i = 0; i < wantTag.size() && wants.size() < DemandEntry.MAX_LINES; i++) {
            CompoundTag lineTag = wantTag.getCompound(i);
            ItemStack stack = ShopNbt.loadStack(lineTag, player.level().registryAccess());
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack one = stack.copy();
            one.setCount(1);
            int count = Math.max(1, Math.min(64, lineTag.getInt("count")));
            boolean merged = false;
            for (DemandEntry.WantLine exists : wants) {
                if (ItemStack.isSameItemSameComponents(exists.stack, one)) {
                    exists.count = Math.min(64, exists.count + count);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                wants.add(new DemandEntry.WantLine(one, count));
            }
        }
        if (wants.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_need_hand")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        long money = parsePrice(tag.getString("reward_money"));
        long maxReward = Config.demandMaxReward();
        if (maxReward > 0L && money > maxReward) {
            money = maxReward;
        }
        ItemStack rewardItem = player.getOffhandItem().copy();
        if (money <= 0L && rewardItem.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_need_reward")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        PlayerAccount account = account(player);
        ShopSavedData data = data(player.server);
        if (demandCountOf(data, player.getUUID()) >= Config.demandSlots()) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_full", Config.demandSlots())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        long fee = (long) Math.ceil(money * Config.demandFeePercent() / 100.0D);
        if (money + fee > account.balance) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // 先把「最新的那条上限」守住：超出时把最老的一条撤下并退款。
        while (data.demands.size() >= Config.demandMaxListed()) {
            DemandEntry oldest = data.demands.get(0);
            for (DemandEntry candidate : data.demands) {
                if (candidate.createdReal < oldest.createdReal) {
                    oldest = candidate;
                }
            }
            data.demands.remove(oldest);
            refundDemand(player.server, oldest, "系统");
            if (oldest.requester != null) {
                ServerPlayer onlineOwner = player.server.getPlayerList().getPlayer(oldest.requester);
                if (onlineOwner != null) {
                    notifyPlayer(onlineOwner, Component.translatable("msg.server_shop_mod.demand_expired",
                            oldest.summary()).withStyle(ChatFormatting.YELLOW));
                }
            }
        }
        // 托管：扣金币（含手续费）、收走副手整叠悬赏物品。
        if (debit(account, money + fee, "demand_escrow") < 0L) { return; }
        if (!rewardItem.isEmpty()) {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        DemandEntry entry = new DemandEntry();
        entry.id = nextDemandId(data);
        entry.requester = player.getUUID();
        entry.requesterName = player.getGameProfile().getName();
        entry.wants.addAll(wants);
        entry.rewardMoney = money;
        entry.rewardItem = rewardItem;
        entry.createdGame = player.server.overworld().getGameTime();
        entry.createdReal = System.currentTimeMillis();
        data.demands.add(entry);
        data.setDirty();
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_created",
                entry.summary(), demandRewardText(entry))
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }

    /** 「悬赏内容」的可读文本（金币 + 物品，供聊天与界面复用）。 */
    private static Component demandRewardText(DemandEntry entry) {
        Component money = entry.rewardMoney > 0L ? Component.literal(Long.toString(entry.rewardMoney)) : null;
        Component item = entry.rewardItem != null && !entry.rewardItem.isEmpty()
                ? Component.translatable("gui.server_shop_mod.demand.reward_item",
                        entry.rewardItem.getHoverName(), entry.rewardItem.getCount())
                : null;
        if (money != null && item != null) {
            return Component.translatable("gui.server_shop_mod.demand.reward_both", money, item);
        }
        return money != null ? money : (item != null ? item : Component.empty());
    }

    /** 撤销自己的求购（管理员可撤销任意一条）：托管原样退回。 */
    public static void demandCancel(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        DemandEntry entry = demandOf(data, tag.getString("id"));
        if (entry == null) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_gone")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        boolean admin = player.hasPermissions(2);
        if (!admin && !player.getUUID().equals(entry.requester)) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_not_yours")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        data.demands.remove(entry);
        refundDemand(player.server, entry, admin && !player.getUUID().equals(entry.requester)
                ? "管理员" : "系统");
        data.setDirty();
        if (admin && !player.getUUID().equals(entry.requester)) {
            ServerPlayer onlineOwner = player.server.getPlayerList().getPlayer(entry.requester);
            if (onlineOwner != null) {
                notifyPlayer(onlineOwner, Component.translatable("msg.server_shop_mod.demand_admin_cancelled",
                        entry.summary()).withStyle(ChatFormatting.YELLOW));
            }
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_admin_cancelled_self",
                    entry.summary()).withStyle(ChatFormatting.GREEN));
        } else {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_cancelled")
                    .withStyle(ChatFormatting.YELLOW));
        }
        broadcastSnapshots(player.server);
    }

    /**
     * 交付（成交）：交付者背包里必须真的有足够的物品，交出去之后悬赏当场付给他。
     * 悬赏金币按余额上限入账（装不下的转金钱邮件），悬赏物品进背包 / 邮箱，绝不私吞。
     */
    private static void demandFulfill(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        DemandEntry entry = demandOf(data, tag.getString("id"));
        if (entry == null) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_gone")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (player.getUUID().equals(entry.requester)) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_self")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // 逐条清点：清单里每一种都必须够数，才允许交付（避免只交一半）。
        for (DemandEntry.WantLine line : entry.wants) {
            ItemStack template = line.stack.copy();
            template.setCount(1);
            int have = countMatching(player, template);
            if (have < line.count) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_not_enough_line",
                        line.stack.getHoverName(), line.count, have).withStyle(ChatFormatting.RED));
                return;
            }
        }
        // 扣物品：先全部取出来记在 taken 里；只要有一条失败，**已取出的全部退回**（绝不半途吞物品）。
        List<ItemStack> takenStacks = new ArrayList<>();
        boolean takeFailed = false;
        for (DemandEntry.WantLine line : entry.wants) {
            ItemStack template = line.stack.copy();
            template.setCount(1);
            int taken = takeMatchingStack(player, template, line.count);
            if (taken <= 0) {
                takeFailed = true;
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_not_enough_line",
                        line.stack.getHoverName(), line.count, 0).withStyle(ChatFormatting.RED));
                break;
            }
            ItemStack got = template.copy();
            got.setCount(taken);
            takenStacks.add(got);
            if (taken < line.count) {
                takeFailed = true;
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_not_enough_line",
                        line.stack.getHoverName(), line.count, taken).withStyle(ChatFormatting.RED));
                break;
            }
        }
        if (takeFailed) {
            for (ItemStack back : takenStacks) {
                addItemOrMail(player, back, SOURCE_DEMAND_REFUND);
            }
            return;
        }
        data.demands.remove(entry);
        // ★ 把 B 交出来的物品**投递给 A**：在线直接进背包（背包满转邮箱），离线装进同一封邮件。
        // 之前这里只发了"求购已完成"的通知邮件、物品没投递，于是玩家看到"钱扣了、东西没到、邮箱里也是空的"。
        ServerPlayer demandOwner = player.server.getPlayerList().getPlayer(entry.requester);
        if (demandOwner != null) {
            for (ItemStack got : takenStacks) {
                addItemOrMail(demandOwner, got, SOURCE_DEMAND_DELIVER);
            }
        } else if (entry.requester != null && !takenStacks.isEmpty()) {
            MailTemplates.DraftMail deliverDraft = MailTemplates.build(
                    MailTemplates.Template.DEMAND_DONE, MailTemplates.map(
                            "player", nameOf(player.server, entry.requester),
                            "item", entry.summary().getString(),
                            "count", Integer.toString(entry.totalCount()),
                            "target", player.getGameProfile().getName()))
                    .source(SOURCE_DEMAND_DELIVER);
            for (ItemStack got : takenStacks) {
                deliverDraft.addItem(got);
            }
            addMailDraft(player.server, entry.requester, deliverDraft);
        }
        // 付悬赏（金币按余额上限入账，装不下的转金钱邮件；悬赏物品进背包 / 邮箱）
        PlayerAccount account = account(player);
        if (entry.rewardMoney > 0L) {
            long credited = credit(account, entry.rewardMoney, Config.maxBalanceLimit());
            long rest = entry.rewardMoney - credited;
            if (credited > 0L) {
                TransactionLog.record(player.server, player.getUUID(), TransactionLog.TRANSFER_IN,
                        "求购:" + (entry.requesterName == null ? "?" : entry.requesterName),
                        credited, account.balance, "交付 " + entry.summary().getString());
            }
            if (rest > 0L) {
                addMoneyMail(player.server, player.getUUID(), rest, entry.rewardItem.copy(),
                        SOURCE_DEMAND_REWARD, entry.requesterName, entry.requester);
            }
        }
        if (entry.rewardItem != null && !entry.rewardItem.isEmpty()) {
            addItemOrMail(player, entry.rewardItem.copy(), SOURCE_DEMAND_REWARD);
        }
        // 通知双方
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_fulfilled",
                entry.summary(), entry.requesterName).withStyle(ChatFormatting.GREEN));
        ServerPlayer owner = player.server.getPlayerList().getPlayer(entry.requester);
        if (owner != null) {
            notifyPlayer(owner, Component.translatable("msg.server_shop_mod.demand_done_owner",
                    entry.summary(), player.getGameProfile().getName()).withStyle(ChatFormatting.GOLD));
        }
        if (entry.requester != null) {
            // 发布者离线也要点亮邮箱红点（物品会用投递邮件送过去，见上面）
            NotificationManager.notify(player.server, entry.requester, NotificationType.MAIL);
            if (owner != null) {
                // 在线：物品已经直接进背包了，这里再发一封纯通知邮件（不重复附物品）
                addMailDraft(player.server, entry.requester, MailTemplates.build(
                        MailTemplates.Template.DEMAND_DONE, MailTemplates.map(
                                "player", player.getGameProfile().getName(),
                                "item", entry.summary().getString(),
                                "count", Integer.toString(entry.totalCount()),
                                "target", player.getGameProfile().getName())));
            }
        }
        data.setDirty();
        broadcastSnapshots(player.server);
    }

    /** 管理员：求购基本设置（开关 / 每人条数 / 手续费 / 单笔上限）。 */
    private static void adminDemandSet(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        boolean enabled = "1".equals(tag.getString("enabled").trim())
                || "true".equalsIgnoreCase(tag.getString("enabled").trim());
        int slots;
        int fee;
        long maxReward;
        try {
            slots = Integer.parseInt(tag.getString("slots").trim());
            fee = Integer.parseInt(tag.getString("fee").trim());
            maxReward = Long.parseLong(tag.getString("max_reward").trim());
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_bad_settings")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (slots < 1 || slots > 45 || fee < 0 || fee > 100 || maxReward < 0L) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_bad_settings")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.demandEnabled.set(enabled);
        Config.INSTANCE.demandSlots.set(slots);
        Config.INSTANCE.demandFeePercent.set((double) fee);
        Config.INSTANCE.demandMaxReward.set((int) Math.min(Integer.MAX_VALUE, maxReward));
        Config.SPEC.save();
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_settings_saved",
                Component.translatable(enabled ? "gui.server_shop_mod.choice.enabled.1"
                        : "gui.server_shop_mod.choice.enabled.0"),
                slots, fee, maxReward <= 0L ? Component.translatable("gui.server_shop_mod.demand.no_limit")
                        : Component.literal(Long.toString(maxReward)))
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }

    /** 管理员：求购自动下架时间（永久 / 游戏天数 / 现实小时）。 */
    private static void adminDemandExpire(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        String modeText = tag.getString("expire_mode").trim().toLowerCase(java.util.Locale.ROOT);
        String mode = ("game".equals(modeText) || "real".equals(modeText)) ? modeText : "permanent";
        int amount;
        try {
            amount = Integer.parseInt(tag.getString("expire_amount").trim());
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.stall_expire_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (amount < 0 || amount > 100000) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.stall_expire_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.demandExpireMode.set(mode);
        Config.INSTANCE.demandExpireAmount.set(amount);
        Config.SPEC.save();
        Component desc = "permanent".equals(mode) || amount <= 0
                ? Component.translatable("gui.server_shop_mod.choice.expire_mode.permanent")
                : Component.translatable("gui.server_shop_mod.stall.expire_value",
                        Component.translatable("gui.server_shop_mod.choice.expire_mode." + mode), amount);
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.demand_expire_saved", desc)
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }

    /**
     * 求购**自动下架**：到时间就把这条求购撤下，并把托管的悬赏原样退回发布者（余额 / 背包 / 邮箱）。
     * 老存档没有发布时间的条目会在第一次 tick 补打时间戳，不会被"立刻撤下"。
     */
    public static void tickDemandExpire(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ShopSavedData data = data(server);
        boolean dirty = false;
        if (Config.demandExpireActive()) {
            boolean byGame = "game".equals(Config.demandExpireMode());
            long limit = byGame ? Config.demandExpireGameTicks() : Config.demandExpireRealMillis();
            long nowGame = server.overworld().getGameTime();
            long nowReal = System.currentTimeMillis();
            for (int i = data.demands.size() - 1; i >= 0; i--) {
                DemandEntry entry = data.demands.get(i);
                if (entry.createdGame <= 0L) {
                    entry.createdGame = nowGame;
                    dirty = true;
                }
                if (entry.createdReal <= 0L) {
                    entry.createdReal = nowReal;
                    dirty = true;
                }
                long age = byGame ? nowGame - entry.createdGame : nowReal - entry.createdReal;
                if (age < limit) {
                    continue;
                }
                data.demands.remove(i);
                dirty = true;
                refundDemand(server, entry, "系统");
                ServerPlayer owner = entry.requester == null ? null
                        : server.getPlayerList().getPlayer(entry.requester);
                if (owner != null) {
                    notifyPlayer(owner, Component.translatable("msg.server_shop_mod.demand_expired",
                            entry.summary()).withStyle(ChatFormatting.YELLOW));
                    sendSnapshot(owner, "demand");
                }
            }
        }
        if (dirty) {
            data.setDirty();
            broadcastSnapshots(server);
        }
    }

    /**
     * 摊位**自动下架**：按「商店设置 → 摊位 → 下架时间」把到期的商品撤下来。
     *
     * <p>规则：
     * <ul>
     *     <li>{@code permanent}（或时长为 0）→ 永不下架，直接返回；</li>
     *     <li>{@code game} → 上架后超过「游戏天数 × 24000 刻」就下架；</li>
     *     <li>{@code real} → 上架后超过「现实小时 × 3600000 毫秒」就下架；</li>
     * </ul>
     * 下架时**剩余库存全部退回**：在线直接进背包，背包满了 / 离线则进邮箱（绝不销毁），
     * 并给摊主一条说明。老存档里没有上架时刻的商品会在第一次 tick 时补打时间戳（给一个完整的限时窗口）。
     */
    public static void tickStallExpire(MinecraftServer server) {
        if (server == null || !Config.stallExpireActive()) {
            return;
        }
        boolean byGame = "game".equals(Config.stallExpireMode());
        long limit = byGame ? Config.stallExpireGameTicks() : Config.stallExpireRealMillis();
        if (limit <= 0L) {
            return;
        }
        long nowGame = server.overworld().getGameTime();
        long nowReal = System.currentTimeMillis();
        ShopSavedData data = data(server);
        boolean dirty = false;
        boolean touched = false;
        for (Map.Entry<UUID, PlayerAccount> pair : data.accounts().entrySet()) {
            UUID owner = pair.getKey();
            PlayerAccount account = pair.getValue();
            if (account.stall.isEmpty()) {
                continue;
            }
            ServerPlayer online = server.getPlayerList().getPlayer(owner);
            boolean removed = false;
            for (int i = account.stall.size() - 1; i >= 0; i--) {
                StallEntry entry = account.stall.get(i);
                // 老存档 / 老数据：先补打上架时刻，不立刻下架。
                if (entry.listedGame <= 0L) {
                    entry.listedGame = nowGame;
                    dirty = true;
                }
                if (entry.listedReal <= 0L) {
                    entry.listedReal = nowReal;
                    dirty = true;
                }
                long age = byGame ? nowGame - entry.listedGame : nowReal - entry.listedReal;
                if (age < limit) {
                    continue;
                }
                account.stall.remove(i);
                dirty = true;
                touched = true;
                removed = true;
                int refundCount = Math.max(0, entry.stock);
                ItemStack name = entry.stack.copy();
                if (refundCount > 0) {
                    ItemStack refund = entry.stack.copy();
                    refund.setCount(refundCount);
                    // 在线：直接进背包（满了自动转邮箱）；离线：直接进邮箱。
                    if (online != null) {
                        addItemOrMail(online, refund, SOURCE_STALL_EXPIRED);
                    } else {
                        addMail(server, owner, refund, SOURCE_STALL_EXPIRED, "系统");
                    }
                }
                if (online != null) {
                    notifyPlayer(online, Component.translatable("msg.server_shop_mod.stall_expired",
                            name.getHoverName(), refundCount).withStyle(ChatFormatting.YELLOW));
                }
            }
            // 一个玩家可能有多个商品同时到期：这里只推一次快照，免得刷屏。
            if (removed && online != null) {
                sendSnapshot(online, "stall");
            }
        }
        if (dirty) {
            data.setDirty();
        }
        if (touched) {
            broadcastSnapshots(server);
        }
    }

    private static void stallApplyPrice(ServerPlayer player, CompoundTag tag) {
        int index = tag.getInt("index");
        long price = parsePrice(tag.getString("price"));
        PlayerAccount account = account(player);
        if (index < 0 || index >= account.stall.size() || price <= 0) {
            return;
        }
        account.stall.get(index).price = price;
        data(player.server).setDirty();
        sendSnapshot(player, "stall");
        broadcastSnapshots(player.server);
    }

    private static void removeStall(ServerPlayer player, int index) {
        PlayerAccount account = account(player);
        if (index < 0 || index >= account.stall.size()) {
            return;
        }
        StallEntry entry = account.stall.remove(index);
        // 需求 6：下架时把剩余库存（不是 1 件）全部退还。
        if (entry.stock > 0) {
            ItemStack refund = entry.stack.copy();
            refund.setCount(entry.stock);
            addItemOrMail(player, refund, SOURCE_STALL_UNSOLD);
        }
        data(player.server).setDirty();
        sendSnapshot(player, "stall");
        broadcastSnapshots(player.server);
    }

    private static void openStallBrowse(ServerPlayer player, String ownerUuid) {
        UUID owner;
        try {
            owner = UUID.fromString(ownerUuid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        ShopSavedData data = data(player.server);
        PlayerAccount account = data.peek(owner);
        if (account == null) {
            return;
        }
        HolderLookup.Provider regs = player.level().registryAccess();
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "stall_browse");
        tag.putString("owner", owner.toString());
        tag.putString("ownerName", account.name.isEmpty() ? "Unknown" : account.name);
        ListTag list = new ListTag();
        for (int i = 0; i < account.stall.size(); i++) {
            StallEntry entry = account.stall.get(i);
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            c.putLong("price", entry.price);
            c.putInt("stock", entry.stock);
            // 需求 9：浏览摊位时也显示该商品已售出件数。
            c.putInt("sold", entry.sold);
            c.putInt("index", i);
            list.add(c);
        }
        tag.put("entries", list);
        ClientboundShopSyncPayload.send(player, tag);
    }

    private static void stallBuy(ServerPlayer player, CompoundTag tag) {
        UUID owner;
        try {
            owner = UUID.fromString(tag.getString("owner"));
        } catch (IllegalArgumentException ex) {
            return;
        }
        int index = tag.getInt("index");
        // 需求 5：购买数量由玩家在弹窗中选择，服务端再次夹取到库存与余额上限。
        int requested = parseInt(tag.getString("quantity"));
        int want = requested <= 0 ? 1 : requested;
        if (owner.equals(player.getUUID())) {
            return;
        }
        synchronized (LOCK) {
            ShopSavedData data = data(player.server);
            PlayerAccount sellerAccount = data.peek(owner);
            // 需求 9：按索引从服务端真实摊位数据取物品与价格，不信任客户端。
            if (sellerAccount == null || index < 0 || index >= sellerAccount.stall.size()) {
                return;
            }
            StallEntry entry = sellerAccount.stall.get(index);
            if (entry.stock <= 0) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.stall_sold_out").withStyle(ChatFormatting.RED));
                return;
            }
            int buyCount = Math.min(want, entry.stock);
            // 摊位单价由玩家自己设置，同样必须做溢出保护（裸乘溢出为负 → 判定失效 + 给买家加钱）。
            long total = safeMultiply(entry.price, buyCount);
            if (total <= 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount buyer = account(player);
            if (buyer.balance < total) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money").withStyle(ChatFormatting.RED));
                return;
            }
            if (debit(buyer, total, "stall_buy") < 0L) { return; }
            entry.stock -= buyCount;
            // 需求 9：记录该商品累计已售出件数（各商品分别统计）。
            entry.sold += buyCount;
            // 第七轮改动：卖家货款**直接进钱包**（饱和入账；只有余额上限装不下的部分才转金额邮件兜底，绝不销毁）。
            PlayerAccount stallSellerAcct = data.account(owner);
            long credited = credit(stallSellerAcct, total, Config.maxBalanceLimit());
            if (credited < total) {
                addMoneyMail(player.server, owner, total - credited, entry.stack.copy(), SOURCE_STALL_SOLD,
                        player.getGameProfile().getName(), player.getUUID());
            }
            ServerPlayer onlineSeller = player.server.getPlayerList().getPlayer(owner);
            if (onlineSeller != null) {
                notifyPlayer(onlineSeller, Component.translatable("msg.server_shop_mod.stall_sold_wallet",
                        entry.stack.getHoverName(), buyCount, total).withStyle(ChatFormatting.GREEN));
            }
            // 售空提醒：该商品库存清零时给摊主一封提醒，替代原来的「每笔金币邮件」。
            if (entry.stock <= 0) {
                addMailDraft(player.server, owner, MailTemplates.build(MailTemplates.Template.STALL_DEPLETED,
                                MailTemplates.map("player", nameOf(player.server, owner),
                                        "item", MailTemplates.itemText(entry.stack, false),
                                        "amount", Integer.toString(entry.sold),
                                        "price", Long.toString(total)))
                        .source(SOURCE_STALL_SOLD));
            }
            // 需求 5/10：保留 NBT；装不下的部分发到邮箱。
            // 来源用“购买”而不是“上架未售出”，否则买家邮箱里会显示成“你上架的物品没卖出去，已退还”。
            Component rewardName = entry.stack.getHoverName();
            boolean mailed = deliver(player, entry.stack, buyCount, SOURCE_STALL_BOUGHT);
            data.setDirty();
            // 需求一：摘要统一为「花费 金额 星币 购买 物品名×数量」，金额字段在前。
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.bought_count", total, rewardName, buyCount)
                    .withStyle(ChatFormatting.GREEN));
            if (mailed) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.inventory_full_mail").withStyle(ChatFormatting.RED));
            }
            ServerPlayer seller = player.server.getPlayerList().getPlayer(owner);
            if (seller != null) {
                notifyPlayer(seller, Component.translatable("msg.server_shop_mod.stall_sold", rewardName, buyCount, total)
                        .withStyle(ChatFormatting.GREEN));
            }
            // 需求 10：卖家摊位有物品被买走 → 提示红点。
            NotificationManager.notify(player.server, owner, NotificationType.STALL);
            String sellerName = sellerAccount.name == null || sellerAccount.name.isEmpty() ? "摊位" : sellerAccount.name;
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.STALL_BUY, sellerName,
                    -total, buyer.balance, rewardName + "×" + buyCount);
            TransactionLog.record(player.server, owner, TransactionLog.STALL_SELL,
                    player.getGameProfile().getName(), total, sellerAccount.balance,
                    rewardName + "×" + buyCount + "（货款存邮箱）");
        }
        // 静默刷新买家缓存，并把最新库存推回正在浏览的摊位界面（需求 5：买完还剩多少显示多少）。
        sendSnapshot(player, "stall", true);
        broadcastSnapshots(player.server);
        openStallBrowse(player, owner.toString());
    }

    // =========================================================== 邮箱

    /** 第七轮：切换邮件星标（置顶显示），成功后回推快照。 */
    private static void toggleMailStar(ServerPlayer player, String mailId) {
        PlayerAccount account = account(player);
        MailEntry entry = findMail(account, mailId);
        if (entry == null) {
            return;
        }
        entry.starred = !entry.starred;
        data(player.server).setDirty();
        notifyPlayer(player, Component.translatable(entry.starred
                ? "msg.server_shop_mod.mail_starred" : "msg.server_shop_mod.mail_unstarred")
                .withStyle(ChatFormatting.GOLD));
        sendSnapshot(player, "mail");
    }

    /** 按 mailId 在服务端真实邮箱里查表，绝不信任客户端传来的下标。 */
    private static MailEntry findMail(PlayerAccount account, String mailId) {
        if (mailId == null || mailId.isEmpty()) {
            return null;
        }
        for (MailEntry entry : account.mailbox) {
            if (mailId.equals(entry.mailId)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 物品类邮件：领取进背包。背包放不下的部分继续留在邮件里（绝不丢 NBT，也不会重复发放）。
     * 并发安全：整段在 LOCK 内执行，并且只有全部放入后才置 isClaimed=true。
     */
    private static void claimMail(ServerPlayer player, String mailId) {
        // 第七轮：领取前先算背包空间，装不下就拦截（不消费邮件）。
        if (!fitsInBagOrWarn(player, pendingItems(player, mailId))) {
            return;
        }
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            MailEntry entry = findMail(account, mailId);
            if (entry == null || entry.type != MailType.ITEM || entry.isClaimed || !entry.hasItems()) {
                return;
            }
            // 第八轮：一封邮件可能装多件物品，逐件尝试放入，装不下的部分留在邮件里。
            List<ItemStack> left = new ArrayList<>();
            StringBuilder names = new StringBuilder();
            for (ItemStack stack : entry.itemStacks()) {
                ItemStack remaining = stack.copy();
                player.getInventory().add(remaining);
                if (!remaining.isEmpty()) {
                    left.add(remaining);
                }
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append(stack.getHoverName().getString());
            }
            entry.setItems(left);
            if (!left.isEmpty()) {
                // 邮件里的数量已经在内存里减少，必须标脏落盘：SavedData 只写 isDirty() 的数据，
                // 否则定时重启/崩溃后邮件恢复原数量，等于“部分领取 + 重启”复制物品。
                data(player.server).setDirty();
                // 部分物品没领完：明确告诉玩家剩余物品仍在邮箱，清理背包后可再次「全部领取」。
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.claim_all_partial")
                        .withStyle(ChatFormatting.RED));
                sendSnapshot(player, "mail");
                return;
            }
            entry.isClaimed = true;
            data(player.server).setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.MAIL_ITEM, "邮箱",
                    0L, account.balance, names.toString());
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mail_claimed", names.toString())
                    .withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "mail");
        }
    }

    /** 金额类邮件：把星币加进钱包并标记已查看。 */
    private static void viewMail(ServerPlayer player, String mailId) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            MailEntry entry = findMail(account, mailId);
            if (entry == null || entry.type != MailType.MONEY || entry.isViewed) {
                return;
            }
            long amount = entry.currencyAmount;
            long credited = credit(account, amount, Config.maxBalanceLimit());
            if (credited < amount) {
                // 余额装不下：只领能装下的部分，剩下的留在邮件里（绝不静默销毁）。
                entry.currencyAmount = amount - credited;
                data(player.server).setDirty();
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.mail_partial_money",
                        credited, entry.currencyAmount).withStyle(ChatFormatting.GOLD));
                sendSnapshot(player, "mail");
                return;
            }
            entry.isViewed = true;
            data(player.server).setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.MAIL_MONEY, "邮箱",
                    credited, account.balance, "");
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mail_viewed", amount)
                    .withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "mail");
        }
    }

    /** 一键领取/查看所有未处理邮件；背包放不下的物品留在邮箱并提示。 */
    private static void claimAllMail(ServerPlayer player) {
        // 第七轮优化：不再整批拦截 —— 金额先到账（金额不占背包），物品逐封尝试放入，
        // 放不下的那部分**留在邮箱**并在最后统一提示，避免"因为一件物品放不下，连钱都领不到"。
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            if (account.mailbox.isEmpty()) {
                return;
            }
            boolean changed = false;
            boolean full = false;
            for (MailEntry entry : account.mailbox) {
                if (entry.type == MailType.MONEY) {
                    if (!entry.isViewed) {
                        long amount = entry.currencyAmount;
                        long credited = credit(account, amount, Config.maxBalanceLimit());
                        if (credited < amount) {
                            // 装不下的部分留在邮件里，等余额有空间再领。
                            entry.currencyAmount = amount - credited;
                        } else {
                            entry.isViewed = true;
                        }
                        if (credited > 0L) {
                            TransactionLog.record(player.server, player.getUUID(), TransactionLog.MAIL_MONEY,
                                    "邮箱", credited, account.balance, "");
                        }
                        changed = true;
                    }
                    continue;
                }
                if (entry.isClaimed || !entry.hasItems()) {
                    continue;
                }
                // 第八轮：一封邮件多件物品，逐件放入；放不下的留在邮件里。
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack stack : entry.itemStacks()) {
                    ItemStack remaining = stack.copy();
                    player.getInventory().add(remaining);
                    if (!remaining.isEmpty()) {
                        left.add(remaining);
                    }
                }
                entry.setItems(left);
                if (!left.isEmpty()) {
                    // 背包满：该物品留在邮箱，但继续处理后面的金额邮件（金额不占背包）。
                    full = true;
                    // 数量已在内存里减少，同样要标脏落盘（理由见 claimMail）。
                    changed = true;
                    continue;
                }
                entry.isClaimed = true;
                changed = true;
            }
            if (changed) {
                data(player.server).setDirty();
            }
            if (full) {
                // 部分物品没领完：明确告诉玩家剩余物品仍在邮箱，清理背包后可再次「全部领取」。
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.claim_all_partial")
                        .withStyle(ChatFormatting.RED));
            }
            sendSnapshot(player, "mail");
        }
    }

    /**
     * 删除一封邮件。未领取/未查看的邮件必须带 confirmed=true（客户端弹二次确认），
     * 服务端同样强制校验，防止误删重要资产。
     */
    private static void deleteMail(ServerPlayer player, String mailId, boolean confirmed) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            MailEntry entry = findMail(account, mailId);
            if (entry == null) {
                return;
            }
            if (entry.pending() && !confirmed) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.mail_delete_need_confirm")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            account.mailbox.remove(entry);
            data(player.server).setDirty();
            sendSnapshot(player, "mail");
        }
    }

    /** 需求四：一键清理所有已经领取（物品）/已查看（金额）的邮件。 */
    private static void deleteReadMail(ServerPlayer player) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            boolean removed = account.mailbox.removeIf(entry -> !entry.pending());
            if (!removed) {
                return;
            }
            data(player.server).setDirty();
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mail_deleted_read")
                    .withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "mail");
        }
    }

    // =========================================================== 钱包 / 转账

    private static void doTransfer(ServerPlayer player, CompoundTag tag) {
        if (!Config.INSTANCE.allowPlayerTransfer.get()) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.transfer_disabled").withStyle(ChatFormatting.RED));
            return;
        }
        String targetName = tag.getString("target");
        long amount = parsePositive(tag.getString("amount"));
        if (targetName.isEmpty() || amount <= 0) {
            return;
        }
        ServerPlayer target = player.server.getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.target_offline").withStyle(ChatFormatting.RED));
            // 需求一备用模板：转账失败日志「向 目标玩家名 转账 金额 星币失败」。
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.transfer_failed", targetName, amount)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (target == player) {
            return;
        }
        // 需求 7：同一 IP 的玩家之间不可转账，管理员不受限制。
        if (sameIp(player, target) && !player.hasPermissions(2) && !target.hasPermissions(2)) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.transfer_same_ip").withStyle(ChatFormatting.RED));
            return;
        }
        ShopSavedData data = data(player.server);
        // 需求 4/8：余额校验与扣款放在同一把锁内，防止并发转账把钱扣成负数。
        synchronized (LOCK) {
            PlayerAccount from = account(player);
            long now = player.level().getGameTime();
            if (now - from.lastTransferTick < Config.INSTANCE.transferCooldownTicks.get()) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.transfer_cooldown").withStyle(ChatFormatting.RED));
                return;
            }
            if (from.balance < amount) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.no_money").withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount to = account(target);
            // 对方余额装不下这笔转账：整笔拒绝，避免“付款方已扣、收款方只收到一部分”。
            long room = Math.max(0L, Config.maxBalanceLimit() - to.balance);
            if (room < amount) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.target_balance_full",
                        target.getGameProfile().getName()).withStyle(ChatFormatting.RED));
                return;
            }
            if (debit(from, amount, "transfer") < 0L) { return; }
            to.balance += amount;
            from.lastTransferTick = now;
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.TRANSFER_OUT,
                    target.getGameProfile().getName(), -amount, from.balance, "");
            TransactionLog.record(player.server, target.getUUID(), TransactionLog.TRANSFER_IN,
                    player.getGameProfile().getName(), amount, to.balance, "");
        }
        // 需求一：转账模板固定为「已向 目标玩家名 转账 金额 星币」/「收到 目标玩家名 的转账 金额 星币」，
        // 目标名在前、金额在后，靠字段位置区分，不再靠“文本是不是数字”判断。
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.transfer_sent",
                target.getGameProfile().getName(), amount).withStyle(ChatFormatting.GREEN));
        notifyPlayer(target, Component.translatable("msg.server_shop_mod.transfer_received",
                player.getGameProfile().getName(), amount).withStyle(ChatFormatting.GREEN));
        // 需求 4：收到转账后不再在转账按钮上显示提示（移除钱包红点），只保留聊天栏通知。
        sendSnapshot(player, "wallet");
        // 收款方的界面余额也要刷新，否则他看到的还是转账前的数字（大额转入后立刻消费会误判）。
        sendSnapshot(target, "wallet", true);
    }

    /** 需求 7：比较两名玩家的连接 IP。 */
    private static boolean sameIp(ServerPlayer a, ServerPlayer b) {
        String ipA = a.getIpAddress();
        String ipB = b.getIpAddress();
        return ipA != null && !ipA.isEmpty() && ipA.equals(ipB);
    }

    // =========================================================== 管理员配置

    /** 设置页“交易记录”按钮：把某玩家的流水发给管理员本人。 */
    private static void sendHistory(ServerPlayer admin, String targetName, int page) {
        List<Component> lines = historyLines(admin.server, targetName, page <= 0 ? 1 : page);
        if (lines.isEmpty()) {
            notifyPlayer(admin, Component.translatable("msg.server_shop_mod.grant_unknown", targetName)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        for (Component line : lines) {
            notifyPlayer(admin, line);
        }
    }

    private static void adminSetPriceNew(ServerPlayer player, CompoundTag tag) {
        // 需求 11：模板来自幽灵槽，不是 PENDING_ITEM。
        ItemStack stack = ADMIN_TEMPLATE.remove(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            return;
        }
        // 幽灵槽只保存模板副本、原物已经退回管理员背包（见 ADMIN_TEMPLATE 注释），
        // 价格非法时直接中止，绝不能再把模板当真实物品发一次（否则输入非数字价格就能凭空复制物品）。
        long price = parseAdminPrice(player, tag.getString("price"));
        if (price <= 0) {
            return;
        }
        AdminEditType type = editType(player, tag);
        ShopSavedData data = data(player.server);
        Rarity rarity = parseRarity(tag.getString("rarity"), price);
        // 需求 6/7：管理员可同时配置品质与数量（数量为空则退回默认上限）。
        if (type == AdminEditType.MALL) {
            // 购物城：数量字段是**库存**（留空 / -1 = 无限，0 = 售罄）。
            MallItem item = new MallItem(stack, price, rarity, parseMallStock(tag.getString("quantity")));
            item.sort = data.mallPool.size();
            data.mallPool.add(item);
        } else if (type == AdminEditType.SHIPPING) {
            // 出货箱：这里是「兑换规则」——只需要物品 + 单价（每日份数由各等级的「每日额度」统一管）。
            if (!ShippingRules.addRule(stack, price)) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_rule_added",
                    stack.getHoverName(), price + currencyName()).withStyle(ChatFormatting.GREEN));
        } else if (type == AdminEditType.DAILY_SHOP) {
            ConfiguredOffer offer = new ConfiguredOffer(stack, price, rarity);
            offer.amount = parseInt(tag.getString("quantity"));
            data.shopPool.add(offer);
        } else {
            ConfiguredOffer offer = new ConfiguredOffer(stack, price, rarity);
            offer.amount = parseInt(tag.getString("quantity"));
            data.buybackPool.add(offer);
        }
        data.setDirty();
        // 购物城加完物品直接回购物城页（马上就能看到新上架的那件）；商店/收购仍留在管理页。
        sendSnapshot(player, type == AdminEditType.MALL ? "mall" : "admin");
    }

    /** 购物城库存：留空 / 非数字 / 负数 = 无限库存（界面显示 ∞）。 */
    private static int parseMallStock(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? -1 : parsed;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static void adminApplyPrice(ServerPlayer player, CompoundTag tag) {
        int index = tag.getInt("index");
        // 确认后输入界面会立刻关闭：这里必须给一条**说明原因**的反馈，否则管理员会以为「确认没生效」。
        long price = parseAdminPrice(player, tag.getString("price"));
        if (price <= 0) {
            return;
        }
        AdminEditType type = editType(player, tag);
        ShopSavedData data = data(player.server);
        if (type == AdminEditType.MALL) {
            if (index < 0 || index >= data.mallPool.size()) {
                return;
            }
            MallItem item = data.mallPool.get(index);
            item.price = price;
            item.rarity = parseRarity(tag.getString("rarity"), price);
            // 改库存同时更新「补货目标」：管理员填多少，定时补货以后就补回多少。
            item.setStock(parseMallStock(tag.getString("quantity")));
        } else if (type == AdminEditType.SHIPPING) {
            // 出货箱的"条目"就是兑换规则：只改单价（格式错误的那几行不能改，只能右键删掉重写）。
            if (!ShippingRules.updatePrice(index, price)) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_rule_broken")
                        .withStyle(ChatFormatting.RED));
            }
        } else {
            List<ConfiguredOffer> pool = type == AdminEditType.DAILY_SHOP ? data.shopPool : data.buybackPool;
            if (index < 0 || index >= pool.size()) {
                return;
            }
            ConfiguredOffer offer = pool.get(index);
            offer.price = price;
            // 需求 6/7：商店与收购都可单独设置品质、数量；品质留空时按价格推导。
            offer.rarity = parseRarity(tag.getString("rarity"), price);
            offer.amount = parseInt(tag.getString("quantity"));
        }
        data.setDirty();
        sendSnapshot(player, "admin");
    }

    private static void adminRemove(ServerPlayer player, CompoundTag tag) {
        int index = tag.getInt("index");
        AdminEditType type = editType(player, tag);
        ShopSavedData data = data(player.server);
        if (type == AdminEditType.MALL) {
            if (index < 0 || index >= data.mallPool.size()) {
                return;
            }
            // 与每日商店一致：条目只是模板，原物始终留在管理员背包里，这里不再邮寄（否则等于复制物品）。
            data.mallPool.remove(index);
        } else if (type == AdminEditType.SHIPPING) {
            // 出货箱：删掉这条兑换规则（会写回配置文件）；格式错误的行同样能这样删掉。
            ShippingRules.removeEntry(index);
        } else {
            List<ConfiguredOffer> pool = type == AdminEditType.DAILY_SHOP ? data.shopPool : data.buybackPool;
            if (index < 0 || index >= pool.size()) {
                return;
            }
            pool.remove(index);
        }
        data.setDirty();
        sendSnapshot(player, "admin");
    }

    // =========================================================== 货币兑换
    //
    // 管理员配置「1 个物品 = N 星币」，玩家两个方向都能用：
    //   · 兑换（物品 → 星币）：按汇率把背包里的该货币折算成星币；
    //   · 取款（星币 → 物品）：按汇率扣星币后把货币物品发回玩家（背包放不下进邮箱）。
    // 所有汇率都用 ExchangeEntry.value 与 safeMultiply 处理，绝不出现负数或溢出。

    /** 取兑换条目并校验；下标越界或条目非法返回 null。 */
    private static ExchangeEntry exchangeEntry(ShopSavedData data, int index) {
        if (index < 0 || index >= data.exchangePool.size()) {
            return null;
        }
        ExchangeEntry entry = data.exchangePool.get(index);
        return entry != null && entry.valid() ? entry : null;
    }

    /** 背包中与该模板完全一致（同物品 + 同组件）的货币总数（用于“全部兑换”的默认数量与上限）。 */
    private static int countMatching(ServerPlayer player, ItemStack template) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 取走与该模板完全一致的货币，返回实际取走的数量。
     * 用「同物品 + 同组件」匹配而不是只比物品：管理员把某个带 NBT/自定义名称的货币设为兑换项时，
     * 普通同名物品不能被拿来兑换（否则等于用普通物品换特殊货币）。
     */
    private static int takeMatchingStack(ServerPlayer player, ItemStack template, int max) {
        Inventory inventory = player.getInventory();
        int taken = 0;
        for (int i = 0; i < inventory.getContainerSize() && taken < max; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) {
                continue;
            }
            int take = Math.min(max - taken, stack.getCount());
            stack.shrink(take);
            taken += take;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    /** 「兑换」按钮：弹数量输入框，默认填满背包持有量。 */
    private static void exchangeDepositGui(ServerPlayer player, int index) {
        ExchangeEntry entry = exchangeEntry(data(player.server), index);
        if (entry == null) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int owned = countMatching(player, entry.stack);
        if (owned <= 0) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_no_items")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", index);
        extra.putString("default_quantity", Integer.toString(owned));
        // 标题里带上真实货币名（客户端会用本地语言渲染，如「兑换数量（银币 → 星币）」）。
        openInput(player, "exchange_deposit", "gui.server_shop_mod.input.exchange_deposit", extra,
                new String[]{entry.stack.getHoverName().getString(), Config.INSTANCE.currencyName.get()},
                "quantity");
    }

    /** 「取款」按钮：弹数量输入框，默认 1 个，界面显示当前余额。 */
    private static void exchangeWithdrawGui(ServerPlayer player, int index) {
        ExchangeEntry entry = exchangeEntry(data(player.server), index);
        if (entry == null) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        PlayerAccount account = account(player);
        long affordable = account.balance / entry.value;
        if (affordable <= 0L) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_withdraw_no_money", entry.value)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int max = (int) Math.min(Integer.MAX_VALUE, affordable);
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", index);
        extra.putString("default_quantity", Integer.toString(Math.min(max, Math.max(1, entry.stack.getMaxStackSize()))));
        extra.putLong("balance", account.balance);
        extra.putBoolean("show_balance", true);
        // 让输入界面能实时判断“填的数量买不起”，并在右侧红字提示「余额不足」。
        extra.putLong("unit_price", entry.value);
        extra.putString("total_key", "gui.server_shop_mod.exchange.total");
        openInput(player, "exchange_withdraw", "gui.server_shop_mod.input.exchange_withdraw", extra,
                new String[]{Config.INSTANCE.currencyName.get(), entry.stack.getHoverName().getString()},
                "quantity");
    }

    /** 兑换（物品 → 星币）：余额装不下的部分不取走物品，绝不让物品凭空消失。 */
    private static void exchangeDeposit(ServerPlayer player, CompoundTag tag) {
        synchronized (LOCK) {
            ExchangeEntry entry = exchangeEntry(data(player.server), tag.getInt("index"));
            if (entry == null) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_invalid")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            long max = Config.maxBalanceLimit();
            long room = Math.max(0L, max - account.balance);
            if (room < entry.value) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.grant_cap_reached")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            int owned = countMatching(player, entry.stack);
            if (owned <= 0) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_no_items")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            int requested = parseInt(tag.getString("quantity"));
            int wanted = Math.min(requested <= 0 ? owned : requested, owned);
            int affordable = (int) Math.min(Integer.MAX_VALUE, room / entry.value);
            wanted = Math.min(wanted, affordable);
            if (wanted <= 0 || safeMultiply(entry.value, wanted) <= 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.grant_cap_reached")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            int taken = takeMatchingStack(player, entry.stack, wanted);
            if (taken <= 0) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_no_items")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            long gain = safeMultiply(entry.value, taken);
            long added = credit(account, gain, max);
            if (added < gain) {
                // 兜底：上面已按余额空间夹取，这里只防配置在两次读取之间被改小，差额原样退回物品。
                int back = (int) Math.min(Integer.MAX_VALUE, (gain - added) / entry.value);
                if (back > 0) {
                    deliver(player, entry.stack, back, SOURCE_EXCHANGE);
                }
            }
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.EXCHANGE_BUY, "货币兑换",
                    added, account.balance, entry.stack.getHoverName().getString() + "×" + taken);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_done",
                    entry.stack.getHoverName(), taken, added).withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "exchange");
        }
    }

    /** 取款（星币 → 物品）：先确认余额再扣款发货，背包放不下的进邮箱。 */
    private static void exchangeWithdraw(ServerPlayer player, CompoundTag tag) {
        synchronized (LOCK) {
            ExchangeEntry entry = exchangeEntry(data(player.server), tag.getInt("index"));
            if (entry == null) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_invalid")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            int requested = parseInt(tag.getString("quantity"));
            int affordable = (int) Math.min(Integer.MAX_VALUE, account.balance / entry.value);
            int wanted = Math.min(requested <= 0 ? 1 : requested, affordable);
            long cost = safeMultiply(entry.value, wanted);
            if (wanted <= 0 || cost <= 0L || cost > account.balance) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_withdraw_no_money",
                        Math.max(entry.value, cost)).withStyle(ChatFormatting.RED));
                return;
            }
            if (debit(account, cost, "purchase") < 0L) { return; }
            boolean mailed = deliver(player, entry.stack, wanted, SOURCE_EXCHANGE);
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.EXCHANGE_WITHDRAW, "货币兑换",
                    -cost, account.balance, entry.stack.getHoverName().getString() + "×" + wanted);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_withdraw_done",
                    cost, entry.stack.getHoverName(), wanted).withStyle(ChatFormatting.GREEN));
            if (mailed) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.inventory_full_mail")
                        .withStyle(ChatFormatting.YELLOW));
            }
            sendSnapshot(player, "exchange");
        }
    }

    /** 管理员：新增兑换货币（模板来自幽灵槽，价格来自输入框）；同一物品重复添加视为改价。 */
    private static void adminExchangeSet(ServerPlayer player, CompoundTag tag) {
        ItemStack stack = ADMIN_TEMPLATE.remove(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            return;
        }
        long value = parsePrice(tag.getString("value"));
        if (value <= 0L) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ShopSavedData data = data(player.server);
        ExchangeEntry entry = new ExchangeEntry(stack, value);
        entry.normalize();
        for (ExchangeEntry existing : data.exchangePool) {
            if (ItemStack.isSameItemSameComponents(existing.stack, entry.stack)) {
                existing.value = value;
                data.setDirty();
                sendSnapshot(player, "exchange");
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_updated",
                        entry.stack.getHoverName(), value).withStyle(ChatFormatting.GREEN));
                return;
            }
        }
        data.exchangePool.add(entry);
        data.setDirty();
        sendSnapshot(player, "exchange");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_added",
                entry.stack.getHoverName(), value).withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：改汇率（界面左键点击条目）。 */
    private static void adminExchangeEdit(ServerPlayer player, CompoundTag tag) {
        int index = tag.getInt("index");
        ShopSavedData data = data(player.server);
        long value = parsePrice(tag.getString("value"));
        ExchangeEntry entry = exchangeEntry(data, index);
        if (entry == null || value <= 0L) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        entry.value = value;
        data.setDirty();
        sendSnapshot(player, "exchange");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_updated",
                entry.stack.getHoverName(), value).withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：移除兑换货币（模板不退还，原因同商品池：退还会复制物品）。 */
    private static void adminExchangeRemove(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        ExchangeEntry entry = exchangeEntry(data, tag.getInt("index"));
        if (entry == null) {
            return;
        }
        data.exchangePool.remove(entry);
        data.setDirty();
        sendSnapshot(player, "exchange");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.exchange_removed",
                entry.stack.getHoverName()).withStyle(ChatFormatting.YELLOW));
    }

    // =========================================================== 签到（需求 7）

    /** 现实日期（本地时区当天，epochDay）：签到按现实 0 点刷新。 */
    public static long realDay() {
        return java.time.LocalDate.now().toEpochDay();
    }

    /** 连续签到到某天数时，每天额外多拿多少星币（累计加成，关闭时返回 0）。 */
    private static long cumulativeBonus(ShopSavedData data, int streak) {
        if (!data.signInCumulative) {
            return 0L;
        }
        long bonus = 0L;
        for (int i = 0; i < data.signInTierDays.length && i < data.signInTierBonus.length; i++) {
            if (streak >= data.signInTierDays[i]) {
                bonus = Math.max(bonus, data.signInTierBonus[i]);
            }
        }
        return bonus;
    }

    /** 真正结算一次签到（玩家点击或登录自动）。 */
    private static void doSignIn(ServerPlayer player) {
        synchronized (LOCK) {
            ShopSavedData data = data(player.server);
            if (!data.signInEnabled) {
                return;
            }
            long today = realDay();
            SignInData sign = data.signIn(player.getUUID());
            if (sign.signedOn(today)) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.signin_done")
                        .withStyle(ChatFormatting.YELLOW));
                sendSnapshot(player, "signin");
                return;
            }
            sign.streak = sign.lastSignDay == today - 1L ? sign.streak + 1 : 1;
            sign.lastSignDay = today;
            sign.signedDays.add(today);
            if (sign.streak > sign.bestStreak) {
                sign.bestStreak = sign.streak;
            }
            PlayerAccount account = account(player);
            long max = Config.maxBalanceLimit();
            long reward = Math.max(0L, data.signInBaseDaily) + cumulativeBonus(data, sign.streak);
            long added = credit(account, reward, max);
            // 里程奖励：连续签到达到配置天数时额外发一次（同一轮只发一次）。
            long milestone = 0L;
            for (int i = 0; i < data.signInMileDays.length && i < data.signInMileMoney.length; i++) {
                int need = data.signInMileDays[i];
                if (sign.streak >= need && !sign.claimedMilestones.contains(need)) {
                    sign.claimedMilestones.add(need);
                    milestone += Math.max(0L, data.signInMileMoney[i]);
                }
            }
            long mileAdded = milestone > 0L ? credit(account, milestone, max) : 0L;
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SIGN_IN, "每日签到",
                    added + mileAdded, account.balance, Component.translatable("msg.server_shop_mod.signin_note",
                            sign.streak).getString());
            // 事件：签到完成（成功后发布）
            cn.autoforged.server_shop_mod_1789689358.api.event.ApiEvents.post(
                    new cn.autoforged.server_shop_mod_1789689358.api.event.SignInCompletedEvent(
                            player.getUUID(), ShopManager.realDay(), sign.streak, added + mileAdded));
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.signin_ok",
                    added, sign.streak).withStyle(ChatFormatting.GREEN));
            if (mileAdded > 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.signin_milestone", mileAdded)
                        .withStyle(ChatFormatting.GOLD));
            }
            if (added + mileAdded <= 0L) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.grant_cap_reached")
                        .withStyle(ChatFormatting.RED));
            }
            sendSnapshot(player, "signin");
        }
    }

    /**
     * 从已签到记录重算「当前连续天数」：以今天（或最后一次签到日）为终点向前数。
     * 补签把缺口补上以后，连续天数会重新连成一条，连续签到加成也就随之生效。
     */
    private static int recomputeStreak(SignInData sign, long today) {
        long cursor;
        if (sign.signedDays.contains(today)) {
            cursor = today;
        } else if (sign.signedDays.contains(today - 1L)) {
            cursor = today - 1L;
        } else {
            cursor = sign.lastSignDay;
        }
        int streak = 0;
        while (cursor > 0L && sign.signedDays.contains(cursor)) {
            streak++;
            cursor--;
        }
        return streak;
    }

    /** 补签：花星币把过去某一天补成已签到，并**重算连续天数**（补上缺口后连续签到加成立即生效）。 */
    private static void doMakeupSign(ServerPlayer player, long day) {
        synchronized (LOCK) {
            ShopSavedData data = data(player.server);
            long today = realDay();
            SignInData sign = data.signIn(player.getUUID());
            if (!sign.canMakeup(day, today, data.signInMakeupDays)) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.signin_makeup_invalid")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount account = account(player);
            long cost = Math.max(0L, data.signInMakeupCost);
            if (account.balance < cost) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.signin_makeup_no_money", cost)
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (debit(account, cost, "purchase") < 0L) { return; }
            sign.signedDays.add(day);
            // 补签后重算连续天数（只增不减，避免把原本更高的记录改小）：
            // 之前补签只补记录、不动连续天数，所以玩家补完签加成永远激活不了。
            int recomputed = recomputeStreak(sign, today);
            if (recomputed > sign.streak) {
                sign.streak = recomputed;
            }
            if (sign.streak > sign.bestStreak) {
                sign.bestStreak = sign.streak;
            }
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SIGN_MAKEUP, "补签",
                    -cost, account.balance, "补签 " + day);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.signin_makeup_ok_streak",
                    cost, sign.streak).withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "signin");
        }
    }

    /** 管理员：登录自动签到开关。 */
    private static void adminSignInAutoToggle(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        data.signInAuto = !data.signInAuto;
        data.setDirty();
        sendSnapshot(player, "signin");
        notifyPlayer(player, Component.translatable(data.signInAuto
                ? "msg.server_shop_mod.signin_auto_on" : "msg.server_shop_mod.signin_auto_off")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：累计签到加成开关（关闭时界面不显示里程进度条）。 */
    private static void adminSignInCumulativeToggle(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        data.signInCumulative = !data.signInCumulative;
        data.setDirty();
        sendSnapshot(player, "signin");
        notifyPlayer(player, Component.translatable(data.signInCumulative
                ? "msg.server_shop_mod.signin_cumulative_on" : "msg.server_shop_mod.signin_cumulative_off")
                .withStyle(ChatFormatting.GREEN));
    }

    /**
     * 管理员：一次性修改签到设置。
     * fields：base=每日基础奖励，makeupCost=补签花费，makeupDays=可补签天数，
     * tiers=「天数:每日加成」逗号分隔（如 3:5,5:7,7:10），miles=「天数:一次性奖励」逗号分隔。
     */
    private static void adminSignInSet(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        long base = parsePositive(tag.getString("base"));
        long cost = parsePositive(tag.getString("makeupCost"));
        int days = parseInt(tag.getString("makeupDays"));
        int[] tiers = parsePairs(tag.getString("tiers"), data.signInTierDays);
        long[] bonuses = parsePairValues(tag.getString("tiers"), data.signInTierBonus);
        int[] miles = parsePairs(tag.getString("miles"), data.signInMileDays);
        long[] money = parsePairValues(tag.getString("miles"), data.signInMileMoney);
        if (base >= 0L) {
            data.signInBaseDaily = base;
        }
        data.signInMakeupCost = cost;
        if (days > 0) {
            data.signInMakeupDays = Math.min(30, days);
        }
        System.arraycopy(tiers, 0, data.signInTierDays, 0, Math.min(tiers.length, data.signInTierDays.length));
        System.arraycopy(bonuses, 0, data.signInTierBonus, 0, Math.min(bonuses.length, data.signInTierBonus.length));
        System.arraycopy(miles, 0, data.signInMileDays, 0, Math.min(miles.length, data.signInMileDays.length));
        System.arraycopy(money, 0, data.signInMileMoney, 0, Math.min(money.length, data.signInMileMoney.length));
        data.signInEnabled = data.signInBaseDaily > 0L || data.signInMileMoney[0] > 0L;
        data.setDirty();
        sendSnapshot(player, "signin");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.signin_set_ok")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 解析「3:5,5:7,7:10」里的天数部分；解析失败时保留原值。 */
    private static int[] parsePairs(String text, int[] fallback) {
        int[] out = fallback.clone();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            String[] kv = parts[i].split(":");
            if (kv.length == 2) {
                int v = parseInt(kv[0]);
                if (v > 0) {
                    out[i] = v;
                }
            }
        }
        return out;
    }

    /** 解析「3:5,5:7,7:10」里的数值部分；解析失败时保留原值。 */
    private static long[] parsePairValues(String text, long[] fallback) {
        long[] out = fallback.clone();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            String[] kv = parts[i].split(":");
            if (kv.length == 2) {
                out[i] = parsePositive(kv[1]);
            }
        }
        return out;
    }

    /** 登录时按设置自动签到。 */
    public static void autoSignInIfNeeded(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        if (!data.signInEnabled || !data.signInAuto) {
            return;
        }
        if (!data.signIn(player.getUUID()).signedOn(realDay())) {
            doSignIn(player);
        }
    }

    /**
     * 管理员：设置某一天为活动日（标题、公告正文、当天登录发放的星币、是否给离线玩家补发）。
     * 不填标题且金额为 0 视为取消该活动日。
     */
    private static void adminActivityDaySet(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        long day = tag.getLong("day");
        if (day <= 0L) {
            day = realDay();
        }
        String title = tag.getString("title").trim();
        String note = tag.getString("note").trim();
        // 第八轮：公告/颜色/时间由「日程编辑」界面写，规则开关由「日程奖励设置」写，奖励由「日程奖励」容器写。
        // 每个界面只写自己负责的字段，**未携带的键一律保持原值**，避免被别的界面的空值覆盖
        // （此前奖励界面会用它打开时捕获的标题回写，标题就丢了）。
        ActivityDay existing = data.activityDay(day);
        if (!tag.contains("title") && !tag.contains("note") && !tag.contains("money")
                && !tag.contains("color") && !tag.contains("hour")
                && !tag.contains("offline") && !tag.contains("catchUp")) {
            // 什么都没带：只推一次快照，不动数据。
            sendSnapshot(player, "signin");
            return;
        }
        long money = tag.contains("money")
                ? parsePositive(tag.getString("money"))
                : (existing == null ? 0L : existing.money);
        boolean offline = tag.contains("offline")
                ? ("true".equalsIgnoreCase(tag.getString("offline").trim())
                        || "1".equals(tag.getString("offline").trim()))
                : existing != null && existing.forOffline;
        boolean hasItems = existing != null && !existing.rewardStacks().isEmpty();
        // 清空条件：界面明确提交了空标题，且没有金额、没有物品奖励。
        if (tag.contains("title") && title.isEmpty() && money <= 0L && !hasItems) {
            data.activityDays.remove(day);
            data.setDirty();
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.activity_cleared")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            ActivityDay act = data.setActivityDay(day);
            if (tag.contains("title")) {
                act.title = title;
            }
            if (tag.contains("note")) {
                act.note = note;
            }
            if (tag.contains("money")) {
                act.money = money;
            }
            if (tag.contains("offline")) {
                act.forOffline = offline;
            }
            if (tag.contains("catchUp")) {
                // 第七项：错过即补发开关（客户端默认 true）。
                act.catchUp = !"false".equalsIgnoreCase(tag.getString("catchUp").trim());
            }
            if (tag.contains("color")) {
                act.color = parseColor(tag.getString("color"));
            }
            if (tag.contains("hour")) {
                act.hour = parseHour(tag.getString("hour"));
            }
            if (tag.contains("gift")) {
                ItemStack giftStack = parseItemText(tag.getString("gift"));
                act.gift = giftStack.isEmpty() ? ItemStack.EMPTY : giftStack;
                act.giftGranted.clear();
            }
            if (tag.contains("item")) {
                ItemStack itemStack = parseItemText(tag.getString("item"));
                act.stack = itemStack.isEmpty() ? ItemStack.EMPTY : itemStack;
                act.itemGranted.clear();
            }
            data.setDirty();
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.activity_set", day)
                    .withStyle(ChatFormatting.GREEN));
        }
        sendSnapshot(player, "signin");
    }

    /** 奖励物品的「id xN」文本（客户端据此画物品图标）。 */
    private static String rewardIdsText(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append(" x").append(stack.getCount());
        }
        return sb.toString();
    }

    /** 管理员：取消某天的活动日。 */
    private static void adminActivityDayClear(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        data.activityDays.remove(tag.getLong("day"));
        data.setDirty();
        sendSnapshot(player, "signin");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.activity_cleared")
                .withStyle(ChatFormatting.YELLOW));
    }

    // =========================================================== 日程奖励（第八轮：物品发放式界面）

    /** 正在编辑「日程奖励」的管理员 → 目标日期 + 打开时的原始槽位内容。 */
    private record RewardEdit(long day, List<ItemStack> prefill) {
    }

    private static final Map<UUID, RewardEdit> REWARD_EDIT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 管理员：打开某天的「日程奖励」界面。
     * <p>
     * 这里**直接复用物品发放的容器**（{@link GrantMenu}：3×9 真实物品槽）。
     * 打开时把该日程**当前的奖励物品预填进槽位**，管理员看到的就是现配置，直接增删即可；
     * 关闭时只退还「管理员自己加进去的那部分」，预填部分不会被退成实物（见
     * {@link #adminActivityRewardCancel}），所以既不会丢东西也不会复制物品。
     */
    private static void adminActivityRewardOpen(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        long day = tag.getLong("day");
        if (day <= 0L) {
            day = realDay();
        }
        // 先推一次快照：界面右侧的「当前奖励」直接读客户端缓存，保证是最新值。
        sendSnapshot(player, "signin");
        List<ItemStack> prefill = new ArrayList<>();
        ActivityDay act = data(player.server).activityDay(day);
        if (act != null) {
            for (ItemStack stack : act.rewardStacks()) {
                if (prefill.size() >= GrantMenu.GRANT_SLOTS) {
                    break;
                }
                prefill.add(stack.copy());
            }
        }
        while (prefill.size() < GrantMenu.GRANT_SLOTS) {
            prefill.add(ItemStack.EMPTY);
        }
        SimpleContainer container = new SimpleContainer(GrantMenu.GRANT_SLOTS);
        for (int i = 0; i < GrantMenu.GRANT_SLOTS; i++) {
            container.setItem(i, prefill.get(i).copy());
        }
        REWARD_EDIT.put(player.getUUID(), new RewardEdit(day, prefill));
        // 标题由客户端自绘（面板是深色的，原版标题色在暗底上看不清），这里传空标题。
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new GrantMenu(
                        cn.autoforged.server_shop_mod_1789689358.menu.ModMenus.SCHEDULE_REWARD.get(), id, inventory,
                        container),
                Component.empty()));
    }

    /** 管理员：保存「日程奖励」界面里的物品与金额到该日程。 */
    private static void adminActivityRewardSave(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        RewardEdit edit = REWARD_EDIT.remove(player.getUUID());
        long day = edit != null ? edit.day() : tag.getLong("day");
        if (day <= 0L) {
            day = realDay();
        }
        if (!(player.containerMenu instanceof GrantMenu menu)) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.grant_no_menu")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Container container = menu.grant();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        long money = Math.min(parsePositive(tag.getString("money")), Config.maxBalanceLimit());
        ShopSavedData data = data(player.server);
        synchronized (LOCK) {
            ActivityDay act = data.activityDay(day);
            if (act == null && items.isEmpty() && money <= 0L) {
                // 既没有物品也没有金额：不要凭空建出一个空日程。
                clearContainer(container);
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.grant_nothing")
                        .withStyle(ChatFormatting.RED));
                sendSnapshot(player, "signin");
                return;
            }
            act = data.setActivityDay(day);
            // 先清空槽位再落库：物品已经写进日程，绝不能再被 removed() 退还给管理员（否则等于复制物品）。
            clearContainer(container);
            act.rewards.clear();
            act.rewards.addAll(items);
            act.money = money;
            // 奖励改了，之前“已领过物品”的记录一并清空：新的奖励应该重新发一次。
            act.rewardsGranted.clear();
            act.itemGranted.clear();
            act.giftGranted.clear();
            act.stack = ItemStack.EMPTY;
            act.gift = ItemStack.EMPTY;
            data.setDirty();
        }
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.activity_reward_set",
                LocalDate.ofEpochDay(day).toString(), items.size(),
                money + currencyName()).withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "signin");
    }

    /**
     * 管理员：取消「日程奖励」编辑。
     * <p>
     * 只把**管理员自己放进去的那部分**退还给背包（预填的旧奖励原样留在日程里）：
     * 槽位内容与打开时一致 → 不退还；只是数量变多 → 退还多出来的部分；换成别的东西 → 退还新的那件。
     */
    private static void adminActivityRewardCancel(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            return;
        }
        RewardEdit edit = REWARD_EDIT.remove(player.getUUID());
        if (!(player.containerMenu instanceof GrantMenu menu)) {
            return;
        }
        Container container = menu.grant();
        List<ItemStack> prefill = edit == null ? List.of() : edit.prefill();
        synchronized (LOCK) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack current = container.getItem(i);
                if (current.isEmpty()) {
                    continue;
                }
                ItemStack original = i < prefill.size() ? prefill.get(i) : ItemStack.EMPTY;
                container.setItem(i, ItemStack.EMPTY);
                if (!original.isEmpty() && ItemStack.matches(current, original)) {
                    // 原封不动：属于日程里的旧奖励，不退还。
                    continue;
                }
                if (!original.isEmpty() && current.getItem() == original.getItem()
                        && current.getCount() > original.getCount()) {
                    // 同一种物品只是加量：只退还多加的那部分。
                    giveBack(player, current.copyWithCount(current.getCount() - original.getCount()));
                    continue;
                }
                giveBack(player, current.copy());
            }
        }
    }

    /** 把槽位全部清空（避免容器关闭时的“退还未领取物品”逻辑把已入库的物品又发给管理员）。 */
    private static void clearContainer(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            container.setItem(i, ItemStack.EMPTY);
        }
    }

    private static boolean loggedBalanceCap;

    /**
     * 启动后第一次下发快照时，把生效的余额上限打一行日志。
     * <p>
     * 「余额后面一直显示（已满）」几乎都是配置里的上限太小（比如想写 0 表示不限，
     * 结果被旧的范围下限纠正成 1）。打这一行日志，管理员看一眼控制台就知道该改哪里。
     */
    private static void logBalanceCapOnce() {
        if (loggedBalanceCap) {
            return;
        }
        loggedBalanceCap = true;
        long limit = Config.maxBalanceLimit();
        org.slf4j.LoggerFactory.getLogger("server_shop").info(
                "[模块商店] 余额上限 maxBalance = {}（0 或负数 = 不限；过小会导致余额很快显示「已满」）",
                limit == Long.MAX_VALUE ? "不限" : Long.toString(limit));
    }

    // =========================================================== 兑换码奖励草稿（第八轮：按钮 + 物品发放式界面）
    /**
     * 正在编辑「兑换码奖励」的管理员 → 打开容器时的原始槽位内容。
     * 取消时只退还「管理员自己加进去的那部分」，草稿里的既有物品不会被退成实物。
     */
    private record RedeemRewardEdit(List<ItemStack> prefill) {
    }

    /** 兑换码奖励草稿：管理员在「奖励配置」界面里配好的物品 / 星币 / 指定玩家。 */
    private record RedeemDraft(List<ItemStack> items, long money, List<UUID> players) {
    }

    private static final Map<UUID, RedeemRewardEdit> REDEEM_REWARD_EDIT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, RedeemDraft> REDEEM_DRAFT = new java.util.concurrent.ConcurrentHashMap<>();
    /** 这次打开奖励配置界面是在**改哪个兑换码**（空 = 正在配新兑换码的草稿）。 */
    private static final Map<UUID, String> REDEEM_EDIT_TARGET = new java.util.concurrent.ConcurrentHashMap<>();

    private static RedeemDraft redeemDraft(ServerPlayer player) {
        return REDEEM_DRAFT.getOrDefault(player.getUUID(), new RedeemDraft(List.of(), 0L, List.of()));
    }

    /** 把草稿推给客户端：生成器显示「物品 / 星币 / 玩家」摘要，编辑界面据此回填。 */
    private static void sendRedeemDraft(ServerPlayer player) {
        RedeemDraft draft = redeemDraft(player);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_draft");
        tag.putString("items", MailTemplates.itemsText(draft.items()));
        tag.putLong("money", draft.money());
        ListTag names = new ListTag();
        for (UUID id : draft.players()) {
            names.add(StringTag.valueOf(playerName(player.server, id)));
        }
        tag.put("players", names);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /** UUID → 显示名（在线优先，其次历史账号，都没有时显示短 UUID）。 */
    private static String playerName(MinecraftServer server, UUID id) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getUUID().equals(id)) {
                return online.getGameProfile().getName();
            }
        }
        PlayerAccount account = data(server).accounts().get(id);
        if (account != null && account.name != null && !account.name.isEmpty()) {
            return account.name;
        }
        return id.toString().substring(0, 8);
    }

    /**
     * 管理员：打开「兑换码奖励配置」界面。
     * <p>
     * 与日程奖励一样**复用物品发放的容器**（3×9 真实物品槽）：打开时把当前草稿物品预填进槽位，
     * 管理员看到的/改的就是现配置；关闭时只退还他自己加进去的那部分。
     */
    private static void adminRedeemRewardOpen(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        // 玩家列表与草稿都先推一次，保证界面一打开就有数据（不依赖网络时序）。
        GrantManager.sendTargets(player);
        // 带 code = 改这个已有兑换码（预填它的物品/星币/玩家列表）；不带 = 配新兑换码的草稿。
        String target = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        List<ItemStack> source;
        if (!target.isEmpty()) {
            RedeemCode existing = data(player.server).redeemCodes.get(target);
            if (existing == null) {
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.redeem.notfound")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            REDEEM_EDIT_TARGET.put(player.getUUID(), target);
            source = new ArrayList<>(existing.items);
            // 全体可兑换的码不推玩家名单（界面上"一个都没勾"就是全体玩家）。
            sendRedeemDraftFor(player, existing.items, existing.money,
                    existing.global ? new ArrayList<>() : new ArrayList<>(existing.players));
        } else {
            REDEEM_EDIT_TARGET.remove(player.getUUID());
            RedeemDraft draft = redeemDraft(player);
            source = draft.items();
            sendRedeemDraft(player);
        }
        List<ItemStack> prefill = new ArrayList<>();
        for (ItemStack stack : source) {
            if (prefill.size() >= GrantMenu.GRANT_SLOTS) {
                break;
            }
            prefill.add(stack.copy());
        }
        while (prefill.size() < GrantMenu.GRANT_SLOTS) {
            prefill.add(ItemStack.EMPTY);
        }
        SimpleContainer container = new SimpleContainer(GrantMenu.GRANT_SLOTS);
        for (int i = 0; i < GrantMenu.GRANT_SLOTS; i++) {
            container.setItem(i, prefill.get(i).copy());
        }
        REDEEM_REWARD_EDIT.put(player.getUUID(), new RedeemRewardEdit(prefill));
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new GrantMenu(
                        cn.autoforged.server_shop_mod_1789689358.menu.ModMenus.REDEEM_REWARD.get(), id, inventory,
                        container),
                Component.empty()));
    }

    /** 把「某个兑换码当前的内容」推给客户端（编辑界面回填用）。 */
    private static void sendRedeemDraftFor(ServerPlayer player, List<ItemStack> items, long money,
            List<UUID> players) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_draft");
        tag.putString("items", MailTemplates.itemsText(items));
        tag.putLong("money", money);
        ListTag names = new ListTag();
        for (UUID id : players) {
            names.add(StringTag.valueOf(playerName(player.server, id)));
        }
        tag.put("players", names);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /**
     * 管理员：保存「兑换码奖励配置」界面里的物品 + 星币 + 玩家。
     * <p>
     * 从生成器进来 = 写进服务端草稿（下一步生成兑换码时使用）；
     * 从「编辑兑换码」进来 = **直接改这个兑换码**（已领取记录 {@code usedBy} 原样保留，已领过的玩家不受影响）。
     */
    private static void adminRedeemRewardSave(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        REDEEM_REWARD_EDIT.remove(player.getUUID());
        String target = REDEEM_EDIT_TARGET.getOrDefault(player.getUUID(), "");
        if (!(player.containerMenu instanceof GrantMenu menu)) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.grant_no_menu")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Container container = menu.grant();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        // 先清空槽位再落库：物品已经写进数据，绝不能再被 removed() 退还给管理员（否则等于复制物品）。
        clearContainer(container);
        List<UUID> players = new ArrayList<>();
        ListTag targets = tag.getList("targets", 8);
        for (int i = 0; i < targets.size(); i++) {
            try {
                players.add(UUID.fromString(targets.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID 直接跳过，服务端以自己解析出来的列表为准。
            }
        }
        // 勾选名单可以是空的：**空 = 全体玩家都能兑换**（默认），非空 = 只有名单里的玩家能兑换。
        long money = Math.min(parsePositive(tag.getString("money")), Config.maxBalanceLimit());
        if (target.isEmpty()) {
            REDEEM_DRAFT.put(player.getUUID(), new RedeemDraft(items, money, players));
        } else {
            ShopSavedData data = data(player.server);
            synchronized (LOCK) {
                RedeemCode rc = data.redeemCodes.get(target);
                if (rc != null) {
                    rc.items.clear();
                    rc.items.addAll(items);
                    rc.money = money;
                    rc.global = players.isEmpty();
                    rc.players.clear();
                    if (!rc.global) {
                        rc.players.addAll(players);
                    }
                    // usedBy / usedCount 保持原样：已经领过的玩家就这么算领过了，不再补发。
                    data.setDirty();
                }
            }
            sendRedeemList(player);
        }
        Component scope = players.isEmpty()
                ? Component.translatable("msg.server_shop_mod.redeem_all_players")
                : Component.literal(Integer.toString(players.size()));
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.redeem_reward_set",
                items.size(), money + currencyName(), scope)
                .withStyle(ChatFormatting.GREEN));
        sendRedeemDraft(player);
    }

    /** 管理员：取消「兑换码奖励配置」（只退还自己加进去的物品，草稿/兑换码保持原样）。 */
    private static void adminRedeemRewardCancel(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            return;
        }
        RedeemRewardEdit edit = REDEEM_REWARD_EDIT.remove(player.getUUID());
        if (!(player.containerMenu instanceof GrantMenu menu)) {
            return;
        }
        Container container = menu.grant();
        List<ItemStack> prefill = edit == null ? List.of() : edit.prefill();
        synchronized (LOCK) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack current = container.getItem(i);
                if (current.isEmpty()) {
                    continue;
                }
                ItemStack original = i < prefill.size() ? prefill.get(i) : ItemStack.EMPTY;
                container.setItem(i, ItemStack.EMPTY);
                if (!original.isEmpty() && ItemStack.matches(current, original)) {
                    continue;
                }
                if (!original.isEmpty() && current.getItem() == original.getItem()
                        && current.getCount() > original.getCount()) {
                    giveBack(player, current.copyWithCount(current.getCount() - original.getCount()));
                    continue;
                }
                giveBack(player, current.copy());
            }
        }
        // 编辑已有兑换码时把它的当前内容推回去（界面回填），否则推草稿。
        String target = REDEEM_EDIT_TARGET.getOrDefault(player.getUUID(), "");
        RedeemCode rc = target.isEmpty() ? null : data(player.server).redeemCodes.get(target);
        if (rc != null) {
            sendRedeemDraftFor(player, rc.items, rc.money,
                    rc.global ? new ArrayList<>() : new ArrayList<>(rc.players));
        } else {
            sendRedeemDraft(player);
        }
    }

    /** 退还物品：优先进背包，装不下的原样掉在脚下。 */
    private static void giveBack(ServerPlayer player, ItemStack stack) {
        ItemStack remaining = stack.copy();
        player.getInventory().add(remaining);
        if (!remaining.isEmpty()) {
            player.drop(remaining, false);
        }
    }

    /**
     * 管理员：修改当前模块（每日商店 / 每日收购）的刷新参数。
     * fields：weights=「传说,史诗,稀有,普通」权重，guarantee=「稀有,史诗,传说」保底，slots=条目/牌数上限。
     */
    private static void adminShopGenSet(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        boolean shop = isShopMode(player, tag);
        int[] weights = shop ? data.shopWeights : data.buybackWeights;
        int[] guarantee = shop ? data.shopGuarantee : data.buybackGuarantee;
        int[] newWeights = parseInts(tag.getString("weights"), weights);
        int[] newGuarantee = parseInts(tag.getString("guarantee"), guarantee);
        int slots = parseInt(tag.getString("slots"));
        System.arraycopy(newWeights, 0, weights, 0, weights.length);
        System.arraycopy(newGuarantee, 0, guarantee, 0, guarantee.length);
        if (slots > 0) {
            if (shop) {
                data.shopSlotCount = Math.min(12, slots);
            } else {
                data.buybackSlotCount = Math.min(12, slots);
            }
        }
        data.setDirty();
        refreshDaily(player);
        sendSnapshot(player, "admin");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.gen_set_ok")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 解析「5,15,30,50」形式的整数数组（留空或不足时保留原值）。 */
    private static int[] parseInts(String text, int[] fallback) {
        int[] out = fallback.clone();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            String piece = parts[i].trim();
            if (piece.isEmpty()) {
                continue;
            }
            try {
                out[i] = Math.max(0, Integer.parseInt(piece));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** 解析 #RRGGBB / RRGGBB 颜色（失败返回 0 = 默认橙色）。 */
    private static int parseColor(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String raw = text.trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        try {
            return 0xFF000000 | ((int) Long.parseLong(raw, 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 解析定时小时（0-23；其它值 = -1 不定时）。 */
    private static int parseHour(String text) {
        int hour = parseInt(text);
        return hour >= 0 && hour <= 23 ? hour : -1;
    }

    /** 取玩家名（离线也能取；取不到返回 "-"），供邮件模板的 {player} 占位符使用。 */
    public static String nameOf(MinecraftServer server, @Nullable UUID id) {
        if (id == null) {
            return "-";
        }
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        PlayerAccount account = data(server).peek(id);
        return account == null || account.name == null || account.name.isEmpty() ? "-" : account.name;
    }

    /** 解析「物品ID x数量」文本（如 minecraft:diamond x2）；解析失败返回空栈。 */
    private static ItemStack parseItemText(String text) {
        if (text == null || text.isBlank()) {
            return ItemStack.EMPTY;
        }
        String raw = text.trim();
        int count = 1;
        int idx = raw.lastIndexOf('x');
        if (idx > 0) {
            String num = raw.substring(idx + 1).trim();
            String head = raw.substring(0, idx).trim();
            if (!num.isEmpty() && head.contains(":")) {
                int parsed = parseInt(num);
                if (parsed > 0) {
                    count = Math.min(64, parsed);
                    raw = head;
                }
            }
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(raw);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, count);
    }

    /** 登录/签到时的活动日奖励发放（含物品与离线补发判断）。 */
    public static void grantActivityDay(ServerPlayer player) {
        synchronized (LOCK) {
            ShopSavedData data = data(player.server);
            long today = realDay();
            ActivityDay act = data.activityDay(today);
            if (act == null) {
                return;
            }
            List<ItemStack> dayItems = act.rewardStacks();
            // 星币与物品**分别**判断是否已发：管理员中途改了奖励、清空了「已领物品」记录后，
            // 玩家下次登录仍能拿到新的物品奖励（不会因为当天星币已发就被整段跳过）。
            boolean moneyDone = act.granted.contains(player.getUUID());
            boolean itemsDone = dayItems.isEmpty() || act.itemsGrantedTo(player.getUUID());
            if (moneyDone && itemsDone) {
                return;
            }
            // 定时发放：还没到设定的时间点、且该日程不允许离线领取 → 等在线到点由 tick 发放。
            if (act.hour >= 0 && java.time.LocalTime.now().getHour() < act.hour && !act.forOffline && !act.catchUp) {
                return;
            }
            if (act.money <= 0L && dayItems.isEmpty()) {
                return;
            }
            PlayerAccount account = account(player);
            long added = 0L;
            if (!moneyDone && act.money > 0L) {
                added = credit(account, act.money, Config.maxBalanceLimit());
                act.granted.add(player.getUUID());
            }
            if (!itemsDone) {
                // 活动日物品走邮箱发放（背包满也不会丢）；第八轮起支持一次配置多件物品，并且合并进同一封邮件。
                act.rewardsGranted.add(player.getUUID());
                addMailDraft(player.server, player.getUUID(), MailTemplates.build(
                                MailTemplates.Template.ACTIVITY_REWARD, MailTemplates.map(
                                        "player", player.getGameProfile().getName(),
                                        "activityName", act.title))
                        .source(SOURCE_ADMIN)
                        .addItems(dayItems));
            }
            data.setDirty();
            if (added > 0L) {
                TransactionLog.record(player.server, player.getUUID(), TransactionLog.SIGN_IN, "活动日",
                        added, account.balance, act.title);
                notifyPlayer(player, Component.translatable("msg.server_shop_mod.activity_granted",
                        act.title, added).withStyle(ChatFormatting.GOLD));
            }
            sendSnapshot(player, "signin");
        }
    }

    /** 上一次做活动日离线补发的现实日期（每个日期只跑一次）。 */
    private static long lastOfflineGrantDay = Long.MIN_VALUE;

    /** 每日检查（挂在服务器 tick 上）：活动日离线补发，每个现实日期只执行一次。 */
    /** 定时发放：日程到了设定的现实时间点时，给当时在线的玩家发物品/星币（每人只发一次）。 */
    private static void tickActivitySchedules(MinecraftServer server) {
        ShopSavedData data = data(server);
        ActivityDay act = data.activityDay(realDay());
        if (act == null || act.hour < 0) {
            return;
        }
        if (java.time.LocalTime.now().getHour() != act.hour) {
            return;
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (!act.granted.contains(online.getUUID()) || !act.giftGranted.contains(online.getUUID())) {
                grantActivityDay(online);
            }
        }
    }

    public static void tickActivityDayOffline(MinecraftServer server) {
        tickActivitySchedules(server);
        long today = realDay();
        if (lastOfflineGrantDay == today) {
            return;
        }
        lastOfflineGrantDay = today;
        grantActivityDayOffline(server);
    }

    /** 服务器启动/每日检查：给配置了「补发给离线玩家」的活动日补发。 */
    public static void grantActivityDayOffline(MinecraftServer server) {
        synchronized (LOCK) {
            ShopSavedData data = data(server);
            long today = realDay();
            ActivityDay act = data.activityDay(today);
            if (act == null || !act.forOffline || (act.money <= 0L && act.rewardStacks().isEmpty())) {
                return;
            }
            boolean changed = false;
            List<ItemStack> dayItems = act.rewardStacks();
            for (Map.Entry<UUID, PlayerAccount> entry : data.accounts().entrySet()) {
                // 星币与物品分别判断：当天星币已补发过、但奖励物品后来被改过时，仍然要把物品补上。
                boolean moneyDone = act.granted.contains(entry.getKey());
                boolean itemsDone = dayItems.isEmpty() || act.itemsGrantedTo(entry.getKey());
                if (moneyDone && itemsDone) {
                    continue;
                }
                long added = 0L;
                if (!moneyDone && act.money > 0L) {
                    added = credit(entry.getValue(), act.money, Config.maxBalanceLimit());
                    act.granted.add(entry.getKey());
                }
                if (!itemsDone) {
                    act.rewardsGranted.add(entry.getKey());
                    addMailDraft(server, entry.getKey(), MailTemplates.build(
                                    MailTemplates.Template.ACTIVITY_REWARD, MailTemplates.map(
                                            "player", entry.getValue().name,
                                            "activityName", act.title))
                            .source(SOURCE_ADMIN)
                            .addItems(dayItems));
                }
                changed = true;
                if (added > 0L) {
                    TransactionLog.record(server, entry.getKey(), TransactionLog.SIGN_IN, "活动日（离线补发）",
                            added, entry.getValue().balance, act.title);
                }
            }
            if (changed) {
                data.setDirty();
            }
        }
    }

    // =========================================================== 收购全清奖励

    /**
     * 收购全清奖励的实际金额：管理员覆盖值优先；关闭时返回 0，
     * 于是界面不再显示提示行、maybeGrantBuybackBonus 里的「奖励为 0 就跳过」逻辑也不会发钱。
     */
    public static long buybackBonusAmount(ShopSavedData data) {
        if (data == null || !data.buybackBonusEnabled) {
            return 0L;
        }
        return data.buybackBonusOverride >= 0L ? data.buybackBonusOverride : Config.INSTANCE.buybackAllBonus.get();
    }

    /** 管理员：开关收购全清奖励。 */
    private static void adminBuybackBonusToggle(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        data.buybackBonusEnabled = !data.buybackBonusEnabled;
        data.setDirty();
        sendSnapshot(player, "buyback");
        notifyPlayer(player, Component.translatable(data.buybackBonusEnabled
                ? "msg.server_shop_mod.buyback_bonus_on" : "msg.server_shop_mod.buyback_bonus_off")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：修改收购全清奖励金额（0 = 关闭奖励）。 */
    private static void adminBuybackBonusSet(ServerPlayer player, CompoundTag tag) {
        long amount = parsePrice(tag.getString("amount"));
        ShopSavedData data = data(player.server);
        if (amount < 0L) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        data.buybackBonusOverride = amount;
        data.buybackBonusEnabled = amount > 0L;
        data.setDirty();
        sendSnapshot(player, "buyback");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.buyback_bonus_set", amount)
                .withStyle(ChatFormatting.GREEN));
    }

    // =========================================================== 兑换码（第七轮）

    /**
     * 玩家兑换：统一「去首尾空格 + 转大写」后校验（存在 / 未过期 / 白名单 / 每人限领 / 总上限），
     * 通过则按模板发奖到邮箱并累计使用次数；无论成功失败都回传 {@code kind=redeem_result} 给客户端展示。
     */
    private static void redeemCode(ServerPlayer player, String rawCode) {
        ShopSavedData data = data(player.server);
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        if (code.isEmpty()) {
            sendRedeemResult(player, false, code, "empty");
            return;
        }
        RedeemCode entry = data.redeemCodes.get(code);
        if (entry == null) {
            sendRedeemResult(player, false, code, "notfound");
            return;
        }
        if (entry.expired(now)) {
            sendRedeemResult(player, false, code, "expired");
            return;
        }
        if (!entry.global && !entry.players.contains(player.getUUID())) {
            sendRedeemResult(player, false, code, "scope");
            return;
        }
        if (entry.remainingFor(player.getUUID()) <= 0) {
            sendRedeemResult(player, false, code, "used");
            return;
        }
        if (entry.exhausted()) {
            sendRedeemResult(player, false, code, "exhausted");
            return;
        }
        if (!entry.hasReward()) {
            sendRedeemResult(player, false, code, "noreward");
            return;
        }
        MailTemplates.DraftMail draft = MailTemplates.build(MailTemplates.Template.REDEEM_OK,
                        MailTemplates.map("player", player.getGameProfile().getName(), "code", code))
                .source(SOURCE_ADMIN)
                .money(entry.money)
                .addItems(entry.items);
        if (entry.title != null && !entry.title.isEmpty()) {
            draft.title(entry.title);
        }
        if (entry.note != null && !entry.note.isEmpty()) {
            draft.content(draft.content() + "\n" + entry.note);
        }
        addMailDraft(player.server, player.getUUID(), draft);
        entry.usedCount++;
        entry.usedBy.merge(player.getUUID(), 1, Integer::sum);
        data.setDirty();
        TransactionLog.record(player.server, player.getUUID(), TransactionLog.REDEEM, "兑换码",
                entry.money, account(player).balance, code + " " + MailTemplates.itemsText(entry.items));
        sendRedeemResult(player, true, code, "ok");
        sendSnapshot(player, "mail");
    }

    /** 管理员生成兑换码：码必填且不可重复、必须有奖励；指定玩家模式会把名字解析成 UUID。 */
    private static void adminRedeemCreate(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty()) {
            sendRedeemCreated(player, false, code, "empty");
            return;
        }
        if (data.redeemCodes.containsKey(code)) {
            sendRedeemCreated(player, false, code, "exists");
            return;
        }
        int days = parseInt(tag.getString("days"));
        RedeemCode rc = new RedeemCode();
        rc.code = code;
        rc.expireAt = days <= 0 ? 0L : System.currentTimeMillis() + days * 86_400_000L;
        rc.perPlayerLimit = Math.max(0, parseInt(tag.getString("perPlayer")));
        rc.totalLimit = Math.max(0, parseInt(tag.getString("total")));
        rc.title = tag.getString("title").trim();
        rc.note = tag.getString("note").trim();
        // 第八轮：奖励内容（物品 / 星币 / 玩家）来自「奖励配置」界面写下的草稿；
        // 兼容旧客户端：没有草稿时仍按报文里的文本字段解析。
        RedeemDraft draft = REDEEM_DRAFT.get(player.getUUID());
        if (draft != null) {
            // 草稿里没勾人 = 全体玩家可兑换（默认）；勾了人才是"指定玩家"。
            rc.global = draft.players().isEmpty();
            rc.money = draft.money();
            rc.items.addAll(draft.items());
            if (!rc.global) {
                rc.players.addAll(draft.players());
            }
        } else {
            rc.global = !"false".equalsIgnoreCase(tag.getString("global").trim());
            rc.money = parsePrice(tag.getString("money"));
            rc.items.addAll(parseItemList(tag.getString("items")));
            if (!rc.global) {
                for (String piece : tag.getString("players").split(",")) {
                    String name = piece.trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    UUID id = resolvePlayerByName(data, player.server, name);
                    if (id != null) {
                        rc.players.add(id);
                    }
                }
            }
        }
        rc.creator = player.getGameProfile().getName();
        rc.createdAt = System.currentTimeMillis();
        if (!rc.global && rc.players.isEmpty()) {
            sendRedeemCreated(player, false, code, "noplayer");
            return;
        }
        if (!rc.hasReward()) {
            sendRedeemCreated(player, false, code, "noreward");
            return;
        }
        data.redeemCodes.put(code, rc);
        data.setDirty();
        // 生成成功后清掉草稿：下一个兑换码重新配置，避免不小心把同一次奖励连发两遍。
        REDEEM_DRAFT.remove(player.getUUID());
        sendRedeemDraft(player);
        sendRedeemCreated(player, true, code, "ok");
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.redeem_created", code)
                .withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：把某个兑换码当前的奖励内容推给客户端（编辑界面摘要显示用，不打开任何容器）。 */
    private static void adminRedeemPreview(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        RedeemCode rc = data(player.server).redeemCodes.get(code);
        if (rc == null) {
            return;
        }
        sendRedeemDraftFor(player, rc.items, rc.money, new ArrayList<>(rc.players));
    }

    /** 管理员：下发已生成的兑换码列表（客户端列表界面展示与删除）。 */
    private static void sendRedeemList(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_list");
        ListTag list = new ListTag();
        for (RedeemCode rc : data.redeemCodes.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("code", rc.code);
            c.putLong("expireAt", rc.expireAt);
            c.putBoolean("global", rc.global);
            c.putInt("players", rc.players.size());
            c.putInt("used", rc.usedCount);
            c.putInt("total", rc.totalLimit);
            c.putInt("perPlayer", rc.perPlayerLimit);
            c.putLong("money", rc.money);
            c.putString("items", MailTemplates.itemsText(rc.items));
            c.putString("creator", rc.creator);
            // 第八轮：编辑界面要回填标题/备注/指定玩家，列表页也直接显示它们。
            c.putString("title", rc.title);
            c.putString("note", rc.note);
            ListTag ids = new ListTag();
            for (UUID id : rc.players) {
                ids.add(StringTag.valueOf(id.toString()));
            }
            c.put("playerIds", ids);
            list.add(c);
        }
        tag.put("codes", list);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /** 管理员：修改已有兑换码的基础设置（时效 / 限领 / 总上限 / 标题 / 备注）。 */
    private static void adminRedeemUpdate(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        RedeemCode rc = data.redeemCodes.get(code);
        if (rc == null) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.redeem.notfound")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int days = parseInt(tag.getString("days"));
        synchronized (LOCK) {
            rc.expireAt = days <= 0 ? 0L : System.currentTimeMillis() + days * 86_400_000L;
            rc.perPlayerLimit = Math.max(0, parseInt(tag.getString("perPlayer")));
            rc.totalLimit = Math.max(0, parseInt(tag.getString("total")));
            rc.title = tag.getString("title").trim();
            rc.note = tag.getString("note").trim();
            data.setDirty();
        }
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.redeem_updated", code)
                .withStyle(ChatFormatting.GREEN));
        sendRedeemList(player);
    }

    /** 管理员：查看某个兑换码「谁领过、各领了几次」。 */
    private static void sendRedeemClaimed(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        RedeemCode rc = data.redeemCodes.get(code);
        CompoundTag out = new CompoundTag();
        out.putString("kind", "redeem_claimed");
        out.putString("code", code);
        ListTag list = new ListTag();
        if (rc != null) {
            for (Map.Entry<UUID, Integer> entry : rc.usedBy.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putString("name", playerName(player.server, entry.getKey()));
                c.putInt("count", entry.getValue());
                list.add(c);
            }
        }
        out.put("entries", list);
        ClientboundShopSyncPayload.send(player, out);
    }

    /** 管理员删除兑换码。 */
    private static void adminRedeemDelete(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        if (data.redeemCodes.remove(code) != null) {
            data.setDirty();
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.redeem_deleted", code)
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /** 把「名字」解析成 UUID（先在线玩家，再历史账号）。 */
    private static UUID resolvePlayerByName(ShopSavedData data, MinecraftServer server, String name) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getGameProfile().getName().equalsIgnoreCase(name)) {
                return online.getUUID();
            }
        }
        for (Map.Entry<UUID, PlayerAccount> entry : data.accounts().entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue().name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 解析多件物品：「minecraft:diamond x2,minecraft:gold_ingot x5」。 */
    private static java.util.List<ItemStack> parseItemList(String text) {
        java.util.List<ItemStack> list = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return list;
        }
        for (String piece : text.split(",")) {
            ItemStack stack = parseItemText(piece);
            if (!stack.isEmpty()) {
                list.add(stack);
            }
        }
        return list;
    }

    /** 回传「生成兑换码」结果。 */
    private static void sendRedeemCreated(ServerPlayer player, boolean ok, String code, String reason) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_created");
        tag.putBoolean("ok", ok);
        tag.putString("code", code);
        tag.putString("reason", reason);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /** 回传兑换结果（客户端据 ok/reason 显示红字抖动或成功展示）。 */
    private static void sendRedeemResult(ServerPlayer player, boolean ok, String code, String reason) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_result");
        tag.putBoolean("ok", ok);
        tag.putString("code", code);
        tag.putString("reason", reason);
        ClientboundShopSyncPayload.send(player, tag);
    }

    // =========================================================== 邮箱空间拦截（第七轮）

    /** 单封邮件里待领的物品（非物品类或已领取返回空列表）。 */
    private static List<ItemStack> pendingItems(ServerPlayer player, String mailId) {
        MailEntry entry = findMail(account(player), mailId);
        if (entry != null && entry.type == MailType.ITEM && !entry.isClaimed) {
            return entry.itemStacks();
        }
        return new ArrayList<>();
    }

    /** 邮箱里所有待领物品（「全部领取」用）。 */
    private static List<ItemStack> pendingItemsAll(ServerPlayer player) {
        List<ItemStack> list = new ArrayList<>();
        for (MailEntry entry : account(player).mailbox) {
            if (entry.type == MailType.ITEM && !entry.isClaimed) {
                list.addAll(entry.itemStacks());
            }
        }
        return list;
    }

    /**
     * 背包空间校验：按「同物品同组件先填未满堆叠，再占新槽位」模拟，
     * 装不下时给玩家「背包物品已达限额提醒」并返回 false（调用方必须中止领取）。
     */
    private static boolean fitsInBagOrWarn(ServerPlayer player, List<ItemStack> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return true;
        }
        Inventory inv = player.getInventory();
        int usable = Math.min(36, inv.getContainerSize());
        List<ItemStack> simulated = new ArrayList<>();
        for (int i = 0; i < usable; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                simulated.add(stack.copy());
            }
        }
        int freeSlots = usable - simulated.size();
        int needSlots = 0;
        for (ItemStack stack : incoming) {
            int remaining = stack.getCount();
            int per = Math.max(1, stack.getMaxStackSize());
            for (ItemStack existing : simulated) {
                if (remaining <= 0) {
                    break;
                }
                if (!ItemStack.isSameItemSameComponents(existing, stack)) {
                    continue;
                }
                int room = per - existing.getCount();
                if (room <= 0) {
                    continue;
                }
                int put = Math.min(room, remaining);
                existing.setCount(existing.getCount() + put);
                remaining -= put;
            }
            while (remaining > 0) {
                ItemStack fresh = stack.copy();
                int put = Math.min(per, remaining);
                fresh.setCount(put);
                simulated.add(fresh);
                needSlots++;
                remaining -= put;
            }
        }
        if (needSlots <= freeSlots) {
            return true;
        }
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.bag_full_block", needSlots, freeSlots)
                .withStyle(ChatFormatting.RED));
        return false;
    }

    // =========================================================== 工具

    /**
     * 安全入账：只加“余额还能装得下”的部分，返回实际到账金额（饱和加法，永不溢出）。
     * <p>
     * 原先各处直接写 {@code Math.min(max, balance + amount)}：收款方到顶时差额被静默销毁，
     * 而且 amount 极大时还会先溢出成负数。所有入账都应走这里，差额由调用方退回或转为邮件。
     */
    public static long credit(PlayerAccount account, long amount, long max) {
        // 事件：余额变化（业务成功后发布；监听器异常不影响本次入账）
        long beforeBalance = account.balance;
        long creditedNow = creditInner(account, amount, max);
        if (creditedNow > 0L && account.uuidForEvents != null) {
            cn.autoforged.server_shop_mod_1789689358.api.event.ApiEvents.post(
                    new cn.autoforged.server_shop_mod_1789689358.api.event.BalanceChangedEvent(
                            account.uuidForEvents, beforeBalance, account.balance, creditedNow, "credit"));
        }
        return creditedNow;
    }

    /** 原来的 credit 逻辑（不含事件），供 {@link #credit} 复用。 */
    private static long creditInner(PlayerAccount account, long amount, long max) {
        long room = Math.max(0L, max - account.balance);
        long add = Math.min(Math.max(0L, amount), room);
        account.balance += add;
        return add;
    }

    /**
     * **统一扣款入口**：所有"玩家付出金币"的业务都应改用它（而不是直接 {@code account.balance -= x}）。
     *
     * <p>它会：①余额不足时**不扣**并返回 -1（调用方必须检查）；②扣完发布 {@code BalanceChangedEvent}；
     * ③不做持久化（调用方照旧负责 {@code setDirty()}）。
     *
     * @return 实际扣除金额；余额不足返回 -1
     */
    public static long debit(PlayerAccount account, long amount, String reason) {
        if (account == null || amount <= 0L) {
            return amount <= 0L ? 0L : -1L;
        }
        if (account.balance < amount) {
            return -1L;
        }
        long before = account.balance;
        account.balance -= amount;
        if (account.uuidForEvents != null) {
            cn.autoforged.server_shop_mod_1789689358.api.event.ApiEvents.post(
                    new cn.autoforged.server_shop_mod_1789689358.api.event.BalanceChangedEvent(
                            account.uuidForEvents, before, account.balance, -amount,
                            reason == null ? "debit" : reason));
        }
        return amount;
    }

    // =========================================================== 交易流水（管理员查看）

    /**
     * 取某玩家的交易流水渲染行（管理员用）。找不到该玩家时返回空列表。
     * 玩家名支持在线名与账户表里的历史名（含离线），也支持直接写 UUID。
     */
    public static List<Component> historyLines(MinecraftServer server, String targetName, int page) {
        GrantManager.ResolveResult resolved = GrantManager.resolveTargets(server, targetName);
        if (resolved.ids().isEmpty()) {
            return List.of();
        }
        UUID id = resolved.ids().get(0);
        PlayerAccount account = data(server).account(id);
        String display = account.name == null || account.name.isEmpty() ? targetName : account.name;
        return TransactionLog.format(account, display, page);
    }

    /** 管理员命令：直接发放星币（单目标，供 /shop wallet give 使用）。 */
    public static void adminGive(ServerPlayer target, long amount) {
        PlayerAccount account = account(target);
        long credited = credit(account, amount, Config.maxBalanceLimit());
        data(target.server).setDirty();
        notifyPlayer(target, Component.translatable("msg.server_shop_mod.admin_gave", credited, account.balance)
                .withStyle(ChatFormatting.GREEN));
        TransactionLog.record(target.server, target.getUUID(), TransactionLog.ADMIN_MONEY, "管理员",
                credited, account.balance, "");
    }

    /**
     * 管理员命令：**把余额直接设为 amount**（0 = 清空），用于纠正账户而不是「加一笔」。
     * <p>
     * 与 {@link #adminGive} 的区别：那个只会往上加（受上限约束），这里是无条件覆写，
     * 因此也用于把误发的余额收回来。会写一条流水（差额），并在目标在线时立刻推送新快照，
     * 避免玩家界面还显示旧余额。
     */
    public static void adminSetBalance(ServerPlayer target, long amount, String operator) {
        long max = Config.maxBalanceLimit();
        long value = Math.max(0L, Math.min(amount, max));
        PlayerAccount account = account(target);
        long before = account.balance;
        long delta = value - before;
        account.balance = value;
        data(target.server).setDirty();
        TransactionLog.record(target.server, target.getUUID(), TransactionLog.ADMIN_MONEY,
                "管理员设置余额", delta, value, operator == null ? "" : operator);
        notifyPlayer(target, Component.translatable("msg.server_shop_mod.admin_set_balance",
                value + currencyName()).withStyle(ChatFormatting.GREEN));
        // 在线目标立即刷新界面上的余额（快照里带 balance，客户端顶栏会跟着闪一下）。
        sendSnapshot(target, null, true);
    }

    /** 按名字（在线 / 历史账号 / UUID）设置余额；找不到返回 false。 */
    public static boolean adminSetBalanceByName(MinecraftServer server, String targetName, long amount,
            String operator) {
        GrantManager.ResolveResult resolved = GrantManager.resolveTargets(server, targetName);
        if (resolved.ids().isEmpty()) {
            return false;
        }
        UUID id = resolved.ids().get(0);
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(id);
        long max = Config.maxBalanceLimit();
        long value = Math.max(0L, Math.min(amount, max));
        long delta = value - account.balance;
        account.balance = value;
        data.setDirty();
        TransactionLog.record(server, id, TransactionLog.ADMIN_MONEY, "管理员设置余额", delta, value,
                operator == null ? "" : operator);
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.server_shop_mod.admin_set_balance",
                    value + currencyName()).withStyle(ChatFormatting.GREEN));
            sendSnapshot(online, null, true);
        }
        return true;
    }

    public static long balanceOf(ServerPlayer player) {
        return account(player).balance;
    }

    /** 只读余额（找不到账号返回 0，供流水记录使用）。 */
    private static long balanceOf(ShopSavedData data, UUID id) {
        PlayerAccount account = id == null ? null : data.peek(id);
        return account == null ? 0L : account.balance;
    }

    public static void onLogin(ServerPlayer player) {
        // 签到（需求 7）：开启「自动签到」时登录即签到。
        autoSignInIfNeeded(player);
        // 活动日奖励（需求 7）：当天登录即可领取管理员配置的星币。
        grantActivityDay(player);
        // 同 IP 多账号登录限制已按需求**移除**：同一个 IP 可以正常登录多个账号，不再踢人。
        PlayerAccount account = account(player);
        // 需求 13：惰性刷新——登录时检查时间戳，过期才重新生成每日商店/收购，不做全局 tick 遍历。
        ensureDaily(player, account, data(player.server));
        // 需求 10：登录时把持久化的未读数量同步给客户端，红点不会因为重连丢失。
        NotificationManager.sendCounts(player, null);
        // 已领取/已查看的邮件仍保留在邮箱中，这里只统计未处理的邮件数量。
        long pendingMails = account.mailbox.stream().filter(MailEntry::pending).count();
        if (pendingMails > 0) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.mail_waiting", pendingMails)
                    .withStyle(ChatFormatting.GOLD));
        }
        // 出货箱：离线期间结算过 → 登录时补一条提示。
        if (account.shipping.pendingNotice) {
            account.shipping.pendingNotice = false;
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.shipping_sold_offline",
                    account.shipping.lastCount, account.shipping.lastPayout)
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /** 需求 12：管理员指定某玩家刷新每日商店货物与收购商品种类（离线玩家下次登录生效）。 */
    public static void refreshDaily(ServerPlayer target) {
        PlayerAccount account = account(target);
        account.dailyShopDay = Long.MIN_VALUE;
        account.dailyBuybackDay = Long.MIN_VALUE;
        account.dailyFlipped.clear();
        data(target.server).setDirty();
        ensureDaily(target, account, data(target.server));
        sendSnapshot(target, "daily", true);
    }

    public static void onLogout(ServerPlayer player) {
        returnPending(player);
        TradeManager.onLogout(player);
        DEPOSIT_CONTEXT.remove(player.getUUID());
        ADMIN_EDIT_TYPE.remove(player.getUUID());
        ADMIN_TEMPLATE.remove(player.getUUID());
        BATCH_TEMPLATE.remove(player.getUUID());
    }

    // =========================================================== 侧栏布局（拖拽排序）

    /**
     * 管理员：保存侧栏页签的**顺序列表**（面板里是固定网格，坐标由客户端按顺序算，所以只存顺序）。
     * 服务端只接受合法 id 并去重，防止客户端塞垃圾进存档。
     */
    private static void adminSidebarOrder(ServerPlayer player, CompoundTag tag) {
        ListTag list = tag.getList("order", 8);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < list.size() && ids.size() < 32; i++) {
            String id = list.getString(i).trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        // 被移除的模块（不在 order 里的那些）。
        ListTag hiddenList = tag.getList("hidden", 8);
        List<String> hidden = new ArrayList<>();
        for (int i = 0; i < hiddenList.size() && hidden.size() < 32; i++) {
            String id = hiddenList.getString(i).trim();
            if (!id.isEmpty() && !hidden.contains(id) && !ids.contains(id)) {
                hidden.add(id);
            }
        }
        if (ids.isEmpty()) {
            // 至少保留一个模块：否则侧栏空了，管理员就没有入口再进「商店设置」加回来。
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.sidebar_need_one")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ShopSavedData data = data(player.server);
        data.sidebarLayout.setOrder(ids);
        data.sidebarLayout.setHidden(hidden);
        data.setDirty();
        broadcastSidebarLayout(player.server);
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.sidebar_saved")
                .withStyle(ChatFormatting.GREEN));
    }

    /**
     * 可重命名的模块 id（与客户端 {@code ShopScreen.TAB_IDS} 一一对应）。
     * <p>改名只允许改**显示名**，id 永不改变（存档 / 配置文件 / 动作包都按 id 走）。
     */
    private static final List<String> MODULE_IDS = List.of(
            "daily", "buyback", "mall", "shipping", "auction", "stall", "mail", "exchange");

    /**
     * 管理员：**重命名模块**（编辑布局里右键页签）。
     *
     * <p>只改显示名：名字存进 {@link SidebarLayout#names()}，随快照下发，所有玩家立刻看到；
     * 传空名字 = 恢复成 lang 里的默认名。名字会去掉 {@code §} 格式符并截断到 12 个字。
     */
    private static void adminSidebarRename(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        String id = tag.getString("tab").trim();
        if (!MODULE_IDS.contains(id)) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.sidebar_rename_no_module")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ShopSavedData data = data(player.server);
        String raw = tag.getString("name");
        String clean = SidebarLayout.sanitize(raw);
        boolean truncated = SidebarLayout.sanitizeFull(raw).length() > SidebarLayout.MAX_NAME;
        String previousName = data.sidebarLayout.nameOf(id);
        if (clean.isEmpty()) {
            if (previousName == null) {
                return;   // 本来就是默认名，什么都不用做
            }
            data.sidebarLayout.setName(id, null);
            data.setDirty();
            broadcastSidebarLayout(player.server);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.sidebar_rename_reset")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (data.sidebarLayout.setName(id, clean)) {
            data.setDirty();
            broadcastSidebarLayout(player.server);
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.sidebar_renamed", clean)
                    .withStyle(ChatFormatting.GREEN));
        }
        if (truncated) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.sidebar_rename_too_long",
                    SidebarLayout.MAX_NAME).withStyle(ChatFormatting.YELLOW));
        }
    }

    /**
     * 管理员：**摊位自动下架时间**（商店设置 → 摊位）。
     *
     * <p>{@code expire_mode}: {@code permanent} / {@code game} / {@code real}；
     * {@code expire_amount}: game 按**游戏天数**、real 按**现实小时**（0 或 permanent = 永不自动下架）。
     */
    private static void adminStallExpire(ServerPlayer player, CompoundTag tag) {
        if (!player.hasPermissions(2)) {
            return;
        }
        String modeText = tag.getString("expire_mode").trim().toLowerCase(java.util.Locale.ROOT);
        String mode = ("game".equals(modeText) || "real".equals(modeText)) ? modeText : "permanent";
        int amount;
        try {
            amount = Integer.parseInt(tag.getString("expire_amount").trim());
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.stall_expire_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (amount < 0 || amount > 100000) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.stall_expire_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.stallExpireMode.set(mode);
        Config.INSTANCE.stallExpireAmount.set(amount);
        Config.SPEC.save();
        Component desc = "permanent".equals(mode) || amount <= 0
                ? Component.translatable("gui.server_shop_mod.choice.expire_mode.permanent")
                : Component.translatable("gui.server_shop_mod.stall.expire_value",
                        Component.translatable("gui.server_shop_mod.choice.expire_mode." + mode), amount);
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.stall_expire_saved", desc)
                .withStyle(ChatFormatting.GREEN));
        // 让所有玩家立刻拿到新的下架方式（摊位页会显示每个商品的剩余时间）。
        broadcastSnapshots(player.server);
    }

    /** 管理员：把所有页签位置恢复默认。 */    private static void adminSidebarReset(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        data.sidebarLayout.clear();
        data.setDirty();
        broadcastSidebarLayout(player.server);
        notifyPlayer(player, Component.translatable("msg.server_shop_mod.sidebar_reset")
                .withStyle(ChatFormatting.YELLOW));
    }

    /** 布局变了：给所有在线玩家推一份静默快照（只更新缓存、不打断他们的界面）。 */
    private static void broadcastSidebarLayout(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            sendSnapshot(online, null, true);
        }
    }

    // =========================================================== 账单（玩家自己的收支明细）
    /** 账单一次下发的最大条数（再多就用滚动看旧的）。 */
    public static final int BILL_MAX_ROWS = 60;

    /**
     * 下发玩家自己的账单：明细 + 今日 / 本月收支汇总。
     * <p>
     * 按天分组由客户端做（服务端只按时间倒序给原始明细），
     * 每条含时间 / 类型 / 对方 / 金额 / 变动后余额 / 备注。
     */
    public static void sendBill(ServerPlayer player) {
        PlayerAccount account = account(player);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "bill");
        tag.putLong("balance", account.balance);
        tag.putInt("limit", Config.INSTANCE.transactionHistorySize.get());
        List<TransactionEntry> all = account.transactions;
        tag.putInt("size", all.size());
        // 今日 / 本月汇总（现实时间）。
        java.time.LocalDate today = java.time.LocalDate.now();
        long todayIn = 0L;
        long todayOut = 0L;
        long monthIn = 0L;
        long monthOut = 0L;
        for (TransactionEntry entry : all) {
            if (entry.amount == 0L || entry.time <= 0L) {
                continue;
            }
            java.time.LocalDate date = java.time.Instant.ofEpochMilli(entry.time)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            boolean sameMonth = date.getYear() == today.getYear() && date.getMonthValue() == today.getMonthValue();
            if (entry.amount > 0L) {
                if (sameMonth) {
                    monthIn += entry.amount;
                }
                if (date.equals(today)) {
                    todayIn += entry.amount;
                }
            } else {
                if (sameMonth) {
                    monthOut += -entry.amount;
                }
                if (date.equals(today)) {
                    todayOut += -entry.amount;
                }
            }
        }
        tag.putLong("todayIn", todayIn);
        tag.putLong("todayOut", todayOut);
        tag.putLong("monthIn", monthIn);
        tag.putLong("monthOut", monthOut);
        ListTag list = new ListTag();
        for (int i = all.size() - 1; i >= 0 && list.size() < BILL_MAX_ROWS; i--) {
            list.add(all.get(i).save());
        }
        tag.put("entries", list);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /**
     * 记一笔外部账目（其它插件 / 模组扣的或加的钱）：只写账单，不动本模组余额。
     * 其它插件可以在控制台执行 {@code /shop wallet log <玩家> <金额> <事由>} 触发同一条路径。
     */
    public static boolean recordExternal(MinecraftServer server, String playerName, long amount, String reason,
            String source) {
        UUID id = resolvePlayerId(server, playerName);
        if (id == null || amount == 0L) {
            return false;
        }
        TransactionLog.recordExternal(server, id, amount, reason, source);
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            sendBill(online);
        }
        return true;
    }

    /** 按玩家名解析 UUID（在线优先，其次账户表里记录过的名字）。找不到返回 null。 */
    @Nullable
    private static UUID resolvePlayerId(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return null;
        }
        String target = name.trim();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getGameProfile().getName().equalsIgnoreCase(target)) {
                return online.getUUID();
            }
        }
        for (Map.Entry<UUID, PlayerAccount> entry : data(server).accounts().entrySet()) {
            String accountName = entry.getValue().name;
            if (accountName != null && accountName.equalsIgnoreCase(target)) {
                return entry.getKey();
            }
        }
        try {
            return UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void addItemOrMail(ServerPlayer player, ItemStack stack, String source) {
        // Inventory.add 会把传入栈原地 shrink 成“没塞进去的剩余量”，必须保留剩余量，否则半组同类物品时会丢物品。
        ItemStack remaining = stack.copy();
        player.getInventory().add(remaining);
        if (remaining.isEmpty()) {
            // 需求一：物品获取日志模板「获得 物品名×数量」，物品名可为纯数字，数量单独作字段。
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.received",
                    stack.getHoverName(), stack.getCount()).withStyle(ChatFormatting.GREEN));
            return;
        }
        // 第七轮：背包放不下的部分进邮箱，并用「背包已满」模板（正文含「背包物品已达限额提醒」）。
        addMailDraft(player.server, player.getUUID(), MailTemplates.build(
                        MailTemplates.Template.BAG_FULL, MailTemplates.map(
                                "player", player.getGameProfile().getName(),
                                "item", MailTemplates.itemText(remaining, true)))
                .source(source)
                .addItem(remaining));
    }

    public static void addMail(MinecraftServer server, @Nullable UUID owner, ItemStack stack, String source) {
        addMail(server, owner, stack, source, "");
    }

    /** 需求：带发送者与发送者 UUID 的物品类邮件。 */
    public static void addMail(MinecraftServer server, @Nullable UUID owner, ItemStack stack, String source, String sender) {
        if (owner == null || stack.isEmpty()) {
            return;
        }
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(owner);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        int max = Config.INSTANCE.maxMailboxSize.get();
        // 同样要处理 Inventory.add 的“部分放入”，只把真正剩下的部分继续进邮箱，避免丢物品。
        ItemStack remaining = stack.copy();
        if (account.mailbox.size() >= max && !ensureMailRoom(server, account, online)) {
            // 邮箱已满且没有已读邮件可清理：优先塞背包，仍放不下的直接掉落在玩家脚下。
            if (online != null) {
                online.getInventory().add(remaining);
                if (remaining.isEmpty()) {
                    notifyPlayer(online, Component.translatable("msg.server_shop_mod.received",
                            stack.getHoverName(), stack.getCount()).withStyle(ChatFormatting.GREEN));
                    data.setDirty();
                    return;
                }
                online.drop(remaining, false);
                notifyPlayer(online, Component.translatable("msg.server_shop_mod.mailbox_full")
                        .withStyle(ChatFormatting.RED));
                data.setDirty();
                return;
            }
            // 离线玩家无法掉落，为避免系统返还的物品彻底丢失，仍然保留在邮箱（超出上限仅限系统资产）。
        }
        MailEntry newEntry = MailEntry.item(remaining, source, sender, null, currentDay(server.overworld()));
        // 第七轮：全服物品发放（admin_grant）保留 30 天有效期；其余邮件默认永久。
        if ("admin_grant".equals(source)) {
            newEntry.expireAt = System.currentTimeMillis() + GRANT_EXPIRE_DAYS * 86_400_000L;
        }
        account.mailbox.add(newEntry);
        data.setDirty();
        // 需求 10：收到新物品（交易返还/管理员发放等）→ 邮箱提示红点。
        NotificationManager.notify(server, owner, NotificationType.MAIL);
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.server_shop_mod.mail_received", stack.getHoverName())
                    .withStyle(ChatFormatting.GOLD));
            online.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
        }
    }

    /**
     * 需求：发送一封金额类邮件（拍卖成交/摊位售出所得）。
     * 邮箱满时金额不能丢，直接进账并提示，避免玩家资产凭空消失。
     */
    public static void addMoneyMail(MinecraftServer server, @Nullable UUID owner, long amount, ItemStack display,
            String source, String sender, @Nullable UUID senderUUID) {
        if (owner == null || amount <= 0L) {
            return;
        }
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(owner);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        if (account.mailbox.size() >= Config.INSTANCE.maxMailboxSize.get()
                && !ensureMailRoom(server, account, online)) {
            // 邮箱满且没有已读邮件可清理：先把能装下的直接到账，余额也装不下的部分仍然留在邮箱，绝不销毁。
            long credited = credit(account, amount, Config.maxBalanceLimit());
            long rest = amount - credited;
            if (rest > 0L) {
                account.mailbox.add(MailEntry.money(rest, display, source, sender, senderUUID,
                        currentDay(server.overworld())));
                NotificationManager.notify(server, owner, NotificationType.MAIL);
            }
            data.setDirty();
            if (online != null) {
                notifyPlayer(online, Component.translatable("msg.server_shop_mod.mailbox_full_money", credited)
                        .withStyle(ChatFormatting.GOLD));
            }
            return;
        }
        account.mailbox.add(MailEntry.money(amount, display, source, sender, senderUUID,
                currentDay(server.overworld())));
        data.setDirty();
        NotificationManager.notify(server, owner, NotificationType.MAIL);
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.server_shop_mod.mail_received_money", amount)
                    .withStyle(ChatFormatting.GOLD));
            online.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
        }
    }

    private static void notifyPlayer(ServerPlayer player, Component message) {
        player.displayClientMessage(message, false);
    }

    /** 需求 2：拍卖手续费 = 成交价 × 配置百分比（向上取整）。 */
    private static long auctionFee(long amount) {
        double percent = Config.INSTANCE.auctionFeePercent.get();
        return percent <= 0.0D ? 0L : (long) Math.ceil(amount * percent / 100.0D);
    }

    private static long parsePositive(String text) {
        try {
            long value = Long.parseLong(text.trim());
            return Math.max(0L, value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 当前货币名（下发到客户端显示）。
     * <p>
     * 旧版本的默认货币名是「金币」，老配置里会显式写着它，导致升级后仍显示金币；
     * 这里把「金币」与留空都当作旧默认值统一迁移成「星币」，服主自定义的其它名字原样返回。
     * 只影响显示名：配置键名、余额数值与对外接口都不变，ServerShopVaultBridge / Vault /
     * 领地插件（Residence 等）的联动不受影响。
     */
    public static String currencyName() {
        String name = Config.INSTANCE.currencyName.get();
        if (name == null || name.isBlank() || "金币".equals(name.trim())) {
            return "星币";
        }
        return name;
    }

    /**
     * 解析“价格”：非正数、或超过余额上限（谁也付不起）一律返回 0 视为非法。
     * 之所以要加上界：价格会参与 price×count 运算，天文数字会让乘法溢出成负数，
     * 进而使“余额不足”判定失效、扣款变成加钱（刷钱漏洞）。
     */
    private static long parsePrice(String text) {
        long price = parsePositive(text);
        return price > Config.maxBalanceLimit() ? 0L : price;
    }

    /**
     * 管理员填价格时的统一校验：合法返回价格（&gt;0）；非法返回 0，并**当场说明是哪一种问题**。
     * <p>
     * 以前只有一句「价格无效」，管理员完全分不清是"没填 / 填了非数字"还是"超过了余额上限"
     * （曾经因为余额上限被旧配置写成 1，所有商品都存不下来，却只看到"价格无效"）。
     */
    private static long parseAdminPrice(ServerPlayer player, String text) {
        long raw = parsePositive(text);
        if (raw <= 0L) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.invalid_price")
                    .withStyle(ChatFormatting.RED));
            return 0L;
        }
        long cap = Config.maxBalanceLimit();
        if (cap != Long.MAX_VALUE && raw > cap) {
            notifyPlayer(player, Component.translatable("msg.server_shop_mod.price_over_cap",
                    Long.toString(cap)).withStyle(ChatFormatting.RED));
            return 0L;
        }
        return raw;
    }

    /** 乘法溢出保护：溢出或非正数统一返回 0，调用方按“价格非法”处理。 */
    private static long safeMultiply(long price, int count) {
        if (price <= 0L || count <= 0) {
            return 0L;
        }
        return price > Long.MAX_VALUE / count ? 0L : price * count;
    }

    /**
     * 交付 count 件物品：按单栈上限拆成多份，先塞背包，塞不下的进邮箱（邮箱满由 addMail 兜底）。
     *
     * @return 是否有物品进了邮箱
     */
    private static boolean deliver(ServerPlayer player, ItemStack template, int count, String source) {
        int per = Math.max(1, template.getMaxStackSize());
        int left = count;
        boolean mailed = false;
        while (left > 0) {
            ItemStack part = template.copy();
            int give = Math.min(per, left);
            part.setCount(give);
            left -= give;
            player.getInventory().add(part);
            if (!part.isEmpty()) {
                addMail(player.server, player.getUUID(), part, source);
                mailed = true;
            }
        }
        return mailed;
    }

    private static int parseInt(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 需求 6/7：解析管理员输入的品质，无法识别（含留空）时按价格推导。 */
    private static Rarity parseRarity(String text, long price) {
        Rarity rarity = Rarity.byIdOrNull(text);
        return rarity != null ? rarity : Rarity.fromPrice(price);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ServerShopMod.MODID, path);
    }
}
