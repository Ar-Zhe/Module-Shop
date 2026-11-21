package cn.autoforged.module_shop.config;

import cn.autoforged.module_shop.shop.media.ShopMediaStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;

/**
 * 商店模板的**文件层**：支持 {@code .mshop}（整合包容器，含图片资源）、{@code .json.gz} 与 {@code .json}。
 *
 * <p>目录结构（客户端与专用服务器一致，只是根目录不同）：
 * <pre>
 * &lt;root&gt;/moduleshop/
 * ├── shop_config.mshop / .json.gz / .json  默认模板（导入列表置顶）
 * ├── 商店配置_yyyy-MM-dd_HH-mm-ss.mshop     手动导出的模板
 * ├── backups/                               每次覆盖前的自动备份（保留最近 N 份）
 * └── exports/                               旧版导出目录（仍兼容扫描，不主动写入）
 * </pre>
 */
public final class ShopTemplateStore {
    /** 目录名（`config/moduleshop`）。 */
    public static final String DIR_NAME = "moduleshop";
    /** 传统默认 JSON 模板文件名。 */
    public static final String DEFAULT_FILE = "shop_config.json";
    /** 新默认容器模板文件名。 */
    public static final String DEFAULT_MSHOP_FILE = "shop_config.mshop";
    /** 默认 GZip 模板文件名。 */
    public static final String DEFAULT_GZ_FILE = "shop_config.json.gz";
    /** 导出名称留空时的默认前缀。 */
    public static final String DEFAULT_EXPORT_PREFIX = "商店配置";

    /** 单个压缩包/文件磁盘与传输大小上限（32 MiB）。 */
    public static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;
    /** 解压后 JSON 文本安全上限（128 MiB，防 ZIP 炸弹）。 */
    public static final long MAX_UNCOMPRESSED_JSON_BYTES = 128L * 1024L * 1024L;
    /** 单份 `.mshop` 内嵌媒体资源总大小上限（32 MiB）。 */
    public static final long MAX_MEDIA_TOTAL_BYTES = 32L * 1024L * 1024L;
    /** 单张内嵌媒体资源上限（8 MiB）。 */
    public static final long MAX_SINGLE_MEDIA_BYTES = 8L * 1024L * 1024L;
    /** 若未缩进 JSON 超过该阈值则跳过展开美化，节省大型模板体积与内存。 */
    private static final int PRETTIFY_MAX_CHARS = 512 * 1024;
    /** 备份保留数量。 */
    public static final int KEEP_BACKUPS = 10;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    /** 支持的模板文件格式。 */
    public enum TemplateFormat {
        MSHOP(".mshop", "shop_config.mshop"),
        JSON_GZ(".json.gz", "shop_config.json.gz"),
        JSON(".json", "shop_config.json");

        public final String extension;
        public final String defaultFileName;

        TemplateFormat(String extension, String defaultFileName) {
            this.extension = extension;
            this.defaultFileName = defaultFileName;
        }

        public static TemplateFormat fromFileName(@Nullable String name, TemplateFormat fallback) {
            if (name == null || name.isBlank()) {
                return fallback;
            }
            String lower = name.trim().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mshop")) {
                return MSHOP;
            }
            if (lower.endsWith(".json.gz")) {
                return JSON_GZ;
            }
            if (lower.endsWith(".json")) {
                return JSON;
            }
            return fallback;
        }

        public static TemplateFormat byId(@Nullable String raw, TemplateFormat fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "mshop", ".mshop" -> MSHOP;
                case "json_gz", "json.gz", ".json.gz", "gz" -> JSON_GZ;
                case "json", ".json" -> JSON;
                default -> fallback;
            };
        }
    }

    /** 存在于同目录下的非模板系统 JSON（扫描列表与上传覆盖时一律跳过/禁止）。 */
    private static final Set<String> RESERVED_NON_TEMPLATE = Set.of(
            "professions.json",
            "quality_rules.json",
            "mod_names_zh_cn.json",
            "announcements.json",
            "physical_currency.json",
            "media_index.json"
    );

    /** Windows 保留设备名（不区分大小写）。 */
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private ShopTemplateStore() {
    }

    /** `config/moduleshop` 目录（不创建）。 */
    public static Path dir(Path configDir) {
        return configDir.resolve(DIR_NAME);
    }

    public static Path defaultFile(Path configDir) {
        return dir(configDir).resolve(DEFAULT_FILE);
    }

    public static Path defaultFile(Path configDir, TemplateFormat format) {
        TemplateFormat fmt = format == null ? TemplateFormat.JSON : format;
        return dir(configDir).resolve(fmt.defaultFileName);
    }

    /** 查找新世界初始化或默认读取时应优先采用的默认模板（在已存在文件中取最近修改的一份，偏好 .mshop）。 */
    public static Path resolveExistingDefaultFile(Path configDir) {
        if (configDir == null) {
            return null;
        }
        Path[] candidates = {
                defaultFile(configDir, TemplateFormat.MSHOP),
                defaultFile(configDir, TemplateFormat.JSON_GZ),
                defaultFile(configDir, TemplateFormat.JSON)
        };
        Path best = null;
        long bestMtime = Long.MIN_VALUE;
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                long mtime = lastModifiedMillis(p);
                if (best == null || mtime > bestMtime) {
                    best = p;
                    bestMtime = mtime;
                }
            }
        }
        return best;
    }

    public static Path backupsDir(Path configDir) {
        return dir(configDir).resolve("backups");
    }

    public static Path exportsDir(Path configDir) {
        return dir(configDir).resolve("exports");
    }

    /** 是否属于系统内置非模板 JSON（不纳入配置模板列表，也禁止覆盖）。 */
    public static boolean isReservedNonTemplate(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return true;
        }
        return RESERVED_NON_TEMPLATE.contains(fileName.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isTemplateFileName(@Nullable String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".json") || lower.endsWith(".json.gz") || lower.endsWith(".mshop"))) {
            return false;
        }
        return !isReservedNonTemplate(fileName);
    }

    private static String stripKnownExtension(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceFirst("(?i)(\\.json\\.gz|\\.mshop|\\.json)$", "").trim();
    }

    /** 输入名称是否代表默认模板 `shop_config`（允许带/不带 `.json`/`.json.gz`/`.mshop`）。 */
    public static boolean isDefaultName(String rawName) {
        if (rawName == null) {
            return false;
        }
        return "shop_config".equalsIgnoreCase(stripKnownExtension(rawName));
    }

    /**
     * 清洗用户输入的导出前缀：保留中文与合法 Unicode 字符，仅剔除路径非法字符、首尾空白/点号、
     * 以及 Windows 保留设备名；若结果为空则返回 {@link #DEFAULT_EXPORT_PREFIX}。
     */
    public static String sanitizePrefix(String rawName) {
        if (rawName == null) {
            return DEFAULT_EXPORT_PREFIX;
        }
        String s = stripKnownExtension(rawName);
        s = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_");
        s = s.replaceAll("^[.\\s]+|[._\\s]+$", "");
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) {
            return DEFAULT_EXPORT_PREFIX;
        }
        if (s.length() > 64) {
            s = s.substring(0, 64).replaceAll("[._\\s]+$", "");
            if (s.isEmpty()) {
                return DEFAULT_EXPORT_PREFIX;
            }
        }
        int dot = s.indexOf('.');
        String deviceHead = (dot >= 0 ? s.substring(0, dot) : s).toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_NAMES.contains(deviceHead)) {
            s = "_" + s;
        }
        return s;
    }

    /** 预览即将生成的文件名（传统默认 `.json`）。 */
    public static String previewExportFileName(String rawName) {
        return previewExportFileName(rawName, LocalDateTime.now(), TemplateFormat.JSON);
    }

    /** 指定时间戳预览导出文件名（保持单元测试确定性，传统 `.json`）。 */
    public static String previewExportFileName(String rawName, LocalDateTime at) {
        return previewExportFileName(rawName, at, TemplateFormat.JSON);
    }

    public static String previewExportFileName(String rawName, TemplateFormat format) {
        return previewExportFileName(rawName, LocalDateTime.now(), format);
    }

    public static String previewExportFileName(String rawName, @Nullable LocalDateTime at, TemplateFormat format) {
        TemplateFormat fmt = format == null ? TemplateFormat.MSHOP : format;
        if (isDefaultName(rawName)) {
            return fmt.defaultFileName;
        }
        LocalDateTime time = at == null ? LocalDateTime.now() : at;
        return sanitizePrefix(rawName) + "_" + time.format(STAMP) + fmt.extension;
    }

    /** 读取结果（兼容旧调用点）：成功带模板，失败带原因码。 */
    public record ReadResult(@Nullable ShopTemplate template, String error) {
        public boolean ok() {
            return template != null;
        }
    }

    /** 详细读取结果（区分具体错误码、报错细节与单条校验警告）。 */
    public record ReadDetailedResult(@Nullable ShopTemplate template, String errorCode, String errorDetail,
                                     List<ShopTemplate.ValidationIssue> issues) {
        public boolean ok() {
            return template != null && (errorCode == null || errorCode.isBlank());
        }

        public ReadResult toLegacy() {
            return new ReadResult(template, errorCode == null ? "" : errorCode);
        }
    }

    /** 读取一个模板文件；自动识别 `.mshop` / `.json.gz` / `.json`。 */
    public static ReadResult read(Path file) {
        return readDetailed(file).toLegacy();
    }

    /** 详细读取磁盘模板文件（支持 `.mshop` / `.json.gz` / `.json` 及魔数校验）。 */
    public static ReadDetailedResult readDetailed(@Nullable Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return new ReadDetailedResult(null, "missing", "", List.of());
            }
            long size = Files.size(file);
            if (size <= 0L) {
                return new ReadDetailedResult(null, "empty", "", List.of());
            }
            if (size > MAX_FILE_BYTES) {
                return new ReadDetailedResult(null, "too_large", size + " > " + MAX_FILE_BYTES, List.of());
            }
            byte[] raw = Files.readAllBytes(file);
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            return readBytesDetailed(raw, name);
        } catch (IOException | RuntimeException error) {
            return new ReadDetailedResult(null, "io_error", error.toString(), List.of());
        }
    }

    /** 从内存字节流解析模板（自动识别 ZIP `.mshop` / GZip `.json.gz` / UTF-8 JSON）。 */
    public static ReadDetailedResult readBytesDetailed(@Nullable byte[] raw, @Nullable String fileNameHint) {
        if (raw == null || raw.length == 0) {
            return new ReadDetailedResult(null, "empty", "", List.of());
        }
        if (raw.length > MAX_FILE_BYTES) {
            return new ReadDetailedResult(null, "too_large", raw.length + " > " + MAX_FILE_BYTES, List.of());
        }
        String hintLower = fileNameHint == null ? "" : fileNameHint.trim().toLowerCase(Locale.ROOT);
        boolean zipMagic = isZipMagic(raw);
        boolean gzMagic = isGzipMagic(raw);

        if (zipMagic || hintLower.endsWith(".mshop")) {
            if (!zipMagic) {
                return new ReadDetailedResult(null, "corrupt_archive", "Invalid .mshop ZIP header", List.of());
            }
            return readMshopArchive(raw);
        }
        if (gzMagic || hintLower.endsWith(".json.gz")) {
            if (!gzMagic) {
                return new ReadDetailedResult(null, "corrupt_archive", "Invalid .json.gz GZip header", List.of());
            }
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                String json = readBoundedUtf8(gz, MAX_UNCOMPRESSED_JSON_BYTES);
                return parseJsonResult(json);
            } catch (SizeLimitExceededException ex) {
                return new ReadDetailedResult(null, "too_large", ex.getMessage(), List.of());
            } catch (ZipException | EOFException ex) {
                return new ReadDetailedResult(null, "corrupt_archive", ex.getMessage(), List.of());
            } catch (IOException | RuntimeException ex) {
                return new ReadDetailedResult(null, "corrupt_archive", ex.toString(), List.of());
            }
        }
        if (raw.length > MAX_UNCOMPRESSED_JSON_BYTES) {
            return new ReadDetailedResult(null, "too_large", raw.length + " bytes", List.of());
        }
        String json = new String(raw, StandardCharsets.UTF_8);
        return parseJsonResult(json);
    }

    private static ReadDetailedResult readMshopArchive(byte[] raw) {
        String templateJson = null;
        String manifestError = null;
        Map<String, byte[]> mediaMap = new LinkedHashMap<>();
        long mediaTotalBytes = 0L;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new ByteArrayInputStream(raw)),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    zis.closeEntry();
                    return new ReadDetailedResult(null, "corrupt_archive",
                            "Unsafe ZIP entry: " + entryName, List.of());
                }
                if ("template.json".equalsIgnoreCase(entryName)) {
                    templateJson = readBoundedUtf8(zis, MAX_UNCOMPRESSED_JSON_BYTES);
                } else if ("manifest.json".equalsIgnoreCase(entryName)) {
                    String manifestRaw = readBoundedUtf8(zis, 1024L * 1024L);
                    manifestError = checkManifestVersion(manifestRaw);
                    if (manifestError != null) {
                        zis.closeEntry();
                        return new ReadDetailedResult(null, "newer_format", manifestError, List.of());
                    }
                } else if (entryName.startsWith("assets/") && entryName.length() > "assets/".length()) {
                    String mediaId = entryName.substring("assets/".length());
                    if (!mediaId.contains("/") && isSafeMediaId(mediaId)) {
                        byte[] assetBytes = readBoundedBytes(zis, MAX_SINGLE_MEDIA_BYTES);
                        mediaTotalBytes += assetBytes.length;
                        if (mediaTotalBytes > MAX_MEDIA_TOTAL_BYTES) {
                            zis.closeEntry();
                            return new ReadDetailedResult(null, "too_large",
                                    "Embedded assets exceed " + MAX_MEDIA_TOTAL_BYTES, List.of());
                        }
                        mediaMap.put(mediaId, assetBytes);
                    }
                }
                zis.closeEntry();
            }
        } catch (SizeLimitExceededException ex) {
            return new ReadDetailedResult(null, "too_large", ex.getMessage(), List.of());
        } catch (ZipException | EOFException ex) {
            return new ReadDetailedResult(null, "corrupt_archive", ex.getMessage(), List.of());
        } catch (IOException | RuntimeException ex) {
            return new ReadDetailedResult(null, "corrupt_archive", ex.toString(), List.of());
        }

        if (templateJson == null) {
            return new ReadDetailedResult(null, "corrupt_archive", "Missing template.json inside .mshop", List.of());
        }
        ReadDetailedResult res = parseJsonResult(templateJson);
        if (res.ok() && !mediaMap.isEmpty()) {
            res.template().embeddedMedia.putAll(mediaMap);
        }
        return res;
    }

    private static @Nullable String checkManifestVersion(String manifestRaw) {
        try {
            var el = com.google.gson.JsonParser.parseString(ShopTemplate.stripBom(manifestRaw));
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("container_version") && obj.get("container_version").isJsonPrimitive()) {
                    int cv = obj.get("container_version").getAsInt();
                    if (cv > 1) {
                        return "container_version=" + cv;
                    }
                }
                if (obj.has("format_version") && obj.get("format_version").isJsonPrimitive()) {
                    int fv = obj.get("format_version").getAsInt();
                    if (fv > ShopTemplate.FORMAT_VERSION) {
                        return "format_version=" + fv;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static ReadDetailedResult parseJsonResult(String rawJson) {
        ShopTemplate.ParseResult pr = ShopTemplate.parseDetailed(rawJson);
        if (!pr.ok()) {
            return new ReadDetailedResult(null, pr.errorCode(), pr.errorDetail(), pr.issues());
        }
        return new ReadDetailedResult(pr.template(), "", "", pr.issues());
    }

    private static boolean isSafeMediaId(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            if (ShopMediaStore.isValidId(id)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return id.matches("[a-zA-Z0-9._-]{1,128}");
    }

    private static boolean isZipMagic(byte[] raw) {
        return raw.length >= 4
                && raw[0] == 0x50 && raw[1] == 0x4B
                && ((raw[2] == 0x03 && raw[3] == 0x04) || (raw[2] == 0x05 && raw[3] == 0x06));
    }

    private static boolean isGzipMagic(byte[] raw) {
        return raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
    }

    private static final class SizeLimitExceededException extends IOException {
        SizeLimitExceededException(String msg) {
            super(msg);
        }
    }

    private static String readBoundedUtf8(InputStream in, long maxBytes) throws IOException {
        return new String(readBoundedBytes(in, maxBytes), StandardCharsets.UTF_8);
    }

    private static byte[] readBoundedBytes(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) {
                continue;
            }
            total += n;
            if (total > maxBytes) {
                throw new SizeLimitExceededException("Stream exceeded " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 序列化成字节 & 落盘写入

    /** 把模板序列化成指定格式的完整文件字节数组（适合网络分片或写盘）。 */
    public static byte[] writeToBytes(ShopTemplate template, TemplateFormat format,
                                      @Nullable Set<ShopTemplate.ExportSection> sections) throws IOException {
        TemplateFormat fmt = format == null ? TemplateFormat.MSHOP : format;
        String compactJson = template.toJson(sections).toString();
        return switch (fmt) {
            case JSON -> {
                String text = compactJson.length() <= PRETTIFY_MAX_CHARS ? prettify(compactJson) : compactJson;
                yield text.getBytes(StandardCharsets.UTF_8);
            }
            case JSON_GZ -> {
                ByteArrayOutputStream rawOut = new ByteArrayOutputStream(Math.min(compactJson.length(), 65536));
                try (GZIPOutputStream gz = new GZIPOutputStream(rawOut)) {
                    gz.write(compactJson.getBytes(StandardCharsets.UTF_8));
                }
                yield rawOut.toByteArray();
            }
            case MSHOP -> buildMshopBytes(template, compactJson, sections);
        };
    }

    private static byte[] buildMshopBytes(ShopTemplate template, String compactJson,
                                        @Nullable Set<ShopTemplate.ExportSection> sections) throws IOException {
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream(16384);
        try (ZipOutputStream zos = new ZipOutputStream(rawOut, StandardCharsets.UTF_8)) {
            JsonObject manifest = new JsonObject();
            manifest.addProperty("container_version", 1);
            manifest.addProperty("format_version", ShopTemplate.FORMAT_VERSION);
            manifest.addProperty("mod_version", template.modVersion == null ? "" : template.modVersion);
            manifest.addProperty("name", template.name == null ? "" : template.name);
            manifest.addProperty("created_at", template.createdAt == null ? "" : template.createdAt);
            if (sections != null && !sections.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (ShopTemplate.ExportSection s : sections) {
                    arr.add(s.id);
                }
                manifest.add("included_sections", arr);
            }
            manifest.addProperty("media_count", template.embeddedMedia.size());

            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(prettify(manifest.toString()).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("template.json"));
            zos.write(compactJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            for (Map.Entry<String, byte[]> media : template.embeddedMedia.entrySet()) {
                String id = media.getKey();
                byte[] bytes = media.getValue();
                if (id == null || bytes == null || bytes.length == 0 || !isSafeMediaId(id)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry("assets/" + id));
                zos.write(bytes);
                zos.closeEntry();
            }
        }
        return rawOut.toByteArray();
    }

    /** 原子写入模板（根据文件名扩展名自动选择 .mshop / .json.gz / .json，并在覆盖前备份旧文件）。 */
    public static String write(Path file, ShopTemplate template) {
        return write(file, template, null);
    }

    public static String write(Path file, ShopTemplate template,
                               @Nullable Set<ShopTemplate.ExportSection> sections) {
        if (file == null || template == null) {
            return "invalid";
        }
        try {
            TemplateFormat fmt = TemplateFormat.fromFileName(
                    file.getFileName() == null ? null : file.getFileName().toString(),
                    TemplateFormat.JSON);
            byte[] payload = writeToBytes(template, fmt, sections);
            return writeRawAtomic(file, payload);
        } catch (IOException | RuntimeException error) {
            return "io_error";
        }
    }

    /** 把已序列化好的字节安全原子落盘，并在覆盖前自动备份旧文件。 */
    public static String writeRawAtomic(Path file, byte[] payload) {
        if (file == null || payload == null) {
            return "invalid";
        }
        try {
            Path parent = file.getParent();
            if (parent == null) {
                return "invalid";
            }
            Files.createDirectories(parent);
            if (Files.isRegularFile(file) && Files.size(file) > 0L) {
                backup(file);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, payload);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moved) {
                Files.deleteIfExists(tmp);
                Files.write(file, payload);
            }
            return "";
        } catch (IOException | RuntimeException error) {
            return "io_error";
        }
    }

    /** 保存导出模板（传统 3 参签名默认写 `.json` 以保持完全兼容）。 */
    public static Path saveExportOrDefault(Path configDir, ShopTemplate template, String rawName) {
        return saveExportOrDefault(configDir, template, rawName, TemplateFormat.JSON, null);
    }

    public static Path saveExportOrDefault(Path configDir, ShopTemplate template, String rawName,
                                           TemplateFormat format,
                                           @Nullable Set<ShopTemplate.ExportSection> sections) {
        if (configDir == null || template == null) {
            return null;
        }
        TemplateFormat fmt = format == null ? TemplateFormat.MSHOP : format;
        try {
            Path root = dir(configDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path target;
            if (isDefaultName(rawName)) {
                target = root.resolve(fmt.defaultFileName);
            } else {
                String prefix = sanitizePrefix(rawName);
                String stamp = LocalDateTime.now().format(STAMP);
                String candidate = prefix + "_" + stamp + fmt.extension;
                target = root.resolve(candidate);
                int index = 2;
                while (Files.exists(target) && index <= 99) {
                    target = root.resolve(prefix + "_" + stamp + "_" + index + fmt.extension);
                    index++;
                }
            }
            if (!target.normalize().startsWith(root)) {
                return null;
            }
            String error = write(target, template, sections);
            return error.isEmpty() ? target : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    /** 导出模板（旧调用兼容入口）。 */
    public static Path exportTo(Path configDir, ShopTemplate template, String prefix) {
        return saveExportOrDefault(configDir, template, prefix);
    }

    /**
     * 联机专用服上传本地模板并在确认导入后同步落盘到服务端 `config/moduleshop/`：
     * 保留清洗后的原文件名（不再二次追加时间戳），并阻止覆盖系统保留 JSON。
     */
    public static Path saveUploadedFile(Path configDir, String rawFileName, ShopTemplate template) {
        if (configDir == null || template == null) {
            return null;
        }
        try {
            TemplateFormat fmt = TemplateFormat.fromFileName(rawFileName, TemplateFormat.JSON);
            Path target = resolveUploadTarget(configDir, rawFileName, fmt);
            if (target == null) {
                return null;
            }
            String error = write(target, template);
            return error.isEmpty() ? target : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    /** 直接落盘客户端上传的原始压缩/二进制文件（保留原始格式与资产）。 */
    public static Path saveUploadedBytes(Path configDir, String rawFileName, byte[] rawBytes) {
        if (configDir == null || rawBytes == null || rawBytes.length == 0) {
            return null;
        }
        try {
            TemplateFormat fallback = isZipMagic(rawBytes) ? TemplateFormat.MSHOP
                    : (isGzipMagic(rawBytes) ? TemplateFormat.JSON_GZ : TemplateFormat.JSON);
            TemplateFormat fmt = TemplateFormat.fromFileName(rawFileName, fallback);
            Path target = resolveUploadTarget(configDir, rawFileName, fmt);
            if (target == null) {
                return null;
            }
            String error = writeRawAtomic(target, rawBytes);
            return error.isEmpty() ? target : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static @Nullable Path resolveUploadTarget(Path configDir, String rawFileName, TemplateFormat fmt)
            throws IOException {
        Path root = dir(configDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        String base = rawFileName == null ? "" : rawFileName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String finalName = isDefaultName(base) ? fmt.defaultFileName : sanitizePrefix(base) + fmt.extension;
        if (isReservedNonTemplate(finalName)) {
            finalName = "imported_" + finalName;
        }
        Path target = root.resolve(finalName).normalize();
        return target.startsWith(root) ? target : null;
    }

    /** 把旧文件复制到 `config/moduleshop/backups/`，只保留最近 {@link #KEEP_BACKUPS} 份。 */
    private static void backup(Path file) {
        try {
            Path parent = file.getParent();
            if (parent == null) {
                return;
            }
            Path root = parent.getFileName() != null
                    && "exports".equalsIgnoreCase(parent.getFileName().toString())
                    && parent.getParent() != null
                    ? parent.getParent()
                    : parent;
            Path dir = root.resolve("backups");
            Files.createDirectories(dir);
            String rawName = file.getFileName().toString();
            TemplateFormat fmt = TemplateFormat.fromFileName(rawName, TemplateFormat.JSON);
            String base = stripKnownExtension(rawName);
            String stamp = LocalDateTime.now().format(STAMP);
            Path target = dir.resolve(base + "-" + stamp + fmt.extension);
            int index = 2;
            while (Files.exists(target) && index <= 99) {
                target = dir.resolve(base + "-" + stamp + "-" + index + fmt.extension);
                index++;
            }
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            prune(dir);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void prune(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            if (files.size() <= KEEP_BACKUPS) {
                return;
            }
            files.sort(Comparator.comparingLong(ShopTemplateStore::lastModifiedMillis));
            for (int i = 0; i < files.size() - KEEP_BACKUPS; i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * 统一扁平化扫描可导入的模板文件（支持 `.mshop` / `.json.gz` / `.json`）：
     * 默认模板 `shop_config.*` 始终置顶，其余按最后修改时间倒序排列。
     */
    public static List<Path> listTemplates(Path configDir) {
        if (configDir == null) {
            return List.of();
        }
        Path root = dir(configDir).toAbsolutePath().normalize();
        Path defMshop = root.resolve(DEFAULT_MSHOP_FILE).normalize();
        Path defGz = root.resolve(DEFAULT_GZ_FILE).normalize();
        Path defJson = root.resolve(DEFAULT_FILE).normalize();
        Set<Path> defaults = Set.of(defMshop, defGz, defJson);

        Map<Path, Path> seen = new LinkedHashMap<>();
        List<Path> others = new ArrayList<>();
        collectTemplates(root, seen, others, defaults);
        collectTemplates(root.resolve("exports"), seen, others, defaults);

        others.sort(Comparator.comparingLong(ShopTemplateStore::lastModifiedMillis).reversed()
                .thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        List<Path> ordered = new ArrayList<>(others.size() + 3);
        if (Files.isRegularFile(defMshop)) {
            ordered.add(defMshop);
        }
        if (Files.isRegularFile(defGz)) {
            ordered.add(defGz);
        }
        if (Files.isRegularFile(defJson)) {
            ordered.add(defJson);
        }
        ordered.addAll(others);
        return ordered;
    }

    private static void collectTemplates(Path directory, Map<Path, Path> seen, List<Path> others, Set<Path> defaults) {
        for (Path p : listTemplateFiles(directory)) {
            Path norm = p.toAbsolutePath().normalize();
            String fileName = norm.getFileName().toString();
            if (isReservedNonTemplate(fileName) || defaults.contains(norm)) {
                continue;
            }
            if (seen.putIfAbsent(norm, norm) == null) {
                others.add(norm);
            }
        }
    }

    /** 计算相对于 `config/moduleshop/` 的键（如 `shop_config.mshop` 或 `exports/old.json`）。 */
    public static String relativeKey(Path configDir, Path file) {
        if (configDir == null || file == null) {
            return file == null || file.getFileName() == null ? "" : file.getFileName().toString();
        }
        Path root = dir(configDir).toAbsolutePath().normalize();
        Path norm = file.toAbsolutePath().normalize();
        if (norm.startsWith(root)) {
            return root.relativize(norm).toString().replace('\\', '/');
        }
        return norm.getFileName().toString();
    }

    /**
     * 安全解析客户端传来的服务端模板相对键：允许 `config/moduleshop/*.(mshop|json.gz|json)` 与
     * `config/moduleshop/exports/*.(mshop|json.gz|json)`，严格拦截 `..`、绝对路径、`backups/` 与系统保留 JSON。
     */
    public static Path resolveSafeServerFile(Path configDir, String key) {
        if (configDir == null || key == null || key.isBlank()) {
            return null;
        }
        String clean = key.trim();
        if (clean.indexOf('\0') >= 0
                || clean.contains("..")
                || clean.contains("\\")
                || clean.contains(":")
                || clean.startsWith("/")) {
            return null;
        }
        String[] parts = clean.split("/", -1);
        String fileName;
        if (parts.length == 1) {
            fileName = parts[0];
        } else if (parts.length == 2 && "exports".equals(parts[0])) {
            fileName = parts[1];
        } else {
            return null;
        }
        if (fileName.isBlank()
                || ".".equals(fileName)
                || "..".equals(fileName)
                || !isTemplateFileName(fileName)) {
            return null;
        }
        Path root = dir(configDir).toAbsolutePath().normalize();
        Path resolved = root.resolve(clean).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        Path parent = resolved.getParent();
        if (parent == null || (!parent.equals(root) && !parent.equals(root.resolve("exports")))) {
            return null;
        }
        return resolved;
    }

    /** 列出 `exports/` 下的模板文件（新的在前）。 */
    public static List<Path> listExports(Path configDir) {
        return listTemplateFiles(exportsDir(configDir));
    }

    /** 列出某个目录下的模板文件（`.mshop` / `.json.gz` / `.json`，新的在前）。 */
    public static List<Path> listTemplateFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isTemplateFileName(path.getFileName().toString()))
                    .toList());
            files.sort(Comparator.comparingLong(ShopTemplateStore::lastModifiedMillis).reversed());
            return files;
        } catch (IOException | RuntimeException error) {
            return List.of();
        }
    }

    /** 列出某个目录下的 `.json` 文件（兼容旧调用点）。 */
    public static List<Path> listJson(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .toList());
            files.sort(Comparator.comparingLong(ShopTemplateStore::lastModifiedMillis).reversed());
            return files;
        } catch (IOException | RuntimeException error) {
            return List.of();
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException error) {
            return 0L;
        }
    }

    /** Gson 输出的单行 JSON 展开成可读的多行（只处理引号外的 `,` / `{` / `[`）。 */
    private static String prettify(String compact) {
        StringBuilder out = new StringBuilder(compact.length() + 64);
        int indent = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    out.append(c).append('\n');
                    indent++;
                    out.append("  ".repeat(indent));
                }
                case '}', ']' -> {
                    out.append('\n');
                    indent = Math.max(0, indent - 1);
                    out.append("  ".repeat(indent)).append(c);
                }
                case ',' -> out.append(c).append('\n').append("  ".repeat(indent));
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
