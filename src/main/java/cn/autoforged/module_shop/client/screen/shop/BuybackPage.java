package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.UIViewMode;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.ShopConfirmScreen;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.CardGrid;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.DailyBuybackEntry;
import cn.autoforged.module_shop.data.PriceSpec;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final CardGrid GRID = new CardGrid();
    /** 0 = 全部，1 = 背包里有，2 = 已收满。 */
    private static int filter;
    private static String query = "";

    private SkiaTextField search;
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

    /** 背包里与收购品同种物品的数量（与服务端 takeMatching 一样只比较物品种类，含护甲 / 副手栏）。 */
    static int countInBag(ItemStack template) {
        var player = Minecraft.getInstance().player;
        if (player == null || template.isEmpty()) {
            return 0;
        }
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == template.getItem()) {
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
        long starGain = 0;
        Map<String, Long> physGain = new LinkedHashMap<>();
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
                PriceSpec spec = e.resolvePriceSpec();
                String ch = resolveSellChannel(spec);
                if (spec.mode == PriceSpec.CurrencyMode.PHYS_ONLY
                        || (spec.mode == PriceSpec.CurrencyMode.EITHER && ClientHomeCurrencyState.CHANNEL_PHYS.equals(ch))) {
                    physGain.merge(spec.physTierKey, spec.physCount * (long) sell, Long::sum);
                } else {
                    if (spec.supportsStar()) {
                        starGain += spec.starPrice * (long) sell;
                    }
                    if (spec.mode == PriceSpec.CurrencyMode.BOTH && spec.supportsPhys()) {
                        physGain.merge(spec.physTierKey, spec.physCount * (long) sell, Long::sum);
                    }
                }
            }
            if ((filter == 0 || (filter == 1 && have > 0) || (filter == 2 && e.remaining() <= 0))
                    && (query.isBlank() || PageKit.matchesItemQuery(e.stack, query))) {
                shown.add(i);
            }
        }
        String gainSummary = PriceDisplay.formatLedgerSummary(starGain, physGain);
        drawToolbar(c, f, list.size(), haveCount, fullCount, sumKinds, gainSummary);
        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        if (UIViewMode.isGrid()) {
            drawGrid(c, f, f.x(), top, f.w(), bottom - top);
            String left = tr("gui.module_shop.v2.showing", GRID.firstVisible(), GRID.lastVisible(), shown.size());
            V2.foot(c, f.x(), bottom, f.w(), left, tr("gui.module_shop.v2.buyback.hint"));
        } else {
            drawTable(c, f, f.x(), top, f.w(), bottom - top);
            String left = tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size());
            V2.foot(c, f.x(), bottom, f.w(), left, tr("gui.module_shop.v2.buyback.hint"));
        }
    }

    private void drawToolbar(Canvas c, PageFrame f, int all, int have, int full, int kinds, String gainSummary) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.buyback"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        String[] seg = {tr("gui.module_shop.v2.mall.all") + "  " + all, tr("gui.module_shop.v2.buyback.have") + "  " + have,
                tr("gui.module_shop.v2.buyback.full") + "  " + full};
        segY = cy - V2.CTRL / 2;
        segRects = V2.segmented(c, seg, filter, x + 8 + tw + 10, segY, null);

        // 右侧从右往左：全部卖出 / 刷新 / 管理员奖励设置 / 搜索 / 说明文字
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
        boolean canRefresh = ClientShopData.hasBuybackRefreshCost() && !ClientShopData.dailyBuyback.isEmpty();
        float hr = over(f, right, segY, rw, V2.CTRL) ? 1 : 0;
        V2.button(c, refreshLabel, canRefresh ? V2.Btn.NORMAL : V2.Btn.DISABLED, right, segY, rw, V2.CTRL, hr);
        refreshRect = canRefresh ? new float[]{right, segY, rw, V2.CTRL} : null;
        if (hr > 0) {
            int left = Math.max(0, ClientShopData.buybackRefreshMax - ClientShopData.buybackRefreshUsed);
            f.host().textTooltip(canRefresh
                    ? Component.translatable("gui.module_shop.refresh_buyback_tooltip",
                    PriceDisplay.formatPlain(ClientShopData.resolveBuybackRefreshSpec()), left,
                    ClientShopData.buybackRefreshMax)
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
        float segEnd = segRects[segRects.length - 1][0] + segRects[segRects.length - 1][1] + 8;
        float avail = right - 8 - segEnd;
        float afterSearch = segEnd;
        if (search != null) {
            if (avail >= 48.0F) {
                float srW = Math.min(125.0F, Math.max(48.0F, avail >= 210.0F ? 105.0F : avail));
                search.visible(true).bounds(segEnd, segY, srW, V2.CTRL);
                search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
                afterSearch = segEnd + srW + 8.0F;
            } else {
                search.visible(false);
            }
        }
        // 说明：全清奖励进度 + 背包可卖合计
        List<String> notes = new ArrayList<>();
        if (ClientShopData.buybackBonusEnabled && ClientShopData.buybackBonusSpec.isValid()) {
            String bonusStr = PriceDisplay.formatPlain(ClientShopData.buybackBonusSpec);
            notes.add(ClientShopData.buybackBonusDone
                    ? tr("gui.module_shop.buyback_bonus_done", bonusStr)
                    : tr("gui.module_shop.buyback_bonus_progress", bonusStr, ClientShopData.buybackLeft));
        }
        if (kinds > 0) {
            notes.add(tr("gui.module_shop.v2.buyback.can_sell", kinds, gainSummary));
        }
        if (!notes.isEmpty() && right - 8 - afterSearch >= 65.0F) {
            V2.text(c, String.join("  ·  ", notes), V2.FS_XS, IOStyle.TEXT_2, afterSearch, cy, right - 8 - afterSearch,
                    Text.Align.RIGHT);
        }
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        String fullLabel = tr("gui.module_shop.v2.buyback.full");
        float actW = Math.max(40.0F, Math.max(V2.buttonWidth(fullLabel), V2.buttonWidth(tr("gui.module_shop.v2.sell"))));
        float gap = 5.0F;
        float[][] baseCols = V2.columns(x, w - actW - gap, 10, gap, 14, 0, 64, 44, 84, 68);
        float[][] cols = new float[7][2];
        System.arraycopy(baseCols, 0, cols, 0, 6);
        cols[6][0] = x + w - 10 - actW;
        cols[6][1] = actW;
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
            float tagReserve = full ? (V2.tagWidth(fullLabel) + 5.0F) : 0.0F;
            float nameMaxW = Math.max(16.0F, cols[1][1] - tagReserve);
            float nw = V2.text(cv, e.stack.getHoverName().getString(), V2.FS, full ? IOStyle.TEXT_3 : IOStyle.TEXT,
                    nx, cy, nameMaxW, Text.Align.LEFT);
            if (full) {
                V2.tag(cv, fullLabel, IOStyle.TEXT_3, nx + Math.min(nw, nameMaxW) + 5, cy);
            }
            PriceSpec spec = e.resolvePriceSpec();
            PriceDisplay.drawCell(cv, f.host().items(), spec, cols[2][0], cy, cols[2][1], Text.Align.RIGHT,
                    IOStyle.ORANGE, false);
            if (have > 0) {
                V2.bold(cv, V2.money(have), V2.FS, IOStyle.TEXT, cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
            } else {
                V2.text(cv, "—", V2.FS, IOStyle.TEXT_3, cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
            }
            float barW = Math.min(30.0F, cols[4][1] * 0.34F);
            float pct = e.limit <= 0 ? 1 : e.sold / (float) e.limit;
            String quota = e.sold + " / " + e.limit;
            V2.bar(cv, cols[4][0] + cols[4][1] - barW, cy, barW, pct, IOStyle.BLUE);
            V2.text(cv, quota, V2.FS_S, IOStyle.TEXT_2, cols[4][0], cy, Math.max(12.0F, cols[4][1] - barW - 4),
                    Text.Align.RIGHT);
            if (sell > 0) {
                PriceDisplay.drawCell(cv, f.host().items(), spec.scale(sell), cols[5][0], cy, cols[5][1],
                        Text.Align.RIGHT, IOStyle.GREEN, false, "+");
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

    private void drawGrid(Canvas c, PageFrame f, float x, float y, float w, float h) {
        List<DailyBuybackEntry> list = ClientShopData.dailyBuyback;
        GRID.layoutUnified(x, y, w, h);
        if (shown.isEmpty()) {
            V2.empty(c, tr(list.isEmpty() ? "gui.module_shop.empty" : "gui.module_shop.v2.no_match"), x, y, w, h);
            return;
        }
        GRID.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, card, cx, cy, cw, ch, hover) -> {
            DailyBuybackEntry e = list.get(shown.get(card));
            int have = countInBag(e.stack);
            int sell = sellable(e);
            boolean full = e.remaining() <= 0;
            int rc = IOStyle.rarity(e.rarity);
            CardGrid.cardBg(cv, cx, cy, cw, ch, hover, rc, have > 0 && !full, full);

            if (sell > 0) {
                V2.bold(cv, "×" + V2.money(sell), V2.FS_XS, IOStyle.GREEN, cx + 4, cy + 7.0F, cw * 0.48F, Text.Align.LEFT);
            } else {
                V2.text(cv, have > 0 ? "×" + V2.money(have) : "—", V2.FS_XS,
                        have > 0 ? IOStyle.TEXT_2 : IOStyle.TEXT_3, cx + 4, cy + 7.0F, cw * 0.48F, Text.Align.LEFT);
            }
            V2.text(cv, full ? tr("gui.module_shop.v2.buyback.full") : (e.sold + "/" + e.limit),
                    V2.FS_XS, full ? IOStyle.RED : IOStyle.TEXT_3,
                    cx + cw * 0.46F, cy + 7.0F, cw * 0.54F - 4, Text.Align.RIGHT);

            float iconSize = 16.0F;
            f.host().items().add(e.stack, cx + (cw - iconSize) * 0.5F, cy + 13.0F, iconSize, true);

            V2.text(cv, e.stack.getHoverName().getString(), V2.FS_S, full ? IOStyle.TEXT_3 : IOStyle.TEXT,
                    cx + 3, cy + 34.5F, cw - 6, Text.Align.CENTER);
            PriceDisplay.drawCard(cv, f.host().items(), e.resolvePriceSpec(), cx + 3, cy + 44.5F, cx + cw * 0.5F,
                    cw - 6, 1.0F, full ? IOStyle.TEXT_3 : IOStyle.ORANGE, full);
            float pct = e.limit <= 0 ? 1.0F : e.sold / (float) e.limit;
            V2.bar(cv, cx + 5, cy + 50.5F, cw - 10, pct, full ? IOStyle.TEXT_3 : (have > 0 ? IOStyle.GREEN : IOStyle.BLUE));
        });
    }

    // ------------------------------------------------------------------ 输入

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
            if (UIViewMode.isGrid() ? GRID.pressBar(mx, my) : TABLE.pressBar(mx, my)) {
                return true;
            }
        }
        if (UIViewMode.isGrid()) {
            CardGrid.Hit hit = GRID.click(mx, my);
            if (hit == null || hit.index() < 0 || hit.index() >= shown.size()) {
                return hit != null;
            }
            int index = shown.get(hit.index());
            DailyBuybackEntry e = ClientShopData.dailyBuyback.get(index);
            if (e.remaining() <= 0) {
                return true;
            }
            if (button == 0 && Net.shift()) {
                int sell = sellable(e);
                if (sell > 0) {
                    ClickSound.play();
                    sendSell(f, index, sell);
                }
                return true;
            }
            if (button == 0 && ("sell".equals(hit.button()) || hit.button() == null)) {
                ClickSound.play();
                CompoundTag tag = new CompoundTag();
                tag.putInt("index", index);
                f.host().action("sell_buyback_gui", tag);
                return true;
            }
            return hit.button() != null;
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

    private static String resolveSellChannel(PriceSpec spec) {
        if (spec == null) {
            return ClientHomeCurrencyState.CHANNEL_STAR;
        }
        return switch (spec.mode) {
            case STAR_ONLY -> ClientHomeCurrencyState.CHANNEL_STAR;
            case PHYS_ONLY -> ClientHomeCurrencyState.CHANNEL_PHYS;
            case BOTH -> "both";
            case EITHER -> ClientHomeCurrencyState.CHANNEL_PHYS.equals(ClientHomeCurrencyState.preferredEitherChannel)
                    ? ClientHomeCurrencyState.CHANNEL_PHYS
                    : ClientHomeCurrencyState.CHANNEL_STAR;
        };
    }

    private static void sendSell(PageFrame f, int index, int quantity) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", index);
        tag.putString("quantity", String.valueOf(quantity));
        if (index >= 0 && index < ClientShopData.dailyBuyback.size()) {
            tag.putString("pay_channel", resolveSellChannel(ClientShopData.dailyBuyback.get(index).resolvePriceSpec()));
        }
        f.host().action("sell_buyback", tag);
    }

    private void confirmSellAll(PageFrame f) {
        List<DailyBuybackEntry> list = ClientShopData.dailyBuyback;
        List<int[]> plan = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        long starTotal = 0;
        Map<String, Long> physTotal = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            DailyBuybackEntry e = list.get(i);
            int sell = sellable(e);
            if (sell <= 0) {
                continue;
            }
            plan.add(new int[]{i, sell});
            PriceSpec unitSpec = e.resolvePriceSpec();
            PriceSpec rowSpec = unitSpec.scale(sell);
            String ch = resolveSellChannel(unitSpec);
            if (unitSpec.mode == PriceSpec.CurrencyMode.PHYS_ONLY
                    || (unitSpec.mode == PriceSpec.CurrencyMode.EITHER && ClientHomeCurrencyState.CHANNEL_PHYS.equals(ch))) {
                physTotal.merge(unitSpec.physTierKey, unitSpec.physCount * (long) sell, Long::sum);
                rowSpec = PriceSpec.phys(unitSpec.physTierKey, unitSpec.physCount * (long) sell);
            } else {
                if (unitSpec.supportsStar()) {
                    starTotal += unitSpec.starPrice * (long) sell;
                }
                if (unitSpec.mode == PriceSpec.CurrencyMode.BOTH && unitSpec.supportsPhys()) {
                    physTotal.merge(unitSpec.physTierKey, unitSpec.physCount * (long) sell, Long::sum);
                } else if (unitSpec.mode == PriceSpec.CurrencyMode.EITHER) {
                    rowSpec = PriceSpec.star(unitSpec.starPrice * (long) sell);
                }
            }
            if (plan.size() <= 8) {
                lines.append("\n").append(e.stack.getHoverName().getString()).append(" ×").append(sell)
                        .append("  +").append(PriceDisplay.formatPlain(rowSpec));
            }
        }
        if (plan.isEmpty()) {
            return;
        }
        if (plan.size() > 8) {
            lines.append("\n").append(tr("gui.module_shop.v2.buyback.more", plan.size() - 8));
        }
        Component body = Component.translatable("gui.module_shop.v2.buyback.sell_all_body", plan.size(),
                PriceDisplay.formatLedgerSummary(starTotal, physTotal), lines.toString());
        ShopHost host = f.host();
        Nav.open(new ShopConfirmScreen(Component.translatable("gui.module_shop.v2.buyback.sell_all_title"), body, ok -> {
            if (ok) {
                for (int[] p : plan) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("index", p[0]);
                    tag.putString("quantity", String.valueOf(p[1]));
                    if (p[0] >= 0 && p[0] < ClientShopData.dailyBuyback.size()) {
                        tag.putString("pay_channel",
                                resolveSellChannel(ClientShopData.dailyBuyback.get(p[0]).resolvePriceSpec()));
                    }
                    host.action("sell_buyback", tag);
                }
            }
        }));
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
        return UIViewMode.isGrid() ? GRID.wheel(mx, my, scrollY, Net.shift()) : TABLE.wheel(mx, my, scrollY, Net.shift());
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        TABLE.releaseBar();
        GRID.releaseBar();
    }

    /** 小工具：Shift 是否按下。 */
    static final class Net {
        static boolean shift() {
            return net.minecraft.client.gui.screens.Screen.hasShiftDown();
        }
    }
}
