package cn.autoforged.module_shop.shop.media;

import cn.autoforged.module_shop.config.ShopTemplateStore;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 商店共用二进制图片存储（服务端权威、内容寻址、零图形库依赖）。
 *
 * <p>同时服务「左上角自定义品牌图标」与「服务器公告图文混排」。
 * 仅使用纯 Java 字节解析 PNG / WebP / JPEG 魔数与宽高头，不依赖 Skija 或 {@code ImageIO}，
 * 可在无头 Linux 专用服上稳定运行。
 */
public final class ShopMediaStore {
    public static final int MAX_BYTES = 1048576;
    public static final int MAX_DIMENSION = 2048;

    private static final String[] SUPPORTED_EXTS = {"png", "webp", "jpg"};
    private static final Map<String, MediaMeta> META_CACHE = new ConcurrentHashMap<>();

    public record MediaMeta(String id, String ext, String mime, int width, int height, int sizeBytes) {
    }

    public record SaveResult(boolean ok, String errorKey, @Nullable MediaMeta meta) {
    }

    private record ParsedHeader(String ext, String mime, int width, int height) {
    }

    private ShopMediaStore() {
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("^[0-9a-f]{64}$");
    }

    public static Path mediaDir() {
        return FMLPaths.CONFIGDIR.get().resolve(ShopTemplateStore.DIR_NAME).resolve("media");
    }

    public static SaveResult saveVerified(byte[] bytes) {
        return saveVerified(mediaDir(), bytes);
    }

    public static SaveResult saveVerified(Path dir, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new SaveResult(false, "empty", null);
        }
        if (bytes.length > MAX_BYTES) {
            return new SaveResult(false, "too_large", null);
        }
        ParsedHeader parsed = inspectHeader(bytes);
        if (parsed == null || parsed.width() <= 0 || parsed.height() <= 0) {
            return new SaveResult(false, "bad_image", null);
        }
        if (parsed.width() > MAX_DIMENSION || parsed.height() > MAX_DIMENSION) {
            return new SaveResult(false, "dimensions_too_large", null);
        }
        String id = sha256Hex(bytes);
        MediaMeta meta = new MediaMeta(id, parsed.ext(), parsed.mime(), parsed.width(), parsed.height(), bytes.length);
        Path target = dir.resolve(id + "." + parsed.ext());
        try {
            if (!Files.isRegularFile(target)) {
                Files.createDirectories(dir);
                Path tmp = dir.resolve(id + "." + parsed.ext() + ".tmp");
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            META_CACHE.put(id, meta);
            return new SaveResult(true, "", meta);
        } catch (IOException ex) {
            return new SaveResult(false, "io_error", null);
        }
    }

    public static @Nullable byte[] readBytes(String id) {
        return readBytes(mediaDir(), id);
    }

    public static @Nullable byte[] readBytes(Path dir, String id) {
        if (!isValidId(id) || dir == null) {
            return null;
        }
        Path file = findMediaFile(dir, id);
        if (file == null) {
            return null;
        }
        try {
            long size = Files.size(file);
            if (size <= 0L || size > MAX_BYTES) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            return null;
        }
    }

    public static @Nullable MediaMeta readMeta(String id) {
        return readMeta(mediaDir(), id);
    }

    public static @Nullable MediaMeta readMeta(Path dir, String id) {
        if (!isValidId(id)) {
            return null;
        }
        MediaMeta cached = META_CACHE.get(id);
        if (cached != null && findMediaFile(dir, id) != null) {
            return cached;
        }
        byte[] bytes = readBytes(dir, id);
        if (bytes == null) {
            META_CACHE.remove(id);
            return null;
        }
        ParsedHeader parsed = inspectHeader(bytes);
        if (parsed == null) {
            return null;
        }
        MediaMeta meta = new MediaMeta(id, parsed.ext(), parsed.mime(), parsed.width(), parsed.height(), bytes.length);
        META_CACHE.put(id, meta);
        return meta;
    }

    public static Map<String, byte[]> exportMediaMap(Collection<String> ids) {
        return exportMediaMap(mediaDir(), ids);
    }

    public static Map<String, byte[]> exportMediaMap(Path dir, Collection<String> ids) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (String raw : ids) {
            if (raw == null) {
                continue;
            }
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (!isValidId(id) || out.containsKey(id)) {
                continue;
            }
            byte[] bytes = readBytes(dir, id);
            if (bytes != null && bytes.length > 0) {
                out.put(id, bytes);
            }
        }
        return out;
    }

    public static void importMediaMap(Map<String, byte[]> mediaBytesById) {
        importMediaMap(mediaDir(), mediaBytesById);
    }

    public static void importMediaMap(Path dir, Map<String, byte[]> mediaBytesById) {
        if (mediaBytesById == null || mediaBytesById.isEmpty()) {
            return;
        }
        for (Map.Entry<String, byte[]> entry : mediaBytesById.entrySet()) {
            byte[] data = entry.getValue();
            if (data == null || data.length == 0) {
                continue;
            }
            saveVerified(dir, data);
        }
    }

    @Nullable
    private static Path findMediaFile(Path dir, String id) {
        for (String ext : SUPPORTED_EXTS) {
            Path candidate = dir.resolve(id + "." + ext);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Nullable
    private static ParsedHeader inspectHeader(byte[] bytes) {
        ParsedHeader png = parsePng(bytes);
        if (png != null) {
            return png;
        }
        ParsedHeader webp = parseWebp(bytes);
        if (webp != null) {
            return webp;
        }
        return parseJpeg(bytes);
    }

    @Nullable
    private static ParsedHeader parsePng(byte[] b) {
        if (b.length < 24) {
            return null;
        }
        if ((b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G'
                || b[4] != 0x0D || b[5] != 0x0A || b[6] != 0x1A || b[7] != 0x0A) {
            return null;
        }
        int ihdrLen = readU32BE(b, 8);
        if (ihdrLen < 13 || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
            return null;
        }
        int width = readU32BE(b, 16);
        int height = readU32BE(b, 20);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new ParsedHeader("png", "image/png", width, height);
    }

    @Nullable
    private static ParsedHeader parseWebp(byte[] b) {
        if (b.length < 21) {
            return null;
        }
        if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'E' || b[10] != 'B' || b[11] != 'P') {
            return null;
        }
        // Chunk 0 fourcc at 12..15, size at 16..19, payload at 20+
        if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == ' ') {
            if (b.length < 30) {
                return null;
            }
            if ((b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2A) {
                return null;
            }
            int width = readU16LE(b, 26) & 0x3FFF;
            int height = readU16LE(b, 28) & 0x3FFF;
            return width > 0 && height > 0 ? new ParsedHeader("webp", "image/webp", width, height) : null;
        }
        if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == 'L') {
            if (b.length < 25 || (b[20] & 0xFF) != 0x2F) {
                return null;
            }
            int bits = readU32LE(b, 21);
            int width = (bits & 0x3FFF) + 1;
            int height = ((bits >> 14) & 0x3FFF) + 1;
            return width > 0 && height > 0 ? new ParsedHeader("webp", "image/webp", width, height) : null;
        }
        if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == 'X') {
            if (b.length < 30 || readU32LE(b, 16) < 10) {
                return null;
            }
            int width = 1 + readU24LE(b, 24);
            int height = 1 + readU24LE(b, 27);
            return width > 0 && height > 0 ? new ParsedHeader("webp", "image/webp", width, height) : null;
        }
        return null;
    }

    @Nullable
    private static ParsedHeader parseJpeg(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return null;
        }
        int pos = 2;
        while (pos < b.length) {
            while (pos < b.length && (b[pos] & 0xFF) != 0xFF) {
                pos++;
            }
            while (pos < b.length && (b[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= b.length) {
                break;
            }
            int marker = b[pos++] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) {
                break;
            }
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                continue;
            }
            if (pos + 2 > b.length) {
                break;
            }
            int segLen = readU16BE(b, pos);
            if (segLen < 2 || pos + segLen > b.length) {
                break;
            }
            if (isSofMarker(marker)) {
                if (segLen < 7) {
                    return null;
                }
                int height = readU16BE(b, pos + 3);
                int width = readU16BE(b, pos + 5);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                return new ParsedHeader("jpg", "image/jpeg", width, height);
            }
            pos += segLen;
        }
        return null;
    }

    private static boolean isSofMarker(int m) {
        return (m >= 0xC0 && m <= 0xC3)
                || (m >= 0xC5 && m <= 0xC7)
                || (m >= 0xC9 && m <= 0xCB)
                || (m >= 0xCD && m <= 0xCF);
    }

    private static int readU16BE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readU16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int readU24LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16);
    }

    private static int readU32BE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static int readU32LE(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte v : digest) {
                int x = v & 0xFF;
                if (x < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(x));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
