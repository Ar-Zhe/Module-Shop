package cn.autoforged.module_shop.client.ui.v2;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.PhysTierView;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.PriceSpec.CurrencyMode;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.item.ItemStack;

/**
 * 双独立并行账本价格渲染与支付可用性工具（UI v2）。
 *
 * <p>严格遵守双账本隔离规则：
 * <ul>
 *     <li>星币通道仅校验 {@link ClientShopData#balance}；</li>
 *     <li>实体货币通道仅校验背包实体阶梯链（{@link PhysicalCurrencyEngine#planPhysicalTierPay}），绝不与星币混算；</li>
 *     <li>所有表格/卡片小图标均使用 {@link ItemOverlay#addNoHover}（{@code 7~9px}、{@code decorations=false}），
 *     防止抢夺商品自身的悬停提示与 JEI 快捷键。</li>
 * </ul>
 */
public final class PriceDisplay {
    private static final float CELL_ICON = 9.0F;
    private static final float CARD_ICON = 8.0F;
    private static final float WIDTH_EPS = 0.5F;

    private PriceDisplay() {
    }

    // ------------------------------------------------------------------ 表格与卡片统一绘制

    public static void drawCell(Canvas c, @Nullable ItemOverlay items, @Nullable PriceSpec spec,
                                float x, float cy, float w, Text.Align align, int starColor, boolean dim) {
        drawCell(c, items, spec, x, cy, w, align, starColor, dim, null);
    }

    public static void drawCell(Canvas c, @Nullable ItemOverlay items, @Nullable PriceSpec spec,
                                float x, float cy, float w, Text.Align align, int starColor, boolean dim,
                                @Nullable String prefix) {
        drawInternal(c, items, spec, x, cy, x + w * 0.5F, w, 1.0F, align, starColor, dim, prefix, CELL_ICON);
    }

    public static void drawCard(Canvas c, @Nullable ItemOverlay items, @Nullable PriceSpec spec,
                                float cx, float cy, float cardMidX, float w, float scaleX,
                                int starColor, boolean dim) {
        if (scaleX <= 0.05F) {
            return;
        }
        drawInternal(c, items, spec, cx, cy, cardMidX, w, scaleX, Text.Align.CENTER,
                starColor, dim, null, CARD_ICON);
    }

    private record Layout(
            float fs,
            float iconSize,
            float gap,
            String prefix,
            String starText,
            String sepText,
            @Nullable ItemStack physStack,
            String physText,
            float prefixW,
            float starW,
            float sepW,
            float iconW,
            float physW,
            float totalW
    ) {
    }

    private static void drawInternal(Canvas c, @Nullable ItemOverlay items, @Nullable PriceSpec rawSpec,
                                     float x, float cy, float cardMidX, float w, float scaleX,
                                     Text.Align align, int starColor, boolean dim,
                                     @Nullable String prefix, float baseIcon) {
        if (c == null || w <= 2.0F) {
            return;
        }
        PriceSpec spec = rawSpec == null ? PriceSpec.ZERO : rawSpec;
        String safePrefix = prefix == null ? "" : prefix;
        int mainColor = dim ? IOStyle.TEXT_3 : starColor;
        int sepColor = dim ? IOStyle.TEXT_3 : IOStyle.TEXT_3;
        int physColor = dim ? IOStyle.TEXT_3 : (starColor == IOStyle.RED ? IOStyle.RED : starColor);

        Layout lay = chooseLayout(spec, safePrefix, w, baseIcon);
        // 单段纯数字（如 STAR_ONLY / 带前缀的单币）在列宽富余时直接按整列宽绘制，彻底避免浮点减法误差触发省略号（1,200 → 1,2…）
        if (lay.physStack == null && lay.physText.isEmpty() && lay.sepText.isEmpty() && lay.totalW <= w) {
            String single = lay.prefix.isEmpty() ? lay.starText : (lay.prefix + lay.starText);
            if (!single.isEmpty()) {
                Text.drawLine(c, single, AtomUi.BOLD, lay.fs, mainColor, x, cy, w, align);
            }
            return;
        }
        float drawW = Math.min(w, lay.totalW);
        float startX = switch (align) {
            case RIGHT -> x + w - drawW;
            case CENTER -> x + Math.max(0.0F, (w - drawW) * 0.5F);
            default -> x;
        };
        float maxRight = x + w;
        float cur = startX;

        if (!lay.prefix.isEmpty() && cur < maxRight) {
            float cw = safeSegmentWidth(lay.prefixW, maxRight - cur);
            Text.drawLine(c, lay.prefix, AtomUi.BOLD, lay.fs, mainColor, cur, cy, cw, Text.Align.LEFT);
            cur += lay.prefixW;
        }

        if (!lay.starText.isEmpty() && cur < maxRight) {
            float cw = safeSegmentWidth(lay.starW, maxRight - cur);
            Text.drawLine(c, lay.starText, AtomUi.BOLD, lay.fs, mainColor, cur, cy, cw, Text.Align.LEFT);
            cur += lay.starW;
        }

        if (!lay.sepText.isEmpty() && cur < maxRight) {
            float cw = safeSegmentWidth(lay.sepW, maxRight - cur);
            Text.drawLine(c, lay.sepText, AtomUi.REGULAR, lay.fs, sepColor, cur, cy, cw, Text.Align.LEFT);
            cur += lay.sepW;
        }

        if (lay.physStack != null && !lay.physStack.isEmpty() && cur + 2.0F < maxRight) {
            if (items != null && scaleX >= 0.35F) {
                float projectedX = Math.abs(scaleX - 1.0F) < 0.001F
                        ? cur
                        : cardMidX + (cur - cardMidX) * scaleX;
                float iy = cy - lay.iconSize * 0.5F;
                items.addNoHover(lay.physStack, projectedX, iy, lay.iconSize, scaleX, false);
            }
            cur += lay.iconSize + lay.gap;
        }

        if (!lay.physText.isEmpty() && cur < maxRight) {
            float avail = safeSegmentWidth(lay.physW, maxRight - cur);
            if (avail >= 2.0F) {
                Text.drawLine(c, lay.physText, AtomUi.BOLD, lay.fs, physColor, cur, cy, avail, Text.Align.LEFT);
            }
        }
    }

    private static float safeSegmentWidth(float measuredW, float avail) {
        if (avail <= 0.0F) {
            return 0.0F;
        }
        if (avail >= measuredW - 0.05F) {
            return Math.max(avail, measuredW + WIDTH_EPS);
        }
        return Math.max(1.0F, avail);
    }

    private static Layout chooseLayout(PriceSpec spec, String prefix, float maxW, float baseIcon) {
        PhysTierView tier = spec.supportsPhys() ? ClientHomeCurrencyState.findTier(spec.physTierKey) : null;
        ItemStack iconStack = tier != null && !tier.itemStack.isEmpty()
                ? tier.itemStack.copyWithCount(1)
                : null;
        String missingTierName = iconStack == null && spec.supportsPhys()
                ? "[" + fallbackTierLabel(spec.physTierKey) + "]"
                : "";

        // 0 级：标准字号 7.0 + 完整逗号数字；1 级：降字号 6.0 + 紧凑间隔；2 级：最小字号 5.25 + k/M 缩写数字
        for (int level = 0; level < 3; level++) {
            float fs = level == 0 ? V2.FS : (level == 1 ? V2.FS_S : V2.FS_XS);
            float icon = level == 0 ? baseIcon : (level == 1 ? Math.min(baseIcon, 7.5F) : Math.min(baseIcon, 7.0F));
            float gap = level == 0 ? 1.0F : 0.5F;
            boolean compact = level >= 2;

            String starText = spec.supportsStar()
                    ? formatNumber(spec.starPrice, compact)
                    : (spec.mode == CurrencyMode.STAR_ONLY ? formatNumber(spec.starPrice, compact) : "");
            String sepText = switch (spec.mode) {
                case EITHER -> level == 0 ? " | " : "|";
                case BOTH -> level == 0 ? " + " : "+";
                default -> "";
            };
            String physText = "";
            if (spec.supportsPhys()) {
                String countStr = formatNumber(spec.physCount, compact);
                if (iconStack != null) {
                    physText = spec.mode == CurrencyMode.PHYS_ONLY ? ("×" + countStr) : countStr;
                } else {
                    physText = missingTierName + "×" + countStr;
                }
            }

            float prefixW = prefix.isEmpty() ? 0.0F : Text.width(prefix, AtomUi.BOLD, fs);
            float starW = starText.isEmpty() ? 0.0F : Text.width(starText, AtomUi.BOLD, fs);
            float sepW = sepText.isEmpty() ? 0.0F : Text.width(sepText, AtomUi.REGULAR, fs);
            float iconW = iconStack != null ? (icon + gap) : 0.0F;
            float physW = physText.isEmpty() ? 0.0F : Text.width(physText, AtomUi.BOLD, fs);
            float totalW = prefixW + starW + sepW + iconW + physW;

            if (totalW + WIDTH_EPS <= maxW || level == 2) {
                // 二次超窄兜底（仅当 level == 2 且仍超宽）：按比例缩窄文字分配，避免后续叠加或出框
                if (totalW > maxW && !starText.isEmpty() && !physText.isEmpty()) {
                    float fixedW = prefixW + sepW + iconW;
                    float textBudget = Math.max(8.0F, maxW - fixedW);
                    float sumText = starW + physW;
                    if (sumText > textBudget) {
                        starW = Math.max(4.0F, textBudget * (starW / sumText));
                        physW = Math.max(4.0F, textBudget - starW);
                        totalW = maxW;
                    }
                }
                return new Layout(fs, icon, gap, prefix, starText, sepText, iconStack, physText,
                        prefixW, starW, sepW, iconW, physW, totalW);
            }
        }
        throw new AssertionError("unreachable");
    }

    // ------------------------------------------------------------------ 文本格式化

    /** Tooltip 与纯文本回退用（例如 "188 星币"、"3 铁币"、"188 星币 / 3 铁币"、"188 星币 + 3 铁币"）。 */
    public static String formatPlain(@Nullable PriceSpec rawSpec) {
        if (rawSpec == null) {
            return ClientShopData.money(0L);
        }
        return switch (rawSpec.mode) {
            case STAR_ONLY -> ClientShopData.money(rawSpec.starPrice);
            case PHYS_ONLY -> physPlain(rawSpec.physTierKey, rawSpec.physCount);
            case EITHER -> ClientShopData.money(rawSpec.starPrice)
                    + " / " + physPlain(rawSpec.physTierKey, rawSpec.physCount);
            case BOTH -> ClientShopData.money(rawSpec.starPrice)
                    + " + " + physPlain(rawSpec.physTierKey, rawSpec.physCount);
        };
    }

    /** 紧凑版纯文本（不带星币后缀，物理币带名，适合窄按钮/标题）。 */
    public static String formatCompact(@Nullable PriceSpec rawSpec) {
        if (rawSpec == null) {
            return V2.money(0L);
        }
        return switch (rawSpec.mode) {
            case STAR_ONLY -> V2.money(rawSpec.starPrice);
            case PHYS_ONLY -> physPlain(rawSpec.physTierKey, rawSpec.physCount);
            case EITHER -> V2.money(rawSpec.starPrice) + " / " + physPlain(rawSpec.physTierKey, rawSpec.physCount);
            case BOTH -> V2.money(rawSpec.starPrice) + " + " + physPlain(rawSpec.physTierKey, rawSpec.physCount);
        };
    }

    public static String physPlain(@Nullable String tierKey, long count) {
        return Math.max(0L, count) + " " + tierDisplayName(tierKey);
    }

    public static String tierDisplayName(@Nullable String tierKey) {
        PhysTierView tier = ClientHomeCurrencyState.findTier(tierKey);
        if (tier != null) {
            return tier.displayName();
        }
        return fallbackTierLabel(tierKey);
    }

    /** 将星币总额与各阶实体币映射汇总为人类可读的双账本明细串（如全卖/出货箱预计收益）。 */
    public static String formatLedgerSummary(long starTotal, @Nullable Map<String, Long> physTotals) {
        List<String> parts = new ArrayList<>();
        if (starTotal > 0L) {
            parts.add(ClientShopData.money(starTotal));
        }
        if (physTotals != null && !physTotals.isEmpty()) {
            for (Map.Entry<String, Long> entry : physTotals.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0L) {
                    parts.add(physPlain(entry.getKey(), entry.getValue()));
                }
            }
        }
        if (parts.isEmpty()) {
            return ClientShopData.money(0L);
        }
        return String.join(" + ", parts);
    }

    public static void addTotals(@Nullable PriceSpec spec, int qty,
                                 long[] starOut, Map<String, Long> physOut) {
        if (spec == null || qty <= 0) {
            return;
        }
        if (spec.mode == CurrencyMode.PHYS_ONLY) {
            if (spec.physCount > 0L && spec.physTierKey != null && !spec.physTierKey.isEmpty()) {
                physOut.merge(spec.physTierKey, safeMul(spec.physCount, qty), PriceDisplay::safeAdd);
            }
            return;
        }
        if (spec.mode == CurrencyMode.BOTH) {
            if (spec.starPrice > 0L) {
                starOut[0] = safeAdd(starOut[0], safeMul(spec.starPrice, qty));
            }
            if (spec.physCount > 0L && spec.physTierKey != null && !spec.physTierKey.isEmpty()) {
                physOut.merge(spec.physTierKey, safeMul(spec.physCount, qty), PriceDisplay::safeAdd);
            }
            return;
        }
        // STAR_ONLY 与 EITHER 按默认星币通道计入星币总值，EITHER 另外保留实体可选项提示
        if (spec.starPrice > 0L) {
            starOut[0] = safeAdd(starOut[0], safeMul(spec.starPrice, qty));
        }
    }

    // ------------------------------------------------------------------ 双独立账本支付可用性

    public static boolean canAfford(@Nullable PriceSpec spec, int qty) {
        if (spec == null || qty <= 0) {
            return true;
        }
        return switch (spec.mode) {
            case STAR_ONLY -> ClientShopData.balance >= safeMul(spec.starPrice, qty);
            case PHYS_ONLY -> canAffordPhys(spec.physTierKey, spec.physCount, qty);
            case EITHER -> ClientShopData.balance >= safeMul(spec.starPrice, qty)
                    || canAffordPhys(spec.physTierKey, spec.physCount, qty);
            case BOTH -> ClientShopData.balance >= safeMul(spec.starPrice, qty)
                    && canAffordPhys(spec.physTierKey, spec.physCount, qty);
        };
    }

    public static boolean canAffordChannel(@Nullable PriceSpec spec, int qty, @Nullable String channel) {
        if (spec == null || qty <= 0) {
            return true;
        }
        boolean wantPhys = ClientHomeCurrencyState.CHANNEL_PHYS.equalsIgnoreCase(channel);
        return switch (spec.mode) {
            case STAR_ONLY -> !wantPhys && ClientShopData.balance >= safeMul(spec.starPrice, qty);
            case PHYS_ONLY -> wantPhys && canAffordPhys(spec.physTierKey, spec.physCount, qty);
            case EITHER -> wantPhys
                    ? canAffordPhys(spec.physTierKey, spec.physCount, qty)
                    : ClientShopData.balance >= safeMul(spec.starPrice, qty);
            case BOTH -> ClientShopData.balance >= safeMul(spec.starPrice, qty)
                    && canAffordPhys(spec.physTierKey, spec.physCount, qty);
        };
    }

    /**
     * 为左键快捷购买决策支付通道（严格双独立账本）：
     * {@code STAR_ONLY -> "star"}、{@code PHYS_ONLY -> "phys"}、{@code BOTH -> "both"}、
     * {@code EITHER -> 优先 preferredEitherChannel，若偏好通道不足但另一通道足够则自动切到可用通道}。
     */
    public static String resolveBuyChannel(@Nullable PriceSpec spec, int qty) {
        if (spec == null) {
            return ClientHomeCurrencyState.CHANNEL_STAR;
        }
        return switch (spec.mode) {
            case STAR_ONLY -> ClientHomeCurrencyState.CHANNEL_STAR;
            case PHYS_ONLY -> ClientHomeCurrencyState.CHANNEL_PHYS;
            case BOTH -> "both";
            case EITHER -> {
                String pref = ClientHomeCurrencyState.CHANNEL_PHYS.equals(ClientHomeCurrencyState.preferredEitherChannel)
                        ? ClientHomeCurrencyState.CHANNEL_PHYS
                        : ClientHomeCurrencyState.CHANNEL_STAR;
                if (canAffordChannel(spec, qty, pref)) {
                    yield pref;
                }
                String alt = ClientHomeCurrencyState.CHANNEL_PHYS.equals(pref)
                        ? ClientHomeCurrencyState.CHANNEL_STAR
                        : ClientHomeCurrencyState.CHANNEL_PHYS;
                if (canAffordChannel(spec, qty, alt)) {
                    yield alt;
                }
                yield pref;
            }
        };
    }

    public static boolean canAffordPhys(@Nullable String tierKey, long unitCount, int qty) {
        if (unitCount <= 0L || qty <= 0) {
            return true;
        }
        if (!ClientHomeCurrencyState.physEnabled) {
            return false;
        }
        int tierIdx = ClientHomeCurrencyState.findTierIndex(tierKey);
        if (tierIdx < 0) {
            return false;
        }
        long totalCount = safeMul(unitCount, qty);
        return PhysicalCurrencyEngine.planPhysicalTierPay(
                tierIdx,
                totalCount,
                ClientHomeCurrencyState.tierValues(),
                ClientHomeCurrencyState.tierOwnedCounts()).ok();
    }

    // ------------------------------------------------------------------ 内部数字与名称工具

    private static String fallbackTierLabel(@Nullable String tierKey) {
        if (tierKey == null || tierKey.isBlank()) {
            return "?";
        }
        String s = tierKey.trim();
        int bar = s.indexOf('|');
        if (bar > 0) {
            s = s.substring(0, bar);
        }
        int colon = s.indexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) {
            s = s.substring(colon + 1);
        }
        return s.isEmpty() ? "?" : s;
    }

    public static String formatNumber(long v, boolean compact) {
        if (!compact || Math.abs(v) < 10_000L) {
            return V2.money(v);
        }
        long abs = Math.abs(v);
        String sign = v < 0L ? "-" : "";
        if (abs >= 1_000_000_000L) {
            return sign + trimDecimal(abs / 1_000_000_000.0D) + "G";
        }
        if (abs >= 1_000_000L) {
            return sign + trimDecimal(abs / 1_000_000.0D) + "M";
        }
        return sign + trimDecimal(abs / 1_000.0D) + "k";
    }

    private static String trimDecimal(double val) {
        if (val >= 100.0D) {
            return String.format(Locale.ROOT, "%.0f", val);
        }
        String s = String.format(Locale.ROOT, "%.1f", val);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static long safeMul(long a, int qty) {
        if (a <= 0L || qty <= 0) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / qty) {
            return Long.MAX_VALUE;
        }
        return a * qty;
    }

    private static long safeAdd(long a, long b) {
        if (a <= 0L) {
            return Math.max(0L, b);
        }
        if (b <= 0L) {
            return a;
        }
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
