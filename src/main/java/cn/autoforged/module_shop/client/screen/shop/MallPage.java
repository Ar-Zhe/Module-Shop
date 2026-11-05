package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.MallFavorites;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.ShopScreen;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 购物城页（Skija 版）：管理员上架的常驻货架。
 *
 * <ul>
 *     <li>第一行：搜索框（{@link SkiaTextField}，实时过滤，支持中文输入法）+「搜索」+「只看收藏」；</li>
 *     <li>第二行：排序下拉 / 按模组分类下拉（可滚动）/「最近购买」；</li>
 *     <li>货架：两行六列一页 12 件，品质只用 1px 细边框表示，右上角星标收藏；左键购买、右键批量购买；
 *     滚轮或底栏按钮翻页；</li>
 *     <li>最近购买：两行一条（物品×数量 + 金额 / 玩家 + 时间），平滑滚动；</li>
 *     <li>下拉展开时点击只归下拉处理：点选项即应用，点别处只收起（不会顺手买到卡片上的东西）。</li>
 * </ul>
 *
 * 过滤 / 排序 / 分类 / 收藏 key 都复用 {@link ShopScreen} 里的同一份实现，与旧界面结果完全一致。
 */
public final class MallPage implements ShopPage {
    private static final int COLS = 6;
    private static final int ROWS = 2;
    private static final int PAGE_SIZE = COLS * ROWS;
    private static final float CTRL_H = 16.0F;
    private static final float GAP = 4.0F;
    private static final float GRID_TOP = 38.0F;
    private static final float CARD_GAP = 3.0F;
    private static final float SEARCH_W = 140.0F;
    private static final float SORT_W = 74.0F;
    private static final float CAT_W = 118.0F;
    private static final float MENU_ROW = 14.0F;
    private static final int CAT_ROWS = 8;
    private static final float RECENT_ROW = 24.0F;
    private static final float RECENT_CARD = 22.0F;

    private static final Paint STAR = new Paint().setAntiAlias(true);
    private static final Paint STAR_LINE = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE).setStrokeWidth(1.0F);

    private static float savedRecentScroll;

    private SkiaTextField search;
    private final ScrollController recentScroll = new ScrollController();
    private boolean restored;
    private boolean sortOpen;
    private boolean catOpen;
    private int catScroll;
    private float[] hoverT = new float[PAGE_SIZE];

    private final Button searchBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.mall.search").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button favBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.mall.fav_only").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button sortBtn = new Button(Button.Kind.NORMAL, () -> Component.translatable(
            "gui.module_shop.mall.sort_prefix", ShopScreen.mallSortName(ClientShopData.mallSort)).getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button catBtn = new Button(Button.Kind.NORMAL, () -> Component.translatable(
            "gui.module_shop.mall.cat_prefix", categoryName(ClientShopData.mallCategory)).getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button recentBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.mall.recent").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button backBtn = new Button(Button.Kind.ACCENT,
            () -> Component.translatable("gui.module_shop.mall.back_shelf").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button prevBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.page_prev").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button nextBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.page_next").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);

    // 本帧几何
    private float gridX, gridY, cardW, cardH;
    private float menuX, menuY, menuW, menuH;
    private float listX, listY, listW, listH;
    private List<ClientShopData.MallView> shown = List.of();

    @Override
    public String id() {
        return "mall";
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 32)
                .placeholder(Component.translatable("gui.module_shop.mall.search_hint").getString())
                .fontSize(Text.SMALL)
                .onChange(text -> {
                    ClientShopData.mallSearch = text;
                    ClientShopData.mallPage = 0;
                });
        search.setValue(ClientShopData.mallSearch == null ? "" : ClientShopData.mallSearch);
        host.registerInput(search.editBox());
    }

    private static String categoryName(String ns) {
        return ns == null || ns.isEmpty()
                ? Component.translatable("gui.module_shop.mall.cat_all").getString()
                : ShopScreen.mallModName(ns);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        MallFavorites.ensureLoaded();
        boolean recent = ClientShopData.mallViewRecent;
        float cx = f.x();
        float cy = f.y();
        float cw = f.w();
        float y2 = cy + CTRL_H + GAP;
        float catX = cx + SORT_W + GAP;
        if (recent) {
            closeMenus();
            search.visible(false);
            // 货架视图的控件这一帧不画，也必须不可点（否则按上一帧的位置仍能被点中）
            for (Button b : new Button[]{searchBtn, favBtn, sortBtn, catBtn, recentBtn, prevBtn, nextBtn}) {
                b.visible(false);
            }
            backBtn.visible(true);
            backBtn.onClick(() -> {
                f.host().action("mall_recent_toggle", null);
                recentScroll.reset();
                savedRecentScroll = 0.0F;
            }).bounds(cx, cy, 104.0F, CTRL_H);
            backBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            renderRecent(canvas, f, cy + CTRL_H + GAP);
            return;
        }

        backBtn.visible(false);
        for (Button b : new Button[]{searchBtn, favBtn, sortBtn, catBtn, recentBtn}) {
            b.visible(true);
        }
        // ---- 第一行：搜索 + 只看收藏 ----
        search.bounds(cx, cy, SEARCH_W, CTRL_H).visible(true);
        search.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float searchBtnX = cx + SEARCH_W + GAP;
        searchBtn.onClick(() -> search.blur()).bounds(searchBtnX, cy, 40.0F, CTRL_H);
        searchBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float favX = searchBtnX + 40.0F + GAP;
        favBtn.kind(ClientShopData.mallFavOnly ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> f.host().action("mall_fav_toggle", null))
                .bounds(favX, cy, cx + cw - favX, CTRL_H);
        favBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());

        // ---- 第二行：排序 / 分类 / 最近购买 ----
        sortBtn.kind(sortOpen ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> {
                    sortOpen = !sortOpen;
                    catOpen = false;
                }).bounds(cx, y2, SORT_W, CTRL_H);
        sortBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        catBtn.kind(catOpen ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> {
                    catOpen = !catOpen;
                    sortOpen = false;
                    catScroll = 0;
                }).bounds(catX, y2, CAT_W, CTRL_H);
        catBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float recentX = catX + CAT_W + GAP;
        recentBtn.onClick(() -> {
            search.blur();
            f.host().action("mall_recent_toggle", null);
            recentScroll.reset();
            savedRecentScroll = 0.0F;
        }).bounds(recentX, y2, cx + cw - recentX, CTRL_H);
        recentBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());

        renderGrid(canvas, f);

        // ---- 下拉面板（最后画，盖在所有内容之上）----
        if (sortOpen) {
            renderSortMenu(canvas, f, cx, y2 + CTRL_H + 2.0F);
        } else if (catOpen) {
            renderCatMenu(canvas, f, catX, y2 + CTRL_H + 2.0F);
        }
    }

    private void renderGrid(Canvas canvas, PageFrame f) {
        boolean menu = sortOpen || catOpen;
        shown = ShopScreen.mallFiltered();
        int pages = Math.max(1, (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        ClientShopData.mallPage = Math.max(0, Math.min(ClientShopData.mallPage, pages - 1));
        gridX = f.x();
        gridY = f.y() + GRID_TOP;
        float gridH = Math.max(40.0F, f.barTop() - 4.0F - gridY);
        cardW = (float) Math.floor((f.w() - (COLS - 1) * CARD_GAP) / COLS);
        cardH = (float) Math.floor((gridH - (ROWS - 1) * CARD_GAP) / ROWS);
        if (shown.isEmpty()) {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.mall.empty").getString(),
                    f.x(), gridY + gridH / 2.0F, f.w());
        }
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = ClientShopData.mallPage * PAGE_SIZE + i;
            if (index >= shown.size()) {
                break;
            }
            float x = gridX + (i % COLS) * (cardW + CARD_GAP);
            float y = gridY + (i / COLS) * (cardH + CARD_GAP);
            drawCard(canvas, f, shown.get(index), i, x, y, menu);
        }

        // ---- 底栏：状态（左）+ 翻页（右）----
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        float right = f.x() + f.w() - 4.0F;
        prevBtn.visible(pages > 1);
        nextBtn.visible(pages > 1);
        if (pages > 1) {
            right -= 40.0F;
            nextBtn.enabled(ClientShopData.mallPage < pages - 1)
                    .bounds(right, barMid - 6.0F, 40.0F, 12.0F);
            nextBtn.onClick(() -> f.host().action("mall_next", null));
            nextBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            right -= 44.0F;
            prevBtn.enabled(ClientShopData.mallPage > 0).bounds(right, barMid - 6.0F, 40.0F, 12.0F);
            prevBtn.onClick(() -> f.host().action("mall_prev", null));
            prevBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }
        Component status = Component.translatable("gui.module_shop.mall.status",
                ClientShopData.mallPage + 1, pages, shown.size());
        if (ClientShopData.mallStockPersonal) {
            // 个人库存：先说清楚「这个剩余量是你自己的」
            status = Component.translatable("gui.module_shop.mall.personal_stock")
                    .append(Component.literal("  ·  ")).append(status);
        }
        if (ClientShopData.mallRestockOn) {
            // 开了定时补货：写清楚每天几点补货，玩家就知道售罄只是暂时的
            status = Component.translatable("gui.module_shop.mall.restock_status",
                            ClientShopData.mallRestockTimeText,
                            Component.translatable("gui.module_shop.choice.restock_mode."
                                    + ("game".equals(ClientShopData.mallRestockMode) ? "game" : "real")))
                    .append(Component.literal("  ·  ")).append(status);
        }
        Text.drawLine(canvas, status.getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                f.x() + 4.0F, barMid, right - 8.0F - (f.x() + 4.0F), Text.Align.LEFT);
    }

    /** 一张货架卡片：品质只用 1px 细边框；图标 / 名称 / 价格 / 库存 + 右上角收藏星标。 */
    private void drawCard(Canvas canvas, PageFrame f, ClientShopData.MallView view, int slot, float x, float y,
            boolean menuOpen) {
        boolean hover = !menuOpen && f.mouseX() >= x && f.mouseX() < x + cardW
                && f.mouseY() >= y && f.mouseY() < y + cardH;
        hoverT[slot] = Motion.approach(hoverT[slot], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
        int quality = IOStyle.rarity(view.rarity);
        boolean soldOut = view.soldOut();
        Draw.fill(canvas, x, y, cardW, cardH, 2.0F, Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hoverT[slot]));
        Draw.stroke(canvas, x, y, cardW, cardH, 2.0F, 1.0F, soldOut ? Draw.alpha(quality, 0.35F) : quality);
        float slotX = x + cardW / 2.0F - 9.0F;
        Draw.fill(canvas, slotX, y + 3.0F, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
        Draw.stroke(canvas, slotX, y + 3.0F, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
        if (!menuOpen) {
            // 下拉展开时不登记物品：物品图标画在 Skija 之后，会穿透下拉面板
            f.host().items().add(view.stack, slotX + 1.0F, y + 4.0F);
        }
        Text.drawLine(canvas, PageKit.plainName(view.stack), AtomUi.REGULAR, Text.SMALL,
                soldOut ? IOStyle.TEXT_3 : IOStyle.TEXT, x + 2.0F, y + 27.0F, cardW - 4.0F, Text.Align.CENTER);
        Text.drawLine(canvas, ClientShopData.money(view.price), AtomUi.BOLD, Text.SMALL, IOStyle.ORANGE,
                x + 2.0F, y + 36.0F, cardW - 4.0F, Text.Align.CENTER);
        Text.drawLine(canvas, ShopScreen.mallStockText(view).getString(), AtomUi.REGULAR, Text.SMALL,
                soldOut ? IOStyle.RED : (view.unlimited() ? IOStyle.TEXT_2 : IOStyle.GREEN),
                x + 2.0F, y + 45.0F, cardW - 4.0F, Text.Align.CENTER);
        boolean fav = MallFavorites.contains(ShopScreen.mallItemId(view));
        boolean starHover = hover && overStar(f.mouseX(), f.mouseY(), x, y);
        star(canvas, x + cardW - 6.0F, y + 6.0F, 3.6F, fav,
                fav ? IOStyle.ORANGE : (starHover ? IOStyle.TEXT : IOStyle.TEXT_3));
        if (hover && !starHover) {
            f.host().items().setHovered(view.stack); // 整张卡悬停都显示 tooltip / JEI
        }
    }

    private boolean overStar(double mx, double my, float x, float y) {
        return mx >= x + cardW - 12.0F && mx < x + cardW && my >= y && my < y + 12.0F;
    }

    /** 五角星（收藏标记）：实心 = 已收藏，空心 = 未收藏。 */
    private static void star(Canvas canvas, float cx, float cy, float r, boolean filled, int color) {
        try (PathBuilder pb = new PathBuilder()) {
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2.0D + i * Math.PI / 5.0D;
                float rr = i % 2 == 0 ? r : r * 0.45F;
                float px = cx + (float) Math.cos(a) * rr;
                float py = cy + (float) Math.sin(a) * rr;
                if (i == 0) {
                    pb.moveTo(px, py);
                } else {
                    pb.lineTo(px, py);
                }
            }
            pb.closePath();
            drawPath(canvas, pb, filled, color);
        }
    }

    private static void drawPath(Canvas canvas, PathBuilder pb, boolean filled, int color) {
        try (Path path = pb.build()) {
            if (filled) {
                STAR.setColor(color);
                canvas.drawPath(path, STAR);
            } else {
                STAR_LINE.setColor(color);
                canvas.drawPath(path, STAR_LINE);
            }
        }
    }

    private void renderSortMenu(Canvas canvas, PageFrame f, float x, float y) {
        menuX = x;
        menuY = y;
        menuW = 104.0F;
        menuH = 5 * MENU_ROW + 4.0F;
        menuBackground(canvas);
        for (int i = 0; i < 5; i++) {
            float ry = y + 2.0F + i * MENU_ROW;
            menuRow(canvas, f, ry, ShopScreen.mallSortName(i).getString(), ClientShopData.mallSort == i);
        }
    }

    private void renderCatMenu(Canvas canvas, PageFrame f, float x, float y) {
        List<String> mods = ShopScreen.mallCategories();
        int total = mods.size() + 1;
        int rows = Math.min(CAT_ROWS, total);
        int maxScroll = Math.max(0, total - rows);
        catScroll = Math.max(0, Math.min(catScroll, maxScroll));
        menuX = x;
        menuY = y;
        menuW = 168.0F;
        menuH = rows * MENU_ROW + 4.0F;
        menuBackground(canvas);
        for (int i = catScroll; i < Math.min(total, catScroll + rows); i++) {
            String ns = i == 0 ? "" : mods.get(i - 1);
            float ry = y + 2.0F + (i - catScroll) * MENU_ROW;
            menuRow(canvas, f, ry, categoryName(ns), ns.equals(ClientShopData.mallCategory));
        }
        if (maxScroll > 0) {
            float viewH = rows * MENU_ROW;
            float trackX = x + menuW - 4.0F;
            Draw.fill(canvas, trackX, y + 2.0F, 1.5F, viewH, 0.75F, IOStyle.HAIR);
            float thumbH = Math.max(12.0F, viewH * rows / total);
            float thumbY = y + 2.0F + (viewH - thumbH) * catScroll / maxScroll;
            Draw.fill(canvas, trackX, thumbY, 1.5F, thumbH, 0.75F, IOStyle.BLUE);
        }
    }

    private void menuBackground(Canvas canvas) {
        Draw.fill(canvas, menuX + 1.0F, menuY + 2.0F, menuW, menuH, 3.0F, IOStyle.SHADOW_2);
        Draw.fill(canvas, menuX, menuY, menuW, menuH, 3.0F, IOStyle.CARD);
        Draw.stroke(canvas, menuX, menuY, menuW, menuH, 3.0F, 1.0F, IOStyle.BORDER);
    }

    private void menuRow(Canvas canvas, PageFrame f, float ry, String label, boolean current) {
        float rx = menuX + 2.0F;
        float rw = menuW - 4.0F - 4.0F;
        boolean hover = f.mouseX() >= rx && f.mouseX() < rx + rw && f.mouseY() >= ry && f.mouseY() < ry + MENU_ROW;
        if (current || hover) {
            Draw.fill(canvas, rx, ry, rw, MENU_ROW, 2.0F, current ? IOStyle.BLUE_TINT : IOStyle.ROW_HOVER);
        }
        Text.drawLine(canvas, label, AtomUi.REGULAR, Text.SMALL, current ? IOStyle.TEXT : IOStyle.TEXT_2,
                rx + 6.0F, ry + MENU_ROW / 2.0F, rw - 10.0F, Text.Align.LEFT);
    }

    /** 最近购买：两行一条（物品×数量 + 金额 / 玩家 + 时间），最新的在最上面。 */
    private void renderRecent(Canvas canvas, PageFrame f, float top) {
        List<ClientShopData.MallPurchaseView> list = ClientShopData.mallRecent;
        listX = f.x();
        listY = top;
        listW = f.w();
        listH = f.listBottom() - top;
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        Text.drawLine(canvas, Component.translatable("gui.module_shop.mall.recent_status", list.size()).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, f.x() + 4.0F, barMid, f.w() - 8.0F, Text.Align.LEFT);
        if (list.isEmpty()) {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.mall.recent_empty").getString(),
                    f.x(), top + listH / 2.0F, f.w());
            return;
        }
        recentScroll.setContent(list.size() * RECENT_ROW - (RECENT_ROW - RECENT_CARD), listH);
        if (!restored) {
            recentScroll.scrollTo(savedRecentScroll, false);
            restored = true;
        }
        recentScroll.update();
        savedRecentScroll = recentScroll.offset();
        float rowW = listW - (recentScroll.canScroll() ? 6.0F : 0.0F);
        int saved = canvas.save();
        f.host().items().pushClip(listX, listY, listW, listH);
        try {
            Draw.clip(canvas, listX - 1.0F, listY, listW + 2.0F, listH);
            float off = recentScroll.offset();
            for (int i = Math.max(0, (int) (off / RECENT_ROW)); i < list.size(); i++) {
                float y = listY + i * RECENT_ROW - off;
                if (y > listY + listH) {
                    break;
                }
                ClientShopData.MallPurchaseView r = list.get(i);
                PageKit.card(canvas, listX, y, rowW, RECENT_CARD);
                PageKit.slot(canvas, f.host(), r.stack, listX + 2.0F, y + 2.0F);
                float textX = listX + 24.0F;
                String money = ClientShopData.money(r.total);
                float moneyW = Text.width(money, AtomUi.BOLD, Text.SMALL);
                Text.drawLine(canvas, PageKit.plainName(r.stack) + " ×" + r.count, AtomUi.REGULAR, Text.SMALL,
                        IOStyle.TEXT, textX, y + 7.0F, rowW - 30.0F - moneyW - (textX - listX), Text.Align.LEFT);
                Text.drawLine(canvas, money, AtomUi.BOLD, Text.SMALL, IOStyle.ORANGE,
                        listX + rowW - 6.0F - moneyW, y + 7.0F, moneyW + 1.0F, Text.Align.LEFT);
                String time = ShopScreen.mallTimeText(r.time);
                float timeW = Text.width(time, AtomUi.REGULAR, Text.SMALL);
                Text.drawLine(canvas, r.playerName, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                        textX, y + 16.0F, rowW - 30.0F - timeW - (textX - listX), Text.Align.LEFT);
                Text.drawLine(canvas, time, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                        listX + rowW - 6.0F - timeW, y + 16.0F, timeW + 1.0F, Text.Align.LEFT);
                if (!recentScroll.isDragging() && f.mouseX() >= listX && f.mouseX() < listX + rowW
                        && f.mouseY() >= Math.max(y, listY) && f.mouseY() < Math.min(y + RECENT_CARD, listY + listH)) {
                    f.host().items().setHovered(r.stack);
                }
            }
        } finally {
            f.host().items().popClip();
            canvas.restoreToCount(saved);
        }
        recentScroll.drawBar(canvas, listX, listY, listW, listH, f.mouseX(), f.mouseY(), f.dtMs());
        if (recentScroll.hasMoreBelow()) {
            PageKit.fadeBottom(canvas, listX, listY + listH, rowW, IOStyle.CONTENT);
        }
    }

    // ------------------------------------------------------------------ 输入

    private void closeMenus() {
        sortOpen = false;
        catOpen = false;
    }

    private Button[] buttons() {
        return new Button[]{searchBtn, favBtn, sortBtn, catBtn, recentBtn, backBtn, prevBtn, nextBtn};
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        boolean recent = ClientShopData.mallViewRecent;
        // 下拉展开：这次点击只归下拉处理（点选项应用，点别处只收起）
        if (!recent && (sortOpen || catOpen)) {
            boolean inMenu = mx >= menuX && mx < menuX + menuW && my >= menuY && my < menuY + menuH;
            boolean onRow = mx >= menuX + 2.0F && mx < menuX + menuW - 6.0F
                    && my >= menuY + 2.0F && my < menuY + menuH - 2.0F;
            if (inMenu && !onRow) {
                return true;
            }
            if (button == 0 && onRow) {
                int row = (int) ((my - menuY - 2.0F) / MENU_ROW);
                if (sortOpen && row >= 0 && row < 5) {
                    ClickSound.play();
                    CompoundTag extra = new CompoundTag();
                    extra.putInt("sort", row);
                    f.host().action("mall_sort_pick", extra);
                } else if (catOpen) {
                    int index = catScroll + row;
                    List<String> mods = ShopScreen.mallCategories();
                    if (index >= 0 && index <= mods.size()) {
                        ClickSound.play();
                        CompoundTag extra = new CompoundTag();
                        extra.putString("category", index == 0 ? "" : mods.get(index - 1));
                        f.host().action("mall_cat_pick", extra);
                    }
                }
                closeMenus();
                return true;
            }
            // 点到下拉按钮本身：交给按钮切换；其它地方只收起（顺带让搜索框失焦）
            if (!(sortBtn.isMouseOver(mx, my) || catBtn.isMouseOver(mx, my))) {
                closeMenus();
                search.blur();
                return true;
            }
        }
        if (!recent && search.mouseClicked(mx, my, button)) {
            return true;
        }
        for (Button b : buttons()) {
            if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        if (recent) {
            return button == 0 && recentScroll.pressBar(mx, my, listX, listY, listW, listH);
        }
        // 货架卡片：星标 = 收藏；左键购买、右键批量购买（两者都用货架下标，不依赖过滤结果）
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = ClientShopData.mallPage * PAGE_SIZE + i;
            if (index >= shown.size()) {
                break;
            }
            float x = gridX + (i % COLS) * (cardW + CARD_GAP);
            float y = gridY + (i / COLS) * (cardH + CARD_GAP);
            if (mx < x || mx >= x + cardW || my < y || my >= y + cardH) {
                continue;
            }
            ClientShopData.MallView view = shown.get(index);
            CompoundTag extra = new CompoundTag();
            extra.putInt("index", view.shelf);
            if (overStar(mx, my, x, y)) {
                if (button == 0) {
                    f.host().action("mall_star", extra); // 收藏动作自己会播放点击音
                }
                return true; // 与旧版一致：星标区域的右键什么都不做（不会触发批量购买）
            }
            if (button == 0 || button == 1) {
                if (!view.soldOut()) {
                    ClickSound.play();
                }
                f.host().action(button == 0 ? "mall_buy" : "mall_buy_many", extra);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = recentScroll.releaseBar();
        for (Button b : buttons()) {
            handled |= b.mouseReleased(mx, my, button);
        }
        if (search != null) {
            handled |= search.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return ClientShopData.mallViewRecent && recentScroll.dragBar(my, listH);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (ClientShopData.mallViewRecent) {
            recentScroll.wheel(scrollY);
            return true;
        }
        if (catOpen) {
            if (mx >= menuX && mx < menuX + menuW && my >= menuY && my < menuY + menuH) {
                catScroll = Math.max(0, catScroll + (scrollY > 0 ? -1 : 1));
            }
            return true;
        }
        if (sortOpen) {
            return true; // 排序只有 5 项，不滚动，但也不要把滚轮漏给货架
        }
        // 货架：滚轮翻页（上限在绘制时按过滤结果夹紧）
        ClientShopData.mallPage = Math.max(0, ClientShopData.mallPage + (scrollY > 0 ? -1 : 1));
        return true;
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
        closeMenus();
        recentScroll.releaseBar();
        for (Button b : buttons()) {
            b.cancelPress();
        }
        if (search != null) {
            search.blur();
            search.visible(false); // 切走后隐藏背后的输入框，下次渲染时重新显示
        }
    }
}
