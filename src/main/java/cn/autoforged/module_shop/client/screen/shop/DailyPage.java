package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.UIViewMode;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.CardGrid;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.DailyShopEntry;
import cn.autoforged.module_shop.data.PriceSpec;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 每日商店（UI v2：支持列表视图与方块视图）。
 *
 * <p>翻牌仍是服务端状态：未翻开的行只显示稀有度与「？？？」，操作是「翻开」（{@code flip_shop}）；
 * 翻开后显示物品、单价、剩余与限购，操作是「购买」（{@code buy_shop}，弹数量输入）。
 * 顶部有全部 / 已翻开 / 未翻开 筛选、「全部翻开」（逐条发 flip_shop）与付费「刷新商店」。
 */
public final class DailyPage implements ShopPage {
    private static final ListTable TABLE = new ListTable();
    private static final CardGrid GRID = new CardGrid();
    private static float[] flipAnim = new float[0];
    private static int filter;
    private static String query = "";

    private SkiaTextField search;
    private List<Integer> shown = new ArrayList<>();
    private float[][] segRects = new float[0][2];
    private float segY;
    private float[] refreshRect;
    private float[] flipAllRect;

    @Override
    public String id() {
        return "daily";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 48)
                .placeholder(tr("gui.module_shop.search.placeholder"))
                .clearable(true)
                .fontSize(V2.FS_S)
                .onChange(t -> {
                    query = t == null ? "" : t;
                    TABLE.scrollToTop();
                    GRID.scrollToTop();
                });
        search.setValue(query);
        host.registerInput(search.editBox());
    }

    private static boolean flipped(int index) {
        return ClientShopData.dailyFlipped.contains(index);
    }

    private static boolean matchesEntry(DailyShopEntry e, boolean fl) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String rarity = Component.translatable(e.rarity.translationKey()).getString();
        if (!fl) {
            return PageKit.matchesQuery(query, rarity, "???");
        }
        for (String word : query.trim().split("\\s+")) {
            if (!word.isEmpty() && !PageKit.matchesItemQuery(e.stack, word) && !PageKit.matchesQuery(word, rarity)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        List<DailyShopEntry> list = ClientShopData.dailyShop;
        int open = 0;
        shown = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            boolean fl = flipped(i);
            if (fl) {
                open++;
            }
            if ((filter == 0 || (filter == 1 && fl) || (filter == 2 && !fl)) && matchesEntry(list.get(i), fl)) {
                shown.add(i);
            }
        }
        drawToolbar(c, f, list.size(), open);
        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        if (UIViewMode.isGrid()) {
            drawGrid(c, f, f.x(), top, f.w(), bottom - top);
            V2.foot(c, f.x(), bottom, f.w(), tr("gui.module_shop.v2.showing", GRID.firstVisible(), GRID.lastVisible(), shown.size()),
                    tr("gui.module_shop.v2.daily.hint"));
        } else {
            drawTable(c, f, f.x(), top, f.w(), bottom - top);
            V2.foot(c, f.x(), bottom, f.w(), tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size()),
                    tr("gui.module_shop.v2.daily.hint"));
        }
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private void drawToolbar(Canvas c, PageFrame f, int all, int open) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.daily"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        String[] seg = {tr("gui.module_shop.v2.mall.all") + "  " + all, tr("gui.module_shop.v2.daily.open") + "  " + open,
                tr("gui.module_shop.v2.daily.closed") + "  " + (all - open)};
        segY = cy - V2.CTRL / 2;
        segRects = V2.segmented(c, seg, filter, x + 8 + tw + 10, segY, null);

        float right = x + f.w() - 8;
        String rl = tr("gui.module_shop.refresh_daily");
        float rw = V2.buttonWidth(rl);
        right -= rw;
        boolean canRefresh = ClientShopData.hasRefreshCost() && all > 0;
        boolean hr = over(f, right, segY, rw, V2.CTRL);
        V2.button(c, rl, canRefresh ? V2.Btn.NORMAL : V2.Btn.DISABLED, right, segY, rw, V2.CTRL, hr ? 1 : 0);
        refreshRect = canRefresh ? new float[]{right, segY, rw, V2.CTRL} : null;
        String refreshCostStr = PriceDisplay.formatPlain(ClientShopData.resolveRefreshSpec());
        if (hr) {
            int left = Math.max(0, ClientShopData.refreshMax - ClientShopData.refreshUsed);
            f.host().textTooltip(canRefresh
                    ? Component.translatable("gui.module_shop.refresh_tooltip",
                    refreshCostStr, left, ClientShopData.refreshMax)
                    : Component.translatable("gui.module_shop.refresh_tooltip_none", ClientShopData.refreshMax));
        }
        String fl = tr("gui.module_shop.v2.daily.flip_all");
        float fw = V2.buttonWidth(fl);
        right -= fw + 5;
        boolean canFlip = open < all;
        V2.button(c, fl, canFlip ? V2.Btn.PRIMARY : V2.Btn.DISABLED, right, segY, fw, V2.CTRL,
                over(f, right, segY, fw, V2.CTRL) ? 1 : 0);
        flipAllRect = canFlip ? new float[]{right, segY, fw, V2.CTRL} : null;

        String info = ClientShopData.hasRefreshCost()
                ? tr("gui.module_shop.v2.daily.refresh_info", ClientShopData.refreshUsed, ClientShopData.refreshMax,
                refreshCostStr)
                : tr("gui.module_shop.v2.daily.refresh_none", ClientShopData.refreshMax);
        float segEnd = segRects[segRects.length - 1][0] + segRects[segRects.length - 1][1] + 8;
        float avail = right - 8 - segEnd;
        float afterSearch = segEnd;
        if (search != null) {
            if (avail >= 48.0F) {
                float sw = Math.min(130.0F, Math.max(48.0F, avail >= 210.0F ? 110.0F : avail));
                search.visible(true).bounds(segEnd, segY, sw, V2.CTRL);
                search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
                afterSearch = segEnd + sw + 8.0F;
            } else {
                search.visible(false);
            }
        }
        if (right - 8 - afterSearch >= 65.0F) {
            V2.text(c, info, V2.FS_XS, IOStyle.TEXT_2, afterSearch, cy, right - 8 - afterSearch, Text.Align.RIGHT);
        }
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float actW = Math.max(40.0F, Math.max(V2.buttonWidth(tr("gui.module_shop.v2.daily.flip")),
                Math.max(V2.buttonWidth(tr("gui.module_shop.v2.buy")), V2.buttonWidth(tr("gui.module_shop.v2.daily.done")))));
        float gap = 5.0F;
        float[][] baseCols = V2.columns(x, w - actW - gap, 10, gap, 14, 0, 46, 66, 40, 80);
        float[][] cols = new float[7][2];
        System.arraycopy(baseCols, 0, cols, 0, 6);
        cols[6][0] = x + w - 10 - actW;
        cols[6][1] = actW;
        String[] heads = {"", tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.rarity"),
                tr("gui.module_shop.v2.col.price"), tr("gui.module_shop.v2.col.left"), tr("gui.module_shop.v2.col.bought"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h, cols, heads, al, null);
        if (shown.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, -1, false);
            V2.empty(c, tr(ClientShopData.dailyShop.isEmpty() ? "gui.module_shop.empty" : "gui.module_shop.v2.no_match"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        List<DailyShopEntry> list = ClientShopData.dailyShop;
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
            int index = shown.get(row);
            DailyShopEntry e = list.get(index);
            float cy = ry + V2.ROW / 2;
            int rc = IOStyle.rarity(e.rarity);
            Draw.fill(cv, cols[0][0] - 5, ry + 3, 1.5F, V2.ROW - 6, 0.75F, rc);
            boolean fl = flipped(index);
            String rarity = Component.translatable(e.rarity.translationKey()).getString();
            float right = cols[6][0] + cols[6][1];
            if (!fl) {
                Draw.fill(cv, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, V2.ICON, 2, Draw.alpha(rc, 0.18F));
                V2.bold(cv, "?", V2.FS, rc, cols[0][0], cy, V2.ICON, Text.Align.CENTER);
                V2.text(cv, "？？？", V2.FS, IOStyle.TEXT_3, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
                V2.bold(cv, rarity, V2.FS_S, rc, cols[2][0], cy, cols[2][1], Text.Align.LEFT);
                for (int i = 3; i <= 5; i++) {
                    V2.text(cv, "？", V2.FS_S, IOStyle.TEXT_3, cols[i][0], cy, cols[i][1], Text.Align.RIGHT);
                }
                TABLE.button(cv, row, "flip", tr("gui.module_shop.v2.daily.flip"), V2.Btn.NORMAL, right, ry, hover);
                return;
            }
            int left = e.remaining();
            f.host().items().add(e.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
            V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(e.stack), V2.FS, left > 0 ? IOStyle.TEXT : IOStyle.TEXT_3,
                    cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            V2.bold(cv, rarity, V2.FS_S, rc, cols[2][0], cy, cols[2][1], Text.Align.LEFT);
            PriceSpec spec = e.resolvePriceSpec();
            boolean can = PriceDisplay.canAfford(spec, 1);
            PriceDisplay.drawCell(cv, f.host().items(), spec, cols[3][0], cy, cols[3][1], Text.Align.RIGHT,
                    left > 0 ? (can ? IOStyle.ORANGE : IOStyle.RED) : IOStyle.TEXT_3, left <= 0);
            V2.text(cv, String.valueOf(left), V2.FS, left > 0 ? IOStyle.TEXT : IOStyle.RED, cols[4][0], cy, cols[4][1],
                    Text.Align.RIGHT);
            float barW = Math.min(28.0F, cols[5][1] * 0.34F);
            float pct = e.limit <= 0 ? 1 : e.bought / (float) e.limit;
            V2.bar(cv, cols[5][0] + cols[5][1] - barW, cy, barW, pct, left > 0 ? IOStyle.BLUE : IOStyle.TEXT_3);
            V2.text(cv, e.bought + " / " + e.limit, V2.FS_S, IOStyle.TEXT_2, cols[5][0], cy,
                    Math.max(12.0F, cols[5][1] - barW - 4), Text.Align.RIGHT);
            if (left > 0) {
                TABLE.button(cv, row, "buy", tr("gui.module_shop.v2.buy"), V2.Btn.NORMAL, right, ry, hover);
            } else {
                TABLE.button(cv, row, "none", tr("gui.module_shop.v2.daily.done"), V2.Btn.DISABLED, right, ry, hover);
            }
        });
    }

    private void drawGrid(Canvas c, PageFrame f, float x, float y, float w, float h) {
        List<DailyShopEntry> list = ClientShopData.dailyShop;
        if (flipAnim.length != list.size()) {
            float[] next = new float[list.size()];
            for (int i = 0; i < next.length; i++) {
                next[i] = i < flipAnim.length ? flipAnim[i] : (flipped(i) ? 1.0F : 0.0F);
            }
            flipAnim = next;
        }
        for (int i = 0; i < list.size(); i++) {
            if (flipped(i)) {
                flipAnim[i] = Math.min(1.0F, flipAnim[i] + f.dtMs() / 240.0F);
            } else {
                flipAnim[i] = 0.0F;
            }
        }
        GRID.layoutUnified(x, y, w, h);
        if (shown.isEmpty()) {
            V2.empty(c, tr(list.isEmpty() ? "gui.module_shop.empty" : "gui.module_shop.v2.no_match"), x, y, w, h);
            return;
        }
        GRID.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, card, cx, cy, cw, ch, hover) -> {
            int index = shown.get(card);
            DailyShopEntry e = list.get(index);
            int rc = IOStyle.rarity(e.rarity);
            String rarity = Component.translatable(e.rarity.translationKey()).getString();
            float t = index >= 0 && index < flipAnim.length ? flipAnim[index] : (flipped(index) ? 1.0F : 0.0F);
            boolean mid = t > 0.0F && t < 1.0F;
            float sx = mid ? Math.max(0.08F, Math.abs(1.0F - 2.0F * t)) : 1.0F;
            int saved = cv.save();
            if (mid) {
                float midX = cx + cw * 0.5F;
                float midY = cy + ch * 0.5F;
                cv.translate(midX, midY);
                cv.scale(sx, 1.0F);
                cv.translate(-midX, -midY);
            }
            try {
                if (!flipped(index) || t < 0.5F) {
                    CardGrid.cardBg(cv, cx, cy, cw, ch, hover, rc, true, false);
                    V2.bold(cv, rarity, V2.FS_XS, rc, cx + 4, cy + 7.0F, cw - 8, Text.Align.LEFT);
                    float box = 16.0F;
                    float bx = cx + (cw - box) * 0.5F;
                    float by = cy + 13.0F;
                    Draw.fill(cv, bx, by, box, box, 3.0F, Draw.alpha(rc, 0.16F));
                    Draw.stroke(cv, bx, by, box, box, 3.0F, 0.5F, Draw.alpha(rc, 0.45F));
                    V2.bold(cv, "?", V2.FS_T, rc, bx, by + box * 0.5F, box, Text.Align.CENTER);
                    V2.text(cv, "???", V2.FS_S, IOStyle.TEXT_2, cx + 3, cy + 34.5F, cw - 6, Text.Align.CENTER);
                    V2.bold(cv, tr("gui.module_shop.v2.daily.flip"), V2.FS_XS, hover > 0.4F ? IOStyle.BLUE : IOStyle.TEXT_3,
                            cx + 3, cy + 45.0F, cw - 6, Text.Align.CENTER);
                    return;
                }
                int left = e.remaining();
                CardGrid.cardBg(cv, cx, cy, cw, ch, hover, rc, left > 0, left <= 0);
                V2.bold(cv, rarity, V2.FS_XS, left > 0 ? rc : Draw.alpha(rc, 0.55F), cx + 4, cy + 7.0F, cw * 0.52F, Text.Align.LEFT);
                V2.text(cv, e.bought + "/" + e.limit, V2.FS_XS, left > 0 ? IOStyle.TEXT_3 : IOStyle.RED,
                        cx + cw * 0.50F, cy + 7.0F, cw * 0.50F - 4, Text.Align.RIGHT);

                float iconSize = 16.0F;
                f.host().items().add(e.stack, cx + (cw - iconSize * sx) * 0.5F, cy + 13.0F, iconSize, sx, true);

                V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(e.stack), V2.FS_S, left > 0 ? IOStyle.TEXT : IOStyle.TEXT_3,
                        cx + 3, cy + 34.5F, cw - 6, Text.Align.CENTER);
                if (left > 0) {
                    PriceSpec spec = e.resolvePriceSpec();
                    boolean can = PriceDisplay.canAfford(spec, 1);
                    PriceDisplay.drawCard(cv, f.host().items(), spec, cx + 3, cy + 44.5F, cx + cw * 0.5F, cw - 6, sx,
                            can ? IOStyle.ORANGE : IOStyle.RED, false);
                } else {
                    V2.bold(cv, tr("gui.module_shop.v2.daily.done"), V2.FS, IOStyle.TEXT_3,
                            cx + 3, cy + 44.5F, cw - 6, Text.Align.CENTER);
                }
                float pct = e.limit <= 0 ? 1.0F : e.bought / (float) e.limit;
                V2.bar(cv, cx + 5, cy + 50.5F, cw - 10, pct, left > 0 ? IOStyle.BLUE : IOStyle.TEXT_3);
            } finally {
                cv.restoreToCount(saved);
            }
        });
    }

    private static boolean in(float[] r, double x, double y) {
        return r != null && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

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
                    ClickSound.play();
                    filter = i;
                    TABLE.scrollToTop();
                    GRID.scrollToTop();
                    return true;
                }
            }
            if (in(refreshRect, mx, my)) {
                ClickSound.play();
                CompoundTag extra = new CompoundTag();
                extra.putString("pay_channel", PriceDisplay.resolveBuyChannel(ClientShopData.resolveRefreshSpec(), 1));
                f.host().action("refresh_daily_shop", extra);
                return true;
            }
            if (in(flipAllRect, mx, my)) {
                ClickSound.play();
                for (int i = 0; i < ClientShopData.dailyShop.size(); i++) {
                    if (!flipped(i)) {
                        CompoundTag extra = new CompoundTag();
                        extra.putInt("index", i);
                        f.host().action("flip_shop", extra);
                    }
                }
                return true;
            }
            if (UIViewMode.isGrid() ? GRID.pressBar(mx, my) : TABLE.pressBar(mx, my)) {
                return true;
            }
        }
        if (UIViewMode.isGrid()) {
            CardGrid.Hit hit = GRID.click(mx, my);
            if (hit == null || hit.index() < 0 || hit.index() >= shown.size()) {
                return hit != null;
            }
            if (button != 0 || "none".equals(hit.button())) {
                return true;
            }
            int index = shown.get(hit.index());
            if (!flipped(index) || "buy".equals(hit.button()) || "flip".equals(hit.button()) || hit.button() == null) {
                if (flipped(index) && ClientShopData.dailyShop.get(index).remaining() <= 0) {
                    return true;
                }
                ClickSound.play();
                CompoundTag extra = new CompoundTag();
                extra.putInt("index", index);
                if (flipped(index)) {
                    extra.putString("pay_channel",
                            PriceDisplay.resolveBuyChannel(ClientShopData.dailyShop.get(index).resolvePriceSpec(), 1));
                }
                f.host().action(flipped(index) ? "buy_shop" : "flip_shop", extra);
            }
            return true;
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null || hit.row() < 0 || hit.row() >= shown.size()) {
            return hit != null;
        }
        if (button != 0 || hit.button() == null || "none".equals(hit.button())) {
            return true;
        }
        int index = shown.get(hit.row());
        ClickSound.play();
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", index);
        if (flipped(index)) {
            extra.putString("pay_channel",
                    PriceDisplay.resolveBuyChannel(ClientShopData.dailyShop.get(index).resolvePriceSpec(), 1));
        }
        f.host().action(flipped(index) ? "buy_shop" : "flip_shop", extra);
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
        return UIViewMode.isGrid() ? GRID.releaseBar() : TABLE.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return UIViewMode.isGrid() ? GRID.dragBar(my) : TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        return UIViewMode.isGrid() ? GRID.wheel(mx, my, scrollY, page) : TABLE.wheel(mx, my, scrollY, page);
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        TABLE.releaseBar();
        GRID.releaseBar();
    }
}
