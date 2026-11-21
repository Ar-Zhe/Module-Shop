package cn.autoforged.module_shop.shop;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.config.ShopTemplate;
import cn.autoforged.module_shop.config.ShopTemplate.ExportSection;
import cn.autoforged.module_shop.config.ShopTemplate.ValidationIssue;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.data.ConfiguredOffer;
import cn.autoforged.module_shop.data.ExchangeEntry;
import cn.autoforged.module_shop.data.MallItem;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.data.SidebarLayout;
import cn.autoforged.module_shop.shop.auth.AdminAuthService;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig;
import cn.autoforged.module_shop.shop.home.AnnouncementConfig;
import cn.autoforged.module_shop.shop.home.AnnouncementEntry;
import cn.autoforged.module_shop.shop.media.ShopMediaStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模板服务：在「世界数据 / 服务器配置文件 / 媒体资产」与「模板容器」之间做完整无损转换。
 *
 * <ul>
 *   <li>始终透传 {@link HolderLookup.Provider}（{@code server.registryAccess()}）读写物品，完整保留 TACZ、饰品等复杂 1.21.1 {@code DataComponents}；</li>
 *   <li>所有池子的查找/去重/合并均使用 {@link ItemStack#isSameItemSameComponents(ItemStack, ItemStack)}，绝不按裸 {@code itemId} 误合并；</li>
 *   <li>玩家余额、购买记录、拍卖、求购、邮件、兑换码、抽奖记录等运行数据**永远不会**被带出；</li>
 *   <li>{@link #initNewWorldIfNeeded} 支持 {@code .mshop / .json.gz / .json}，遇到瞬态 IO 错误不永久跳过初始化。</li>
 * </ul>
 */
public final class TemplateService {
    private static final Logger LOG = LoggerFactory.getLogger("module_shop/template");

    private TemplateService() {
    }

    /** 应用模式。 */
    public enum Mode {
        /** 替换：清空现有选中模块，全部用模板内容。 */
        REPLACE,
        /** 合并：保留现有条目，模板里没有的追加；同物品同组件按模板覆盖。 */
        MERGE
    }

    /** 应用结果（兼容既有调用点）。 */
    public record ApplyResult(int applied, int invalid, int duplicates, int removed, String error) {
        public boolean ok() {
            return error == null || error.isEmpty();
        }

        public String summary() {
            return "applied=" + applied + " invalid=" + invalid + " duplicates=" + duplicates + " removed=" + removed;
        }
    }

    /** 带详细校验问题的应用结果。 */
    public record ApplyDetailedResult(ApplyResult result, List<ValidationIssue> issues) {
    }

    /** 预览结果（兼容既有调用点）。 */
    public record Preview(int total, int valid, int invalid, int conflicts, String name, String createdAt) {
    }

    /** 带模块明细与问题清单的详细预览。 */
    public record PreviewDetailed(Preview preview, EnumSet<ExportSection> includedSections,
                                  List<ValidationIssue> issues, int mediaCount, long mediaBytes, boolean hasSecurity) {
    }

    // ------------------------------------------------------------------ 世界 -> 模板

    /** 从世界数据提取全量默认模板（不带管理员密码锁）。 */
    public static ShopTemplate extract(ShopSavedData data, String name) {
        MinecraftServer server = currentServerSafe();
        return extract(server, data, name, ExportSection.allDefault(false));
    }

    /** 按指定模块勾选集从服务器与世界数据提取完整 v3 模板（含 DataComponents 与媒体字节）。 */
    public static ShopTemplate extract(@Nullable MinecraftServer server, ShopSavedData data, String name,
                                       @Nullable Set<ExportSection> selectedSections) {
        HolderLookup.Provider registries = server != null ? server.registryAccess() : currentRegistryAccessSafe();
        EnumSet<ExportSection> sections = selectedSections == null || selectedSections.isEmpty()
                ? ExportSection.allDefault(false)
                : EnumSet.copyOf(selectedSections);

        ShopTemplate template = new ShopTemplate();
        template.formatVersion = ShopTemplate.FORMAT_VERSION;
        template.modVersion = ShopTemplate.currentModVersion();
        template.createdAt = Instant.now().toString();
        template.name = name == null || name.isBlank() ? "商店配置" : name;
        template.presentSections.addAll(sections);

        if (sections.contains(ExportSection.SHOP)) {
            template.shopSlots = data.shopSlotCount;
            template.shopWeights = copy(data.shopWeights);
            template.shopGuarantee = copy(data.shopGuarantee);
            for (PriceSpec spec : data.dailyRefreshSpecs) {
                if (spec != null) {
                    template.dailyRefreshSpecs.add(spec);
                }
            }
            for (ConfiguredOffer offer : data.shopPool) {
                template.shopOffers.add(ShopTemplate.Offer.of(offer, registries));
            }
        }

        if (sections.contains(ExportSection.BUYBACK)) {
            template.buybackSlots = data.buybackSlotCount;
            template.buybackWeights = copy(data.buybackWeights);
            template.buybackGuarantee = copy(data.buybackGuarantee);
            for (PriceSpec spec : data.buybackRefreshSpecs) {
                if (spec != null) {
                    template.buybackRefreshSpecs.add(spec);
                }
            }
            template.buybackBonusEnabled = data.buybackBonusEnabled;
            template.buybackBonusSpec = data.buybackBonusSpec;
            for (ConfiguredOffer offer : data.buybackPool) {
                template.buybackOffers.add(ShopTemplate.Offer.of(offer, registries));
            }
        }

        if (sections.contains(ExportSection.MALL)) {
            for (MallItem item : data.mallPool) {
                template.mallItems.add(ShopTemplate.MallEntry.of(item, registries));
            }
        }

        if (sections.contains(ExportSection.EXCHANGE)) {
            for (ExchangeEntry entry : data.exchangePool) {
                template.exchangeEntries.add(ShopTemplate.Exchange.of(entry, registries));
            }
        }

        if (sections.contains(ExportSection.SIGNIN)) {
            template.signInEnabled = data.signInEnabled;
            template.signInAuto = data.signInAuto;
            template.signInCumulative = data.signInCumulative;
            var baseSpec = data.resolveSignInBaseSpec();
            var makeupSpec = data.resolveSignInMakeupSpec();
            template.signInBaseSpec = baseSpec;
            template.signInBaseDaily = baseSpec.supportsStar() ? baseSpec.starPrice : 0L;
            template.signInMakeupSpec = makeupSpec;
            template.signInMakeupCost = makeupSpec.supportsStar() ? makeupSpec.starPrice : 0L;
            template.signInMakeupDays = data.signInMakeupDays;
            template.signInTierDays = copy(data.signInTierDays);
            template.signInTierBonus = copy(data.signInTierBonus);
            for (int i = 0; i < data.signInTierBonusSpecs.length; i++) {
                template.signInTierBonusSpecs.add(data.resolveSignInTierSpec(i));
            }
            template.signInMileDays = copy(data.signInMileDays);
            template.signInMileMoney = copy(data.signInMileMoney);
            for (int i = 0; i < data.signInMileSpecs.length; i++) {
                template.signInMileSpecs.add(data.resolveSignInMileSpec(i));
            }
        }

        if (sections.contains(ExportSection.CURRENCY)) {
            template.currency = data.shopCurrency == null ? "" : data.shopCurrency;
            template.physicalCurrency = copyPhysicalCurrencySafe();
        }

        if (sections.contains(ExportSection.SHIPPING)) {
            template.shipping = extractShipping(data);
        }

        Set<String> referencedMediaIds = new LinkedHashSet<>();

        if (sections.contains(ExportSection.LAYOUT)) {
            template.layout = extractLayout(data.sidebarLayout);
            if (!template.layout.brandIconId.isBlank()) {
                referencedMediaIds.add(template.layout.brandIconId);
            }
        }

        if (sections.contains(ExportSection.ANNOUNCEMENTS)) {
            try {
                for (AnnouncementEntry entry : AnnouncementConfig.get().sortedCopy()) {
                    if (entry != null) {
                        AnnouncementEntry copyEntry = entry.copy();
                        template.announcements.add(copyEntry);
                        referencedMediaIds.addAll(copyEntry.referencedMediaIds());
                    }
                }
            } catch (Throwable ex) {
                LOG.warn("[ModuleShop] 导出公告配置失败: {}", ex.toString());
            }
        }

        if (!referencedMediaIds.isEmpty()) {
            try {
                Map<String, byte[]> exported = ShopMediaStore.exportMediaMap(referencedMediaIds);
                if (exported != null && !exported.isEmpty()) {
                    template.embeddedMedia.putAll(exported);
                }
            } catch (Throwable ex) {
                LOG.warn("[ModuleShop] 导出内嵌媒体资源失败: {}", ex.toString());
            }
        }

        if (sections.contains(ExportSection.SECURITY)) {
            try {
                AdminAuthService.ExportedAuth exportedAuth = AdminAuthService.exportForTemplate();
                if (exportedAuth != null) {
                    ShopTemplate.AuthData auth = new ShopTemplate.AuthData();
                    auth.enabled = exportedAuth.enabled();
                    auth.algo = exportedAuth.algo();
                    auth.iterations = exportedAuth.iterations();
                    auth.salt = exportedAuth.salt();
                    auth.hash = exportedAuth.hash();
                    auth.sessionTimeoutMinutes = exportedAuth.sessionTimeoutMinutes();
                    template.adminAuth = auth;
                }
            } catch (Throwable ex) {
                LOG.warn("[ModuleShop] 导出管理员密码配置失败: {}", ex.toString());
            }
        }

        return template;
    }

    /** 导出当前世界模板（兼容入口）。 */
    public static Path export(MinecraftServer server, ShopSavedData data, Path configDir, String name) {
        ShopTemplate template = extract(server, data, name, ExportSection.allDefault(false));
        return ShopTemplateStore.saveExportOrDefault(configDir, template, "moduleshop_shop",
                ShopTemplateStore.TemplateFormat.MSHOP, template.presentSections);
    }

    /** 把当前世界模板写进默认文件 `shop_config.mshop`（或按格式写入）。 */
    public static String saveAsDefault(ShopSavedData data, Path configDir, String name) {
        ShopTemplate template = extract(currentServerSafe(), data, name, ExportSection.allDefault(false));
        return ShopTemplateStore.write(
                ShopTemplateStore.defaultFile(configDir, ShopTemplateStore.TemplateFormat.JSON),
                template, null);
    }

    // ------------------------------------------------------------------ 预览（不改世界数据）

    /** 统计预览信息（兼容旧 2 参入口）。 */
    public static Preview preview(ShopTemplate template, ShopSavedData data) {
        return previewDetailed(template, data, currentRegistryAccessSafe(), null).preview();
    }

    /** 详细预览（检查物品、组件 SNBT、同物品不同组件的精确冲突、媒体字节与包含模块）。 */
    public static PreviewDetailed previewDetailed(ShopTemplate template, ShopSavedData data,
                                                  @Nullable HolderLookup.Provider registries,
                                                  @Nullable Set<ExportSection> selectedSections) {
        if (template == null) {
            return new PreviewDetailed(new Preview(0, 0, 0, 0, "", ""),
                    EnumSet.noneOf(ExportSection.class), List.of(), 0, 0L, false);
        }
        HolderLookup.Provider regs = registries != null ? registries : currentRegistryAccessSafe();
        EnumSet<ExportSection> included = computeEffectiveSections(template, selectedSections);
        List<ValidationIssue> issues = new ArrayList<>();

        int total = 0;
        int valid = 0;
        int invalid = 0;
        int conflicts = 0;

        Set<String> existingShop = offerKeys(data.shopPool, regs);
        Set<String> existingBuyback = offerKeys(data.buybackPool, regs);
        Set<String> existingMall = mallKeys(data.mallPool, regs);
        Set<String> existingExchange = exchangeKeys(data.exchangePool, regs);

        if (included.contains(ExportSection.SHOP)) {
            for (int i = 0; i < template.shopOffers.size(); i++) {
                ShopTemplate.Offer offer = template.shopOffers.get(i);
                total++;
                ItemStack stack = ShopTemplate.stackOf(offer.item, offer.count, offer.componentsSnbt,
                        regs, issues, "shop[" + i + "]");
                if (stack.isEmpty()) {
                    invalid++;
                } else {
                    valid++;
                    String key = stackMatchKey(stack, regs, offer.item, offer.componentsSnbt);
                    if (existingShop.contains(key)) {
                        conflicts++;
                    }
                }
            }
        }
        if (included.contains(ExportSection.BUYBACK)) {
            for (int i = 0; i < template.buybackOffers.size(); i++) {
                ShopTemplate.Offer offer = template.buybackOffers.get(i);
                total++;
                ItemStack stack = ShopTemplate.stackOf(offer.item, offer.count, offer.componentsSnbt,
                        regs, issues, "buyback[" + i + "]");
                if (stack.isEmpty()) {
                    invalid++;
                } else {
                    valid++;
                    String key = stackMatchKey(stack, regs, offer.item, offer.componentsSnbt);
                    if (existingBuyback.contains(key)) {
                        conflicts++;
                    }
                }
            }
        }
        if (included.contains(ExportSection.MALL)) {
            for (int i = 0; i < template.mallItems.size(); i++) {
                ShopTemplate.MallEntry entry = template.mallItems.get(i);
                total++;
                ItemStack stack = ShopTemplate.stackOf(entry.item, entry.count, entry.componentsSnbt,
                        regs, issues, "mall[" + i + "]");
                if (stack.isEmpty()) {
                    invalid++;
                } else {
                    valid++;
                    String key = stackMatchKey(stack, regs, entry.item, entry.componentsSnbt);
                    if (existingMall.contains(key)) {
                        conflicts++;
                    }
                }
            }
        }
        if (included.contains(ExportSection.EXCHANGE)) {
            for (int i = 0; i < template.exchangeEntries.size(); i++) {
                ShopTemplate.Exchange entry = template.exchangeEntries.get(i);
                total++;
                ItemStack stack = ShopTemplate.stackOf(entry.item, entry.count, entry.componentsSnbt,
                        regs, issues, "exchange[" + i + "]");
                if (stack.isEmpty()) {
                    invalid++;
                } else {
                    valid++;
                    String key = stackMatchKey(stack, regs, entry.item, entry.componentsSnbt);
                    if (existingExchange.contains(key)) {
                        conflicts++;
                    }
                }
            }
        }
        if (included.contains(ExportSection.CURRENCY)
                && template.physicalCurrency != null && template.physicalCurrency.tiers != null) {
            for (int i = 0; i < template.physicalCurrency.tiers.size(); i++) {
                var tier = template.physicalCurrency.tiers.get(i);
                if (tier != null) {
                    ShopTemplate.stackOf(tier.itemId, 1, tier.componentsSnbt,
                            regs, issues, "currency_tier[" + i + "]");
                }
            }
        }

        long mediaBytes = 0L;
        for (byte[] bytes : template.embeddedMedia.values()) {
            if (bytes != null) {
                mediaBytes += bytes.length;
            }
        }
        boolean hasSec = template.adminAuth != null && template.adminAuth.enabled;
        return new PreviewDetailed(
                new Preview(total, valid, invalid, conflicts, template.name, template.createdAt),
                included, List.copyOf(issues), template.embeddedMedia.size(), mediaBytes, hasSec);
    }

    // ------------------------------------------------------------------ 模板 -> 世界

    /** 应用模板（兼容 3 参入口）。 */
    public static ApplyResult apply(ShopTemplate template, ShopSavedData data, Mode mode) {
        return applyDetailed(template, data, mode, currentRegistryAccessSafe(), null).result();
    }

    /**
     * 应用模板到世界数据与服务器配置。
     *
     * <p>绝不触碰玩家余额、购买记录、拍卖、求购、邮件、兑换码、抽奖记录等运行态数据。
     * 无效物品单独记录诊断信息并跳过，不破坏其余正常商品。
     */
    public static ApplyDetailedResult applyDetailed(ShopTemplate template, ShopSavedData data, Mode mode,
                                                    @Nullable HolderLookup.Provider registries,
                                                    @Nullable Set<ExportSection> selectedSections) {
        if (template == null || data == null) {
            return new ApplyDetailedResult(new ApplyResult(0, 0, 0, 0, "invalid"), List.of());
        }
        HolderLookup.Provider regs = registries != null ? registries : currentRegistryAccessSafe();
        EnumSet<ExportSection> included = computeEffectiveSections(template, selectedSections);
        List<ValidationIssue> issues = new ArrayList<>();
        boolean replace = mode == Mode.REPLACE;

        int applied = 0;
        int invalid = 0;
        int duplicates = 0;
        int removed = 0;
        int configSectionsApplied = 0;

        // 0. 若包含媒体资产（品牌图标或公告插图），先行导入媒体仓库以免引用悬空
        if ((included.contains(ExportSection.LAYOUT) || included.contains(ExportSection.ANNOUNCEMENTS))
                && !template.embeddedMedia.isEmpty()) {
            try {
                ShopMediaStore.importMediaMap(template.embeddedMedia);
            } catch (Throwable ex) {
                LOG.warn("[ModuleShop] 导入模板内嵌媒体失败: {}", ex.toString());
            }
        }

        // ---- 每日商店 ----
        if (included.contains(ExportSection.SHOP)) {
            if (replace) {
                removed += data.shopPool.size();
                data.shopPool.clear();
            }
            data.shopSlotCount = Math.max(0, Math.min(ShopManager.MAX_DAILY_SLOTS, template.shopSlots));
            copyInto(template.shopWeights, data.shopWeights);
            copyInto(template.shopGuarantee, data.shopGuarantee);
            if (!template.dailyRefreshSpecs.isEmpty() || replace) {
                data.dailyRefreshSpecs.clear();
                data.dailyRefreshSpecs.addAll(template.dailyRefreshSpecs);
            }
            PoolCounts c = applyOffers(template.shopOffers, data.shopPool, replace, regs, issues, "shop");
            applied += c.applied;
            invalid += c.invalid;
            duplicates += c.duplicates;
        }

        // ---- 每日收购 ----
        if (included.contains(ExportSection.BUYBACK)) {
            if (replace) {
                removed += data.buybackPool.size();
                data.buybackPool.clear();
            }
            data.buybackSlotCount = Math.max(0, Math.min(ShopManager.MAX_DAILY_SLOTS, template.buybackSlots));
            copyInto(template.buybackWeights, data.buybackWeights);
            copyInto(template.buybackGuarantee, data.buybackGuarantee);
            if (!template.buybackRefreshSpecs.isEmpty() || replace) {
                data.buybackRefreshSpecs.clear();
                data.buybackRefreshSpecs.addAll(template.buybackRefreshSpecs);
            }
            data.buybackBonusEnabled = template.buybackBonusEnabled;
            if (template.buybackBonusSpec != null) {
                data.setBuybackBonusSpec(template.buybackBonusSpec);
            }
            PoolCounts c = applyOffers(template.buybackOffers, data.buybackPool, replace, regs, issues, "buyback");
            applied += c.applied;
            invalid += c.invalid;
            duplicates += c.duplicates;
        }

        // ---- 购物城 ----
        if (included.contains(ExportSection.MALL)) {
            if (replace) {
                removed += data.mallPool.size();
                data.mallPool.clear();
            }
            Set<String> seenInSource = new HashSet<>();
            for (int i = 0; i < template.mallItems.size(); i++) {
                ShopTemplate.MallEntry entry = template.mallItems.get(i);
                MallItem item = entry.toMallItem(regs, issues, "mall[" + i + "]");
                if (item == null) {
                    invalid++;
                    continue;
                }
                String selfKey = stackMatchKey(item.stack, regs, entry.item, entry.componentsSnbt);
                boolean dupInTemplate = !seenInSource.add(selfKey);
                MallItem existing = findMall(data.mallPool, item.stack, regs);
                if (existing != null) {
                    existing.setPriceSpec(item.resolvePriceSpec());
                    existing.stock = item.stock;
                    existing.stockMax = item.stockMax;
                    existing.sort = item.sort;
                    existing.rarity = item.rarity;
                    existing.stack = item.stack;
                    applied++;
                    if (!replace || dupInTemplate) {
                        duplicates++;
                    }
                    continue;
                }
                data.mallPool.add(item);
                applied++;
            }
        }

        // ---- 货币兑换 ----
        if (included.contains(ExportSection.EXCHANGE)) {
            if (replace) {
                removed += data.exchangePool.size();
                data.exchangePool.clear();
            }
            Set<String> seenInSource = new HashSet<>();
            for (int i = 0; i < template.exchangeEntries.size(); i++) {
                ShopTemplate.Exchange entry = template.exchangeEntries.get(i);
                ExchangeEntry converted = entry.toEntry(regs, issues, "exchange[" + i + "]");
                if (converted == null) {
                    invalid++;
                    continue;
                }
                String selfKey = stackMatchKey(converted.stack, regs, entry.item, entry.componentsSnbt);
                boolean dupInTemplate = !seenInSource.add(selfKey);
                ExchangeEntry existing = findExchange(data.exchangePool, converted.stack, regs);
                if (existing != null) {
                    existing.value = converted.value;
                    existing.stack = converted.stack;
                    applied++;
                    if (!replace || dupInTemplate) {
                        duplicates++;
                    }
                    continue;
                }
                data.exchangePool.add(converted);
                applied++;
            }
        }

        // ---- 签到与活动参数 ----
        if (included.contains(ExportSection.SIGNIN)) {
            data.signInEnabled = template.signInEnabled;
            data.signInAuto = template.signInAuto;
            data.signInCumulative = template.signInCumulative;
            data.setSignInBaseSpec(template.signInBaseSpec != null
                    ? template.signInBaseSpec
                    : PriceSpec.star(Math.max(0L, template.signInBaseDaily)));
            data.setSignInMakeupSpec(template.signInMakeupSpec != null
                    ? template.signInMakeupSpec
                    : PriceSpec.star(Math.max(0L, template.signInMakeupCost)));
            data.signInMakeupDays = Math.max(0, Math.min(30, template.signInMakeupDays));
            copyInto(template.signInTierDays, data.signInTierDays);
            copyInto(template.signInTierBonus, data.signInTierBonus);
            for (int i = 0; i < data.signInTierBonusSpecs.length; i++) {
                PriceSpec spec = i < template.signInTierBonusSpecs.size()
                        ? template.signInTierBonusSpecs.get(i)
                        : PriceSpec.star(i < data.signInTierBonus.length ? Math.max(0L, data.signInTierBonus[i]) : 0L);
                data.setSignInTierSpec(i, spec);
            }
            copyInto(template.signInMileDays, data.signInMileDays);
            copyInto(template.signInMileMoney, data.signInMileMoney);
            for (int i = 0; i < data.signInMileSpecs.length; i++) {
                PriceSpec spec = i < template.signInMileSpecs.size()
                        ? template.signInMileSpecs.get(i)
                        : PriceSpec.star(i < data.signInMileMoney.length ? Math.max(0L, data.signInMileMoney[i]) : 0L);
                data.setSignInMileSpec(i, spec);
            }
            configSectionsApplied++;
        }

        Path configDir = resolveConfigDirSafe();

        // ---- 货币名 & 实体货币 ----
        if (included.contains(ExportSection.CURRENCY)) {
            if (template.currency != null && (!template.currency.isBlank() || replace)) {
                data.shopCurrency = template.currency;
            }
            if (template.physicalCurrency != null && configDir != null) {
                try {
                    PhysicalCurrencyConfig pcCopy = copyPhysicalCurrency(template.physicalCurrency);
                    PhysicalCurrencyConfig.replaceAndSave(configDir, pcCopy);
                    configSectionsApplied++;
                } catch (Throwable ex) {
                    LOG.warn("[ModuleShop] 应用实体货币配置失败: {}", ex.toString());
                }
            }
        }

        // ---- 出货箱规则与等级上限 ----
        if (included.contains(ExportSection.SHIPPING) && template.shipping != null) {
            if (applyShipping(data, template.shipping, replace)) {
                configSectionsApplied++;
            }
        }

        // ---- 侧栏布局与品牌图标 ----
        if (included.contains(ExportSection.LAYOUT) && template.layout != null) {
            applyLayout(data.sidebarLayout, template.layout, replace);
            try {
                ShopManager.saveBrandDefault(data.sidebarLayout);
            } catch (Throwable ignored) {
            }
            configSectionsApplied++;
        }

        // ---- 公告与图文块 ----
        if (included.contains(ExportSection.ANNOUNCEMENTS) && configDir != null) {
            boolean hasAnnPayload = template.presentSections.contains(ExportSection.ANNOUNCEMENTS)
                    || !template.announcements.isEmpty();
            if (hasAnnPayload) {
                try {
                    applyAnnouncements(configDir, template.announcements, replace);
                    configSectionsApplied++;
                } catch (Throwable ex) {
                    LOG.warn("[ModuleShop] 应用公告配置失败: {}", ex.toString());
                }
            }
        }

        // ---- 可选：管理员密码锁（仅目标端未设置密码时安全注入哈希，永不静默覆盖既有密码） ----
        if (included.contains(ExportSection.SECURITY) && template.adminAuth != null) {
            try {
                AdminAuthService.ExportedAuth auth = new AdminAuthService.ExportedAuth(
                        template.adminAuth.enabled,
                        template.adminAuth.algo,
                        template.adminAuth.iterations,
                        template.adminAuth.salt,
                        template.adminAuth.hash,
                        template.adminAuth.sessionTimeoutMinutes
                );
                AdminAuthService.importFromTemplateIfUnconfigured(auth);
                configSectionsApplied++;
            } catch (Throwable ex) {
                LOG.warn("[ModuleShop] 导入管理员密码配置失败: {}", ex.toString());
            }
        }

        data.setDirty();
        int effectiveApplied = applied > 0 ? applied : configSectionsApplied;
        return new ApplyDetailedResult(
                new ApplyResult(effectiveApplied, invalid, duplicates, removed, ""),
                List.copyOf(issues));
    }

    // ------------------------------------------------------------------ 新世界初始化

    /**
     * 新世界首次启动：用默认模板（支持 `.mshop` / `.json.gz` / `.json`）初始化商店。
     *
     * <p>遇到瞬态磁盘 IO 错误（{@code io_error}）时不写入 {@code templateInitDone=true}，以便下次重启重试；
     * 仅在成功导入或明确不存在/格式不可恢复时打标。
     */
    public static void initNewWorldIfNeeded(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            ShopSavedData data = ShopSavedData.get(server);
            Path configDir = resolveConfigDirSafe();
            Path defaultFile = configDir == null ? null : ShopTemplateStore.resolveExistingDefaultFile(configDir);
            if (defaultFile == null && configDir != null) {
                defaultFile = ShopTemplateStore.defaultFile(configDir);
            }
            LOG.info("模板初始化检查：initDone={} seedDefault={} 模板文件={}",
                    data.templateInitDone, data.isSeedDefaultPools(), defaultFile);
            if (data.templateInitDone) {
                return;
            }
            if (!data.isSeedDefaultPools()) {
                data.templateInitDone = true;
                data.setDirty();
                return;
            }
            ShopTemplateStore.ReadDetailedResult result = ShopTemplateStore.readDetailed(defaultFile);
            if (!result.ok()) {
                String err = result.errorCode();
                if ("io_error".equals(err)) {
                    LOG.warn("默认商店模板读取遇到瞬态 IO 错误（{}），本次保留种子数据、下次启动重试",
                            result.errorDetail());
                    return;
                }
                data.templateInitDone = true;
                data.setDirty();
                if (!"missing".equals(err) && !"empty".equals(err)) {
                    LOG.warn("默认商店模板无法使用（{}: {}），新世界使用模组自带默认商品",
                            err, result.errorDetail());
                }
                return;
            }
            ApplyDetailedResult detailed = applyDetailed(
                    result.template(), data, Mode.REPLACE, server.registryAccess(), null);
            data.templateInitDone = true;
            data.setDirty();
            LOG.info("已用默认商店模板初始化新世界：{}（{}，校验问题={}）",
                    result.template().name, detailed.result().summary(), detailed.issues().size());
        } catch (Throwable error) {
            LOG.warn("新世界商店初始化失败：{}", error.toString(), error);
        }
    }

    // ------------------------------------------------------------------ 内部池子与扩展域助手

    private record PoolCounts(int applied, int invalid, int duplicates) {
    }

    private static PoolCounts applyOffers(List<ShopTemplate.Offer> source, List<ConfiguredOffer> target,
                                          boolean replace, @Nullable HolderLookup.Provider regs,
                                          List<ValidationIssue> issues, String domain) {
        int applied = 0;
        int invalid = 0;
        int duplicates = 0;
        Set<String> seenInSource = new HashSet<>();
        for (int i = 0; i < source.size(); i++) {
            ShopTemplate.Offer offer = source.get(i);
            ConfiguredOffer converted = offer.toConfigured(regs, issues, domain + "[" + i + "]");
            if (converted == null) {
                invalid++;
                continue;
            }
            String selfKey = stackMatchKey(converted.stack, regs, offer.item, offer.componentsSnbt);
            boolean dupInTemplate = !seenInSource.add(selfKey);
            ConfiguredOffer existing = findByItem(target, converted.stack, regs);
            if (existing != null) {
                existing.setPriceSpec(converted.resolvePriceSpec());
                existing.rarity = converted.rarity;
                existing.amount = converted.amount;
                existing.stack = converted.stack;
                applied++;
                if (!replace || dupInTemplate) {
                    duplicates++;
                }
                continue;
            }
            target.add(converted);
            applied++;
        }
        return new PoolCounts(applied, invalid, duplicates);
    }

    private static ConfiguredOffer findByItem(List<ConfiguredOffer> pool, ItemStack candidate,
                                              @Nullable HolderLookup.Provider regs) {
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }
        for (ConfiguredOffer offer : pool) {
            if (stacksMatch(offer.stack, candidate, regs)) {
                return offer;
            }
        }
        return null;
    }

    private static MallItem findMall(List<MallItem> pool, ItemStack candidate,
                                     @Nullable HolderLookup.Provider regs) {
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }
        for (MallItem item : pool) {
            if (stacksMatch(item.stack, candidate, regs)) {
                return item;
            }
        }
        return null;
    }

    private static ExchangeEntry findExchange(List<ExchangeEntry> pool, ItemStack candidate,
                                              @Nullable HolderLookup.Provider regs) {
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }
        for (ExchangeEntry entry : pool) {
            if (stacksMatch(entry.stack, candidate, regs)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean stacksMatch(@Nullable ItemStack a, @Nullable ItemStack b,
                                       @Nullable HolderLookup.Provider regs) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (ItemStack.isSameItemSameComponents(a, b)) {
            return true;
        }
        // 当某方在无注册表模式下构造（无组件），回落不可误并：仅完全一致才匹配
        return false;
    }

    private static Set<String> offerKeys(List<ConfiguredOffer> pool, @Nullable HolderLookup.Provider regs) {
        Set<String> out = new HashSet<>();
        for (ConfiguredOffer offer : pool) {
            if (offer.stack != null && !offer.stack.isEmpty()) {
                out.add(stackKey(offer.stack, regs));
            }
        }
        return out;
    }

    private static Set<String> mallKeys(List<MallItem> pool, @Nullable HolderLookup.Provider regs) {
        Set<String> out = new HashSet<>();
        for (MallItem item : pool) {
            if (item.stack != null && !item.stack.isEmpty()) {
                out.add(stackKey(item.stack, regs));
            }
        }
        return out;
    }

    private static Set<String> exchangeKeys(List<ExchangeEntry> pool, @Nullable HolderLookup.Provider regs) {
        Set<String> out = new HashSet<>();
        for (ExchangeEntry entry : pool) {
            if (entry.stack != null && !entry.stack.isEmpty()) {
                out.add(stackKey(entry.stack, regs));
            }
        }
        return out;
    }

    private static String stackKey(ItemStack stack, @Nullable HolderLookup.Provider regs) {
        return ShopTemplate.itemKey(ShopTemplate.itemId(stack), ShopTemplate.extractComponentsSnbt(stack, regs));
    }

    private static String stackMatchKey(ItemStack stack, @Nullable HolderLookup.Provider regs,
                                        String fallbackId, String fallbackSnbt) {
        if (regs != null && !stack.isEmpty()) {
            return stackKey(stack, regs);
        }
        return ShopTemplate.itemKey(fallbackId, fallbackSnbt);
    }

    private static EnumSet<ExportSection> computeEffectiveSections(ShopTemplate template,
                                                                   @Nullable Set<ExportSection> requested) {
        EnumSet<ExportSection> base = !template.presentSections.isEmpty()
                ? EnumSet.copyOf(template.presentSections)
                : ExportSection.allDefault(template.adminAuth != null);
        if (requested != null && !requested.isEmpty()) {
            base.retainAll(requested);
        }
        return base;
    }

    private static ShopTemplate.ShippingData extractShipping(ShopSavedData data) {
        ShopTemplate.ShippingData out = new ShopTemplate.ShippingData();
        try {
            out.rules.addAll(ShippingRules.rawLines());
        } catch (Throwable ex) {
            LOG.warn("[ModuleShop] 读取出货箱规则失败: {}", ex.toString());
        }
        try {
            for (String s : Config.INSTANCE.shippingLevelSlots.get()) {
                if (s != null) {
                    out.levelSlots.add(s);
                }
            }
            for (String s : Config.INSTANCE.shippingUpgradeCosts.get()) {
                if (s != null) {
                    out.upgradeCosts.add(s);
                }
            }
            for (String s : Config.INSTANCE.shippingDailyLimits.get()) {
                if (s != null) {
                    out.dailyLimits.add(s);
                }
            }
            String rt = Config.INSTANCE.shippingResetTime.get();
            out.resetTime = rt == null || rt.isBlank() ? "00:00" : rt;
        } catch (Throwable ex) {
            LOG.warn("[ModuleShop] 读取出货箱 Config 失败: {}", ex.toString());
        }
        for (PriceSpec spec : data.shippingUpgradeSpecs) {
            if (spec != null) {
                out.upgradeSpecs.add(spec);
            }
        }
        return out;
    }

    private static boolean applyShipping(ShopSavedData data, ShopTemplate.ShippingData shipping, boolean replace) {
        try {
            boolean configDirty = false;
            if (!shipping.rules.isEmpty() || replace) {
                List<String> lines = ShippingRules.rawLines();
                lines.clear();
                lines.addAll(shipping.rules);
                ShippingRules.refresh();
                Config.INSTANCE.shippingRules.set(List.copyOf(shipping.rules));
                configDirty = true;
            }
            if (!shipping.levelSlots.isEmpty()) {
                Config.INSTANCE.shippingLevelSlots.set(List.copyOf(shipping.levelSlots));
                configDirty = true;
            }
            if (!shipping.upgradeCosts.isEmpty()) {
                Config.INSTANCE.shippingUpgradeCosts.set(List.copyOf(shipping.upgradeCosts));
                configDirty = true;
            }
            if (!shipping.dailyLimits.isEmpty()) {
                Config.INSTANCE.shippingDailyLimits.set(List.copyOf(shipping.dailyLimits));
                configDirty = true;
            }
            if (shipping.resetTime != null && !shipping.resetTime.isBlank()) {
                Config.INSTANCE.shippingResetTime.set(shipping.resetTime);
                configDirty = true;
            }
            if (configDirty) {
                Config.SPEC.save();
            }
            if (!shipping.upgradeSpecs.isEmpty() || replace) {
                data.shippingUpgradeSpecs.clear();
                data.shippingUpgradeSpecs.addAll(shipping.upgradeSpecs);
            }
            return true;
        } catch (Throwable ex) {
            LOG.warn("[ModuleShop] 应用出货箱配置失败: {}", ex.toString());
            return false;
        }
    }

    private static ShopTemplate.LayoutData extractLayout(SidebarLayout layout) {
        ShopTemplate.LayoutData out = new ShopTemplate.LayoutData();
        out.order.addAll(layout.order());
        out.hidden.addAll(layout.hidden());
        out.names.putAll(layout.names());
        String title = layout.shopTitle();
        out.shopTitle = title == null ? "" : title;
        out.brandIconId = readSidebarBrandIconId(layout);
        return out;
    }

    private static void applyLayout(SidebarLayout target, ShopTemplate.LayoutData src, boolean replace) {
        if (replace) {
            target.clear();
            target.setOrder(src.order);
            target.setHidden(src.hidden);
            for (Map.Entry<String, String> entry : src.names.entrySet()) {
                target.setName(entry.getKey(), entry.getValue());
            }
            target.setShopTitle(src.shopTitle);
            writeSidebarBrandIconId(target, src.brandIconId);
            return;
        }
        if (!src.order.isEmpty()) {
            target.setOrder(src.order);
        }
        if (!src.hidden.isEmpty()) {
            target.setHidden(src.hidden);
        }
        for (Map.Entry<String, String> entry : src.names.entrySet()) {
            target.setName(entry.getKey(), entry.getValue());
        }
        if (src.shopTitle != null && !src.shopTitle.isBlank()) {
            target.setShopTitle(src.shopTitle);
        }
        if (src.brandIconId != null && !src.brandIconId.isBlank()) {
            writeSidebarBrandIconId(target, src.brandIconId);
        }
    }

    private static void applyAnnouncements(Path configDir, List<AnnouncementEntry> incoming, boolean replace) {
        if (replace) {
            AnnouncementConfig.replaceAndSave(configDir, incoming);
            return;
        }
        Map<String, AnnouncementEntry> merged = new LinkedHashMap<>();
        for (AnnouncementEntry oldEntry : AnnouncementConfig.get().sortedCopy()) {
            if (oldEntry != null && oldEntry.id != null && !oldEntry.id.isBlank()) {
                merged.put(oldEntry.id, oldEntry.copy());
            }
        }
        for (AnnouncementEntry next : incoming) {
            if (next != null) {
                AnnouncementEntry copy = next.copy();
                copy.sanitize(merged.size());
                merged.put(copy.id, copy);
            }
        }
        AnnouncementConfig.replaceAndSave(configDir, new ArrayList<>(merged.values()));
    }

    private static @Nullable PhysicalCurrencyConfig copyPhysicalCurrencySafe() {
        try {
            return copyPhysicalCurrency(PhysicalCurrencyConfig.get());
        } catch (Throwable ex) {
            LOG.warn("[ModuleShop] 读取实体货币配置失败: {}", ex.toString());
            return null;
        }
    }

    private static PhysicalCurrencyConfig copyPhysicalCurrency(PhysicalCurrencyConfig src) {
        PhysicalCurrencyConfig copy = new PhysicalCurrencyConfig();
        copy.formatVersion = src.formatVersion;
        copy.enabled = src.enabled;
        copy.blockBuybackAndShipping = src.blockBuybackAndShipping;
        copy.tiers = new ArrayList<>();
        if (src.tiers != null) {
            for (PhysicalCurrencyConfig.CurrencyTier tier : src.tiers) {
                if (tier != null) {
                    copy.tiers.add(tier.copy());
                }
            }
        }
        copy.normalize();
        return copy;
    }

    private static String readSidebarBrandIconId(SidebarLayout layout) {
        String id = layout.brandIconId();
        return id == null ? "" : id;
    }

    private static void writeSidebarBrandIconId(SidebarLayout layout, String iconId) {
        layout.setBrandIconId(iconId == null ? "" : iconId.trim());
    }

    private static @Nullable MinecraftServer currentServerSafe() {
        try {
            return ServerLifecycleHooks.getCurrentServer();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable HolderLookup.Provider currentRegistryAccessSafe() {
        MinecraftServer server = currentServerSafe();
        return server != null ? server.registryAccess() : null;
    }

    private static @Nullable Path resolveConfigDirSafe() {
        try {
            return FMLPaths.CONFIGDIR.get();
        } catch (Throwable ignored) {
            return null;
        }
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
