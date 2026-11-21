package cn.autoforged.module_shop.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShopTemplateStoreTest {

    private static ShopTemplate sampleTemplate(String name) {
        ShopTemplate t = new ShopTemplate();
        t.name = name;
        return t;
    }

    @Test
    @DisplayName("中文与空白导出命名：支持中文前缀、留空默认「商店配置」、过滤非法字符与保留名")
    void exportNamingAndPreview(@TempDir Path configDir) {
        LocalDateTime fixed = LocalDateTime.of(2026, 11, 14, 20, 30, 45);
        assertEquals("主城商店_2026-11-14_20-30-45.json",
                ShopTemplateStore.previewExportFileName("主城商店", fixed));
        assertEquals("商店配置_2026-11-14_20-30-45.json",
                ShopTemplateStore.previewExportFileName("   ", fixed));
        assertEquals("商店配置_2026-11-14_20-30-45.json",
                ShopTemplateStore.previewExportFileName(null, fixed));
        assertEquals("shop_config.json",
                ShopTemplateStore.previewExportFileName("Shop_Config.JSON", fixed));
        assertEquals("_con", ShopTemplateStore.sanitizePrefix("con.json"));
        assertEquals("商店_配置", ShopTemplateStore.sanitizePrefix("商店/配置:?.json"));
        assertEquals("商店配置", ShopTemplateStore.sanitizePrefix("../.."));

        Path written = ShopTemplateStore.saveExportOrDefault(configDir, sampleTemplate("主城"), "主城商店");
        assertNotNull(written);
        assertEquals(ShopTemplateStore.dir(configDir).toAbsolutePath().normalize(),
                written.getParent().toAbsolutePath().normalize());
        assertTrue(written.getFileName().toString().startsWith("主城商店_"));
        assertTrue(written.getFileName().toString().endsWith(".json"));

        ShopTemplateStore.ReadResult read = ShopTemplateStore.read(written);
        assertTrue(read.ok());
        assertEquals("主城", read.template().name);
    }

    @Test
    @DisplayName("默认模板覆盖写入：输入 shop_config 写 shop_config.json 且旧文件自动备份至 backups/")
    void saveDefaultBacksUpPreviousFile(@TempDir Path configDir) throws Exception {
        Path first = ShopTemplateStore.saveExportOrDefault(configDir, sampleTemplate("第一版"), "shop_config");
        assertNotNull(first);
        assertEquals("shop_config.json", first.getFileName().toString());

        Path second = ShopTemplateStore.saveExportOrDefault(configDir, sampleTemplate("第二版"), "SHOP_CONFIG.json");
        assertNotNull(second);
        assertEquals(first, second);

        ShopTemplateStore.ReadResult latest = ShopTemplateStore.read(second);
        assertTrue(latest.ok());
        assertEquals("第二版", latest.template().name);

        Path backups = ShopTemplateStore.backupsDir(configDir);
        assertTrue(Files.isDirectory(backups));
        try (Stream<Path> s = Files.list(backups)) {
            List<Path> backupFiles = s.filter(Files::isRegularFile).toList();
            assertEquals(1, backupFiles.size());
            assertTrue(backupFiles.get(0).getFileName().toString().startsWith("shop_config-"));
            ShopTemplateStore.ReadResult oldBackup = ShopTemplateStore.read(backupFiles.get(0));
            assertTrue(oldBackup.ok());
            assertEquals("第一版", oldBackup.template().name);
        }
    }

    @Test
    @DisplayName("扁平化扫描与兼容性：排除系统保留 JSON、忽略 backups/、兼容 exports/ 且默认模板置顶")
    void listTemplatesFiltersSystemFilesAndPinsDefault(@TempDir Path configDir) throws Exception {
        Path root = ShopTemplateStore.dir(configDir);
        Path exports = ShopTemplateStore.exportsDir(configDir);
        Path backups = ShopTemplateStore.backupsDir(configDir);
        Files.createDirectories(root);
        Files.createDirectories(exports);
        Files.createDirectories(backups);

        Files.writeString(root.resolve("professions.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Quality_Rules.JSON"), "{}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("mod_names_zh_cn.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(backups.resolve("old_backup.json"), "{}", StandardCharsets.UTF_8);

        Path oldExport = exports.resolve("legacy.json");
        ShopTemplateStore.write(oldExport, sampleTemplate("旧版导出"));
        Files.setLastModifiedTime(oldExport, FileTime.from(Instant.now().minusSeconds(120)));

        Path rootExport = root.resolve("新活动_2026-11-14_20-00-00.json");
        ShopTemplateStore.write(rootExport, sampleTemplate("新活动"));
        Files.setLastModifiedTime(rootExport, FileTime.from(Instant.now().minusSeconds(10)));

        Path def = ShopTemplateStore.defaultFile(configDir);
        ShopTemplateStore.write(def, sampleTemplate("默认模板"));
        Files.setLastModifiedTime(def, FileTime.from(Instant.now().minusSeconds(300)));

        List<Path> listed = ShopTemplateStore.listTemplates(configDir);
        assertEquals(3, listed.size());
        assertEquals("shop_config.json", listed.get(0).getFileName().toString());
        assertEquals("新活动_2026-11-14_20-00-00.json", listed.get(1).getFileName().toString());
        assertEquals("legacy.json", listed.get(2).getFileName().toString());
        assertEquals("exports/legacy.json", ShopTemplateStore.relativeKey(configDir, listed.get(2)));
    }

    @Test
    @DisplayName("上传同步落盘与路径穿越防护：拦截非法键及系统保留文件名")
    void uploadedSyncAndSafeResolution(@TempDir Path configDir) {
        Path saved = ShopTemplateStore.saveUploadedFile(configDir, "../中文配置.json", sampleTemplate("同步模板"));
        assertNotNull(saved);
        assertEquals("中文配置.json", saved.getFileName().toString());

        Path blockedReserved = ShopTemplateStore.saveUploadedFile(
                configDir, "professions.json", sampleTemplate("不能覆盖职业配置"));
        assertNotNull(blockedReserved);
        assertFalse("professions.json".equalsIgnoreCase(blockedReserved.getFileName().toString()));
        assertTrue(blockedReserved.getFileName().toString().startsWith("imported_"));

        assertNotNull(ShopTemplateStore.resolveSafeServerFile(configDir, "shop_config.json"));
        assertNotNull(ShopTemplateStore.resolveSafeServerFile(configDir, "中文配置.json"));
        assertNotNull(ShopTemplateStore.resolveSafeServerFile(configDir, "exports/old.json"));

        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "../secret.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "backups/shop_config-1.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "professions.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "quality_rules.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "mod_names_zh_cn.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "/abs/path.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "C:\\evil.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "not_json.txt"));
    }
}
