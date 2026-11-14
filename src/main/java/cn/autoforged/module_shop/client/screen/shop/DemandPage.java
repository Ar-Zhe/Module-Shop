package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.UIViewMode;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.CardGrid;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.data.DemandEntry;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家求购（UI v2 列表视图）：发布者 · 求购清单 · 总件数 · 悬赏 · 剩余时间 · 我能否交付 · 交付 / 撤销。
 * 点一行展开子表（每种物品：需要 / 背包有 / 满足），同一时间只展开一条。动作字符串不变。
 */
public final class DemandPage implements ShopPage {
    private static final ListTable TABLE = new ListTable();
    private static final CardGrid GRID = new CardGrid();
    private static boolean onlyMine;
    private static String expandedId;

    /** 表格的一行：line = -1 为求购本身，否则为展开后的第 line 种物品。 */
    private record Row(DemandEntry entry, int line) {
    }

    private List<Row> rows = new ArrayList<>();
    private List<DemandEntry> cards = new ArrayList<>();
    private float[][] segRects = new float[0][2];
    private float segY;
    private float[] createRect;
    private float[] closeDrawerRect;
    private float[] drawerActionRect;
    private String drawerActionCmd;

    @Override
    public String id() {
        return "demand";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static UUID self() {
        var p = Minecraft.getInstance().player;
        return p == null ? null : p.getUUID();
    }

    /** 背包里有多少个「同物品同组件」（与服务端清点方式一致）。 */
    private static int countInBag(ItemStack template) {
        var player = Minecraft.getInstance().player;
        if (player == null || template.isEmpty()) {
            return 0;
        }
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, template)) {
                total += s.getCount();
            }
        }
        return total;
    }

    /** 还缺多少件才能交付（0 = 都够）。 */
    private static int missing(DemandEntry e) {
        int miss = 0;
        for (DemandEntry.WantLine w : e.wants) {
            miss += Math.max(0, w.count - countInBag(w.stack));
        }
        return miss;
    }

    static String rewardLine(DemandEntry e) {
        String money = e.rewardMoney > 0L ? ClientShopData.money(e.rewardMoney) : null;
        String item = e.rewardItem != null && !e.rewardItem.isEmpty()
                ? e.rewardItem.getHoverName().getString() + " x" + e.rewardItem.getCount() : null;
        if (money != null && item != null) {
            return money + " + " + item;
        }
        return money != null ? money : (item != null ? item : "-");
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        if (!ClientShopData.demandEnabled) {
            V2.empty(c, tr("gui.module_shop.demand.disabled"), x, f.y(), f.w(), f.h());
            return;
        }
        UUID me = self();
        int mineCount = 0;
        rows = new ArrayList<>();
        cards = new ArrayList<>();
        DemandEntry expandedEntry = null;
        for (DemandEntry e : ClientShopData.demands) {
            boolean mine = me != null && me.equals(e.requester);
            if (mine) {
                mineCount++;
            }
            if (onlyMine && !mine) {
                continue;
            }
            cards.add(e);
            rows.add(new Row(e, -1));
            if (e.id != null && e.id.equals(expandedId)) {
                expandedEntry = e;
                for (int i = 0; i < e.wants.size(); i++) {
                    rows.add(new Row(e, i));
                }
            }
        }
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.demand"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        String[] seg = {tr("gui.module_shop.v2.mall.all") + "  " + ClientShopData.demands.size(),
                tr("gui.module_shop.v2.demand.mine") + "  " + mineCount};
        segY = by;
        segRects = V2.segmented(c, seg, onlyMine ? 1 : 0, x + 8 + tw + 10, by, null);
        String cl = tr("gui.module_shop.v2.demand.create");
        float cw = V2.buttonWidth(cl);
        float cx = x + f.w() - 8 - cw;
        V2.button(c, cl, V2.Btn.PRIMARY, cx, by, cw, V2.CTRL, over(f, cx, by, cw, V2.CTRL) ? 1 : 0);
        createRect = new float[]{cx, by, cw, V2.CTRL};
        float segEnd = segRects[1][0] + segRects[1][1] + 10;
        V2.text(c, tr("gui.module_shop.v2.demand.rules", mineCount, ClientShopData.demandSlots,
                        String.format("%.0f", ClientShopData.demandFeePercent), ClientShopData.money(ClientShopData.demandMaxReward)),
                V2.FS_XS, IOStyle.TEXT_2, segEnd, cy, cx - 8 - segEnd, Text.Align.RIGHT);

        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        if (UIViewMode.isGrid()) {
            drawGrid(c, f, x, top, f.w(), bottom - top, me, expandedEntry);
            V2.foot(c, x, bottom, f.w(), tr("gui.module_shop.v2.showing", GRID.firstVisible(), GRID.lastVisible(), cards.size()),
                    tr("gui.module_shop.v2.demand.hint"));
            return;
        }
        closeDrawerRect = null;
        float[][] cols = V2.columns(x, f.w(), 10, 6, 14, 0, 76, 44, 104, 60, 66, 50);
        String[] heads = {"", tr("gui.module_shop.v2.col.wants"), tr("gui.module_shop.v2.col.requester"), tr("gui.module_shop.v2.col.total_count"),
                tr("gui.module_shop.v2.col.reward"), tr("gui.module_shop.v2.col.remain"), tr("gui.module_shop.v2.col.can_deliver"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, top, f.w(), bottom - top, cols, heads, al, null);
        if (rows.isEmpty()) {
            V2.tableHead(c, x, top, f.w(), cols, heads, al, -1, false);
            V2.empty(c, tr(onlyMine ? "gui.module_shop.demand.empty_mine" : "gui.module_shop.demand.empty"), x, top + V2.HEAD, f.w(),
                    bottom - top - V2.HEAD);
        } else {
            TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), rows.size(), (cv, row, ry, hover) -> {
                Row r = rows.get(row);
                DemandEntry e = r.entry();
                float rcy = ry + V2.ROW / 2;
                boolean mine = me != null && me.equals(e.requester);
                if (r.line() >= 0) {
                    drawLine(cv, f, cols, e.wants.get(r.line()), ry, rcy);
                    return;
                }
                if (e.id != null && e.id.equals(expandedId)) {
                    TABLE.selected(cv, ry);
                }
                if (!e.wants.isEmpty()) {
                    f.host().items().add(e.wants.get(0).display(), cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
                }
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < e.wants.size() && i < 3; i++) {
                    if (i > 0) {
                        names.append("、");
                    }
                    names.append(e.wants.get(i).stack.getHoverName().getString()).append(" ×").append(e.wants.get(i).count);
                }
                if (e.wants.size() > 3) {
                    names.append(" ").append(tr("gui.module_shop.v2.demand.more", e.wants.size()));
                }
                V2.text(cv, names.toString(), V2.FS, IOStyle.TEXT, cols[1][0], rcy, cols[1][1], Text.Align.LEFT);
                V2.text(cv, e.requesterName, V2.FS_S, mine ? IOStyle.BLUE : IOStyle.TEXT_2, cols[2][0], rcy, cols[2][1], Text.Align.LEFT);
                V2.text(cv, String.valueOf(e.totalCount()), V2.FS_S, IOStyle.TEXT, cols[3][0], rcy, cols[3][1], Text.Align.RIGHT);
                V2.bold(cv, rewardLine(e), V2.FS_S, IOStyle.ORANGE, cols[4][0], rcy, cols[4][1], Text.Align.RIGHT);
                String remain = PageKit.remainText(ClientShopData.demandExpireMode, ClientShopData.demandExpireAmount, e.createdGame,
                        e.createdReal);
                V2.text(cv, remain == null ? tr("gui.module_shop.v2.stall.permanent") : remain, V2.FS_S,
                        remain == null ? IOStyle.TEXT_3 : IOStyle.ORANGE, cols[5][0], rcy, cols[5][1], Text.Align.RIGHT);
                if (mine) {
                    V2.text(cv, tr("gui.module_shop.v2.demand.own"), V2.FS_XS, IOStyle.TEXT_3, cols[6][0], rcy, cols[6][1], Text.Align.RIGHT);
                } else {
                    int miss = missing(e);
                    String s = miss == 0 ? tr("gui.module_shop.v2.demand.ready") : tr("gui.module_shop.v2.demand.missing", miss);
                    V2.bold(cv, s, V2.FS_S, miss == 0 ? IOStyle.GREEN : IOStyle.TEXT_3, cols[6][0], rcy, cols[6][1], Text.Align.RIGHT);
                }
                float right = cols[7][0] + cols[7][1];
                if (mine) {
                    TABLE.button(cv, row, "cancel", tr("gui.module_shop.demand.cancel_action"), V2.Btn.DANGER, right, ry, hover);
                } else {
                    TABLE.button(cv, row, "fulfill", tr("gui.module_shop.demand.fulfill_action"),
                            missing(e) == 0 ? V2.Btn.PRIMARY : V2.Btn.DISABLED, right, ry, hover);
                }
            });
        }
        V2.foot(c, x, bottom, f.w(), tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), rows.size()),
                tr("gui.module_shop.v2.demand.hint"));
    }

    private void drawGrid(Canvas c, PageFrame f, float x, float top, float w, float h, UUID me, DemandEntry expanded) {
        closeDrawerRect = null;
        drawerActionRect = null;
        drawerActionCmd = null;
        float drawerH = expanded == null ? 0.0F : Math.min(Math.max(46.0F, h * 0.42F), 20.0F + Math.max(1, expanded.wants.size()) * 14.0F);
        float gridH = h - drawerH;
        GRID.layoutUnified(x, top, w, gridH);
        if (cards.isEmpty()) {
            V2.empty(c, tr(onlyMine ? "gui.module_shop.demand.empty_mine" : "gui.module_shop.demand.empty"), x, top, w, gridH);
        } else {
            GRID.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), cards.size(), (cv, card, cx, cy, cw, ch, hover) -> {
                DemandEntry e = cards.get(card);
                boolean mine = me != null && me.equals(e.requester);
                boolean selected = e.id != null && e.id.equals(expandedId);
                int miss = mine ? 0 : missing(e);
                int accent = selected ? IOStyle.BLUE : (mine ? IOStyle.BLUE : (miss == 0 ? IOStyle.GREEN : 0));
                CardGrid.cardBg(cv, cx, cy, cw, ch, hover, accent, selected || miss == 0 || mine, false);

                String leftStr = mine ? tr("gui.module_shop.v2.demand.own")
                        : (miss == 0 ? tr("gui.module_shop.v2.demand.ready") : tr("gui.module_shop.v2.demand.missing", miss));
                int leftCol = mine ? IOStyle.BLUE : (miss == 0 ? IOStyle.GREEN : IOStyle.TEXT_3);
                V2.bold(cv, leftStr, V2.FS_XS, leftCol, cx + 4, cy + 7.0F, cw * 0.56F, Text.Align.LEFT);

                String rightStr = e.wants.size() > 1 ? "+" + (e.wants.size() - 1) : "×" + e.totalCount();
                V2.text(cv, rightStr, V2.FS_XS, IOStyle.TEXT_2, cx + cw * 0.56F, cy + 7.0F, cw * 0.44F - 4, Text.Align.RIGHT);

                float iconSize = 16.0F;
                if (!e.wants.isEmpty()) {
                    DemandEntry.WantLine first = e.wants.get(0);
                    f.host().items().add(first.display(), cx + (cw - iconSize) * 0.5F, cy + 13.0F, iconSize, true);
                    V2.text(cv, first.stack.getHoverName().getString(), V2.FS_S, IOStyle.TEXT,
                            cx + 3, cy + 34.5F, cw - 6, Text.Align.CENTER);
                }

                String moneyStr = e.rewardMoney > 0L ? V2.money(e.rewardMoney) : rewardLine(e);
                V2.bold(cv, moneyStr, V2.FS, IOStyle.ORANGE, cx + 3, cy + 45.0F, cw - 6, Text.Align.CENTER);
            });
        }
        if (expanded != null && drawerH > 0.0F) {
            drawDrawer(c, f, x, top + gridH, w, drawerH, me, expanded);
        }
    }

    private void drawDrawer(Canvas c, PageFrame f, float x, float y, float w, float h, UUID me, DemandEntry e) {
        Draw.fill(c, x, y, w, h, 0, IOStyle.SIDEBAR);
        Draw.hairline(c, x, y, w, IOStyle.BORDER);
        boolean mine = me != null && me.equals(e.requester);
        int miss = mine ? 0 : missing(e);

        float closeX = x + w - 18;
        float closeY = y + 3;
        boolean ch = over(f, closeX, closeY, 12, 12);
        Draw.cross(c, closeX + 6, closeY + 6, 2.5F, 0.9F, ch ? IOStyle.RED : IOStyle.TEXT_3);
        closeDrawerRect = new float[]{closeX, closeY, 12, 12};

        String actLabel = tr(mine ? "gui.module_shop.demand.cancel_action" : "gui.module_shop.demand.fulfill_action");
        float abw = Math.max(36.0F, V2.buttonWidth(actLabel));
        float abx = closeX - abw - 6;
        float aby = y + 2.0F;
        boolean canAct = mine || miss == 0;
        V2.Btn kind = mine ? V2.Btn.DANGER : (canAct ? V2.Btn.PRIMARY : V2.Btn.DISABLED);
        V2.button(c, actLabel, kind, abx, aby, abw, 12.0F, canAct && over(f, abx, aby, abw, 12.0F) ? 1.0F : 0.0F);
        if (canAct) {
            drawerActionRect = new float[]{abx, aby, abw, 12.0F};
            drawerActionCmd = mine ? "demand_cancel_go" : "demand_fulfill_go";
        }

        V2.bold(c, e.requesterName + " · " + tr("gui.module_shop.v2.col.wants") + " (" + e.wants.size() + ") · " + rewardLine(e),
                V2.FS_S, IOStyle.TEXT, x + 10, y + 9, Math.max(40.0F, abx - x - 16), Text.Align.LEFT);

        int saved = c.save();
        Draw.clip(c, x, y + 16, w, h - 18);
        f.host().items().pushClip(x, y + 16, w, h - 18);
        try {
            float ry = y + 16;
            for (DemandEntry.WantLine wl : e.wants) {
                if (ry + 13 > y + h) {
                    break;
                }
                float rcy = ry + 6.5F;
                f.host().items().add(wl.display(), x + 10, ry + 0.5F, 12, false);
                V2.text(c, wl.stack.getHoverName().getString(), V2.FS_S, IOStyle.TEXT_2, x + 26, rcy, w * 0.45F, Text.Align.LEFT);
                int have = countInBag(wl.stack);
                boolean ok = have >= wl.count;
                V2.text(c, tr("gui.module_shop.v2.demand.need", wl.count), V2.FS_XS, IOStyle.TEXT_2,
                        x + w * 0.50F, rcy, 58, Text.Align.RIGHT);
                V2.text(c, tr("gui.module_shop.v2.demand.have", have), V2.FS_XS, ok ? IOStyle.GREEN : IOStyle.TEXT_3,
                        x + w * 0.50F + 64, rcy, 58, Text.Align.RIGHT);
                V2.bold(c, ok ? tr("gui.module_shop.v2.demand.ready") : tr("gui.module_shop.v2.demand.short", wl.count - have),
                        V2.FS_XS, ok ? IOStyle.GREEN : IOStyle.RED, x + w - 76, rcy, 66, Text.Align.RIGHT);
                ry += 14.0F;
            }
        } finally {
            f.host().items().popClip();
            c.restoreToCount(saved);
        }
    }

    private void drawLine(Canvas c, PageFrame f, float[][] cols, DemandEntry.WantLine w, float ry, float cy) {
        Draw.fill(c, cols[0][0] - 6, ry, cols[7][0] + cols[7][1] - cols[0][0] + 12, V2.ROW, 0, Draw.alpha(IOStyle.SLOT_INNER, 0.5F));
        Draw.fill(c, cols[1][0] - 2, ry + 3, 1, V2.ROW - 6, 0, IOStyle.BORDER);
        f.host().items().add(w.display(), cols[1][0] + 4, ry + (V2.ROW - V2.ICON) / 2, V2.ICON, false);
        V2.text(c, w.stack.getHoverName().getString(), V2.FS_S, IOStyle.TEXT_2, cols[1][0] + 20, cy, cols[1][1] - 20, Text.Align.LEFT);
        int have = countInBag(w.stack);
        boolean ok = have >= w.count;
        V2.text(c, tr("gui.module_shop.v2.demand.need", w.count), V2.FS_S, IOStyle.TEXT_2, cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
        V2.text(c, tr("gui.module_shop.v2.demand.have", have), V2.FS_S, ok ? IOStyle.GREEN : IOStyle.TEXT_3, cols[4][0], cy, cols[4][1],
                Text.Align.RIGHT);
        V2.bold(c, ok ? "✓" : tr("gui.module_shop.v2.demand.short", w.count - have), V2.FS_S, ok ? IOStyle.GREEN : IOStyle.RED,
                cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        for (int i = 0; i < segRects.length; i++) {
            if (mx >= segRects[i][0] && mx < segRects[i][0] + segRects[i][1] && my >= segY && my < segY + V2.CTRL) {
                ClickSound.play();
                onlyMine = i == 1;
                TABLE.scrollToTop();
                GRID.scrollToTop();
                return true;
            }
        }
        if (createRect != null && mx >= createRect[0] && mx < createRect[0] + createRect[2] && my >= createRect[1]
                && my < createRect[1] + createRect[3]) {
            ClickSound.play();
            f.host().action("demand_create", null);
            return true;
        }
        if (UIViewMode.isGrid()) {
            if (closeDrawerRect != null && mx >= closeDrawerRect[0] && mx < closeDrawerRect[0] + closeDrawerRect[2]
                    && my >= closeDrawerRect[1] && my < closeDrawerRect[1] + closeDrawerRect[3]) {
                ClickSound.play();
                expandedId = null;
                return true;
            }
            if (drawerActionRect != null && drawerActionCmd != null && expandedId != null
                    && mx >= drawerActionRect[0] && mx < drawerActionRect[0] + drawerActionRect[2]
                    && my >= drawerActionRect[1] && my < drawerActionRect[1] + drawerActionRect[3]) {
                ClickSound.play();
                CompoundTag tag = new CompoundTag();
                tag.putString("id", expandedId);
                f.host().action(drawerActionCmd, tag);
                return true;
            }
            if (GRID.pressBar(mx, my)) {
                return true;
            }
            CardGrid.Hit hit = GRID.click(mx, my);
            if (hit == null || hit.index() < 0 || hit.index() >= cards.size()) {
                return hit != null;
            }
            DemandEntry e = cards.get(hit.index());
            ClickSound.play();
            if ("fulfill".equals(hit.button()) || "cancel".equals(hit.button())) {
                CompoundTag tag = new CompoundTag();
                tag.putString("id", e.id);
                f.host().action("cancel".equals(hit.button()) ? "demand_cancel_go" : "demand_fulfill_go", tag);
                return true;
            }
            expandedId = e.id != null && e.id.equals(expandedId) ? null : e.id;
            return true;
        }
        if (TABLE.pressBar(mx, my)) {
            return true;
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null || hit.row() < 0 || hit.row() >= rows.size()) {
            return hit != null;
        }
        Row r = rows.get(hit.row());
        DemandEntry e = r.entry();
        ClickSound.play();
        if ("fulfill".equals(hit.button()) || "cancel".equals(hit.button())) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", e.id);
            f.host().action("cancel".equals(hit.button()) ? "demand_cancel_go" : "demand_fulfill_go", tag);
            return true;
        }
        expandedId = e.id != null && e.id.equals(expandedId) ? null : e.id;
        return true;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return UIViewMode.isGrid() ? GRID.releaseBar() : TABLE.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return UIViewMode.isGrid() ? GRID.dragBar(my) : TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        return UIViewMode.isGrid() ? GRID.wheel(mx, my, scrollY, false) : TABLE.wheel(mx, my, scrollY, false);
    }

    @Override
    public void cancelInput() {
        TABLE.releaseBar();
        GRID.releaseBar();
    }
}
