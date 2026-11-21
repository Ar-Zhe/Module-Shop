package cn.autoforged.module_shop.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 双独立并行账本定价规格：支持纯星币、纯实体货币、二选一双通道、组合同付四种模式。
 *
 * <p>星币账本（{@code starPrice}）与实体货币账本（{@code physTierKey × physCount}）严格隔离，
 * 彼此之间绝不按汇率折算或混合扣款。
 */
public final class PriceSpec {
    public enum CurrencyMode {
        STAR_ONLY("star"),
        PHYS_ONLY("phys"),
        EITHER("either"),
        BOTH("both");

        private final String id;

        CurrencyMode(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public boolean includesStar() {
            return this == STAR_ONLY || this == EITHER || this == BOTH;
        }

        public boolean includesPhys() {
            return this == PHYS_ONLY || this == EITHER || this == BOTH;
        }

        /** 与客户端控件命名兼容的别名，等价于 {@link #includesStar()}。 */
        public boolean supportsStar() {
            return includesStar();
        }

        /** 与客户端控件命名兼容的别名，等价于 {@link #includesPhys()}。 */
        public boolean supportsPhys() {
            return includesPhys();
        }

        public static CurrencyMode fromId(@Nullable String id) {
            if (id == null || id.isBlank()) {
                return STAR_ONLY;
            }
            String norm = id.trim().toLowerCase(Locale.ROOT);
            for (CurrencyMode mode : values()) {
                if (mode.id.equals(norm) || mode.name().toLowerCase(Locale.ROOT).equals(norm)) {
                    return mode;
                }
            }
            return STAR_ONLY;
        }
    }

    public static final String TAG_MODE = "mode";
    public static final String TAG_STAR = "star";
    public static final String TAG_PHYS_TIER = "physTier";
    public static final String TAG_PHYS_COUNT = "physCount";

    public static final PriceSpec ZERO = new PriceSpec(CurrencyMode.STAR_ONLY, 0L, "", 0L);

    public final CurrencyMode mode;
    public final long starPrice;
    public final String physTierKey;
    public final long physCount;

    private PriceSpec(CurrencyMode mode, long starPrice, String physTierKey, long physCount) {
        this.mode = mode;
        this.starPrice = starPrice;
        this.physTierKey = physTierKey;
        this.physCount = physCount;
    }

    public static PriceSpec star(long starPrice) {
        long clean = Math.max(0L, starPrice);
        return clean == 0L ? ZERO : new PriceSpec(CurrencyMode.STAR_ONLY, clean, "", 0L);
    }

    public static PriceSpec phys(@Nullable String physTierKey, long physCount) {
        String key = cleanKey(physTierKey);
        long cnt = Math.max(0L, physCount);
        return new PriceSpec(CurrencyMode.PHYS_ONLY, 0L, key, cnt);
    }

    public static PriceSpec either(long starPrice, @Nullable String physTierKey, long physCount) {
        return of(CurrencyMode.EITHER, starPrice, physTierKey, physCount);
    }

    public static PriceSpec both(long starPrice, @Nullable String physTierKey, long physCount) {
        return of(CurrencyMode.BOTH, starPrice, physTierKey, physCount);
    }

    public static PriceSpec of(@Nullable CurrencyMode mode, long starPrice, @Nullable String physTierKey, long physCount) {
        CurrencyMode m = mode == null ? CurrencyMode.STAR_ONLY : mode;
        long s = Math.max(0L, starPrice);
        String key = cleanKey(physTierKey);
        long cnt = Math.max(0L, physCount);
        return switch (m) {
            case STAR_ONLY -> star(s);
            case PHYS_ONLY -> new PriceSpec(CurrencyMode.PHYS_ONLY, 0L, key, cnt);
            case EITHER -> new PriceSpec(CurrencyMode.EITHER, s, key, cnt);
            case BOTH -> new PriceSpec(CurrencyMode.BOTH, s, key, cnt);
        };
    }

    public boolean isValid() {
        return switch (mode) {
            case STAR_ONLY -> starPrice > 0L;
            case PHYS_ONLY -> !physTierKey.isEmpty() && physCount > 0L;
            case EITHER, BOTH -> starPrice > 0L && !physTierKey.isEmpty() && physCount > 0L;
        };
    }

    public boolean isZero() {
        return !supportsStar() && !supportsPhys();
    }

    public boolean supportsStar() {
        return mode.includesStar() && starPrice > 0L;
    }

    public boolean supportsPhys() {
        return mode.includesPhys() && !physTierKey.isEmpty() && physCount > 0L;
    }

    public PriceSpec scale(int qty) {
        if (qty <= 0) {
            return ZERO;
        }
        if (qty == 1) {
            return this;
        }
        long nextStar = safeMultiply(starPrice, qty);
        long nextPhys = safeMultiply(physCount, qty);
        return of(mode, nextStar, physTierKey, nextPhys);
    }

    public PriceSpec withStarPrice(long nextStarPrice) {
        long clean = Math.max(0L, nextStarPrice);
        if (mode == CurrencyMode.PHYS_ONLY) {
            return this;
        }
        return of(mode, clean, physTierKey, physCount);
    }

    public PriceSpec withPhysTierKey(@Nullable String nextTierKey) {
        if (!mode.includesPhys()) {
            return this;
        }
        return of(mode, starPrice, nextTierKey, physCount);
    }

    public boolean sameCurrencyUnit(@Nullable PriceSpec other) {
        if (other == null || this.mode != other.mode) {
            return false;
        }
        if (this.mode == CurrencyMode.STAR_ONLY) {
            return true;
        }
        return Objects.equals(this.physTierKey, other.physTierKey);
    }

    // ------------------------------------------------------------------ NBT 序列化

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_MODE, mode.id());
        tag.putLong(TAG_STAR, starPrice);
        tag.putString(TAG_PHYS_TIER, physTierKey);
        tag.putLong(TAG_PHYS_COUNT, physCount);
        return tag;
    }

    public static PriceSpec fromTag(@Nullable CompoundTag tag, long fallbackStar) {
        long cleanFallback = Math.max(0L, fallbackStar);
        if (tag == null || tag.isEmpty()) {
            return star(cleanFallback);
        }
        boolean hasAny = tag.contains(TAG_MODE)
                || tag.contains(TAG_STAR)
                || tag.contains(TAG_PHYS_TIER)
                || tag.contains(TAG_PHYS_COUNT);
        if (!hasAny) {
            return star(cleanFallback);
        }
        CurrencyMode mode = tag.contains(TAG_MODE, Tag.TAG_STRING)
                ? CurrencyMode.fromId(tag.getString(TAG_MODE))
                : CurrencyMode.STAR_ONLY;
        long star = tag.contains(TAG_STAR, Tag.TAG_ANY_NUMERIC)
                ? Math.max(0L, tag.getLong(TAG_STAR))
                : (mode.includesStar() ? cleanFallback : 0L);
        String tier = tag.getString(TAG_PHYS_TIER);
        long physCnt = tag.contains(TAG_PHYS_COUNT, Tag.TAG_ANY_NUMERIC)
                ? Math.max(0L, tag.getLong(TAG_PHYS_COUNT))
                : 0L;
        return of(mode, star, tier, physCnt);
    }

    public static PriceSpec readFromParent(@Nullable CompoundTag parent, String specKey, String legacyLongKey) {
        if (parent == null) {
            return ZERO;
        }
        long legacy = (legacyLongKey != null && !legacyLongKey.isEmpty()
                && parent.contains(legacyLongKey, Tag.TAG_ANY_NUMERIC))
                ? Math.max(0L, parent.getLong(legacyLongKey))
                : 0L;
        if (specKey != null && !specKey.isEmpty() && parent.contains(specKey, Tag.TAG_COMPOUND)) {
            return fromTag(parent.getCompound(specKey), legacy);
        }
        return star(legacy);
    }

    public void writeToParent(@Nullable CompoundTag parent, String specKey, @Nullable String legacyLongKey) {
        if (parent == null) {
            return;
        }
        if (specKey != null && !specKey.isEmpty()) {
            parent.put(specKey, toTag());
        }
        if (legacyLongKey != null && !legacyLongKey.isEmpty()) {
            parent.putLong(legacyLongKey, supportsStar() ? starPrice : 0L);
        }
    }

    // ------------------------------------------------------------------ JSON 模板辅助

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("mode", mode.id());
        if (mode.includesStar() || starPrice > 0L) {
            obj.addProperty("star", starPrice);
        }
        if (mode.includesPhys() || !physTierKey.isEmpty()) {
            obj.addProperty("phys_tier", physTierKey);
            obj.addProperty("phys_count", physCount);
        }
        return obj;
    }

    public static PriceSpec fromJson(@Nullable JsonElement element, long fallbackStar) {
        long cleanFallback = Math.max(0L, fallbackStar);
        if (element == null || !element.isJsonObject()) {
            return star(cleanFallback);
        }
        JsonObject obj = element.getAsJsonObject();
        String modeStr = obj.has("mode") && !obj.get("mode").isJsonNull()
                ? obj.get("mode").getAsString()
                : "star";
        CurrencyMode mode = CurrencyMode.fromId(modeStr);
        long star = obj.has("star") && obj.get("star").isJsonPrimitive()
                ? safeJsonLong(obj.get("star"), cleanFallback)
                : (mode.includesStar() ? cleanFallback : 0L);
        String tier = obj.has("phys_tier") && !obj.get("phys_tier").isJsonNull()
                ? obj.get("phys_tier").getAsString()
                : "";
        long physCnt = obj.has("phys_count") && obj.get("phys_count").isJsonPrimitive()
                ? safeJsonLong(obj.get("phys_count"), 0L)
                : 0L;
        return of(mode, star, tier, physCnt);
    }

    private static long safeJsonLong(JsonElement el, long fallback) {
        try {
            return Math.max(0L, el.getAsLong());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ 规则文本序列化（TOML / ShippingRules）

    /**
     * 序列化为紧凑无空格规则文本：
     * - STAR_ONLY: {@code 100}
     * - PHYS_ONLY: {@code phys:<escapedTier>*<count>}
     * - EITHER: {@code 100|phys:<escapedTier>*<count>}
     * - BOTH: {@code 100+phys:<escapedTier>*<count>}
     */
    public String toRuleText() {
        return switch (mode) {
            case STAR_ONLY -> Long.toString(starPrice);
            case PHYS_ONLY -> "phys:" + escapeTierKey(physTierKey) + "*" + physCount;
            case EITHER -> starPrice + "|phys:" + escapeTierKey(physTierKey) + "*" + physCount;
            case BOTH -> starPrice + "+phys:" + escapeTierKey(physTierKey) + "*" + physCount;
        };
    }

    /**
     * 解析规则文本，非法格式返回 {@code null}。
     * 纯数字字符串无条件解析为 {@link CurrencyMode#STAR_ONLY}。
     */
    public static @Nullable PriceSpec parseRuleText(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // 纯整数（允许纯数字）：无条件按 STAR_ONLY 解析
        Long pure = tryParseNonNegativeLong(text);
        if (pure != null) {
            return star(pure);
        }
        // EITHER: <a>|<b>
        int bar = text.indexOf('|');
        if (bar >= 0) {
            if (text.indexOf('|', bar + 1) >= 0 || text.indexOf('+') >= 0) {
                return null;
            }
            return parseCombined(CurrencyMode.EITHER, text.substring(0, bar), text.substring(bar + 1));
        }
        // BOTH: <a>+<b>
        int plus = text.indexOf('+');
        if (plus >= 0) {
            if (text.indexOf('+', plus + 1) >= 0) {
                return null;
            }
            return parseCombined(CurrencyMode.BOTH, text.substring(0, plus), text.substring(plus + 1));
        }
        // PHYS_ONLY: phys:<escapedTier>*<count>
        PhysPart phys = parsePhysPart(text);
        if (phys == null) {
            return null;
        }
        return phys(phys.tierKey, phys.count);
    }

    private static @Nullable PriceSpec parseCombined(CurrencyMode mode, String leftRaw, String rightRaw) {
        String a = leftRaw.trim();
        String b = rightRaw.trim();
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        Long star = tryParseNonNegativeLong(a);
        PhysPart phys = parsePhysPart(b);
        if (star == null || phys == null) {
            star = tryParseNonNegativeLong(b);
            phys = parsePhysPart(a);
        }
        if (star == null || phys == null || star <= 0L) {
            return null;
        }
        return of(mode, star, phys.tierKey, phys.count);
    }

    private record PhysPart(String tierKey, long count) {
    }

    private static @Nullable PhysPart parsePhysPart(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("phys:") && !trimmed.startsWith("PHYS:")) {
            return null;
        }
        String body = trimmed.substring(5);
        int starIdx = body.lastIndexOf('*');
        if (starIdx <= 0 || starIdx >= body.length() - 1) {
            return null;
        }
        String escapedKey = body.substring(0, starIdx).trim();
        String countText = body.substring(starIdx + 1).trim();
        Long count = tryParseNonNegativeLong(countText);
        if (count == null || count <= 0L) {
            return null;
        }
        String unescaped = unescapeTierKey(escapedKey);
        if (unescaped == null || unescaped.isEmpty()) {
            return null;
        }
        return new PhysPart(unescaped, count);
    }

    /**
     * 对 tierKey 中的规则分隔符与空白做 %XX 转义，确保写入 {@code target=rule xMax} 永不误断词。
     */
    public static String escapeTierKey(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String trimmed = raw.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch <= ' ' || ch == '%' || ch == '|' || ch == '+' || ch == '*' || ch == '=') {
                sb.append('%');
                int code = ch & 0xFF;
                if (code < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(code).toUpperCase(Locale.ROOT));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    public static @Nullable String unescapeTierKey(@Nullable String escaped) {
        if (escaped == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char ch = escaped.charAt(i);
            if (ch == '%') {
                if (i + 2 >= escaped.length()) {
                    return null;
                }
                int hi = Character.digit(escaped.charAt(i + 1), 16);
                int lo = Character.digit(escaped.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                sb.append((char) ((hi << 4) | lo));
                i += 2;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString().trim();
    }

    private static @Nullable Long tryParseNonNegativeLong(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
        }
        try {
            long val = Long.parseLong(text);
            return val < 0L ? null : val;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String cleanKey(@Nullable String key) {
        return key == null ? "" : key.trim();
    }

    private static long safeMultiply(long a, int qty) {
        if (a <= 0L || qty <= 0) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / qty) {
            return Long.MAX_VALUE;
        }
        return a * qty;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PriceSpec other)) {
            return false;
        }
        return starPrice == other.starPrice
                && physCount == other.physCount
                && mode == other.mode
                && physTierKey.equals(other.physTierKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, starPrice, physTierKey, physCount);
    }

    @Override
    public String toString() {
        return "PriceSpec{" + toRuleText() + "}";
    }
}
