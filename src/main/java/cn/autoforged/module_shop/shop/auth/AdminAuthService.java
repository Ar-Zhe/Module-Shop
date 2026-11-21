package cn.autoforged.module_shop.shop.auth;

import cn.autoforged.module_shop.config.ShopTemplateStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理员密码锁服务（默认关闭）。
 *
 * <p>同时优化「整合包单机防误改」与「多人专用服防协管越权/防死锁」双场景：
 * <ul>
 *     <li>配置仅存加盐 PBKDF2-HMAC-SHA256 哈希，绝不保存或同步明文；</li>
 *     <li>必须同时具备 {@code hasPermissions(2)} 与内存解锁会话（OP ∧ 会话解锁）；</li>
 *     <li>物理服务端控制台可通过 {@code /shop auth disable} 免密重置以防死锁。</li>
 * </ul>
 */
public final class AdminAuthService {
    public static final String ALGO = "PBKDF2WithHmacSHA256";
    public static final int DEFAULT_ITERATIONS = 120_000;
    public static final int KEY_BITS = 256;
    public static final int SALT_BYTES = 16;
    public static final int DEFAULT_TIMEOUT_MINUTES = 30;
    public static final int MAX_TIMEOUT_MINUTES = 1440;
    public static final int MIN_PASSWORD_LENGTH = 4;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final int FAIL_LIMIT = 5;
    private static final long FAIL_WINDOW_MS = 5 * 60_000L;
    private static final int BASE_COOLDOWN_SEC = 60;
    private static final int MAX_COOLDOWN_SEC = 300;
    private static final String FILE_NAME = "admin_auth.json";

    private static final Logger LOG = LoggerFactory.getLogger("module_shop/auth");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final SecureRandom RNG = new SecureRandom();

    private static final Map<UUID, UnlockSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, FailureTracker> FAILURES = new ConcurrentHashMap<>();

    private static volatile AuthState state;
    private static volatile @Nullable Path overrideConfigDir;

    public record ExportedAuth(boolean enabled, String algo, int iterations, String salt, String hash,
                               int sessionTimeoutMinutes) {
    }

    public record UnlockOutcome(boolean ok, String errorKey, int retryAfterSeconds) {
    }

    public record SaveAuthOutcome(boolean ok, String errorKey) {
    }

    private static final class AuthState {
        boolean enabled;
        String algo = ALGO;
        int iterations = DEFAULT_ITERATIONS;
        String salt = "";
        String hash = "";
        int sessionTimeoutMinutes = DEFAULT_TIMEOUT_MINUTES;
    }

    private static final class UnlockSession {
        long lastActiveMs;

        UnlockSession(long nowMs) {
            this.lastActiveMs = nowMs;
        }
    }

    private static final class FailureTracker {
        int count;
        long windowStartMs;
        long lockedUntilMs;
        int lockTier;
    }

    private AdminAuthService() {
    }

    private static Path authFile() {
        Path base = overrideConfigDir != null ? overrideConfigDir : FMLPaths.CONFIGDIR.get();
        return base.resolve(ShopTemplateStore.DIR_NAME).resolve(FILE_NAME);
    }

    private static AuthState getState() {
        AuthState cur = state;
        if (cur == null) {
            synchronized (AdminAuthService.class) {
                cur = state;
                if (cur == null) {
                    cur = loadFromDisk(authFile());
                    state = cur;
                }
            }
        }
        return cur;
    }

    public static synchronized void reload() {
        reload(FMLPaths.CONFIGDIR.get());
    }

    public static synchronized void reload(@Nullable Path configDir) {
        Path prevDir = overrideConfigDir;
        overrideConfigDir = configDir;
        AuthState prev = state;
        AuthState next = loadFromDisk(authFile());
        state = next;
        boolean dirChanged = configDir != null && !configDir.equals(prevDir)
                || (configDir == null && prevDir != null);
        if (dirChanged || prev == null || prev.enabled != next.enabled
                || !safeStr(prev.hash).equals(safeStr(next.hash))
                || !safeStr(prev.salt).equals(safeStr(next.salt))) {
            SESSIONS.clear();
            FAILURES.clear();
        }
    }

    public static boolean isLockEnabled() {
        AuthState s = getState();
        return s.enabled && !s.salt.isEmpty() && !s.hash.isEmpty();
    }

    public static int sessionTimeoutMinutes() {
        return clampTimeout(getState().sessionTimeoutMinutes);
    }

    public static boolean isUnlocked(@Nullable ServerPlayer player) {
        return player != null && isUnlocked(player.getUUID());
    }

    public static boolean isUnlocked(@Nullable UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (!isLockEnabled()) {
            return true;
        }
        UnlockSession session = SESSIONS.get(playerId);
        if (session == null) {
            return false;
        }
        int timeoutMin = sessionTimeoutMinutes();
        long now = System.currentTimeMillis();
        if (timeoutMin > 0) {
            long ttlMs = timeoutMin * 60_000L;
            if (now - session.lastActiveMs > ttlMs) {
                SESSIONS.remove(playerId);
                return false;
            }
        }
        session.lastActiveMs = now;
        return true;
    }

    public static boolean canEditAdmin(@Nullable ServerPlayer player) {
        return player != null && canEditAdmin(player.getUUID(), player.hasPermissions(2));
    }

    public static boolean canEditAdmin(@Nullable UUID playerId, boolean hasOp) {
        return playerId != null && hasOp && isUnlocked(playerId);
    }

    public static UnlockOutcome tryUnlock(@Nullable ServerPlayer player, @Nullable String password) {
        if (player == null) {
            return new UnlockOutcome(false, "msg.module_shop.auth_required", 0);
        }
        return tryUnlock(player.getUUID(), player.hasPermissions(2), password, player.getGameProfile().getName());
    }

    public static UnlockOutcome tryUnlock(@Nullable UUID playerId, boolean hasOp, @Nullable String password) {
        return tryUnlock(playerId, hasOp, password, playerId == null ? "unknown" : playerId.toString());
    }

    private static UnlockOutcome tryUnlock(@Nullable UUID playerId, boolean hasOp,
            @Nullable String password, String actorName) {
        if (playerId == null || !hasOp) {
            return new UnlockOutcome(false, "msg.module_shop.auth_required", 0);
        }
        AuthState s = getState();
        long now = System.currentTimeMillis();
        if (!isLockEnabled()) {
            SESSIONS.put(playerId, new UnlockSession(now));
            return new UnlockOutcome(true, "", 0);
        }
        FailureTracker tracker = FAILURES.computeIfAbsent(playerId, k -> new FailureTracker());
        synchronized (tracker) {
            if (tracker.lockedUntilMs > now) {
                int remain = (int) Math.max(1L, (tracker.lockedUntilMs - now + 999L) / 1000L);
                return new UnlockOutcome(false, "msg.module_shop.auth_cooldown", remain);
            }
            if (verifyPassword(password == null ? "" : password, s)) {
                FAILURES.remove(playerId);
                SESSIONS.put(playerId, new UnlockSession(now));
                return new UnlockOutcome(true, "", 0);
            }
            if (now - tracker.windowStartMs > FAIL_WINDOW_MS) {
                tracker.count = 0;
                tracker.windowStartMs = now;
            }
            tracker.count++;
            if (tracker.count >= FAIL_LIMIT) {
                int shift = Math.min(3, tracker.lockTier);
                int cooldown = Math.min(MAX_COOLDOWN_SEC, BASE_COOLDOWN_SEC << shift);
                tracker.lockedUntilMs = now + cooldown * 1000L;
                tracker.count = 0;
                tracker.lockTier = Math.min(3, tracker.lockTier + 1);
                LOG.warn("[ModuleShop] 玩家 {} 连续输入错误管理密码，冷却 {} 秒", actorName, cooldown);
                return new UnlockOutcome(false, "msg.module_shop.auth_cooldown", cooldown);
            }
            return new UnlockOutcome(false, "msg.module_shop.auth_wrong_password", 0);
        }
    }

    public static synchronized SaveAuthOutcome configure(@Nullable ServerPlayer actor, boolean enable,
            @Nullable String currentPassword, @Nullable String newPassword, int timeoutMinutes) {
        if (actor == null) {
            return new SaveAuthOutcome(false, "msg.module_shop.auth_required");
        }
        return configure(actor.getUUID(), actor.hasPermissions(2), enable, currentPassword, newPassword, timeoutMinutes);
    }

    public static synchronized SaveAuthOutcome configure(@Nullable UUID actorId, boolean hasOp, boolean enable,
            @Nullable String currentPassword, @Nullable String newPassword, int timeoutMinutes) {
        if (actorId == null || !hasOp) {
            return new SaveAuthOutcome(false, "msg.module_shop.auth_required");
        }
        AuthState current = getState();
        int clampedTimeout = clampTimeout(timeoutMinutes);
        String curPwd = currentPassword == null ? "" : currentPassword;
        String nextPwd = newPassword == null ? "" : newPassword;

        if (!isLockEnabled()) {
            if (!enable) {
                AuthState updated = new AuthState();
                updated.enabled = false;
                updated.sessionTimeoutMinutes = clampedTimeout;
                if (!writeAtomic(authFile(), updated)) {
                    return new SaveAuthOutcome(false, "msg.module_shop.action_failed");
                }
                state = updated;
                return new SaveAuthOutcome(true, "");
            }
            if (nextPwd.length() < MIN_PASSWORD_LENGTH || nextPwd.length() > MAX_PASSWORD_LENGTH) {
                return new SaveAuthOutcome(false, "msg.module_shop.auth_wrong_password");
            }
            AuthState updated = buildEnabledState(nextPwd, clampedTimeout);
            if (!writeAtomic(authFile(), updated)) {
                return new SaveAuthOutcome(false, "msg.module_shop.action_failed");
            }
            state = updated;
            SESSIONS.clear();
            FAILURES.clear();
            SESSIONS.put(actorId, new UnlockSession(System.currentTimeMillis()));
            return new SaveAuthOutcome(true, "");
        }

        // 当前已启用密码锁：
        if (!enable) {
            if (!verifyPassword(curPwd, current)) {
                return new SaveAuthOutcome(false, "msg.module_shop.auth_wrong_password");
            }
            AuthState updated = new AuthState();
            updated.enabled = false;
            updated.sessionTimeoutMinutes = clampedTimeout;
            if (!writeAtomic(authFile(), updated)) {
                return new SaveAuthOutcome(false, "msg.module_shop.action_failed");
            }
            state = updated;
            SESSIONS.clear();
            FAILURES.clear();
            return new SaveAuthOutcome(true, "");
        }

        boolean changingPassword = !nextPwd.isEmpty();
        if (changingPassword) {
            if (!verifyPassword(curPwd, current)) {
                return new SaveAuthOutcome(false, "msg.module_shop.auth_wrong_password");
            }
            if (nextPwd.length() < MIN_PASSWORD_LENGTH || nextPwd.length() > MAX_PASSWORD_LENGTH) {
                return new SaveAuthOutcome(false, "msg.module_shop.auth_wrong_password");
            }
            AuthState updated = buildEnabledState(nextPwd, clampedTimeout);
            if (!writeAtomic(authFile(), updated)) {
                return new SaveAuthOutcome(false, "msg.module_shop.action_failed");
            }
            state = updated;
            SESSIONS.clear();
            FAILURES.clear();
            SESSIONS.put(actorId, new UnlockSession(System.currentTimeMillis()));
            return new SaveAuthOutcome(true, "");
        }

        // 仅改会话超时：需要当前处于已解锁或提供正确当前密码
        if (!isUnlocked(actorId) && !verifyPassword(curPwd, current)) {
            return new SaveAuthOutcome(false, "msg.module_shop.auth_required");
        }
        AuthState updated = new AuthState();
        updated.enabled = true;
        updated.algo = current.algo;
        updated.iterations = current.iterations;
        updated.salt = current.salt;
        updated.hash = current.hash;
        updated.sessionTimeoutMinutes = clampedTimeout;
        if (!writeAtomic(authFile(), updated)) {
            return new SaveAuthOutcome(false, "msg.module_shop.action_failed");
        }
        state = updated;
        SESSIONS.put(actorId, new UnlockSession(System.currentTimeMillis()));
        return new SaveAuthOutcome(true, "");
    }

    public static void lockPlayer(UUID uuid) {
        if (uuid != null) {
            SESSIONS.remove(uuid);
        }
    }

    public static void onLogout(UUID uuid) {
        if (uuid != null) {
            SESSIONS.remove(uuid);
        }
    }

    public static synchronized void consoleDisable() {
        AuthState next = new AuthState();
        next.enabled = false;
        next.sessionTimeoutMinutes = sessionTimeoutMinutes();
        writeAtomic(authFile(), next);
        state = next;
        SESSIONS.clear();
        FAILURES.clear();
        LOG.info("[ModuleShop] 控制台已强制关闭管理员密码锁");
    }

    public static @Nullable ExportedAuth exportForTemplate() {
        AuthState s = getState();
        if (!isLockEnabled()) {
            return null;
        }
        return new ExportedAuth(true, s.algo, s.iterations, s.salt, s.hash, clampTimeout(s.sessionTimeoutMinutes));
    }

    public static synchronized void importFromTemplateIfUnconfigured(@Nullable ExportedAuth auth) {
        if (auth == null || !auth.enabled() || isLockEnabled()) {
            return;
        }
        if (!ALGO.equals(auth.algo()) || isBlank(auth.salt()) || isBlank(auth.hash())) {
            return;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(auth.salt());
            byte[] hash = Base64.getDecoder().decode(auth.hash());
            if (salt.length < 8 || hash.length < 16) {
                return;
            }
        } catch (IllegalArgumentException ex) {
            return;
        }
        AuthState next = new AuthState();
        next.enabled = true;
        next.algo = ALGO;
        next.iterations = Math.max(10_000, Math.min(1_000_000, auth.iterations()));
        next.salt = auth.salt().trim();
        next.hash = auth.hash().trim();
        next.sessionTimeoutMinutes = clampTimeout(auth.sessionTimeoutMinutes());
        if (writeAtomic(authFile(), next)) {
            state = next;
            SESSIONS.clear();
            FAILURES.clear();
            LOG.info("[ModuleShop] 已从整合包模板启用管理员密码锁");
        }
    }

    public static int activeSessionCount() {
        return SESSIONS.size();
    }

    public static int remainingCooldownSeconds(@Nullable ServerPlayer player) {
        return player == null ? 0 : remainingCooldownSeconds(player.getUUID());
    }

    public static int remainingCooldownSeconds(@Nullable UUID playerId) {
        if (playerId == null || !isLockEnabled()) {
            return 0;
        }
        FailureTracker tracker = FAILURES.get(playerId);
        if (tracker == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        synchronized (tracker) {
            if (tracker.lockedUntilMs <= now) {
                return 0;
            }
            return (int) Math.max(1L, (tracker.lockedUntilMs - now + 999L) / 1000L);
        }
    }

    private static AuthState buildEnabledState(String password, int timeoutMinutes) {
        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, DEFAULT_ITERATIONS);
        AuthState next = new AuthState();
        next.enabled = true;
        next.algo = ALGO;
        next.iterations = DEFAULT_ITERATIONS;
        next.salt = Base64.getEncoder().encodeToString(salt);
        next.hash = Base64.getEncoder().encodeToString(derived);
        next.sessionTimeoutMinutes = clampTimeout(timeoutMinutes);
        return next;
    }

    private static boolean verifyPassword(String rawPassword, AuthState s) {
        if (!s.enabled || isBlank(s.salt) || isBlank(s.hash) || rawPassword == null || rawPassword.isEmpty()) {
            return false;
        }
        if (!ALGO.equals(s.algo) || s.iterations < 10_000 || s.iterations > 1_000_000) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(s.salt);
            byte[] expected = Base64.getDecoder().decode(s.hash);
            byte[] actual = pbkdf2(rawPassword, salt, s.iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGO).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PBKDF2 unavailable", ex);
        }
    }

    private static AuthState loadFromDisk(Path file) {
        AuthState fallback = new AuthState();
        try {
            if (!Files.isRegularFile(file)) {
                return fallback;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            AuthState parsed = GSON.fromJson(raw, AuthState.class);
            if (parsed == null) {
                return fallback;
            }
            parsed.algo = ALGO.equals(parsed.algo) ? parsed.algo : ALGO;
            parsed.iterations = parsed.iterations > 0 ? parsed.iterations : DEFAULT_ITERATIONS;
            parsed.salt = safeStr(parsed.salt);
            parsed.hash = safeStr(parsed.hash);
            parsed.sessionTimeoutMinutes = clampTimeout(parsed.sessionTimeoutMinutes);
            if (!parsed.enabled || parsed.salt.isEmpty() || parsed.hash.isEmpty()) {
                parsed.enabled = false;
            }
            return parsed;
        } catch (Throwable ex) {
            LOG.warn("[ModuleShop] 读取 {} 失败，密码锁默认回退关闭：{}", FILE_NAME, ex.toString());
            return fallback;
        }
    }

    private static boolean writeAtomic(Path file, AuthState s) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable ex) {
            LOG.warn("[ModuleShop] 写入 {} 失败：{}", FILE_NAME, ex.toString());
            return false;
        }
    }

    private static int clampTimeout(int minutes) {
        if (minutes <= 0) {
            return 0;
        }
        return Math.min(MAX_TIMEOUT_MINUTES, minutes);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeStr(String s) {
        return s == null ? "" : s.trim();
    }
}
