package cn.autoforged.module_shop.data;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 单个玩家的全部经济相关数据。 */
public final class PlayerAccount {
    public long balance;
    /** **运行时专用**：该账户属于哪个玩家（不写进存档；由 {@code ShopSavedData.account(uuid)} 填充）。
     *  <p>用于事件/日志等需要"账户 → 玩家"反查的场合。 */
    public java.util.UUID uuidForEvents;
    public long lastTransferTick = -10000L;
    public String name = "";

    public long dailyShopDay = Long.MIN_VALUE;
    public final List<DailyShopEntry> dailyShop = new ArrayList<>();
    /** 已翻开的每日商店卡牌下标（需求 11：翻牌状态要持久化）。 */
    public final List<Integer> dailyFlipped = new ArrayList<>();

    public long dailyBuybackDay = Long.MIN_VALUE;
    public final List<DailyBuybackEntry> dailyBuyback = new ArrayList<>();

    public final List<MailEntry> mailbox = new ArrayList<>();
    public final List<StallEntry> stall = new ArrayList<>();
    /** 交易/资产流水（最新在末尾，条数上限见 Config.transactionHistorySize）。 */
    public final List<TransactionEntry> transactions = new ArrayList<>();
    /** 当日已付费刷新每日商店的次数（每日重置，价格表见 Config.dailyRefreshCosts）。 */
    public int dailyShopRefreshUsed;
    /** 当日已付费刷新**每日收购**的次数（与商店分开计数，价格表见 Config.buybackRefreshCosts）。 */
    public int dailyBuybackRefreshUsed;
    /** 当日“收购清单全部完成”奖励是否已发放（每日重置）。 */
    public boolean dailyBuybackBonusDone;

    /** 需求 10：各类型未读数量，服务端权威维护并持久化。 */
    public final Map<NotificationType, Integer> notifications = new EnumMap<>(NotificationType.class);
    /** 需求 10：上一次“商店有 N 条未处理事项”聊天提醒的现实时间戳（毫秒）。 */
    public long lastNotificationReminderMs;

    /** 出货箱（新增模块）：每个玩家一份独立空间。 */
    public final ShippingBin shipping = new ShippingBin();

    /** 货币兑换每日个人额度追踪（按现实日期刷新）。 */
    public long exchangeQuotaDay = Long.MIN_VALUE;
    public final Map<String, Integer> exchangeDepositUsed = new HashMap<>();
    public final Map<String, Integer> exchangeWithdrawUsed = new HashMap<>();

    /** 实体货币阶梯兑换每日个人额度追踪（按折算基准面值累计）。 */
    public long physConvertQuotaDay = Long.MIN_VALUE;
    public long physConvertUsedValue;

    public void ensureExchangeQuotaDay(long day) {
        if (this.exchangeQuotaDay != day) {
            this.exchangeQuotaDay = day;
            this.exchangeDepositUsed.clear();
            this.exchangeWithdrawUsed.clear();
        }
    }

    public int exchangeDepositUsedFor(String key, long day) {
        ensureExchangeQuotaDay(day);
        return key == null || key.isEmpty() ? 0 : Math.max(0, this.exchangeDepositUsed.getOrDefault(key, 0));
    }

    public void addExchangeDepositUsed(String key, long day, int count) {
        if (key == null || key.isEmpty() || count <= 0) {
            return;
        }
        ensureExchangeQuotaDay(day);
        this.exchangeDepositUsed.merge(key, count, Integer::sum);
    }

    public int exchangeWithdrawUsedFor(String key, long day) {
        ensureExchangeQuotaDay(day);
        return key == null || key.isEmpty() ? 0 : Math.max(0, this.exchangeWithdrawUsed.getOrDefault(key, 0));
    }

    public void addExchangeWithdrawUsed(String key, long day, int count) {
        if (key == null || key.isEmpty() || count <= 0) {
            return;
        }
        ensureExchangeQuotaDay(day);
        this.exchangeWithdrawUsed.merge(key, count, Integer::sum);
    }

    public void ensurePhysConvertQuotaDay(long day) {
        if (this.physConvertQuotaDay != day) {
            this.physConvertQuotaDay = day;
            this.physConvertUsedValue = 0L;
        }
    }

    public long physConvertUsedFor(long day) {
        ensurePhysConvertQuotaDay(day);
        return Math.max(0L, this.physConvertUsedValue);
    }

    public void addPhysConvertUsed(long day, long value) {
        if (value <= 0L) {
            return;
        }
        ensurePhysConvertQuotaDay(day);
        this.physConvertUsedValue = this.physConvertUsedValue > Long.MAX_VALUE - value
                ? Long.MAX_VALUE : (this.physConvertUsedValue + value);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("balance", balance);
        tag.putLong("lastTransferTick", lastTransferTick);
        tag.putString("name", name);
        tag.putLong("dailyShopDay", dailyShopDay);
        tag.putLong("dailyBuybackDay", dailyBuybackDay);

        ListTag shopList = new ListTag();
        for (DailyShopEntry entry : dailyShop) {
            shopList.add(entry.save(registries));
        }
        tag.put("dailyShop", shopList);

        int[] flipped = new int[dailyFlipped.size()];
        for (int i = 0; i < flipped.length; i++) {
            flipped[i] = dailyFlipped.get(i);
        }
        tag.putIntArray("dailyFlipped", flipped);

        ListTag buybackList = new ListTag();
        for (DailyBuybackEntry entry : dailyBuyback) {
            buybackList.add(entry.save(registries));
        }
        tag.put("dailyBuyback", buybackList);

        ListTag mailList = new ListTag();
        for (MailEntry entry : mailbox) {
            mailList.add(entry.save(registries));
        }
        tag.put("mailbox", mailList);

        ListTag stallList = new ListTag();
        for (StallEntry entry : stall) {
            stallList.add(entry.save(registries));
        }
        tag.put("stall", stallList);

        ListTag historyList = new ListTag();
        for (TransactionEntry entry : transactions) {
            historyList.add(entry.save());
        }
        tag.put("transactions", historyList);
        tag.putInt("dailyShopRefreshUsed", dailyShopRefreshUsed);
        tag.putInt("dailyBuybackRefreshUsed", dailyBuybackRefreshUsed);
        tag.putBoolean("dailyBuybackBonusDone", dailyBuybackBonusDone);

        CompoundTag notificationsTag = new CompoundTag();
        for (Map.Entry<NotificationType, Integer> entry : notifications.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                notificationsTag.putInt(entry.getKey().id(), entry.getValue());
            }
        }
        tag.put("notifications", notificationsTag);
        tag.putLong("lastNotificationReminderMs", lastNotificationReminderMs);
        tag.put("shipping", shipping.save(registries));
        if (exchangeQuotaDay != Long.MIN_VALUE) {
            tag.putLong("exchangeQuotaDay", exchangeQuotaDay);
        }
        if (!exchangeDepositUsed.isEmpty()) {
            CompoundTag depTag = new CompoundTag();
            for (Map.Entry<String, Integer> e : exchangeDepositUsed.entrySet()) {
                if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null && e.getValue() > 0) {
                    depTag.putInt(e.getKey(), e.getValue());
                }
            }
            if (!depTag.isEmpty()) {
                tag.put("exchangeDepositUsed", depTag);
            }
        }
        if (!exchangeWithdrawUsed.isEmpty()) {
            CompoundTag wdrTag = new CompoundTag();
            for (Map.Entry<String, Integer> e : exchangeWithdrawUsed.entrySet()) {
                if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null && e.getValue() > 0) {
                    wdrTag.putInt(e.getKey(), e.getValue());
                }
            }
            if (!wdrTag.isEmpty()) {
                tag.put("exchangeWithdrawUsed", wdrTag);
            }
        }
        if (physConvertQuotaDay != Long.MIN_VALUE) {
            tag.putLong("physConvertQuotaDay", physConvertQuotaDay);
        }
        if (physConvertUsedValue > 0L) {
            tag.putLong("physConvertUsedValue", physConvertUsedValue);
        }
        return tag;
    }

    public static PlayerAccount load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerAccount account = new PlayerAccount();
        account.balance = tag.getLong("balance");
        account.lastTransferTick = tag.getLong("lastTransferTick");
        account.name = tag.getString("name");
        account.dailyShopDay = tag.contains("dailyShopDay") ? tag.getLong("dailyShopDay") : Long.MIN_VALUE;
        account.dailyBuybackDay = tag.contains("dailyBuybackDay") ? tag.getLong("dailyBuybackDay") : Long.MIN_VALUE;

        ListTag shopList = tag.getList("dailyShop", Tag.TAG_COMPOUND);
        for (int i = 0; i < shopList.size(); i++) {
            account.dailyShop.add(DailyShopEntry.load(shopList.getCompound(i), registries));
        }
        for (int index : tag.getIntArray("dailyFlipped")) {
            account.dailyFlipped.add(index);
        }
        ListTag buybackList = tag.getList("dailyBuyback", Tag.TAG_COMPOUND);
        for (int i = 0; i < buybackList.size(); i++) {
            account.dailyBuyback.add(DailyBuybackEntry.load(buybackList.getCompound(i), registries));
        }
        ListTag mailList = tag.getList("mailbox", Tag.TAG_COMPOUND);
        for (int i = 0; i < mailList.size(); i++) {
            account.mailbox.add(MailEntry.load(mailList.getCompound(i), registries));
        }
        ListTag stallList = tag.getList("stall", Tag.TAG_COMPOUND);
        for (int i = 0; i < stallList.size(); i++) {
            account.stall.add(StallEntry.load(stallList.getCompound(i), registries));
        }
        ListTag historyList = tag.getList("transactions", Tag.TAG_COMPOUND);
        for (int i = 0; i < historyList.size(); i++) {
            account.transactions.add(TransactionEntry.load(historyList.getCompound(i)));
        }
        account.dailyShopRefreshUsed = Math.max(0, tag.getInt("dailyShopRefreshUsed"));
        account.dailyBuybackRefreshUsed = Math.max(0, tag.getInt("dailyBuybackRefreshUsed"));
        account.dailyBuybackBonusDone = tag.getBoolean("dailyBuybackBonusDone");
        CompoundTag notificationsTag = tag.getCompound("notifications");
        for (String key : notificationsTag.getAllKeys()) {
            NotificationType type = NotificationType.byId(key);
            if (type != null) {
                account.notifications.put(type, notificationsTag.getInt(key));
            }
        }
        account.lastNotificationReminderMs = tag.getLong("lastNotificationReminderMs");
        if (tag.contains("shipping")) {
            ShippingBin loaded = ShippingBin.load(tag.getCompound("shipping"), registries);
            account.shipping.level = loaded.level;
            account.shipping.slots.clear();
            account.shipping.slots.addAll(loaded.slots);
            account.shipping.soldDay = loaded.soldDay;
            account.shipping.soldToday = loaded.soldToday;
            account.shipping.ruleUsed.putAll(loaded.ruleUsed);
            account.shipping.lastSettleGameDay = loaded.lastSettleGameDay;
            account.shipping.lastPayout = loaded.lastPayout;
            account.shipping.lastPhysPayout.clear();
            account.shipping.lastPhysPayout.putAll(loaded.lastPhysPayout);
            account.shipping.lastPayoutSummary = loaded.lastPayoutSummary;
            account.shipping.lastCount = loaded.lastCount;
            account.shipping.pendingNotice = loaded.pendingNotice;
        }
        account.exchangeQuotaDay = tag.contains("exchangeQuotaDay") ? tag.getLong("exchangeQuotaDay") : Long.MIN_VALUE;
        if (tag.contains("exchangeDepositUsed", Tag.TAG_COMPOUND)) {
            CompoundTag depTag = tag.getCompound("exchangeDepositUsed");
            for (String key : depTag.getAllKeys()) {
                int v = depTag.getInt(key);
                if (v > 0) {
                    account.exchangeDepositUsed.put(key, v);
                }
            }
        }
        if (tag.contains("exchangeWithdrawUsed", Tag.TAG_COMPOUND)) {
            CompoundTag wdrTag = tag.getCompound("exchangeWithdrawUsed");
            for (String key : wdrTag.getAllKeys()) {
                int v = wdrTag.getInt(key);
                if (v > 0) {
                    account.exchangeWithdrawUsed.put(key, v);
                }
            }
        }
        account.physConvertQuotaDay = tag.contains("physConvertQuotaDay") ? tag.getLong("physConvertQuotaDay") : Long.MIN_VALUE;
        account.physConvertUsedValue = Math.max(0L, tag.getLong("physConvertUsedValue"));
        return account;
    }
}
