package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.media.ClientMediaCache;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.StyledText;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.shop.home.AnnouncementBlock;
import com.atom.lib.design.atomchat.SkiaDraw;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.SamplingMode;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 公告图文混排共享测量、裁剪虚拟滚动渲染与点击灯箱放大组件（问题 #4）：
 * <p>同时服务于玩家个人主页（{@code ProfileHomePage}）与后台编辑器右侧实时所见即所得预览（{@code AnnouncementAdminScreen}）。
 */
public final class AnnouncementBlockRenderer {
    public record ImageHit(float x, float y, float w, float h, String mediaId, String caption, int imgW, int imgH) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private sealed interface MeasuredBlock permits MeasuredText, MeasuredImage {
        float y();

        float height();
    }

    private record MeasuredText(float y, float height, List<StyledText.Line> lines, float lineH)
            implements MeasuredBlock {
    }

    private record MeasuredImage(float y, float height, float boxX, float boxW, float boxH,
            String mediaId, String caption, int natW, int natH) implements MeasuredBlock {
    }

    private String cachedKey = "";
    private float totalHeight;
    private List<MeasuredBlock> measured = List.of();
    private final List<ImageHit> lastHits = new ArrayList<>();
    private float viewX, viewY, viewW, viewH;

    public void invalidate() {
        this.cachedKey = "";
    }

    /** 测量、裁剪、虚拟滚动绘制全部公告块，并更新本帧可点击的图片区域列表。 */
    public void render(Canvas canvas, ScrollController scroll, float x, float y, float w, float h,
            float maxImgH, List<AnnouncementBlock> blocks, String keyPrefix,
            float fontSize, float lineExtraGap, int baseColor,
            float mouseX, float mouseY, float dtMs, boolean allowHover) {
        this.lastHits.clear();
        this.viewX = x;
        this.viewY = y;
        this.viewW = w;
        this.viewH = h;

        float contentW = Math.max(24.0F, w - 8.0F);
        float maxImageHeight = Math.max(48.0F, maxImgH);
        int themeStep = Math.round(IOStyle.themeProgress() * 20.0F);
        String layoutKey = buildLayoutKey(keyPrefix, blocks, contentW, maxImageHeight, fontSize, themeStep);
        if (!layoutKey.equals(this.cachedKey)) {
            this.cachedKey = layoutKey;
            measureBlocks(blocks, contentW, maxImageHeight, fontSize, lineExtraGap, baseColor);
        }

        scroll.setContent(this.totalHeight, h);
        scroll.update();

        float scrollOff = scroll.offset();
        int saved = canvas.save();
        Draw.clip(canvas, x, y, w, h);
        try {
            for (MeasuredBlock block : this.measured) {
                float top = y + block.y() - scrollOff;
                float bottom = top + block.height();
                if (bottom < y || top > y + h) {
                    continue;
                }
                if (block instanceof MeasuredText mt) {
                    for (int li = 0; li < mt.lines().size(); li++) {
                        float ly = top + li * mt.lineH();
                        if (ly + mt.lineH() < y || ly > y + h) {
                            continue;
                        }
                        StyledText.draw(canvas, mt.lines().get(li), x, ly, fontSize);
                    }
                } else if (block instanceof MeasuredImage mi) {
                    float ix = x + mi.boxX();
                    boolean overImg = allowHover
                            && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
                            && mouseX >= ix && mouseX < ix + mi.boxW()
                            && mouseY >= top && mouseY < top + mi.boxH();
                    Image img = ClientMediaCache.getSkiaImage(mi.mediaId());
                    drawImageBlock(canvas, img, mi, ix, top, overImg);
                    this.lastHits.add(new ImageHit(ix, top, mi.boxW(), mi.boxH(),
                            mi.mediaId(), mi.caption(), mi.natW(), mi.natH()));
                }
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        scroll.drawBar(canvas, x, y, w + 4.0F, h, mouseX, mouseY, dtMs);
    }

    public @Nullable ImageHit hitTestImage(double mx, double my) {
        if (mx < this.viewX || mx >= this.viewX + this.viewW
                || my < this.viewY || my >= this.viewY + this.viewH) {
            return null;
        }
        for (int i = this.lastHits.size() - 1; i >= 0; i--) {
            ImageHit h = this.lastHits.get(i);
            if (h.contains(mx, my)) {
                return h;
            }
        }
        return null;
    }

    private void drawImageBlock(Canvas canvas, @Nullable Image img, MeasuredImage mi,
            float ix, float iy, boolean hovered) {
        float bw = mi.boxW();
        float bh = mi.boxH();
        float radius = 4.0F;
        if (img != null) {
            Draw.fill(canvas, ix, iy, bw, bh, radius, IOStyle.FIELD_BG);
            SkiaDraw.drawRoundedImage(canvas, img, ix, iy, bw, bh, radius, SamplingMode.LINEAR);
            Draw.stroke(canvas, ix, iy, bw, bh, radius, hovered ? 1.1F : 0.75F,
                    hovered ? IOStyle.BLUE : IOStyle.BORDER_DIM);
            if (hovered && bw >= 48.0F && bh >= 24.0F) {
                String badge = ClientHomeCurrencyState.tr("gui.module_shop.announce.click_zoom", "点击放大");
                float tagW = Math.min(bw - 8.0F, Text.width(badge, AtomUi.REGULAR, 5.25F) + 8.0F);
                float bx = ix + bw - tagW - 4.0F;
                float by = iy + bh - 14.0F;
                Draw.fill(canvas, bx, by, tagW, 10.5F, 2.5F, 0xAA0A0C12);
                Text.drawLine(canvas, badge, AtomUi.REGULAR, 5.25F, 0xFFFFFFFF,
                        bx, by + 5.25F, tagW, Text.Align.CENTER);
            }
        } else {
            Draw.fill(canvas, ix, iy, bw, bh, radius, Draw.alpha(IOStyle.FIELD_BG, 0.9F));
            Draw.stroke(canvas, ix, iy, bw, bh, radius, 0.75F, IOStyle.BORDER_DIM);
            String state = ClientMediaCache.isFetching(mi.mediaId())
                    ? ClientHomeCurrencyState.tr("gui.module_shop.media.loading", "图片加载中…")
                    : ClientHomeCurrencyState.tr("gui.module_shop.media.unavailable", "图片暂不可用");
            Text.drawLine(canvas, state, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                    ix + 6.0F, iy + bh * 0.5F, Math.max(8.0F, bw - 12.0F), Text.Align.CENTER);
        }

        if (mi.caption() != null && !mi.caption().isBlank()) {
            float capY = iy + bh + 6.0F;
            Text.drawLine(canvas, mi.caption(), AtomUi.REGULAR, 5.5F, IOStyle.TEXT_2,
                    ix, capY, bw, Text.Align.CENTER);
        }
    }

    private void measureBlocks(@Nullable List<AnnouncementBlock> blocks, float availW,
            float maxImgH, float fontSize, float lineExtraGap, int baseColor) {
        List<MeasuredBlock> out = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            this.measured = out;
            this.totalHeight = 0.0F;
            return;
        }
        float lineH = StyledText.lineHeight(fontSize) + lineExtraGap;
        float curY = 0.0F;
        for (int i = 0; i < blocks.size(); i++) {
            AnnouncementBlock b = blocks.get(i);
            if (b == null) {
                continue;
            }
            if (AnnouncementBlock.TYPE_IMAGE.equals(b.type())) {
                Image img = ClientMediaCache.getSkiaImage(b.mediaId());
                int natW = img != null ? img.getWidth() : (b.imgW() > 0 ? b.imgW() : 16);
                int natH = img != null ? img.getHeight() : (b.imgH() > 0 ? b.imgH() : 9);
                int pct = Math.max(25, Math.min(100, b.widthPct() <= 0 ? 100 : b.widthPct()));
                float targetW = Math.max(28.0F, availW * (pct / 100.0F));
                float drawW = targetW;
                float drawH = drawW * ((float) natH / Math.max(1, natW));
                if (drawH > maxImgH) {
                    float scale = maxImgH / drawH;
                    drawH = maxImgH;
                    drawW = Math.max(24.0F, drawW * scale);
                }
                float boxX;
                if ("left".equals(b.align())) {
                    boxX = 0.0F;
                } else if ("right".equals(b.align())) {
                    boxX = Math.max(0.0F, availW - drawW);
                } else {
                    boxX = Math.max(0.0F, (availW - drawW) * 0.5F);
                }
                boolean hasCaption = b.caption() != null && !b.caption().isBlank();
                float blockH = drawH + (hasCaption ? 11.0F : 0.0F);
                out.add(new MeasuredImage(curY, blockH, boxX, drawW, drawH,
                        b.mediaId(), b.caption() == null ? "" : b.caption(), natW, natH));
                curY += blockH + (i + 1 < blocks.size() ? 6.0F : 2.0F);
            } else {
                String raw = b.text() == null ? "" : b.text();
                List<StyledText.Line> lines = raw.isEmpty()
                        ? List.of()
                        : StyledText.wrap(ClientHomeCurrencyState.parseStyled(raw, baseColor), availW, fontSize, 0);
                float blockH = lines.isEmpty() ? lineH * 0.55F : lines.size() * lineH;
                out.add(new MeasuredText(curY, blockH, lines, lineH));
                curY += blockH + (i + 1 < blocks.size() ? 3.5F : 0.0F);
            }
        }
        this.measured = out;
        this.totalHeight = Math.max(lineH, curY);
    }

    private static String buildLayoutKey(String prefix, @Nullable List<AnnouncementBlock> blocks,
            float availW, float maxImgH, float fontSize, int themeStep) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(prefix).append('|').append(Math.round(availW)).append('|').append(Math.round(maxImgH))
                .append('|').append(Math.round(fontSize * 10.0F)).append('|').append(themeStep);
        if (blocks != null) {
            for (AnnouncementBlock b : blocks) {
                if (b == null) {
                    continue;
                }
                sb.append('#').append(b.type());
                if (AnnouncementBlock.TYPE_IMAGE.equals(b.type())) {
                    Image img = ClientMediaCache.getSkiaImage(b.mediaId());
                    sb.append(':').append(b.mediaId()).append(':').append(b.widthPct()).append(':')
                            .append(b.align()).append(':').append(b.caption()).append(':')
                            .append(img == null ? b.imgW() + "x" + b.imgH()
                                    : img.getWidth() + "x" + img.getHeight());
                } else {
                    sb.append(':').append(b.text());
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 图片灯箱放大弹窗

    /** 全屏图片放大预览灯箱（打开期间主页轮播自动暂停，ESC 或点外侧关闭）。 */
    public static final class LightboxScreen extends SkiaDialog {
        private static volatile boolean open;

        private final String mediaId;
        private final String caption;
        private final int fallbackW;
        private final int fallbackH;
        private float[] imageRect;
        private int[] fbBackBtn;

        public LightboxScreen(String mediaId, String caption, int fallbackW, int fallbackH) {
            super(Component.literal(caption == null || caption.isBlank()
                    ? ClientHomeCurrencyState.tr("gui.module_shop.announce.lightbox_title", "图片预览")
                    : caption));
            this.mediaId = mediaId == null ? "" : mediaId;
            this.caption = caption == null ? "" : caption;
            this.fallbackW = fallbackW;
            this.fallbackH = fallbackH;
        }

        public static boolean isOpen() {
            return open;
        }

        @Override
        protected void initSkia() {
            open = true;
        }

        @Override
        public void onClose() {
            open = false;
            Nav.back();
        }

        @Override
        public void removed() {
            open = false;
            super.removed();
        }

        @Override
        protected boolean onClick(double mx, double my, int button) {
            if (button == 0 && this.imageRect != null
                    && !(mx >= this.imageRect[0] && mx < this.imageRect[0] + this.imageRect[2]
                    && my >= this.imageRect[1] && my < this.imageRect[1] + this.imageRect[3])) {
                onClose();
                return true;
            }
            return false;
        }

        @Override
        protected void paint(Canvas canvas) {
            Image img = ClientMediaCache.getSkiaImage(this.mediaId);
            int natW = img != null ? img.getWidth() : Math.max(16, this.fallbackW);
            int natH = img != null ? img.getHeight() : Math.max(9, this.fallbackH);

            float maxW = Math.max(160.0F, this.width - 36.0F);
            float maxH = Math.max(110.0F, this.height - 64.0F);
            double scale = Math.min(maxW / natW, maxH / natH);
            float drawW = Math.max(60.0F, (float) (natW * scale));
            float drawH = Math.max(40.0F, (float) (natH * scale));
            float frameW = drawW + 16.0F;
            float frameH = drawH + (this.caption.isEmpty() ? 32.0F : 46.0F);
            float fx = Math.round((this.width - frameW) * 0.5F);
            float fy = Math.round((this.height - frameH) * 0.5F);

            Draw.outerShadow(canvas, fx, fy, frameW, frameH, 6.0F, 14.0F, 4.0F, IOStyle.SHADOW_TINT);
            Draw.fill(canvas, fx, fy, frameW, frameH, 6.0F, IOStyle.CONTENT);
            Draw.stroke(canvas, fx, fy, frameW, frameH, 6.0F, 0.85F, IOStyle.BORDER);

            float ix = fx + 8.0F;
            float iy = fy + 8.0F;
            this.imageRect = new float[]{fx, fy, frameW, frameH};
            if (img != null) {
                SkiaDraw.drawRoundedImage(canvas, img, ix, iy, drawW, drawH, 4.0F, SamplingMode.LINEAR);
            } else {
                Draw.fill(canvas, ix, iy, drawW, drawH, 4.0F, IOStyle.FIELD_BG);
                center(canvas, ClientHomeCurrencyState.tr("gui.module_shop.media.loading", "图片加载中…"),
                        ix, iy + drawH * 0.5F, drawW, IOStyle.TEXT_3);
            }

            float footerY = iy + drawH + 5.0F;
            if (!this.caption.isEmpty()) {
                small(canvas, this.caption, ix, footerY + 5.0F, frameW - 76.0F, IOStyle.TEXT);
                footerY += 13.0F;
            }
            small(canvas, natW + "×" + natH, ix, footerY + 6.0F, 80.0F, IOStyle.TEXT_3);
            button(canvas, "close_lb", ClientHomeCurrencyState.tr("gui.module_shop.close", "关闭"),
                    fx + frameW - 60.0F, iy + drawH + 4.0F, 52.0F, 16.0F, Button.Kind.NORMAL, true, this::onClose);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            if (skiaRender(g, mouseX, mouseY, partialTick)) {
                return;
            }
            open = true;
            super.render(g, mouseX, mouseY, partialTick);
            int pw = 240;
            int ph = 96;
            int x = (this.width - pw) / 2;
            int y = (this.height - ph) / 2;
            Bricks.panel(g, x, y, pw, ph);
            IOStyle.centerAt(g, this.font, this.title, x + pw / 2, y + 18, IOStyle.ORANGE);
            IOStyle.centerAt(g, this.font,
                    Component.literal(ClientHomeCurrencyState.tr("gui.module_shop.ann_admin.no_skia",
                            "请在支持 Skija 的客户端环境中使用图片预览")),
                    x + pw / 2, y + 44, IOStyle.TEXT_2);
            int bx = x + (pw - 70) / 2;
            int by = y + ph - 26;
            IOStyle.button(g, bx, by, 70, 16, Component.translatable("gui.module_shop.cfg.back_btn"),
                    this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
            this.fbBackBtn = new int[]{bx, by, 70, 16};
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (skia()) {
                return skiaClicked(mouseX, mouseY, button);
            }
            if (button == 0 && this.fbBackBtn != null && IOStyle.inside(
                    this.fbBackBtn[0], this.fbBackBtn[1], this.fbBackBtn[2], this.fbBackBtn[3], mouseX, mouseY)) {
                ClickSound.play();
                onClose();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }
}
