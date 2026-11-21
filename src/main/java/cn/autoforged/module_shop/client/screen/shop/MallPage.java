package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.MallFavorites;
import cn.autoforged.module_shop.client.UIViewMode;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.ShopScreen;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.CardGrid;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.PriceSpec;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 购物城（UI v2 列表视图）：管理员上架的常驻货架。
 *
 * <ul>
 *     <li>工具条：标题、搜索（实时过滤，支持输入法）、全部 / 收藏 / 最近购买 三段切换、补货说明；</li>
 *     <li>左侧筛选：按模组分类（带数量）、只看有货、买得起；</li>
 *     <li>表格：物品 · 模组 · 收藏 · 单价 · 库存 · 购买，点「单价 / 库存」表头排序；
 *     左键「购买」买 1 个，右键整行 = 批量购买，点星标收藏；</li>
 *     <li>最近购买：时间 · 玩家 · 物品 · 数量 · 单价 · 总价。</li>
 * </ul>
 * 过滤 / 排序 / 分类 / 收藏复用 {@link ShopScreen} 的同一份实现，动作字符串与旧界面一致。
 */
public final class MallPage implements ShopPage {
    private static final float FILTER_W = 98.0F;
    /** 跨界面重建保留：表格、分类筛选滚动与客户端附加筛选。 */
    private static final ListTable TABLE = new ListTable();
    private static final ListTable RECENT = new ListTable();
    private static final CardGrid GRID = new CardGrid();
    private static final ScrollController CAT_SCROLL = new ScrollController();
    private static boolean onlyStock;
    private static boolean affordable;

    private SkiaTextField search;
    private List<ClientShopData.MallView> shown = List.of();
    private float[][] segRects = new float[0][2];
    private float segY;
    private float modBarX, modBarY, modBarW, modBarH;
    private final List<float[]> filterRects = new ArrayList<>();
    private final List<String> filterKeys = new ArrayList<>();
    private final List<float[]> sortPillRects = new ArrayList<>();

    @Override
    public String id() {
        return "mall";
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 48)
                .placeholder(Component.translatable("gui.module_shop.mall.search_hint").getString())
                .clearable(true)
                .fontSize(V2.FS_S)
                .onChange(text -> {
                    ClientShopData.mallSearch = text;
                    ClientShopData.mallPage = 0;
                    TABLE.scrollToTop();
                    GRID.scrollToTop();
                    RECENT.scrollToTop();
                    CAT_SCROLL.reset();
                });
        search.setValue(ClientShopData.mallSearch == null ? "" : ClientShopData.mallSearch);
        host.registerInput(search.editBox());
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static List<ClientShopData.MallView> applyClientFilters(List<ClientShopData.MallView> base) {
        if (!onlyStock && !affordable) {
            return base;
        }
        List<ClientShopData.MallView> out = new ArrayList<>();
        for (ClientShopData.MallView v : base) {
            if (onlyStock && v.soldOut()) {
                continue;
            }
            if (affordable && !PriceDisplay.canAfford(v.resolvePriceSpec(), 1)) {
                continue;
            }
            out.add(v);
        }
        return out;
    }

    private List<ClientShopData.MallView> filtered() {
        return applyClientFilters(ShopScreen.mallFiltered());
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas c, PageFrame f) {
        MallFavorites.ensureLoaded();
        boolean recent = ClientShopData.mallViewRecent;
        drawToolbar(c, f, recent);
        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        if (recent) {
            drawRecent(c, f, f.x(), top, f.w(), bottom - top);
            return;
        }
        drawFilter(c, f, f.x(), top, bottom - top);
        shown = filtered();
        if (UIViewMode.isGrid()) {
            drawGrid(c, f, f.x() + FILTER_W, top, f.w() - FILTER_W, bottom - top);
            V2.foot(c, f.x() + FILTER_W, bottom, f.w() - FILTER_W,
                    tr("gui.module_shop.v2.showing", GRID.firstVisible(), GRID.lastVisible(), shown.size()),
                    tr("gui.module_shop.v2.mall.hint"));
        } else {
            drawTable(c, f, f.x() + FILTER_W, top, f.w() - FILTER_W, bottom - top);
            V2.foot(c, f.x() + FILTER_W, bottom, f.w() - FILTER_W,
                    tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size()),
                    tr("gui.module_shop.v2.mall.hint"));
        }
    }

    private void drawToolbar(Canvas c, PageFrame f, boolean recent) {
        float x = f.x();
        float y = f.y();
        float cy = y + V2.BAR / 2;
        Draw.hairline(c, x, y + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.mall"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 80, Text.Align.LEFT);
        float sx = x + 8 + tw + 10;
        float searchW = Math.min(140.0F, Math.max(90.0F, f.w() * 0.24F));
        search.visible(true).bounds(sx, cy - V2.CTRL / 2, searchW, V2.CTRL);
        search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
        sx += searchW + 6;
        String[] seg = {
                tr("gui.module_shop.v2.mall.all") + "  " + ClientShopData.mall.size(),
                tr("gui.module_shop.v2.mall.fav") + "  " + MallFavorites.size(),
                tr("gui.module_shop.mall.recent")};
        int sel = recent ? 2 : ClientShopData.mallFavOnly ? 1 : 0;
        this.segY = cy - V2.CTRL / 2;
        this.segRects = V2.segmented(c, seg, sel, sx, this.segY, null);
        // 右侧：补货 / 个人库存说明
        List<String> notes = new ArrayList<>();
        if (ClientShopData.mallStockPersonal) {
            notes.add(tr("gui.module_shop.mall.personal_stock"));
        }
        if (ClientShopData.mallRestockOn) {
            notes.add(tr("gui.module_shop.mall.restock_status", ClientShopData.mallRestockTimeText,
                    Component.translatable("gui.module_shop.choice.restock_mode."
                            + ("game".equals(ClientShopData.mallRestockMode) ? "game" : "real")),
                    ClientShopData.mallRestockAmount));
        }
        if (!notes.isEmpty()) {
            float segEnd = this.segRects[this.segRects.length - 1][0] + this.segRects[this.segRects.length - 1][1] + 12;
            if (x + f.w() - 8 - segEnd > 32) {
                V2.text(c, String.join(" · ", notes), V2.FS_XS, IOStyle.TEXT_3, segEnd, cy, x + f.w() - 8 - segEnd,
                        Text.Align.RIGHT);
            }
        }
    }

    private void drawFilter(Canvas c, PageFrame f, float x, float y, float h) {
        filterRects.clear();
        filterKeys.clear();
        Draw.fill(c, x + FILTER_W - 0.5F, y, 0.5F, h, 0, IOStyle.BORDER_DIM);
        // 各模组数量按当前搜索 / 收藏 / 附加条件统计（只读查询，不修改 ClientShopData.mallCategory）
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<ClientShopData.MallView> all = applyClientFilters(ShopScreen.mallFiltered(""));
        for (ClientShopData.MallView v : all) {
            counts.merge(v.namespace(), 1, Integer::sum);
        }
        String activeCat = ClientShopData.mallCategory == null ? "" : ClientShopData.mallCategory;
        if (!activeCat.isEmpty() && counts.getOrDefault(activeCat, 0) == 0 && !all.isEmpty()) {
            activeCat = "";
            ClientShopData.mallCategory = "";
        }
        float cy = y + 8;
        V2.section(c, tr("gui.module_shop.v2.filter.mods"), x + 8, cy, FILTER_W - 12);
        cy += 6;
        boolean filtering = (ClientShopData.mallSearch != null && !ClientShopData.mallSearch.isBlank())
                || ClientShopData.mallFavOnly || onlyStock || affordable;
        List<String> mods = new ArrayList<>();
        mods.add("");
        for (String ns : ShopScreen.mallCategories()) {
            if (!filtering || counts.getOrDefault(ns, 0) > 0 || ns.equals(activeCat)) {
                mods.add(ns);
            }
        }
        float listTop = cy;
        float listBottom = y + h - 64;
        float listH = Math.max(28.0F, listBottom - listTop);
        float rowH = 13.0F;
        float pitch = 14.0F;
        float contentH = mods.isEmpty() ? 0.0F : (mods.size() * pitch - 1.0F);
        CAT_SCROLL.setContent(contentH, listH);
        CAT_SCROLL.update();
        this.modBarX = x;
        this.modBarY = listTop;
        this.modBarW = FILTER_W - 1.0F;
        this.modBarH = listH;
        float barReserve = CAT_SCROLL.canScroll() ? 6.0F : 0.0F;
        float itemW = FILTER_W - 8.0F - barReserve;
        int saved = c.save();
        Draw.clip(c, x, listTop, FILTER_W - 1.0F, listH);
        float off = CAT_SCROLL.offset();
        for (int i = 0; i < mods.size(); i++) {
            float iy = listTop + i * pitch - off;
            if (iy + rowH < listTop || iy > listBottom) {
                continue;
            }
            String ns = mods.get(i);
            String label = ns.isEmpty() ? tr("gui.module_shop.mall.cat_all") : ShopScreen.mallModName(ns);
            int n = ns.isEmpty() ? all.size() : counts.getOrDefault(ns, 0);
            boolean on = ns.equals(activeCat);
            float visTop = Math.max(listTop, iy);
            float visBot = Math.min(listBottom, iy + rowH);
            boolean over = f.mouseX() >= x + 4 && f.mouseX() < x + 4 + itemW
                    && f.mouseY() >= visTop && f.mouseY() < visBot;
            V2.filterItem(c, label, String.valueOf(n), on, over ? 1 : 0, x + 4, iy, itemW);
            filterRects.add(new float[]{x + 4, visTop, itemW, visBot - visTop});
            filterKeys.add("cat:" + ns);
        }
        c.restoreToCount(saved);
        CAT_SCROLL.drawBar(c, this.modBarX, this.modBarY, this.modBarW, this.modBarH, f.mouseX(), f.mouseY(), f.dtMs());

        cy = listBottom + 6;
        Draw.hairline(c, x + 6, cy, FILTER_W - 12, IOStyle.BORDER_DIM);
        cy += 8;
        V2.section(c, tr("gui.module_shop.v2.filter.title"), x + 8, cy, FILTER_W - 12);
        cy += 10;
        V2.check(c, tr("gui.module_shop.v2.filter.in_stock"), onlyStock, x + 8, cy, FILTER_W - 14, null);
        filterRects.add(new float[]{x + 4, cy - 6.5F, FILTER_W - 8, 13});
        filterKeys.add("stock");
        cy += 14;
        V2.check(c, tr("gui.module_shop.v2.filter.affordable"), affordable, x + 8, cy, FILTER_W - 14, null);
        filterRects.add(new float[]{x + 4, cy - 6.5F, FILTER_W - 8, 13});
        filterKeys.add("afford");
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        String buyLabel = tr("gui.module_shop.v2.buy");
        String soldOutLabel = tr("gui.module_shop.v2.sold_out");
        float btnColW = Math.max(36.0F, Math.max(V2.buttonWidth(buyLabel), V2.buttonWidth(soldOutLabel)) + 6.0F);
        boolean tight = w < 420.0F;
        float modW = tight ? 52.0F : 66.0F;
        float favW = tight ? 18.0F : 22.0F;
        float priceW = tight ? 68.0F : 84.0F;
        float stockW = tight ? 48.0F : 60.0F;
        float[][] cols = V2.columns(x, w, tight ? 6 : 8, tight ? 5 : 6, 14, 0, modW, favW, priceW, stockW, btnColW);
        String[] heads = {"", tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.mod"),
                tr("gui.module_shop.v2.col.fav"), tr("gui.module_shop.v2.col.price"), tr("gui.module_shop.v2.col.stock"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.CENTER, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h, cols, heads, al, new boolean[]{false, false, false, false, true, true, false});
        // 表头排序状态由 ClientShopData.mallSort 决定（1/2 = 价格升 / 降，3/4 = 库存升 / 降）
        int sort = ClientShopData.mallSort;
        TABLE.sortCol = sort == 1 || sort == 2 ? 4 : sort == 3 || sort == 4 ? 5 : -1;
        TABLE.sortDesc = sort == 2 || sort == 4;
        if (shown.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, TABLE.sortCol, TABLE.sortDesc);
            V2.empty(c, tr(ClientShopData.mall.isEmpty() ? "gui.module_shop.mall.empty" : "gui.module_shop.v2.no_match"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
            ClientShopData.MallView v = shown.get(row);
            float cy = ry + V2.ROW / 2;
            boolean sold = v.soldOut();
            Draw.fill(cv, cols[0][0] - 5, ry + 3, 1.5F, V2.ROW - 6, 0.75F, IOStyle.rarity(v.rarity));
            f.host().items().add(v.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
            String name = v.stack.getHoverName().getString();
            V2.text(cv, name, V2.FS, sold ? IOStyle.TEXT_3 : IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            V2.text(cv, ShopScreen.mallModName(v.namespace()), V2.FS_S, IOStyle.TEXT_3, cols[2][0], cy, cols[2][1],
                    Text.Align.LEFT);
            boolean fav = MallFavorites.contains(ShopScreen.mallItemId(v));
            V2.text(cv, fav ? "★" : "☆", V2.FS, fav ? IOStyle.ORANGE : IOStyle.TEXT_3, cols[3][0], cy, cols[3][1],
                    Text.Align.CENTER);
            PriceSpec spec = v.resolvePriceSpec();
            boolean can = PriceDisplay.canAfford(spec, 1);
            PriceDisplay.drawCell(cv, f.host().items(), spec, cols[4][0], cy, cols[4][1], Text.Align.RIGHT,
                    sold ? IOStyle.TEXT_3 : (can ? IOStyle.ORANGE : IOStyle.RED), sold);
            int stockColor = sold ? IOStyle.RED : v.unlimited() ? IOStyle.TEXT_2 : IOStyle.TEXT;
            V2.text(cv, ShopScreen.mallStockText(v).getString(), V2.FS_S, stockColor, cols[5][0], cy, cols[5][1],
                    Text.Align.RIGHT);
            float right = cols[6][0] + cols[6][1];
            if (sold) {
                TABLE.button(cv, row, "none", tr("gui.module_shop.v2.sold_out"), V2.Btn.DISABLED, right, ry, hover);
            } else {
                TABLE.button(cv, row, "buy", tr("gui.module_shop.v2.buy"), V2.Btn.NORMAL, right, ry, hover);
            }
        });
    }

    private void drawGrid(Canvas c, PageFrame f, float x, float y, float w, float h) {
        int sort = ClientShopData.mallSort;
        String priceLabel = tr("gui.module_shop.v2.col.price") + (sort == 1 ? " ↑" : sort == 2 ? " ↓" : "");
        String stockLabel = tr("gui.module_shop.v2.col.stock") + (sort == 3 ? " ↑" : sort == 4 ? " ↓" : "");
        String[] sortLabels = {tr("gui.module_shop.v2.sort.default"), priceLabel, stockLabel};
        int activeSort = (sort == 1 || sort == 2) ? 1 : (sort == 3 || sort == 4) ? 2 : 0;
        CardGrid.drawSortBar(c, x, y, w, f.mouseX(), f.mouseY(), sortLabels, activeSort, null, sortPillRects);

        GRID.layoutUnified(x, y + V2.HEAD, w, h - V2.HEAD);
        if (shown.isEmpty()) {
            V2.empty(c, tr(ClientShopData.mall.isEmpty() ? "gui.module_shop.empty" : "gui.module_shop.v2.no_match"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        GRID.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, card, cx, cy, cw, ch, hover) -> {
            ClientShopData.MallView v = shown.get(card);
            boolean sold = v.soldOut();
            int rc = IOStyle.rarity(v.rarity);
            boolean fav = MallFavorites.contains(ShopScreen.mallItemId(v));
            CardGrid.cardBg(cv, cx, cy, cw, ch, hover, rc, fav && !sold, sold);

            float starCx = cx + 7.5F;
            float starCy = cy + 7.0F;
            boolean starHover = f.mouseX() >= cx + 1 && f.mouseX() < cx + 14 && f.mouseY() >= cy + 1 && f.mouseY() < cy + 13;
            CardGrid.star(cv, starCx, starCy, starHover ? 3.8F : 3.2F, fav, fav ? IOStyle.ORANGE : (starHover ? IOStyle.TEXT : IOStyle.TEXT_3));
            GRID.hitZone(card, "star", cx + 1, cy + 1, 13, 13);

            String stockStr = sold
                    ? (v.permanentLimit ? tr("gui.module_shop.mall.permanent_sold_out") : tr("gui.module_shop.v2.sold_out"))
                    : (v.unlimited() ? "∞" : (v.permanentLimit ? tr("gui.module_shop.mall.permanent_short", V2.money(v.stock)) : V2.money(v.stock)));
            V2.text(cv, stockStr, V2.FS_XS, sold ? IOStyle.RED : IOStyle.TEXT_3,
                    cx + 15.0F, cy + 7.0F, cw - 19.0F, Text.Align.RIGHT);

            float iconSize = 16.0F;
            f.host().items().add(v.stack, cx + (cw - iconSize) * 0.5F, cy + 13.0F, iconSize, true);

            V2.text(cv, v.stack.getHoverName().getString(), V2.FS_S, sold ? IOStyle.TEXT_3 : IOStyle.TEXT,
                    cx + 3, cy + 34.5F, cw - 6, Text.Align.CENTER);
            if (sold) {
                V2.bold(cv, tr("gui.module_shop.v2.sold_out"), V2.FS, IOStyle.TEXT_3,
                        cx + 3, cy + 45.0F, cw - 6, Text.Align.CENTER);
            } else {
                PriceSpec spec = v.resolvePriceSpec();
                boolean can = PriceDisplay.canAfford(spec, 1);
                PriceDisplay.drawCard(cv, f.host().items(), spec, cx + 3, cy + 45.0F, cx + cw * 0.5F, cw - 6, 1.0F,
                        can ? IOStyle.ORANGE : IOStyle.RED, false);
            }
        });
    }

    private void drawRecent(Canvas c, PageFrame f, float x, float y, float w, float h) {
        List<ClientShopData.MallPurchaseView> rawList = ClientShopData.mallRecent;
        String query = ClientShopData.mallSearch == null ? "" : ClientShopData.mallSearch.trim();
        List<ClientShopData.MallPurchaseView> list;
        if (query.isEmpty()) {
            list = rawList;
        } else {
            list = new ArrayList<>();
            for (ClientShopData.MallPurchaseView r : rawList) {
                if (PageKit.matchesQuery(query, r.playerName, ShopScreen.mallTimeText(r.time))
                        || PageKit.matchesItemQuery(r.stack, query)) {
                    list.add(r);
                }
            }
        }
        boolean tight = w < 520.0F;
        float[][] cols = V2.columns(x, w, tight ? 6 : 8, tight ? 5 : 6,
                tight ? 56 : 68, tight ? 64 : 86, 14, 0, tight ? 36 : 48, tight ? 64 : 76, tight ? 68 : 84);
        String[] heads = {tr("gui.module_shop.v2.col.time"), tr("gui.module_shop.v2.col.player"), "",
                tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.count"), tr("gui.module_shop.v2.col.price"),
                tr("gui.module_shop.v2.col.total")};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        RECENT.layout(x, y, w, h, cols, heads, al, null);
        if (list.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, -1, false);
            V2.empty(c, tr(rawList.isEmpty() ? "gui.module_shop.mall.recent_empty" : "gui.module_shop.search.empty"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
        } else {
            RECENT.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), list.size(), (cv, row, ry, hover) -> {
                ClientShopData.MallPurchaseView r = list.get(row);
                float cy = ry + V2.ROW / 2;
                V2.text(cv, ShopScreen.mallTimeText(r.time), V2.FS_S, IOStyle.TEXT_2, cols[0][0], cy, cols[0][1], Text.Align.LEFT);
                V2.text(cv, r.playerName, V2.FS_S, IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
                f.host().items().add(r.stack, cols[2][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, false);
                V2.text(cv, r.stack.getHoverName().getString(), V2.FS, IOStyle.TEXT, cols[3][0], cy, cols[3][1], Text.Align.LEFT);
                V2.text(cv, "×" + r.count, V2.FS_S, IOStyle.TEXT_2, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
                PriceDisplay.drawCell(cv, f.host().items(), r.resolveUnitSpec(), cols[5][0], cy, cols[5][1],
                        Text.Align.RIGHT, IOStyle.TEXT_2, false);
                PriceDisplay.drawCell(cv, f.host().items(), r.resolveTotalSpec(), cols[6][0], cy, cols[6][1],
                        Text.Align.RIGHT, IOStyle.ORANGE, false);
            });
        }
        String footText = !query.isEmpty() && !rawList.isEmpty()
                ? tr("gui.module_shop.search.match_count", list.size(), rawList.size())
                : tr("gui.module_shop.v2.mall.recent_count", list.size());
        V2.foot(c, x, y + h, w, footText, null);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (search != null && search.isVisible() && search.mouseClicked(mx, my, button)) {
            return true;
        }
        if (search != null && search.isFocused()) {
            search.blur();
        }
        if (button == 0) {
            for (int i = 0; i < segRects.length; i++) {
                if (mx >= segRects[i][0] && mx < segRects[i][0] + segRects[i][1] && my >= segY && my < segY + V2.CTRL) {
                    clickSegment(f, i);
                    return true;
                }
            }
        }
        if (ClientShopData.mallViewRecent) {
            return button == 0 && RECENT.pressBar(mx, my);
        }
        if (button == 0) {
            if (CAT_SCROLL.pressBar(mx, my, this.modBarX, this.modBarY, this.modBarW, this.modBarH)) {
                return true;
            }
            for (int i = 0; i < filterRects.size(); i++) {
                float[] r = filterRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    clickFilter(f, filterKeys.get(i));
                    return true;
                }
            }
            if (UIViewMode.isGrid() ? GRID.pressBar(mx, my) : TABLE.pressBar(mx, my)) {
                return true;
            }
            if (UIViewMode.isGrid()) {
                for (int i = 0; i < sortPillRects.size(); i++) {
                    float[] r = sortPillRects.get(i);
                    if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                        int cur = ClientShopData.mallSort;
                        int next = switch (i) {
                            case 1 -> cur == 1 ? 2 : 1;
                            case 2 -> cur == 3 ? 4 : 3;
                            default -> 0;
                        };
                        CompoundTag extra = new CompoundTag();
                        extra.putInt("sort", next);
                        ClickSound.play();
                        f.host().action("mall_sort_pick", extra);
                        GRID.scrollToTop();
                        return true;
                    }
                }
            }
        }
        if (UIViewMode.isGrid()) {
            CardGrid.Hit hit = GRID.click(mx, my);
            if (hit == null || hit.index() < 0 || hit.index() >= shown.size()) {
                return hit != null;
            }
            ClientShopData.MallView v = shown.get(hit.index());
            CompoundTag extra = new CompoundTag();
            extra.putInt("index", v.shelf);
            if ("star".equals(hit.button())) {
                if (button == 0) {
                    f.host().action("mall_star", extra);
                }
                return true;
            }
            if (button == 1) {
                if (!v.soldOut()) {
                    extra.putString("pay_channel", PriceDisplay.resolveBuyChannel(v.resolvePriceSpec(), 1));
                    ClickSound.play();
                    f.host().action("mall_buy_many", extra);
                }
                return true;
            }
            if (button == 0 && !v.soldOut() && ("buy".equals(hit.button()) || hit.button() == null)) {
                extra.putString("pay_channel", PriceDisplay.resolveBuyChannel(v.resolvePriceSpec(), 1));
                ClickSound.play();
                f.host().action("mall_buy", extra);
                return true;
            }
            return hit.button() != null;
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null) {
            return false;
        }
        if ("sort".equals(hit.button())) {
            // 表头：单价 / 库存，升降切换 → 映射回原有的 mall_sort_pick
            int sort = TABLE.sortCol == 4 ? (TABLE.sortDesc ? 2 : 1) : TABLE.sortCol == 5 ? (TABLE.sortDesc ? 4 : 3) : 0;
            CompoundTag extra = new CompoundTag();
            extra.putInt("sort", sort);
            ClickSound.play();
            f.host().action("mall_sort_pick", extra);
            return true;
        }
        if (hit.row() < 0 || hit.row() >= shown.size()) {
            return false;
        }
        ClientShopData.MallView v = shown.get(hit.row());
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", v.shelf);
        float[][] cols = TABLE.cols();
        boolean onStar = mx >= cols[3][0] && mx < cols[3][0] + cols[3][1];
        if (onStar) {
            if (button == 0) {
                f.host().action("mall_star", extra);
            }
            return true;
        }
        if (button == 1) {
            if (!v.soldOut()) {
                extra.putString("pay_channel", PriceDisplay.resolveBuyChannel(v.resolvePriceSpec(), 1));
                ClickSound.play();
                f.host().action("mall_buy_many", extra);
            }
            return true;
        }
        if (button == 0 && "buy".equals(hit.button())) {
            extra.putString("pay_channel", PriceDisplay.resolveBuyChannel(v.resolvePriceSpec(), 1));
            ClickSound.play();
            f.host().action("mall_buy", extra);
            return true;
        }
        return hit.button() != null;
    }

    private void clickSegment(PageFrame f, int index) {
        ClickSound.play();
        boolean recent = ClientShopData.mallViewRecent;
        switch (index) {
            case 0 -> {
                if (recent) {
                    f.host().action("mall_recent_toggle", null);
                }
                if (ClientShopData.mallFavOnly) {
                    f.host().action("mall_fav_toggle", null);
                }
            }
            case 1 -> {
                if (recent) {
                    f.host().action("mall_recent_toggle", null);
                }
                if (!ClientShopData.mallFavOnly) {
                    f.host().action("mall_fav_toggle", null);
                }
            }
            default -> {
                if (!recent) {
                    f.host().action("mall_recent_toggle", null);
                }
            }
        }
        TABLE.scrollToTop();
        GRID.scrollToTop();
    }

    private void clickFilter(PageFrame f, String key) {
        ClickSound.play();
        if (key.startsWith("cat:")) {
            CompoundTag extra = new CompoundTag();
            extra.putString("category", key.substring(4));
            f.host().action("mall_cat_pick", extra);
        } else if ("stock".equals(key)) {
            onlyStock = !onlyStock;
        } else if ("afford".equals(key)) {
            affordable = !affordable;
        }
        TABLE.scrollToTop();
        GRID.scrollToTop();
    }

    @Override
    public void clickedOutside() {
        if (search != null && search.isFocused()) {
            search.blur();
        }
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        if (search != null) {
            search.mouseReleased(mx, my, button);
        }
        return CAT_SCROLL.releaseBar() | RECENT.releaseBar() | (UIViewMode.isGrid() ? GRID.releaseBar() : TABLE.releaseBar());
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        if (!ClientShopData.mallViewRecent && CAT_SCROLL.dragBar(my, this.modBarH)) {
            return true;
        }
        return ClientShopData.mallViewRecent ? RECENT.dragBar(my) : (UIViewMode.isGrid() ? GRID.dragBar(my) : TABLE.dragBar(my));
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (!ClientShopData.mallViewRecent && mx >= this.modBarX && mx < this.modBarX + this.modBarW
                && my >= this.modBarY && my < this.modBarY + this.modBarH && CAT_SCROLL.canScroll()) {
            CAT_SCROLL.wheel(scrollY);
            return true;
        }
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        return ClientShopData.mallViewRecent ? RECENT.wheel(mx, my, scrollY, page)
                : (UIViewMode.isGrid() ? GRID.wheel(mx, my, scrollY, page) : TABLE.wheel(mx, my, scrollY, page));
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        CAT_SCROLL.releaseBar();
        TABLE.releaseBar();
        GRID.releaseBar();
        RECENT.releaseBar();
    }
}
