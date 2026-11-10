package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
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
    private static boolean onlyMine;
    private static String expandedId;

    /** 表格的一行：line = -1 为求购本身，否则为展开后的第 line 种物品。 */
    private record Row(DemandEntry entry, int line) {
    }

    private List<Row> rows = new ArrayList<>();
    private float[][] segRects = new float[0][2];
    private float segY;
    private float[] createRect;

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
        for (DemandEntry e : ClientShopData.demands) {
            boolean mine = me != null && me.equals(e.requester);
            if (mine) {
                mineCount++;
            }
            if (onlyMine && !mine) {
                continue;
            }
            rows.add(new Row(e, -1));
            if (e.id != null && e.id.equals(expandedId)) {
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
                return true;
            }
        }
        if (createRect != null && mx >= createRect[0] && mx < createRect[0] + createRect[2] && my >= createRect[1]
                && my < createRect[1] + createRect[3]) {
            ClickSound.play();
            f.host().action("demand_create", null);
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
        return TABLE.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        return TABLE.wheel(mx, my, scrollY, false);
    }

    @Override
    public void cancelInput() {
        TABLE.releaseBar();
    }
}
