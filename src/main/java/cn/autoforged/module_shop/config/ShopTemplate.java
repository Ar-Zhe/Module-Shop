package cn.autoforged.module_shop.config;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.data.ConfiguredOffer;
import cn.autoforged.module_shop.data.ExchangeEntry;
import cn.autoforged.module_shop.data.MallItem;
import cn.autoforged.module_shop.data.Rarity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 商店**模板**（可跨世界 / 随整合包共享的那一份数据）。
 *
 * <p>这里只放"配置层面"的字段：商品、价格、品质、数量、库存、刷新权重、签到参数、货币名。
 * 玩家余额、购买记录、拍卖 / 求购 / 邮件 / 兑换码等**运行数据一律不出现**在本类里，
 * 由 {@link cn.autoforged.module_shop.shop.TemplateService} 在两端严格过滤。
 *
 * <p>JSON 格式带独立的 {@link #FORMAT_VERSION}，与模组版本解耦：
 * 以后改字段只需要升格式号 + 在读取端加迁移分支。
 */
public final class ShopTemplate {
    /** 当前 JSON 格式版本。 */
    public static final int FORMAT_VERSION = 1;

    /** 当前模组版本字符串（读 mods.toml 里展开的版本；取不到就给空串，不抛异常）。 */
    public static String currentModVersion() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById(
                    cn.autoforged.module_shop.ModuleShopMod.MODID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Throwable ignored) {
            return "";
        }
    }
    /** 单个池子允许的最大条目数（防炸）。 */
    public static final int MAX_ENTRIES = 5000;
    /** 模板名称的最大长度。 */
    public static final int MAX_NAME = 64;

    public int formatVersion = FORMAT_VERSION;
    public String modVersion = currentModVersion();
    public String createdAt = "";
    public String name = "商店配置";
    public String note = "";

    // ---- 每日商店 ----
    public int shopSlots = 6;
    public int[] shopWeights = {5, 15, 30, 50};
    public int[] shopGuarantee = {0, 0, 0};
    public final List<Offer> shopOffers = new ArrayList<>();

    // ---- 每日收购 ----
    public int buybackSlots = 6;
    public int[] buybackWeights = {5, 15, 30, 50};
    public int[] buybackGuarantee = {0, 0, 0};
    public final List<Offer> buybackOffers = new ArrayList<>();

    // ---- 购物城 ----
    public final List<MallEntry> mallItems = new ArrayList<>();

    // ---- 货币兑换 ----
    public final List<Exchange> exchangeEntries = new ArrayList<>();

    // ---- 签到 ----
    public boolean signInEnabled = true;
    public long signInBaseDaily = 10L;
    public long signInMakeupCost = 100L;
    public int signInMakeupDays = 2;
    public int[] signInTierDays = {3, 5, 7};
    public long[] signInTierBonus = {5L, 7L, 10L};
    public int[] signInMileDays = {3, 5, 7};
    public long[] signInMileMoney = {100L, 300L, 800L};

    // ---- 其它模板字段 ----
    public String currency = "";
    public boolean buybackBonusEnabled = true;

    /** 商品条目（每日商店 / 收购共用）。 */
    public static final class Offer {
        public String item = "";
        public int count = 1;
        public long price;
        public String rarity = Rarity.COMMON.id();
        public int amount;

        public static Offer of(ConfiguredOffer source) {
            Offer out = new Offer();
            ItemStack stack = source.stack;
            out.item = stack == null || stack.isEmpty() ? ""
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            out.count = stack == null || stack.isEmpty() ? 1 : Math.max(1, stack.getCount());
            out.price = Math.max(0L, source.price);
            out.rarity = source.rarity == null ? Rarity.COMMON.id() : source.rarity.id();
            out.amount = source.amount;
            return out;
        }

        /** 转成世界数据；物品无法识别时返回 {@code null}（调用方计入"无效"）。 */
        public ConfiguredOffer toConfigured() {
            ItemStack stack = ShopTemplate.stackOf(item, count);
            if (stack.isEmpty()) {
                return null;
            }
            ConfiguredOffer offer = new ConfiguredOffer(stack, Math.max(0L, price), Rarity.byId(rarity));
            offer.amount = amount;
            return offer;
        }
    }

    /** 购物城条目。 */
    public static final class MallEntry {
        public String item = "";
        public int count = 1;
        public long price;
        public int stock = -1;
        public int stockMax = -1;
        public int sort;
        public String rarity = Rarity.COMMON.id();

        public static MallEntry of(MallItem source) {
            MallEntry out = new MallEntry();
            ItemStack stack = source.stack;
            out.item = stack == null || stack.isEmpty() ? ""
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            out.count = stack == null || stack.isEmpty() ? 1 : Math.max(1, stack.getCount());
            out.price = Math.max(0L, source.price);
            out.stock = source.stock;
            out.stockMax = source.stockMax;
            out.sort = source.sort;
            out.rarity = source.rarity == null ? Rarity.COMMON.id() : source.rarity.id();
            return out;
        }

        public MallItem toMallItem() {
            ItemStack stack = ShopTemplate.stackOf(item, count);
            if (stack.isEmpty()) {
                return null;
            }
            MallItem item = new MallItem();
            item.stack = stack;
            item.price = Math.max(0L, price);
            item.stock = stock;
            item.stockMax = stockMax;
            item.sort = sort;
            item.rarity = Rarity.byId(rarity);
            return item;
        }
    }

    /** 货币兑换规则。 */
    public static final class Exchange {
        public String item = "";
        public int count = 1;
        public long value;

        public static Exchange of(ExchangeEntry source) {
            Exchange out = new Exchange();
            ItemStack stack = source.stack;
            out.item = stack == null || stack.isEmpty() ? ""
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            out.count = stack == null || stack.isEmpty() ? 1 : Math.max(1, stack.getCount());
            out.value = Math.max(0L, source.value);
            return out;
        }

        public ExchangeEntry toEntry() {
            ItemStack stack = ShopTemplate.stackOf(item, count);
            if (stack.isEmpty()) {
                return null;
            }
            return new ExchangeEntry(stack, Math.max(0L, value));
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 由物品 id + 数量构造栈；id 非法或物品不存在时返回空栈。 */
    public static ItemStack stackOf(String id, int count) {
        if (id == null || id.isBlank()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation key = ResourceLocation.tryParse(id.trim());
        if (key == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
            return ItemStack.EMPTY;
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, Math.max(1, Math.min(64, count)));
    }

    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // ------------------------------------------------------------------ JSON

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.addProperty("mod_version", modVersion == null ? "" : modVersion);
        root.addProperty("created_at", createdAt == null ? "" : createdAt);
        root.addProperty("name", name == null ? "" : name);
        if (note != null && !note.isBlank()) {
            root.addProperty("note", note);
        }

        JsonObject shop = new JsonObject();
        shop.addProperty("slots", shopSlots);
        shop.add("weights", ints(shopWeights));
        shop.add("guarantee", ints(shopGuarantee));
        shop.add("offers", offers(shopOffers));
        root.add("shop", shop);

        JsonObject buyback = new JsonObject();
        buyback.addProperty("slots", buybackSlots);
        buyback.add("weights", ints(buybackWeights));
        buyback.add("guarantee", ints(buybackGuarantee));
        buyback.add("offers", offers(buybackOffers));
        root.add("buyback", buyback);

        JsonObject mall = new JsonObject();
        JsonArray mallItemsJson = new JsonArray();
        for (MallEntry entry : mallItems) {
            JsonObject one = new JsonObject();
            one.addProperty("item", entry.item);
            one.addProperty("count", entry.count);
            one.addProperty("price", entry.price);
            one.addProperty("stock", entry.stock);
            one.addProperty("stock_max", entry.stockMax);
            one.addProperty("sort", entry.sort);
            one.addProperty("rarity", entry.rarity);
            mallItemsJson.add(one);
        }
        mall.add("items", mallItemsJson);
        root.add("mall", mall);

        JsonObject exchange = new JsonObject();
        JsonArray exchangeJson = new JsonArray();
        for (Exchange entry : exchangeEntries) {
            JsonObject one = new JsonObject();
            one.addProperty("item", entry.item);
            one.addProperty("count", entry.count);
            one.addProperty("value", entry.value);
            exchangeJson.add(one);
        }
        exchange.add("entries", exchangeJson);
        root.add("exchange", exchange);

        JsonObject signin = new JsonObject();
        signin.addProperty("enabled", signInEnabled);
        signin.addProperty("base_daily", signInBaseDaily);
        signin.addProperty("makeup_cost", signInMakeupCost);
        signin.addProperty("makeup_days", signInMakeupDays);
        signin.add("tier_days", ints(signInTierDays));
        signin.add("tier_bonus", longs(signInTierBonus));
        signin.add("mile_days", ints(signInMileDays));
        signin.add("mile_money", longs(signInMileMoney));
        root.add("signin", signin);

        JsonObject other = new JsonObject();
        other.addProperty("currency", currency == null ? "" : currency);
        other.addProperty("buyback_bonus", buybackBonusEnabled);
        root.add("other", other);
        return root;
    }

    private static JsonArray offers(List<Offer> list) {
        JsonArray array = new JsonArray();
        for (Offer offer : list) {
            JsonObject one = new JsonObject();
            one.addProperty("item", offer.item);
            one.addProperty("count", offer.count);
            one.addProperty("price", offer.price);
            one.addProperty("rarity", offer.rarity);
            one.addProperty("amount", offer.amount);
            array.add(one);
        }
        return array;
    }

    private static JsonArray ints(int[] values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (int v : values) {
                array.add(v);
            }
        }
        return array;
    }

    private static JsonArray longs(long[] values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (long v : values) {
                array.add(v);
            }
        }
        return array;
    }

    /**
     * 解析 JSON。
     *
     * @return 解析结果；结构不合法时返回 {@code null}（调用方提示玩家，**不覆盖原文件**）
     */
    public static ShopTemplate fromJson(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return null;
            }
            return fromObject(parsed.getAsJsonObject());
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static ShopTemplate fromObject(JsonObject root) {
        ShopTemplate t = new ShopTemplate();
        t.formatVersion = optInt(root, "format_version", FORMAT_VERSION);
        t.modVersion = optString(root, "mod_version", "");
        t.createdAt = optString(root, "created_at", "");
        t.name = trim(optString(root, "name", "商店配置"), MAX_NAME);
        t.note = optString(root, "note", "");

        JsonObject shop = optObject(root, "shop");
        if (shop != null) {
            t.shopSlots = clamp(optInt(shop, "slots", 6), 1, 27);
            t.shopWeights = readInts(shop, "weights", t.shopWeights);
            t.shopGuarantee = readInts(shop, "guarantee", t.shopGuarantee);
            readOffers(shop, t.shopOffers);
        }
        JsonObject buyback = optObject(root, "buyback");
        if (buyback != null) {
            t.buybackSlots = clamp(optInt(buyback, "slots", 6), 1, 27);
            t.buybackWeights = readInts(buyback, "weights", t.buybackWeights);
            t.buybackGuarantee = readInts(buyback, "guarantee", t.buybackGuarantee);
            readOffers(buyback, t.buybackOffers);
        }
        JsonObject mall = optObject(root, "mall");
        if (mall != null && mall.has("items") && mall.get("items").isJsonArray()) {
            for (JsonElement element : mall.getAsJsonArray("items")) {
                if (t.mallItems.size() >= MAX_ENTRIES || !element.isJsonObject()) {
                    break;
                }
                JsonObject one = element.getAsJsonObject();
                MallEntry entry = new MallEntry();
                entry.item = optString(one, "item", "");
                entry.count = clamp(optInt(one, "count", 1), 1, 64);
                entry.price = Math.max(0L, optLong(one, "price", 0L));
                entry.stock = optInt(one, "stock", -1);
                entry.stockMax = optInt(one, "stock_max", -1);
                entry.sort = optInt(one, "sort", 0);
                entry.rarity = optString(one, "rarity", Rarity.COMMON.id());
                t.mallItems.add(entry);
            }
        }
        JsonObject exchange = optObject(root, "exchange");
        if (exchange != null && exchange.has("entries") && exchange.get("entries").isJsonArray()) {
            for (JsonElement element : exchange.getAsJsonArray("entries")) {
                if (t.exchangeEntries.size() >= MAX_ENTRIES || !element.isJsonObject()) {
                    break;
                }
                JsonObject one = element.getAsJsonObject();
                Exchange entry = new Exchange();
                entry.item = optString(one, "item", "");
                entry.count = clamp(optInt(one, "count", 1), 1, 64);
                entry.value = Math.max(0L, optLong(one, "value", 0L));
                t.exchangeEntries.add(entry);
            }
        }
        JsonObject signin = optObject(root, "signin");
        if (signin != null) {
            t.signInEnabled = optBool(signin, "enabled", true);
            t.signInBaseDaily = Math.max(0L, optLong(signin, "base_daily", 10L));
            t.signInMakeupCost = Math.max(0L, optLong(signin, "makeup_cost", 100L));
            t.signInMakeupDays = clamp(optInt(signin, "makeup_days", 2), 0, 30);
            t.signInTierDays = readInts(signin, "tier_days", t.signInTierDays);
            t.signInTierBonus = readLongs(signin, "tier_bonus", t.signInTierBonus);
            t.signInMileDays = readInts(signin, "mile_days", t.signInMileDays);
            t.signInMileMoney = readLongs(signin, "mile_money", t.signInMileMoney);
        }
        JsonObject other = optObject(root, "other");
        if (other != null) {
            t.currency = trim(optString(other, "currency", ""), 16);
            t.buybackBonusEnabled = optBool(other, "buyback_bonus", true);
        }
        return t;
    }

    private static void readOffers(JsonObject parent, List<Offer> into) {
        if (!parent.has("offers") || !parent.get("offers").isJsonArray()) {
            return;
        }
        for (JsonElement element : parent.getAsJsonArray("offers")) {
            if (into.size() >= MAX_ENTRIES || !element.isJsonObject()) {
                break;
            }
            JsonObject one = element.getAsJsonObject();
            Offer offer = new Offer();
            offer.item = optString(one, "item", "");
            offer.count = clamp(optInt(one, "count", 1), 1, 64);
            offer.price = Math.max(0L, optLong(one, "price", 0L));
            offer.rarity = optString(one, "rarity", Rarity.COMMON.id());
            offer.amount = optInt(one, "amount", 0);
            into.add(offer);
        }
    }

    // ---- 小工具（全部容错：字段缺失/类型错都给默认值）----

    private static JsonObject optObject(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String optString(JsonObject parent, String key, String fallback) {
        try {
            JsonElement element = parent.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static int optInt(JsonObject parent, String key, int fallback) {
        try {
            JsonElement element = parent.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static long optLong(JsonObject parent, String key, long fallback) {
        try {
            JsonElement element = parent.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsLong() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static boolean optBool(JsonObject parent, String key, boolean fallback) {
        try {
            JsonElement element = parent.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static int[] readInts(JsonObject parent, String key, int[] fallback) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        JsonArray array = element.getAsJsonArray();
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = array.get(i).getAsInt();
            } catch (RuntimeException error) {
                out[i] = 0;
            }
        }
        return out.length == 0 ? fallback : out;
    }

    private static long[] readLongs(JsonObject parent, String key, long[] fallback) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        JsonArray array = element.getAsJsonArray();
        long[] out = new long[array.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = array.get(i).getAsLong();
            } catch (RuntimeException error) {
                out[i] = 0L;
            }
        }
        return out.length == 0 ? fallback : out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
