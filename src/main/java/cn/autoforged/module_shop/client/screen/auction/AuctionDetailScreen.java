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
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;

/**
 * 拍卖详情：物品完整属性、价格信息、出价 / 一口价 / 撤销 / 管理员强制操作、竞价记录。
 *
 * <p>打开时向服务端订阅这场拍卖（auction_detail），之后有人出价、拍卖结束都会推送最新详情；
 * 关闭时退订。出价时附带「我看到的当前价」，价格已变化时服务端拒绝并推送最新数据，玩家不会按过时价格误出价。
 * 一口价需要连续点两次确认。
 */
public final class AuctionDetailScreen extends SkiaDialog {
    private final long id;
    private SkiaTextField amount;
    private final ScrollController attrScroll = new ScrollController();
    private final ScrollController bidScroll = new ScrollController();
    private int seenSeq = -1;
    private int seenResult;
    private boolean pending;
    private long pendingAt;
    private long buyoutArmedAt;
    private String armed = "";
    private List<String> attrLines = List.of();

    public AuctionDetailScreen(long id) {
        super(Component.translatable("gui.module_shop.auction.detail_title", id));
        this.id = id;
        this.seenResult = AuctionClient.resultSeq;
    }

    @Override
    protected void initSkia() {
        String keep = amount == null ? "" : amount.value();
        amount = field(18, AuctionUi.tr("gui.module_shop.auction.bid_hint"));
        amount.setValue(keep);
        CompoundTag tag = new CompoundTag();
        tag.putLong("id", id);
        AuctionClient.send("auction_detail", tag);
    }

    private AuctionClient.Detail detail() {
        AuctionClient.Detail d = AuctionClient.detail;
        return d != null && d.id == id ? d : null;
    }

    private void onNewDetail(AuctionClient.Detail d) {
        if (d.view == null) {
            return;
        }
        // 价格变了：把输入框重置成新的最低出价（玩家正在输入时不打断，除非比最低价还低）
        long typed = AuctionUi.parse(amount.value());
        if (!amount.isFocused() || typed < d.view.minNext) {
            amount.setValue(Long.toString(d.view.minNext));
        }
        List<String> lines = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (!d.view.stack.isEmpty() && mc.level != null) {
            TooltipFlag flag = mc.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL;
            List<Component> tip = d.view.stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, flag);
            for (int i = 1; i < tip.size(); i++) {
                String s = net.minecraft.ChatFormatting.stripFormatting(tip.get(i).getString());
                if (s != null && !s.isBlank()) {
                    lines.add(s);
                }
            }
        }
        attrLines = lines;
    }

    private void submit(String action, CompoundTag tag) {
        if (pending && System.currentTimeMillis() - pendingAt < 5000L) {
            return;
        }
        pending = true;
        pendingAt = System.currentTimeMillis();
        tag.putLong("id", id);
        AuctionClient.sendOnce(action, tag);
    }

    private void bid(AuctionClient.Detail d) {
        long value = AuctionUi.parse(amount.value());
        if (value <= 0L) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("amount", Long.toString(value));
        tag.putLong("expect", d.view.currentBid);
        submit("bid", tag);
    }

    private void adjust(AuctionClient.Detail d, int steps) {
        long value = AuctionUi.parse(amount.value());
        if (value <= 0L) {
            value = d.view.minNext;
        }
        long inc = Math.max(1L, d.view.increment);
        long next = value + inc * steps;
        if (steps > 0 && next < value) {
            next = Long.MAX_VALUE;
        }
        amount.setValue(Long.toString(Math.max(d.view.minNext, next)));
    }

    /** 危险 / 不可撤回的操作：第一次点击只「上膛」，3 秒内再点一次才执行。 */
    private boolean confirm(String key) {
        long now = System.currentTimeMillis();
        if (key.equals(armed) && now - buyoutArmedAt < 3000L) {
            armed = "";
            return true;
        }
        armed = key;
        buyoutArmedAt = now;
        return false;
    }

    private String armedLabel(String key, String normal) {
        return key.equals(armed) && System.currentTimeMillis() - buyoutArmedAt < 3000L
                ? AuctionUi.tr("gui.module_shop.auction.confirm_again") : normal;
    }

    @Override
    protected void paint(Canvas c) {
        AuctionClient.Detail d = detail();
        if (d != null && AuctionClient.detailSeq != seenSeq) {
            seenSeq = AuctionClient.detailSeq;
            onNewDetail(d);
        }
        if (AuctionClient.resultSeq != seenResult) {
            seenResult = AuctionClient.resultSeq;
            pending = false;
        }
        float w = Math.min(420.0F, this.width - 16.0F);
        float h = Math.min(272.0F, this.height - 16.0F);
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        panel(c, x, y, w, h, this.title.getString());
        float innerX = x + PAD;
        float innerY = y + HEADER_H + 8.0F;
        float innerW = w - PAD * 2;
        float bottom = y + h - PAD;
        if (d == null || d.missing || d.view == null) {
            amount.visible(false);
            center(c, AuctionUi.tr(d == null ? "gui.module_shop.loading" : "gui.module_shop.auction_gone"),
                    innerX, innerY + 40.0F, innerW, IOStyle.TEXT_2);
            button(c, "back", AuctionUi.tr("gui.module_shop.back"), x + w / 2 - 45, bottom - 18, 90, 18,
                    Button.Kind.NORMAL, true, this::onClose);
            return;
        }
        ClientShopData.AuctionView v = d.view;
        boolean active = "active".equals(d.status);
        long remain = active ? Math.max(0L, v.remaining - (System.currentTimeMillis() - d.receivedAt)) : 0L;

        // ---------------- 左栏：物品 ----------------
        // 弹窗最少 260px 宽时右栏仍要留出 150px，否则价格 / 按钮会互相压住或越出面板
        float leftW = Math.max(96.0F, Math.min(innerW * 0.40F, innerW - 150.0F));
        card(c, innerX, innerY, leftW, 44.0F);
        Draw.fill(c, innerX + 6, innerY + 6, 32, 32, 2, IOStyle.SLOT_INNER);
        Draw.stroke(c, innerX + 6, innerY + 6, 32, 32, 2, 1, IOStyle.SLOT_BORDER);
        items.add(v.stack, innerX + 8, innerY + 8, 28.0F, true);
        if (over(innerX + 6, innerY + 6, 32, 32)) {
            items.setHovered(v.stack);
        }
        float tx = innerX + 44.0F;
        float tw = leftW - 48.0F;
        Text.drawLine(c, PageKit.plainName(v.stack), AtomUi.BOLD, Text.BODY, IOStyle.TEXT, tx, innerY + 11, tw,
                Text.Align.LEFT);
        small(c, AuctionUi.tr("gui.module_shop.auction.count_cat", v.stack.getCount(),
                AuctionUi.tr(AuctionClient.categoryOf(v).langKey())), tx, innerY + 22, tw, IOStyle.TEXT_2);
        small(c, AuctionUi.tr("gui.module_shop.auction_seller", v.seller)
                + (v.own ? " " + AuctionUi.tr("gui.module_shop.auction_mine") : ""), tx, innerY + 33, tw,
                v.own ? IOStyle.ORANGE : IOStyle.TEXT_2);
        // 属性（完整 tooltip，悬停图标也能看原版样式）
        float attrY = innerY + 50.0F;
        float attrH = bottom - attrY;
        card(c, innerX, attrY, leftW, attrH);
        small(c, AuctionUi.tr("gui.module_shop.auction.attributes"), innerX + 6, attrY + 8, leftW - 12, IOStyle.TEXT_3);
        float listTop = attrY + 15.0F;
        float listH = attrH - 18.0F;
        float rowH = 10.0F;
        float oy = beginList(c, attrScroll, innerX + 2, listTop, leftW - 4, listH, Math.max(1, attrLines.size()) * rowH);
        if (attrLines.isEmpty()) {
            small(c, AuctionUi.tr("gui.module_shop.auction.no_attributes"), innerX + 6, oy + 5, leftW - 12, IOStyle.TEXT_3);
        }
        for (int i = 0; i < attrLines.size(); i++) {
            float ly = oy + i * rowH + 5.0F;
            if (ly < listTop - rowH || ly > listTop + listH + rowH) {
                continue;
            }
            small(c, attrLines.get(i), innerX + 6, ly, leftW - 16, IOStyle.TEXT_2);
        }
        endList(c);

        // ---------------- 右栏：价格与操作 ----------------
        float rx = innerX + leftW + 8.0F;
        float rw = innerW - leftW - 8.0F;
        card(c, rx, innerY, rw, 92.0F);
        float lx = rx + 6.0F;
        float colW = (rw - 12.0F) / 2.0F;
        // 当前价（最醒目）
        boolean hasBid = v.currentBid > 0L;
        small(c, AuctionUi.tr(hasBid ? "gui.module_shop.auction.label_current" : "gui.module_shop.auction.label_start"),
                lx, innerY + 8, colW, IOStyle.TEXT_3);
        Text.drawLine(c, AuctionUi.money(hasBid ? v.currentBid : v.startPrice), AtomUi.BOLD, Text.TITLE,
                IOStyle.ORANGE, lx, innerY + 19, colW, Text.Align.LEFT);
        small(c, AuctionUi.tr("gui.module_shop.auction.label_remain"), lx + colW, innerY + 8, colW, IOStyle.TEXT_3);
        String remainText = active ? AuctionUi.remain(remain) : AuctionUi.status(d.status);
        Text.drawLine(c, remainText, AtomUi.BOLD, Text.TITLE, active ? AuctionUi.remainColor(remain)
                : AuctionUi.statusColor(d.status), lx + colW, innerY + 19, colW, Text.Align.LEFT);
        float gy = innerY + 34.0F;
        info(c, lx, gy, colW, "gui.module_shop.auction.label_start", AuctionUi.money(v.startPrice), IOStyle.TEXT);
        info(c, lx + colW, gy, colW, "gui.module_shop.auction.label_leader",
                hasBid ? (v.topMe ? AuctionUi.tr("gui.module_shop.auction.you") : v.topBidder)
                        : AuctionUi.tr("gui.module_shop.auction.no_bid"), v.topMe ? IOStyle.GREEN : IOStyle.TEXT);
        info(c, lx, gy + 14, colW, "gui.module_shop.auction.label_min_next",
                v.allowBid ? AuctionUi.money(v.minNext) : "-", IOStyle.TEXT);
        info(c, lx + colW, gy + 14, colW, "gui.module_shop.auction.label_buyout",
                v.buyout > 0L ? AuctionUi.money(v.buyout) : AuctionUi.tr("gui.module_shop.auction.none"),
                v.buyout > 0L ? IOStyle.GREEN : IOStyle.TEXT_3);
        info(c, lx, gy + 28, colW, "gui.module_shop.auction.label_bidders",
                AuctionUi.tr("gui.module_shop.auction.bidders_value", v.bidders, v.bidCount), IOStyle.TEXT);
        info(c, lx + colW, gy + 28, colW, "gui.module_shop.auction.label_increment",
                AuctionUi.money(v.increment), IOStyle.TEXT);
        if (!active && d.finalPrice > 0L) {
            info(c, lx, gy + 42, rw - 12, "gui.module_shop.auction.label_final",
                    AuctionUi.money(d.finalPrice) + " · " + d.buyerName, IOStyle.GREEN);
        } else if (v.extensions > 0) {
            small(c, AuctionUi.tr("gui.module_shop.auction.extended_times", v.extensions), lx, gy + 46, rw - 12,
                    IOStyle.TEXT_3);
        }

        // 操作区
        float ay = innerY + 98.0F;
        boolean canAct = active && remain > 0L && !v.own;
        boolean enabled = AuctionClient.settings.enabled;
        float gap = Math.min(4.0F, Math.max(0.0F, rw * 0.02F));
        if (canAct && v.allowBid) {
            String minusLabel = "-" + v.increment;
            String plusLabel = "+" + v.increment;
            String bidLabel = AuctionUi.tr(pending ? "gui.module_shop.auction.submitting" : "gui.module_shop.auction.bid_btn");
            float stepNeed = Math.max(42.0F, Math.max(Text.width(minusLabel, AtomUi.REGULAR, Text.BODY),
                    Text.width(plusLabel, AtomUi.REGULAR, Text.BODY)) + 10.0F);
            float bidNeed = Math.max(36.0F, Text.width(bidLabel, AtomUi.REGULAR, Text.BODY) + 10.0F);
            float available = Math.max(0.0F, rw - gap * 3.0F);
            // 顺序为输入框、减、加、出价；空间不足时一起收缩，富余空间只补给输入框
            float[] widths = PageKit.distribute(available, new float[]{56.0F, stepNeed, stepNeed, bidNeed},
                    new float[]{28.0F, 24.0F, 24.0F, Math.min(36.0F, bidNeed)});
            float used = widths[0] + widths[1] + widths[2] + widths[3];
            float fieldW = widths[0] + Math.max(0.0F, available - used);
            float minusW = widths[1];
            float plusW = widths[2];
            float bidW = widths[3];
            button(c, "minus", minusLabel, rx, ay, minusW, 18, Button.Kind.NORMAL, true, () -> adjust(d, -1));
            amount.bounds(rx + minusW + gap, ay, fieldW, 18).visible(true);
            float plusX = rx + minusW + gap + fieldW + gap;
            button(c, "plus", plusLabel, plusX, ay, plusW, 18, Button.Kind.NORMAL, true, () -> adjust(d, 1));
            long typed = AuctionUi.parse(amount.value());
            boolean okAmount = typed >= v.minNext && typed <= d.balance && !v.topMe
                    && (v.buyout <= 0L || typed < v.buyout);
            button(c, "bid", bidLabel, plusX + plusW + gap, ay, bidW, 18, Button.Kind.ACCENT,
                    okAmount && !pending && enabled, () -> bid(d));
            String hint;
            int hintColor = IOStyle.TEXT_3;
            if (v.topMe) {
                hint = AuctionUi.tr("gui.module_shop.auction.hint_leading");
                hintColor = IOStyle.GREEN;
            } else if (typed > d.balance) {
                hint = AuctionUi.tr("gui.module_shop.auction.hint_no_money", AuctionUi.money(d.balance));
                hintColor = IOStyle.RED;
            } else if (typed > 0L && typed < v.minNext) {
                hint = AuctionUi.tr("gui.module_shop.bid_min", AuctionUi.money(v.minNext));
                hintColor = IOStyle.RED;
            } else if (v.buyout > 0L && typed >= v.buyout) {
                hint = AuctionUi.tr("gui.module_shop.auction.hint_use_buyout");
                hintColor = IOStyle.ORANGE;
            } else {
                hint = AuctionUi.tr("gui.module_shop.auction.hint_rule", AuctionUi.money(d.balance));
            }
            small(c, hint, rx + 2, ay + 26, rw - 4, hintColor);
            ay += 34.0F;
        } else {
            amount.visible(false);
            if (active && v.own) {
                small(c, AuctionUi.tr(v.currentBid > 0L ? "gui.module_shop.auction.hint_own_has_bid"
                        : "gui.module_shop.auction.hint_own"), rx + 2, ay + 6, rw - 4, IOStyle.TEXT_2);
                ay += 16.0F;
            } else if (active && !v.allowBid) {
                small(c, AuctionUi.tr("gui.module_shop.auction.hint_buyout_only"), rx + 2, ay + 6, rw - 4, IOStyle.TEXT_2);
                ay += 16.0F;
            }
        }
        float btnW = (rw - gap) / 2.0F;
        if (canAct && v.buyout > 0L) {
            boolean afford = d.balance >= v.buyout - (v.topMe ? v.currentBid : 0L);
            button(c, "buyout", armedLabel("buyout", AuctionUi.tr("gui.module_shop.auction.buyout_btn",
                            AuctionUi.money(v.buyout))), rx, ay, rw, 18, Button.Kind.ACCENT,
                    afford && !pending && enabled, () -> {
                        if (confirm("buyout")) {
                            CompoundTag tag = new CompoundTag();
                            tag.putLong("expect", v.buyout);
                            submit("auction_buyout", tag);
                        }
                    });
            ay += 22.0F;
        }
        if (d.canCancel) {
            button(c, "cancel", armedLabel("cancel", AuctionUi.tr("gui.module_shop.auction.cancel_btn")), rx, ay, rw, 18,
                    Button.Kind.DANGER, !pending, () -> {
                        if (confirm("cancel")) {
                            submit("auction_cancel", new CompoundTag());
                        }
                    });
            ay += 22.0F;
        }
        if (d.admin && active) {
            button(c, "aend", armedLabel("aend", AuctionUi.tr("gui.module_shop.auction.admin_end")), rx, ay, btnW, 18,
                    Button.Kind.NORMAL, true, () -> {
                        if (confirm("aend")) {
                            submit("admin_auction_end", new CompoundTag());
                        }
                    });
            button(c, "acancel", armedLabel("acancel", AuctionUi.tr("gui.module_shop.auction.admin_cancel")),
                    rx + btnW + gap, ay, btnW, 18, Button.Kind.DANGER, true, () -> {
                        if (confirm("acancel")) {
                            submit("admin_auction_cancel", new CompoundTag());
                        }
                    });
            ay += 22.0F;
        }

        // 竞价记录
        float histY = ay + 2.0F;
        float histH = bottom - histY - 22.0F;
        if (histH > 24.0F) {
            card(c, rx, histY, rw, histH);
            small(c, AuctionUi.tr("gui.module_shop.auction.bid_history", d.bids.size()), rx + 6, histY + 8, rw - 12,
                    IOStyle.TEXT_3);
            float hTop = histY + 15.0F;
            float hH = histH - 17.0F;
            float row = 11.0F;
            float hy = beginList(c, bidScroll, rx + 2, hTop, rw - 4, hH, Math.max(1, d.bids.size()) * row);
            if (d.bids.isEmpty()) {
                small(c, AuctionUi.tr("gui.module_shop.auction.no_bid_yet"), rx + 6, hy + 5, rw - 12, IOStyle.TEXT_3);
            }
            long since = System.currentTimeMillis() - d.receivedAt;
            for (int i = 0; i < d.bids.size(); i++) {
                AuctionClient.Bid b = d.bids.get(i);
                float ly = hy + i * row + 5.5F;
                if (ly < hTop - row || ly > hTop + hH + row) {
                    continue;
                }
                int color = i == 0 ? IOStyle.ORANGE : IOStyle.TEXT_2;
                small(c, (b.me ? AuctionUi.tr("gui.module_shop.auction.you") : b.name), rx + 6, ly, rw * 0.38F,
                        b.me ? IOStyle.GREEN : color);
                Text.drawLine(c, AuctionUi.money(b.amount), i == 0 ? AtomUi.BOLD : AtomUi.REGULAR, Text.SMALL, color,
                        rx + rw * 0.38F, ly, rw * 0.34F, Text.Align.RIGHT);
                Text.drawLine(c, AuctionUi.ago(b.ago + since), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                        rx + rw * 0.72F, ly, rw * 0.28F - 8.0F, Text.Align.RIGHT);
            }
            endList(c);
        }
        button(c, "back", AuctionUi.tr("gui.module_shop.back"), rx + rw - 70.0F, bottom - 18, 70, 18,
                Button.Kind.NORMAL, true, this::onClose);
        if (v.own || v.iBid) {
            button(c, "mine", AuctionUi.tr("gui.module_shop.auction.mine_btn"), rx, bottom - 18, 70, 18,
                    Button.Kind.NORMAL, true, AuctionUi::openMine);
        }
    }

    private static void info(Canvas c, float x, float y, float w, String labelKey, String value, int color) {
        String label = AuctionUi.tr(labelKey);
        float lw = Math.min(w * 0.5F, Text.width(label, AtomUi.REGULAR, Text.SMALL) + 4.0F);
        Text.drawLine(c, label, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3, x, y + 5, lw, Text.Align.LEFT);
        Text.drawLine(c, value, AtomUi.REGULAR, Text.SMALL, color, x + lw, y + 5, w - lw - 4.0F, Text.Align.LEFT);
    }

    @Override
    protected boolean onEnter() {
        AuctionClient.Detail d = detail();
        if (d != null && d.view != null && "active".equals(d.status) && d.view.allowBid && !d.view.own) {
            bid(d);
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        AuctionClient.send("auction_detail_close", new CompoundTag());
        Nav.back();
    }
}
