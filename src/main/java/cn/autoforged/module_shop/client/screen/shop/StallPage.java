package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.StallClient;
import cn.autoforged.module_shop.client.screen.BillScreen;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.stall.StallListScreen;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.StallEntry;
import cn.autoforged.module_shop.shop.auction.AuctionCategory;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 玩家商店页（Skija 版）。顶部切换「我的商店 / 商店大厅」，右上角「查看摊位」（原有入口，打开有摊位的玩家列表）。
 *
 * <h3>我的商店（原「我的摊位」，玩法不变）</h3>
 * 两列网格，至少 12 格。每格：物品、价格、限时剩余、库存 / 售罄、累计已售；右侧「+」补货、「×」下架。
 * 点击格子改价。底部：上架商品（从背包选择）、交易记录（只看摊位买卖）。
 *
 * <h3>商店大厅</h3>
 * 汇总所有玩家摊位上有货的商品：搜索（名称 / 物品 ID / 卖家）、分类、按最新 / 价格排序、分页（滚轮翻页）。
 * 点商品直接进入卖家的摊位并选中这件商品，在摊位详情里调数量、看总价、购买。
 */
public final class StallPage implements ShopPage {
    private static final int COLS = 2;
    private static final int MIN_CELLS = 12;
    private static final float CELL_H = 26.0F;
    private static final float GAP_X = 6.0F;
    private static final float GAP_Y = 4.0F;
    private static final float CTRL_H = 16.0F;
    private static final float MENU_ROW = 14.0F;

    private static float savedScroll;

    private final ScrollController scroll = new ScrollController();
    private boolean restored;
    private final List<Button> removeButtons = new ArrayList<>();
    private final List<Button> restockButtons = new ArrayList<>();
    private float[] hoverT = new float[0];

    private final Button tabMine = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.stall.tab_mine").getString(), null).radius(2.0F)
            .fontSize(Text.SMALL);
    private final Button tabHall = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.stall.tab_hall").getString(), null).radius(2.0F)
            .fontSize(Text.SMALL);
    private final Button browse = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.stall_browse").getString(), null).radius(2.0F)
            .fontSize(Text.SMALL);
    private final Button create = new Button(Button.Kind.ACCENT,
            () -> Component.translatable("gui.module_shop.stall_create").getString(), null).radius(3.0F);
    private final Button records = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.stall.records").getString(), null).radius(3.0F);

    // 大厅控件
    private SkiaTextField search;
    private boolean catOpen;
    private boolean sortOpen;
    private final Button catBtn = new Button(Button.Kind.NORMAL, () -> Component.translatable(
            "gui.module_shop.auction.cat_prefix", Component.translatable(StallClient.category.langKey())).getString(),
            null).radius(2.0F).fontSize(Text.SMALL);
    private final Button sortBtn = new Button(Button.Kind.NORMAL, () -> Component.translatable(
            "gui.module_shop.auction.sort_prefix", Component.translatable(StallClient.sortKey(StallClient.sort)))
            .getString(), null).radius(2.0F).fontSize(Text.SMALL);
    private final Button prevBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.page_prev").getString(), null).radius(2.0F)
            .fontSize(Text.SMALL);
    private final Button nextBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.page_next").getString(), null).radius(2.0F)
            .fontSize(Text.SMALL);
    private float menuX, menuY, menuW, menuH;
    private float hallX, hallY, hallW, hallH, hallCardW, hallCardH;
    private int hallCols = 1, hallPer = 1;
    private List<StallClient.Item> hallShown = List.of();

    private float gridX, gridY, gridW, gridH, cellW;
    private int shownEntries;

    @Override
    public String id() {
        return "stall";
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 40)
                .placeholder(Component.translatable("gui.module_shop.stall.search_hint").getString())
                .fontSize(Text.SMALL)
                .onChange(text -> {
                    StallClient.search = text;
                    StallClient.page = 0;
                });
        search.setValue(StallClient.search == null ? "" : StallClient.search);
        host.registerInput(search.editBox());
    }

    @Override
    public void render(Canvas canvas, PageFrame f) {
        // ---- 顶部：视图切换 + 查看摊位 ----
        float x = f.x();
        float y = f.y();
        tabMine.kind(StallClient.hallView ? Button.Kind.NORMAL : Button.Kind.ACCENT)
                .onClick(() -> {
                    StallClient.hallView = false;
                    closeMenus();
                }).bounds(x, y, 70.0F, CTRL_H);
        tabMine.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        tabHall.kind(StallClient.hallView ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> StallClient.hallView = true).bounds(x + 74.0F, y, 70.0F, CTRL_H);
        tabHall.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        browse.onClick(() -> f.host().action("open_avatar", null)).bounds(x + f.w() - 70.0F, y, 70.0F, CTRL_H);
        browse.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        if (StallClient.hallView) {
            renderHall(canvas, f, y + CTRL_H + 4.0F);
        } else {
            search.visible(false);
            renderMine(canvas, f, y + CTRL_H + 4.0F);
        }
    }

    // =================================================================== 我的商店

    private void renderMine(Canvas canvas, PageFrame f, float top) {
        List<StallEntry> stall = ClientShopData.stall;
        int cells = Math.max(MIN_CELLS, stall.size() + (stall.size() % COLS));
        int rows = (cells + COLS - 1) / COLS;
        gridX = f.x();
        gridY = top;
        gridW = f.w();
        gridH = f.listBottom() - gridY;
        scroll.setContent(rows * (CELL_H + GAP_Y) - GAP_Y, gridH);
        if (!restored) {
            scroll.scrollTo(savedScroll, false);
            restored = true;
        }
        scroll.update();
        savedScroll = scroll.offset();
        cellW = (gridW - (scroll.canScroll() ? 6.0F : 0.0F) - GAP_X * (COLS - 1)) / COLS;
        while (removeButtons.size() < stall.size()) {
            removeButtons.add(new Button(Button.Kind.DANGER, "×", null).radius(2.0F).fontSize(Text.BODY));
            restockButtons.add(new Button(Button.Kind.NORMAL, "+", null).radius(2.0F).fontSize(Text.BODY));
        }
        if (hoverT.length != cells) {
            hoverT = new float[cells];
        }
        shownEntries = stall.size();

        int saved = canvas.save();
        f.host().items().pushClip(gridX, gridY, gridW, gridH);
        try {
            Draw.clip(canvas, gridX - 1.0F, gridY, gridW + 2.0F, gridH);
            float off = scroll.offset();
            for (int i = 0; i < cells; i++) {
                float cx = gridX + (i % COLS) * (cellW + GAP_X);
                float cy = gridY + (i / COLS) * (CELL_H + GAP_Y) - off;
                if (cy + CELL_H < gridY || cy > gridY + gridH) {
                    if (i < stall.size()) {
                        removeButtons.get(i).visible(false);
                        restockButtons.get(i).visible(false);
                    }
                    continue;
                }
                StallEntry e = i < stall.size() ? stall.get(i) : null;
                boolean hover = e != null && !scroll.isDragging()
                        && f.mouseX() >= cx && f.mouseX() < cx + cellW - 40.0F
                        && f.mouseY() >= Math.max(cy, gridY) && f.mouseY() < Math.min(cy + CELL_H, gridY + gridH);
                hoverT[i] = Motion.approach(hoverT[i], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
                if (e == null) {
                    Draw.fill(canvas, cx, cy, cellW, CELL_H, 3.0F, IOStyle.SIDEBAR);
                    Draw.stroke(canvas, cx, cy, cellW, CELL_H, 3.0F, 1.0F, IOStyle.BORDER_DIM);
                    PageKit.slot(canvas, f.host(), null, cx + 4.0F, cy + 4.0F);
                    continue;
                }
                PageKit.hoverCard(canvas, cx, cy, cellW, CELL_H, hoverT[i]);
                PageKit.slot(canvas, f.host(), e.stack, cx + 4.0F, cy + 4.0F);
                drawCellText(canvas, e, cx + 27.0F, cy, cx + cellW - 42.0F);
                boolean inView = cy >= gridY - 1.0F && cy + CELL_H <= gridY + gridH + 1.0F;
                CompoundTag idx = entryTag(i, e);
                Button plus = restockButtons.get(i);
                final int slotIndex = i;
                final StallEntry entry = e;
                plus.onClick(() -> f.host().action("stall_restock_gui", restockTag(slotIndex, entry)))
                        .bounds(cx + cellW - 38.0F, cy + (CELL_H - 14.0F) / 2.0F, 16.0F, 14.0F)
                        .visible(inView);
                plus.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
                Button xb = removeButtons.get(i);
                xb.onClick(() -> f.host().action("remove_stall", idx))
                        .bounds(cx + cellW - 20.0F, cy + (CELL_H - 14.0F) / 2.0F, 17.0F, 14.0F)
                        .visible(inView);
                xb.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            }
        } finally {
            f.host().items().popClip();
            canvas.restoreToCount(saved);
        }
        scroll.drawBar(canvas, gridX, gridY, gridW, gridH, f.mouseX(), f.mouseY(), f.dtMs());

        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        create.onClick(() -> Nav.open(new StallListScreen()))
                .bounds(f.x() + 4.0F, barMid - 8.0F, 80.0F, 16.0F);
        records.onClick(() -> Nav.open(BillScreen.stallRecords()))
                .bounds(f.x() + 90.0F, barMid - 8.0F, 70.0F, 16.0F);
        create.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        records.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        int totalSold = 0;
        for (StallEntry e : stall) {
            totalSold += e.sold;
        }
        Text.drawLine(canvas, Component.translatable("gui.module_shop.stall.mine_summary", stall.size(),
                        StallClient.slots, totalSold).getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                f.x() + 166.0F, barMid, f.w() - 170.0F, Text.Align.RIGHT);
    }

    /** 两行文字：价格（+ 剩余时间）/ 库存或售罄（+ 累计已售）。 */
    private static void drawCellText(Canvas canvas, StallEntry e, float left, float cy, float right) {
        float w = right - left;
        float top = cy + 8.5F;
        float bottom = cy + 18.5F;
        float pw = Text.drawLine(canvas, ClientShopData.money(e.price), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                left, top, w, Text.Align.LEFT);
        String remain = PageKit.remainText(ClientShopData.stallExpireMode, ClientShopData.stallExpireAmount,
                e.listedGame, e.listedReal);
        if (remain != null) {
            float rw = Text.width(remain, AtomUi.REGULAR, Text.SMALL);
            if (left + pw + 4.0F + rw <= right) {
                Text.drawLine(canvas, remain, AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE, right - rw, top, rw + 1.0F,
                        Text.Align.LEFT);
            }
        }
        String stock = e.stock <= 0
                ? Component.translatable("gui.module_shop.stall_sold_out").getString()
                : Component.translatable("gui.module_shop.stall_stock", e.stock).getString();
        float sw = Text.drawLine(canvas, stock, AtomUi.REGULAR, Text.SMALL, e.stock <= 0 ? IOStyle.RED : IOStyle.TEXT_2,
                left, bottom, w, Text.Align.LEFT);
        float soldX = left + sw + 6.0F;
        if (soldX < right - 10.0F) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.stall_sold_count", e.sold).getString(),
                    AtomUi.REGULAR, Text.SMALL, IOStyle.BLUE_HOVER, soldX, bottom, right - soldX, Text.Align.LEFT);
        }
    }

    /** 下标 + 商品 id（服务端优先按 id 定位，下标只给旧逻辑兜底）。 */
    private static CompoundTag entryTag(int i, StallEntry e) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", i);
        if (e != null && e.order > 0L) {
            tag.putLong("id", e.order);
        }
        return tag;
    }

    /** 补货：默认数量 = 背包里同样物品的数量（不超过剩余可补的库存）。 */
    private static CompoundTag restockTag(int i, StallEntry e) {
        CompoundTag tag = entryTag(i, e);
        int have = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            var inv = mc.player.getInventory();
            for (int s = 0; s < inv.getContainerSize(); s++) {
                var st = inv.getItem(s);
                if (!st.isEmpty() && net.minecraft.world.item.ItemStack.isSameItemSameComponents(st, e.stack)) {
                    have += st.getCount();
                }
            }
        }
        int room = Math.max(0, StallClient.maxStock - e.stock);
        tag.putString("default_quantity", Integer.toString(Math.max(1, Math.min(have, room))));
        tag.putString("info", Component.translatable("gui.module_shop.stall.restock_info", e.stack.getHoverName(),
                e.stock, have, room).getString());
        return tag;
    }

    // =================================================================== 商店大厅

    private void renderHall(Canvas canvas, PageFrame f, float top) {
        StallClient.ensureMarket();
        float x = f.x();
        float w = f.w();
        float catW = 82.0F;
        float sortW = 96.0F;
        float searchW = w - catW - sortW - 8.0F;
        search.bounds(x, top, searchW, CTRL_H).visible(true);
        search.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float catX = x + searchW + 4.0F;
        catBtn.kind(catOpen || StallClient.category != AuctionCategory.ALL ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> {
                    catOpen = !catOpen;
                    sortOpen = false;
                }).bounds(catX, top, catW, CTRL_H);
        catBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float sortX = catX + catW + 4.0F;
        sortBtn.kind(sortOpen ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> {
                    sortOpen = !sortOpen;
                    catOpen = false;
                }).bounds(sortX, top, sortW, CTRL_H);
        sortBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());

        boolean menu = catOpen || sortOpen;
        hallX = x;
        hallY = top + CTRL_H + 4.0F;
        hallW = w;
        hallH = f.listBottom() - hallY;
        hallCols = w >= 330.0F ? 4 : 3;
        float gap = 3.0F;
        hallCardW = (float) Math.floor((w - gap * (hallCols - 1)) / hallCols);
        hallCardH = 36.0F;
        int rows = Math.max(1, (int) ((hallH + gap) / (hallCardH + gap)));
        hallPer = rows * hallCols;
        hallShown = StallClient.filtered();
        int pages = Math.max(1, (hallShown.size() + hallPer - 1) / hallPer);
        StallClient.page = Math.max(0, Math.min(StallClient.page, pages - 1));
        if (hallShown.isEmpty()) {
            String key = StallClient.market.isEmpty() ? (StallClient.localVersion == 0L ? "gui.module_shop.loading"
                    : "gui.module_shop.stall.hall_empty") : "gui.module_shop.auction.no_match";
            PageKit.emptyText(canvas, Component.translatable(key).getString(), x, hallY + hallH / 2.0F, w);
        }
        for (int i = 0; i < hallPer; i++) {
            int index = StallClient.page * hallPer + i;
            if (index >= hallShown.size()) {
                break;
            }
            StallClient.Item it = hallShown.get(index);
            float cx = hallX + (i % hallCols) * (hallCardW + gap);
            float cy = hallY + (i / hallCols) * (hallCardH + gap);
            boolean hover = !menu && f.mouseX() >= cx && f.mouseX() < cx + hallCardW
                    && f.mouseY() >= cy && f.mouseY() < cy + hallCardH;
            PageKit.hoverCard(canvas, cx, cy, hallCardW, hallCardH, hover ? 1.0F : 0.0F);
            Draw.fill(canvas, cx + 4.0F, cy + 4.0F, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
            Draw.stroke(canvas, cx + 4.0F, cy + 4.0F, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
            if (!menu) {
                f.host().items().add(it.stack, cx + 5.0F, cy + 5.0F);
                if (f.mouseX() >= cx + 4.0F && f.mouseX() < cx + 22.0F && f.mouseY() >= cy + 4.0F
                        && f.mouseY() < cy + 22.0F) {
                    f.host().items().setHovered(it.stack);
                }
            }
            float tx = cx + 25.0F;
            float tw = hallCardW - 28.0F;
            Text.drawLine(canvas, PageKit.plainName(it.stack), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT, tx, cy + 8.0F,
                    tw, Text.Align.LEFT);
            Text.drawLine(canvas, ClientShopData.money(it.price), AtomUi.BOLD, Text.SMALL, IOStyle.ORANGE, tx, cy + 17.0F,
                    tw, Text.Align.LEFT);
            Text.drawLine(canvas, Component.translatable("gui.module_shop.stall.stock_short", it.stock).getString(),
                    AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, cx + 4.0F, cy + 29.0F, 40.0F, Text.Align.LEFT);
            Text.drawLine(canvas, it.own ? Component.translatable("gui.module_shop.stall.mine_tag").getString()
                            : it.ownerName, AtomUi.REGULAR, Text.SMALL, it.own ? IOStyle.ORANGE : IOStyle.TEXT_3,
                    cx + 40.0F, cy + 29.0F, hallCardW - 44.0F, Text.Align.RIGHT);
        }
        // 底栏：状态 + 翻页
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        float right = x + w - 4.0F;
        prevBtn.visible(pages > 1);
        nextBtn.visible(pages > 1);
        if (pages > 1) {
            right -= 40.0F;
            nextBtn.enabled(StallClient.page < pages - 1).bounds(right, barMid - 6.0F, 40.0F, 12.0F);
            nextBtn.onClick(() -> StallClient.page++);
            nextBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            right -= 44.0F;
            prevBtn.enabled(StallClient.page > 0).bounds(right, barMid - 6.0F, 40.0F, 12.0F);
            prevBtn.onClick(() -> StallClient.page = Math.max(0, StallClient.page - 1));
            prevBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }
        Text.drawLine(canvas, Component.translatable("gui.module_shop.stall.hall_status", StallClient.page + 1, pages,
                        hallShown.size(), ClientShopData.money(ClientShopData.balance)).getString(), AtomUi.REGULAR,
                Text.SMALL, IOStyle.TEXT_2, x + 4.0F, barMid, right - 8.0F - (x + 4.0F), Text.Align.LEFT);

        if (catOpen) {
            AuctionCategory[] cats = AuctionCategory.values();
            openMenu(canvas, catX, top + CTRL_H + 2.0F, 110.0F, cats.length);
            for (int i = 0; i < cats.length; i++) {
                menuRow(canvas, f, i, Component.translatable(cats[i].langKey()).getString(),
                        StallClient.category == cats[i]);
            }
        } else if (sortOpen) {
            openMenu(canvas, sortX, top + CTRL_H + 2.0F, 110.0F, 3);
            for (int i = 0; i < 3; i++) {
                menuRow(canvas, f, i, Component.translatable(StallClient.sortKey(i)).getString(), StallClient.sort == i);
            }
        }
    }

    private void openMenu(Canvas canvas, float x, float y, float w, int rows) {
        menuX = x;
        menuY = y;
        menuW = w;
        menuH = rows * MENU_ROW + 4.0F;
        Draw.fill(canvas, menuX, menuY, menuW, menuH, 2.0F, IOStyle.CARD);
        Draw.stroke(canvas, menuX, menuY, menuW, menuH, 2.0F, 1.0F, IOStyle.BORDER);
    }

    private void menuRow(Canvas canvas, PageFrame f, int i, String label, boolean current) {
        float rx = menuX + 2.0F;
        float ry = menuY + 2.0F + i * MENU_ROW;
        float rw = menuW - 4.0F;
        boolean hover = f.mouseX() >= rx && f.mouseX() < rx + rw && f.mouseY() >= ry && f.mouseY() < ry + MENU_ROW;
        if (current || hover) {
            Draw.fill(canvas, rx, ry, rw, MENU_ROW, 1.0F, current ? IOStyle.BLUE_TINT : IOStyle.ROW_HOVER);
        }
        Text.drawLine(canvas, label, AtomUi.REGULAR, Text.SMALL, current ? IOStyle.TEXT : IOStyle.TEXT_2,
                rx + 6.0F, ry + MENU_ROW / 2.0F, rw - 10.0F, Text.Align.LEFT);
    }

    private void closeMenus() {
        catOpen = false;
        sortOpen = false;
    }

    /** 从大厅打开卖家摊位，并预选这件商品。 */
    private static void openSeller(StallClient.Item it) {
        if (it.owner == null) {
            return;
        }
        StallClient.pendingSelect = it.id;
        // 玩家主动点进摊位：解除「刚关闭过摊位界面」的保护，否则 5 秒内再点别的商品会打不开
        Nav.rearm(cn.autoforged.module_shop.client.screen.StallBrowseScreen.class);
        CompoundTag tag = new CompoundTag();
        tag.putString("owner", it.owner.toString());
        StallClient.send("open_stall", tag);
    }

    // =================================================================== 输入

    private Button[] topButtons() {
        return new Button[]{tabMine, tabHall, browse};
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (StallClient.hallView && (catOpen || sortOpen)) {
            boolean inMenu = mx >= menuX && mx < menuX + menuW && my >= menuY && my < menuY + menuH;
            if (inMenu && button == 0) {
                int row = (int) ((my - menuY - 2.0F) / MENU_ROW);
                if (catOpen && row >= 0 && row < AuctionCategory.values().length) {
                    ClickSound.play();
                    StallClient.category = AuctionCategory.values()[row];
                    StallClient.page = 0;
                } else if (sortOpen && row >= 0 && row < 3) {
                    ClickSound.play();
                    StallClient.sort = row;
                    StallClient.page = 0;
                }
                closeMenus();
                return true;
            }
            if (!(catBtn.isMouseOver(mx, my) || sortBtn.isMouseOver(mx, my))) {
                closeMenus();
                return true;
            }
        }
        for (Button b : topButtons()) {
            if (b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        if (StallClient.hallView) {
            if (search.mouseClicked(mx, my, button)) {
                return true;
            }
            for (Button b : new Button[]{catBtn, sortBtn, prevBtn, nextBtn}) {
                if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                    return true;
                }
            }
            search.blur();
            if (button == 0) {
                for (int i = 0; i < hallPer; i++) {
                    int index = StallClient.page * hallPer + i;
                    if (index >= hallShown.size()) {
                        break;
                    }
                    float cx = hallX + (i % hallCols) * (hallCardW + 3.0F);
                    float cy = hallY + (i / hallCols) * (hallCardH + 3.0F);
                    if (mx >= cx && mx < cx + hallCardW && my >= cy && my < cy + hallCardH) {
                        ClickSound.play();
                        openSeller(hallShown.get(index));
                        return true;
                    }
                }
            }
            return false;
        }
        // ---- 我的商店 ----
        if (button == 0 && scroll.pressBar(mx, my, gridX, gridY, gridW, gridH)) {
            return true;
        }
        if (create.mouseClicked(mx, my, button) || records.mouseClicked(mx, my, button)) {
            return true;
        }
        for (int i = 0; i < Math.min(shownEntries, removeButtons.size()); i++) {
            if (removeButtons.get(i).isVisible() && removeButtons.get(i).mouseClicked(mx, my, button)) {
                return true;
            }
            if (restockButtons.get(i).isVisible() && restockButtons.get(i).mouseClicked(mx, my, button)) {
                return true;
            }
        }
        if (button == 0 && mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
            float localY = (float) (my - gridY + scroll.offset());
            int row = (int) (localY / (CELL_H + GAP_Y));
            int col = (int) ((mx - gridX) / (cellW + GAP_X));
            float inCellX = (float) (mx - gridX - col * (cellW + GAP_X));
            float inCellY = localY - row * (CELL_H + GAP_Y);
            int index = row * COLS + col;
            List<StallEntry> stall = ClientShopData.stall;
            if (col >= 0 && col < COLS && inCellY < CELL_H && inCellX < cellW - 40.0F
                    && index >= 0 && index < stall.size()) {
                ClickSound.play();
                // 点格子改价（售罄的也可以改价，补货后按新价格卖）
                f.host().action("stall_edit_gui", entryTag(index, stall.get(index)));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = scroll.releaseBar();
        for (Button b : new Button[]{tabMine, tabHall, browse, create, records, catBtn, sortBtn, prevBtn, nextBtn}) {
            handled |= b.mouseReleased(mx, my, button);
        }
        for (Button b : removeButtons) {
            handled |= b.mouseReleased(mx, my, button);
        }
        for (Button b : restockButtons) {
            handled |= b.mouseReleased(mx, my, button);
        }
        if (search != null) {
            handled |= search.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (StallClient.hallView) {
            return search != null && search.mouseDragged(mx, my, button, dx, dy);
        }
        return scroll.dragBar(my, gridH);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (StallClient.hallView) {
            if (catOpen || sortOpen) {
                return true;
            }
            if (my < f.listBottom()) {
                StallClient.page = Math.max(0, StallClient.page + (scrollY > 0 ? -1 : 1));
                return true;
            }
            return false;
        }
        if (my < f.listBottom() && scroll.canScroll()) {
            scroll.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void clickedOutside() {
        closeMenus();
        if (search != null && search.isFocused()) {
            search.blur();
        }
    }

    @Override
    public void cancelInput() {
        scroll.releaseBar();
        closeMenus();
        for (Button b : new Button[]{tabMine, tabHall, browse, create, records, catBtn, sortBtn, prevBtn, nextBtn}) {
            b.cancelPress();
        }
        removeButtons.forEach(Button::cancelPress);
        restockButtons.forEach(Button::cancelPress);
        if (search != null) {
            search.blur();
            search.visible(false);
        }
    }
}
