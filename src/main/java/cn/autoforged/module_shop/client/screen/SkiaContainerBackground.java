package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.ModuleShopMod;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;

/**
 * 容器界面（出货箱、物品发放、存入……）的 Skija 底板改在
 * {@link ContainerScreenEvent.Render.Background}（最低优先级）里画，而不是在 {@code renderBg} 里。
 *
 * <p>原因：有些界面美化模组（例如 ModernUI）会在 {@code renderBackground} 期间或之后再铺一层半透明暗色背景。
 * 如果底板在 {@code renderBg} 里画，就会被这层暗色盖住，整块面板发灰、文字看不清。
 * 这个事件在 {@code renderBackground} 之后、槽位与物品之前触发；用最低优先级，排在其它模组的处理之后，
 * 底板就总在暗色背景上面、物品下面。
 */
@EventBusSubscriber(modid = ModuleShopMod.MODID, value = Dist.CLIENT)
public final class SkiaContainerBackground {
    /** 由使用 Skija 底板的容器界面实现；只在 AtomLib 可用时被调用。 */
    public interface Painter {
        /** 本界面这一帧是否用 Skija 画底板（AtomLib 不可用时返回 false，走旧的 renderBg）。 */
        boolean usesSkiaBackground();

        void paintSkiaBackground(GuiGraphics g, int mouseX, int mouseY);
    }

    private SkiaContainerBackground() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBackground(ContainerScreenEvent.Render.Background event) {
        if (event.getContainerScreen() instanceof Painter painter && painter.usesSkiaBackground()) {
            painter.paintSkiaBackground(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
        }
    }
}
