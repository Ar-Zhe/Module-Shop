package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.PhysTierView;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 实体货币系统管理配置弹窗（仅管理员，继承 {@link SkiaDialog}，Forge 1.20.1）：
 * <ul>
 *     <li>顶部：总开关（默认关闭）、防套利保护开关、三种结算模式切换；</li>
 *     <li>左侧：可视化货币阶梯兑换链（支持新增、删除、上下移、一键填入默认示例链条）；</li>
 *     <li>右侧：等级编辑（显示名、晋级比例联动后续面值、手动改面值、物品 ID、NBT SNBT 与玩家背包拾取保留 NBT）；</li>
 *     <li>底部：温和原版物品防刷提示与「保存并热更新」（{@code admin_phys_currency_save}）。</li>
 * </ul>
 */
public class PhysicalCurrencyConfigScreen extends SkiaDialog {
    private static final float MAX_W = 420.0F;
    private static final float MAX_H = 262.0F;

    private static final class TierDraft {
        String id = "";
        String itemId = "";
        String componentsSnbt = "";
        int stepRatio = 1;
        long value = 1L;
        String label = "";
        ItemStack sampleStack = ItemStack.EMPTY;
        boolean customPicked;
        boolean addonDefault;

        String uniqueItemKey() {
            String item = this.itemId == null ? "" : this.itemId.trim().toLowerCase(java.util.Locale.ROOT);
            String snbt = this.componentsSnbt == null ? "" : this.componentsSnbt.trim();
            return item + "|" + snbt;
        }

        ItemStack displayStack() {
            if (this.sampleStack != null && !this.sampleStack.isEmpty()) {
                return this.sampleStack;
            }
            this.sampleStack = ClientHomeCurrencyState.resolveStack(
                    this.itemId, this.componentsSnbt, ClientHomeCurrencyState.currentRegistries());
            return this.sampleStack;
        }

        boolean hasCustomComponents() {
            return (this.componentsSnbt != null && !this.componentsSnbt.isBlank()
                    && !"{}".equals(this.componentsSnbt.trim()))
                    || ClientHomeCurrencyState.hasNonEmptyTag(this.sampleStack);
        }

        String displayTitle() {
            if (this.label != null && !this.label.isBlank()) {
                return this.label;
            }
            ItemStack st = displayStack();
            if (!st.isEmpty()) {
                return PageKit.plainName(st);
            }
            return this.itemId == null || this.itemId.isBlank() ? "?" : this.itemId;
        }
    }

    private boolean enabled;
    private boolean blockBuyback;
    private int maxConvertItems;
    private long dailyConvertLimit;
    private final List<TierDraft> tiers = new ArrayList<>();
    private final ScrollController listScroll = new ScrollController();

    private int selectedIndex = -1;
    private boolean pickingInventory;
    private boolean syncingFields;
    private String errorText = "";

    private SkiaTextField maxConvertField;
    private SkiaTextField dailyConvertLimitField;
    private SkiaTextField labelField;
    private SkiaTextField ratioField;
    private SkiaTextField valueField;
    private SkiaTextField itemIdField;
    private SkiaTextField snbtField;

    private int[] fbBackBtn;

    public PhysicalCurrencyConfigScreen() {
        super(Component.literal(tr("gui.module_shop.phys_cfg.title", "实体货币系统配置")));
        this.enabled = ClientHomeCurrencyState.physEnabled;
        this.blockBuyback = ClientHomeCurrencyState.physBlockBuyback;
        this.maxConvertItems = Math.max(1, Math.min(64000, ClientHomeCurrencyState.physMaxConvertItems));
        this.dailyConvertLimit = Math.max(0L, ClientHomeCurrencyState.physDailyConvertLimit);
        int addonDefaultIdx = ClientHomeCurrencyState.findTierIndex(ClientHomeCurrencyState.physAddonDefaultTier);
        List<PhysTierView> initTiers = ClientHomeCurrencyState.physTiers;
        for (int i = 0; i < initTiers.size(); i++) {
            PhysTierView tv = initTiers.get(i);
            TierDraft d = new TierDraft();
            d.id = tv.id == null ? "" : tv.id;
            d.itemId = tv.itemId == null ? "" : tv.itemId;
            d.componentsSnbt = tv.componentsSnbt == null ? "" : tv.componentsSnbt;
            d.stepRatio = Math.max(1, tv.stepRatio);
            d.value = Math.max(1L, tv.value);
            d.label = tv.label == null ? "" : tv.label;
            d.sampleStack = tv.itemStack == null ? ItemStack.EMPTY : tv.itemStack.copy();
            d.customPicked = ClientHomeCurrencyState.hasNonEmptyTag(d.sampleStack);
            d.addonDefault = (i == addonDefaultIdx);
            this.tiers.add(d);
        }
        if (!this.tiers.isEmpty()) {
            this.selectedIndex = 0;
        }
    }

    private static String tr(String key, String fallback, Object... args) {
        return ClientHomeCurrencyState.tr(key, fallback, args);
    }

    @Override
    public void onClose() {
        if (this.pickingInventory) {
            this.pickingInventory = false;
            syncFieldsFromSelected();
            return;
        }
        Nav.back();
    }

    @Override
    protected boolean onEnter() {
        return false;
    }

    @Override
    protected void initSkia() {
        this.maxConvertField = field(5, "2304").fontSize(Text.SMALL);
        this.dailyConvertLimitField = field(14, "0").fontSize(Text.SMALL);
        this.maxConvertField.editBox().setFilter(s -> s.isEmpty() || s.matches("[0-9]{0,5}"));
        this.dailyConvertLimitField.editBox().setFilter(s -> s.isEmpty() || s.matches("[0-9]{0,14}"));
        this.maxConvertField.setValue(String.valueOf(this.maxConvertItems));
        this.dailyConvertLimitField.setValue(String.valueOf(this.dailyConvertLimit));
        this.maxConvertField.onChange(val -> {
            int parsed = parsePositiveInt(val, 0);
            if (parsed > 0) {
                this.maxConvertItems = parsed;
            }
            this.errorText = "";
        });
        this.dailyConvertLimitField.onChange(val -> {
            long parsed = (val == null || val.isBlank()) ? 0L : parsePositiveLong(val, -1L);
            if (parsed >= 0L) {
                this.dailyConvertLimit = parsed;
            }
            this.errorText = "";
        });

        this.labelField = field(32, tr("gui.module_shop.phys_cfg.ph_label", "如：金币")).fontSize(Text.SMALL);
        this.ratioField = field(8, "10").fontSize(Text.SMALL);
        this.valueField = field(14, "100").fontSize(Text.SMALL);
        this.itemIdField = field(120, "minecraft:gold_ingot").fontSize(Text.SMALL);
        this.snbtField = field(2000, tr("gui.module_shop.phys_cfg.ph_snbt", "留空 = 无组件要求")).fontSize(Text.SMALL);

        this.ratioField.editBox().setFilter(s -> s.isEmpty() || s.matches("[0-9]{0,8}"));
        this.valueField.editBox().setFilter(s -> s.isEmpty() || s.matches("[0-9]{0,14}"));

        this.labelField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            TierDraft sel = selected();
            if (sel != null) {
                sel.label = val == null ? "" : val;
                this.errorText = "";
            }
        });
        this.ratioField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            TierDraft sel = selected();
            if (sel == null || this.selectedIndex <= 0) {
                return;
            }
            int parsed = parsePositiveInt(val, 0);
            if (parsed >= 1) {
                sel.stepRatio = parsed;
                recalculateChainFrom(this.selectedIndex);
                syncValueFieldOnly();
                this.errorText = "";
            }
        });
        this.valueField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            TierDraft sel = selected();
            if (sel == null) {
                return;
            }
            long parsed = parsePositiveLong(val, 0L);
            if (parsed >= 1L) {
                sel.value = parsed;
                this.errorText = "";
            }
        });
        this.itemIdField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            TierDraft sel = selected();
            if (sel != null) {
                sel.itemId = val == null ? "" : val.trim();
                sel.customPicked = false;
                sel.sampleStack = ClientHomeCurrencyState.resolveStack(
                        sel.itemId, sel.componentsSnbt, ClientHomeCurrencyState.currentRegistries());
                this.errorText = "";
            }
        });
        this.snbtField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            TierDraft sel = selected();
            if (sel != null) {
                sel.componentsSnbt = val == null ? "" : val.trim();
                sel.customPicked = false;
                sel.sampleStack = ClientHomeCurrencyState.resolveStack(
                        sel.itemId, sel.componentsSnbt, ClientHomeCurrencyState.currentRegistries());
                this.errorText = "";
            }
        });

        syncFieldsFromSelected();
    }

    // ------------------------------------------------------------------ 数据辅助

    private TierDraft selected() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tiers.size()) {
            return null;
        }
        return this.tiers.get(this.selectedIndex);
    }

    private void selectIndex(int idx) {
        this.pickingInventory = false;
        if (this.tiers.isEmpty()) {
            this.selectedIndex = -1;
        } else {
            this.selectedIndex = Math.max(0, Math.min(this.tiers.size() - 1, idx));
        }
        syncFieldsFromSelected();
    }

    private void syncFieldsFromSelected() {
        if (this.labelField == null) {
            return;
        }
        this.syncingFields = true;
        try {
            TierDraft sel = selected();
            if (sel == null) {
                this.labelField.setValue("");
                this.ratioField.setValue("");
                this.valueField.setValue("");
                this.itemIdField.setValue("");
                this.snbtField.setValue("");
                return;
            }
            this.labelField.setValue(sel.label);
            this.ratioField.setValue(String.valueOf(this.selectedIndex == 0 ? 1 : Math.max(1, sel.stepRatio)));
            this.valueField.setValue(String.valueOf(Math.max(1L, sel.value)));
            this.itemIdField.setValue(sel.itemId);
            this.snbtField.setValue(sel.componentsSnbt);
        } finally {
            this.syncingFields = false;
        }
    }

    private void syncValueFieldOnly() {
        if (this.valueField == null) {
            return;
        }
        boolean prev = this.syncingFields;
        this.syncingFields = true;
        try {
            TierDraft sel = selected();
            this.valueField.setValue(sel == null ? "" : String.valueOf(Math.max(1L, sel.value)));
        } finally {
            this.syncingFields = prev;
        }
    }

    private void recalculateChainFrom(int startIdx) {
        if (this.tiers.isEmpty()) {
            return;
        }
        int start = Math.max(1, startIdx);
        for (int i = start; i < this.tiers.size(); i++) {
            TierDraft prev = this.tiers.get(i - 1);
            TierDraft cur = this.tiers.get(i);
            int ratio = Math.max(1, cur.stepRatio);
            cur.stepRatio = ratio;
            cur.value = safeMul(Math.max(1L, prev.value), ratio);
        }
    }

    private static long safeMul(long a, int b) {
        if (a <= 0L || b <= 0) {
            return 1L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    private void addTier() {
        TierDraft d = new TierDraft();
        if (this.tiers.isEmpty()) {
            d.itemId = "minecraft:dirt";
            d.stepRatio = 1;
            d.value = 1L;
            d.label = tr("gui.module_shop.phys_cfg.sample_dirt", "泥土币");
        } else {
            TierDraft last = this.tiers.get(this.tiers.size() - 1);
            d.itemId = "minecraft:emerald";
            d.stepRatio = 10;
            d.value = safeMul(Math.max(1L, last.value), 10);
            d.label = tr("gui.module_shop.phys_cfg.new_tier_label", "货币 %s", this.tiers.size() + 1);
        }
        d.sampleStack = ClientHomeCurrencyState.resolveStack(d.itemId, "", ClientHomeCurrencyState.currentRegistries());
        this.tiers.add(d);
        selectIndex(this.tiers.size() - 1);
        this.listScroll.scrollTo(Float.MAX_VALUE, true);
        this.errorText = "";
    }

    private void fillDefaultExample() {
        this.tiers.clear();
        RegistryAccess regs = ClientHomeCurrencyState.currentRegistries();
        addExampleTier("minecraft:dirt", 1, 1L, tr("gui.module_shop.phys_cfg.sample_dirt", "泥土币"), regs);
        addExampleTier("minecraft:iron_ingot", 10, 10L, tr("gui.module_shop.phys_cfg.sample_iron", "铁币"), regs);
        addExampleTier("minecraft:gold_ingot", 10, 100L, tr("gui.module_shop.phys_cfg.sample_gold", "金币"), regs);
        addExampleTier("minecraft:diamond", 10, 1000L, tr("gui.module_shop.phys_cfg.sample_diamond", "钻石币"), regs);
        addExampleTier("minecraft:netherite_ingot", 5, 5000L, tr("gui.module_shop.phys_cfg.sample_netherite", "下界合金币"), regs);
        selectIndex(0);
        this.listScroll.reset();
        this.errorText = "";
    }

    private void addExampleTier(String id, int ratio, long val, String label, RegistryAccess regs) {
        TierDraft d = new TierDraft();
        d.itemId = id;
        d.componentsSnbt = "";
        d.stepRatio = ratio;
        d.value = val;
        d.label = label;
        d.sampleStack = ClientHomeCurrencyState.resolveStack(id, "", regs);
        this.tiers.add(d);
    }

    private void deleteSelected() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tiers.size()) {
            return;
        }
        this.tiers.remove(this.selectedIndex);
        if (!this.tiers.isEmpty()) {
            this.tiers.get(0).stepRatio = 1;
            selectIndex(Math.min(this.selectedIndex, this.tiers.size() - 1));
        } else {
            selectIndex(-1);
        }
        this.errorText = "";
    }

    private void moveSelected(int delta) {
        int next = this.selectedIndex + delta;
        if (this.selectedIndex < 0 || next < 0 || next >= this.tiers.size()) {
            return;
        }
        TierDraft moved = this.tiers.remove(this.selectedIndex);
        this.tiers.add(next, moved);
        this.tiers.get(0).stepRatio = 1;
        this.selectedIndex = next;
        syncFieldsFromSelected();
        this.errorText = "";
    }

    private void pickFromInventory(ItemStack stack) {
        TierDraft sel = selected();
        if (sel == null || stack == null || stack.isEmpty()) {
            return;
        }
        RegistryAccess regs = ClientHomeCurrencyState.currentRegistries();
        ItemStack single = stack.copyWithCount(1);
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(single.getItem());
        String prevName = sel.displayStack().isEmpty() ? "" : PageKit.plainName(sel.displayStack());
        sel.sampleStack = single;
        sel.customPicked = true;
        sel.itemId = key == null ? "" : key.toString();
        sel.componentsSnbt = ClientHomeCurrencyState.extractComponentsSnbt(single, regs);
        if (sel.label == null || sel.label.isBlank() || sel.label.equals(prevName)) {
            sel.label = PageKit.plainName(single);
        }
        this.pickingInventory = false;
        this.errorText = "";
        syncFieldsFromSelected();
    }

    private void saveConfig() {
        if (this.enabled && this.tiers.isEmpty()) {
            this.errorText = tr("gui.module_shop.phys_cfg.err_empty", "启用实体货币前请至少配置 1 个货币等级");
            return;
        }
        int nextMaxConvert = parsePositiveInt(
                this.maxConvertField == null ? String.valueOf(this.maxConvertItems) : this.maxConvertField.value(), -1);
        if (nextMaxConvert < 1 || nextMaxConvert > 64000) {
            this.errorText = tr("gui.module_shop.phys_cfg.err_max_convert", "单次阶梯兑换上限必须为 1 ~ 64000 的整数");
            return;
        }
        String rawDaily = this.dailyConvertLimitField == null
                ? String.valueOf(this.dailyConvertLimit)
                : this.dailyConvertLimitField.value();
        long nextDailyLimit = (rawDaily == null || rawDaily.isBlank()) ? 0L : parsePositiveLong(rawDaily, -1L);
        if (nextDailyLimit < 0L) {
            this.errorText = tr("gui.module_shop.phys_cfg.err_daily_limit", "每日兑换上限不能为负数（0 = 不限）");
            return;
        }
        RegistryAccess regs = ClientHomeCurrencyState.currentRegistries();
        List<ItemStack> seenStacks = new ArrayList<>();
        Set<Long> seenValues = new HashSet<>();
        for (int i = 0; i < this.tiers.size(); i++) {
            TierDraft t = this.tiers.get(i);
            String id = t.itemId == null ? "" : t.itemId.trim();
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Optional<Item> opt = rl == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(rl);
            if (opt.isEmpty() || opt.get() == Items.AIR) {
                selectIndex(i);
                this.errorText = tr("gui.module_shop.phys_cfg.err_item", "第 %s 级物品 ID 无效：%s", i + 1, id);
                return;
            }
            if (!ClientHomeCurrencyState.isValidComponentsSnbt(t.componentsSnbt)) {
                selectIndex(i);
                this.errorText = tr("gui.module_shop.phys_cfg.err_snbt", "第 %s 级组件 SNBT 格式无效", i + 1);
                return;
            }
            if (t.value < 1L || t.stepRatio < 1) {
                selectIndex(i);
                this.errorText = tr("gui.module_shop.phys_cfg.err_num", "第 %s 级比例与面值必须为正整数", i + 1);
                return;
            }
            if (!seenValues.add(t.value)) {
                selectIndex(i);
                this.errorText = tr("gui.module_shop.phys_cfg.err_dup_val", "第 %s 级存在重复的货币面值：%s", i + 1, t.value);
                return;
            }
            ItemStack resolved = t.customPicked && !t.sampleStack.isEmpty()
                    ? t.sampleStack.copyWithCount(1)
                    : ClientHomeCurrencyState.resolveStack(id, t.componentsSnbt, regs);
            for (int j = 0; j < seenStacks.size(); j++) {
                if (ItemStack.isSameItemSameTags(seenStacks.get(j), resolved)) {
                    selectIndex(i);
                    this.errorText = tr("gui.module_shop.phys_cfg.err_dup", "第 %s 级与第 %s 级货币物品完全相同", j + 1, i + 1);
                    return;
                }
            }
            seenStacks.add(resolved);
        }

        ListTag tierList = new ListTag();
        for (int i = 0; i < this.tiers.size(); i++) {
            TierDraft t = this.tiers.get(i);
            CompoundTag itemTag = new CompoundTag();
            if (t.id != null && !t.id.isBlank()) {
                itemTag.putString("id", t.id.trim());
            }
            itemTag.putString("itemId", t.itemId == null ? "" : t.itemId.trim());
            itemTag.putString("componentsSnbt", t.componentsSnbt == null ? "" : t.componentsSnbt.trim());
            itemTag.putInt("stepRatio", i == 0 ? 1 : Math.max(1, t.stepRatio));
            itemTag.putLong("value", Math.max(1L, t.value));
            itemTag.putString("label", t.label == null || t.label.isBlank() ? t.displayTitle() : t.label.trim());
            if (t.customPicked && t.sampleStack != null && !t.sampleStack.isEmpty()) {
                itemTag.put("sampleStack", t.sampleStack.copyWithCount(1).save(new CompoundTag()));
            }
            tierList.add(itemTag);
        }

        String addonDefaultKey = "";
        TierDraft defDraft = addonDefaultDraft();
        if (defDraft != null) {
            addonDefaultKey = (defDraft.id != null && !defDraft.id.isBlank())
                    ? defDraft.id.trim()
                    : defDraft.uniqueItemKey();
        }

        CompoundTag payload = new CompoundTag();
        payload.putString("action", "admin_phys_currency_save");
        payload.putBoolean("physEnabled", this.enabled);
        payload.putBoolean("physBlockBuyback", this.blockBuyback);
        payload.putInt("physMaxConvertItems", nextMaxConvert);
        payload.putLong("physDailyConvertLimit", nextDailyLimit);
        payload.putString("physAddonDefaultTier", addonDefaultKey);
        payload.put("physTiers", tierList);
        ServerboundShopActionPayload.send(payload);
        Nav.back();
    }

    private void toggleAddonDefault(TierDraft target) {
        if (target == null) {
            return;
        }
        boolean next = !target.addonDefault;
        for (TierDraft t : this.tiers) {
            t.addonDefault = false;
        }
        target.addonDefault = next;
        this.errorText = "";
    }

    private TierDraft addonDefaultDraft() {
        for (TierDraft t : this.tiers) {
            if (t.addonDefault) {
                return t;
            }
        }
        return null;
    }

    private boolean hasAnyVanillaWithoutComponents() {
        for (TierDraft t : this.tiers) {
            if (ClientHomeCurrencyState.isVanillaWithoutComponents(t.itemId, t.componentsSnbt, t.displayStack())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Skija 绘制

    @Override
    protected void paint(Canvas canvas) {
        float w = Math.max(300.0F, Math.min(MAX_W, this.width - 20.0F));
        float h = Math.max(220.0F, Math.min(MAX_H, this.height - 20.0F));
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);

        panel(canvas, x, y, w, h, this.title.getString());
        float closeX = x + w - PAD - 8.0F;
        float closeY = y + HEADER_H * 0.5F;
        float chv = hover("close", closeX - 6.0F, closeY - 6.0F, 12.0F, 12.0F);
        Draw.cross(canvas, closeX, closeY, 3.0F, 1.1F, Draw.lerp(IOStyle.TEXT_2, IOStyle.RED, chv));
        hit(closeX - 6.0F, closeY - 6.0F, 12.0F, 12.0F, this::onClose, null);

        float innerX = x + PAD;
        float innerW = w - PAD * 2.0F;
        float topY = y + HEADER_H + 6.0F;

        // 顶部开关、兑换上限与模式选择卡
        float topCardH = 66.0F;
        card(canvas, innerX, topY, innerW, topCardH);
        paintTopSettings(canvas, innerX, topY, innerW, topCardH);

        // 底部按钮与状态栏
        float footerBtnH = 18.0F;
        float footerBtnY = y + h - PAD - footerBtnH;
        float splitTop = topY + topCardH + 6.0F;
        float splitBottom = footerBtnY - 12.0F;
        float splitH = Math.max(80.0F, splitBottom - splitTop);

        float leftW = Math.min(186.0F, Math.max(152.0F, innerW * 0.46F));
        float gap = 8.0F;
        float rightX = innerX + leftW + gap;
        float rightW = innerX + innerW - rightX;

        paintTierList(canvas, innerX, splitTop, leftW, splitH);
        paintRightArea(canvas, rightX, splitTop, rightW, splitH);

        // 状态 / 温和防刷提示
        if (!this.errorText.isEmpty()) {
            small(canvas, this.errorText, innerX, footerBtnY - 7.0F, innerW, IOStyle.RED);
        } else if (hasAnyVanillaWithoutComponents()) {
            small(canvas, tr("gui.module_shop.phys_cfg.vanilla_warn",
                            "提示：当前包含无自定义组件的原版常见物品，建议开启防套利或改用自定义组件货币以防刷取"),
                    innerX, footerBtnY - 7.0F, innerW, IOStyle.ORANGE);
        } else {
            small(canvas, tr("gui.module_shop.phys_cfg.footer_hint",
                            "阶梯从低面值到高面值排列 · 调整比例自动重算后续面值"),
                    innerX, footerBtnY - 7.0F, innerW, IOStyle.TEXT_3);
        }

        float saveW = 104.0F;
        float cancelW = 68.0F;
        button(canvas, "cancel", tr("gui.module_shop.cancel", "取消"),
                innerX + innerW - saveW - 6.0F - cancelW, footerBtnY, cancelW, footerBtnH,
                Button.Kind.NORMAL, true, () -> Nav.back());
        button(canvas, "save", tr("gui.module_shop.phys_cfg.save", "保存并热更新"),
                innerX + innerW - saveW, footerBtnY, saveW, footerBtnH,
                Button.Kind.ACCENT, true, this::saveConfig);
    }

    private void paintTopSettings(Canvas canvas, float x, float y, float w, float h) {
        float row1Y = y + 6.0F;
        toggle(canvas, "phys_enabled", x + 8.0F, row1Y, this.enabled, () -> {
            this.enabled = !this.enabled;
            this.errorText = "";
        });
        small(canvas, tr("gui.module_shop.phys_cfg.enabled", "启用实体货币"),
                x + 34.0F, row1Y + 6.0F, 90.0F, this.enabled ? IOStyle.GREEN : IOStyle.TEXT_2);

        float bbX = x + 132.0F;
        toggle(canvas, "phys_block", bbX, row1Y, this.blockBuyback, () -> this.blockBuyback = !this.blockBuyback);
        small(canvas, tr("gui.module_shop.phys_cfg.block_arb", "防套利保护（禁收购/出货箱/低价套利）"),
                bbX + 26.0F, row1Y + 6.0F, Math.max(40.0F, x + w - bbX - 32.0F),
                this.blockBuyback ? IOStyle.TEXT : IOStyle.TEXT_3);

        // 第二行：阶梯兑换单次枚数上限 + 每人每日兑换面值上限（0 = 不限）
        float row2Y = y + 23.0F;
        float halfW = (w - 22.0F) * 0.5F;
        String maxLbl = tr("gui.module_shop.phys_cfg.max_convert", "单次兑换上限(枚)");
        float maxLblW = small(canvas, maxLbl, x + 8.0F, row2Y + 7.5F, halfW * 0.58F, IOStyle.TEXT_2);
        if (this.maxConvertField != null) {
            float fx = x + 12.0F + maxLblW;
            this.maxConvertField.bounds(fx, row2Y, Math.max(40.0F, x + 8.0F + halfW - fx), 15.0F).visible(true);
        }
        float r2x = x + 14.0F + halfW;
        String dailyLbl = tr("gui.module_shop.phys_cfg.daily_convert", "每日兑换上限(0=不限)");
        float dailyLblW = small(canvas, dailyLbl, r2x, row2Y + 7.5F, halfW * 0.58F, IOStyle.TEXT_2);
        if (this.dailyConvertLimitField != null) {
            float fx = r2x + 4.0F + dailyLblW;
            this.dailyConvertLimitField.bounds(fx, row2Y, Math.max(48.0F, x + w - 8.0F - fx), 15.0F).visible(true);
        }

        // 第三行：双独立并行账本说明条
        float row3Y = y + 43.0F;
        float bannerW = Math.max(60.0F, w - 16.0F);
        Draw.fill(canvas, x + 8.0F, row3Y, bannerW, 16.0F, 3.0F, Draw.alpha(IOStyle.BLUE, 0.10F));
        Draw.stroke(canvas, x + 8.0F, row3Y, bannerW, 16.0F, 3.0F, 0.6F, Draw.alpha(IOStyle.BLUE, 0.35F));
        String tagTxt = tr("gui.module_shop.phys_cfg.dual_tag", "双独立账本");
        float tagW = Text.width(tagTxt, AtomUi.BOLD, 5.25F) + 10.0F;
        Draw.fill(canvas, x + 12.0F, row3Y + 3.0F, tagW, 10.0F, 2.0F, Draw.alpha(IOStyle.BLUE, 0.22F));
        Text.drawLine(canvas, tagTxt, AtomUi.BOLD, 5.25F, IOStyle.BLUE,
                x + 12.0F, row3Y + 8.0F, tagW, Text.Align.CENTER);
        String descTxt;
        TierDraft addonDef = addonDefaultDraft();
        if (addonDef != null) {
            descTxt = this.enabled
                    ? tr("gui.module_shop.phys_cfg.dual_desc_addon",
                    "双账本独立 · 附属默认货币：%s（再次点击可切回虚拟主货币）", addonDef.displayTitle())
                    : tr("gui.module_shop.phys_cfg.dual_desc_addon_off",
                    "双账本独立 · 启用后附属默认货币：%s", addonDef.displayTitle());
        } else {
            descTxt = tr("gui.module_shop.phys_cfg.dual_desc_star",
                    "双账本独立 · 附属默认：虚拟主货币（可在右侧指定实体币档位）");
        }
        float descX = x + 16.0F + tagW;
        small(canvas, descTxt, descX, row3Y + 8.0F, Math.max(20.0F, x + w - 12.0F - descX), IOStyle.TEXT_2);
    }

    private void paintTierList(Canvas canvas, float x, float y, float w, float h) {
        float topH = 16.0F;
        float btnGap = 4.0F;
        float addW = 48.0F;
        float defW = 54.0F;
        small(canvas, tr("gui.module_shop.phys_cfg.chain_title", "阶梯兑换链"),
                x, y + 8.0F, Math.max(20.0F, w - addW - defW - btnGap * 2.0F), IOStyle.TEXT_2);
        button(canvas, "add_tier", tr("gui.module_shop.phys_cfg.add", "+ 新增"),
                x + w - defW - btnGap - addW, y, addW, topH, Button.Kind.NORMAL, true, this::addTier);
        button(canvas, "def_tier", tr("gui.module_shop.phys_cfg.fill_default", "示例链"),
                x + w - defW, y, defW, topH, Button.Kind.NORMAL, true, this::fillDefaultExample);

        float ctrlH = 16.0F;
        float ctrlY = y + h - ctrlH;
        float listY = y + topH + 4.0F;
        float listH = Math.max(36.0F, ctrlY - 4.0F - listY);
        card(canvas, x, listY, w, listH);

        if (this.tiers.isEmpty()) {
            paragraph(canvas, tr("gui.module_shop.phys_cfg.empty", "暂无货币等级\n点「示例链」或「+ 新增」"),
                    x + 8.0F, listY + listH * 0.5F - 10.0F, w - 16.0F, Text.SMALL, IOStyle.TEXT_3, 3, true);
        } else {
            float rowH = 26.0F;
            float rowGap = 3.0F;
            float padIn = 4.0F;
            float contentH = this.tiers.size() * rowH + Math.max(0, this.tiers.size() - 1) * rowGap + padIn * 2.0F;
            float oy = beginList(canvas, this.listScroll, x + 2.0F, listY + 2.0F, w - 4.0F, listH - 4.0F, contentH);
            float rx = x + padIn + 1.0F;
            float rw = w - padIn * 2.0F - 10.0F;
            for (int i = 0; i < this.tiers.size(); i++) {
                final int idx = i;
                TierDraft t = this.tiers.get(i);
                float ry = oy + padIn + i * (rowH + rowGap);
                if (ry + rowH < listY || ry > listY + listH) {
                    continue;
                }
                boolean sel = !this.pickingInventory && i == this.selectedIndex;
                float hv = hover("tier_row:" + i, rx, ry, rw, rowH);
                int bg = sel ? Draw.alpha(IOStyle.BLUE, 0.16F) : Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hv);
                int border = sel ? IOStyle.BLUE : Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, hv);
                Draw.fill(canvas, rx, ry, rw, rowH, 3.0F, bg);
                Draw.stroke(canvas, rx, ry, rw, rowH, 3.0F, sel ? 1.1F : 0.7F, border);

                ItemStack stack = t.displayStack();
                item(stack, rx + 4.0F, ry + 5.0F);
                float tx = rx + 24.0F;
                float tw = Math.max(20.0F, rw - 28.0F);
                String name = "#" + (i + 1) + " " + t.displayTitle();
                float tagRight = rx + rw - 4.0F;
                if (t.addonDefault) {
                    String defTag = tr("gui.module_shop.phys_cfg.addon_tag", "附属默认");
                    float addonTagW = Text.width(defTag, AtomUi.BOLD, 5.0F) + 6.0F;
                    float defX = tagRight - addonTagW;
                    Draw.fill(canvas, defX, ry + 3.0F, addonTagW, 9.0F, 2.0F, Draw.alpha(IOStyle.ORANGE, 0.24F));
                    Text.drawLine(canvas, defTag, AtomUi.BOLD, 5.0F, IOStyle.ORANGE,
                            defX, ry + 7.5F, addonTagW + 0.5F, Text.Align.CENTER);
                    tagRight = defX - 3.0F;
                }
                if (t.hasCustomComponents()) {
                    String tag = tr("gui.module_shop.phys_cfg.comp_tag", "组件");
                    float tagW = Text.width(tag, AtomUi.BOLD, 5.0F) + 6.0F;
                    float compX = tagRight - tagW;
                    Draw.fill(canvas, compX, ry + 3.0F, tagW, 9.0F, 2.0F, Draw.alpha(IOStyle.EPIC, 0.22F));
                    Text.drawLine(canvas, tag, AtomUi.BOLD, 5.0F, IOStyle.EPIC,
                            compX, ry + 7.5F, tagW + 0.5F, Text.Align.CENTER);
                    tagRight = compX - 3.0F;
                }
                float nameMaxW = Math.max(12.0F, tagRight - tx);
                Text.drawLine(canvas, name, sel ? AtomUi.BOLD : AtomUi.REGULAR, Text.SMALL,
                        sel ? IOStyle.TEXT : IOStyle.TEXT_2, tx, ry + 8.0F, nameMaxW + 0.5F, Text.Align.LEFT);

                String sub;
                if (i == 0) {
                    sub = tr("gui.module_shop.phys_cfg.base_sub", "基准面值 = %s", t.value);
                } else {
                    TierDraft prev = this.tiers.get(i - 1);
                    if (prev.value > 0L && t.value > prev.value && t.value % prev.value == 0L) {
                        long ratio = t.value / prev.value;
                        sub = tr("gui.module_shop.phys_cfg.step_sub", "%s × %s → 1 (%s)",
                                ratio, prev.displayTitle(), t.value);
                    } else {
                        sub = tr("gui.module_shop.phys_cfg.step_non_div_sub", "非整倍 · 面值 = %s", t.value);
                    }
                }
                Text.drawLine(canvas, sub, AtomUi.REGULAR, 5.25F, IOStyle.ORANGE,
                        tx, ry + 18.5F, tw, Text.Align.LEFT);

                hit(rx, ry, rw, rowH, () -> selectIndex(idx), null);
            }
            endList(canvas);
        }

        float bGap = 4.0F;
        float bw = (w - bGap * 2.0F) / 3.0F;
        button(canvas, "t_up", tr("gui.module_shop.phys_cfg.up", "↑ 上移"),
                x, ctrlY, bw, ctrlH, Button.Kind.NORMAL, selected() != null && this.selectedIndex > 0,
                () -> moveSelected(-1));
        button(canvas, "t_dn", tr("gui.module_shop.phys_cfg.dn", "↓ 下移"),
                x + bw + bGap, ctrlY, bw, ctrlH, Button.Kind.NORMAL,
                selected() != null && this.selectedIndex < this.tiers.size() - 1, () -> moveSelected(1));
        button(canvas, "t_del", tr("gui.module_shop.v2.remove", "删除"),
                x + (bw + bGap) * 2.0F, ctrlY, bw, ctrlH, Button.Kind.DANGER, selected() != null,
                this::deleteSelected);
    }

    private void paintRightArea(Canvas canvas, float x, float y, float w, float h) {
        card(canvas, x, y, w, h);
        TierDraft sel = selected();
        if (sel == null) {
            hideAllFields();
            center(canvas, tr("gui.module_shop.phys_cfg.pick_tier", "请在左侧选择或添加货币等级"),
                    x + 10.0F, y + h * 0.5F, w - 20.0F, IOStyle.TEXT_3);
            return;
        }
        if (this.pickingInventory) {
            hideAllFields();
            paintInventoryPicker(canvas, x, y, w, h);
            return;
        }

        float ix = x + 8.0F;
        float iw = w - 16.0F;
        float cy = y + 6.0F;

        // 顶部物品预览 + 一键背包拾取按钮
        slotFrame(canvas, ix + 1.0F, cy + 1.0F);
        ItemStack preview = sel.displayStack();
        item(preview, ix + 1.0F, cy + 1.0F);

        float pickW = 50.0F;
        float addonW = 62.0F;
        float btnGapTop = 3.0F;
        float pickX = ix + iw - pickW;
        float addonX = pickX - btnGapTop - addonW;
        String addonLbl = sel.addonDefault
                ? tr("gui.module_shop.phys_cfg.addon_on", "★ 附属默认")
                : tr("gui.module_shop.phys_cfg.addon_set", "设为附属默认");
        button(canvas, "addon_default", addonLbl, addonX, cy, addonW, 18.0F,
                sel.addonDefault ? Button.Kind.ACCENT : Button.Kind.NORMAL, true,
                () -> toggleAddonDefault(sel));
        if (over(addonX, cy, addonW, 18.0F)) {
            tip(Component.literal(sel.addonDefault
                    ? tr("gui.module_shop.phys_cfg.addon_tip_on", "当前为附属默认结算币；再次点击取消并回退虚拟主货币")
                    : tr("gui.module_shop.phys_cfg.addon_tip_set", "设为附属模组（红包/盲仓/股票/任务等）默认结算实体货币")));
        }
        button(canvas, "pick_inv", tr("gui.module_shop.phys_cfg.pick_inv", "背包拾取"),
                pickX, cy, pickW, 18.0F, Button.Kind.NORMAL, true,
                () -> this.pickingInventory = true);

        float titleX = ix + 22.0F;
        float titleMaxW = Math.max(20.0F, addonX - 4.0F - titleX);
        String itemTitle = preview.isEmpty()
                ? tr("gui.module_shop.phys_cfg.invalid_item", "未匹配到物品")
                : PageKit.plainName(preview);
        Text.drawLine(canvas, itemTitle, AtomUi.BOLD, Text.BODY,
                preview.isEmpty() ? IOStyle.RED : IOStyle.TEXT, titleX, cy + 5.5F, titleMaxW, Text.Align.LEFT);
        String compInfo = sel.hasCustomComponents()
                ? tr("gui.module_shop.phys_cfg.has_comp", "已保留自定义组件")
                : tr("gui.module_shop.phys_cfg.no_comp", "普通物品（无组件）");
        Text.drawLine(canvas, compInfo, AtomUi.REGULAR, 5.25F,
                sel.hasCustomComponents() ? IOStyle.EPIC : IOStyle.TEXT_3,
                titleX, cy + 14.5F, titleMaxW, Text.Align.LEFT);
        cy += 23.0F;

        // 显示名称
        small(canvas, tr("gui.module_shop.phys_cfg.field_label", "显示名称"), ix, cy + 4.0F, iw, IOStyle.TEXT_2);
        cy += 9.0F;
        this.labelField.bounds(ix, cy, iw, 15.0F).visible(true);
        cy += 18.0F;

        // 晋级比例 + 折算面值
        float colGap = 6.0F;
        float ratioW = (iw - colGap) * 0.45F;
        float valW = iw - colGap - ratioW;
        boolean isBase = this.selectedIndex == 0;
        String ratioLbl = isBase
                ? tr("gui.module_shop.phys_cfg.field_ratio_base", "晋级比例（基准=1）")
                : tr("gui.module_shop.phys_cfg.field_ratio", "晋级比例（相对前级）");
        small(canvas, ratioLbl, ix, cy + 4.0F, ratioW, IOStyle.TEXT_2);
        small(canvas, tr("gui.module_shop.phys_cfg.field_value", "折算面值（可手动改）"),
                ix + ratioW + colGap, cy + 4.0F, valW, IOStyle.TEXT_2);
        cy += 9.0F;
        this.ratioField.bounds(ix, cy, ratioW, 15.0F).enabled(!isBase).visible(true);
        this.valueField.bounds(ix + ratioW + colGap, cy, valW, 15.0F).visible(true);
        cy += 18.0F;

        // 物品 ID
        small(canvas, tr("gui.module_shop.phys_cfg.field_itemid", "物品 ID"), ix, cy + 4.0F, iw, IOStyle.TEXT_2);
        cy += 9.0F;
        this.itemIdField.bounds(ix, cy, iw, 15.0F).visible(true);
        cy += 18.0F;

        // NBT SNBT（沿用 field_snbt key 保持跨版本统一）
        small(canvas, tr("gui.module_shop.phys_cfg.field_snbt", "组件 SNBT（可选，建议用「背包拾取」）"),
                ix, cy + 4.0F, iw, IOStyle.TEXT_2);
        cy += 9.0F;
        this.snbtField.bounds(ix, cy, iw, 15.0F).visible(true);
    }

    private void paintInventoryPicker(Canvas canvas, float x, float y, float w, float h) {
        float ix = x + 8.0F;
        float iw = w - 16.0F;
        float backW = 54.0F;
        small(canvas, tr("gui.module_shop.phys_cfg.pick_inv_title", "点击背包物品拾取（保留组件）"),
                ix, y + 12.0F, Math.max(20.0F, iw - backW - 4.0F), IOStyle.ORANGE);
        button(canvas, "pick_back", tr("gui.module_shop.cfg.back_btn", "返回"),
                ix + iw - backW, y + 4.0F, backW, 15.0F, Button.Kind.NORMAL, true, () -> {
                    this.pickingInventory = false;
                    syncFieldsFromSelected();
                });

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            center(canvas, "—", ix, y + h * 0.5F, iw, IOStyle.TEXT_3);
            return;
        }
        Inventory inv = mc.player.getInventory();
        float cell = 18.0F;
        float gridW = 9.0F * cell;
        float gx = x + (w - gridW) * 0.5F;
        float gy = y + 25.0F;

        // 背包 3×9（槽位 9..35）+ 快捷栏 1×9（槽位 0..8）
        for (int row = 0; row < 4; row++) {
            float ry = gy + row * cell + (row == 3 ? 4.0F : 0.0F);
            for (int col = 0; col < 9; col++) {
                int slotIdx = row < 3 ? (9 + row * 9 + col) : col;
                float sx = gx + col * cell;
                ItemStack st = inv.getItem(slotIdx);
                boolean hv = !st.isEmpty() && over(sx, ry, 16.0F, 16.0F);
                Draw.fill(canvas, sx, ry, 16.0F, 16.0F, 2.0F, hv ? IOStyle.ROW_HOVER : IOStyle.FIELD_BG);
                Draw.stroke(canvas, sx, ry, 16.0F, 16.0F, 2.0F, 0.8F, hv ? IOStyle.BLUE : IOStyle.BORDER_DIM);
                if (!st.isEmpty()) {
                    item(st, sx, ry);
                    final ItemStack chosen = st.copy();
                    hit(sx, ry, 16.0F, 16.0F, () -> pickFromInventory(chosen), null);
                }
            }
        }

        // 副手槽放在网格下方左侧
        float offY = gy + 4.0F * cell + 9.0F;
        if (offY + 16.0F <= y + h - 4.0F) {
            ItemStack off = mc.player.getOffhandItem();
            small(canvas, tr("gui.module_shop.phys_cfg.offhand", "副手"), gx, offY + 8.0F, 26.0F, IOStyle.TEXT_3);
            float ox = gx + 28.0F;
            boolean ohv = !off.isEmpty() && over(ox, offY, 16.0F, 16.0F);
            Draw.fill(canvas, ox, offY, 16.0F, 16.0F, 2.0F, ohv ? IOStyle.ROW_HOVER : IOStyle.FIELD_BG);
            Draw.stroke(canvas, ox, offY, 16.0F, 16.0F, 2.0F, 0.8F, ohv ? IOStyle.BLUE : IOStyle.BORDER_DIM);
            if (!off.isEmpty()) {
                item(off, ox, offY);
                final ItemStack chosenOff = off.copy();
                hit(ox, offY, 16.0F, 16.0F, () -> pickFromInventory(chosenOff), null);
            }
        }
    }

    private void hideAllFields() {
        if (this.labelField != null) {
            this.labelField.visible(false);
            this.ratioField.visible(false);
            this.valueField.visible(false);
            this.itemIdField.visible(false);
            this.snbtField.visible(false);
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parsePositiveLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ 非 Skia 兜底

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        this.renderBackground(g);
        int pw = 280;
        int ph = 110;
        int x = (this.width - pw) / 2;
        int y = (this.height - ph) / 2;
        Bricks.panel(g, x, y, pw, ph);
        IOStyle.centerAt(g, this.font, this.title, x + pw / 2, y + 14, IOStyle.ORANGE);
        IOStyle.centerAt(g, this.font,
                Component.literal(tr("gui.module_shop.phys_cfg.no_skia", "请在支持 Skija 的客户端环境中使用实体货币配置界面")),
                x + pw / 2, y + 46, IOStyle.TEXT_2);
        int bx = x + (pw - 80) / 2;
        int by = y + ph - 30;
        IOStyle.button(g, bx, by, 80, 18, Component.translatable("gui.module_shop.cfg.back_btn"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fbBackBtn = new int[]{bx, by, 80, 18};
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            return skiaClicked(mouseX, mouseY, button);
        }
        if (button == 0 && this.fbBackBtn != null
                && IOStyle.inside(this.fbBackBtn[0], this.fbBackBtn[1], this.fbBackBtn[2], this.fbBackBtn[3], mouseX, mouseY)) {
            ClickSound.play();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
