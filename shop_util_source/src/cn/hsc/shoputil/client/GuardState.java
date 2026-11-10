package cn.hsc.shoputil.client;

import cn.hsc.shoputil.Log;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 商店守卫状态：锁定/解锁、管理员 UUID。
 * 所有文件读取都带缓存，避免反复读盘。
 */
public final class GuardState {

    public static boolean unlocked = false;
    public static boolean adminOverride = false;

    private static List<String> cachedAdmins = null;
    private static String cachedAdminsPlayer = null;

    private GuardState() {}

    public static boolean isUnlocked() {
        return unlocked || adminOverride;
    }

    /**
     * 检查当前玩家是否是管理员。带缓存，只在玩家切换时重新读文件。
     */
    public static void checkAdmin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { adminOverride = false; cachedAdmins = null; cachedAdminsPlayer = null; return; }
        String uuid = mc.player.getUUID().toString();
        if (uuid.equals(cachedAdminsPlayer) && cachedAdmins != null) {
            adminOverride = cachedAdmins.stream().anyMatch(u -> u.equals(uuid));
            return;
        }
        try {
            Path file = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("admins.txt");
            if (Files.isRegularFile(file)) {
                cachedAdmins = Files.readAllLines(file);
                cachedAdminsPlayer = uuid;
                adminOverride = cachedAdmins.stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                        .anyMatch(u -> u.equals(uuid));
            } else {
                cachedAdmins = List.of();
                cachedAdminsPlayer = uuid;
                adminOverride = false;
            }
        } catch (Exception e) {
            Log.warn("读取 admins.txt 失败: " + e.getMessage());
            cachedAdmins = List.of();
            cachedAdminsPlayer = uuid;
            adminOverride = false;
        }
    }

    public static String getPassword() {
        return cn.hsc.shoputil.ShopUtilConfig.PASSWORD.get();
    }
}
