package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.PriceSpecEditor;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.menu.GrantMenu;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.time.LocalDate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 日程奖励配置：左侧 3×9 物品槽 + 右侧双独立账本配价组件（星币 / 实体货币 / 组合同发）。
 */
public class ScheduleRewardItemsScreen extends AbstractContainerScreen<GrantMenu>
        implements SkiaContainerBackground.Painter {

    @Override
    public boolean usesSkiaBackground() {
        return this.skia;
    }

    @Override
    public void paintSkiaBackground(GuiGraphics g, int mouseX, int mouseY) {
        skBg(g, this.leftPos, this.topPos, mouseX, mouseY);
    }

    private static final int PANEL_W = 378;
    private static final int PANEL_H = 236;
    private static final int LIST_X = 182;
    private static final int LIST_W = 188;

    private static Screen returnTo;
    private EditBox moneyBox;
    private String fallbackError = "";

    private final ItemOverlay currencyItems = new ItemOverlay();
    private PriceSpecEditor moneyEditor;
    private float edX;
    private float edY;
    private float edW;
    private float edH;
    private long lastFrameNs;

    public static void setReturnTo(Screen screen) {
        returnTo = screen;
    }

    public ScheduleRewardItemsScreen(GrantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = GrantMenu.INVENTORY_Y - 12;
    }

    private long day() {
        return ClientShopData.scheduleDay;
    }

    private static ClientShopData.ActivityView findDay(long targetDay) {
        ClientShopData.ActivityView draft = ClientShopData.activityDraft;
        if (draft != null && draft.day == targetDay) {
            return draft;
        }
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == targetDay) {
                return view;
            }
        }
        return null;
    }

    private PriceSpec currentMoneySpec() {
        ClientShopData.ActivityView act = findDay(day());
        return act == null ? PriceSpec.ZERO : act.resolveMoneySpec();
    }

    private String currentItemsText() {
        ClientShopData.ActivityView act = findDay(day());
        if (act == null || act.itemText == null || act.itemText.isEmpty()) {
            return "-";
        }
        return act.itemText;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos;
        int y = this.topPos;
        this.skia = AtomUi.available();
        if (this.skia) {
            PriceSpec preserved = this.moneyEditor != null ? this.moneyEditor.buildSpec() : null;
            this.moneyEditor = new PriceSpecEditor(this, this.font, PriceSpecEditor.Scope.OP)
                    .allowZero(true)
                    .items(this.currencyItems);
            this.moneyEditor.setInitial(preserved != null ? preserved : currentMoneySpec());
            this.moneyEditor.registerInputs(this::addWidget);
            return;
        }
        this.moneyEditor = null;
        this.moneyBox = new EditBox(this.font, x + LIST_X + 1, y + 122, LIST_W - 2, 16,
                Component.translatable("gui.module_shop.grant.amount"));
        this.moneyBox.setMaxLength(64);
        this.moneyBox.setHint(Component.translatable("gui.module_shop.grant.amount_hint"));
        this.moneyBox.setValue(currentMoneySpec().toRuleText());
        this.addRenderableWidget(this.moneyBox);

        int btnY = y + 194;
        this.addRenderableWidget(new ShopButton(x + LIST_X, btnY, 106, 20,
                Component.translatable("gui.module_shop.schedule.save"), b -> save()));
        this.addRenderableWidget(new ShopButton(x + LIST_X + 112, btnY, 76, 20,
                Component.translatable("gui.module_shop.schedule.cancel"), b -> finish(false)));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private PriceSpec resolveSaveSpec() {
        if (this.skia && this.moneyEditor != null) {
            if (this.moneyEditor.mode() == PriceSpec.CurrencyMode.STAR_ONLY
                    && this.moneyEditor.starField().value().isBlank()) {
                return PriceSpec.ZERO;
            }
            return this.moneyEditor.buildSpec();
        }
        String raw = this.moneyBox == null ? "" : this.moneyBox.getValue().trim();
        if (raw.isEmpty()) {
            return PriceSpec.ZERO;
        }
        return PriceSpec.parseRuleText(raw);
    }

    /** 保存：把槽内物品 + 双账本金额规格写进该日程草稿。 */
    private void save() {
        PriceSpec spec = resolveSaveSpec();
        if (spec == null) {
            if (!this.skia) {
                this.fallbackError = ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.parse_err",
                        "格式无效：%s", this.moneyBox == null ? "" : this.moneyBox.getValue());
            }
            return;
        }
        this.fallbackError = "";
        CompoundTag tag = new CompoundTag();
        tag.putLong("day", day());
        tag.put("moneySpec", spec.toTag());
        tag.putString("money", spec.toRuleText());
        tag.putString("action", "admin_signin_reward_save");
        ServerboundShopActionPayload.send(tag);
        finish(true);
    }

    private void finish(boolean saved) {
        if (!saved) {
            CompoundTag tag = new CompoundTag();
            tag.putString("action", "admin_signin_reward_cancel");
            ServerboundShopActionPayload.send(tag);
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
        }
        Screen target = returnTo;
        returnTo = null;
        if (this.minecraft != null) {
            this.minecraft.setScreen(target);
        }
    }

    @Override
    public void onClose() {
        finish(false);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        if (this.skia) {
            return;
        }
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                Bricks.slot(g, x + 8 + col * 18, y + 18 + row * 18);
            }
        }
        g.blit(Bricks.PLAYER_SLOTS, x + 7, y + GrantMenu.INVENTORY_Y - 1, 0, 0, 162, 76, 162, 76);
        small(g, x + 8, y + 80, Component.translatable("gui.module_shop.schedule.reward_items_hint"));
        g.fill(x + LIST_X - 3, y + 15, x + LIST_X + LIST_W + 3, y + 220, 0x30101114);
        IOStyle.text(g, this.font, Component.translatable("gui.module_shop.schedule.reward_items_title",
                LocalDate.ofEpochDay(day()).toString()), x + 8, y + 6, IOStyle.TEXT);
        small(g, x + LIST_X, y + 22, Component.translatable("gui.module_shop.schedule.reward_current"));
        drawWrapped(g, x + LIST_X, y + 34, LIST_W,
                Component.translatable("gui.module_shop.schedule.reward_money_line",
                        PriceDisplay.formatPlain(currentMoneySpec())).getString(), IOStyle.ORANGE);
        drawWrapped(g, x + LIST_X, y + 54, LIST_W, currentItemsText(), IOStyle.TEXT_2);
        small(g, x + LIST_X, y + 112, Component.translatable("gui.module_shop.grant.amount_label"));
        if (!this.fallbackError.isEmpty()) {
            drawWrapped(g, x + LIST_X, y + 144, LIST_W, this.fallbackError, IOStyle.RED);
        } else {
            drawWrapped(g, x + LIST_X, y + 144, LIST_W,
                    Component.translatable("gui.module_shop.schedule.reward_save_hint").getString(), IOStyle.TEXT_2);
        }
    }

    // ------------------------------------------------------------------ Skija

    private boolean skia;

    private void skBg(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float dt = this.lastFrameNs == 0L ? 16.0F : Math.min(100.0F, (now - this.lastFrameNs) / 1_000_000.0F);
        this.lastFrameNs = now;

        this.currencyItems.begin(mouseX, mouseY, true);
        SkiaFrame.draw(g, c -> {
            SkiaDialog.bare(c, x, y, PANEL_W, PANEL_H);
            Text.drawLine(c, Component.translatable(
                            "gui.module_shop.schedule.reward_items_title", LocalDate.ofEpochDay(day()).toString())
                            .getString(), AtomUi.BOLD, Text.BODY, IOStyle.TEXT, x + 8, y + 9, 166, Text.Align.LEFT);
            for (int i = 0; i < 27; i++) {
                SkiaDialog.slotFrame(c, x + 8 + (i % 9) * 18, y + 18 + (i / 9) * 18);
            }
            SkiaDialog.paragraph(c, Component.translatable("gui.module_shop.schedule.reward_items_hint").getString(),
                    x + 8, y + 78, 164, Text.SMALL, IOStyle.TEXT_2, 3, false);
            SkiaDialog.small(c, this.playerInventoryTitle.getString(), x + 8, y + GrantMenu.INVENTORY_Y - 6, 160,
                    IOStyle.TEXT_3);
            SkiaDialog.playerSlots(c, x + 8, y + GrantMenu.INVENTORY_Y);

            float rx = x + LIST_X;
            SkiaDialog.card(c, rx - 4, y + 14, LIST_W + 8, 210);
            SkiaDialog.small(c, Component.translatable("gui.module_shop.schedule.reward_current").getString(),
                    rx, y + 23, LIST_W, IOStyle.TEXT_2);
            float ty = y + 32;
            ty += SkiaDialog.paragraph(c, Component.translatable("gui.module_shop.schedule.reward_money_line",
                            PriceDisplay.formatPlain(currentMoneySpec())).getString(),
                    rx, ty, LIST_W, Text.SMALL, IOStyle.ORANGE, 2, false) + 2;
            ty += SkiaDialog.paragraph(c, currentItemsText(), rx, ty, LIST_W,
                    Text.SMALL, IOStyle.TEXT_2, 2, false) + 4;

            float labelY = Math.max(y + 66.0F, ty);
            SkiaDialog.small(c, Component.translatable("gui.module_shop.grant.amount_label").getString(),
                    rx, labelY, LIST_W, IOStyle.TEXT_2);
            this.edX = rx;
            this.edY = labelY + 8.0F;
            this.edW = LIST_W;
            this.moneyEditor.visible(true);
            this.moneyEditor.bounds(this.edX, this.edY, this.edW);
            this.edH = this.moneyEditor.preferredHeight();
            this.moneyEditor.render(c, this.currencyItems, mouseX, mouseY, dt);

            String edErr = (this.moneyEditor.mode() == PriceSpec.CurrencyMode.STAR_ONLY
                    && this.moneyEditor.starField().value().isBlank()) ? null : this.moneyEditor.validateError();
            float hintY = this.edY + this.edH + 5.0F;
            int hintLines = Math.max(1, (int) ((y + 194.0F - hintY) / 9.0F));
            if (y + 194.0F - hintY >= 8.0F) {
                if (edErr != null) {
                    SkiaDialog.paragraph(c, edErr, rx, hintY, LIST_W,
                            Text.SMALL, IOStyle.RED, Math.min(2, hintLines), false);
                } else {
                    SkiaDialog.paragraph(c, Component.translatable("gui.module_shop.schedule.reward_save_hint").getString(),
                            rx, hintY, LIST_W, Text.SMALL, IOStyle.TEXT_3, Math.min(3, hintLines), false);
                }
            }

            SkiaDialog.flatButton(c, rx, y + 196, 106, 20, Component.translatable("gui.module_shop.schedule.save")
                    .getString(), Button.Kind.ACCENT, edErr == null, mouseX, mouseY);
            SkiaDialog.flatButton(c, rx + 112, y + 196, 76, 20,
                    Component.translatable("gui.module_shop.schedule.cancel").getString(),
                    Button.Kind.NORMAL, true, mouseX, mouseY);
        });
        this.currencyItems.flush(g, this.font);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        if (!this.skia) {
            super.renderLabels(g, mouseX, mouseY);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (this.skia) {
            if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
                this.renderTooltip(g, mouseX, mouseY);
            } else if (!this.currencyItems.renderTooltip(g, this.font, mouseX, mouseY)
                    && this.moneyEditor != null && this.moneyEditor.hoveredTooltip() != null) {
                g.renderTooltip(this.font, this.moneyEditor.hoveredTooltip(), mouseX, mouseY);
            }
        } else if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            this.renderTooltip(g, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.skia) {
            if (this.moneyEditor != null && this.moneyEditor.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (button == 0) {
                int x = this.leftPos;
                int y = this.topPos;
                if (IOStyle.inside(x + LIST_X, y + 196, 106, 20, mouseX, mouseY)) {
                    Bricks.click();
                    save();
                    return true;
                }
                if (IOStyle.inside(x + LIST_X + 112, y + 196, 76, 20, mouseX, mouseY)) {
                    Bricks.click();
                    finish(false);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.skia && this.moneyEditor != null
                && this.moneyEditor.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.skia && this.moneyEditor != null
                && this.moneyEditor.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.skia && this.moneyEditor != null
                && this.moneyEditor.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void drawWrapped(GuiGraphics g, int x, int y, int width, String text, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String candidate = line.toString() + ch;
            if (this.font.width(candidate) * 0.8F > width) {
                small(g, x, lineY, Component.literal(line.toString()), color);
                lineY += 10;
                line = new StringBuilder();
            }
            line.append(ch);
        }
        if (line.length() > 0) {
            small(g, x, lineY, Component.literal(line.toString()), color);
        }
    }

    private void small(GuiGraphics g, int x, int y, Component text) {
        small(g, x, y, text, IOStyle.TEXT_2);
    }

    private void small(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }
}
