package cn.autoforged.module_shop.client.ui.v2;

import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;

/**
 * UI v2 的统一列表（表格）：表头（可排序）+ 平滑滚动的行 + 行内按钮命中 + 滚动条。
 *
 * <p>用法（每帧）：{@link #layout} 给几何与列规格 → {@link #render} 传入行数与每行的绘制回调；
 * 回调里用 {@link #button} 画行内按钮（同时登记命中区）。输入时 {@link #click} 返回命中的按钮或行。
 *
 * <p>实例应放在页面的<b>静态字段</b>里：商店界面每次服务端操作后会重建，滚动位置与排序要跨重建保留。
 */
public final class ListTable {
    /** 每行的绘制回调：y 为行顶，hover 为悬停动画值（0..1）。 */
    public interface RowPainter {
        void paint(Canvas c, int row, float y, float hover);
    }

    /** 一次点击命中的结果：button 为行内按钮 id（点在行的空白处则为 null）。 */
    public record Hit(int row, String button) {
    }

    private final ScrollController scroll = new ScrollController();
    private final List<float[]> buttonRects = new ArrayList<>();
    private final List<String> buttonIds = new ArrayList<>();
    private final List<float[]> sortRects = new ArrayList<>();
    private float[] hoverAnim = new float[0];

    private float x;
    private float y;
    private float w;
    private float h;
    private float rowH = V2.ROW;
    private float[][] cols = new float[0][2];
    private String[] heads = new String[0];
    private Text.Align[] aligns = new Text.Align[0];
    private boolean[] sortable = new boolean[0];
    private int rows;
    private int hoverRow = -1;
    private float mx;
    private float my;

    /** 当前排序列（-1 = 不排序）与方向。 */
    public int sortCol = -1;
    public boolean sortDesc;

    /**
     * 几何：表格区域（含表头）与列规格（见 {@link V2#columns}）。heads / aligns 与列一一对应；
     * sortable 为 null 表示都不可排序。
     */
    public ListTable layout(float x, float y, float w, float h, float[][] cols, String[] heads, Text.Align[] aligns,
                            boolean[] sortable) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.cols = cols;
        this.heads = heads;
        this.aligns = aligns;
        this.sortable = sortable == null ? new boolean[cols.length] : sortable;
        return this;
    }

    public ListTable rowHeight(float rowH) {
        this.rowH = rowH;
        return this;
    }

    public float[][] cols() {
        return this.cols;
    }

    public float listTop() {
        return this.y + V2.HEAD;
    }

    public float listHeight() {
        return this.h - V2.HEAD;
    }

    public int hoverRow() {
        return this.hoverRow;
    }

    /** 绘制。items 非空时会把物品层裁剪到列表区域。 */
    public void render(Canvas c, ItemOverlay items, float mouseX, float mouseY, float dtMs, int rows, RowPainter painter) {
        this.rows = rows;
        this.mx = mouseX;
        this.my = mouseY;
        this.buttonRects.clear();
        this.buttonIds.clear();
        this.sortRects.clear();
        if (this.hoverAnim.length != rows) {
            float[] next = new float[rows];
            System.arraycopy(this.hoverAnim, 0, next, 0, Math.min(rows, this.hoverAnim.length));
            this.hoverAnim = next;
        }
        // 表头 + 排序命中区
        V2.tableHead(c, this.x, this.y, this.w, this.cols, this.heads, this.aligns, this.sortCol, this.sortDesc);
        for (int i = 0; i < this.cols.length; i++) {
            if (i < this.sortable.length && this.sortable[i]) {
                this.sortRects.add(new float[]{this.cols[i][0], this.y, this.cols[i][1], V2.HEAD, i});
            }
        }
        float top = listTop();
        float lh = listHeight();
        this.scroll.setContent(rows * this.rowH, lh);
        this.scroll.update();
        float off = this.scroll.offset();
        boolean inside = mouseX >= this.x && mouseX < this.x + this.w && mouseY >= top && mouseY < top + lh;
        this.hoverRow = -1;
        if (inside) {
            int r = (int) Math.floor((mouseY - top + off) / this.rowH);
            if (r >= 0 && r < rows) {
                this.hoverRow = r;
            }
        }
        int saved = c.save();
        Draw.clip(c, this.x, top, this.w, lh);
        if (items != null) {
            items.pushClip(this.x, top, this.w, lh);
        }
        try {
            int first = Math.max(0, (int) Math.floor(off / this.rowH));
            int last = Math.min(rows - 1, (int) Math.floor((off + lh) / this.rowH));
            for (int r = first; r <= last; r++) {
                float ry = top + r * this.rowH - off;
                this.hoverAnim[r] = Motion.approach(this.hoverAnim[r], r == this.hoverRow ? 1.0F : 0.0F, dtMs,
                        r == this.hoverRow ? Motion.HOVER_MS : Motion.HOVER_OUT_MS);
                V2.rowBg(c, this.x, ry, this.w, this.rowH, r, this.hoverAnim[r], false);
                painter.paint(c, r, ry, this.hoverAnim[r]);
            }
        } finally {
            if (items != null) {
                items.popClip();
            }
            c.restoreToCount(saved);
        }
        this.scroll.drawBar(c, this.x, top, this.w, lh, mouseX, mouseY, dtMs);
    }

    /** 选中行的强调底（在 painter 里先调用）。 */
    public void selected(Canvas c, float rowY) {
        Draw.fill(c, this.x, rowY, this.w, this.rowH, 0, Draw.alpha(IOStyle.BLUE, 0.16F));
        Draw.fill(c, this.x, rowY, 1.5F, this.rowH, 0, IOStyle.BLUE);
    }

    /**
     * 行内按钮：画出并登记命中区。按钮右对齐在 [x, x+w] 内（w 为列宽），返回按钮左边 x，便于再往左排。
     */
    public float button(Canvas c, int row, String id, String label, V2.Btn kind, float right, float rowY, float rowHover) {
        float bw = V2.buttonWidth(label);
        float bh = Math.min(11.0F, this.rowH - 4);
        float bx = right - bw;
        float by = rowY + (this.rowH - bh) / 2;
        boolean over = this.mx >= bx && this.mx < bx + bw && this.my >= by && this.my < by + bh;
        V2.Btn k = kind;
        if (kind == V2.Btn.NORMAL && rowHover > 0.5F && over) {
            k = V2.Btn.PRIMARY;
        }
        V2.button(c, label, k, bx, by, bw, bh, over ? 1.0F : 0.0F);
        if (kind != V2.Btn.DISABLED) {
            this.buttonRects.add(new float[]{bx, by, bw, bh, row});
            this.buttonIds.add(id);
        }
        return bx - 3;
    }


    // ------------------------------------------------------------------ 输入

    /** 点击：表头可排序列 → 切换排序（返回 Hit(-1, "sort")）；行内按钮 → Hit(row, id)；行 → Hit(row, null)。 */
    public Hit click(double mouseX, double mouseY) {
        for (float[] r : this.sortRects) {
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                int col = (int) r[4];
                if (this.sortCol == col) {
                    this.sortDesc = !this.sortDesc;
                } else {
                    this.sortCol = col;
                    this.sortDesc = false;
                }
                return new Hit(-1, "sort");
            }
        }
        float top = listTop();
        if (mouseX < this.x || mouseX >= this.x + this.w || mouseY < top || mouseY >= top + listHeight()) {
            return null;
        }
        for (int i = 0; i < this.buttonRects.size(); i++) {
            float[] r = this.buttonRects.get(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                return new Hit((int) r[4], this.buttonIds.get(i));
            }
        }
        int r = (int) Math.floor((mouseY - top + this.scroll.offset()) / this.rowH);
        return r >= 0 && r < this.rows ? new Hit(r, null) : null;
    }

    /** 滚动条按下（拖拽开始）。 */
    public boolean pressBar(double mouseX, double mouseY) {
        return this.scroll.pressBar(mouseX, mouseY, this.x, listTop(), this.w, listHeight());
    }

    public boolean dragBar(double mouseY) {
        return this.scroll.dragBar(mouseY, listHeight());
    }

    public boolean releaseBar() {
        return this.scroll.releaseBar();
    }

    /** 滚轮（Minecraft 的滚轮量，正数向上）；Shift 时翻整屏。 */
    public boolean wheel(double mouseX, double mouseY, double amount, boolean page) {
        if (mouseX < this.x || mouseX >= this.x + this.w || mouseY < this.y || mouseY >= this.y + this.h) {
            return false;
        }
        if (page) {
            this.scroll.scrollTo(this.scroll.offset() - (float) Math.signum(amount) * listHeight() * 0.9F, true);
        } else {
            this.scroll.wheel(amount * 1.25);
        }
        return true;
    }

    public void scrollToTop() {
        this.scroll.scrollTo(0, false);
    }

    /** 当前可见的首行 / 末行（底栏「显示 a–b」用）。 */
    public int firstVisible() {
        return this.rows == 0 ? 0 : Math.min(this.rows, (int) Math.floor(this.scroll.offset() / this.rowH) + 1);
    }

    public int lastVisible() {
        return Math.min(this.rows, (int) Math.floor((this.scroll.offset() + listHeight() - 1) / this.rowH) + 1);
    }
}
