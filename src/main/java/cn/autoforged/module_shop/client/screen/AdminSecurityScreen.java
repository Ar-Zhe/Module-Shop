package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import io.github.humbleui.skija.Canvas;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 管理密码锁设置弹窗（问题 #5）：
 * <ul>
 *     <li>支持开启 / 关闭管理密码锁（开启后写入 {@code config/<DIR>/admin_auth.json} 的加盐哈希）；</li>
 *     <li>修改密码、调节会话滑动超时（不限 / 15 / 30 / 60 / 120 分钟）；</li>
 *     <li>已开启状态下，修改密码或关闭锁均要求校验当前密码；并提供「立即锁定」按钮方便临时离开。</li>
 * </ul>
 */
public class AdminSecurityScreen extends SkiaDialog {
    private static final float PANEL_W = 352.0F;
    private static final float PANEL_H = 248.0F;
    private static final int[] TIMEOUT_OPTIONS = {0, 15, 30, 60, 120};

    @SuppressWarnings("unused")
    private final @Nullable Screen parent;

    private SkiaTextField currentField;
    private SkiaTextField newField;
    private SkiaTextField confirmField;

    private boolean wantEnabled;
    private int timeoutMinutes;
    private boolean showPassword;
    private boolean saving;
    private String statusText = "";
    private int statusColor = IOStyle.TEXT_2;
    private int[] fbBackBtn;

    public AdminSecurityScreen(@Nullable Screen parent) {
        super(Component.literal(tr("gui.module_shop.auth.security_title", "管理密码锁设置")));
        this.parent = parent;
        this.wantEnabled = ClientShopData.adminLockEnabled;
        this.timeoutMinutes = Math.max(0, ClientShopData.adminSessionTimeout);
    }

    private static String tr(String key, String fallback, Object... args) {
        return ClientHomeCurrencyState.tr(key, fallback, args);
    }

    @Override
    public void onClose() {
        clearSensitiveFields();
        Nav.back();
    }

    @Override
    public void removed() {
        clearSensitiveFields();
        super.removed();
    }

    private void clearSensitiveFields() {
        if (this.currentField != null) {
            this.currentField.setValue("");
        }
        if (this.newField != null) {
            this.newField.setValue("");
        }
        if (this.confirmField != null) {
            this.confirmField.setValue("");
        }
    }

    @Override
    protected boolean onEnter() {
        saveSecurityConfig();
        return true;
    }

    @Override
    protected void initSkia() {
        this.currentField = field(64, tr("gui.module_shop.auth.ph_current", "输入当前管理密码以验证身份"))
                .setPasswordMode(!this.showPassword)
                .fontSize(Text.SMALL);
        this.newField = field(64, ClientShopData.adminLockEnabled
                        ? tr("gui.module_shop.auth.ph_new_keep", "留空表示沿用原密码（至少 4 位）")
                        : tr("gui.module_shop.auth.ph_new", "设置新管理密码（至少 4 位）"))
                .setPasswordMode(!this.showPassword)
                .fontSize(Text.SMALL);
        this.confirmField = field(64, tr("gui.module_shop.auth.ph_confirm", "再次输入新密码确认"))
                .setPasswordMode(!this.showPassword)
                .fontSize(Text.SMALL);

        if (ClientShopData.adminLockEnabled) {
            this.currentField.focus();
        } else {
            this.newField.focus();
        }
    }

    public void onServerResult(boolean ok, String message) {
        this.saving = false;
        this.statusColor = ok ? IOStyle.GREEN : IOStyle.RED;
        this.statusText = message;
        if (ok) {
            this.wantEnabled = ClientShopData.adminLockEnabled;
            this.timeoutMinutes = ClientShopData.adminSessionTimeout;
            clearSensitiveFields();
            if (this.newField != null) {
                this.newField.placeholder(ClientShopData.adminLockEnabled
                        ? tr("gui.module_shop.auth.ph_new_keep", "留空表示沿用原密码（至少 4 位）")
                        : tr("gui.module_shop.auth.ph_new", "设置新管理密码（至少 4 位）"));
            }
        }
    }

    private void toggleShowPasswords() {
        this.showPassword = !this.showPassword;
        if (this.currentField != null) {
            this.currentField.setPasswordMode(!this.showPassword);
        }
        if (this.newField != null) {
            this.newField.setPasswordMode(!this.showPassword);
        }
        if (this.confirmField != null) {
            this.confirmField.setPasswordMode(!this.showPassword);
        }
    }

    private void saveSecurityConfig() {
        String currentPwd = this.currentField != null ? this.currentField.value() : "";
        String newPwd = this.newField != null ? this.newField.value() : "";
        String confirmPwd = this.confirmField != null ? this.confirmField.value() : "";

        boolean currentlyEnabled = ClientShopData.adminLockEnabled;
        if (currentlyEnabled && currentPwd.isEmpty()) {
            this.statusColor = IOStyle.RED;
            this.statusText = tr("gui.module_shop.auth.err_need_current", "当前已开启密码保护，必须先填写当前密码");
            if (this.currentField != null) {
                this.currentField.focus();
            }
            return;
        }
        if (this.wantEnabled) {
            if (!currentlyEnabled && newPwd.length() < 4) {
                this.statusColor = IOStyle.RED;
                this.statusText = tr("gui.module_shop.auth.err_short", "密码长度不能少于 4 位");
                if (this.newField != null) {
                    this.newField.focus();
                }
                return;
            }
            if (!newPwd.isEmpty() && newPwd.length() < 4) {
                this.statusColor = IOStyle.RED;
                this.statusText = tr("gui.module_shop.auth.err_short", "密码长度不能少于 4 位");
                if (this.newField != null) {
                    this.newField.focus();
                }
                return;
            }
            if (!newPwd.equals(confirmPwd)) {
                this.statusColor = IOStyle.RED;
                this.statusText = tr("gui.module_shop.auth.err_mismatch", "两次输入的新密码不一致");
                if (this.confirmField != null) {
                    this.confirmField.focus();
                }
                return;
            }
        }

        this.saving = true;
        this.statusColor = IOStyle.TEXT_2;
        this.statusText = tr("gui.module_shop.auth.saving", "正在保存密码锁配置…");
        ClientAdminAuth.sendConfigure(this.wantEnabled, currentPwd, newPwd, this.timeoutMinutes);
    }

    @Override
    protected void paint(Canvas canvas) {
        float w = Math.max(290.0F, Math.min(PANEL_W, this.width - 16.0F));
        float h = Math.max(210.0F, Math.min(PANEL_H, this.height - 16.0F));
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
        float cy = y + HEADER_H + 8.0F;

        // ---- 状态摘要卡片 ----
        float statusCardH = 26.0F;
        card(canvas, ix, cy, iw, statusCardH);
        boolean lockedOn = ClientShopData.adminLockEnabled;
        boolean unlockedNow = ClientShopData.adminUnlocked;
        String stateLabel = !lockedOn
                ? tr("gui.module_shop.auth.state_off", "当前状态：未开启（所有 OP 可直接修改商店）")
                : (unlockedNow
                        ? tr("gui.module_shop.auth.state_unlocked", "当前状态：已开启 · 本次会话已解锁")
                        : tr("gui.module_shop.auth.state_locked", "当前状态：已开启 · 未解锁"));
        int stateColor = !lockedOn ? IOStyle.TEXT_2 : (unlockedNow ? IOStyle.GREEN : IOStyle.ORANGE);
        float lockBtnW = (lockedOn && unlockedNow) ? 62.0F : 0.0F;
        small(canvas, stateLabel, ix + 8.0F, cy + statusCardH * 0.5F, iw - 16.0F - lockBtnW, stateColor);
        if (lockedOn && unlockedNow) {
            button(canvas, "lock_now", tr("gui.module_shop.auth.lock_now", "立即锁定"),
                    ix + iw - lockBtnW - 4.0F, cy + 4.0F, lockBtnW, 18.0F, Button.Kind.DANGER, true, () -> {
                        ClientAdminAuth.sendLock();
                        onClose();
                    });
        }
        cy += statusCardH + 8.0F;

        // ---- 开关行 ----
        toggle(canvas, "enable_lock", ix, cy + 1.0F, this.wantEnabled, () -> {
            this.wantEnabled = !this.wantEnabled;
            this.statusText = "";
        });
        Text.drawLine(canvas, tr("gui.module_shop.auth.enable_label", "开启管理密码锁"),
                AtomUi.BOLD, Text.BODY, IOStyle.TEXT, ix + 28.0F, cy + 7.0F, iw - 82.0F, Text.Align.LEFT);
        chip(canvas, "show_pwd",
                this.showPassword
                        ? tr("gui.module_shop.auth.hide", "隐藏")
                        : tr("gui.module_shop.auth.show", "显示"),
                ix + iw - 44.0F, cy, 44.0F, 14.0F, this.showPassword, IOStyle.BLUE, this::toggleShowPasswords);
        cy += 18.0F;

        // ---- 当前密码（仅已开启时显示） ----
        if (lockedOn) {
            small(canvas, tr("gui.module_shop.auth.current_password", "当前密码"), ix, cy + 4.0F, iw, IOStyle.TEXT_2);
            cy += 9.0F;
            this.currentField.bounds(ix, cy, iw, 16.0F).visible(true);
            cy += 21.0F;
        } else {
            this.currentField.visible(false);
        }

        // ---- 新密码 + 确认新密码 ----
        if (this.wantEnabled) {
            float halfGap = 8.0F;
            float halfW = (iw - halfGap) * 0.5F;
            small(canvas, tr("gui.module_shop.auth.new_password", "新密码"), ix, cy + 4.0F, halfW, IOStyle.TEXT_2);
            small(canvas, tr("gui.module_shop.auth.confirm_password", "确认新密码"),
                    ix + halfW + halfGap, cy + 4.0F, halfW, IOStyle.TEXT_2);
            cy += 9.0F;
            this.newField.bounds(ix, cy, halfW, 16.0F).visible(true);
            this.confirmField.bounds(ix + halfW + halfGap, cy, halfW, 16.0F).visible(true);
            cy += 22.0F;

            // ---- 会话超时选项 ----
            small(canvas, tr("gui.module_shop.auth.timeout_label", "解锁会话超时（不操作自动重新锁定）："),
                    ix, cy + 4.0F, iw, IOStyle.TEXT_2);
            cy += 11.0F;
            float cGap = 4.0F;
            float cw = (iw - cGap * (TIMEOUT_OPTIONS.length - 1)) / TIMEOUT_OPTIONS.length;
            for (int mins : TIMEOUT_OPTIONS) {
                int idx = java.util.Arrays.binarySearch(TIMEOUT_OPTIONS, mins);
                float cx = ix + idx * (cw + cGap);
                String lbl = mins == 0
                        ? tr("gui.module_shop.auth.timeout_session", "下线前有效")
                        : tr("gui.module_shop.auth.timeout_min", "%s 分钟", mins);
                chip(canvas, "ttl_" + mins, lbl, cx, cy, cw, 16.0F,
                        this.timeoutMinutes == mins, IOStyle.BLUE, () -> this.timeoutMinutes = mins);
            }
            cy += 21.0F;
        } else {
            this.newField.visible(false);
            this.confirmField.visible(false);
            small(canvas, tr("gui.module_shop.auth.disable_hint",
                            "关闭密码锁后，OP 玩家与指令将恢复直接修改商店配置"),
                    ix, cy + 8.0F, iw, IOStyle.TEXT_3);
        }

        // ---- 提示 + 底部保存 ----
        float footBtnH = 18.0F;
        float footBtnY = y + h - PAD - footBtnH;
        String footerMsg = !this.statusText.isEmpty()
                ? this.statusText
                : tr("gui.module_shop.auth.modpack_tip",
                        "提示：仅以加盐 PBKDF2 哈希存盘，整合包导出可勾选携带密码锁；专用服控制台支持 /shop auth disable");
        small(canvas, footerMsg, ix, footBtnY - 7.0F, iw,
                !this.statusText.isEmpty() ? this.statusColor : IOStyle.TEXT_3);

        float saveW = 86.0F;
        float cancelW = 62.0F;
        button(canvas, "cancel", tr("gui.module_shop.cancel", "取消"),
                ix + iw - saveW - 6.0F - cancelW, footBtnY, cancelW, footBtnH, Button.Kind.NORMAL, true, this::onClose);
        button(canvas, "save", tr("gui.module_shop.save", "保存设置"),
                ix + iw - saveW, footBtnY, saveW, footBtnH, Button.Kind.ACCENT, !this.saving, this::saveSecurityConfig);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
        int pw = 280;
        int ph = 110;
        int x = (this.width - pw) / 2;
        int y = (this.height - ph) / 2;
        Bricks.panel(g, x, y, pw, ph);
        IOStyle.centerAt(g, this.font, this.title, x + pw / 2, y + 14, IOStyle.ORANGE);
        IOStyle.centerAt(g, this.font,
                Component.literal(tr("gui.module_shop.ann_admin.no_skia", "请在支持 Skija 的客户端环境中使用可视化配置界面")),
                x + pw / 2, y + 46, IOStyle.TEXT_2);
        int bx = x + (pw - 80) / 2;
        int by = y + ph - 30;
        IOStyle.button(g, bx, by, 80, 18, Component.translatable("gui.module_shop.cfg.back_btn"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fbBackBtn = new int[]{bx, by, 80, 18};
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            return skiaClicked(mouseX, mouseY, button);
        }
        if (button == 0 && this.fbBackBtn != null && IOStyle.inside(
                this.fbBackBtn[0], this.fbBackBtn[1], this.fbBackBtn[2], this.fbBackBtn[3], mouseX, mouseY)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
