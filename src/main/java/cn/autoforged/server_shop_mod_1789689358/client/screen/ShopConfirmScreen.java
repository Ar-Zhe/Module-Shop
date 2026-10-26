package cn.autoforged.server_shop_mod_1789689358.client.screen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 商店风格的确认框（替代原版 {@code ConfirmScreen}）。
 * <p>
 * 用模组自己的面板与按钮，因此点「是 / 否」响的是**模组统一点击音**（原版确认框会响原版按钮音）。
 * 关闭后通过 {@link Nav#back()} 回到上一级界面。
 */
public class ShopConfirmScreen extends Screen {
    /** 结果回调：true = 确定，false = 取消 / ESC。 */
    public interface Callback {
        void accept(boolean confirmed);
    }

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 124;
    private static final int BTN_W = 80;
    private static final int BTN_H = 22;

    private final Component title;
    private final Component message;
    private final Component yesLabel;
    private final Component noLabel;
    private final Callback callback;
    private final List<int[]> buttons = new ArrayList<>();
    private int pointerX;
    private int pointerY;

    public ShopConfirmScreen(Component title, Component message, Callback callback) {
        this(title, message, null, null, callback);
    }

    /**
     * 自定义两个按钮的文字（例如求购详情里的「交付 / 取消」）。
     * 传 {@code null} 就用默认的「是 / 否」。
     */
    public ShopConfirmScreen(Component title, Component message, Component yesLabel, Component noLabel,
            Callback callback) {
        super(title);
        this.title = title;
        this.message = message;
        this.yesLabel = yesLabel;
        this.noLabel = noLabel;
        this.callback = callback;
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        finish(false);
    }

    /** 结算并回到上一级界面。 */
    private void finish(boolean confirmed) {
        if (this.callback != null) {
            this.callback.accept(confirmed);
        }
        Nav.back();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.buttons.clear();
        this.pointerX = mouseX;
        this.pointerY = mouseY;
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, this.title, x + PANEL_W / 2, y + 12, IOStyle.ORANGE);
        drawWrapped(g, x + 16, y + 36, PANEL_W - 32, this.message.getString(), IOStyle.TEXT);
        int by = y + PANEL_H - 32;
        int total = BTN_W * 2 + 12;
        int bx = x + (PANEL_W - total) / 2;
        IOStyle.button(g, bx, by, BTN_W, BTN_H,
                this.yesLabel == null ? Component.translatable("gui.server_shop_mod.yes") : this.yesLabel,
                this.font, mouseX, mouseY, true, IOStyle.Btn.PRIMARY);
        this.buttons.add(new int[]{bx, by, BTN_W, BTN_H, 1});
        IOStyle.button(g, bx + BTN_W + 12, by, BTN_W, BTN_H,
                this.noLabel == null ? Component.translatable("gui.server_shop_mod.no") : this.noLabel,
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.buttons.add(new int[]{bx + BTN_W + 12, by, BTN_W, BTN_H, 0});
    }

    /** 0.8 倍小字居中换行绘制正文。 */
    private void drawWrapped(GuiGraphics g, int x, int y, int width, String text, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                String candidate = line.toString() + paragraph.charAt(i);
                if (this.font.width(candidate) * 0.8F > width && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder();
                }
                line.append(paragraph.charAt(i));
            }
            lines.add(line.toString());
        }
        int lineY = y;
        for (String line : lines) {
            g.pose().pushPose();
            g.pose().translate(x + width / 2.0F, lineY, 0.0F);
            g.pose().scale(0.8F, 0.8F, 1.0F);
            g.drawString(this.font, line, -this.font.width(line) / 2, 0, color, true);
            g.pose().popPose();
            lineY += 11;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int[] rect : this.buttons) {
            if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                Bricks.click();
                finish(rect[4] == 1);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
