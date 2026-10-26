package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.menu.BatchAddMenu;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 管理页「添加条目」的批量界面（参考发放界面的容器样式，但槽位是**幽灵槽**）：
 * 上方 3×9 = 27 个格子可一次放入最多 27 种物品，右边是操作说明与已放入数量，
 * 点「确认」后弹出统一价格 / 品质 / 数量的窗口，保存即把这 27 种物品一次性加进当前模块。
 */
public class BatchAddScreen extends AbstractContainerScreen<BatchAddMenu> {
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 212;
    private static final int INFO_X = 180;

    public BatchAddScreen(BatchAddMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = BatchAddMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 8;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消 / ESC：回上一级（商店主界面的管理页）。 */
    @Override
    public void onClose() {
        super.onClose();
        Nav.backToShop();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        Bricks.panel(g, x, y, this.imageWidth, this.imageHeight);
        // 幽灵槽区域加一层淡底，和背包区分
        g.fill(x + 6, BatchAddMenu.SLOT_Y + y - 4, x + 170, y + BatchAddMenu.SLOT_Y + 54, 0x30101114);
        for (int i = 0; i < BatchAddMenu.GHOST_SLOTS; i++) {
            Bricks.slot(g, x + BatchAddMenu.SLOT_X + (i % 9) * 18, y + BatchAddMenu.SLOT_Y + (i / 9) * 18);
        }
        g.blit(Bricks.PLAYER_SLOTS, x + BatchAddMenu.SLOT_X - 1, y + BatchAddMenu.INVENTORY_Y - 1,
                0, 0, 162, 76, 162, 76);
        // 右侧说明卡
        IOStyle.card(g, x + INFO_X - 8, y + 24, PANEL_W - INFO_X - 2, PANEL_H - 60, 0);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = this.leftPos;
        int y = this.topPos;
        // 右侧说明（0.8 倍小字，逐行排，不重叠）
        int ty = y + 30;
        drawSmall(g, x + INFO_X, ty, Component.translatable("gui.module_shop.batch.count",
                getMenu().filled(), BatchAddMenu.GHOST_SLOTS), IOStyle.ORANGE);
        ty += 14;
        for (String key : new String[]{"gui.module_shop.batch.tip1", "gui.module_shop.batch.tip2",
                "gui.module_shop.batch.tip3"}) {
            drawSmall(g, x + INFO_X, ty, Component.translatable(key), IOStyle.TEXT_2);
            ty += 12;
        }
        // 按钮：确认 / 取消
        int by = y + BatchAddMenu.SLOT_Y + 62;
        boolean canConfirm = getMenu().filled() > 0;
        boolean hover = IOStyle.inside(x + 8, by, 78, 18, mouseX, mouseY);
        IOStyle.button(g, x + 8, by, 78, 18, Component.translatable("gui.module_shop.batch.confirm"),
                this.font, mouseX, mouseY, canConfirm, canConfirm ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
        IOStyle.button(g, x + 92, by, 78, 18, Component.translatable("gui.module_shop.cancel"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        if (!canConfirm && hover) {
            g.renderTooltip(this.font, Component.translatable("gui.module_shop.batch.empty"), mouseX, mouseY);
        }
        // 槽位悬停提示：必须画在最后（原版 AbstractContainerScreen 先画，会被自绘面板盖住）。
        if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            g.renderTooltip(this.font, this.hoveredSlot.getItem(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        IOStyle.text(g, this.font, Component.translatable("gui.module_shop.batch.title",
                ClientShopData.adminModeName()), 8, 6, IOStyle.TEXT);
        IOStyle.text(g, this.font, Component.translatable("gui.module_shop.batch.subtitle"), 8, 20,
                IOStyle.TEXT_3);
        IOStyle.text(g, this.font, Component.translatable("gui.module_shop.shipping.bag"),
                8, BatchAddMenu.INVENTORY_Y - 10, IOStyle.TEXT_3);
    }

    private void drawSmall(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.leftPos;
        int y = this.topPos;
        int by = y + BatchAddMenu.SLOT_Y + 62;
        if (button == 0 && IOStyle.inside(x + 8, by, 78, 18, mouseX, mouseY)) {
            Bricks.click();
            if (getMenu().filled() > 0) {
                openBatchDialog();
            }
            return true;
        }
        if (button == 0 && IOStyle.inside(x + 92, by, 78, 18, mouseX, mouseY)) {
            Bricks.click();
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 「确认」：交给服务端处理 —— 它把幽灵槽里的物品存成模板、关掉容器（原物退还），
     * 再打开统一设置价格 / 品质 / 数量的输入窗口（字段按当前模块决定）。
     */
    private void openBatchDialog() {
        CompoundTag req = new CompoundTag();
        req.putString("action", "admin_batch_prepare");
        ServerboundShopActionPayload.send(req);
    }
}
