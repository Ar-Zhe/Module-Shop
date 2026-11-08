package cn.autoforged.module_shop.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** 全局的世界级商店数据。所有跨维度的持久化都集中在这里。 */
public final class ShopSavedData extends SavedData {
    private static final String FILE_ID = "module_shop_data";

    private final Map<UUID, PlayerAccount> accounts = new HashMap<>();
    public final List<ConfiguredOffer> shopPool = new ArrayList<>();
    public final List<ConfiguredOffer> buybackPool = new ArrayList<>();
    /** 购物城（新增模块）的常驻货架。 */
    public final List<MallItem> mallPool = new ArrayList<>();
    /** 购物城的"最近购买"记录（最新的一条在最后，最多 {@link #MALL_RECENT_MAX} 条）。 */
    public final List<MallPurchase> mallRecent = new ArrayList<>();
    /** 最近购买记录上限：只用于展示，超出后丢最旧的，避免存档无限膨胀。 */
    public static final int MALL_RECENT_MAX = 200;
    public final List<AuctionEntry> auctions = new ArrayList<>();
    /** 已结束的拍卖记录（成交 / 流拍 / 撤销 / 损坏），最新在最后；条数上限见 Config.auctionHistoryLimit。 */
    public final List<AuctionEntry> auctionHistory = new ArrayList<>();
    /** 拍卖资金 / 结算异常日志（审计、结算异常时追加），供管理员查看。最多 {@link #AUCTION_ANOMALY_MAX} 条。 */
    public final List<String> auctionAnomalies = new ArrayList<>();
    public static final int AUCTION_ANOMALY_MAX = 300;
    /**
     * 读档时整条解析失败的拍卖（原始 NBT 原样保留并写回），避免一条坏数据让整个存档读不出来，
     * 也避免把它悄悄丢掉。管理员可以用 /shop auction audit 看到数量。
     */
    public final List<CompoundTag> auctionCorrupt = new ArrayList<>();
    /** 拍卖列表版本号（不存档）：任何拍卖变化 +1，客户端据此判断要不要重新拉列表。 */
    public transient long auctionVersion = 1L;
    /** 玩家求购（悬赏）单：悬赏在发布时就已托管，撤销 / 到期原样退回。 */
    public final List<DemandEntry> demands = new ArrayList<>();
    /** 管理员配置的可兑换货币（1 个物品 = N 星币，双向兑换）。 */
    public final List<ExchangeEntry> exchangePool = new ArrayList<>();

    /**
     * 公共 API 的**幂等键**集合（{@code 玩家UUID|requestId}）。
     * <p>只追加、不改变其它字段；老存档没有这个键时读成空集合。最多保留 {@link #API_REQUESTS_MAX} 条（丢最旧）。
     */
    public final java.util.LinkedHashSet<String> apiRequests = new java.util.LinkedHashSet<>();
    /** 商店**自定义货币名称**（空 = 用 config 里的 currencyName）。管理员在编辑布局里右键余额修改。 */

    /** 新世界模板初始化是否已执行过（true = 之后永不再自动初始化）。 */
    public boolean templateInitDone;

    /** 是否已经写过模组自带的默认商品（用于区分新世界与老存档）。 */
    public boolean seedDefaultsApplied;
    public String shopCurrency = "";

    /** 幂等键保留上限（防无限增长）。 */
    public static final int API_REQUESTS_MAX = 4000;

    /** 超出上限时丢掉最旧的键。 */
    public void trimApiRequests() {
        while (this.apiRequests.size() > API_REQUESTS_MAX) {
            java.util.Iterator<String> it = this.apiRequests.iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }
    /** 收购清单全部完成奖励开关（管理员可在每日收购页切换；关闭时界面提示消失、也不发奖励）。 */
    public boolean buybackBonusEnabled = true;
    // ===== 签到（需求 7）=====
    /** 玩家 UUID → 签到数据。 */
    public final Map<UUID, SignInData> signIns = new HashMap<>();
    /** 模块总开关。 */
    public boolean signInEnabled = true;
    /** 登录自动签到（关闭时玩家要自己在签到界面点）。 */
    public boolean signInAuto = true;
    /** 累计签到加成开关（关闭时签到界面不显示里程进度条）。 */
    public boolean signInCumulative = true;
    /** 每日基础签到奖励。 */
    public long signInBaseDaily = 10L;
    /** 补签花费（0 = 免费）。 */
    public long signInMakeupCost = 100L;
    /** 允许补签的天数范围（今天之前 N 天内）。 */
    public int signInMakeupDays = 2;
    /** 累计签到档位天数与对应每日加成（默认 3 天 +5、5 天 +7、7 天 +10）。 */
    public final int[] signInTierDays = {3, 5, 7};
    public final long[] signInTierBonus = {5L, 7L, 10L};
    /** 连续签到里程奖励天数与金额（默认 3 天 100、5 天 300、7 天 800）。 */
    public final int[] signInMileDays = {3, 5, 7};
    public final long[] signInMileMoney = {100L, 300L, 800L};

    // ===== 刷新参数（需求 8）：每日商店与每日收购各自一套 =====
    /** 品质权重（传说/史诗/稀有/普通）。 */
    public final int[] shopWeights = {5, 15, 30, 50};
    /** 保底数量（稀有/史诗/传说 每天至少几个）。 */
    public final int[] shopGuarantee = {0, 0, 0};
    /** 每日商店牌数上限。 */
    public int shopSlotCount = 6;
    /** 每日收购同理。 */
    public final int[] buybackWeights = {5, 15, 30, 50};
    public final int[] buybackGuarantee = {0, 0, 0};
    public int buybackSlotCount = 6;
    /** 兑换码（第七轮）：码（大写）→ 配置。 */
    public final Map<String, RedeemCode> redeemCodes = new java.util.LinkedHashMap<>();
    /** 活动日（需求 7）：日期 epochDay → 活动日配置。 */
    public final Map<Long, ActivityDay> activityDays = new java.util.LinkedHashMap<>();

    /** 取某天的活动日配置（没有则返回 null）。 */
    public ActivityDay activityDay(long day) {
        return activityDays.get(day);
    }

    /** 设置/更新某天的活动日配置。 */
    public ActivityDay setActivityDay(long day) {
        ActivityDay act = activityDays.get(day);
        if (act == null) {
            act = new ActivityDay();
            act.day = day;
            activityDays.put(day, act);
        }
        setDirty();
        return act;
    }

    /** 取（必要时创建）某玩家的签到数据。 */
    public SignInData signIn(UUID id) {
        SignInData sign = signIns.get(id);
        if (sign == null) {
            sign = new SignInData();
            signIns.put(id, sign);
            setDirty();
        }
        return sign;
    }
    /** 收购全清奖励金额覆盖值（-1 = 使用配置默认值 buybackAllBonus）。 */
    public long buybackBonusOverride = -1L;
    public long listingCounter = 1L;
    public long nextAuctionId = 1L;
    /** 求购单号自增（用来生成短 id）。 */
    public long demandCounter = 1L;
    public long lastStallClearDay = Long.MIN_VALUE;
    /** 购物城上次补货的「现实日期」（现实时间口径；Long.MIN_VALUE = 还没记录过）。 */
    public long lastMallRestockDay = Long.MIN_VALUE;
    /** 购物城上次补货的「游戏日」（游戏时间口径；Long.MIN_VALUE = 还没记录过）。 */
    public long lastMallRestockGameDay = Long.MIN_VALUE;
    /** 购物城「库存周期刷新」上次刷新时刻：现实毫秒（0 = 还没记录基准）。 */
    public long mallRefreshLastReal;
    /** 购物城「库存周期刷新」上次刷新时刻：游戏刻（0 = 还没记录基准）。 */
    public long mallRefreshLastGame;
    // 同 IP 多账号登录限制已按需求移除（不再记录 IP 绑定）。
    /** 商店侧栏自定义布局（管理员拖动后保存，热同步给所有玩家）。 */
    public final SidebarLayout sidebarLayout = new SidebarLayout();

    /** 记一条购物城购买记录（自动按上限裁剪，只保留最新的若干条）。 */
    public void addMallRecent(MallPurchase record) {
        mallRecent.add(record);
        trimMallRecent();
    }

    /** 最近购买记录超上限时丢掉最旧的。 */
    public void trimMallRecent() {
        while (mallRecent.size() > MALL_RECENT_MAX) {
            mallRecent.remove(0);
        }
    }

    public PlayerAccount account(UUID uuid) {
        PlayerAccount account = accounts.get(uuid);
        if (account != null) {
            account.uuidForEvents = uuid;   // 事件/日志反查用（不写存档）
        }
        if (account == null) {
            account = new PlayerAccount();
            account.balance = cn.autoforged.module_shop.Config.INSTANCE.initialBalance.get();
            account.uuidForEvents = uuid;
            accounts.put(uuid, account);
            setDirty();
        }
        return account;
    }

    public PlayerAccount peek(UUID uuid) {
        return accounts.get(uuid);
    }

    public Map<UUID, PlayerAccount> accounts() {
        return accounts;
    }

    public List<AuctionEntry> auctionsOf(UUID seller) {
        List<AuctionEntry> result = new ArrayList<>();
        for (AuctionEntry entry : auctions) {
            if (entry.seller != null && entry.seller.equals(seller)) {
                result.add(entry);
            }
        }
        return result;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag accountsTag = new CompoundTag();
        for (Map.Entry<UUID, PlayerAccount> entry : accounts.entrySet()) {
            accountsTag.put(entry.getKey().toString(), entry.getValue().save(registries));
        }
        tag.put("accounts", accountsTag);

        tag.putBoolean("templateInitDone", templateInitDone);
        tag.putBoolean("seedDefaultsApplied", seedDefaultsApplied);
        tag.put("shopPool", saveOffers(shopPool, registries));
        tag.put("buybackPool", saveOffers(buybackPool, registries));
        // 购物城：常驻货架 + 最近购买记录
        ListTag mallList = new ListTag();
        for (MallItem item : mallPool) {
            mallList.add(item.save(registries));
        }
        tag.put("mallPool", mallList);
        ListTag recentList = new ListTag();
        for (MallPurchase record : mallRecent) {
            recentList.add(record.save(registries));
        }
        tag.put("mallRecent", recentList);
        tag.put("auctions", saveAuctions(registries));
        ListTag historyList = new ListTag();
        for (AuctionEntry entry : auctionHistory) {
            historyList.add(entry.save(registries));
        }
        tag.put("auctionHistory", historyList);
        ListTag anomalyList = new ListTag();
        for (String line : auctionAnomalies) {
            anomalyList.add(StringTag.valueOf(line));
        }
        tag.put("auctionAnomalies", anomalyList);
        ListTag corruptList = new ListTag();
        for (CompoundTag raw : auctionCorrupt) {
            corruptList.add(raw.copy());
        }
        tag.put("auctionCorrupt", corruptList);
        ListTag demandList = new ListTag();
        for (DemandEntry entry : demands) {
            demandList.add(entry.save(registries));
        }
        tag.put("demands", demandList);
        tag.putLong("demandCounter", demandCounter);
        // 公共 API 幂等键（字符串列表；老存档没有就为空）
        ListTag apiRequestList = new ListTag();
        for (String key : apiRequests) {
            apiRequestList.add(StringTag.valueOf(key));
        }
        tag.put("apiRequests", apiRequestList);
        // 自定义货币名（追加字段，老存档没有就是空 = 用配置值）
        tag.putString("shopCurrency", shopCurrency == null ? "" : shopCurrency);
        ListTag exchangeList = new ListTag();
        for (ExchangeEntry entry : exchangePool) {
            exchangeList.add(entry.save(registries));
        }
        tag.put("exchangePool", exchangeList);
        tag.putLong("listingCounter", listingCounter);
        tag.putLong("nextAuctionId", nextAuctionId);
        tag.putLong("lastStallClearDay", lastStallClearDay);
        tag.putLong("lastMallRestockDay", lastMallRestockDay);
        tag.putLong("lastMallRestockGameDay", lastMallRestockGameDay);
        tag.putLong("mallRefreshLastReal", mallRefreshLastReal);
        tag.putLong("mallRefreshLastGame", mallRefreshLastGame);
        tag.putBoolean("buybackBonusEnabled", buybackBonusEnabled);
        tag.putIntArray("shopWeights", shopWeights);
        tag.putIntArray("shopGuarantee", shopGuarantee);
        tag.putInt("shopSlotCount", shopSlotCount);
        tag.putIntArray("buybackWeights", buybackWeights);
        tag.putIntArray("buybackGuarantee", buybackGuarantee);
        tag.putInt("buybackSlotCount", buybackSlotCount);
        CompoundTag signTag = new CompoundTag();
        for (Map.Entry<UUID, SignInData> entry : signIns.entrySet()) {
            signTag.put(entry.getKey().toString(), entry.getValue().save(registries));
        }
        tag.put("signIns", signTag);
        net.minecraft.nbt.ListTag redeemList = new net.minecraft.nbt.ListTag();
        for (RedeemCode rc : redeemCodes.values()) {
            redeemList.add(rc.save(registries));
        }
        tag.put("redeemCodes", redeemList);
        net.minecraft.nbt.ListTag actList = new net.minecraft.nbt.ListTag();
        for (ActivityDay act : activityDays.values()) {
            actList.add(act.save(registries));
        }
        tag.put("activityDays", actList);
        tag.putBoolean("signInEnabled", signInEnabled);
        tag.putBoolean("signInAuto", signInAuto);
        tag.putBoolean("signInCumulative", signInCumulative);
        tag.putLong("signInBaseDaily", signInBaseDaily);
        tag.putLong("signInMakeupCost", signInMakeupCost);
        tag.putInt("signInMakeupDays", signInMakeupDays);
        tag.putIntArray("signInTierDays", signInTierDays);
        tag.putLongArray("signInTierBonus", signInTierBonus);
        tag.putIntArray("signInMileDays", signInMileDays);
        tag.putLongArray("signInMileMoney", signInMileMoney);
        tag.putLong("buybackBonusOverride", buybackBonusOverride);
        tag.put("sidebarLayout", sidebarLayout.save());
        return tag;
    }

    public static ShopSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ShopSavedData data = new ShopSavedData();

        data.templateInitDone = tag.getBoolean("templateInitDone");
        data.seedDefaultsApplied = tag.getBoolean("seedDefaultsApplied");
        CompoundTag accountsTag = tag.getCompound("accounts");
        for (String key : accountsTag.getAllKeys()) {
            try {
                data.accounts.put(UUID.fromString(key), PlayerAccount.load(accountsTag.getCompound(key), registries));
            } catch (IllegalArgumentException ignored) {
            }
        }

        ListTag shopList = tag.getList("shopPool", Tag.TAG_COMPOUND);
        for (int i = 0; i < shopList.size(); i++) {
            data.shopPool.add(ConfiguredOffer.load(shopList.getCompound(i), registries));
        }
        ListTag buybackList = tag.getList("buybackPool", Tag.TAG_COMPOUND);
        for (int i = 0; i < buybackList.size(); i++) {
            data.buybackPool.add(ConfiguredOffer.load(buybackList.getCompound(i), registries));
        }
        ListTag mallList = tag.getList("mallPool", Tag.TAG_COMPOUND);
        for (int i = 0; i < mallList.size(); i++) {
            MallItem item = MallItem.load(mallList.getCompound(i), registries);
            if (item.stack != null && !item.stack.isEmpty() && item.price > 0L) {
                data.mallPool.add(item);
            }
        }
        ListTag recentList = tag.getList("mallRecent", Tag.TAG_COMPOUND);
        for (int i = 0; i < recentList.size(); i++) {
            data.mallRecent.add(MallPurchase.load(recentList.getCompound(i), registries));
        }
        data.trimMallRecent();
        ListTag demandList = tag.getList("demands", Tag.TAG_COMPOUND);
        for (int i = 0; i < demandList.size(); i++) {
            data.demands.add(DemandEntry.load(demandList.getCompound(i), registries));
        }
        data.demandCounter = Math.max(1L, tag.getLong("demandCounter"));
        data.shopCurrency = tag.getString("shopCurrency");
        ListTag apiRequestList = tag.getList("apiRequests", Tag.TAG_STRING);
        for (int i = 0; i < apiRequestList.size(); i++) {
            data.apiRequests.add(apiRequestList.getString(i));
        }
        long legacyDuration;
        try {
            legacyDuration = cn.autoforged.module_shop.Config.INSTANCE.auctionDurationSeconds.get() * 1000L;
        } catch (RuntimeException notLoaded) {
            legacyDuration = 7_200_000L;
        }
        ListTag auctionList = tag.getList("auctions", Tag.TAG_COMPOUND);
        for (int i = 0; i < auctionList.size(); i++) {
            loadAuctionSafely(data, auctionList.getCompound(i), registries, legacyDuration, false);
        }
        ListTag historyList = tag.getList("auctionHistory", Tag.TAG_COMPOUND);
        for (int i = 0; i < historyList.size(); i++) {
            loadAuctionSafely(data, historyList.getCompound(i), registries, legacyDuration, true);
        }
        ListTag anomalyList = tag.getList("auctionAnomalies", Tag.TAG_STRING);
        for (int i = 0; i < anomalyList.size(); i++) {
            data.auctionAnomalies.add(anomalyList.getString(i));
        }
        ListTag corruptList = tag.getList("auctionCorrupt", Tag.TAG_COMPOUND);
        for (int i = 0; i < corruptList.size(); i++) {
            data.auctionCorrupt.add(corruptList.getCompound(i).copy());
        }
        // 防御：存档里记录的自增 id 落后于实际最大 id（手改存档 / 旧版本 bug）时往前推，避免 id 撞车。
        long maxId = 0L;
        for (AuctionEntry e : data.auctions) {
            maxId = Math.max(maxId, e.id);
        }
        for (AuctionEntry e : data.auctionHistory) {
            maxId = Math.max(maxId, e.id);
        }
        data.pendingAuctionMaxId = maxId;
        ListTag exchangeList = tag.getList("exchangePool", Tag.TAG_COMPOUND);
        for (int i = 0; i < exchangeList.size(); i++) {
            ExchangeEntry entry = ExchangeEntry.load(exchangeList.getCompound(i), registries);
            if (entry.valid()) {
                data.exchangePool.add(entry);
            }
        }
        // 老存档没有这两个标签时按「开启 + 用配置默认金额」处理。
        if (tag.contains("shopSlotCount")) {
            data.shopSlotCount = Math.max(1, Math.min(12, tag.getInt("shopSlotCount")));
            data.buybackSlotCount = Math.max(1, Math.min(12, tag.getInt("buybackSlotCount")));
            copyInts(tag.getIntArray("shopWeights"), data.shopWeights);
            copyInts(tag.getIntArray("shopGuarantee"), data.shopGuarantee);
            copyInts(tag.getIntArray("buybackWeights"), data.buybackWeights);
            copyInts(tag.getIntArray("buybackGuarantee"), data.buybackGuarantee);
        }
        CompoundTag signTag = tag.getCompound("signIns");
        for (String key : signTag.getAllKeys()) {
            try {
                data.signIns.put(UUID.fromString(key), SignInData.load(signTag.getCompound(key), registries));
            } catch (IllegalArgumentException ignored) {
            }
        }
        net.minecraft.nbt.ListTag redeemList = tag.getList("redeemCodes", 10);
        for (int i = 0; i < redeemList.size(); i++) {
            RedeemCode rc = RedeemCode.load(redeemList.getCompound(i), registries);
            if (!rc.code.isEmpty()) {
                data.redeemCodes.put(rc.code, rc);
            }
        }
        net.minecraft.nbt.ListTag actList = tag.getList("activityDays", 10);
        for (int i = 0; i < actList.size(); i++) {
            ActivityDay act = ActivityDay.load(actList.getCompound(i), registries);
            data.activityDays.put(act.day, act);
        }
        if (tag.contains("signInEnabled")) {
            data.signInEnabled = tag.getBoolean("signInEnabled");
            data.signInAuto = tag.getBoolean("signInAuto");
            data.signInCumulative = tag.getBoolean("signInCumulative");
            data.signInBaseDaily = tag.getLong("signInBaseDaily");
            data.signInMakeupCost = tag.getLong("signInMakeupCost");
            data.signInMakeupDays = tag.getInt("signInMakeupDays");
            int[] td = tag.getIntArray("signInTierDays");
            long[] tb = tag.getLongArray("signInTierBonus");
            if (td.length > 0) { System.arraycopy(td, 0, data.signInTierDays, 0, Math.min(td.length, data.signInTierDays.length)); }
            if (tb.length > 0) { System.arraycopy(tb, 0, data.signInTierBonus, 0, Math.min(tb.length, data.signInTierBonus.length)); }
            int[] md = tag.getIntArray("signInMileDays");
            long[] mm = tag.getLongArray("signInMileMoney");
            if (md.length > 0) { System.arraycopy(md, 0, data.signInMileDays, 0, Math.min(md.length, data.signInMileDays.length)); }
            if (mm.length > 0) { System.arraycopy(mm, 0, data.signInMileMoney, 0, Math.min(mm.length, data.signInMileMoney.length)); }
        }
        data.buybackBonusEnabled = !tag.contains("buybackBonusEnabled") || tag.getBoolean("buybackBonusEnabled");
        data.buybackBonusOverride = tag.contains("buybackBonusOverride") ? tag.getLong("buybackBonusOverride") : -1L;
        data.listingCounter = tag.getLong("listingCounter");        if (data.listingCounter <= 0) {
            data.listingCounter = 1L;
        }
        data.nextAuctionId = tag.getLong("nextAuctionId");
        if (data.nextAuctionId <= data.pendingAuctionMaxId) {
            data.nextAuctionId = data.pendingAuctionMaxId + 1L;
        }
        if (data.nextAuctionId <= 0) {
            data.nextAuctionId = 1L;
        }
        data.lastStallClearDay = tag.contains("lastStallClearDay") ? tag.getLong("lastStallClearDay") : Long.MIN_VALUE;
        data.lastMallRestockDay = tag.contains("lastMallRestockDay") ? tag.getLong("lastMallRestockDay") : Long.MIN_VALUE;
        data.mallRefreshLastReal = tag.getLong("mallRefreshLastReal");
        data.mallRefreshLastGame = tag.getLong("mallRefreshLastGame");
        data.lastMallRestockGameDay = tag.contains("lastMallRestockGameDay")
                ? tag.getLong("lastMallRestockGameDay") : Long.MIN_VALUE;
        SidebarLayout layout = SidebarLayout.load(tag.getCompound("sidebarLayout"));
        data.sidebarLayout.clear();
        data.sidebarLayout.setOrder(layout.order());

        data.seedDefaultsIfEmpty();
        return data;
    }

    private ListTag saveOffers(List<ConfiguredOffer> offers, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ConfiguredOffer offer : offers) {
            list.add(offer.save(registries));
        }
        return list;
    }

    /** 读档时的拍卖最大 id（只在 load 里用来修正 nextAuctionId）。 */
    private transient long pendingAuctionMaxId;

    /** 一条拍卖解析失败时只隔离这一条：原始数据进 auctionCorrupt，存档其余部分照常读取。 */
    private static void loadAuctionSafely(ShopSavedData data, CompoundTag raw, HolderLookup.Provider registries,
            long legacyDuration, boolean history) {
        try {
            AuctionEntry entry = AuctionEntry.load(raw, registries, legacyDuration);
            if (history || !entry.active()) {
                data.auctionHistory.add(entry);
            } else {
                data.auctions.add(entry);
            }
        } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger("module_shop").error("拍卖数据损坏，已隔离保存（id={}）",
                    raw.getLong("id"), error);
            data.auctionCorrupt.add(raw.copy());
        }
    }

    /** 追加一条拍卖异常日志（带时间戳，超出上限丢最旧）。 */
    public void auctionAnomaly(String message) {
        String line = java.time.LocalDateTime.now().withNano(0) + " " + message;
        auctionAnomalies.add(line);
        while (auctionAnomalies.size() > AUCTION_ANOMALY_MAX) {
            auctionAnomalies.remove(0);
        }
        org.slf4j.LoggerFactory.getLogger("module_shop/auction").warn(message);
        setDirty();
    }

    private ListTag saveAuctions(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (AuctionEntry entry : auctions) {
            list.add(entry.save(registries));
        }
        return list;
    }

    /** 把 NBT 数组拷进定长数组（长度不足时保留原值）。 */
    private static void copyInts(int[] from, int[] to) {
        if (from == null || from.length == 0) {
            return;
        }
        System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
    }

    private static ShopSavedData create() {
        ShopSavedData data = new ShopSavedData();
        data.seedDefaultsIfEmpty();
        return data;
    }

    private static final Factory<ShopSavedData> FACTORY = new Factory<>(ShopSavedData::create, ShopSavedData::load);

    public static ShopSavedData get(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(FACTORY, FILE_ID);
    }

    // ============================ 默认商品池 ============================

    /**
     * 池子是否仍是模组自带的默认商品（用于判断"这是新世界还是已配置过的世界"）。
     *
     * <p>比对的是物品 id 集合；只要玩家增删过任何一件商品就不算默认，自动初始化就不会覆盖它。
     */
    public boolean isSeedDefaultPools() {
        // 直接和"刚从默认种子生成的一份数据"逐项比对：以后默认池改了这里也不用改。
        ShopSavedData fresh = new ShopSavedData();
        fresh.seedDefaultsIfEmpty();
        if (shopSlotCount != fresh.shopSlotCount || buybackSlotCount != fresh.buybackSlotCount) {
            return false;
        }
        if (!java.util.Arrays.equals(shopWeights, fresh.shopWeights)
                || !java.util.Arrays.equals(buybackWeights, fresh.buybackWeights)) {
            return false;
        }
        if (!java.util.Arrays.equals(shopGuarantee, fresh.shopGuarantee)
                || !java.util.Arrays.equals(buybackGuarantee, fresh.buybackGuarantee)) {
            return false;
        }
        return sameIds(shopPool, fresh.shopPool) && sameIds(buybackPool, fresh.buybackPool);
    }

    /** 两个商品池的物品 id 多重集合是否完全一致。 */
    private static boolean sameIds(java.util.List<ConfiguredOffer> a, java.util.List<ConfiguredOffer> b) {
        if (a.size() != b.size()) {
            return false;
        }
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (ConfiguredOffer offer : a) {
            try {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(offer.stack.getItem()).toString();
                counts.merge(id, 1, Integer::sum);
            } catch (RuntimeException error) {
                return false;
            }
        }
        for (ConfiguredOffer offer : b) {
            try {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(offer.stack.getItem()).toString();
                Integer left = counts.get(id);
                if (left == null || left <= 0) {
                    return false;
                }
                counts.put(id, left - 1);
            } catch (RuntimeException error) {
                return false;
            }
        }
        return true;
    }

    private void seedDefaultsIfEmpty() {
        if (shopPool.isEmpty() && buybackPool.isEmpty()) {
            addDefault(shopPool, Items.APPLE, 5, Rarity.COMMON);
            addDefault(shopPool, Items.BREAD, 8, Rarity.COMMON);
            addDefault(shopPool, Items.TORCH, 2, Rarity.COMMON);
            addDefault(shopPool, Items.COAL, 4, Rarity.COMMON);
            addDefault(shopPool, Items.OAK_LOG, 3, Rarity.COMMON);
            addDefault(shopPool, Items.WHEAT_SEEDS, 2, Rarity.COMMON);
            addDefault(shopPool, Items.STRING, 3, Rarity.COMMON);
            addDefault(shopPool, Items.LEATHER, 6, Rarity.COMMON);

            addDefault(shopPool, Items.IRON_INGOT, 60, Rarity.RARE);
            addDefault(shopPool, Items.GOLD_INGOT, 80, Rarity.RARE);
            addDefault(shopPool, Items.REDSTONE, 60, Rarity.RARE);
            addDefault(shopPool, Items.LAPIS_LAZULI, 70, Rarity.RARE);
            addDefault(shopPool, Items.ENDER_PEARL, 90, Rarity.RARE);

            addDefault(shopPool, Items.DIAMOND, 300, Rarity.EPIC);
            addDefault(shopPool, Items.EMERALD, 260, Rarity.EPIC);
            addDefault(shopPool, Items.NETHERITE_SCRAP, 500, Rarity.EPIC);
            addDefault(shopPool, Items.GOLDEN_APPLE, 400, Rarity.EPIC);

            addDefault(shopPool, Items.NETHERITE_INGOT, 1500, Rarity.LEGENDARY);
            addDefault(shopPool, Items.DIAMOND_BLOCK, 2500, Rarity.LEGENDARY);
            addDefault(shopPool, Items.ELYTRA, 5000, Rarity.LEGENDARY);
            addDefault(shopPool, Items.NETHER_STAR, 8000, Rarity.LEGENDARY);
            addDefault(shopPool, Items.BEACON, 3000, Rarity.LEGENDARY);

            addDefault(buybackPool, Items.WHEAT, 4, Rarity.COMMON);
            addDefault(buybackPool, Items.CARROT, 4, Rarity.COMMON);
            addDefault(buybackPool, Items.POTATO, 4, Rarity.COMMON);
            addDefault(buybackPool, Items.COBBLESTONE, 1, Rarity.COMMON);
            addDefault(buybackPool, Items.OAK_LOG, 2, Rarity.COMMON);
            addDefault(buybackPool, Items.BONE, 3, Rarity.COMMON);
            addDefault(buybackPool, Items.IRON_INGOT, 40, Rarity.RARE);
            addDefault(buybackPool, Items.GOLD_INGOT, 55, Rarity.RARE);
            addDefault(buybackPool, Items.DIAMOND, 180, Rarity.EPIC);
            addDefault(buybackPool, Items.NETHERITE_SCRAP, 320, Rarity.EPIC);
        }
    }

    private static void addDefault(List<ConfiguredOffer> pool, net.minecraft.world.item.Item item, long price, Rarity rarity) {
        pool.add(new ConfiguredOffer(new ItemStack(item), price, rarity));
    }
}
