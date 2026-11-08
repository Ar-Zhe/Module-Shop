package cn.autoforged.module_shop.shop;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.config.ShopTemplate;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.data.ConfiguredOffer;
import cn.autoforged.module_shop.data.ExchangeEntry;
import cn.autoforged.module_shop.data.MallItem;
import cn.autoforged.module_shop.data.ShopSavedData;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * 模板服务：在「世界数据」和「模板文件」之间做**单向白名单**转换。
 *
 * <ul>
 *   <li>{@link #extract} 只读取模板字段（商品 / 价格 / 权重 / 签到参数 / 货币名），
 *       余额、购买记录、拍卖、求购、邮件、兑换码等运行数据**永远不会**被带出；</li>
 *   <li>{@link #apply} 只写模板字段，绝不触碰运行数据；</li>
 *   <li>{@link #initNewWorldIfNeeded} 只在"新世界 + 池子仍是默认种子 + 未初始化过"时执行一次。</li>
 * </ul>
 */
public final class TemplateService {
    private TemplateService() {
    }

    /** 应用模式。 */
    public enum Mode {
        /** 替换：清空现有池子，全部用模板内容。 */
        REPLACE,
        /** 合并：保留现有条目，模板里没有的物品追加；同物品按模板价格覆盖。 */
        MERGE
    }

    /** 应用结果（用于提示与预览）。 */
    public record ApplyResult(int applied, int invalid, int duplicates, int removed, String error) {
        public boolean ok() {
            return error == null || error.isEmpty();
        }

        public String summary() {
            return "applied=" + applied + " invalid=" + invalid + " duplicates=" + duplicates + " removed=" + removed;
        }
    }

    /** 预览结果。 */
    public record Preview(int total, int valid, int invalid, int conflicts, String name, String createdAt) {
    }

    // ------------------------------------------------------------------ 世界 -> 模板

    /** 从世界数据提取模板（白名单）。 */
    public static ShopTemplate extract(ShopSavedData data, String name) {
        ShopTemplate template = new ShopTemplate();
        template.modVersion = ShopTemplate.currentModVersion();
        template.createdAt = Instant.now().toString();
        template.name = name == null || name.isBlank() ? "商店配置" : name;

        template.shopSlots = data.shopSlotCount;
        template.shopWeights = copy(data.shopWeights);
        template.shopGuarantee = copy(data.shopGuarantee);
        for (ConfiguredOffer offer : data.shopPool) {
            template.shopOffers.add(ShopTemplate.Offer.of(offer));
        }

        template.buybackSlots = data.buybackSlotCount;
        template.buybackWeights = copy(data.buybackWeights);
        template.buybackGuarantee = copy(data.buybackGuarantee);
        for (ConfiguredOffer offer : data.buybackPool) {
            template.buybackOffers.add(ShopTemplate.Offer.of(offer));
        }

        for (MallItem item : data.mallPool) {
            template.mallItems.add(ShopTemplate.MallEntry.of(item));
        }
        for (ExchangeEntry entry : data.exchangePool) {
            template.exchangeEntries.add(ShopTemplate.Exchange.of(entry));
        }

        template.signInEnabled = data.signInEnabled;
        template.signInBaseDaily = data.signInBaseDaily;
        template.signInMakeupCost = data.signInMakeupCost;
        template.signInMakeupDays = data.signInMakeupDays;
        template.signInTierDays = copy(data.signInTierDays);
        template.signInTierBonus = copy(data.signInTierBonus);
        template.signInMileDays = copy(data.signInMileDays);
        template.signInMileMoney = copy(data.signInMileMoney);

        template.currency = data.shopCurrency == null ? "" : data.shopCurrency;
        template.buybackBonusEnabled = data.buybackBonusEnabled;
        return template;
    }

    /** 导出当前世界模板到 `exports/`；返回写出的文件（失败 {@code null}）。 */
    public static Path export(MinecraftServer server, ShopSavedData data, Path configDir, String name) {
        ShopTemplate template = extract(data, name);
        return ShopTemplateStore.exportTo(configDir, template, "moduleshop_shop");
    }

    /** 把当前世界模板写进默认文件 `shop_config.json`（作为"客户端默认模板/整合包模板"）。 */
    public static String saveAsDefault(ShopSavedData data, Path configDir, String name) {
        ShopTemplate template = extract(data, name);
        return ShopTemplateStore.write(ShopTemplateStore.defaultFile(configDir), template);
    }

    // ------------------------------------------------------------------ 模板 -> 世界

    /** 统计预览信息（不改世界数据）。 */
    public static Preview preview(ShopTemplate template, ShopSavedData data) {
        int total = 0;
        int valid = 0;
        int invalid = 0;
        int conflicts = 0;

        Set<String> existingShop = ids(data.shopPool);
        Set<String> existingBuyback = ids(data.buybackPool);
        Set<String> existingMall = mallIds(data.mallPool);

        for (ShopTemplate.Offer offer : template.shopOffers) {
            total++;
            if (ShopTemplate.stackOf(offer.item, offer.count).isEmpty()) {
                invalid++;
            } else {
                valid++;
                if (existingShop.contains(offer.item)) {
                    conflicts++;
                }
            }
        }
        for (ShopTemplate.Offer offer : template.buybackOffers) {
            total++;
            if (ShopTemplate.stackOf(offer.item, offer.count).isEmpty()) {
                invalid++;
            } else {
                valid++;
                if (existingBuyback.contains(offer.item)) {
                    conflicts++;
                }
            }
        }
        for (ShopTemplate.MallEntry entry : template.mallItems) {
            total++;
            if (ShopTemplate.stackOf(entry.item, entry.count).isEmpty()) {
                invalid++;
            } else {
                valid++;
                if (existingMall.contains(entry.item)) {
                    conflicts++;
                }
            }
        }
        for (ShopTemplate.Exchange entry : template.exchangeEntries) {
            total++;
            if (ShopTemplate.stackOf(entry.item, entry.count).isEmpty()) {
                invalid++;
            } else {
                valid++;
            }
        }
        return new Preview(total, valid, invalid, conflicts, template.name, template.createdAt);
    }

    /**
     * 应用模板。
     *
     * <p>只写模板字段；运行数据（accounts / signIns / mallRecent / auctions / demands / redeemCodes）不动。
     * 无效物品**跳过并计数**，不会让整次导入失败。
     */
    public static ApplyResult apply(ShopTemplate template, ShopSavedData data, Mode mode) {
        if (template == null || data == null) {
            return new ApplyResult(0, 0, 0, 0, "invalid");
        }
        boolean replace = mode == Mode.REPLACE;
        int applied = 0;
        int invalid = 0;
        int duplicates = 0;
        int removed = 0;

        // ---- 每日商店 ----
        if (replace) {
            removed += data.shopPool.size();
            data.shopPool.clear();
        }
        data.shopSlotCount = Math.max(1, Math.min(27, template.shopSlots));
        if (template.shopWeights != null && template.shopWeights.length > 0) {
            System.arraycopy(template.shopWeights, 0, data.shopWeights, 0,
                    Math.min(template.shopWeights.length, data.shopWeights.length));
        }
        if (template.shopGuarantee != null && template.shopGuarantee.length > 0) {
            System.arraycopy(template.shopGuarantee, 0, data.shopGuarantee, 0,
                    Math.min(template.shopGuarantee.length, data.shopGuarantee.length));
        }
        applied += mergeOffers(template.shopOffers, data.shopPool, replace);
        invalid += countInvalid(template.shopOffers);
        duplicates += countDuplicates(template.shopOffers, data.shopPool, replace);

        // ---- 每日收购 ----
        if (replace) {
            removed += data.buybackPool.size();
            data.buybackPool.clear();
        }
        data.buybackSlotCount = Math.max(1, Math.min(27, template.buybackSlots));
        if (template.buybackWeights != null && template.buybackWeights.length > 0) {
            System.arraycopy(template.buybackWeights, 0, data.buybackWeights, 0,
                    Math.min(template.buybackWeights.length, data.buybackWeights.length));
        }
        if (template.buybackGuarantee != null && template.buybackGuarantee.length > 0) {
            System.arraycopy(template.buybackGuarantee, 0, data.buybackGuarantee, 0,
                    Math.min(template.buybackGuarantee.length, data.buybackGuarantee.length));
        }
        applied += replace
                ? mergeOffers(template.buybackOffers, data.buybackPool, true)
                : mergeOffers(template.buybackOffers, data.buybackPool, false);
        invalid += countInvalid(template.buybackOffers);

        // ---- 购物城 ----
        if (replace) {
            removed += data.mallPool.size();
            data.mallPool.clear();
        }
        for (ShopTemplate.MallEntry entry : template.mallItems) {
            MallItem item = entry.toMallItem();
            if (item == null) {
                invalid++;
                continue;
            }
            MallItem existing = findMall(data.mallPool, entry.item);
            if (existing != null) {
                if (replace) {
                    data.mallPool.remove(existing);
                } else {
                    existing.price = item.price;
                    existing.stock = item.stock;
                    existing.stockMax = item.stockMax;
                    existing.sort = item.sort;
                    existing.rarity = item.rarity;
                    applied++;
                    duplicates++;
                    continue;
                }
            }
            data.mallPool.add(item);
            applied++;
        }

        // ---- 货币兑换 ----
        if (replace) {
            removed += data.exchangePool.size();
            data.exchangePool.clear();
        }
        for (ShopTemplate.Exchange entry : template.exchangeEntries) {
            ExchangeEntry converted = entry.toEntry();
            if (converted == null) {
                invalid++;
                continue;
            }
            ExchangeEntry existing = findExchange(data.exchangePool, entry.item);
            if (existing != null) {
                existing.value = converted.value;
                existing.stack = converted.stack;
                duplicates++;
                continue;
            }
            data.exchangePool.add(converted);
            applied++;
        }

        // ---- 签到 / 货币 / 收购奖励 ----
        data.signInEnabled = template.signInEnabled;
        data.signInBaseDaily = Math.max(0L, template.signInBaseDaily);
        data.signInMakeupCost = Math.max(0L, template.signInMakeupCost);
        data.signInMakeupDays = Math.max(0, Math.min(30, template.signInMakeupDays));
        copyInto(template.signInTierDays, data.signInTierDays);
        copyInto(template.signInTierBonus, data.signInTierBonus);
        copyInto(template.signInMileDays, data.signInMileDays);
        copyInto(template.signInMileMoney, data.signInMileMoney);
        if (template.currency != null && !template.currency.isBlank()) {
            data.shopCurrency = template.currency;
        }
        data.buybackBonusEnabled = template.buybackBonusEnabled;

        data.setDirty();
        return new ApplyResult(applied, invalid, duplicates, removed, "");
    }

    // ------------------------------------------------------------------ 新世界初始化

    /**
     * 新世界首次启动：用客户端 / 服务器的默认模板初始化商店。
     *
     * <p>三个前置条件缺一不可：
     * <ol>
     *   <li>配置开关 `templateInitEnabled` 为真；</li>
     *   <li>世界从未初始化过（`templateInitDone == false`）；</li>
     *   <li>池子仍然是"默认种子"（说明没人改过商品），避免覆盖已配置好的老世界。</li>
     * </ol>
     * 执行后立即把 `templateInitDone` 置真并落盘，之后进多少次都不会再跑。
     */
    public static void initNewWorldIfNeeded(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            ShopSavedData data = ShopSavedData.get(server);
            org.slf4j.LoggerFactory.getLogger("module_shop").info(
                    "模板初始化检查：initDone={} seedDefault={} 模板文件={}", data.templateInitDone,
                    data.isSeedDefaultPools(),
                    cn.autoforged.module_shop.config.ShopTemplateStore.defaultFile(
                            net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()));
            if (data.templateInitDone) {
                return;
            }

            if (!data.isSeedDefaultPools()) {
                data.templateInitDone = true;
                data.setDirty();
                return;
            }
            Path configDir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
            ShopTemplateStore.ReadResult result = ShopTemplateStore.read(ShopTemplateStore.defaultFile(configDir));
            if (!result.ok()) {
                // 没有模板 / 模板损坏：保留模组自带默认池，打标记避免反复尝试
                data.templateInitDone = true;
                data.setDirty();
                if ("bad_json".equals(result.error()) || "newer_format".equals(result.error())) {
                    org.slf4j.LoggerFactory.getLogger("module_shop")
                            .warn("默认商店模板无法使用（{}），新世界使用模组自带默认商品", result.error());
                }
                return;
            }
            ApplyResult applied = apply(result.template(), data, Mode.REPLACE);
            data.templateInitDone = true;
            data.setDirty();
            org.slf4j.LoggerFactory.getLogger("module_shop").info(
                    "已用默认商店模板初始化新世界：{}（{}）", result.template().name, applied.summary());
        } catch (Throwable error) {
            org.slf4j.LoggerFactory.getLogger("module_shop").warn("新世界商店初始化失败：{}", error.toString());
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private static int mergeOffers(List<ShopTemplate.Offer> source, List<ConfiguredOffer> target, boolean replace) {
        int applied = 0;
        for (ShopTemplate.Offer offer : source) {
            ConfiguredOffer converted = offer.toConfigured();
            if (converted == null) {
                continue;
            }
            ConfiguredOffer existing = findByItem(target, offer.item);
            if (existing != null) {
                existing.price = converted.price;
                existing.rarity = converted.rarity;
                existing.amount = converted.amount;
                existing.stack = converted.stack;
                applied++;
                continue;
            }
            target.add(converted);
            applied++;
        }
        return applied;
    }

    private static int countInvalid(List<ShopTemplate.Offer> source) {
        int invalid = 0;
        for (ShopTemplate.Offer offer : source) {
            if (ShopTemplate.stackOf(offer.item, offer.count).isEmpty()) {
                invalid++;
            }
        }
        return invalid;
    }

    private static int countDuplicates(List<ShopTemplate.Offer> source, List<ConfiguredOffer> target, boolean replace) {
        if (replace) {
            // 替换模式下"冲突"没有意义（旧内容本来就清空）
            return 0;
        }
        int duplicates = 0;
        Set<String> seen = new HashSet<>();
        for (ShopTemplate.Offer offer : source) {
            if (findByItem(target, offer.item) != null || !seen.add(offer.item)) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private static ConfiguredOffer findByItem(List<ConfiguredOffer> pool, String itemId) {
        for (ConfiguredOffer offer : pool) {
            if (itemId != null && itemId.equals(ShopTemplate.itemId(offer.stack))) {
                return offer;
            }
        }
        return null;
    }

    private static MallItem findMall(List<MallItem> pool, String itemId) {
        for (MallItem item : pool) {
            if (itemId != null && itemId.equals(ShopTemplate.itemId(item.stack))) {
                return item;
            }
        }
        return null;
    }

    private static ExchangeEntry findExchange(List<ExchangeEntry> pool, String itemId) {
        for (ExchangeEntry entry : pool) {
            if (itemId != null && itemId.equals(ShopTemplate.itemId(entry.stack))) {
                return entry;
            }
        }
        return null;
    }

    private static Set<String> ids(List<ConfiguredOffer> pool) {
        Set<String> out = new HashSet<>();
        for (ConfiguredOffer offer : pool) {
            out.add(ShopTemplate.itemId(offer.stack));
        }
        out.remove("");
        return out;
    }

    private static Set<String> mallIds(List<MallItem> pool) {
        Set<String> out = new HashSet<>();
        for (MallItem item : pool) {
            out.add(ShopTemplate.itemId(item.stack));
        }
        out.remove("");
        return out;
    }

    private static int[] copy(int[] source) {
        return source == null ? new int[0] : source.clone();
    }

    private static long[] copy(long[] source) {
        return source == null ? new long[0] : source.clone();
    }

    private static void copyInto(int[] source, int[] target) {
        if (source == null || target == null) {
            return;
        }
        int n = Math.min(source.length, target.length);
        System.arraycopy(source, 0, target, 0, n);
    }

    private static void copyInto(long[] source, long[] target) {
        if (source == null || target == null) {
            return;
        }
        int n = Math.min(source.length, target.length);
        System.arraycopy(source, 0, target, 0, n);
    }

    /** 诊断用：模板里各池子的条目数。 */
    public static Map<String, Integer> counts(ShopTemplate template) {
        Map<String, Integer> map = new HashMap<>();
        if (template == null) {
            return map;
        }
        map.put("shop", template.shopOffers.size());
        map.put("buyback", template.buybackOffers.size());
        map.put("mall", template.mallItems.size());
        map.put("exchange", template.exchangeEntries.size());
        return map;
    }
}
