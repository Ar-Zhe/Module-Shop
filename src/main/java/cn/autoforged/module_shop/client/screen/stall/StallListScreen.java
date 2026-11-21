package cn.autoforged.module_shop.client.screen.stall;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.StallClient;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.SkiaDialog;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.PriceSpecEditor;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.PriceSpec.CurrencyMode;
import io.github.humbleui.skija.Canvas;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 我的商店 → 上架商品：从背包选物品，设置单价与上架数量。
 *
 * <p>只发「槽位 + 物品 id + 数量 + 单价」，服务端核对这一格里确实有这么多同种物品后，
 * 在同一次调用里收上架费、取物品、加入摊位（玩家必须真的拥有这些物品，不会凭空生成商品）。
 * 原来的「存放槽 → 输入价格」上架方式仍然保留（AtomLib 不可用时使用）。
 */
public final class StallListScreen extends SkiaDialog {
    private static final float SLOT = 18.0F;
    private int slot = -1;
    private SkiaTextField count;
    private PriceSpecEditor priceEditor;
    private boolean pending;
    private int seenResult;

    public StallListScreen() {
        super(Component.translatable("gui.module_shop.stall.list_title"));
        this.seenResult = StallClient.resultSeq;
    }

    @Override
    protected void initSkia() {
        String c = count == null ? "" : count.value();
        boolean hadPrev = priceEditor != null;
        CurrencyMode prevMode = hadPrev ? priceEditor.mode() : CurrencyMode.STAR_ONLY;
        String prevTier = hadPrev ? priceEditor.selectedTierKey() : "";
        String prevStar = hadPrev ? priceEditor.starField().value() : "";
        String prevPhys = hadPrev ? priceEditor.physField().value() : "";

        count = field(6, "1");
        count.setValue(c);

        priceEditor = new PriceSpecEditor(this, this.font, PriceSpecEditor.Scope.PLAYER)
                .starPlaceholder(Component.translatable("gui.module_shop.stall.price_hint").getString())
                .items(this.items);
        if (hadPrev) {
            priceEditor.setInitial(PriceSpec.of(prevMode, Math.max(1L, parse(prevStar)), prevTier,
                    Math.max(1L, parse(prevPhys))));
            priceEditor.starField().setValue(prevStar);
            priceEditor.physField().setValue(prevPhys);
        }
        priceEditor.registerInputs(this::addWidget);
    }

    @Nullable
    private static Inventory inventory() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getInventory();
    }

    private ItemStack selected() {
        Inventory inv = inventory();
        return inv == null || slot < 0 ? ItemStack.EMPTY : inv.getItem(slot);
    }

    private int maxCount(ItemStack st) {
        return Math.max(1, Math.min(StallClient.maxStock, st.getCount()));
    }

    private long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private PriceSpec previewUnitSpec() {
        if (priceEditor == null) {
            return PriceSpec.ZERO;
        }
        if (priceEditor.mode() == CurrencyMode.PHYS_ONLY) {
            return PriceSpec.phys(priceEditor.selectedTierKey(), Math.max(0L, priceEditor.physCountOr(0L)));
        }
        return PriceSpec.star(Math.max(0L, priceEditor.starValueOr(0L)));
    }

    private static PriceSpec feeSpec(@Nullable PriceSpec unitSpec) {
        if (unitSpec == null || !unitSpec.isValid()) {
            return PriceSpec.ZERO;
        }
        if (unitSpec.mode == CurrencyMode.PHYS_ONLY) {
            long f = fee(unitSpec.physCount);
            return f > 0L ? PriceSpec.phys(unitSpec.physTierKey, f) : PriceSpec.ZERO;
        }
        long f = fee(unitSpec.starPrice);
        return f > 0L ? PriceSpec.star(f) : PriceSpec.ZERO;
    }

    @Nullable
    private String problem() {
        if (ClientShopData.stall.size() >= StallClient.slots) {
            return Component.translatable("msg.module_shop.stall_full").getString();
        }
        ItemStack st = selected();
        if (st.isEmpty()) {
            return Component.translatable("gui.module_shop.stall.pick_item").getString();
        }
        long n = parse(count.value());
        if (n < 1 || n > maxCount(st)) {
            return Component.translatable("msg.module_shop.stall.err_count", maxCount(st)).getString();
        }
        if (priceEditor == null) {
            return Component.translatable("gui.module_shop.stall.need_price").getString();
        }
        if (priceEditor.mode() == CurrencyMode.STAR_ONLY && priceEditor.starField().value().trim().isEmpty()) {
            return Component.translatable("gui.module_shop.stall.need_price").getString();
        }
        String editorErr = priceEditor.validateError();
        if (editorErr != null) {
            return editorErr;
        }
        PriceSpec spec = priceEditor.buildSpec();
        if (spec == null || !spec.isValid()) {
            return Component.translatable("gui.module_shop.stall.need_price").getString();
        }
        PriceSpec feeSpec = feeSpec(spec);
        if (!feeSpec.isZero() && !PriceDisplay.canAfford(feeSpec, 1)) {
            if (spec.mode == CurrencyMode.PHYS_ONLY) {
                return ClientHomeCurrencyState.tr("msg.module_shop.stall.no_phys_fee",
                        "实体币不足以支付上架费 %s", PriceDisplay.formatPlain(feeSpec));
            }
            return Component.translatable("msg.module_shop.no_money").getString();
        }
        return null;
    }

    private static long fee(long p) {
        return StallClient.feePercent <= 0.0D || p <= 0L ? 0L : (long) Math.ceil(p * StallClient.feePercent / 100.0D);
    }

    private void submit() {
        if (pending || problem() != null || priceEditor == null) {
            return;
        }
        PriceSpec spec = priceEditor.buildSpec();
        if (spec == null || !spec.isValid()) {
            return;
        }
        ItemStack st = selected();
        CompoundTag tag = new CompoundTag();
        tag.putInt("slot", slot);
        tag.putString("item", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        tag.putInt("count", (int) parse(count.value()));
        tag.put("priceSpec", spec.toTag());
        tag.putString("price", Long.toString(spec.supportsPhys() ? spec.physCount : spec.starPrice));
        pending = true;
        StallClient.sendOnce("stall_list", tag);
    }

    @Override
    protected void paint(Canvas c) {
        if (StallClient.resultSeq != seenResult) {
            seenResult = StallClient.resultSeq;
            pending = false;
            if (StallClient.lastOk && "list".equals(StallClient.lastOp)) {
                onClose();
                return;
            }
        }
        float w = Math.min(384.0F, this.width - 16.0F);
        float h = Math.min(262.0F, this.height - 16.0F);
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        panel(c, x, y, w, h, this.title.getString());
        float ix = x + PAD;
        float iy = y + HEADER_H + 8;
        float iw = w - PAD * 2;
        float bottom = y + h - PAD;
        float gridW = SLOT * 9;
        small(c, Component.translatable("gui.module_shop.stall.pick_from_inv").getString(), ix, iy + 4, gridW,
                IOStyle.TEXT_3);
        float gy = iy + 12;
        Inventory inv = inventory();
        for (int i = 0; i < 36; i++) {
            int index = i < 27 ? i + 9 : i - 27;
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
                hit(sx - 1, sy - 1, 18, 18, () -> {
                    slot = pick;
                    count.setValue(Integer.toString(maxCount(st)));
                    if (priceEditor != null) {
                        priceEditor.blur();
                    }
                }, () -> {
                    slot = pick;
                    count.setValue("1");
                    if (priceEditor != null) {
                        priceEditor.blur();
                    }
                });
            }
        }
        // 右侧：选中物品 + 双账本配价
        float rx = ix + gridW + 10;
        float rw = iw - gridW - 10;
        ItemStack sel = selected();
        card(c, rx, iy, rw, 30);
        if (sel.isEmpty()) {
            small(c, Component.translatable("gui.module_shop.stall.pick_item").getString(), rx + 6, iy + 15, rw - 12,
                    IOStyle.TEXT_3);
        } else {
            Draw.fill(c, rx + 6, iy + 6, 18, 18, 2, IOStyle.SLOT_INNER);
            item(sel, rx + 7, iy + 7);
            Text.drawLine(c, PageKit.plainName(sel), AtomUi.REGULAR, Text.BODY, IOStyle.TEXT, rx + 30, iy + 10,
                    rw - 36, Text.Align.LEFT);
            small(c, Component.translatable("gui.module_shop.stall.have", sel.getCount(), maxCount(sel)).getString(),
                    rx + 30, iy + 21, rw - 36, IOStyle.TEXT_2);
        }
        float fy = iy + 36;
        small(c, Component.translatable("gui.module_shop.stall.f_count").getString(), rx, fy + 8, 40, IOStyle.TEXT_2);
        count.bounds(rx + 42, fy, rw - 42, 16).visible(true);
        fy += 21;
        if (priceEditor != null) {
            priceEditor.bounds(rx, fy, rw);
            priceEditor.render(c, this.items, this.mouseX, this.mouseY, this.dtMs);
            if (priceEditor.hoveredTooltip() != null) {
                tip(priceEditor.hoveredTooltip());
            }
            fy += priceEditor.preferredHeight() + 6;
        }
        PriceSpec unitSpec = previewUnitSpec();
        long n = Math.max(0L, parse(count.value()));
        PriceSpec worthSpec = (unitSpec.isValid() && n > 0L)
                ? unitSpec.scale((int) Math.min(Integer.MAX_VALUE, n))
                : null;
        float worthLblW = small(c, ClientHomeCurrencyState.tr("gui.module_shop.stall.worth_lbl", "全部卖出可得"),
                rx, fy + 4, rw * 0.55F, IOStyle.TEXT_2) + 4.0F;
        if (worthSpec != null && worthSpec.isValid()) {
            PriceDisplay.drawCell(c, this.items, worthSpec, rx + worthLblW, fy + 4, rw - worthLblW,
                    Text.Align.RIGHT, IOStyle.ORANGE, false);
        } else {
            Text.drawLine(c, "—", AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                    rx + worthLblW, fy + 4, rw - worthLblW, Text.Align.RIGHT);
        }
        fy += 12;
        PriceSpec feeSpec = feeSpec(unitSpec);
        boolean affordFee = feeSpec.isZero() || PriceDisplay.canAfford(feeSpec, 1);
        float feeLblW = small(c, ClientHomeCurrencyState.tr("gui.module_shop.stall.fee_lbl", "上架费"),
                rx, fy + 4, rw * 0.50F, IOStyle.TEXT_2) + 4.0F;
        if (!feeSpec.isZero()) {
            PriceDisplay.drawCell(c, this.items, feeSpec, rx + feeLblW, fy + 4, rw - feeLblW,
                    Text.Align.RIGHT, affordFee ? IOStyle.ORANGE : IOStyle.RED, false);
        } else {
            Text.drawLine(c, ClientHomeCurrencyState.tr("gui.module_shop.stall.fee_free", "免费"),
                    AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3, rx + feeLblW, fy + 4, rw - feeLblW, Text.Align.RIGHT);
        }
        fy += 12;
        small(c, Component.translatable("gui.module_shop.stall.slots_line", ClientShopData.stall.size(),
                StallClient.slots).getString(), rx, fy + 4, rw, IOStyle.TEXT_3);
        String problem = problem();
        if (problem != null && !sel.isEmpty()) {
            paragraph(c, problem, rx, bottom - 40, rw, Text.SMALL, IOStyle.RED, 2, false);
        }
        float bw = (rw - 4) / 2;
        button(c, "cancel", Component.translatable("gui.module_shop.cancel").getString(), rx, bottom - 18, bw, 18,
                Button.Kind.NORMAL, true, this::onClose);
        button(c, "submit", Component.translatable(pending ? "gui.module_shop.auction.submitting"
                        : "gui.module_shop.stall.list_btn").getString(), rx + bw + 4, bottom - 18, bw, 18,
                Button.Kind.ACCENT, problem == null && !pending, this::submit);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (skia() && priceEditor != null) {
            float k = Math.max(0.01F, SkiaFrame.unitsToGui());
            if (priceEditor.mouseClicked(mx / k, my / k, button)) {
                if (count != null) {
                    count.blur();
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (skia() && priceEditor != null) {
            float k = Math.max(0.01F, SkiaFrame.unitsToGui());
            if (priceEditor.mouseReleased(mx / k, my / k, button)) {
                return true;
            }
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (skia() && priceEditor != null) {
            float k = Math.max(0.01F, SkiaFrame.unitsToGui());
            if (priceEditor.mouseDragged(mx / k, my / k, button, dx / k, dy / k)) {
                return true;
            }
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    protected boolean onScroll(double mx, double my, double amount) {
        if (priceEditor != null && priceEditor.mouseScrolled(mx, my, amount)) {
            return true;
        }
        return super.onScroll(mx, my, amount);
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
