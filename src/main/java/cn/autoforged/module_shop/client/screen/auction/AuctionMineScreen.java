package cn.autoforged.module_shop.client.screen.auction;

import cn.autoforged.module_shop.client.AuctionClient;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.SkiaDialog;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.data.AuctionEntry;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 我的拍卖：正在出售 / 我的竞拍 / 已成交 / 已流拍 / 已撤销 五个分页。
 * 进行中的两页随大厅增量实时更新；历史三页在打开时拉取。历史记录可「重新上架」（只带出参数，物品需重新从背包选择）。
 */
public final class AuctionMineScreen extends SkiaDialog {
    private static final String[] TABS = {"selling", "bidding", "sold", "unsold", "cancelled"};
    private static int tab;
    private final ScrollController scroll = new ScrollController();
    private int lastTab = -1;

    public AuctionMineScreen() {
        super(Component.translatable("gui.module_shop.auction.mine_title"));
    }

    @Override
    protected void initSkia() {
        AuctionClient.send("auction_mine", new CompoundTag());
    }

    private List<AuctionClient.History> history(int t) {
        List<AuctionClient.History> out = new ArrayList<>();
        for (AuctionClient.History h : AuctionClient.mineHistory) {
            boolean sold = AuctionEntry.SOLD.equals(h.status) || AuctionEntry.BUYOUT.equals(h.status);
            boolean unsold = AuctionEntry.UNSOLD.equals(h.status);
            boolean cancelled = !sold && !unsold;
            if ((t == 2 && sold) || (t == 3 && unsold && "seller".equals(h.role))
                    || (t == 4 && cancelled && "seller".equals(h.role))) {
                out.add(h);
            }
        }
        return out;
    }

    @Override
    protected void paint(Canvas c) {
        if (lastTab != tab) {
            lastTab = tab;
            scroll.reset();
        }
        float w = Math.min(420.0F, this.width - 16.0F);
        float h = Math.min(262.0F, this.height - 16.0F);
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        panel(c, x, y, w, h, this.title.getString());
        float ix = x + PAD;
        float iy = y + HEADER_H + 6.0F;
        float iw = w - PAD * 2;
        float bottom = y + h - PAD;
        // 分页
        float tw = (iw - 4 * 3) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int count = switch (i) {
                case 0 -> AuctionClient.mineSelling.size();
                case 1 -> AuctionClient.mineBidding.size();
                default -> history(i).size();
            };
            final int pick = i;
            chip(c, "tab" + i, AuctionUi.tr("gui.module_shop.auction.mine_tab." + TABS[i], count), ix + i * (tw + 3), iy,
                    tw, 16, tab == i, IOStyle.BLUE, () -> tab = pick);
        }
        float ly = iy + 22.0F;
        float lh = bottom - ly - 24.0F;
        float row = 30.0F;
        if (tab <= 1) {
            List<ClientShopData.AuctionView> list = tab == 0 ? AuctionClient.mineSelling : AuctionClient.mineBidding;
            if (list.isEmpty()) {
                center(c, AuctionUi.tr("gui.module_shop.auction.mine_empty"), ix, ly + lh / 2, iw, IOStyle.TEXT_3);
            } else {
                float oy = beginList(c, scroll, ix, ly, iw, lh, list.size() * row);
                for (int i = 0; i < list.size(); i++) {
                    float ry = oy + i * row;
                    if (ry + row < ly || ry > ly + lh) {
                        continue;
                    }
                    activeRow(c, list.get(i), ix, ry, iw - 6, row - 3);
                }
                endList(c);
            }
        } else {
            List<AuctionClient.History> list = history(tab);
            if (list.isEmpty()) {
                center(c, AuctionUi.tr("gui.module_shop.auction.mine_empty"), ix, ly + lh / 2, iw, IOStyle.TEXT_3);
            } else {
                float oy = beginList(c, scroll, ix, ly, iw, lh, list.size() * row);
                for (int i = 0; i < list.size(); i++) {
                    float ry = oy + i * row;
                    if (ry + row < ly || ry > ly + lh) {
                        continue;
                    }
                    historyRow(c, list.get(i), ix, ry, iw - 6, row - 3);
                }
                endList(c);
            }
        }
        button(c, "create", AuctionUi.tr("gui.module_shop.auction_create"), ix, bottom - 18, 80, 18,
                Button.Kind.ACCENT, true, () -> AuctionUi.openCreate(null));
        button(c, "refresh", AuctionUi.tr("gui.module_shop.auction.refresh"), ix + 84, bottom - 18, 60, 18,
                Button.Kind.NORMAL, true, () -> AuctionClient.send("auction_mine", new CompoundTag()));
        button(c, "back", AuctionUi.tr("gui.module_shop.back"), ix + iw - 70, bottom - 18, 70, 18,
                Button.Kind.NORMAL, true, this::onClose);
    }

    private void activeRow(Canvas c, ClientShopData.AuctionView v, float x, float y, float w, float h) {
        float hv = hover("a" + v.id, x, y, w, h);
        Draw.fill(c, x, y, w, h, 3, Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hv));
        Draw.stroke(c, x, y, w, h, 3, 1, IOStyle.BORDER_DIM);
        Draw.fill(c, x + 5, y + 5, 18, 18, 2, IOStyle.SLOT_INNER);
        item(v.stack, x + 6, y + 6);
        if (over(x + 5, y + 5, 18, 18)) {
            items.setHovered(v.stack);
        }
        float tx = x + 28;
        float col = w * 0.42F;
        Text.drawLine(c, PageKit.plainName(v.stack) + " ×" + v.stack.getCount(), AtomUi.REGULAR, Text.BODY,
                IOStyle.TEXT, tx, y + 9, col - 30, Text.Align.LEFT);
        String sub;
        int subColor = IOStyle.TEXT_2;
        if (tab == 0) {
            sub = v.currentBid > 0L ? AuctionUi.tr("gui.module_shop.auction.leader_line", v.topBidder, v.bidCount)
                    : AuctionUi.tr("gui.module_shop.auction.no_bid");
        } else if (v.topMe) {
            sub = AuctionUi.tr("gui.module_shop.auction.state_leading");
            subColor = IOStyle.GREEN;
        } else {
            sub = AuctionUi.tr("gui.module_shop.auction.state_outbid", v.topBidder);
            subColor = IOStyle.RED;
        }
        small(c, sub, tx, y + 20, col - 30, subColor);
        long price = v.currentBid > 0L ? v.currentBid : v.startPrice;
        Text.drawLine(c, AuctionUi.money(price), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE, x + col, y + 9, w * 0.30F,
                Text.Align.LEFT);
        small(c, AuctionUi.tr(v.currentBid > 0L ? "gui.module_shop.auction.label_current"
                : "gui.module_shop.auction.label_start"), x + col, y + 20, w * 0.30F, IOStyle.TEXT_3);
        long remain = v.remainingNow();
        float bx = x + w - 56;
        Text.drawLine(c, AuctionUi.remain(remain), AtomUi.REGULAR, Text.SMALL, AuctionUi.remainColor(remain),
                x + col + w * 0.30F, y + 13, bx - (x + col + w * 0.30F) - 4, Text.Align.RIGHT);
        button(c, "open" + v.id, AuctionUi.tr(tab == 1 && !v.topMe ? "gui.module_shop.auction.rebid"
                        : "gui.module_shop.auction.view"), bx, y + 6, 50, 16,
                tab == 1 && !v.topMe ? Button.Kind.ACCENT : Button.Kind.NORMAL, true, () -> AuctionUi.openDetail(v.id));
    }

    private void historyRow(Canvas c, AuctionClient.History hst, float x, float y, float w, float h) {
        Draw.fill(c, x, y, w, h, 3, IOStyle.CARD);
        Draw.stroke(c, x, y, w, h, 3, 1, IOStyle.BORDER_DIM);
        Draw.fill(c, x + 5, y + 5, 18, 18, 2, IOStyle.SLOT_INNER);
        item(hst.stack, x + 6, y + 6);
        if (over(x + 5, y + 5, 18, 18)) {
            items.setHovered(hst.stack);
        }
        float tx = x + 28;
        float col = w * 0.40F;
        String name = hst.stack.isEmpty() ? "?" : PageKit.plainName(hst.stack) + " ×" + hst.stack.getCount();
        Text.drawLine(c, name, AtomUi.REGULAR, Text.BODY, IOStyle.TEXT, tx, y + 9, col - 30, Text.Align.LEFT);
        String when = cn.autoforged.module_shop.client.screen.ShopScreen.mallTimeText(hst.endedAt);
        boolean sold = AuctionEntry.SOLD.equals(hst.status) || AuctionEntry.BUYOUT.equals(hst.status);
        String sub;
        if (sold) {
            sub = "seller".equals(hst.role) ? AuctionUi.tr("gui.module_shop.auction.sold_to", hst.buyer, when)
                    : AuctionUi.tr("gui.module_shop.auction.bought_from", hst.seller, when);
        } else {
            sub = AuctionUi.status(hst.status) + " · " + when
                    + (hst.operator.isEmpty() ? "" : " · " + hst.operator);
        }
        small(c, sub, tx, y + 20, col - 30, IOStyle.TEXT_2);
        float mx = x + col;
        float mw = w * 0.36F;
        if (sold) {
            Text.drawLine(c, AuctionUi.money(hst.finalPrice), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE, mx, y + 9, mw,
                    Text.Align.LEFT);
            String detail = "seller".equals(hst.role)
                    ? AuctionUi.tr("gui.module_shop.auction.fee_net", AuctionUi.money(hst.fee), AuctionUi.money(hst.net))
                    : AuctionUi.status(hst.status);
            small(c, detail, mx, y + 20, mw, "seller".equals(hst.role) ? IOStyle.GREEN : IOStyle.TEXT_3);
        } else {
            Text.drawLine(c, AuctionUi.status(hst.status), AtomUi.REGULAR, Text.BODY, AuctionUi.statusColor(hst.status),
                    mx, y + 9, mw, Text.Align.LEFT);
            if (hst.listingFee > 0L) {
                small(c, AuctionUi.tr("gui.module_shop.auction.listing_fee_paid", AuctionUi.money(hst.listingFee)),
                        mx, y + 20, mw, IOStyle.TEXT_3);
            }
        }
        if ("seller".equals(hst.role) && !hst.stack.isEmpty()) {
            button(c, "relist" + hst.id, AuctionUi.tr("gui.module_shop.auction.relist"), x + w - 56, y + 6, 50, 16,
                    Button.Kind.NORMAL, true, () -> AuctionUi.openCreate(hst));
        }
    }

    @Override
    public void onClose() {
        Nav.back();
    }
}
