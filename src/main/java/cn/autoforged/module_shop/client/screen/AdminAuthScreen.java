package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import io.github.humbleui.skija.Canvas;

/**
 * 管理员密码解锁弹窗（问题 #5）：
 * <ul>
 *     <li>通过 {@link SkiaTextField#setPasswordMode(boolean)} 掩码输入（支持切换显隐），绝不在客户端保存明文；</li>
 *     <li>支持暴力破解冷却倒计时提示与回车快速提交；</li>
 *     <li>解锁成功后关闭本弹窗并续接 {@link ClientAdminAuth} 或构造时传入的 {@code onSuccess} 回调。</li>
 * </ul>
 */
public class AdminAuthScreen extends SkiaDialog {
    private static final float PANEL_W = 282.0F;
    private static final float PANEL_H = 148.0F;

    private final @Nullable Screen parent;
    private final @Nullable Runnable onSuccess;

    private SkiaTextField passwordField;
    private EditBox fallbackBox;
    private boolean showPassword;
    private boolean submitting;
    private String statusText = "";
    private int statusColor = IOStyle.TEXT_2;

    private int[] fbSubmitBtn;
    private int[] fbCancelBtn;

    public AdminAuthScreen(@Nullable Screen parent, @Nullable Runnable onSuccess) {
        super(Component.literal(tr("gui.module_shop.auth.unlock_title", "输入管理密码")));
        this.parent = parent;
        this.onSuccess = onSuccess;
        String notice = ClientAdminAuth.consumeLastNotice();
        if (!notice.isEmpty()) {
            this.statusText = notice;
            this.statusColor = IOStyle.ORANGE;
        }
    }

    private static String tr(String key, String fallback, Object... args) {
        return ClientHomeCurrencyState.tr(key, fallback, args);
    }

    @Override
    public void onClose() {
        clearSensitiveInputs();
        Minecraft mc = Minecraft.getInstance();
        if (this.parent == null || Nav.isChatScreen(this.parent)) {
            if (mc != null) {
                mc.setScreen(null);
            }
            return;
        }
        Nav.back();
    }

    @Override
    public void removed() {
        clearSensitiveInputs();
        super.removed();
    }

    private void clearSensitiveInputs() {
        if (this.passwordField != null) {
            this.passwordField.setValue("");
        }
        if (this.fallbackBox != null) {
            this.fallbackBox.setValue("");
        }
    }

    @Override
    protected boolean onEnter() {
        submitUnlock();
        return true;
    }

    @Override
    protected void initSkia() {
        this.passwordField = field(64, tr("gui.module_shop.auth.password", "管理密码"))
                .setPasswordMode(!this.showPassword)
                .fontSize(Text.BODY);
        this.passwordField.focus();
    }

    @Override
    protected void init() {
        if (skiaInit()) {
            return;
        }
        int pw = 246;
        int ph = 118;
        int x = (this.width - pw) / 2;
        int y = (this.height - ph) / 2;
        this.fallbackBox = new EditBox(this.font, x + 14, y + 48, pw - 28, 18, Component.empty());
        this.fallbackBox.setMaxLength(64);
        this.fallbackBox.setFormatter((text, offset) ->
                FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
        this.addRenderableWidget(this.fallbackBox);
        this.setInitialFocus(this.fallbackBox);
    }

    public void onServerSuccess() {
        this.submitting = false;
        Runnable cb = this.onSuccess;
        onClose();
        if (cb != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(cb);
            } else {
                cb.run();
            }
        }
    }

    public void onServerError(String message, int retryAfterSeconds) {
        this.submitting = false;
        this.statusColor = IOStyle.RED;
        this.statusText = retryAfterSeconds > 0
                ? tr("msg.module_shop.auth_cooldown", "尝试次数过多，请等待 %s 秒后重试", retryAfterSeconds)
                : message;
        clearSensitiveInputs();
        if (this.passwordField != null) {
            this.passwordField.focus();
        }
    }

    public void refreshHint(String message, int retryAfterSeconds) {
        this.submitting = false;
        this.statusColor = retryAfterSeconds > 0 ? IOStyle.RED : IOStyle.ORANGE;
        this.statusText = message;
    }

    private void submitUnlock() {
        int cd = ClientAdminAuth.cooldownRemainingSeconds();
        if (cd > 0) {
            this.statusColor = IOStyle.RED;
            this.statusText = tr("msg.module_shop.auth_cooldown", "尝试次数过多，请等待 %s 秒后重试", cd);
            return;
        }
        String pwd = this.passwordField != null ? this.passwordField.value()
                : (this.fallbackBox != null ? this.fallbackBox.getValue() : "");
        if (pwd == null || pwd.isEmpty()) {
            this.statusColor = IOStyle.ORANGE;
            this.statusText = tr("gui.module_shop.auth.enter_prompt", "请输入管理密码");
            return;
        }
        this.submitting = true;
        this.statusColor = IOStyle.TEXT_2;
        this.statusText = tr("gui.module_shop.auth.verifying", "正在校验管理密码…");
        ClientAdminAuth.sendUnlock(pwd);
    }

    private void toggleShowPassword() {
        this.showPassword = !this.showPassword;
        if (this.passwordField != null) {
            this.passwordField.setPasswordMode(!this.showPassword);
        }
    }

    @Override
    protected void paint(Canvas canvas) {
        float w = Math.max(240.0F, Math.min(PANEL_W, this.width - 16.0F));
        float h = Math.max(134.0F, Math.min(PANEL_H, this.height - 16.0F));
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);

        panel(canvas, x, y, w, h, this.title.getString());

        float closeX = x + w - PAD - 8.0F;
        float closeY = y + HEADER_H * 0.5F;
        float chv = hover("close", closeX - 6.0F, closeY - 6.0F, 12.0F, 12.0F);
        Draw.cross(canvas, closeX, closeY, 3.0F, 1.1F, Draw.lerp(IOStyle.TEXT_2, IOStyle.RED, chv));
        hit(closeX - 6.0F, closeY - 6.0F, 12.0F, 12.0F, this::onClose, null);

        float ix = x + PAD;
        float iw = w - PAD * 2.0F;
        float cy = y + HEADER_H + 10.0F;

        small(canvas, tr("gui.module_shop.auth.unlock_desc",
                        "已开启商店配置保护，解锁后可修改配置与使用管理指令"),
                ix, cy + 5.0F, iw, IOStyle.TEXT_2);
        cy += 18.0F;

        small(canvas, tr("gui.module_shop.auth.password", "管理密码"), ix, cy + 4.0F, iw, IOStyle.TEXT_2);
        cy += 10.0F;

        float toggleW = 40.0F;
        float fieldW = iw - toggleW - 6.0F;
        this.passwordField.bounds(ix, cy, fieldW, 18.0F).visible(true);
        chip(canvas, "eye",
                this.showPassword
                        ? tr("gui.module_shop.auth.hide", "隐藏")
                        : tr("gui.module_shop.auth.show", "显示"),
                ix + fieldW + 6.0F, cy, toggleW, 18.0F,
                this.showPassword, IOStyle.BLUE, this::toggleShowPassword);
        cy += 24.0F;

        int cd = ClientAdminAuth.cooldownRemainingSeconds();
        String msg = cd > 0
                ? tr("msg.module_shop.auth_cooldown", "尝试次数过多，请等待 %s 秒后重试", cd)
                : this.statusText;
        if (!msg.isEmpty()) {
            Text.drawLine(canvas, msg, AtomUi.REGULAR, Text.SMALL, cd > 0 ? IOStyle.RED : this.statusColor,
                    ix, cy + 5.0F, iw, Text.Align.LEFT);
        }

        float btnH = 18.0F;
        float btnY = y + h - PAD - btnH;
        float okW = 76.0F;
        float cancelW = 62.0F;
        button(canvas, "cancel", tr("gui.module_shop.cancel", "取消"),
                ix + iw - okW - 6.0F - cancelW, btnY, cancelW, btnH, Button.Kind.NORMAL, true, this::onClose);
        button(canvas, "unlock", tr("gui.module_shop.auth.unlock_btn", "解锁"),
                ix + iw - okW, btnY, okW, btnH, Button.Kind.ACCENT, !this.submitting && cd <= 0, this::submitUnlock);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
        int pw = 246;
        int ph = 118;
        int x = (this.width - pw) / 2;
        int y = (this.height - ph) / 2;
        Bricks.panel(g, x, y, pw, ph);
        IOStyle.centerAt(g, this.font, this.title, x + pw / 2, y + 14, IOStyle.ORANGE);
        g.drawString(this.font, Component.translatable("gui.module_shop.auth.password"),
                x + 14, y + 34, IOStyle.TEXT_2, false);
        if (!this.statusText.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.literal(this.statusText), x + pw / 2, y + 74, IOStyle.RED);
        }
        int bw = 72;
        int by = y + ph - 28;
        IOStyle.button(g, x + pw - 14 - bw * 2 - 8, by, bw, 18, Component.translatable("gui.module_shop.cancel"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fbCancelBtn = new int[]{x + pw - 14 - bw * 2 - 8, by, bw, 18};
        IOStyle.button(g, x + pw - 14 - bw, by, bw, 18,
                Component.literal(tr("gui.module_shop.auth.unlock_btn", "解锁")),
                this.font, mouseX, mouseY, !this.submitting, IOStyle.Btn.PRIMARY);
        this.fbSubmitBtn = new int[]{x + pw - 14 - bw, by, bw, 18};
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!skia() && (keyCode == 257 || keyCode == 335)) {
            submitUnlock();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            return skiaClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            if (this.fbCancelBtn != null && IOStyle.inside(
                    this.fbCancelBtn[0], this.fbCancelBtn[1], this.fbCancelBtn[2], this.fbCancelBtn[3], mouseX, mouseY)) {
                ClickSound.play();
                onClose();
                return true;
            }
            if (this.fbSubmitBtn != null && IOStyle.inside(
                    this.fbSubmitBtn[0], this.fbSubmitBtn[1], this.fbSubmitBtn[2], this.fbSubmitBtn[3], mouseX, mouseY)) {
                ClickSound.play();
                submitUnlock();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
