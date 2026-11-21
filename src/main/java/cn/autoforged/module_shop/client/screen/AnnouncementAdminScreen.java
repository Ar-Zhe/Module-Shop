package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.AnnouncementView;
import cn.autoforged.module_shop.client.media.ClientImageUploader;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import cn.autoforged.module_shop.shop.home.AnnouncementBlock;
import io.github.humbleui.skija.Canvas;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 公告管理热更新弹窗（问题 #4 升级版：结构化图文混排块 + 实时所见即所得预览 + 多通道传图）：
 * <ul>
 *     <li>左侧：公告列表（置顶标记、新增、删除、上移/下移、一键切换置顶）；</li>
 *     <li>右上：标题 / 分类 / 日期 / 置顶开关 / 块操作栏（加文字、插图片、传图收图箱、移动/删块）；</li>
 *     <li>右中：块结构列表 + 右侧实时图文混排预览（点击图片可放大预览；拖放图片直接插入）；</li>
 *     <li>右下：文字块段落输入框（Enter 分段 / ↑↓ 切换）或图片块参数调节（宽比 40%/70%/100%、对齐、图注）。</li>
 * </ul>
 */
public class AnnouncementAdminScreen extends SkiaDialog {
    private static final float MAX_W = 436.0F;
    private static final float MAX_H = 276.0F;
    private static final int MAX_BLOCKS_PER_ANN = 40;
    private static final int[] WIDTH_PRESETS = {40, 70, 100};
    private static final String[] ALIGN_MODES = {"left", "center", "right"};

    private final List<AnnouncementView> drafts = new ArrayList<>();
    private final List<AnnouncementBlock> blocks = new ArrayList<>();
    private final ScrollController listScroll = new ScrollController();
    private final ScrollController blockScroll = new ScrollController();
    private final ScrollController previewScroll = new ScrollController();
    private final ScrollController inboxScroll = new ScrollController();
    private final AnnouncementBlockRenderer previewRenderer = new AnnouncementBlockRenderer();

    private int selectedIndex = -1;
    private int activeBlock = 0;
    private boolean syncingFields;
    private boolean showInboxDrawer;
    private boolean uploadingImage;
    private String statusText = "";
    private int statusColor = IOStyle.TEXT_2;
    private boolean pendingEnsureActiveBlock;

    private SkiaTextField titleField;
    private SkiaTextField categoryField;
    private SkiaTextField dateField;
    private SkiaTextField lineField;
    private SkiaTextField captionField;
    private SkiaTextField annSearchField;
    private SkiaTextField inboxSearchField;

    private List<Path> inboxFiles = List.of();
    private long lastInboxRefreshMs;
    private ClientImageUploader.DropHandle dropHandle;

    private int[] fbBackBtn;

    public AnnouncementAdminScreen() {
        super(Component.literal(tr("gui.module_shop.ann_admin.title", "公告管理 · 热更新")));
        for (AnnouncementView v : ClientHomeCurrencyState.announcements) {
            this.drafts.add(v.copy());
        }
        if (!this.drafts.isEmpty()) {
            this.selectedIndex = 0;
            loadBlocksFromSelected();
        }
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
        return false;
    }

    @Override
    protected void initSkia() {
        installDrop();
        refreshInbox(true);
        this.titleField = field(80, tr("gui.module_shop.ann_admin.ph_title", "输入公告标题…")).fontSize(Text.BODY);
        this.categoryField = field(24, tr("gui.module_shop.ann_admin.ph_cat", "如：系统 / 活动")).fontSize(Text.SMALL);
        this.dateField = field(24, "YYYY-MM-DD").fontSize(Text.SMALL);
        this.lineField = field(1000, tr("gui.module_shop.ann_admin.ph_line", "编辑当前文字段（Enter 新增下一段）…"))
                .fontSize(Text.SMALL);
        this.captionField = field(80, tr("gui.module_shop.announce.img_caption", "图片说明（可选）"))
                .fontSize(Text.SMALL);
        this.annSearchField = field(48, Component.translatable("gui.module_shop.search.placeholder").getString())
                .clearable(true).fontSize(Text.SMALL);
        this.annSearchField.onChange(val -> this.listScroll.reset());
        this.inboxSearchField = field(48, Component.translatable("gui.module_shop.search.placeholder").getString())
                .clearable(true).fontSize(Text.SMALL);
        this.inboxSearchField.onChange(val -> this.inboxScroll.reset());

        this.titleField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            AnnouncementView sel = selected();
            if (sel != null) {
                sel.title = val == null ? "" : val;
                this.statusText = "";
            }
        });
        this.categoryField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            AnnouncementView sel = selected();
            if (sel != null) {
                sel.category = val == null ? "" : val;
            }
        });
        this.dateField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            AnnouncementView sel = selected();
            if (sel != null) {
                sel.date = val == null ? "" : val;
            }
        });
        this.lineField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            AnnouncementBlock cur = activeBlockObj();
            if (cur != null && AnnouncementBlock.TYPE_TEXT.equals(cur.type())) {
                this.blocks.set(this.activeBlock, AnnouncementBlock.textBlock(val == null ? "" : val));
                syncBlocksToSelected();
            }
        });
        this.captionField.onChange(val -> {
            if (this.syncingFields) {
                return;
            }
            AnnouncementBlock cur = activeBlockObj();
            if (cur != null && AnnouncementBlock.TYPE_IMAGE.equals(cur.type())) {
                this.blocks.set(this.activeBlock, AnnouncementBlock.imageBlock(
                        cur.mediaId(), cur.widthPct(), cur.align(),
                        val == null ? "" : val, cur.imgW(), cur.imgH()));
                syncBlocksToSelected();
            }
        });

        syncFieldsFromSelected();
        if (selected() != null) {
            this.titleField.focus();
        }
    }

    private void installDrop() {
        if (this.dropHandle != null) {
            return;
        }
        this.dropHandle = ClientImageUploader.installDropCallback(path ->
                Minecraft.getInstance().execute(() -> {
                    if (selected() == null) {
                        addAnnouncement();
                    }
                    uploadAndInsertImageFile(path);
                }));
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

    // ------------------------------------------------------------------ 数据辅助

    private @Nullable AnnouncementView selected() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.drafts.size()) {
            return null;
        }
        return this.drafts.get(this.selectedIndex);
    }

    private @Nullable AnnouncementBlock activeBlockObj() {
        if (this.activeBlock < 0 || this.activeBlock >= this.blocks.size()) {
            return null;
        }
        return this.blocks.get(this.activeBlock);
    }

    private void selectIndex(int idx) {
        if (this.drafts.isEmpty()) {
            this.selectedIndex = -1;
            this.blocks.clear();
            this.activeBlock = 0;
        } else {
            this.selectedIndex = Math.max(0, Math.min(this.drafts.size() - 1, idx));
            loadBlocksFromSelected();
        }
        this.blockScroll.reset();
        this.previewScroll.reset();
        this.previewRenderer.invalidate();
        syncFieldsFromSelected();
    }

    private void loadBlocksFromSelected() {
        this.blocks.clear();
        AnnouncementView sel = selected();
        if (sel != null) {
            this.blocks.addAll(sel.effectiveBlocks());
        }
        if (this.blocks.isEmpty()) {
            this.blocks.add(AnnouncementBlock.textBlock(""));
        }
        this.activeBlock = 0;
    }

    private void syncBlocksToSelected() {
        AnnouncementView sel = selected();
        if (sel != null) {
            sel.blocks = new ArrayList<>(this.blocks);
            sel.content = sel.derivePlainContent();
            this.previewRenderer.invalidate();
        }
    }

    private void syncFieldsFromSelected() {
        if (this.titleField == null) {
            return;
        }
        this.syncingFields = true;
        try {
            AnnouncementView sel = selected();
            if (sel == null) {
                this.titleField.setValue("");
                this.categoryField.setValue("");
                this.dateField.setValue("");
                this.lineField.setValue("");
                this.captionField.setValue("");
                return;
            }
            this.titleField.setValue(sel.title);
            this.categoryField.setValue(sel.category);
            this.dateField.setValue(sel.date);
            syncActiveBlockFieldOnly();
        } finally {
            this.syncingFields = false;
        }
    }

    private void syncActiveBlockFieldOnly() {
        if (this.lineField == null || this.captionField == null) {
            return;
        }
        boolean wasSyncing = this.syncingFields;
        this.syncingFields = true;
        try {
            AnnouncementBlock cur = activeBlockObj();
            if (cur == null) {
                this.lineField.setValue("");
                this.captionField.setValue("");
            } else if (AnnouncementBlock.TYPE_IMAGE.equals(cur.type())) {
                this.lineField.setValue("");
                this.captionField.setValue(cur.caption() == null ? "" : cur.caption());
            } else {
                this.lineField.setValue(cur.text() == null ? "" : cur.text());
                this.captionField.setValue("");
            }
        } finally {
            this.syncingFields = wasSyncing;
        }
    }

    private void addAnnouncement() {
        AnnouncementView v = new AnnouncementView();
        v.id = "ann_" + UUID.randomUUID().toString().substring(0, 8);
        v.title = tr("gui.module_shop.ann_admin.default_title", "新服务器公告");
        v.category = tr("gui.module_shop.ann_admin.default_cat", "公告");
        v.date = LocalDate.now().toString();
        String defText = tr("gui.module_shop.ann_admin.default_content", "在这里输入公告正文内容…");
        v.content = defText;
        v.blocks = new ArrayList<>(List.of(AnnouncementBlock.textBlock(defText)));
        v.pinned = false;
        v.order = this.drafts.size();
        this.drafts.add(v);
        if (this.annSearchField != null && !this.annSearchField.value().isEmpty()) {
            this.annSearchField.setValue("");
        }
        selectIndex(this.drafts.size() - 1);
        this.listScroll.scrollTo(Float.MAX_VALUE, true);
        if (this.titleField != null) {
            this.titleField.focus();
        }
        this.statusText = "";
    }

    private void deleteSelected() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.drafts.size()) {
            return;
        }
        this.drafts.remove(this.selectedIndex);
        if (this.drafts.isEmpty()) {
            selectIndex(-1);
        } else {
            selectIndex(Math.min(this.selectedIndex, this.drafts.size() - 1));
        }
        this.statusText = "";
    }

    private void moveSelected(int delta) {
        int next = this.selectedIndex + delta;
        if (this.selectedIndex < 0 || next < 0 || next >= this.drafts.size()) {
            return;
        }
        AnnouncementView item = this.drafts.remove(this.selectedIndex);
        this.drafts.add(next, item);
        this.selectedIndex = next;
        this.statusText = "";
    }

    private void togglePinned(int idx) {
        if (idx < 0 || idx >= this.drafts.size()) {
            return;
        }
        AnnouncementView item = this.drafts.get(idx);
        item.pinned = !item.pinned;
        this.statusText = "";
    }

    private void insertTextBlockBelow(String initialText, boolean focusLine) {
        if (selected() == null) {
            return;
        }
        if (this.blocks.size() >= MAX_BLOCKS_PER_ANN) {
            this.statusColor = IOStyle.ORANGE;
            this.statusText = tr("gui.module_shop.ann_admin.err_max_blocks", "单条公告最多支持 %s 个排版块", MAX_BLOCKS_PER_ANN);
            return;
        }
        int insertAt = Math.max(0, Math.min(this.blocks.size(), this.activeBlock + 1));
        this.blocks.add(insertAt, AnnouncementBlock.textBlock(initialText == null ? "" : initialText));
        this.activeBlock = insertAt;
        syncBlocksToSelected();
        syncActiveBlockFieldOnly();
        this.pendingEnsureActiveBlock = true;
        if (focusLine && this.lineField != null) {
            this.lineField.focus();
            this.lineField.editBox().moveCursorToStart(false);
        }
    }

    private void insertImageBlockBelow(String mediaId, int w, int h) {
        if (selected() == null) {
            return;
        }
        if (this.blocks.size() >= MAX_BLOCKS_PER_ANN) {
            this.statusColor = IOStyle.ORANGE;
            this.statusText = tr("gui.module_shop.ann_admin.err_max_blocks", "单条公告最多支持 %s 个排版块", MAX_BLOCKS_PER_ANN);
            return;
        }
        int insertAt = Math.max(0, Math.min(this.blocks.size(), this.activeBlock + 1));
        this.blocks.add(insertAt, AnnouncementBlock.imageBlock(mediaId, 100, "center", "", w, h));
        this.activeBlock = insertAt;
        this.showInboxDrawer = false;
        syncBlocksToSelected();
        syncActiveBlockFieldOnly();
        this.pendingEnsureActiveBlock = true;
    }

    private void moveActiveBlock(int delta) {
        int next = this.activeBlock + delta;
        if (selected() == null || this.activeBlock < 0 || next < 0 || next >= this.blocks.size()) {
            return;
        }
        AnnouncementBlock item = this.blocks.remove(this.activeBlock);
        this.blocks.add(next, item);
        this.activeBlock = next;
        this.pendingEnsureActiveBlock = true;
        syncBlocksToSelected();
    }

    private void removeActiveBlock() {
        if (selected() == null || this.blocks.isEmpty()) {
            return;
        }
        if (this.blocks.size() == 1) {
            this.blocks.set(0, AnnouncementBlock.textBlock(""));
            syncBlocksToSelected();
            syncActiveBlockFieldOnly();
            return;
        }
        this.blocks.remove(this.activeBlock);
        this.activeBlock = Math.max(0, Math.min(this.blocks.size() - 1, this.activeBlock));
        this.pendingEnsureActiveBlock = true;
        syncBlocksToSelected();
        syncActiveBlockFieldOnly();
    }

    public void cancelUploadIndicator() {
        this.uploadingImage = false;
    }

    private void pickAndInsertImage() {
        if (selected() == null || this.uploadingImage) {
            return;
        }
        this.uploadingImage = true;
        this.statusColor = IOStyle.TEXT_2;
        this.statusText = tr("gui.module_shop.media.uploading", "正在处理并上传图片…");
        ClientImageUploader.pickAndUpload("announcement", 1280, createInsertCallback());
    }

    private void uploadAndInsertImageFile(Path path) {
        if (selected() == null || this.uploadingImage) {
            return;
        }
        this.uploadingImage = true;
        this.statusColor = IOStyle.TEXT_2;
        this.statusText = tr("gui.module_shop.media.uploading", "正在处理并上传图片…");
        ClientImageUploader.uploadFile(path, "announcement", 1280, createInsertCallback());
    }

    private ClientImageUploader.UploadCallback createInsertCallback() {
        return new ClientImageUploader.UploadCallback() {
            @Override
            public void onUploaded(String mediaId, int width, int height) {
                AnnouncementAdminScreen.this.uploadingImage = false;
                AnnouncementAdminScreen.this.statusColor = IOStyle.GREEN;
                AnnouncementAdminScreen.this.statusText = tr("msg.module_shop.media_upload_ok", "图片上传成功");
                insertImageBlockBelow(mediaId, width, height);
            }

            @Override
            public void onFailed(String reason) {
                AnnouncementAdminScreen.this.uploadingImage = false;
                AnnouncementAdminScreen.this.statusColor = IOStyle.ORANGE;
                AnnouncementAdminScreen.this.statusText = reason;
            }
        };
    }

    private void setActiveImageProps(@Nullable Integer newWidthPct, @Nullable String newAlign) {
        AnnouncementBlock cur = activeBlockObj();
        if (cur == null || !AnnouncementBlock.TYPE_IMAGE.equals(cur.type())) {
            return;
        }
        int wp = newWidthPct != null ? newWidthPct : cur.widthPct();
        String al = newAlign != null ? newAlign : cur.align();
        this.blocks.set(this.activeBlock, AnnouncementBlock.imageBlock(
                cur.mediaId(), wp, al, cur.caption(), cur.imgW(), cur.imgH()));
        syncBlocksToSelected();
    }

    private void saveAndHotReload() {
        for (int i = 0; i < this.drafts.size(); i++) {
            AnnouncementView v = this.drafts.get(i);
            if (v.title == null || v.title.isBlank()) {
                selectIndex(i);
                if (this.titleField != null) {
                    this.titleField.focus();
                }
                this.statusColor = IOStyle.RED;
                this.statusText = tr("gui.module_shop.ann_admin.err_empty_title", "第 %s 条公告标题不能为空", i + 1);
                return;
            }
        }
        ClientAdminAuth.guard(() -> {
            List<AnnouncementView> ordered = new ArrayList<>(this.drafts.size());
            for (AnnouncementView v : this.drafts) {
                if (v.pinned) {
                    ordered.add(v);
                }
            }
            for (AnnouncementView v : this.drafts) {
                if (!v.pinned) {
                    ordered.add(v);
                }
            }
            ListTag listTag = new ListTag();
            for (int i = 0; i < ordered.size(); i++) {
                AnnouncementView v = ordered.get(i);
                if (v.id == null || v.id.isBlank()) {
                    v.id = "ann_" + UUID.randomUUID().toString().substring(0, 8);
                }
                v.order = i;
                AnnouncementView clean = v.copy();
                clean.title = v.title.trim();
                clean.category = v.category == null ? "" : v.category.trim();
                clean.date = v.date == null ? "" : v.date.trim();
                clean.content = clean.derivePlainContent();
                listTag.add(clean.toTag());
            }
            CompoundTag payload = new CompoundTag();
            payload.putString("action", "admin_announcements_save");
            payload.put("announcements", listTag);
            ServerboundShopActionPayload.send(payload);
            onClose();
        });
    }

    // ------------------------------------------------------------------ 键盘多段编辑支持

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        AnnouncementBlock cur = activeBlockObj();
        if (skia() && cur != null && AnnouncementBlock.TYPE_TEXT.equals(cur.type())
                && this.lineField != null && this.lineField.isVisible() && this.lineField.isFocused()
                && selected() != null) {
            EditBox box = this.lineField.editBox();
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                String val = box.getValue();
                int cursor = Math.max(0, Math.min(val.length(), box.getCursorPosition()));
                String left = val.substring(0, cursor);
                String right = val.substring(cursor);
                this.blocks.set(this.activeBlock, AnnouncementBlock.textBlock(left));
                insertTextBlockBelow(right, true);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP && this.activeBlock > 0) {
                this.activeBlock--;
                this.pendingEnsureActiveBlock = true;
                syncActiveBlockFieldOnly();
                box.moveCursorToEnd(false);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN && this.activeBlock + 1 < this.blocks.size()) {
                this.activeBlock++;
                this.pendingEnsureActiveBlock = true;
                syncActiveBlockFieldOnly();
                box.moveCursorToEnd(false);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && box.getCursorPosition() == 0
                    && box.highlightPos == 0 && this.activeBlock > 0) {
                AnnouncementBlock prev = this.blocks.get(this.activeBlock - 1);
                if (AnnouncementBlock.TYPE_TEXT.equals(prev.type())) {
                    String curTxt = cur.text() == null ? "" : cur.text();
                    String prevTxt = prev.text() == null ? "" : prev.text();
                    this.blocks.remove(this.activeBlock);
                    this.activeBlock--;
                    int joinPos = prevTxt.length();
                    this.blocks.set(this.activeBlock, AnnouncementBlock.textBlock(prevTxt + curTxt));
                    syncBlocksToSelected();
                    syncActiveBlockFieldOnly();
                    box.moveCursorTo(joinPos, false);
                    return true;
                }
            }
            if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_V) {
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip != null && (clip.contains("\n") || clip.contains("\r"))) {
                    String[] parts = clip.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                    String val = box.getValue();
                    int a = Math.min(box.getCursorPosition(), box.highlightPos);
                    int b = Math.max(box.getCursorPosition(), box.highlightPos);
                    a = Math.max(0, Math.min(val.length(), a));
                    b = Math.max(a, Math.min(val.length(), b));
                    String head = val.substring(0, a);
                    String tail = val.substring(b);
                    this.blocks.set(this.activeBlock, AnnouncementBlock.textBlock(head + parts[0]));
                    for (int k = 1; k < parts.length - 1 && this.blocks.size() < MAX_BLOCKS_PER_ANN; k++) {
                        this.activeBlock++;
                        this.blocks.add(this.activeBlock, AnnouncementBlock.textBlock(parts[k]));
                    }
                    if (this.blocks.size() < MAX_BLOCKS_PER_ANN) {
                        String lastPart = parts[parts.length - 1] + tail;
                        this.activeBlock++;
                        this.blocks.add(this.activeBlock, AnnouncementBlock.textBlock(lastPart));
                    }
                    syncBlocksToSelected();
                    syncActiveBlockFieldOnly();
                    box.moveCursorTo(parts[parts.length - 1].length(), false);
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ Skija 主绘制

    @Override
    protected void paint(Canvas canvas) {
        refreshInbox(false);
        float w = Math.max(320.0F, Math.min(MAX_W, this.width - 12.0F));
        float h = Math.max(230.0F, Math.min(MAX_H, this.height - 10.0F));
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);

        panel(canvas, x, y, w, h, this.title.getString());
        float closeX = x + w - PAD - 8.0F;
        float closeY = y + HEADER_H * 0.5F;
        float chv = hover("close", closeX - 6.0F, closeY - 6.0F, 12.0F, 12.0F);
        Draw.cross(canvas, closeX, closeY, 3.0F, 1.1F, Draw.lerp(IOStyle.TEXT_2, IOStyle.RED, chv));
        hit(closeX - 6.0F, closeY - 6.0F, 12.0F, 12.0F, this::onClose, null);

        float innerX = x + PAD;
        float innerW = w - PAD * 2.0F;
        float topY = y + HEADER_H + 6.0F;
        float footerBtnH = 18.0F;
        float footerBtnY = y + h - PAD - footerBtnH;
        float contentBottom = footerBtnY - 12.0F;

        float leftW = Math.min(120.0F, Math.max(106.0F, innerW * 0.28F));
        float gap = 8.0F;
        float rightX = innerX + leftW + gap;
        float rightW = innerX + innerW - rightX;

        paintLeftList(canvas, innerX, topY, leftW, contentBottom - topY);
        paintRightForm(canvas, rightX, topY, rightW, contentBottom - topY);

        if (!this.statusText.isEmpty()) {
            small(canvas, this.statusText, innerX, footerBtnY - 6.0F, innerW, this.statusColor);
        } else {
            small(canvas, tr("gui.module_shop.ann_admin.footer_hint",
                            "支持 §/& 颜色码 · 拖入图片可直接插入 · 置顶公告自动排在轮播最前"),
                    innerX, footerBtnY - 6.0F, innerW, IOStyle.TEXT_3);
        }

        float saveW = 100.0F;
        float cancelW = 62.0F;
        button(canvas, "cancel", tr("gui.module_shop.cancel", "取消"),
                innerX + innerW - saveW - 6.0F - cancelW, footerBtnY, cancelW, footerBtnH,
                Button.Kind.NORMAL, true, this::onClose);
        button(canvas, "save", tr("gui.module_shop.ann_admin.save", "保存并热更新"),
                innerX + innerW - saveW, footerBtnY, saveW, footerBtnH,
                Button.Kind.ACCENT, !this.uploadingImage, this::saveAndHotReload);
    }

    private void paintLeftList(Canvas canvas, float x, float y, float w, float h) {
        float topBtnH = 15.0F;
        float halfW = (w - 4.0F) * 0.5F;
        button(canvas, "add_ann", tr("gui.module_shop.ann_admin.add", "+ 新增"),
                x, y, halfW, topBtnH, Button.Kind.NORMAL, true, this::addAnnouncement);
        button(canvas, "del_ann", tr("gui.module_shop.v2.remove", "删除"),
                x + halfW + 4.0F, y, halfW, topBtnH, Button.Kind.DANGER, selected() != null, this::deleteSelected);

        float searchY = y + topBtnH + 3.0F;
        if (this.annSearchField != null) {
            this.annSearchField.bounds(x, searchY, w, 14.0F).visible(true);
        }

        float ctrlH = 15.0F;
        float ctrlY = y + h - ctrlH;
        float listY = searchY + 14.0F + 3.0F;
        float listH = Math.max(40.0F, ctrlY - 4.0F - listY);
        card(canvas, x, listY, w, listH);

        String query = this.annSearchField == null ? "" : this.annSearchField.value();
        List<Integer> filteredIndices = new ArrayList<>(this.drafts.size());
        for (int i = 0; i < this.drafts.size(); i++) {
            if (matchesAnnouncement(this.drafts.get(i), query)) {
                filteredIndices.add(i);
            }
        }

        if (this.drafts.isEmpty()) {
            paragraph(canvas, tr("gui.module_shop.ann_admin.empty", "暂无公告\n点上方「+ 新增」创建"),
                    x + 6.0F, listY + listH * 0.5F - 10.0F, w - 12.0F, Text.SMALL, IOStyle.TEXT_3, 3, true);
        } else if (filteredIndices.isEmpty()) {
            paragraph(canvas, Component.translatable("gui.module_shop.search.empty").getString(),
                    x + 6.0F, listY + listH * 0.5F - 6.0F, w - 12.0F, Text.SMALL, IOStyle.TEXT_3, 2, true);
        } else {
            float rowH = 25.0F;
            float rowGap = 3.0F;
            float padIn = 3.5F;
            float contentH = filteredIndices.size() * rowH
                    + Math.max(0, filteredIndices.size() - 1) * rowGap + padIn * 2.0F;
            float oy = beginList(canvas, this.listScroll, x + 2.0F, listY + 2.0F, w - 4.0F, listH - 4.0F, contentH);
            float rx = x + padIn + 1.0F;
            float rw = w - padIn * 2.0F - 10.0F;
            for (int vi = 0; vi < filteredIndices.size(); vi++) {
                final int idx = filteredIndices.get(vi);
                AnnouncementView item = this.drafts.get(idx);
                float ry = oy + padIn + vi * (rowH + rowGap);
                if (ry + rowH < listY || ry > listY + listH) {
                    continue;
                }
                boolean sel = idx == this.selectedIndex;
                float hv = hover("ann_row:" + idx, rx, ry, rw, rowH);
                int bg = sel ? Draw.alpha(IOStyle.BLUE, 0.16F) : Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hv);
                int border = sel ? IOStyle.BLUE : Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, hv);
                Draw.fill(canvas, rx, ry, rw, rowH, 3.0F, bg);
                Draw.stroke(canvas, rx, ry, rw, rowH, 3.0F, sel ? 1.1F : 0.7F, border);

                float pinW = 14.0F;
                float pinX = rx + rw - pinW - 2.5F;
                float pinY = ry + 3.0F;
                float pinH = 10.0F;
                float pinHv = hover("pin_row:" + idx, pinX, pinY, pinW, pinH);
                Draw.fill(canvas, pinX, pinY, pinW, pinH, 2.0F, item.pinned
                        ? Draw.alpha(IOStyle.ORANGE, 0.24F)
                        : Draw.alpha(IOStyle.GRAY_BTN, 0.7F + 0.3F * pinHv));
                Text.drawLine(canvas, tr("gui.module_shop.ann_admin.pin_short", "顶"),
                        AtomUi.BOLD, 5.0F, item.pinned ? IOStyle.ORANGE : IOStyle.TEXT_3,
                        pinX, pinY + pinH * 0.5F, pinW, Text.Align.CENTER);
                hit(pinX, pinY, pinW, pinH, () -> togglePinned(idx), null);

                String rawTitle = item.title == null || item.title.isBlank()
                        ? tr("gui.module_shop.home.ann_untitled", "未命名公告") : item.title;
                String plainTitle = ClientHomeCurrencyState.stripColorCodes(rawTitle);
                Text.drawLine(canvas, plainTitle, sel ? AtomUi.BOLD : AtomUi.REGULAR, 6.0F,
                        sel ? IOStyle.TEXT : IOStyle.TEXT_2, rx + 4.0F, ry + 8.0F,
                        Math.max(12.0F, pinX - rx - 7.0F) + 0.5F, Text.Align.LEFT);

                String sub = (item.category == null || item.category.isBlank() ? "" : item.category + " · ")
                        + (item.date == null ? "" : item.date);
                Text.drawLine(canvas, sub, AtomUi.REGULAR, 5.0F, IOStyle.TEXT_3,
                        rx + 4.0F, ry + 18.0F, Math.max(12.0F, rw - 8.0F) + 0.5F, Text.Align.LEFT);

                hit(rx, ry, Math.max(10.0F, pinX - rx - 2.0F), rowH, () -> selectIndex(idx), null);
            }
            endList(canvas);
        }

        AnnouncementView sel = selected();
        float btnGap = 3.0F;
        float pinW = w * 0.46F;
        float moveW = (w - pinW - btnGap * 2.0F) * 0.5F;
        button(canvas, "pin_sel", sel != null && sel.pinned
                        ? tr("gui.module_shop.ann_admin.unpin", "取消置顶")
                        : tr("gui.module_shop.ann_admin.pin", "设为置顶"),
                x, ctrlY, pinW, ctrlH, sel != null && sel.pinned ? Button.Kind.ACCENT : Button.Kind.NORMAL,
                sel != null, () -> togglePinned(this.selectedIndex));
        button(canvas, "move_up", "↑", x + pinW + btnGap, ctrlY, moveW, ctrlH, Button.Kind.NORMAL,
                sel != null && this.selectedIndex > 0, () -> moveSelected(-1));
        button(canvas, "move_dn", "↓", x + pinW + btnGap + moveW + btnGap, ctrlY, moveW, ctrlH, Button.Kind.NORMAL,
                sel != null && this.selectedIndex < this.drafts.size() - 1, () -> moveSelected(1));
    }

    private static boolean matchesAnnouncement(AnnouncementView item, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String plainTitle = ClientHomeCurrencyState.stripColorCodes(item.title == null ? "" : item.title);
        String plainContent = ClientHomeCurrencyState.stripColorCodes(item.content == null ? "" : item.content);
        StringBuilder blockText = new StringBuilder();
        if (item.blocks != null) {
            for (AnnouncementBlock b : item.blocks) {
                if (b.text() != null && !b.text().isEmpty()) {
                    blockText.append(' ').append(ClientHomeCurrencyState.stripColorCodes(b.text()));
                }
                if (b.caption() != null && !b.caption().isEmpty()) {
                    blockText.append(' ').append(b.caption());
                }
            }
        }
        return PageKit.matchesQuery(query, plainTitle, item.title, item.category, item.date,
                plainContent, blockText.toString());
    }

    private void paintRightForm(Canvas canvas, float x, float y, float w, float h) {
        AnnouncementView sel = selected();
        if (sel == null) {
            card(canvas, x, y, w, h);
            this.titleField.visible(false);
            this.categoryField.visible(false);
            this.dateField.visible(false);
            this.lineField.visible(false);
            this.captionField.visible(false);
            if (this.inboxSearchField != null) {
                this.inboxSearchField.visible(false);
            }
            center(canvas, tr("gui.module_shop.ann_admin.pick_hint", "请在左侧选择或新增一条公告进行编辑"),
                    x + 10.0F, y + h * 0.5F, w - 20.0F, IOStyle.TEXT_3);
            return;
        }

        float cy = y;
        // 第 1 行：标题 + 置顶开关
        float pinAreaW = 54.0F;
        this.titleField.bounds(x, cy, w - pinAreaW - 6.0F, 15.0F).visible(true);
        toggle(canvas, "pinned_toggle", x + w - pinAreaW, cy + 1.5F, sel.pinned,
                () -> togglePinned(this.selectedIndex));
        small(canvas, tr("gui.module_shop.ann_admin.pinned_label", "置顶"),
                x + w - pinAreaW + 25.0F, cy + 7.5F, 29.0F, sel.pinned ? IOStyle.ORANGE : IOStyle.TEXT_2);
        cy += 19.0F;

        // 第 2 行：分类 + 日期
        float catW = (w - 6.0F) * 0.48F;
        float dateW = w - 6.0F - catW;
        this.categoryField.bounds(x, cy, catW, 15.0F).visible(true);
        this.dateField.bounds(x + catW + 6.0F, cy, dateW, 15.0F).visible(true);
        cy += 19.0F;

        // 第 3 行：排版块工具栏（+文字 / +图片 / 收图箱 / 目录 / ↑ / ↓ / 删块）
        float g = 3.0F;
        float bDelW = 28.0F;
        float bMvW = 16.0F;
        float bDirW = 30.0F;
        float bBoxW = 36.0F;
        float bImgW = 46.0F;
        float bTxtW = 42.0F;
        float tx = x;
        button(canvas, "blk_add_txt", tr("gui.module_shop.ann_admin.add_para", "+ 文字"),
                tx, cy, bTxtW, 14.0F, Button.Kind.NORMAL, true, () -> insertTextBlockBelow("", true));
        tx += bTxtW + g;
        button(canvas, "blk_add_img", tr("gui.module_shop.announce.add_image", "插入图片"),
                tx, cy, bImgW, 14.0F, Button.Kind.ACCENT, !this.uploadingImage, this::pickAndInsertImage);
        tx += bImgW + g;
        button(canvas, "blk_inbox", tr("gui.module_shop.ann_admin.inbox_btn", "收图箱"),
                tx, cy, bBoxW, 14.0F, this.showInboxDrawer ? Button.Kind.ACCENT : Button.Kind.NORMAL,
                true, () -> {
                    this.showInboxDrawer = !this.showInboxDrawer;
                    refreshInbox(true);
                });
        tx += bBoxW + g;
        button(canvas, "blk_open_dir", tr("gui.module_shop.ann_admin.dir_btn", "目录"),
                tx, cy, bDirW, 14.0F, Button.Kind.NORMAL, true, ClientImageUploader::openInboxDir);

        float rightCtrlX = x + w - bDelW;
        button(canvas, "blk_del", tr("gui.module_shop.ann_admin.del_block", "删块"),
                rightCtrlX, cy, bDelW, 14.0F, Button.Kind.DANGER, this.blocks.size() > 1, this::removeActiveBlock);
        rightCtrlX -= bMvW + g;
        button(canvas, "blk_dn", "↓", rightCtrlX, cy, bMvW, 14.0F, Button.Kind.NORMAL,
                this.activeBlock + 1 < this.blocks.size(), () -> moveActiveBlock(1));
        rightCtrlX -= bMvW + g;
        button(canvas, "blk_up", "↑", rightCtrlX, cy, bMvW, 14.0F, Button.Kind.NORMAL,
                this.activeBlock > 0, () -> moveActiveBlock(-1));
        cy += 18.0F;

        // 底部块属性/编辑条预留高度
        AnnouncementBlock curBlock = activeBlockObj();
        boolean isImage = curBlock != null && AnnouncementBlock.TYPE_IMAGE.equals(curBlock.type());
        float editorAreaH = isImage ? 34.0F : 17.0F;
        float editorY = y + h - editorAreaH;
        float midH = Math.max(54.0F, editorY - 5.0F - cy);

        // 中部左右分栏：左侧块大纲 | 右侧实时预览（或收图箱快速选图抽屉）
        float outlineW = Math.round(w * 0.44F);
        float previewX = x + outlineW + 6.0F;
        float previewW = w - outlineW - 6.0F;

        card(canvas, x, cy, outlineW, midH);
        paintBlockOutline(canvas, x, cy, outlineW, midH);

        card(canvas, previewX, cy, previewW, midH);
        if (this.showInboxDrawer) {
            paintInboxDrawer(canvas, previewX, cy, previewW, midH);
        } else {
            if (this.inboxSearchField != null) {
                this.inboxSearchField.visible(false);
            }
            paintLivePreview(canvas, previewX, cy, previewW, midH);
        }

        // 底部活跃块编辑器
        if (isImage) {
            this.lineField.visible(false);
            paintImagePropsBar(canvas, curBlock, x, editorY, w);
        } else {
            this.captionField.visible(false);
            this.lineField.bounds(x, editorY, w, 16.0F).visible(true);
        }
    }

    private void paintBlockOutline(Canvas canvas, float x, float y, float w, float h) {
        float padIn = 3.0F;
        float rowH = 16.0F;
        float rowGap = 2.0F;
        float totalH = padIn * 2.0F + this.blocks.size() * rowH + Math.max(0, this.blocks.size() - 1) * rowGap;
        if (this.pendingEnsureActiveBlock) {
            this.pendingEnsureActiveBlock = false;
            this.blockScroll.setContent(totalH, h - 4.0F);
            float top = padIn + this.activeBlock * (rowH + rowGap);
            this.blockScroll.ensureVisible(top, top + rowH + padIn);
        }
        float oy = beginList(canvas, this.blockScroll, x + 2.0F, y + 2.0F, w - 4.0F, h - 4.0F, totalH);
        float rx = x + padIn + 1.0F;
        float rw = w - padIn * 2.0F - 10.0F;
        for (int i = 0; i < this.blocks.size(); i++) {
            final int bIdx = i;
            AnnouncementBlock b = this.blocks.get(i);
            float ry = oy + padIn + i * (rowH + rowGap);
            if (ry + rowH < y || ry > y + h) {
                continue;
            }
            boolean active = i == this.activeBlock;
            float hv = hover("blk:" + i, rx, ry, rw, rowH);
            int bg = active ? Draw.alpha(IOStyle.BLUE, 0.18F) : Draw.lerp(IOStyle.FIELD_BG, IOStyle.ROW_HOVER, hv);
            Draw.fill(canvas, rx, ry, rw, rowH, 2.5F, bg);
            if (active) {
                Draw.fill(canvas, rx, ry + 2.0F, 1.5F, rowH - 4.0F, 0.75F, IOStyle.BLUE);
            }
            boolean imgType = AnnouncementBlock.TYPE_IMAGE.equals(b.type());
            String badge = imgType ? "图" : "文";
            int badgeCol = imgType ? IOStyle.ORANGE : IOStyle.BLUE;
            Draw.fill(canvas, rx + 4.0F, ry + 3.0F, 11.0F, 10.0F, 2.0F, Draw.alpha(badgeCol, 0.22F));
            Text.drawLine(canvas, badge, AtomUi.BOLD, 5.0F, badgeCol, rx + 4.0F, ry + rowH * 0.5F, 11.0F,
                    Text.Align.CENTER);

            String summary;
            if (imgType) {
                String cap = b.caption() == null ? "" : b.caption().trim();
                summary = b.widthPct() + "% · " + alignLabel(b.align()) + (cap.isEmpty() ? "" : " · " + cap);
            } else {
                String plain = ClientHomeCurrencyState.stripColorCodes(b.text() == null ? "" : b.text());
                summary = plain.isEmpty() ? tr("gui.module_shop.ann_admin.empty_para", "（空段）") : plain;
            }
            Text.drawLine(canvas, summary, AtomUi.REGULAR, 5.75F, active ? IOStyle.TEXT : IOStyle.TEXT_2,
                    rx + 18.0F, ry + rowH * 0.5F, Math.max(8.0F, rw - 22.0F) + 0.5F, Text.Align.LEFT);

            hit(rx, ry, rw, rowH, () -> {
                this.activeBlock = bIdx;
                syncActiveBlockFieldOnly();
                AnnouncementBlock clicked = activeBlockObj();
                if (clicked != null && AnnouncementBlock.TYPE_IMAGE.equals(clicked.type())) {
                    this.captionField.focus();
                } else {
                    this.lineField.focus();
                }
            }, null);
        }
        endList(canvas);
    }

    private void paintLivePreview(Canvas canvas, float x, float y, float w, float h) {
        Text.drawLine(canvas, tr("gui.module_shop.ann_admin.live_preview", "实时排版预览"),
                AtomUi.BOLD, 5.25F, IOStyle.TEXT_3, x + 5.0F, y + 6.5F, w - 10.0F + 0.5F, Text.Align.LEFT);
        Draw.hairline(canvas, x + 3.0F, y + 12.0F, w - 6.0F, Draw.alpha(IOStyle.BORDER_DIM, 0.65F));

        float py = y + 14.0F;
        float ph = Math.max(24.0F, h - 16.0F);
        AnnouncementView sel = selected();
        String keyPrefix = "admin_prev:" + (sel == null ? "" : sel.id);
        this.previewRenderer.render(canvas, this.previewScroll, x + 4.0F, py, w - 8.0F, ph,
                Math.max(48.0F, ph * 0.65F), this.blocks, keyPrefix, Text.SMALL, 1.5F, IOStyle.TEXT_2,
                this.mouseX, this.mouseY, this.dtMs, true);
        registerScrollArea(this.previewScroll, x + 4.0F, py, w - 4.0F, ph);
    }

    private void paintInboxDrawer(Canvas canvas, float x, float y, float w, float h) {
        Text.drawLine(canvas, tr("gui.module_shop.ann_admin.inbox_title", "传图目录图片（点选插入）"),
                AtomUi.BOLD, 5.25F, IOStyle.ORANGE, x + 5.0F, y + 6.5F, w - 26.0F + 0.5F, Text.Align.LEFT);
        button(canvas, "close_inbox", "×", x + w - 18.0F, y + 1.5F, 15.0F, 11.0F,
                Button.Kind.NORMAL, true, () -> this.showInboxDrawer = false);
        Draw.hairline(canvas, x + 3.0F, y + 13.0F, w - 6.0F, IOStyle.BORDER_DIM);

        if (this.inboxSearchField != null) {
            this.inboxSearchField.bounds(x + 4.0F, y + 15.0F, w - 8.0F, 14.0F).visible(true);
        }

        float listY = y + 32.0F;
        float listH = Math.max(20.0F, h - 34.0F);
        if (this.inboxFiles.isEmpty()) {
            paragraph(canvas, tr("gui.module_shop.ann_admin.inbox_empty",
                            "目录暂无图片\n请拖入窗口或点「目录」放入 PNG/JPG/WEBP"),
                    x + 6.0F, listY + listH * 0.5F - 14.0F, w - 12.0F, 5.5F, IOStyle.TEXT_3, 3, true);
            return;
        }
        String query = this.inboxSearchField == null ? "" : this.inboxSearchField.value();
        List<Path> filteredInbox = new ArrayList<>(this.inboxFiles.size());
        for (Path p : this.inboxFiles) {
            String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
            if (PageKit.matchesQuery(query, name, p.toString())) {
                filteredInbox.add(p);
            }
        }
        if (filteredInbox.isEmpty()) {
            paragraph(canvas, Component.translatable("gui.module_shop.search.empty").getString(),
                    x + 6.0F, listY + listH * 0.5F - 6.0F, w - 12.0F, 5.5F, IOStyle.TEXT_3, 2, true);
            return;
        }
        float rowH = 15.0F;
        float contentH = filteredInbox.size() * (rowH + 2.0F) + 4.0F;
        float oy = beginList(canvas, this.inboxScroll, x + 2.0F, listY, w - 4.0F, listH, contentH);
        float rw = w - 14.0F;
        for (int i = 0; i < filteredInbox.size(); i++) {
            Path p = filteredInbox.get(i);
            float ry = oy + 2.0F + i * (rowH + 2.0F);
            if (ry + rowH < listY || ry > listY + listH) {
                continue;
            }
            float hv = hover("ann_inbox:" + i, x + 4.0F, ry, rw, rowH);
            Draw.fill(canvas, x + 4.0F, ry, rw, rowH, 2.0F, Draw.lerp(IOStyle.GRAY_BTN, IOStyle.ROW_HOVER, hv));
            String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
            Text.drawLine(canvas, name, AtomUi.REGULAR, 5.5F, IOStyle.TEXT,
                    x + 7.0F, ry + rowH * 0.5F, Math.max(8.0F, rw - 10.0F) + 0.5F, Text.Align.LEFT);
            hit(x + 4.0F, ry, rw, rowH, () -> uploadAndInsertImageFile(p), null);
        }
        endList(canvas);
    }

    private void paintImagePropsBar(Canvas canvas, AnnouncementBlock cur, float x, float y, float w) {
        // 第 1 行：宽度比例 40% / 70% / 100% + 对齐 左/中/右
        float cx = x;
        small(canvas, tr("gui.module_shop.ann_admin.img_width", "宽"), cx, y + 7.0F, 12.0F, IOStyle.TEXT_2);
        cx += 13.0F;
        for (int pct : WIDTH_PRESETS) {
            chip(canvas, "wp_" + pct, pct + "%", cx, y, 28.0F, 14.0F,
                    cur.widthPct() == pct, IOStyle.BLUE, () -> setActiveImageProps(pct, null));
            cx += 30.0F;
        }
        cx += 4.0F;
        small(canvas, tr("gui.module_shop.ann_admin.img_align", "对齐"), cx, y + 7.0F, 18.0F, IOStyle.TEXT_2);
        cx += 19.0F;
        for (String align : ALIGN_MODES) {
            chip(canvas, "al_" + align, alignLabel(align), cx, y, 22.0F, 14.0F,
                    align.equals(cur.align()), IOStyle.ORANGE, () -> setActiveImageProps(null, align));
            cx += 24.0F;
        }
        button(canvas, "img_replace", tr("gui.module_shop.ann_admin.img_replace", "换图"),
                x + w - 34.0F, y, 34.0F, 14.0F, Button.Kind.NORMAL, !this.uploadingImage, () -> {
                    this.uploadingImage = true;
                    this.statusColor = IOStyle.TEXT_2;
                    this.statusText = tr("gui.module_shop.media.uploading", "正在处理并上传图片…");
                    ClientImageUploader.pickAndUpload("announcement", 1280, new ClientImageUploader.UploadCallback() {
                        @Override
                        public void onUploaded(String mediaId, int nw, int nh) {
                            AnnouncementAdminScreen.this.uploadingImage = false;
                            AnnouncementAdminScreen.this.statusColor = IOStyle.GREEN;
                            AnnouncementAdminScreen.this.statusText = tr("msg.module_shop.media_upload_ok", "图片上传成功");
                            AnnouncementBlock latest = activeBlockObj();
                            if (latest != null && AnnouncementBlock.TYPE_IMAGE.equals(latest.type())) {
                                AnnouncementAdminScreen.this.blocks.set(
                                        AnnouncementAdminScreen.this.activeBlock,
                                        AnnouncementBlock.imageBlock(mediaId, latest.widthPct(), latest.align(),
                                                latest.caption(), nw, nh));
                                syncBlocksToSelected();
                            }
                        }

                        @Override
                        public void onFailed(String reason) {
                            AnnouncementAdminScreen.this.uploadingImage = false;
                            AnnouncementAdminScreen.this.statusColor = IOStyle.ORANGE;
                            AnnouncementAdminScreen.this.statusText = reason;
                        }
                    });
                });

        // 第 2 行：图注输入框
        this.captionField.bounds(x, y + 18.0F, w, 15.0F).visible(true);
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (button == 0 && !this.showInboxDrawer) {
            AnnouncementBlockRenderer.ImageHit hit = this.previewRenderer.hitTestImage(mx, my);
            if (hit != null) {
                ClickSound.play();
                Nav.open(new AnnouncementBlockRenderer.LightboxScreen(
                        hit.mediaId(), hit.caption(), hit.imgW(), hit.imgH()));
                return true;
            }
        }
        return super.onClick(mx, my, button);
    }

    private static String alignLabel(String align) {
        return switch (align == null ? "center" : align) {
            case "left" -> tr("gui.module_shop.ann_admin.align_left", "左");
            case "right" -> tr("gui.module_shop.ann_admin.align_right", "右");
            default -> tr("gui.module_shop.ann_admin.align_center", "中");
        };
    }

    // ------------------------------------------------------------------ 非 Skia 兜底

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
                Component.literal(tr("gui.module_shop.ann_admin.no_skia", "请在支持 Skija 的客户端环境中使用公告可视化编辑器")),
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
        if (button == 0 && this.fbBackBtn != null
                && IOStyle.inside(this.fbBackBtn[0], this.fbBackBtn[1], this.fbBackBtn[2], this.fbBackBtn[3], mouseX, mouseY)) {
            ClickSound.play();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
