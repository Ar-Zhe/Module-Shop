package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
public class DemandCreateScreen extends Screen {
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
    private static final int PICK_VISIBLE = 8;
    private static final int PICK_X_OFFSET = 186;
    private static final int PICK_TOP_OFFSET = 40;

    private final List<ItemStack> allItems = new ArrayList<>();
    private final List<Entry> picked = new ArrayList<>();
    private final List<int[]> cellRects = new ArrayList<>();

    private EditBox searchBox;
    private EditBox moneyBox;
    private int page;
    private int pickScroll;
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
        super(Component.translatable("gui.server_shop_mod.input.demand_create"));
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
        super.init();
        int x = left();
        int y = top();
        this.searchBox = new EditBox(this.font, x + 10, y + 24, 148, 12,
                Component.translatable("gui.server_shop_mod.demand.search_hint"));
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(32);
        this.searchBox.setHint(Component.translatable("gui.server_shop_mod.demand.search_hint"));
        this.searchBox.setValue(this.filter);
        this.searchBox.setResponder(text -> {
            this.filter = text == null ? "" : text.trim();
            this.page = 0;
        });
        this.addRenderableWidget(this.searchBox);
        this.moneyBox = new EditBox(this.font, x + PANEL_W - 90, y + PANEL_H - 28, 58, 12,
                Component.translatable("gui.server_shop_mod.field.reward_money"));
        this.moneyBox.setBordered(false);
        this.moneyBox.setMaxLength(12);
        this.moneyBox.setValue("0");
        this.addRenderableWidget(this.moneyBox);
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
        String needle = this.filter.toLowerCase(Locale.ROOT);
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : this.allItems) {
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
            if (name.contains(needle) || id.contains(needle)) {
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

    /** 加入清单（同种物品合并数量）。 */
    private void add(ItemStack stack) {
        for (Entry entry : this.picked) {
            if (ItemStack.isSameItemSameComponents(entry.stack, stack)) {
                entry.count = Math.min(64, entry.count + 1);
                return;
            }
        }
        if (this.picked.size() >= MAX_LINES) {
            this.status = Component.translatable("gui.server_shop_mod.demand.too_many", MAX_LINES).getString();
            return;
        }
        ItemStack one = stack.copy();
        one.setCount(1);
        this.picked.add(new Entry(one, 1));
        this.status = "";
        // 新加的那条若超出一屏，自动滚到能看见它
        this.pickScroll = Math.min(maxPickScroll(), Math.max(0, this.picked.size() - PICK_VISIBLE));
    }

    private void publish() {
        if (this.picked.isEmpty()) {
            this.status = Component.translatable("gui.server_shop_mod.demand.pick_first").getString();
            return;
        }
        long money;
        try {
            money = Math.max(0L, Long.parseLong(this.moneyBox.getValue().trim()));
        } catch (NumberFormatException ex) {
            this.status = Component.translatable("gui.server_shop_mod.demand.money_bad").getString();
            return;
        }
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "demand_create");
        ListTag list = new ListTag();
        for (Entry entry : this.picked) {
            CompoundTag line = new CompoundTag();
            line.put("item", entry.stack.saveOptional(this.minecraft.level.registryAccess()));
            line.putInt("count", entry.count);
            list.add(line);
        }
        tag.put("wants", list);
        tag.putString("reward_money", Long.toString(money));
        ServerboundShopActionPayload.send(tag);
        Nav.back();
    }

    // ---------------------------------------------------------------- 背景

    /**
     * 背景**不做世界模糊**：原版会把世界模糊后垫在下面，这块面板又大又偏暗，看起来会"糊成一片"。
     * 这里直接铺一层实色底，内容看起来干净清晰。
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xFF0E1013);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 顺序：背景 → 原版组件（输入框）→ 底板 → 内容。内容永远在最上层。
        this.renderBackground(g, mouseX, mouseY, partialTick);
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
        drawSmallLabel(g, x + 8, y + PANEL_H - 54, Component.translatable("gui.server_shop_mod.page",
                this.page + 1, pages()), IOStyle.TEXT_2);
        IOStyle.button(g, x + 96, y + PANEL_H - 56, 14, 12, Component.literal("<"), this.font, mouseX, mouseY,
                this.page > 0, IOStyle.Btn.GRAY);
        IOStyle.button(g, x + 112, y + PANEL_H - 56, 14, 12, Component.literal(">"), this.font, mouseX, mouseY,
                this.page + 1 < pages(), IOStyle.Btn.GRAY);

        // ---- 右：已选清单（固定行距 + 裁剪 + 滚轮）----
        int rx = x + PICK_X_OFFSET;
        int listTop = y + PICK_TOP_OFFSET;
        int listBottom = listTop + PICK_VISIBLE * PICK_ROW_H;
        g.drawString(this.font, Component.translatable("gui.server_shop_mod.demand.picked",
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
            Bricks.slot(g, rx, ry);
            g.renderItem(entry.stack, rx, ry);
            drawSmallLabel(g, rx + 20, ry + 5,
                    Component.literal(this.font.plainSubstrByWidth(entry.stack.getHoverName().getString(), 74)),
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

        // ---- 底部：悬赏金币 + 发布 / 取消 ----
        drawSmallLabel(g, x + PANEL_W - 148, y + PANEL_H - 26, Component.translatable(
                "gui.server_shop_mod.field.reward_money"), IOStyle.TEXT_2);
        IOStyle.button(g, x + 8, y + PANEL_H - 32, 70, 16,
                Component.translatable("gui.server_shop_mod.demand.publish"), this.font, mouseX, mouseY, true,
                IOStyle.Btn.PRIMARY);
        IOStyle.button(g, x + 84, y + PANEL_H - 32, 70, 16, Component.translatable("gui.server_shop_mod.cancel"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        // 提示 / 报错都放左下角（网格下方那条空带），不会压到右侧清单
        Component tip = this.status.isEmpty()
                ? Component.translatable("gui.server_shop_mod.demand.offhand_hint")
                : Component.literal(this.status);
        drawSmallLabel(g, x + 8, y + PANEL_H - 44, tip,
                this.status.isEmpty() ? IOStyle.TEXT_3 : IOStyle.RED);
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
            // 已选清单的 +/−/移除（按可见行换算回真实下标）
            int rx = x + PICK_X_OFFSET;
            int listTop = y + PICK_TOP_OFFSET;
            for (int i = this.pickScroll; i < Math.min(this.picked.size(), this.pickScroll + PICK_VISIBLE); i++) {
                Entry entry = this.picked.get(i);
                int ry = listTop + (i - this.pickScroll) * PICK_ROW_H;
                if (IOStyle.inside(rx + 120, ry + 2, 12, 12, mouseX, mouseY)) {
                    Bricks.click();
                    entry.count = Math.max(1, entry.count - 1);
                    return true;
                }
                if (IOStyle.inside(rx + 134, ry + 2, 12, 12, mouseX, mouseY)) {
                    Bricks.click();
                    entry.count = Math.min(64, entry.count + 1);
                    return true;
                }
                if (IOStyle.inside(rx + 148, ry + 2, 12, 12, mouseX, mouseY)) {
                    Bricks.click();
                    this.picked.remove(i);
                    this.pickScroll = Math.max(0, Math.min(this.pickScroll, maxPickScroll()));
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
