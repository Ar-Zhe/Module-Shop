package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ClientTemplateIO;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.config.ShopTemplateStore.TemplateFormat;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * 「配置管理」界面（AtomLib Skija + 非 Skia 兜底）：
 * <ul>
 *     <li>兼容显示并导入/导出 {@code .mshop}、{@code .json.gz} 与 {@code .json} 三种模板；</li>
 *     <li>单机模式直接零网络拷贝服务端读写；专用服大文件自动走 256 KiB 分片上传/下载并展示进度条与取消按钮；</li>
 *     <li>专用服列表行内支持直接下载服务端配置到本地计算机。</li>
 * </ul>
 */
public class ConfigManagerScreen extends SkiaDialog {
    private record ServerEntry(String key, String fileName, boolean isDefault, long sizeBytes, TemplateFormat format) {
    }

    private record ConfigEntry(boolean server, String key, String fileName, boolean isDefault,
                               boolean legacyExports, long sizeBytes, TemplateFormat format, Path localFile) {
        String uniqueKey() {
            return (server ? "S:" : "L:") + key;
        }
    }

    private final List<ServerEntry> serverEntries = new ArrayList<>();
    private final List<ConfigEntry> entries = new ArrayList<>();
    private final ScrollController listScroll = new ScrollController();
    private SkiaTextField skSearch;
    private String query = "";

    private boolean dedicated;
    private String selectedKey = "";
    private String statusText = "";
    private int statusColor = IOStyle.TEXT_2;
    private int pollTicks;
    private String localFingerprint = "";

    private static final int ACT_IMPORT = -1;
    private static final int ACT_EXPORT = -2;
    private static final int ACT_BACK = -3;
    private static final int ACT_CANCEL_TRANSFER = -4;
    private final List<int[]> fallbackHits = new ArrayList<>();
    private int fallbackScroll;

    public ConfigManagerScreen() {
        super(Component.translatable("gui.module_shop.cfg.title"));
        this.dedicated = !ClientTemplateIO.isSingleplayer();
    }

    @Override
    protected void init() {
        this.pollTicks = 0;
        rebuildEntries();
        ClientTemplateIO.requestServerList();
        super.init();
    }

    @Override
    protected void initSkia() {
        this.skSearch = field(64, Component.translatable("gui.module_shop.search.placeholder").getString())
                .clearable(true);
        this.skSearch.editBox().setValue(this.query);
        this.skSearch.onChange(v -> {
            this.query = v == null ? "" : v;
            this.listScroll.scrollTo(0, false);
        });
    }

    @Override
    public void tick() {
        super.tick();
        ClientTemplateIO.tick();
        if (++this.pollTicks >= 20) {
            this.pollTicks = 0;
            String fp = ClientTemplateIO.localFingerprint();
            if (!fp.equals(this.localFingerprint)) {
                rebuildEntries();
            }
        }
    }

    @Override
    public void onClose() {
        if (ClientTemplateIO.hasActiveTransfer()) {
            ClientTemplateIO.cancelTransfer();
        }
        Nav.back();
    }

    /** 服务端下发配置列表（与单机/专用服标记）。 */
    public void onServerList(CompoundTag data) {
        this.dedicated = data.getBoolean("dedicated");
        if (data.contains("adminLockEnabled")) {
            ClientShopData.adminLockEnabled = data.getBoolean("adminLockEnabled");
        }
        this.serverEntries.clear();
        ListTag list = data.getList("files", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            String key = item.getString("key");
            String name = item.getString("name");
            if (name.isBlank()) {
                int slash = key.lastIndexOf('/');
                name = slash >= 0 ? key.substring(slash + 1) : key;
            }
            TemplateFormat fmt = TemplateFormat.byId(item.getString("format"),
                    TemplateFormat.fromFileName(name, TemplateFormat.JSON));
            this.serverEntries.add(new ServerEntry(key, name, item.getBoolean("isDefault"),
                    item.getLong("size"), fmt));
        }
        rebuildEntries();
    }

    /** 导出/下载落盘成功后原地刷新列表与状态栏。 */
    public void onLocalExported(Path written) {
        rebuildEntries();
        String fileName = written != null && written.getFileName() != null ? written.getFileName().toString() : "";
        this.statusColor = IOStyle.GREEN;
        this.statusText = Component.translatable("gui.module_shop.cfg.status_exported", fileName).getString();
    }

    public void onTransferError(String message) {
        this.statusColor = IOStyle.RED;
        this.statusText = message == null ? "" : message;
    }

    public void onAppliedDone(CompoundTag tag) {
        this.statusColor = tag.getInt("invalid") > 0 ? IOStyle.ORANGE : IOStyle.GREEN;
        this.statusText = Component.translatable("msg.module_shop.cfg_applied",
                tag.getInt("applied"), tag.getInt("invalid"), tag.getInt("duplicates")).getString();
        rebuildEntries();
    }

    /** 服务端预览校验返回后打开二级确认弹窗（支持服务端文件与分片传输会话）。 */
    public void onPreview(CompoundTag data) {
        String source = data.getString("source");
        boolean validPayload = "server".equals(source)
                || !data.getString("transferId").isBlank();
        if (!validPayload) {
            return;
        }
        this.statusText = "";
        Nav.open(new TemplatePreviewScreen(data));
    }

    private void rebuildEntries() {
        this.entries.clear();
        this.localFingerprint = ClientTemplateIO.localFingerprint();
        if (this.dedicated) {
            for (ServerEntry se : this.serverEntries) {
                boolean legacy = se.key().startsWith("exports/");
                this.entries.add(new ConfigEntry(true, se.key(), se.fileName(), se.isDefault(),
                        legacy, se.sizeBytes(), se.format(), null));
            }
        }
        Path cfgDir = ClientTemplateIO.configDir();
        for (Path file : ClientTemplateIO.listImportable()) {
            String key = ShopTemplateStore.relativeKey(cfgDir, file);
            String name = file.getFileName().toString();
            boolean isDefault = ShopTemplateStore.isDefaultName(name);
            boolean legacy = key.startsWith("exports/");
            TemplateFormat fmt = TemplateFormat.fromFileName(name, TemplateFormat.JSON);
            long size = 0L;
            try {
                size = Files.size(file);
            } catch (IOException ignored) {
            }
            this.entries.add(new ConfigEntry(false, key, name, isDefault, legacy, size, fmt, file));
        }
        int maxFallback = Math.max(0, this.entries.size() - fallbackVisibleRows());
        this.fallbackScroll = Math.max(0, Math.min(this.fallbackScroll, maxFallback));
    }

    private void onEntryClick(ConfigEntry entry) {
        if (ClientTemplateIO.hasActiveTransfer()) {
            this.statusColor = IOStyle.ORANGE;
            this.statusText = Component.translatableWithFallback(
                    "gui.module_shop.cfg.transfer_busy", "当前正在传输模板，请等待完成或点击取消。").getString();
            return;
        }
        ClientAdminAuth.guard(() -> {
            this.selectedKey = entry.uniqueKey();
            this.statusColor = IOStyle.BLUE;
            this.statusText = Component.translatable("gui.module_shop.cfg.selected", entry.fileName()).getString();
            Nav.rearm(TemplatePreviewScreen.class);
            if (entry.server()) {
                CompoundTag tag = new CompoundTag();
                tag.putString("action", "cfg_server_preview");
                tag.putString("key", entry.key());
                ServerboundShopActionPayload.send(tag);
                return;
            }
            if (!ClientTemplateIO.startPreviewForLocalFile(entry.localFile(), this.dedicated)) {
                String err = ClientTemplateIO.lastErrorMessage();
                if (!err.isBlank()) {
                    this.statusColor = IOStyle.RED;
                    this.statusText = err;
                }
            }
        });
    }

    private void onDownloadServerEntry(ConfigEntry entry) {
        if (!entry.server() || ClientTemplateIO.hasActiveTransfer()) {
            return;
        }
        ClientAdminAuth.guard(() -> {
            this.statusColor = IOStyle.BLUE;
            this.statusText = Component.translatableWithFallback(
                    "gui.module_shop.cfg.downloading_file", "正在从服务端下载 %s...", entry.fileName()).getString();
            CompoundTag req = new CompoundTag();
            req.putString("action", "cfg_download_file");
            req.putString("key", entry.key());
            ServerboundShopActionPayload.send(req);
        });
    }

    private void clickImport() {
        boolean opened = ClientTemplateIO.openTemplateDir();
        rebuildEntries();
        if (opened) {
            this.statusColor = IOStyle.GREEN;
            this.statusText = Component.translatable("gui.module_shop.cfg.import_opened",
                    ShopTemplateStore.DIR_NAME).getString();
        } else {
            this.statusColor = IOStyle.RED;
            this.statusText = Component.translatable("gui.module_shop.cfg.open_dir_fail").getString();
        }
    }

    private void clickExport() {
        ClientAdminAuth.guard(() -> Nav.open(new ConfigExportScreen(this.dedicated)));
    }

    // ------------------------------------------------------------------ Skija 主绘制

    @Override
    protected void paint(Canvas canvas) {
        float w = Math.max(320.0F, Math.min(460.0F, this.width - 28.0F));
        float h = Math.max(230.0F, Math.min(324.0F, this.height - 24.0F));
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        float innerX = x + PAD;
        float innerW = w - PAD * 2;

        panel(canvas, x, y, w, h, this.title.getString());

        float topY = y + HEADER_H + 8.0F;
        String modeHint = this.dedicated
                ? Component.translatable("gui.module_shop.cfg.mode_server", ShopTemplateStore.DIR_NAME).getString()
                : Component.translatable("gui.module_shop.cfg.mode_local", ShopTemplateStore.DIR_NAME).getString();
        small(canvas, modeHint, innerX, topY + 5.0F, innerW, this.dedicated ? IOStyle.BLUE : IOStyle.GREEN);

        String listHint = Component.translatable("gui.module_shop.cfg.list_hint").getString();
        small(canvas, listHint, innerX, topY + 18.0F, innerW, IOStyle.TEXT_2);

        if (this.skSearch != null) {
            this.skSearch.bounds(innerX, topY + 25.0F, innerW, 16.0F);
        }

        List<ConfigEntry> visibleEntries = new ArrayList<>();
        for (ConfigEntry entry : this.entries) {
            if (PageKit.matchesQuery(this.query, entry.fileName(), entry.key(), entry.format().name(),
                    entry.server() ? "server 服务端" : "local 本地")) {
                visibleEntries.add(entry);
            }
        }

        boolean transferring = ClientTemplateIO.hasActiveTransfer();
        float btnH = 20.0F;
        float btnY = y + h - PAD - btnH;
        float progressH = transferring ? 22.0F : 0.0F;
        float statusY = btnY - progressH - 14.0F;
        float listY = topY + 45.0F;
        float listH = Math.max(70.0F, statusY - 6.0F - listY);

        card(canvas, innerX, listY, innerW, listH);

        if (visibleEntries.isEmpty()) {
            String msg = this.entries.isEmpty()
                    ? Component.translatable("gui.module_shop.cfg.empty").getString()
                    : Component.translatable("gui.module_shop.search.empty").getString();
            center(canvas, msg, innerX, listY + listH / 2.0F, innerW, IOStyle.TEXT_2);
        } else {
            float rowH = 26.0F;
            float gap = 4.0F;
            float padIn = 5.0F;
            float contentH = visibleEntries.size() * rowH + Math.max(0, visibleEntries.size() - 1) * gap + padIn * 2;
            float oy = beginList(canvas, this.listScroll, innerX + 2.0F, listY + 2.0F,
                    innerW - 4.0F, listH - 4.0F, contentH);
            float rx = innerX + padIn + 2.0F;
            float rw = innerW - padIn * 2 - 12.0F;

            for (int i = 0; i < visibleEntries.size(); i++) {
                ConfigEntry entry = visibleEntries.get(i);
                float ry = oy + padIn + i * (rowH + gap);
                if (ry + rowH < listY || ry > listY + listH) {
                    continue;
                }
                boolean selected = entry.uniqueKey().equals(this.selectedKey);
                float hv = hover("row:" + entry.uniqueKey(), rx, ry, rw, rowH);
                int bg = Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hv);
                int border = selected ? IOStyle.BLUE : Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, hv);
                Draw.fill(canvas, rx, ry, rw, rowH, 4.0F, bg);
                Draw.stroke(canvas, rx, ry, rw, rowH, 4.0F, selected ? 1.25F : 0.8F, border);

                float cursorX = rx + 6.0F;
                float cy = ry + rowH / 2.0F;

                String scopeText = Component.translatable(
                        entry.server() ? "gui.module_shop.cfg.badge_server" : "gui.module_shop.cfg.badge_local")
                        .getString();
                int scopeColor = entry.server() ? IOStyle.BLUE : IOStyle.GREEN;
                float scopeW = Math.max(28.0F, Text.width(scopeText, AtomUi.REGULAR, Text.SMALL) + 8.0F);
                drawBadge(canvas, scopeText, cursorX, cy - 7.0F, scopeW, 14.0F, scopeColor);
                cursorX += scopeW + 4.0F;

                String fmtLabel = switch (entry.format()) {
                    case MSHOP -> "MSHOP";
                    case JSON_GZ -> "GZ";
                    case JSON -> "JSON";
                };
                int fmtColor = switch (entry.format()) {
                    case MSHOP -> IOStyle.ORANGE;
                    case JSON_GZ -> IOStyle.BLUE;
                    case JSON -> IOStyle.TEXT_2;
                };
                float fmtW = Math.max(22.0F, Text.width(fmtLabel, AtomUi.REGULAR, Text.SMALL) + 8.0F);
                drawBadge(canvas, fmtLabel, cursorX, cy - 7.0F, fmtW, 14.0F, fmtColor);
                cursorX += fmtW + 4.0F;

                if (entry.isDefault()) {
                    String defText = Component.translatable("gui.module_shop.cfg.badge_default").getString();
                    float defW = Math.max(26.0F, Text.width(defText, AtomUi.REGULAR, Text.SMALL) + 8.0F);
                    drawBadge(canvas, defText, cursorX, cy - 7.0F, defW, 14.0F, IOStyle.ORANGE);
                    cursorX += defW + 4.0F;
                }

                float rightReserve = 8.0F;
                if (entry.server()) {
                    float dlW = 38.0F;
                    float dlX = rx + rw - rightReserve - dlW;
                    button(canvas, "dl_" + entry.uniqueKey(), "↓下载", dlX, cy - 8.0F, dlW, 16.0F,
                            Button.Kind.NORMAL, !transferring, () -> onDownloadServerEntry(entry));
                    rightReserve += dlW + 6.0F;
                }

                String sizeText = ClientTemplateIO.formatSize(entry.sizeBytes()) + "  ›";
                float metaW = Math.max(48.0F, Text.width(sizeText, AtomUi.REGULAR, Text.BODY) + 4.0F);
                right(canvas, sizeText, rx + rw - rightReserve - metaW, cy, metaW + 0.5F, IOStyle.TEXT_3);

                String titleName = entry.legacyExports() ? "exports/" + entry.fileName() : entry.fileName();
                float nameMaxW = Math.max(40.0F, (rx + rw - rightReserve - metaW - 6.0F) - cursorX);
                text(canvas, titleName, cursorX, cy, nameMaxW + 0.5F, IOStyle.TEXT);

                float rowHitW = entry.server() ? (rw - 48.0F) : rw;
                hit(rx, ry, rowHitW, rowH, () -> onEntryClick(entry),
                        entry.server() ? () -> onDownloadServerEntry(entry) : null);
            }
            endList(canvas);
        }

        if (transferring) {
            float barY = btnY - progressH - 2.0F;
            float cancelW = 54.0F;
            float barW = innerW - cancelW - 6.0F;
            Draw.fill(canvas, innerX, barY + 12.0F, barW, 5.0F, 2.5F, IOStyle.BORDER_DIM);
            float fillW = Math.max(4.0F, barW * ClientTemplateIO.transferProgress());
            Draw.fill(canvas, innerX, barY + 12.0F, fillW, 5.0F, 2.5F, IOStyle.BLUE);
            small(canvas, ClientTemplateIO.transferStatusText(), innerX, barY + 5.0F, barW, IOStyle.BLUE);
            button(canvas, "cancelTransfer",
                    Component.translatableWithFallback("gui.module_shop.cancel", "取消").getString(),
                    innerX + barW + 6.0F, barY + 1.0F, cancelW, 17.0F,
                    Button.Kind.DANGER, true, ClientTemplateIO::cancelTransfer);
        }

        if (!this.statusText.isEmpty()) {
            center(canvas, this.statusText, innerX, statusY + 4.0F, innerW, this.statusColor);
        }

        float gap = 8.0F;
        float bw = (innerW - gap * 2) / 3.0F;
        button(canvas, "import", Component.translatable("gui.module_shop.cfg.import_btn").getString(),
                innerX, btnY, bw, btnH, Button.Kind.NORMAL, true, this::clickImport);
        button(canvas, "export", Component.translatable("gui.module_shop.cfg.export_btn").getString(),
                innerX + bw + gap, btnY, bw, btnH, Button.Kind.ACCENT, true, this::clickExport);
        button(canvas, "back", Component.translatable("gui.module_shop.cfg.back_btn").getString(),
                innerX + (bw + gap) * 2, btnY, bw, btnH, Button.Kind.NORMAL, true, this::onClose);
    }

    private static void drawBadge(Canvas canvas, String label, float x, float y, float w, float h, int color) {
        Draw.fill(canvas, x, y, w, h, 3.0F, Draw.alpha(color, 0.20F));
        Draw.stroke(canvas, x, y, w, h, 3.0F, 0.75F, Draw.alpha(color, 0.65F));
        Text.drawLine(canvas, label, AtomUi.REGULAR, Text.SMALL, color, x + 2.0F, y + h / 2.0F, w - 4.0F,
                Text.Align.CENTER);
    }

    // ------------------------------------------------------------------ 非 Skia 兜底

    private static final int FB_W = 352;
    private static final int FB_H = 256;
    private static final int FB_ROW_H = 18;

    private int fallbackVisibleRows() {
        return ClientTemplateIO.hasActiveTransfer() ? 6 : 7;
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
        Bricks.panel(g, x, y, FB_W, FB_H);
        IOStyle.centerAt(g, this.font, this.title, x + FB_W / 2, y + 10, IOStyle.ORANGE);

        Component hint = this.dedicated
                ? Component.translatable("gui.module_shop.cfg.mode_server", ShopTemplateStore.DIR_NAME)
                : Component.translatable("gui.module_shop.cfg.mode_local", ShopTemplateStore.DIR_NAME);
        g.drawString(this.font, hint, x + 14, y + 25, IOStyle.TEXT_2, false);

        int listX = x + 14;
        int listY = y + 40;
        int listW = FB_W - 28;
        int visible = fallbackVisibleRows();
        if (this.entries.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.cfg.empty"),
                    x + FB_W / 2, listY + 56, IOStyle.TEXT_2);
        } else {
            for (int i = 0; i < visible && this.fallbackScroll + i < this.entries.size(); i++) {
                int idx = this.fallbackScroll + i;
                ConfigEntry entry = this.entries.get(idx);
                int ry = listY + i * (FB_ROW_H + 2);
                String fmt = switch (entry.format()) {
                    case MSHOP -> "[MSHOP] ";
                    case JSON_GZ -> "[GZ] ";
                    case JSON -> "[JSON] ";
                };
                String prefix = (entry.server() ? "[服] " : "[本地] ") + fmt + (entry.isDefault() ? "★ " : "");
                String label = prefix + (entry.legacyExports() ? "exports/" + entry.fileName() : entry.fileName());
                if (label.length() > 40) {
                    label = label.substring(0, 39) + "…";
                }
                boolean sel = entry.uniqueKey().equals(this.selectedKey);
                IOStyle.button(g, listX, ry, listW, FB_ROW_H, Component.literal(label), this.font,
                        mouseX, mouseY, true, sel ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
                this.fallbackHits.add(new int[]{listX, ry, listW, FB_ROW_H, idx});
            }
        }

        if (ClientTemplateIO.hasActiveTransfer()) {
            int barY = y + FB_H - 54;
            g.drawString(this.font, ClientTemplateIO.transferStatusText(), listX, barY, IOStyle.BLUE, false);
            IOStyle.button(g, listX + listW - 44, barY - 2, 44, 14, Component.literal("取消"),
                    this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
            this.fallbackHits.add(new int[]{listX + listW - 44, barY - 2, 44, 14, ACT_CANCEL_TRANSFER});
        }

        if (!this.statusText.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.literal(this.statusText),
                    x + FB_W / 2, y + FB_H - 42, this.statusColor);
        }

        int btnY = y + FB_H - 28;
        int gap = 8;
        int bw = (listW - gap * 2) / 3;
        IOStyle.button(g, listX, btnY, bw, 20, Component.translatable("gui.module_shop.cfg.import_btn"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fallbackHits.add(new int[]{listX, btnY, bw, 20, ACT_IMPORT});
        IOStyle.button(g, listX + bw + gap, btnY, bw, 20, Component.translatable("gui.module_shop.cfg.export_btn"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.PRIMARY);
        this.fallbackHits.add(new int[]{listX + bw + gap, btnY, bw, 20, ACT_EXPORT});
        IOStyle.button(g, listX + (bw + gap) * 2, btnY, bw, 20, Component.translatable("gui.module_shop.cfg.back_btn"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fallbackHits.add(new int[]{listX + (bw + gap) * 2, btnY, bw, 20, ACT_BACK});
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            return skiaClicked(mouseX, mouseY, button);
        }
        if (button == 0 || button == 1) {
            for (int[] rect : this.fallbackHits) {
                if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                    Bricks.click();
                    int code = rect[4];
                    if (button == 1) {
                        if (code >= 0 && code < this.entries.size() && this.entries.get(code).server()) {
                            onDownloadServerEntry(this.entries.get(code));
                            return true;
                        }
                        continue;
                    }
                    if (code == ACT_IMPORT) {
                        clickImport();
                    } else if (code == ACT_EXPORT) {
                        clickExport();
                    } else if (code == ACT_BACK) {
                        onClose();
                    } else if (code == ACT_CANCEL_TRANSFER) {
                        ClientTemplateIO.cancelTransfer();
                    } else if (code >= 0 && code < this.entries.size()) {
                        onEntryClick(this.entries.get(code));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (skia()) {
            return super.mouseScrolled(mouseX, mouseY, scrollY);
        }
        int max = Math.max(0, this.entries.size() - fallbackVisibleRows());
        if (max > 0) {
            this.fallbackScroll = Math.max(0, Math.min(max, this.fallbackScroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }
}
