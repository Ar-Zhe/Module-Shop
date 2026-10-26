package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.client.ClientShopData;
import cn.autoforged.server_shop_mod_1789689358.menu.GrantMenu;
import cn.autoforged.server_shop_mod_1789689358.network.payload.ServerboundShopActionPayload;
import java.time.LocalDate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 日程奖励（第八轮）：**直接沿用「物品发放」的逻辑与版式** ——
 * 左侧 3×9 真实物品槽（从自己背包放入，服务端权威、关闭时原样退还），
 * 右侧显示该日程「当前奖励」并可填金额，底部「保存 / 取消」。
 * <p>
 * 与发放界面的区别只有两点：右侧不勾选玩家（日程奖励发放给所有签到/在线玩家），
 * 以及「发送」改成「保存」（写进该日程的奖励配置）。保存时服务端会先清空槽位再落库，
 * 所以既不会复制物品，也不会把已发放的物品退回管理员背包。
 */
public class ScheduleRewardItemsScreen extends AbstractContainerScreen<GrantMenu> {
    private static final int PANEL_W = 336;
    private static final int PANEL_H = 236;
    private static final int LIST_X = 182;
    private static final int LIST_W = 146;

    /** 关闭本界面后要回去的界面（由日程奖励设置界面在打开前设置）。 */
    private static Screen returnTo;
    private EditBox moneyBox;

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

    private long currentMoney() {
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day()) {
                return view.money;
            }
        }
        return 0L;
    }

    private String currentItemsText() {
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day()) {
                return view.itemText == null || view.itemText.isEmpty() ? "-" : view.itemText;
            }
        }
        return "-";
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos;
        int y = this.topPos;
        this.moneyBox = new EditBox(this.font, x + LIST_X + 1, y + 122, LIST_W - 2, 16,
                Component.translatable("gui.server_shop_mod.grant.amount"));
        this.moneyBox.setMaxLength(12);
        this.moneyBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,12}"));
        this.moneyBox.setHint(Component.translatable("gui.server_shop_mod.grant.amount_hint"));
        this.moneyBox.setValue(Long.toString(Math.max(0L, currentMoney())));
        this.addRenderableWidget(this.moneyBox);

        int btnY = y + 178;
        this.addRenderableWidget(new ShopButton(x + LIST_X, btnY, 86, 20,
                Component.translatable("gui.server_shop_mod.schedule.save"), b -> save()));
        this.addRenderableWidget(new ShopButton(x + LIST_X + 90, btnY, 56, 20,
                Component.translatable("gui.server_shop_mod.schedule.cancel"), b -> finish(false)));
    }

    private long amount() {
        try {
            return Math.max(0L, Long.parseLong(this.moneyBox.getValue().trim()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 保存：把槽内物品 + 金额写进该日程（服务端读取权威槽位，客户端只传日期与金额）。 */
    private void save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("day", day());
        tag.putString("money", Long.toString(amount()));
        tag.putString("action", "admin_signin_reward_save");
        ServerboundShopActionPayload.send(tag);
        finish(true);
    }

    /** 关闭界面：先通知服务端关闭容器（槽内物品原样退还），再回到上一级界面。 */
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
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        // 左侧：3×9 发放槽（与物品发放完全一致）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                Bricks.slot(g, x + 8 + col * 18, y + 18 + row * 18);
            }
        }
        g.blit(Bricks.PLAYER_SLOTS, x + 7, y + GrantMenu.INVENTORY_Y - 1, 0, 0, 162, 76, 162, 76);
        // 左侧提示：这里是「要发放的物品」
        small(g, x + 8, y + 80, Component.translatable("gui.server_shop_mod.schedule.reward_items_hint"));
        // 右侧栏（沿用发放界面右侧深色面板）
        g.fill(x + LIST_X - 3, y + 15, x + LIST_X + LIST_W + 3, y + 205, 0x30101114);
        IOStyle.text(g, this.font, Component.translatable("gui.server_shop_mod.schedule.reward_items_title",
                LocalDate.ofEpochDay(day()).toString()), x + 8, y + 6, IOStyle.TEXT);
        small(g, x + LIST_X, y + 22, Component.translatable("gui.server_shop_mod.schedule.reward_current"));
        drawWrapped(g, x + LIST_X, y + 34, LIST_W,
                Component.translatable("gui.server_shop_mod.schedule.reward_money_line",
                        ClientShopData.money(currentMoney())).getString(), IOStyle.ORANGE);
        drawWrapped(g, x + LIST_X, y + 46, LIST_W, currentItemsText(), IOStyle.TEXT_2);
        small(g, x + LIST_X, y + 112, Component.translatable("gui.server_shop_mod.grant.amount_label"));
        small(g, x + LIST_X, y + 140, Component.translatable("gui.server_shop_mod.schedule.reward_save_hint"));
    }

    /** 0.8 倍小字号，带自动换行（右侧栏只有 146px 宽）。 */
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
