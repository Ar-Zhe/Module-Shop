package cn.autoforged.module_shop.client.ui.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * 物品图标层：Skija 绘制期间只“登记”物品，Skija 画完后再用原版 {@link GuiGraphics} 统一画出。
 *
 * <p>为什么不把物品画进 Skija：原版 {@code renderItem} + {@code renderItemDecorations} 天然支持
 * 模型、附魔光效、数量、耐久条、模组自定义渲染器，且不需要额外的离屏纹理和 GL 状态备份。
 * 层序问题通过“分层”解决——一帧可以有多个层：
 * <pre>
 *   Skija 页面 → 页面物品层.flush → Skija 弹窗 → 弹窗物品层.flush → 原版 tooltip
 * </pre>
 * 这样弹窗能正常盖住页面上的物品。
 *
 * <p>坐标为 GUI 像素；裁剪区（滚动列表）用原版 scissor 实现，与 Skija 裁剪保持一致。
 * 同时记录鼠标悬停的物品，供 tooltip 与 JEI（R/U）使用。
 */
public final class ItemOverlay {
    private record Clip(int x0, int y0, int x1, int y1) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private record Entry(ItemStack stack, float x, float y, float size, float scaleX, boolean decorations, Clip clip,
                         java.util.function.BiConsumer<GuiGraphics, Font> custom) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Deque<Clip> clips = new ArrayDeque<>();
    private double mouseX;
    private double mouseY;
    private boolean hoverEnabled = true;
    private ItemStack hovered = ItemStack.EMPTY;

    /** 每帧开始时调用：清空上一帧的登记。hoverEnabled=false 时不检测悬停（例如被弹窗遮住）。 */
    public void begin(double mouseX, double mouseY, boolean hoverEnabled) {
        entries.clear();
        clips.clear();
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.hoverEnabled = hoverEnabled;
        hovered = ItemStack.EMPTY;
    }

    /** 之后登记的物品都裁剪到该矩形内（与当前裁剪区取交集）。 */
    public void pushClip(float x, float y, float w, float h) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = (int) Math.ceil(x + w);
        int y1 = (int) Math.ceil(y + h);
        Clip parent = clips.peek();
        if (parent != null) {
            x0 = Math.max(x0, parent.x0);
            y0 = Math.max(y0, parent.y0);
            x1 = Math.min(x1, parent.x1);
            y1 = Math.min(y1, parent.y1);
        }
        clips.push(new Clip(x0, y0, Math.max(x0, x1), Math.max(y0, y1)));
    }

    public void popClip() {
        clips.poll();
    }

    /** 登记一个 16×16 的物品（含数量/耐久装饰）。 */
    public void add(ItemStack stack, float x, float y) {
        add(stack, x, y, 16.0F, true);
    }

    /** 登记一个物品；size 为边长（GUI 像素），原版图标为 16。 */
    public void add(ItemStack stack, float x, float y, float size, boolean decorations) {
        add(stack, x, y, size, 1.0F, decorations);
    }

    /**
     * 登记一个横向额外压缩的物品（翻牌动画用）：高为 size，宽为 size × scaleX，(x, y) 为压缩后的左上角。
     * scaleX 很小（牌转到侧面）时不画，也不参与悬停检测。
     */
    public void add(ItemStack stack, float x, float y, float size, float scaleX, boolean decorations) {
        if (stack == null || stack.isEmpty() || scaleX <= 0.05F) {
            return;
        }
        float w = size * scaleX;
        Clip clip = clips.peek();
        if (clip != null && (x >= clip.x1 || y >= clip.y1 || x + w <= clip.x0 || y + size <= clip.y0)) {
            return; // 完全在裁剪区外
        }
        entries.add(new Entry(stack, x, y, size, scaleX, decorations, clip, null));
        if (hoverEnabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + size
                && (clip == null || clip.contains(mouseX, mouseY))) {
            hovered = stack;
        }
    }

    /**
     * 登记一段自定义的原版绘制（玩家头像、通知红点等无法用 Skija 画的内容）。
     * 与物品一样在 Skija 之后执行，并遵守当前裁剪区；(x, y, w, h) 只用于判断是否完全在裁剪区外。
     */
    public void addCustom(float x, float y, float w, float h,
                          java.util.function.BiConsumer<GuiGraphics, Font> painter) {
        Clip clip = clips.peek();
        if (clip != null && (x >= clip.x1 || y >= clip.y1 || x + w <= clip.x0 || y + h <= clip.y0)) {
            return;
        }
        entries.add(new Entry(ItemStack.EMPTY, x, y, 0.0F, 1.0F, false, clip, painter));
    }

    /** 把本帧登记的物品画出来。必须在 Skija 绘制（SkiaFrame.draw）之后调用。 */
    public void flush(GuiGraphics graphics, Font font) {
        Clip active = null;
        boolean scissor = false;
        for (Entry e : entries) {
            if (e.clip != active) {
                if (scissor) {
                    graphics.disableScissor();
                    scissor = false;
                }
                active = e.clip;
                if (active != null) {
                    graphics.enableScissor(active.x0, active.y0, active.x1, active.y1);
                    scissor = true;
                }
            }
            if (e.custom != null) {
                graphics.pose().pushPose();
                e.custom.accept(graphics, font);
                graphics.pose().popPose();
                continue;
            }
            float scale = e.size / 16.0F;
            graphics.pose().pushPose();
            graphics.pose().translate(e.x, e.y, 0.0F);
            graphics.pose().scale(scale * e.scaleX, scale, 1.0F);
            graphics.renderItem(e.stack, 0, 0);
            if (e.decorations) {
                graphics.renderItemDecorations(font, e.stack, 0, 0);
            }
            graphics.pose().popPose();
        }
        if (scissor) {
            graphics.disableScissor();
        }
        graphics.flush();
    }

    /** 直接指定本帧的悬停物品（例如整行悬停都显示该行物品的 tooltip）。 */
    public void setHovered(ItemStack stack) {
        if (hoverEnabled && stack != null && !stack.isEmpty()) {
            hovered = stack;
        }
    }

    /** 本帧鼠标悬停的物品（没有则为 EMPTY）。 */
    public ItemStack hovered() {
        return hovered;
    }

    /** 若有悬停物品则画原版 tooltip。应在所有层之后调用。 */
    public boolean renderTooltip(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (hovered.isEmpty()) {
            return false;
        }
        graphics.renderTooltip(font, hovered, mouseX, mouseY);
        return true;
    }
}
