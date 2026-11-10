package cn.hsc.shoputil.client;

import cn.hsc.shoputil.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class PasswordScreen extends Screen {

    private final Screen parent;
    private EditBox passwordBox;
    private String errorMsg = "";

    public PasswordScreen(Screen parent) {
        super(Component.literal("解锁管理员功能"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        passwordBox = new EditBox(this.font, cx - 100, cy - 20, 200, 20, Component.literal("密码"));
        passwordBox.setMaxLength(32);
        this.addRenderableWidget(passwordBox);
        this.addRenderableWidget(Button.builder(Component.literal("确认"), b -> onConfirm())
                .bounds(cx - 100, cy + 10, 90, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(cx + 10, cy + 10, 90, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (cn.hsc.shoputil.ShopUtilConfig.UI_STYLE.get() == cn.hsc.shoputil.ShopUtilConfig.UiStyle.OPAQUE) {
            g.fill(0, 0, this.width, this.height, 0xFF101018);
        } else {
            super.renderBackground(g, mouseX, mouseY, partial);
        }
    }

    private void onConfirm() {
        String input = passwordBox.getValue().trim();
        String correct = GuardState.getPassword();
        if (input.equals(correct)) {
            GuardState.unlocked = true;
            Log.info("密码正确，已解锁");
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§a[商店守卫] 解锁成功，管理员功能已开启"), false);
            }
            mc.setScreen(null);
        } else {
            errorMsg = "密码错误";
            passwordBox.setValue("");
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.drawCenteredString(this.font, this.title, cx, cy - 50, 0xFFFFFFFF);
        if (!errorMsg.isEmpty()) {
            g.drawCenteredString(this.font, errorMsg, cx, cy - 35, 0xFFFF5555);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
