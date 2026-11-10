package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.ShippingBinScreen;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 出货箱（UI v2）：顶部概览条（等级 / 箱内物品 / 本次可得 / 今日额度 / 下次结算 / 升级 / 打开），
 * 下面是「可出货物品」表（由出货规则展开：物品 · 模组 · 单价 · 每日份数 · 背包持有 · 全放入可得）。
 * 放物品仍在出货箱容器界面里完成；点表格任一行 = 打开出货箱。
 */
public final class ShippingPage implements ShopPage {
    private static final float OVERVIEW_H = 40.0F;
    private static final ListTable TABLE = new ListTable();
    private static boolean onlyOwned;

    private SkiaTextField search;
    private String query = "";
    private List<ShippingBinScreen.Entry> shown = List.of();
    private float[] openRect;
    private float[] upgradeRect;
    private float[] ownedRect;

    @Override
    public String id() {
        return "shipping";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 32)
                .placeholder(tr("gui.module_shop.shipping.search_hint")).fontSize(V2.FS_S)
                .onChange(t -> {
                    query = t == null ? "" : t.toLowerCase(Locale.ROOT);
                    TABLE.scrollToTop();
                });
        search.setValue(query);
        host.registerInput(search.editBox());
    }

    private static int owned(ItemStack template) {
        return BuybackPage.countInBag(template);
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        float x = f.x();
        float y = f.y();
        if (!ClientShopData.shipEnabled) {
            search.visible(false);
            V2.empty(c, tr("gui.module_shop.shipping.disabled"), x, y, f.w(), f.h());
            return;
        }
        drawToolbar(c, f);
        drawOverview(c, f, x, y + V2.BAR, f.w());
        List<ShippingBinScreen.Entry> out = new ArrayList<>();
        for (ShippingBinScreen.Entry e : ShippingBinScreen.allEntries()) {
            if (!query.isEmpty() && !e.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
                    && !e.id().contains(query)) {
                continue;
            }
            if (onlyOwned && owned(e.stack()) <= 0) {
                continue;
            }
            out.add(e);
        }
        shown = out;
        float top = y + V2.BAR + OVERVIEW_H;
        float bottom = y + f.h() - V2.FOOT;
        drawTable(c, f, x, top, f.w(), bottom - top);
        V2.foot(c, x, bottom, f.w(), tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size()),
                tr("gui.module_shop.v2.shipping.hint"));
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private void drawToolbar(Canvas c, PageFrame f) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.shipping"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        float sx = x + 8 + tw + 10;
        search.visible(true).bounds(sx, by, 130, V2.CTRL);
        search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
        sx += 138;
        String owned = tr("gui.module_shop.shipping.only_owned");
        float ow = V2.width(owned, V2.FS_S, false) + 20;
        V2.check(c, owned, onlyOwned, sx + 2, cy, ow, null);
        ownedRect = new float[]{sx, by, ow, V2.CTRL};

        float right = x + f.w() - 8;
        String ol = tr("gui.module_shop.shipping.open");
        float olw = V2.buttonWidth(ol);
        right -= olw;
        V2.button(c, ol, V2.Btn.PRIMARY, right, by, olw, V2.CTRL, over(f, right, by, olw, V2.CTRL) ? 1 : 0);
        openRect = new float[]{right, by, olw, V2.CTRL};
        boolean canUp = ClientShopData.shipUpgradeCost >= 0L;
        String ul = canUp ? tr("gui.module_shop.shipping.upgrade_cost", ClientShopData.money(ClientShopData.shipUpgradeCost))
                : tr("gui.module_shop.shipping.max");
        float ulw = V2.buttonWidth(ul);
        right -= ulw + 5;
        V2.button(c, ul, canUp ? V2.Btn.NORMAL : V2.Btn.DISABLED, right, by, ulw, V2.CTRL, over(f, right, by, ulw, V2.CTRL) ? 1 : 0);
        upgradeRect = canUp ? new float[]{right, by, ulw, V2.CTRL} : null;
    }

    /** 概览条：一排等宽指标。 */
    private void drawOverview(Canvas c, PageFrame f, float x, float y, float w) {
        Draw.fill(c, x, y, w, OVERVIEW_H, 0, Draw.alpha(IOStyle.CARD, 0.6F));
        Draw.hairline(c, x, y + OVERVIEW_H - 0.5F, w, IOStyle.BORDER_DIM);
        String[][] cells = {
                {tr("gui.module_shop.v2.shipping.level"), ClientShopData.shipLevel + " / " + ClientShopData.shipMaxLevel,
                        tr("gui.module_shop.v2.shipping.slots", ClientShopData.shipSlots)},
                {tr("gui.module_shop.v2.shipping.items"), V2.money(ClientShopData.shipItems), ""},
                {tr("gui.module_shop.v2.shipping.value"), "+" + V2.money(ClientShopData.shipValue), ""},
                {tr("gui.module_shop.v2.shipping.today"), ClientShopData.shipLimit > 0
                        ? V2.money(ClientShopData.shipSoldToday) + " / " + V2.money(ClientShopData.shipLimit)
                        : V2.money(ClientShopData.shipSoldToday), ClientShopData.shipLimit > 0 ? "" : tr("gui.module_shop.v2.shipping.no_cap")},
                {tr("gui.module_shop.v2.shipping.next"), nextSettleText(), tr("gui.module_shop.shipping.settle_time",
                        String.format("%02d:%02d", (ClientShopData.shipSettleTick / 1000 + 6) % 24,
                                (ClientShopData.shipSettleTick % 1000) * 60 / 1000))},
                {tr("gui.module_shop.v2.shipping.reset"), ClientShopData.shipResetTimeText, ""}};
        float cw = w / cells.length;
        for (int i = 0; i < cells.length; i++) {
            float cx = x + i * cw;
            if (i > 0) {
                Draw.fill(c, cx, y + 7, 0.5F, OVERVIEW_H - 14, 0, IOStyle.BORDER_DIM);
            }
            V2.text(c, cells[i][0], V2.FS_XS, IOStyle.TEXT_3, cx + 9, y + 10, cw - 14, Text.Align.LEFT);
            int color = i == 2 ? IOStyle.ORANGE : IOStyle.TEXT;
            V2.bold(c, cells[i][1], V2.FS + 1.0F, color, cx + 9, y + 21, cw - 14, Text.Align.LEFT);
            if (!cells[i][2].isEmpty()) {
                V2.text(c, cells[i][2], V2.FS_XS, IOStyle.TEXT_3, cx + 9, y + 31, cw - 14, Text.Align.LEFT);
            }
            if (i == 0) {
                for (int p = 0; p < ClientShopData.shipMaxLevel && p < 12; p++) {
                    Draw.fill(c, cx + 50 + p * 6, y + 19, 4.5F, 4, 1, p < ClientShopData.shipLevel ? IOStyle.BLUE : IOStyle.BORDER_DIM);
                }
            }
            if (i == 3 && ClientShopData.shipLimit > 0) {
                V2.bar(c, cx + 9, y + 31, cw - 22, ClientShopData.shipSoldToday / (float) ClientShopData.shipLimit,
                        ClientShopData.shipSoldToday >= ClientShopData.shipLimit * 0.9 ? IOStyle.ORANGE : IOStyle.BLUE);
            }
        }
    }

    private static String nextSettleText() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return "—";
        }
        long timeOfDay = level.getDayTime() % 24000L;
        long start = ClientShopData.shipSettleTick;
        long remain = timeOfDay <= start ? start - timeOfDay : 24000L - timeOfDay + start;
        long minutes = Math.max(0L, remain) * 60L / 1000L;
        if (remain <= 0L || minutes < 1L) {
            return tr("gui.module_shop.shipping.settling");
        }
        return tr("gui.module_shop.shipping.next_in", minutes / 60L, minutes % 60L);
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float[][] cols = V2.columns(x, w, 10, 6, 14, 0, 80, 56, 64, 64, 80);
        String[] heads = {"", tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.mod"), tr("gui.module_shop.v2.col.price"),
                tr("gui.module_shop.v2.col.daily_cap"), tr("gui.module_shop.v2.col.have"), tr("gui.module_shop.v2.col.ship_value")};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h, cols, heads, al, null);
        if (shown.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, -1, false);
            V2.empty(c, tr("gui.module_shop.shipping.list_empty"), x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
            ShippingBinScreen.Entry e = shown.get(row);
            float cy = ry + V2.ROW / 2;
            int have = owned(e.stack());
            f.host().items().add(e.stack(), cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, false);
            V2.text(cv, e.stack().getHoverName().getString(), V2.FS, IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            V2.text(cv, ShippingBinScreen.modName(e.namespace()), V2.FS_S, IOStyle.TEXT_3, cols[2][0], cy, cols[2][1], Text.Align.LEFT);
            V2.bold(cv, V2.money(e.price()), V2.FS, IOStyle.ORANGE, cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
            V2.text(cv, e.dailyMax() > 0 ? String.valueOf(e.dailyMax()) : "∞", V2.FS_S, IOStyle.TEXT_2, cols[4][0], cy,
                    cols[4][1], Text.Align.RIGHT);
            if (have > 0) {
                V2.bold(cv, V2.money(have), V2.FS, IOStyle.TEXT, cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
                int count = e.dailyMax() > 0 ? Math.min(have, e.dailyMax()) : have;
                V2.bold(cv, V2.signed(count * e.price()), V2.FS, IOStyle.GREEN, cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
            } else {
                V2.text(cv, "—", V2.FS, IOStyle.TEXT_3, cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
                V2.text(cv, "—", V2.FS, IOStyle.TEXT_3, cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
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
        if (button != 0) {
            return false;
        }
        if (in(openRect, mx, my)) {
            ClickSound.play();
            f.host().action("shipping_open", null);
            return true;
        }
        if (in(upgradeRect, mx, my)) {
            ClickSound.play();
            f.host().action("shipping_upgrade", null);
            return true;
        }
        if (in(ownedRect, mx, my)) {
            ClickSound.play();
            onlyOwned = !onlyOwned;
            TABLE.scrollToTop();
            return true;
        }
        if (TABLE.pressBar(mx, my)) {
            return true;
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit != null && hit.row() >= 0) {
            ClickSound.play();
            f.host().action("shipping_open", null);
            return true;
        }
        return hit != null;
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
        return TABLE.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        return TABLE.wheel(mx, my, scrollY, net.minecraft.client.gui.screens.Screen.hasShiftDown());
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        TABLE.releaseBar();
    }
}
