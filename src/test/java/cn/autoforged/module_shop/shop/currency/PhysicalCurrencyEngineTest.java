package cn.autoforged.module_shop.shop.currency;

import static org.junit.jupiter.api.Assertions.*;

import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.data.PlayerAccount;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.PriceSpec.CurrencyMode;
import cn.autoforged.module_shop.shop.ShippingRules;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.CurrencyTier;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.ConvertPlan;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.PayoutPlan;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.PlanResult;
import cn.autoforged.module_shop.shop.home.AnnouncementConfig;
import cn.autoforged.module_shop.shop.home.AnnouncementEntry;
import cn.autoforged.module_shop.shop.home.ProfileStatsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhysicalCurrencyEngineTest {
    private static final long[] STD_VALUES = {1L, 10L, 100L, 1000L, 5000L};

    private static void assertPhysPlanInvariants(PlanResult res, long targetUnits, long[] values, int[] owned) {
        assertTrue(res.ok(), "预期纯实体支付应成功，但失败原因=" + res.failReason());
        assertEquals(0L, res.virtualDeduct(), "实体账本扣款绝对不能动用虚拟星币");
        long takeVal = 0L;
        long changeVal = 0L;
        for (int i = 0; i < values.length; i++) {
            assertTrue(res.takeCounts()[i] >= 0 && res.takeCounts()[i] <= owned[i],
                    "扣除数量越界: tier=" + i + " take=" + res.takeCounts()[i] + " owned=" + owned[i]);
            assertTrue(res.changeCounts()[i] >= 0, "找零不能为负");
            assertFalse(res.takeCounts()[i] > 0 && res.changeCounts()[i] > 0,
                    "同档位不应既扣又找零，tier=" + i);
            takeVal += res.takeCounts()[i] * values[i];
            changeVal += res.changeCounts()[i] * values[i];
        }
        assertEquals(res.physicalPaid(), takeVal - changeVal, "实体净支付额不自洽");
        assertEquals(targetUnits, res.physicalPaid(), "实体净支付必须严格等于目标面值");
    }

    @Test
    @DisplayName("1. 默认配置安全停用、v2 稳定 id 生成及旧 v1 配置平滑迁移")
    void defaultConfigAndV1Migration(@TempDir Path configDir) throws Exception {
        PhysicalCurrencyConfig.reload(configDir);
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();

        assertFalse(cfg.enabled, "实体货币默认必须关闭");
        assertFalse(cfg.isActive(), "未开启时 isActive 必须为 false");
        assertTrue(cfg.blockBuybackAndShipping, "防套利保护开关默认开启");
        assertEquals(2, cfg.formatVersion);
        assertEquals(5, cfg.tiers.size());
        assertArrayEquals(STD_VALUES, cfg.tierValues());
        assertEquals(1, cfg.tiers.get(0).stepRatio);
        assertEquals(10, cfg.tiers.get(1).stepRatio);
        assertEquals(10, cfg.tiers.get(2).stepRatio);
        assertEquals(10, cfg.tiers.get(3).stepRatio);
        assertEquals(5, cfg.tiers.get(4).stepRatio);
        for (CurrencyTier tier : cfg.tiers) {
            assertFalse(tier.id.isBlank(), "每个实体币档位必须自动分配稳定 id");
            assertEquals(tier.id, tier.tierKey());
            assertSame(tier, cfg.findTier(tier.id));
            assertSame(tier, cfg.findTier(tier.uniqueItemKey()));
            assertSame(tier, cfg.findTier(tier.itemId));
        }
        assertTrue(Files.isRegularFile(PhysicalCurrencyConfig.resolveFile(configDir)));

        // 旧 v1 配置（含废弃 mode / playerOwnedValue，缺少 formatVersion 和 id）也能平滑加载且补齐稳定 id
        Path v1File = PhysicalCurrencyConfig.resolveFile(configDir);
        Files.writeString(v1File, """
                {
                  "enabled": true,
                  "mode": "HYBRID_PHYSICAL_FIRST",
                  "blockBuybackAndShipping": false,
                  "tiers": [
                    { "itemId": "minecraft:iron_nugget", "stepRatio": 1, "value": 1, "label": "铁币" },
                    { "itemId": "minecraft:gold_nugget", "stepRatio": 10, "value": 10, "label": "金币" }
                  ]
                }
                """);
        PhysicalCurrencyConfig.reload(configDir);
        PhysicalCurrencyConfig v1Loaded = PhysicalCurrencyConfig.get();
        assertTrue(v1Loaded.isActive());
        assertFalse(v1Loaded.blockBuybackAndShipping);
        assertEquals(2, v1Loaded.tiers.size());
        assertFalse(v1Loaded.tiers.get(0).id.isBlank());
        assertNotEquals(v1Loaded.tiers.get(0).id, v1Loaded.tiers.get(1).id);

        assertTrue(ShopTemplateStore.isReservedNonTemplate("physical_currency.json"));
        assertTrue(ShopTemplateStore.isReservedNonTemplate("announcements.json"));
    }

    @Test
    @DisplayName("2. 链内直扣与破整找零：1金买3铁 -> 扣1金找7铁；有足够铁币时精确直扣零找零")
    void exactBreakAndChangeFromSingleGold() {
        int[] oneGoldOnly = {0, 0, 1, 0, 0}; // 1 金 = 100
        PlanResult unitPay = PhysicalCurrencyEngine.planPhysicalPay(35L, STD_VALUES, oneGoldOnly);
        assertPhysPlanInvariants(unitPay, 35L, STD_VALUES, oneGoldOnly);
        assertArrayEquals(new int[]{0, 0, 1, 0, 0}, unitPay.takeCounts());
        assertArrayEquals(new int[]{5, 6, 0, 0, 0}, unitPay.changeCounts());

        // 业务不变量：1 金币买 3 铁币商品 -> 扣 1 金找 7 铁
        PlanResult tierBreak = PhysicalCurrencyEngine.planPhysicalTierPay(1, 3L, STD_VALUES, oneGoldOnly);
        assertPhysPlanInvariants(tierBreak, 30L, STD_VALUES, oneGoldOnly);
        assertArrayEquals(new int[]{0, 0, 1, 0, 0}, tierBreak.takeCounts());
        assertArrayEquals(new int[]{0, 7, 0, 0, 0}, tierBreak.changeCounts());

        // 持有充足目标档位时直接扣目标档位（零找零），即使高阶档位也在背包
        int[] hasEnoughIron = {0, 4, 1, 0, 0};
        PlanResult directTake = PhysicalCurrencyEngine.planPhysicalTierPay(1, 3L, STD_VALUES, hasEnoughIron);
        assertPhysPlanInvariants(directTake, 30L, STD_VALUES, hasEnoughIron);
        assertArrayEquals(new int[]{0, 3, 0, 0, 0}, directTake.takeCounts());
        assertArrayEquals(new int[]{0, 0, 0, 0, 0}, directTake.changeCounts());

        // 同档对消：2铁 + 1金 买 25 -> 净 -1金 +7铁 +5泥土
        int[] twoIronOneGold = {0, 2, 1, 0, 0};
        PlanResult cancelRes = PhysicalCurrencyEngine.planPhysicalPay(25L, STD_VALUES, twoIronOneGold);
        assertPhysPlanInvariants(cancelRes, 25L, STD_VALUES, twoIronOneGold);
        assertArrayEquals(new int[]{0, 0, 1, 0, 0}, cancelRes.takeCounts());
        assertArrayEquals(new int[]{5, 7, 0, 0, 0}, cancelRes.changeCounts());
    }

    @Test
    @DisplayName("3. 双独立账本严格隔离：星币/实体永不混扣折抵 + 链内套利拦截")
    void dualLedgerIsolationAndAntiArbitrage(@TempDir Path configDir) {
        PhysicalCurrencyConfig.reload(configDir);
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        cfg.enabled = true;
        PhysicalCurrencyConfig.replaceAndSave(configDir, cfg);
        String ironKey = cfg.tiers.get(1).tierKey();

        // 3a. 实体货币账本短缺时即使星币无限多也绝对不允许挪用星币
        int[] smallOwned = {0, 2, 0, 0, 0}; // 2 铁 = 20
        PlanResult tierShort = PhysicalCurrencyEngine.planPhysicalTierPay(1, 3L, STD_VALUES, smallOwned);
        assertFalse(tierShort.ok());
        assertEquals("INSUFFICIENT_FUNDS", tierShort.failReason());

        // 3b. CurrencySettlement.planSpecMatrix 四态与跨账本严格隔离校验
        List<String> keys = cfg.tiers.stream().map(CurrencyTier::tierKey).toList();
        int[] oneGoldBag = {0, 0, 1, 0, 0}; // 1 金 = 100
        int[] poorBag = {0, 2, 0, 0, 0};    // 2 铁 = 20

        PlayerAccount richStar = new PlayerAccount();
        richStar.balance = 500_000L;
        PlayerAccount poorStar = new PlayerAccount();
        poorStar.balance = 50L;

        // STAR_ONLY 缺星币直接拒绝（即使背包有金币也绝不扣实体币）；禁止指定 phys 通道
        var starFail = CurrencySettlement.planSpecMatrix(poorStar, PriceSpec.star(100L), 1, "star",
                true, keys, STD_VALUES, oneGoldBag);
        assertFalse(starFail.ok());
        assertEquals("msg.module_shop.no_money", starFail.failKey());
        var wrongChan = CurrencySettlement.planSpecMatrix(richStar, PriceSpec.star(100L), 1, "phys",
                true, keys, STD_VALUES, oneGoldBag);
        assertFalse(wrongChan.ok());
        assertEquals("msg.module_shop.channel_not_supported", wrongChan.failKey());

        // PHYS_ONLY 禁止选 star 通道；实体币不足时绝不扣 richStar
        var physRejectStar = CurrencySettlement.planSpecMatrix(richStar, PriceSpec.phys(ironKey, 3L), 1, "star",
                true, keys, STD_VALUES, oneGoldBag);
        assertFalse(physRejectStar.ok());
        assertEquals("msg.module_shop.channel_not_supported", physRejectStar.failKey());
        var physShort = CurrencySettlement.planSpecMatrix(richStar, PriceSpec.phys(ironKey, 3L), 1, "phys",
                true, keys, STD_VALUES, poorBag);
        assertFalse(physShort.ok());
        assertEquals("msg.module_shop.phys_no_money", physShort.failKey());

        // PHYS_ONLY 用 1 金买 3 铁：只动用实体账本，扣 1 金找 7 铁
        var physBreak = CurrencySettlement.planSpecMatrix(poorStar, PriceSpec.phys(ironKey, 3L), 1, "phys",
                true, keys, STD_VALUES, oneGoldBag);
        assertTrue(physBreak.ok());
        assertEquals(CurrencyMode.PHYS_ONLY, physBreak.resolvedMode());
        assertEquals(0L, physBreak.starNeed());
        assertArrayEquals(new int[]{0, 0, 1, 0, 0}, physBreak.takeCounts());
        assertArrayEquals(new int[]{0, 7, 0, 0, 0}, physBreak.changeCounts());

        // EITHER 指定通道独立结算
        PriceSpec either = PriceSpec.either(100L, ironKey, 3L);
        var eitherStar = CurrencySettlement.planSpecMatrix(richStar, either, 1, "star",
                true, keys, STD_VALUES, poorBag);
        assertTrue(eitherStar.ok());
        assertEquals(CurrencyMode.STAR_ONLY, eitherStar.resolvedMode());
        assertEquals(100L, eitherStar.starNeed());
        assertEquals(0L, eitherStar.physCount());

        var eitherPhysFail = CurrencySettlement.planSpecMatrix(richStar, either, 1, "phys",
                true, keys, STD_VALUES, poorBag);
        assertFalse(eitherPhysFail.ok());

        // BOTH 任一账本不足必须原子整单拦截
        PriceSpec both = PriceSpec.both(100L, ironKey, 3L);
        var bothStarShort = CurrencySettlement.planSpecMatrix(poorStar, both, 1, "both",
                true, keys, STD_VALUES, oneGoldBag);
        assertFalse(bothStarShort.ok());
        assertEquals("msg.module_shop.no_money", bothStarShort.failKey());

        var bothOk = CurrencySettlement.planSpecMatrix(richStar, both, 2, "both",
                true, keys, STD_VALUES, oneGoldBag);
        assertTrue(bothOk.ok());
        assertEquals(CurrencyMode.BOTH, bothOk.resolvedMode());
        assertEquals(200L, bothOk.starNeed());
        assertArrayEquals(new int[]{0, 0, 1, 0, 0}, bothOk.takeCounts());
        assertArrayEquals(new int[]{0, 4, 0, 0, 0}, bothOk.changeCounts());

        // 3c. 链内等值防套利：用 90 泥土(90)买 1 金(100)拦截；用 100 泥土/10 铁买 1 金放行
        assertTrue(PhysicalCurrencyEngine.isChainArbitrage(STD_VALUES, 2, 1L, 0, 90L));
        assertFalse(PhysicalCurrencyEngine.isChainArbitrage(STD_VALUES, 2, 1L, 0, 100L));
        assertFalse(PhysicalCurrencyEngine.isChainArbitrage(STD_VALUES, 2, 1L, 1, 10L));
    }

    @Test
    @DisplayName("4. 缺基础档抗不可分保护、找零防炸拦截与按标定档位发放压缩")
    void missingUnitTierChangeExplosionAndTierPayout() {
        long[] noOneTier = {10L, 100L};
        int[] oneGold = {0, 1};

        // 实体模式没有 1 面值档位，买 35 必须拒绝，绝不回退星币
        PlanResult pureFail = PhysicalCurrencyEngine.planPhysicalPay(35L, noOneTier, oneGold);
        assertFalse(pureFail.ok());
        assertEquals("INDIVISIBLE_AMOUNT", pureFail.failReason());

        // 非倍比档位 [2, 3] 买 2 单位 -> 扣 2×3=6 并精确找零 2×2=4（净付 2）
        long[] coprime = {2L, 3L};
        int[] twoThrees = {0, 2};
        PlanResult coprimePay = PhysicalCurrencyEngine.planPhysicalPay(2L, coprime, twoThrees);
        assertPhysPlanInvariants(coprimePay, 2L, coprime, twoThrees);
        assertArrayEquals(new int[]{0, 2}, coprimePay.takeCounts());
        assertArrayEquals(new int[]{2, 0}, coprimePay.changeCounts());

        // 极差面值 [1, 1_000_000] 买 1 触发最大找零物品件数防炸拦截
        long[] extremeTiers = {1L, 1_000_000L};
        int[] bigCoin = {0, 1};
        PlanResult guardFail = PhysicalCurrencyEngine.planPhysicalPay(1L, extremeTiers, bigCoin, 2304);
        assertFalse(guardFail.ok());
        assertEquals("CHANGE_TOO_MANY_ITEMS", guardFail.failReason());

        // 按标定档位发放：常规件数优先发指定档位本身；超大件数才向上压箱
        PayoutPlan directTier = PhysicalCurrencyEngine.planTierPayout(1, 5L, STD_VALUES, 2304);
        assertEquals(0L, directTier.remainder());
        assertArrayEquals(new int[]{0, 5, 0, 0, 0}, directTier.counts());

        PayoutPlan compressed = PhysicalCurrencyEngine.planTierPayout(0, 50_000L, STD_VALUES, 2304);
        assertEquals(0L, compressed.remainder());
        assertArrayEquals(new int[]{0, 0, 0, 0, 10}, compressed.counts());
    }

    @Test
    @DisplayName("5. 收入发放到实体档位拆分与零头保留不变量 + 大数饱和防溢出")
    void payoutDecompositionAndSaturation() {
        PayoutPlan full = PhysicalCurrencyEngine.decomposePayout(15678L, STD_VALUES);
        assertArrayEquals(new int[]{8, 7, 6, 0, 3}, full.counts());
        assertEquals(0L, full.remainder());

        PayoutPlan withRem = PhysicalCurrencyEngine.decomposePayout(35L, new long[]{10L, 100L});
        assertArrayEquals(new int[]{3, 0}, withRem.counts());
        assertEquals(5L, withRem.remainder());

        long sat = PhysicalCurrencyEngine.totalPhysicalValue(
                new long[]{Long.MAX_VALUE / 2}, new int[]{10});
        assertEquals(Long.MAX_VALUE, sat, "持币估值必须饱和截断而非长整型溢出成负数");
    }

    @Test
    @DisplayName("6. 阶梯实体货币双向兑换（凑整 / 拆零）比例锁与防爆量校验")
    void tierConversionChecks() {
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.createDefault();
        cfg.enabled = true;
        cfg.normalize();

        // 10 泥土 -> 1 铁（请求 2 批，包里只有 15 泥土 -> 拒绝）
        int[] owned = {15, 2, 0, 0, 0};
        ConvertPlan notEnough = PhysicalCurrencyEngine.planConversion(cfg, 1, "up", 2, owned);
        assertFalse(notEnough.ok());
        assertEquals("NOT_ENOUGH_ITEMS", notEnough.failReason());

        // 请求 1 批：10 泥土 -> 1 铁
        ConvertPlan upOk = PhysicalCurrencyEngine.planConversion(cfg, 1, "up", 1, owned);
        assertTrue(upOk.ok());
        assertEquals(0, upOk.takeTier());
        assertEquals(10, upOk.takeCount());
        assertEquals(1, upOk.giveTier());
        assertEquals(1, upOk.giveCount());

        // 拆零：2 铁 -> 20 泥土
        ConvertPlan downOk = PhysicalCurrencyEngine.planConversion(cfg, 1, "down", 2, owned);
        assertTrue(downOk.ok());
        assertEquals(1, downOk.takeTier());
        assertEquals(2, downOk.takeCount());
        assertEquals(0, downOk.giveTier());
        assertEquals(20, downOk.giveCount());

        // 防恶意传大 batches 刷爆邮件限制
        ConvertPlan tooMany = PhysicalCurrencyEngine.planConversion(cfg, 1, "down", 500, new int[]{0, 500, 0, 0, 0});
        assertFalse(tooMany.ok());
        assertEquals("TOO_MANY_OUTPUT_ITEMS", tooMany.failReason());

        // 非整数倍比配置禁止跨档直接兑换（如 3 铁 vs 10 金）
        PhysicalCurrencyConfig nonMult = new PhysicalCurrencyConfig();
        nonMult.enabled = true;
        nonMult.tiers.add(new CurrencyTier("minecraft:dirt", "", 1, 3L, "A"));
        nonMult.tiers.add(new CurrencyTier("minecraft:iron_ingot", "", 3, 10L, "B"));
        nonMult.normalize();
        assertEquals(0, nonMult.tiers.get(1).stepRatio);
        ConvertPlan badRatio = PhysicalCurrencyEngine.planConversion(nonMult, 1, "up", 1, new int[]{10, 1});
        assertFalse(badRatio.ok());
        assertEquals("INVALID_RATIO", badRatio.failReason());
    }

    @Test
    @DisplayName("7. 公告排序规则（置顶优先 + order 升序）与空白清空保存不复活默认公告")
    void announcementConfigPersistenceAndSorting(@TempDir Path configDir) {
        AnnouncementConfig.reload(configDir);
        List<AnnouncementEntry> initial = AnnouncementConfig.get().sortedCopy();
        assertTrue(initial.size() >= 2);
        assertTrue(initial.get(0).pinned, "首次生成的默认公告首条应置顶");

        // 模拟管理员提交乱序公告 -> 自动按 pinned 降序、order 升序落盘
        List<AnnouncementEntry> unsort = List.of(
                new AnnouncementEntry("id1", "普通二号", "公告", "内容", false, 5, "2026-11-16"),
                new AnnouncementEntry("id2", "置顶二号", "置顶", "内容", true, 2, "2026-11-16"),
                new AnnouncementEntry("id3", "置顶一号", "置顶", "内容", true, 1, "2026-11-16"),
                new AnnouncementEntry("id4", "普通一号", "公告", "内容", false, 1, "2026-11-16")
        );
        assertTrue(AnnouncementConfig.replaceAndSave(configDir, unsort));

        AnnouncementConfig.reload(configDir);
        List<AnnouncementEntry> reloaded = AnnouncementConfig.get().sortedCopy();
        assertEquals(List.of("id3", "id2", "id4", "id1"),
                reloaded.stream().map(e -> e.id).toList());

        // 清空保存后再重载，必须尊重空列表而不是重新写出默认公告
        assertTrue(AnnouncementConfig.replaceAndSave(configDir, List.of()));
        AnnouncementConfig.reload(configDir);
        assertTrue(AnnouncementConfig.get().sortedCopy().isEmpty());
    }

    @Test
    @DisplayName("8. LuckPerms 称号颜色代码清洗/降级为客户端 § 渲染格式")
    void luckPermsColorTranslation() {
        assertEquals("§a§l[VIP]§r ", ProfileStatsService.translateColorCodes("&a&l[VIP]&r "));
        String hexOut = ProfileStatsService.translateColorCodes("&#FF0000[Admin] &7");
        assertTrue(hexOut.startsWith("§"), "&#FF0000 应转换为标准 § 颜色码");
        assertFalse(hexOut.contains("&#"), "不应残留原始 &# 文本");
        assertTrue(hexOut.endsWith("[Admin] §7"));
        assertEquals("§4Tester", ProfileStatsService.translateColorCodes("<bold>&x&f&f&0&0&0&0Tester</bold>"));
        assertEquals("§cTester2", ProfileStatsService.translateColorCodes("&#FF5555Tester2"));
    }

    @Test
    @DisplayName("9. PriceSpec 四态缩放/规则文本往返与出货箱规则防误切 x 校验")
    void priceSpecSerializationAndShippingRuleParsing() {
        String weirdKey = "mod:coin|{name:\"a+b*c x=1\"}";
        List<PriceSpec> specs = List.of(
                PriceSpec.star(250L),
                PriceSpec.phys(weirdKey, 7L),
                PriceSpec.either(500L, weirdKey, 3L),
                PriceSpec.both(120L, "iron_tier", 4L)
        );
        for (PriceSpec spec : specs) {
            PriceSpec fromRule = PriceSpec.parseRuleText(spec.toRuleText());
            assertEquals(spec, fromRule, "规则文本往返不一致: " + spec.toRuleText());
            assertTrue(spec.sameCurrencyUnit(fromRule));
        }

        // scale 与独立字段变更测试
        PriceSpec unitEither = PriceSpec.either(100L, "iron_tier", 3L);
        assertEquals(PriceSpec.either(400L, "iron_tier", 12L), unitEither.scale(4));
        assertEquals(PriceSpec.either(77L, "iron_tier", 3L), unitEither.withStarPrice(77L));
        assertEquals(PriceSpec.either(100L, "gold_tier", 3L), unitEither.withPhysTierKey("gold_tier"));
        assertFalse(PriceSpec.phys("iron_tier", 2L).sameCurrencyUnit(PriceSpec.star(10L)));
        assertFalse(PriceSpec.phys("iron_tier", 2L).sameCurrencyUnit(PriceSpec.phys("gold_tier", 2L)));

        // 纯数字 ruleText 无条件解析为 STAR_ONLY
        assertEquals(PriceSpec.star(100L), PriceSpec.parseRuleText("100"));
        assertEquals(PriceSpec.ZERO, PriceSpec.parseRuleText("0"));
        assertNull(PriceSpec.parseRuleText("100|phys:copper*0"));
        assertNull(PriceSpec.parseRuleText("100+phys:*2"));

        // ShippingRules.parse 不可因物品 ID 中的字母 x（如 experience_bottle）误截断
        ShippingRules.Parsed parsed = ShippingRules.parse(List.of(
                "#c:ores=phys:minecraft:experience_bottle*3 x64",
                "minecraft:*=100|phys:copper_coin*5 x16",
                "#c:gems=200+phys:gold_coin*1"
        ));
        assertTrue(parsed.errors().isEmpty(), "合法双账本出货规则不应报错: " + parsed.errors());
        assertEquals(3, parsed.rules().size());

        ShippingRules.Rule r0 = parsed.rules().get(0);
        assertEquals(PriceSpec.phys("minecraft:experience_bottle", 3L), r0.resolvePriceSpec());
        assertEquals(64, r0.dailyMax());
        assertEquals(0L, r0.price());

        ShippingRules.Rule r1 = parsed.rules().get(1);
        assertEquals(PriceSpec.either(100L, "copper_coin", 5L), r1.resolvePriceSpec());
        assertEquals(16, r1.dailyMax());
        assertEquals(100L, r1.price());

        ShippingRules.Rule r2 = parsed.rules().get(2);
        assertEquals(PriceSpec.both(200L, "gold_coin", 1L), r2.resolvePriceSpec());
        assertEquals(0, r2.dailyMax());
    }
}
