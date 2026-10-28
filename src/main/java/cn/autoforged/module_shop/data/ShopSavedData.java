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
        ListTag auctionList = tag.getList("auctions", Tag.TAG_COMPOUND);
        for (int i = 0; i < auctionList.size(); i++) {
            data.auctions.add(AuctionEntry.load(auctionList.getCompound(i), registries));
        }
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
