package cn.autoforged.module_shop.client.ui.v2;

import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;

/**
 * UI v2 统一卡片网格（方块视图）：自适应列数 + 虚拟裁剪绘制 + 平滑滚动 + 卡片/卡内按钮命中。
 *
 * <p>实例放在页面静态字段里，服务端回包重建 {@code ShopScreen} 后仍保留滚动位置。
 */
public final class CardGrid {
    /** 全部 6 个前端页面统一的方块视图栅格规格（对齐密集紧凑卡片比例：全宽 ~12 列、带侧栏 ~10 列）。 */
    public static final float UNIFIED_PAD = 6.0F;
    public static final float UNIFIED_GAP = 4.0F;
    public static final float UNIFIED_MIN_W = 50.0F;
    public static final float UNIFIED_H = 54.0F;

    /** 单张卡片绘制回调：(cx, cy, cw, ch) 为卡片几何，hover 为 0..1 悬停动画。 */
    public interface CardPainter {
        void paint(Canvas c, int index, float cx, float cy, float cw, float ch, float hover);
    }

    /** 点击命中结果：button 为卡内按钮或子区域 id；点卡片主体时 button 为 null。 */
    public record Hit(int index, String button) {
    }

    private final ScrollController scroll = new ScrollController();
    private final List<float[]> hitRects = new ArrayList<>();
    private final List<String> hitIds = new ArrayList<>();
    private float[] hoverAnim = new float[0];

    private float x;
    private float y;
    private float w;
    private float h;
    private float pad = 8.0F;
    private float gap = 6.0F;
    private int cols = 1;
    private float cardW = 120.0F;
    private float cardH = 80.0F;
    private int count;
    private int hoverIndex = -1;
    private float mx;
    private float my;

    /** 使用全站统一卡片尺寸规格布局（推荐 6 个前端页面统一调用）。 */
    public CardGrid layoutUnified(float x, float y, float w, float h) {
        return layout(x, y, w, h, UNIFIED_PAD, UNIFIED_GAP, UNIFIED_MIN_W, UNIFIED_H);
    }

    /** 根据可用宽度自动计算列数与卡片宽度（右侧留 5 单位给滚动条）。 */
    public CardGrid layout(float x, float y, float w, float h, float pad, float gap, float minCardW, float cardH) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.pad = pad;
        this.gap = gap;
        this.cardH = cardH;
        this.count = 0;
        float innerW = Math.max(40.0F, w - pad * 2.0F - 5.0F);
        this.cols = Math.max(1, (int) Math.floor((innerW + gap) / (Math.max(36.0F, minCardW) + gap)));
        this.cardW = (innerW - gap * (this.cols - 1)) / this.cols;
        return this;
    }

    public int cols() {
        return this.cols;
    }

    public float cardW() {
        return this.cardW;
    }

    public float cardH() {
        return this.cardH;
    }

    public int hoverIndex() {
        return this.hoverIndex;
    }

    /** 绘制可见卡片，并同步裁剪 Skija 与 ItemOverlay 物品层。 */
    public void render(Canvas c, ItemOverlay items, float mouseX, float mouseY, float dtMs, int count, CardPainter painter) {
        this.count = count;
        this.mx = mouseX;
        this.my = mouseY;
        this.hitRects.clear();
        this.hitIds.clear();
        if (this.hoverAnim.length != count) {
            float[] next = new float[count];
            System.arraycopy(this.hoverAnim, 0, next, 0, Math.min(count, this.hoverAnim.length));
            this.hoverAnim = next;
        }
        int rows = count <= 0 ? 0 : (count + this.cols - 1) / this.cols;
        float stepY = this.cardH + this.gap;
        float contentH = rows == 0 ? 0.0F : this.pad * 2.0F + rows * this.cardH + (rows - 1) * this.gap;
        this.scroll.setContent(contentH, this.h);
        this.scroll.update();
        float off = this.scroll.offset();

        this.hoverIndex = hitCell(mouseX, mouseY, off);

        int saved = c.save();
        Draw.clip(c, this.x, this.y, this.w, this.h);
        if (items != null) {
            items.pushClip(this.x, this.y, this.w, this.h);
        }
        try {
            if (rows > 0) {
                int firstRow = Math.max(0, (int) Math.floor((off - this.pad) / stepY));
                int lastRow = Math.min(rows - 1, (int) Math.floor((off + this.h - this.pad) / stepY));
                for (int r = firstRow; r <= lastRow; r++) {
                    float cy = this.y + this.pad + r * stepY - off;
                    for (int col = 0; col < this.cols; col++) {
                        int i = r * this.cols + col;
                        if (i >= count) {
                            break;
                        }
                        float cx = this.x + this.pad + col * (this.cardW + this.gap);
                        boolean over = i == this.hoverIndex;
                        this.hoverAnim[i] = Motion.approach(this.hoverAnim[i], over ? 1.0F : 0.0F, dtMs,
                                over ? Motion.HOVER_MS : Motion.HOVER_OUT_MS);
                        painter.paint(c, i, cx, cy, this.cardW, this.cardH, this.hoverAnim[i]);
                    }
                }
            }
        } finally {
            if (items != null) {
                items.popClip();
            }
            c.restoreToCount(saved);
        }
        this.scroll.drawBar(c, this.x, this.y, this.w, this.h, mouseX, mouseY, dtMs);
    }

    /** 统一卡片底壳：圆角矩形 + 左侧/顶部色条 + 悬停边框亮起。 */
    public static void cardBg(Canvas c, float x, float y, float w, float h, float hover, int accent, boolean tinted, boolean dimmed) {
        int base = dimmed ? Draw.alpha(IOStyle.CARD, 0.72F) : IOStyle.CARD;
        int overBg = dimmed ? Draw.alpha(IOStyle.ROW_HOVER, 0.85F) : IOStyle.ROW_HOVER;
        int bg = Draw.lerp(base, overBg, hover);
        Draw.fill(c, x, y, w, h, V2.R, bg);
        if (tinted && accent != 0) {
            Draw.fill(c, x, y, w, h, V2.R, Draw.alpha(accent, dimmed ? 0.04F : 0.08F));
        }
        int targetBorder = accent != 0 ? Draw.lerp(IOStyle.BORDER_HOVER, accent, 0.6F) : IOStyle.BORDER_HOVER;
        int border = Draw.lerp(IOStyle.BORDER_DIM, targetBorder, Math.max(hover, tinted && accent != 0 ? 0.35F : 0.0F));
        Draw.stroke(c, x, y, w, h, V2.R, 0.6F, border);
        if (accent != 0) {
            Draw.fill(c, x + 6.0F, y + 0.5F, Math.max(8.0F, w - 12.0F), 1.5F, 0.75F,
                    dimmed ? Draw.alpha(accent, 0.45F) : accent);
        }
    }

    /** 几何五角星（不依赖字体子集，避免方块缺失）。 */
    public static void star(Canvas c, float cx, float cy, float outerR, boolean filled, int color) {
        float innerR = outerR * 0.46F;
        try (var pb = new io.github.humbleui.skija.PathBuilder()) {
            for (int i = 0; i < 10; i++) {
                double ang = -Math.PI / 2.0 + i * Math.PI / 5.0;
                float r = (i & 1) == 0 ? outerR : innerR;
                float px = cx + (float) (Math.cos(ang) * r);
                float py = cy + (float) (Math.sin(ang) * r);
                if (i == 0) {
                    pb.moveTo(px, py);
                } else {
                    pb.lineTo(px, py);
                }
            }
            pb.closePath();
            try (var path = pb.detach(); var paint = new io.github.humbleui.skija.Paint()) {
                paint.setAntiAlias(true);
                paint.setColor(color);
                if (filled) {
                    paint.setMode(io.github.humbleui.skija.PaintMode.FILL);
                } else {
                    paint.setMode(io.github.humbleui.skija.PaintMode.STROKE);
                    paint.setStrokeWidth(0.85F);
                }
                c.drawPath(path, paint);
            }
        }
    }

    /**
     * 卡片模式下的紧凑排序条（占用 {@link V2#HEAD} 高度）：返回选中的排序值，未点中则返回 null。
     * labels 与 sortVals 等长；支持点击同一选项切换升/降序（若该列有双态）。
     */
    public static int drawSortBar(Canvas c, float x, float y, float w, float mx, float my,
                                  String[] labels, int activeIdx, String rightText, List<float[]> outRects) {
        outRects.clear();
        Draw.fill(c, x, y, w, V2.HEAD, 0, IOStyle.SIDEBAR);
        Draw.hairline(c, x, y + V2.HEAD - 0.5F, w, IOStyle.BORDER_DIM);
        float cx = x + 8.0F;
        float cy = y + V2.HEAD / 2.0F;
        for (int i = 0; i < labels.length; i++) {
            float pw = Math.max(24.0F, V2.width(labels[i], V2.FS_XS, i == activeIdx) + 10.0F);
            float py = y + 2.0F;
            float ph = V2.HEAD - 4.0F;
            boolean over = mx >= cx && mx < cx + pw && my >= py && my < py + ph;
            boolean sel = i == activeIdx;
            if (sel) {
                Draw.fill(c, cx, py, pw, ph, 2.5F, Draw.alpha(IOStyle.BLUE, 0.22F));
                Draw.stroke(c, cx, py, pw, ph, 2.5F, 0.5F, Draw.alpha(IOStyle.BLUE, 0.65F));
            } else if (over) {
                Draw.fill(c, cx, py, pw, ph, 2.5F, Draw.alpha(IOStyle.ROW_HOVER, 0.9F));
            }
            V2.text(c, labels[i], V2.FS_XS, sel ? IOStyle.TEXT : over ? IOStyle.TEXT : IOStyle.TEXT_2,
                    cx, cy, pw, cn.autoforged.module_shop.client.ui.render.Text.Align.CENTER);
            outRects.add(new float[]{cx, py, pw, ph});
            cx += pw + 4.0F;
        }
        if (rightText != null && cx + 8.0F < x + w - 8.0F) {
            V2.text(c, rightText, V2.FS_XS, IOStyle.TEXT_3, cx + 4.0F, cy, x + w - 8.0F - (cx + 4.0F),
                    cn.autoforged.module_shop.client.ui.render.Text.Align.RIGHT);
        }
        return outRects.size();
    }

    /** 卡内按钮：绘制并登记裁剪范围内的点击热区。 */
    public void button(Canvas c, int cardIndex, String id, String label, V2.Btn kind, float bx, float by, float bw, float bh) {
        boolean over = insideViewport(this.mx, this.my)
                && this.mx >= bx && this.mx < bx + bw && this.my >= by && this.my < by + bh;
        V2.button(c, label, kind, bx, by, bw, bh, over ? 1.0F : 0.0F);
        if (kind != V2.Btn.DISABLED) {
            hitZone(cardIndex, id, bx, by, bw, bh);
        }
    }

    /** 登记卡片内的子命中区（例如收藏星星、特定操作区）。 */
    public void hitZone(int cardIndex, String id, float hx, float hy, float hw, float hh) {
        float x0 = Math.max(this.x, hx);
        float y0 = Math.max(this.y, hy);
        float x1 = Math.min(this.x + this.w, hx + hw);
        float y1 = Math.min(this.y + this.h, hy + hh);
        if (x1 > x0 && y1 > y0) {
            this.hitRects.add(new float[]{x0, y0, x1 - x0, y1 - y0, cardIndex});
            this.hitIds.add(id);
        }
    }

    /** 点击判定：优先卡内按钮/子区，其次卡片主体；点中缝隙返回 null。 */
    public Hit click(double mouseX, double mouseY) {
        if (!insideViewport(mouseX, mouseY)) {
            return null;
        }
        for (int i = this.hitRects.size() - 1; i >= 0; i--) {
            float[] r = this.hitRects.get(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                return new Hit((int) r[4], this.hitIds.get(i));
            }
        }
        int cell = hitCell((float) mouseX, (float) mouseY, this.scroll.offset());
        return cell >= 0 ? new Hit(cell, null) : null;
    }

    public boolean pressBar(double mouseX, double mouseY) {
        return this.scroll.pressBar(mouseX, mouseY, this.x, this.y, this.w, this.h);
    }

    public boolean dragBar(double mouseY) {
        return this.scroll.dragBar(mouseY, this.h);
    }

    public boolean releaseBar() {
        return this.scroll.releaseBar();
    }

    public boolean wheel(double mouseX, double mouseY, double amount, boolean page) {
        if (!insideViewport(mouseX, mouseY)) {
            return false;
        }
        if (page) {
            this.scroll.scrollTo(this.scroll.offset() - (float) Math.signum(amount) * this.h * 0.9F, true);
        } else {
            this.scroll.wheel(amount * 1.25);
        }
        return true;
    }

    public void scrollToTop() {
        this.scroll.scrollTo(0, false);
    }

    /** 当前可见首张/末张编号（1-based，给底栏「显示 a–b」用）。 */
    public int firstVisible() {
        if (this.count <= 0) {
            return 0;
        }
        int firstRow = Math.max(0, (int) Math.floor((this.scroll.offset() - this.pad) / (this.cardH + this.gap)));
        return Math.min(this.count, firstRow * this.cols + 1);
    }

    public int lastVisible() {
        if (this.count <= 0) {
            return 0;
        }
        int lastRow = Math.max(0, (int) Math.floor((this.scroll.offset() + this.h - this.pad - 1.0F) / (this.cardH + this.gap)));
        return Math.min(this.count, (lastRow + 1) * this.cols);
    }

    private boolean insideViewport(double px, double py) {
        return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
    }

    private int hitCell(float px, float py, float off) {
        if (!insideViewport(px, py) || this.count <= 0) {
            return -1;
        }
        float lx = px - (this.x + this.pad);
        float ly = py - this.y + off - this.pad;
        if (lx < 0.0F || ly < 0.0F) {
            return -1;
        }
        float stepX = this.cardW + this.gap;
        float stepY = this.cardH + this.gap;
        int col = (int) Math.floor(lx / stepX);
        int row = (int) Math.floor(ly / stepY);
        if (col < 0 || col >= this.cols || row < 0) {
            return -1;
        }
        // 点在卡片之间的间距里不算命中
        if ((lx - col * stepX) >= this.cardW || (ly - row * stepY) >= this.cardH) {
            return -1;
        }
        int idx = row * this.cols + col;
        return idx >= 0 && idx < this.count ? idx : -1;
    }
}
