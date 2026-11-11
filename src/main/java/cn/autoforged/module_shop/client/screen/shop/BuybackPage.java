package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.ShopConfirmScreen;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.data.DailyBuybackEntry;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 每日收购（UI v2 列表视图）。
 *
 * <p>表格：物品 · 单价 · 背包持有 · 今日已收 / 上限 · 全卖可得 · 卖出。顶部：全部 / 背包里有 / 已收满 筛选、
 * 全清奖励进度、刷新、<b>全部卖出</b>。
 *
 * <p><b>全部卖出</b>是纯客户端编排：按「物品种类」统计背包数量（与服务端扣物品的规则一致），
 * 确认后逐条发送原有的 {@code sell_buyback}（index + quantity），服务端仍按额度与余额上限截断。
 */
public final class BuybackPage implements ShopPage {
    private static final ListTable TABLE = new ListTable();
    /** 0 = 全部，1 = 背包里有，2 = 已收满。 */
    private static int filter;

    private List<Integer> shown = new ArrayList<>();
    private float[][] segRects = new float[0][2];
    private float segY;
    private float[] refreshRect;
    private float[] sellAllRect;
    private float[] bonusSetRect;
    private float[] bonusToggleRect;

    @Override
    public String id() {
        return "buyback";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** 背包里与收购品同种物品的数量（与服务端 takeMatching 一样只比较物品种类，含护甲 / 副手栏）。 */
    static int countInBag(ItemStack template) {
        var player = Minecraft.getInstance().player;
        if (player == null || template.isEmpty()) {
            return 0;
        }
        Item item = template.getItem();
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                total += s.getCount();
            }
        }
        return total;
    }

    /** 这一条现在能卖多少（背包数量与今日剩余额度取小）。 */
    private static int sellable(DailyBuybackEntry e) {
        return Math.max(0, Math.min(countInBag(e.stack), e.remaining()));
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas c, PageFrame f) {
        List<DailyBuybackEntry> list = ClientShopData.dailyBuyback;
        int haveCount = 0;
        int fullCount = 0;
        long sumGain = 0;
        int sumKinds = 0;
        shown = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            DailyBuybackEntry e = list.get(i);
            int have = countInBag(e.stack);
            int sell = sellable(e);
            if (have > 0) {
                haveCount++;
            }
            if (e.remaining() <= 0) {
                fullCount++;
            }
            if (sell > 0) {
                sumKinds++;
                sumGain += sell * e.price;
            }
            if (filter == 0 || (filter == 1 && have > 0) || (filter == 2 && e.remaining() <= 0)) {
                shown.add(i);
            }
        }
        drawToolbar(c, f, list.size(), haveCount, fullCount, sumKinds, sumGain);
        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        drawTable(c, f, f.x(), top, f.w(), bottom - top);
        String left = tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size());
        V2.foot(c, f.x(), bottom, f.w(), left, tr("gui.module_shop.v2.buyback.hint"));
    }

    private void drawToolbar(Canvas c, PageFrame f, int all, int have, int full, int kinds, long gain) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.buyback"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        String[] seg = {tr("gui.module_shop.v2.mall.all") + "  " + all, tr("gui.module_shop.v2.buyback.have") + "  " + have,
                tr("gui.module_shop.v2.buyback.full") + "  " + full};
        segY = cy - V2.CTRL / 2;
        segRects = V2.segmented(c, seg, filter, x + 8 + tw + 10, segY, null);

        // 右侧从右往左：全部卖出 / 刷新 / 管理员奖励设置 / 说明文字
        float right = x + f.w() - 8;
        String sellLabel = tr("gui.module_shop.v2.buyback.sell_all");
        float sw = V2.buttonWidth(sellLabel);
        right -= sw;
        boolean canSell = kinds > 0;
        float hs = over(f, right, segY, sw, V2.CTRL) ? 1 : 0;
        V2.button(c, sellLabel, canSell ? V2.Btn.PRIMARY : V2.Btn.DISABLED, right, segY, sw, V2.CTRL, hs);
        sellAllRect = canSell ? new float[]{right, segY, sw, V2.CTRL} : null;

        String refreshLabel = tr("gui.module_shop.refresh_buyback");
        float rw = V2.buttonWidth(refreshLabel);
        right -= rw + 5;
        boolean canRefresh = ClientShopData.buybackRefreshCost >= 0L && !ClientShopData.dailyBuyback.isEmpty();
        float hr = over(f, right, segY, rw, V2.CTRL) ? 1 : 0;
        V2.button(c, refreshLabel, canRefresh ? V2.Btn.NORMAL : V2.Btn.DISABLED, right, segY, rw, V2.CTRL, hr);
        refreshRect = canRefresh ? new float[]{right, segY, rw, V2.CTRL} : null;
        if (hr > 0) {
            int left = Math.max(0, ClientShopData.buybackRefreshMax - ClientShopData.buybackRefreshUsed);
            f.host().textTooltip(canRefresh
                    ? Component.translatable("gui.module_shop.refresh_buyback_tooltip",
                    ClientShopData.money(ClientShopData.buybackRefreshCost), left, ClientShopData.buybackRefreshMax)
                    : Component.translatable("gui.module_shop.refresh_buyback_tooltip_none", ClientShopData.buybackRefreshMax));
        }

        bonusSetRect = null;
        bonusToggleRect = null;
        if (ClientShopData.canAdmin) {
            String tl = tr(ClientShopData.buybackBonusEnabled ? "gui.module_shop.v2.buyback.bonus_on" : "gui.module_shop.v2.buyback.bonus_off");
            float tw2 = V2.buttonWidth(tl);
            right -= tw2 + 5;
            V2.button(c, tl, V2.Btn.GHOST, right, segY, tw2, V2.CTRL, over(f, right, segY, tw2, V2.CTRL) ? 1 : 0);
            bonusToggleRect = new float[]{right, segY, tw2, V2.CTRL};
            String bl = tr("gui.module_shop.buyback_bonus_set");
            float bw = V2.buttonWidth(bl);
            right -= bw + 3;
            V2.button(c, bl, V2.Btn.GHOST, right, segY, bw, V2.CTRL, over(f, right, segY, bw, V2.CTRL) ? 1 : 0);
            bonusSetRect = new float[]{right, segY, bw, V2.CTRL};
        }
        // 说明：全清奖励进度 + 背包可卖合计
        List<String> notes = new ArrayList<>();
        if (ClientShopData.buybackBonusEnabled && ClientShopData.buybackBonus > 0L) {
            notes.add(ClientShopData.buybackBonusDone
                    ? tr("gui.module_shop.buyback_bonus_done", ClientShopData.money(ClientShopData.buybackBonus))
                    : tr("gui.module_shop.buyback_bonus_progress", ClientShopData.money(ClientShopData.buybackBonus),
                    ClientShopData.buybackLeft));
        }
        if (kinds > 0) {
            notes.add(tr("gui.module_shop.v2.buyback.can_sell", kinds, V2.money(gain)));
        }
        float segEnd = segRects[segRects.length - 1][0] + segRects[segRects.length - 1][1] + 10;
        if (!notes.isEmpty() && right - 8 > segEnd) {
            V2.text(c, String.join("  ·  ", notes), V2.FS_XS, IOStyle.TEXT_2, segEnd, cy, right - 8 - segEnd, Text.Align.RIGHT);
        }
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float[][] cols = V2.columns(x, w, 10, 6, 14, 0, 58, 64, 112, 72, 40);
        String[] heads = {"", tr("gui.module_shop.v2.col.buyback_item"), tr("gui.module_shop.v2.col.price"),
                tr("gui.module_shop.v2.col.have"), tr("gui.module_shop.v2.col.quota"), tr("gui.module_shop.v2.col.gain"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h, cols, heads, al, null);
        if (shown.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, -1, false);
            V2.empty(c, tr(ClientShopData.dailyBuyback.isEmpty() ? "gui.module_shop.empty" : "gui.module_shop.v2.no_match"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        List<DailyBuybackEntry> list = ClientShopData.dailyBuyback;
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
            DailyBuybackEntry e = list.get(shown.get(row));
            float cy = ry + V2.ROW / 2;
            int have = countInBag(e.stack);
            int sell = sellable(e);
            boolean full = e.remaining() <= 0;
            Draw.fill(cv, cols[0][0] - 5, ry + 3, 1.5F, V2.ROW - 6, 0.75F, IOStyle.rarity(e.rarity));
            f.host().items().add(e.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
            float nx = cols[1][0];
            float nw = V2.text(cv, e.stack.getHoverName().getString(), V2.FS, full ? IOStyle.TEXT_3 : IOStyle.TEXT,
                    nx, cy, cols[1][1] - 60, Text.Align.LEFT);
            if (full) {
                V2.tag(cv, tr("gui.module_shop.v2.buyback.full"), IOStyle.TEXT_3, nx + nw + 5, cy);
            }
            V2.bold(cv, V2.money(e.price), V2.FS, IOStyle.ORANGE, cols[2][0], cy, cols[2][1], Text.Align.RIGHT);
            if (have > 0) {
                V2.bold(cv, V2.money(have), V2.FS, IOStyle.TEXT, cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
            } else {
                V2.text(cv, "—", V2.FS, IOStyle.TEXT_3, cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
            }
            float barW = 34;
            float pct = e.limit <= 0 ? 1 : e.sold / (float) e.limit;
            String quota = e.sold + " / " + e.limit;
            V2.bar(cv, cols[4][0] + cols[4][1] - barW, cy, barW, pct, IOStyle.BLUE);
            V2.text(cv, quota, V2.FS_S, IOStyle.TEXT_2, cols[4][0], cy, cols[4][1] - barW - 5, Text.Align.RIGHT);
            if (sell > 0) {
                V2.bold(cv, V2.signed(sell * e.price), V2.FS, IOStyle.GREEN, cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
            } else {
                V2.text(cv, "—", V2.FS, IOStyle.TEXT_3, cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
            }
            float right = cols[6][0] + cols[6][1];
            if (full) {
                TABLE.button(cv, row, "none", tr("gui.module_shop.v2.buyback.full"), V2.Btn.DISABLED, right, ry, hover);
            } else {
                TABLE.button(cv, row, "sell", tr("gui.module_shop.v2.sell"), have > 0 ? V2.Btn.NORMAL : V2.Btn.GHOST,
                        right, ry, hover);
            }
        });
    }

    // ------------------------------------------------------------------ 输入

    private static boolean in(float[] r, double x, double y) {
        return r != null && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < segRects.length; i++) {
                if (mx >= segRects[i][0] && mx < segRects[i][0] + segRects[i][1] && my >= segY && my < segY + V2.CTRL) {
                    ClickSound.play();
                    filter = i;
                    TABLE.scrollToTop();
                    return true;
                }
            }
            if (in(sellAllRect, mx, my)) {
                ClickSound.play();
                confirmSellAll(f);
                return true;
            }
            if (in(refreshRect, mx, my)) {
                ClickSound.play();
                f.host().action("refresh_daily_buyback", null);
                return true;
            }
            if (in(bonusSetRect, mx, my)) {
                ClickSound.play();
                f.host().action("buyback_bonus_set", null);
                return true;
            }
            if (in(bonusToggleRect, mx, my)) {
                ClickSound.play();
                f.host().action("admin_buyback_bonus_toggle", null);
                return true;
            }
            if (TABLE.pressBar(mx, my)) {
                return true;
            }
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null || hit.row() < 0 || hit.row() >= shown.size()) {
            return hit != null;
        }
        int index = shown.get(hit.row());
        DailyBuybackEntry e = ClientShopData.dailyBuyback.get(index);
        if (e.remaining() <= 0) {
            return true;
        }
        if (button == 0 && Net.shift()) {
            // Shift + 左键：直接卖出背包里这一种的全部（不弹数量框）
            int sell = sellable(e);
            if (sell > 0) {
                ClickSound.play();
                sendSell(f, index, sell);
            }
            return true;
        }
        if (button == 0 && "sell".equals(hit.button())) {
            ClickSound.play();
            CompoundTag tag = new CompoundTag();
            tag.putInt("index", index);
            f.host().action("sell_buyback_gui", tag);
            return true;
        }
        return hit.button() != null;
    }

    private static void sendSell(PageFrame f, int index, int quantity) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", index);
        tag.putString("quantity", String.valueOf(quantity));
        f.host().action("sell_buyback", tag);
    }

    private void confirmSellAll(PageFrame f) {
        List<DailyBuybackEntry> list = ClientShopData.dailyBuyback;
        List<int[]> plan = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        long total = 0;
        for (int i = 0; i < list.size(); i++) {
            DailyBuybackEntry e = list.get(i);
            int sell = sellable(e);
            if (sell <= 0) {
                continue;
            }
            plan.add(new int[]{i, sell});
            total += sell * e.price;
            if (plan.size() <= 8) {
                lines.append("\n").append(e.stack.getHoverName().getString()).append(" ×").append(sell)
                        .append("  +").append(V2.money(sell * e.price));
            }
        }
        if (plan.isEmpty()) {
            return;
        }
        if (plan.size() > 8) {
            lines.append("\n").append(tr("gui.module_shop.v2.buyback.more", plan.size() - 8));
        }
        Component body = Component.translatable("gui.module_shop.v2.buyback.sell_all_body", plan.size(),
                V2.money(total), lines.toString());
        ShopHost host = f.host();
        Nav.open(new ShopConfirmScreen(Component.translatable("gui.module_shop.v2.buyback.sell_all_title"), body, ok -> {
            if (ok) {
                for (int[] p : plan) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("index", p[0]);
                    tag.putString("quantity", String.valueOf(p[1]));
                    host.action("sell_buyback", tag);
                }
            }
        }));
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
        return TABLE.wheel(mx, my, scrollY, Net.shift());
    }

    @Override
    public void cancelInput() {
        TABLE.releaseBar();
    }

    /** 小工具：Shift 是否按下。 */
    static final class Net {
        static boolean shift() {
            return net.minecraft.client.gui.screens.Screen.hasShiftDown();
        }
    }
}
