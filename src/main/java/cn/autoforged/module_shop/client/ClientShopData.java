package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.data.ConfiguredOffer;
import cn.autoforged.module_shop.data.DailyBuybackEntry;
import cn.autoforged.module_shop.data.DailyShopEntry;
import cn.autoforged.module_shop.data.MailEntry;
import cn.autoforged.module_shop.data.Rarity;
import cn.autoforged.module_shop.data.ShopNbt;
import cn.autoforged.module_shop.data.StallEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 客户端缓存的商店快照。仅作展示，所有数值以服务端为准。 */
public final class ClientShopData {
    /** 模组默认货币名（= Config.currencyName 的默认值）：显示时翻译，英文客户端会显示 Star Coin。 */
    public static final String DEFAULT_CURRENCY = "星币";

    public static long balance;
    /** 余额上限（服务端下发，用于显示「已满」提示）。 */
    public static long maxBalance;
    public static String currency = "";
    public static String symbol = "";
    /** 需求 6：金钱显示统一追加的后缀（如“星币”），颜色与金额一致。 */
    public static String moneySuffix = "";
    public static boolean canAdmin;
    public static String defaultTab = "daily";
    public static String playerName = "";

    // ---- 玩家交易（kind=trade）----
    public static String tradeSelfName = "";
    public static String tradeOtherName = "";
    public static List<ItemStack> tradeSelfItems = new ArrayList<>();
    public static List<ItemStack> tradeOtherItems = new ArrayList<>();
    public static long tradeSelfMoney;
    public static long tradeOtherMoney;
    public static boolean tradeSelfAgreed;
    public static boolean tradeOtherAgreed;
    public static boolean tradeSelfConfirmed;
    public static boolean tradeOtherConfirmed;
    public static boolean tradeAgreedBoth;
    public static long tradeCountdownMs;
    /** 需求 2：对方是否已同意；未同意前发起方看到对方那侧为灰色等待态。 */
    public static boolean tradeAccepted;
    /** 需求 2：当前玩家是否为发起方。 */
    public static boolean tradeRequester;

    /** 需求 11：服务端当前记录的编辑模块（shop=每日商店 / buyback=每日收购）。 */
    public static String adminEditType = "shop";
    /** 需求 1：收到的交易请求列表。 */
    public static List<TradeRequest> tradeRequests = new ArrayList<>();

    public static List<DailyShopEntry> dailyShop = new ArrayList<>();
    public static List<Integer> dailyFlipped = new ArrayList<>();
    public static List<OnlinePlayer> onlinePlayers = new ArrayList<>();
    public static List<DailyBuybackEntry> dailyBuyback = new ArrayList<>();
    public static List<AuctionView> auctions = new ArrayList<>();
    public static List<StallEntry> stall = new ArrayList<>();
    public static List<StallPlayer> stallPlayers = new ArrayList<>();
    public static List<MailEntry> mail = new ArrayList<>();
    /** 第七轮：邮箱当前选中的邮件（mailId；空 = 未选中）。 */
    public static String mailSelected = "";
    public static List<ConfiguredOffer> adminShop = new ArrayList<>();
    public static List<ConfiguredOffer> adminBuyback = new ArrayList<>();
    /** 购物城（新增模块）：常驻货架。 */
    public static List<MallView> mall = new ArrayList<>();
    /** 购物城快照版本号（每次收到新快照 +1；过滤缓存据此失效）。 */
    public static int mallVersion;
    /** 购物城：管理员可编辑的货架（amount 复用为库存，-1 = 无限）。 */
    public static List<ConfiguredOffer> adminMall = new ArrayList<>();
    /** 出货箱：管理员可编辑的「兑换规则」（amount 复用为每日份数，0 = 不限）。 */
    public static List<ConfiguredOffer> adminShipping = new ArrayList<>();
    /** 出货箱规则的原文与格式错误原因（与 adminShipping 一一对应；错误为空表示这条正常）。 */
    public static List<String> adminShippingRaw = new ArrayList<>();
    public static List<String> adminShippingErrors = new ArrayList<>();
    /** 购物城：最近购买记录（最新的在前）。 */
    public static List<MallPurchaseView> mallRecent = new ArrayList<>();

    // ---- 购物城界面状态（跨界面重建保留：买卖一次后不会跳回第一页 / 丢掉搜索词）----
    /** 当前页码（0 起）。 */
    public static int mallPage;
    /** 搜索词（物品名 / 模组名，忽略大小写）。 */
    public static String mallSearch = "";
    /** 排序：0 默认 / 1 价格升 / 2 价格降 / 3 库存升 / 4 库存降。 */
    public static int mallSort;
    /** 分类过滤：空 = 全部；否则是模组命名空间。 */
    public static String mallCategory = "";
    /** 只看收藏。 */
    public static boolean mallFavOnly;
    /** true = 看"最近购买"，false = 看货架。 */
    public static boolean mallViewRecent;
    /** 最近购买列表的滚动行。 */
    public static int mallRecentScroll;

    // ---- 出货箱（新增模块）----
    /** 是否启用。 */
    public static boolean shipEnabled = true;
    /** 当前等级与最大等级。 */
    public static int shipLevel = 1;
    public static int shipMaxLevel = 3;
    /** 当前等级的格子数与箱内件数。 */
    public static int shipSlots = 9;
    public static int shipItems;
    /** 本次结算能拿到的金额（已按今日剩余额度截断）。 */
    public static long shipValue;
    /** 今日已结算金额与每日上限（0 = 不限）。 */
    public static long shipSoldToday;
    public static long shipLimit;
    /** 升级花费；-1 = 已满级 / 无法升级。 */
    public static long shipUpgradeCost = -1L;
    /** 每天结算的游戏刻（0 = 早上 6:00）。 */
    public static int shipSettleTick;
    /** 额度重置时间（现实时间 HH:mm；展示与「每日额度」弹窗回填用）。 */
    public static String shipResetTimeText = "00:00";
    // ---- 购物城定时补货（管理页「补货设置」弹窗回填 + 玩家页一天一行提示）----
    /** 是否开启了定时补货。 */
    public static boolean mallRestockOn;
    /** 时间口径："real"（现实时间） / "game"（游戏时间）。 */
    public static String mallRestockMode = "real";
    /** 补货时刻（HH:mm）。 */
    public static String mallRestockTimeText = "00:00";
    /** 补货数量：0 = 补回各自原量；>0 = 统一补到该数量。 */
    public static int mallRestockAmount;
    /** 购物城库存周期刷新：off / real / game + 周期（real=小时、game=天）。 */
    public static String mallRefreshMode = "off";
    public static int mallRefreshAmount = 24;
    /** 购物城库存是不是「个人库存」（服务端下发；个人库存时每件商品的 stock 是**自己**的剩余量）。 */
    public static boolean mallStockPersonal;
    /**
     * 摊位自动下架方式（服务端配置下发）：{@code permanent} / {@code game} / {@code real}。
     * <p>摊位页据此显示每件商品的剩余时间；{@code permanent} 时不显示。
     */
    public static String stallExpireMode = "permanent";
    /** 摊位限时时长：game 按游戏天数、real 按现实小时。 */
    public static int stallExpireAmount = 7;
    // ---- 玩家求购（悬赏）----
    /** 全部求购单（服务端权威；含托管中的悬赏内容）。 */
    public static java.util.List<cn.autoforged.module_shop.data.DemandEntry> demands =
            new java.util.ArrayList<>();
    public static boolean demandEnabled = true;
    public static int demandSlots = 5;
    public static double demandFeePercent;
    public static long demandMaxReward;
    public static String demandExpireMode = "permanent";
    public static int demandExpireAmount = 7;
    /** 管理页当前模块的**显示名**（已按改名规则解析：改过名时是「新名（原名）」；批量添加界面标题用）。 */
    public static String adminModeLabel = "";

    /** 管理页当前模块的显示名（批量添加界面标题用）。 */
    public static String adminModeName() {
        return adminModeLabel == null || adminModeLabel.isBlank()
                ? Component.translatable("gui.module_shop.admin_shop").getString()
                : adminModeLabel;
    }

    /** 每个等级的每日结算上限（管理页「每日额度」弹窗回填用；0 = 不限）。 */
    public static java.util.List<Long> shipDailyLimits = new java.util.ArrayList<>();
    /** 规则条数与格式错误条数（错误时界面红字提示）。 */
    public static int shipRules;
    public static int shipRuleErrors;
    public static String shipRuleErrorText = "";
    /** 生效的规则原文（换行分隔）：出货箱界面用它本地计算"本次结算金额"。 */
    public static String shipRulesText = "";
    /** 货币兑换：管理员配置的「1 个物品 = N 星币」列表。 */
    public static List<ExchangeView> exchange = new ArrayList<>();
    /** 管理员发放界面的可选玩家（在线优先，含历史离线玩家）。 */
    public static List<GrantTarget> grantTargets = new ArrayList<>();

    // ---- 兑换码奖励草稿（管理员在「奖励配置」界面里配好的物品 / 星币 / 玩家）----
    /** 草稿物品（「id ×N,」文本，展示用）。 */
    public static String redeemDraftItems = "";
    /** 草稿星币金额。 */
    public static long redeemDraftMoney;
    /** 草稿指定的玩家名（展示用；勾选状态按名字回填）。 */
    public static List<String> redeemDraftPlayers = new ArrayList<>();
    /** 「查看」兑换码时服务端回传的领取名单（name + 次数）。 */
    public static String redeemClaimedCode = "";
    public static List<String> redeemClaimedNames = new ArrayList<>();
    public static List<Integer> redeemClaimedCounts = new ArrayList<>();

    // ---- 每日商店付费刷新 / 收购全清奖励 ----
    /** 今日已付费刷新次数。 */
    public static int refreshUsed;
    /** 今日可付费刷新总次数（= 服务端价格表长度）。 */
    public static int refreshMax;
    /** 下一次付费刷新的花费；-1 表示今日次数已用完。 */
    public static long refreshCost = -1L;
    // ---- 每日收购付费刷新（与商店完全分开计数 / 定价）----
    /** 今日已付费刷新每日收购的次数。 */
    public static int buybackRefreshUsed;
    /** 今日可付费刷新每日收购的总次数（= 服务端价格表长度）。 */
    public static int buybackRefreshMax;
    /** 下一次付费刷新每日收购的花费；-1 表示今日次数已用完。 */
    public static long buybackRefreshCost = -1L;
    /** 当日收购清单全部完成的奖励金额（0=关闭）。 */
    public static long buybackBonus;
    /** 收购全清奖励是否开启（关闭时每日收购底部不再显示奖励提示）。 */
    public static boolean buybackBonusEnabled = true;

    // ---- 签到（需求 7）----
    public static boolean signInEnabled = true;
    public static boolean signInAuto = true;
    public static boolean signInCumulative = true;
    public static long signInBase;
    public static long signInMakeupCost;
    public static int signInMakeupDays;
    public static long signInToday;
    public static boolean signInDone;
    public static int signInStreak;
    public static int signInBest;
    public static List<Long> signInDays = new ArrayList<>();
    public static int[] signInTierDays = {3, 5, 7};
    public static long[] signInTierBonus = {5L, 7L, 10L};
    public static int[] signInMileDays = {3, 5, 7};
    public static long[] signInMileMoney = {100L, 300L, 800L};
    /** 里程轮次长度（默认 7 天一轮）：进度条按「本轮第几天」画，满一轮就重新从 0 开始。 */
    public static int signInCycleDays = 7;
    /** 活动日（需求 7）：日期 epochDay → {标题, 公告, 金额, 是否已领}。 */
    public static List<ActivityView> activityDays = new ArrayList<>();
    /**
     * 管理员正在编辑的日程**草稿**（服务端只发给正在编辑的那个管理员）。
     *
     * <p>日程编辑的三个界面（编辑 / 奖励设置 / 奖励容器）都只改这份草稿，
     * 只有编辑界面点「保存」才真正写进 {@link #activityDays} 并广播给所有玩家。
     * {@code null} = 当前没有草稿。
     */
    public static ActivityView activityDraft;
    /**
     * 侧栏自定义顺序（页签 id 列表）。服务端权威：管理员拖拽排序后保存并热同步给所有玩家。
     * <p>
     * 只存顺序、不存坐标 —— 面板里是固定网格，坐标由 {@code ShopScreen} 按顺序算出来，
     * 这样永远不会出现像素级偏移或按钮互相压住。
     */
    public static List<String> sidebarOrder = new ArrayList<>();
    /** 被管理员移除、不在侧栏显示的模块 id（新增模块仍会自动出现）。 */
    public static List<String> sidebarHidden = new ArrayList<>();
    /**
     * 模块**自定义名称**（id → 名字）：管理员在编辑布局里右键页签改名后由服务端下发。
     * <p>没改过的模块不在这个表里，界面退回 lang 里的默认翻译名。
     */
    public static java.util.Map<String, String> sidebarNames = new java.util.LinkedHashMap<>();
    /** 商店主标题（左上角）；空 = 用默认「模块商店」。 */
    public static String shopTitle = "";

    /** 某个模块的自定义名；没改过返回 {@code null}。 */
    public static String sidebarNameOf(String id) {
        String name = id == null ? null : sidebarNames.get(id);
        return name == null || name.isBlank() ? null : name;
    }

    /** 某页签在当前自定义顺序里的下标；不在自定义顺序里返回 -1（调用方退回默认顺序）。 */
    public static int sidebarIndex(String id) {
        return id == null ? -1 : sidebarOrder.indexOf(id);
    }
    /** 第八轮：正在配置「日程奖励」的日期（epochDay；0 = 未在配置）。 */
    public static long scheduleDay;
    /** 刷新参数（需求 8）：每日收购列表滚动到的首个条目下标。 */
    public static int buybackListRow;
    /** 刷新参数（需求 8）：每日商店牌数超过 6 时，列表滚动到的首个条目下标。 */
    public static int dailyListRow;
    // ---- 刷新参数（需求 8）：每日商店 / 每日收购各自一套 ----
    public static int[] shopWeights = {5, 15, 30, 50};
    public static int[] shopGuarantee = {0, 0, 0};
    public static int shopSlotCount = 6;
    public static int[] buybackWeights = {5, 15, 30, 50};
    public static int[] buybackGuarantee = {0, 0, 0};
    public static int buybackSlotCount = 6;
    /** 签到界面当前显示的月份（自 1970-01 起的月数）。 */
    public static int signInMonth;
    /** 该奖励今日是否已发放。 */
    public static boolean buybackBonusDone;
    /** 收购清单里还剩几项没卖满。 */
    public static int buybackLeft;

    // ---- 界面状态（跨界面重建保留）----
    // 服务端在每次操作后会重建商店界面；若不在客户端记住页数，编辑条目/买卖后就会被弹回第一页。
    public static String lastTab = "daily";
    public static int lastAuctionPage;
    public static int lastAdminPage;
    public static int lastMailPage;
    public static String lastAdminMode = "shop";
    /** 货币兑换界面：当前展开的条目下标（-1 = 全部收起）。 */
    public static int lastExchangeOpen = -1;
    /** 货币兑换界面：列表滚动到的首个条目下标（整行滚动，不是像素偏移）。 */
    public static int lastExchangeScrollRow;

    // ---- 账单（点主界面余额进入）----
    /** 一条账单明细。 */
    public static final class BillEntry {
        public long time;
        public String type = "";
        public String party = "";
        public long amount;
        public long balanceAfter;
        public String note = "";
    }

    /** 账单明细（最新在前，条数上限由服务端下发）。 */
    public static List<BillEntry> bill = new ArrayList<>();
    public static long billBalance;
    public static int billLimit;
    public static int billSize;
    public static long billTodayIn;
    public static long billTodayOut;
    public static long billMonthIn;
    public static long billMonthOut;
    /** 上一次收到账单数据的时间（客户端本地毫秒，用于界面显示"刚刚更新"）。 */
    public static long billUpdatedAt;

    /** 解析服务端下发的账单（kind=bill）。 */
    private static void applyBill(CompoundTag tag) {
        billBalance = tag.getLong("balance");
        billLimit = tag.getInt("limit");
        billSize = tag.getInt("size");
        billTodayIn = tag.getLong("todayIn");
        billTodayOut = tag.getLong("todayOut");
        billMonthIn = tag.getLong("monthIn");
        billMonthOut = tag.getLong("monthOut");
        List<BillEntry> list = new ArrayList<>();
        ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag c = entries.getCompound(i);
            BillEntry entry = new BillEntry();
            entry.time = c.getLong("time");
            entry.type = c.getString("type");
            entry.party = c.contains("counterParty") ? c.getString("counterParty") : "";
            entry.amount = c.getLong("amount");
            entry.balanceAfter = c.getLong("balanceAfter");
            entry.note = c.getString("note");
            list.add(entry);
        }
        bill = list;
        billUpdatedAt = System.currentTimeMillis();
    }

    /** 解析服务端下发的日程编辑草稿（kind=activity_draft；day<=0 表示草稿已清空）。 */
    private static void applyActivityDraft(CompoundTag c) {
        long day = c.getLong("day");
        if (day <= 0L) {
            activityDraft = null;
            return;
        }
        ActivityView view = new ActivityView();
        view.day = day;
        view.title = c.getString("title");
        view.note = c.getString("note");
        view.money = c.getLong("money");
        view.forOffline = c.getBoolean("forOffline");
        view.granted = false;
        view.catchUp = !c.contains("catchUp") || c.getBoolean("catchUp");
        view.itemText = c.getString("item");
        view.itemIds = c.getString("itemIds");
        view.color = c.getInt("color");
        view.hour = c.contains("hour") ? c.getInt("hour") : -1;
        activityDraft = view;
    }

    private ClientShopData() {
    }

    public static void update(CompoundTag tag, HolderLookup.Provider registries) {
        if ("bill".equals(tag.getString("kind"))) {
            applyBill(tag);
            return;
        }
        // 兑换码草稿是独立的小包（不含余额等快照字段），必须在通用赋值之前返回，否则会把余额清零。
        if ("redeem_draft".equals(tag.getString("kind"))) {
            applyRedeemDraft(tag);
            return;
        }
        // 日程编辑草稿：同样是小包（只有管理员会收到），只影响编辑界面看到的数值。
        if ("activity_draft".equals(tag.getString("kind"))) {
            applyActivityDraft(tag);
            return;
        }
        balance = tag.getLong("balance");
        maxBalance = tag.contains("maxBalance") ? tag.getLong("maxBalance") : 0L;
        currency = tag.getString("currency");
        symbol = tag.getString("symbol");
        // 需求 6：统一金钱后缀（如“星币”）。
        moneySuffix = tag.contains("moneySuffix") ? tag.getString("moneySuffix") : currency;
        // 管理员改过货币名时，把语言表里写死的「星币」统一换掉（聊天与界面文案一起生效）。
        CurrencyNames.apply(moneySuffix);
        canAdmin = tag.getBoolean("canAdmin");
        defaultTab = tag.getString("defaultTab");
        playerName = tag.getString("playerName");
        if (tag.contains("adminEditType")) {
            adminEditType = tag.getString("adminEditType");
        }
        // 付费刷新每日商店 / 收购全清奖励状态。
        refreshUsed = tag.getInt("refreshUsed");
        refreshMax = tag.getInt("refreshMax");
        refreshCost = tag.contains("refreshCost") ? tag.getLong("refreshCost") : -1L;
        buybackRefreshUsed = tag.getInt("buybackRefreshUsed");
        buybackRefreshMax = tag.getInt("buybackRefreshMax");
        buybackRefreshCost = tag.contains("buybackRefreshCost") ? tag.getLong("buybackRefreshCost") : -1L;
        buybackBonus = tag.getLong("buybackBonus");
        buybackBonusEnabled = !tag.contains("buybackBonusEnabled") || tag.getBoolean("buybackBonusEnabled");
        signInEnabled = !tag.contains("signInEnabled") || tag.getBoolean("signInEnabled");
        signInAuto = !tag.contains("signInAuto") || tag.getBoolean("signInAuto");
        signInCumulative = !tag.contains("signInCumulative") || tag.getBoolean("signInCumulative");
        signInBase = tag.getLong("signInBase");
        signInMakeupCost = tag.getLong("signInMakeupCost");
        signInMakeupDays = tag.getInt("signInMakeupDays");
        signInToday = tag.getLong("signInToday");
        signInDone = tag.getBoolean("signInDone");
        signInStreak = tag.getInt("signInStreak");
        signInBest = tag.getInt("signInBest");
        List<Long> days = new ArrayList<>();
        for (long d : tag.getLongArray("signInDays")) {
            days.add(d);
        }
        signInDays = days;
        List<ActivityView> acts = new ArrayList<>();
        ListTag actTag = tag.getList("activityDays", Tag.TAG_COMPOUND);
        for (int i = 0; i < actTag.size(); i++) {
            CompoundTag c = actTag.getCompound(i);
            ActivityView view = new ActivityView();
            view.day = c.getLong("day");
            view.title = c.getString("title");
            view.note = c.getString("note");
            view.money = c.getLong("money");
            view.forOffline = c.getBoolean("forOffline");
            view.granted = c.getBoolean("granted");
            view.catchUp = !c.contains("catchUp") || c.getBoolean("catchUp");
            view.itemText = c.getString("item");
            // 第八轮：可解析的物品 id 列表（用于在悬浮窗里画物品图标）。
            view.itemIds = c.getString("itemIds");
            view.color = c.getInt("color");
            view.hour = c.contains("hour") ? c.getInt("hour") : -1;
            view.giftText = c.getString("gift");
            acts.add(view);
        }
        activityDays = acts;
        // 侧栏顺序（服务端权威；空 = 用默认顺序）+ 被移除的模块。
        sidebarOrder = new ArrayList<>();
        ListTag orderList = tag.getCompound("sidebarLayout").getList("order", Tag.TAG_STRING);
        for (int i = 0; i < orderList.size(); i++) {
            sidebarOrder.add(orderList.getString(i));
        }
        sidebarHidden = new ArrayList<>();
        ListTag hiddenList = tag.getCompound("sidebarLayout").getList("hidden", Tag.TAG_STRING);
        for (int i = 0; i < hiddenList.size(); i++) {
            sidebarHidden.add(hiddenList.getString(i));
        }
        // 模块自定义名（改名后所有界面都用它显示）
        sidebarNames = new java.util.LinkedHashMap<>();
        shopTitle = tag.getCompound("sidebarLayout").getString("title");
        CompoundTag nameTag = tag.getCompound("sidebarLayout").getCompound("names");
        for (String key : nameTag.getAllKeys()) {
            String value = nameTag.getString(key);
            if (value != null && !value.isBlank()) {
                sidebarNames.put(key, value);
            }
        }
        int[] sw = tag.getIntArray("shopWeights");
        if (sw.length >= 4) {
            shopWeights = sw;
        }
        int[] sg = tag.getIntArray("shopGuarantee");
        if (sg.length >= 3) {
            shopGuarantee = sg;
        }
        int[] bw = tag.getIntArray("buybackWeights");
        if (bw.length >= 4) {
            buybackWeights = bw;
        }
        int[] bg = tag.getIntArray("buybackGuarantee");
        if (bg.length >= 3) {
            buybackGuarantee = bg;
        }
        if (tag.contains("shopSlotCount")) {
            shopSlotCount = Math.max(1, tag.getInt("shopSlotCount"));
            buybackSlotCount = Math.max(1, tag.getInt("buybackSlotCount"));
        }
        int[] td = tag.getIntArray("signInTierDays");
        if (td.length > 0) { signInTierDays = td; }
        long[] tb = tag.getLongArray("signInTierBonus");
        if (tb.length > 0) { signInTierBonus = tb; }
        int[] md = tag.getIntArray("signInMileDays");
        if (md.length > 0) { signInMileDays = md; }
        long[] mm = tag.getLongArray("signInMileMoney");
        if (mm.length > 0) { signInMileMoney = mm; }
        signInCycleDays = tag.contains("signInCycleDays") ? Math.max(1, tag.getInt("signInCycleDays")) : 7;
        buybackBonusDone = tag.getBoolean("buybackBonusDone");
        buybackLeft = tag.getInt("buybackLeft");

        List<DailyShopEntry> newDaily = new ArrayList<>();
        ListTag dailyTag = tag.getList("dailyShop", Tag.TAG_COMPOUND);
        for (int i = 0; i < dailyTag.size(); i++) {
            newDaily.add(DailyShopEntry.load(dailyTag.getCompound(i), registries));
        }
        dailyShop = newDaily;

        List<Integer> newFlipped = new ArrayList<>();
        for (int index : tag.getIntArray("dailyFlipped")) {
            newFlipped.add(index);
        }
        dailyFlipped = newFlipped;

        List<OnlinePlayer> newOnline = new ArrayList<>();
        ListTag onlineTag = tag.getList("onlinePlayers", Tag.TAG_COMPOUND);
        for (int i = 0; i < onlineTag.size(); i++) {
            CompoundTag c = onlineTag.getCompound(i);
            OnlinePlayer view = new OnlinePlayer();
            try {
                view.uuid = UUID.fromString(c.getString("uuid"));
            } catch (IllegalArgumentException ignored) {
                view.uuid = null;
            }
            view.name = c.getString("name");
            newOnline.add(view);
        }
        onlinePlayers = newOnline;

        List<DailyBuybackEntry> newBuyback = new ArrayList<>();
        ListTag buybackTag = tag.getList("dailyBuyback", Tag.TAG_COMPOUND);
        for (int i = 0; i < buybackTag.size(); i++) {
            newBuyback.add(DailyBuybackEntry.load(buybackTag.getCompound(i), registries));
        }
        dailyBuyback = newBuyback;

        List<AuctionView> newAuctions = new ArrayList<>();
        ListTag auctionTag = tag.getList("auctions", Tag.TAG_COMPOUND);
        for (int i = 0; i < auctionTag.size(); i++) {
            CompoundTag c = auctionTag.getCompound(i);
            AuctionView view = new AuctionView();
            view.id = c.getLong("id");
            view.stack = ItemStack.parseOptional(registries, c.getCompound("item"));
            view.seller = c.getString("seller");
            view.startPrice = c.getLong("startPrice");
            view.currentBid = c.getLong("currentBid");
            // 需求 3：上一次出价，用于竞拍界面的划掉效果。
            view.lastBid = c.getLong("lastBid");
            view.topBidder = c.getString("topBidder");
            view.remaining = c.getLong("remaining");
            // 需求三：记录快照到达时刻，界面据此在两次同步之间实时递减剩余时间。
            view.receivedAt = System.currentTimeMillis();
            view.own = c.getBoolean("own");
            // 我是不是当前最高出价者（用于“最高：你”提示）。
            view.topMe = c.getBoolean("topMe");
            newAuctions.add(view);
        }
        auctions = newAuctions;

        List<StallEntry> newStall = new ArrayList<>();
        ListTag stallTag = tag.getList("stall", Tag.TAG_COMPOUND);
        for (int i = 0; i < stallTag.size(); i++) {
            newStall.add(StallEntry.load(stallTag.getCompound(i), registries));
        }
        stall = newStall;

        List<StallPlayer> newPlayers = new ArrayList<>();
        ListTag playerTag = tag.getList("stallPlayers", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerTag.size(); i++) {
            CompoundTag c = playerTag.getCompound(i);
            StallPlayer view = new StallPlayer();
            try {
                view.uuid = UUID.fromString(c.getString("uuid"));
            } catch (IllegalArgumentException ignored) {
                view.uuid = null;
            }
            view.name = c.getString("name");
            view.count = c.getInt("count");
            newPlayers.add(view);
        }
        stallPlayers = newPlayers;

        List<MailEntry> newMail = new ArrayList<>();
        ListTag mailTag = tag.getList("mail", Tag.TAG_COMPOUND);
        for (int i = 0; i < mailTag.size(); i++) {
            MailEntry entry = MailEntry.load(mailTag.getCompound(i), registries);
            // 邮件标题/正文是服务端拼好的**纯文本**（不走语言表），所以这里单独替换货币名。
            entry.title = CurrencyNames.text(entry.title);
            entry.content = CurrencyNames.text(entry.content);
            newMail.add(entry);
        }
        mail = newMail;

        adminShop = parseOffers(tag.getList("adminShop", Tag.TAG_COMPOUND), registries);
        adminBuyback = parseOffers(tag.getList("adminBuyback", Tag.TAG_COMPOUND), registries);
        adminMall = parseOffers(tag.getList("adminMall", Tag.TAG_COMPOUND), registries);
        adminShipping = parseOffers(tag.getList("adminShipping", Tag.TAG_COMPOUND), registries);
        List<String> rawRules = new ArrayList<>();
        ListTag rawTag = tag.getList("adminShippingRaw", Tag.TAG_STRING);
        for (int i = 0; i < rawTag.size(); i++) {
            rawRules.add(rawTag.getString(i));
        }
        adminShippingRaw = rawRules;
        List<String> ruleErrors = new ArrayList<>();
        ListTag errTag = tag.getList("adminShippingErrors", Tag.TAG_STRING);
        for (int i = 0; i < errTag.size(); i++) {
            ruleErrors.add(errTag.getString(i));
        }
        adminShippingErrors = ruleErrors;

        // 购物城货架
        List<MallView> newMall = new ArrayList<>();
        ListTag mallTag = tag.getList("mall", Tag.TAG_COMPOUND);
        for (int i = 0; i < mallTag.size(); i++) {
            CompoundTag c = mallTag.getCompound(i);
            MallView view = new MallView();
            view.shelf = newMall.size();
            view.stack = ShopNbt.loadStack(c, registries);
            view.price = c.getLong("price");
            view.rarity = Rarity.byId(c.getString("rarity"));
            view.stock = c.contains("stock") ? c.getInt("stock") : -1;
            view.sort = c.getInt("sort");
            if (!view.stack.isEmpty()) {
                newMall.add(view);
            }
        }
        mall = newMall;
        // 每次快照都换一批新对象：过滤缓存的 key 里带上版本号，避免界面还拿着旧对象的下标去购买。
        mallVersion++;

        // 购物城最近购买（服务端已按最新在前下发）
        List<MallPurchaseView> newRecent = new ArrayList<>();
        ListTag recentTag = tag.getList("mallRecent", Tag.TAG_COMPOUND);
        for (int i = 0; i < recentTag.size(); i++) {
            CompoundTag c = recentTag.getCompound(i);
            MallPurchaseView view = new MallPurchaseView();
            view.stack = ShopNbt.loadStack(c, registries);
            view.playerName = c.getString("player");
            view.count = Math.max(1, c.getInt("count"));
            view.unitPrice = c.getLong("unit");
            view.total = c.getLong("total");
            view.time = c.getLong("time");
            newRecent.add(view);
        }
        mallRecent = newRecent;

        // 出货箱（新增模块）：老服务端没有这些字段时保持默认值，界面按"未启用"显示。
        shipEnabled = !tag.contains("shipEnabled") || tag.getBoolean("shipEnabled");
        shipLevel = Math.max(1, tag.contains("shipLevel") ? tag.getInt("shipLevel") : 1);
        shipMaxLevel = Math.max(1, tag.contains("shipMaxLevel") ? tag.getInt("shipMaxLevel") : 3);
        shipSlots = Math.max(1, tag.contains("shipSlots") ? tag.getInt("shipSlots") : 9);
        shipItems = tag.getInt("shipItems");
        shipValue = tag.getLong("shipValue");
        shipSoldToday = tag.getLong("shipSoldToday");
        shipLimit = tag.getLong("shipLimit");
        shipUpgradeCost = tag.contains("shipUpgradeCost") ? tag.getLong("shipUpgradeCost") : -1L;
        shipSettleTick = tag.getInt("shipSettleTick");
        shipResetTimeText = tag.contains("shipResetTime") ? tag.getString("shipResetTime") : "00:00";
        mallRestockOn = tag.getBoolean("mallRestockOn");
        mallRestockMode = tag.contains("mallRestockMode") ? tag.getString("mallRestockMode") : "real";
        mallRestockTimeText = tag.contains("mallRestockTime") ? tag.getString("mallRestockTime") : "00:00";
        mallRestockAmount = tag.getInt("mallRestockAmount");
        mallRefreshMode = tag.contains("mallRefreshMode") ? tag.getString("mallRefreshMode") : "off";
        mallRefreshAmount = tag.contains("mallRefreshAmount") ? tag.getInt("mallRefreshAmount") : 24;
        mallStockPersonal = tag.getBoolean("mallStockPersonal");
        stallExpireMode = tag.contains("stallExpireMode") ? tag.getString("stallExpireMode") : "permanent";
        stallExpireAmount = tag.contains("stallExpireAmount") ? tag.getInt("stallExpireAmount") : 7;
        // 玩家求购
        demandEnabled = !tag.contains("demandEnabled") || tag.getBoolean("demandEnabled");
        demandSlots = tag.contains("demandSlots") ? tag.getInt("demandSlots") : 5;
        demandFeePercent = tag.getDouble("demandFeePercent");
        demandMaxReward = tag.getLong("demandMaxReward");
        demandExpireMode = tag.contains("demandExpireMode") ? tag.getString("demandExpireMode") : "permanent";
        demandExpireAmount = tag.contains("demandExpireAmount") ? tag.getInt("demandExpireAmount") : 7;
        java.util.List<cn.autoforged.module_shop.data.DemandEntry> newDemands =
                new java.util.ArrayList<>();
        ListTag demandTag = tag.getList("demands", Tag.TAG_COMPOUND);
        for (int i = 0; i < demandTag.size(); i++) {
            newDemands.add(cn.autoforged.module_shop.data.DemandEntry.load(
                    demandTag.getCompound(i), registries));
        }
        demands = newDemands;
        shipRules = tag.getInt("shipRules");
        shipRuleErrors = tag.getInt("shipRuleErrors");
        shipRuleErrorText = tag.getString("shipRuleErrorText");
        shipRulesText = tag.getString("shipRulesText");
        java.util.List<Long> limits = new java.util.ArrayList<>();
        for (long value : tag.getLongArray("shipDailyLimits")) {
            limits.add(value);
        }
        shipDailyLimits = limits;

        List<ExchangeView> newExchange = new ArrayList<>();
        ListTag exchangeTag = tag.getList("exchange", Tag.TAG_COMPOUND);
        for (int i = 0; i < exchangeTag.size(); i++) {
            CompoundTag c = exchangeTag.getCompound(i);
            ExchangeView view = new ExchangeView();
            view.stack = ShopNbt.loadStack(c, registries);
            view.value = c.getLong("value");
            view.owned = c.getInt("owned");
            newExchange.add(view);
        }
        exchange = newExchange;
        if (lastExchangeOpen >= exchange.size()) {
            lastExchangeOpen = -1;
        }
    }

    /** 单独更新交易面板数据（kind=trade）。 */
    public static void updateTrade(CompoundTag tag, HolderLookup.Provider registries) {
        tradeSelfName = tag.getString("selfName");
        tradeOtherName = tag.getString("otherName");
        tradeSelfItems = parseItems(tag.getList("selfItems", Tag.TAG_COMPOUND), registries);
        tradeOtherItems = parseItems(tag.getList("otherItems", Tag.TAG_COMPOUND), registries);
        tradeSelfMoney = tag.getLong("selfMoney");
        tradeOtherMoney = tag.getLong("otherMoney");
        tradeSelfAgreed = tag.getBoolean("selfAgreed");
        tradeOtherAgreed = tag.getBoolean("otherAgreed");
        tradeSelfConfirmed = tag.getBoolean("selfConfirmed");
        tradeOtherConfirmed = tag.getBoolean("otherConfirmed");
        tradeAgreedBoth = tag.getBoolean("agreedBoth");
        tradeCountdownMs = tag.getLong("countdown");
        tradeAccepted = tag.getBoolean("accepted");
        tradeRequester = tag.getBoolean("requester");
    }

    /** 管理员发放界面：更新可选玩家列表（kind=admin_grant）。 */
    public static void updateGrantTargets(CompoundTag tag) {
        List<GrantTarget> result = new ArrayList<>();
        ListTag list = tag.getList("targets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            GrantTarget target = new GrantTarget();
            try {
                target.uuid = UUID.fromString(c.getString("uuid"));
            } catch (IllegalArgumentException ignored) {
                target.uuid = null;
            }
            target.name = c.getString("name");
            target.online = c.getBoolean("online");
            result.add(target);
        }
        grantTargets = result;
    }

    /** 兑换码奖励草稿（kind=redeem_draft）：生成器上的「奖励内容」摘要与编辑界面的回填都用它。 */
    public static void applyRedeemDraft(CompoundTag tag) {
        redeemDraftItems = tag.getString("items");
        redeemDraftMoney = Math.max(0L, tag.getLong("money"));
        List<String> names = new ArrayList<>();
        ListTag list = tag.getList("players", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String name = list.getString(i);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        redeemDraftPlayers = names;
    }

    /** 兑换码领取名单（kind=redeem_claimed）：「查看」按钮弹出的列表用它。 */
    public static void applyRedeemClaimed(CompoundTag tag) {
        redeemClaimedCode = tag.getString("code");
        List<String> names = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            names.add(c.getString("name"));
            counts.add(c.getInt("count"));
        }
        redeemClaimedNames = names;
        redeemClaimedCounts = counts;
    }

    /** 需求 1：更新“收到的交易请求”列表。 */
    public static void updateRequests(CompoundTag tag) {
        List<TradeRequest> result = new ArrayList<>();
        ListTag list = tag.getList("requests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            TradeRequest request = new TradeRequest();
            request.id = c.getLong("id");
            request.from = c.getString("from");
            request.ageMs = c.getLong("age");
            // 需求二：记录快照到达时刻，倒计时文字据此每帧实时递减。
            request.receivedAt = System.currentTimeMillis();
            result.add(request);
        }
        tradeRequests = result;
    }

    /**
     * 货币显示：金额 + 货币名。
     * 默认货币名（星币）走语言文件，所以英文客户端会显示成 “1000 Star Coin”；
     * 服主在配置里自定义的货币名原样显示（ASCII 名字会自动补一个空格）。
     */
    public static String money(long amount) {
        String name = currencyName();
        if (name.isEmpty()) {
            return Long.toString(amount);
        }
        // 中文名紧跟数字（1000星币），英文/ASCII 名前面补空格（1000 Star Coin）。
        return amount + (name.charAt(0) < 0x80 ? " " : "") + name;
    }

    /** 当前显示用的货币名：默认值走语言文件，自定义值原样返回。 */
    public static String currencyName() {
        String name = moneySuffix == null ? "" : moneySuffix;
        if (DEFAULT_CURRENCY.equals(name)) {
            return Component.translatable("currency.module_shop.default").getString();
        }
        return name;
    }

    public static String money(long amount, String prefix) {
        return prefix + money(amount);
    }

    /** 交易结束后清空本地交易缓存，避免影响后续界面判断。 */
    public static void clearTrade() {
        tradeSelfName = "";
        tradeOtherName = "";
        tradeSelfItems = new ArrayList<>();
        tradeOtherItems = new ArrayList<>();
        tradeSelfMoney = 0L;
        tradeOtherMoney = 0L;
        tradeSelfAgreed = false;
        tradeOtherAgreed = false;
        tradeSelfConfirmed = false;
        tradeOtherConfirmed = false;
        tradeAgreedBoth = false;
        tradeCountdownMs = 0L;
        tradeAccepted = false;
        tradeRequester = false;
    }

    private static List<ItemStack> parseItems(ListTag list, HolderLookup.Provider registries) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(ShopNbt.loadStack(list.getCompound(i), registries));
        }
        return result;
    }

    private static List<ConfiguredOffer> parseOffers(ListTag list, HolderLookup.Provider registries) {
        List<ConfiguredOffer> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(ConfiguredOffer.load(list.getCompound(i), registries));
        }
        return result;
    }

    public static final class AuctionView {
        public long id;
        public ItemStack stack = ItemStack.EMPTY;
        public String seller = "";
        public long startPrice;
        public long currentBid;
        /** 需求 3：上一次出价（0 表示尚无人出价）。 */
        public long lastBid;
        public String topBidder = "";
        public long remaining;
        /** 需求三：该快照到达客户端的时刻（毫秒），用于实时递减剩余时间。 */
        public long receivedAt;
        public boolean own;
        /** 我是不是当前最高出价者。 */
        public boolean topMe;
    }

    public static final class StallPlayer {
        public UUID uuid;
        public String name = "";
        public int count;
    }

    /** 在线玩家（需求 4：交易界面显示在线玩家名片）。 */
    public static final class OnlinePlayer {
        public UUID uuid;
        public String name = "";
    }

    /** 管理员发放界面的一个可选玩家（在线 + 历史离线玩家）。 */
    public static final class GrantTarget {
        public UUID uuid;
        public String name = "";
        public boolean online;
    }

    /** 活动日（签到日历用）。 */
    public static final class ActivityView {
        public long day;
        public String title = "";
        public String note = "";
        public long money;
        public boolean forOffline;
        public boolean granted;
        /** 错过定时点后是否补发（true = 当天任意时刻登录都补发一次）。 */
        public boolean catchUp = true;
        /** 活动日物品（「物品ID x数量」文本，展示/回填用）。 */
        public String itemText = "";
        /** 日程颜色（ARGB；0 = 默认橙色），可配置。 */
        public int color;
        /** 定时发放的小时（现实时间 0-23；-1 = 不定时）。 */
        public int hour = -1;
        /** 定时发放的物品（「物品ID x数量」文本）。 */
        public String giftText = "";
        /** 第八轮：全部奖励物品的可解析 id 列表（如 {@code minecraft:diamond x2,minecraft:gold_ingot x5}）。 */
        public String itemIds = "";
    }

    /** 货币兑换界面的一行：模板物品 + 汇率 + 玩家背包里的持有量。 */
    public static final class ExchangeView {
        public ItemStack stack = ItemStack.EMPTY;
        /** 1 个该物品 = value 星币。 */
        public long value;
        /** 玩家背包中该物品的数量（服务端统计，界面展示用）。 */
        public int owned;
    }

    /** 购物城货架上的一件商品。 */
    public static final class MallView {
        /** 该商品在 mall 列表里的下标（= 服务端 mallPool 下标）：点击购买时用它，绝不靠对象身份查找。 */
        public int shelf = -1;
        public ItemStack stack = ItemStack.EMPTY;
        public long price;
        public Rarity rarity = Rarity.COMMON;
        /** 库存：-1 = 无限；0 = 售罄；>0 = 剩余数量。 */
        public int stock = -1;
        /** 上架顺序（默认排序用）。 */
        public int sort;

        public boolean unlimited() {
            return stock < 0;
        }

        public boolean soldOut() {
            return stock == 0;
        }

        /** 该物品属于哪个模组（注册名的命名空间），用于「按模组分类」。 */
        public String namespace() {
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? "minecraft" : id.getNamespace();
        }
    }

    /** 购物城的一条购买记录。 */
    public static final class MallPurchaseView {
        public String playerName = "";
        public ItemStack stack = ItemStack.EMPTY;
        public int count = 1;
        public long unitPrice;
        public long total;
        public long time;
    }

    /** 需求 1：一条待处理的交易请求。 */
    public static final class TradeRequest {
        public long id;
        public String from = "";
        public long ageMs;
        /** 需求二：该请求快照到达客户端的时刻（毫秒），用于实时递减倒计时。 */
        public long receivedAt;
    }
}
