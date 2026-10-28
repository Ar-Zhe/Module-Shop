package cn.autoforged.module_shop;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 模块商店的公共配置。所有数值都可以由服主在 config/module_shop-common.toml 中调整。
 * 需求里出现冲突的属性，这里采用“以玩法意图优先”的折中默认值，并在注释中标注。
 */
public final class Config {
    public static final Config INSTANCE;
    public static final ModConfigSpec SPEC;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    // ---- 钱包 ----
    public final ModConfigSpec.ConfigValue<String> currencyName;
    public final ModConfigSpec.ConfigValue<String> currencySymbol;
    public final ModConfigSpec.LongValue initialBalance;
    public final ModConfigSpec.LongValue maxBalance;
    public final ModConfigSpec.IntValue transferCooldownTicks;
    public final ModConfigSpec.BooleanValue allowPlayerTransfer;

    // ---- 每日商店 ----
    public final ModConfigSpec.IntValue dailyShopSlots;
    /** 每日商店里“普通”档位最多出现几个（0=不限制）。 */
    public final ModConfigSpec.IntValue dailyShopCommonLimit;
    /** 付费刷新每日商店的价格表（按次数顺序取用：第 1 次、第 2 次…），用完即不可再刷新。 */
    public final ModConfigSpec.ConfigValue<List<? extends String>> dailyRefreshCosts;
    /** 每日收购的付费刷新价格表（与每日商店**完全分开**：价格、次数各自独立）。 */
    public final ModConfigSpec.ConfigValue<List<? extends String>> buybackRefreshCosts;
    public final ModConfigSpec.IntValue weightLegendary;
    public final ModConfigSpec.IntValue weightEpic;
    public final ModConfigSpec.IntValue weightRare;
    public final ModConfigSpec.IntValue weightCommon;
    public final ModConfigSpec.IntValue limitLegendary;
    public final ModConfigSpec.IntValue limitEpic;
    public final ModConfigSpec.IntValue limitRare;
    public final ModConfigSpec.IntValue limitCommon;

    // ---- 每日收购 ----
    public final ModConfigSpec.IntValue buybackSlots;
    public final ModConfigSpec.IntValue buybackCapacity;
    /** 当日把收购清单全部卖完的一次性奖励（0=关闭，默认 1000）。 */
    public final ModConfigSpec.LongValue buybackAllBonus;

    // ---- 拍卖（时长全部按现实时间，单位秒）----
    public final ModConfigSpec.IntValue auctionDurationSeconds;
    public final ModConfigSpec.LongValue auctionMinIncrement;
    public final ModConfigSpec.IntValue maxConcurrentAuctions;
    /** 需求 2：拍卖成交后从卖家所得中扣除的手续费百分比（默认 5%）。 */
    public final ModConfigSpec.DoubleValue auctionFeePercent;
    public final ModConfigSpec.BooleanValue antiSnipe;
    public final ModConfigSpec.IntValue antiSnipeSeconds;
    public final ModConfigSpec.IntValue antiSnipeExtensionSeconds;

    // ---- 邮箱 ----
    /** 邮箱上限（默认 100；界面每页 45 封，可翻页）。达到上限时系统资产返还优先改为掉落入包，防止溢出刷物品。 */
    public final ModConfigSpec.IntValue maxMailboxSize;
    public final ModConfigSpec.IntValue mailExpireDays;
    /** 每名玩家保留的交易流水条数（管理员用 /shop admin history 查看）。 */
    public final ModConfigSpec.IntValue transactionHistorySize;

    // ---- 摊位 ----
    public final ModConfigSpec.IntValue stallSlots;
    public final ModConfigSpec.DoubleValue stallListingFeePercent;
    /** 玩家摊位自动下架方式：permanent=永久 / game=按游戏时间 / real=按现实时间。 */
    /** 购物城：库存刷新方式（off / real / game）与周期（real=小时、game=天）。 */
    public final ModConfigSpec.ConfigValue<String> mallRefreshMode;
    public final ModConfigSpec.IntValue mallRefreshAmount;
    /** 购物城库存模式：global = 全服共用一份库存 / personal = 每个玩家各自一份库存。 */
    public final ModConfigSpec.ConfigValue<String> mallStockMode;
    public final ModConfigSpec.ConfigValue<String> stallExpireMode;
    /** 限时时长：game 按游戏天数，real 按现实小时（0 = 永不自动下架）。 */
    public final ModConfigSpec.IntValue stallExpireAmount;

    // ---- 玩家求购（悬赏）----
    /** 是否开启玩家求购模块。 */
    public final ModConfigSpec.BooleanValue demandEnabled;
    /** 每名玩家最多同时发布几条求购。 */
    public final ModConfigSpec.IntValue demandSlots;
    /** 发布手续费百分比（按悬赏金币算，0 = 免费）。 */
    public final ModConfigSpec.DoubleValue demandFeePercent;
    /** 单笔悬赏金币上限（0 = 不限）。 */
    public final ModConfigSpec.IntValue demandMaxReward;
    /** 全服最多保留多少条求购（超出时最老的自动下架并退款）。 */
    public final ModConfigSpec.IntValue demandMaxListed;
    /** 求购自动下架方式：permanent=永久 / game=按游戏时间 / real=按现实时间。 */
    public final ModConfigSpec.ConfigValue<String> demandExpireMode;
    /** 限时时长：game 按游戏天数，real 按现实小时（0 = 永不自动下架）。 */
    public final ModConfigSpec.IntValue demandExpireAmount;

    // ---- 玩家交易（现实时间，单位秒）----
    public final ModConfigSpec.IntValue tradeCountdownSeconds;
    /** 需求 1：交易请求失效时间（现实秒），超时自动清理。 */
    public final ModConfigSpec.IntValue tradeRequestExpireSeconds;
    /** 已接受的交易多久没有任何操作就自动结束并退回物品（现实秒，默认 10 分钟）。 */
    public final ModConfigSpec.IntValue tradeSessionTimeoutSeconds;

    // ---- 出货箱（新增模块，参考星露谷售货箱）----
    public final ModConfigSpec.BooleanValue shippingEnabled;
    /** 兑换规则（每行一条：物品ID=价格 / #标签=价格 / 命名空间:*=价格，价格后可加 x数量 限定每日份数）。 */
    public final ModConfigSpec.ConfigValue<List<? extends String>> shippingRules;
    /** 每个等级的槽位数（列表长度 = 等级数）。 */
    public final ModConfigSpec.ConfigValue<List<? extends String>> shippingLevelSlots;
    /** 升级花费（第 1 项 = 1→2 级）。 */
    public final ModConfigSpec.ConfigValue<List<? extends String>> shippingUpgradeCosts;
    /** 每个等级的每日结算上限（现实 0 点刷新）。 */
    public final ModConfigSpec.ConfigValue<List<? extends String>> shippingDailyLimits;
    /** 每天结算的游戏刻（0 = 早上 06:00）。 */
    public final ModConfigSpec.IntValue shippingSettleTick;
    /** 结算窗口长度（刻）。 */
    public final ModConfigSpec.IntValue shippingSettleWindow;
    /** 每日额度按**现实时间**在几点重置（HH:mm，默认 00:00）。 */
    public final ModConfigSpec.ConfigValue<String> shippingResetTime;

    // ---- 购物城（新增模块）----
    /** 购物城是否定时补货（默认关；管理员可在游戏内「补货设置」里打开）。 */
    public final ModConfigSpec.BooleanValue mallRestockEnabled;
    /** 补货时刻的时间口径：{@code real} = 现实时间，{@code game} = 游戏时间。 */
    public final ModConfigSpec.ConfigValue<String> mallRestockMode;
    /** 补货时刻（HH:mm）。 */
    public final ModConfigSpec.ConfigValue<String> mallRestockTime;
    /** 补货数量：0 = 每件商品补回各自原量；>0 = 所有商品统一补到该数量。 */
    public final ModConfigSpec.IntValue mallRestockAmount;

    // ---------------------------------------------------------------- HTTP / REST API（默认关闭）
    /** 是否启用 HTTP API（默认关闭；启用前请务必设置 token 并保持只监听 127.0.0.1）。 */
    public final ModConfigSpec.BooleanValue httpApiEnabled;
    /** 监听地址（默认 127.0.0.1；填 0.0.0.0 会把经济接口暴露到网络，除非你很清楚在做什么）。 */
    public final ModConfigSpec.ConfigValue<String> httpApiBind;
    /** 监听端口。 */
    public final ModConfigSpec.IntValue httpApiPort;
    /** 访问令牌（Bearer）。为空时 HTTP API 拒绝启动。 */
    public final ModConfigSpec.ConfigValue<String> httpApiToken;
    /** 是否允许写操作（加钱/扣钱/发奖/发邮件）。默认 false = 只读。 */
    public final ModConfigSpec.BooleanValue httpApiAllowWrite;
    /** 每个 IP 每分钟最多请求数（0 = 不限，不建议）。 */
    public final ModConfigSpec.IntValue httpApiRateLimit;

    private Config(ModConfigSpec.Builder builder) {
        builder.push("wallet");
        currencyName = builder.comment("货币名称（需求：币种统一为星币，所有金额显示统一追加该后缀）").define("currencyName", "星币");
        currencySymbol = builder.comment("货币符号，留空则只显示数字").define("currencySymbol", "");
        initialBalance = builder.comment("新玩家初始余额").defineInRange("initialBalance", 0L, 0L, 9999999999L);
        // 0 = 不限：服主想「不设上限」时直接写 0 即可（以前最小值是 1，写 0 会被自动纠正成 1，
        // 于是玩家余额一超过 1 就显示「（已满）」——这就是那个「已满」bug 的根源）。
        maxBalance = builder.comment("余额上限（0 = 不限）").defineInRange("maxBalance", 9999999999L, 0L, Long.MAX_VALUE);
        transferCooldownTicks = builder.comment("玩家间转账冷却（tick）").defineInRange("transferCooldownTicks", 20, 0, 100000);
        allowPlayerTransfer = builder.comment("是否允许玩家间转账").define("allowPlayerTransfer", true);
        builder.pop();

        builder.push("daily_shop");
        // 界面固定 3 列 × 2 行卡片（每日商店没有翻页），配置上限就是 6。
        dailyShopSlots = builder.comment("每日商店槽位数量（界面固定 3 列 × 2 行，最多 6）").defineInRange("dailyShopSlots", 6, 1, 6);
        dailyShopCommonLimit = builder.comment("每日商店“普通”档位最多出现数量（0=不限制，默认 3）")
                .defineInRange("dailyShopCommonLimit", 3, 0, 6);
        dailyRefreshCosts = builder.comment("付费刷新【每日商店】的价格表（按次数顺序：默认第 1 次 200、第 2 次 500；列表长度=每天可刷新次数）")
                .defineList("dailyRefreshCosts", List.of("200", "500"), () -> "200", o -> o instanceof String);
        buybackRefreshCosts = builder.comment("付费刷新【每日收购】的价格表（与每日商店相互独立：可分别设置价格与每天次数）")
                .defineList("buybackRefreshCosts", List.of("200", "500"), () -> "200", o -> o instanceof String);
        weightLegendary = builder.comment("传说权重").defineInRange("weightLegendary", 5, 0, 100000);
        weightEpic = builder.comment("史诗权重").defineInRange("weightEpic", 15, 0, 100000);
        weightRare = builder.comment("稀有权重").defineInRange("weightRare", 30, 0, 100000);
        weightCommon = builder.comment("普通权重").defineInRange("weightCommon", 50, 0, 100000);
        limitLegendary = builder.comment("每日每玩家可购买传说数量").defineInRange("limitLegendary", 1, 1, 1000);
        limitEpic = builder.comment("每日每玩家可购买史诗数量").defineInRange("limitEpic", 2, 1, 1000);
        limitRare = builder.comment("每日每玩家可购买稀有数量").defineInRange("limitRare", 5, 1, 1000);
        limitCommon = builder.comment("每日每玩家可购买普通数量").defineInRange("limitCommon", 10, 1, 1000);
        builder.pop();

        builder.push("daily_buyback");
        // 需求冲突：daily_slots=4 与 buyback_slots="6项(丰富)"。取可玩性更高的 6。
        // 界面只渲染 6 行（没有翻页），所以每日最多随机 6 项收购需求。
        buybackSlots = builder.comment("每日收购槽位数量（每日最多随机 6 项，界面只显示 6 行）").defineInRange("buybackSlots", 6, 1, 6);
        buybackCapacity = builder.comment("每个收购项每日可收购次数").defineInRange("buybackCapacity", 16, 1, 100000);
        buybackAllBonus = builder.comment("当日收购清单全部完成的一次性奖励星币（0=关闭）")
                .defineInRange("buybackAllBonus", 1000L, 0L, 9999999999L);
        builder.pop();

        builder.push("auction");
        // 现实时间：7200 秒 = 2 小时
        auctionDurationSeconds = builder.comment("拍卖持续时间（现实秒，7200=2小时）").defineInRange("auctionDurationSeconds", 7200, 1, Integer.MAX_VALUE);
        // 需求冲突：min_bid_increment=1 与 bid_increment="10星币(标准)"。取标准值 10。
        auctionMinIncrement = builder.comment("最小加价幅度").defineInRange("auctionMinIncrement", 10L, 1L, Long.MAX_VALUE);
        maxConcurrentAuctions = builder.comment("每玩家同时进行的拍卖上限").defineInRange("maxConcurrentAuctions", 6, 1, 100);
        // 需求 2：交易成功扣除卖家 5% 手续费。
        auctionFeePercent = builder.comment("拍卖成交手续费百分比（从卖家所得中扣除）").defineInRange("auctionFeePercent", 5.0D, 0.0D, 100.0D);
        antiSnipe = builder.comment("是否开启防狙击").define("antiSnipe", true);
        antiSnipeSeconds = builder.comment("剩余多少秒内出价算狙击（现实秒）").defineInRange("antiSnipeSeconds", 60, 0, 3600);
        antiSnipeExtensionSeconds = builder.comment("每次狙击延长多少秒（现实秒，0 表示不延长）").defineInRange("antiSnipeExtensionSeconds", 0, 0, Integer.MAX_VALUE);
        builder.pop();

        builder.push("mailbox");
        maxMailboxSize = builder.comment("邮箱最大邮件数（默认 100，界面每页 45 封可翻页）").defineInRange("maxMailboxSize", 100, 1, 1000);
        mailExpireDays = builder.comment("邮件过期天数（0=永久，遵循 expire_time=永久；只清理已领取/已查看的邮件）").defineInRange("mailExpireDays", 0, 0, 100000);
        transactionHistorySize = builder.comment("每名玩家保留的交易流水条数（/shop admin history 查看）").defineInRange("transactionHistorySize", 50, 10, 200);
        builder.pop();

        builder.push("stall");
        stallSlots = builder.comment("摊位槽位数量").defineInRange("stallSlots", 12, 1, 45);
        // 需求冲突：free_listing=true 与 listing_fee="上架价5%"。默认免费，百分比留给服主调节。
        stallListingFeePercent = builder.comment("上架手续费百分比（0=免费上架）").defineInRange("stallListingFeePercent", 0.0D, 0.0D, 100.0D);

        // 玩家摊位自动下架：商店设置里的「下架时间」（永久 / 按游戏天数 / 按现实小时）。
        stallExpireMode = builder.comment("玩家摊位自动下架方式：permanent=永久 / game=按游戏时间 / real=按现实时间")
                .define("stallExpireMode", "permanent");
        stallExpireAmount = builder.comment("限时时长：game 按游戏天数、real 按现实小时（0 = 永不自动下架）")
                .defineInRange("stallExpireAmount", 7, 0, 100000);
        builder.pop();

        builder.push("demand");
        demandEnabled = builder.comment("是否开启玩家求购（悬赏）模块")
                .define("demandEnabled", true);
        demandSlots = builder.comment("每名玩家最多同时发布几条求购").defineInRange("demandSlots", 5, 1, 45);
        demandFeePercent = builder.comment("发布手续费百分比（按悬赏金币算，0=免费）")
                .defineInRange("demandFeePercent", 0.0D, 0.0D, 100.0D);
        demandMaxReward = builder.comment("单笔悬赏金币上限（0=不限）")
                .defineInRange("demandMaxReward", 0, 0, Integer.MAX_VALUE);
        demandMaxListed = builder.comment("全服最多保留多少条求购（超出时最老的自动下架并退款）")
                .defineInRange("demandMaxListed", 60, 1, 400);
        demandExpireMode = builder.comment("求购自动下架方式：permanent=永久 / game=按游戏时间 / real=按现实时间")
                .define("demandExpireMode", "permanent");
        demandExpireAmount = builder.comment("限时时长：game 按游戏天数、real 按现实小时（0 = 永不自动下架）")
                .defineInRange("demandExpireAmount", 7, 0, 100000);
        builder.pop();

        builder.push("http_api");
        httpApiEnabled = builder.comment("是否启用 HTTP API（默认关闭）。开启前必须设置 httpApiToken，且建议保持只监听 127.0.0.1")
                .define("httpApiEnabled", false);
        httpApiBind = builder.comment("HTTP API 监听地址（默认只监听本机）").define("httpApiBind", "127.0.0.1");
        httpApiPort = builder.comment("HTTP API 端口").defineInRange("httpApiPort", 8765, 1024, 65535);
        httpApiToken = builder.comment("HTTP API 访问令牌（Bearer）；为空则不启动 HTTP API").define("httpApiToken", "");
        httpApiAllowWrite = builder.comment("是否允许写操作（加钱/扣钱/发奖/发邮件）；false = 只读")
                .define("httpApiAllowWrite", false);
        httpApiRateLimit = builder.comment("每个 IP 每分钟最大请求数（0 = 不限）")
                .defineInRange("httpApiRateLimit", 60, 0, 100000);
        builder.pop();

        builder.push("trade");
        tradeCountdownSeconds = builder.comment("双方同意后的确认倒计时（现实秒）").defineInRange("tradeCountdownSeconds", 3, 1, 60);
        // 需求 1：未被对方接受的交易请求超时后自动清理。
        tradeRequestExpireSeconds = builder.comment("交易请求失效时间（现实秒）").defineInRange("tradeRequestExpireSeconds", 60, 5, 3600);
        tradeSessionTimeoutSeconds = builder.comment("已接受的交易闲置多久自动结束并退回物品（现实秒，默认 600=10 分钟）")
                .defineInRange("tradeSessionTimeoutSeconds", 600, 30, 86400);
        builder.pop();

        builder.push("shipping_bin");
        shippingEnabled = builder.comment("是否启用「出货箱」模块（关掉后侧栏不显示，也不结算）")
                .define("shippingEnabled", true);
        shippingRules = builder.comment(
                "兑换规则：每行一条，格式为 物品ID=价格（也支持 #标签=价格、命名空间:*=价格）",
                "价格后面可以再加 x数量 限定该物品每天最多结算多少个，例如：",
                "  minecraft:wheat=4",
                "  #minecraft:logs=3",
                "  minecraft:diamond=100 x64",
                "注释行用 // 开头；格式写错会在游戏内提醒，并且对应的物品无法放进出货箱")
                .defineList("shippingRules", List.of(
                        "minecraft:wheat=4",
                        "minecraft:carrot=4",
                        "minecraft:potato=4",
                        "minecraft:beetroot=5",
                        "minecraft:sugar_cane=3",
                        "minecraft:pumpkin=6",
                        "minecraft:melon_slice=2",
                        "minecraft:egg=3",
                        "minecraft:leather=8",
                        "#minecraft:logs=3",
                        "minecraft:iron_ingot=60",
                        "minecraft:gold_ingot=80",
                        "minecraft:diamond=300",
                        "minecraft:emerald=260"),
                        () -> "minecraft:wheat=4", o -> o instanceof String);
        shippingLevelSlots = builder.comment("每个等级的槽位数（列表长度 = 等级数；默认 9/18/27，最后一项是最大格数）")
                .defineList("shippingLevelSlots", List.of("9", "18", "27"), () -> "9", o -> o instanceof String);
        shippingUpgradeCosts = builder.comment("升级花费（第 1 项 = 1→2 级，第 2 项 = 2→3 级；列表比等级数少 1）")
                .defineList("shippingUpgradeCosts", List.of("2000", "10000"), () -> "2000", o -> o instanceof String);
        shippingDailyLimits = builder.comment("每个等级的每日结算金额上限（现实时间 0 点刷新；0 = 不限）")
                .defineList("shippingDailyLimits", List.of("1000", "2500", "6000"), () -> "1000", o -> o instanceof String);
        shippingSettleTick = builder.comment("每天结算的游戏刻（0 = 早上 06:00；结算窗口见下一项）")
                .defineInRange("shippingSettleTick", 0, 0, 23999);
        shippingSettleWindow = builder.comment("结算窗口长度（刻，默认 180 = 游戏内 9 秒；窗口内只会结算一次）")
                .defineInRange("shippingSettleWindow", 180, 1, 12000);
        shippingResetTime = builder.comment("每日额度按【现实时间】在几点重置（HH:mm，默认 00:00 = 现实零点）")
                .define("shippingResetTime", "00:00");
        builder.pop();

        builder.push("mall");
        mallRestockEnabled = builder.comment("购物城是否定时补货（默认关；也可在游戏内「商店设置 → 购物城 → 补货设置」里打开）")
                .define("mallRestockEnabled", false);
        mallRestockMode = builder.comment("补货时刻按哪种时间算：real = 现实时间，game = 游戏时间（其它值按 real）")
                .define("mallRestockMode", "real");
        mallRestockTime = builder.comment("补货时刻（HH:mm）。real：现实几点（默认 00:00）；game：游戏内时间（06:00 = 游戏 0 刻）")
                .define("mallRestockTime", "00:00");
        mallRefreshMode = builder.comment("购物城库存刷新方式：off=关闭 / real=按现实时间 / game=按游戏时间")
                .define("mallRefreshMode", "off");
        mallRefreshAmount = builder.comment("库存刷新周期：real 按小时、game 按游戏天数（默认 24 小时 / 1 天）")
                .defineInRange("mallRefreshAmount", 24, 1, 100000);
        mallStockMode = builder.comment("购物城库存模式：global = 全服共用一份库存（谁先买谁先得）"
                        + " / personal = 每个玩家各自一份库存（互不影响，刷新或补货时每人恢复）")
                .define("mallStockMode", "global");
        mallRestockAmount = builder.comment("补货数量：0 = 每件商品补回各自原量；>0 = 所有商品统一补到该数量")
                .defineInRange("mallRestockAmount", 0, 0, 1000000);
        builder.pop();

        // 同 IP 多账号登录限制已按需求移除：同一个 IP 可以正常登录多个账号，不再踢人。
    }

    // =========================================================== 出货箱：配置读取

    /** 出货箱等级数（至少 1 级）。 */
    public static int shippingLevels() {
        return Math.max(1, INSTANCE.shippingLevelSlots.get().size());
    }

    /** 第 level 级（1 起）的槽位数。 */
    public static int shippingSlotsFor(int level) {
        List<? extends String> list = INSTANCE.shippingLevelSlots.get();
        int index = Math.max(0, Math.min(list.size() - 1, level - 1));
        return Math.max(1, Math.min(54, parseIntSafe(list.get(index), 9)));
    }

    /** 从 level 升到 level+1 的花费；已满级返回 -1。 */
    public static long shippingUpgradeCost(int level) {
        List<? extends String> costs = INSTANCE.shippingUpgradeCosts.get();
        // 第 1 项对应「1 → 2 级」，第 2 项对应「2 → 3 级」，所以下标是 level-1
        // （以前写成 level，于是 1→2 级读的是第 2 项、2→3 级直接读不到 → 界面显示"已满级"）。
        int index = level - 1;
        if (index < 0 || index >= costs.size()) {
            return -1L;
        }
        return Math.max(0L, parseLongSafe(costs.get(index), 0L));
    }

    /** 第 level 级的每日结算上限（0 = 不限 → 返回 Long.MAX_VALUE）。 */
    public static long shippingDailyLimit(int level) {
        List<? extends String> list = INSTANCE.shippingDailyLimits.get();
        if (list.isEmpty()) {
            return Long.MAX_VALUE;
        }
        int index = Math.max(0, Math.min(list.size() - 1, level - 1));
        long value = parseLongSafe(list.get(index), 0L);
        return value <= 0L ? Long.MAX_VALUE : value;
    }

    private static int parseIntSafe(String text, int fallback) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLongSafe(String text, long fallback) {
        try {
            return Long.parseLong(text == null ? "" : text.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // =========================================================== 时间格式（HH:mm）

    /** 「HH:mm」→ 一天中的分钟数；格式不对返回 -1。 */
    public static int parseClock(String text) {
        if (text == null) {
            return -1;
        }
        String value = text.trim();
        int colon = value.indexOf(':');
        if (colon <= 0 || colon >= value.length() - 1) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(value.substring(0, colon).trim());
            int minute = Integer.parseInt(value.substring(colon + 1).trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** 「HH:mm」→ 游戏刻（0 = 早上 06:00，1000 刻 = 1 小时）；格式不对返回 -1。 */
    public static int clockToDayTick(String text) {
        int minutes = parseClock(text);
        if (minutes < 0) {
            return -1;
        }
        // 游戏内 0 刻 = 06:00，所以要把「06:00」当零点重新折算
        int shifted = (minutes - 6 * 60 + 24 * 60) % (24 * 60);
        return (int) ((long) shifted * 1000L / 60L);
    }

    /** 游戏刻 → 「HH:mm」（用于把配置里的结算时刻显示成时间）。 */
    public static String dayTickToClock(int tick) {
        int minutes = Math.floorMod((int) (tick * 60L / 1000L) + 6 * 60, 24 * 60);
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /** 每日额度的现实重置时间（分钟数；配置格式不对时按 0 点处理）。 */
    public static int shippingResetMinutes() {
        int minutes = parseClock(INSTANCE.shippingResetTime.get());
        return Math.max(0, minutes);
    }

    /** 每日额度归属的「现实日期」：以重置时间为分界（例如重置时间 04:00，则凌晨 3 点仍算前一天）。 */
    public static long shippingQuotaDay() {
        return realDayWithReset(INSTANCE.shippingResetTime.get());
    }

    /** 以某个「HH:mm」为分界的现实日期（epochDay）：凌晨过了分界点才算新的一天。 */
    public static long realDayWithReset(String clock) {
        int minutes = Math.max(0, parseClock(clock));
        return java.time.LocalDateTime.now().minusMinutes(minutes).toLocalDate().toEpochDay();
    }

    // =========================================================== 购物城：定时补货

    /** 购物城是否开启定时补货。 */    public static boolean mallRestockEnabled() {
        return INSTANCE.mallRestockEnabled.get();
    }

    /** 补货时刻是否按**游戏时间**算（false = 现实时间）。 */
    public static boolean mallRestockGameTime() {
        String mode = INSTANCE.mallRestockMode.get();
        return mode != null && "game".equalsIgnoreCase(mode.trim());
    }

    /** 补货时刻（HH:mm，配置格式不对时按 00:00 处理）。 */
    public static String mallRestockTime() {
        String text = INSTANCE.mallRestockTime.get();
        return parseClock(text) < 0 ? "00:00" : text.trim();
    }

    /** 补货时刻对应的游戏刻（游戏时间口径用；0 = 早上 06:00）。 */
    public static int mallRestockTick() {
        return Math.max(0, clockToDayTick(mallRestockTime()));
    }

    /** 补货数量：0 = 补回各自原量；>0 = 统一补到该数量。 */
    public static int mallRestockAmount() {
        return Math.max(0, INSTANCE.mallRestockAmount.get());
    }

    /** 购物城补货归属的「现实日期」（现实时间口径）。 */
    public static long mallRestockRealDay() {
        return realDayWithReset(mallRestockTime());
    }

    /**
     * 生效的余额上限：配置里 {@code <= 0} 表示**不限**（返回 {@link Long#MAX_VALUE}）。
     * <p>
     * 所有涉及上限的地方都要走这里，否则「0 = 不限」会在入账 / 限价 / 发放等路径上被当成「一分钱都装不下」。
     * <p>
     * ⚠ {@code 1} 也按「不限」处理：早期版本的配置范围下限误写成 {@code 1L}，
     * NeoForge 会把配置文件里的 {@code maxBalance = 0}（不限）**纠正成 1 并写回文件**，
     * 于是"不限"变成"上限 1 星币"，管理员连 5 星币的商品都存不下来（报"价格无效"）。
     * 下限已改为 {@code 0L}，这里再兜一层，保证旧配置文件同样能正常工作。
     */
    public static long maxBalanceLimit() {
        long value = INSTANCE.maxBalance.get();
        return value <= 1L ? Long.MAX_VALUE : value;
    }

    /** 下发给客户端的余额上限：不限时下发 0（客户端遇到 0 就不显示「已满」）。 */
    public static long maxBalanceForClient() {
        long value = maxBalanceLimit();
        return value == Long.MAX_VALUE ? 0L : value;
    }

    // =========================================================== 购物城：库存周期刷新

    /** 库存刷新方式（归一化：off / real / game）。 */
    public static String mallRefreshMode() {
        String mode = INSTANCE.mallRefreshMode.get();
        if (mode == null) {
            return "off";
        }
        String clean = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("real".equals(clean) || "game".equals(clean)) {
            return clean;
        }
        return "off";
    }

    public static int mallRefreshAmount() {
        return Math.max(1, INSTANCE.mallRefreshAmount.get());
    }

    public static boolean mallRefreshActive() {
        return !"off".equals(mallRefreshMode());
    }

    /** 购物城库存是不是「个人库存」（每个玩家各自一份；全服共用时返回 false）。 */
    public static boolean mallStockPersonal() {
        String mode = INSTANCE.mallStockMode.get();
        if (mode == null) {
            return false;
        }
        return "personal".equals(mode.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** 现实时间周期（毫秒）。 */
    public static long mallRefreshRealMillis() {
        return mallRefreshAmount() * 3_600_000L;
    }

    /** 游戏时间周期（刻；24000 刻 = 1 天）。 */
    public static long mallRefreshGameTicks() {
        return mallRefreshAmount() * 24_000L;
    }

    // =========================================================== 玩家求购（悬赏）

    public static boolean demandEnabled() {
        return INSTANCE.demandEnabled.get();
    }

    public static int demandSlots() {
        return Math.max(1, INSTANCE.demandSlots.get());
    }

    public static double demandFeePercent() {
        return Math.max(0.0D, Math.min(100.0D, INSTANCE.demandFeePercent.get()));
    }

    public static long demandMaxReward() {
        return Math.max(0L, INSTANCE.demandMaxReward.get());
    }

    public static int demandMaxListed() {
        return Math.max(1, INSTANCE.demandMaxListed.get());
    }

    /** 求购自动下架方式（归一化：{@code permanent} / {@code game} / {@code real}）。 */
    public static String demandExpireMode() {
        String mode = INSTANCE.demandExpireMode.get();
        if (mode == null) {
            return "permanent";
        }
        String clean = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("game".equals(clean) || "real".equals(clean)) {
            return clean;
        }
        return "permanent";
    }

    public static int demandExpireAmount() {
        return Math.max(0, INSTANCE.demandExpireAmount.get());
    }

    /** 是否真的启用自动下架（永久 或 0 时长 = 不启用）。 */
    public static boolean demandExpireActive() {
        return !"permanent".equals(demandExpireMode()) && demandExpireAmount() > 0;
    }

    public static long demandExpireGameTicks() {
        return demandExpireAmount() * 24_000L;
    }

    public static long demandExpireRealMillis() {
        return demandExpireAmount() * 3_600_000L;
    }

    // =========================================================== 玩家摊位：自动下架

    /** 摊位自动下架方式（归一化后只会是 {@code permanent} / {@code game} / {@code real}）。 */
    public static String stallExpireMode() {
        String mode = INSTANCE.stallExpireMode.get();
        if (mode == null) {
            return "permanent";
        }
        String clean = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("game".equals(clean) || "real".equals(clean)) {
            return clean;
        }
        return "permanent";
    }

    /** 限时时长原值（game 按天、real 按小时；0 也当永久）。 */
    public static int stallExpireAmount() {
        return Math.max(0, INSTANCE.stallExpireAmount.get());
    }

    /** 实际是否启用自动下架（永久 或 0 时长 = 不启用）。 */
    public static boolean stallExpireActive() {
        return !"permanent".equals(stallExpireMode()) && stallExpireAmount() > 0;
    }

    /** 限时长度 → **游戏刻**（game 模式用；24000 刻 = 1 天）。 */
    public static long stallExpireGameTicks() {
        return stallExpireAmount() * 24_000L;
    }

    /** 限时长度 → **现实毫秒**（real 模式用；3600_000 ms = 1 小时）。 */
    public static long stallExpireRealMillis() {
        return stallExpireAmount() * 3_600_000L;
    }
}
