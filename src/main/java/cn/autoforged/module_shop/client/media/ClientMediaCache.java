package cn.autoforged.module_shop.client.media;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import com.mojang.logging.LogUtils;
import io.github.humbleui.skija.Codec;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.types.IPoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

/**
 * 客户端媒体缓存：内存 LRU（持有 Skija {@link Image}）+ {@code .minecraft/cache/module_shop/media/} 磁盘缓存。
 * 仅在缺图时按 SHA-256 哈希发送 {@code media_fetch} 按需拉取，避免每次全量快照重复传大图。
 */
public final class ClientMediaCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private static final int MAX_MEM_ENTRIES = 48;
    private static final long MAX_MEM_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_SERVER_DOWNLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DECODE_SIDE = 2048;
    private static final long FETCH_TIMEOUT_MS = 20_000L;
    private static final long RETRY_BACKOFF_MS = 15_000L;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, CacheEntry> MEMORY = new LinkedHashMap<>(32, 0.75F, true);
    private static long memoryBytes;

    private static final Map<String, Long> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED_UNTIL = new ConcurrentHashMap<>();
    private static final Map<String, PendingUpload> PENDING_UPLOADS = new ConcurrentHashMap<>();

    private static final ExecutorService IO_EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "module_shop-media-io");
        t.setDaemon(true);
        return t;
    });

    private static final class CacheEntry {
        final byte[] bytes;
        @Nullable Image image;
        int width;
        int height;
        boolean decodeFailed;

        CacheEntry(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    private record PendingUpload(
            byte[] encodedBytes,
            int width,
            int height,
            ClientImageUploader.UploadCallback callback,
            long createdAtMs) {
    }

    private ClientMediaCache() {
    }

    public static boolean isValidId(@Nullable String id) {
        return id != null && SHA256_HEX.matcher(id).matches();
    }

    public static String normalizeId(@Nullable String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isFetching(@Nullable String mediaId) {
        String id = normalizeId(mediaId);
        if (!isValidId(id)) {
            return false;
        }
        Long startedAt = IN_FLIGHT.get(id);
        if (startedAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - startedAt > FETCH_TIMEOUT_MS) {
            IN_FLIGHT.remove(id, startedAt);
            return false;
        }
        return true;
    }

    public static boolean isFailed(@Nullable String mediaId) {
        String id = normalizeId(mediaId);
        if (!isValidId(id)) {
            return true;
        }
        Long until = FAILED_UNTIL.get(id);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            FAILED_UNTIL.remove(id, until);
            return false;
        }
        return true;
    }

    public static Path cacheDir() {
        Minecraft mc = Minecraft.getInstance();
        Path gameDir = mc == null || mc.gameDirectory == null ? Path.of(".") : mc.gameDirectory.toPath();
        return gameDir.resolve("cache").resolve("module_shop").resolve("media");
    }

    /**
     * 按媒体 ID 获取可直接绘制的 Skija {@link Image}；
     * 若尚未缓存则异步检查本地磁盘缓存，仍缺失则自动向服务端发送 {@code media_fetch} 并返回 {@code null}。
     */
    public static @Nullable Image getSkiaImage(@Nullable String mediaId) {
        String id = normalizeId(mediaId);
        if (!isValidId(id)) {
            return null;
        }
        CacheEntry entry;
        synchronized (LOCK) {
            entry = MEMORY.get(id);
        }
        if (entry != null) {
            if (entry.image != null) {
                return entry.image;
            }
            if (entry.decodeFailed) {
                return null;
            }
            Image decoded = decodeOnRenderThread(entry.bytes);
            if (decoded != null) {
                synchronized (LOCK) {
                    entry.image = decoded;
                    entry.width = decoded.getWidth();
                    entry.height = decoded.getHeight();
                }
                return decoded;
            }
            entry.decodeFailed = true;
            FAILED_UNTIL.put(id, System.currentTimeMillis() + RETRY_BACKOFF_MS);
            return null;
        }

        if (isFetching(id) || isFailed(id)) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (IN_FLIGHT.putIfAbsent(id, now) == null) {
            IO_EXEC.execute(() -> loadFromDiskOrRequest(id));
        }
        return null;
    }

    public static @Nullable int[] getDimensions(@Nullable String mediaId) {
        String id = normalizeId(mediaId);
        if (!isValidId(id)) {
            return null;
        }
        synchronized (LOCK) {
            CacheEntry entry = MEMORY.get(id);
            if (entry != null && entry.width > 0 && entry.height > 0) {
                return new int[]{entry.width, entry.height};
            }
        }
        return null;
    }

    /** 接收服务端 {@code kind="media_data"} 下发包。 */
    public static void handleMediaData(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        String id = normalizeId(tag.getString("id"));
        IN_FLIGHT.remove(id);
        if (!isValidId(id)) {
            return;
        }
        boolean ok = !tag.contains("ok") || tag.getBoolean("ok");
        byte[] bytes = tag.getByteArray("bytes");
        if (!ok || bytes.length == 0 || bytes.length > MAX_SERVER_DOWNLOAD_BYTES) {
            FAILED_UNTIL.put(id, System.currentTimeMillis() + RETRY_BACKOFF_MS);
            return;
        }
        String actualHash = sha256Hex(bytes);
        if (!id.equals(actualHash)) {
            LOGGER.warn("[module_shop] 媒体校验失败：期望 {}，实际 {}", id, actualHash);
            FAILED_UNTIL.put(id, System.currentTimeMillis() + RETRY_BACKOFF_MS);
            return;
        }
        FAILED_UNTIL.remove(id);
        putInMemory(id, bytes, tag.getInt("width"), tag.getInt("height"));
        IO_EXEC.execute(() -> writeDiskQuietly(id, bytes));
    }

    /** 接收服务端 {@code kind="media_uploaded"} 上传回执。 */
    public static void handleMediaUploaded(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        String requestId = tag.getString("requestId");
        PendingUpload pending = requestId.isEmpty() ? null : PENDING_UPLOADS.remove(requestId);
        if (pending == null && requestId.isEmpty() && PENDING_UPLOADS.size() == 1) {
            Iterator<Map.Entry<String, PendingUpload>> it = PENDING_UPLOADS.entrySet().iterator();
            if (it.hasNext()) {
                pending = it.next().getValue();
                it.remove();
            }
        }
        pruneStaleUploads();

        boolean ok = tag.getBoolean("ok");
        String id = normalizeId(tag.getString("id"));
        int width = tag.getInt("width");
        int height = tag.getInt("height");
        String errorKey = tag.getString("errorKey");

        if (ok && isValidId(id)) {
            FAILED_UNTIL.remove(id);
            if (pending != null && pending.encodedBytes != null && pending.encodedBytes.length > 0) {
                int w = width > 0 ? width : pending.width;
                int h = height > 0 ? height : pending.height;
                putInMemory(id, pending.encodedBytes, w, h);
                byte[] bytesCopy = pending.encodedBytes;
                IO_EXEC.execute(() -> writeDiskQuietly(id, bytesCopy));
                width = w;
                height = h;
            }
            if (pending != null && pending.callback != null) {
                final int fw = width;
                final int fh = height;
                final ClientImageUploader.UploadCallback cb = pending.callback;
                runOnClient(() -> cb.onUploaded(id, fw, fh));
            }
        } else if (pending != null && pending.callback != null) {
            final String reason = mapUploadError(errorKey);
            final ClientImageUploader.UploadCallback cb = pending.callback;
            runOnClient(() -> cb.onFailed(reason));
        }
    }

    public static void clearMemory() {
        List<Image> toClose = new ArrayList<>();
        synchronized (LOCK) {
            for (CacheEntry e : MEMORY.values()) {
                if (e.image != null) {
                    toClose.add(e.image);
                    e.image = null;
                }
            }
            MEMORY.clear();
            memoryBytes = 0L;
        }
        IN_FLIGHT.clear();
        FAILED_UNTIL.clear();
        PENDING_UPLOADS.clear();
        closeImagesSafe(toClose);
    }

    static void registerPendingUpload(String requestId, byte[] encodedBytes, int width, int height,
            ClientImageUploader.UploadCallback callback) {
        pruneStaleUploads();
        PENDING_UPLOADS.put(requestId, new PendingUpload(
                encodedBytes, width, height, callback, System.currentTimeMillis()));
    }

    static void failPendingUpload(String requestId, String reason) {
        PendingUpload pending = PENDING_UPLOADS.remove(requestId);
        if (pending != null && pending.callback != null) {
            runOnClient(() -> pending.callback.onFailed(reason));
        }
    }

    private static void pruneStaleUploads() {
        long cutoff = System.currentTimeMillis() - 60_000L;
        PENDING_UPLOADS.entrySet().removeIf(e -> e.getValue().createdAtMs < cutoff);
    }

    private static void loadFromDiskOrRequest(String id) {
        try {
            Path file = cacheDir().resolve(id + ".bin");
            if (Files.isRegularFile(file)) {
                long size = Files.size(file);
                if (size > 0 && size <= MAX_SERVER_DOWNLOAD_BYTES) {
                    byte[] bytes = Files.readAllBytes(file);
                    if (id.equals(sha256Hex(bytes))) {
                        putInMemory(id, bytes, 0, 0);
                        IN_FLIGHT.remove(id);
                        return;
                    }
                }
                Files.deleteIfExists(file);
            }
        } catch (Exception ex) {
            LOGGER.debug("[module_shop] 读取本地媒体缓存失败 {}: {}", id, ex.getMessage());
        }

        runOnClient(() -> {
            if (Minecraft.getInstance().getConnection() == null) {
                IN_FLIGHT.remove(id);
                return;
            }
            CompoundTag req = new CompoundTag();
            req.putString("action", "media_fetch");
            req.putString("id", id);
            ServerboundShopActionPayload.send(req);
        });
    }

    private static void writeDiskQuietly(String id, byte[] bytes) {
        try {
            Path dir = cacheDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(id + ".bin");
            if (Files.isRegularFile(target)) {
                return;
            }
            Path tmp = dir.resolve(id + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            LOGGER.debug("[module_shop] 写入本地媒体缓存失败 {}: {}", id, ex.getMessage());
        }
    }

    private static void putInMemory(String id, byte[] bytes, int width, int height) {
        List<Image> evictedImages = new ArrayList<>();
        synchronized (LOCK) {
            CacheEntry old = MEMORY.remove(id);
            if (old != null) {
                memoryBytes -= old.bytes.length;
                if (old.image != null) {
                    evictedImages.add(old.image);
                }
            }
            CacheEntry next = new CacheEntry(bytes);
            next.width = width;
            next.height = height;
            MEMORY.put(id, next);
            memoryBytes += bytes.length;

            Iterator<Map.Entry<String, CacheEntry>> it = MEMORY.entrySet().iterator();
            while (it.hasNext() && (MEMORY.size() > MAX_MEM_ENTRIES || memoryBytes > MAX_MEM_BYTES)) {
                Map.Entry<String, CacheEntry> victim = it.next();
                if (victim.getKey().equals(id)) {
                    continue;
                }
                it.remove();
                memoryBytes -= victim.getValue().bytes.length;
                if (victim.getValue().image != null) {
                    evictedImages.add(victim.getValue().image);
                }
            }
        }
        closeImagesSafe(evictedImages);
    }

    private static @Nullable Image decodeOnRenderThread(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (Data data = Data.makeFromBytes(bytes);
             Codec codec = Codec.makeFromData(data)) {
            if (codec == null) {
                return null;
            }
            IPoint size = codec.getSize();
            if (size.getX() <= 0 || size.getY() <= 0
                    || size.getX() > MAX_DECODE_SIDE || size.getY() > MAX_DECODE_SIDE) {
                return null;
            }
            return Image.makeFromEncoded(bytes);
        } catch (Throwable t) {
            LOGGER.warn("[module_shop] Skija 图片解码失败", t);
            return null;
        }
    }

    private static void closeImagesSafe(List<Image> images) {
        if (images.isEmpty()) {
            return;
        }
        runOnClient(() -> {
            for (Image img : images) {
                try {
                    if (!img.isClosed()) {
                        img.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void runOnClient(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    private static String mapUploadError(String errorKey) {
        if ("auth_required".equals(errorKey)) {
            return ClientHomeCurrencyState.tr("msg.module_shop.auth_required", "已开启配置保护，请先输入管理密码解锁");
        }
        if ("too_large".equals(errorKey)) {
            return ClientHomeCurrencyState.tr("msg.module_shop.media_too_large", "压缩后图片仍超过 1MB 上限，请换小一点的图片");
        }
        return ClientHomeCurrencyState.tr("msg.module_shop.media_bad_image",
                "图片格式不支持或尺寸超限（支持 PNG/WebP/JPG，最大 1MB / 2048px）");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }
}
