package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.menu.DepositMenu;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** “放入物品”界面。放好物品后点确认，服务端会弹出数值编辑界面。 */
public class DepositScreen extends AbstractContainerScreen<DepositMenu> implements SkiaContainerBackground.Painter {
    /** AtomLib 可用时用 Skija 画底板、槽位框、标题与确认按钮；槽位里的物品仍由原版绘制。 */
    private boolean skia;
    private final Motion.Clock clock = new Motion.Clock();
    private final cn.autoforged.module_shop.client.ui.widget.Button confirmButton =
            new cn.autoforged.module_shop.client.ui.widget.Button(
                    cn.autoforged.module_shop.client.ui.widget.Button.Kind.ACCENT,
                    () -> Component.translatable("gui.module_shop.confirm").getString(), DepositScreen::sendConfirm);

    public DepositScreen(DepositMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    private static void sendConfirm() {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "deposit_confirm");
        ServerboundShopActionPayload.send(tag);
    }

    @Override
    protected void init() {
        super.init();
        this.skia = AtomUi.available();
        int px = (this.width - this.imageWidth) / 2;
        int py = (this.height - this.imageHeight) / 2;
        if (this.skia) {
            this.confirmButton.bounds(px + 62, py + 42, 52, 18);
            return;
        }
        this.addRenderableWidget(new ShopButton(px + 62, py + 43, 52, 16,
                Component.translatable("gui.module_shop.confirm"), b -> sendConfirm()));
    }

    @Override
    public void onClose() {
        // 交易中取消“放入物品”时回到交易界面，而不是直接关闭。
        boolean inTrade = !ClientShopData.tradeSelfName.isEmpty();
            Nav.backToShop();
        if (inTrade) {
            Nav.open(new TradeScreen());
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        if (this.skia) {
            return;   // Skija 底板在 paintSkiaBackground 里画（见 SkiaContainerBackground）
        }
        Bricks.panel(g, x, y, this.imageWidth, this.imageHeight);
        Bricks.slot(g, x + 80, y + 20);
        g.blit(Bricks.PLAYER_SLOTS, x + 7, y + 83, 0, 0, 162, 76, 162, 76);
    }

    @Override
    public boolean usesSkiaBackground() {
        return this.skia;
    }

    @Override
    public void paintSkiaBackground(GuiGraphics g, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        {
            float dt = this.clock.tick();
            SkiaFrame.draw(g, c -> {
                SkiaDialog.bare(c, x, y, this.imageWidth, this.imageHeight);
                Text.drawLine(c, this.title.getString(), AtomUi.BOLD, Text.BODY, IOStyle.TEXT, x + 8, y + 10,
                        this.imageWidth - 16, Text.Align.LEFT);
                SkiaDialog.slotFrame(c, x + 80, y + 20);
                Text.drawLine(c, this.playerInventoryTitle.getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                        x + 8, y + this.inventoryLabelY + 4, 160, Text.Align.LEFT);
                SkiaDialog.playerSlots(c, x + 8, y + 84);
                this.confirmButton.render(c, mouseX, mouseY, dt);
            });
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        if (!this.skia) {
            super.renderLabels(g, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.skia && this.confirmButton.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.skia && this.confirmButton.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
