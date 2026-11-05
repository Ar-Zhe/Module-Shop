package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 需求 2：竞拍（出价）界面。
 * 上方为拍卖物品图标（仅图标，不可拿取/丢弃，悬停显示名称/附魔/属性/NBT/标签），
 * 下方显示当前价格，并提供 -10、-5、自定义金额、+5、+10 的快捷调价，
 * 最下方左侧取消、右侧确认。
 */
public class AuctionBidScreen extends SkiaDialog {
    private static final int PANEL_W = 280;
    private static final int PANEL_H = 180;
    private static final int COLOR_TEXT = IOStyle.TEXT;
    private static final int COLOR_MUTED = IOStyle.TEXT_2;
    private static final int COLOR_GOLD = IOStyle.ORANGE;

    private final long id;
    private final ItemStack stack;
    private final long startPrice;
    private long currentBid;
    private long lastBid;
    private final long minIncrement;
    /** 该拍卖是否还在列表里（不在说明已结束，出价没有意义）。 */
    private boolean listed = true;
    private EditBox amountBox;
    private int left;
    private int top;

    public AuctionBidScreen(ClientShopData.AuctionView view) {
        super(Component.translatable("gui.module_shop.input.bid"));
        this.id = view.id;
        this.stack = view.stack.copy();
        this.startPrice = view.startPrice;
        this.currentBid = view.currentBid;
        this.lastBid = view.lastBid;
        this.minIncrement = Math.max(1L, Config.INSTANCE.auctionMinIncrement.get());
    }

    /** 需求：最低出价 = 当前价 + 加价幅度（无人出价时为起拍价）。 */
    private long minimum() {
        return currentBid > 0L ? currentBid + minIncrement : Math.max(1L, startPrice);
    }

    /**
     * 用最新快照刷新当前价：界面打开后别人继续加价时，本界面提示的最低出价也要跟着变，
     * 否则玩家按旧提示出价会被服务端以“出价过低”拒绝。
     */
    private void refreshFromCache() {
        for (ClientShopData.AuctionView view : ClientShopData.auctions) {
            if (view.id == this.id) {
                this.currentBid = view.currentBid;
                this.lastBid = view.lastBid;
                this.listed = true;
                return;
            }
        }
        this.listed = false;
    }

    @Override
    protected void init() {
        if (skiaInit()) {
            return;
        }
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
        int rowY = this.top + 82;
        int x = this.left + 12;
        this.addRenderableWidget(new ShopButton(x, rowY, 36, 20, Component.literal("-10"), b -> adjust(-10L)));
        this.addRenderableWidget(new ShopButton(x + 40, rowY, 36, 20, Component.literal("-5"), b -> adjust(-5L)));
        this.amountBox = new EditBox(this.font, x + 80, rowY, 80, 20,
                Component.translatable("gui.module_shop.field.amount"));
        this.amountBox.setMaxLength(18);
        this.amountBox.setValue(Long.toString(minimum()));
        this.addRenderableWidget(this.amountBox);
        this.addRenderableWidget(new ShopButton(x + 164, rowY, 36, 20, Component.literal("+5"), b -> adjust(5L)));
        this.addRenderableWidget(new ShopButton(x + 204, rowY, 36, 20, Component.literal("+10"), b -> adjust(10L)));
        int buttonY = this.top + PANEL_H - 28;
        // 需求 2：最下为取消和确认。
        this.addRenderableWidget(new ShopButton(this.left + 24, buttonY, 90, 20,
                Component.translatable("gui.module_shop.cancel"), b -> onClose()));
        this.addRenderableWidget(new ShopButton(this.left + PANEL_W - 114, buttonY, 90, 20,
                Component.translatable("gui.module_shop.confirm"), b -> confirm()));
        this.setInitialFocus(this.amountBox);
    }

    private void adjust(long delta) {
        long value;
        try {
            value = Long.parseLong(this.amountBox.getValue().trim());
        } catch (NumberFormatException ex) {
            value = minimum();
        }
        this.amountBox.setValue(Long.toString(Math.max(minimum(), value + delta)));
    }

    private void confirm() {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "bid");
        tag.putLong("id", this.id);
        tag.putString("amount", this.amountBox.getValue().trim());
        ServerboundShopActionPayload.send(tag);
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        if (skia()) {
            return;
        }
        Bricks.panel(g, this.left, this.top, PANEL_W, PANEL_H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        refreshFromCache();
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.left + PANEL_W / 2;
        IOStyle.centerAt(g, this.font, this.title, cx, this.top + 10, COLOR_GOLD);
        // 上方物品图标（仅展示，不是可交互槽位）。
        int iconX = cx - 8;
        int iconY = this.top + 26;
        Bricks.slot(g, iconX - 1, iconY - 1);
        if (!this.stack.isEmpty()) {
            g.renderItem(this.stack, iconX, iconY);
            g.renderItemDecorations(this.font, this.stack, iconX, iconY);
        }
        IOStyle.centerAt(g, this.font, this.stack.isEmpty() ? Component.literal("-") : this.stack.getHoverName(),
                cx, this.top + 48, COLOR_TEXT);
        // 需求三：居中显示当前最高出价，并在下方用划掉效果展示上一轮出价。
        if (this.currentBid > 0L) {
            String current = Component.translatable("gui.module_shop.auction_current_bid",
                    ClientShopData.money(this.currentBid)).getString();
            IOStyle.centerAt(g, this.font, current, cx, this.top + 58, COLOR_GOLD);
            if (this.lastBid > 0L) {
                String last = Component.translatable("gui.module_shop.auction_last_bid",
                        ClientShopData.money(this.lastBid)).getString();
                int lastX = cx - this.font.width(last) / 2;
                g.drawString(this.font, last, lastX, this.top + 66, 0x8A8A9A, false);
                g.fill(lastX, this.top + 71, lastX + this.font.width(last), this.top + 72, 0x8A8A9A);
            }
        } else {
            String start = Component.translatable("gui.module_shop.auction_start",
                    ClientShopData.money(this.startPrice)).getString();
            IOStyle.centerAt(g, this.font, start, cx, this.top + 60, COLOR_GOLD);
        }
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.bid_min",
                ClientShopData.money(minimum())), cx, this.top + 74, COLOR_MUTED);
        // 明确“出价即扣款、被超价/流拍会退回”，并显示当前余额，避免玩家以为钱被吃掉。
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.bid_rule"),
                cx, this.top + 112, COLOR_MUTED);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.bid_balance",
                ClientShopData.money(ClientShopData.balance)), cx, this.top + 124, COLOR_GOLD);
        if (!this.listed) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.auction_gone"),
                    cx, this.top + 138, 0xFF6B6B);
        }
        // 悬停图标显示物品完整信息（名称/附魔/属性/NBT/标签）。
        if (mouseX >= iconX && mouseX < iconX + 16 && mouseY >= iconY && mouseY < iconY + 16 && !this.stack.isEmpty()) {
            g.renderTooltip(this.font, this.stack, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------ Skija

    private cn.autoforged.module_shop.client.ui.widget.SkiaTextField skAmount;

    @Override
    protected void initSkia() {
        String keep = this.amountBox == null ? "" : this.amountBox.getValue();
        this.skAmount = field(18, "");
        this.amountBox = this.skAmount.editBox();
        this.amountBox.setValue(keep.isEmpty() ? Long.toString(minimum()) : keep);
        this.skAmount.focus();
    }

    @Override
    protected boolean onEnter() {
        confirm();
        return true;
    }

    @Override
    protected void paint(io.github.humbleui.skija.Canvas c) {
        refreshFromCache();
        float w = 260.0F;
        float h = 206.0F;
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        float innerX = x + PAD;
        float innerW = w - PAD * 2;
        panel(c, x, y, w, h, this.title.getString());
        // 物品卡：图标 + 名称
        float cardY = y + HEADER_H + 8;
        card(c, innerX, cardY, innerW, 30);
        cn.autoforged.module_shop.client.ui.render.Draw.fill(c, innerX + 6, cardY + 6, 18, 18, 2, IOStyle.FIELD_BG);
        item(this.stack, innerX + 7, cardY + 7);
        text(c, this.stack.isEmpty() ? "-" : this.stack.getHoverName().getString(), innerX + 30, cardY + 15,
                innerW - 36, IOStyle.TEXT);
        float cy = cardY + 40;
        if (this.currentBid > 0L) {
            center(c, Component.translatable("gui.module_shop.auction_current_bid",
                    ClientShopData.money(this.currentBid)).getString(), innerX, cy, innerW, IOStyle.ORANGE);
            if (this.lastBid > 0L) {
                String last = Component.translatable("gui.module_shop.auction_last_bid",
                        ClientShopData.money(this.lastBid)).getString();
                float lw = cn.autoforged.module_shop.client.ui.render.Text.width(last,
                        cn.autoforged.module_shop.client.ui.AtomUi.REGULAR,
                        cn.autoforged.module_shop.client.ui.render.Text.SMALL);
                float lx = innerX + (innerW - lw) / 2;
                small(c, last, lx, cy + 11, lw + 2, IOStyle.TEXT_3);
                cn.autoforged.module_shop.client.ui.render.Draw.fill(c, lx, cy + 11, lw, 0.8F, 0, IOStyle.TEXT_3);
            }
        } else {
            center(c, Component.translatable("gui.module_shop.auction_start",
                    ClientShopData.money(this.startPrice)).getString(), innerX, cy, innerW, IOStyle.ORANGE);
        }
        center(c, Component.translatable("gui.module_shop.bid_min", ClientShopData.money(minimum())).getString(),
                innerX, cy + 22, innerW, IOStyle.TEXT_2);
        // 调价行：-10 -5 [金额] +5 +10
        float rowY = cy + 32;
        float bw = 32.0F;
        float gap = 4.0F;
        float fieldW = innerW - bw * 4 - gap * 4;
        cn.autoforged.module_shop.client.ui.widget.Button.Kind n =
                cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL;
        button(c, "m10", "-10", innerX, rowY, bw, 18, n, true, () -> adjust(-10L));
        button(c, "m5", "-5", innerX + bw + gap, rowY, bw, 18, n, true, () -> adjust(-5L));
        this.skAmount.bounds(innerX + (bw + gap) * 2, rowY, fieldW, 18);
        float px = innerX + (bw + gap) * 2 + fieldW + gap;
        button(c, "p5", "+5", px, rowY, bw, 18, n, true, () -> adjust(5L));
        button(c, "p10", "+10", px + bw + gap, rowY, bw, 18, n, true, () -> adjust(10L));
        float infoY = rowY + 30;
        center(c, Component.translatable("gui.module_shop.bid_rule").getString(), innerX, infoY, innerW,
                IOStyle.TEXT_3);
        center(c, Component.translatable("gui.module_shop.bid_balance",
                ClientShopData.money(ClientShopData.balance)).getString(), innerX, infoY + 12, innerW, IOStyle.ORANGE);
        if (!this.listed) {
            center(c, Component.translatable("gui.module_shop.auction_gone").getString(), innerX, infoY + 24, innerW,
                    IOStyle.RED);
        }
        float by = y + h - PAD - 20;
        button(c, "cancel", Component.translatable("gui.module_shop.cancel").getString(), x + PAD + 8, by, 90, 20, n,
                true, this::onClose);
        button(c, "confirm", Component.translatable("gui.module_shop.confirm").getString(), x + w - PAD - 8 - 90, by,
                90, 20, cn.autoforged.module_shop.client.ui.widget.Button.Kind.ACCENT, this.listed, this::confirm);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // ESC 返回上一级界面（Nav 返回栈），没有上一级时才真正关闭。
        Nav.back();
    }
}
