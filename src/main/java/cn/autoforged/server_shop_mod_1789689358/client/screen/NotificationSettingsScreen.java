package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.client.ClickSound;
import cn.autoforged.server_shop_mod_1789689358.client.ClientNotifications;
import cn.autoforged.server_shop_mod_1789689358.data.NotificationType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 需求 10-5：提醒音效设置界面。
 * 顶部是整体提醒音效总开关，下面是各类型单独开关。
 * 界面点击音效（ui_click）不经过本开关，因此关闭提醒音效仍能听到界面点击声。
 */
public class NotificationSettingsScreen extends Screen {
    private static final int PANEL_W = 240;
    /** 比原来高一行：底部多了一行「界面点击音效」+ 一行提示，面板加高避免与返回按钮重叠。 */
    private static final int PANEL_H = 224;
    private static final int ROW_H = 20;
    private static final int COLOR_TEXT = IOStyle.TEXT;
    private static final int COLOR_MUTED = IOStyle.TEXT_2;
    private static final int COLOR_GOLD = IOStyle.ORANGE;
    private static final int COLOR_ON = 0xFF3FA34D;
    private static final int COLOR_OFF = 0xFF5A2A2A;

    private final List<Row> rows = new ArrayList<>();
    /** 点击音效那一行的命中区域（点击切换 + 试听）。 */
    private int[] clickRow;

    public NotificationSettingsScreen() {
        super(Component.translatable("gui.server_shop_mod.notify.settings_title"));
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.rows.clear();
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, this.title, x + PANEL_W / 2, y + 9, COLOR_GOLD);

        int rowY = y + 26;
        addRow(g, x, rowY, Component.translatable("gui.server_shop_mod.notify.master"), null);
        rowY += ROW_H;
        for (NotificationType type : NotificationType.values()) {
            addRow(g, x, rowY, Component.translatable("gui.server_shop_mod.notify." + type.id()), type);
            rowY += ROW_H;
        }
        // 界面点击音效：点一下换一款并当场试听（不受上面提醒音效开关影响）。
        this.clickRow = new int[]{x + 10, rowY, PANEL_W - 20, ROW_H - 3};
        g.fill(x + 10, rowY, x + PANEL_W - 10, rowY + ROW_H - 3, 0xFF1B1C1F);
        g.drawString(this.font, Component.translatable("gui.server_shop_mod.notify.clicksound"), x + 16, rowY + 5,
                COLOR_TEXT, false);
        String name = ClickSound.displayName().getString();
        IOStyle.centerAt(g, this.font, Component.literal(name), x + PANEL_W - 66, rowY + 5, COLOR_GOLD);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.server_shop_mod.notify.clicksound_hint"),
                x + PANEL_W / 2, rowY + ROW_H - 1, COLOR_MUTED);

        drawButton(g, x + 10, y + PANEL_H - 24, 70, 16, Component.translatable("gui.server_shop_mod.back"));
    }

    private void addRow(GuiGraphics g, int x, int y, Component label, NotificationType type) {
        g.fill(x + 10, y, x + PANEL_W - 10, y + ROW_H - 3, 0xFF1B1C1F);
        g.drawString(this.font, label, x + 16, y + 5, COLOR_TEXT, false);
        boolean on = type == null ? ClientNotifications.masterSound : ClientNotifications.typeSound(type);
        int bx = x + PANEL_W - 62;
        int by = y + 2;
        g.fill(bx, by, bx + 48, by + 13, on ? COLOR_ON : COLOR_OFF);
        g.fill(bx, by, bx + 48, by + 1, on ? 0xFF6FE07F : 0xFF8A4A4A);
        IOStyle.centerAt(g, this.font, on ? Component.translatable("gui.server_shop_mod.notify.on")
                : Component.translatable("gui.server_shop_mod.notify.off"), bx + 24, by + 3, COLOR_TEXT);
        this.rows.add(new Row(bx, by, 48, 13, type));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = left();
        int y = top();
        if (button == 0 && mouseX >= x + 10 && mouseX < x + 80
                && mouseY >= y + PANEL_H - 24 && mouseY < y + PANEL_H - 8) {
            Bricks.click();
            this.onClose();
            return true;
        }
        // 点击音效行：换下一款并当场试听（试听的就是新选中的那款）。
        if (button == 0 && this.clickRow != null && mouseX >= this.clickRow[0]
                && mouseX < this.clickRow[0] + this.clickRow[2] && mouseY >= this.clickRow[1]
                && mouseY < this.clickRow[1] + this.clickRow[3]) {
            ClickSound.next();
            Bricks.click();
            return true;
        }
        for (Row row : this.rows) {
            if (button == 0 && mouseX >= row.x && mouseX < row.x + row.w
                    && mouseY >= row.y && mouseY < row.y + row.h) {
                Bricks.click();
                if (row.type == null) {
                    ClientNotifications.masterSound = !ClientNotifications.masterSound;
                } else {
                    ClientNotifications.setTypeSound(row.type, !ClientNotifications.typeSound(row.type));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        // 返回主界面，不重新请求数据，保留当前页面上下文。
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ShopScreen("daily"));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label) {
        // 新 UI 皮肤：渐变底 + 内高光 + 悬停上浮 + 按下缩放 + 点击水波。
        double[] m = IOStyle.mouseGui();
        boolean hover = IOStyle.inside(x, y, w, h, m[0], m[1]);
        IOStyle.smallButton(g, x, y, w, h, label, this.font, hover, IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER,
                COLOR_TEXT, (int) Math.round(m[0]), (int) Math.round(m[1]));
    }

    private record Row(int x, int y, int w, int h, NotificationType type) {
    }
}
