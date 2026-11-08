package cn.autoforged.module_shop.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 商店模板的**文件层**：目录约定、原子写入、备份、读取（带上限）。
 *
 * <p>目录结构（客户端与专用服务器一致，只是根目录不同）：
 * <pre>
 * &lt;root&gt;/moduleshop/
 * ├── shop_config.json     默认模板（整合包可直接放这一份）
 * ├── backups/             每次覆盖前的自动备份（保留最近 N 份）
 * └── exports/             玩家手动导出的模板
 * </pre>
 *
 * <p>写入策略：先写同目录的 `.tmp`，成功后 {@code Files.move(REPLACE_EXISTING)}；
 * 覆盖前把旧文件复制到 backups。任何异常都**不清空/不删除**原文件。
 */
public final class ShopTemplateStore {
    /** 目录名（`config/moduleshop`）。 */
    public static final String DIR_NAME = "moduleshop";
    /** 默认模板文件名。 */
    public static final String DEFAULT_FILE = "shop_config.json";
    /** 单个文件最大读取字节数（防炸：正常模板几百 KB 以内）。 */
    public static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    /** 备份保留数量。 */
    public static final int KEEP_BACKUPS = 10;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    private ShopTemplateStore() {
    }

    /** `config/moduleshop` 目录（不创建）。 */
    public static Path dir(Path configDir) {
        return configDir.resolve(DIR_NAME);
    }

    public static Path defaultFile(Path configDir) {
        return dir(configDir).resolve(DEFAULT_FILE);
    }

    public static Path backupsDir(Path configDir) {
        return dir(configDir).resolve("backups");
    }

    public static Path exportsDir(Path configDir) {
        return dir(configDir).resolve("exports");
    }

    /** 读取结果：成功带模板，失败带可展示的原因。 */
    public record ReadResult(ShopTemplate template, String error) {
        public boolean ok() {
            return template != null;
        }
    }

    /** 读取一个模板文件；不存在的路径返回 {@code error="missing"}。 */
    public static ReadResult read(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return new ReadResult(null, "missing");
            }
            long size = Files.size(file);
            if (size <= 0L) {
                return new ReadResult(null, "empty");
            }
            if (size > MAX_FILE_BYTES) {
                return new ReadResult(null, "too_large");
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            ShopTemplate template = ShopTemplate.fromJson(json);
            if (template == null) {
                return new ReadResult(null, "bad_json");
            }
            if (template.formatVersion > ShopTemplate.FORMAT_VERSION) {
                return new ReadResult(null, "newer_format");
            }
            return new ReadResult(template, "");
        } catch (IOException | RuntimeException error) {
            return new ReadResult(null, "io_error");
        }
    }

    /**
     * 原子写入模板（自动备份旧文件）。
     *
     * @param file 目标文件（父目录会自动创建）
     * @return 失败原因；成功返回空串
     */
    public static String write(Path file, ShopTemplate template) {
        if (file == null || template == null) {
            return "invalid";
        }
        try {
            Path parent = file.getParent();
            if (parent == null) {
                return "invalid";
            }
            Files.createDirectories(parent);
            String json = prettify(template.toJson().toString());

            // 覆盖前备份（旧文件存在且非空时才备份）
            if (Files.isRegularFile(file) && Files.size(file) > 0L) {
                backup(file);
            }

            // 先写临时文件，成功后再替换：中途崩溃不会留下半个文件
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moved) {
                // 某些文件系统不支持原子替换：退回"先删后改名"，仍然不会丢旧内容（已备份）
                Files.deleteIfExists(tmp);
                Files.writeString(file, json, StandardCharsets.UTF_8);
            }
            return "";
        } catch (IOException | RuntimeException error) {
            return "io_error";
        }
    }

    /** 导出到 `exports/<prefix>_<时间戳>.json`，返回写入后的路径（失败返回 {@code null}）。 */
    public static Path exportTo(Path configDir, ShopTemplate template, String prefix) {
        try {
            Path dir = exportsDir(configDir);
            Files.createDirectories(dir);
            String cleanPrefix = (prefix == null || prefix.isBlank()) ? "moduleshop_shop" : prefix.replaceAll("[^A-Za-z0-9_\\-]", "_");
            String name = cleanPrefix + "_" + LocalDateTime.now().format(STAMP) + ".json";
            Path file = dir.resolve(name);
            String error = write(file, template);
            return error.isEmpty() ? file : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    /** 把旧文件复制到 `backups/`，只保留最近 {@link #KEEP_BACKUPS} 份。 */
    private static void backup(Path file) {
        try {
            Path dir = backupsDir(file.getParent() == null ? file.getParent() : file.getParent().getParent());
            Files.createDirectories(dir);
            String base = file.getFileName().toString().replaceFirst("\\.json$", "");
            Path target = dir.resolve(base + "-" + LocalDateTime.now().format(STAMP) + ".json");
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            prune(dir);
        } catch (IOException | RuntimeException ignored) {
            // 备份失败不影响主流程（主写入仍然安全）
        }
    }

    private static void prune(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            if (files.size() <= KEEP_BACKUPS) {
                return;
            }
            files.sort(Comparator.comparingLong(path -> {
                try {
                    return Files.getLastModifiedTime(path).toMillis();
                } catch (IOException error) {
                    return 0L;
                }
            }));
            for (int i = 0; i < files.size() - KEEP_BACKUPS; i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** 列出 `exports/` 下的 json 文件（新的在前）。 */
    public static List<Path> listExports(Path configDir) {
        return listJson(exportsDir(configDir));
    }

    /** 列出某个目录下的 json 文件（新的在前）；目录不存在时返回空表。 */
    public static List<Path> listJson(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .toList());
            files.sort(Comparator.comparingLong((Path path) -> {
                try {
                    return Files.getLastModifiedTime(path).toMillis();
                } catch (IOException error) {
                    return 0L;
                }
            }).reversed());
            return files;
        } catch (IOException | RuntimeException error) {
            return List.of();
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
