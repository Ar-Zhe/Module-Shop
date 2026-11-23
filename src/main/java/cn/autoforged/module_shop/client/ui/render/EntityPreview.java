package cn.autoforged.module_shop.client.ui.render;

import cn.autoforged.module_shop.client.screen.shop.PageFrame;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 3D 实体/玩家动态皮肤预览（跟随鼠标视线），封装原版 {@link InventoryScreen#renderEntityInInventoryFollowsMouse}
 * 与 AtomLib UI v2 缩放画布之间的坐标/矩阵换算细节，并为跨 MC 版本移植提供隔离点。
 *
 * <p><b>为什么必须这样做</b>：
 * <ul>
 *     <li>AtomLib 的 {@code drawFrame} 期间禁止调用原版 GL 绘制，因此必须通过
 *     {@link ItemOverlay#addCustom} 延迟到 Skija 帧之后在 {@code GuiGraphics} 层渲染；</li>
 *     <li>UI v2 执行 {@code ItemOverlay.flush} 时，{@code g.pose()} 已被 {@code scale(k, k, 1)} 与翻页滑入平移，
 *     但原版 {@code GuiGraphics.enableScissor} 只认 1:1 GUI 坐标并忽略 {@code pose()}，而
 *     {@code InventoryScreen.renderEntityInInventoryFollowsMouse} 又把同一套盒坐标同时交给剪裁与 {@code pose()}。
 *     因此这里从 {@code g.pose()} 读取当前变换后的精确 GUI 边界，随后临时重置 {@code pose} 为单位矩阵再调用原生绘制。</li>
 * </ul>
 */
public final class EntityPreview {
    /** 与原版背包界面的玩家纵偏一致（1/16 格）。 */
    public static final float DEFAULT_Y_OFFSET = 0.0625F;
    private static final float DEFAULT_INSET = 1.0F;

    private EntityPreview() {
    }

    /** 在 UI v2 页面内容区内登记一个跟随鼠标转头的 3D 实体预览框。 */
    public static void drawFollowsMouse(PageFrame frame, float x, float y, float w, float h,
                                        float baseScale, LivingEntity entity) {
        drawFollowsMouse(frame, x, y, w, h, baseScale, DEFAULT_Y_OFFSET, entity);
    }

    /** 同上，支持自定义 {@code yOffset}（画布单位）。 */
    public static void drawFollowsMouse(PageFrame frame, float x, float y, float w, float h,
                                        float baseScale, float yOffset, LivingEntity entity) {
        if (frame == null || frame.host() == null) {
            return;
        }
        drawFollowsMouse(frame.host().items(), x, y, w, h, DEFAULT_INSET, baseScale, yOffset,
                frame.mouseX(), frame.mouseY(), entity);
    }

    /** 通用重载（可用于 {@code SkiaDialog} 等直接持有 {@link ItemOverlay} 的界面）。 */
    public static void drawFollowsMouse(ItemOverlay overlay, float x, float y, float w, float h,
                                        float baseScale, float yOffset, float mouseX, float mouseY,
                                        LivingEntity entity) {
        drawFollowsMouse(overlay, x, y, w, h, DEFAULT_INSET, baseScale, yOffset, mouseX, mouseY, entity);
    }

    /** 完整重载：允许指定内缩边距 {@code inset} 以避开外框圆角描边。 */
    public static void drawFollowsMouse(ItemOverlay overlay, float x, float y, float w, float h, float inset,
                                        float baseScale, float yOffset, float mouseX, float mouseY,
                                        LivingEntity entity) {
        if (overlay == null || entity == null || w <= 2.0F || h <= 2.0F || baseScale <= 0.0F) {
            return;
        }
        float pad = Math.max(0.0F, inset);
        float ix0 = x + pad;
        float iy0 = y + pad;
        float ix1 = x + w - pad;
        float iy1 = y + h - pad;
        if (ix1 <= ix0 || iy1 <= iy0) {
            return;
        }
        // 当顶层浮层拦截鼠标传入哨兵坐标（如 -10000）时，让角色自然朝前微看而不是极端斜视左上角
        final float lookX = (mouseX < -1000.0F || mouseY < -1000.0F) ? (ix0 + ix1) * 0.5F : mouseX;
        final float lookY = (mouseX < -1000.0F || mouseY < -1000.0F) ? (iy0 + iy1) * 0.40F : mouseY;

        overlay.addCustom(x, y, w, h, (g, font) -> {
            Matrix4f mat = g.pose().last().pose();
            Vector3f p0 = mat.transformPosition(ix0, iy0, 0.0F, new Vector3f());
            Vector3f p1 = mat.transformPosition(ix1, iy1, 0.0F, new Vector3f());
            Vector3f pm = mat.transformPosition(lookX, lookY, 0.0F, new Vector3f());

            float sx = (float) Math.hypot(mat.m00(), mat.m01());
            float sy = (float) Math.hypot(mat.m10(), mat.m11());
            float scaleFactor = (sx + sy) * 0.5F;
            if (scaleFactor <= 0.01F) {
                scaleFactor = SkiaFrame.unitsToGui();
            }

            int gx1 = (int) Math.floor(Math.min(p0.x, p1.x));
            int gy1 = (int) Math.floor(Math.min(p0.y, p1.y));
            int gx2 = (int) Math.ceil(Math.max(p0.x, p1.x));
            int gy2 = (int) Math.ceil(Math.max(p0.y, p1.y));
            if (gx2 <= gx1 || gy2 <= gy1) {
                return;
            }

            int entityScale = Math.max(1, Math.round(baseScale * scaleFactor));
            g.pose().pushPose();
            g.pose().setIdentity();
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(
                        g, gx1, gy1, gx2, gy2, entityScale, yOffset, pm.x, pm.y, entity);
            } finally {
                g.pose().popPose();
            }
        });
    }
}
