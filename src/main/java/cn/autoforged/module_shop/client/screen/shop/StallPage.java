package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.data.StallEntry;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 我的摊位页（Skija 版）：两列网格，至少显示 12 格（空格子代表还能上架的位置）。
 *
 * <p>每格：物品、价格（金色）、限时上架时的剩余时间、库存 / 售罄、累计已售；右侧「×」下架（remove_stall）。
 * 有库存的格子点击改价（stall_edit_gui），售罄的只能下架后重新上架（与旧界面规则一致）。
 * 底部：上架物品（create_stall）、逛摊位（open_avatar）。
 */
public final class StallPage implements ShopPage {
    private static final int COLS = 2;
    private static final int MIN_CELLS = 12;
    private static final float CELL_H = 26.0F;
    private static final float GAP_X = 6.0F;
    private static final float GAP_Y = 4.0F;
    private static final int SOLD_COLOR = 0xFF88BBFF;
    private static final int REMAIN_COLOR = 0xFFE0A34A;

    private static float savedScroll;

    private final ScrollController scroll = new ScrollController();
    private boolean restored;
    private final List<Button> removeButtons = new ArrayList<>();
    private float[] hoverT = new float[0];

    private final Button create = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.stall_create").getString(), null).radius(3.0F);
    private final Button browse = new Button(Button.Kind.ACCENT,
            () -> Component.translatable("gui.module_shop.stall_browse").getString(), null).radius(3.0F);

    private float gridX, gridY, gridW, gridH, cellW;
    private int shownEntries;

    @Override
    public String id() {
        return "stall";
    }

    @Override
    public void render(Canvas canvas, PageFrame f) {
        List<StallEntry> stall = ClientShopData.stall;
        int cells = Math.max(MIN_CELLS, stall.size() + (stall.size() % COLS));
        int rows = (cells + COLS - 1) / COLS;
        gridX = f.x();
        gridY = f.y() + 4.0F;
        gridW = f.w();
        gridH = f.listBottom() - gridY;
        scroll.setContent(rows * (CELL_H + GAP_Y) - GAP_Y, gridH);
        if (!restored) {
            scroll.scrollTo(savedScroll, false);
            restored = true;
        }
        scroll.update();
        savedScroll = scroll.offset();
        cellW = (gridW - (scroll.canScroll() ? 6.0F : 0.0F) - GAP_X * (COLS - 1)) / COLS;
        while (removeButtons.size() < stall.size()) {
            removeButtons.add(new Button(Button.Kind.DANGER, "×", null).radius(2.0F).fontSize(Text.BODY));
        }
        if (hoverT.length != cells) {
            hoverT = new float[cells];
        }
        shownEntries = stall.size();

        int saved = canvas.save();
        f.host().items().pushClip(gridX, gridY, gridW, gridH);
        try {
            Draw.clip(canvas, gridX - 1.0F, gridY, gridW + 2.0F, gridH);
            float off = scroll.offset();
            for (int i = 0; i < cells; i++) {
                float cx = gridX + (i % COLS) * (cellW + GAP_X);
                float cy = gridY + (i / COLS) * (CELL_H + GAP_Y) - off;
                if (cy + CELL_H < gridY || cy > gridY + gridH) {
                    if (i < stall.size()) {
                        removeButtons.get(i).visible(false);
                    }
                    continue;
                }
                StallEntry e = i < stall.size() ? stall.get(i) : null;
                boolean hover = e != null && e.stock > 0 && !scroll.isDragging()
                        && f.mouseX() >= cx && f.mouseX() < cx + cellW - 22.0F
                        && f.mouseY() >= Math.max(cy, gridY) && f.mouseY() < Math.min(cy + CELL_H, gridY + gridH);
                hoverT[i] = Motion.approach(hoverT[i], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
                if (e == null) {
                    // 空位：虚一点的卡片
                    Draw.fill(canvas, cx, cy, cellW, CELL_H, 3.0F, IOStyle.SIDEBAR);
                    Draw.stroke(canvas, cx, cy, cellW, CELL_H, 3.0F, 1.0F, IOStyle.BORDER_DIM);
                    PageKit.slot(canvas, f.host(), null, cx + 4.0F, cy + 4.0F);
                    continue;
                }
                PageKit.hoverCard(canvas, cx, cy, cellW, CELL_H, hoverT[i]);
                PageKit.slot(canvas, f.host(), e.stack, cx + 4.0F, cy + 4.0F);
                drawCellText(canvas, e, cx + 27.0F, cy, cx + cellW - 24.0F);
                Button x = removeButtons.get(i);
                CompoundTag idx = indexTag(i);
                x.onClick(() -> f.host().action("remove_stall", idx))
                        .bounds(cx + cellW - 20.0F, cy + (CELL_H - 14.0F) / 2.0F, 17.0F, 14.0F)
                        .visible(cy >= gridY - 1.0F && cy + CELL_H <= gridY + gridH + 1.0F);
                x.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            }
        } finally {
            f.host().items().popClip();
            canvas.restoreToCount(saved);
        }
        scroll.drawBar(canvas, gridX, gridY, gridW, gridH, f.mouseX(), f.mouseY(), f.dtMs());

        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        create.onClick(() -> f.host().action("create_stall", null))
                .bounds(f.x() + 4.0F, barMid - 8.0F, 80.0F, 16.0F);
        browse.onClick(() -> f.host().action("open_avatar", null))
                .bounds(f.x() + 90.0F, barMid - 8.0F, 80.0F, 16.0F);
        create.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        browse.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
    }

    /** 两行文字：价格（+ 剩余时间）/ 库存或售罄（+ 累计已售）。 */
    private static void drawCellText(Canvas canvas, StallEntry e, float left, float cy, float right) {
        float w = right - left;
        float top = cy + 8.5F;
        float bottom = cy + 18.5F;
        float pw = Text.drawLine(canvas, ClientShopData.money(e.price), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                left, top, w, Text.Align.LEFT);
        String remain = PageKit.remainText(ClientShopData.stallExpireMode, ClientShopData.stallExpireAmount,
                e.listedGame, e.listedReal);
        if (remain != null) {
            float rw = Text.width(remain, AtomUi.REGULAR, Text.SMALL);
            if (left + pw + 4.0F + rw <= right) {
                Text.drawLine(canvas, remain, AtomUi.REGULAR, Text.SMALL, REMAIN_COLOR, right - rw, top, rw + 1.0F,
                        Text.Align.LEFT);
            }
        }
        String stock = e.stock <= 0
                ? Component.translatable("gui.module_shop.stall_sold_out").getString()
                : Component.translatable("gui.module_shop.stall_stock", e.stock).getString();
        float sw = Text.drawLine(canvas, stock, AtomUi.REGULAR, Text.SMALL, e.stock <= 0 ? IOStyle.RED : IOStyle.TEXT_2,
                left, bottom, w, Text.Align.LEFT);
        float soldX = left + sw + 6.0F;
        if (soldX < right - 10.0F) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.stall_sold_count", e.sold).getString(),
                    AtomUi.REGULAR, Text.SMALL, SOLD_COLOR, soldX, bottom, right - soldX, Text.Align.LEFT);
        }
    }

    private static CompoundTag indexTag(int i) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", i);
        return tag;
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button == 0 && scroll.pressBar(mx, my, gridX, gridY, gridW, gridH)) {
            return true;
        }
        if (create.mouseClicked(mx, my, button) || browse.mouseClicked(mx, my, button)) {
            return true;
        }
        for (int i = 0; i < Math.min(shownEntries, removeButtons.size()); i++) {
            Button b = removeButtons.get(i);
            if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        if (button == 0 && mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
            float localY = (float) (my - gridY + scroll.offset());
            int row = (int) (localY / (CELL_H + GAP_Y));
            int col = (int) ((mx - gridX) / (cellW + GAP_X));
            float inCellX = (float) (mx - gridX - col * (cellW + GAP_X));
            float inCellY = localY - row * (CELL_H + GAP_Y);
            int index = row * COLS + col;
            List<StallEntry> stall = ClientShopData.stall;
            if (col >= 0 && col < COLS && inCellY < CELL_H && inCellX < cellW - 22.0F
                    && index >= 0 && index < stall.size() && stall.get(index).stock > 0) {
                ClickSound.play();
                f.host().action("stall_edit_gui", indexTag(index));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = scroll.releaseBar() | create.mouseReleased(mx, my, button)
                | browse.mouseReleased(mx, my, button);
        for (Button b : removeButtons) {
            handled |= b.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return scroll.dragBar(my, gridH);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (my < f.listBottom() && scroll.canScroll()) {
            scroll.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void cancelInput() {
        scroll.releaseBar();
        create.cancelPress();
        browse.cancelPress();
        removeButtons.forEach(Button::cancelPress);
    }
}
