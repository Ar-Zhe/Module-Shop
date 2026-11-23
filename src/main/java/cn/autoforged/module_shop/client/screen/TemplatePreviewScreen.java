package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientTemplateIO;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.config.ShopTemplate.ExportSection;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * 导入配置二级确认界面（AtomLib Skija + 非 Skia 兜底）：
 * <ul>
 *     <li>展示模板版本、来源、总数/有效/无效/冲突、内嵌媒体资源数；</li>
 *     <li>支持勾选本次要应用的配置模块（默认全选文件所含模块，密码锁仅在目标端未设密码时生效）；</li>
 *     <li>遇到缺失模组物品或组件解析错误时，展开显示逐条问题清单而不丢弃其余商品；</li>
 *     <li>使用 {@code transferId} 引用服务端已解析好的会话缓存，不再把整份 JSON 回传一次。</li>
 * </ul>
 */
public class TemplatePreviewScreen extends SkiaDialog {
    private record SectionDef(ExportSection section, String label) {
    }

    private static final List<SectionDef> ALL_SECTIONS = List.of(
            new SectionDef(ExportSection.SHOP, "每日商店"),
            new SectionDef(ExportSection.BUYBACK, "每日收购"),
            new SectionDef(ExportSection.MALL, "购物城"),
            new SectionDef(ExportSection.EXCHANGE, "货币兑换"),
            new SectionDef(ExportSection.SIGNIN, "签到活动"),
            new SectionDef(ExportSection.CURRENCY, "实体货币"),
            new SectionDef(ExportSection.SHIPPING, "出货箱"),
            new SectionDef(ExportSection.LAYOUT, "布局图标"),
            new SectionDef(ExportSection.ANNOUNCEMENTS, "公告图文"),
            new SectionDef(ExportSection.SECURITY, "密码锁")
    );

    private CompoundTag preview;
    private final EnumSet<ExportSection> availableSections = EnumSet.noneOf(ExportSection.class);
    private final EnumSet<ExportSection> selectedSections = EnumSet.noneOf(ExportSection.class);
    private final List<String> issueLines = new ArrayList<>();
    private final ScrollController issueScroll = new ScrollController();

    private static final int FB_W = 350;
    private static final int FB_H = 248;
    private final List<int[]> fallbackHits = new ArrayList<>();
    private static final int ACT_REPLACE = 1;
    private static final int ACT_MERGE = 2;
    private static final int ACT_CANCEL = 3;
    private static final int ACT_SEC_BASE = 100;

    public TemplatePreviewScreen(CompoundTag preview) {
        super(Component.translatable("gui.module_shop.cfg.preview_title"));
        updatePreviewState(preview == null ? new CompoundTag() : preview.copy());
    }

    /** 若预览页已打开又收到新统计，原地更新展示内容。 */
    public void onPreview(CompoundTag data) {
        if (data != null) {
            updatePreviewState(data.copy());
        }
    }

    public void onAppliedDone(CompoundTag data) {
        if (this.minecraft != null && this.minecraft.screen == this) {
            Nav.back();
        }
    }

    private void updatePreviewState(CompoundTag data) {
        this.preview = data;
        this.availableSections.clear();
        this.selectedSections.clear();
        EnumSet<ExportSection> parsed = ExportSection.parseMask(data.getString("sections"));
        if (parsed.isEmpty()) {
            parsed = ExportSection.allDefault(data.getBoolean("hasSecurity"));
        }
        this.availableSections.addAll(parsed);
        this.selectedSections.addAll(parsed);

        this.issueLines.clear();
        ListTag list = data.getList("issues", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag it = list.getCompound(i);
            String loc = it.getString("location");
            String item = it.getString("itemId");
            String detail = it.getString("detail");
            String code = it.getString("code");
            String text = "[" + code + "] " + (item.isBlank() ? loc : loc + " -> " + item);
            if (!detail.isBlank()) {
                text += ": " + detail;
            }
            this.issueLines.add(text);
        }
        int totalIssues = data.getInt("totalIssues");
        if (totalIssues > this.issueLines.size()) {
            this.issueLines.add("... 另有 " + (totalIssues - this.issueLines.size()) + " 条问题未展开");
        }
    }

    @Override
    public void onClose() {
        Nav.back();
    }

    private void toggleSection(ExportSection s) {
        if (!this.availableSections.contains(s)) {
            return;
        }
        if (this.selectedSections.contains(s)) {
            this.selectedSections.remove(s);
        } else {
            this.selectedSections.add(s);
        }
    }

    private void apply(String mode) {
        if (this.selectedSections.isEmpty()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "cfg_import_apply");
        tag.putString("mode", mode);
        tag.putString("sections", ExportSection.serializeMask(this.selectedSections));

        String transferId = this.preview.getString("transferId");
        if (!transferId.isBlank()) {
            tag.putString("transferId", transferId);
        }
        if ("server".equals(this.preview.getString("source"))) {
            tag.putString("source", "server");
            tag.putString("fileKey", this.preview.getString("fileKey"));
        } else {
            tag.putString("source", "local");
            tag.putString("fileName", this.preview.getString("fileName"));
        }
        ServerboundShopActionPayload.send(tag);
        Nav.back();
    }

    // ------------------------------------------------------------------ Skija 主绘制

    @Override
    protected void paint(Canvas canvas) {
        boolean syncNotice = this.preview.getBoolean("syncServer");
        boolean fromServer = "server".equals(this.preview.getString("source"));
        int mediaCount = this.preview.getInt("mediaCount");
        long mediaBytes = this.preview.getLong("mediaBytes");

        float w = Math.min(404.0F, Math.max(320.0F, this.width - 16.0F));
        int cols = 4;
        List<SectionDef> visibleSecs = visibleSections();
        int secRows = visibleSecs.isEmpty() ? 0 : (visibleSecs.size() + cols - 1) / cols;
        float secGridH = secRows > 0 ? (secRows * 17.0F + 15.0F) : 0.0F;
        float mediaH = mediaCount > 0 ? 14.0F : 0.0F;
        float syncH = syncNotice ? 14.0F : 0.0F;
        float cardH = 78.0F + mediaH;

        float fixedNonIssueH = HEADER_H + 8.0F + cardH + secGridH + syncH + 40.0F + PAD;
        float maxScreenH = Math.max(200.0F, this.height - 16.0F);
        float issueBoxH = this.issueLines.isEmpty() ? 0.0F
                : Math.max(42.0F, Math.min(72.0F, maxScreenH - fixedNonIssueH));
        float h = Math.min(maxScreenH, fixedNonIssueH + issueBoxH);

        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        float innerX = x + PAD;
        float innerW = w - PAD * 2;

        panel(canvas, x, y, w, h, this.title.getString());

        float cy = y + HEADER_H + 6.0F;
        card(canvas, innerX, cy, innerW, cardH);

        float rowX = innerX + 10.0F;
        float rowW = innerW - 20.0F;

        // 第 1 行：配置名称 + 格式版本标签
        int fmtVer = Math.max(1, this.preview.getInt("formatVersion"));
        String verText = "v" + fmtVer;
        drawBadge(canvas, verText, rowX + rowW - 28.0F, cy + 7.0F, 28.0F, 13.0F, IOStyle.BLUE);
        String nameLine = Component.translatable("gui.module_shop.cfg.preview_name",
                this.preview.getString("name")).getString();
        text(canvas, nameLine, rowX, cy + 13.0F, rowW - 34.0F, IOStyle.TEXT);

        // 第 2 行：来源徽标 + 文件名
        String fileName = this.preview.getString("fileName");
        if (fileName.isBlank()) {
            fileName = "-";
        }
        String badge = Component.translatable(
                fromServer ? "gui.module_shop.cfg.badge_server" : "gui.module_shop.cfg.badge_local").getString();
        int badgeColor = fromServer ? IOStyle.BLUE : IOStyle.GREEN;
        float badgeW = 40.0F;
        drawBadge(canvas, badge, rowX, cy + 24.0F, badgeW, 13.0F, badgeColor);
        small(canvas, fileName, rowX + badgeW + 6.0F, cy + 30.5F, rowW - badgeW - 6.0F, IOStyle.TEXT_2);

        // 第 3-4 行：条目统计
        int total = this.preview.getInt("total");
        int valid = this.preview.getInt("valid");
        int invalid = this.preview.getInt("invalid");
        int conflicts = this.preview.getInt("conflicts");
        float halfW = rowW / 2.0F;
        text(canvas, Component.translatable("gui.module_shop.cfg.preview_total", total).getString(),
                rowX, cy + 48.0F, halfW - 4.0F, IOStyle.TEXT);
        text(canvas, Component.translatable("gui.module_shop.cfg.preview_valid", valid).getString(),
                rowX + halfW, cy + 48.0F, halfW - 4.0F, IOStyle.GREEN);
        text(canvas, Component.translatable("gui.module_shop.cfg.preview_invalid", invalid).getString(),
                rowX, cy + 64.0F, halfW - 4.0F, invalid > 0 ? IOStyle.ORANGE : IOStyle.TEXT_2);
        text(canvas, Component.translatable("gui.module_shop.cfg.preview_conflicts", conflicts).getString(),
                rowX + halfW, cy + 64.0F, halfW - 4.0F, conflicts > 0 ? IOStyle.ORANGE : IOStyle.TEXT_2);

        if (mediaCount > 0) {
            String mediaText = Component.translatableWithFallback(
                    "gui.module_shop.cfg.preview_media",
                    "包含图片资产：%s 个（%s）",
                    mediaCount, ClientTemplateIO.formatSize(mediaBytes)).getString();
            small(canvas, mediaText, rowX, cy + 80.0F, rowW, IOStyle.BLUE);
        }

        cy += cardH + 5.0F;

        // 模块勾选过滤网格
        if (!visibleSecs.isEmpty()) {
            small(canvas, Component.translatableWithFallback(
                    "gui.module_shop.cfg.preview_sections", "勾选本次导入的模块（点击切换）：").getString(),
                    innerX, cy + 5.0F, innerW, IOStyle.TEXT_2);
            cy += 12.0F;
            float colGap = 4.0F;
            float colW = (innerW - colGap * (cols - 1)) / cols;
            for (int i = 0; i < visibleSecs.size(); i++) {
                SectionDef def = visibleSecs.get(i);
                int row = i / cols;
                int col = i % cols;
                float cx = innerX + col * (colW + colGap);
                float ry = cy + row * 17.0F;
                boolean on = this.selectedSections.contains(def.section);
                String lbl = (on ? "☑ " : "☐ ") + Component.translatableWithFallback(
                        "gui.module_shop.cfg.sec." + def.section.id, def.label).getString();
                chip(canvas, "psec_" + def.section.id, lbl, cx, ry, colW, 15.0F, on,
                        def.section == ExportSection.SECURITY ? IOStyle.ORANGE : IOStyle.GREEN,
                        () -> toggleSection(def.section));
            }
            cy += secRows * 17.0F + 3.0F;
        }

        // 问题物品诊断滚动区（有错才出现）
        if (!this.issueLines.isEmpty()) {
            small(canvas, Component.translatableWithFallback(
                    "gui.module_shop.cfg.preview_issues_title",
                    "以下物品/组件将在导入时跳过（其余有效物品正常导入）：").getString(),
                    innerX, cy + 5.0F, innerW, IOStyle.ORANGE);
            float boxY = cy + 11.0F;
            float boxH = Math.max(26.0F, issueBoxH - 13.0F);
            card(canvas, innerX, boxY, innerW, boxH);
            float rowH = 13.0F;
            float contentH = this.issueLines.size() * rowH + 6.0F;
            float oy = beginList(canvas, this.issueScroll, innerX + 2.0F, boxY + 2.0F,
                    innerW - 4.0F, boxH - 4.0F, contentH);
            for (int i = 0; i < this.issueLines.size(); i++) {
                float ry = oy + 2.0F + i * rowH;
                if (ry + rowH < boxY || ry > boxY + boxH) {
                    continue;
                }
                small(canvas, this.issueLines.get(i), innerX + 8.0F, ry + 6.0F, innerW - 18.0F, IOStyle.ORANGE);
            }
            endList(canvas);
            cy = boxY + boxH + 3.0F;
        }

        if (syncNotice) {
            small(canvas, Component.translatable("gui.module_shop.cfg.preview_sync_notice",
                    ShopTemplateStore.DIR_NAME).getString(), innerX, cy + 5.0F, innerW, IOStyle.BLUE);
            cy += 14.0F;
        }

        center(canvas, Component.translatable("gui.module_shop.cfg.preview_mode_hint").getString(),
                innerX, cy + 5.0F, innerW, IOStyle.TEXT_2);

        float gap = 8.0F;
        float bw = (innerW - gap * 2) / 3.0F;
        float by = y + h - PAD - 20.0F;
        boolean canApply = !this.selectedSections.isEmpty();
        button(canvas, "replace", Component.translatable("gui.module_shop.cfg.replace").getString(),
                innerX, by, bw, 20.0F, Button.Kind.ACCENT, canApply, () -> apply("replace"));
        button(canvas, "merge", Component.translatable("gui.module_shop.cfg.merge").getString(),
                innerX + bw + gap, by, bw, 20.0F, Button.Kind.NORMAL, canApply, () -> apply("merge"));
        button(canvas, "cancel", Component.translatable("gui.module_shop.cancel").getString(),
                innerX + (bw + gap) * 2, by, bw, 20.0F, Button.Kind.NORMAL, true, this::onClose);
    }

    private List<SectionDef> visibleSections() {
        List<SectionDef> list = new ArrayList<>();
        for (SectionDef def : ALL_SECTIONS) {
            if (this.availableSections.contains(def.section)) {
                list.add(def);
            }
        }
        return list;
    }

    private static void drawBadge(Canvas canvas, String label, float x, float y, float w, float h, int color) {
        Draw.fill(canvas, x, y, w, h, 3.0F, Draw.alpha(color, 0.20F));
        Draw.stroke(canvas, x, y, w, h, 3.0F, 0.75F, Draw.alpha(color, 0.65F));
        Text.drawLine(canvas, label, AtomUi.REGULAR, Text.SMALL, color, x + 2.0F, y + h / 2.0F, w - 4.0F,
                Text.Align.CENTER);
    }

    // ------------------------------------------------------------------ 非 Skia 兜底

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        this.fallbackHits.clear();
        super.render(g, mouseX, mouseY, partialTick);

        int x = (this.width - FB_W) / 2;
        int y = (this.height - FB_H) / 2;
        Bricks.panel(g, x, y, FB_W, FB_H);
        IOStyle.centerAt(g, this.font, this.title, x + FB_W / 2, y + 10, IOStyle.ORANGE);

        int cy = y + 26;
        IOStyle.centerAt(g, this.font,
                Component.translatable("gui.module_shop.cfg.preview_name", this.preview.getString("name")),
                x + FB_W / 2, cy, IOStyle.TEXT);
        int invalid = this.preview.getInt("invalid");
        String statLine = String.format("总计 %d | 有效 %d | 无效 %d | 冲突 %d",
                this.preview.getInt("total"), this.preview.getInt("valid"),
                invalid, this.preview.getInt("conflicts"));
        IOStyle.centerAt(g, this.font, Component.literal(statLine),
                x + FB_W / 2, cy + 14, invalid > 0 ? IOStyle.ORANGE : IOStyle.GREEN);

        List<SectionDef> visibleSecs = visibleSections();
        int listW = FB_W - 28;
        int cw = (listW - 8) / 3;
        int sy = cy + 32;
        for (int i = 0; i < visibleSecs.size(); i++) {
            SectionDef def = visibleSecs.get(i);
            int row = i / 3;
            int col = i % 3;
            int rx = x + 14 + col * (cw + 4);
            int ry = sy + row * 18;
            boolean on = this.selectedSections.contains(def.section);
            String label = (on ? "[√] " : "[ ] ") + def.label;
            IOStyle.button(g, rx, ry, cw, 16, Component.literal(label), this.font,
                    mouseX, mouseY, true, on ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
            this.fallbackHits.add(new int[]{rx, ry, cw, 16, ACT_SEC_BASE + i});
        }

        if (!this.issueLines.isEmpty()) {
            int iy = sy + ((visibleSecs.size() + 2) / 3) * 18 + 6;
            int show = Math.min(2, this.issueLines.size());
            for (int i = 0; i < show; i++) {
                String line = this.issueLines.get(i);
                if (line.length() > 48) {
                    line = line.substring(0, 47) + "…";
                }
                g.drawString(this.font, line, x + 14, iy + i * 11, IOStyle.ORANGE, false);
            }
        }

        if (this.preview.getBoolean("syncServer")) {
            IOStyle.centerAt(g, this.font,
                    Component.translatable("gui.module_shop.cfg.preview_sync_notice", ShopTemplateStore.DIR_NAME),
                    x + FB_W / 2, y + FB_H - 42, IOStyle.BLUE);
        }

        int gap = 8;
        int bw = (listW - gap * 2) / 3;
        int bx = x + 14;
        int by = y + FB_H - 28;
        boolean canApply = !this.selectedSections.isEmpty();
        IOStyle.button(g, bx, by, bw, 20, Component.translatable("gui.module_shop.cfg.replace"),
                this.font, mouseX, mouseY, canApply, IOStyle.Btn.PRIMARY);
        this.fallbackHits.add(new int[]{bx, by, bw, 20, ACT_REPLACE});
        IOStyle.button(g, bx + bw + gap, by, bw, 20, Component.translatable("gui.module_shop.cfg.merge"),
                this.font, mouseX, mouseY, canApply, IOStyle.Btn.GRAY);
        this.fallbackHits.add(new int[]{bx + bw + gap, by, bw, 20, ACT_MERGE});
        IOStyle.button(g, bx + (bw + gap) * 2, by, bw, 20, Component.translatable("gui.module_shop.cancel"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fallbackHits.add(new int[]{bx + (bw + gap) * 2, by, bw, 20, ACT_CANCEL});
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
                    int code = rect[4];
                    if (code == ACT_REPLACE) {
                        apply("replace");
                    } else if (code == ACT_MERGE) {
                        apply("merge");
                    } else if (code == ACT_CANCEL) {
                        onClose();
                    } else if (code >= ACT_SEC_BASE) {
                        List<SectionDef> visibleSecs = visibleSections();
                        int idx = code - ACT_SEC_BASE;
                        if (idx >= 0 && idx < visibleSecs.size()) {
                            toggleSection(visibleSecs.get(idx).section);
                        }
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
