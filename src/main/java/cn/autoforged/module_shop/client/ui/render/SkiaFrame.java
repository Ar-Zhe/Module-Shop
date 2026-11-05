package cn.autoforged.module_shop.client.ui.render;

import com.atom.lib.client.render.MinecraftRenderBridge;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.humbleui.skija.Canvas;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 一帧 Skija 绘制的编排（参考 AtomChat {@code SkiaGraphics} 的精简版，MIT）。
 *
 * <p>顺序：
 * <ol>
 *     <li>{@code graphics.flush()}：先把已排队的原版 GUI 绘制提交掉，保证层序；</li>
 *     <li>{@code bridge.vanillaPass(preUi)}：可选，需要在 Skija 之前做的原版 GL 工作（以后的物品图标准备放这里）；</li>
 *     <li>{@code bridge.drawFrame(...)}：Skija 绘制。画布统一缩放到 <b>Minecraft GUI 坐标</b>
 *     （1 单位 = 1 个 GUI 像素），所以鼠标坐标、原版物品/提示框与 Skija 内容共用同一套坐标，无需换算。</li>
 * </ol>
 * 之后调用方可以继续用 {@link GuiGraphics} 在 Skija 之上叠加原版内容（物品、tooltip、隐藏输入框）。
 */
public final class SkiaFrame {
    private SkiaFrame() {
    }

    public static void draw(GuiGraphics graphics, Consumer<Canvas> painter) {
        draw(graphics, null, painter);
    }

    public static void draw(GuiGraphics graphics, Runnable preUi, Consumer<Canvas> painter) {
        RenderSystem.assertOnRenderThread();
        graphics.flush();
        MinecraftRenderBridge bridge = MinecraftRenderBridge.get();
        if (preUi != null) {
            bridge.vanillaPass(preUi);
        }
        float scale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        bridge.drawFrame((canvas, frame) -> {
            int base = canvas.getSaveCount();
            canvas.save();
            try {
                canvas.scale(scale, scale);
                painter.accept(canvas);
            } finally {
                // 无论绘制代码有没有配平 save/restore，都回到进入时的状态。
                canvas.restoreToCount(base);
            }
        });
    }
}
