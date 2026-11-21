package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.config.ShopTemplate.ExportSection;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.config.ShopTemplateStore.TemplateFormat;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 「导出商店配置」配置与命名弹窗（AtomLib Skija + 非 Skia 兜底）：
 * <ul>
 *     <li>支持 {@code .mshop}（推荐含图片与全部设置）、{@code .json.gz} 与 {@code .json} 三种格式；</li>
 *     <li>支持按模块勾选导出（核心池 / 签到 / 实体货币 / 出货箱 / 布局图标 / 公告图文 / 可选密码锁）；</li>
 *     <li>专用服支持二选一：「保存至服务端」或「分片下载到本地电脑」。</li>
 * </ul>
 */
public class ConfigExportScreen extends SkiaDialog {
    private record SectionItem(ExportSection section, String fallbackLabel) {
    }

    private static final List<SectionItem> SECTION_ITEMS = List.of(
            new SectionItem(ExportSection.SHOP, "每日商店"),
            new SectionItem(ExportSection.BUYBACK, "每日收购"),
            new SectionItem(ExportSection.MALL, "购物城"),
            new SectionItem(ExportSection.EXCHANGE, "货币兑换"),
            new SectionItem(ExportSection.SIGNIN, "签到活动"),
            new SectionItem(ExportSection.CURRENCY, "实体货币"),
            new SectionItem(ExportSection.SHIPPING, "出货箱规则"),
            new SectionItem(ExportSection.LAYOUT, "布局与图标"),
            new SectionItem(ExportSection.ANNOUNCEMENTS, "公告与图片"),
            new SectionItem(ExportSection.SECURITY, "附带密码锁")
    );

    private final boolean dedicated;
    private String currentName = "";
    private TemplateFormat format = TemplateFormat.MSHOP;
    private String dest = "local";
    private final EnumSet<ExportSection> selected = ExportSection.allDefault(false);
    private boolean securityTouched;
    private String errorText = "";

    private SkiaTextField skNameField;

    private static final int FB_W = 356;
    private static final int FB_H = 266;
    private EditBox fallbackBox;
    private final List<int[]> fallbackHits = new ArrayList<>();
    private static final int HIT_CONFIRM = 1;
    private static final int HIT_CANCEL = 2;
    private static final int HIT_DEST_SERVER = 3;
    private static final int HIT_DEST_LOCAL = 4;
    private static final int HIT_FMT_MSHOP = 5;
    private static final int HIT_FMT_GZ = 6;
    private static final int HIT_FMT_JSON = 7;
    private static final int HIT_ALL = 8;
    private static final int HIT_CORE = 9;
    private static final int HIT_SECTION_BASE = 100;

    public ConfigExportScreen(boolean dedicated) {
        super(Component.translatable("gui.module_shop.cfg.export_title"));
        this.dedicated = dedicated;
        this.dest = dedicated ? "server" : "local";
    }

    @Override
    protected void init() {
        if (skiaInit()) {
            this.fallbackBox = null;
            return;
        }
        int x = (this.width - FB_W) / 2;
        int y = (this.height - FB_H) / 2;
        this.fallbackBox = new EditBox(this.font, x + 16, y + 66, FB_W - 32, 18, Component.empty());
        this.fallbackBox.setMaxLength(64);
        this.fallbackBox.setValue(this.currentName);
        this.fallbackBox.setResponder(this::onNameChanged);
        this.addRenderableWidget(this.fallbackBox);
        this.setInitialFocus(this.fallbackBox);
    }

    @Override
    protected void initSkia() {
        this.skNameField = field(64, Component.translatable("gui.module_shop.cfg.export_placeholder").getString());
        EditBox box = this.skNameField.editBox();
        box.setValue(this.currentName);
        box.setResponder(this::onNameChanged);
        this.skNameField.focus();
    }

    private void onNameChanged(String val) {
        this.currentName = val == null ? "" : val;
        if (!this.securityTouched) {
            if (ShopTemplateStore.isDefaultName(this.currentName) && ClientShopData.adminLockEnabled) {
                this.selected.add(ExportSection.SECURITY);
            } else {
                this.selected.remove(ExportSection.SECURITY);
            }
        }
    }

    public void onExportFinished(@Nullable Path written) {
        Nav.back();
    }

    public void onTransferError(String msg) {
        this.errorText = msg == null ? "" : msg;
    }

    @Override
    public void onClose() {
        Nav.back();
    }

    @Override
    protected boolean onEnter() {
        confirmExport();
        return true;
    }

    private void toggleSection(ExportSection sec) {
        if (sec == ExportSection.SECURITY) {
            if (!ClientShopData.adminLockEnabled) {
                return;
            }
            this.securityTouched = true;
            if (this.selected.contains(sec)) {
                this.selected.remove(sec);
            } else {
                ClientAdminAuth.guard(() -> this.selected.add(ExportSection.SECURITY));
            }
            return;
        }
        if (this.selected.contains(sec)) {
            this.selected.remove(sec);
        } else {
            this.selected.add(sec);
        }
    }

    private void selectAll(boolean includeSecurity) {
        this.selected.clear();
        this.selected.addAll(ExportSection.allDefault(includeSecurity && ClientShopData.adminLockEnabled));
    }

    private void selectCoreOnly() {
        this.selected.clear();
        this.selected.addAll(ExportSection.coreCommerceOnly());
    }

    private void confirmExport() {
        if (this.selected.isEmpty()) {
            this.errorText = Component.translatableWithFallback(
                    "gui.module_shop.cfg.need_one_section", "请至少勾选 1 个导出模块。").getString();
            return;
        }
        Runnable task = () -> {
            String raw = this.currentName == null ? "" : this.currentName.trim();
            CompoundTag tag = new CompoundTag();
            tag.putString("action", "cfg_export");
            tag.putString("rawName", raw);
            tag.putString("name", raw);
            tag.putString("format", this.format.name().toLowerCase(java.util.Locale.ROOT));
            tag.putString("sections", ExportSection.serializeMask(this.selected));
            tag.putString("dest", this.dedicated ? this.dest : "local");
            ServerboundShopActionPayload.send(tag);
            Nav.back();
        };
        if (this.selected.contains(ExportSection.SECURITY) && ClientShopData.adminLockEnabled) {
            ClientAdminAuth.guard(task);
        } else {
            task.run();
        }
    }

    // ------------------------------------------------------------------ Skija 主绘制

    @Override
    protected void paint(Canvas canvas) {
        boolean mediaWarn = (this.selected.contains(ExportSection.LAYOUT)
                || this.selected.contains(ExportSection.ANNOUNCEMENTS)) && this.format != TemplateFormat.MSHOP;
        float desiredW = 396.0F;
        float w = Math.min(desiredW, Math.max(300.0F, this.width - 16.0F));
        float rowH = 16.0F;
        float gridH = 4 * (rowH + 2.0F) + 5.0F; // 3 列 × 4 行 容纳 10 项
        float h = HEADER_H + (this.dedicated ? 212.0F : 196.0F) + (mediaWarn ? 13.0F : 0.0F)
                + (this.errorText.isEmpty() ? 0.0F : 13.0F);
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        float innerX = x + PAD;
        float innerW = w - PAD * 2;

        panel(canvas, x, y, w, h, this.title.getString());

        float cy = y + HEADER_H + 6.0F;
        if (this.dedicated) {
            small(canvas, Component.translatableWithFallback(
                    "gui.module_shop.cfg.export_target_label", "保存目标：").getString(),
                    innerX, cy + 7.0F, 56.0F, IOStyle.TEXT_2);
            float chipW = (innerW - 62.0F) / 2.0F;
            chip(canvas, "destServer",
                    Component.translatableWithFallback("gui.module_shop.cfg.dest_server", "保存至服务端").getString(),
                    innerX + 58.0F, cy, chipW, 16.0F, "server".equals(this.dest), IOStyle.BLUE,
                    () -> this.dest = "server");
            chip(canvas, "destLocal",
                    Component.translatableWithFallback("gui.module_shop.cfg.dest_local", "下载到本地").getString(),
                    innerX + 62.0F + chipW, cy, chipW, 16.0F, "local".equals(this.dest), IOStyle.GREEN,
                    () -> this.dest = "local");
            cy += 20.0F;
        } else {
            String destHint = Component.translatable(
                    "gui.module_shop.cfg.export_dest_local", ShopTemplateStore.DIR_NAME).getString();
            small(canvas, destHint, innerX, cy + 5.0F, innerW, IOStyle.GREEN);
            cy += 13.0F;
        }

        // 文件名输入 + 格式预览
        small(canvas, Component.translatable("gui.module_shop.cfg.export_field_label").getString(),
                innerX, cy + 5.0F, innerW, IOStyle.TEXT);
        if (this.skNameField != null) {
            this.skNameField.bounds(innerX, cy + 11.0F, innerW, 17.0F);
        }
        cy += 31.0F;

        boolean isDef = ShopTemplateStore.isDefaultName(this.currentName);
        String previewFile = ShopTemplateStore.previewExportFileName(this.currentName, this.format);
        String previewLine = Component.translatable(
                isDef ? "gui.module_shop.cfg.export_preview_default" : "gui.module_shop.cfg.export_preview",
                previewFile).getString();
        small(canvas, previewLine, innerX, cy + 5.0F, innerW, isDef ? IOStyle.ORANGE : IOStyle.TEXT_2);
        cy += 14.0F;

        // 格式选择（.mshop / .json.gz / .json）
        float fmtW = (innerW - 8.0F) / 3.0F;
        chip(canvas, "fmtMshop",
                Component.translatableWithFallback("gui.module_shop.cfg.fmt_mshop", ".mshop 整合包").getString(),
                innerX, cy, fmtW, 17.0F, this.format == TemplateFormat.MSHOP, IOStyle.ORANGE,
                () -> this.format = TemplateFormat.MSHOP);
        chip(canvas, "fmtGz",
                Component.translatableWithFallback("gui.module_shop.cfg.fmt_gz", ".json.gz 压缩").getString(),
                innerX + fmtW + 4.0F, cy, fmtW, 17.0F, this.format == TemplateFormat.JSON_GZ, IOStyle.BLUE,
                () -> this.format = TemplateFormat.JSON_GZ);
        chip(canvas, "fmtJson",
                Component.translatableWithFallback("gui.module_shop.cfg.fmt_json", ".json 纯文本").getString(),
                innerX + (fmtW + 4.0F) * 2.0F, cy, fmtW, 17.0F, this.format == TemplateFormat.JSON, IOStyle.TEXT_2,
                () -> this.format = TemplateFormat.JSON);
        cy += 21.0F;

        // 模块勾选框标题 + 快捷全选/核心按钮
        small(canvas, Component.translatableWithFallback("gui.module_shop.cfg.sections_title", "包含内容（点击切换）").getString(),
                innerX, cy + 6.0F, innerW - 118.0F, IOStyle.TEXT);
        chip(canvas, "secAll", Component.translatableWithFallback("gui.module_shop.cfg.sec_all", "全选").getString(),
                innerX + innerW - 114.0F, cy, 46.0F, 14.0F, false, IOStyle.BLUE, () -> selectAll(false));
        chip(canvas, "secCore", Component.translatableWithFallback("gui.module_shop.cfg.sec_core", "仅核心商品").getString(),
                innerX + innerW - 64.0F, cy, 64.0F, 14.0F, false, IOStyle.ORANGE, this::selectCoreOnly);
        cy += 17.0F;

        float colGap = 4.0F;
        float colW = (innerW - 10.0F - colGap * 2.0F) / 3.0F;
        card(canvas, innerX, cy, innerW, gridH);
        for (int i = 0; i < SECTION_ITEMS.size(); i++) {
            SectionItem item = SECTION_ITEMS.get(i);
            int row = i / 3;
            int col = i % 3;
            float cx = innerX + 5.0F + col * (colW + colGap);
            float ry = cy + 3.0F + row * (rowH + 2.0F);
            boolean on = this.selected.contains(item.section);
            boolean disabled = item.section == ExportSection.SECURITY && !ClientShopData.adminLockEnabled;
            String label = Component.translatableWithFallback(
                    "gui.module_shop.cfg.sec." + item.section.id, item.fallbackLabel).getString();
            if (disabled) {
                label += " (无)";
            }
            String mark = disabled ? "× " : (on ? "☑ " : "☐ ");
            int accent = item.section == ExportSection.SECURITY ? IOStyle.ORANGE
                    : (on ? IOStyle.GREEN : IOStyle.TEXT_3);
            chip(canvas, "sec_" + item.section.id, mark + label, cx, ry, colW, rowH,
                    on && !disabled, disabled ? IOStyle.TEXT_3 : accent, () -> toggleSection(item.section));
        }
        cy += gridH + 5.0F;

        if (mediaWarn) {
            small(canvas, Component.translatableWithFallback(
                    "gui.module_shop.cfg.mshop_media_hint",
                    "提示：仅 .mshop 格式会打包品牌图标与公告插图文件").getString(),
                    innerX, cy + 5.0F, innerW, IOStyle.ORANGE);
            cy += 13.0F;
        }
        if (!this.errorText.isEmpty()) {
            small(canvas, this.errorText, innerX, cy + 5.0F, innerW, IOStyle.RED);
        }

        float bw = 98.0F;
        float gap = 12.0F;
        float bx = x + (w - bw * 2 - gap) / 2.0F;
        float by = y + h - PAD - 20.0F;
        button(canvas, "confirm", Component.translatable("gui.module_shop.cfg.export_confirm").getString(),
                bx, by, bw, 20.0F, Button.Kind.ACCENT, !this.selected.isEmpty(), this::confirmExport);
        button(canvas, "cancel", Component.translatable("gui.module_shop.cancel").getString(),
                bx + bw + gap, by, bw, 20.0F, Button.Kind.NORMAL, true, this::onClose);
    }

    // ------------------------------------------------------------------ 非 Skia 兜底

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        if (skia()) {
            return;
        }
        int x = (this.width - FB_W) / 2;
        int y = (this.height - FB_H) / 2;
        Bricks.panel(g, x, y, FB_W, FB_H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        this.fallbackHits.clear();
        super.render(g, mouseX, mouseY, partialTick);

        int x = (this.width - FB_W) / 2;
        int y = (this.height - FB_H) / 2;
        IOStyle.centerAt(g, this.font, this.title, x + FB_W / 2, y + 10, IOStyle.ORANGE);

        int innerX = x + 14;
        int innerW = FB_W - 28;
        if (this.dedicated) {
            int dw = (innerW - 6) / 2;
            IOStyle.button(g, innerX, y + 24, dw, 16, Component.literal("保存至服务端"),
                    this.font, mouseX, mouseY, true,
                    "server".equals(this.dest) ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
            this.fallbackHits.add(new int[]{innerX, y + 24, dw, 16, HIT_DEST_SERVER});
            IOStyle.button(g, innerX + dw + 6, y + 24, dw, 16, Component.literal("下载到本地"),
                    this.font, mouseX, mouseY, true,
                    "local".equals(this.dest) ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
            this.fallbackHits.add(new int[]{innerX + dw + 6, y + 24, dw, 16, HIT_DEST_LOCAL});
        } else {
            g.drawString(this.font,
                    Component.translatable("gui.module_shop.cfg.export_dest_local", ShopTemplateStore.DIR_NAME),
                    innerX, y + 28, IOStyle.GREEN, false);
        }

        g.drawString(this.font, Component.translatable("gui.module_shop.cfg.export_field_label"),
                innerX, y + 50, IOStyle.TEXT, false);

        String previewFile = ShopTemplateStore.previewExportFileName(this.currentName, this.format);
        boolean isDef = ShopTemplateStore.isDefaultName(this.currentName);
        g.drawString(this.font,
                Component.translatable(isDef ? "gui.module_shop.cfg.export_preview_default"
                        : "gui.module_shop.cfg.export_preview", previewFile),
                innerX, y + 88, isDef ? IOStyle.ORANGE : IOStyle.TEXT_2, false);

        // 格式按钮
        int fw = (innerW - 8) / 3;
        int fy = y + 102;
        IOStyle.button(g, innerX, fy, fw, 16, Component.literal(".mshop"), this.font, mouseX, mouseY, true,
                this.format == TemplateFormat.MSHOP ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
        this.fallbackHits.add(new int[]{innerX, fy, fw, 16, HIT_FMT_MSHOP});
        IOStyle.button(g, innerX + fw + 4, fy, fw, 16, Component.literal(".json.gz"), this.font, mouseX, mouseY, true,
                this.format == TemplateFormat.JSON_GZ ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
        this.fallbackHits.add(new int[]{innerX + fw + 4, fy, fw, 16, HIT_FMT_GZ});
        IOStyle.button(g, innerX + (fw + 4) * 2, fy, fw, 16, Component.literal(".json"), this.font, mouseX, mouseY, true,
                this.format == TemplateFormat.JSON ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
        this.fallbackHits.add(new int[]{innerX + (fw + 4) * 2, fy, fw, 16, HIT_FMT_JSON});

        // 模块勾选网格（2 列 × 5 行）
        int cw = (innerW - 6) / 2;
        int sy = y + 124;
        for (int i = 0; i < SECTION_ITEMS.size(); i++) {
            SectionItem item = SECTION_ITEMS.get(i);
            int col = i % 2;
            int row = i / 2;
            int rx = innerX + col * (cw + 6);
            int ry = sy + row * 19;
            boolean on = this.selected.contains(item.section);
            boolean disabled = item.section == ExportSection.SECURITY && !ClientShopData.adminLockEnabled;
            String label = (disabled ? "[×] " : (on ? "[√] " : "[ ] ")) + item.fallbackLabel;
            IOStyle.button(g, rx, ry, cw, 16, Component.literal(label), this.font, mouseX, mouseY,
                    !disabled, on && !disabled ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
            if (!disabled) {
                this.fallbackHits.add(new int[]{rx, ry, cw, 16, HIT_SECTION_BASE + i});
            }
        }

        if (!this.errorText.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.literal(this.errorText), x + FB_W / 2, y + FB_H - 42, IOStyle.RED);
        }

        int bw = 92;
        int gap = 12;
        int bx = x + (FB_W - bw * 2 - gap) / 2;
        int by = y + FB_H - 28;
        IOStyle.button(g, bx, by, bw, 20, Component.translatable("gui.module_shop.cfg.export_confirm"),
                this.font, mouseX, mouseY, !this.selected.isEmpty(), IOStyle.Btn.PRIMARY);
        this.fallbackHits.add(new int[]{bx, by, bw, 20, HIT_CONFIRM});
        IOStyle.button(g, bx + bw + gap, by, bw, 20, Component.translatable("gui.module_shop.cancel"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fallbackHits.add(new int[]{bx + bw + gap, by, bw, 20, HIT_CANCEL});
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            return skiaClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            for (int[] rect : this.fallbackHits) {
                if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                    Bricks.click();
                    int act = rect[4];
                    switch (act) {
                        case HIT_CONFIRM -> confirmExport();
                        case HIT_CANCEL -> onClose();
                        case HIT_DEST_SERVER -> this.dest = "server";
                        case HIT_DEST_LOCAL -> this.dest = "local";
                        case HIT_FMT_MSHOP -> this.format = TemplateFormat.MSHOP;
                        case HIT_FMT_GZ -> this.format = TemplateFormat.JSON_GZ;
                        case HIT_FMT_JSON -> this.format = TemplateFormat.JSON;
                        case HIT_ALL -> selectAll(false);
                        case HIT_CORE -> selectCoreOnly();
                        default -> {
                            int idx = act - HIT_SECTION_BASE;
                            if (idx >= 0 && idx < SECTION_ITEMS.size()) {
                                toggleSection(SECTION_ITEMS.get(idx).section);
                            }
                        }
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!skia() && (keyCode == 257 || keyCode == 335)) {
            confirmExport();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
