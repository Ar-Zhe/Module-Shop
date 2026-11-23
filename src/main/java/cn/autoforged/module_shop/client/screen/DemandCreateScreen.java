package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.PhysTierView;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.widget.PriceSpecEditor;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.PriceSpec.CurrencyMode;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「发起求购」的物品选择界面：**类似 JEI 的物品搜索**。
 *
 * <p>左边搜索 + 物品网格（点一下加进清单），右边是已选清单（最多 12 种，可 +/− 数量或移除），
 * 底部填悬赏金币后点「发布」。副手放的东西会作为**悬赏物品**一起托管（可以为空）。
 *
 * <p>右侧清单**固定行距 18px**（16px 图标不再互相压住），一屏显示 8 行，超出时**滚轮上下滑**
 * 并显示细滚动条；清单区有裁剪，绝不会画到底栏按钮上。
 */
public class DemandCreateScreen extends SkiaDialog {
    /** 一条求购最多几种物品（与服务端 {@code DemandEntry.MAX_LINES} 保持一致）。 */
    private static final int MAX_LINES = 12;

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 236;
    private static final int COLS = 9;
    private static final int ROWS = 6;
    private static final int CELL = 18;
    private static final int PAGE_SIZE = COLS * ROWS;
    /** 右侧清单：行距与可见行数。 */
    private static final int PICK_ROW_H = 18;
    private static final int PICK_VISIBLE = 7;
    private static final int PICK_X_OFFSET = 186;
    private static final int PICK_TOP_OFFSET = 40;

    private final List<ItemStack> allItems = new ArrayList<>();
    private final List<Entry> picked = new ArrayList<>();
    private final List<int[]> cellRects = new ArrayList<>();

    private EditBox searchBox;
    private EditBox moneyBox;
    private EditBox countBox;
    private int page;
    private int pickScroll;
    private int selectedPick = -1;
    private boolean syncingCount;
    private String filter = "";
    private String status = "";

    /** 已选清单的一行。 */
    private static final class Entry {
        final ItemStack stack;
        int count = 1;

        Entry(ItemStack stack, int count) {
            this.stack = stack;
            this.count = count;
        }
    }

    public DemandCreateScreen() {
        super(Component.translatable("gui.module_shop.input.demand_create"));
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }
            this.allItems.add(stack);
        }
        // 打开界面时把主手物品直接放进清单（"拿着想要的东西"的老习惯仍然好用）。
        if (this.minecraft != null && this.minecraft.player != null) {
            ItemStack hand = this.minecraft.player.getMainHandItem();
            if (!hand.isEmpty()) {
                add(hand.copy());
            }
        }
    }

    @Override
    protected void init() {
        if (skiaInit()) {
            return;
        }
        int x = left();
        int y = top();
        this.searchBox = new EditBox(this.font, x + 10, y + 24, 148, 12,
                Component.translatable("gui.module_shop.demand.search_hint"));
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(32);
        this.searchBox.setHint(Component.translatable("gui.module_shop.demand.search_hint"));
        this.searchBox.setValue(this.filter);
        this.searchBox.setResponder(text -> {
            this.filter = text == null ? "" : text.trim();
            this.page = 0;
        });
        this.addRenderableWidget(this.searchBox);
        this.moneyBox = new EditBox(this.font, x + PANEL_W - 90, y + PANEL_H - 28, 58, 12,
                Component.translatable("gui.module_shop.field.reward_money"));
        this.moneyBox.setBordered(false);
        this.moneyBox.setMaxLength(12);
        this.moneyBox.setValue("0");
        this.addRenderableWidget(this.moneyBox);
        this.countBox = new EditBox(this.font, x + PICK_X_OFFSET + 34, y + PANEL_H - 52, 42, 12,
                Component.translatable("gui.module_shop.stall.f_count"));
        this.countBox.setBordered(false);
        this.countBox.setMaxLength(2);
        this.countBox.setFilter(t -> t.isEmpty() || t.matches("[0-9]{0,2}"));
        this.countBox.setResponder(this::onCountInputChanged);
        this.addRenderableWidget(this.countBox);
        syncCountField(false);
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Nav.back();
    }

    // ---------------------------------------------------------------- 数据

    private List<ItemStack> filtered() {
        if (this.filter.isEmpty()) {
            return this.allItems;
        }
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : this.allItems) {
            if (cn.autoforged.module_shop.client.screen.shop.PageKit.matchesItemQuery(stack, this.filter)) {
                out.add(stack);
            }
        }
        return out;
    }

    private int pages() {
        return Math.max(1, (filtered().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private int maxPickScroll() {
        return Math.max(0, this.picked.size() - PICK_VISIBLE);
    }

    private Entry selectedEntry() {
        if (this.selectedPick < 0 || this.selectedPick >= this.picked.size()) {
            return null;
        }
        return this.picked.get(this.selectedPick);
    }

    private void selectPick(int index, boolean focusCount) {
        if (this.picked.isEmpty()) {
            this.selectedPick = -1;
        } else {
            this.selectedPick = Math.max(0, Math.min(index, this.picked.size() - 1));
        }
        syncCountField(focusCount);
    }

    private void setSelectedCount(int newCount, boolean focusCount) {
        Entry sel = selectedEntry();
        if (sel == null) {
            return;
        }
        sel.count = Math.max(1, Math.min(64, newCount));
        syncCountField(focusCount);
    }

    private void removePick(int index) {
        if (index < 0 || index >= this.picked.size()) {
            return;
        }
        this.picked.remove(index);
        if (this.picked.isEmpty()) {
            this.selectedPick = -1;
        } else if (this.selectedPick >= this.picked.size()) {
            this.selectedPick = this.picked.size() - 1;
        } else if (index < this.selectedPick) {
            this.selectedPick--;
        }
        this.pickScroll = Math.max(0, Math.min(this.pickScroll, maxPickScroll()));
        syncCountField(false);
    }

    private void syncCountField(boolean focus) {
        Entry sel = selectedEntry();
        String val = sel == null ? "" : Integer.toString(sel.count);
        this.syncingCount = true;
        try {
            if (this.skCount != null) {
                this.skCount.setValue(val);
                if (focus && sel != null) {
                    this.skCount.focus();
                }
            } else if (this.countBox != null) {
                this.countBox.setValue(val);
                this.countBox.setEditable(sel != null);
                this.countBox.setVisible(sel != null);
            }
        } finally {
            this.syncingCount = false;
        }
    }

    private void onCountInputChanged(String text) {
        if (this.syncingCount) {
            return;
        }
        Entry sel = selectedEntry();
        if (sel == null || text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            long val = Long.parseLong(text.trim());
            if (val >= 1L && val <= 64L) {
                sel.count = (int) val;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private String countProblem() {
        Entry sel = selectedEntry();
        if (sel == null) {
            return null;
        }
        String raw = this.skCount != null ? this.skCount.value()
                : (this.countBox != null ? this.countBox.getValue() : Integer.toString(sel.count));
        if (raw == null || raw.trim().isEmpty()) {
            return Component.translatable("msg.module_shop.stall.err_count", 64).getString();
        }
        try {
            long val = Long.parseLong(raw.trim());
            if (val < 1L || val > 64L) {
                return Component.translatable("msg.module_shop.stall.err_count", 64).getString();
            }
        } catch (NumberFormatException ex) {
            return Component.translatable("msg.module_shop.stall.err_count", 64).getString();
        }
        return null;
    }

    /** 加入清单（同种物品合并数量；右键可直接拉满 64 个并选中）。 */
    private void add(ItemStack stack) {
        add(stack, false);
    }

    private void add(ItemStack stack, boolean maxStack) {
        for (int i = 0; i < this.picked.size(); i++) {
            Entry entry = this.picked.get(i);
            if (ItemStack.isSameItemSameTags(entry.stack, stack)) {
                entry.count = maxStack ? 64 : Math.min(64, entry.count + 1);
                selectPick(i, false);
                return;
            }
        }
        if (this.picked.size() >= MAX_LINES) {
            this.status = Component.translatable("gui.module_shop.demand.too_many", MAX_LINES).getString();
            return;
        }
        ItemStack one = stack.copy();
        one.setCount(1);
        this.picked.add(new Entry(one, maxStack ? 64 : 1));
        this.status = "";
        // 新加的那条若超出一屏，自动滚到能看见它
        this.pickScroll = Math.min(maxPickScroll(), Math.max(0, this.picked.size() - PICK_VISIBLE));
        selectPick(this.picked.size() - 1, false);
    }

    private boolean hasOffhandReward() {
        return this.minecraft != null && this.minecraft.player != null
                && !this.minecraft.player.getOffhandItem().isEmpty();
    }

    private static long feeOf(long rewardAmount) {
        if (ClientShopData.demandFeePercent <= 0.0D || rewardAmount <= 0L) {
            return 0L;
        }
        return (long) Math.ceil(rewardAmount * ClientShopData.demandFeePercent / 100.0D);
    }

    private static long safeAdd(long a, long b) {
        if (a <= 0L) {
            return Math.max(0L, b);
        }
        if (b <= 0L) {
            return a;
        }
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static PriceSpec feeSpecOf(@Nullable PriceSpec rewardSpec) {
        if (rewardSpec == null || !rewardSpec.isValid()) {
            return PriceSpec.ZERO;
        }
        if (rewardSpec.mode == CurrencyMode.PHYS_ONLY) {
            long fee = feeOf(rewardSpec.physCount);
            return fee > 0L ? PriceSpec.phys(rewardSpec.physTierKey, fee) : PriceSpec.ZERO;
        }
        long fee = feeOf(rewardSpec.starPrice);
        return fee > 0L ? PriceSpec.star(fee) : PriceSpec.ZERO;
    }

    private static PriceSpec escrowSpecOf(@Nullable PriceSpec rewardSpec) {
        if (rewardSpec == null || !rewardSpec.isValid()) {
            return PriceSpec.ZERO;
        }
        if (rewardSpec.mode == CurrencyMode.PHYS_ONLY) {
            long fee = feeOf(rewardSpec.physCount);
            return PriceSpec.phys(rewardSpec.physTierKey, safeAdd(rewardSpec.physCount, fee));
        }
        long fee = feeOf(rewardSpec.starPrice);
        return PriceSpec.star(safeAdd(rewardSpec.starPrice, fee));
    }

    private PriceSpec previewRewardSpec() {
        if (this.priceEditor != null) {
            if (this.priceEditor.mode() == CurrencyMode.PHYS_ONLY) {
                return PriceSpec.phys(this.priceEditor.selectedTierKey(),
                        Math.max(0L, this.priceEditor.physCountOr(0L)));
            }
            return PriceSpec.star(Math.max(0L, this.priceEditor.starValueOr(0L)));
        }
        long m = 0L;
        if (this.moneyBox != null) {
            try {
                m = Math.max(0L, Long.parseLong(this.moneyBox.getValue().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return PriceSpec.star(m);
    }

    @Nullable
    private String rewardProblem() {
        if (this.priceEditor == null) {
            return null;
        }
        String editorErr = this.priceEditor.validateError();
        if (editorErr != null) {
            return editorErr;
        }
        PriceSpec spec = this.priceEditor.buildSpec();
        if (spec == null) {
            return ClientHomeCurrencyState.tr("gui.module_shop.demand.money_bad_spec", "请填写合法的悬赏金额");
        }
        if (spec.mode == CurrencyMode.STAR_ONLY && ClientShopData.demandMaxReward > 0L
                && spec.starPrice > ClientShopData.demandMaxReward) {
            return ClientHomeCurrencyState.tr("gui.module_shop.demand.over_max_reward",
                    "超出单笔星币上限 %s", ClientShopData.money(ClientShopData.demandMaxReward));
        }
        if (!spec.isValid() && !hasOffhandReward()) {
            return Component.translatable("msg.module_shop.demand_need_reward").getString();
        }
        PriceSpec escrow = escrowSpecOf(spec);
        if (!escrow.isZero() && !PriceDisplay.canAfford(escrow, 1)) {
            if (escrow.mode == CurrencyMode.PHYS_ONLY) {
                PhysTierView tier = ClientHomeCurrencyState.findTier(escrow.physTierKey);
                String have = PriceDisplay.physPlain(escrow.physTierKey, tier != null ? tier.ownedCount : 0);
                return ClientHomeCurrencyState.tr("gui.module_shop.demand.no_phys",
                        "实体币不足或无法找零（需 %s，持有 %s）", PriceDisplay.formatPlain(escrow), have);
            }
            return ClientHomeCurrencyState.tr("gui.module_shop.demand.no_star",
                    "星币余额不足（需托管 %s，当前 %s）",
                    ClientShopData.money(escrow.starPrice), ClientShopData.money(ClientShopData.balance));
        }
        return null;
    }

    private void publish() {
        if (this.picked.isEmpty()) {
            this.status = Component.translatable("gui.module_shop.demand.pick_first").getString();
            return;
        }
        String countErr = countProblem();
        if (countErr != null) {
            this.status = countErr;
            return;
        }
        PriceSpec rewardSpec;
        if (skia() && this.priceEditor != null) {
            String rewardErr = rewardProblem();
            if (rewardErr != null) {
                this.status = rewardErr;
                return;
            }
            rewardSpec = this.priceEditor.buildSpec();
            if (rewardSpec == null) {
                this.status = ClientHomeCurrencyState.tr("gui.module_shop.demand.money_bad_spec", "请填写合法的悬赏金额");
                return;
            }
        } else {
            long money;
            try {
                money = Math.max(0L, Long.parseLong(this.moneyBox.getValue().trim()));
            } catch (NumberFormatException ex) {
                this.status = Component.translatable("gui.module_shop.demand.money_bad").getString();
                return;
            }
            if (money <= 0L && !hasOffhandReward()) {
                this.status = Component.translatable("msg.module_shop.demand_need_reward").getString();
                return;
            }
            rewardSpec = PriceSpec.star(money);
        }
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "demand_create");
        ListTag list = new ListTag();
        for (Entry entry : this.picked) {
            CompoundTag line = new CompoundTag();
            line.put("item", entry.stack.save(new CompoundTag()));
            line.putInt("count", entry.count);
            list.add(line);
        }
        tag.put("wants", list);
        tag.put("rewardSpec", rewardSpec.toTag());
        tag.putString("reward_money", Long.toString(rewardSpec.supportsStar() ? rewardSpec.starPrice : 0L));
        ServerboundShopActionPayload.send(tag);
        Nav.back();
    }

    // ---------------------------------------------------------------- 背景

    /**
     * 背景**不做世界模糊**：原版会把世界模糊后垫在下面，这块面板又大又偏暗，看起来会"糊成一片"。
     * 这里直接铺一层实色底，内容看起来干净清晰。
     */
    @Override
    public void renderBackground(GuiGraphics g) {
        g.fill(0, 0, this.width, this.height, 0xFF0E1013);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        // 顺序：背景 → 原版组件（输入框）→ 底板 → 内容。内容永远在最上层。
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0xFF14161B);
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        this.cellRects.clear();
        g.drawString(this.font, this.title, x + 8, y + 8, IOStyle.TEXT, false);
        // 输入框的内凹底
        IOStyle.rounded(g, x + 8, y + 22, 152, 16, 0, IOStyle.SIDEBAR);
        IOStyle.ring(g, x + 8, y + 22, 152, 16, 0, IOStyle.BORDER_DIM);
        IOStyle.rounded(g, x + PANEL_W - 92, y + PANEL_H - 30, 62, 16, 0, IOStyle.SIDEBAR);
        IOStyle.ring(g, x + PANEL_W - 92, y + PANEL_H - 30, 62, 16, 0, IOStyle.BORDER_DIM);

        // ---- 左：物品网格 ----
        List<ItemStack> list = filtered();
        int from = this.page * PAGE_SIZE;
        int to = Math.min(list.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            int cell = i - from;
            int cx = x + 8 + (cell % COLS) * CELL;
            int cy = y + 44 + (cell / COLS) * CELL;
            ItemStack stack = list.get(i);
            boolean cellHover = IOStyle.inside(cx, cy, 16, 16, mouseX, mouseY);
            Bricks.slot(g, cx, cy);
            g.renderItem(stack, cx, cy);
            g.renderItemDecorations(this.font, stack, cx, cy);
            if (cellHover) {
                g.renderTooltip(this.font, stack, mouseX, mouseY);
            }
            this.cellRects.add(new int[]{cx, cy, 16, 16, i});
        }
        // 翻页
        drawSmallLabel(g, x + 8, y + PANEL_H - 54, Component.translatable("gui.module_shop.page",
                this.page + 1, pages()), IOStyle.TEXT_2);
        IOStyle.button(g, x + 96, y + PANEL_H - 56, 14, 12, Component.literal("<"), this.font, mouseX, mouseY,
                this.page > 0, IOStyle.Btn.GRAY);
        IOStyle.button(g, x + 112, y + PANEL_H - 56, 14, 12, Component.literal(">"), this.font, mouseX, mouseY,
                this.page + 1 < pages(), IOStyle.Btn.GRAY);

        // ---- 右：已选清单（固定行距 + 裁剪 + 滚轮）----
        int rx = x + PICK_X_OFFSET;
        int listTop = y + PICK_TOP_OFFSET;
        int listBottom = listTop + PICK_VISIBLE * PICK_ROW_H;
        g.drawString(this.font, Component.translatable("gui.module_shop.demand.picked",
                this.picked.size(), MAX_LINES), rx, y + 26, IOStyle.ORANGE, false);
        this.pickScroll = Math.max(0, Math.min(this.pickScroll, maxPickScroll()));
        boolean scissored = false;
        int first = this.pickScroll;
        int last = Math.min(this.picked.size(), first + PICK_VISIBLE);
        for (int i = first; i < last; i++) {
            Entry entry = this.picked.get(i);
            int ry = listTop + (i - first) * PICK_ROW_H;
            if (!scissored) {
                g.enableScissor(rx - 2, listTop - 2, rx + 166, listBottom);
                scissored = true;
            }
            if (i == this.selectedPick) {
                g.fill(rx - 1, ry, rx + 162, ry + PICK_ROW_H - 1, IOStyle.withAlpha(IOStyle.BLUE, 64));
            }
            Bricks.slot(g, rx, ry);
            g.renderItem(entry.stack, rx, ry);
            drawSmallLabel(g, rx + 20, ry + 5,
                    Component.literal(this.font.plainSubstrByWidth(cn.autoforged.module_shop.client.ui.ItemNames.display(entry.stack), 74)),
                    IOStyle.TEXT);
            drawSmallLabel(g, rx + 104, ry + 5, Component.literal("x" + entry.count), IOStyle.TEXT_2);
            IOStyle.button(g, rx + 120, ry + 2, 12, 12, Component.literal("-"), this.font, mouseX, mouseY,
                    entry.count > 1, IOStyle.Btn.GRAY);
            IOStyle.button(g, rx + 134, ry + 2, 12, 12, Component.literal("+"), this.font, mouseX, mouseY,
                    entry.count < 64, IOStyle.Btn.GRAY);
            IOStyle.button(g, rx + 148, ry + 2, 12, 12, Component.literal("x"), this.font, mouseX, mouseY,
                    true, IOStyle.Btn.GRAY);
        }
        if (scissored) {
            g.disableScissor();
        }
        // 滚动条（清单超出一屏时才有）
        if (maxPickScroll() > 0) {
            int trackX = rx + 164;
            int trackH = PICK_VISIBLE * PICK_ROW_H - 2;
            g.fill(trackX, listTop, trackX + 2, listTop + trackH, IOStyle.withAlpha(IOStyle.BORDER_DIM, 190));
            int thumbH = Math.max(12, trackH * PICK_VISIBLE / Math.max(1, this.picked.size()));
            int thumbY = listTop + (trackH - thumbH) * this.pickScroll / Math.max(1, maxPickScroll());
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, IOStyle.BLUE);
        }
        // 右侧固定数量输入框（兼容模式）
        Entry selLegacy = selectedEntry();
        if (this.countBox != null) {
            this.countBox.setVisible(selLegacy != null);
            this.countBox.setEditable(selLegacy != null);
        }
        if (selLegacy != null) {
            int editY = listBottom + 4;
            drawSmallLabel(g, rx, editY + 4, Component.translatable("gui.module_shop.stall.f_count"), IOStyle.TEXT_2);
            IOStyle.rounded(g, rx + 32, editY, 46, 16, 0, IOStyle.SIDEBAR);
            IOStyle.ring(g, rx + 32, editY, 46, 16, 0, IOStyle.BORDER_DIM);
            if (this.countBox != null) {
                this.countBox.setX(rx + 34);
                this.countBox.setY(editY + 4);
            }
            IOStyle.button(g, rx + 82, editY + 1, 20, 14, Component.literal("1"), this.font, mouseX, mouseY,
                    selLegacy.count != 1, IOStyle.Btn.GRAY);
            IOStyle.button(g, rx + 106, editY + 1, 24, 14, Component.literal("64"), this.font, mouseX, mouseY,
                    selLegacy.count != 64, IOStyle.Btn.GRAY);
        }

        // ---- 底部：悬赏金币 + 发布 / 取消 ----
        drawSmallLabel(g, x + PANEL_W - 148, y + PANEL_H - 26, Component.translatable(
                "gui.module_shop.field.reward_money"), IOStyle.TEXT_2);
        IOStyle.button(g, x + 8, y + PANEL_H - 32, 70, 16,
                Component.translatable("gui.module_shop.demand.publish"), this.font, mouseX, mouseY, true,
                IOStyle.Btn.PRIMARY);
        IOStyle.button(g, x + 84, y + PANEL_H - 32, 70, 16, Component.translatable("gui.module_shop.cancel"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        // 提示 / 报错都放左下角（网格下方那条空带），不会压到右侧清单
        String countErr = countProblem();
        String msg = countErr != null ? countErr : this.status;
        Component tip = msg.isEmpty()
                ? Component.translatable("gui.module_shop.demand.offhand_hint")
                : Component.literal(msg);
        drawSmallLabel(g, x + 8, y + PANEL_H - 44, tip,
                msg.isEmpty() ? IOStyle.TEXT_3 : IOStyle.RED);
    }

    // ---------------------------------------------------------------- Skija

    private final cn.autoforged.module_shop.client.ui.widget.ScrollController skGrid =
            new cn.autoforged.module_shop.client.ui.widget.ScrollController();
    private final cn.autoforged.module_shop.client.ui.widget.ScrollController skPicked =
            new cn.autoforged.module_shop.client.ui.widget.ScrollController();
    private cn.autoforged.module_shop.client.ui.widget.SkiaTextField skSearch;
    private cn.autoforged.module_shop.client.ui.widget.SkiaTextField skCount;
    private PriceSpecEditor priceEditor;
    private String skFilterKey;
    private List<ItemStack> skFiltered = List.of();

    @Override
    protected void initSkia() {
        boolean hadPrev = this.priceEditor != null;
        CurrencyMode prevMode = hadPrev ? this.priceEditor.mode() : CurrencyMode.STAR_ONLY;
        String prevTier = hadPrev ? this.priceEditor.selectedTierKey() : "";
        String prevStar = hadPrev ? this.priceEditor.starField().value() : "0";
        String prevPhys = hadPrev ? this.priceEditor.physField().value() : "";

        this.skSearch = field(48, Component.translatable("gui.module_shop.search.placeholder").getString())
                .clearable(true);
        this.searchBox = this.skSearch.editBox();
        this.searchBox.setValue(this.filter);
        this.skSearch.onChange(text -> {
            this.filter = text == null ? "" : text.trim();
            this.skGrid.scrollTo(0, false);
        });
        this.skCount = field(2, "1~64");
        this.countBox = this.skCount.editBox();
        this.countBox.setFilter(t -> t.isEmpty() || t.matches("[0-9]{0,2}"));
        this.skCount.onChange(this::onCountInputChanged);
        syncCountField(false);

        this.priceEditor = new PriceSpecEditor(this, this.font, PriceSpecEditor.Scope.PLAYER)
                .allowZero(true)
                .starPlaceholder("0")
                .items(this.items);
        if (hadPrev) {
            if (prevMode == CurrencyMode.PHYS_ONLY && prevTier != null && !prevTier.isEmpty()) {
                this.priceEditor.setInitial(PriceSpec.phys(prevTier, 1L));
            } else {
                this.priceEditor.setInitial(PriceSpec.ZERO);
            }
            this.priceEditor.starField().setValue(prevStar);
            this.priceEditor.physField().setValue(prevPhys);
        } else {
            this.priceEditor.setInitial(PriceSpec.ZERO);
        }
        this.priceEditor.registerInputs(this::addWidget);
        this.skSearch.focus();
    }

    private List<ItemStack> skFiltered() {
        if (!this.filter.equals(this.skFilterKey)) {
            this.skFilterKey = this.filter;
            this.skFiltered = filtered();
        }
        return this.skFiltered;
    }

    @Override
    protected void paint(io.github.humbleui.skija.Canvas c) {
        float w = Math.min(420.0F, this.width - 16.0F);
        float h = Math.min(272.0F, this.height - 16.0F);
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        float innerX = x + PAD;
        panel(c, x, y, w, h, this.title.getString());
        // 左：搜索 + 物品网格（左键添加/选中+1，右键直接拉满64并选中）
        float gridW = COLS * CELL + 8;
        float top = y + HEADER_H + 8;
        float bottom = y + h - PAD;
        this.skSearch.bounds(innerX, top, gridW, 16);
        float gridY = top + 22;
        float gridH = 7 * CELL;
        card(c, innerX, gridY - 2, gridW, gridH + 4);
        List<ItemStack> list = skFiltered();
        int rows = (list.size() + COLS - 1) / COLS;
        float oy = beginList(c, this.skGrid, innerX + 2, gridY, gridW - 2, gridH, rows * CELL);
        int firstRow = Math.max(0, (int) ((gridY - oy) / CELL));
        int lastRow = Math.min(rows, firstRow + 9);
        for (int r = firstRow; r < lastRow; r++) {
            for (int col = 0; col < COLS; col++) {
                int i = r * COLS + col;
                if (i >= list.size()) {
                    break;
                }
                float cx = innerX + 2 + col * CELL;
                float cy = oy + r * CELL;
                ItemStack stack = list.get(i);
                if (over(cx, cy, CELL, CELL)) {
                    cn.autoforged.module_shop.client.ui.render.Draw.fill(c, cx, cy, CELL, CELL, 2,
                            IOStyle.GRAY_BTN_HOVER);
                }
                item(stack, cx + 1, cy + 1);
                hit(cx, cy, CELL, CELL, () -> add(stack, false), () -> add(stack, true));
            }
        }
        endList(c);
        small(c, Component.translatable("gui.module_shop.mall.count", list.size()).getString(), innerX,
                gridY + gridH + 8, gridW, IOStyle.TEXT_3);

        // 副手物品悬赏状态预览（与货币悬赏互补/可纯物品悬赏）
        float offY = gridY + gridH + 16;
        ItemStack offhand = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getOffhandItem()
                : ItemStack.EMPTY;
        if (!offhand.isEmpty()) {
            item(offhand, innerX, offY);
            small(c, ClientHomeCurrencyState.tr("gui.module_shop.demand.offhand_badge",
                    "副手悬赏：%s ×%s", offhand.getHoverName().getString(), offhand.getCount()),
                    innerX + 19, offY + 8, gridW - 19, IOStyle.BLUE);
        } else {
            small(c, Component.translatable("gui.module_shop.demand.offhand_hint").getString(),
                    innerX, offY + 8, gridW, IOStyle.TEXT_3);
        }

        // 左下：校验提示 + 发布 / 取消按钮
        float by = bottom - 20;
        String countErr = countProblem();
        String rewErr = rewardProblem();
        String prob = countErr != null ? countErr : rewErr;
        String tipText = !this.status.isEmpty() ? this.status : (prob != null ? prob : "");
        if (!tipText.isEmpty()) {
            boolean isErr = this.status.isEmpty() || prob != null
                    || this.status.contains("失败") || this.status.contains("不足") || this.status.contains("错误");
            paragraph(c, tipText, innerX, by - 20, gridW,
                    cn.autoforged.module_shop.client.ui.render.Text.SMALL,
                    isErr ? IOStyle.RED : IOStyle.TEXT_2, 2, false);
        }
        boolean canPublish = countErr == null && rewErr == null;
        float bw = (gridW - 6.0F) / 2.0F;
        button(c, "publish", Component.translatable("gui.module_shop.demand.publish").getString(), innerX, by, bw, 20,
                cn.autoforged.module_shop.client.ui.widget.Button.Kind.ACCENT, canPublish, this::publish);
        button(c, "cancel", Component.translatable("gui.module_shop.cancel").getString(), innerX + bw + 6.0F, by, bw,
                20, cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL, true, Nav::back);

        // 右：已选清单 + 数量编辑条 + 悬赏货币编辑器（双账本）
        float rx = innerX + gridW + 10;
        float rw = x + w - PAD - rx;
        text(c, Component.translatable("gui.module_shop.demand.picked", this.picked.size(), MAX_LINES).getString(),
                rx, top + 8, rw, IOStyle.ORANGE);
        float listY = gridY - 2;
        float editH = 24.0F;
        float editorH = this.priceEditor.preferredHeight();
        float rewardCardH = editorH + 24.0F;
        float listH = Math.max(48.0F, bottom - listY - 4.0F - editH - 5.0F - rewardCardH);
        card(c, rx, listY, rw, listH);
        if (this.picked.isEmpty()) {
            paragraph(c, Component.translatable("gui.module_shop.demand.pick_first").getString(), rx + 6,
                    listY + listH / 2 - 6, rw - 12, cn.autoforged.module_shop.client.ui.render.Text.SMALL,
                    IOStyle.TEXT_3, 3, true);
        } else {
            float rowH = 20.0F;
            float py = beginList(c, this.skPicked, rx + 2, listY + 2, rw - 4, listH - 4, this.picked.size() * rowH);
            for (int i = 0; i < this.picked.size(); i++) {
                final int idx = i;
                Entry entry = this.picked.get(i);
                float ry = py + i * rowH;
                if (ry + rowH < listY || ry > listY + listH) {
                    continue;
                }
                boolean selected = (i == this.selectedPick);
                if (selected) {
                    cn.autoforged.module_shop.client.ui.render.Draw.fill(c, rx + 3, ry + 1, rw - 10, rowH - 2, 3.0F,
                            cn.autoforged.module_shop.client.ui.render.Draw.alpha(IOStyle.BLUE, 0.16F));
                    cn.autoforged.module_shop.client.ui.render.Draw.stroke(c, rx + 3, ry + 1, rw - 10, rowH - 2, 3.0F,
                            1.0F, IOStyle.BLUE);
                }
                item(entry.stack, rx + 5, ry + 2);
                float bx = rx + rw - 6 - 17 * 3;
                hit(rx + 3, ry + 1, bx - (rx + 3) - 2, rowH - 2, () -> selectPick(idx, true), null);
                text(c, cn.autoforged.module_shop.client.ui.ItemNames.display(entry.stack), rx + 24, ry + 7, bx - rx - 27.5F, IOStyle.TEXT);
                small(c, "×" + entry.count, rx + 24, ry + 15, 44.5F, selected ? IOStyle.BLUE : IOStyle.TEXT_2);
                cn.autoforged.module_shop.client.ui.widget.Button.Kind n =
                        cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL;
                button(c, "dec" + i, "-", bx, ry + 3, 16, 14, n, entry.count > 1, () -> {
                    selectPick(idx, false);
                    setSelectedCount(entry.count - 1, false);
                });
                button(c, "inc" + i, "+", bx + 17, ry + 3, 16, 14, n, entry.count < 64, () -> {
                    selectPick(idx, false);
                    setSelectedCount(entry.count + 1, false);
                });
                button(c, "rm" + i, "×", bx + 34, ry + 3, 16, 14,
                        cn.autoforged.module_shop.client.ui.widget.Button.Kind.DANGER, true,
                        () -> removePick(idx));
            }
            endList(c);
        }

        // 右侧固定「数量」编辑条
        float editY = listY + listH + 4.0F;
        card(c, rx, editY, rw, editH);
        Entry sel = selectedEntry();
        if (sel == null) {
            this.skCount.visible(false);
            small(c, Component.translatable("gui.module_shop.stall.pick_item").getString(), rx + 8,
                    editY + editH / 2.0F, rw - 16, IOStyle.TEXT_3);
        } else {
            item(sel.stack, rx + 5, editY + 4);
            small(c, Component.translatable("gui.module_shop.stall.f_count").getString(), rx + 25,
                    editY + editH / 2.0F, 28, IOStyle.TEXT_2);
            float setX = rx + rw - 60.0F;
            float cntW = Math.max(44.0F, setX - (rx + 54.0F) - 4.0F);
            this.skCount.bounds(rx + 54.0F, editY + 4.0F, cntW, 16.0F).visible(true);
            cn.autoforged.module_shop.client.ui.widget.Button.Kind n =
                    cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL;
            button(c, "set1", "1", setX, editY + 4.0F, 24.0F, 16.0F, n, sel.count != 1,
                    () -> setSelectedCount(1, true));
            button(c, "set64", "64", setX + 26.0F, editY + 4.0F, 28.0F, 16.0F, n, sel.count != 64,
                    () -> setSelectedCount(64, true));
        }

        // 右下：悬赏货币编辑器 + 同账本手续费与托管总额预览
        float rewardY = editY + editH + 5.0F;
        card(c, rx, rewardY, rw, rewardCardH);
        this.priceEditor.bounds(rx + 6.0F, rewardY + 5.0F, rw - 12.0F);
        this.priceEditor.render(c, this.items, this.mouseX, this.mouseY, this.dtMs);
        Component editorTip = this.priceEditor.hoveredTooltip();
        if (editorTip != null) {
            tip(editorTip);
        }
        float footY = rewardY + 5.0F + editorH + 10.0F;
        String edErr = this.priceEditor.validateError();
        if (edErr != null) {
            small(c, edErr, rx + 8.0F, footY, rw - 16.0F, IOStyle.RED);
        } else {
            PriceSpec preview = previewRewardSpec();
            if (preview == null || preview.isZero()) {
                small(c, ClientHomeCurrencyState.tr("gui.module_shop.demand.reward_zero_preview",
                        "无货币悬赏 · 手续费 0"), rx + 8.0F, footY, rw - 16.0F, IOStyle.TEXT_3);
            } else if (preview.supportsStar() && ClientShopData.demandMaxReward > 0
                    && preview.starPrice > ClientShopData.demandMaxReward) {
                small(c, ClientHomeCurrencyState.tr("gui.module_shop.demand.over_max_reward",
                        "超出悬赏上限 %s", PriceDisplay.formatNumber(ClientShopData.demandMaxReward, false)),
                        rx + 8.0F, footY, rw - 16.0F, IOStyle.RED);
            } else {
                PriceSpec feeSpec = feeSpecOf(preview);
                PriceSpec escrowSpec = escrowSpecOf(preview);
                boolean canPay = escrowSpec != null && PriceDisplay.canAfford(escrowSpec, 1);
                if (feeSpec == null || feeSpec.isZero()) {
                    small(c, ClientHomeCurrencyState.tr("gui.module_shop.demand.fee_zero_lbl", "费: 免费"),
                            rx + 8.0F, footY, 76.0F, IOStyle.TEXT_2);
                } else {
                    PriceDisplay.drawCell(c, this.items, feeSpec, rx + 8.0F, footY, 86.0F,
                            Text.Align.LEFT, IOStyle.TEXT_2, false,
                            ClientHomeCurrencyState.tr("gui.module_shop.demand.fee_prefix", "费:"));
                }
                int escrowCol = canPay ? IOStyle.TEXT : IOStyle.RED;
                PriceDisplay.drawCell(c, this.items, escrowSpec, rx + 96.0F, footY, rw - 104.0F,
                        Text.Align.RIGHT, escrowCol, false,
                        ClientHomeCurrencyState.tr("gui.module_shop.demand.escrow_prefix", "托管:"));
            }
        }
    }

    /** 0.8 倍小字（左对齐）。 */
    private void drawSmallLabel(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- 交互

    @Override
    protected boolean onScroll(double mx, double my, double amount) {
        if (this.priceEditor != null && this.priceEditor.mouseScrolled(mx, my, amount)) {
            return true;
        }
        return super.onScroll(mx, my, amount);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (skia()) {
            return super.mouseScrolled(mouseX, mouseY, scrollY);
        }
        int x = left();
        int y = top();
        int rx = x + PICK_X_OFFSET;
        int listTop = y + PICK_TOP_OFFSET;
        int listBottom = listTop + PICK_VISIBLE * PICK_ROW_H;
        if (maxPickScroll() > 0
                && IOStyle.inside(rx - 2, listTop - 2, 170, listBottom - listTop + 4, mouseX, mouseY)) {
            int dir = scrollY > 0 ? -1 : 1;
            this.pickScroll = Math.max(0, Math.min(maxPickScroll(), this.pickScroll + dir));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (skia() && this.priceEditor != null) {
            float k = Math.max(0.01F, SkiaFrame.unitsToGui());
            this.priceEditor.mouseReleased((float) (mouseX / k), (float) (mouseY / k), button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (skia() && this.priceEditor != null) {
            float k = Math.max(0.01F, SkiaFrame.unitsToGui());
            if (this.priceEditor.mouseDragged(mouseX / k, mouseY / k, button, dx / k, dy / k)) {
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            if (this.priceEditor != null) {
                float k = Math.max(0.01F, SkiaFrame.unitsToGui());
                float sx = (float) (mouseX / k);
                float sy = (float) (mouseY / k);
                if (this.priceEditor.mouseClicked(sx, sy, button)) {
                    // 只让本界面的「搜索 / 数量」输入框失焦。**绝不能再 setFocused(null)**：
                    // PriceSpecEditor 内的 SkiaTextField.focus() 刚把 Screen 焦点交给星币/实体币 EditBox，
                    // 清空 Screen 焦点会连带 EditBox.setFocused(false)，键盘与字符事件再也送不进去——
                    // 星币框永远停在默认的「0」，并行模式下只能靠默认「1」枚的实体币发布求购（v1.5.1 修复）。
                    if (this.skSearch != null) {
                        this.skSearch.blur();
                    }
                    if (this.skCount != null) {
                        this.skCount.blur();
                    }
                    return true;
                }

                this.priceEditor.blur();
            }
            return skiaClicked(mouseX, mouseY, button);
        }
        int x = left();
        int y = top();
        if (button == 0) {
            // 物品网格
            for (int[] rect : this.cellRects) {
                if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                    Bricks.click();
                    add(filtered().get(rect[4]));
                    return true;
                }
            }
            // 翻页
            if (IOStyle.inside(x + 96, y + PANEL_H - 56, 14, 12, mouseX, mouseY)) {
                Bricks.click();
                this.page = Math.max(0, this.page - 1);
                return true;
            }
            if (IOStyle.inside(x + 112, y + PANEL_H - 56, 14, 12, mouseX, mouseY)) {
                Bricks.click();
                this.page = Math.min(pages() - 1, this.page + 1);
                return true;
            }
            // 已选清单的 +/−/移除/点击选中（按可见行换算回真实下标）
            int rx = x + PICK_X_OFFSET;
            int listTop = y + PICK_TOP_OFFSET;
            int listBottom = listTop + PICK_VISIBLE * PICK_ROW_H;
            for (int i = this.pickScroll; i < Math.min(this.picked.size(), this.pickScroll + PICK_VISIBLE); i++) {
                Entry entry = this.picked.get(i);
                int ry = listTop + (i - this.pickScroll) * PICK_ROW_H;
                if (IOStyle.inside(rx + 120, ry + 2, 12, 12, mouseX, mouseY)) {
                    Bricks.click();
                    selectPick(i, false);
                    setSelectedCount(entry.count - 1, false);
                    return true;
                }
                if (IOStyle.inside(rx + 134, ry + 2, 12, 12, mouseX, mouseY)) {
                    Bricks.click();
                    selectPick(i, false);
                    setSelectedCount(entry.count + 1, false);
                    return true;
                }
                if (IOStyle.inside(rx + 148, ry + 2, 12, 12, mouseX, mouseY)) {
                    Bricks.click();
                    removePick(i);
                    return true;
                }
                if (IOStyle.inside(rx, ry, 118, PICK_ROW_H, mouseX, mouseY)) {
                    Bricks.click();
                    selectPick(i, true);
                    return true;
                }
            }
            if (selectedEntry() != null) {
                int editY = listBottom + 4;
                if (IOStyle.inside(rx + 82, editY + 1, 20, 14, mouseX, mouseY)) {
                    Bricks.click();
                    setSelectedCount(1, true);
                    return true;
                }
                if (IOStyle.inside(rx + 106, editY + 1, 24, 14, mouseX, mouseY)) {
                    Bricks.click();
                    setSelectedCount(64, true);
                    return true;
                }
            }
            // 发布 / 取消
            if (IOStyle.inside(x + 8, y + PANEL_H - 32, 70, 16, mouseX, mouseY)) {
                Bricks.click();
                publish();
                return true;
            }
            if (IOStyle.inside(x + 84, y + PANEL_H - 32, 70, 16, mouseX, mouseY)) {
                Bricks.click();
                Nav.back();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
