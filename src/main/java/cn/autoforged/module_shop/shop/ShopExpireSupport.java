package cn.autoforged.module_shop.shop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 摊位 / 求购下架时间配置、退件拆堆与 TOML 原子回写的纯逻辑辅助类（便于 1.21.1 / 1.20.1 共用与单元测试）。
 */
public final class ShopExpireSupport {
    public static final int MAX_EXPIRE_AMOUNT = 100_000;

    private ShopExpireSupport() {
    }

    /** 归一化下架方式：仅接受 {@code game} / {@code real}，其余一律视为 {@code permanent}。 */
    public static String normalizeExpireMode(String raw) {
        if (raw == null) {
            return "permanent";
        }
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if ("game".equals(clean) || "real".equals(clean)) {
            return clean;
        }
        return "permanent";
    }

    /** 限时下架是否实际生效（permanent 或 amount <= 0 均视作永不下架）。 */
    public static boolean isExpireActive(String mode, int amount) {
        return !"permanent".equals(normalizeExpireMode(mode)) && amount > 0;
    }

    /**
     * 解析管理员输入的下架时长（0..100000）。
     * <p>当模式为 {@code permanent} 且输入为空串时，允许直接沿用当前值，避免选「永久」并清空输入框时被误拦截。
     *
     * @return 合法时长；输入非法或越界时返回 {@code null}
     */
    public static Integer parseExpireAmount(String mode, String rawAmount, int fallbackAmount) {
        String cleanMode = normalizeExpireMode(mode);
        String trimmed = rawAmount == null ? "" : rawAmount.trim();
        if ("permanent".equals(cleanMode) && trimmed.isEmpty()) {
            return Math.max(0, Math.min(MAX_EXPIRE_AMOUNT, fallbackAmount));
        }
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (parsed < 0 || parsed > MAX_EXPIRE_AMOUNT) {
            return null;
        }
        return parsed;
    }

    /**
     * 判断切换下架配置时是否需要重置全服存量商品/求购的上架时间戳：
     * <ul>
     *     <li>从「永久 / 0」切换到限时模式 → 需要重置，避免老商品 1 秒内全部被下架；</li>
     *     <li>在 {@code game} 与 {@code real} 两种计时口径之间互切 → 需要重置，避免另一种时钟的历史时间戳直接超限；</li>
     *     <li>目标为永久或同模式内仅调整数值 → 不重置。</li>
     * </ul>
     */
    public static boolean shouldResetExpireTimestamps(String oldMode, int oldAmount, String newMode, int newAmount) {
        if (!isExpireActive(newMode, newAmount)) {
            return false;
        }
        if (!isExpireActive(oldMode, oldAmount)) {
            return true;
        }
        return !normalizeExpireMode(oldMode).equals(normalizeExpireMode(newMode));
    }

    /** 每日例行清理是否应当推进（仅当现实日期严格前进时执行，忽略系统时钟偶发回拨）。 */
    public static boolean shouldAdvanceDailyDay(long currentDay, long lastDay) {
        return currentDay > lastDay;
    }

    /**
     * 活动日当前小时是否允许向登录/在线玩家发放：
     * <ul>
     *     <li>{@code scheduledHour < 0}（不定时）→ 始终允许；</li>
     *     <li>{@code nowHour < scheduledHour}（未到定时点）→ 始终拒绝（无论 catchUp/forOffline）；</li>
     *     <li>{@code nowHour == scheduledHour}（正好到点）→ 允许；</li>
     *     <li>{@code nowHour > scheduledHour}（错过定时点）→ 仅当 {@code catchUp || forOffline} 允许补发。</li>
     * </ul>
     */
    public static boolean canGrantActivityDayAtHour(int scheduledHour, int nowHour, boolean catchUp, boolean forOffline) {
        if (scheduledHour < 0) {
            return true;
        }
        if (nowHour < scheduledHour) {
            return false;
        }
        return nowHour == scheduledHour || catchUp || forOffline;
    }

    /** 活动日离线全服补发当前小时是否允许执行（不定时或已到达/超过定时点）。 */
    public static boolean canGrantActivityOfflineAtHour(int scheduledHour, int nowHour) {
        return scheduledHour < 0 || nowHour >= scheduledHour;
    }

    /** 按单叠上限拆分数量，保证每一份均在 {@code [1, max(1, maxStackSize)]} 内且总和守恒。 */
    public static int[] splitStackCounts(int totalCount, int maxStackSize) {
        if (totalCount <= 0) {
            return new int[0];
        }
        int per = Math.max(1, maxStackSize);
        int chunks = (totalCount + per - 1) / per;
        int[] out = new int[chunks];
        int left = totalCount;
        for (int i = 0; i < chunks; i++) {
            int take = Math.min(per, left);
            out[i] = take;
            left -= take;
        }
        return out;
    }

    /**
     * 在 TOML 行列表中仅修改指定 {@code [sectionName]} 下的键值，不误伤其他节或注释。
     * 若目标节不存在则追加到文末；若节存在但缺少部分键则补在下一节前（或文末）。
     */
    public static List<String> patchTomlSectionKeys(List<String> lines, String sectionName,
            Map<String, String> formattedValues) {
        List<String> result = new ArrayList<>(lines == null ? List.of() : lines);
        if (sectionName == null || sectionName.isBlank() || formattedValues == null || formattedValues.isEmpty()) {
            return result;
        }
        String targetHeader = "[" + sectionName.trim() + "]";
        Map<String, String> remaining = new LinkedHashMap<>(formattedValues);
        boolean inTarget = false;
        boolean sectionFound = false;
        int sectionEndIdx = result.size();
        String sectionIndent = "\t";

        for (int i = 0; i < result.size(); i++) {
            String rawLine = result.get(i);
            String trimmed = rawLine.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.startsWith("[[")) {
                if (inTarget) {
                    sectionEndIdx = i;
                    inTarget = false;
                    break;
                }
                if (targetHeader.equals(trimmed)) {
                    inTarget = true;
                    sectionFound = true;
                }
                continue;
            }
            if (!inTarget || trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            int keyStart = rawLine.indexOf(key);
            if (keyStart >= 0) {
                sectionIndent = rawLine.substring(0, keyStart);
            }
            if (remaining.containsKey(key)) {
                String value = remaining.remove(key);
                result.set(i, sectionIndent + key + " = " + value);
            }
        }

        if (sectionFound && !remaining.isEmpty()) {
            List<String> inserts = new ArrayList<>(remaining.size());
            for (Map.Entry<String, String> entry : remaining.entrySet()) {
                inserts.add(sectionIndent + entry.getKey() + " = " + entry.getValue());
            }
            result.addAll(sectionEndIdx, inserts);
        } else if (!sectionFound) {
            if (!result.isEmpty() && !result.get(result.size() - 1).isEmpty()) {
                result.add("");
            }
            result.add(targetHeader);
            for (Map.Entry<String, String> entry : remaining.entrySet()) {
                result.add("\t" + entry.getKey() + " = " + entry.getValue());
            }
        }
        return result;
    }

    /**
     * 原子回写 TOML 指定节的配置键（先写临时文件再原子替换，规避 Forge 1.20.1 多连写与 FileWatcher 竞态及异常丢配置）。
     */
    public static boolean writeTomlSectionAtomic(Path tomlPath, String sectionName,
            Map<String, String> formattedValues) {
        if (tomlPath == null || !Files.exists(tomlPath)) {
            return false;
        }
        Path parent = tomlPath.getParent();
        Path temp = null;
        try {
            List<String> lines = Files.readAllLines(tomlPath, StandardCharsets.UTF_8);
            List<String> patched = patchTomlSectionKeys(lines, sectionName, formattedValues);
            String body = String.join(System.lineSeparator(), patched) + System.lineSeparator();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (parent != null) {
                temp = Files.createTempFile(parent, "module_shop-cfg-", ".tmp");
                Files.write(temp, bytes);
                try {
                    Files.move(temp, tomlPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ex) {
                    Files.move(temp, tomlPath, StandardCopyOption.REPLACE_EXISTING);
                }
                temp = null;
            } else {
                Files.write(tomlPath, bytes);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
