package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.MallFavorites;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.ShopScreen;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 购物城（UI v2 列表视图）：管理员上架的常驻货架。
 *
 * <ul>
 *     <li>工具条：标题、搜索（实时过滤，支持输入法）、全部 / 收藏 / 最近购买 三段切换、补货说明；</li>
 *     <li>左侧筛选：按模组分类（带数量）、只看有货、买得起；</li>
 *     <li>表格：物品 · 模组 · 收藏 · 单价 · 库存 · 购买，点「单价 / 库存」表头排序；
 *     左键「购买」买 1 个，右键整行 = 批量购买，点星标收藏；</li>
 *     <li>最近购买：时间 · 玩家 · 物品 · 数量 · 单价 · 总价。</li>
 * </ul>
 * 过滤 / 排序 / 分类 / 收藏复用 {@link ShopScreen} 的同一份实现，动作字符串与旧界面一致。
 */
public final class MallPage implements ShopPage {
    private static final float FILTER_W = 86.0F;
    /** 跨界面重建保留：表格滚动与客户端附加筛选。 */
    private static final ListTable TABLE = new ListTable();
    private static final ListTable RECENT = new ListTable();
    private static boolean onlyStock;
    private static boolean affordable;

    private SkiaTextField search;
    private List<ClientShopData.MallView> shown = List.of();
    private float[][] segRects = new float[0][2];
    private float segY;
    private final List<float[]> filterRects = new ArrayList<>();
    private final List<String> filterKeys = new ArrayList<>();

    @Override
    public String id() {
        return "mall";
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 32)
                .placeholder(Component.translatable("gui.module_shop.mall.search_hint").getString())
                .fontSize(V2.FS_S)
                .onChange(text -> {
                    ClientShopData.mallSearch = text;
                    ClientShopData.mallPage = 0;
                    TABLE.scrollToTop();
                });
        search.setValue(ClientShopData.mallSearch == null ? "" : ClientShopData.mallSearch);
        host.registerInput(search.editBox());
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private List<ClientShopData.MallView> filtered() {
        List<ClientShopData.MallView> base = ShopScreen.mallFiltered();
        if (!onlyStock && !affordable) {
            return base;
        }
        List<ClientShopData.MallView> out = new ArrayList<>();
        for (ClientShopData.MallView v : base) {
            if (onlyStock && v.soldOut()) {
                continue;
            }
            if (affordable && v.price > ClientShopData.balance) {
                continue;
            }
            out.add(v);
        }
        return out;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas c, PageFrame f) {
        MallFavorites.ensureLoaded();
        boolean recent = ClientShopData.mallViewRecent;
        drawToolbar(c, f, recent);
        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        if (recent) {
            search.visible(false);
            drawRecent(c, f, f.x(), top, f.w(), bottom - top);
            return;
        }
        drawFilter(c, f, f.x(), top, bottom - top);
        shown = filtered();
        drawTable(c, f, f.x() + FILTER_W, top, f.w() - FILTER_W, bottom - top);
        V2.foot(c, f.x() + FILTER_W, bottom, f.w() - FILTER_W,
                tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size()),
                tr("gui.module_shop.v2.mall.hint"));
    }

    private void drawToolbar(Canvas c, PageFrame f, boolean recent) {
        float x = f.x();
        float y = f.y();
        float cy = y + V2.BAR / 2;
        Draw.hairline(c, x, y + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.mall"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 80, Text.Align.LEFT);
        float sx = x + 8 + tw + 10;
        if (!recent) {
            search.visible(true).bounds(sx, cy - V2.CTRL / 2, 140, V2.CTRL);
            search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
            sx += 146;
        }
        String[] seg = {
                tr("gui.module_shop.v2.mall.all") + "  " + ClientShopData.mall.size(),
                tr("gui.module_shop.v2.mall.fav") + "  " + MallFavorites.size(),
                tr("gui.module_shop.mall.recent")};
        int sel = recent ? 2 : ClientShopData.mallFavOnly ? 1 : 0;
        this.segY = cy - V2.CTRL / 2;
        this.segRects = V2.segmented(c, seg, sel, sx, this.segY, null);
        // 右侧：补货 / 个人库存说明
        List<String> notes = new ArrayList<>();
        if (ClientShopData.mallStockPersonal) {
            notes.add(tr("gui.module_shop.mall.personal_stock"));
        }
        if (ClientShopData.mallRestockOn) {
            notes.add(tr("gui.module_shop.mall.restock_status", ClientShopData.mallRestockTimeText,
                    Component.translatable("gui.module_shop.choice.restock_mode."
                            + ("game".equals(ClientShopData.mallRestockMode) ? "game" : "real")),
                    ClientShopData.mallRestockAmount));
        }
        if (!notes.isEmpty()) {
            float segEnd = this.segRects[this.segRects.length - 1][0] + this.segRects[this.segRects.length - 1][1] + 12;
            V2.text(c, String.join(" · ", notes), V2.FS_XS, IOStyle.TEXT_3, segEnd, cy, x + f.w() - 8 - segEnd,
                    Text.Align.RIGHT);
        }
    }

    private void drawFilter(Canvas c, PageFrame f, float x, float y, float h) {
        filterRects.clear();
        filterKeys.clear();
        Draw.fill(c, x + FILTER_W - 0.5F, y, 0.5F, h, 0, IOStyle.BORDER_DIM);
        // 各模组数量按当前搜索 / 收藏条件统计（不受分类本身影响）
        Map<String, Integer> counts = new LinkedHashMap<>();
        String savedCat = ClientShopData.mallCategory;
        ClientShopData.mallCategory = "";
        List<ClientShopData.MallView> all = ShopScreen.mallFiltered();
        ClientShopData.mallCategory = savedCat;
        for (ClientShopData.MallView v : all) {
            counts.merge(v.namespace(), 1, Integer::sum);
        }
        float cy = y + 8;
        V2.section(c, tr("gui.module_shop.v2.filter.mods"), x + 8, cy, FILTER_W - 12);
        cy += 6;
        List<String> mods = new ArrayList<>();
        mods.add("");
        mods.addAll(ShopScreen.mallCategories());
        float listBottom = y + h - 64;
        for (String ns : mods) {
            if (cy + 13 > listBottom) {
                break;
            }
            String label = ns.isEmpty() ? tr("gui.module_shop.mall.cat_all") : ShopScreen.mallModName(ns);
            int n = ns.isEmpty() ? all.size() : counts.getOrDefault(ns, 0);
            boolean on = ns.equals(ClientShopData.mallCategory);
            boolean over = f.mouseX() >= x + 4 && f.mouseX() < x + FILTER_W - 4 && f.mouseY() >= cy && f.mouseY() < cy + 13;
            V2.filterItem(c, label, String.valueOf(n), on, over ? 1 : 0, x + 4, cy, FILTER_W - 8);
            filterRects.add(new float[]{x + 4, cy, FILTER_W - 8, 13});
            filterKeys.add("cat:" + ns);
            cy += 14;
        }
        cy += 6;
        Draw.hairline(c, x + 6, cy, FILTER_W - 12, IOStyle.BORDER_DIM);
        cy += 8;
        V2.section(c, tr("gui.module_shop.v2.filter.title"), x + 8, cy, FILTER_W - 12);
        cy += 10;
        V2.check(c, tr("gui.module_shop.v2.filter.in_stock"), onlyStock, x + 8, cy, FILTER_W - 14, null);
        filterRects.add(new float[]{x + 4, cy - 6.5F, FILTER_W - 8, 13});
        filterKeys.add("stock");
        cy += 14;
        V2.check(c, tr("gui.module_shop.v2.filter.affordable"), affordable, x + 8, cy, FILTER_W - 14, null);
        filterRects.add(new float[]{x + 4, cy - 6.5F, FILTER_W - 8, 13});
        filterKeys.add("afford");
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float[][] cols = V2.columns(x, w, 8, 6, 14, 0, 72, 26, 58, 66, 40);
        String[] heads = {"", tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.mod"),
                tr("gui.module_shop.v2.col.fav"), tr("gui.module_shop.v2.col.price"), tr("gui.module_shop.v2.col.stock"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.CENTER, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h, cols, heads, al, new boolean[]{false, false, false, false, true, true, false});
        // 表头排序状态由 ClientShopData.mallSort 决定（1/2 = 价格升 / 降，3/4 = 库存升 / 降）
        int sort = ClientShopData.mallSort;
        TABLE.sortCol = sort == 1 || sort == 2 ? 4 : sort == 3 || sort == 4 ? 5 : -1;
        TABLE.sortDesc = sort == 2 || sort == 4;
        if (shown.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, TABLE.sortCol, TABLE.sortDesc);
            V2.empty(c, tr(ClientShopData.mall.isEmpty() ? "gui.module_shop.mall.empty" : "gui.module_shop.v2.no_match"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
            ClientShopData.MallView v = shown.get(row);
            float cy = ry + V2.ROW / 2;
            boolean sold = v.soldOut();
            Draw.fill(cv, cols[0][0] - 5, ry + 3, 1.5F, V2.ROW - 6, 0.75F, IOStyle.rarity(v.rarity));
            f.host().items().add(v.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
            String name = v.stack.getHoverName().getString();
            V2.text(cv, name, V2.FS, sold ? IOStyle.TEXT_3 : IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            V2.text(cv, ShopScreen.mallModName(v.namespace()), V2.FS_S, IOStyle.TEXT_3, cols[2][0], cy, cols[2][1],
                    Text.Align.LEFT);
            boolean fav = MallFavorites.contains(ShopScreen.mallItemId(v));
            V2.text(cv, fav ? "★" : "☆", V2.FS, fav ? IOStyle.ORANGE : IOStyle.TEXT_3, cols[3][0], cy, cols[3][1],
                    Text.Align.CENTER);
            V2.bold(cv, V2.money(v.price), V2.FS, sold ? IOStyle.TEXT_3 : IOStyle.ORANGE, cols[4][0], cy, cols[4][1],
                    Text.Align.RIGHT);
            int stockColor = sold ? IOStyle.RED : v.unlimited() ? IOStyle.TEXT_2 : IOStyle.TEXT;
            V2.text(cv, ShopScreen.mallStockText(v).getString(), V2.FS_S, stockColor, cols[5][0], cy, cols[5][1],
                    Text.Align.RIGHT);
            float right = cols[6][0] + cols[6][1];
            if (sold) {
                TABLE.button(cv, row, "none", tr("gui.module_shop.v2.sold_out"), V2.Btn.DISABLED, right, ry, hover);
            } else {
                TABLE.button(cv, row, "buy", tr("gui.module_shop.v2.buy"), V2.Btn.NORMAL, right, ry, hover);
            }
        });
    }

    private void drawRecent(Canvas c, PageFrame f, float x, float y, float w, float h) {
        List<ClientShopData.MallPurchaseView> list = ClientShopData.mallRecent;
        float[][] cols = V2.columns(x, w, 8, 6, 70, 90, 14, 0, 48, 64, 72);
        String[] heads = {tr("gui.module_shop.v2.col.time"), tr("gui.module_shop.v2.col.player"), "",
                tr("gui.module_shop.v2.col.item"), tr("gui.module_shop.v2.col.count"), tr("gui.module_shop.v2.col.price"),
                tr("gui.module_shop.v2.col.total")};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        RECENT.layout(x, y, w, h, cols, heads, al, null);
        if (list.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, -1, false);
            V2.empty(c, tr("gui.module_shop.mall.recent_empty"), x, y + V2.HEAD, w, h - V2.HEAD);
        } else {
            RECENT.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), list.size(), (cv, row, ry, hover) -> {
                ClientShopData.MallPurchaseView r = list.get(row);
                float cy = ry + V2.ROW / 2;
                V2.text(cv, ShopScreen.mallTimeText(r.time), V2.FS_S, IOStyle.TEXT_2, cols[0][0], cy, cols[0][1], Text.Align.LEFT);
                V2.text(cv, r.playerName, V2.FS_S, IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
                f.host().items().add(r.stack, cols[2][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, false);
                V2.text(cv, r.stack.getHoverName().getString(), V2.FS, IOStyle.TEXT, cols[3][0], cy, cols[3][1], Text.Align.LEFT);
                V2.text(cv, "×" + r.count, V2.FS_S, IOStyle.TEXT_2, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
                V2.text(cv, V2.money(r.unitPrice), V2.FS_S, IOStyle.TEXT_2, cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
                V2.bold(cv, V2.money(r.total), V2.FS, IOStyle.ORANGE, cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
            });
        }
        V2.foot(c, x, y + h, w, tr("gui.module_shop.v2.mall.recent_count", list.size()), null);
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
        if (button == 0) {
            for (int i = 0; i < segRects.length; i++) {
                if (mx >= segRects[i][0] && mx < segRects[i][0] + segRects[i][1] && my >= segY && my < segY + V2.CTRL) {
                    clickSegment(f, i);
                    return true;
                }
            }
        }
        if (ClientShopData.mallViewRecent) {
            return button == 0 && RECENT.pressBar(mx, my);
        }
        if (button == 0) {
            for (int i = 0; i < filterRects.size(); i++) {
                float[] r = filterRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    clickFilter(f, filterKeys.get(i));
                    return true;
                }
            }
            if (TABLE.pressBar(mx, my)) {
                return true;
            }
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null) {
            return false;
        }
        if ("sort".equals(hit.button())) {
            // 表头：单价 / 库存，升降切换 → 映射回原有的 mall_sort_pick
            int sort = TABLE.sortCol == 4 ? (TABLE.sortDesc ? 2 : 1) : TABLE.sortCol == 5 ? (TABLE.sortDesc ? 4 : 3) : 0;
            CompoundTag extra = new CompoundTag();
            extra.putInt("sort", sort);
            ClickSound.play();
            f.host().action("mall_sort_pick", extra);
            return true;
        }
        if (hit.row() < 0 || hit.row() >= shown.size()) {
            return false;
        }
        ClientShopData.MallView v = shown.get(hit.row());
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", v.shelf);
        float[][] cols = TABLE.cols();
        boolean onStar = mx >= cols[3][0] && mx < cols[3][0] + cols[3][1];
        if (onStar) {
            if (button == 0) {
                f.host().action("mall_star", extra);
            }
            return true;
        }
        if (button == 1) {
            if (!v.soldOut()) {
                ClickSound.play();
                f.host().action("mall_buy_many", extra);
            }
            return true;
        }
        if (button == 0 && "buy".equals(hit.button())) {
            ClickSound.play();
            f.host().action("mall_buy", extra);
            return true;
        }
        return hit.button() != null;
    }

    private void clickSegment(PageFrame f, int index) {
        ClickSound.play();
        boolean recent = ClientShopData.mallViewRecent;
        switch (index) {
            case 0 -> {
                if (recent) {
                    f.host().action("mall_recent_toggle", null);
                }
                if (ClientShopData.mallFavOnly) {
                    f.host().action("mall_fav_toggle", null);
                }
            }
            case 1 -> {
                if (recent) {
                    f.host().action("mall_recent_toggle", null);
                }
                if (!ClientShopData.mallFavOnly) {
                    f.host().action("mall_fav_toggle", null);
                }
            }
            default -> {
                if (!recent) {
                    f.host().action("mall_recent_toggle", null);
                }
            }
        }
        TABLE.scrollToTop();
    }

    private void clickFilter(PageFrame f, String key) {
        ClickSound.play();
        if (key.startsWith("cat:")) {
            CompoundTag extra = new CompoundTag();
            extra.putString("category", key.substring(4));
            f.host().action("mall_cat_pick", extra);
        } else if ("stock".equals(key)) {
            onlyStock = !onlyStock;
        } else if ("afford".equals(key)) {
            affordable = !affordable;
        }
        TABLE.scrollToTop();
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
        return TABLE.releaseBar() | RECENT.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return ClientShopData.mallViewRecent ? RECENT.dragBar(my) : TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        return ClientShopData.mallViewRecent ? RECENT.wheel(mx, my, scrollY, page) : TABLE.wheel(mx, my, scrollY, page);
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        TABLE.releaseBar();
        RECENT.releaseBar();
    }
}
