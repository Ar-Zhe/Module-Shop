package cn.autoforged.module_shop.client.screen.auction;

import cn.autoforged.module_shop.client.AuctionClient;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.SkiaDialog;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import io.github.humbleui.skija.Canvas;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 拍卖上架界面：直接从背包选物品，一次填完数量 / 起拍价 / 加价幅度 / 一口价 / 时长 / 是否允许竞价。
 *
 * <p>提交时只发「槽位 + 物品 id + 数量 + 参数」，服务端重新核对槽位里的物品、全部规则与手续费后
 * 在同一次调用里扣费、取物品、建拍卖；物品不会在内存里「悬空」，崩服也不会丢。
 * 这里的校验和手续费预览只是提示，以服务端为准。
 *
 * <p>「重新上架」传入历史记录作为模板：只带出价格与时长，物品仍须从背包里重新选（不能凭记录复制物品）。
 */
public final class AuctionCreateScreen extends SkiaDialog {
    private static final float SLOT = 18.0F;

    @Nullable
    private final AuctionClient.History template;
    private int slot = -1;
    private boolean allowBid = true;
    private int duration;
    private SkiaTextField count;
    private SkiaTextField start;
    private SkiaTextField increment;
    private SkiaTextField buyout;
    private boolean pending;
    private int seenResult;
    private boolean templateApplied;

    public AuctionCreateScreen(@Nullable AuctionClient.History template) {
        super(Component.translatable(template == null ? "gui.module_shop.auction.create_title"
                : "gui.module_shop.auction.relist_title"));
        this.template = template;
        this.seenResult = AuctionClient.resultSeq;
    }

    @Override
    protected void initSkia() {
        String c = count == null ? "" : count.value();
        String s = start == null ? "" : start.value();
        String i = increment == null ? "" : increment.value();
        String b = buyout == null ? "" : buyout.value();
        count = field(3, "1");
        start = field(18, AuctionUi.tr("gui.module_shop.auction.start_hint"));
        increment = field(18, Long.toString(AuctionClient.settings.minIncrement));
        buyout = field(18, AuctionUi.tr("gui.module_shop.auction.buyout_hint"));
        count.setValue(c);
        start.setValue(s);
        increment.setValue(i);
        buyout.setValue(b);
        if (duration == 0) {
            duration = AuctionClient.settings.defaultDuration;
        }
        // 设置（时长选项、手续费）随列表下发；没拉过时顺手拉一次
        AuctionClient.requestList();
        applyTemplate();
    }

    private void applyTemplate() {
        if (template == null || templateApplied) {
            return;
        }
        templateApplied = true;
        allowBid = template.allowBid;
        start.setValue(template.allowBid ? Long.toString(template.startPrice) : "");
        increment.setValue(template.increment > 0L ? Long.toString(template.increment) : "");
        buyout.setValue(template.buyout > 0L ? Long.toString(template.buyout) : "");
        duration = template.duration;
        // 在背包里找同样的物品帮玩家预选（只是预选，提交时服务端仍按槽位重新核对）
        Inventory inv = inventory();
        if (inv != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && ItemStack.isSameItemSameComponents(st, template.stack)) {
                    select(i, Math.min(st.getCount(), template.stack.getCount()));
                    break;
                }
            }
        }
    }

    @Nullable
    private static Inventory inventory() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getInventory();
    }

    private ItemStack selected() {
        Inventory inv = inventory();
        if (inv == null || slot < 0) {
            return ItemStack.EMPTY;
        }
        return inv.getItem(slot);
    }

    private int maxCount(ItemStack st) {
        return Math.max(1, Math.min(Math.min(AuctionClient.settings.maxCount, st.getMaxStackSize()), st.getCount()));
    }

    private void select(int index, int amount) {
        slot = index;
        ItemStack st = selected();
        if (!st.isEmpty()) {
            count.setValue(Integer.toString(Math.max(1, Math.min(amount, maxCount(st)))));
        }
    }

    /** 当前输入的问题（null = 可以提交）。 */
    @Nullable
    private String problem() {
        AuctionClient.Settings s = AuctionClient.settings;
        if (!s.enabled) {
            return AuctionUi.tr("msg.module_shop.auction.err_disabled");
        }
        Minecraft mc = Minecraft.getInstance();
        if (s.blockCreative && mc.player != null && mc.player.isCreative()) {
            return AuctionUi.tr("msg.module_shop.auction.err_creative");
        }
        if (s.myActive >= s.maxPerPlayer) {
            return AuctionUi.tr("msg.module_shop.auction_limit");
        }
        ItemStack st = selected();
        if (st.isEmpty()) {
            return AuctionUi.tr("gui.module_shop.auction.pick_item");
        }
        long n = AuctionUi.parse(count.value());
        if (n <= 0L || n > maxCount(st)) {
            return AuctionUi.tr("msg.module_shop.auction.err_count", maxCount(st));
        }
        long b = AuctionUi.parse(buyout.value());
        long inc = AuctionUi.parse(increment.value());
        if (b < 0L || inc < 0L) {
            return AuctionUi.tr("msg.module_shop.auction.err_amount");
        }
        if (b > 0L && !s.allowBuyout) {
            return AuctionUi.tr("msg.module_shop.auction.err_buyout_disabled");
        }
        long price;
        if (allowBid) {
            price = AuctionUi.parse(start.value());
            if (price <= 0L) {
                return AuctionUi.tr("gui.module_shop.auction.need_start");
            }
            if (inc > 0L && inc < s.minIncrement) {
                return AuctionUi.tr("msg.module_shop.auction.err_increment", s.minIncrement);
            }
            if (b > 0L && b <= price) {
                return AuctionUi.tr("msg.module_shop.auction.err_buyout_low");
            }
        } else {
            if (b <= 0L) {
                return AuctionUi.tr("msg.module_shop.auction.err_need_buyout");
            }
            price = b;
        }
        if (price < s.minStart || (s.maxStart > 0L && price > s.maxStart)) {
            return AuctionUi.tr("msg.module_shop.auction.err_start_range", s.minStart,
                    s.maxStart > 0L ? Long.toString(s.maxStart) : "∞");
        }
        long fee = s.listingFee(price);
        if (fee > cn.autoforged.module_shop.client.ClientShopData.balance) {
            return AuctionUi.tr("msg.module_shop.auction.err_fee", fee);
        }
        if (!s.durations.contains(duration)) {
            return AuctionUi.tr("msg.module_shop.auction.err_duration");
        }
        return null;
    }

    private void submit() {
        if (pending || problem() != null) {
            return;
        }
        ItemStack st = selected();
        CompoundTag tag = new CompoundTag();
        tag.putInt("slot", slot);
        tag.putString("item", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        tag.putInt("count", (int) AuctionUi.parse(count.value()));
        tag.putBoolean("allowBid", allowBid);
        tag.putString("start", allowBid ? start.value().trim() : "");
        tag.putString("increment", increment.value().trim());
        tag.putString("buyout", buyout.value().trim());
        tag.putInt("duration", duration);
        pending = true;
        AuctionClient.sendOnce("auction_create", tag);
    }

    @Override
    protected void paint(Canvas c) {
        if (AuctionClient.resultSeq != seenResult) {
            seenResult = AuctionClient.resultSeq;
            pending = false;
            if (AuctionClient.lastOk && "create".equals(AuctionClient.lastOp)) {
                onClose();
                return;
            }
        }
        AuctionClient.Settings s = AuctionClient.settings;
        if (duration == 0 || !s.durations.contains(duration)) {
            duration = s.durations.contains(s.defaultDuration) ? s.defaultDuration : s.durations.get(0);
        }
        float w = Math.min(400.0F, this.width - 16.0F);
        float h = Math.min(262.0F, this.height - 16.0F);
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        panel(c, x, y, w, h, this.title.getString());
        float ix = x + PAD;
        float iy = y + HEADER_H + 8.0F;
        float iw = w - PAD * 2;
        float bottom = y + h - PAD;

        // ---------------- 左：背包 ----------------
        float gridW = SLOT * 9;
        small(c, AuctionUi.tr("gui.module_shop.auction.pick_from_inv"), ix, iy + 4, gridW, IOStyle.TEXT_3);
        float gy = iy + 12.0F;
        Inventory inv = inventory();
        for (int i = 0; i < 36; i++) {
            int index = i < 27 ? i + 9 : i - 27;      // 先主背包三行，再快捷栏
            float sx = ix + (i % 9) * SLOT + 1;
            float sy = gy + (i / 9) * SLOT + 1 + (i >= 27 ? 4 : 0);
            slotFrame(c, sx, sy);
            ItemStack st = inv == null ? ItemStack.EMPTY : inv.getItem(index);
            if (index == slot) {
                Draw.fill(c, sx - 1, sy - 1, 18, 18, 2, Draw.alpha(IOStyle.BLUE, 0.30F));
                Draw.stroke(c, sx - 1, sy - 1, 18, 18, 2, 1, IOStyle.BLUE);
            }
            if (!st.isEmpty()) {
                item(st, sx, sy);
                if (over(sx - 1, sy - 1, 18, 18)) {
                    items.setHovered(st);
                }
                final int pick = index;
                hit(sx - 1, sy - 1, 18, 18, () -> select(pick, st.getCount()), () -> select(pick, 1));
            }
        }
        float selY = gy + SLOT * 4 + 10.0F;
        card(c, ix, selY, gridW, 30);
        ItemStack sel = selected();
        if (sel.isEmpty()) {
            small(c, AuctionUi.tr("gui.module_shop.auction.pick_item"), ix + 6, selY + 15, gridW - 12, IOStyle.TEXT_3);
        } else {
            Draw.fill(c, ix + 6, selY + 6, 18, 18, 2, IOStyle.SLOT_INNER);
            item(sel, ix + 7, selY + 7);
            Text.drawLine(c, PageKit.plainName(sel), AtomUi.REGULAR, Text.BODY, IOStyle.TEXT, ix + 30, selY + 10,
                    gridW - 36, Text.Align.LEFT);
            small(c, AuctionUi.tr("gui.module_shop.auction.selected_max", maxCount(sel),
                            AuctionUi.tr(cn.autoforged.module_shop.shop.auction.AuctionCategory.of(sel).langKey())),
                    ix + 30, selY + 21, gridW - 36, IOStyle.TEXT_2);
        }
        if (template != null) {
            paragraph(c, AuctionUi.tr("gui.module_shop.auction.relist_hint"), ix, selY + 36, gridW, Text.SMALL,
                    IOStyle.ORANGE, 3, false);
        }
        small(c, AuctionUi.tr("gui.module_shop.auction.my_active", s.myActive, s.maxPerPlayer), ix, bottom - 4,
                gridW, s.myActive >= s.maxPerPlayer ? IOStyle.RED : IOStyle.TEXT_3);

        // ---------------- 右：参数 ----------------
        float rx = ix + gridW + 12.0F;
        float rw = iw - gridW - 12.0F;
        float labelW = 56.0F;
        float fx = rx + labelW;
        float fw = rw - labelW;
        float ry = iy;
        row(c, rx, ry, "gui.module_shop.auction.f_count");
        count.bounds(fx, ry, 48, 16).visible(true);
        ry += 22;
        row(c, rx, ry, "gui.module_shop.auction.f_allow_bid");
        toggle(c, "allowBid", fx, ry + 2, allowBid, () -> allowBid = !allowBid);
        small(c, AuctionUi.tr(allowBid ? "gui.module_shop.auction.allow_bid_on" : "gui.module_shop.auction.allow_bid_off"),
                fx + 28, ry + 8, fw - 28, IOStyle.TEXT_2);
        ry += 22;
        if (allowBid) {
            row(c, rx, ry, "gui.module_shop.auction.f_start");
            start.bounds(fx, ry, fw, 16).visible(true);
            ry += 22;
            row(c, rx, ry, "gui.module_shop.auction.f_increment");
            increment.bounds(fx, ry, fw, 16).visible(true);
            ry += 22;
        } else {
            start.visible(false);
            increment.visible(false);
        }
        if (s.allowBuyout) {
            row(c, rx, ry, allowBid ? "gui.module_shop.auction.f_buyout" : "gui.module_shop.auction.f_price");
            buyout.bounds(fx, ry, fw, 16).visible(true);
            ry += 22;
        } else {
            buyout.visible(false);
        }
        row(c, rx, ry, "gui.module_shop.auction.f_duration");
        float cx = fx;
        float cy = ry;
        for (int minutes : s.durations) {
            String label = AuctionUi.duration(minutes);
            float cw = Math.max(30.0F, Text.width(label, AtomUi.REGULAR, Text.BODY) + 10.0F);
            if (cx + cw > rx + rw) {
                cx = fx;
                cy += 18;
            }
            final int m = minutes;
            chip(c, "dur" + minutes, label, cx, cy, cw, 15, duration == minutes, IOStyle.BLUE, () -> duration = m);
            cx += cw + 3;
        }
        ry = cy + 22;
        // 费用预览
        long price = allowBid ? Math.max(0L, AuctionUi.parse(start.value())) : Math.max(0L, AuctionUi.parse(buyout.value()));
        long fee = s.listingFee(price);
        small(c, AuctionUi.tr("gui.module_shop.auction.fee_preview", AuctionUi.money(fee),
                trimPercent(s.saleFeePercent)), rx, ry + 4, rw, fee > 0L ? IOStyle.ORANGE : IOStyle.TEXT_2);
        ry += 12;
        if (allowBid) {
            small(c, AuctionUi.tr("gui.module_shop.auction.rule_preview"), rx, ry + 4, rw, IOStyle.TEXT_3);
        }
        String problem = problem();
        if (problem != null && !selected().isEmpty()) {
            small(c, problem, rx, bottom - 26, rw, IOStyle.RED);
        }
        float bw = 80.0F;
        button(c, "cancel", AuctionUi.tr("gui.module_shop.cancel"), rx + rw - bw * 2 - 6, bottom - 18, bw, 18,
                Button.Kind.NORMAL, true, this::onClose);
        button(c, "submit", AuctionUi.tr(pending ? "gui.module_shop.auction.submitting" : "gui.module_shop.auction.submit"),
                rx + rw - bw, bottom - 18, bw, 18, Button.Kind.ACCENT, problem == null && !pending, this::submit);
    }

    private static String trimPercent(double p) {
        return p == Math.floor(p) ? Long.toString((long) p) : String.format("%.2f", p);
    }

    private static void row(Canvas c, float x, float y, String key) {
        Text.drawLine(c, AuctionUi.tr(key), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, x, y + 8, 54, Text.Align.LEFT);
    }

    @Override
    protected boolean onEnter() {
        submit();
        return true;
    }

    @Override
    public void onClose() {
        Nav.back();
    }
}
