package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.AuctionClient;
import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.auction.AuctionUi;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.shop.auction.AuctionCategory;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * 拍卖大厅（Skija 版）。
 *
 * <ul>
 *     <li>顶栏：搜索（名称 / 物品 id / 模组名 / 附魔 / 卖家，多个关键词空格分隔，实时筛选）、分类下拉、排序下拉、我的拍卖、管理（管理员）；</li>
 *     <li>列表：分页显示，每页行数随窗口高度自适应；滚轮翻页；数据变化后自动夹紧页码，不会翻到空白页；</li>
 *     <li>每行：物品（悬停完整 tooltip）/ 名称 / 卖家｜当前价或起拍价 + 最低下次出价｜一口价 + 竞拍人数｜倒计时 + 领先者；
 *     左侧色条：橙 = 我的拍卖，绿 = 我领先，红 = 我被超过；</li>
 *     <li>底栏：页码 / 总数 / 我的在售数量 + 翻页 + 上架。</li>
 * </ul>
 * 点击任意一行打开详情页（出价、一口价、撤销都在详情页里完成）。
 */
public final class AuctionPage implements ShopPage {
    private static final float ROW_H = 31.0F;
    private static final float CARD_H = 28.0F;
    private static final float CTRL_H = 16.0F;
    private static final float GAP = 4.0F;
    private static final float MENU_ROW = 14.0F;

    private SkiaTextField search;
    private boolean catOpen;
    private boolean sortOpen;
    private float[] hoverT = new float[0];

    private final Button catBtn = new Button(Button.Kind.NORMAL, () -> Component.translatable(
            "gui.module_shop.auction.cat_prefix",
            Component.translatable(AuctionClient.category.langKey())).getString(), null).radius(2.0F).fontSize(Text.SMALL);
    private final Button sortBtn = new Button(Button.Kind.NORMAL, () -> Component.translatable(
            "gui.module_shop.auction.sort_prefix",
            Component.translatable(AuctionClient.sortKey(AuctionClient.sort))).getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button mineBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.auction.mine_btn").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button adminBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.auction.admin_btn").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button create = new Button(Button.Kind.ACCENT,
            () -> Component.translatable("gui.module_shop.auction_create").getString(), null).radius(3.0F);
    private final Button prevBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.page_prev").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button nextBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.page_next").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);

    // 本帧几何
    private float listX, listY, listW, listH;
    private float menuX, menuY, menuW, menuH;
    private int perPage = 1;
    private List<ClientShopData.AuctionView> shown = List.of();

    @Override
    public String id() {
        return "auction";
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 40)
                .placeholder(Component.translatable("gui.module_shop.auction.search_hint").getString())
                .fontSize(Text.SMALL)
                .onChange(text -> {
                    AuctionClient.search = text;
                    AuctionClient.page = 0;
                });
        search.setValue(AuctionClient.search == null ? "" : AuctionClient.search);
        host.registerInput(search.editBox());
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        AuctionClient.ensureList();
        boolean admin = AuctionClient.settings.admin || ClientShopData.canAdmin;
        float x = f.x();
        float y = f.y();
        float w = f.w();
        // ---- 顶栏 ----
        float mineW = 58.0F;
        float adminW = admin ? 36.0F : 0.0F;
        float catW = 82.0F;
        float sortW = 96.0F;
        float searchW = Math.max(60.0F, w - catW - sortW - mineW - adminW - GAP * (admin ? 4 : 3));
        search.bounds(x, y, searchW, CTRL_H).visible(true);
        search.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float bx = x + searchW + GAP;
        catBtn.kind(catOpen || AuctionClient.category != AuctionCategory.ALL ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> {
                    catOpen = !catOpen;
                    sortOpen = false;
                }).bounds(bx, y, catW, CTRL_H);
        catBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        bx += catW + GAP;
        sortBtn.kind(sortOpen ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> {
                    sortOpen = !sortOpen;
                    catOpen = false;
                }).bounds(bx, y, sortW, CTRL_H);
        sortBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float catX = x + searchW + GAP;
        float sortX = bx;
        bx += sortW + GAP;
        mineBtn.onClick(AuctionUi::openMine).bounds(bx, y, mineW, CTRL_H);
        mineBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        bx += mineW + GAP;
        adminBtn.visible(admin);
        if (admin) {
            adminBtn.onClick(AuctionUi::openAdmin).bounds(bx, y, adminW, CTRL_H);
            adminBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }

        // ---- 列表 ----
        boolean menu = catOpen || sortOpen;
        listX = x;
        listY = y + CTRL_H + GAP + 2.0F;
        listW = w;
        listH = f.listBottom() - listY;
        perPage = Math.max(1, (int) ((listH + (ROW_H - CARD_H)) / ROW_H));
        shown = AuctionClient.filtered();
        int pages = Math.max(1, (shown.size() + perPage - 1) / perPage);
        AuctionClient.page = Math.max(0, Math.min(AuctionClient.page, pages - 1));
        if (hoverT.length != perPage) {
            hoverT = new float[perPage];
        }
        if (shown.isEmpty()) {
            String key = ClientShopData.auctions.isEmpty() ? (AuctionClient.localVersion == 0L
                    ? "gui.module_shop.loading" : "gui.module_shop.auction_empty")
                    : "gui.module_shop.auction.no_match";
            PageKit.emptyText(canvas, Component.translatable(key).getString(), x, listY + listH / 2.0F, w);
        }
        for (int i = 0; i < perPage; i++) {
            int index = AuctionClient.page * perPage + i;
            if (index >= shown.size()) {
                break;
            }
            float ry = listY + i * ROW_H;
            boolean hover = !menu && f.mouseX() >= listX && f.mouseX() < listX + listW
                    && f.mouseY() >= ry && f.mouseY() < ry + CARD_H;
            hoverT[i] = Motion.approach(hoverT[i], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
            drawRow(canvas, f, shown.get(index), listX, ry, listW, hoverT[i], menu);
        }

        // ---- 底栏 ----
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        float right = x + w - 4.0F;
        float createW = 70.0F;
        create.onClick(() -> AuctionUi.openCreate(null));
        create.enabled(AuctionClient.settings.enabled)
                .bounds(right - createW, barMid - 8.0F, createW, 16.0F);
        create.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        right -= createW + 6.0F;
        prevBtn.visible(pages > 1);
        nextBtn.visible(pages > 1);
        if (pages > 1) {
            right -= 40.0F;
            nextBtn.enabled(AuctionClient.page < pages - 1).bounds(right, barMid - 6.0F, 40.0F, 12.0F);
            nextBtn.onClick(() -> AuctionClient.page++);
            nextBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            right -= 44.0F;
            prevBtn.enabled(AuctionClient.page > 0).bounds(right, barMid - 6.0F, 40.0F, 12.0F);
            prevBtn.onClick(() -> AuctionClient.page = Math.max(0, AuctionClient.page - 1));
            prevBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }
        String status = Component.translatable("gui.module_shop.auction.status_bar", AuctionClient.page + 1, pages,
                shown.size(), AuctionClient.settings.myActive, AuctionClient.settings.maxPerPlayer).getString();
        if (!AuctionClient.settings.enabled) {
            status = Component.translatable("gui.module_shop.auction.closed").getString() + "  ·  " + status;
        }
        Text.drawLine(canvas, status, AtomUi.REGULAR, Text.SMALL,
                AuctionClient.settings.enabled ? IOStyle.TEXT_2 : IOStyle.RED,
                x + 4.0F, barMid, right - 8.0F - (x + 4.0F), Text.Align.LEFT);

        // ---- 下拉（最后画，盖在列表上）----
        if (catOpen) {
            AuctionCategory[] cats = AuctionCategory.values();
            openMenu(canvas, catX, y + CTRL_H + 2.0F, 110.0F, cats.length);
            for (int i = 0; i < cats.length; i++) {
                menuRow(canvas, f, i, Component.translatable(cats[i].langKey()).getString(),
                        AuctionClient.category == cats[i]);
            }
        } else if (sortOpen) {
            openMenu(canvas, sortX, y + CTRL_H + 2.0F, 110.0F, AuctionClient.SORT_COUNT);
            for (int i = 0; i < AuctionClient.SORT_COUNT; i++) {
                menuRow(canvas, f, i, Component.translatable(AuctionClient.sortKey(i)).getString(),
                        AuctionClient.sort == i);
            }
        }
    }

    private void drawRow(Canvas canvas, PageFrame f, ClientShopData.AuctionView v, float x, float y, float w,
                         float hover, boolean menu) {
        PageKit.hoverCard(canvas, x, y, w, CARD_H, hover);
        // 左侧状态条：我的拍卖 / 我领先 / 我被超过
        int bar = v.own ? IOStyle.ORANGE : v.topMe ? IOStyle.GREEN : v.iBid ? IOStyle.RED : 0;
        if (bar != 0) {
            Draw.fill(canvas, x + 1.0F, y + 4.0F, 2.0F, CARD_H - 8.0F, 0.0F, bar);
        }
        Draw.fill(canvas, x + 5.0F, y + 5.0F, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
        Draw.stroke(canvas, x + 5.0F, y + 5.0F, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
        if (!menu) {
            f.host().items().add(v.stack, x + 6.0F, y + 6.0F);
            if (f.mouseX() >= x + 5.0F && f.mouseX() < x + 23.0F && f.mouseY() >= y + 5.0F && f.mouseY() < y + 23.0F) {
                f.host().items().setHovered(v.stack);
            }
        }
        float colA = x + 28.0F;
        float colB = x + w * 0.38F;
        float colC = x + w * 0.60F;
        float colD = x + w * 0.79F;
        float right = x + w - 6.0F;
        float top = y + 9.0F;
        float bottom = y + 20.0F;

        // A：名称 / 卖家
        Text.drawLine(canvas, PageKit.plainName(v.stack), AtomUi.REGULAR, Text.BODY, IOStyle.TEXT,
                colA, top, colB - colA - 4.0F, Text.Align.LEFT);
        String seller = Component.translatable("gui.module_shop.auction_seller", v.seller).getString();
        if (v.own) {
            seller += " " + Component.translatable("gui.module_shop.auction_mine").getString();
        }
        Text.drawLine(canvas, seller, AtomUi.REGULAR, Text.SMALL, v.own ? IOStyle.ORANGE : IOStyle.TEXT_2,
                colA, bottom, colB - colA - 4.0F, Text.Align.LEFT);

        // B：价格
        float bw = colC - colB - 4.0F;
        if (!v.allowBid) {
            Text.drawLine(canvas, AuctionUi.money(v.buyout), AtomUi.BOLD, Text.BODY, IOStyle.GREEN,
                    colB, top, bw, Text.Align.LEFT);
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction.buyout_only").getString(),
                    AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3, colB, bottom, bw, Text.Align.LEFT);
        } else if (v.currentBid > 0L) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction_current_bid",
                    AuctionUi.money(v.currentBid)).getString(), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                    colB, top, bw, Text.Align.LEFT);
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction.next_min",
                    AuctionUi.money(v.minNext)).getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                    colB, bottom, bw, Text.Align.LEFT);
        } else {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction_start",
                    AuctionUi.money(v.startPrice)).getString(), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                    colB, top, bw, Text.Align.LEFT);
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction.no_bid").getString(),
                    AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3, colB, bottom, bw, Text.Align.LEFT);
        }

        // C：一口价 / 竞拍人数
        float cw = colD - colC - 4.0F;
        if (v.buyout > 0L && v.allowBid) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction.buyout_short",
                    AuctionUi.money(v.buyout)).getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.GREEN,
                    colC, top, cw, Text.Align.LEFT);
        } else if (v.allowBid) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction.no_buyout").getString(),
                    AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3, colC, top, cw, Text.Align.LEFT);
        }
        if (v.allowBid) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction.bidders_short", v.bidders,
                    v.bidCount).getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, colC, bottom, cw,
                    Text.Align.LEFT);
        }

        // D：倒计时 / 领先者
        long remain = v.remainingNow();
        Text.drawLine(canvas, AuctionUi.remain(remain), AtomUi.BOLD, Text.SMALL, AuctionUi.remainColor(remain),
                colD, top, right - colD, Text.Align.RIGHT);
        if (v.currentBid > 0L) {
            String leader = Component.translatable(v.topMe ? "gui.module_shop.auction_top_me"
                    : "gui.module_shop.auction_top_bidder", v.topBidder).getString();
            Text.drawLine(canvas, leader, AtomUi.REGULAR, Text.SMALL,
                    v.topMe ? IOStyle.GREEN : v.iBid ? IOStyle.RED : IOStyle.TEXT_2,
                    colD - 20.0F, bottom, right - colD + 20.0F, Text.Align.RIGHT);
        }
    }

    private void openMenu(Canvas canvas, float x, float y, float w, int rows) {
        menuX = x;
        menuY = y;
        menuW = w;
        menuH = rows * MENU_ROW + 4.0F;
        Draw.fill(canvas, menuX, menuY, menuW, menuH, 2.0F, IOStyle.CARD);
        Draw.stroke(canvas, menuX, menuY, menuW, menuH, 2.0F, 1.0F, IOStyle.BORDER);
    }

    private void menuRow(Canvas canvas, PageFrame f, int i, String label, boolean current) {
        float rx = menuX + 2.0F;
        float ry = menuY + 2.0F + i * MENU_ROW;
        float rw = menuW - 4.0F;
        boolean hover = f.mouseX() >= rx && f.mouseX() < rx + rw && f.mouseY() >= ry && f.mouseY() < ry + MENU_ROW;
        if (current || hover) {
            Draw.fill(canvas, rx, ry, rw, MENU_ROW, 1.0F, current ? IOStyle.BLUE_TINT : IOStyle.ROW_HOVER);
        }
        Text.drawLine(canvas, label, AtomUi.REGULAR, Text.SMALL, current ? IOStyle.TEXT : IOStyle.TEXT_2,
                rx + 6.0F, ry + MENU_ROW / 2.0F, rw - 10.0F, Text.Align.LEFT);
    }

    // ------------------------------------------------------------------ 输入

    private Button[] buttons() {
        return new Button[]{catBtn, sortBtn, mineBtn, adminBtn, create, prevBtn, nextBtn};
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (catOpen || sortOpen) {
            boolean inMenu = mx >= menuX && mx < menuX + menuW && my >= menuY && my < menuY + menuH;
            if (inMenu && button == 0) {
                int row = (int) ((my - menuY - 2.0F) / MENU_ROW);
                if (catOpen && row >= 0 && row < AuctionCategory.values().length) {
                    ClickSound.play();
                    AuctionClient.category = AuctionCategory.values()[row];
                    AuctionClient.page = 0;
                } else if (sortOpen && row >= 0 && row < AuctionClient.SORT_COUNT) {
                    ClickSound.play();
                    AuctionClient.sort = row;
                    AuctionClient.page = 0;
                }
                catOpen = false;
                sortOpen = false;
                return true;
            }
            if (!(catBtn.isMouseOver(mx, my) || sortBtn.isMouseOver(mx, my))) {
                catOpen = false;
                sortOpen = false;
                return true;
            }
        }
        if (search.mouseClicked(mx, my, button)) {
            return true;
        }
        for (Button b : buttons()) {
            if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        if (button == 0 && mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int i = (int) ((my - listY) / ROW_H);
            int index = AuctionClient.page * perPage + i;
            if (i >= 0 && i < perPage && index < shown.size() && (my - listY) - i * ROW_H < CARD_H) {
                ClickSound.play();
                search.blur();
                AuctionUi.openDetail(shown.get(index).id);
                return true;
            }
        }
        search.blur();
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = false;
        for (Button b : buttons()) {
            handled |= b.mouseReleased(mx, my, button);
        }
        if (search != null) {
            handled |= search.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return search != null && search.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (catOpen || sortOpen) {
            return true;
        }
        if (my < f.listBottom()) {
            // 滚轮翻页（页码在绘制时按筛选结果夹紧，不会翻出空白页）
            AuctionClient.page = Math.max(0, AuctionClient.page + (scrollY > 0 ? -1 : 1));
            return true;
        }
        return false;
    }

    @Override
    public void clickedOutside() {
        catOpen = false;
        sortOpen = false;
        if (search != null && search.isFocused()) {
            search.blur();
        }
    }

    @Override
    public void cancelInput() {
        catOpen = false;
        sortOpen = false;
        for (Button b : buttons()) {
            b.cancelPress();
        }
        if (search != null) {
            search.blur();
            search.visible(false);
        }
    }
}
