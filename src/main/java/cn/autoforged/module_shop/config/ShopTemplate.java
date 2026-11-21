package cn.autoforged.module_shop.config;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.data.ConfiguredOffer;
import cn.autoforged.module_shop.data.ExchangeEntry;
import cn.autoforged.module_shop.data.MallItem;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.Rarity;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig;
import cn.autoforged.module_shop.shop.home.AnnouncementEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 商店**模板**（可跨世界 / 随整合包共享的配置镜像）。
 *
 * <p>格式版本 v3 升级要点：
 * <ul>
 *     <li>商品条目支持完整 1.21.1 {@code DataComponents}（{@code components_snbt}），兼容 TACZ、奇异饰品等复杂组件物品；</li>
 *     <li>支持完整配置域：每日商店/收购刷新费率、出货箱规则与等级上限、侧栏布局与品牌图标、公告与图文块、实体货币、可选管理员密码哈希；</li>
 *     <li>完全向下兼容读取 v1 / v2 {@code shop_config.json}，且保留 {@code PriceSpec} 双独立账本价格规格。</li>
 * </ul>
 */
public final class ShopTemplate {
    private static final Logger LOG = LoggerFactory.getLogger("module_shop/template");

    /** 当前 JSON 格式版本（v3 向下兼容读取 v1 / v2 模板）。 */
    public static final int FORMAT_VERSION = 3;

    /** 单个池子允许的最大条目数（适配大型整合包）。 */
    public static final int MAX_ENTRIES = 50000;
    /** 模板名称的最大长度。 */
    public static final int MAX_NAME = 64;
    /** 单物品组件 SNBT 防御性上限字符数（512K 字符）。 */
    public static final int MAX_COMPONENTS_SNBT_CHARS = 512 * 1024;
    /** 公告/布局最大条目防呆限制。 */
    public static final int MAX_LIST_CONFIG = 5000;

    /** 导出/导入支持的功能域勾选切片。 */
    public enum ExportSection {
        SHOP("shop"),
        BUYBACK("buyback"),
        MALL("mall"),
        EXCHANGE("exchange"),
        SIGNIN("signin"),
        CURRENCY("currency"),
        SHIPPING("shipping"),
        LAYOUT("layout"),
        ANNOUNCEMENTS("announcements"),
        SECURITY("security");

        public final String id;

        ExportSection(String id) {
            this.id = id;
        }

        public static @Nullable ExportSection byId(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String clean = raw.trim().toLowerCase(Locale.ROOT);
            for (ExportSection s : values()) {
                if (s.id.equals(clean)) {
                    return s;
                }
            }
            return null;
        }

        public static EnumSet<ExportSection> allDefault(boolean includeSecurity) {
            EnumSet<ExportSection> set = EnumSet.allOf(ExportSection.class);
            if (!includeSecurity) {
                set.remove(SECURITY);
            }
            return set;
        }

        public static EnumSet<ExportSection> coreCommerceOnly() {
            return EnumSet.of(SHOP, BUYBACK, MALL, EXCHANGE);
        }

        public static String serializeMask(@Nullable Collection<ExportSection> sections) {
            if (sections == null || sections.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (ExportSection s : sections) {
                if (s == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(s.id);
            }
            return sb.toString();
        }

        public static EnumSet<ExportSection> parseMask(@Nullable String csv) {
            EnumSet<ExportSection> set = EnumSet.noneOf(ExportSection.class);
            if (csv == null || csv.isBlank()) {
                return set;
            }
            for (String part : csv.split(",")) {
                ExportSection s = byId(part);
                if (s != null) {
                    set.add(s);
                }
            }
            return set;
        }
    }

    /** 单条校验/解析问题（不会拖垮整份模板）。 */
    public record ValidationIssue(String code, String location, String itemId, String detail) {
        public String toSummary() {
            String target = itemId == null || itemId.isBlank() ? location : location + " -> " + itemId;
            if (detail == null || detail.isBlank()) {
                return "[" + code + "] " + target;
            }
            return "[" + code + "] " + target + ": " + detail;
        }
    }

    /** 结构化 JSON 解析结果。 */
    public record ParseResult(@Nullable ShopTemplate template, String errorCode, String errorDetail,
                              List<ValidationIssue> issues) {
        public boolean ok() {
            return template != null && (errorCode == null || errorCode.isBlank());
        }

        public static ParseResult ok(ShopTemplate template, List<ValidationIssue> issues) {
            return new ParseResult(template, "", "", List.copyOf(issues));
        }

        public static ParseResult error(String errorCode, String errorDetail) {
            return new ParseResult(null, errorCode == null ? "bad_json" : errorCode,
                    errorDetail == null ? "" : errorDetail, List.of());
        }
    }

    /** 当前模组版本字符串（读 mods.toml 里展开的版本；取不到就给空串，不抛异常）。 */
    public static String currentModVersion() {
        try {
            return net.minecraftforge.fml.ModList.get().getModContainerById(ModuleShopMod.MODID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Throwable ignored) {
            return "";
        }
    }

    public int formatVersion = FORMAT_VERSION;
    public String modVersion = currentModVersion();
    public String createdAt = "";
    public String name = "商店配置";
    public String note = "";

    /** 记录本次文件显式包含的模块（空 = 视同未过滤的传统/全量模板）。 */
    public final EnumSet<ExportSection> presentSections = EnumSet.noneOf(ExportSection.class);

    // ---- 每日商店 ----
    public int shopSlots = 6;
    public int[] shopWeights = {5, 15, 30, 50};
    public int[] shopGuarantee = {0, 0, 0};
    public final List<PriceSpec> dailyRefreshSpecs = new ArrayList<>();
    public final List<Offer> shopOffers = new ArrayList<>();

    // ---- 每日收购 ----
    public int buybackSlots = 6;
    public int[] buybackWeights = {5, 15, 30, 50};
    public int[] buybackGuarantee = {0, 0, 0};
    public final List<PriceSpec> buybackRefreshSpecs = new ArrayList<>();
    public final List<Offer> buybackOffers = new ArrayList<>();

    // ---- 购物城 ----
    public final List<MallEntry> mallItems = new ArrayList<>();

    // ---- 货币兑换 ----
    public final List<Exchange> exchangeEntries = new ArrayList<>();

    // ---- 签到 ----
    public boolean signInEnabled = true;
    public boolean signInAuto = true;
    public boolean signInCumulative = true;
    public long signInBaseDaily = 10L;
    public PriceSpec signInBaseSpec;
    public long signInMakeupCost = 100L;
    public PriceSpec signInMakeupSpec;
    public int signInMakeupDays = 2;
    public int[] signInTierDays = {3, 5, 7};
    public long[] signInTierBonus = {5L, 7L, 10L};
    public final List<PriceSpec> signInTierBonusSpecs = new ArrayList<>();
    public int[] signInMileDays = {3, 5, 7};
    public long[] signInMileMoney = {100L, 300L, 800L};
    public final List<PriceSpec> signInMileSpecs = new ArrayList<>();

    // ---- 其它模板字段 & 实体货币 ----
    public String currency = "";
    public boolean buybackBonusEnabled = true;
    public PriceSpec buybackBonusSpec;
    public @Nullable PhysicalCurrencyConfig physicalCurrency;

    // ---- v3 新增完整配置域 ----
    public @Nullable ShippingData shipping;
    public @Nullable LayoutData layout;
    public final List<AnnouncementEntry> announcements = new ArrayList<>();
    public @Nullable AuthData adminAuth;

    /** `.mshop` 打包/解包时暂存的二进制媒体资产（mediaId -> bytes），不写入 `template.json`。 */
    public transient final Map<String, byte[]> embeddedMedia = new LinkedHashMap<>();

    /** 出货箱配置镜像（规则 + 等级格子 + 升级费 + 每日上限 + 重置时间）。 */
    public static final class ShippingData {
        public final List<String> rules = new ArrayList<>();
        public final List<String> levelSlots = new ArrayList<>();
        public final List<String> upgradeCosts = new ArrayList<>();
        public final List<PriceSpec> upgradeSpecs = new ArrayList<>();
        public final List<String> dailyLimits = new ArrayList<>();
        public String resetTime = "00:00";

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.add("rules", stringArray(rules));
            obj.add("level_slots", stringArray(levelSlots));
            obj.add("upgrade_costs", stringArray(upgradeCosts));
            if (!upgradeSpecs.isEmpty()) {
                obj.add("upgrade_specs", priceSpecArray(upgradeSpecs));
            }
            obj.add("daily_limits", stringArray(dailyLimits));
            obj.addProperty("reset_time", resetTime == null ? "00:00" : resetTime);
            return obj;
        }

        public static ShippingData fromJson(JsonObject obj) {
            ShippingData out = new ShippingData();
            readStrings(obj, "rules", out.rules, MAX_LIST_CONFIG);
            readStrings(obj, "level_slots", out.levelSlots, 32);
            readStrings(obj, "upgrade_costs", out.upgradeCosts, 32);
            readPriceSpecs(obj, "upgrade_specs", out.upgradeSpecs, 32);
            readStrings(obj, "daily_limits", out.dailyLimits, 32);
            out.resetTime = trim(optString(obj, "reset_time", "00:00"), 16);
            return out;
        }
    }

    /** 侧栏布局、商店标题与品牌图标镜像。 */
    public static final class LayoutData {
        public final List<String> order = new ArrayList<>();
        public final List<String> hidden = new ArrayList<>();
        public final Map<String, String> names = new LinkedHashMap<>();
        public String shopTitle = "";
        public String brandIconId = "";

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.add("order", stringArray(order));
            obj.add("hidden", stringArray(hidden));
            JsonObject namesObj = new JsonObject();
            for (Map.Entry<String, String> e : names.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    namesObj.addProperty(e.getKey(), e.getValue());
                }
            }
            obj.add("names", namesObj);
            obj.addProperty("shop_title", shopTitle == null ? "" : shopTitle);
            if (brandIconId != null && !brandIconId.isBlank()) {
                obj.addProperty("brand_icon_id", brandIconId);
            }
            return obj;
        }

        public static LayoutData fromJson(JsonObject obj) {
            LayoutData out = new LayoutData();
            readStrings(obj, "order", out.order, 64);
            readStrings(obj, "hidden", out.hidden, 64);
            JsonObject namesObj = optObject(obj, "names");
            if (namesObj != null) {
                for (Map.Entry<String, JsonElement> entry : namesObj.entrySet()) {
                    if (out.names.size() >= 64) {
                        break;
                    }
                    if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                        out.names.put(entry.getKey(), trim(entry.getValue().getAsString(), 32));
                    }
                }
            }
            out.shopTitle = trim(optString(obj, "shop_title", ""), 32);
            out.brandIconId = trim(optString(obj, "brand_icon_id", ""), 128);
            return out;
        }
    }

    /** 管理员密码锁配置镜像（仅保存 PBKDF2 摘要，绝无明文）。 */
    public static final class AuthData {
        public boolean enabled;
        public String algo = "PBKDF2WithHmacSHA256";
        public int iterations = 120000;
        public String salt = "";
        public String hash = "";
        public int sessionTimeoutMinutes = 30;

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("algo", algo == null ? "" : algo);
            obj.addProperty("iterations", iterations);
            obj.addProperty("salt", salt == null ? "" : salt);
            obj.addProperty("hash", hash == null ? "" : hash);
            obj.addProperty("session_timeout_minutes", sessionTimeoutMinutes);
            return obj;
        }

        public static AuthData fromJson(JsonObject obj) {
            AuthData out = new AuthData();
            out.enabled = optBool(obj, "enabled", false);
            out.algo = trim(optString(obj, "algo", "PBKDF2WithHmacSHA256"), 64);
            out.iterations = clamp(optInt(obj, "iterations", 120000), 1000, 2_000_000);
            out.salt = trim(optString(obj, "salt", ""), 256);
            out.hash = trim(optString(obj, "hash", ""), 512);
            out.sessionTimeoutMinutes = clamp(optInt(obj, "session_timeout_minutes", 30), 1, 1440);
            return out;
        }
    }

    /** 商品条目（每日商店 / 收购共用）。 */
    public static final class Offer {
        public String item = "";
        public int count = 1;
        public String componentsSnbt = "";
        public long price;
        public PriceSpec priceSpec;
        public String rarity = Rarity.COMMON.id();
        public int amount;

        public PriceSpec effectivePriceSpec() {
            return priceSpec != null ? priceSpec : PriceSpec.star(Math.max(0L, price));
        }

        public String itemKey() {
            return ShopTemplate.itemKey(item, componentsSnbt);
        }

        public static Offer of(ConfiguredOffer source) {
            return of(source, null);
        }

        public static Offer of(ConfiguredOffer source, @Nullable HolderLookup.Provider registries) {
            Offer out = new Offer();
            ItemStack stack = source.stack;
            out.item = itemId(stack);
            out.count = stack == null || stack.isEmpty() ? 1 : Math.max(1, stack.getCount());
            out.componentsSnbt = extractComponentsSnbt(stack, registries);
            PriceSpec spec = source.resolvePriceSpec();
            out.priceSpec = spec;
            out.price = spec.supportsStar() ? spec.starPrice : 0L;
            out.rarity = source.rarity == null ? Rarity.COMMON.id() : source.rarity.id();
            out.amount = source.amount;
            return out;
        }

        /** 转成世界数据；物品无法识别时返回 {@code null}（调用方计入"无效"）。 */
        public ConfiguredOffer toConfigured() {
            return toConfigured(null, null, "offer");
        }

        public ConfiguredOffer toConfigured(@Nullable HolderLookup.Provider registries,
                                            @Nullable List<ValidationIssue> issues, String location) {
            ItemStack stack = ShopTemplate.stackOf(item, count, componentsSnbt, registries, issues, location);
            if (stack.isEmpty()) {
                return null;
            }
            ConfiguredOffer offer = new ConfiguredOffer(stack, effectivePriceSpec(), Rarity.byId(rarity));
            offer.amount = amount;
            return offer;
        }
    }

    /** 购物城条目。 */
    public static final class MallEntry {
        public String item = "";
        public int count = 1;
        public String componentsSnbt = "";
        public long price;
        public PriceSpec priceSpec;
        public int stock = -1;
        public int stockMax = -1;
        public int sort;
        public boolean permanentLimit = false;
        public String rarity = Rarity.COMMON.id();

        public PriceSpec effectivePriceSpec() {
            return priceSpec != null ? priceSpec : PriceSpec.star(Math.max(0L, price));
        }

        public String itemKey() {
            return ShopTemplate.itemKey(item, componentsSnbt);
        }

        public static MallEntry of(MallItem source) {
            return of(source, null);
        }

        public static MallEntry of(MallItem source, @Nullable HolderLookup.Provider registries) {
            MallEntry out = new MallEntry();
            ItemStack stack = source.stack;
            out.item = itemId(stack);
            out.count = stack == null || stack.isEmpty() ? 1 : Math.max(1, stack.getCount());
            out.componentsSnbt = extractComponentsSnbt(stack, registries);
            PriceSpec spec = source.resolvePriceSpec();
            out.priceSpec = spec;
            out.price = spec.supportsStar() ? spec.starPrice : 0L;
            out.stock = source.stock;
            out.stockMax = source.stockMax;
            out.sort = source.sort;
            out.permanentLimit = source.permanentLimit;
            out.rarity = source.rarity == null ? Rarity.COMMON.id() : source.rarity.id();
            return out;
        }

        public MallItem toMallItem() {
            return toMallItem(null, null, "mall");
        }

        public MallItem toMallItem(@Nullable HolderLookup.Provider registries,
                                   @Nullable List<ValidationIssue> issues, String location) {
            ItemStack stack = ShopTemplate.stackOf(item, count, componentsSnbt, registries, issues, location);
            if (stack.isEmpty()) {
                return null;
            }
            PriceSpec spec = effectivePriceSpec();
            MallItem m = new MallItem(stack, spec, Rarity.byId(rarity), stock);
            m.stock = stock;
            m.stockMax = stockMax >= 0 ? stockMax : stock;
            m.sort = sort;
            m.permanentLimit = permanentLimit;
            return m;
        }
    }

    /** 货币兑换规则。 */
    public static final class Exchange {
        public String item = "";
        public int count = 1;
        public String componentsSnbt = "";
        public long value;
        public int dailyDepositLimit;
        public int dailyWithdrawLimit;

        public String itemKey() {
            return ShopTemplate.itemKey(item, componentsSnbt);
        }

        public static Exchange of(ExchangeEntry source) {
            return of(source, null);
        }

        public static Exchange of(ExchangeEntry source, @Nullable HolderLookup.Provider registries) {
            Exchange out = new Exchange();
            ItemStack stack = source.stack;
            out.item = itemId(stack);
            out.count = stack == null || stack.isEmpty() ? 1 : Math.max(1, stack.getCount());
            out.componentsSnbt = extractComponentsSnbt(stack, registries);
            out.value = Math.max(0L, source.value);
            out.dailyDepositLimit = Math.max(0, source.dailyDepositLimit);
            out.dailyWithdrawLimit = Math.max(0, source.dailyWithdrawLimit);
            return out;
        }

        public ExchangeEntry toEntry() {
            return toEntry(null, null, "exchange");
        }

        public ExchangeEntry toEntry(@Nullable HolderLookup.Provider registries,
                                     @Nullable List<ValidationIssue> issues, String location) {
            ItemStack stack = ShopTemplate.stackOf(item, count, componentsSnbt, registries, issues, location);
            if (stack.isEmpty()) {
                return null;
            }
            return new ExchangeEntry(stack, Math.max(0L, value),
                    Math.max(0, dailyDepositLimit), Math.max(0, dailyWithdrawLimit));
        }
    }

    // ------------------------------------------------------------------ 物品与组件工具

    public static String itemKey(@Nullable String itemId, @Nullable String componentsSnbt) {
        String id = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
        String snbt = componentsSnbt == null ? "" : componentsSnbt.trim();
        return snbt.isEmpty() ? id : id + "|" + snbt;
    }

    /** 提取物品上的完整 CompoundTag NBT SNBT（1.20.1 兼容 TACZ、饰品、附魔等全部附加数据）；绝不抛出异常。 */
    public static String extractComponentsSnbt(@Nullable ItemStack stack, @Nullable HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return "";
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        try {
            return tag.getAsString();
        } catch (Throwable ex) {
            LOG.warn("[ModuleShop] 序列化物品 NBT {} 失败: {}", itemId(stack), ex.toString());
            return "";
        }
    }

    /** 由物品 id + 数量构造栈（不含自定义组件）；id 非法或物品不存在时返回空栈。 */
    public static ItemStack stackOf(String id, int count) {
        return stackOf(id, count, "", null, null, "");
    }

    /** 由物品 id + 数量 + 组件 SNBT 构造完整栈（保留 TACZ / 饰品 / 附魔等复杂 DataComponents）。 */
    public static ItemStack stackOf(String id, int count, @Nullable String componentsSnbt,
                                    @Nullable HolderLookup.Provider registries) {
        return stackOf(id, count, componentsSnbt, registries, null, "");
    }

    public static ItemStack stackOf(String id, int count, @Nullable String componentsSnbt,
                                    @Nullable HolderLookup.Provider registries,
                                    @Nullable List<ValidationIssue> issues, String location) {
        if (id == null || id.isBlank()) {
            if (issues != null) {
                issues.add(new ValidationIssue("missing_item", location, "", "empty item id"));
            }
            return ItemStack.EMPTY;
        }
        ResourceLocation key = ResourceLocation.tryParse(id.trim());
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            if (issues != null) {
                issues.add(new ValidationIssue("missing_item", location, id.trim(), "unknown item id"));
            }
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(key);
        if (item == Items.AIR) {
            if (issues != null) {
                issues.add(new ValidationIssue("missing_item", location, id.trim(), "air item"));
            }
            return ItemStack.EMPTY;
        }
        int safeCount = Math.max(1, Math.min(99, count));
        String snbt = componentsSnbt == null ? "" : componentsSnbt.trim();
        if (snbt.isEmpty()) {
            return new ItemStack(item, safeCount);
        }
        if (snbt.length() > MAX_COMPONENTS_SNBT_CHARS) {
            if (issues != null) {
                issues.add(new ValidationIssue("bad_components", location, key.toString(),
                        "components_snbt too long: " + snbt.length()));
            }
            return ItemStack.EMPTY;
        }
        CompoundTag comp;
        try {
            comp = TagParser.parseTag(snbt);
        } catch (CommandSyntaxException ex) {
            if (issues != null) {
                issues.add(new ValidationIssue("bad_snbt", location, key.toString(), ex.getMessage()));
            }
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, safeCount);
        if (!comp.isEmpty()) {
            stack.setTag(comp);
        }
        return stack;
    }

    public static String itemId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 对模板中的物品与组件做一次全面体检，不截断、不丢弃有效条目。 */
    public List<ValidationIssue> validateItems(@Nullable HolderLookup.Provider registries,
                                               @Nullable Set<ExportSection> sections) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (includes(sections, ExportSection.SHOP)) {
            for (int i = 0; i < shopOffers.size(); i++) {
                shopOffers.get(i).toConfigured(registries, issues, "shop[" + i + "]");
            }
        }
        if (includes(sections, ExportSection.BUYBACK)) {
            for (int i = 0; i < buybackOffers.size(); i++) {
                buybackOffers.get(i).toConfigured(registries, issues, "buyback[" + i + "]");
            }
        }
        if (includes(sections, ExportSection.MALL)) {
            for (int i = 0; i < mallItems.size(); i++) {
                mallItems.get(i).toMallItem(registries, issues, "mall[" + i + "]");
            }
        }
        if (includes(sections, ExportSection.EXCHANGE)) {
            for (int i = 0; i < exchangeEntries.size(); i++) {
                exchangeEntries.get(i).toEntry(registries, issues, "exchange[" + i + "]");
            }
        }
        if (includes(sections, ExportSection.CURRENCY) && physicalCurrency != null && physicalCurrency.tiers != null) {
            for (int i = 0; i < physicalCurrency.tiers.size(); i++) {
                PhysicalCurrencyConfig.CurrencyTier tier = physicalCurrency.tiers.get(i);
                if (tier != null) {
                    stackOf(tier.itemId, 1, tier.componentsSnbt, registries, issues, "currency_tier[" + i + "]");
                }
            }
        }
        return issues;
    }

    public boolean includes(@Nullable Set<ExportSection> requested, ExportSection section) {
        if (requested != null && !requested.isEmpty()) {
            return requested.contains(section);
        }
        if (!presentSections.isEmpty()) {
            return presentSections.contains(section);
        }
        return true;
    }

    // ------------------------------------------------------------------ JSON 序列化

    public JsonObject toJson() {
        return toJson(null);
    }

    public JsonObject toJson(@Nullable Set<ExportSection> selectedSections) {
        boolean filtered = selectedSections != null && !selectedSections.isEmpty();
        JsonObject root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.addProperty("mod_version", modVersion == null ? "" : modVersion);
        root.addProperty("created_at", createdAt == null ? "" : createdAt);
        root.addProperty("name", name == null ? "" : name);
        if (note != null && !note.isBlank()) {
            root.addProperty("note", note);
        }
        if (filtered) {
            JsonArray included = new JsonArray();
            for (ExportSection s : selectedSections) {
                included.add(s.id);
            }
            root.add("included_sections", included);
        }

        if (!filtered || selectedSections.contains(ExportSection.SHOP)) {
            JsonObject shop = new JsonObject();
            shop.addProperty("slots", shopSlots);
            shop.add("weights", ints(shopWeights));
            shop.add("guarantee", ints(shopGuarantee));
            if (!dailyRefreshSpecs.isEmpty()) {
                shop.add("refresh_specs", priceSpecArray(dailyRefreshSpecs));
            }
            shop.add("offers", offers(shopOffers));
            root.add("shop", shop);
        }

        if (!filtered || selectedSections.contains(ExportSection.BUYBACK)) {
            JsonObject buyback = new JsonObject();
            buyback.addProperty("slots", buybackSlots);
            buyback.add("weights", ints(buybackWeights));
            buyback.add("guarantee", ints(buybackGuarantee));
            if (!buybackRefreshSpecs.isEmpty()) {
                buyback.add("refresh_specs", priceSpecArray(buybackRefreshSpecs));
            }
            buyback.addProperty("bonus_enabled", buybackBonusEnabled);
            if (buybackBonusSpec != null) {
                buyback.add("bonus_spec", buybackBonusSpec.toJson());
            }
            buyback.add("offers", offers(buybackOffers));
            root.add("buyback", buyback);
        }

        if (!filtered || selectedSections.contains(ExportSection.MALL)) {
            JsonObject mall = new JsonObject();
            JsonArray mallItemsJson = new JsonArray();
            for (MallEntry entry : mallItems) {
                JsonObject one = new JsonObject();
                one.addProperty("item", entry.item == null ? "" : entry.item);
                one.addProperty("count", entry.count);
                if (entry.componentsSnbt != null && !entry.componentsSnbt.isEmpty()) {
                    one.addProperty("tag_snbt", entry.componentsSnbt);
                }
                PriceSpec spec = entry.effectivePriceSpec();
                one.addProperty("price", spec.supportsStar() ? spec.starPrice : 0L);
                if (spec.mode != PriceSpec.CurrencyMode.STAR_ONLY) {
                    one.add("price_spec", spec.toJson());
                }
                one.addProperty("stock", entry.stock);
                one.addProperty("stock_max", entry.stockMax);
                one.addProperty("sort", entry.sort);
                if (entry.permanentLimit) {
                    one.addProperty("permanent_limit", true);
                }
                one.addProperty("rarity", entry.rarity == null ? Rarity.COMMON.id() : entry.rarity);
                mallItemsJson.add(one);
            }
            mall.add("items", mallItemsJson);
            root.add("mall", mall);
        }

        if (!filtered || selectedSections.contains(ExportSection.EXCHANGE)) {
            JsonObject exchange = new JsonObject();
            JsonArray exchangeJson = new JsonArray();
            for (Exchange entry : exchangeEntries) {
                JsonObject one = new JsonObject();
                one.addProperty("item", entry.item == null ? "" : entry.item);
                one.addProperty("count", entry.count);
                if (entry.componentsSnbt != null && !entry.componentsSnbt.isEmpty()) {
                    one.addProperty("tag_snbt", entry.componentsSnbt);
                }
                one.addProperty("value", entry.value);
                if (entry.dailyDepositLimit > 0) {
                    one.addProperty("daily_deposit_limit", entry.dailyDepositLimit);
                }
                if (entry.dailyWithdrawLimit > 0) {
                    one.addProperty("daily_withdraw_limit", entry.dailyWithdrawLimit);
                }
                exchangeJson.add(one);
            }
            exchange.add("entries", exchangeJson);
            root.add("exchange", exchange);
        }

        if (!filtered || selectedSections.contains(ExportSection.SIGNIN)) {
            JsonObject signin = new JsonObject();
            signin.addProperty("enabled", signInEnabled);
            signin.addProperty("auto", signInAuto);
            signin.addProperty("cumulative", signInCumulative);
            PriceSpec baseSpec = signInBaseSpec != null ? signInBaseSpec : PriceSpec.star(signInBaseDaily);
            PriceSpec makeupSpec = signInMakeupSpec != null ? signInMakeupSpec : PriceSpec.star(signInMakeupCost);
            signin.addProperty("base_daily", baseSpec.supportsStar() ? baseSpec.starPrice : 0L);
            if (baseSpec.mode != PriceSpec.CurrencyMode.STAR_ONLY) {
                signin.add("base_daily_spec", baseSpec.toJson());
            }
            signin.addProperty("makeup_cost", makeupSpec.supportsStar() ? makeupSpec.starPrice : 0L);
            if (makeupSpec.mode != PriceSpec.CurrencyMode.STAR_ONLY) {
                signin.add("makeup_cost_spec", makeupSpec.toJson());
            }
            signin.addProperty("makeup_days", signInMakeupDays);
            signin.add("tier_days", ints(signInTierDays));
            signin.add("tier_bonus", longs(signInTierBonus));
            if (!signInTierBonusSpecs.isEmpty()) {
                signin.add("tier_bonus_specs", priceSpecArray(signInTierBonusSpecs));
            }
            signin.add("mile_days", ints(signInMileDays));
            signin.add("mile_money", longs(signInMileMoney));
            if (!signInMileSpecs.isEmpty()) {
                signin.add("mile_specs", priceSpecArray(signInMileSpecs));
            }
            root.add("signin", signin);
        }

        boolean writeOther = !filtered
                || selectedSections.contains(ExportSection.CURRENCY)
                || selectedSections.contains(ExportSection.BUYBACK);
        if (writeOther) {
            JsonObject other = new JsonObject();
            other.addProperty("currency", currency == null ? "" : currency);
            other.addProperty("buyback_bonus", buybackBonusEnabled);
            if (buybackBonusSpec != null) {
                other.add("buyback_bonus_spec", buybackBonusSpec.toJson());
            }
            root.add("other", other);
        }

        if ((!filtered || selectedSections.contains(ExportSection.CURRENCY)) && physicalCurrency != null) {
            root.add("physical_currency", physicalCurrencyToJson(physicalCurrency));
        }

        if ((!filtered || selectedSections.contains(ExportSection.SHIPPING)) && shipping != null) {
            root.add("shipping", shipping.toJson());
        }

        if ((!filtered || selectedSections.contains(ExportSection.LAYOUT)) && layout != null) {
            root.add("layout", layout.toJson());
        }

        if (!filtered || selectedSections.contains(ExportSection.ANNOUNCEMENTS)) {
            if (!announcements.isEmpty() || (filtered && selectedSections.contains(ExportSection.ANNOUNCEMENTS))) {
                JsonArray annArray = new JsonArray();
                for (AnnouncementEntry entry : announcements) {
                    if (entry != null) {
                        annArray.add(entry.toJson());
                    }
                }
                root.add("announcements", annArray);
            }
        }

        if ((filtered ? selectedSections.contains(ExportSection.SECURITY) : presentSections.contains(ExportSection.SECURITY))
                && adminAuth != null) {
            root.add("admin_auth", adminAuth.toJson());
        }

        return root;
    }

    private static JsonObject physicalCurrencyToJson(PhysicalCurrencyConfig cfg) {
        JsonObject obj = new JsonObject();
        obj.addProperty("format_version", cfg.formatVersion);
        obj.addProperty("enabled", cfg.enabled);
        obj.addProperty("block_buyback_and_shipping", cfg.blockBuybackAndShipping);
        obj.addProperty("max_convert_output_items", cfg.effectiveMaxConvertOutputItems());
        obj.addProperty("daily_convert_value_limit", cfg.effectiveDailyConvertValueLimit());
        JsonArray tiers = new JsonArray();
        if (cfg.tiers != null) {
            for (PhysicalCurrencyConfig.CurrencyTier tier : cfg.tiers) {
                if (tier == null) {
                    continue;
                }
                JsonObject t = new JsonObject();
                t.addProperty("id", tier.id == null ? "" : tier.id);
                t.addProperty("item_id", tier.itemId == null ? "" : tier.itemId);
                if (tier.componentsSnbt != null && !tier.componentsSnbt.isEmpty()) {
                    t.addProperty("tag_snbt", tier.componentsSnbt);
                }
                t.addProperty("step_ratio", tier.stepRatio);
                t.addProperty("value", tier.value);
                t.addProperty("label", tier.label == null ? "" : tier.label);
                tiers.add(t);
            }
        }
        obj.add("tiers", tiers);
        return obj;
    }

    private static PhysicalCurrencyConfig physicalCurrencyFromJson(JsonObject obj) {
        PhysicalCurrencyConfig cfg = new PhysicalCurrencyConfig();
        cfg.formatVersion = optInt(obj, "format_version", optInt(obj, "formatVersion", 2));
        cfg.enabled = optBool(obj, "enabled", false);
        cfg.blockBuybackAndShipping = optBool(obj, "block_buyback_and_shipping",
                optBool(obj, "blockBuybackAndShipping", true));
        cfg.maxConvertOutputItems = optInt(obj, "max_convert_output_items",
                optInt(obj, "maxConvertOutputItems",
                        cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.DEFAULT_MAX_CHANGE_ITEMS));
        cfg.dailyConvertValueLimit = optLong(obj, "daily_convert_value_limit",
                optLong(obj, "dailyConvertValueLimit", 0L));
        cfg.tiers = new ArrayList<>();
        if (obj.has("tiers") && obj.get("tiers").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("tiers")) {
                if (!el.isJsonObject() || cfg.tiers.size() >= PhysicalCurrencyConfig.MAX_TIERS) {
                    continue;
                }
                JsonObject one = el.getAsJsonObject();
                String id = optString(one, "id", "");
                String itemId = optString(one, "item_id", optString(one, "itemId", ""));
                String snbt = optString(one, "tag_snbt",
                        optString(one, "components_snbt", optString(one, "componentsSnbt", "")));
                int ratio = optInt(one, "step_ratio", optInt(one, "stepRatio", 1));
                long val = optLong(one, "value", 1L);
                String label = optString(one, "label", "");
                cfg.tiers.add(new PhysicalCurrencyConfig.CurrencyTier(id, itemId, snbt, ratio, val, label));
            }
        }
        cfg.normalize();
        return cfg;
    }

    private static JsonArray offers(List<Offer> list) {
        JsonArray array = new JsonArray();
        for (Offer offer : list) {
            JsonObject one = new JsonObject();
            one.addProperty("item", offer.item == null ? "" : offer.item);
            one.addProperty("count", offer.count);
            if (offer.componentsSnbt != null && !offer.componentsSnbt.isEmpty()) {
                one.addProperty("tag_snbt", offer.componentsSnbt);
            }
            PriceSpec spec = offer.effectivePriceSpec();
            one.addProperty("price", spec.supportsStar() ? spec.starPrice : 0L);
            if (spec.mode != PriceSpec.CurrencyMode.STAR_ONLY) {
                one.add("price_spec", spec.toJson());
            }
            one.addProperty("rarity", offer.rarity == null ? Rarity.COMMON.id() : offer.rarity);
            one.addProperty("amount", offer.amount);
            array.add(one);
        }
        return array;
    }

    private static JsonArray priceSpecArray(List<PriceSpec> list) {
        JsonArray array = new JsonArray();
        for (PriceSpec spec : list) {
            array.add((spec == null ? PriceSpec.ZERO : spec).toJson());
        }
        return array;
    }

    private static JsonArray stringArray(List<String> list) {
        JsonArray array = new JsonArray();
        for (String s : list) {
            if (s != null) {
                array.add(s);
            }
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

    // ------------------------------------------------------------------ JSON 解析

    /** 解析 JSON（向下兼容旧调用点；失败返回 {@code null}，需要详情时请用 {@link #parseDetailed}）。 */
    public static @Nullable ShopTemplate fromJson(@Nullable String json) {
        return parseDetailed(json).template();
    }

    /** 结构化解析 JSON，自动剥离 UTF-8 BOM 并区分空文件、JSON 语法错、版本过高与单条坏项。 */
    public static ParseResult parseDetailed(@Nullable String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return ParseResult.error("empty", "JSON content is empty");
        }
        String clean = stripBom(rawJson);
        if (clean.isBlank()) {
            return ParseResult.error("empty", "JSON content is blank after BOM strip");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(clean);
        } catch (JsonSyntaxException ex) {
            return ParseResult.error("bad_json", ex.getMessage());
        } catch (RuntimeException ex) {
            return ParseResult.error("bad_json", ex.toString());
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return ParseResult.error("bad_json", "Root JSON element is not an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        int ver = optInt(root, "format_version", 1);
        if (ver > FORMAT_VERSION) {
            return ParseResult.error("newer_format",
                    "Template format_version=" + ver + " exceeds supported v" + FORMAT_VERSION);
        }
        List<ValidationIssue> issues = new ArrayList<>();
        try {
            ShopTemplate t = fromObject(root, issues);
            return ParseResult.ok(t, issues);
        } catch (RuntimeException ex) {
            LOG.warn("[ModuleShop] 模板反序列化异常: {}", ex.toString(), ex);
            return ParseResult.error("bad_json", ex.toString());
        }
    }

    public static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text == null ? "" : text;
    }

    private static ShopTemplate fromObject(JsonObject root, List<ValidationIssue> issues) {
        ShopTemplate t = new ShopTemplate();
        t.formatVersion = optInt(root, "format_version", FORMAT_VERSION);
        t.modVersion = optString(root, "mod_version", "");
        t.createdAt = optString(root, "created_at", "");
        t.name = trim(optString(root, "name", "商店配置"), MAX_NAME);
        t.note = optString(root, "note", "");

        if (root.has("included_sections") && root.get("included_sections").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("included_sections")) {
                if (el != null && el.isJsonPrimitive()) {
                    ExportSection s = ExportSection.byId(el.getAsString());
                    if (s != null) {
                        t.presentSections.add(s);
                    }
                }
            }
        }
        boolean explicitMask = !t.presentSections.isEmpty();

        JsonObject shop = optObject(root, "shop");
        if (shop != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.SHOP);
            }
            t.shopSlots = clamp(optInt(shop, "slots", 6), 0, 64);
            t.shopWeights = readInts(shop, "weights", t.shopWeights);
            t.shopGuarantee = readInts(shop, "guarantee", t.shopGuarantee);
            readPriceSpecs(shop, "refresh_specs", t.dailyRefreshSpecs, 32);
            readOffers(shop, t.shopOffers, issues, "shop");
        }

        JsonObject buyback = optObject(root, "buyback");
        if (buyback != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.BUYBACK);
            }
            t.buybackSlots = clamp(optInt(buyback, "slots", 6), 0, 64);
            t.buybackWeights = readInts(buyback, "weights", t.buybackWeights);
            t.buybackGuarantee = readInts(buyback, "guarantee", t.buybackGuarantee);
            readPriceSpecs(buyback, "refresh_specs", t.buybackRefreshSpecs, 32);
            if (buyback.has("bonus_enabled")) {
                t.buybackBonusEnabled = optBool(buyback, "bonus_enabled", true);
            }
            if (buyback.has("bonus_spec")) {
                t.buybackBonusSpec = PriceSpec.fromJson(buyback.get("bonus_spec"), 0L);
            }
            readOffers(buyback, t.buybackOffers, issues, "buyback");
        }

        JsonObject mall = optObject(root, "mall");
        if (mall != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.MALL);
            }
            if (mall.has("items") && mall.get("items").isJsonArray()) {
                JsonArray arr = mall.getAsJsonArray("items");
                for (int i = 0; i < arr.size(); i++) {
                    if (t.mallItems.size() >= MAX_ENTRIES) {
                        issues.add(new ValidationIssue("truncated", "mall", "", "exceeded MAX_ENTRIES=" + MAX_ENTRIES));
                        break;
                    }
                    JsonElement element = arr.get(i);
                    if (!element.isJsonObject()) {
                        issues.add(new ValidationIssue("bad_entry", "mall[" + i + "]", "", "not a json object"));
                        continue;
                    }
                    JsonObject one = element.getAsJsonObject();
                    MallEntry entry = new MallEntry();
                    entry.item = optString(one, "item", "");
                    entry.count = clamp(optInt(one, "count", 1), 1, 99);
                    entry.componentsSnbt = readComponentsSnbt(one);
                    entry.price = Math.max(0L, optLong(one, "price", 0L));
                    entry.priceSpec = one.has("price_spec")
                            ? PriceSpec.fromJson(one.get("price_spec"), entry.price)
                            : PriceSpec.star(entry.price);
                    entry.price = entry.priceSpec.supportsStar() ? entry.priceSpec.starPrice : 0L;
                    entry.stock = optInt(one, "stock", -1);
                    entry.stockMax = optInt(one, "stock_max", -1);
                    entry.sort = optInt(one, "sort", 0);
                    entry.permanentLimit = optBool(one, "permanent_limit", optBool(one, "permanentLimit", false));
                    entry.rarity = optString(one, "rarity", Rarity.COMMON.id());
                    t.mallItems.add(entry);
                }
            }
        }

        JsonObject exchange = optObject(root, "exchange");
        if (exchange != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.EXCHANGE);
            }
            if (exchange.has("entries") && exchange.get("entries").isJsonArray()) {
                JsonArray arr = exchange.getAsJsonArray("entries");
                for (int i = 0; i < arr.size(); i++) {
                    if (t.exchangeEntries.size() >= MAX_ENTRIES) {
                        issues.add(new ValidationIssue("truncated", "exchange", "", "exceeded MAX_ENTRIES=" + MAX_ENTRIES));
                        break;
                    }
                    JsonElement element = arr.get(i);
                    if (!element.isJsonObject()) {
                        issues.add(new ValidationIssue("bad_entry", "exchange[" + i + "]", "", "not a json object"));
                        continue;
                    }
                    JsonObject one = element.getAsJsonObject();
                    Exchange entry = new Exchange();
                    entry.item = optString(one, "item", "");
                    entry.count = clamp(optInt(one, "count", 1), 1, 99);
                    entry.componentsSnbt = readComponentsSnbt(one);
                    entry.value = Math.max(0L, optLong(one, "value", 0L));
                    entry.dailyDepositLimit = Math.max(0,
                            optInt(one, "daily_deposit_limit", optInt(one, "dailyDepositLimit", 0)));
                    entry.dailyWithdrawLimit = Math.max(0,
                            optInt(one, "daily_withdraw_limit", optInt(one, "dailyWithdrawLimit", 0)));
                    t.exchangeEntries.add(entry);
                }
            }
        }

        JsonObject signin = optObject(root, "signin");
        if (signin != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.SIGNIN);
            }
            t.signInEnabled = optBool(signin, "enabled", true);
            t.signInAuto = optBool(signin, "auto", true);
            t.signInCumulative = optBool(signin, "cumulative", true);
            t.signInBaseDaily = Math.max(0L, optLong(signin, "base_daily", 10L));
            t.signInBaseSpec = signin.has("base_daily_spec")
                    ? PriceSpec.fromJson(signin.get("base_daily_spec"), t.signInBaseDaily)
                    : PriceSpec.star(t.signInBaseDaily);
            t.signInBaseDaily = t.signInBaseSpec.supportsStar() ? t.signInBaseSpec.starPrice : 0L;
            t.signInMakeupCost = Math.max(0L, optLong(signin, "makeup_cost", 100L));
            t.signInMakeupSpec = signin.has("makeup_cost_spec")
                    ? PriceSpec.fromJson(signin.get("makeup_cost_spec"), t.signInMakeupCost)
                    : PriceSpec.star(t.signInMakeupCost);
            t.signInMakeupCost = t.signInMakeupSpec.supportsStar() ? t.signInMakeupSpec.starPrice : 0L;
            t.signInMakeupDays = clamp(optInt(signin, "makeup_days", 2), 0, 30);
            t.signInTierDays = readInts(signin, "tier_days", t.signInTierDays);
            t.signInTierBonus = readLongs(signin, "tier_bonus", t.signInTierBonus);
            readPriceSpecs(signin, "tier_bonus_specs", t.signInTierBonusSpecs, 16);
            t.signInMileDays = readInts(signin, "mile_days", t.signInMileDays);
            t.signInMileMoney = readLongs(signin, "mile_money", t.signInMileMoney);
            readPriceSpecs(signin, "mile_specs", t.signInMileSpecs, 16);
        }

        JsonObject other = optObject(root, "other");
        if (other != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.CURRENCY);
            }
            t.currency = trim(optString(other, "currency", ""), 16);
            if (buyback == null || !buyback.has("bonus_enabled")) {
                t.buybackBonusEnabled = optBool(other, "buyback_bonus", true);
            }
            if ((buyback == null || !buyback.has("bonus_spec")) && other.has("buyback_bonus_spec")) {
                t.buybackBonusSpec = PriceSpec.fromJson(other.get("buyback_bonus_spec"), 0L);
            }
        }

        JsonObject pcObj = optObject(root, "physical_currency");
        if (pcObj != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.CURRENCY);
            }
            t.physicalCurrency = physicalCurrencyFromJson(pcObj);
        }

        JsonObject shipObj = optObject(root, "shipping");
        if (shipObj != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.SHIPPING);
            }
            t.shipping = ShippingData.fromJson(shipObj);
        }

        JsonObject layoutObj = optObject(root, "layout");
        if (layoutObj != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.LAYOUT);
            }
            t.layout = LayoutData.fromJson(layoutObj);
        }

        if (root.has("announcements") && root.get("announcements").isJsonArray()) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.ANNOUNCEMENTS);
            }
            JsonArray arr = root.getAsJsonArray("announcements");
            for (int i = 0; i < arr.size() && t.announcements.size() < 50; i++) {
                JsonElement el = arr.get(i);
                if (!el.isJsonObject()) {
                    continue;
                }
                AnnouncementEntry parsed = AnnouncementEntry.fromJson(el.getAsJsonObject());
                if (parsed != null) {
                    parsed.sanitize(i);
                    t.announcements.add(parsed);
                }
            }
        }

        JsonObject authObj = optObject(root, "admin_auth");
        if (authObj != null) {
            if (!explicitMask) {
                t.presentSections.add(ExportSection.SECURITY);
            }
            t.adminAuth = AuthData.fromJson(authObj);
        }

        return t;
    }

    private static String readComponentsSnbt(JsonObject one) {
        String s = optString(one, "tag_snbt", "");
        if (s.isEmpty()) {
            s = optString(one, "components_snbt", "");
        }
        if (s.isEmpty() && one.has("tag") && one.get("tag").isJsonPrimitive()) {
            s = optString(one, "tag", "");
        }
        if (s.isEmpty() && one.has("components") && one.get("components").isJsonPrimitive()) {
            s = optString(one, "components", "");
        }
        return s == null ? "" : s.trim();
    }

    private static void readOffers(JsonObject parent, List<Offer> into, List<ValidationIssue> issues, String domain) {
        if (!parent.has("offers") || !parent.get("offers").isJsonArray()) {
            return;
        }
        JsonArray arr = parent.getAsJsonArray("offers");
        for (int i = 0; i < arr.size(); i++) {
            if (into.size() >= MAX_ENTRIES) {
                issues.add(new ValidationIssue("truncated", domain, "", "exceeded MAX_ENTRIES=" + MAX_ENTRIES));
                break;
            }
            JsonElement element = arr.get(i);
            if (!element.isJsonObject()) {
                issues.add(new ValidationIssue("bad_entry", domain + "[" + i + "]", "", "not a json object"));
                continue;
            }
            JsonObject one = element.getAsJsonObject();
            Offer offer = new Offer();
            offer.item = optString(one, "item", "");
            offer.count = clamp(optInt(one, "count", 1), 1, 99);
            offer.componentsSnbt = readComponentsSnbt(one);
            offer.price = Math.max(0L, optLong(one, "price", 0L));
            offer.priceSpec = one.has("price_spec")
                    ? PriceSpec.fromJson(one.get("price_spec"), offer.price)
                    : PriceSpec.star(offer.price);
            offer.price = offer.priceSpec.supportsStar() ? offer.priceSpec.starPrice : 0L;
            offer.rarity = optString(one, "rarity", Rarity.COMMON.id());
            offer.amount = optInt(one, "amount", 0);
            into.add(offer);
        }
    }

    // ---- 小工具（全部容错：字段缺失/类型错都给默认值）----

    private static void readStrings(JsonObject parent, String key, List<String> into, int limit) {
        if (!parent.has(key) || !parent.get(key).isJsonArray()) {
            return;
        }
        for (JsonElement el : parent.getAsJsonArray(key)) {
            if (into.size() >= limit) {
                break;
            }
            if (el != null && el.isJsonPrimitive()) {
                into.add(el.getAsString());
            }
        }
    }

    private static void readPriceSpecs(JsonObject parent, String key, List<PriceSpec> into, int limit) {
        if (!parent.has(key) || !parent.get(key).isJsonArray()) {
            return;
        }
        for (JsonElement el : parent.getAsJsonArray(key)) {
            if (into.size() >= limit) {
                break;
            }
            into.add(PriceSpec.fromJson(el, 0L));
        }
    }

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
