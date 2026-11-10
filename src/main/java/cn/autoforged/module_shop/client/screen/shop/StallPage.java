package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.StallClient;
import cn.autoforged.module_shop.client.screen.BillScreen;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.stall.StallListScreen;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.StallEntry;
import cn.autoforged.module_shop.shop.auction.AuctionCategory;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 玩家摊位（UI v2）：两个视图用分段切换。
 * <ul>
 *     <li><b>商店大厅</b>：全服摊位商品一张表（物品 · 卖家 · 单价 · 比价 · 库存 · 已售 · 分类 · 去购买），左侧分类，
 *     表头单价排序；「比价」是客户端对同名商品取最低价算出来的（全服最低 / +x%）；点行进入卖家摊位并预选这件；</li>
 *     <li><b>我的商店</b>：物品 · 单价 · 库存 · 已售 · 销售额 · 剩余上架时间 · 改价 / 补货 / 下架，顶部统计与「上架商品 / 交易记录」。</li>
 * </ul>
 */
public final class StallPage implements ShopPage {
    private static final float FILTER_W = 86.0F;
    private static final ListTable HALL = new ListTable();
    private static final ListTable MINE = new ListTable();

    private SkiaTextField search;
    private List<StallClient.Item> hallShown = List.of();
    private Map<String, Long> lowest = new HashMap<>();
    private float[][] segRects = new float[0][2];
    private float segY;
    private final List<float[]> btnRects = new ArrayList<>();
    private final List<Runnable> btnRuns = new ArrayList<>();

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
                .placeholder(tr("gui.module_shop.stall.search_hint")).fontSize(V2.FS_S)
                .onChange(t -> {
                    StallClient.search = t == null ? "" : t;
                    HALL.scrollToTop();
                });
        search.setValue(StallClient.search);
        host.registerInput(search.editBox());
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
            search.visible(false);
            renderMine(c, f, by);
        }
    }

    // ------------------------------------------------------------------ 商店大厅

    private void renderHall(Canvas c, PageFrame f, float sx, float by) {
        StallClient.ensureMarket();
        float x = f.x();
        search.visible(true).bounds(sx, by, 160, V2.CTRL);
        search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
        float right = x + f.w() - 8;
        right = btn(c, f, tr("gui.module_shop.v2.stall.owners"), V2.Btn.NORMAL, right, by, () -> f.host().action("open_avatar", null));
        java.util.Set<java.util.UUID> owners = new java.util.HashSet<>();
        for (StallClient.Item it : StallClient.market) {
            if (it.owner != null) {
                owners.add(it.owner);
            }
        }
        V2.text(c, tr("gui.module_shop.v2.stall.summary", StallClient.market.size(), owners.size()), V2.FS_XS, IOStyle.TEXT_2,
                sx + 168, f.y() + V2.BAR / 2, right - sx - 172, Text.Align.RIGHT);

        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        drawCategories(c, f, x, top, bottom - top);
        hallShown = StallClient.filtered();
        lowest = new HashMap<>();
        for (StallClient.Item it : StallClient.market) {
            if (it.stock > 0) {
                lowest.merge(it.stack.getItem().toString() + "|" + it.stack.getHoverName().getString(), it.price, Math::min);
            }
        }
        float tx = x + FILTER_W;
        float tw = f.w() - FILTER_W;
        float[][] cols = V2.columns(tx, tw, 10, 6, 14, 0, 76, 56, 56, 50, 44, 50, 52);
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
                float nw = V2.text(cv, it.stack.getHoverName().getString(), V2.FS, sold ? IOStyle.TEXT_3 : IOStyle.TEXT, cols[1][0],
                        cy, cols[1][1] - 30, Text.Align.LEFT);
                if (it.own) {
                    V2.tag(cv, tr("gui.module_shop.stall.mine_tag"), IOStyle.BLUE, cols[1][0] + nw + 4, cy);
                }
                V2.text(cv, it.ownerName, V2.FS_S, it.own ? IOStyle.BLUE : IOStyle.TEXT_2, cols[2][0], cy, cols[2][1], Text.Align.LEFT);
                V2.bold(cv, V2.money(it.price), V2.FS, sold ? IOStyle.TEXT_3 : IOStyle.ORANGE, cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
                Long low = lowest.get(it.stack.getItem().toString() + "|" + it.stack.getHoverName().getString());
                if (sold || low == null || low <= 0) {
                    V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
                } else if (it.price <= low) {
                    float tw2 = V2.tagWidth(tr("gui.module_shop.v2.stall.lowest"));
                    V2.tag(cv, tr("gui.module_shop.v2.stall.lowest"), IOStyle.GREEN, cols[4][0] + cols[4][1] - tw2 + 3, cy);
                } else {
                    long pct = Math.round((it.price - low) * 100.0 / low);
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

    private final List<float[]> catRects = new ArrayList<>();
    private final List<AuctionCategory> catKeys = new ArrayList<>();

    private void drawCategories(Canvas c, PageFrame f, float x, float y, float h) {
        catRects.clear();
        catKeys.clear();
        Draw.fill(c, x + FILTER_W - 0.5F, y, 0.5F, h, 0, IOStyle.BORDER_DIM);
        Map<AuctionCategory, Integer> counts = new EnumMap<>(AuctionCategory.class);
        for (StallClient.Item it : StallClient.market) {
            counts.merge(StallClient.categoryOf(it), 1, Integer::sum);
        }
        float cy = y + 8;
        V2.section(c, tr("gui.module_shop.v2.filter.category"), x + 8, cy, FILTER_W - 12);
        cy += 6;
        for (AuctionCategory cat : AuctionCategory.values()) {
            int n = cat == AuctionCategory.ALL ? StallClient.market.size() : counts.getOrDefault(cat, 0);
            V2.filterItem(c, tr(cat.langKey()), String.valueOf(n), cat == StallClient.category,
                    over(f, x + 4, cy, FILTER_W - 8, 13) ? 1 : 0, x + 4, cy, FILTER_W - 8);
            catRects.add(new float[]{x + 4, cy, FILTER_W - 8, 13});
            catKeys.add(cat);
            cy += 14;
        }
    }

    // ------------------------------------------------------------------ 我的商店

    private void renderMine(Canvas c, PageFrame f, float by) {
        float x = f.x();
        List<StallEntry> stall = ClientShopData.stall;
        float right = x + f.w() - 8;
        right = btn(c, f, tr("gui.module_shop.stall_create"), V2.Btn.PRIMARY, right, by, () -> Nav.open(new StallListScreen()));
        right = btn(c, f, tr("gui.module_shop.stall.records"), V2.Btn.NORMAL, right, by, () -> Nav.open(BillScreen.stallRecords()));
        int totalSold = 0;
        long revenue = 0;
        for (StallEntry e : stall) {
            totalSold += e.sold;
            revenue += e.sold * e.price;
        }
        float sx = segRects[1][0] + segRects[1][1] + 10;
        String expire = "permanent".equals(String.valueOf(ClientShopData.stallExpireMode))
                ? tr("gui.module_shop.v2.stall.permanent") : tr("gui.module_shop.v2.stall.timed");
        V2.text(c, tr("gui.module_shop.v2.stall.mine_stats", stall.size(), StallClient.slots, V2.money(totalSold),
                        V2.money(revenue), String.format("%.0f", StallClient.feePercent), expire),
                V2.FS_XS, IOStyle.TEXT_2, sx, f.y() + V2.BAR / 2, right - sx - 4, Text.Align.RIGHT);

        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        float[][] cols = V2.columns(x, f.w(), 10, 6, 14, 0, 60, 74, 50, 68, 74, 130);
        String[] heads = {"", tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.price"), tr("gui.module_shop.v2.col.stock"),
                tr("gui.module_shop.v2.col.sold"), tr("gui.module_shop.v2.col.revenue"), tr("gui.module_shop.v2.col.remain"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        MINE.layout(x, top, f.w(), bottom - top, cols, heads, al, null);
        int rows = Math.max(stall.size(), StallClient.slots);
        MINE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), rows, (cv, row, ry, hover) -> {
            float cy = ry + V2.ROW / 2;
            if (row >= stall.size()) {
                Draw.stroke(cv, cols[0][0], ry + 1.5F, V2.ICON, V2.ICON, 2, 0.5F, IOStyle.BORDER_DIM);
                V2.text(cv, tr("gui.module_shop.v2.stall.empty_slot"), V2.FS_S, IOStyle.TEXT_3, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
                return;
            }
            StallEntry e = stall.get(row);
            boolean sold = e.stock <= 0;
            f.host().items().add(e.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
            V2.text(cv, e.stack.getHoverName().getString(), V2.FS, IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            V2.bold(cv, V2.money(e.price), V2.FS, IOStyle.ORANGE, cols[2][0], cy, cols[2][1], Text.Align.RIGHT);
            float bw = 26;
            V2.bar(cv, cols[3][0] + cols[3][1] - bw, cy, bw, e.stock / (float) Math.max(1, StallClient.maxStock), V2.levelColor(
                    e.stock / (float) Math.max(1, StallClient.maxStock)));
            V2.text(cv, sold ? tr("gui.module_shop.v2.sold_out") : String.valueOf(e.stock), V2.FS_S, sold ? IOStyle.RED : IOStyle.TEXT,
                    cols[3][0], cy, cols[3][1] - bw - 4, Text.Align.RIGHT);
            V2.text(cv, V2.money(e.sold), V2.FS_S, IOStyle.TEXT_2, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
            long rev = e.sold * e.price;
            V2.bold(cv, rev > 0 ? "+" + V2.money(rev) : "—", V2.FS_S, rev > 0 ? IOStyle.GREEN : IOStyle.TEXT_3, cols[5][0], cy, cols[5][1],
                    Text.Align.RIGHT);
            String remain = PageKit.remainText(ClientShopData.stallExpireMode, ClientShopData.stallExpireAmount, e.listedGame, e.listedReal);
            V2.text(cv, remain == null ? tr("gui.module_shop.v2.stall.permanent") : remain, V2.FS_S,
                    remain == null ? IOStyle.TEXT_3 : IOStyle.ORANGE, cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
            float bx = cols[7][0] + cols[7][1];
            bx = MINE.button(cv, row, "remove", tr("gui.module_shop.v2.stall.remove"), V2.Btn.DANGER, bx, ry, hover);
            bx = MINE.button(cv, row, "restock", tr("gui.module_shop.v2.stall.restock"), V2.Btn.NORMAL, bx, ry, hover);
            MINE.button(cv, row, "edit", tr("gui.module_shop.v2.stall.reprice"), V2.Btn.GHOST, bx, ry, hover);
        });
        V2.foot(c, x, bottom, f.w(), tr("gui.module_shop.stall.mine_summary", stall.size(), StallClient.slots, totalSold),
                tr("gui.module_shop.v2.stall.mine_hint"));
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
            for (int i = 0; i < catRects.size(); i++) {
                float[] r = catRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    ClickSound.play();
                    StallClient.category = catKeys.get(i);
                    StallClient.page = 0;
                    HALL.scrollToTop();
                    return true;
                }
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
                } else {
                    openSeller(it);
                }
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
        if (hit.row() >= stall.size()) {
            ClickSound.play();
            Nav.open(new StallListScreen());
            return true;
        }
        StallEntry e = stall.get(hit.row());
        ClickSound.play();
        if ("remove".equals(hit.button())) {
            f.host().action("remove_stall", entryTag(hit.row(), e));
        } else if ("restock".equals(hit.button())) {
            f.host().action("stall_restock_gui", restockTag(hit.row(), e));
        } else {
            f.host().action("stall_edit_gui", entryTag(hit.row(), e));
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
        return HALL.releaseBar() | MINE.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return StallClient.hallView ? HALL.dragBar(my) : MINE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        return StallClient.hallView ? HALL.wheel(mx, my, scrollY, page) : MINE.wheel(mx, my, scrollY, page);
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        HALL.releaseBar();
        MINE.releaseBar();
    }
}
