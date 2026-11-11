package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.data.DailyShopEntry;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 每日商店（UI v2 列表视图）。
 *
 * <p>翻牌仍是服务端状态：未翻开的行只显示稀有度与「？？？」，操作是「翻开」（{@code flip_shop}）；
 * 翻开后显示物品、单价、剩余与限购，操作是「购买」（{@code buy_shop}，弹数量输入）。
 * 顶部有全部 / 已翻开 / 未翻开 筛选、「全部翻开」（逐条发 flip_shop）与付费「刷新商店」。
 */
public final class DailyPage implements ShopPage {
    private static final ListTable TABLE = new ListTable();
    private static int filter;

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

    private static boolean flipped(int index) {
        return ClientShopData.dailyFlipped.contains(index);
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
            if (filter == 0 || (filter == 1 && fl) || (filter == 2 && !fl)) {
                shown.add(i);
            }
        }
        drawToolbar(c, f, list.size(), open);
        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        drawTable(c, f, f.x(), top, f.w(), bottom - top);
        V2.foot(c, f.x(), bottom, f.w(), tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size()),
                tr("gui.module_shop.v2.daily.hint"));
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
        boolean canRefresh = ClientShopData.refreshCost >= 0L && all > 0;
        boolean hr = over(f, right, segY, rw, V2.CTRL);
        V2.button(c, rl, canRefresh ? V2.Btn.NORMAL : V2.Btn.DISABLED, right, segY, rw, V2.CTRL, hr ? 1 : 0);
        refreshRect = canRefresh ? new float[]{right, segY, rw, V2.CTRL} : null;
        if (hr) {
            int left = Math.max(0, ClientShopData.refreshMax - ClientShopData.refreshUsed);
            f.host().textTooltip(canRefresh
                    ? Component.translatable("gui.module_shop.refresh_tooltip",
                    ClientShopData.money(ClientShopData.refreshCost), left, ClientShopData.refreshMax)
                    : Component.translatable("gui.module_shop.refresh_tooltip_none", ClientShopData.refreshMax));
        }
        String fl = tr("gui.module_shop.v2.daily.flip_all");
        float fw = V2.buttonWidth(fl);
        right -= fw + 5;
        boolean canFlip = open < all;
        V2.button(c, fl, canFlip ? V2.Btn.PRIMARY : V2.Btn.DISABLED, right, segY, fw, V2.CTRL,
                over(f, right, segY, fw, V2.CTRL) ? 1 : 0);
        flipAllRect = canFlip ? new float[]{right, segY, fw, V2.CTRL} : null;

        String info = ClientShopData.refreshCost >= 0L
                ? tr("gui.module_shop.v2.daily.refresh_info", ClientShopData.refreshUsed, ClientShopData.refreshMax,
                ClientShopData.money(ClientShopData.refreshCost))
                : tr("gui.module_shop.v2.daily.refresh_none", ClientShopData.refreshMax);
        float segEnd = segRects[segRects.length - 1][0] + segRects[segRects.length - 1][1] + 10;
        V2.text(c, info, V2.FS_XS, IOStyle.TEXT_2, segEnd, cy, right - 8 - segEnd, Text.Align.RIGHT);
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float[][] cols = V2.columns(x, w, 10, 6, 14, 0, 56, 64, 52, 96, 40);
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
            V2.text(cv, e.stack.getHoverName().getString(), V2.FS, left > 0 ? IOStyle.TEXT : IOStyle.TEXT_3,
                    cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            V2.bold(cv, rarity, V2.FS_S, rc, cols[2][0], cy, cols[2][1], Text.Align.LEFT);
            V2.bold(cv, V2.money(e.price), V2.FS, left > 0 ? IOStyle.ORANGE : IOStyle.TEXT_3, cols[3][0], cy, cols[3][1],
                    Text.Align.RIGHT);
            V2.text(cv, String.valueOf(left), V2.FS, left > 0 ? IOStyle.TEXT : IOStyle.RED, cols[4][0], cy, cols[4][1],
                    Text.Align.RIGHT);
            float barW = 30;
            float pct = e.limit <= 0 ? 1 : e.bought / (float) e.limit;
            V2.bar(cv, cols[5][0] + cols[5][1] - barW, cy, barW, pct, left > 0 ? IOStyle.BLUE : IOStyle.TEXT_3);
            V2.text(cv, e.bought + " / " + e.limit, V2.FS_S, IOStyle.TEXT_2, cols[5][0], cy, cols[5][1] - barW - 5,
                    Text.Align.RIGHT);
            if (left > 0) {
                TABLE.button(cv, row, "buy", tr("gui.module_shop.v2.buy"), V2.Btn.NORMAL, right, ry, hover);
            } else {
                TABLE.button(cv, row, "none", tr("gui.module_shop.v2.daily.done"), V2.Btn.DISABLED, right, ry, hover);
            }
        });
    }

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
            if (in(refreshRect, mx, my)) {
                ClickSound.play();
                f.host().action("refresh_daily_shop", null);
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
            if (TABLE.pressBar(mx, my)) {
                return true;
            }
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
        f.host().action(flipped(index) ? "buy_shop" : "flip_shop", extra);
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
        return TABLE.wheel(mx, my, scrollY, net.minecraft.client.gui.screens.Screen.hasShiftDown());
    }

    @Override
    public void cancelInput() {
        TABLE.releaseBar();
    }
}
