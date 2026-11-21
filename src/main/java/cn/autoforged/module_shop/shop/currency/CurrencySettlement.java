package cn.autoforged.module_shop.shop.currency;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.data.PlayerAccount;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.PriceSpec.CurrencyMode;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.shop.ShopManager;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.CurrencyTier;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.ResolvedTier;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.PlanResult;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 双独立并行账本结算门面：
 * <ul>
 *   <li>星币账本：仅读写 {@link PlayerAccount#balance} 与系统结算接口；</li>
 *   <li>实体货币账本：仅在玩家背包/邮箱与 {@link PhysicalCurrencyConfig} 阶梯链内部流转，绝不与星币混扣或互折。</li>
 * </ul>
 */
public final class CurrencySettlement {
    private CurrencySettlement() {
    }

    public record PayResult(
            boolean ok,
            String failKey,
            String failFallback,
            CurrencyMode executedMode,
            long starPaid,
            String physTierKey,
            long physPaidCount,
            long physPaidUnits,
            boolean overflowMailed
    ) {
        public static PayResult success(CurrencyMode executedMode, long starPaid, String physTierKey,
                                        long physPaidCount, long physPaidUnits, boolean overflowMailed) {
            return new PayResult(true, "", "", executedMode == null ? CurrencyMode.STAR_ONLY : executedMode,
                    starPaid, physTierKey == null ? "" : physTierKey, physPaidCount, physPaidUnits, overflowMailed);
        }

        public static PayResult fail(String key, String fallback) {
            return new PayResult(false, key, fallback, CurrencyMode.STAR_ONLY, 0L, "", 0L, 0L, false);
        }

        public boolean paidStar() {
            return starPaid > 0L;
        }

        public boolean paidPhys() {
            return physPaidCount > 0L && !physTierKey.isEmpty();
        }
    }

    public record PlanSpecResult(
            boolean ok,
            String failKey,
            String failFallback,
            CurrencyMode resolvedMode,
            long starNeed,
            int tierIndex,
            String physTierKey,
            long physCount,
            long physUnits,
            List<ResolvedTier> resolvedTiers,
            int[] takeCounts,
            int[] changeCounts
    ) {
        public static PlanSpecResult fail(String key, String fallback) {
            return new PlanSpecResult(false, key, fallback, CurrencyMode.STAR_ONLY,
                    0L, -1, "", 0L, 0L, List.of(), new int[0], new int[0]);
        }

        public static PlanSpecResult ok(CurrencyMode resolvedMode, long starNeed, int tierIndex,
                                        String physTierKey, long physCount, long physUnits,
                                        List<ResolvedTier> resolvedTiers, int[] takeCounts, int[] changeCounts) {
            return new PlanSpecResult(true, "", "", resolvedMode, starNeed, tierIndex,
                    physTierKey == null ? "" : physTierKey, physCount, physUnits,
                    resolvedTiers, takeCounts, changeCounts);
        }
    }

    public record PayoutResult(
            boolean ok,
            CurrencyMode executedMode,
            long starCredited,
            long starTreasuryPaid,
            String physTierKey,
            long physPaidCount,
            boolean physMailed,
            boolean moneyMailed
    ) {
        public static PayoutResult empty() {
            return new PayoutResult(true, CurrencyMode.STAR_ONLY, 0L, 0L, "", 0L, false, false);
        }
    }

    private static volatile Predicate<ItemStack> clientBlockedHook;

    public static void setClientBlockedHook(@Nullable Predicate<ItemStack> hook) {
        clientBlockedHook = hook;
    }

    /** 检查物品是否因实体货币防套利锁被禁止通过每日收购/出货箱出售（兼容双端调用）。 */
    public static boolean isBlockingBuybackAndShipping(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return isBlockingBuybackAndShipping(stack, server.registryAccess());
        }
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            Predicate<ItemStack> hook = clientBlockedHook;
            return hook != null && hook.test(stack);
        }
        return false;
    }

    public static boolean isBlockingBuybackAndShipping(@Nullable ItemStack stack,
                                                       @Nullable HolderLookup.Provider regs) {
        if (stack == null || stack.isEmpty() || regs == null) {
            return false;
        }
        return PhysicalCurrencyConfig.get().isBlockingBuybackAndShipping()
                && PhysicalCurrencyEngine.isCurrencyItem(stack, regs);
    }

    /**
     * 当 {@code blockBuybackAndShipping} 开启时，仅拦截未专门配为实体币定价的原版收购/通配出货箱规则；
     * 若 OP 明确给精确物品配置了实体币结算（{@code exactItemRule && spec.supportsPhys()}），允许按 OP 设定执行。
     */
    public static boolean isBlockingBuybackAndShippingForSpec(@Nullable ItemStack stack,
                                                              @Nullable PriceSpec spec,
                                                              @Nullable HolderLookup.Provider regs,
                                                              boolean exactItemRule) {
        if (!isBlockingBuybackAndShipping(stack, regs)) {
            return false;
        }
        return !(exactItemRule && spec != null && spec.supportsPhys());
    }

    // ------------------------------------------------------------------ 预演与双账本原子扣款

    /**
     * 纯数据预演双独立账本扣款（支持无服务端实体环境下测试与复用）：
     * 根据 {@code unitSpec} 模式与 {@code payChannel} 决定单一/组合目标账本并完成各自独立校验。
     */
    public static PlanSpecResult planSpecMatrix(@Nullable PlayerAccount account,
                                                @Nullable PriceSpec unitSpec,
                                                int count,
                                                @Nullable String payChannel,
                                                boolean physActive,
                                                List<String> tierKeys,
                                                long[] tierValues,
                                                int[] owned) {
        if (count <= 0 || unitSpec == null || unitSpec.isZero()) {
            return PlanSpecResult.ok(CurrencyMode.STAR_ONLY, 0L, -1, "", 0L, 0L,
                    List.of(), new int[0], new int[0]);
        }
        PriceSpec total = unitSpec.scale(count);
        if (!total.isValid()) {
            return PlanSpecResult.fail("msg.module_shop.invalid_price", "价格配置无效");
        }
        String ch = payChannel == null ? "" : payChannel.trim().toLowerCase(java.util.Locale.ROOT);
        CurrencyMode targetMode;
        switch (total.mode) {
            case STAR_ONLY -> {
                if ("phys".equals(ch)) {
                    return PlanSpecResult.fail("msg.module_shop.channel_not_supported", "该项不支持实体货币结算");
                }
                targetMode = CurrencyMode.STAR_ONLY;
            }
            case PHYS_ONLY -> {
                if ("star".equals(ch)) {
                    return PlanSpecResult.fail("msg.module_shop.channel_not_supported", "该项仅支持实体货币结算");
                }
                targetMode = CurrencyMode.PHYS_ONLY;
            }
            case BOTH -> targetMode = CurrencyMode.BOTH;
            case EITHER -> {
                if ("star".equals(ch)) {
                    targetMode = CurrencyMode.STAR_ONLY;
                } else if ("phys".equals(ch)) {
                    targetMode = CurrencyMode.PHYS_ONLY;
                } else if (ch.isEmpty()) {
                    boolean starOk = canAffordStar(account, total.starPrice);
                    boolean physOk = planPhysMatrix(total.physTierKey, total.physCount,
                            physActive, tierKeys, tierValues, owned, List.of()).ok();
                    targetMode = (!starOk && physOk) ? CurrencyMode.PHYS_ONLY : CurrencyMode.STAR_ONLY;
                } else {
                    return PlanSpecResult.fail("msg.module_shop.channel_not_supported", "无效的支付账本通道");
                }
            }
            default -> targetMode = CurrencyMode.STAR_ONLY;
        }

        long starNeed = targetMode.includesStar() ? total.starPrice : 0L;
        if (targetMode.includesStar() && !canAffordStar(account, starNeed)) {
            return PlanSpecResult.fail("msg.module_shop.no_money", "星币余额不足");
        }
        if (!targetMode.includesPhys()) {
            return PlanSpecResult.ok(targetMode, starNeed, -1, "", 0L, 0L,
                    List.of(), new int[0], new int[0]);
        }
        PlanSpecResult physPlan = planPhysMatrix(total.physTierKey, total.physCount,
                physActive, tierKeys, tierValues, owned, List.of());
        if (!physPlan.ok()) {
            return physPlan;
        }
        return PlanSpecResult.ok(
                targetMode,
                starNeed,
                physPlan.tierIndex(),
                physPlan.physTierKey(),
                physPlan.physCount(),
                physPlan.physUnits(),
                physPlan.resolvedTiers(),
                physPlan.takeCounts(),
                physPlan.changeCounts());
    }

    public static PlanSpecResult planSpec(@Nullable ServerPlayer player,
                                          @Nullable PlayerAccount account,
                                          @Nullable PriceSpec unitSpec,
                                          int count,
                                          @Nullable String payChannel) {
        if (count <= 0 || unitSpec == null || unitSpec.isZero()) {
            return PlanSpecResult.ok(CurrencyMode.STAR_ONLY, 0L, -1, "", 0L, 0L,
                    List.of(), new int[0], new int[0]);
        }
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        boolean physActive = cfg.isActive() && player != null;
        List<ResolvedTier> tiers = List.of();
        List<String> tierKeys = List.of();
        long[] values = new long[0];
        int[] owned = new int[0];
        String canonicalPhysKey = unitSpec.supportsPhys() ? cfg.canonicalTierKey(unitSpec.physTierKey) : "";
        PriceSpec resolvedSpec = unitSpec.supportsPhys() ? unitSpec.withPhysTierKey(canonicalPhysKey) : unitSpec;
        if (physActive && unitSpec.mode.includesPhys()) {
            HolderLookup.Provider regs = player.level().registryAccess();
            tiers = cfg.resolveTiers(regs);
            tierKeys = new java.util.ArrayList<>(tiers.size());
            values = new long[tiers.size()];
            for (int i = 0; i < tiers.size(); i++) {
                tierKeys.add(tiers.get(i).spec.tierKey());
                values[i] = tiers.get(i).spec.value;
            }
            owned = PhysicalCurrencyEngine.countInventory(player.getInventory(), tiers);
        }
        PlanSpecResult res = planSpecMatrix(account, resolvedSpec, count, payChannel, physActive,
                tierKeys, values, owned);
        if (res.ok() && res.resolvedMode().includesPhys() && !tiers.isEmpty()) {
            return PlanSpecResult.ok(res.resolvedMode(), res.starNeed(), res.tierIndex(),
                    res.physTierKey(), res.physCount(), res.physUnits(), tiers, res.takeCounts(), res.changeCounts());
        }
        return res;
    }

    private static boolean canAffordStar(@Nullable PlayerAccount account, long need) {
        if (need <= 0L) {
            return true;
        }
        return account != null && account.balance >= need;
    }

    private static PlanSpecResult planPhysMatrix(String tierKey, long tierCount, boolean physActive,
                                                 List<String> tierKeys, long[] values, int[] owned,
                                                 List<ResolvedTier> tiers) {
        if (tierCount <= 0L) {
            return PlanSpecResult.ok(CurrencyMode.PHYS_ONLY, 0L, -1, tierKey, 0L, 0L,
                    tiers, new int[0], new int[0]);
        }
        if (!physActive || tierKeys == null || values == null || owned == null) {
            return PlanSpecResult.fail("msg.module_shop.phys_disabled", "实体货币未启用或玩家离线");
        }
        int resolvedIdx = -1;
        for (int i = 0; i < tierKeys.size(); i++) {
            if (tierKeys.get(i).equals(tierKey)) {
                resolvedIdx = i;
                break;
            }
        }
        if (resolvedIdx < 0 || resolvedIdx >= values.length || owned.length < values.length) {
            return PlanSpecResult.fail("msg.module_shop.phys_tier_unavailable", "所需的实体货币档位不可用");
        }
        PlanResult plan = PhysicalCurrencyEngine.planPhysicalTierPay(resolvedIdx, tierCount, values, owned);
        if (!plan.ok()) {
            return switch (plan.failReason()) {
                case "INDIVISIBLE_AMOUNT" -> PlanSpecResult.fail(
                        "msg.module_shop.phys_indivisible",
                        "背包内实体货币无法精确找零该数量");
                case "CHANGE_TOO_MANY_ITEMS" -> PlanSpecResult.fail(
                        "msg.module_shop.phys_change_too_large",
                        "本次交易找零硬币数量过大，已中止以保护背包");
                case "INVALID_TIER", "NO_TIERS" -> PlanSpecResult.fail(
                        "msg.module_shop.phys_tier_unavailable",
                        "所需的实体货币档位不可用");
                default -> PlanSpecResult.fail(
                        "msg.module_shop.phys_no_money",
                        "背包内实体货币不足");
            };
        }
        return PlanSpecResult.ok(
                CurrencyMode.PHYS_ONLY,
                0L,
                resolvedIdx,
                tierKey,
                tierCount,
                plan.physicalPaid(),
                tiers,
                plan.takeCounts(),
                plan.changeCounts());
    }

    public static PayResult paySpec(@Nullable ServerPlayer player,
                                    @Nullable PlayerAccount account,
                                    @Nullable ShopSavedData data,
                                    @Nullable PriceSpec unitSpec,
                                    int count,
                                    @Nullable String payChannel,
                                    String reason) {
        return paySpec(player, account, data, unitSpec, count, payChannel, reason, "phys_change", false);
    }

    public static PayResult paySpec(@Nullable ServerPlayer player,
                                    @Nullable PlayerAccount account,
                                    @Nullable ShopSavedData data,
                                    @Nullable PriceSpec unitSpec,
                                    int count,
                                    @Nullable String payChannel,
                                    String reason,
                                    String changeSource,
                                    boolean unusedTreasuryFlag) {
        PlanSpecResult plan = planSpec(player, account, unitSpec, count, payChannel);
        if (!plan.ok()) {
            return PayResult.fail(plan.failKey(), plan.failFallback());
        }
        if (plan.starNeed() > 0L) {
            if (ShopManager.debit(account, plan.starNeed(), reason) < 0L) {
                return PayResult.fail("msg.module_shop.no_money", "星币余额不足");
            }
        }
        boolean mailed = false;
        if (plan.physCount() > 0L && player != null) {
            mailed = PhysicalCurrencyEngine.applyInventoryDelta(
                    player,
                    plan.resolvedTiers(),
                    plan.takeCounts(),
                    plan.changeCounts(),
                    changeSource == null || changeSource.isBlank() ? "phys_change" : changeSource);
            if (mailed) {
                ShopManager.notifyPlayer(player, Component.translatable("msg.module_shop.inventory_full_mail")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
        return PayResult.success(
                plan.resolvedMode(),
                plan.starNeed(),
                plan.physTierKey(),
                plan.physCount(),
                plan.physUnits(),
                mailed);
    }

    public static PayResult payStar(@Nullable PlayerAccount account, long amount, String reason) {
        if (amount <= 0L) {
            return PayResult.success(CurrencyMode.STAR_ONLY, 0L, "", 0L, 0L, false);
        }
        if (ShopManager.debit(account, amount, reason) < 0L) {
            return PayResult.fail("msg.module_shop.no_money", "星币余额不足");
        }
        return PayResult.success(CurrencyMode.STAR_ONLY, amount, "", 0L, 0L, false);
    }

    public static PayResult payPhysTier(ServerPlayer player, String tierKey, long count, String changeSource) {
        return paySpec(player, null, null, PriceSpec.phys(tierKey, count), 1,
                "phys", "phys", changeSource, false);
    }

    public static void notifyPayFailure(ServerPlayer player, PayResult res) {
        if (player == null || res == null || res.ok()) {
            return;
        }
        if ("msg.module_shop.no_money".equals(res.failKey())) {
            ShopManager.notifyPlayer(player, Component.translatable("msg.module_shop.no_money")
                    .withStyle(ChatFormatting.RED));
        } else {
            ShopManager.notifyPlayer(player, Component.translatableWithFallback(res.failKey(), res.failFallback())
                    .withStyle(ChatFormatting.RED));
        }
    }

    // ------------------------------------------------------------------ 双独立账本发放 / 退款

    public static long payoutStarOnly(@Nullable MinecraftServer server,
                                      @Nullable UUID targetId,
                                      @Nullable PlayerAccount account,
                                      @Nullable ShopSavedData data,
                                      long amount,
                                      boolean unusedTreasuryFlag,
                                      boolean overflowToMoneyMail,
                                      String mailSource,
                                      @Nullable String senderName,
                                      @Nullable ItemStack mailDisplay) {
        if (amount <= 0L || account == null) {
            return 0L;
        }
        long max = Config.maxBalanceLimit();
        if (!overflowToMoneyMail) {
            long room = Math.max(0L, max - account.balance);
            long want = Math.min(amount, room);
            if (want <= 0L) {
                return 0L;
            }
            return ShopManager.credit(account, want, max);
        }

        long credited = ShopManager.credit(account, amount, max);
        if (credited < amount && server != null && targetId != null) {
            ShopManager.addMoneyMail(server, targetId, amount - credited,
                    mailDisplay == null ? ItemStack.EMPTY : mailDisplay.copy(),
                    mailSource, senderName == null ? "" : senderName, null);
            return amount;
        }
        return credited;
    }

    public static boolean payoutPhysTier(@Nullable MinecraftServer server,
                                         @Nullable UUID targetId,
                                         @Nullable String tierKey,
                                         long count,
                                         String mailSource) {
        if (server == null || targetId == null || tierKey == null || tierKey.isBlank() || count <= 0L) {
            return false;
        }
        return PhysicalCurrencyEngine.deliverPhysicalTierAmount(server, targetId, tierKey, count, mailSource);
    }

    public static PayoutResult payoutSpec(@Nullable MinecraftServer server,
                                          @Nullable UUID targetId,
                                          @Nullable PlayerAccount targetAccount,
                                          @Nullable ShopSavedData data,
                                          @Nullable PriceSpec unitSpec,
                                          int count,
                                          @Nullable String payChannel,
                                          String mailSource,
                                          @Nullable String senderName,
                                          @Nullable ItemStack mailDisplay,
                                          boolean overflowToMoneyMail,
                                          boolean unusedTreasuryFlag) {
        if (count <= 0 || unitSpec == null || unitSpec.isZero()) {
            return PayoutResult.empty();
        }
        PriceSpec total = unitSpec.scale(count);
        if (!total.isValid()) {
            return PayoutResult.empty();
        }
        String ch = payChannel == null ? "" : payChannel.trim().toLowerCase(java.util.Locale.ROOT);
        CurrencyMode execMode;
        if (total.mode == CurrencyMode.EITHER) {
            execMode = "phys".equals(ch) ? CurrencyMode.PHYS_ONLY : CurrencyMode.STAR_ONLY;
        } else {
            execMode = total.mode;
        }

        long starCredited = 0L;
        boolean moneyMailed = false;
        if (execMode.includesStar() && total.starPrice > 0L && targetAccount != null) {
            long max = Config.maxBalanceLimit();
            if (!overflowToMoneyMail) {
                long room = Math.max(0L, max - targetAccount.balance);
                long want = Math.min(total.starPrice, room);
                if (want > 0L) {
                    starCredited = ShopManager.credit(targetAccount, want, max);
                }
            } else {
                long got = ShopManager.credit(targetAccount, total.starPrice, max);
                if (got < total.starPrice && server != null && targetId != null) {
                    ShopManager.addMoneyMail(server, targetId, total.starPrice - got,
                            mailDisplay == null ? ItemStack.EMPTY : mailDisplay.copy(),
                            mailSource, senderName == null ? "" : senderName, null);
                    starCredited = total.starPrice;
                    moneyMailed = true;
                } else {
                    starCredited = got;
                }
            }
        }

        long physPaid = 0L;
        boolean physMailed = false;
        String tierKey = "";
        if (execMode.includesPhys() && total.supportsPhys() && server != null && targetId != null) {
            tierKey = total.physTierKey;
            physMailed = payoutPhysTier(server, targetId, tierKey, total.physCount, mailSource);
            physPaid = total.physCount;
        }

        return new PayoutResult(true, execMode, starCredited, 0L,
                tierKey, physPaid, physMailed, moneyMailed);
    }

    // ------------------------------------------------------------------ 文本格式化与快照同步

    public static String tierDisplayName(@Nullable String tierKey, @Nullable MinecraftServer server) {
        if (tierKey == null || tierKey.isBlank()) {
            return "";
        }
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        CurrencyTier tier = cfg.findTier(tierKey);
        if (tier != null) {
            if (tier.label != null && !tier.label.isBlank()) {
                return tier.label;
            }
            if (server != null) {
                ItemStack stack = PhysicalCurrencyConfig.buildTemplate(tier, server.registryAccess());
                if (!stack.isEmpty()) {
                    return stack.getHoverName().getString();
                }
            }
            return tier.itemId;
        }
        return tierKey;
    }

    public static String formatSpec(@Nullable PriceSpec spec, @Nullable MinecraftServer server) {
        if (spec == null || spec.isZero()) {
            return "0 " + ShopManager.currencyName(server);
        }
        String starName = ShopManager.currencyName(server);
        String tierName = tierDisplayName(spec.physTierKey, server);
        return switch (spec.mode) {
            case STAR_ONLY -> spec.starPrice + " " + starName;
            case PHYS_ONLY -> spec.physCount + " " + tierName;
            case EITHER -> spec.starPrice + " " + starName + " / " + spec.physCount + " " + tierName;
            case BOTH -> spec.starPrice + " " + starName + " + " + spec.physCount + " " + tierName;
        };
    }

    public static void writeSnapshot(@Nullable ServerPlayer player, CompoundTag tag, HolderLookup.Provider regs) {
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        List<ResolvedTier> displayTiers = cfg.resolveAllForDisplay(regs);

        int[] owned = new int[displayTiers.size()];
        if (player != null && !displayTiers.isEmpty()) {
            var inv = player.getInventory();
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                if (slot >= 36 && slot < 40) {
                    continue;
                }
                ItemStack st = inv.getItem(slot);
                if (st.isEmpty()) {
                    continue;
                }
                for (int t = 0; t < displayTiers.size(); t++) {
                    ResolvedTier rt = displayTiers.get(t);
                    if (!rt.template.isEmpty() && PhysicalCurrencyEngine.matchesTier(st, rt.template)) {
                        owned[t] += st.getCount();
                        break;
                    }
                }
            }
        }

        tag.putBoolean("physEnabled", cfg.isActive());
        tag.putBoolean("physBlockBuyback", cfg.blockBuybackAndShipping);
        tag.putInt("physMaxConvertItems", cfg.effectiveMaxConvertOutputItems());
        tag.putLong("physDailyConvertLimit", Math.max(0L, cfg.dailyConvertValueLimit));
        if (player != null) {
            long day = cn.autoforged.module_shop.Config.shippingQuotaDay();
            tag.putLong("physDailyConvertUsed", ShopManager.account(player).physConvertUsedFor(day));
        }

        ListTag tierList = new ListTag();
        for (int i = 0; i < displayTiers.size(); i++) {
            ResolvedTier rt = displayTiers.get(i);
            CompoundTag c = new CompoundTag();
            String itemId = rt.spec.itemId == null ? "" : rt.spec.itemId;
            if (itemId.isEmpty() && !rt.template.isEmpty()) {
                ResourceLocation rl = BuiltInRegistries.ITEM.getKey(rt.template.getItem());
                itemId = rl.toString();
            }
            c.putString("id", rt.spec.id == null ? "" : rt.spec.id);
            c.putString("tierKey", rt.spec.tierKey());
            c.putString("itemId", itemId);
            CompoundTag itemTag = new CompoundTag();
            if (!rt.template.isEmpty()) {
                net.minecraft.nbt.Tag encoded = rt.template.copyWithCount(1).saveOptional(regs);
                if (encoded instanceof CompoundTag ct) {
                    itemTag = ct;
                }
            }
            c.put("itemStack", itemTag);
            c.putString("componentsSnbt", rt.spec.componentsSnbt == null ? "" : rt.spec.componentsSnbt);
            c.putInt("stepRatio", rt.spec.stepRatio);
            c.putLong("value", rt.spec.value);
            c.putString("label", rt.spec.label == null ? "" : rt.spec.label);
            c.putInt("ownedCount", owned[i]);
            tierList.add(c);
        }
        tag.put("physTiers", tierList);
    }
}
