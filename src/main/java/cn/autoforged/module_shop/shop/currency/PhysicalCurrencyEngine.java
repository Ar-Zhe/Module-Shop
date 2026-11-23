package cn.autoforged.module_shop.shop.currency;

import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.shop.ShopManager;
import cn.autoforged.module_shop.shop.TransactionLog;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.CurrencyTier;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.ResolvedTier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 分层实体货币数学求解与背包/邮箱原子执行引擎（严格仅限实体货币账本内部，绝不触碰虚拟星币）。
 *
 * <p>纯算法核心方法不依赖运行时 Minecraft 注册表，可直接在 JUnit 5 中验证全部找零、兑换与链内防刷不变量。
 */
public final class PhysicalCurrencyEngine {
    /** 单次找零/发放/兑换允许生成的最大实体货币总件数（防极差配置导致的邮箱/背包爆炸）。 */
    public static final int DEFAULT_MAX_CHANGE_ITEMS = 36 * 64;

    private PhysicalCurrencyEngine() {
    }

    public record PlanResult(
            boolean ok,
            String failReason,
            long physicalPaid,
            long virtualDeduct,
            int[] takeCounts,
            int[] changeCounts
    ) {
        public static PlanResult fail(String reason, int tierCount) {
            return new PlanResult(false, reason == null ? "" : reason, 0L, 0L,
                    new int[tierCount], new int[tierCount]);
        }

        public static PlanResult ok(long physicalPaid, long virtualDeduct, int[] takeCounts, int[] changeCounts) {
            return new PlanResult(true, "", physicalPaid, virtualDeduct, takeCounts, changeCounts);
        }
    }

    public record PayoutPlan(int[] counts, long remainder) {
    }

    public record ConvertPlan(
            boolean ok,
            String failReason,
            int takeTier,
            int takeCount,
            int giveTier,
            int giveCount
    ) {
        public static ConvertPlan fail(String reason) {
            return new ConvertPlan(false, reason, -1, 0, -1, 0);
        }
    }

    private record PhysicalPayAttempt(boolean ok, String reason, int[] take, int[] change) {
    }

    // =========================================================== 纯算法层（单测直调）

    public static long totalPhysicalValue(long[] values, int[] owned) {
        if (values == null || owned == null) {
            return 0L;
        }
        int n = Math.min(values.length, owned.length);
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            long v = values[i];
            int c = owned[i];
            if (v <= 0L || c <= 0) {
                continue;
            }
            long term = c > Long.MAX_VALUE / v ? Long.MAX_VALUE : v * (long) c;
            sum = term > Long.MAX_VALUE - sum ? Long.MAX_VALUE : sum + term;
        }
        return sum;
    }

    /** 按目标实体等值总量规划纯实体货币扣减与找零（绝不动用虚拟星币）。 */
    public static PlanResult planPhysicalPay(long targetUnits, long[] values, int[] owned) {
        return planPhysicalPay(targetUnits, values, owned, DEFAULT_MAX_CHANGE_ITEMS);
    }

    public static PlanResult planPhysicalPay(long targetUnits, long[] values, int[] owned, int maxChangeItems) {
        int n = values == null ? 0 : values.length;
        if (targetUnits <= 0L) {
            return PlanResult.ok(0L, 0L, new int[n], new int[n]);
        }
        if (n == 0 || owned == null || owned.length < n) {
            return PlanResult.fail("NO_TIERS", n);
        }
        long totalPhys = totalPhysicalValue(values, owned);
        if (totalPhys < targetUnits) {
            return PlanResult.fail("INSUFFICIENT_FUNDS", n);
        }
        PhysicalPayAttempt attempt = solveExactPhysicalPay(targetUnits, values, owned, maxChangeItems);
        if (!attempt.ok) {
            return PlanResult.fail(attempt.reason, n);
        }
        return PlanResult.ok(targetUnits, 0L, attempt.take, attempt.change);
    }

    /**
     * 按指定目标档位 {@code targetTierIdx × tierCount} 规划实体账本支付：
     * <ul>
     *   <li>若背包持有足够的目标档位硬币，优先精确直扣该档位（零找零）；</li>
     *   <li>否则在实体阶梯链内部自动破整找零 / 凑整补齐（如 1 金币买 3 铁币 -> 扣 1 金找 7 铁）。</li>
     * </ul>
     */
    public static PlanResult planPhysicalTierPay(int targetTierIdx, long tierCount, long[] values, int[] owned) {
        return planPhysicalTierPay(targetTierIdx, tierCount, values, owned, DEFAULT_MAX_CHANGE_ITEMS);
    }

    public static PlanResult planPhysicalTierPay(int targetTierIdx, long tierCount, long[] values, int[] owned,
                                                 int maxChangeItems) {
        int n = values == null ? 0 : values.length;
        if (tierCount <= 0L) {
            return PlanResult.ok(0L, 0L, new int[n], new int[n]);
        }
        if (n == 0 || owned == null || owned.length < n || targetTierIdx < 0 || targetTierIdx >= n) {
            return PlanResult.fail("INVALID_TIER", n);
        }
        long unitVal = values[targetTierIdx];
        if (unitVal <= 0L) {
            return PlanResult.fail("INVALID_TIER", n);
        }
        if (tierCount > Long.MAX_VALUE / unitVal) {
            return PlanResult.fail("INSUFFICIENT_FUNDS", n);
        }
        long targetUnits = tierCount * unitVal;
        if (tierCount <= owned[targetTierIdx] && tierCount <= Integer.MAX_VALUE) {
            int[] take = new int[n];
            take[targetTierIdx] = (int) tierCount;
            return PlanResult.ok(targetUnits, 0L, take, new int[n]);
        }
        return planPhysicalPay(targetUnits, values, owned, maxChangeItems);
    }

    /**
     * 计算玩家当前背包在实体货币链内按目标档位 {@code targetTierIdx} 可精确支付的最大枚数：
     * 先按总面值上界尝试，若非倍比链存在找零限制则向下回退 + 二分调用 {@link #planPhysicalTierPay} 精确求解。
     */
    public static long maxPayableTierCount(int targetTierIdx, long[] values, int[] owned) {
        return maxPayableTierCount(targetTierIdx, values, owned, DEFAULT_MAX_CHANGE_ITEMS);
    }

    public static long maxPayableTierCount(int targetTierIdx, long[] values, int[] owned, int maxChangeItems) {
        int n = values == null ? 0 : values.length;
        if (n == 0 || owned == null || owned.length < n || targetTierIdx < 0 || targetTierIdx >= n) {
            return 0L;
        }
        long unitVal = values[targetTierIdx];
        if (unitVal <= 0L) {
            return 0L;
        }
        long totalVal = totalPhysicalValue(values, owned);
        long upper = totalVal / unitVal;
        if (upper <= 0L) {
            return 0L;
        }
        if (planPhysicalTierPay(targetTierIdx, upper, values, owned, maxChangeItems).ok()) {
            return upper;
        }
        long lo = Math.min(upper, Math.max(0L, (long) owned[targetTierIdx]));
        long scanMin = Math.max(lo + 1L, upper - 16L);
        for (long candidate = upper - 1L; candidate >= scanMin; candidate--) {
            if (planPhysicalTierPay(targetTierIdx, candidate, values, owned, maxChangeItems).ok()) {
                return candidate;
            }
        }
        long best = lo;
        long left = lo + 1L;
        long right = scanMin - 1L;
        while (left <= right) {
            long mid = left + ((right - left) >>> 1);
            if (planPhysicalTierPay(targetTierIdx, mid, values, owned, maxChangeItems).ok()) {
                best = mid;
                left = mid + 1L;
            } else {
                right = mid - 1L;
            }
        }
        return best;
    }

    /**
     * 按指定目标档位规划实体货币发放：
     * 优先按标定档位 {@code targetTierIdx × tierCount} 直接发放；若超过最大物品件数上限则自动向高阶压缩。
     */
    public static PayoutPlan planTierPayout(int targetTierIdx, long tierCount, long[] values) {
        return planTierPayout(targetTierIdx, tierCount, values, DEFAULT_MAX_CHANGE_ITEMS);
    }

    public static PayoutPlan planTierPayout(int targetTierIdx, long tierCount, long[] values, int maxOutputItems) {
        int n = values == null ? 0 : values.length;
        if (tierCount <= 0L || n == 0 || targetTierIdx < 0 || targetTierIdx >= n) {
            return new PayoutPlan(new int[n], Math.max(0L, tierCount));
        }
        if (tierCount <= maxOutputItems && tierCount <= Integer.MAX_VALUE) {
            int[] counts = new int[n];
            counts[targetTierIdx] = (int) tierCount;
            return new PayoutPlan(counts, 0L);
        }
        long unitVal = values[targetTierIdx];
        if (unitVal <= 0L || tierCount > Long.MAX_VALUE / unitVal) {
            return new PayoutPlan(new int[n], tierCount);
        }
        long totalUnits = tierCount * unitVal;
        PayoutPlan greedy = decomposePayout(totalUnits, values);
        if (greedy.remainder == 0L && totalItems(greedy.counts) <= maxOutputItems) {
            return greedy;
        }
        int[] exact = new int[n];
        if (decomposeExact(totalUnits, values, exact) && totalItems(exact) <= maxOutputItems) {
            return new PayoutPlan(exact, 0L);
        }
        return greedy;
    }

    /** 链内等值防套利检查：若所付实体总链值小于买得币面值，则判定存在低买高套利。 */
    public static boolean isChainArbitrage(long[] values, int boughtTierIdx, long boughtCount,
                                           int payTierIdx, long payCount) {
        if (values == null || boughtTierIdx < 0 || boughtTierIdx >= values.length
                || payTierIdx < 0 || payTierIdx >= values.length || boughtCount <= 0L) {
            return false;
        }
        long boughtUnit = values[boughtTierIdx];
        long payUnit = values[payTierIdx];
        if (boughtUnit <= 0L) {
            return false;
        }
        if (payUnit <= 0L || payCount <= 0L) {
            return true;
        }
        long boughtTotal = boughtCount > Long.MAX_VALUE / boughtUnit ? Long.MAX_VALUE : boughtCount * boughtUnit;
        long payTotal = payCount > Long.MAX_VALUE / payUnit ? Long.MAX_VALUE : payCount * payUnit;
        return payTotal < boughtTotal;
    }

    private static PhysicalPayAttempt solveExactPhysicalPay(long targetPay, long[] values, int[] owned,
                                                            int maxChangeItems) {
        int n = values.length;
        if (targetPay <= 0L) {
            return new PhysicalPayAttempt(true, "", new int[n], new int[n]);
        }
        int[] baseTake = new int[n];
        long remaining = targetPay;
        for (int i = n - 1; i >= 0 && remaining > 0L; i--) {
            if (owned[i] <= 0 || values[i] <= 0L) {
                continue;
            }
            long fit = remaining / values[i];
            int use = (int) Math.min(owned[i], fit);
            if (use > 0) {
                baseTake[i] = use;
                remaining -= (long) use * values[i];
            }
        }
        if (remaining == 0L) {
            return new PhysicalPayAttempt(true, "", baseTake, new int[n]);
        }

        // 先尝试按小到大补用少量未使用硬币凑出可精确找零的超额部分（兼容如 [2, 3] 非倍比找零）
        String lastErr = "INDIVISIBLE_AMOUNT";
        for (int j = 0; j < n; j++) {
            int avail = owned[j] - baseTake[j];
            if (avail <= 0 || values[j] <= 0L) {
                continue;
            }
            long needLong = values[j] >= remaining ? 1L : (remaining + values[j] - 1L) / values[j];
            if (needLong > avail || needLong > Integer.MAX_VALUE) {
                continue;
            }
            int minExtra = (int) needLong;
            int maxExtra = Math.min(avail, minExtra + 3);
            for (int extra = minExtra; extra <= maxExtra; extra++) {
                if ((long) extra > Long.MAX_VALUE / values[j]) {
                    break;
                }
                int[] take = Arrays.copyOf(baseTake, n);
                take[j] += extra;
                long overshoot = (long) extra * values[j] - remaining;
                int[] change = new int[n];
                if (!decomposeExact(overshoot, values, change)) {
                    lastErr = "INDIVISIBLE_AMOUNT";
                    continue;
                }
                cancelOverlap(take, change);
                if (totalItems(change) > maxChangeItems) {
                    lastErr = "CHANGE_TOO_MANY_ITEMS";
                    continue;
                }
                return new PhysicalPayAttempt(true, "", take, change);
            }
        }

        // 兜底：若需要组合多枚更小的剩余硬币凑够 >= targetPay（如非倍数特殊配置）
        int[] take = Arrays.copyOf(baseTake, n);
        long rem = remaining;
        for (int j = n - 1; j >= 0 && rem > 0L; j--) {
            int left = owned[j] - take[j];
            if (left <= 0 || values[j] <= 0L) {
                continue;
            }
            long need = (rem + values[j] - 1L) / values[j];
            int add = (int) Math.min(left, need);
            if (add > 0) {
                if ((long) add > Long.MAX_VALUE / values[j]) {
                    return new PhysicalPayAttempt(false, "INSUFFICIENT_FUNDS", new int[n], new int[n]);
                }
                take[j] += add;
                rem -= (long) add * values[j];
            }
        }
        if (rem > 0L) {
            return new PhysicalPayAttempt(false, "INSUFFICIENT_FUNDS", new int[n], new int[n]);
        }
        int[] change = new int[n];
        long overshoot = -rem;
        if (!decomposeExact(overshoot, values, change)) {
            return new PhysicalPayAttempt(false, lastErr, new int[n], new int[n]);
        }
        cancelOverlap(take, change);
        if (totalItems(change) > maxChangeItems) {
            return new PhysicalPayAttempt(false, "CHANGE_TOO_MANY_ITEMS", new int[n], new int[n]);
        }
        return new PhysicalPayAttempt(true, "", take, change);
    }

    /** 精确找零拆分：优先高位贪心；若配置含非倍比硬币（如 2, 3 凑 4）回溯搜索少量节点保证成功。 */
    public static boolean decomposeExact(long amount, long[] values, int[] out) {
        if (amount == 0L) {
            return true;
        }
        if (amount < 0L || values == null || values.length == 0 || out == null || out.length < values.length) {
            return false;
        }
        Arrays.fill(out, 0);
        long rem = amount;
        for (int i = values.length - 1; i >= 0 && rem > 0L; i--) {
            long v = values[i];
            if (v <= 0L || v > rem) {
                continue;
            }
            long q = rem / v;
            if (q > Integer.MAX_VALUE) {
                return false;
            }
            out[i] = (int) q;
            rem -= q * v;
        }
        if (rem == 0L) {
            return true;
        }
        Arrays.fill(out, 0);
        int[] budget = {256};
        return decomposeBacktrack(amount, values, values.length - 1, out, budget);
    }

    private static boolean decomposeBacktrack(long rem, long[] values, int idx, int[] out, int[] budget) {
        if (rem == 0L) {
            return true;
        }
        if (idx < 0 || rem < 0L || budget[0]-- <= 0) {
            return false;
        }
        long v = values[idx];
        if (v <= 0L) {
            return decomposeBacktrack(rem, values, idx - 1, out, budget);
        }
        long maxQ = Math.min(Integer.MAX_VALUE, rem / v);
        long minTry = Math.max(0L, maxQ - 8L);
        for (long q = maxQ; q >= minTry; q--) {
            out[idx] = (int) q;
            if (decomposeBacktrack(rem - q * v, values, idx - 1, out, budget)) {
                return true;
            }
        }
        out[idx] = 0;
        return false;
    }

    public static PayoutPlan decomposePayout(long amount, long[] values) {
        int n = values == null ? 0 : values.length;
        int[] counts = new int[n];
        if (amount <= 0L || n == 0) {
            return new PayoutPlan(counts, Math.max(0L, amount));
        }
        long left = amount;
        for (int i = n - 1; i >= 0 && left > 0L; i--) {
            long v = values[i];
            if (v <= 0L || v > left) {
                continue;
            }
            long q = Math.min((long) Integer.MAX_VALUE - counts[i], left / v);
            if (q > 0L) {
                counts[i] += (int) q;
                left -= q * v;
            }
        }
        return new PayoutPlan(counts, left);
    }

    public static ConvertPlan planConversion(PhysicalCurrencyConfig config, int tierIndex,
                                             String direction, int batches, int[] owned) {
        int maxOut = config == null ? DEFAULT_MAX_CHANGE_ITEMS : config.effectiveMaxConvertOutputItems();
        return planConversion(config, tierIndex, direction, batches, owned, maxOut);
    }

    public static ConvertPlan planConversion(PhysicalCurrencyConfig config, int tierIndex,
                                             String direction, int batches, int[] owned, int maxOutputItems) {
        if (config == null || !config.isActive()) {
            return ConvertPlan.fail("DISABLED");
        }
        int effectiveMax = maxOutputItems <= 0 ? DEFAULT_MAX_CHANGE_ITEMS : Math.max(1, Math.min(64000, maxOutputItems));
        List<CurrencyTier> tiers = config.tiers;
        if (tierIndex < 1 || tierIndex >= tiers.size()) {
            return ConvertPlan.fail("INVALID_TIER");
        }
        if (batches <= 0) {
            return ConvertPlan.fail("INVALID_BATCHES");
        }
        CurrencyTier lower = tiers.get(tierIndex - 1);
        CurrencyTier upper = tiers.get(tierIndex);
        if (lower.value <= 0L || upper.value <= lower.value || upper.stepRatio <= 1) {
            return ConvertPlan.fail("INVALID_RATIO");
        }
        try {
            if (Math.multiplyExact(lower.value, (long) upper.stepRatio) != upper.value) {
                return ConvertPlan.fail("INVALID_RATIO");
            }
        } catch (ArithmeticException ex) {
            return ConvertPlan.fail("INVALID_RATIO");
        }
        if (owned == null || owned.length <= tierIndex) {
            return ConvertPlan.fail("INVALID_INVENTORY");
        }

        if ("up".equalsIgnoreCase(direction)) {
            long needLower;
            try {
                needLower = Math.multiplyExact((long) batches, (long) upper.stepRatio);
            } catch (ArithmeticException ex) {
                return ConvertPlan.fail("TOO_LARGE");
            }
            if (needLower > Integer.MAX_VALUE) {
                return ConvertPlan.fail("TOO_LARGE");
            }
            if (batches > effectiveMax) {
                return ConvertPlan.fail("TOO_MANY_OUTPUT_ITEMS");
            }
            if (owned[tierIndex - 1] < needLower) {
                return ConvertPlan.fail("NOT_ENOUGH_ITEMS");
            }
            return new ConvertPlan(true, "", tierIndex - 1, (int) needLower, tierIndex, batches);
        }
        if ("down".equalsIgnoreCase(direction)) {
            long giveLower;
            try {
                giveLower = Math.multiplyExact((long) batches, (long) upper.stepRatio);
            } catch (ArithmeticException ex) {
                return ConvertPlan.fail("TOO_LARGE");
            }
            if (giveLower > Integer.MAX_VALUE || giveLower > effectiveMax) {
                return ConvertPlan.fail("TOO_MANY_OUTPUT_ITEMS");
            }
            if (owned[tierIndex] < batches) {
                return ConvertPlan.fail("NOT_ENOUGH_ITEMS");
            }
            return new ConvertPlan(true, "", tierIndex, batches, tierIndex - 1, (int) giveLower);
        }
        return ConvertPlan.fail("INVALID_DIRECTION");
    }

    private static void cancelOverlap(int[] take, int[] change) {
        int n = Math.min(take.length, change.length);
        for (int i = 0; i < n; i++) {
            int m = Math.min(take[i], change[i]);
            if (m > 0) {
                take[i] -= m;
                change[i] -= m;
            }
        }
    }

    private static long totalItems(int[] counts) {
        long sum = 0L;
        for (int c : counts) {
            if (c > 0) {
                sum += c;
            }
        }
        return sum;
    }

    // =========================================================== 运行时 Minecraft 背包/物品匹配

    public static boolean matchesTier(ItemStack stack, ItemStack template) {
        return stack != null && !stack.isEmpty()
                && template != null && !template.isEmpty()
                && ItemStack.isSameItemSameComponents(stack, template);
    }

    @Nullable
    public static ResolvedTier findTierForItem(ItemStack stack, @Nullable HolderLookup.Provider regs) {
        if (stack == null || stack.isEmpty() || regs == null) {
            return null;
        }
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        if (!cfg.isActive()) {
            return null;
        }
        for (ResolvedTier rt : cfg.resolveTiers(regs)) {
            if (matchesTier(stack, rt.template)) {
                return rt;
            }
        }
        return null;
    }

    public static boolean isCurrencyItem(ItemStack stack, @Nullable HolderLookup.Provider regs) {
        return findTierForItem(stack, regs) != null;
    }

    /**
     * 链内防套利检查：仅当走实体货币通道购买本身就是实体货币档位的物品时，
     * 校验所付实体总链值不得低于所买得的货币面值（纯星币渠道已与实体完全隔离，不再触发虚实折算拦截）。
     */
    public static boolean isBuyPhysBelowCurrencyValue(ItemStack boughtStack, int buyCount, PriceSpec totalSpec,
                                                      @Nullable HolderLookup.Provider regs) {
        if (buyCount <= 0 || totalSpec == null || !totalSpec.supportsPhys() || regs == null) {
            return false;
        }
        ResolvedTier boughtTier = findTierForItem(boughtStack, regs);
        if (boughtTier == null) {
            return false;
        }
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        int boughtCfgIdx = cfg.findTierIndex(boughtTier.spec.tierKey());
        int payTierIdx = cfg.findTierIndex(totalSpec.physTierKey);
        if (boughtCfgIdx < 0 || payTierIdx < 0) {
            return false;
        }
        long[] values = cfg.tierValues();
        if (boughtCfgIdx >= values.length || payTierIdx >= values.length) {
            return false;
        }
        long stackCount = Math.max(1L, boughtStack.getCount());
        long boughtTotalCount = stackCount > Long.MAX_VALUE / buyCount ? Long.MAX_VALUE : stackCount * buyCount;
        return isChainArbitrage(values, boughtCfgIdx, boughtTotalCount, payTierIdx, totalSpec.physCount);
    }

    public static int[] countInventory(Inventory inv, List<ResolvedTier> tiers) {
        int[] counts = new int[tiers.size()];
        if (inv == null || tiers.isEmpty()) {
            return counts;
        }
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (isArmorSlot(slot)) {
                continue;
            }
            ItemStack st = inv.getItem(slot);
            if (st.isEmpty()) {
                continue;
            }
            for (int t = 0; t < tiers.size(); t++) {
                if (matchesTier(st, tiers.get(t).template)) {
                    counts[t] += st.getCount();
                    break;
                }
            }
        }
        return counts;
    }

    /**
     * 原子扣除背包硬币并发放找零；若背包放不下找零，则通过模组邮箱投递，绝不吞币。
     *
     * @return true 表示部分找零通过邮件发出
     */
    public static boolean applyInventoryDelta(ServerPlayer player, List<ResolvedTier> tiers,
                                              int[] takeCounts, int[] changeCounts, String changeSource) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < tiers.size(); i++) {
            int wantTake = takeCounts[i];
            if (wantTake > 0) {
                int took = takeMatchingFromBag(inv, tiers.get(i).template, wantTake);
                if (took < wantTake) {
                    throw new IllegalStateException("实体货币背包扣除数量异常: expected=" + wantTake + ", actual=" + took);
                }
            }
        }
        inv.setChanged();

        boolean mailed = false;
        for (int i = 0; i < tiers.size(); i++) {
            int give = changeCounts[i];
            if (give > 0) {
                if (deliverPhysicalItems(player.server, player.getUUID(), player,
                        tiers.get(i).template, give, changeSource)) {
                    mailed = true;
                }
            }
        }
        player.containerMenu.broadcastChanges();
        return mailed;
    }

    /**
     * 向玩家发放指定实体货币档位 × 枚数（优先发标定档位，超大件数时按链内高阶向低阶压缩；满背包走邮件）。
     * 若期间 OP 临时停用或修改了档位配置，仍会尽力从显示配置或物品注册表还原对应模板以保证托管退款绝不吞币。
     */
    public static boolean deliverPhysicalTierAmount(MinecraftServer server, UUID targetId,
                                                    @Nullable String tierKey, long tierCount, String source) {
        if (server == null || targetId == null || tierKey == null || tierKey.isBlank() || tierCount <= 0L) {
            return false;
        }
        HolderLookup.Provider regs = server.registryAccess();
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        List<ResolvedTier> displayTiers = cfg.resolveAllForDisplay(regs);
        int idx = -1;
        CurrencyTier matched = cfg.findTier(tierKey);
        if (matched != null) {
            for (int i = 0; i < displayTiers.size(); i++) {
                if (displayTiers.get(i).spec.tierKey().equals(matched.tierKey())
                        || displayTiers.get(i).spec.uniqueItemKey().equals(matched.uniqueItemKey())) {
                    idx = i;
                    break;
                }
            }
        }
        ServerPlayer online = server.getPlayerList().getPlayer(targetId);
        if (idx >= 0 && !displayTiers.get(idx).template.isEmpty()) {
            long[] values = new long[displayTiers.size()];
            for (int i = 0; i < displayTiers.size(); i++) {
                values[i] = displayTiers.get(i).template.isEmpty() ? 0L : displayTiers.get(i).spec.value;
            }
            PayoutPlan plan = planTierPayout(idx, tierCount, values, DEFAULT_MAX_CHANGE_ITEMS);
            if (plan.remainder == 0L) {
                boolean mailed = false;
                for (int i = displayTiers.size() - 1; i >= 0; i--) {
                    int cnt = plan.counts()[i];
                    if (cnt > 0 && !displayTiers.get(i).template.isEmpty()) {
                        if (deliverPhysicalItems(server, targetId, online,
                                displayTiers.get(i).template, cnt, source)) {
                            mailed = true;
                        }
                    }
                }
                if (online != null) {
                    online.containerMenu.broadcastChanges();
                }
                return mailed;
            }
            // 超大数量兜底：直接分批按标定档位发往背包/邮件
            int safeCount = (int) Math.min(Integer.MAX_VALUE, tierCount);
            boolean mailed = deliverPhysicalItems(server, targetId, online,
                    displayTiers.get(idx).template, safeCount, source);
            if (online != null) {
                online.containerMenu.broadcastChanges();
            }
            return mailed;
        }
        // 兜底：档位已被移除时尝试将 tierKey 按 itemId[|componentsSnbt] 还原物品，保证历史托管绝不丢失
        ItemStack fallback = resolveFallbackTierStack(tierKey, regs);
        if (!fallback.isEmpty()) {
            int safeCount = (int) Math.min(Integer.MAX_VALUE, tierCount);
            boolean mailed = deliverPhysicalItems(server, targetId, online, fallback, safeCount, source);
            if (online != null) {
                online.containerMenu.broadcastChanges();
            }
            return mailed;
        }
        return false;
    }

    private static ItemStack resolveFallbackTierStack(String tierKey, HolderLookup.Provider regs) {
        String clean = tierKey.trim();
        int pipe = clean.indexOf('|');
        String itemPart = pipe >= 0 ? clean.substring(0, pipe).trim() : clean;
        String snbtPart = pipe >= 0 ? clean.substring(pipe + 1).trim() : "";
        int hash = itemPart.indexOf('#');
        if (hash > 0) {
            itemPart = itemPart.substring(0, hash).trim();
        }
        ResourceLocation rl = ResourceLocation.tryParse(itemPart);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        if (snbtPart.isEmpty()) {
            return new ItemStack(item, 1);
        }
        return PhysicalCurrencyConfig.buildTemplate(
                new CurrencyTier("", rl.toString(), snbtPart, 1, 1L, ""), regs);
    }

    /** 将指定实体货币档位 × 数量拆分为可放进邮箱/背包的物品栈列表（超限自动高阶压箱，档位删除时兜底还原）。 */
    public static List<ItemStack> buildPhysicalTierStacks(@Nullable HolderLookup.Provider regs,
                                                          @Nullable String tierKey,
                                                          long tierCount) {
        List<ItemStack> out = new java.util.ArrayList<>();
        if (regs == null || tierKey == null || tierKey.isBlank() || tierCount <= 0L) {
            return out;
        }
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        List<ResolvedTier> displayTiers = cfg.resolveAllForDisplay(regs);
        int idx = -1;
        CurrencyTier matched = cfg.findTier(tierKey);
        if (matched != null) {
            for (int i = 0; i < displayTiers.size(); i++) {
                if (displayTiers.get(i).spec.tierKey().equals(matched.tierKey())
                        || displayTiers.get(i).spec.uniqueItemKey().equals(matched.uniqueItemKey())) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx >= 0 && !displayTiers.get(idx).template.isEmpty()) {
            long[] values = new long[displayTiers.size()];
            for (int i = 0; i < displayTiers.size(); i++) {
                values[i] = displayTiers.get(i).template.isEmpty() ? 0L : displayTiers.get(i).spec.value;
            }
            PayoutPlan plan = planTierPayout(idx, tierCount, values, DEFAULT_MAX_CHANGE_ITEMS);
            if (plan.remainder == 0L) {
                for (int i = displayTiers.size() - 1; i >= 0; i--) {
                    int cnt = plan.counts()[i];
                    if (cnt > 0 && !displayTiers.get(i).template.isEmpty()) {
                        appendSplitStacks(out, displayTiers.get(i).template, cnt);
                    }
                }
                return out;
            }
            appendSplitStacks(out, displayTiers.get(idx).template, (int) Math.min(Integer.MAX_VALUE, tierCount));
            return out;
        }
        ItemStack fallback = resolveFallbackTierStack(tierKey, regs);
        if (!fallback.isEmpty()) {
            appendSplitStacks(out, fallback, (int) Math.min(Integer.MAX_VALUE, tierCount));
        }
        return out;
    }

    private static void appendSplitStacks(List<ItemStack> out, ItemStack template, int totalCount) {
        if (template == null || template.isEmpty() || totalCount <= 0) {
            return;
        }
        int per = Math.max(1, template.getMaxStackSize());
        int left = totalCount;
        while (left > 0) {
            int take = Math.min(per, left);
            ItemStack part = template.copy();
            part.setCount(take);
            out.add(part);
            left -= take;
        }
    }

    public static boolean deliverPhysicalItems(MinecraftServer server, UUID ownerId,
                                               @Nullable ServerPlayer online, ItemStack template,
                                               int count, String source) {
        if (count <= 0 || template == null || template.isEmpty()) {
            return false;
        }
        int perStack = Math.max(1, template.getMaxStackSize());
        int left = count;
        boolean mailed = false;
        while (left > 0) {
            int chunk = Math.min(perStack, left);
            left -= chunk;
            ItemStack part = template.copyWithCount(chunk);
            if (online != null) {
                online.getInventory().add(part);
                if (!part.isEmpty()) {
                    ShopManager.addMail(server, ownerId, part, source);
                    mailed = true;
                }
            } else {
                ShopManager.addMail(server, ownerId, part, source);
                mailed = true;
            }
        }
        if (online != null) {
            online.getInventory().setChanged();
        }
        return mailed;
    }

    /** 玩家手动在背包内进行相邻档位实体货币凑整 / 拆零互换。 */
    public static void executeConvert(ServerPlayer player, int tierIndex, String direction, int batches) {
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        if (!cfg.isActive()) {
            ShopManager.notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.phys_disabled", "实体货币系统当前未启用")
                    .withStyle(ChatFormatting.RED));
            ShopManager.sendSnapshot(player, null);
            return;
        }
        List<ResolvedTier> tiers = cfg.resolveTiers(player.level().registryAccess());
        if (tiers.size() != cfg.tiers.size()) {
            ShopManager.notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.phys_invalid_tier", "部分货币档位物品在当前服务端无效，无法兑换")
                    .withStyle(ChatFormatting.RED));
            ShopManager.sendSnapshot(player, null);
            return;
        }
        int[] owned = countInventory(player.getInventory(), tiers);
        ConvertPlan plan = planConversion(cfg, tierIndex, direction, batches, owned);
        if (!plan.ok) {
            String key = switch (plan.failReason) {
                case "NOT_ENOUGH_ITEMS" -> "msg.module_shop.not_enough_items";
                case "TOO_MANY_OUTPUT_ITEMS", "TOO_LARGE" -> "msg.module_shop.phys_convert_too_many";
                case "INVALID_RATIO" -> "msg.module_shop.phys_convert_bad_ratio";
                default -> "msg.module_shop.phys_convert_failed";
            };
            String fallback = switch (plan.failReason) {
                case "NOT_ENOUGH_ITEMS" -> "背包内实体货币数量不足";
                case "TOO_MANY_OUTPUT_ITEMS", "TOO_LARGE" -> "单次兑换数量过大，请减少组数";
                case "INVALID_RATIO" -> "该档位面值不是整数倍比关系，无法直接兑换";
                default -> "兑换参数无效，无法执行";
            };
            ShopManager.notifyPlayer(player, Component.translatableWithFallback(key, fallback)
                    .withStyle(ChatFormatting.RED));
            ShopManager.sendSnapshot(player, null);
            return;
        }

        ResolvedTier fromTier = tiers.get(plan.takeTier);
        ResolvedTier toTier = tiers.get(plan.giveTier);
        long convertValue = tiers.get(tierIndex).spec.value > 0L
                ? (batches > Long.MAX_VALUE / tiers.get(tierIndex).spec.value
                ? Long.MAX_VALUE : (long) batches * tiers.get(tierIndex).spec.value)
                : 0L;
        long quotaDay = cn.autoforged.module_shop.Config.shippingQuotaDay();
        cn.autoforged.module_shop.data.PlayerAccount account = ShopManager.account(player);
        long dailyCap = Math.max(0L, cfg.dailyConvertValueLimit);
        if (dailyCap > 0L && convertValue > 0L) {
            long used = account.physConvertUsedFor(quotaDay);
            long remaining = Math.max(0L, dailyCap - used);
            if (remaining <= 0L || convertValue > remaining) {
                ShopManager.notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.phys_convert_daily_limit",
                        "超出每日实体货币阶梯兑换上限（今日已兑 %s / 上限 %s）",
                        used, dailyCap).withStyle(ChatFormatting.RED));
                ShopManager.sendSnapshot(player, null);
                return;
            }
        }
        int took = takeMatchingFromBag(player.getInventory(), fromTier.template, plan.takeCount);
        if (took < plan.takeCount) {
            if (took > 0) {
                deliverPhysicalItems(player.server, player.getUUID(), player, fromTier.template, took, "phys_convert");
            }
            ShopManager.notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.not_enough_items", "背包内实体货币数量不足").withStyle(ChatFormatting.RED));
            ShopManager.sendSnapshot(player, null);
            return;
        }
        player.getInventory().setChanged();
        boolean mailed = deliverPhysicalItems(player.server, player.getUUID(), player,
                toTier.template, plan.giveCount, "phys_convert");
        player.containerMenu.broadcastChanges();

        if (dailyCap > 0L && convertValue > 0L) {
            account.addPhysConvertUsed(quotaDay, convertValue);
            ShopManager.data(player.server).setDirty();
        }
        long acctBal = account.balance;
        String fromName = tierDisplayName(fromTier);
        String toName = tierDisplayName(toTier);
        TransactionLog.record(player.server, player.getUUID(), TransactionLog.EXCHANGE_BUY, "实体货币兑换",
                0L, acctBal, fromName + "×" + plan.takeCount + " → " + toName + "×" + plan.giveCount);

        ShopManager.notifyPlayer(player, Component.translatableWithFallback(
                "msg.module_shop.phys_convert_done",
                "已将 %s × %s 兑换为 %s × %s",
                fromName, plan.takeCount, toName, plan.giveCount).withStyle(ChatFormatting.GREEN));
        if (mailed) {
            ShopManager.notifyPlayer(player, Component.translatable("msg.module_shop.inventory_full_mail")
                    .withStyle(ChatFormatting.YELLOW));
        }
        ShopManager.sendSnapshot(player, null);
    }

    private static String tierDisplayName(ResolvedTier tier) {
        if (tier.spec.label != null && !tier.spec.label.isBlank()) {
            return tier.spec.label;
        }
        return tier.template.getHoverName().getString();
    }

    private static boolean isArmorSlot(int slot) {
        return slot >= Inventory.INVENTORY_SIZE && slot < Inventory.INVENTORY_SIZE + 4;
    }

    private static int takeMatchingFromBag(Inventory inv, ItemStack template, int max) {
        int taken = 0;
        for (int slot = 0; slot < inv.getContainerSize() && taken < max; slot++) {
            if (isArmorSlot(slot)) {
                continue;
            }
            ItemStack st = inv.getItem(slot);
            if (!matchesTier(st, template)) {
                continue;
            }
            int take = Math.min(max - taken, st.getCount());
            st.shrink(take);
            taken += take;
            if (st.isEmpty()) {
                inv.setItem(slot, ItemStack.EMPTY);
            }
        }
        return taken;
    }
}
