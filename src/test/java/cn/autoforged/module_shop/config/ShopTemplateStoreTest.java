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
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "mod_names_en_us.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "brand_default.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "admin_auth.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "/abs/path.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "C:\\evil.json"));
        assertNull(ShopTemplateStore.resolveSafeServerFile(configDir, "not_json.txt"));
    }

    @Test
    @DisplayName("默认模板跨格式更新：保存新后缀 shop_config 时自动归档移除旧后缀并避免解压同秒冲突")
    void saveDefaultArchivesOtherFormatSiblings(@TempDir Path configDir) throws Exception {
        Path mshop = ShopTemplateStore.saveExportOrDefault(
                configDir, sampleTemplate("旧版整合包默认"), "shop_config",
                ShopTemplateStore.TemplateFormat.MSHOP, null);
        assertNotNull(mshop);
        assertEquals("shop_config.mshop", mshop.getFileName().toString());
        assertTrue(Files.isRegularFile(mshop));

        Path json = ShopTemplateStore.saveExportOrDefault(
                configDir, sampleTemplate("新版纯文本默认"), "shop_config",
                ShopTemplateStore.TemplateFormat.JSON, null);
        assertNotNull(json);
        assertEquals("shop_config.json", json.getFileName().toString());
        assertTrue(Files.isRegularFile(json));
        assertFalse(Files.exists(mshop), "旧后缀 shop_config.mshop 应已归档移除");

        Path resolved = ShopTemplateStore.resolveExistingDefaultFile(configDir);
        assertEquals(json.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize());
        assertEquals("新版纯文本默认", ShopTemplateStore.read(resolved).template().name);

        // 确认旧的 .mshop 已安全备份进 backups/
        try (Stream<Path> s = Files.list(ShopTemplateStore.backupsDir(configDir))) {
            List<Path> backups = s.filter(Files::isRegularFile).toList();
            assertEquals(1, backups.size());
            assertTrue(backups.get(0).getFileName().toString().endsWith(".mshop"));
            assertEquals("旧版整合包默认", ShopTemplateStore.read(backups.get(0)).template().name);
        }

        // 同时验证 brand_default.json / admin_auth.json / mod_names_*.json 不会混入列表
        Path root = ShopTemplateStore.dir(configDir);
        Files.writeString(root.resolve("brand_default.json"), "{\"shopTitle\":\"test\"}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("admin_auth.json"), "{\"enabled\":true}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("mod_names_en_us.json"), "{}", StandardCharsets.UTF_8);

        List<Path> listed = ShopTemplateStore.listTemplates(configDir);
        assertEquals(1, listed.size());
        assertEquals("shop_config.json", listed.get(0).getFileName().toString());
    }

    @Test
    @DisplayName("模板 JSON 读取支持 UTF-8 BOM 剥离与 GB18030 中文编码回退")
    void readTemplateSupportsUtf8BomAndGb18030(@TempDir Path configDir) throws Exception {
        Path root = ShopTemplateStore.dir(configDir);
        Files.createDirectories(root);

        byte[] utf8 = "{\"format_version\":1,\"name\":\"繁星商店\"}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[3 + utf8.length];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, withBom, 3, utf8.length);
        Path bomFile = root.resolve("bom_tpl.json");
        Files.write(bomFile, withBom);
        ShopTemplateStore.ReadDetailedResult bomRes = ShopTemplateStore.readDetailed(bomFile);
        assertTrue(bomRes.ok(), () -> "UTF-8 BOM 读取失败: " + bomRes.errorCode() + " / " + bomRes.errorDetail());
        assertEquals("繁星商店", bomRes.template().name);

        byte[] gbBytes = "{\"format_version\":1,\"name\":\"记事本中文模板\"}"
                .getBytes(java.nio.charset.Charset.forName("GB18030"));
        Path gbFile = root.resolve("gb_tpl.json");
        Files.write(gbFile, gbBytes);
        ShopTemplateStore.ReadDetailedResult gbRes = ShopTemplateStore.readDetailed(gbFile);
        assertTrue(gbRes.ok(), () -> "GB18030 读取失败: " + gbRes.errorCode() + " / " + gbRes.errorDetail());
        assertEquals("记事本中文模板", gbRes.template().name);

        // UTF-16LE BOM (FF FE)
        byte[] leBody = "{\"format_version\":1,\"name\":\"UTF16小端模板\"}".getBytes(StandardCharsets.UTF_16LE);
        byte[] leBytes = new byte[2 + leBody.length];
        leBytes[0] = (byte) 0xFF;
        leBytes[1] = (byte) 0xFE;
        System.arraycopy(leBody, 0, leBytes, 2, leBody.length);
        Path leFile = root.resolve("utf16le_tpl.json");
        Files.write(leFile, leBytes);
        ShopTemplateStore.ReadDetailedResult leRes = ShopTemplateStore.readDetailed(leFile);
        assertTrue(leRes.ok(), () -> "UTF-16LE BOM 读取失败: " + leRes.errorCode() + " / " + leRes.errorDetail());
        assertEquals("UTF16小端模板", leRes.template().name);

        // UTF-16BE BOM (FE FF)
        byte[] beBody = "{\"format_version\":1,\"name\":\"UTF16大端模板\"}".getBytes(StandardCharsets.UTF_16BE);
        byte[] beBytes = new byte[2 + beBody.length];
        beBytes[0] = (byte) 0xFE;
        beBytes[1] = (byte) 0xFF;
        System.arraycopy(beBody, 0, beBytes, 2, beBody.length);
        Path beFile = root.resolve("utf16be_tpl.json");
        Files.write(beFile, beBytes);
        ShopTemplateStore.ReadDetailedResult beRes = ShopTemplateStore.readDetailed(beFile);
        assertTrue(beRes.ok(), () -> "UTF-16BE BOM 读取失败: " + beRes.errorCode() + " / " + beRes.errorDetail());
        assertEquals("UTF16大端模板", beRes.template().name);

        // 损坏二进制必须被严格拒绝，不静默产出乱码
        byte[] corrupt = new byte[]{(byte) 0xFF, (byte) 0x80, (byte) 0xFE, (byte) 0xFF};
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShopTemplateStore.decodeJsonText(corrupt));
        ShopTemplateStore.ReadDetailedResult corruptRes = ShopTemplateStore.readBytesDetailed(corrupt, "broken.json");
        assertFalse(corruptRes.ok());
        assertEquals("invalid_json", corruptRes.errorCode());
    }
}
