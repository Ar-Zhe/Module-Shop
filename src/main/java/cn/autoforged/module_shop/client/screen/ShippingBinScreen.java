package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ShippingFavorites;
import cn.autoforged.module_shop.menu.ShippingBinMenu;
import cn.autoforged.module_shop.shop.ShippingRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 出货箱容器界面（参考星露谷售货箱 + 本模组统一的深色扁平风）。
 *
 * <ul>
 *     <li><b>左边</b>：你自己的出货箱格子（按等级 9 / 18 / 27 格）+ 下方自己的背包；</li>
 *     <li><b>右边</b>：可卖物品清单 —— 搜索 / 按模组分类 / 排序 / 标记物品 / 只看已有，全部排在**清单上方**，
 *     下面才是列表；点一行 = 把背包里那一叠直接放进去；</li>
 *     <li>打开「只看已有」时，**背包里能卖的东西会亮起绿框**；打开「只看标记」时，**标记的物品亮金框**，
 *     一眼就知道该往箱子里放什么。</li>
 * </ul>
 */
public class ShippingBinScreen extends AbstractContainerScreen<ShippingBinMenu> {
    /** 面板宽度：左边 178（出货箱 + 背包）+ 间距 + 右边 160（可卖清单）。 */
    private static final int PANEL_W = 342;
    /** 右侧清单区域。 */
    private static final int LIST_X = 176;
    private static final int LIST_W = 160;
    private static final int LIST_ROW_H = 16;
    private static final int CTRL_H = 14;
    /** 右侧控件纵向位置（相对面板顶部）——整体上移，给清单腾出更多行。 */
    private static final int SEARCH_Y = 8;
    private static final int ROW2_Y = SEARCH_Y + CTRL_H + 3;
    private static final int ROW3_Y = ROW2_Y + CTRL_H + 3;
    private static final int LIST_TOP = ROW3_Y + CTRL_H + 4;
    /** 面板底部额外留出的高度：全给右侧清单用（列表能多放 2 行）。 */
    private static final int BOTTOM_PAD = 24;
    /** 下拉面板：行高 / 面板宽度（与清单同宽，点开时像一张正经菜单）/ 分类最多显示几行。 */
    private static final int DD_ROW_H = 14;
    private static final int DD_SORT_W = LIST_W;
    private static final int DD_CAT_W = LIST_W;
    private static final int DD_CAT_ROWS = 9;

    /** 一行「可卖物品」。 */
    private record Entry(ItemStack stack, long price, int dailyMax, String id, String namespace) {
    }

    /** 规则原文 → 展开后的可卖物品（缓存，规则没变就不重建）。 */
    private static List<Entry> entryCache = List.of();
    private static String entryKey = "\u0000";
    private static ShippingRules.Parsed ruleCache;
    private static String ruleKey = "\u0000";

    private EditBox searchBox;
    /** 列表里悬停那一行的提示：留到裁剪区之外再画（在 scissor 里画会被裁掉大半）。 */
    private Component pendingTooltip;
    private int listScroll;
    private boolean onlyOwned;
    private boolean onlyMarked;
    private String category = "";
    private int sort;
    private boolean sortOpen;
    private boolean catOpen;
    private int catScroll;
    private final int[] catRect = new int[4];
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<int[]> starRects = new ArrayList<>();
    private final List<int[]> sortRects = new ArrayList<>();
    private final List<int[]> catRects = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();

    public ShippingBinScreen(ShippingBinMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = Math.max(menu.inventoryY() + 76, 150) + BOTTOM_PAD;
        this.inventoryLabelY = menu.inventoryY() - 11;
    }

    // =========================================================== 数据

    private static ShippingRules.Parsed rules() {
        String text = ClientShopData.shipRulesText == null ? "" : ClientShopData.shipRulesText;
        if (ruleCache == null || !text.equals(ruleKey)) {
            ruleKey = text;
            ruleCache = ShippingRules.parse(List.of(text.split("\n")));
        }
        return ruleCache;
    }

    private static String itemId(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private static String namespaceOf(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "minecraft" : id.getNamespace();
    }

    /** 把规则展开成"具体能卖哪些物品"（标签 / 整模组会展开成物品；单条规则最多 400 件，总计 2000 件）。 */
    private static List<Entry> allEntries() {
        String text = ClientShopData.shipRulesText == null ? "" : ClientShopData.shipRulesText;
        if (!entryCache.isEmpty() && text.equals(entryKey)) {
            return entryCache;
        }
        entryKey = text;
        List<Entry> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ShippingRules.Rule rule : rules().rules()) {
            int added = 0;
            for (var item : BuiltInRegistries.ITEM) {
                if (added >= 400 || result.size() >= 2000) {
                    break;
                }
                ItemStack stack = new ItemStack(item);
                if (stack.isEmpty() || !rule.matches(stack)) {
                    continue;
                }
                String id = itemId(stack);
                if (id.isEmpty() || !seen.add(id)) {
                    continue;
                }
                result.add(new Entry(stack, rule.price(), rule.dailyMax(), id, namespaceOf(stack)));
                added++;
            }
        }
        entryCache = result;
        return result;
    }

    /** 分类（可卖物品里出现过的模组，按显示名排序）。 */
    private static List<String> categories() {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        for (Entry entry : allEntries()) {
            set.add(entry.namespace());
        }
        List<String> list = new ArrayList<>(set);
        list.sort(Comparator.comparing(ns -> modName(ns).toLowerCase()));
        return list;
    }

    private static String modName(String namespace) {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById(namespace)
                    .map(c -> c.getModInfo().getDisplayName()).orElse(namespace);
        } catch (Throwable ignored) {
            return namespace;
        }
    }

    /** 背包里有多少个该物品（只看已有 / 悬停提示用）。 */
    private static int ownedCount(ItemStack want) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, want)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 这件物品可不可以卖（用于给背包格子画绿框）。 */
    private static boolean sellable(ItemStack stack) {
        return !stack.isEmpty() && ShippingRules.match(rules().rules(), stack) != null;
    }

    private void rebuildVisible() {
        this.visible.clear();
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase();
        for (Entry entry : allEntries()) {
            if (!this.category.isEmpty() && !this.category.equals(entry.namespace())) {
                continue;
            }
            if (this.onlyMarked && !ShippingFavorites.contains(entry.id())) {
                continue;
            }
            if (this.onlyOwned && ownedCount(entry.stack()) <= 0) {
                continue;
            }
            if (!query.isEmpty()) {
                String name = entry.stack().getHoverName().getString().toLowerCase();
                if (!name.contains(query) && !entry.namespace().contains(query)
                        && !modName(entry.namespace()).toLowerCase().contains(query)) {
                    continue;
                }
            }
            this.visible.add(entry);
        }
        switch (this.sort) {
            case 1 -> this.visible.sort(Comparator.comparingLong(Entry::price));
            case 2 -> this.visible.sort((a, b) -> Long.compare(b.price(), a.price()));
            case 3 -> this.visible.sort(Comparator.comparing(e -> e.stack().getHoverName().getString()));
            default -> {
            }
        }
    }

    // =========================================================== 初始化

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 8;
        ShippingFavorites.ensureLoaded();
        this.searchBox = new EditBox(this.font, this.leftPos + LIST_X + 5, this.topPos + SEARCH_Y + 4,
                LIST_W - 10, CTRL_H, Component.translatable("gui.module_shop.shipping.search_hint"));
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(32);
        // hint 由界面自己画（原版 hint 只在失焦时出现，会和自绘的叠在一起）——所以这里清空。
        this.searchBox.setHint(Component.empty());
        this.searchBox.setResponder(text -> this.listScroll = 0);
        this.addRenderableWidget(this.searchBox);
    }

    /** ESC / 关闭：回到商店主界面的「出货箱」页，而不是直接退回游戏。 */
    @Override
    public void onClose() {
        super.onClose();
        Nav.backToShop();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =========================================================== 渲染

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        Bricks.panel(g, x, y, this.imageWidth, this.imageHeight);
        int rows = Math.max(1, (getMenu().binSlots() + 8) / 9);
        g.fill(x + 6, y + ShippingBinMenu.BIN_Y - 4, x + ShippingBinMenu.BIN_X + 162,
                y + ShippingBinMenu.BIN_Y + rows * 18, 0x30101114);
        for (int i = 0; i < getMenu().binSlots(); i++) {
            Bricks.slot(g, x + ShippingBinMenu.BIN_X + (i % 9) * 18,
                    y + ShippingBinMenu.BIN_Y + (i / 9) * 18);
        }
        g.blit(Bricks.PLAYER_SLOTS, x + ShippingBinMenu.BIN_X - 1, y + getMenu().inventoryY() - 1,
                0, 0, 162, 76, 162, 76);
        // 右侧清单底板（从顶部一直到底部，比左边更高，尽量多放几行）
        IOStyle.card(g, x + LIST_X - 6, y + 2, LIST_W + 12, this.imageHeight - 6, 0);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = this.leftPos;
        int y = this.topPos;
        this.rowRects.clear();
        this.starRects.clear();
        this.sortRects.clear();
        this.catRects.clear();
        rebuildVisible();
        // ---- 顶部两行信息（本次结算 / 今日已结算），宽度限制在右侧清单之前 ----
        long value = liveValue();
        drawSmall(g, x + 8, y + 18, Component.translatable("gui.module_shop.shipping.next_payout",
                ClientShopData.money(value), liveItems()), value > 0L ? IOStyle.ORANGE : IOStyle.TEXT_2, 152);
        drawSmall(g, x + 8, y + 26, ClientShopData.shipLimit > 0L
                        ? Component.translatable("gui.module_shop.shipping.today_line",
                                ClientShopData.money(ClientShopData.shipSoldToday),
                                ClientShopData.money(ClientShopData.shipLimit))
                        : Component.translatable("gui.module_shop.shipping.today_line_unlimited",
                                ClientShopData.money(ClientShopData.shipSoldToday)),
                IOStyle.TEXT_2, 156);
        highlightInventory(g, x, y);
        drawList(g, x, y, mouseX, mouseY);
        drawDropdowns(g, x, y, mouseX, mouseY);
        // 悬停提示框放到最后：既在列表裁剪区之外，也不会被下拉面板压住。
        if (this.pendingTooltip != null) {
            g.renderTooltip(this.font, this.pendingTooltip, mouseX, mouseY);
            this.pendingTooltip = null;
        }
        // 槽位悬停提示：必须画在最后（原版 AbstractContainerScreen 先画，会被自绘面板盖住）。
        if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            g.renderTooltip(this.font, this.hoveredSlot.getItem(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        IOStyle.text(g, this.font, Component.translatable("gui.module_shop.shipping.title",
                ClientShopData.shipLevel, ClientShopData.shipSlots), 8, 6, IOStyle.TEXT);
        IOStyle.text(g, this.font, Component.translatable("gui.module_shop.shipping.bag"),
                8, getMenu().inventoryY() - 10, IOStyle.TEXT_3);
    }

    /** 背包里的可卖 / 已标记物品画个亮框（只在对应开关打开时显示，避免平时太花）。 */
    private void highlightInventory(GuiGraphics g, int x, int y) {
        if (!this.onlyOwned && !this.onlyMarked) {
            return;
        }
        for (Slot slot : getMenu().slots) {
            if (slot.container != this.minecraft.player.getInventory() || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            boolean marked = ShippingFavorites.contains(itemId(stack));
            boolean canSell = sellable(stack);
            int color = 0;
            if (this.onlyMarked && marked) {
                color = IOStyle.ORANGE;
            } else if (this.onlyOwned && canSell) {
                color = IOStyle.GREEN;
            }
            if (color != 0) {
                IOStyle.ring(g, x + slot.x - 1, y + slot.y - 1, 18, 18, 0, color);
                IOStyle.ring(g, x + slot.x - 2, y + slot.y - 2, 20, 20, 0, IOStyle.withAlpha(color, 90));
            }
        }
    }

    /** 右侧清单：搜索框 + 两行按钮（在**上方**）+ 列表（在下面）+ 底部数量提示。 */
    private void drawList(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int sx = x + LIST_X;
        int halfW = (LIST_W - 4) / 2;
        this.pendingTooltip = null;
        // 搜索框
        IOStyle.rounded(g, sx, y + SEARCH_Y, LIST_W, CTRL_H, 0, IOStyle.FIELD_BG);
        boolean searchFocused = this.searchBox != null && this.searchBox.isFocused();
        IOStyle.ring(g, sx, y + SEARCH_Y, LIST_W, CTRL_H, 0,
                searchFocused ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
        // 提示文字自己画：原版只在"未聚焦"时画 hint，这里保证玩家一眼看到该框能打字。
        if (this.searchBox != null && this.searchBox.getValue().isEmpty() && !searchFocused) {
            drawSmall(g, sx + 5, y + SEARCH_Y + 4,
                    Component.translatable("gui.module_shop.shipping.search_hint"), IOStyle.TEXT_3,
                    LIST_W - 10);
        }
        // 分类 / 排序
        String catName = this.category.isEmpty()
                ? Component.translatable("gui.module_shop.shipping.cat_all").getString()
                : modName(this.category);
        int catMax = (int) ((halfW - 8) / 0.8F);
        drawSmallLabelButton(g, sx, y + ROW2_Y, halfW, CTRL_H, Component.literal(this.font.width(catName) > catMax
                        ? this.font.plainSubstrByWidth(catName, Math.max(1, catMax - 6)) + "…" : catName),
                IOStyle.inside(sx, y + ROW2_Y, halfW, CTRL_H, mouseX, mouseY), this.catOpen, true);
        int sortX = sx + halfW + 4;
        drawSmallLabelButton(g, sortX, y + ROW2_Y, halfW, CTRL_H,
                Component.translatable("gui.module_shop.shipping.sort_label", sortName()),
                IOStyle.inside(sortX, y + ROW2_Y, halfW, CTRL_H, mouseX, mouseY), this.sortOpen, true);
        // 只看已有 / 只看标记
        drawSmallLabelButton(g, sx, y + ROW3_Y, halfW, CTRL_H,
                Component.translatable("gui.module_shop.shipping.only_owned"),
                IOStyle.inside(sx, y + ROW3_Y, halfW, CTRL_H, mouseX, mouseY), this.onlyOwned, true);
        drawSmallLabelButton(g, sortX, y + ROW3_Y, halfW, CTRL_H,
                Component.translatable("gui.module_shop.shipping.only_marked"),
                IOStyle.inside(sortX, y + ROW3_Y, halfW, CTRL_H, mouseX, mouseY), this.onlyMarked, true);
        // 列表
        int top = y + LIST_TOP;
        int countY = y + this.imageHeight - 16;
        int bottom = countY - 4;
        int per = Math.max(1, (bottom - top) / LIST_ROW_H);
        if (this.visible.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.shipping.list_empty"),
                    sx + LIST_W / 2, top + 14, IOStyle.TEXT_3);
            return;
        }
        int maxScroll = Math.max(0, this.visible.size() - per);
        this.listScroll = Mth.clamp(this.listScroll, 0, maxScroll);
        // 打开着下拉面板时，和面板重叠的那几行**不画物品图标**：
        // ⚠ 物品图标是 GuiGraphics.renderItem 画的（z = 150 的三维物品渲染，且画完立刻 flush 并写深度），
        // 而面板是 z = 0 的平面填充 —— 深度测试一挡，图标就会「穿透」浮在面板上面
        // （以前点开「排序」时，面板被清单里的胡萝卜 / 马铃薯图标盖住，看着像没弹出来）。
        // 与其和深度缓冲较劲，不如把被遮住的那几个图标直接不画（购物城 / 管理页的下拉也是同样处理）。
        int[] overlay = openDropdownRect();
        g.enableScissor(sx, top, sx + LIST_W, bottom);
        for (int i = this.listScroll; i < this.visible.size() && i - this.listScroll < per; i++) {
            Entry entry = this.visible.get(i);
            int ry = top + (i - this.listScroll) * LIST_ROW_H;
            boolean hover = IOStyle.inside(sx, ry, LIST_W, LIST_ROW_H - 1, mouseX, mouseY);
            IOStyle.rounded(g, sx, ry, LIST_W, LIST_ROW_H - 1, 0, hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR);
            boolean marked = ShippingFavorites.contains(entry.id());
            g.drawString(this.font, marked ? "★" : "☆", sx + 2, ry + 4,
                    marked ? IOStyle.ORANGE : IOStyle.TEXT_3, false);
            boolean iconCovered = overlay != null
                    && overlaps(overlay[0], overlay[1], overlay[2], overlay[3], sx + 14, ry, 16, 16);
            if (!iconCovered) {
                Bricks.slot(g, sx + 14, ry);
                g.renderItem(entry.stack(), sx + 14, ry);
                g.renderItemDecorations(this.font, entry.stack(), sx + 14, ry);
            }
            int priceW = (int) (this.font.width(ClientShopData.money(entry.price())) * 0.8F);
            int nameMax = (int) ((LIST_W - 24 - priceW - 6) / 0.8F);
            String name = entry.stack().getHoverName().getString();
            drawSmall(g, sx + 32, ry + 4, Component.literal(this.font.width(name) > nameMax
                    ? this.font.plainSubstrByWidth(name, Math.max(1, nameMax - 6)) + "…" : name),
                    IOStyle.TEXT, LIST_W - 34 - priceW);
            g.pose().pushPose();
            g.pose().translate(sx + LIST_W - 4 - priceW, ry + 4, 0.0F);
            g.pose().scale(0.8F, 0.8F, 1.0F);
            g.drawString(this.font, ClientShopData.money(entry.price()), 0, 0, IOStyle.ORANGE, false);
            g.pose().popPose();
            int index = i;
            this.starRects.add(new int[]{sx + 1, ry + 2, 11, LIST_ROW_H - 4, index});
            this.rowRects.add(new int[]{sx + 14, ry, LIST_W - 18, LIST_ROW_H - 1, index});
            if (hover) {
                // ⚠ 提示框必须**留到裁剪区之外**再画：列表用 scissor 裁过，
                // 在里面画 tooltip 会被裁掉一大半（之前只看得见最后一行）。
                this.pendingTooltip = Component.literal(entry.stack().getHoverName().getString())
                        .append("\n").append(Component.translatable("gui.module_shop.shipping.tip_price",
                                ClientShopData.money(entry.price())))
                        .append("\n").append(entry.dailyMax() > 0
                                ? Component.translatable("gui.module_shop.shipping.tip_daily", entry.dailyMax())
                                : Component.translatable("gui.module_shop.shipping.tip_daily_none"))
                        .append("\n").append(Component.translatable("gui.module_shop.shipping.tip_owned",
                                ownedCount(entry.stack())))
                        .append("\n").append(Component.translatable("gui.module_shop.shipping.tip_click"));
            }
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int viewH = per * LIST_ROW_H;
            int trackX = sx + LIST_W - 3;
            g.fill(trackX, top, trackX + 1, top + viewH, IOStyle.HAIR);
            int thumbH = Math.max(12, viewH * per / Math.max(1, this.visible.size()));
            int thumbY = top + (viewH - thumbH) * this.listScroll / Math.max(1, maxScroll);
            g.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, IOStyle.BLUE);
        }
        // 底部数量提示（在面板内的清单底板里，不会被边框切掉）
        drawSmall(g, sx, countY, Component.translatable(
                "gui.module_shop.shipping.list_count", this.visible.size(), allEntries().size()),
                IOStyle.TEXT_3, LIST_W);
        // 提示框不在这里画：交给 render() 在「提交物品批次 + 画完下拉面板」之后统一画，避免被压住。
    }

    private Component sortName() {
        return Component.translatable(switch (this.sort) {
            case 1 -> "gui.module_shop.shipping.sort_price_asc";
            case 2 -> "gui.module_shop.shipping.sort_price_desc";
            case 3 -> "gui.module_shop.shipping.sort_name";
            default -> "gui.module_shop.shipping.sort_default";
        });
    }

    private void drawDropdowns(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int sx = x + LIST_X;
        int panelY = y + LIST_TOP;
        if (this.catOpen) {
            List<String> cats = categories();
            int total = cats.size() + 1;
            int rows = Math.min(DD_CAT_ROWS, total);
            int pw = DD_CAT_W;
            int ph = rows * DD_ROW_H + 4;
            this.catRect[0] = sx;
            this.catRect[1] = panelY;
            this.catRect[2] = pw;
            this.catRect[3] = ph;
            this.catScroll = Mth.clamp(this.catScroll, 0, Math.max(0, total - rows));
            IOStyle.card(g, sx, panelY, pw, ph, 0);
            for (int i = this.catScroll; i < Math.min(total, this.catScroll + rows); i++) {
                String ns = i == 0 ? "" : cats.get(i - 1);
                String name = i == 0 ? Component.translatable("gui.module_shop.shipping.cat_all").getString()
                        : modName(ns);
                int ry = panelY + 2 + (i - this.catScroll) * DD_ROW_H;
                boolean current = ns.equals(this.category);
                boolean hover = IOStyle.inside(sx + 2, ry, pw - 4, DD_ROW_H, mouseX, mouseY);
                IOStyle.rounded(g, sx + 2, ry, pw - 4, DD_ROW_H, 0,
                        current ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                IOStyle.ring(g, sx + 2, ry, pw - 4, DD_ROW_H, 0,
                        current ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                int nameMax = (int) ((pw - 16) / 0.8F);
                drawSmall(g, sx + 8, ry + 3, Component.literal(this.font.width(name) > nameMax
                        ? this.font.plainSubstrByWidth(name, Math.max(1, nameMax - 6)) + "…" : name),
                        current ? IOStyle.BLUE : IOStyle.TEXT, pw - 16);
                this.catRects.add(new int[]{sx + 2, ry, pw - 4, DD_ROW_H, i});
            }
        }
        if (this.sortOpen) {
            int pw = DD_SORT_W;
            int ph = 4 * DD_ROW_H + 4;
            IOStyle.card(g, sx, panelY, pw, ph, 0);
            for (int i = 0; i < 4; i++) {
                int ry = panelY + 2 + i * DD_ROW_H;
                boolean current = this.sort == i;
                boolean hover = IOStyle.inside(sx + 2, ry, pw - 4, DD_ROW_H, mouseX, mouseY);
                IOStyle.rounded(g, sx + 2, ry, pw - 4, DD_ROW_H, 0,
                        current ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                IOStyle.ring(g, sx + 2, ry, pw - 4, DD_ROW_H, 0, current ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                String label = switch (i) {
                    case 1 -> Component.translatable("gui.module_shop.shipping.sort_price_asc").getString();
                    case 2 -> Component.translatable("gui.module_shop.shipping.sort_price_desc").getString();
                    case 3 -> Component.translatable("gui.module_shop.shipping.sort_name").getString();
                    default -> Component.translatable("gui.module_shop.shipping.sort_default").getString();
                };
                drawSmall(g, sx + 8, ry + 3, Component.literal(label), current ? IOStyle.BLUE : IOStyle.TEXT, pw - 16);
                this.sortRects.add(new int[]{sx + 2, ry, pw - 4, DD_ROW_H, i});
            }
        }
    }

    /** 当前展开的下拉面板矩形（x, y, w, h）；都没展开时返回 {@code null}。 */
    private int[] openDropdownRect() {
        if (this.sortOpen) {
            return new int[]{this.leftPos + LIST_X, this.topPos + LIST_TOP, DD_SORT_W, 4 * DD_ROW_H + 4};
        }
        if (this.catOpen) {
            int rows = Math.min(DD_CAT_ROWS, categories().size() + 1);
            return new int[]{this.leftPos + LIST_X, this.topPos + LIST_TOP, DD_CAT_W, rows * DD_ROW_H + 4};
        }
        return null;
    }

    /** 两个矩形是否相交（用来判断清单行的物品图标会不会被下拉面板盖住）。 */
    private static boolean overlaps(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
    }

    // =========================================================== 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.leftPos;
        int y = this.topPos;
        int sx = x + LIST_X;
        int halfW = (LIST_W - 4) / 2;
        int sortX = sx + halfW + 4;
        if ((this.catOpen || this.sortOpen) && button == 0) {
            for (int[] rect : this.catRects) {
                if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                    Bricks.click();
                    List<String> cats = categories();
                    this.category = rect[4] == 0 ? "" : cats.get(rect[4] - 1);
                    this.catOpen = false;
                    this.listScroll = 0;
                    return true;
                }
            }
            for (int[] rect : this.sortRects) {
                if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                    Bricks.click();
                    this.sort = rect[4];
                    this.sortOpen = false;
                    return true;
                }
            }
            this.catOpen = false;
            this.sortOpen = false;
            return true;
        }
        if (button == 0 && IOStyle.inside(sx, y + ROW2_Y, halfW, CTRL_H, mouseX, mouseY)) {
            Bricks.click();
            this.catOpen = !this.catOpen;
            this.sortOpen = false;
            this.catScroll = 0;
            return true;
        }
        if (button == 0 && IOStyle.inside(sortX, y + ROW2_Y, halfW, CTRL_H, mouseX, mouseY)) {
            Bricks.click();
            this.sortOpen = !this.sortOpen;
            this.catOpen = false;
            return true;
        }
        if (button == 0 && IOStyle.inside(sx, y + ROW3_Y, halfW, CTRL_H, mouseX, mouseY)) {
            Bricks.click();
            this.onlyOwned = !this.onlyOwned;
            this.listScroll = 0;
            return true;
        }
        if (button == 0 && IOStyle.inside(sortX, y + ROW3_Y, halfW, CTRL_H, mouseX, mouseY)) {
            Bricks.click();
            this.onlyMarked = !this.onlyMarked;
            this.listScroll = 0;
            return true;
        }
        for (int[] rect : this.starRects) {
            if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                Bricks.click();
                ShippingFavorites.toggle(this.visible.get(rect[4]).id());
                return true;
            }
        }
        for (int[] rect : this.rowRects) {
            if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                quickPut(this.visible.get(rect[4]));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 一键放入：把背包里**所有**这一种物品都移进出货箱（一次 Shift + 左键只搬一叠，
     * 所以这里把每一叠都搬一次；箱子放不下时剩下的自动留在背包里，不会丢）。
     */
    private void quickPut(Entry entry) {
        List<Slot> targets = new ArrayList<>();
        for (Slot slot : getMenu().slots) {
            if (slot.container == this.minecraft.player.getInventory() && slot.hasItem()
                    && ItemStack.isSameItemSameComponents(slot.getItem(), entry.stack())) {
                targets.add(slot);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Bricks.click();
        for (Slot slot : targets) {
            // 每搬一叠都重新确认一次：前面的移动可能已经把这一格合并掉了。
            if (slot.hasItem() && ItemStack.isSameItemSameComponents(slot.getItem(), entry.stack())) {
                this.slotClicked(slot, slot.index, 0, ClickType.QUICK_MOVE);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            if (this.catOpen && IOStyle.inside(this.catRect[0], this.catRect[1], this.catRect[2], this.catRect[3],
                    mouseX, mouseY)) {
                this.catScroll = Math.max(0, this.catScroll + (scrollY > 0 ? -1 : 1));
                return true;
            }
            if (mouseX >= this.leftPos + LIST_X - 6 && mouseX < this.leftPos + LIST_X + LIST_W
                    && mouseY >= this.topPos + LIST_TOP) {
                this.listScroll = Math.max(0, this.listScroll + (scrollY > 0 ? -1 : 1));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 搜索框聚焦时数字键不要拿去交换快捷栏。
        if (this.searchBox != null && this.searchBox.isFocused()
                && keyCode >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && keyCode <= org.lwjgl.glfw.GLFW.GLFW_KEY_9) {
            return this.searchBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // =========================================================== 计算与绘制小工具

    /** 箱内物品件数（直接用容器内容，实时）。 */
    private int liveItems() {
        int total = 0;
        for (int i = 0; i < getMenu().binSlots(); i++) {
            ItemStack stack = getMenu().getSlot(i).getItem();
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 本次结算金额（本地实时预估，真正结算以服务端为准）。 */
    private long liveValue() {
        long total = 0L;
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (int i = 0; i < getMenu().binSlots(); i++) {
            ItemStack stack = getMenu().getSlot(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            for (ShippingRules.Rule rule : rules().rules()) {
                if (!rule.matches(stack)) {
                    continue;
                }
                String key = Integer.toString(rule.line());
                int already = counted.getOrDefault(key, 0);
                int allowed = rule.dailyMax() <= 0 ? stack.getCount() : Math.max(0, rule.dailyMax() - already);
                int count = Math.min(stack.getCount(), allowed);
                if (count > 0) {
                    counted.put(key, already + count);
                    total += rule.price() * count;
                }
                break;
            }
        }
        long limit = ClientShopData.shipLimit;
        if (limit > 0L) {
            return Math.min(total, Math.max(0L, limit - ClientShopData.shipSoldToday));
        }
        return total;
    }

    /** 0.8 倍小字（超宽自动截断）。 */
    private void drawSmall(GuiGraphics g, int x, int y, Component text, int color, int maxWidth) {
        int max = (int) (maxWidth / 0.8F);
        String plain = text.getString();
        String shown = this.font.width(plain) > max
                ? this.font.plainSubstrByWidth(plain, Math.max(1, max - 6)) + "…" : plain;
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, shown, 0, 0, color, false);
        g.pose().popPose();
    }

    private void drawSmallLabelButton(GuiGraphics g, int x, int y, int w, int h, Component label, boolean hover,
            boolean selected, boolean enabled) {
        IOStyle.rounded(g, x, y, w, h, 0, hover && enabled ? IOStyle.GRAY_BTN_HOVER : IOStyle.GRAY_BTN);
        IOStyle.ring(g, x, y, w, h, 0, selected ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
        drawSmall(g, x + 4, y + (h - 8) / 2 + 1, label,
                !enabled ? IOStyle.TEXT_3 : (selected ? IOStyle.BLUE : IOStyle.TEXT), w - 8);
    }
}
