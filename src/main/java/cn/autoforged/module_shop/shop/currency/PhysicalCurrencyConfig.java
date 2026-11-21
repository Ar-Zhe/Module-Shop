package cn.autoforged.module_shop.shop.currency;

import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.data.ShopNbt;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分层实体货币配置与热更新持久化（{@code config/moduleshop/physical_currency.json}）。
 *
 * <p>在双独立并行账本架构下，实体货币与星币账本完全隔离、互不折抵；
 * 仅在同一实体货币配置链内部支持按 {@code value / stepRatio} 破整找零与双向兑换。
 * 每个档位拥有稳定 {@link CurrencyTier#id}，供商品/挂单长期引用而不受排序或增删档位影响。
 */
public final class PhysicalCurrencyConfig {
    public static final String FILE_NAME = "physical_currency.json";
    public static final String CONFIG_SUBDIR = ShopTemplateStore.DIR_NAME;
    public static final int MAX_TIERS = 16;

    public static final class CurrencyTier {
        public String id = "";
        public String itemId = "";
        public String componentsSnbt = "";
        public int stepRatio = 1;
        public long value = 1L;
        public String label = "";

        public CurrencyTier() {
        }

        public CurrencyTier(String itemId, String componentsSnbt, int stepRatio, long value, String label) {
            this("", itemId, componentsSnbt, stepRatio, value, label);
        }

        public CurrencyTier(@Nullable String id, String itemId, String componentsSnbt, int stepRatio, long value,
                            String label) {
            this.id = id == null ? "" : id.trim();
            this.itemId = itemId == null ? "" : itemId.trim();
            this.componentsSnbt = componentsSnbt == null ? "" : componentsSnbt.trim();
            this.stepRatio = Math.max(0, stepRatio);
            this.value = value;
            this.label = label == null ? "" : label.trim();
        }

        public CurrencyTier copy() {
            return new CurrencyTier(id, itemId, componentsSnbt, stepRatio, value, label);
        }

        public String uniqueItemKey() {
            String item = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
            String snbt = componentsSnbt == null ? "" : componentsSnbt.trim();
            return item + "|" + snbt;
        }

        public String tierKey() {
            return id != null && !id.isBlank() ? id.trim() : uniqueItemKey();
        }
    }

    public static final class ResolvedTier {
        public final int index;
        public final CurrencyTier spec;
        public final ItemStack template;

        public ResolvedTier(int index, CurrencyTier spec, ItemStack template) {
            this.index = index;
            this.spec = spec;
            this.template = template;
        }
    }

    public record ParseSaveResult(boolean ok, String errorMessage, @Nullable PhysicalCurrencyConfig config) {
    }

    private record ResolvedCache(long generation, HolderLookup.Provider registries, List<ResolvedTier> tiers) {
    }

    private static final class LogHolder {
        private static final Logger LOG = LoggerFactory.getLogger("module_shop/physical_currency");
    }

    private static final class GsonHolder {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    public int formatVersion = 2;
    public boolean enabled = false;
    public boolean blockBuybackAndShipping = true;
    /** 单次阶梯兑换最大产出枚数（默认 2304 = 36×64，防刷与背包/邮箱溢出保护）。 */
    public int maxConvertOutputItems = PhysicalCurrencyEngine.DEFAULT_MAX_CHANGE_ITEMS;
    /** 每人每日阶梯兑换基准面值上限（0 = 不限）。 */
    public long dailyConvertValueLimit = 0L;
    public List<CurrencyTier> tiers = new ArrayList<>();

    private static volatile PhysicalCurrencyConfig instance;
    private static volatile long configGeneration = 1L;
    private static volatile ResolvedCache resolvedCache;

    public static synchronized PhysicalCurrencyConfig get() {
        if (instance == null) {
            reload();
        }
        return instance;
    }

    public static synchronized void reload() {
        reload(resolveRuntimeConfigDir());
    }

    public static synchronized void reload(Path configDir) {
        Path file = resolveFile(configDir);
        try {
            if (Files.isRegularFile(file)) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                PhysicalCurrencyConfig parsed = GsonHolder.GSON.fromJson(raw, PhysicalCurrencyConfig.class);
                if (parsed != null) {
                    parsed.normalize();
                    instance = parsed;
                    configGeneration++;
                    resolvedCache = null;
                    return;
                }
            } else {
                PhysicalCurrencyConfig defaults = createDefault();
                defaults.normalize();
                writeFileAtomic(file, defaults);
                instance = defaults;
                configGeneration++;
                resolvedCache = null;
                return;
            }
        } catch (Throwable ex) {
            LogHolder.LOG.warn("[ModuleShop] 读取 {} 失败，使用内置默认实体货币配置：{}", FILE_NAME, ex.toString());
        }
        PhysicalCurrencyConfig fallback = createDefault();
        fallback.normalize();
        instance = fallback;
        configGeneration++;
        resolvedCache = null;
    }

    public static synchronized boolean replaceAndSave(PhysicalCurrencyConfig next) {
        return replaceAndSave(resolveRuntimeConfigDir(), next);
    }

    public static synchronized boolean replaceAndSave(Path configDir, PhysicalCurrencyConfig next) {
        if (next == null) {
            return false;
        }
        next.normalize();
        instance = next;
        configGeneration++;
        resolvedCache = null;
        return writeFileAtomic(resolveFile(configDir), next);
    }

    /** 是否实际激活实体货币系统（开关开启且至少有一个有效档位）。 */
    public boolean isActive() {
        return enabled && tiers != null && !tiers.isEmpty();
    }

    public boolean isBlockingBuybackAndShipping() {
        return isActive() && blockBuybackAndShipping;
    }

    public int effectiveMaxConvertOutputItems() {
        return maxConvertOutputItems <= 0
                ? PhysicalCurrencyEngine.DEFAULT_MAX_CHANGE_ITEMS
                : Math.max(1, Math.min(64000, maxConvertOutputItems));
    }

    public synchronized void normalize() {
        formatVersion = 2;
        maxConvertOutputItems = effectiveMaxConvertOutputItems();
        dailyConvertValueLimit = Math.max(0L, dailyConvertValueLimit);
        if (tiers == null) {
            tiers = new ArrayList<>();
        }
        List<CurrencyTier> clean = new ArrayList<>(tiers.size());
        Set<String> seenKeys = new HashSet<>();
        Set<Long> seenValues = new HashSet<>();
        Set<String> seenIds = new HashSet<>();
        for (CurrencyTier raw : tiers) {
            if (raw == null || raw.itemId == null || raw.itemId.isBlank() || raw.value <= 0L) {
                continue;
            }
            CurrencyTier tier = raw.copy();
            tier.itemId = tier.itemId.trim();
            tier.componentsSnbt = tier.componentsSnbt == null ? "" : tier.componentsSnbt.trim();
            if (!seenKeys.add(tier.uniqueItemKey()) || !seenValues.add(tier.value)) {
                continue;
            }
            tier.id = ensureStableId(tier, seenIds);
            clean.add(tier);
            if (clean.size() >= MAX_TIERS) {
                break;
            }
        }
        clean.sort(Comparator.comparingLong(t -> t.value));
        recomputeStepRatios(clean);
        this.tiers = clean;
        if (this.tiers.isEmpty()) {
            this.enabled = false;
        }
    }

    private static String ensureStableId(CurrencyTier tier, Set<String> usedIds) {
        String candidate = tier.id == null ? "" : tier.id.trim();
        if (candidate.isEmpty()) {
            String item = tier.itemId.trim().toLowerCase(Locale.ROOT);
            if (tier.componentsSnbt == null || tier.componentsSnbt.isEmpty()) {
                candidate = item;
            } else {
                candidate = item + "#" + shortHash(tier.componentsSnbt);
            }
        }
        String unique = candidate;
        int suffix = 2;
        while (!usedIds.add(unique)) {
            unique = candidate + "_" + suffix++;
        }
        return unique;
    }

    private static String shortHash(String text) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x100000001B3L;
        }
        String hex = Long.toHexString(h);
        return hex.length() > 8 ? hex.substring(0, 8) : hex;
    }

    private static void recomputeStepRatios(List<CurrencyTier> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            CurrencyTier tier = sorted.get(i);
            if (i == 0) {
                tier.stepRatio = Math.max(1, tier.stepRatio);
            } else {
                CurrencyTier prev = sorted.get(i - 1);
                if (prev.value > 0L && tier.value > prev.value && tier.value % prev.value == 0L) {
                    long ratio = tier.value / prev.value;
                    tier.stepRatio = ratio <= Integer.MAX_VALUE ? (int) ratio : 0;
                } else {
                    tier.stepRatio = 0;
                }
            }
        }
    }

    /**
     * 根据稳定 key 查找配置档位（依次兼容匹配 {@code id}、{@code uniqueItemKey()} 及无组件 {@code itemId}）。
     */
    public @Nullable CurrencyTier findTier(@Nullable String key) {
        int idx = findTierIndex(key);
        return idx >= 0 && tiers != null && idx < tiers.size() ? tiers.get(idx) : null;
    }

    public int findTierIndex(@Nullable String key) {
        if (key == null || key.isBlank() || tiers == null || tiers.isEmpty()) {
            return -1;
        }
        String clean = key.trim();
        // 1) 精确匹配稳定 id
        for (int i = 0; i < tiers.size(); i++) {
            CurrencyTier tier = tiers.get(i);
            if (tier.id != null && !tier.id.isEmpty() && tier.id.equals(clean)) {
                return i;
            }
        }
        // 2) 匹配 uniqueItemKey()（itemId|componentsSnbt）
        String normLower = clean.toLowerCase(Locale.ROOT);
        for (int i = 0; i < tiers.size(); i++) {
            CurrencyTier tier = tiers.get(i);
            String uKey = tier.uniqueItemKey();
            if (uKey.equals(clean) || uKey.equals(normLower)) {
                return i;
            }
        }
        // 3) 兼容无组件 itemId（或末尾带 "|" 的空组件写法）
        String stripped = clean.endsWith("|") ? clean.substring(0, clean.length() - 1) : clean;
        for (int i = 0; i < tiers.size(); i++) {
            CurrencyTier tier = tiers.get(i);
            if ((tier.componentsSnbt == null || tier.componentsSnbt.isEmpty())
                    && tier.itemId.equalsIgnoreCase(stripped)) {
                return i;
            }
        }
        // 4) 兜底按 itemId 匹配唯一同名带组件币
        if (!stripped.contains("|") && !stripped.contains("#")) {
            int found = -1;
            for (int i = 0; i < tiers.size(); i++) {
                if (tiers.get(i).itemId.equalsIgnoreCase(stripped)) {
                    if (found >= 0) {
                        return -1; // 多个不同组件复用同一 itemId 时不盲猜
                    }
                    found = i;
                }
            }
            return found;
        }
        return -1;
    }

    public String canonicalTierKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        CurrencyTier found = findTier(key);
        return found != null ? found.tierKey() : key.trim();
    }

    public @Nullable ResolvedTier findResolvedTier(@Nullable String key, @Nullable HolderLookup.Provider regs) {
        CurrencyTier spec = findTier(key);
        if (spec == null || regs == null) {
            return null;
        }
        String targetId = spec.tierKey();
        for (ResolvedTier rt : resolveTiers(regs)) {
            if (rt.spec.tierKey().equals(targetId) || rt.spec.uniqueItemKey().equals(spec.uniqueItemKey())) {
                return rt;
            }
        }
        return null;
    }

    public long[] tierValues() {
        List<CurrencyTier> list = tiers == null ? List.of() : tiers;
        long[] values = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            values[i] = list.get(i).value;
        }
        return values;
    }

    /** 将配置中的档位解析为带完整组件缓存的 {@link ItemStack} 模板（失效项自动跳过）。 */
    public List<ResolvedTier> resolveTiers(@Nullable HolderLookup.Provider regs) {
        if (regs == null) {
            return List.of();
        }
        long gen = configGeneration;
        ResolvedCache cached = resolvedCache;
        if (cached != null && cached.generation == gen && cached.registries == regs) {
            return cached.tiers;
        }
        List<ResolvedTier> built = new ArrayList<>();
        List<CurrencyTier> copy = tiers == null ? List.of() : new ArrayList<>(tiers);
        for (CurrencyTier tier : copy) {
            ItemStack template = buildTemplate(tier, regs);
            if (!template.isEmpty()) {
                built.add(new ResolvedTier(built.size(), tier.copy(), template));
            }
        }
        List<ResolvedTier> immutable = List.copyOf(built);
        resolvedCache = new ResolvedCache(gen, regs, immutable);
        return immutable;
    }

    /** 即使未激活（或有部分档位物品来自未安装模组），也构建完整配置顺序供管理界面展示与编辑。 */
    public List<ResolvedTier> resolveAllForDisplay(@Nullable HolderLookup.Provider regs) {
        List<CurrencyTier> copy = tiers == null ? List.of() : new ArrayList<>(tiers);
        List<ResolvedTier> out = new ArrayList<>(copy.size());
        for (int i = 0; i < copy.size(); i++) {
            CurrencyTier spec = copy.get(i).copy();
            ItemStack template = regs == null ? ItemStack.EMPTY : buildTemplate(spec, regs);
            out.add(new ResolvedTier(i, spec, template));
        }
        return out;
    }

    public static ParseSaveResult parseAndValidateFromTag(CompoundTag tag, HolderLookup.Provider regs) {
        if (tag == null) {
            return new ParseSaveResult(false, "配置数据为空", null);
        }
        boolean nextEnabled = tag.getBoolean("physEnabled");
        boolean nextBlock = !tag.contains("physBlockBuyback") || tag.getBoolean("physBlockBuyback");
        int nextMaxConvert = tag.contains("physMaxConvertItems")
                ? tag.getInt("physMaxConvertItems")
                : PhysicalCurrencyEngine.DEFAULT_MAX_CHANGE_ITEMS;
        if (nextMaxConvert < 1 || nextMaxConvert > 64000) {
            return new ParseSaveResult(false, "单次阶梯兑换上限必须在 1 ~ 64000 之间", null);
        }
        long nextDailyLimit = tag.contains("physDailyConvertLimit")
                ? tag.getLong("physDailyConvertLimit")
                : 0L;
        if (nextDailyLimit < 0L) {
            return new ParseSaveResult(false, "每日阶梯兑换上限不能为负数（0 = 不限）", null);
        }
        ListTag tierList = tag.getList("physTiers", Tag.TAG_COMPOUND);

        PhysicalCurrencyConfig prev = get();
        List<CurrencyTier> parsedTiers = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        Set<Long> seenValues = new HashSet<>();

        for (int i = 0; i < tierList.size(); i++) {
            if (parsedTiers.size() >= MAX_TIERS) {
                return new ParseSaveResult(false, "实体货币最多配置 " + MAX_TIERS + " 个档位", null);
            }
            CompoundTag t = tierList.getCompound(i);
            String tierId = t.getString("id").trim();
            String itemId = t.getString("itemId").trim();
            String componentsSnbt = t.getString("componentsSnbt").trim();
            if (t.contains("sampleStack", Tag.TAG_COMPOUND)) {
                ItemStack sample = parseAnyStackTag(t.getCompound("sampleStack"), regs);
                if (!sample.isEmpty()) {
                    ResourceLocation rl = BuiltInRegistries.ITEM.getKey(sample.getItem());
                    itemId = rl.toString();
                    componentsSnbt = extractComponentsSnbt(sample, regs);
                }
            }
            if (itemId.isEmpty()) {
                return new ParseSaveResult(false, "第 " + (i + 1) + " 档物品 ID 不能为空", null);
            }
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                return new ParseSaveResult(false, "第 " + (i + 1) + " 档未知物品：" + itemId, null);
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == Items.AIR) {
                return new ParseSaveResult(false, "第 " + (i + 1) + " 档不能使用空气作为货币", null);
            }
            if (!componentsSnbt.isEmpty()) {
                try {
                    CompoundTag comp = TagParser.parseTag(componentsSnbt);
                    CompoundTag stackCheckTag = new CompoundTag();
                    stackCheckTag.putString("id", rl.toString());
                    stackCheckTag.putInt("count", 1);
                    stackCheckTag.put("components", comp);
                    ItemStack parsedStack = ItemStack.parseOptional(regs, stackCheckTag);
                    if (parsedStack.isEmpty()) {
                        return new ParseSaveResult(false, "第 " + (i + 1) + " 档组件数据无效", null);
                    }
                    componentsSnbt = extractComponentsSnbt(parsedStack, regs);
                } catch (CommandSyntaxException ex) {
                    return new ParseSaveResult(false, "第 " + (i + 1) + " 档组件 SNBT 格式错误", null);
                }
            }

            long value = t.getLong("value");
            int stepRatio = Math.max(1, t.getInt("stepRatio"));
            if (value <= 0L) {
                return new ParseSaveResult(false, "第 " + (i + 1) + " 档货币面值必须大于 0", null);
            }
            String label = t.getString("label").trim();
            if (label.length() > 32) {
                label = label.substring(0, 32);
            }
            CurrencyTier tier = new CurrencyTier(tierId, rl.toString(), componentsSnbt, stepRatio, value, label);
            if (tier.id.isEmpty() && prev != null) {
                CurrencyTier existing = prev.findTier(tier.uniqueItemKey());
                if (existing != null && existing.id != null) {
                    tier.id = existing.id;
                }
            }
            if (!seenKeys.add(tier.uniqueItemKey())) {
                return new ParseSaveResult(false, "存在重复的货币物品/组件：" + rl, null);
            }
            if (!seenValues.add(value)) {
                return new ParseSaveResult(false, "存在重复的货币面值：" + value, null);
            }
            parsedTiers.add(tier);
        }

        if (nextEnabled && parsedTiers.isEmpty()) {
            return new ParseSaveResult(false, "启用实体货币前请至少配置 1 个货币档位", null);
        }

        PhysicalCurrencyConfig cfg = new PhysicalCurrencyConfig();
        cfg.formatVersion = 2;
        cfg.enabled = nextEnabled;
        cfg.blockBuybackAndShipping = nextBlock;
        cfg.maxConvertOutputItems = nextMaxConvert;
        cfg.dailyConvertValueLimit = nextDailyLimit;
        cfg.tiers = parsedTiers;
        cfg.normalize();
        return new ParseSaveResult(true, "", cfg);
    }

    public static ItemStack parseAnyStackTag(@Nullable CompoundTag rawTag, @Nullable HolderLookup.Provider regs) {
        if (rawTag == null || rawTag.isEmpty() || regs == null) {
            return ItemStack.EMPTY;
        }
        ItemStack direct = ItemStack.parseOptional(regs, rawTag);
        if (!direct.isEmpty()) {
            return direct;
        }
        if (rawTag.contains("item", Tag.TAG_COMPOUND)) {
            return ShopNbt.loadStack(rawTag, regs);
        }
        return ItemStack.EMPTY;
    }

    public static String extractComponentsSnbt(ItemStack stack, @Nullable HolderLookup.Provider regs) {
        if (stack == null || stack.isEmpty() || regs == null) {
            return "";
        }
        try {
            Tag saved = stack.copyWithCount(1).saveOptional(regs);
            if (saved instanceof CompoundTag ct && ct.contains("components", Tag.TAG_COMPOUND)) {
                CompoundTag comps = ct.getCompound("components");
                if (!comps.isEmpty()) {
                    return comps.getAsString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static ItemStack buildTemplate(CurrencyTier tier, @Nullable HolderLookup.Provider regs) {
        if (tier == null || tier.itemId == null || tier.itemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(tier.itemId.trim());
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        String snbt = tier.componentsSnbt == null ? "" : tier.componentsSnbt.trim();
        if (snbt.isEmpty()) {
            return new ItemStack(item, 1);
        }
        if (regs == null) {
            return ItemStack.EMPTY;
        }
        try {
            CompoundTag comp = TagParser.parseTag(snbt);
            CompoundTag stackTag = new CompoundTag();
            stackTag.putString("id", rl.toString());
            stackTag.putInt("count", 1);
            stackTag.put("components", comp);
            return ItemStack.parseOptional(regs, stackTag);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    public static PhysicalCurrencyConfig createDefault() {
        PhysicalCurrencyConfig cfg = new PhysicalCurrencyConfig();
        cfg.formatVersion = 2;
        cfg.enabled = false;
        cfg.blockBuybackAndShipping = true;
        cfg.tiers.add(new CurrencyTier("minecraft:dirt", "minecraft:dirt", "", 1, 1L, "泥土币"));
        cfg.tiers.add(new CurrencyTier("minecraft:iron_ingot", "minecraft:iron_ingot", "", 10, 10L, "铁币"));
        cfg.tiers.add(new CurrencyTier("minecraft:gold_ingot", "minecraft:gold_ingot", "", 10, 100L, "金币"));
        cfg.tiers.add(new CurrencyTier("minecraft:diamond", "minecraft:diamond", "", 10, 1000L, "钻石币"));
        cfg.tiers.add(new CurrencyTier("minecraft:netherite_ingot", "minecraft:netherite_ingot", "", 5, 5000L, "下界合金币"));
        return cfg;
    }

    private static Path resolveRuntimeConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path resolveFile(Path configDir) {
        return configDir.resolve(CONFIG_SUBDIR).resolve(FILE_NAME);
    }

    private static boolean writeFileAtomic(Path file, PhysicalCurrencyConfig cfg) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, GsonHolder.GSON.toJson(cfg), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable ex) {
            LogHolder.LOG.warn("[ModuleShop] 写入 {} 失败：{}", FILE_NAME, ex.toString());
            return false;
        }
    }
}
