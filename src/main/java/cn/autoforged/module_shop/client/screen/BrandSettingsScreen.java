package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.media.ClientImageUploader;
import cn.autoforged.module_shop.client.media.ClientMediaCache;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.SidebarLayout;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import com.atom.lib.design.atomchat.SkiaDraw;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.SamplingMode;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 品牌与图标设置弹窗（问题 #1）：
 * <ul>
 *     <li>支持腐竹上传自定义左上角商店图标（系统弹窗 / 窗口拖放 / {@code upload_inbox} 目录列表），自动缩放至 128×128 PNG；</li>
 *     <li>实时预览 32×32 与 13×13（顶栏实际尺寸）效果，支持一键恢复默认渐变图标；</li>
 *     <li>支持直接修改商店名称并提交持久化。</li>
 * </ul>
 */
public class BrandSettingsScreen extends SkiaDialog {
    private static final float PANEL_W = 356.0F;
    private static final float PANEL_H = 248.0F;

    @SuppressWarnings("unused")
    private final @Nullable Screen parent;
    private final ScrollController inboxScroll = new ScrollController();

    private SkiaTextField titleField;
    private EditBox fallbackTitleBox;

    private String pendingIconId;
    private String pendingTitle;
    private boolean uploading;
    private String statusText = "";
    private int statusColor = IOStyle.TEXT_2;

    private List<Path> inboxFiles = List.of();
    private long lastInboxRefreshMs;
    private ClientImageUploader.DropHandle dropHandle;

    private int[] fbSaveBtn;
    private int[] fbResetBtn;
    private int[] fbUploadBtn;
    private int[] fbBackBtn;

    public BrandSettingsScreen(@Nullable Screen parent) {
        super(Component.literal(tr("gui.module_shop.brand.title", "品牌与图标设置")));
        this.parent = parent;
        this.pendingIconId = ClientShopData.brandIconId == null ? "" : ClientShopData.brandIconId;
        this.pendingTitle = ClientShopData.shopTitle == null ? "" : ClientShopData.shopTitle;
    }

    private static String tr(String key, String fallback, Object... args) {
        return ClientHomeCurrencyState.tr(key, fallback, args);
    }

    @Override
    public void onClose() {
        uninstallDrop();
        Nav.back();
    }

    @Override
    public void removed() {
        uninstallDrop();
        super.removed();
    }

    @Override
    protected boolean onEnter() {
        if (this.titleField != null && this.titleField.isFocused()) {
            saveAndApply();
            return true;
        }
        return false;
    }

    @Override
    protected void initSkia() {
        installDrop();
        refreshInbox(true);
        this.titleField = field(SidebarLayout.MAX_TITLE,
                tr("gui.module_shop.brand.name_ph", "留空恢复默认「模块商店」")).fontSize(Text.BODY);
        this.titleField.setValue(this.pendingTitle);
        this.titleField.onChange(val -> this.pendingTitle = val == null ? "" : val);
        this.titleField.focus();
    }

    @Override
    protected void init() {
        if (skiaInit()) {
            return;
        }
        installDrop();
        int pw = 280;
        int ph = 156;
        int x = (this.width - pw) / 2;
        int y = (this.height - ph) / 2;
        this.fallbackTitleBox = new EditBox(this.font, x + 14, y + 54, pw - 28, 18, Component.empty());
        this.fallbackTitleBox.setMaxLength(SidebarLayout.MAX_TITLE);
        this.fallbackTitleBox.setValue(this.pendingTitle);
        this.fallbackTitleBox.setResponder(val -> this.pendingTitle = val == null ? "" : val);
        this.addRenderableWidget(this.fallbackTitleBox);
        this.setInitialFocus(this.fallbackTitleBox);
    }

    private void installDrop() {
        if (this.dropHandle != null) {
            return;
        }
        this.dropHandle = ClientImageUploader.installDropCallback(path ->
                Minecraft.getInstance().execute(() -> uploadIconFile(path)));
    }

    private void uninstallDrop() {
        if (this.dropHandle != null) {
            this.dropHandle.uninstall();
            this.dropHandle = null;
        }
    }

    private void refreshInbox(boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - this.lastInboxRefreshMs >= 1500L) {
            this.lastInboxRefreshMs = now;
            this.inboxFiles = ClientImageUploader.listInboxImages();
        }
    }

    private void pickAndUploadIcon() {
        if (this.uploading) {
            return;
        }
        this.uploading = true;
        this.statusColor = IOStyle.TEXT_2;
        this.statusText = tr("gui.module_shop.media.uploading", "正在处理并上传图片…");
        ClientImageUploader.pickAndUpload("brand_icon", 128, new ClientImageUploader.UploadCallback() {
            @Override
            public void onUploaded(String mediaId, int width, int height) {
                BrandSettingsScreen.this.uploading = false;
                BrandSettingsScreen.this.pendingIconId = mediaId;
                BrandSettingsScreen.this.statusColor = IOStyle.GREEN;
                BrandSettingsScreen.this.statusText = tr("msg.module_shop.media_upload_ok", "图片上传成功");
            }

            @Override
            public void onFailed(String reason) {
                BrandSettingsScreen.this.uploading = false;
                BrandSettingsScreen.this.statusColor = IOStyle.ORANGE;
                BrandSettingsScreen.this.statusText = reason;
            }
        });
    }

    private void uploadIconFile(Path path) {
        if (this.uploading) {
            return;
        }
        this.uploading = true;
        this.statusColor = IOStyle.TEXT_2;
        this.statusText = tr("gui.module_shop.media.uploading", "正在处理并上传图片…");
        ClientImageUploader.uploadFile(path, "brand_icon", 128, new ClientImageUploader.UploadCallback() {
            @Override
            public void onUploaded(String mediaId, int width, int height) {
                BrandSettingsScreen.this.uploading = false;
                BrandSettingsScreen.this.pendingIconId = mediaId;
                BrandSettingsScreen.this.statusColor = IOStyle.GREEN;
                BrandSettingsScreen.this.statusText = tr("msg.module_shop.media_upload_ok", "图片上传成功");
            }

            @Override
            public void onFailed(String reason) {
                BrandSettingsScreen.this.uploading = false;
                BrandSettingsScreen.this.statusColor = IOStyle.RED;
                BrandSettingsScreen.this.statusText = reason;
            }
        });
    }

    public void cancelUploadIndicator() {
        this.uploading = false;
    }

    private void resetIcon() {
        this.pendingIconId = "";
        this.statusColor = IOStyle.TEXT_2;
        this.statusText = tr("gui.module_shop.brand.reset_pending", "已选择恢复默认图标，点击保存后生效");
    }

    private void saveAndApply() {
        final String targetIcon = this.pendingIconId == null ? "" : this.pendingIconId.trim();
        final String targetName = this.pendingTitle == null ? "" : this.pendingTitle.trim();
        ClientAdminAuth.guard(() -> {
            CompoundTag iconTag = new CompoundTag();
            iconTag.putString("action", "admin_shop_icon_set");
            iconTag.putString("mediaId", targetIcon);
            ServerboundShopActionPayload.send(iconTag);

            CompoundTag titleTag = new CompoundTag();
            titleTag.putString("action", "admin_shop_title_set");
            titleTag.putString("name", targetName);
            ServerboundShopActionPayload.send(titleTag);
            onClose();
        });
    }

    @Override
    protected void paint(Canvas canvas) {
        refreshInbox(false);
        float w = Math.max(280.0F, Math.min(PANEL_W, this.width - 16.0F));
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

        // ---- 上半区：图标预览卡片 + 操作按钮 ----
        float prevW = 104.0F;
        float prevH = 74.0F;
        card(canvas, ix, cy, prevW, prevH);
        String displayTitle = (this.pendingTitle == null || this.pendingTitle.isBlank())
                ? Component.translatable("gui.module_shop.title").getString()
                : this.pendingTitle.trim();
        drawIconSample(canvas, ix + 10.0F, cy + 10.0F, 32.0F, 6.0F, displayTitle, this.pendingIconId);
        small(canvas, tr("gui.module_shop.brand.preview_32", "高清预览"), ix + 48.0F, cy + 20.0F, prevW - 52.0F, IOStyle.TEXT_2);
        small(canvas, this.pendingIconId.isEmpty()
                        ? tr("gui.module_shop.brand.default_mode", "默认样式")
                        : tr("gui.module_shop.brand.custom_mode", "自定义图标"),
                ix + 48.0F, cy + 32.0F, prevW - 52.0F, this.pendingIconId.isEmpty() ? IOStyle.TEXT_3 : IOStyle.BLUE);

        // 顶栏 13x13 实寸模拟条
        float barY = cy + 50.0F;
        Draw.fill(canvas, ix + 6.0F, barY, prevW - 12.0F, 18.0F, 3.0F, IOStyle.SIDEBAR);
        drawIconSample(canvas, ix + 10.0F, barY + 2.5F, 13.0F, 3.5F, displayTitle, this.pendingIconId);
        Text.drawLine(canvas, displayTitle, AtomUi.BOLD, 6.5F, IOStyle.TEXT,
                ix + 27.0F, barY + 9.0F, prevW - 36.0F, Text.Align.LEFT);

        // 右侧上传按钮与拖放提示
        float rx = ix + prevW + 10.0F;
        float rw = iw - prevW - 10.0F;
        small(canvas, tr("gui.module_shop.brand.icon_section", "左上角图标（128×128 PNG）"),
                rx, cy + 5.0F, rw, IOStyle.TEXT_2);
        float btnGap = 5.0F;
        float b1W = (rw - btnGap) * 0.5F;
        button(canvas, "upload_icon", tr("gui.module_shop.brand.upload_icon", "上传图标"),
                rx, cy + 13.0F, b1W, 17.0F, Button.Kind.ACCENT, !this.uploading, this::pickAndUploadIcon);
        button(canvas, "reset_icon", tr("gui.module_shop.brand.reset_icon", "恢复默认"),
                rx + b1W + btnGap, cy + 13.0F, b1W, 17.0F, Button.Kind.NORMAL, !this.pendingIconId.isEmpty(),
                this::resetIcon);
        button(canvas, "open_inbox", tr("gui.module_shop.announce.open_inbox", "打开传图目录"),
                rx, cy + 34.0F, rw, 16.0F, Button.Kind.NORMAL, true, ClientImageUploader::openInboxDir);
        paragraph(canvas, tr("gui.module_shop.brand.drop_hint",
                        "支持直接把图片拖入此窗口，或放进 upload_inbox 后在下方点选"),
                rx, cy + 54.0F, rw, 5.5F, IOStyle.TEXT_3, 2, false);

        cy += prevH + 8.0F;

        // ---- 中部：传图目录快捷选择区 ----
        small(canvas, tr("gui.module_shop.brand.inbox_title", "传图目录快捷选取（upload_inbox）"),
                ix, cy + 4.0F, iw, IOStyle.TEXT_2);
        cy += 10.0F;
        float trayH = 44.0F;
        card(canvas, ix, cy, iw, trayH);
        if (this.inboxFiles.isEmpty()) {
            center(canvas, tr("gui.module_shop.brand.inbox_empty", "传图目录暂无图片 — 可拖入图片或点上方「打开传图目录」"),
                    ix + 8.0F, cy + trayH * 0.5F, iw - 16.0F, IOStyle.TEXT_3);
        } else {
            float rowH = 16.0F;
            float contentH = this.inboxFiles.size() * (rowH + 2.0F) + 6.0F;
            float oy = beginList(canvas, this.inboxScroll, ix + 2.0F, cy + 2.0F, iw - 4.0F, trayH - 4.0F, contentH);
            for (int i = 0; i < this.inboxFiles.size(); i++) {
                Path p = this.inboxFiles.get(i);
                float ry = oy + 3.0F + i * (rowH + 2.0F);
                if (ry + rowH < cy || ry > cy + trayH) {
                    continue;
                }
                float rowW = iw - 14.0F;
                float hv = hover("inbox:" + i, ix + 4.0F, ry, rowW, rowH);
                Draw.fill(canvas, ix + 4.0F, ry, rowW, rowH, 2.5F, Draw.lerp(IOStyle.GRAY_BTN, IOStyle.ROW_HOVER, hv));
                String fileName = p.getFileName() == null ? p.toString() : p.getFileName().toString();
                small(canvas, fileName, ix + 10.0F, ry + rowH * 0.5F, rowW - 56.0F, IOStyle.TEXT);
                Text.drawLine(canvas, tr("gui.module_shop.brand.use_file", "选用"), AtomUi.BOLD, 5.5F, IOStyle.BLUE,
                        ix + 4.0F + rowW - 44.0F, ry + rowH * 0.5F, 38.0F, Text.Align.RIGHT);
                hit(ix + 4.0F, ry, rowW, rowH, () -> uploadIconFile(p), null);
            }
            endList(canvas);
        }

        cy += trayH + 8.0F;

        // ---- 商店名称输入框 ----
        small(canvas, tr("gui.module_shop.brand.shop_name", "商店名称"), ix, cy + 4.0F, iw, IOStyle.TEXT_2);
        cy += 10.0F;
        this.titleField.bounds(ix, cy, iw, 17.0F).visible(true);

        // ---- 底部状态与按钮 ----
        float footBtnH = 18.0F;
        float footBtnY = y + h - PAD - footBtnH;
        if (!this.statusText.isEmpty()) {
            small(canvas, this.statusText, ix, footBtnY - 7.0F, iw, this.statusColor);
        }
        float saveW = 92.0F;
        float cancelW = 62.0F;
        button(canvas, "cancel", tr("gui.module_shop.cancel", "取消"),
                ix + iw - saveW - 6.0F - cancelW, footBtnY, cancelW, footBtnH, Button.Kind.NORMAL, true, this::onClose);
        button(canvas, "save", tr("gui.module_shop.save", "保存设置"),
                ix + iw - saveW, footBtnY, saveW, footBtnH, Button.Kind.ACCENT, !this.uploading, this::saveAndApply);
    }

    private static void drawIconSample(Canvas canvas, float x, float y, float size, float radius,
            String title, String iconMediaId) {
        Image img = iconMediaId != null && !iconMediaId.isEmpty() ? ClientMediaCache.getSkiaImage(iconMediaId) : null;
        if (img != null) {
            SkiaDraw.drawRoundedImage(canvas, img, x, y, size, size, radius, SamplingMode.LINEAR);
            Draw.stroke(canvas, x, y, size, size, radius, 0.75F, IOStyle.BORDER);
        } else {
            Draw.gradientH(canvas, x, y, size, size, radius, IOStyle.BLUE, IOStyle.ACCENT_2);
            String first = title.isEmpty() ? "商" : title.substring(0, 1);
            V2.bold(canvas, first, size * 0.54F, 0xFFFFFFFF, x, y + size * 0.5F, size, Text.Align.CENTER);
        }
    }

    // ------------------------------------------------------------------ 非 Skia 回退

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
        int pw = 280;
        int ph = 156;
        int x = (this.width - pw) / 2;
        int y = (this.height - ph) / 2;
        Bricks.panel(g, x, y, pw, ph);
        IOStyle.centerAt(g, this.font, this.title, x + pw / 2, y + 14, IOStyle.ORANGE);
        g.drawString(this.font, Component.translatable("gui.module_shop.brand.shop_name"),
                x + 14, y + 40, IOStyle.TEXT_2, false);
        if (!this.statusText.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.literal(this.statusText), x + pw / 2, y + 86, IOStyle.TEXT_2);
        }
        int bw = 58;
        int by = y + ph - 28;
        int bx = x + 14;
        IOStyle.button(g, bx, by, bw, 18, Component.literal(tr("gui.module_shop.brand.upload_icon", "上传图标")),
                this.font, mouseX, mouseY, !this.uploading, IOStyle.Btn.GRAY);
        this.fbUploadBtn = new int[]{bx, by, bw, 18};
        bx += bw + 6;
        IOStyle.button(g, bx, by, bw, 18, Component.literal(tr("gui.module_shop.brand.reset_icon", "恢复默认")),
                this.font, mouseX, mouseY, !this.pendingIconId.isEmpty(), IOStyle.Btn.GRAY);
        this.fbResetBtn = new int[]{bx, by, bw, 18};
        bx += bw + 6;
        IOStyle.button(g, bx, by, bw, 18, Component.literal(tr("gui.module_shop.cancel", "取消")),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fbBackBtn = new int[]{bx, by, bw, 18};
        bx += bw + 6;
        IOStyle.button(g, bx, by, bw, 18, Component.literal(tr("gui.module_shop.save", "保存")),
                this.font, mouseX, mouseY, !this.uploading, IOStyle.Btn.PRIMARY);
        this.fbSaveBtn = new int[]{bx, by, bw, 18};
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            return skiaClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            if (inside(this.fbUploadBtn, mouseX, mouseY)) {
                ClickSound.play();
                pickAndUploadIcon();
                return true;
            }
            if (inside(this.fbResetBtn, mouseX, mouseY)) {
                ClickSound.play();
                resetIcon();
                return true;
            }
            if (inside(this.fbBackBtn, mouseX, mouseY)) {
                ClickSound.play();
                onClose();
                return true;
            }
            if (inside(this.fbSaveBtn, mouseX, mouseY)) {
                ClickSound.play();
                saveAndApply();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean inside(@Nullable int[] r, double mx, double my) {
        return r != null && IOStyle.inside(r[0], r[1], r[2], r[3], mx, my);
    }
}
