package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.client.screen.AdminAuthScreen;
import cn.autoforged.module_shop.client.screen.AdminSecurityScreen;
import cn.autoforged.module_shop.client.screen.AnnouncementAdminScreen;
import cn.autoforged.module_shop.client.screen.BrandSettingsScreen;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 客户端管理密码锁协调器：
 * <ul>
 *     <li>统一拦截入口 {@link #guard(Runnable)}：未开启密码锁或会话已解锁时直接执行，否则挂起动作并弹出 {@link AdminAuthScreen}；</li>
 *     <li>处理服务端回包 {@code kind="admin_auth_result"} 与主动解锁提示 {@code kind="admin_auth_prompt"}；</li>
 *     <li>绝不在客户端持久化明文密码，仅同步服务端权威锁状态与解锁会话标志。</li>
 * </ul>
 */
public final class ClientAdminAuth {
    private static @Nullable Runnable pendingAction;
    private static long cooldownUntilMs;
    private static String lastNotice = "";

    private ClientAdminAuth() {
    }

    /** 管理员动作前置守卫：无 OP 直接忽略；已解锁直接放行；锁定态弹密码框并在解锁后自动续接。 */
    public static void guard(@Nullable Runnable onUnlocked) {
        if (!ClientShopData.canAdmin) {
            return;
        }
        if (!ClientShopData.adminLockEnabled || ClientShopData.adminUnlocked) {
            if (onUnlocked != null) {
                onUnlocked.run();
            }
            return;
        }
        pendingAction = onUnlocked;
        Minecraft mc = Minecraft.getInstance();
        Nav.open(new AdminAuthScreen(mc == null ? null : mc.screen, null));
    }

    public static void sendUnlock(String password) {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "admin_auth_unlock");
        tag.putString("password", password == null ? "" : password);
        ServerboundShopActionPayload.send(tag);
    }

    public static void sendLock() {
        pendingAction = null;
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "admin_auth_lock");
        ServerboundShopActionPayload.send(tag);
    }

    public static void sendConfigure(boolean enable, String currentPassword, String newPassword, int timeoutMinutes) {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "admin_auth_configure");
        tag.putBoolean("enable", enable);
        tag.putString("currentPassword", currentPassword == null ? "" : currentPassword);
        tag.putString("newPassword", newPassword == null ? "" : newPassword);
        tag.putInt("timeoutMinutes", Math.max(0, timeoutMinutes));
        ServerboundShopActionPayload.send(tag);
    }

    public static int cooldownRemainingSeconds() {
        long rem = cooldownUntilMs - System.currentTimeMillis();
        return rem <= 0L ? 0 : (int) ((rem + 999L) / 1000L);
    }

    public static String consumeLastNotice() {
        String s = lastNotice;
        lastNotice = "";
        return s;
    }

    public static void clearState() {
        pendingAction = null;
        cooldownUntilMs = 0L;
        lastNotice = "";
    }

    /** 服务端 {@code kind="admin_auth_result"} 回包处理。 */
    public static void handleAuthResult(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        applyLockFlags(tag);
        String mode = tag.getString("mode");
        boolean ok = tag.getBoolean("ok");
        String errorKey = tag.getString("errorKey");
        int retryAfter = Math.max(0, tag.getInt("retryAfterSeconds"));
        if (retryAfter > 0) {
            cooldownUntilMs = System.currentTimeMillis() + retryAfter * 1000L;
        } else if (ok) {
            cooldownUntilMs = 0L;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        if ("unlock".equals(mode)) {
            if (ok) {
                if (mc.screen instanceof AdminAuthScreen authScreen) {
                    authScreen.onServerSuccess();
                }
                Runnable next = pendingAction;
                pendingAction = null;
                if (next != null) {
                    mc.execute(next);
                }
            } else {
                String msg = mapErrorMessage(errorKey, retryAfter);
                if (mc.screen instanceof AdminAuthScreen authScreen) {
                    authScreen.onServerError(msg, retryAfter);
                } else {
                    notifyChat(Component.literal(msg).withStyle(ChatFormatting.RED));
                }
            }
            return;
        }

        if ("lock".equals(mode)) {
            pendingAction = null;
            if (ok && mc.screen instanceof AdminSecurityScreen secScreen) {
                secScreen.onServerResult(true, ClientHomeCurrencyState.tr(
                        "msg.module_shop.auth_locked", "管理会话已锁定"));
            }
            return;
        }

        if ("configure".equals(mode)) {
            if (ok) {
                if (mc.screen instanceof AdminSecurityScreen secScreen) {
                    secScreen.onServerResult(true, ClientHomeCurrencyState.tr(
                            "msg.module_shop.auth_saved", "密码锁配置已更新"));
                }
            } else {
                String msg = mapErrorMessage(errorKey, retryAfter);
                if (mc.screen instanceof AdminSecurityScreen secScreen) {
                    secScreen.onServerResult(false, msg);
                } else {
                    notifyChat(Component.literal(msg).withStyle(ChatFormatting.RED));
                }
            }
        }
    }

    /** 服务端在拦截未解锁写请求或收到玩家 `/shop auth` 后下发 {@code kind="admin_auth_prompt"}。 */
    public static void handleAuthPrompt(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        applyLockFlags(tag);
        if (!ClientShopData.canAdmin) {
            return;
        }
        int retryAfter = Math.max(0, tag.getInt("retryAfterSeconds"));
        if (retryAfter > 0) {
            cooldownUntilMs = System.currentTimeMillis() + retryAfter * 1000L;
        }
        String reasonKey = tag.getString("reasonKey");
        lastNotice = reasonKey.isEmpty()
                ? ClientHomeCurrencyState.tr("msg.module_shop.auth_required", "已开启配置保护，请先输入管理密码解锁")
                : mapErrorMessage(reasonKey, retryAfter);

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        if (mc.screen instanceof BrandSettingsScreen bs) {
            bs.cancelUploadIndicator();
        } else if (mc.screen instanceof AnnouncementAdminScreen aas) {
            aas.cancelUploadIndicator();
        }
        if (mc.screen instanceof AdminAuthScreen authScreen) {
            authScreen.refreshHint(lastNotice, retryAfter);
            return;
        }
        Nav.openPushed(new AdminAuthScreen(mc.screen, null));
    }

    private static void applyLockFlags(CompoundTag tag) {
        if (tag.contains("adminLockEnabled")) {
            ClientShopData.adminLockEnabled = tag.getBoolean("adminLockEnabled");
        }
        if (tag.contains("adminUnlocked")) {
            ClientShopData.adminUnlocked = tag.getBoolean("adminUnlocked");
        }
        if (tag.contains("adminSessionTimeout")) {
            ClientShopData.adminSessionTimeout = Math.max(0, tag.getInt("adminSessionTimeout"));
        }
    }

    private static String mapErrorMessage(String errorKey, int retryAfter) {
        if ("cooldown".equals(errorKey) || "msg.module_shop.auth_cooldown".equals(errorKey) || retryAfter > 0) {
            return ClientHomeCurrencyState.tr("msg.module_shop.auth_cooldown",
                    "尝试次数过多，请等待 %s 秒后重试", Math.max(1, retryAfter));
        }
        if ("wrong_password".equals(errorKey) || "msg.module_shop.auth_wrong_password".equals(errorKey)) {
            return ClientHomeCurrencyState.tr("msg.module_shop.auth_wrong_password", "密码错误");
        }
        if ("password_too_short".equals(errorKey)) {
            return ClientHomeCurrencyState.tr("gui.module_shop.auth.err_short", "密码长度不能少于 4 位");
        }
        if ("auth_required".equals(errorKey) || "locked".equals(errorKey)
                || "msg.module_shop.auth_required".equals(errorKey)) {
            return ClientHomeCurrencyState.tr("msg.module_shop.auth_required", "已开启配置保护，请先输入管理密码解锁");
        }
        if ("io_error".equals(errorKey)) {
            return ClientHomeCurrencyState.tr("msg.module_shop.cfg_save_fail", "保存失败：无法写入文件");
        }
        if (errorKey != null && (errorKey.startsWith("msg.module_shop.") || errorKey.startsWith("gui.module_shop."))) {
            return Component.translatable(errorKey).getString();
        }
        return errorKey == null || errorKey.isBlank()
                ? ClientHomeCurrencyState.tr("msg.module_shop.auth_wrong_password", "密码错误")
                : errorKey;
    }

    private static void notifyChat(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(msg, false);
        }
    }
}
