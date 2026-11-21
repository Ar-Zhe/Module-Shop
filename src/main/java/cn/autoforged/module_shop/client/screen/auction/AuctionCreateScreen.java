package cn.autoforged.module_shop.client.screen.auction;

import cn.autoforged.module_shop.client.AuctionClient;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.PhysTierView;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.SkiaDialog;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.PriceSpec;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
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
    private String physTierKey = "";
    private float currencyScrollX;
    private float currencyMaxScrollX;
    private float stripX;
    private float stripY;
    private float stripW;
    private float stripH;
    private float stripInnerW;
    private float stripNaturalSum;
    private boolean draggingCurrencyBar;
    private float currencyDragStartX;
    private float currencyDragStartScroll;
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
        if (template.isPhysCurrency() && ClientHomeCurrencyState.physEnabled) {
            PhysTierView tier = ClientHomeCurrencyState.findTier(template.physTierKey);
            physTierKey = tier != null ? tier.tierKey() : template.physTierKey;
        } else {
            physTierKey = "";
        }
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

    private boolean isPhysCurrency() {
        return ClientHomeCurrencyState.physEnabled && physTierKey != null && !physTierKey.isEmpty();
    }

    private void selectCurrency(String nextTierKey) {
        this.physTierKey = nextTierKey == null ? "" : nextTierKey;
    }

    private static boolean tierMatches(PhysTierView tv, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return key.equals(tv.tierKey())
                || key.equals(tv.id)
                || key.equalsIgnoreCase(tv.uniqueItemKey())
                || (!key.contains("|") && key.equalsIgnoreCase(tv.itemId));
    }

    private static long physListingFee(AuctionClient.Settings s, long startPrice) {
        if (s.feePercent <= 0.0D || startPrice <= 0L) {
            return 0L;
        }
        return (long) Math.floor(startPrice * s.feePercent / 100.0D);
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
        boolean isPhys = isPhysCurrency();
        if (isPhys && ClientHomeCurrencyState.findTier(physTierKey) == null) {
            return AuctionUi.tr("msg.module_shop.phys_tier_unavailable", "所需的实体货币档位未配置或已失效");
        }
        long b = AuctionUi.parse(buyout.value());
        long inc = AuctionUi.parse(increment.value());
        if (b < 0L || inc < 0L || (isPhys && (b > Long.MAX_VALUE / 2L || inc > Long.MAX_VALUE / 2L))) {
            return AuctionUi.tr("msg.module_shop.auction.err_amount");
        }
        if (b > 0L && !s.allowBuyout) {
            return AuctionUi.tr("msg.module_shop.auction.err_buyout_disabled");
        }
        long minInc = isPhys ? 1L : s.minIncrement;
        long price;
        if (allowBid) {
            price = AuctionUi.parse(start.value());
            if (price <= 0L) {
                return AuctionUi.tr("gui.module_shop.auction.need_start");
            }
            if (isPhys && price > Long.MAX_VALUE / 2L) {
                return AuctionUi.tr("msg.module_shop.auction.err_amount");
            }
            if (inc > 0L && inc < minInc) {
                return isPhys
                        ? AuctionUi.tr("msg.module_shop.auction.err_increment_phys", "加价幅度至少为 %s 枚", minInc)
                        : AuctionUi.tr("msg.module_shop.auction.err_increment", minInc);
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
        long minStart = isPhys ? 1L : s.minStart;
        long maxStart = isPhys ? 0L : s.maxStart;
        if (price < minStart || (maxStart > 0L && price > maxStart)) {
            return isPhys
                    ? AuctionUi.tr("msg.module_shop.auction.err_start_range_phys", "起拍枚数至少为 %s 枚", minStart)
                    : AuctionUi.tr("msg.module_shop.auction.err_start_range", minStart,
                            maxStart > 0L ? Long.toString(maxStart) : "∞");
        }
        if (isPhys) {
            long fee = physListingFee(s, price);
            if (fee > 0L && !PriceDisplay.canAffordPhys(physTierKey, fee, 1)) {
                return AuctionUi.tr("msg.module_shop.auction.err_fee_phys",
                        "实体币不足以支付上架费 %s", PriceDisplay.physPlain(physTierKey, fee));
            }
        } else {
            long fee = s.listingFee(price);
            if (fee > ClientShopData.balance) {
                return AuctionUi.tr("msg.module_shop.auction.err_fee", fee);
            }
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
        tag.putString("physTier", isPhysCurrency() ? physTierKey : "");
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
        if (!ClientHomeCurrencyState.physEnabled) {
            physTierKey = "";
        }
        boolean isPhys = isPhysCurrency();
        if (isPhys) {
            String tierName = PriceDisplay.tierDisplayName(physTierKey);
            start.placeholder(AuctionUi.tr("gui.module_shop.auction.start_hint_phys", "枚数 (%s)", tierName));
            increment.placeholder("1");
        } else {
            start.placeholder(AuctionUi.tr("gui.module_shop.auction.start_hint"));
            increment.placeholder(Long.toString(s.minIncrement));
        }
        float w = Math.min(418.0F, this.width - 16.0F);
        float h = Math.min(272.0F, this.height - 16.0F);
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
        float labelW = Math.min(54.0F, Math.max(28.0F, rw * 0.40F));
        float fx = rx + labelW;
        float fw = Math.max(1.0F, rw - labelW);
        float ry = iy;
        if (ClientHomeCurrencyState.physEnabled) {
            row(c, rx, ry + 3, "gui.module_shop.auction.f_currency", "结算币种", labelW - 2.0F);
            paintCurrencyStrip(c, fx, ry, fw, 22.0F);
            ry += 25.0F;
        } else {
            this.stripW = 0.0F;
        }
        row(c, rx, ry, "gui.module_shop.auction.f_count", labelW - 2.0F);
        count.bounds(fx, ry, Math.min(48.0F, fw), 16).visible(true);
        ry += 20;
        row(c, rx, ry, "gui.module_shop.auction.f_allow_bid", labelW - 2.0F);
        toggle(c, "allowBid", fx, ry + 2, allowBid, () -> allowBid = !allowBid);
        small(c, AuctionUi.tr(allowBid ? "gui.module_shop.auction.allow_bid_on" : "gui.module_shop.auction.allow_bid_off"),
                fx + 28, ry + 8, fw - 28, IOStyle.TEXT_2);
        ry += 19;
        if (allowBid) {
            row(c, rx, ry, "gui.module_shop.auction.f_start", labelW - 2.0F);
            start.bounds(fx, ry, fw, 16).visible(true);
            ry += 20;
            row(c, rx, ry, "gui.module_shop.auction.f_increment", labelW - 2.0F);
            increment.bounds(fx, ry, fw, 16).visible(true);
            ry += 20;
        } else {
            start.visible(false);
            increment.visible(false);
        }
        if (s.allowBuyout) {
            row(c, rx, ry, allowBid ? "gui.module_shop.auction.f_buyout" : "gui.module_shop.auction.f_price",
                    labelW - 2.0F);
            buyout.bounds(fx, ry, fw, 16).visible(true);
            ry += 20;
        } else {
            buyout.visible(false);
        }
        row(c, rx, ry, "gui.module_shop.auction.f_duration", labelW - 2.0F);
        float cx = fx;
        float cy = ry;
        for (int minutes : s.durations) {
            String label = AuctionUi.duration(minutes);
            float cw = Math.min(Math.max(1.0F, fw), Math.max(30.0F,
                    Text.width(label, AtomUi.REGULAR, Text.BODY) + 10.0F));
            if (cx + cw > rx + rw) {
                cx = fx;
                cy += 17;
            }
            final int m = minutes;
            chip(c, "dur" + minutes, label, cx, cy, cw, 15, duration == minutes, IOStyle.BLUE, () -> duration = m);
            cx += cw + 3;
        }
        ry = cy + 20;
        // 费用预览
        long price = allowBid ? Math.max(0L, AuctionUi.parse(start.value())) : Math.max(0L, AuctionUi.parse(buyout.value()));
        if (isPhys) {
            long fee = physListingFee(s, price);
            boolean affordFee = fee <= 0L || PriceDisplay.canAffordPhys(physTierKey, fee, 1);
            if (fee <= 0L) {
                small(c, AuctionUi.tr("gui.module_shop.auction.fee_preview_phys_free",
                                "上架费 免费（免固定星币费）· 成交手续费 %s%%", trimPercent(s.saleFeePercent)),
                        rx, ry + 4, rw, IOStyle.TEXT_2);
            } else {
                String feePrefix = AuctionUi.tr("gui.module_shop.auction.fee_prefix", "上架费 ");
                String feeSuffix = AuctionUi.tr("gui.module_shop.auction.fee_suffix_short",
                        " · 成交 %s%%", trimPercent(s.saleFeePercent));
                float preW = small(c, feePrefix, rx, ry + 4, rw * 0.35F, affordFee ? IOStyle.ORANGE : IOStyle.RED);
                float sufW = Math.min(rw * 0.45F, Text.width(feeSuffix, AtomUi.REGULAR, Text.SMALL));
                float cellW = Math.max(24.0F, rw - preW - sufW - 2.0F);
                PriceDisplay.drawCell(c, items, PriceSpec.phys(physTierKey, fee), rx + preW, ry + 4, cellW,
                        Text.Align.LEFT, affordFee ? IOStyle.ORANGE : IOStyle.RED, false);
                Text.drawLine(c, feeSuffix, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                        rx + preW + cellW + 2.0F, ry + 4, sufW, Text.Align.RIGHT);
            }
        } else {
            long fee = s.listingFee(price);
            boolean affordFee = fee <= ClientShopData.balance;
            small(c, AuctionUi.tr("gui.module_shop.auction.fee_preview", AuctionUi.money(fee),
                            trimPercent(s.saleFeePercent)), rx, ry + 4, rw,
                    !affordFee ? IOStyle.RED : (fee > 0L ? IOStyle.ORANGE : IOStyle.TEXT_2));
        }
        ry += 11;
        if (allowBid) {
            small(c, AuctionUi.tr("gui.module_shop.auction.rule_preview"), rx, ry + 4, rw, IOStyle.TEXT_3);
        }
        String problem = problem();
        if (problem != null && !selected().isEmpty()) {
            small(c, problem, rx, bottom - 26, rw, IOStyle.RED);
        }
        float buttonGap = Math.min(6.0F, Math.max(0.0F, rw * 0.04F));
        float bw = Math.min(80.0F, Math.max(1.0F, (rw - buttonGap) / 2.0F));
        button(c, "cancel", AuctionUi.tr("gui.module_shop.cancel"), rx + rw - bw * 2 - buttonGap, bottom - 18, bw, 18,
                Button.Kind.NORMAL, true, this::onClose);
        button(c, "submit", AuctionUi.tr(pending ? "gui.module_shop.auction.submitting" : "gui.module_shop.auction.submit"),
                rx + rw - bw, bottom - 18, bw, 18, Button.Kind.ACCENT, problem == null && !pending, this::submit);
    }

    private void paintCurrencyStrip(Canvas c, float x, float y, float w, float h) {
        this.stripX = x;
        this.stripY = y;
        this.stripW = w;
        this.stripH = h;
        Draw.fill(c, x, y, w, h, 3.0F, IOStyle.FIELD_BG);
        Draw.stroke(c, x, y, w, h, 3.0F, 0.7F, IOStyle.BORDER_DIM);

        List<PhysTierView> tiers = ClientHomeCurrencyState.physTiers;
        String starName = ClientShopData.currencyName();
        if (starName.isEmpty()) {
            starName = AuctionUi.tr("gui.module_shop.currency_star", "星币");
        }
        String starPillText = "★ " + starName;
        float pad = 2.0F;
        float gap = 3.0F;
        float innerW = Math.max(20.0F, w - pad * 2.0F);
        float pillH = h - pad * 2.0F;
        float py = y + pad;

        int totalCount = 1 + tiers.size();
        float[] widths = new float[totalCount];
        widths[0] = Math.max(40.0F, Math.min(72.0F, Text.width(starPillText, AtomUi.BOLD, V2.FS_S) + 10.0F));
        float naturalSum = widths[0];
        for (int i = 0; i < tiers.size(); i++) {
            PhysTierView tv = tiers.get(i);
            float nameW = Text.width(tv.displayName(), AtomUi.BOLD, V2.FS_S);
            float cntW = Text.width("×" + tv.ownedCount, AtomUi.REGULAR, V2.FS_XS);
            widths[i + 1] = Math.max(48.0F, Math.min(82.0F, 18.0F + Math.max(nameW, cntW)));
            naturalSum += gap + widths[i + 1];
        }
        if (naturalSum < innerW && totalCount <= 4) {
            float extra = (innerW - naturalSum) / totalCount;
            for (int i = 0; i < widths.length; i++) {
                widths[i] += extra;
            }
            naturalSum = innerW;
        }
        this.currencyMaxScrollX = Math.max(0.0F, naturalSum - innerW);
        this.currencyScrollX = Mth.clamp(this.currencyScrollX, 0.0F, this.currencyMaxScrollX);
        this.stripInnerW = innerW;
        this.stripNaturalSum = naturalSum;

        int saved = c.save();
        Draw.clip(c, x + pad, y + 1.0F, innerW, h - 2.0F);
        items.pushClip(x + pad, y + 1.0F, innerW, h - 2.0F);
        pushHitClip(x + pad, y + 1.0F, innerW, h - 2.0F);

        float cursorX = x + pad - this.currencyScrollX;
        // 星币选项
        boolean starSel = physTierKey == null || physTierKey.isEmpty();
        boolean starHov = over(cursorX, py, widths[0], pillH);
        int starBg = starSel ? Draw.alpha(IOStyle.ORANGE, 0.22F)
                : (starHov ? IOStyle.ROW_HOVER : Draw.alpha(IOStyle.CARD, 0.9F));
        int starBorder = starSel ? IOStyle.ORANGE : (starHov ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
        Draw.fill(c, cursorX, py, widths[0], pillH, 2.5F, starBg);
        Draw.stroke(c, cursorX, py, widths[0], pillH, 2.5F, starSel ? 1.0F : 0.6F, starBorder);
        Text.drawLine(c, starPillText, starSel ? AtomUi.BOLD : AtomUi.REGULAR, V2.FS_S,
                starSel ? IOStyle.ORANGE : IOStyle.TEXT_2, cursorX + 2.0F, py + pillH * 0.5F,
                widths[0] - 4.0F, Text.Align.CENTER);
        if (starHov) {
            tip(Component.literal(starName + " · "
                    + AuctionUi.tr("gui.module_shop.auction.star_bal_tip", "余额 %s",
                    AuctionUi.money(ClientShopData.balance))));
        }
        hit(cursorX, py, widths[0], pillH, () -> selectCurrency(""), null);
        cursorX += widths[0] + gap;

        // 各阶实体货币档位
        for (int i = 0; i < tiers.size(); i++) {
            PhysTierView tv = tiers.get(i);
            float pw = widths[i + 1];
            if (cursorX + pw >= x + pad && cursorX <= x + pad + innerW) {
                boolean selItem = tierMatches(tv, physTierKey);
                boolean hov = over(cursorX, py, pw, pillH);
                int bg = selItem ? Draw.alpha(IOStyle.BLUE, 0.20F)
                        : (hov ? IOStyle.ROW_HOVER : Draw.alpha(IOStyle.CARD, 0.9F));
                int border = selItem ? IOStyle.BLUE : (hov ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                Draw.fill(c, cursorX, py, pw, pillH, 2.5F, bg);
                Draw.stroke(c, cursorX, py, pw, pillH, 2.5F, selItem ? 1.0F : 0.6F, border);
                if (!tv.itemStack.isEmpty()) {
                    items.addNoHover(tv.itemStack.copyWithCount(1), cursorX + 2.5F, py + (pillH - 12.0F) * 0.5F,
                            12.0F, false);
                }
                float tx = cursorX + 16.0F;
                float tw = Math.max(8.0F, pw - 18.0F);
                Text.drawLine(c, tv.displayName(), selItem ? AtomUi.BOLD : AtomUi.REGULAR, V2.FS_S,
                        selItem ? IOStyle.TEXT : IOStyle.TEXT_2, tx, py + 5.5F, tw, Text.Align.LEFT);
                Text.drawLine(c, "×" + tv.ownedCount, AtomUi.REGULAR, V2.FS_XS,
                        tv.ownedCount > 0 ? IOStyle.GREEN : IOStyle.TEXT_3, tx, py + 13.0F, tw, Text.Align.LEFT);
                if (hov) {
                    tip(Component.literal(tv.displayName() + " · "
                            + AuctionUi.tr("gui.module_shop.phys_ex.unit_val", "单枚面值 %s",
                            ClientShopData.money(tv.value))
                            + " · " + AuctionUi.tr("gui.module_shop.phys_ex.owned_tip", "持有 %s", tv.ownedCount)));
                }
                final String chosenKey = tv.tierKey();
                hit(cursorX, py, pw, pillH, () -> selectCurrency(chosenKey), null);
            }
            cursorX += pw + gap;
        }
        popHitClip();
        items.popClip();
        c.restoreToCount(saved);

        if (this.currencyMaxScrollX > 0.5F) {
            float barW = Math.max(18.0F, innerW * Math.min(1.0F, innerW / Math.max(innerW + 1.0F, naturalSum)));
            float barX = x + pad + (innerW - barW) * (this.currencyScrollX / this.currencyMaxScrollX);
            float barY = y + h - 3.2F;
            boolean hovBar = this.draggingCurrencyBar || (this.mouseX >= x && this.mouseX <= x + w
                    && this.mouseY >= y + h - 8.0F && this.mouseY <= y + h);
            float barH = hovBar ? 2.6F : 1.8F;
            if (hovBar) {
                Draw.fill(c, x + pad, barY - (barH - 1.8F) * 0.5F, innerW, barH, barH * 0.5F,
                        Draw.alpha(IOStyle.HAIR, 0.7F));
            }
            Draw.fill(c, barX, barY - (barH - 1.8F) * 0.5F, barW, barH, barH * 0.5F,
                    Draw.alpha(IOStyle.BLUE, hovBar ? 1.0F : 0.65F));
        }
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (button == 0 && this.currencyMaxScrollX > 0.5F && this.stripW > 0.0F
                && mx >= this.stripX && mx <= this.stripX + this.stripW
                && my >= this.stripY + this.stripH - 8.0F && my <= this.stripY + this.stripH) {
            float trackW = Math.max(1.0F, this.stripInnerW);
            float barW = Math.max(18.0F, trackW * Math.min(1.0F, trackW / Math.max(trackW + 1.0F, this.stripNaturalSum)));
            float travel = Math.max(1.0F, trackW - barW);
            float rel = (float) mx - (this.stripX + 2.0F) - barW * 0.5F;
            this.currencyScrollX = Mth.clamp(rel / travel * this.currencyMaxScrollX, 0.0F, this.currencyMaxScrollX);
            this.draggingCurrencyBar = true;
            this.currencyDragStartX = (float) mx;
            this.currencyDragStartScroll = this.currencyScrollX;
            return true;
        }
        return super.onClick(mx, my, button);
    }

    @Override
    protected boolean onDrag(double mx, double my, int button, double dx, double dy) {
        if (this.draggingCurrencyBar && this.currencyMaxScrollX > 0.5F) {
            float trackW = Math.max(1.0F, this.stripInnerW);
            float barW = Math.max(18.0F, trackW * Math.min(1.0F, trackW / Math.max(trackW + 1.0F, this.stripNaturalSum)));
            float travel = Math.max(1.0F, trackW - barW);
            float delta = ((float) mx - this.currencyDragStartX) * (this.currencyMaxScrollX / travel);
            this.currencyScrollX = Mth.clamp(this.currencyDragStartScroll + delta, 0.0F, this.currencyMaxScrollX);
            return true;
        }
        return super.onDrag(mx, my, button, dx, dy);
    }

    @Override
    protected boolean onRelease(double mx, double my, int button) {
        boolean was = this.draggingCurrencyBar;
        this.draggingCurrencyBar = false;
        return was;
    }

    @Override
    protected boolean onScroll(double mx, double my, double amount) {
        if (this.stripW > 0.0F && this.currencyMaxScrollX > 0.0F
                && mx >= this.stripX && mx < this.stripX + this.stripW
                && my >= this.stripY && my < this.stripY + this.stripH) {
            this.currencyScrollX = Mth.clamp(this.currencyScrollX - (float) amount * 24.0F, 0.0F,
                    this.currencyMaxScrollX);
            return true;
        }
        return super.onScroll(mx, my, amount);
    }

    private static String trimPercent(double p) {
        return p == Math.floor(p) ? Long.toString((long) p) : String.format("%.2f", p);
    }

    private static void row(Canvas c, float x, float y, String key, float width) {
        Text.drawLine(c, AuctionUi.tr(key), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, x, y + 8,
                Math.max(1.0F, width), Text.Align.LEFT);
    }

    private static void row(Canvas c, float x, float y, String key, String fallback, float width) {
        Text.drawLine(c, AuctionUi.tr(key, fallback), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, x, y + 8,
                Math.max(1.0F, width), Text.Align.LEFT);
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
