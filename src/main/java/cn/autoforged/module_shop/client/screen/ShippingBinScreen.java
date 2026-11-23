package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ShippingFavorites;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.data.PriceSpec;
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
public class ShippingBinScreen extends AbstractContainerScreen<ShippingBinMenu>
        implements JeiHoverSource, SkiaContainerBackground.Painter {

    @Override
    public boolean usesSkiaBackground() {
        return this.skia;
    }

    @Override
    public void paintSkiaBackground(GuiGraphics g, int mouseX, int mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        skBg(g, this.leftPos, this.topPos);
    }

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
    public record Entry(ItemStack stack, PriceSpec priceSpec, long price, int dailyMax, int ruleLine,
                        String id, String namespace) {
        public Entry(ItemStack stack, PriceSpec priceSpec, long price, int dailyMax, String id, String namespace) {
            this(stack, priceSpec, price, dailyMax, 0, id, namespace);
        }

        public Entry(ItemStack stack, long price, int dailyMax, String id, String namespace) {
            this(stack, PriceSpec.star(price), price, dailyMax, 0, id, namespace);
        }
    }

    public static PriceSpec specOfRule(ShippingRules.Rule rule) {
        if (rule == null) {
            return PriceSpec.ZERO;
        }
        String raw = rule.raw() == null ? "" : rule.raw().trim();
        int eq = raw.indexOf('=');
        if (eq >= 0 && eq + 1 < raw.length()) {
            String right = raw.substring(eq + 1).trim();
            int xIdx = indexOfDailyLimitX(right);
            String pricePart = (xIdx >= 0 ? right.substring(0, xIdx) : right).trim();
            PriceSpec parsed = PriceSpec.parseRuleText(pricePart);
            if (parsed != null && (parsed.isValid() || !parsed.isZero())) {
                return parsed.supportsStar() && rule.price() > 0L
                        ? parsed.withStarPrice(rule.price())
                        : parsed;
            }
        }
        return PriceSpec.star(Math.max(0L, rule.price()));
    }

    private static int indexOfDailyLimitX(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == 'x' || c == 'X') && i > 0) {
                int j = i - 1;
                while (j > 0 && Character.isWhitespace(text.charAt(j))) {
                    j--;
                }
                if (Character.isDigit(text.charAt(j))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 规则原文 → 展开后的可卖物品（缓存，规则没变就不重建）。 */
    private static List<Entry> entryCache = List.of();
    private static String entryKey = "\u0000";
    private static ShippingRules.Parsed ruleCache;
    private static String ruleKey = "\u0000";

    private EditBox searchBox;
    /**
     * 列表里悬停那一行的物品：留到裁剪区之外，按**原版物品悬浮框**画
     *（列表用 scissor 裁过，在里面画会被切掉大半）。
     */
    private ItemStack pendingTooltipStack = ItemStack.EMPTY;
    private Entry pendingTooltipEntry;
    /** 鼠标下的物品（给 JEI 快捷键用：R 看怎么合成、U 看能用来做什么）。 */
    private ItemStack jeiHover = ItemStack.EMPTY;
    private int listScroll;
    private boolean draggingListBar;
    private float listDragOffsetY;
    private boolean onlyOwned;
    private boolean onlyMarked;
    private String category = "";
    private int sort;
    private boolean sortOpen;
    private boolean catOpen;
    private int catScroll;
    private boolean draggingCatBar;
    private float catDragOffsetY;
    private int lastMouseX;
    private int lastMouseY;
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
    public static List<Entry> allEntries() {
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
                PriceSpec spec = specOfRule(rule);
                long star = spec.supportsStar() ? spec.starPrice : rule.price();
                result.add(new Entry(stack, spec, star, rule.dailyMax(), rule.line(), id, namespaceOf(stack)));
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
        list.sort(cn.autoforged.module_shop.client.ModNames.namespaceComparator());
        return list;
    }

    public static String modName(String namespace) {
        return cn.autoforged.module_shop.client.ModNames.displayName(namespace);
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
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim();
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
            if (!query.isEmpty() && !PageKit.matchesItemQuery(entry.stack(), query)) {
                continue;
            }
            this.visible.add(entry);
        }
        switch (this.sort) {
            case 1 -> this.visible.sort(Comparator.comparingInt((Entry e) -> e.priceSpec().supportsPhys() ? 1 : 0)
                    .thenComparingLong(Entry::price));
            case 2 -> this.visible.sort(Comparator.comparingInt((Entry e) -> e.priceSpec().supportsPhys() ? 1 : 0)
                    .thenComparing(Comparator.comparingLong(Entry::price).reversed()));
            case 3 -> this.visible.sort(Comparator.comparing(e -> e.stack().getHoverName().getString()));
            default -> {
            }
        }
    }

    // =========================================================== 初始化

    @Override
    protected void init() {
        super.init();
        this.skia = cn.autoforged.module_shop.client.ui.AtomUi.available();
        this.titleLabelX = 8;
        this.titleLabelY = 8;
        ShippingFavorites.ensureLoaded();
        this.searchBox = new EditBox(this.font, this.leftPos + LIST_X + 5, this.topPos + SEARCH_Y + 4,
                LIST_W - 22, CTRL_H, Component.translatable("gui.module_shop.search.placeholder"));
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

    /** JEI 桥接：鼠标下没有悬停物品时返回空，按键就交回给游戏（不吞键）。 */
    @Override
    public ItemStack jeiHoveredStack(double mouseX, double mouseY) {
        return this.jeiHover == null ? ItemStack.EMPTY : this.jeiHover;
    }

    // =========================================================== 渲染

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        if (this.skia) {
            return;   // Skija 底板在 SkiaContainerBackground 里画（避免被其它模组的暗色背景盖住）
        }
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
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.render(g, mouseX, mouseY, partialTick);
        int x = this.leftPos;
        int y = this.topPos;
        if (this.skia) {
            skOverlay(g, x, y, mouseX, mouseY);
            return;
        }
        this.jeiHover = ItemStack.EMPTY;
        this.rowRects.clear();
        this.starRects.clear();
        this.sortRects.clear();
        this.catRects.clear();
        rebuildVisible();
        // ---- 顶部两行信息（本次结算 / 今日已结算），宽度限制在右侧清单之前 ----
        long value = liveValue();
        String payoutText = liveValueSummary();
        drawSmall(g, x + 8, y + 18, Component.translatable("gui.module_shop.shipping.next_payout",
                payoutText, liveItems()), (value > 0L || hasLivePhysValue()) ? IOStyle.ORANGE : IOStyle.TEXT_2, 152);
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
        // 悬停提示框：用**原版物品悬浮框**（物品名 / 附魔 / NBT / 模组信息 / 提示都会正常显示），
        // 不再自绘那段「单价 · 每天份数 · 背包 · 左键」的拼装文本 —— 单价列表里已经写着，
        // 自绘框反而会盖掉物品本身的信息（而且分隔符在小字里会显示成方块）。
        if (!this.pendingTooltipStack.isEmpty()) {
            List<Component> tooltip = new ArrayList<>(getTooltipFromItem(this.minecraft, this.pendingTooltipStack));
            appendDailyCapTooltip(tooltip);
            // 最后补一行「左键：一键放入」：点一整行直接搬进箱子这个操作，不提示没人会发现。
            tooltip.add(Component.translatable("gui.module_shop.shipping.tip_click")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            g.renderTooltip(this.font, tooltip, java.util.Optional.empty(), this.pendingTooltipStack,
                    mouseX, mouseY);
            this.pendingTooltipStack = ItemStack.EMPTY;
            this.pendingTooltipEntry = null;
        }
        // 槽位悬停提示：必须画在最后（原版 AbstractContainerScreen 先画，会被自绘面板盖住）。
        if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            g.renderTooltip(this.font, this.hoveredSlot.getItem(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        if (this.skia) {
            return;
        }
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
        this.pendingTooltipStack = ItemStack.EMPTY;
        this.pendingTooltipEntry = null;
        // 搜索框
        IOStyle.rounded(g, sx, y + SEARCH_Y, LIST_W, CTRL_H, 0, IOStyle.FIELD_BG);
        boolean searchFocused = this.searchBox != null && this.searchBox.isFocused();
        IOStyle.ring(g, sx, y + SEARCH_Y, LIST_W, CTRL_H, 0,
                searchFocused ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
        // 提示文字自己画：原版只在"未聚焦"时画 hint，这里保证玩家一眼看到该框能打字。
        if (this.searchBox != null && this.searchBox.getValue().isEmpty() && !searchFocused) {
            drawSmall(g, sx + 5, y + SEARCH_Y + 4,
                    Component.translatable("gui.module_shop.search.placeholder"), IOStyle.TEXT_3,
                    LIST_W - 22);
        }
        if (this.searchBox != null && !this.searchBox.getValue().isEmpty()) {
            boolean clrHover = IOStyle.inside(sx + LIST_W - 15, y + SEARCH_Y, 15, CTRL_H, mouseX, mouseY);
            g.drawString(this.font, "×", sx + LIST_W - 10, y + SEARCH_Y + 3,
                    clrHover ? IOStyle.RED : IOStyle.TEXT_3, false);
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
        int rowW = maxScroll > 0 ? LIST_W - 8 : LIST_W - 4;
        int rightReserve = maxScroll > 0 ? 10 : 6;
        g.enableScissor(sx, top, sx + LIST_W, bottom);
        for (int i = this.listScroll; i < this.visible.size() && i - this.listScroll < per; i++) {
            Entry entry = this.visible.get(i);
            int ry = top + (i - this.listScroll) * LIST_ROW_H;
            boolean hover = overlay == null && IOStyle.inside(sx, ry, rowW, LIST_ROW_H - 1, mouseX, mouseY);
            IOStyle.rounded(g, sx, ry, rowW, LIST_ROW_H - 1, 0, hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR);
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
            int used = ClientShopData.shipRuleUsedCount(entry.ruleLine());
            String capText = entry.dailyMax() > 0 ? (used + "/" + entry.dailyMax()) : "∞";
            int capColor = entry.dailyMax() > 0
                    ? (used >= entry.dailyMax() ? IOStyle.RED : (used > 0 ? IOStyle.ORANGE : IOStyle.TEXT_2))
                    : IOStyle.TEXT_3;
            String priceText = PriceDisplay.formatPlain(entry.priceSpec());
            int priceW = (int) (this.font.width(priceText) * 0.8F);
            int capW = (int) (this.font.width(capText) * 0.75F);
            int nameAreaW = Math.max(12, LIST_W - 36 - priceW - capW - rightReserve);
            int nameMax = (int) (nameAreaW / 0.8F);
            String name = entry.stack().getHoverName().getString();
            drawSmall(g, sx + 32, ry + 4, Component.literal(this.font.width(name) > nameMax
                    ? this.font.plainSubstrByWidth(name, Math.max(1, nameMax - 6)) + "…" : name),
                    IOStyle.TEXT, nameAreaW);
            g.pose().pushPose();
            g.pose().translate(sx + LIST_W - rightReserve - 2 - priceW - capW, ry + 4.5F, 0.0F);
            g.pose().scale(0.75F, 0.75F, 1.0F);
            g.drawString(this.font, capText, 0, 0, capColor, false);
            g.pose().popPose();
            g.pose().pushPose();
            g.pose().translate(sx + LIST_W - rightReserve - priceW, ry + 4, 0.0F);
            g.pose().scale(0.8F, 0.8F, 1.0F);
            g.drawString(this.font, priceText, 0, 0, IOStyle.ORANGE, false);
            g.pose().popPose();
            int index = i;
            this.starRects.add(new int[]{sx + 1, ry + 2, 11, LIST_ROW_H - 4, index});
            this.rowRects.add(new int[]{sx + 14, ry, rowW - 14, LIST_ROW_H - 1, index});
            if (hover) {
                // 只记录「悬停的是哪件物品」：悬浮框由 render() 在裁剪区之外用原版渲染画，
                // 列表被 scissor 裁过，在这里直接画会被截掉大半。
                this.pendingTooltipStack = entry.stack();
                this.pendingTooltipEntry = entry;
                this.jeiHover = entry.stack();
            }
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int viewH = per * LIST_ROW_H;
            int trackX = sx + LIST_W - 4;
            boolean barHot = this.draggingListBar
                    || (overlay == null && IOStyle.inside(sx + LIST_W - 10, top, 12, viewH, mouseX, mouseY));
            g.fill(trackX, top, trackX + 2, top + viewH, IOStyle.HAIR);
            int thumbH = Math.min(viewH, Math.max(18, viewH * per / Math.max(1, this.visible.size())));
            int thumbY = top + (viewH - thumbH) * this.listScroll / Math.max(1, maxScroll);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH,
                    this.draggingListBar ? IOStyle.BLUE : (barHot ? IOStyle.TEXT_2 : IOStyle.BLUE));
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
            int catMax = Math.max(0, total - rows);
            this.catScroll = Mth.clamp(this.catScroll, 0, catMax);
            boolean hasCatBar = catMax > 0;
            int rowInnerW = hasCatBar ? pw - 10 : pw - 4;
            IOStyle.card(g, sx, panelY, pw, ph, 0);
            for (int i = this.catScroll; i < Math.min(total, this.catScroll + rows); i++) {
                String ns = i == 0 ? "" : cats.get(i - 1);
                String name = i == 0 ? Component.translatable("gui.module_shop.shipping.cat_all").getString()
                        : modName(ns);
                int ry = panelY + 2 + (i - this.catScroll) * DD_ROW_H;
                boolean current = ns.equals(this.category);
                boolean hover = IOStyle.inside(sx + 2, ry, rowInnerW, DD_ROW_H, mouseX, mouseY);
                IOStyle.rounded(g, sx + 2, ry, rowInnerW, DD_ROW_H, 0,
                        current ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                IOStyle.ring(g, sx + 2, ry, rowInnerW, DD_ROW_H, 0,
                        current ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                int nameMax = (int) ((rowInnerW - 12) / 0.8F);
                drawSmall(g, sx + 8, ry + 3, Component.literal(this.font.width(name) > nameMax
                        ? this.font.plainSubstrByWidth(name, Math.max(1, nameMax - 6)) + "…" : name),
                        current ? IOStyle.BLUE : IOStyle.TEXT, rowInnerW - 12);
                this.catRects.add(new int[]{sx + 2, ry, rowInnerW, DD_ROW_H, i});
            }
            if (hasCatBar) {
                int trackX = sx + pw - 5;
                int trackY = panelY + 3;
                int viewH = rows * DD_ROW_H - 2;
                boolean barHot = this.draggingCatBar
                        || IOStyle.inside(sx + pw - 10, panelY, 12, ph, mouseX, mouseY);
                g.fill(trackX, trackY, trackX + 2, trackY + viewH, IOStyle.HAIR);
                int thumbH = Math.min(viewH, Math.max(18, viewH * rows / Math.max(1, total)));
                int thumbY = trackY + (viewH - thumbH) * this.catScroll / Math.max(1, catMax);
                g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH,
                        this.draggingCatBar ? IOStyle.BLUE : (barHot ? IOStyle.TEXT_2 : IOStyle.TEXT_3));
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

    // =========================================================== Skija 绘制
    // 只换绘制：控件位置与旧版一致，并填充同样的命中矩形（rowRects / starRects / sortRects / catRects），
    // 因此点击、滚轮、一键放入等交互逻辑完全复用旧代码。

    private boolean skia;
    private final cn.autoforged.module_shop.client.ui.render.ItemOverlay listItems =
            new cn.autoforged.module_shop.client.ui.render.ItemOverlay();
    private final cn.autoforged.module_shop.client.ui.widget.Motion.Clock clock =
            new cn.autoforged.module_shop.client.ui.widget.Motion.Clock();
    private final Map<String, Float> skAnim = new java.util.HashMap<>();
    private float skDt;

    private float skHover(String key, boolean on) {
        float v = cn.autoforged.module_shop.client.ui.widget.Motion.approach(skAnim.getOrDefault(key, 0.0F),
                on ? 1.0F : 0.0F, this.skDt, cn.autoforged.module_shop.client.ui.widget.Motion.HOVER_MS);
        skAnim.put(key, v);
        return v;
    }

    private void skBg(GuiGraphics g, int x, int y) {
        this.skDt = this.clock.tick();
        int rows = Math.max(1, (getMenu().binSlots() + 8) / 9);
        SkiaFrame.draw(g, c -> {
            SkiaDialog.bare(c, x, y, this.imageWidth, this.imageHeight);
            Text.drawLine(c, Component.translatable("gui.module_shop.shipping.title", ClientShopData.shipLevel,
                            ClientShopData.shipSlots).getString(), AtomUi.BOLD, Text.BODY, IOStyle.TEXT, x + 8, y + 10,
                    160.5F, Text.Align.LEFT);
            long value = liveValue();
            String payoutText = liveValueSummary();
            Text.drawLine(c, Component.translatable("gui.module_shop.shipping.next_payout",
                            payoutText, liveItems()).getString(), AtomUi.REGULAR, Text.SMALL,
                    (value > 0L || hasLivePhysValue()) ? IOStyle.ORANGE : IOStyle.TEXT_2, x + 8, y + 21, 156.5F, Text.Align.LEFT);
            Text.drawLine(c, (ClientShopData.shipLimit > 0L
                            ? Component.translatable("gui.module_shop.shipping.today_line",
                            ClientShopData.money(ClientShopData.shipSoldToday),
                            ClientShopData.money(ClientShopData.shipLimit))
                            : Component.translatable("gui.module_shop.shipping.today_line_unlimited",
                            ClientShopData.money(ClientShopData.shipSoldToday))).getString(), AtomUi.REGULAR,
                    Text.SMALL, IOStyle.TEXT_2, x + 8, y + 29, 160.5F, Text.Align.LEFT);
            Draw.fill(c, x + 6, y + ShippingBinMenu.BIN_Y - 4, ShippingBinMenu.BIN_X + 156,
                    rows * 18 + 4, 3, IOStyle.CARD);
            for (int i = 0; i < getMenu().binSlots(); i++) {
                SkiaDialog.slotFrame(c, x + ShippingBinMenu.BIN_X + (i % 9) * 18,
                        y + ShippingBinMenu.BIN_Y + (i / 9) * 18);
            }
            Text.drawLine(c, Component.translatable("gui.module_shop.shipping.bag").getString(), AtomUi.REGULAR,
                    Text.SMALL, IOStyle.TEXT_3, x + 8, y + getMenu().inventoryY() - 6, 160.5F, Text.Align.LEFT);
            SkiaDialog.playerSlots(c, x + ShippingBinMenu.BIN_X, y + getMenu().inventoryY());
            // 右侧清单底板 + 搜索框底（搜索文字由原版输入框画在上面）
            SkiaDialog.card(c, x + LIST_X - 6, y + 2, LIST_W + 12, this.imageHeight - 6);
            boolean focused = this.searchBox != null && this.searchBox.isFocused();
            Draw.fill(c, x + LIST_X, y + SEARCH_Y, LIST_W, CTRL_H, 3, IOStyle.FIELD_BG);
            Draw.stroke(c, x + LIST_X, y + SEARCH_Y, LIST_W, CTRL_H, 3, 1, focused ? IOStyle.BLUE : IOStyle.BORDER_DIM);
            SkiaDialog.themeBox(this.searchBox);
            if (this.searchBox != null && this.searchBox.getValue().isEmpty() && !focused) {
                Text.drawLine(c, Component.translatable("gui.module_shop.search.placeholder").getString(),
                        AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3, x + LIST_X + 5, y + SEARCH_Y + CTRL_H / 2.0F,
                        LIST_W - 22 + 0.5F, Text.Align.LEFT);
            }
            if (this.searchBox != null && !this.searchBox.getValue().isEmpty()) {
                boolean clrHover = IOStyle.inside(x + LIST_X + LIST_W - 15, y + SEARCH_Y, 15, CTRL_H,
                        this.lastMouseX, this.lastMouseY);
                Text.drawLine(c, "×", AtomUi.BOLD, Text.BODY, clrHover ? IOStyle.RED : IOStyle.TEXT_3,
                        x + LIST_X + LIST_W - 14, y + SEARCH_Y + CTRL_H / 2.0F, 12, Text.Align.CENTER);
            }
        });
    }

    private void skOverlay(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        this.jeiHover = ItemStack.EMPTY;
        this.pendingTooltipStack = ItemStack.EMPTY;
        this.pendingTooltipEntry = null;
        this.rowRects.clear();
        this.starRects.clear();
        this.sortRects.clear();
        this.catRects.clear();
        rebuildVisible();
        int[] dd = openDropdownRect();
        boolean underDropdown = dd != null && overlaps(dd[0], dd[1], dd[2], dd[3], mouseX, mouseY, 1, 1);
        this.listItems.begin(mouseX, mouseY, !underDropdown);
        SkiaFrame.draw(g, c -> {
            skHighlight(c, x, y);
            skList(c, x, y, mouseX, mouseY, underDropdown);
        });
        this.listItems.flush(g, this.font);
        if (dd != null) {
            SkiaFrame.draw(g, c -> skDropdowns(c, x, y, mouseX, mouseY));
        }
        if (!this.pendingTooltipStack.isEmpty()) {
            List<Component> tooltip = new ArrayList<>(getTooltipFromItem(this.minecraft, this.pendingTooltipStack));
            appendDailyCapTooltip(tooltip);
            tooltip.add(Component.translatable("gui.module_shop.shipping.tip_click")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            g.renderTooltip(this.font, tooltip, java.util.Optional.empty(), this.pendingTooltipStack, mouseX, mouseY);
            this.pendingTooltipStack = ItemStack.EMPTY;
            this.pendingTooltipEntry = null;
        } else if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            g.renderTooltip(this.font, this.hoveredSlot.getItem(), mouseX, mouseY);
        }
    }

    private void skHighlight(io.github.humbleui.skija.Canvas c, int x, int y) {
        if (!this.onlyOwned && !this.onlyMarked) {
            return;
        }
        for (Slot slot : getMenu().slots) {
            if (slot.container != this.minecraft.player.getInventory() || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            int color = 0;
            if (this.onlyMarked && ShippingFavorites.contains(itemId(stack))) {
                color = IOStyle.ORANGE;
            } else if (this.onlyOwned && sellable(stack)) {
                color = IOStyle.GREEN;
            }
            if (color != 0) {
                Draw.stroke(c, x + slot.x - 1, y + slot.y - 1, 18, 18, 2, 1.2F, color);
                Draw.stroke(c, x + slot.x - 2.5F, y + slot.y - 2.5F, 21, 21, 3, 1, Draw.alpha(color, 0.35F));
            }
        }
    }

    private void skControl(io.github.humbleui.skija.Canvas c, String key, float x, float y, float w, float h,
                           String label, boolean active, boolean chevron, boolean open, int mouseX, int mouseY) {
        float hv = skHover(key, mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h);
        float on = skHover(key + ":on", active);
        Draw.fill(c, x, y, w, h, 3, Draw.lerp(Draw.lerp(IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER, hv),
                Draw.alpha(IOStyle.BLUE, 0.22F), on));
        Draw.stroke(c, x, y, w, h, 3, 1, Draw.lerp(Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, hv),
                IOStyle.BLUE, on));
        float textW = w - 8 - (chevron ? 10 : 0);
        Text.drawLine(c, label, AtomUi.REGULAR, Text.SMALL, active ? IOStyle.BLUE : IOStyle.TEXT, x + 4,
                y + h / 2.0F, textW + 0.5F, Text.Align.LEFT);
        if (chevron) {
            float cx = x + w - 7;
            float cy = y + h / 2.0F;
            float d = open ? -1.5F : 1.5F;
            Draw.line(c, cx - 3, cy - d, cx, cy + d, 1.2F, IOStyle.TEXT_2);
            Draw.line(c, cx, cy + d, cx + 3, cy - d, 1.2F, IOStyle.TEXT_2);
        }
    }

    private void skList(io.github.humbleui.skija.Canvas c, int x, int y, int mouseX, int mouseY,
                        boolean underDropdown) {
        int sx = x + LIST_X;
        int halfW = (LIST_W - 4) / 2;
        int sortX = sx + halfW + 4;
        String catName = this.category.isEmpty()
                ? Component.translatable("gui.module_shop.shipping.cat_all").getString() : modName(this.category);
        skControl(c, "cat", sx, y + ROW2_Y, halfW, CTRL_H, catName, this.catOpen, true, this.catOpen, mouseX, mouseY);
        skControl(c, "sort", sortX, y + ROW2_Y, halfW, CTRL_H,
                Component.translatable("gui.module_shop.shipping.sort_label", sortName()).getString(), this.sortOpen,
                true, this.sortOpen, mouseX, mouseY);
        skControl(c, "owned", sx, y + ROW3_Y, halfW, CTRL_H,
                Component.translatable("gui.module_shop.shipping.only_owned").getString(), this.onlyOwned, false,
                false, mouseX, mouseY);
        skControl(c, "marked", sortX, y + ROW3_Y, halfW, CTRL_H,
                Component.translatable("gui.module_shop.shipping.only_marked").getString(), this.onlyMarked, false,
                false, mouseX, mouseY);
        int top = y + LIST_TOP;
        int countY = y + this.imageHeight - 16;
        int bottom = countY - 4;
        int per = Math.max(1, (bottom - top) / LIST_ROW_H);
        if (this.visible.isEmpty()) {
            Text.drawLine(c, Component.translatable("gui.module_shop.shipping.list_empty").getString(),
                    AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3, sx, top + 14, LIST_W + 0.5F, Text.Align.CENTER);
            return;
        }
        int maxScroll = Math.max(0, this.visible.size() - per);
        this.listScroll = Mth.clamp(this.listScroll, 0, maxScroll);
        float rowW = maxScroll > 0 ? LIST_W - 8.0F : LIST_W - 4.0F;
        float rightReserve = maxScroll > 0 ? 10.0F : 7.0F;
        int saved = c.save();
        Draw.clip(c, sx, top, LIST_W, bottom - top);
        this.listItems.pushClip(sx, top, LIST_W, bottom - top);
        for (int i = this.listScroll; i < this.visible.size() && i - this.listScroll < per; i++) {
            Entry entry = this.visible.get(i);
            int ry = top + (i - this.listScroll) * LIST_ROW_H;
            boolean hover = !underDropdown && IOStyle.inside(sx, ry, (int) rowW, LIST_ROW_H - 1, mouseX, mouseY);
            float hv = skHover("row:" + entry.id(), hover);
            Draw.fill(c, sx, ry, rowW, LIST_ROW_H - 1, 2, Draw.lerp(IOStyle.SIDEBAR, IOStyle.ROW_HOVER, hv));
            boolean marked = ShippingFavorites.contains(entry.id());
            boolean starHover = !underDropdown && IOStyle.inside(sx + 1, ry + 2, 11, LIST_ROW_H - 4, mouseX, mouseY);
            Text.drawLine(c, marked ? "★" : "☆", AtomUi.REGULAR, Text.BODY,
                    marked ? IOStyle.ORANGE : (starHover ? IOStyle.TEXT : IOStyle.TEXT_3), sx + 2, ry + LIST_ROW_H / 2.0F,
                    10, Text.Align.CENTER);
            this.listItems.add(entry.stack(), sx + 14, ry, 15, true);
            int used = ClientShopData.shipRuleUsedCount(entry.ruleLine());
            String capText = entry.dailyMax() > 0 ? (used + "/" + entry.dailyMax()) : "∞";
            int capColor = entry.dailyMax() > 0
                    ? (used >= entry.dailyMax() ? IOStyle.RED : (used > 0 ? IOStyle.ORANGE : IOStyle.TEXT_2))
                    : IOStyle.TEXT_3;
            float pw = Math.min(62.0F, Math.max(22.0F,
                    Text.width(PriceDisplay.formatPlain(entry.priceSpec()), AtomUi.BOLD, Text.SMALL) + 14.0F));
            float capW = Math.min(38.0F, Text.width(capText, AtomUi.REGULAR, Text.SMALL) + 2.5F);
            float maxCols = Math.max(36.0F, LIST_W - rightReserve - 36.0F - 24.0F);
            if (pw + capW + 3.0F > maxCols) {
                pw = Math.max(22.0F, maxCols - capW - 3.0F);
            }
            float priceX = sx + LIST_W - rightReserve - pw;
            float capX = priceX - 3.0F - capW;
            PriceDisplay.drawCell(c, this.listItems, entry.priceSpec(), priceX,
                    ry + LIST_ROW_H / 2.0F, pw + 0.5F, Text.Align.RIGHT, IOStyle.ORANGE, false);
            Text.drawLine(c, capText, AtomUi.REGULAR, Text.SMALL, capColor,
                    capX, ry + LIST_ROW_H / 2.0F, capW + 0.5F, Text.Align.RIGHT);
            Text.drawLine(c, entry.stack().getHoverName().getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT,
                    sx + 32, ry + LIST_ROW_H / 2.0F, Math.max(12.0F, capX - 3.0F - (sx + 32)) + 0.5F, Text.Align.LEFT);
            this.starRects.add(new int[]{sx + 1, ry + 2, 11, LIST_ROW_H - 4, i});
            this.rowRects.add(new int[]{sx + 14, ry, (int) rowW - 14, LIST_ROW_H - 1, i});
            if (hover) {
                this.pendingTooltipStack = entry.stack();
                this.pendingTooltipEntry = entry;
                this.jeiHover = entry.stack();
            }
        }
        this.listItems.popClip();
        c.restoreToCount(saved);
        if (maxScroll > 0) {
            float viewH = per * LIST_ROW_H;
            float thumbH = Math.min(viewH, Math.max(18.0F, viewH * per / (float) Math.max(1, this.visible.size())));
            float thumbY = top + (viewH - thumbH) * this.listScroll / (float) Math.max(1, maxScroll);
            boolean barHot = !underDropdown && (this.draggingListBar
                    || IOStyle.inside(sx + LIST_W - 10, top, 12, (int) viewH, mouseX, mouseY));
            float trackX = sx + LIST_W - 4.5F;
            Draw.fill(c, trackX, top, 2.5F, viewH, 1.25F, IOStyle.HAIR);
            Draw.fill(c, trackX, thumbY, barHot ? 3.0F : 2.5F, thumbH, 1.5F,
                    this.draggingListBar ? IOStyle.BLUE : (barHot ? IOStyle.TEXT_2 : IOStyle.TEXT_3));
        }
        Text.drawLine(c, Component.translatable("gui.module_shop.shipping.list_count", this.visible.size(),
                        allEntries().size()).getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3, sx, countY + 4,
                LIST_W + 0.5F, Text.Align.LEFT);
    }

    private void skDropdowns(io.github.humbleui.skija.Canvas c, int x, int y, int mouseX, int mouseY) {
        int sx = x + LIST_X;
        int panelY = y + LIST_TOP;
        List<String> labels = new ArrayList<>();
        int current;
        int first = 0;
        int rows;
        int total = 4;
        if (this.catOpen) {
            List<String> cats = categories();
            total = cats.size() + 1;
            rows = Math.min(DD_CAT_ROWS, total);
            this.catScroll = Mth.clamp(this.catScroll, 0, Math.max(0, total - rows));
            first = this.catScroll;
            current = 0;
            for (int i = 0; i < total; i++) {
                String ns = i == 0 ? "" : cats.get(i - 1);
                labels.add(i == 0 ? Component.translatable("gui.module_shop.shipping.cat_all").getString()
                        : modName(ns));
                if (ns.equals(this.category)) {
                    current = i;
                }
            }
            this.catRect[0] = sx;
            this.catRect[1] = panelY;
            this.catRect[2] = DD_CAT_W;
            this.catRect[3] = rows * DD_ROW_H + 4;
        } else {
            rows = 4;
            current = this.sort;
            labels.add(Component.translatable("gui.module_shop.shipping.sort_default").getString());
            labels.add(Component.translatable("gui.module_shop.shipping.sort_price_asc").getString());
            labels.add(Component.translatable("gui.module_shop.shipping.sort_price_desc").getString());
            labels.add(Component.translatable("gui.module_shop.shipping.sort_name").getString());
        }
        int pw = LIST_W;
        int ph = rows * DD_ROW_H + 4;
        int catMax = this.catOpen ? Math.max(0, total - rows) : 0;
        boolean hasCatBar = catMax > 0;
        int rowInnerW = hasCatBar ? pw - 10 : pw - 4;
        Draw.fill(c, sx + 1, panelY + 2, pw, ph, 4, 0x50000000);
        Draw.fill(c, sx, panelY, pw, ph, 4, IOStyle.SHEET);
        Draw.stroke(c, sx, panelY, pw, ph, 4, 1, IOStyle.BORDER);
        for (int i = first; i < Math.min(labels.size(), first + rows); i++) {
            int ry = panelY + 2 + (i - first) * DD_ROW_H;
            boolean cur = i == current;
            boolean hover = IOStyle.inside(sx + 2, ry, rowInnerW, DD_ROW_H, mouseX, mouseY);
            if (cur || hover) {
                Draw.fill(c, sx + 2, ry, rowInnerW, DD_ROW_H, 3, cur ? IOStyle.BLUE_TINT : IOStyle.ROW_HOVER);
            }
            Text.drawLine(c, labels.get(i), AtomUi.REGULAR, Text.SMALL, cur ? IOStyle.BLUE : IOStyle.TEXT, sx + 8,
                    ry + DD_ROW_H / 2.0F, (rowInnerW - 12) + 0.5F, Text.Align.LEFT);
            if (this.catOpen) {
                this.catRects.add(new int[]{sx + 2, ry, rowInnerW, DD_ROW_H, i});
            } else {
                this.sortRects.add(new int[]{sx + 2, ry, rowInnerW, DD_ROW_H, i});
            }
        }
        if (hasCatBar) {
            float trackY = panelY + 3.0F;
            float viewH = rows * DD_ROW_H - 2.0F;
            float thumbH = Math.min(viewH, Math.max(18.0F, viewH * rows / (float) Math.max(1, total)));
            float thumbY = trackY + (viewH - thumbH) * this.catScroll / (float) Math.max(1, catMax);
            boolean barHot = this.draggingCatBar
                    || IOStyle.inside(sx + pw - 10, panelY, 12, ph, mouseX, mouseY);
            float trackX = sx + pw - 5.5F;
            Draw.fill(c, trackX, trackY, 2.5F, viewH, 1.25F, IOStyle.HAIR);
            Draw.fill(c, trackX, thumbY, barHot ? 3.0F : 2.5F, thumbH, 1.5F,
                    this.draggingCatBar ? IOStyle.BLUE : (barHot ? IOStyle.TEXT_2 : IOStyle.TEXT_3));
        }
    }

    // =========================================================== 交互

    private int listPer() {
        int top = this.topPos + LIST_TOP;
        int bottom = this.topPos + this.imageHeight - 20;
        return Math.max(1, (bottom - top) / LIST_ROW_H);
    }

    private int listMaxScroll() {
        return Math.max(0, this.visible.size() - listPer());
    }

    private void applyListDrag(double mouseY) {
        int per = listPer();
        int maxScroll = listMaxScroll();
        if (maxScroll <= 0) {
            this.listScroll = 0;
            return;
        }
        float top = this.topPos + LIST_TOP;
        float viewH = per * LIST_ROW_H;
        float thumbH = Math.min(viewH, Math.max(18.0F, viewH * per / (float) Math.max(1, this.visible.size())));
        float span = Math.max(1.0F, viewH - thumbH);
        float targetTop = Mth.clamp((float) mouseY - this.listDragOffsetY, top, top + span);
        this.listScroll = Mth.clamp(Math.round(((targetTop - top) / span) * maxScroll), 0, maxScroll);
    }

    private void applyCatDrag(double mouseY) {
        int total = categories().size() + 1;
        int rows = Math.min(DD_CAT_ROWS, total);
        int catMax = Math.max(0, total - rows);
        if (catMax <= 0) {
            this.catScroll = 0;
            return;
        }
        float trackY = this.topPos + LIST_TOP + 3.0F;
        float viewH = rows * DD_ROW_H - 2.0F;
        float thumbH = Math.min(viewH, Math.max(18.0F, viewH * rows / (float) Math.max(1, total)));
        float span = Math.max(1.0F, viewH - thumbH);
        float targetTop = Mth.clamp((float) mouseY - this.catDragOffsetY, trackY, trackY + span);
        this.catScroll = Mth.clamp(Math.round(((targetTop - trackY) / span) * catMax), 0, catMax);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.leftPos;
        int y = this.topPos;
        int sx = x + LIST_X;
        int halfW = (LIST_W - 4) / 2;
        int sortX = sx + halfW + 4;
        if (button == 0 && this.searchBox != null && !this.searchBox.getValue().isEmpty()
                && IOStyle.inside(sx + LIST_W - 15, y + SEARCH_Y, 15, CTRL_H, mouseX, mouseY)) {
            Bricks.click();
            this.searchBox.setValue("");
            this.listScroll = 0;
            setFocused(this.searchBox);
            this.searchBox.setFocused(true);
            return true;
        }
        if ((this.catOpen || this.sortOpen) && button == 0) {
            if (this.catOpen) {
                int total = categories().size() + 1;
                int rows = Math.min(DD_CAT_ROWS, total);
                int catMax = Math.max(0, total - rows);
                int panelY = y + LIST_TOP;
                int ph = rows * DD_ROW_H + 4;
                if (catMax > 0 && IOStyle.inside(sx + DD_CAT_W - 10, panelY, 12, ph, mouseX, mouseY)) {
                    float trackY = panelY + 3.0F;
                    float viewH = rows * DD_ROW_H - 2.0F;
                    float thumbH = Math.min(viewH, Math.max(18.0F, viewH * rows / (float) Math.max(1, total)));
                    float thumbY = trackY + (viewH - thumbH) * this.catScroll / (float) Math.max(1, catMax);
                    this.catDragOffsetY = (mouseY >= thumbY && mouseY <= thumbY + thumbH)
                            ? (float) mouseY - thumbY : thumbH * 0.5F;
                    this.draggingCatBar = true;
                    if (mouseY < thumbY || mouseY > thumbY + thumbH) {
                        applyCatDrag(mouseY);
                    }
                    return true;
                }
            }
            for (int[] rect : this.catRects) {
                if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                    Bricks.click();
                    List<String> cats = categories();
                    this.category = rect[4] == 0 ? "" : cats.get(rect[4] - 1);
                    this.catOpen = false;
                    this.draggingCatBar = false;
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
            this.draggingCatBar = false;
            return true;
        }
        if (button == 0 && this.searchBox != null
                && IOStyle.inside(sx, y + SEARCH_Y, LIST_W - 15, CTRL_H, mouseX, mouseY)) {
            setFocused(this.searchBox);
            this.searchBox.setFocused(true);
            this.searchBox.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (button == 0 && IOStyle.inside(sx, y + ROW2_Y, halfW, CTRL_H, mouseX, mouseY)) {
            Bricks.click();
            this.catOpen = !this.catOpen;
            this.sortOpen = false;
            this.draggingCatBar = false;
            this.catScroll = 0;
            return true;
        }
        if (button == 0 && IOStyle.inside(sortX, y + ROW2_Y, halfW, CTRL_H, mouseX, mouseY)) {
            Bricks.click();
            this.sortOpen = !this.sortOpen;
            this.catOpen = false;
            this.draggingCatBar = false;
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
        int per = listPer();
        int maxScroll = listMaxScroll();
        int listTop = y + LIST_TOP;
        int viewH = per * LIST_ROW_H;
        if (button == 0 && maxScroll > 0 && IOStyle.inside(sx + LIST_W - 10, listTop, 12, viewH, mouseX, mouseY)) {
            float thumbH = Math.min(viewH, Math.max(18.0F, viewH * per / (float) Math.max(1, this.visible.size())));
            float thumbY = listTop + (viewH - thumbH) * this.listScroll / (float) Math.max(1, maxScroll);
            this.listDragOffsetY = (mouseY >= thumbY && mouseY <= thumbY + thumbH)
                    ? (float) mouseY - thumbY : thumbH * 0.5F;
            this.draggingListBar = true;
            if (mouseY < thumbY || mouseY > thumbY + thumbH) {
                applyListDrag(mouseY);
            }
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

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            if (this.draggingCatBar) {
                applyCatDrag(mouseY);
                return true;
            }
            if (this.draggingListBar) {
                applyListDrag(mouseY);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (this.draggingCatBar || this.draggingListBar)) {
            this.draggingCatBar = false;
            this.draggingListBar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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
                int total = categories().size() + 1;
                int rows = Math.min(DD_CAT_ROWS, total);
                int catMax = Math.max(0, total - rows);
                this.catScroll = Mth.clamp(this.catScroll + (scrollY > 0 ? -1 : 1), 0, catMax);
                return true;
            }
            if (mouseX >= this.leftPos + LIST_X - 6 && mouseX < this.leftPos + LIST_X + LIST_W
                    && mouseY >= this.topPos + LIST_TOP) {
                this.listScroll = Mth.clamp(this.listScroll + (scrollY > 0 ? -1 : 1), 0, listMaxScroll());
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                && this.searchBox != null && this.searchBox.isFocused() && !this.searchBox.getValue().isEmpty()) {
            this.searchBox.setValue("");
            this.listScroll = 0;
            return true;
        }
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

    /** 本次结算金额（星币部分，本地实时预估）。 */
    private long liveValue() {
        long[] starOut = {0L};
        computeLiveLedger(starOut, null);
        return starOut[0];
    }

    private boolean hasLivePhysValue() {
        Map<String, Long> physOut = new LinkedHashMap<>();
        computeLiveLedger(new long[1], physOut);
        return !physOut.isEmpty();
    }

    private String liveValueSummary() {
        long[] starOut = {0L};
        Map<String, Long> physOut = new LinkedHashMap<>();
        computeLiveLedger(starOut, physOut);
        return PriceDisplay.formatLedgerSummary(starOut[0], physOut);
    }

    private void computeLiveLedger(long[] starOut, Map<String, Long> physOut) {
        long starTotal = 0L;
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
                int already = counted.containsKey(key) ? counted.get(key) : ClientShopData.shipRuleUsedCount(rule.line());
                int allowed = rule.dailyMax() <= 0 ? stack.getCount() : Math.max(0, rule.dailyMax() - already);
                int count = Math.min(stack.getCount(), allowed);
                if (count > 0) {
                    counted.put(key, already + count);
                    PriceSpec spec = specOfRule(rule);
                    if (spec.supportsStar()) {
                        starTotal += spec.starPrice * count;
                    }
                    if (physOut != null && spec.supportsPhys()) {
                        physOut.merge(spec.physTierKey, spec.physCount * (long) count, Long::sum);
                    }
                }
                break;
            }
        }
        long limit = ClientShopData.shipLimit;
        if (limit > 0L) {
            starTotal = Math.min(starTotal, Math.max(0L, limit - ClientShopData.shipSoldToday));
        }
        starOut[0] = starTotal;
    }

    private void appendDailyCapTooltip(List<Component> tooltip) {
        if (this.pendingTooltipEntry == null) {
            return;
        }
        String label = Component.translatable("gui.module_shop.v2.col.daily_cap").getString();
        if (this.pendingTooltipEntry.dailyMax() > 0) {
            int used = ClientShopData.shipRuleUsedCount(this.pendingTooltipEntry.ruleLine());
            boolean full = used >= this.pendingTooltipEntry.dailyMax();
            tooltip.add(Component.literal(label + ": " + used + "/" + this.pendingTooltipEntry.dailyMax())
                    .withStyle(full ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.YELLOW));
        } else {
            tooltip.add(Component.literal(label + ": ∞").withStyle(net.minecraft.ChatFormatting.GRAY));
        }
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
