package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.AuctionClient;
import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.UIViewMode;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.auction.AuctionUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.CardGrid;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.shop.auction.AuctionCategory;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

/**
 * 拍卖会大厅（UI v2 列表视图）：左侧分类（带数量，搜索收敛且独立滚动），工具条搜索与「我的拍卖 / 管理 / 上架」，
 * 表格：拍品 · 卖家 · 当前价 · 下次最低 · 一口价 · 人数 / 出价次数 · 领先者 · 剩余 · 出价。
 * 行左侧色条：我的拍品（金）/ 我领先（绿）/ 我被超过（红）。点任一行或「出价」打开原有的详情界面（出价 / 一口价）。
 * 表头「当前价 / 人数 / 剩余」排序对应原有的 7 种排序。
 */
public final class AuctionPage implements ShopPage {
    private static final float FILTER_W = 96.0F;
    private static final ListTable TABLE = new ListTable();
    private static final CardGrid GRID = new CardGrid();
    private static final ScrollController CAT_SCROLL = new ScrollController();

    private SkiaTextField search;
    private List<ClientShopData.AuctionView> shown = List.of();
    private float catClipY;
    private float catClipH;
    private final List<float[]> catRects = new ArrayList<>();
    private final List<AuctionCategory> catKeys = new ArrayList<>();
    private final List<float[]> btnRects = new ArrayList<>();
    private final List<Runnable> btnRuns = new ArrayList<>();
    private final List<float[]> sortPillRects = new ArrayList<>();

    @Override
    public String id() {
        return "auction";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 48)
                .placeholder(tr("gui.module_shop.auction.search_hint"))
                .clearable(true)
                .fontSize(V2.FS_S)
                .onChange(t -> {
                    AuctionClient.search = t == null ? "" : t;
                    TABLE.scrollToTop();
                    GRID.scrollToTop();
                    CAT_SCROLL.reset();
                });
        search.setValue(AuctionClient.search);
        host.registerInput(search.editBox());
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private void btn(Canvas c, PageFrame f, String label, V2.Btn kind, float x, float y, Runnable run) {
        float w = V2.buttonWidth(label);
        V2.button(c, label, kind, x, y, w, V2.CTRL, over(f, x, y, w, V2.CTRL) ? 1 : 0);
        if (kind != V2.Btn.DISABLED) {
            btnRects.add(new float[]{x, y, w, V2.CTRL});
            btnRuns.add(run);
        }
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        AuctionClient.ensureList();
        btnRects.clear();
        btnRuns.clear();
        AuctionClient.Settings st = AuctionClient.settings;
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.auction"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        float right = x + f.w() - 8;
        String create = tr("gui.module_shop.v2.auction.create");
        right -= V2.buttonWidth(create);
        btn(c, f, create, st.enabled ? V2.Btn.PRIMARY : V2.Btn.DISABLED, right, by, () -> AuctionUi.openCreate(null));
        if (st.admin || ClientShopData.canAdmin) {
            String admin = tr("gui.module_shop.v2.auction.admin");
            right -= V2.buttonWidth(admin) + 5;
            btn(c, f, admin, V2.Btn.GHOST, right, by, AuctionUi::openAdmin);
        }
        String mine = tr("gui.module_shop.v2.auction.mine", st.myActive, st.maxPerPlayer);
        right -= V2.buttonWidth(mine) + 5;
        btn(c, f, mine, V2.Btn.NORMAL, right, by, AuctionUi::openMine);

        float sx = x + 8 + tw + 10;
        float avail = right - 8 - sx;
        float sw = st.enabled ? Math.min(170.0F, Math.max(48.0F, avail))
                : (avail >= 140.0F ? Math.min(130.0F, avail - 60.0F) : Math.min(130.0F, Math.max(48.0F, avail)));
        if (search != null) {
            if (avail >= 48.0F) {
                search.visible(true).bounds(sx, by, sw, V2.CTRL);
                search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
            } else {
                search.visible(false);
            }
        }
        if (!st.enabled && right - 8 - (sx + sw + 8) >= 48.0F) {
            V2.text(c, tr("gui.module_shop.v2.auction.disabled"), V2.FS_XS, IOStyle.RED, sx + sw + 8, cy,
                    right - 8 - (sx + sw + 8), Text.Align.RIGHT);
        }

        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        drawFilter(c, f, x, top, bottom - top);
        shown = AuctionClient.filtered();
        String rules = tr("gui.module_shop.v2.auction.rules", AuctionUi.money(st.feeFixed),
                String.format("%.1f", st.feePercent), String.format("%.1f", st.saleFeePercent));
        if (UIViewMode.isGrid()) {
            drawGrid(c, f, x + FILTER_W, top, f.w() - FILTER_W, bottom - top);
            V2.foot(c, x + FILTER_W, bottom, f.w() - FILTER_W,
                    tr("gui.module_shop.v2.showing", GRID.firstVisible(), GRID.lastVisible(), shown.size()), rules);
        } else {
            drawTable(c, f, x + FILTER_W, top, f.w() - FILTER_W, bottom - top);
            V2.foot(c, x + FILTER_W, bottom, f.w() - FILTER_W,
                    tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size()), rules);
        }
    }

    private void drawFilter(Canvas c, PageFrame f, float x, float y, float h) {
        catRects.clear();
        catKeys.clear();
        Draw.fill(c, x + FILTER_W - 0.5F, y, 0.5F, h, 0, IOStyle.BORDER_DIM);
        Map<AuctionCategory, Integer> counts = AuctionClient.searchCategoryCounts();
        boolean searching = AuctionClient.search != null && !AuctionClient.search.isBlank();
        int allMatches = counts.getOrDefault(AuctionCategory.ALL, 0);
        if (searching && AuctionClient.category != AuctionCategory.ALL
                && counts.getOrDefault(AuctionClient.category, 0) == 0 && allMatches > 0) {
            AuctionClient.category = AuctionCategory.ALL;
            AuctionClient.page = 0;
            TABLE.scrollToTop();
            GRID.scrollToTop();
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
        float legendH = 56.0F;
        float catViewH = Math.max(28.0F, y + h - legendH - catTop);
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
                boolean on = cat == AuctionClient.category;
                boolean hover = f.mouseY() >= catTop && f.mouseY() < catTop + catViewH && over(f, x + 4, iy, itemW, 13);
                V2.filterItem(c, tr(cat.langKey()), String.valueOf(n), on, hover ? 1 : 0, x + 4, iy, itemW);
                catRects.add(new float[]{x + 4, iy, itemW, 13});
                catKeys.add(cat);
            }
        } finally {
            c.restoreToCount(saved);
        }
        CAT_SCROLL.drawBar(c, x, catTop, FILTER_W - 1.0F, catViewH, f.mouseX(), f.mouseY(), f.dtMs());
        float cy = catTop + catViewH + 4.0F;
        Draw.hairline(c, x + 6, cy, FILTER_W - 12, IOStyle.BORDER_DIM);
        cy += 9;
        V2.section(c, tr("gui.module_shop.v2.auction.legend"), x + 8, cy, FILTER_W - 12);
        cy += 9;
        String[][] legend = {{"gui.module_shop.v2.auction.st_mine", "o"}, {"gui.module_shop.v2.auction.st_lead", "g"},
                {"gui.module_shop.v2.auction.st_outbid", "r"}};
        for (String[] l : legend) {
            int col = "o".equals(l[1]) ? IOStyle.ORANGE : "g".equals(l[1]) ? IOStyle.GREEN : IOStyle.RED;
            Draw.fill(c, x + 9, cy - 3.5F, 2, 7, 1, col);
            V2.text(c, tr(l[0]), V2.FS_XS, IOStyle.TEXT_2, x + 15, cy, FILTER_W - 20, Text.Align.LEFT);
            cy += 10;
        }
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float actW = Math.max(40.0F, Math.max(V2.buttonWidth(tr("gui.module_shop.v2.auction.view")),
                V2.buttonWidth(tr("gui.module_shop.v2.auction.bid"))));
        float gap = 5.0F;
        float[][] baseCols = V2.columns(x, w - actW - gap, 10, gap, 4, 14, 0, 48, 56, 46, 46, 38, 46, 42);
        float[][] cols = new float[11][2];
        System.arraycopy(baseCols, 0, cols, 0, 10);
        cols[10][0] = x + w - 10 - actW;
        cols[10][1] = actW;
        String[] heads = {"", "", tr("gui.module_shop.v2.col.lot"), tr("gui.module_shop.v2.col.seller"),
                tr("gui.module_shop.v2.col.current"), tr("gui.module_shop.v2.col.min_next"), tr("gui.module_shop.v2.col.buyout"),
                tr("gui.module_shop.v2.col.bidders"), tr("gui.module_shop.v2.col.leader"), tr("gui.module_shop.v2.col.remain"), ""};
        // 列下标：0 色条 1 图标 2 拍品 3 卖家 4 当前价 5 下次最低 6 一口价 7 人数 8 领先者 9 剩余 10 操作
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h, cols, heads, al, new boolean[]{false, false, false, false, true, false, false, true, false, true, false});
        int sort = AuctionClient.sort;
        TABLE.sortCol = switch (sort) {
            case 2, 3 -> 4;
            case 6 -> 7;
            case 1 -> 9;
            default -> -1;
        };
        TABLE.sortDesc = sort == 3 || sort == 6;
        if (shown.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, TABLE.sortCol, TABLE.sortDesc);
            V2.empty(c, tr(ClientShopData.auctions.isEmpty() ? "gui.module_shop.auction_empty" : "gui.module_shop.v2.no_match"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
            ClientShopData.AuctionView v = shown.get(row);
            float cy = ry + V2.ROW / 2;
            int mark = v.own ? IOStyle.ORANGE : v.topMe ? IOStyle.GREEN : v.iBid ? IOStyle.RED : 0;
            if (mark != 0) {
                Draw.fill(cv, cols[0][0], ry + 2, 2, V2.ROW - 4, 1, mark);
            }
            f.host().items().add(v.stack, cols[1][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
            String badgeText = !v.allowBid ? tr("gui.module_shop.v2.auction.buyout_only")
                    : (v.own ? tr("gui.module_shop.v2.auction.st_mine") : null);
            float tagReserve = badgeText == null ? 0.0F : (V2.tagWidth(badgeText) + 4.0F);
            float nameMaxW = Math.max(16.0F, cols[2][1] - tagReserve);
            float nw = V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(v.stack), V2.FS, IOStyle.TEXT, cols[2][0], cy, nameMaxW,
                    Text.Align.LEFT);
            if (badgeText != null) {
                V2.tag(cv, badgeText, !v.allowBid ? IOStyle.EPIC : IOStyle.ORANGE,
                        cols[2][0] + Math.min(nw, nameMaxW) + 4, cy);
            }
            V2.text(cv, v.seller, V2.FS_S, IOStyle.TEXT_2, cols[3][0], cy, cols[3][1], Text.Align.LEFT);
            boolean hasBid = v.currentBid > 0;
            if (v.allowBid) {
                PriceDisplay.drawCell(cv, f.host().items(), v.specOf(hasBid ? v.currentBid : v.startPrice),
                        cols[4][0], cy, cols[4][1], Text.Align.RIGHT, hasBid ? IOStyle.ORANGE : IOStyle.TEXT_2, false);
                drawAuctionCell(cv, f, v, v.minNext, cols[5][0], cy, cols[5][1], IOStyle.TEXT_2);
            } else {
                V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
                V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
            }
            if (v.buyout > 0) {
                drawAuctionCell(cv, f, v, v.buyout, cols[6][0], cy, cols[6][1], IOStyle.GREEN);
            } else {
                V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
            }
            V2.text(cv, v.bidders + " / " + v.bidCount, V2.FS_S, v.bidders > 0 ? IOStyle.TEXT : IOStyle.TEXT_3, cols[7][0], cy,
                    cols[7][1], Text.Align.RIGHT);
            String leader = v.topBidder == null || v.topBidder.isEmpty() ? "—" : v.topMe ? tr("gui.module_shop.v2.auction.me") : v.topBidder;
            V2.text(cv, leader, V2.FS_S, v.topMe ? IOStyle.GREEN : IOStyle.TEXT_2, cols[8][0], cy, cols[8][1], Text.Align.LEFT);
            long remain = v.remainingNow();
            V2.bold(cv, AuctionUi.remain(remain), V2.FS_S, AuctionUi.remainColor(remain), cols[9][0], cy, cols[9][1], Text.Align.RIGHT);
            TABLE.button(cv, row, "open", tr(v.own ? "gui.module_shop.v2.auction.view" : "gui.module_shop.v2.auction.bid"),
                    v.own ? V2.Btn.GHOST : V2.Btn.NORMAL, cols[10][0] + cols[10][1], ry, hover);
        });
    }

    private void drawGrid(Canvas c, PageFrame f, float x, float y, float w, float h) {
        int sort = AuctionClient.sort;
        String curLabel = tr("gui.module_shop.v2.col.current") + (sort == 2 ? " ↑" : sort == 3 ? " ↓" : "");
        String[] sortLabels = {tr("gui.module_shop.v2.sort.default"), curLabel,
                tr("gui.module_shop.v2.col.bidders"), tr("gui.module_shop.v2.col.remain")};
        int activeSort = (sort == 2 || sort == 3) ? 1 : sort == 6 ? 2 : sort == 1 ? 3 : 0;
        CardGrid.drawSortBar(c, x, y, w, f.mouseX(), f.mouseY(), sortLabels, activeSort, null, sortPillRects);

        GRID.layoutUnified(x, y + V2.HEAD, w, h - V2.HEAD);
        if (shown.isEmpty()) {
            V2.empty(c, tr(ClientShopData.auctions.isEmpty() ? "gui.module_shop.auction_empty" : "gui.module_shop.v2.no_match"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        GRID.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, card, cx, cy, cw, ch, hover) -> {
            ClientShopData.AuctionView v = shown.get(card);
            int mark = v.own ? IOStyle.ORANGE : v.topMe ? IOStyle.GREEN : v.iBid ? IOStyle.RED : 0;
            CardGrid.cardBg(cv, cx, cy, cw, ch, hover, mark, mark != 0, false);

            long remain = v.remainingNow();
            String leftTag = v.own ? tr("gui.module_shop.v2.auction.st_mine")
                    : v.topMe ? tr("gui.module_shop.v2.auction.me")
                    : !v.allowBid ? tr("gui.module_shop.v2.auction.buyout_only")
                    : v.bidders + "/" + v.bidCount;
            V2.bold(cv, leftTag, V2.FS_XS, mark != 0 ? mark : IOStyle.TEXT_3,
                    cx + 4, cy + 7.0F, cw * 0.52F, Text.Align.LEFT);
            V2.bold(cv, AuctionUi.remain(remain), V2.FS_XS, AuctionUi.remainColor(remain),
                    cx + cw * 0.48F, cy + 7.0F, cw * 0.52F - 4, Text.Align.RIGHT);

            float iconSize = 16.0F;
            f.host().items().add(v.stack, cx + (cw - iconSize) * 0.5F, cy + 13.0F, iconSize, true);

            V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(v.stack), V2.FS_S, IOStyle.TEXT,
                    cx + 3, cy + 34.5F, cw - 6, Text.Align.CENTER);

            boolean hasBid = v.currentBid > 0;
            int priceCol = !v.allowBid ? IOStyle.GREEN : (hasBid ? IOStyle.ORANGE : IOStyle.TEXT_2);
            if (!v.allowBid && v.buyout <= 0L) {
                V2.bold(cv, "—", V2.FS, IOStyle.TEXT_3, cx + 3, cy + 45.0F, cw - 6, Text.Align.CENTER);
            } else {
                PriceDisplay.drawCard(cv, f.host().items(), v.effectiveSpec(), cx + 3, cy + 45.0F, cx + cw * 0.5F,
                        cw - 6, 1.0F, priceCol, false);
            }
        });
    }

    private static void drawAuctionCell(Canvas c, PageFrame f, ClientShopData.AuctionView v, long amount,
                                        float x, float cy, float w, int color) {
        if (!v.isPhysCurrency() || w >= 42.0F) {
            PriceDisplay.drawCell(c, f.host().items(), v.specOf(amount), x, cy, w, Text.Align.RIGHT, color, false);
        } else {
            V2.bold(c, PriceDisplay.formatNumber(amount, true), V2.FS_S, color, x, cy, w, Text.Align.RIGHT);
        }
    }

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
        for (int i = 0; i < btnRects.size(); i++) {
            float[] r = btnRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                ClickSound.play();
                btnRuns.get(i).run();
                return true;
            }
        }
        if (catClipH > 0.0F && CAT_SCROLL.pressBar(mx, my, f.x(), catClipY, FILTER_W - 1.0F, catClipH)) {
            return true;
        }
        if (my >= catClipY && my < catClipY + catClipH) {
            for (int i = 0; i < catRects.size(); i++) {
                float[] r = catRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    ClickSound.play();
                    AuctionClient.category = catKeys.get(i);
                    AuctionClient.page = 0;
                    TABLE.scrollToTop();
                    GRID.scrollToTop();
                    return true;
                }
            }
        }
        if (UIViewMode.isGrid()) {
            for (int i = 0; i < sortPillRects.size(); i++) {
                float[] r = sortPillRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    ClickSound.play();
                    AuctionClient.sort = switch (i) {
                        case 1 -> AuctionClient.sort == 2 ? 3 : 2;
                        case 2 -> 6;
                        case 3 -> 1;
                        default -> 0;
                    };
                    GRID.scrollToTop();
                    return true;
                }
            }
            if (GRID.pressBar(mx, my)) {
                return true;
            }
            CardGrid.Hit hit = GRID.click(mx, my);
            if (hit == null) {
                return false;
            }
            if (hit.index() >= 0 && hit.index() < shown.size()) {
                ClickSound.play();
                AuctionUi.openDetail(shown.get(hit.index()).id);
            }
            return true;
        }
        if (TABLE.pressBar(mx, my)) {
            return true;
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null) {
            return false;
        }
        if ("sort".equals(hit.button())) {
            ClickSound.play();
            int col = TABLE.sortCol;
            AuctionClient.sort = switch (col) {
                case 4 -> TABLE.sortDesc ? 3 : 2;
                case 7 -> 6;
                case 9 -> 1;
                default -> 0;
            };
            TABLE.scrollToTop();
            return true;
        }
        if (hit.row() >= 0 && hit.row() < shown.size()) {
            ClickSound.play();
            AuctionUi.openDetail(shown.get(hit.row()).id);
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
        return CAT_SCROLL.releaseBar() | (UIViewMode.isGrid() ? GRID.releaseBar() : TABLE.releaseBar());
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        if (CAT_SCROLL.dragBar(my, catClipH)) {
            return true;
        }
        return UIViewMode.isGrid() ? GRID.dragBar(my) : TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (mx >= f.x() && mx < f.x() + FILTER_W && my >= catClipY && my < catClipY + catClipH && CAT_SCROLL.canScroll()) {
            CAT_SCROLL.wheel(scrollY);
            return true;
        }
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        return UIViewMode.isGrid() ? GRID.wheel(mx, my, scrollY, page) : TABLE.wheel(mx, my, scrollY, page);
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        CAT_SCROLL.releaseBar();
        TABLE.releaseBar();
        GRID.releaseBar();
    }
}
