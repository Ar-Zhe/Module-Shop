package cn.autoforged.module_shop;

import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.config.ShopTemplate;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.data.SidebarLayout;
import cn.autoforged.module_shop.shop.auth.AdminAuthService;
import cn.autoforged.module_shop.shop.home.AnnouncementBlock;
import cn.autoforged.module_shop.shop.home.AnnouncementEntry;
import cn.autoforged.module_shop.shop.media.ShopMediaStore;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 JUnit 回归测试：覆盖阶段二媒体校验、管理员 PBKDF2 密码锁、公告图文混排与多格式模板往返持久化。
 */
class UpgradeRegressionTest {

    // ------------------------------------------------------------------ 0. 实体货币 + 星币并行下的星币求购（v1.5.1）

    @Test
    void parallelCurrencyStarOnlyDemandEscrowUsesStarLedgerOnly() {
        // 并行模式：实体货币已启用且有档位，玩家背包里也有实体币——选「仅星币」求购必须只走星币账本
        List<String> keys = List.of("minecraft:iron_nugget", "minecraft:gold_nugget");
        long[] values = {1L, 100L};
        int[] owned = {64, 64};
        cn.autoforged.module_shop.data.PriceSpec reward = cn.autoforged.module_shop.data.PriceSpec.star(1_000L);
        assertTrue(reward.isValid());
        assertTrue(reward.supportsStar());
        assertFalse(reward.supportsPhys());
        assertEquals(cn.autoforged.module_shop.data.PriceSpec.CurrencyMode.STAR_ONLY, reward.mode);

        cn.autoforged.module_shop.data.PlayerAccount rich = new cn.autoforged.module_shop.data.PlayerAccount();
        rich.balance = 10_000L;
        // 托管 = 悬赏 1000 + 5% 手续费 50
        var plan = cn.autoforged.module_shop.shop.currency.CurrencySettlement.planSpecMatrix(rich,
                cn.autoforged.module_shop.data.PriceSpec.star(1_050L), 1, null, true, keys, values, owned);
        assertTrue(plan.ok(), "并行模式下星币求购托管应成功：" + plan.failKey());
        assertEquals(cn.autoforged.module_shop.data.PriceSpec.CurrencyMode.STAR_ONLY, plan.resolvedMode());
        assertEquals(1_050L, plan.starNeed());
        assertEquals(0L, plan.physCount(), "仅星币求购绝不能动用实体币");

        cn.autoforged.module_shop.data.PlayerAccount poor = new cn.autoforged.module_shop.data.PlayerAccount();
        poor.balance = 10L;
        var poorPlan = cn.autoforged.module_shop.shop.currency.CurrencySettlement.planSpecMatrix(poor,
                cn.autoforged.module_shop.data.PriceSpec.star(1_050L), 1, null, true, keys, values, owned);
        assertFalse(poorPlan.ok(), "星币不足时即使背包有实体币也必须拒绝，不得偷偷改扣实体币");
        assertEquals("msg.module_shop.no_money", poorPlan.failKey());
    }

    @Test
    void demandCreateScreenKeepsPriceEditorFieldFocusAfterClick() throws IOException {
        // 根因回归守卫：点中星币/实体币输入框后不得再 setFocused(null)，否则 EditBox 立刻失焦、敲不进数字
        Path src = Path.of("src/main/java/cn/autoforged/module_shop/client/screen/DemandCreateScreen.java");
        String code = Files.readString(src, StandardCharsets.UTF_8);
        int at = code.indexOf("this.priceEditor.mouseClicked(sx, sy, button)");
        assertTrue(at > 0, "DemandCreateScreen 必须把点击先转发给 PriceSpecEditor");
        int end = code.indexOf("return true;", at);
        assertTrue(end > at);
        String block = code.substring(at, end).replaceAll("(?m)//.*$", "");
        assertFalse(block.contains("setFocused(null)"),
                "PriceSpecEditor 命中后清空 Screen 焦点会让星币输入框无法输入（并行模式星币求购失效）");
    }
    // ------------------------------------------------------------------ 1. ShopMediaStore + .mshop 媒体

    @Test
    void mediaStoreValidatesPngJpegWebpAndRejectsForgedOrOversizedInputs(@TempDir Path tempDir) {
        Path mediaDir = tempDir.resolve("media");

        byte[] png = buildValidPng(32, 24);
        ShopMediaStore.SaveResult pngRes = ShopMediaStore.saveVerified(mediaDir, png);
        assertTrue(pngRes.ok());
        assertEquals("", pngRes.errorKey());
        assertNotNull(pngRes.meta());
        assertEquals("png", pngRes.meta().ext());
        assertEquals("image/png", pngRes.meta().mime());
        assertEquals(32, pngRes.meta().width());
        assertEquals(24, pngRes.meta().height());
        assertEquals(png.length, pngRes.meta().sizeBytes());
        assertTrue(ShopMediaStore.isValidId(pngRes.meta().id()));
        assertArrayEquals(png, ShopMediaStore.readBytes(mediaDir, pngRes.meta().id()));
        assertEquals(pngRes.meta(), ShopMediaStore.readMeta(mediaDir, pngRes.meta().id()));

        // 同内容重复写入应复用同一 SHA-256 ID
        ShopMediaStore.SaveResult dupRes = ShopMediaStore.saveVerified(mediaDir, png);
        assertTrue(dupRes.ok());
        assertEquals(pngRes.meta().id(), dupRes.meta().id());

        byte[] jpg = buildValidJpeg(64, 48);
        ShopMediaStore.SaveResult jpgRes = ShopMediaStore.saveVerified(mediaDir, jpg);
        assertTrue(jpgRes.ok());
        assertEquals("jpg", jpgRes.meta().ext());
        assertEquals("image/jpeg", jpgRes.meta().mime());
        assertEquals(64, jpgRes.meta().width());
        assertEquals(48, jpgRes.meta().height());
        assertArrayEquals(jpg, ShopMediaStore.readBytes(mediaDir, jpgRes.meta().id()));

        byte[] webpVp8x = buildValidWebpVp8x(128, 96);
        ShopMediaStore.SaveResult webpRes = ShopMediaStore.saveVerified(mediaDir, webpVp8x);
        assertTrue(webpRes.ok());
        assertEquals("webp", webpRes.meta().ext());
        assertEquals("image/webp", webpRes.meta().mime());
        assertEquals(128, webpRes.meta().width());
        assertEquals(96, webpRes.meta().height());
        assertArrayEquals(webpVp8x, ShopMediaStore.readBytes(mediaDir, webpRes.meta().id()));

        byte[] webpVp8l = buildValidWebpVp8l(80, 40);
        ShopMediaStore.SaveResult webpLRes = ShopMediaStore.saveVerified(mediaDir, webpVp8l);
        assertTrue(webpLRes.ok());
        assertEquals(80, webpLRes.meta().width());
        assertEquals(40, webpLRes.meta().height());

        // 空字节、超大字节、超限宽高、伪造字节头
        ShopMediaStore.SaveResult emptyRes = ShopMediaStore.saveVerified(mediaDir, new byte[0]);
        assertFalse(emptyRes.ok());
        assertEquals("empty", emptyRes.errorKey());

        byte[] tooBig = new byte[ShopMediaStore.MAX_BYTES + 1];
        System.arraycopy(png, 0, tooBig, 0, png.length);
        ShopMediaStore.SaveResult bigRes = ShopMediaStore.saveVerified(mediaDir, tooBig);
        assertFalse(bigRes.ok());
        assertEquals("too_large", bigRes.errorKey());

        ShopMediaStore.SaveResult hugeDimPng = ShopMediaStore.saveVerified(
                mediaDir, buildValidPng(ShopMediaStore.MAX_DIMENSION + 1, 16));
        assertFalse(hugeDimPng.ok());
        assertEquals("dimensions_too_large", hugeDimPng.errorKey());

        ShopMediaStore.SaveResult hugeDimJpg = ShopMediaStore.saveVerified(
                mediaDir, buildValidJpeg(16, ShopMediaStore.MAX_DIMENSION + 1));
        assertFalse(hugeDimJpg.ok());
        assertEquals("dimensions_too_large", hugeDimJpg.errorKey());

        ShopMediaStore.SaveResult hugeDimWebp = ShopMediaStore.saveVerified(
                mediaDir, buildValidWebpVp8x(ShopMediaStore.MAX_DIMENSION + 1, 16));
        assertFalse(hugeDimWebp.ok());
        assertEquals("dimensions_too_large", hugeDimWebp.errorKey());

        ShopMediaStore.SaveResult forgedText = ShopMediaStore.saveVerified(
                mediaDir, "not actually a png".getBytes(StandardCharsets.UTF_8));
        assertFalse(forgedText.ok());
        assertEquals("bad_image", forgedText.errorKey());

        ShopMediaStore.SaveResult forgedPngSig = ShopMediaStore.saveVerified(
                mediaDir, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});
        assertFalse(forgedPngSig.ok());
        assertEquals("bad_image", forgedPngSig.errorKey());

        ShopMediaStore.SaveResult zeroDimPng = ShopMediaStore.saveVerified(mediaDir, buildValidPng(0, 16));
        assertFalse(zeroDimPng.ok());
        assertEquals("bad_image", zeroDimPng.errorKey());

        // 路径穿越保护
        assertFalse(ShopMediaStore.isValidId("../secret"));
        assertNull(ShopMediaStore.readBytes(mediaDir, "../secret"));
        assertNull(ShopMediaStore.readMeta(mediaDir, "bad-id"));
    }

    @Test
    void mshopBundleRoundtripsEmbeddedMediaThroughShopMediaStore(@TempDir Path tempDir) {
        Path sourceMediaDir = tempDir.resolve("src_media");
        Path targetMediaDir = tempDir.resolve("dst_media");

        byte[] png = buildValidPng(48, 48);
        byte[] webp = buildValidWebpVp8x(100, 60);
        String pngId = ShopMediaStore.saveVerified(sourceMediaDir, png).meta().id();
        String webpId = ShopMediaStore.saveVerified(sourceMediaDir, webp).meta().id();

        ShopTemplate tpl = new ShopTemplate();
        tpl.name = "媒体打包验证";
        tpl.layout = new ShopTemplate.LayoutData();
        tpl.layout.shopTitle = "自定义商店";
        tpl.layout.brandIconId = pngId;
        tpl.announcements.add(new AnnouncementEntry(
                "ann_media", "图文公告", "活动", "", true, 1, "2026-11-18",
                List.of(
                        AnnouncementBlock.textBlock("欢迎光临"),
                        AnnouncementBlock.imageBlock(webpId, 75, "center", "宣传图", 100, 60)
                )));

        Map<String, byte[]> exported = ShopMediaStore.exportMediaMap(sourceMediaDir, List.of(pngId, webpId, "nonexistent"));
        assertEquals(2, exported.size());
        tpl.embeddedMedia.putAll(exported);

        Path mshopFile = tempDir.resolve("bundle.mshop");
        assertEquals("", ShopTemplateStore.write(mshopFile, tpl, EnumSet.allOf(ShopTemplate.ExportSection.class)));

        ShopTemplateStore.ReadDetailedResult read = ShopTemplateStore.readDetailed(mshopFile);
        assertTrue(read.ok(), () -> "读取 mshop 失败: " + read.errorCode() + " / " + read.errorDetail());
        assertNotNull(read.template());
        assertEquals(2, read.template().embeddedMedia.size());
        assertArrayEquals(png, read.template().embeddedMedia.get(pngId));
        assertArrayEquals(webp, read.template().embeddedMedia.get(webpId));

        ShopMediaStore.importMediaMap(targetMediaDir, read.template().embeddedMedia);
        assertArrayEquals(png, ShopMediaStore.readBytes(targetMediaDir, pngId));
        assertArrayEquals(webp, ShopMediaStore.readBytes(targetMediaDir, webpId));
        assertEquals(48, ShopMediaStore.readMeta(targetMediaDir, pngId).width());
        assertEquals(100, ShopMediaStore.readMeta(targetMediaDir, webpId).width());
    }

    // ------------------------------------------------------------------ 2. AdminAuthService

    @Test
    void adminAuthPbkdf2HashingCooldownAndTemplateExportImport(@TempDir Path tempDir) throws IOException {
        Path serverA = tempDir.resolve("serverA_config");
        Path serverB = tempDir.resolve("serverB_config");
        UUID op1 = UUID.randomUUID();
        UUID op2 = UUID.randomUUID();
        UUID nonOp = UUID.randomUUID();

        AdminAuthService.reload(serverA);
        try {
            assertFalse(AdminAuthService.isLockEnabled());
            assertTrue(authCanEditAdmin(op1, true));
            assertFalse(authCanEditAdmin(nonOp, false));

            // 开启密码锁并校验落盘不存明文
            String secret = "Correct-Horse-99";
            AdminAuthService.SaveAuthOutcome enableRes =
                    authConfigure(op1, true, true, "", secret, 30);
            assertTrue(enableRes.ok());
            assertTrue(AdminAuthService.isLockEnabled());
            assertTrue(authCanEditAdmin(op1, true));
            assertFalse(authCanEditAdmin(op2, true));

            Path authFileA = serverA.resolve(ShopTemplateStore.DIR_NAME).resolve("admin_auth.json");
            assertTrue(Files.isRegularFile(authFileA));
            String diskJson = Files.readString(authFileA, StandardCharsets.UTF_8);
            assertFalse(diskJson.contains(secret), "磁盘配置绝不能包含明文密码");
            assertTrue(diskJson.contains(AdminAuthService.ALGO));

            // 切换配置目录或重加载不同实例后会话清空、重新加载原目录时 hash 仍生效
            AdminAuthService.reload(serverB);
            AdminAuthService.reload(serverA);
            assertTrue(AdminAuthService.isLockEnabled());
            assertFalse(authCanEditAdmin(op1, true));
            assertFalse(authIsUnlocked(op1));

            // 连续 4 次输错仅提示密码错误；第 5 次触发冷却；冷却期内即使输对密码也不应放行
            for (int i = 0; i < 4; i++) {
                AdminAuthService.UnlockOutcome fail = authTryUnlock(op2, true, "wrong-" + i);
                assertFalse(fail.ok());
                assertEquals("msg.module_shop.auth_wrong_password", fail.errorKey());
                assertEquals(0, authRemainingCooldownSeconds(op2));
            }
            AdminAuthService.UnlockOutcome fifth = authTryUnlock(op2, true, "wrong-4");
            assertFalse(fifth.ok());
            assertEquals("msg.module_shop.auth_cooldown", fifth.errorKey());
            assertTrue(fifth.retryAfterSeconds() >= 59);
            assertTrue(authRemainingCooldownSeconds(op2) > 0);

            AdminAuthService.UnlockOutcome blockedEvenRight = authTryUnlock(op2, true, secret);
            assertFalse(blockedEvenRight.ok());
            assertEquals("msg.module_shop.auth_cooldown", blockedEvenRight.errorKey());
            assertFalse(authCanEditAdmin(op2, true));

            // 非 OP 即便输对密码也不能编辑管理端
            AdminAuthService.UnlockOutcome nonOpRes = authTryUnlock(nonOp, false, secret);
            assertFalse(nonOpRes.ok());
            assertFalse(authCanEditAdmin(nonOp, false));

            // 另一名 OP 正常解锁、主动锁回与登出清会话
            AdminAuthService.UnlockOutcome okUnlock = authTryUnlock(op1, true, secret);
            assertTrue(okUnlock.ok());
            assertTrue(authIsUnlocked(op1));
            assertTrue(authCanEditAdmin(op1, true));
            AdminAuthService.lockPlayer(op1);
            assertFalse(authCanEditAdmin(op1, true));

            assertTrue(authTryUnlock(op1, true, secret).ok());
            AdminAuthService.onLogout(op1);
            assertFalse(authCanEditAdmin(op1, true));

            // 导出 PBKDF2 加盐哈希并在未配置的新服导入，验证跨服校验一致
            AdminAuthService.ExportedAuth exported = AdminAuthService.exportForTemplate();
            assertNotNull(exported);
            assertTrue(exported.enabled());
            assertEquals(AdminAuthService.ALGO, exported.algo());
            assertEquals(AdminAuthService.DEFAULT_ITERATIONS, exported.iterations());
            assertNotEquals(secret, exported.hash());

            AdminAuthService.reload(serverB);
            assertFalse(AdminAuthService.isLockEnabled());
            AdminAuthService.importFromTemplateIfUnconfigured(exported);
            assertTrue(AdminAuthService.isLockEnabled());
            assertFalse(authTryUnlock(op1, true, "wrong-pass").ok());
            assertTrue(authTryUnlock(op1, true, secret).ok());

            // 已配置的服务器不得被模板导入覆盖密码
            AdminAuthService.reload(serverA);
            AdminAuthService.consoleDisable();
            assertFalse(AdminAuthService.isLockEnabled());
            assertTrue(authConfigure(op1, true, true, "", "NewPass#42", 45).ok());
            AdminAuthService.importFromTemplateIfUnconfigured(exported);
            AdminAuthService.lockPlayer(op1);
            assertFalse(authTryUnlock(op1, true, secret).ok());
            assertTrue(authTryUnlock(op1, true, "NewPass#42").ok());
        } finally {
            AdminAuthService.consoleDisable();
        }
    }

    // ------------------------------------------------------------------ 3. AnnouncementBlock / Entry

    @Test
    void announcementBlocksJsonNbtRoundtripAndLegacyContentCompat() {
        String validHexId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        AnnouncementBlock textBlk = AnnouncementBlock.textBlock("公告正文段落");
        AnnouncementBlock imgBlk = AnnouncementBlock.imageBlock(validHexId, 150, "invalid_align", "图注说明", 640, 360);
        assertEquals(100, imgBlk.widthPct(), "图片宽度百分比应夹紧至 <= 100");
        assertEquals(AnnouncementBlock.ALIGN_CENTER, imgBlk.align(), "非法对齐方式应归一化为 center");

        Object blockTag = invoke(AnnouncementBlock.class, "toTag", imgBlk);
        AnnouncementBlock imgFromTag = (AnnouncementBlock) invokeStatic(
                AnnouncementBlock.class, "fromTag", new Class<?>[]{compoundTagClass()}, blockTag);
        assertEquals(validHexId, imgFromTag.mediaId());
        assertEquals(100, imgFromTag.widthPct());
        assertEquals("center", imgFromTag.align());
        assertEquals("图注说明", imgFromTag.caption());
        assertEquals(640, imgFromTag.imgW());
        assertEquals(360, imgFromTag.imgH());

        Object blockJson = invoke(AnnouncementBlock.class, "toJson", imgBlk);
        AnnouncementBlock imgFromJson = (AnnouncementBlock) invokeStatic(
                AnnouncementBlock.class, "fromJson", new Class<?>[]{jsonObjectClass()}, blockJson);
        assertEquals(imgBlk.mediaId(), imgFromJson.mediaId());
        assertEquals(imgBlk.caption(), imgFromJson.caption());

        AnnouncementEntry mixed = new AnnouncementEntry(
                "notice_1", "混排更新", "更新", "", true, 3, "2026-11-18",
                List.of(textBlk, imgBlk));
        mixed.sanitize(0);
        assertEquals(List.of(validHexId), mixed.referencedMediaIds());
        assertTrue(mixed.content.contains("公告正文段落"), "混排 sanitize 应自动提取纯文本摘要作回退 content");

        Object mixedTag = invoke(AnnouncementEntry.class, "toTag", mixed);
        AnnouncementEntry mixedFromNbt = (AnnouncementEntry) invokeStatic(
                AnnouncementEntry.class, "fromTag", new Class<?>[]{compoundTagClass()}, mixedTag);
        assertEquals(2, mixedFromNbt.blocks().size());
        assertEquals("公告正文段落", mixedFromNbt.blocks().get(0).text());
        assertEquals(validHexId, mixedFromNbt.blocks().get(1).mediaId());
        assertTrue(mixedFromNbt.pinned);
        assertEquals(3, mixedFromNbt.order);

        Object mixedJson = invoke(AnnouncementEntry.class, "toJson", mixed);
        AnnouncementEntry mixedFromJson = (AnnouncementEntry) invokeStatic(
                AnnouncementEntry.class, "fromJson", new Class<?>[]{jsonObjectClass()}, mixedJson);
        assertEquals(2, mixedFromJson.blocks().size());
        assertEquals(validHexId, mixedFromJson.blocks().get(1).mediaId());
        assertEquals("更新", mixedFromJson.category);

        // 旧版仅有 content 字段（无 blocks）兼容测试
        AnnouncementEntry legacy = new AnnouncementEntry(
                "legacy_1", "旧版公告", "通知", "纯文本旧公告内容", false, 5, "2026-10-01");
        assertTrue(legacy.referencedMediaIds().isEmpty());
        assertEquals(1, legacy.blocks().size());
        assertEquals(AnnouncementBlock.TYPE_TEXT, legacy.blocks().get(0).type());
        assertEquals("纯文本旧公告内容", legacy.blocks().get(0).text());

        Object legacyTag = invoke(AnnouncementEntry.class, "toTag", legacy);
        AnnouncementEntry legacyNbt = (AnnouncementEntry) invokeStatic(
                AnnouncementEntry.class, "fromTag", new Class<?>[]{compoundTagClass()}, legacyTag);
        assertEquals(1, legacyNbt.blocks().size());
        assertEquals("纯文本旧公告内容", legacyNbt.blocks().get(0).text());

        Object legacyJson = invoke(AnnouncementEntry.class, "toJson", legacy);
        AnnouncementEntry legacyJsonRestored = (AnnouncementEntry) invokeStatic(
                AnnouncementEntry.class, "fromJson", new Class<?>[]{jsonObjectClass()}, legacyJson);
        assertEquals(1, legacyJsonRestored.blocks().size());
        assertEquals("纯文本旧公告内容", legacyJsonRestored.blocks().get(0).text());
    }

    // ------------------------------------------------------------------ 4. ShopTemplateStore + SidebarLayout

    @Test
    void templateFormatsAndBomJsonRoundtripWithSidebarAndShipping(@TempDir Path tempDir) throws IOException {
        String brandId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        SidebarLayout layout = new SidebarLayout();
        layout.setShopTitle("云顶百货总店");
        layout.setBrandIconId(brandId);
        layout.setOrder(List.of("mall", "home", "auction"));
        layout.setHidden(List.of("shipping", "mail"));
        layout.setName("mall", "精品商城");

        Object savedLayoutTag = invoke(SidebarLayout.class, "save", layout);
        SidebarLayout restoredLayout = (SidebarLayout) invokeStatic(
                SidebarLayout.class, "load", new Class<?>[]{compoundTagClass()}, savedLayoutTag);
        assertEquals("云顶百货总店", restoredLayout.shopTitle());
        assertEquals(brandId, restoredLayout.brandIconId());
        assertEquals(List.of("mall", "home", "auction"), restoredLayout.order());
        assertTrue(restoredLayout.hidden().contains("shipping"), "隐藏的 shipping 项重载后不得被自动恢复");
        assertTrue(restoredLayout.hidden().contains("mail"));
        assertEquals("精品商城", restoredLayout.nameOf("mall"));

        ShopTemplate tpl = new ShopTemplate();
        tpl.name = "阶段二回归模板";
        tpl.shopSlots = 36;
        tpl.buybackSlots = 48;
        tpl.layout = new ShopTemplate.LayoutData();
        tpl.layout.shopTitle = restoredLayout.shopTitle();
        tpl.layout.brandIconId = restoredLayout.brandIconId();
        tpl.layout.order.addAll(restoredLayout.order());
        tpl.layout.hidden.addAll(restoredLayout.hidden());
        tpl.layout.names.putAll(restoredLayout.names());

        tpl.shipping = new ShopTemplate.ShippingData();
        tpl.shipping.rules.add("minecraft:diamond=100");
        tpl.shipping.dailyLimits.add("64");
        tpl.shipping.resetTime = "04:00";

        tpl.announcements.add(new AnnouncementEntry(
                "ann_1", "开服通知", "公告", "", false, 1, "2026-11-18",
                List.of(
                        AnnouncementBlock.textBlock("首行"),
                        AnnouncementBlock.imageBlock(brandId, 50, "left", "Logo", 32, 32)
                )));

        tpl.adminAuth = new ShopTemplate.AuthData();
        tpl.adminAuth.enabled = true;
        tpl.adminAuth.iterations = 120000;
        tpl.adminAuth.salt = "AQIDBAUGBwgJCgsMDQ4PEA==";
        tpl.adminAuth.hash = "c29tZS12YWxpZC1zaGEyNTYtaGFzaC1ieXRlcy0xMjM0NTY=";
        tpl.adminAuth.sessionTimeoutMinutes = 60;

        byte[] iconBytes = buildValidPng(32, 32);
        tpl.embeddedMedia.put(brandId, iconBytes);

        // .mshop 往返（磁盘与内存字节）
        Path mshopPath = tempDir.resolve("roundtrip.mshop");
        assertEquals("", ShopTemplateStore.write(mshopPath, tpl, EnumSet.allOf(ShopTemplate.ExportSection.class)));
        ShopTemplateStore.ReadDetailedResult mshopRead = ShopTemplateStore.readDetailed(mshopPath);
        assertTrue(mshopRead.ok());
        assertTemplateEqualsCore(tpl, mshopRead.template(), brandId);
        assertArrayEquals(iconBytes, mshopRead.template().embeddedMedia.get(brandId));

        byte[] mshopBytes = ShopTemplateStore.writeToBytes(
                tpl, ShopTemplateStore.TemplateFormat.MSHOP, EnumSet.allOf(ShopTemplate.ExportSection.class));
        ShopTemplateStore.ReadDetailedResult mshopMemRead =
                ShopTemplateStore.readBytesDetailed(mshopBytes, "roundtrip.mshop");
        assertTrue(mshopMemRead.ok());
        assertArrayEquals(iconBytes, mshopMemRead.template().embeddedMedia.get(brandId));

        // .json.gz 往返
        Path gzPath = tempDir.resolve("roundtrip.json.gz");
        assertEquals("", ShopTemplateStore.write(gzPath, tpl, EnumSet.allOf(ShopTemplate.ExportSection.class)));
        ShopTemplateStore.ReadDetailedResult gzRead = ShopTemplateStore.readDetailed(gzPath);
        assertTrue(gzRead.ok());
        assertTemplateEqualsCore(tpl, gzRead.template(), brandId);

        // UTF-8 BOM .json 往返
        byte[] jsonBytes = ShopTemplateStore.writeToBytes(
                tpl, ShopTemplateStore.TemplateFormat.JSON, EnumSet.allOf(ShopTemplate.ExportSection.class));
        byte[] bomPrefix = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] bomJsonBytes = new byte[bomPrefix.length + jsonBytes.length];
        System.arraycopy(bomPrefix, 0, bomJsonBytes, 0, bomPrefix.length);
        System.arraycopy(jsonBytes, 0, bomJsonBytes, bomPrefix.length, jsonBytes.length);

        Path bomJsonPath = tempDir.resolve("with_bom.json");
        Files.write(bomJsonPath, bomJsonBytes);
        ShopTemplateStore.ReadDetailedResult bomFileRead = ShopTemplateStore.readDetailed(bomJsonPath);
        assertTrue(bomFileRead.ok(), () -> "BOM JSON 文件解析失败: " + bomFileRead.errorDetail());
        assertTemplateEqualsCore(tpl, bomFileRead.template(), brandId);

        ShopTemplateStore.ReadDetailedResult bomMemRead =
                ShopTemplateStore.readBytesDetailed(bomJsonBytes, "with_bom.json");
        assertTrue(bomMemRead.ok());
        assertTemplateEqualsCore(tpl, bomMemRead.template(), brandId);

        layout.clear();
        assertTrue(layout.isEmpty());
        assertNull(layout.shopTitle());
        assertEquals("", layout.brandIconId());
    }

    // ------------------------------------------------------------------ 5. 阶段二B ScrollController + PageKit.matchesQuery

    @Test
    void scrollControllerTrackJumpImmediateDragAndPageKitMultiWordSearch() {
        ScrollController sc = new ScrollController();
        sc.setContent(2000.0F, 100.0F);
        assertTrue(sc.canScroll());
        assertEquals(1900.0F, sc.maxScroll(), 0.01F);
        assertFalse(sc.isDragging());

        // 命中区检测：远离右侧返回 false，右侧滚动条热区内返回 true
        assertFalse(sc.overBar(20.0D, 50.0D, 0.0F, 0.0F, 100.0F, 100.0F));
        assertTrue(sc.overBar(96.0D, 50.0D, 0.0F, 0.0F, 100.0F, 100.0F));

        // 点击轨道直达并立刻置 dragging=true 允许不松手续拖
        assertTrue(sc.pressBar(96.0D, 50.0D, 0.0F, 0.0F, 100.0F, 100.0F));
        assertTrue(sc.isDragging());
        assertTrue(sc.offset() > 800.0F && sc.offset() < 1100.0F, "点中轨道中点应比例跳转至中段");

        // 继续向上/向下拖动应准确夹紧 [0, maxScroll]
        assertTrue(sc.dragBar(120.0D, 100.0F));
        assertEquals(1900.0F, sc.offset(), 0.01F);
        assertTrue(sc.dragBar(-40.0D, 100.0F));
        assertEquals(0.0F, sc.offset(), 0.01F);

        assertTrue(sc.releaseBar());
        assertFalse(sc.isDragging());
        assertFalse(sc.releaseBar());

        // PageKit.matchesQuery 多词 AND 大小写不敏感
        assertTrue(PageKit.matchesQuery(null, "anything"));
        assertTrue(PageKit.matchesQuery("   ", "anything"));
        assertTrue(PageKit.matchesQuery("暮色 迷宫", "迷宫砖", "暮色森林", "twilightforest:maze_stone"));
        assertTrue(PageKit.matchesQuery("MAZE stone", "Twilight Forest", "twilightforest:maze_stone"));
        assertFalse(PageKit.matchesQuery("暮色 不存在", "迷宫砖", "暮色森林"));
        assertFalse(PageKit.matchesQuery("diamond", (String[]) null));
        assertFalse(PageKit.matchesQuery("diamond", "", null));
    }

    private static void assertTemplateEqualsCore(ShopTemplate expected, ShopTemplate actual, String brandId) {
        assertNotNull(actual);
        assertEquals(expected.shopSlots, actual.shopSlots, "大于 27 的每日槽位不得被截断");
        assertEquals(expected.buybackSlots, actual.buybackSlots, "大于 27 的回收槽位不得被截断");
        assertNotNull(actual.layout);
        assertEquals("云顶百货总店", actual.layout.shopTitle);
        assertEquals(brandId, actual.layout.brandIconId);
        assertTrue(actual.layout.hidden.contains("shipping"));
        assertEquals("精品商城", actual.layout.names.get("mall"));
        assertNotNull(actual.shipping);
        assertEquals(List.of("64"), actual.shipping.dailyLimits);
        assertEquals("04:00", actual.shipping.resetTime);
        assertEquals(1, actual.announcements.size());
        assertEquals(2, actual.announcements.get(0).blocks().size());
        assertEquals(brandId, actual.announcements.get(0).blocks().get(1).mediaId());
        assertNotNull(actual.adminAuth);
        assertTrue(actual.adminAuth.enabled);
        assertEquals(expected.adminAuth.hash, actual.adminAuth.hash);
    }

    // ------------------------------------------------------------------ 反射桥接（规避 testCompileClasspath 缺 MC/Gson 编译依赖，运行时均在 test.classpath 内）

    private static boolean authIsUnlocked(UUID playerId) {
        return (Boolean) invokeStatic(AdminAuthService.class, "isUnlocked", new Class<?>[]{UUID.class}, playerId);
    }

    private static boolean authCanEditAdmin(UUID playerId, boolean hasOp) {
        return (Boolean) invokeStatic(AdminAuthService.class, "canEditAdmin",
                new Class<?>[]{UUID.class, boolean.class}, playerId, hasOp);
    }

    private static AdminAuthService.UnlockOutcome authTryUnlock(UUID playerId, boolean hasOp, String password) {
        return (AdminAuthService.UnlockOutcome) invokeStatic(AdminAuthService.class, "tryUnlock",
                new Class<?>[]{UUID.class, boolean.class, String.class}, playerId, hasOp, password);
    }

    private static AdminAuthService.SaveAuthOutcome authConfigure(UUID actorId, boolean hasOp, boolean enable,
                                                                   String currentPassword, String newPassword,
                                                                   int timeoutMinutes) {
        return (AdminAuthService.SaveAuthOutcome) invokeStatic(AdminAuthService.class, "configure",
                new Class<?>[]{UUID.class, boolean.class, boolean.class, String.class, String.class, int.class},
                actorId, hasOp, enable, currentPassword, newPassword, timeoutMinutes);
    }

    private static int authRemainingCooldownSeconds(UUID playerId) {
        return (Integer) invokeStatic(AdminAuthService.class, "remainingCooldownSeconds",
                new Class<?>[]{UUID.class}, playerId);
    }

    private static Class<?> compoundTagClass() {
        try {
            return Class.forName("net.minecraft.nbt.CompoundTag");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Class<?> jsonObjectClass() {
        try {
            return Class.forName("com.google.gson.JsonObject");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Object invoke(Class<?> owner, String methodName, Object target) {
        try {
            Method m = owner.getMethod(methodName);
            return m.invoke(target);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Object invokeStatic(Class<?> owner, String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = owner.getMethod(methodName, paramTypes);
            return m.invoke(null, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ 最小合法图片头构造工具

    private static byte[] buildValidPng(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            byte[] ihdrPayload = new byte[13];
            ihdrPayload[0] = (byte) (width >>> 24);
            ihdrPayload[1] = (byte) (width >>> 16);
            ihdrPayload[2] = (byte) (width >>> 8);
            ihdrPayload[3] = (byte) width;
            ihdrPayload[4] = (byte) (height >>> 24);
            ihdrPayload[5] = (byte) (height >>> 16);
            ihdrPayload[6] = (byte) (height >>> 8);
            ihdrPayload[7] = (byte) height;
            ihdrPayload[8] = 8;
            ihdrPayload[9] = 6;
            writePngChunk(out, "IHDR", ihdrPayload);
            writePngChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        dos.write(typeBytes);
        dos.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        dos.writeInt((int) crc.getValue());
    }

    private static byte[] buildValidJpeg(int width, int height) {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xC0,
                0x00, 0x0B,
                0x08,
                (byte) (height >>> 8), (byte) height,
                (byte) (width >>> 8), (byte) width,
                0x01, 0x01, 0x11, 0x00,
                (byte) 0xFF, (byte) 0xD9
        };
    }

    private static byte[] buildValidWebpVp8x(int width, int height) {
        int wm1 = width - 1;
        int hm1 = height - 1;
        return new byte[]{
                'R', 'I', 'F', 'F',
                22, 0, 0, 0,
                'W', 'E', 'B', 'P',
                'V', 'P', '8', 'X',
                10, 0, 0, 0,
                0, 0, 0, 0,
                (byte) wm1, (byte) (wm1 >>> 8), (byte) (wm1 >>> 16),
                (byte) hm1, (byte) (hm1 >>> 8), (byte) (hm1 >>> 16)
        };
    }

    private static byte[] buildValidWebpVp8l(int width, int height) {
        int wm1 = width - 1;
        int hm1 = height - 1;
        int packed = (wm1 & 0x3FFF) | ((hm1 & 0x3FFF) << 14);
        return new byte[]{
                'R', 'I', 'F', 'F',
                17, 0, 0, 0,
                'W', 'E', 'B', 'P',
                'V', 'P', '8', 'L',
                5, 0, 0, 0,
                0x2F,
                (byte) packed,
                (byte) (packed >>> 8),
                (byte) (packed >>> 16),
                (byte) (packed >>> 24)
        };
    }

    // ------------------------------------------------------------------ 6. v1.6.0 回归测试（附属页按键转发、永久限购、兑换上限、出货箱4+级）

    @Test
    void addonPageBackwardCompatibleDefaultsAndShopScreenInputRouting() throws Exception {
        String addonPageSrc = Files.readString(
                Path.of("src/main/java/cn/autoforged/module_shop/api/client/AddonPage.java"),
                StandardCharsets.UTF_8);
        assertTrue(addonPageSrc.contains("default boolean keyPressed(int keyCode, int scanCode, int modifiers)"));
        assertTrue(addonPageSrc.contains("default boolean keyReleased(int keyCode, int scanCode, int modifiers)"));
        assertTrue(addonPageSrc.contains("default boolean charTyped(char codePoint, int modifiers)"));
        assertTrue(addonPageSrc.contains("default boolean isInputFocused()"));

        String shopPageSrc = Files.readString(
                Path.of("src/main/java/cn/autoforged/module_shop/client/screen/shop/ShopPage.java"),
                StandardCharsets.UTF_8);
        assertTrue(shopPageSrc.contains("default boolean keyPressed(int keyCode, int scanCode, int modifiers)"));
        assertTrue(shopPageSrc.contains("default boolean keyReleased(int keyCode, int scanCode, int modifiers)"));
        assertTrue(shopPageSrc.contains("default boolean charTyped(char codePoint, int modifiers)"));
        assertTrue(shopPageSrc.contains("default boolean isInputFocused()"));

        String adapterSrc = Files.readString(
                Path.of("src/main/java/cn/autoforged/module_shop/client/screen/shop/AddonPageAdapter.java"),
                StandardCharsets.UTF_8);
        assertTrue(adapterSrc.contains("public boolean keyPressed(int keyCode, int scanCode, int modifiers)"));
        assertTrue(adapterSrc.contains("public boolean keyReleased(int keyCode, int scanCode, int modifiers)"));
        assertTrue(adapterSrc.contains("public boolean charTyped(char codePoint, int modifiers)"));
        assertTrue(adapterSrc.contains("public boolean isInputFocused()"));

        String shopScreenSrc = Files.readString(
                Path.of("src/main/java/cn/autoforged/module_shop/client/screen/ShopScreen.java"),
                StandardCharsets.UTF_8);
        int keyPressedAt = shopScreenSrc.indexOf("public boolean keyPressed(");
        int digitHotkeysAt = shopScreenSrc.indexOf("GLFW.GLFW_KEY_1", keyPressedAt);
        assertTrue(keyPressedAt > 0 && digitHotkeysAt > keyPressedAt);
        String beforeHotkeys = shopScreenSrc.substring(keyPressedAt, digitHotkeysAt);
        assertTrue(beforeHotkeys.contains("active.keyPressed(keyCode, scanCode, modifiers)"),
                "ShopScreen.keyPressed 必须在 1~9 切页前先转发给 activePage()");
        assertTrue(beforeHotkeys.contains("active.isInputFocused()"),
                "ShopScreen.keyPressed 必须在 1~9 切页前检查 activePage().isInputFocused()");
        assertTrue(shopScreenSrc.contains("public boolean charTyped(char codePoint, int modifiers)"));
        assertTrue(shopScreenSrc.contains("public boolean keyReleased(int keyCode, int scanCode, int modifiers)"));
    }

    @Test
    void mallPermanentLimitAndExchangeQuotasRoundTripAndResetDaily() throws Exception {
        ShopTemplate tpl = new ShopTemplate();
        ShopTemplate.MallEntry mallEntry = new ShopTemplate.MallEntry();
        mallEntry.item = "minecraft:nether_star";
        mallEntry.count = 1;
        mallEntry.price = 1000L;
        mallEntry.stock = 2;
        mallEntry.stockMax = 2;
        mallEntry.permanentLimit = true;
        tpl.mallItems.add(mallEntry);

        ShopTemplate.Exchange ex = new ShopTemplate.Exchange();
        ex.item = "minecraft:emerald";
        ex.count = 1;
        ex.value = 50L;
        ex.dailyDepositLimit = 32;
        ex.dailyWithdrawLimit = 16;
        tpl.exchangeEntries.add(ex);

        var physCfg = new cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig();
        physCfg.enabled = true;
        physCfg.maxConvertOutputItems = 5000;
        physCfg.dailyConvertValueLimit = 100_000L;
        physCfg.tiers.add(new cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.CurrencyTier(
                "copper", "minecraft:copper_ingot", "", 1, 1L, "铜币"));
        tpl.physicalCurrency = physCfg;

        byte[] jsonBytes = ShopTemplateStore.writeToBytes(
                tpl, ShopTemplateStore.TemplateFormat.JSON, EnumSet.allOf(ShopTemplate.ExportSection.class));
        ShopTemplateStore.ReadDetailedResult res =
                ShopTemplateStore.readBytesDetailed(jsonBytes, "roundtrip.json");
        assertTrue(res.ok(), "模板应成功往返解析：" + res.errorCode());
        ShopTemplate loaded = res.template();
        assertEquals(1, loaded.mallItems.size());
        assertTrue(loaded.mallItems.get(0).permanentLimit);
        assertEquals(1, loaded.exchangeEntries.size());
        assertEquals(32, loaded.exchangeEntries.get(0).dailyDepositLimit);
        assertEquals(16, loaded.exchangeEntries.get(0).dailyWithdrawLimit);
        assertNotNull(loaded.physicalCurrency);
        assertEquals(5000, loaded.physicalCurrency.maxConvertOutputItems);
        assertEquals(100_000L, loaded.physicalCurrency.dailyConvertValueLimit);

        // PlayerAccount 每日限额跨日自动归零验证
        cn.autoforged.module_shop.data.PlayerAccount acct = new cn.autoforged.module_shop.data.PlayerAccount();
        acct.recordDepositUsed("minecraft:emerald", 100L, 12);
        acct.recordWithdrawUsed("minecraft:emerald", 100L, 5);
        acct.recordPhysConvertUsed(100L, 2500L);
        assertEquals(12, acct.depositUsedFor("minecraft:emerald", 100L));
        assertEquals(5, acct.withdrawUsedFor("minecraft:emerald", 100L));
        assertEquals(2500L, acct.physConvertUsedForDay(100L));
        assertEquals(0, acct.depositUsedFor("minecraft:emerald", 101L));
        assertEquals(0, acct.withdrawUsedFor("minecraft:emerald", 101L));
        assertEquals(0L, acct.physConvertUsedForDay(101L));
    }

    @Test
    void shippingBinRemovesHardcodedLevelCap() throws Exception {
        String shopManagerSrc = Files.readString(
                Path.of("src/main/java/cn/autoforged/module_shop/shop/ShopManager.java"),
                StandardCharsets.UTF_8);
        int limitsAt = shopManagerSrc.indexOf("private static void adminShippingLimits(");
        assertTrue(limitsAt > 0);
        String limitsBody = shopManagerSrc.substring(limitsAt, Math.min(shopManagerSrc.length(), limitsAt + 600));
        assertFalse(limitsBody.contains("i <= 8"), "adminShippingLimits 不应再硬编码 <= 8 级");
        assertTrue(limitsBody.contains("Config.shippingLevels(data)"));

        String cfgSrc = Files.readString(
                Path.of("src/main/java/cn/autoforged/module_shop/Config.java"),
                StandardCharsets.UTF_8);
        assertTrue(cfgSrc.contains("public static int shippingLevels("));
        assertTrue(cfgSrc.contains("last + extra * 9"));
    }
}
