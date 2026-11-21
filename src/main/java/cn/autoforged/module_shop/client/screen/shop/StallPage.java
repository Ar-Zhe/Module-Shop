package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.StallClient;
import cn.autoforged.module_shop.client.UIViewMode;
import cn.autoforged.module_shop.client.screen.BillScreen;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.stall.StallListScreen;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.CardGrid;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.StallEntry;
import cn.autoforged.module_shop.shop.auction.AuctionCategory;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 玩家摊位（UI v2）：两个视图用分段切换。
 * <ul>
 *     <li><b>商店大厅</b>：全服摊位商品一张表（物品 · 卖家 · 单价 · 比价 · 库存 · 已售 · 分类 · 去购买），左侧分类（搜索收敛且独立滚动），
 *     表头单价排序；「比价」是客户端对同名商品取最低价算出来的（全服最低 / +x%）；点行进入卖家摊位并预选这件；</li>
 *     <li><b>我的商店</b>：物品 · 单价 · 库存 · 已售 · 销售额 · 剩余上架时间 · 改价 / 补货 / 下架，顶部搜索与统计 +「上架商品 / 交易记录」。</li>
 * </ul>
 */
public final class StallPage implements ShopPage {
    private static final float FILTER_W = 96.0F;
    private static final ListTable HALL = new ListTable();
    private static final ListTable MINE = new ListTable();
    private static final CardGrid HALL_GRID = new CardGrid();
    private static final CardGrid MINE_GRID = new CardGrid();
    private static final ScrollController CAT_SCROLL = new ScrollController();
    private static String mineSearch = "";

    private SkiaTextField search;
    private List<StallClient.Item> hallShown = List.of();
    private List<Integer> mineShown = List.of();
    private Map<String, Long> lowest = new HashMap<>();
    private float catClipY;
    private float catClipH;
    private float[][] segRects = new float[0][2];
    private float segY;
    private final List<float[]> btnRects = new ArrayList<>();
    private final List<Runnable> btnRuns = new ArrayList<>();
    private final List<float[]> sortPillRects = new ArrayList<>();

    @Override
    public String id() {
        return "stall";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 48)
                .placeholder(tr(StallClient.hallView ? "gui.module_shop.stall.search_hint" : "gui.module_shop.search.placeholder"))
                .clearable(true)
                .fontSize(V2.FS_S)
                .onChange(t -> {
                    String val = t == null ? "" : t;
                    if (StallClient.hallView) {
                        StallClient.search = val;
                        HALL.scrollToTop();
                        HALL_GRID.scrollToTop();
                        CAT_SCROLL.reset();
                    } else {
                        mineSearch = val;
                        MINE.scrollToTop();
                        MINE_GRID.scrollToTop();
                    }
                });
        search.setValue(StallClient.hallView ? StallClient.search : mineSearch);
        host.registerInput(search.editBox());
    }

    private void syncSearchField() {
        if (search == null) {
            return;
        }
        search.placeholder(tr(StallClient.hallView ? "gui.module_shop.stall.search_hint" : "gui.module_shop.search.placeholder"));
        search.setValue(StallClient.hallView ? StallClient.search : mineSearch);
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private float btn(Canvas c, PageFrame f, String label, V2.Btn kind, float right, float y, Runnable run) {
        float w = V2.buttonWidth(label);
        float x = right - w;
        V2.button(c, label, kind, x, y, w, V2.CTRL, over(f, x, y, w, V2.CTRL) ? 1 : 0);
        btnRects.add(new float[]{x, y, w, V2.CTRL});
        btnRuns.add(run);
        return x - 5;
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        btnRects.clear();
        btnRuns.clear();
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.stall"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        String[] seg = {tr("gui.module_shop.stall.tab_hall"), tr("gui.module_shop.stall.tab_mine") + "  " + ClientShopData.stall.size()};
        segY = by;
        segRects = V2.segmented(c, seg, StallClient.hallView ? 0 : 1, x + 8 + tw + 10, by, null);
        float afterSeg = segRects[1][0] + segRects[1][1] + 8;
        if (StallClient.hallView) {
            renderHall(c, f, afterSeg, by);
        } else {
            renderMine(c, f, afterSeg, by);
        }
    }

    // ------------------------------------------------------------------ 商店大厅

    private void renderHall(Canvas c, PageFrame f, float sx, float by) {
        StallClient.ensureMarket();
        float x = f.x();
        float right = x + f.w() - 8;
        right = btn(c, f, tr("gui.module_shop.v2.stall.owners"), V2.Btn.NORMAL, right, by, () -> f.host().action("open_avatar", null));
        float avail = right - 8 - sx;
        float afterSearch = sx;
        if (search != null) {
            if (avail >= 48.0F) {
                float sw = Math.min(160.0F, Math.max(48.0F, avail >= 210.0F ? 130.0F : avail));
                search.visible(true).bounds(sx, by, sw, V2.CTRL);
                search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
                afterSearch = sx + sw + 8.0F;
            } else {
                search.visible(false);
            }
        }
        java.util.Set<java.util.UUID> owners = new java.util.HashSet<>();
        for (StallClient.Item it : StallClient.market) {
            if (it.owner != null) {
                owners.add(it.owner);
            }
        }
        if (right - 8 - afterSearch >= 65.0F) {
            V2.text(c, tr("gui.module_shop.v2.stall.summary", StallClient.market.size(), owners.size()), V2.FS_XS, IOStyle.TEXT_2,
                    afterSearch, f.y() + V2.BAR / 2, right - 8 - afterSearch, Text.Align.RIGHT);
        }

        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        drawCategories(c, f, x, top, bottom - top);
        hallShown = StallClient.filtered();
        lowest = new HashMap<>();
        for (StallClient.Item it : StallClient.market) {
            if (it.stock > 0) {
                PriceSpec spec = it.resolvePriceSpec();
                long amt = spec.supportsPhys() ? spec.physCount : spec.starPrice;
                if (amt > 0L) {
                    lowest.merge(compareKey(it.stack, spec), amt, Math::min);
                }
            }
        }
        float tx = x + FILTER_W;
        float tw = f.w() - FILTER_W;
        if (UIViewMode.isGrid()) {
            drawHallGrid(c, f, tx, top, tw, bottom - top);
            V2.foot(c, tx, bottom, tw, tr("gui.module_shop.v2.showing", HALL_GRID.firstVisible(), HALL_GRID.lastVisible(), hallShown.size()),
                    tr("gui.module_shop.v2.stall.hall_hint"));
            return;
        }
        float actW = Math.max(46.0F, Math.max(V2.buttonWidth(tr("gui.module_shop.v2.stall.manage")),
                V2.buttonWidth(tr("gui.module_shop.v2.stall.go"))));
        float gap = 5.0F;
        float[][] baseCols = V2.columns(tx, tw - actW - gap, 10, gap, 14, 0, 58, 56, 48, 40, 36, 42);
        float[][] cols = new float[9][2];
        System.arraycopy(baseCols, 0, cols, 0, 8);
        cols[8][0] = tx + tw - 10 - actW;
        cols[8][1] = actW;
        String[] heads = {"", tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.seller"), tr("gui.module_shop.v2.col.price"),
                tr("gui.module_shop.v2.col.compare"), tr("gui.module_shop.v2.col.stock"), tr("gui.module_shop.v2.col.sold"),
                tr("gui.module_shop.v2.col.category"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.LEFT, Text.Align.RIGHT};
        HALL.layout(tx, top, tw, bottom - top, cols, heads, al, new boolean[]{false, false, false, true, false, false, false, false, false});
        HALL.sortCol = StallClient.sort == 0 ? -1 : 3;
        HALL.sortDesc = StallClient.sort == 2;
        if (hallShown.isEmpty()) {
            V2.tableHead(c, tx, top, tw, cols, heads, al, HALL.sortCol, HALL.sortDesc);
            V2.empty(c, tr(StallClient.market.isEmpty() ? "gui.module_shop.stall.hall_empty" : "gui.module_shop.v2.no_match"),
                    tx, top + V2.HEAD, tw, bottom - top - V2.HEAD);
        } else {
            HALL.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), hallShown.size(), (cv, row, ry, hover) -> {
                StallClient.Item it = hallShown.get(row);
                float cy = ry + V2.ROW / 2;
                boolean sold = it.stock <= 0;
                f.host().items().add(it.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
                String mineTag = tr("gui.module_shop.stall.mine_tag");
                float tagReserve = it.own ? (V2.tagWidth(mineTag) + 4.0F) : 0.0F;
                float nameMaxW = Math.max(16.0F, cols[1][1] - tagReserve);
                float nw = V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(it.stack), V2.FS, sold ? IOStyle.TEXT_3 : IOStyle.TEXT, cols[1][0],
                        cy, nameMaxW, Text.Align.LEFT);
                if (it.own) {
                    V2.tag(cv, mineTag, IOStyle.BLUE, cols[1][0] + Math.min(nw, nameMaxW) + 4, cy);
                }
                V2.text(cv, it.ownerName, V2.FS_S, it.own ? IOStyle.BLUE : IOStyle.TEXT_2, cols[2][0], cy, cols[2][1], Text.Align.LEFT);
                PriceSpec spec = it.resolvePriceSpec();
                boolean isPhys = spec.supportsPhys();
                long unitAmt = isPhys ? spec.physCount : spec.starPrice;
                PriceDisplay.drawCell(cv, f.host().items(), spec, cols[3][0], cy, cols[3][1], Text.Align.RIGHT,
                        sold ? IOStyle.TEXT_3 : IOStyle.ORANGE, sold);
                Long low = lowest.get(compareKey(it.stack, spec));
                if (sold || unitAmt <= 0L || low == null || low <= 0L) {
                    V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
                } else if (unitAmt <= low) {
                    float tw2 = V2.tagWidth(tr("gui.module_shop.v2.stall.lowest"));
                    V2.tag(cv, tr("gui.module_shop.v2.stall.lowest"), IOStyle.GREEN, cols[4][0] + cols[4][1] - tw2 + 3, cy);
                } else {
                    long pct = Math.round((unitAmt - low) * 100.0 / low);
                    V2.text(cv, "+" + pct + "%", V2.FS_S, IOStyle.RED, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
                }
                V2.text(cv, sold ? tr("gui.module_shop.v2.sold_out") : V2.money(it.stock), V2.FS_S, sold ? IOStyle.RED : IOStyle.TEXT,
                        cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
                V2.text(cv, V2.money(it.sold), V2.FS_S, IOStyle.TEXT_3, cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
                V2.text(cv, tr(StallClient.categoryOf(it).langKey()), V2.FS_XS, IOStyle.TEXT_3, cols[7][0], cy, cols[7][1], Text.Align.LEFT);
                HALL.button(cv, row, "open", tr(it.own ? "gui.module_shop.v2.stall.manage" : "gui.module_shop.v2.stall.go"),
                        it.own ? V2.Btn.GHOST : V2.Btn.NORMAL, cols[8][0] + cols[8][1], ry, hover);
            });
        }
        V2.foot(c, tx, bottom, tw, tr("gui.module_shop.v2.showing", HALL.firstVisible(), HALL.lastVisible(), hallShown.size()),
                tr("gui.module_shop.v2.stall.hall_hint"));
    }

    private void drawHallGrid(Canvas c, PageFrame f, float tx, float top, float tw, float th) {
        int sort = StallClient.sort;
        String priceLabel = tr("gui.module_shop.v2.col.price") + (sort == 1 ? " ↑" : sort == 2 ? " ↓" : "");
        String[] sortLabels = {tr("gui.module_shop.v2.sort.default"), priceLabel};
        CardGrid.drawSortBar(c, tx, top, tw, f.mouseX(), f.mouseY(), sortLabels, sort == 0 ? 0 : 1, null, sortPillRects);

        HALL_GRID.layoutUnified(tx, top + V2.HEAD, tw, th - V2.HEAD);
        if (hallShown.isEmpty()) {
            V2.empty(c, tr(StallClient.market.isEmpty() ? "gui.module_shop.stall.hall_empty" : "gui.module_shop.v2.no_match"),
                    tx, top + V2.HEAD, tw, th - V2.HEAD);
            return;
        }
        HALL_GRID.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), hallShown.size(), (cv, card, cx, cy, cw, ch, hover) -> {
            StallClient.Item it = hallShown.get(card);
            boolean sold = it.stock <= 0;
            CardGrid.cardBg(cv, cx, cy, cw, ch, hover, it.own ? IOStyle.BLUE : 0, it.own, sold);

            PriceSpec spec = it.resolvePriceSpec();
            boolean isPhys = spec.supportsPhys();
            long unitAmt = isPhys ? spec.physCount : spec.starPrice;
            Long low = lowest.get(compareKey(it.stack, spec));
            if (!sold && unitAmt > 0L && low != null && low > 0L && unitAmt <= low) {
                V2.bold(cv, tr("gui.module_shop.v2.stall.lowest"), V2.FS_XS, IOStyle.GREEN, cx + 4, cy + 7.0F, cw * 0.48F, Text.Align.LEFT);
            } else if (!sold && unitAmt > 0L && low != null && low > 0L) {
                long pct = Math.round((unitAmt - low) * 100.0 / low);
                V2.text(cv, "+" + pct + "%", V2.FS_XS, IOStyle.RED, cx + 4, cy + 7.0F, cw * 0.48F, Text.Align.LEFT);
            } else {
                V2.text(cv, it.ownerName, V2.FS_XS, it.own ? IOStyle.BLUE : IOStyle.TEXT_3,
                        cx + 4, cy + 7.0F, cw * 0.52F, Text.Align.LEFT);
            }
            V2.text(cv, sold ? tr("gui.module_shop.v2.sold_out") : V2.money(it.stock), V2.FS_XS,
                    sold ? IOStyle.RED : IOStyle.TEXT_3, cx + cw * 0.48F, cy + 7.0F, cw * 0.52F - 4, Text.Align.RIGHT);

            float iconSize = 16.0F;
            f.host().items().add(it.stack, cx + (cw - iconSize) * 0.5F, cy + 13.0F, iconSize, true);

            V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(it.stack), V2.FS_S, sold ? IOStyle.TEXT_3 : IOStyle.TEXT,
                    cx + 3, cy + 34.5F, cw - 6, Text.Align.CENTER);
            PriceDisplay.drawCard(cv, f.host().items(), spec, cx + 3, cy + 45.0F, cx + cw * 0.5F, cw - 6, 1.0F,
                    sold ? IOStyle.TEXT_3 : IOStyle.ORANGE, sold);
        });
    }

    private final List<float[]> catRects = new ArrayList<>();
    private final List<AuctionCategory> catKeys = new ArrayList<>();

    private void drawCategories(Canvas c, PageFrame f, float x, float y, float h) {
        catRects.clear();
        catKeys.clear();
        Draw.fill(c, x + FILTER_W - 0.5F, y, 0.5F, h, 0, IOStyle.BORDER_DIM);
        Map<AuctionCategory, Integer> counts = StallClient.searchCategoryCounts();
        boolean searching = StallClient.search != null && !StallClient.search.isBlank();
        int allMatches = counts.getOrDefault(AuctionCategory.ALL, 0);
        if (searching && StallClient.category != AuctionCategory.ALL
                && counts.getOrDefault(StallClient.category, 0) == 0 && allMatches > 0) {
            StallClient.category = AuctionCategory.ALL;
            StallClient.page = 0;
            HALL.scrollToTop();
            HALL_GRID.scrollToTop();
            CAT_SCROLL.reset();
        }
        List<AuctionCategory> visibleCats = new ArrayList<>();
        for (AuctionCategory cat : AuctionCategory.values()) {
            if (cat == AuctionCategory.ALL || !searching || counts.getOrDefault(cat, 0) > 0) {
                visibleCats.add(cat);
            }
        }
        V2.section(c, tr("gui.module_shop.v2.filter.category"), x + 8, y + 8, FILTER_W - 12);
        float catTop = y + 16.0F;
        float catViewH = Math.max(28.0F, y + h - 4.0F - catTop);
        catClipY = catTop;
        catClipH = catViewH;
        CAT_SCROLL.setContent(visibleCats.size() * 14.0F, catViewH);
        CAT_SCROLL.update();
        float off = CAT_SCROLL.offset();
        float itemW = FILTER_W - (CAT_SCROLL.canScroll() ? 11.0F : 8.0F);
        int saved = c.save();
        Draw.clip(c, x, catTop, FILTER_W - 1.0F, catViewH);
        try {
            for (int i = 0; i < visibleCats.size(); i++) {
                AuctionCategory cat = visibleCats.get(i);
                float iy = catTop + i * 14.0F - off;
                if (iy + 13.0F <= catTop || iy >= catTop + catViewH) {
                    continue;
                }
                int n = counts.getOrDefault(cat, 0);
                boolean hover = f.mouseY() >= catTop && f.mouseY() < catTop + catViewH && over(f, x + 4, iy, itemW, 13);
                V2.filterItem(c, tr(cat.langKey()), String.valueOf(n), cat == StallClient.category,
                        hover ? 1 : 0, x + 4, iy, itemW);
                catRects.add(new float[]{x + 4, iy, itemW, 13});
                catKeys.add(cat);
            }
        } finally {
            c.restoreToCount(saved);
        }
        CAT_SCROLL.drawBar(c, x, catTop, FILTER_W - 1.0F, catViewH, f.mouseX(), f.mouseY(), f.dtMs());
    }

    // ------------------------------------------------------------------ 我的商店

    private void renderMine(Canvas c, PageFrame f, float sx, float by) {
        float x = f.x();
        List<StallEntry> stall = ClientShopData.stall;
        float right = x + f.w() - 8;
        right = btn(c, f, tr("gui.module_shop.stall_create"), V2.Btn.PRIMARY, right, by, () -> Nav.open(new StallListScreen()));
        right = btn(c, f, tr("gui.module_shop.stall.records"), V2.Btn.NORMAL, right, by, () -> Nav.open(BillScreen.stallRecords()));
        float avail = right - 8 - sx;
        float afterSearch = sx;
        if (search != null) {
            if (avail >= 48.0F) {
                float sw = Math.min(150.0F, Math.max(48.0F, avail >= 210.0F ? 120.0F : avail));
                search.visible(true).bounds(sx, by, sw, V2.CTRL);
                search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
                afterSearch = sx + sw + 8.0F;
            } else {
                search.visible(false);
            }
        }
        int totalSold = 0;
        long starRev = 0;
        Map<String, Long> physRev = new LinkedHashMap<>();
        List<Integer> nextMineShown = new ArrayList<>();
        for (int i = 0; i < stall.size(); i++) {
            StallEntry e = stall.get(i);
            totalSold += e.sold;
            if (e.sold > 0) {
                PriceDisplay.addTotals(e.resolvePriceSpec(), e.sold, new long[]{0L}, physRev);
                if (e.resolvePriceSpec().supportsStar()) {
                    starRev += e.sold * e.resolvePriceSpec().starPrice;
                }
            }
            if (StallClient.matchesEntry(e, mineSearch)) {
                nextMineShown.add(i);
            }
        }
        mineShown = nextMineShown;
        String revText = PriceDisplay.formatLedgerSummary(starRev, physRev);
        String expire = "permanent".equals(String.valueOf(ClientShopData.stallExpireMode))
                ? tr("gui.module_shop.v2.stall.permanent") : tr("gui.module_shop.v2.stall.timed");
        if (right - 8 - afterSearch >= 65.0F) {
            V2.text(c, tr("gui.module_shop.v2.stall.mine_stats", stall.size(), StallClient.slots, V2.money(totalSold),
                            revText, String.format("%.0f", StallClient.feePercent), expire),
                    V2.FS_XS, IOStyle.TEXT_2, afterSearch, f.y() + V2.BAR / 2, right - 8 - afterSearch, Text.Align.RIGHT);
        }

        boolean searchingMine = mineSearch != null && !mineSearch.isBlank();
        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        if (UIViewMode.isGrid()) {
            drawMineGrid(c, f, x, top, f.w(), bottom - top, stall, searchingMine);
            V2.foot(c, x, bottom, f.w(), tr("gui.module_shop.stall.mine_summary", stall.size(), StallClient.slots, totalSold),
                    tr("gui.module_shop.v2.stall.mine_hint"));
            return;
        }
        float actW = V2.buttonWidth(tr("gui.module_shop.v2.stall.remove"))
                + V2.buttonWidth(tr("gui.module_shop.v2.stall.restock"))
                + V2.buttonWidth(tr("gui.module_shop.v2.stall.reprice")) + 8.0F;
        float gap = 5.0F;
        float[][] baseCols = V2.columns(x, f.w() - actW - gap, 10, gap, 14, 0, 62, 62, 42, 64, 56);
        float[][] cols = new float[8][2];
        System.arraycopy(baseCols, 0, cols, 0, 7);
        cols[7][0] = x + f.w() - 10 - actW;
        cols[7][1] = actW;
        String[] heads = {"", tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.price"), tr("gui.module_shop.v2.col.stock"),
                tr("gui.module_shop.v2.col.sold"), tr("gui.module_shop.v2.col.revenue"), tr("gui.module_shop.v2.col.remain"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        MINE.layout(x, top, f.w(), bottom - top, cols, heads, al, null);
        int rows = searchingMine ? mineShown.size() : Math.max(mineShown.size(), StallClient.slots);
        if (rows <= 0) {
            V2.tableHead(c, x, top, f.w(), cols, heads, al, -1, false);
            V2.empty(c, tr(searchingMine ? "gui.module_shop.v2.no_match" : "gui.module_shop.stall.mine_empty"),
                    x, top + V2.HEAD, f.w(), bottom - top - V2.HEAD);
        } else {
            MINE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), rows, (cv, row, ry, hover) -> {
                float cy = ry + V2.ROW / 2;
                if (row >= mineShown.size()) {
                    Draw.stroke(cv, cols[0][0], ry + 1.5F, V2.ICON, V2.ICON, 2, 0.5F, IOStyle.BORDER_DIM);
                    V2.text(cv, tr("gui.module_shop.v2.stall.empty_slot"), V2.FS_S, IOStyle.TEXT_3, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
                    return;
                }
                StallEntry e = stall.get(mineShown.get(row));
                boolean sold = e.stock <= 0;
                f.host().items().add(e.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
                V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(e.stack), V2.FS, IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
                PriceSpec spec = e.resolvePriceSpec();
                PriceDisplay.drawCell(cv, f.host().items(), spec, cols[2][0], cy, cols[2][1], Text.Align.RIGHT,
                        IOStyle.ORANGE, false);
                float bw = Math.min(26.0F, cols[3][1] * 0.34F);
                V2.bar(cv, cols[3][0] + cols[3][1] - bw, cy, bw, e.stock / (float) Math.max(1, StallClient.maxStock), V2.levelColor(
                        e.stock / (float) Math.max(1, StallClient.maxStock)));
                V2.text(cv, sold ? tr("gui.module_shop.v2.sold_out") : String.valueOf(e.stock), V2.FS_S, sold ? IOStyle.RED : IOStyle.TEXT,
                        cols[3][0], cy, Math.max(12.0F, cols[3][1] - bw - 4), Text.Align.RIGHT);
                V2.text(cv, V2.money(e.sold), V2.FS_S, IOStyle.TEXT_2, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
                if (e.sold > 0) {
                    PriceDisplay.drawCell(cv, f.host().items(), spec.scale(e.sold), cols[5][0], cy, cols[5][1],
                            Text.Align.RIGHT, IOStyle.GREEN, false, "+");
                } else {
                    V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
                }
                String remain = PageKit.remainText(ClientShopData.stallExpireMode, ClientShopData.stallExpireAmount, e.listedGame, e.listedReal);
                V2.text(cv, remain == null ? tr("gui.module_shop.v2.stall.permanent") : remain, V2.FS_S,
                        remain == null ? IOStyle.TEXT_3 : IOStyle.ORANGE, cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
                float bx = cols[7][0] + cols[7][1];
                bx = MINE.button(cv, row, "remove", tr("gui.module_shop.v2.stall.remove"), V2.Btn.DANGER, bx, ry, hover);
                bx = MINE.button(cv, row, "restock", tr("gui.module_shop.v2.stall.restock"), V2.Btn.NORMAL, bx, ry, hover);
                MINE.button(cv, row, "edit", tr("gui.module_shop.v2.stall.reprice"), V2.Btn.GHOST, bx, ry, hover);
            });
        }
        V2.foot(c, x, bottom, f.w(), tr("gui.module_shop.stall.mine_summary", stall.size(), StallClient.slots, totalSold),
                tr("gui.module_shop.v2.stall.mine_hint"));
    }

    private void drawMineGrid(Canvas c, PageFrame f, float x, float top, float w, float h, List<StallEntry> stall, boolean searchingMine) {
        int count = searchingMine ? mineShown.size() : Math.max(mineShown.size(), StallClient.slots);
        MINE_GRID.layoutUnified(x, top, w, h);
        if (count <= 0) {
            V2.empty(c, tr(searchingMine ? "gui.module_shop.v2.no_match" : "gui.module_shop.stall.mine_empty"), x, top, w, h);
            return;
        }
        MINE_GRID.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), count, (cv, card, cx, cy, cw, ch, hover) -> {
            if (card >= mineShown.size()) {
                CardGrid.cardBg(cv, cx, cy, cw, ch, hover, 0, false, true);
                float box = 16.0F;
                float bx = cx + (cw - box) * 0.5F;
                float by = cy + 13.0F;
                Draw.stroke(cv, bx, by, box, box, 3.0F, 0.55F, hover > 0.4F ? IOStyle.BLUE : IOStyle.BORDER);
                V2.bold(cv, "+", V2.FS_T, hover > 0.4F ? IOStyle.BLUE : IOStyle.TEXT_3, bx, by + box * 0.5F, box, Text.Align.CENTER);
                V2.text(cv, tr("gui.module_shop.v2.stall.empty_slot"), V2.FS_XS, IOStyle.TEXT_3,
                        cx + 3, cy + 37.5F, cw - 6, Text.Align.CENTER);
                return;
            }
            StallEntry e = stall.get(mineShown.get(card));
            boolean sold = e.stock <= 0;
            CardGrid.cardBg(cv, cx, cy, cw, ch, hover, sold ? IOStyle.RED : IOStyle.BLUE, !sold, sold);

            PriceSpec spec = e.resolvePriceSpec();
            String remain = PageKit.remainText(ClientShopData.stallExpireMode, ClientShopData.stallExpireAmount, e.listedGame, e.listedReal);
            String topLeft = e.sold > 0 ? ("+" + PriceDisplay.formatCompact(spec.scale(e.sold))) : (remain == null ? "" : remain);
            V2.text(cv, topLeft, V2.FS_XS, e.sold > 0 ? IOStyle.GREEN : IOStyle.ORANGE,
                    cx + 4, cy + 7.0F, cw * 0.52F, Text.Align.LEFT);

            if (hover > 0.2F) {
                float rx = cx + cw - 23.0F;
                float dx = cx + cw - 12.0F;
                float ay = cy + 2.0F;
                boolean overR = f.mouseX() >= rx && f.mouseX() < rx + 10.0F && f.mouseY() >= ay && f.mouseY() < ay + 10.0F;
                boolean overD = f.mouseX() >= dx && f.mouseX() < dx + 10.0F && f.mouseY() >= ay && f.mouseY() < ay + 10.0F;
                Draw.fill(cv, rx, ay, 10.0F, 10.0F, 2.0F, Draw.alpha(overR ? IOStyle.BLUE : IOStyle.SLOT_INNER, 0.9F));
                V2.bold(cv, "+", V2.FS_S, IOStyle.TEXT, rx, ay + 5.0F, 10.0F, Text.Align.CENTER);
                MINE_GRID.hitZone(card, "restock", rx, ay, 10.0F, 10.0F);
                Draw.fill(cv, dx, ay, 10.0F, 10.0F, 2.0F, Draw.alpha(overD ? IOStyle.RED : IOStyle.SLOT_INNER, 0.9F));
                V2.bold(cv, "×", V2.FS_S, overD ? IOStyle.TEXT : IOStyle.RED, dx, ay + 5.0F, 10.0F, Text.Align.CENTER);
                MINE_GRID.hitZone(card, "remove", dx, ay, 10.0F, 10.0F);
            } else {
                V2.text(cv, sold ? tr("gui.module_shop.v2.sold_out") : V2.money(e.stock), V2.FS_XS,
                        sold ? IOStyle.RED : IOStyle.TEXT_3, cx + cw * 0.50F, cy + 7.0F, cw * 0.50F - 4, Text.Align.RIGHT);
            }

            float iconSize = 16.0F;
            f.host().items().add(e.stack, cx + (cw - iconSize) * 0.5F, cy + 13.0F, iconSize, true);

            V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(e.stack), V2.FS_S, IOStyle.TEXT,
                    cx + 3, cy + 34.5F, cw - 6, Text.Align.CENTER);
            PriceDisplay.drawCard(cv, f.host().items(), spec, cx + 3, cy + 44.5F, cx + cw * 0.5F, cw - 6, 1.0F,
                    IOStyle.ORANGE, false);
            float pct = e.stock / (float) Math.max(1, StallClient.maxStock);
            V2.bar(cv, cx + 5, cy + 50.5F, cw - 10, pct, V2.levelColor(pct));
        });
    }

    private static String compareKey(net.minecraft.world.item.ItemStack stack, PriceSpec spec) {
        String unit = spec != null && spec.supportsPhys() ? ("phys:" + spec.physTierKey) : "star";
        return stack.getItem().toString() + "|" + stack.getHoverName().getString() + "|" + unit;
    }

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
                if (!st.isEmpty() && net.minecraft.world.item.ItemStack.isSameItemSameTags(st, e.stack)) {
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

    /** 从大厅打开卖家摊位，并预选这件商品。 */
    private static void openSeller(StallClient.Item it) {
        if (it.owner == null) {
            return;
        }
        StallClient.pendingSelect = it.id;
        Nav.rearm(cn.autoforged.module_shop.client.screen.StallBrowseScreen.class);
        CompoundTag tag = new CompoundTag();
        tag.putString("owner", it.owner.toString());
        StallClient.send("open_stall", tag);
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
        if (button != 0) {
            return false;
        }
        for (int i = 0; i < segRects.length; i++) {
            if (mx >= segRects[i][0] && mx < segRects[i][0] + segRects[i][1] && my >= segY && my < segY + V2.CTRL) {
                ClickSound.play();
                StallClient.hallView = i == 0;
                syncSearchField();
                return true;
            }
        }
        for (int i = 0; i < btnRects.size(); i++) {
            float[] r = btnRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                ClickSound.play();
                btnRuns.get(i).run();
                return true;
            }
        }
        if (StallClient.hallView) {
            if (catClipH > 0.0F && CAT_SCROLL.pressBar(mx, my, f.x(), catClipY, FILTER_W - 1.0F, catClipH)) {
                return true;
            }
            if (my >= catClipY && my < catClipY + catClipH) {
                for (int i = 0; i < catRects.size(); i++) {
                    float[] r = catRects.get(i);
                    if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                        ClickSound.play();
                        StallClient.category = catKeys.get(i);
                        StallClient.page = 0;
                        HALL.scrollToTop();
                        HALL_GRID.scrollToTop();
                        return true;
                    }
                }
            }
            if (UIViewMode.isGrid()) {
                for (int i = 0; i < sortPillRects.size(); i++) {
                    float[] r = sortPillRects.get(i);
                    if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                        ClickSound.play();
                        StallClient.sort = i == 1 ? (StallClient.sort == 1 ? 2 : 1) : 0;
                        HALL_GRID.scrollToTop();
                        return true;
                    }
                }
                if (HALL_GRID.pressBar(mx, my)) {
                    return true;
                }
                CardGrid.Hit hit = HALL_GRID.click(mx, my);
                if (hit == null) {
                    return false;
                }
                if (hit.index() >= 0 && hit.index() < hallShown.size()) {
                    ClickSound.play();
                    StallClient.Item it = hallShown.get(hit.index());
                    if (it.own) {
                        StallClient.hallView = false;
                        syncSearchField();
                    } else {
                        openSeller(it);
                    }
                }
                return true;
            }
            if (HALL.pressBar(mx, my)) {
                return true;
            }
            ListTable.Hit hit = HALL.click(mx, my);
            if (hit == null) {
                return false;
            }
            if ("sort".equals(hit.button())) {
                ClickSound.play();
                StallClient.sort = HALL.sortCol == 3 ? (HALL.sortDesc ? 2 : 1) : 0;
                HALL.scrollToTop();
                return true;
            }
            if (hit.row() >= 0 && hit.row() < hallShown.size()) {
                ClickSound.play();
                StallClient.Item it = hallShown.get(hit.row());
                if (it.own) {
                    StallClient.hallView = false;
                    syncSearchField();
                } else {
                    openSeller(it);
                }
            }
            return true;
        }
        if (UIViewMode.isGrid()) {
            if (MINE_GRID.pressBar(mx, my)) {
                return true;
            }
            CardGrid.Hit hit = MINE_GRID.click(mx, my);
            List<StallEntry> stall = ClientShopData.stall;
            if (hit == null || hit.index() < 0) {
                return hit != null;
            }
            if (hit.index() >= mineShown.size()) {
                ClickSound.play();
                Nav.open(new StallListScreen());
                return true;
            }
            int orig = mineShown.get(hit.index());
            StallEntry e = stall.get(orig);
            ClickSound.play();
            if ("remove".equals(hit.button())) {
                f.host().action("remove_stall", entryTag(orig, e));
            } else if ("restock".equals(hit.button())) {
                f.host().action("stall_restock_gui", restockTag(orig, e));
            } else {
                f.host().action("stall_edit_gui", entryTag(orig, e));
            }
            return true;
        }
        if (MINE.pressBar(mx, my)) {
            return true;
        }
        ListTable.Hit hit = MINE.click(mx, my);
        List<StallEntry> stall = ClientShopData.stall;
        if (hit == null || hit.row() < 0) {
            return hit != null;
        }
        if (hit.row() >= mineShown.size()) {
            ClickSound.play();
            Nav.open(new StallListScreen());
            return true;
        }
        int orig = mineShown.get(hit.row());
        StallEntry e = stall.get(orig);
        ClickSound.play();
        if ("remove".equals(hit.button())) {
            f.host().action("remove_stall", entryTag(orig, e));
        } else if ("restock".equals(hit.button())) {
            f.host().action("stall_restock_gui", restockTag(orig, e));
        } else {
            f.host().action("stall_edit_gui", entryTag(orig, e));
        }
        return true;
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
        return CAT_SCROLL.releaseBar()
                | (UIViewMode.isGrid() ? (HALL_GRID.releaseBar() | MINE_GRID.releaseBar()) : (HALL.releaseBar() | MINE.releaseBar()));
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        if (StallClient.hallView && CAT_SCROLL.dragBar(my, catClipH)) {
            return true;
        }
        if (UIViewMode.isGrid()) {
            return StallClient.hallView ? HALL_GRID.dragBar(my) : MINE_GRID.dragBar(my);
        }
        return StallClient.hallView ? HALL.dragBar(my) : MINE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (StallClient.hallView && mx >= f.x() && mx < f.x() + FILTER_W && my >= catClipY && my < catClipY + catClipH
                && CAT_SCROLL.canScroll()) {
            CAT_SCROLL.wheel(scrollY);
            return true;
        }
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        if (UIViewMode.isGrid()) {
            return StallClient.hallView ? HALL_GRID.wheel(mx, my, scrollY, page) : MINE_GRID.wheel(mx, my, scrollY, page);
        }
        return StallClient.hallView ? HALL.wheel(mx, my, scrollY, page) : MINE.wheel(mx, my, scrollY, page);
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        CAT_SCROLL.releaseBar();
        HALL.releaseBar();
        MINE.releaseBar();
        HALL_GRID.releaseBar();
        MINE_GRID.releaseBar();
    }
}
