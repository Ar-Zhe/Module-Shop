package cn.autoforged.module_shop.shop.home;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.neoforged.fml.ModList;

/**
 * 服务器个人主页服务端统计与称号元数据提取。
 *
 * <p>在线时长直接读取服务端 {@link Stats#PLAY_TIME}（刻）；
 * LuckPerms 为零编译依赖的纯运行时软联动（未安装或读取失败时安全回退）。
 */
public final class ProfileStatsService {
    private static final String LUCKPERMS_MODID = "luckperms";
    private static final Pattern AMPERSAND_LEGACY = Pattern.compile("&([0-9a-fA-Fk-oK-OrRxX])");
    private static final Pattern HEX_AMPERSAND = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern BUNGEE_HEX = Pattern.compile("(?i)[&§]x(?:[&§]([0-9a-f])){6}");
    private static final Pattern MINIMESSAGE_TAG = Pattern.compile("</?[a-zA-Z0-9_:#-]+>");

    /** 标准 Minecraft 16 调色板（RGB），用于将 LuckPerms 十六进制颜色逼近为客户端 StyledText 可渲染的 § 代码。 */
    private static final int[] LEGACY_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    private static final char[] LEGACY_HEX_CHARS = "0123456789abcdef".toCharArray();

    private static volatile boolean lpLookupInitialized;
    private static volatile boolean lpReflectionReady;
    private static Method lpProviderGet;
    private static Method lpGetPlayerAdapter;
    private static Method adapterGetMetaData;
    private static Method metaGetPrimaryGroup;
    private static Method metaGetPrefix;

    private ProfileStatsService() {
    }

    public record StatsSnapshot(long playTimeTicks, boolean lpAvailable, String lpGroup, String lpPrefix) {
    }

    public static StatsSnapshot collect(ServerPlayer player) {
        long playTimeTicks = readPlayTimeTicks(player);
        LpMeta lp = readLuckPermsMeta(player);
        return new StatsSnapshot(playTimeTicks, lp.available(), lp.group(), lp.prefix());
    }

    public static void writeSnapshot(ServerPlayer player, CompoundTag snapshotTag) {
        if (snapshotTag == null) {
            return;
        }
        StatsSnapshot stats = collect(player);
        snapshotTag.putLong("playTimeTicks", stats.playTimeTicks());
        snapshotTag.putBoolean("lpAvailable", stats.lpAvailable());
        snapshotTag.putString("lpGroup", stats.lpGroup());
        snapshotTag.putString("lpPrefix", stats.lpPrefix());
    }

    public static long readPlayTimeTicks(ServerPlayer player) {
        if (player == null) {
            return 0L;
        }
        try {
            return Math.max(0L, (long) player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private record LpMeta(boolean available, String group, String prefix) {
        static final LpMeta UNAVAILABLE = new LpMeta(false, "", "");
    }

    private static LpMeta readLuckPermsMeta(ServerPlayer player) {
        if (player == null) {
            return LpMeta.UNAVAILABLE;
        }
        try {
            ModList modList = ModList.get();
            if (modList == null || !modList.isLoaded(LUCKPERMS_MODID)) {
                return LpMeta.UNAVAILABLE;
            }
        } catch (Throwable ignored) {
            return LpMeta.UNAVAILABLE;
        }

        ensureLuckPermsReflection();
        if (!lpReflectionReady) {
            return LpMeta.UNAVAILABLE;
        }

        try {
            Object lp = lpProviderGet.invoke(null);
            if (lp == null) {
                return LpMeta.UNAVAILABLE;
            }
            Object adapter = lpGetPlayerAdapter.invoke(lp, ServerPlayer.class);
            if (adapter == null) {
                return LpMeta.UNAVAILABLE;
            }
            Object meta = adapterGetMetaData.invoke(adapter, player);
            if (meta == null) {
                return new LpMeta(true, "", "");
            }
            Object rawGroup = metaGetPrimaryGroup.invoke(meta);
            Object rawPrefix = metaGetPrefix.invoke(meta);
            String group = rawGroup instanceof String s ? translateColorCodes(s.trim()) : "";
            String prefix = rawPrefix instanceof String s ? translateColorCodes(s.trim()) : "";
            return new LpMeta(true, group, prefix);
        } catch (Throwable ignored) {
            return LpMeta.UNAVAILABLE;
        }
    }

    private static synchronized void ensureLuckPermsReflection() {
        if (lpLookupInitialized) {
            return;
        }
        lpLookupInitialized = true;
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Class<?> apiClass = Class.forName("net.luckperms.api.LuckPerms");
            Class<?> adapterClass = Class.forName("net.luckperms.api.platform.PlayerAdapter");
            Class<?> metaClass = Class.forName("net.luckperms.api.cacheddata.CachedMetaData");

            lpProviderGet = providerClass.getMethod("get");
            lpGetPlayerAdapter = apiClass.getMethod("getPlayerAdapter", Class.class);
            adapterGetMetaData = adapterClass.getMethod("getMetaData", Object.class);
            metaGetPrimaryGroup = metaClass.getMethod("getPrimaryGroup");
            metaGetPrefix = metaClass.getMethod("getPrefix");
            lpReflectionReady = true;
        } catch (Throwable ignored) {
            lpReflectionReady = false;
        }
    }

    /**
     * 将 LuckPerms 常见颜色格式（{@code &a}、{@code &#RRGGBB}、Bungee {@code &x&R&R...}）
     * 规范为客户端 {@code StyledText} 可直接渲染的 {@code §} 样式文本。
     */
    public static String translateColorCodes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw;
        // 1. BungeeCord hex (&x&r&r&g&g&b&b -> nearest §)
        Matcher bungeeMatcher = BUNGEE_HEX.matcher(text);
        if (bungeeMatcher.find()) {
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            do {
                sb.append(text, lastEnd, bungeeMatcher.start());
                String matched = bungeeMatcher.group();
                StringBuilder hexDigits = new StringBuilder(6);
                for (int i = 2; i < matched.length(); i++) {
                    char ch = matched.charAt(i);
                    if (ch != '&' && ch != '§') {
                        hexDigits.append(ch);
                    }
                }
                sb.append('§').append(nearestLegacyChar(hexDigits.toString()));
                lastEnd = bungeeMatcher.end();
            } while (bungeeMatcher.find());
            sb.append(text.substring(lastEnd));
            text = sb.toString();
        }
        // 2. &#RRGGBB -> nearest §
        Matcher hexMatcher = HEX_AMPERSAND.matcher(text);
        if (hexMatcher.find()) {
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            do {
                sb.append(text, lastEnd, hexMatcher.start());
                sb.append('§').append(nearestLegacyChar(hexMatcher.group(1)));
                lastEnd = hexMatcher.end();
            } while (hexMatcher.find());
            sb.append(text.substring(lastEnd));
            text = sb.toString();
        }
        // 3. MiniMessage tags (if LuckPerms prefix uses simple <...> tags)
        if (text.indexOf('<') >= 0 && text.indexOf('>') > text.indexOf('<')) {
            text = MINIMESSAGE_TAG.matcher(text).replaceAll("");
        }
        // 4. Standard &c -> §c
        Matcher legacyMatcher = AMPERSAND_LEGACY.matcher(text);
        if (legacyMatcher.find()) {
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            do {
                sb.append(text, lastEnd, legacyMatcher.start());
                sb.append('§').append(legacyMatcher.group(1).toLowerCase(Locale.ROOT));
                lastEnd = legacyMatcher.end();
            } while (legacyMatcher.find());
            sb.append(text.substring(lastEnd));
            text = sb.toString();
        }
        return text;
    }

    private static char nearestLegacyChar(String hex6) {
        try {
            int rgb = Integer.parseInt(hex6, 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int bestIdx = 15;
            long bestDist = Long.MAX_VALUE;
            for (int i = 0; i < LEGACY_RGB.length; i++) {
                int pr = (LEGACY_RGB[i] >> 16) & 0xFF;
                int pg = (LEGACY_RGB[i] >> 8) & 0xFF;
                int pb = LEGACY_RGB[i] & 0xFF;
                long dr = r - pr;
                long dg = g - pg;
                long db = b - pb;
                long dist = dr * dr + dg * dg + db * db;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                }
            }
            return LEGACY_HEX_CHARS[bestIdx];
        } catch (NumberFormatException ignored) {
            return 'f';
        }
    }
}
