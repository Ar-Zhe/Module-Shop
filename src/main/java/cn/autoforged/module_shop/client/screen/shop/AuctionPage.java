package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 拍卖会页（Skija 版）。
 *
 * <p>每行三栏：左 = 物品 / 名称 / 卖家（自己的标注「我的」）；中 = 当前最高价与上一轮出价（划线），
 * 无人出价时显示起拍价与倒计时；右 = 实时倒计时与最高出价者（自己是最高价时金色）。
 * 点别人的拍卖打开出价界面（bid_gui），自己的拍卖不可点。底部「上架拍卖」。平滑滚动替代翻页。
 */
public final class AuctionPage implements ShopPage {
    private static final float ROW_H = 31.0F;
    private static final float CARD_H = 28.0F;
    private static final int LAST_BID_COLOR = 0xFF8A8A9A;

    private static float savedScroll;

    private final ScrollController scroll = new ScrollController();
    private boolean restored;
    private float[] hoverT = new float[0];

    private final Button create = new Button(Button.Kind.ACCENT,
            () -> Component.translatable("gui.module_shop.auction_create").getString(), null).radius(3.0F);

    private float listX, listY, listW, listH;

    @Override
    public String id() {
        return "auction";
    }

    @Override
    public void render(Canvas canvas, PageFrame f) {
        List<ClientShopData.AuctionView> list = ClientShopData.auctions;
        listX = f.x();
        listY = f.y() + 4.0F;
        listW = f.w();
        listH = f.listBottom() - listY;

        if (list.isEmpty()) {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.auction_empty").getString(),
                    f.x(), f.y() + 54.0F, f.w());
        } else {
            if (hoverT.length != list.size()) {
                hoverT = new float[list.size()];
            }
            scroll.setContent(list.size() * ROW_H - (ROW_H - CARD_H), listH);
            if (!restored) {
                scroll.scrollTo(savedScroll, false);
                restored = true;
            }
            scroll.update();
            savedScroll = scroll.offset();
            float rowW = listW - 2.0F - (scroll.canScroll() ? 6.0F : 0.0F);
            boolean inList = f.mouseX() >= listX && f.mouseX() < listX + rowW
                    && f.mouseY() >= listY && f.mouseY() < listY + listH;
            int saved = canvas.save();
            f.host().items().pushClip(listX, listY, listW, listH);
            try {
                Draw.clip(canvas, listX - 1.0F, listY, listW + 2.0F, listH);
                float off = scroll.offset();
                for (int i = Math.max(0, (int) (off / ROW_H)); i < list.size(); i++) {
                    float y = listY + i * ROW_H - off;
                    if (y > listY + listH) {
                        break;
                    }
                    ClientShopData.AuctionView v = list.get(i);
                    boolean hover = !v.own && inList && !scroll.isDragging()
                            && f.mouseY() >= y && f.mouseY() < y + CARD_H;
                    hoverT[i] = Motion.approach(hoverT[i], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
                    drawRow(canvas, f, v, listX + 2.0F, y, rowW, hoverT[i]);
                }
            } finally {
                f.host().items().popClip();
                canvas.restoreToCount(saved);
            }
            scroll.drawBar(canvas, listX, listY, listW, listH, f.mouseX(), f.mouseY(), f.dtMs());
            if (scroll.hasMoreBelow()) {
                PageKit.fadeBottom(canvas, listX, listY + listH, rowW + 2.0F, IOStyle.CONTENT);
            }
        }

        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        create.onClick(() -> f.host().action("create_auction", new CompoundTag()))
                .bounds(f.x() + f.w() - 4.0F - 76.0F, barMid - 8.0F, 76.0F, 16.0F);
        create.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
    }

    private void drawRow(Canvas canvas, PageFrame f, ClientShopData.AuctionView v, float x, float y, float w,
                         float hover) {
        PageKit.hoverCard(canvas, x, y, w, CARD_H, hover);
        if (v.own) {
            // 自己的拍卖：左侧金色细条标记（不可出价）
            Draw.fill(canvas, x + 2.0F, y + 5.0F, 2.0F, CARD_H - 10.0F, 1.0F, IOStyle.ORANGE);
        }
        PageKit.slot(canvas, f.host(), v.stack, x + 6.0F, y + 5.0F);

        float colA = x + 30.0F;                // 左栏起点
        float colB = x + w * 0.46F;            // 中栏起点
        float colC = x + w * 0.74F;            // 右栏起点
        float right = x + w - 6.0F;
        float top = y + 9.0F;
        float bottom = y + 20.0F;

        // 左栏：名称 / 卖家
        Text.drawLine(canvas, PageKit.plainName(v.stack), AtomUi.REGULAR, Text.BODY, IOStyle.TEXT,
                colA, top, colB - colA - 4.0F, Text.Align.LEFT);
        String seller = Component.translatable("gui.module_shop.auction_seller", v.seller).getString();
        if (v.own) {
            seller += " " + Component.translatable("gui.module_shop.auction_mine").getString();
        }
        Text.drawLine(canvas, seller, AtomUi.REGULAR, Text.SMALL, v.own ? IOStyle.ORANGE : IOStyle.TEXT_2,
                colA, bottom, colB - colA - 4.0F, Text.Align.LEFT);

        // 倒计时：快照剩余时间减去快照到达后的本地流逝时间（实时递减）
        long remainMs = v.receivedAt > 0L
                ? Math.max(0L, v.remaining - (System.currentTimeMillis() - v.receivedAt)) : v.remaining;
        String time = PageKit.duration(remainMs);
        float midW = colC - colB - 4.0F;

        if (v.currentBid > 0L) {
            // 中栏：当前最高价 + 上一轮出价（划线）
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction_current_bid",
                            ClientShopData.money(v.currentBid)).getString(),
                    AtomUi.BOLD, Text.BODY, IOStyle.ORANGE, colB, top, midW, Text.Align.CENTER);
            if (v.lastBid > 0L) {
                String last = Component.translatable("gui.module_shop.auction_last_bid",
                        ClientShopData.money(v.lastBid)).getString();
                float lw = Text.drawLine(canvas, last, AtomUi.REGULAR, Text.SMALL, LAST_BID_COLOR,
                        colB, bottom, midW, Text.Align.CENTER);
                float lx = colB + (midW - lw) / 2.0F;
                Draw.fill(canvas, lx, bottom - 0.25F, lw, 0.75F, 0.0F, LAST_BID_COLOR);
            }
            // 右栏：倒计时 + 最高出价者
            Text.drawLine(canvas, time, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                    colC, top, right - colC, Text.Align.RIGHT);
            if (v.topBidder != null && !v.topBidder.isEmpty()) {
                String topText = Component.translatable(v.topMe ? "gui.module_shop.auction_top_me"
                        : "gui.module_shop.auction_top_bidder", v.topBidder).getString();
                Text.drawLine(canvas, topText, AtomUi.REGULAR, Text.SMALL, v.topMe ? IOStyle.ORANGE : IOStyle.TEXT_2,
                        colC, bottom, right - colC, Text.Align.RIGHT);
            }
        } else {
            // 无人出价：起拍价 + 倒计时
            Text.drawLine(canvas, Component.translatable("gui.module_shop.auction_start",
                            ClientShopData.money(v.startPrice)).getString(),
                    AtomUi.BOLD, Text.BODY, IOStyle.ORANGE, colB, top, midW, Text.Align.CENTER);
            Text.drawLine(canvas, time, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                    colB, bottom, midW, Text.Align.CENTER);
        }
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button == 0 && scroll.pressBar(mx, my, listX, listY, listW, listH)) {
            return true;
        }
        if (create.mouseClicked(mx, my, button)) {
            return true;
        }
        List<ClientShopData.AuctionView> list = ClientShopData.auctions;
        if (button == 0 && mx >= listX && mx < listX + listW - 6.0F && my >= listY && my < listY + listH) {
            float local = (float) (my - listY + scroll.offset());
            int index = (int) (local / ROW_H);
            if (index >= 0 && index < list.size() && local - index * ROW_H < CARD_H && !list.get(index).own) {
                ClickSound.play();
                CompoundTag tag = new CompoundTag();
                tag.putLong("id", list.get(index).id);
                f.host().action("bid_gui", tag);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return scroll.releaseBar() | create.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return scroll.dragBar(my, listH);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (my < f.listBottom()) {
            scroll.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void cancelInput() {
        scroll.releaseBar();
        create.cancelPress();
    }
}
