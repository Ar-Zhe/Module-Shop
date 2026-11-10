package cn.autoforged.module_shop.client.ui.render;

import cn.autoforged.module_shop.client.ui.AtomUi;
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
    /** 当前（或最近一次）Skija 帧里 1 个画布单位 = 多少物理像素。旧界面 = GUI 缩放；UI v2 = 自有缩放。 */
    private static float unitScale = 0.0F;

    /**
     * 画布单位 → 原版 GUI 坐标的换算系数（UI v2 自有缩放时不为 1）。
     * 隐藏输入框（{@code SkiaTextField}）据此把原版 EditBox 放到和画面一致的位置。
     */
    public static float unitsToGui() {
        float gui = (float) Minecraft.getInstance().getWindow().getGuiScale();
        return unitScale <= 0.0F || gui <= 0.0F ? 1.0F : unitScale / gui;
    }

    /** 1 个画布单位 = 多少物理像素（还没画过任何帧时等于 GUI 缩放）。 */
    public static float unitScale() {
        return unitScale <= 0.0F ? (float) Minecraft.getInstance().getWindow().getGuiScale() : unitScale;
    }

    private SkiaFrame() {
    }

    public static void draw(GuiGraphics graphics, Consumer<Canvas> painter) {
        draw(graphics, null, painter);
    }

    public static void draw(GuiGraphics graphics, Runnable preUi, Consumer<Canvas> painter) {
        RenderSystem.assertOnRenderThread();
        if (!AtomUi.available()) {
            // 本帧之前 AtomLib 已被禁用（见下方 LinkageError 处理）：什么都不画，等界面关闭后走旧版。
            return;
        }
        try {
            drawUnchecked(graphics, preUi, painter);
        } catch (LinkageError error) {
            // 前置库更新后接口不兼容：只禁用 AtomLib、关掉当前界面，不让游戏崩溃。
            // 玩家重新打开时 AtomUi.available() 为 false，界面会使用旧版绘制。
            AtomUi.disable(error);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                mc.setScreen(null);
                if (mc.player != null) {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "msg.module_shop.atomlib_incompatible"), false);
                }
            });
        }
    }

    /**
     * 用自定义缩放绘制：画布 1 单位 = {@code scale} 个物理像素（不跟随 Minecraft 的 GUI 缩放）。
     * UI v2 的界面用它按窗口实际大小决定自己的缩放；调用方负责把鼠标坐标换算到同一单位。
     */
    public static void drawScaled(GuiGraphics graphics, float scale, Consumer<Canvas> painter) {
        RenderSystem.assertOnRenderThread();
        if (!AtomUi.available()) {
            return;
        }
        try {
            drawUnchecked(graphics, null, painter, scale);
        } catch (LinkageError error) {
            AtomUi.disable(error);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(null));
        }
    }

    private static void drawUnchecked(GuiGraphics graphics, Runnable preUi, Consumer<Canvas> painter) {
        drawUnchecked(graphics, preUi, painter, (float) Minecraft.getInstance().getWindow().getGuiScale());
    }

    private static void drawUnchecked(GuiGraphics graphics, Runnable preUi, Consumer<Canvas> painter, float scale) {
        graphics.flush();
        MinecraftRenderBridge bridge = MinecraftRenderBridge.get();
        if (preUi != null) {
            bridge.vanillaPass(preUi);
        }
        unitScale = scale;
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
