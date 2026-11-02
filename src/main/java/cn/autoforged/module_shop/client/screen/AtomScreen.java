package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.atom.AtomUi;
import com.atom.lib.client.render.BorrowedFramePainter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * AtomLib 屏幕的输入转发壳。
 * <p>
 * 按下、拖拽、松开、滚轮和 {@code removed} 都交给 {@link com.atom.lib.input.ScreenInputAdapter}。
 * 屏幕关掉时只取消手势，不关闭共享渲染桥。
 */
abstract class AtomScreen extends Screen {
    protected final AtomUi.Pointers pointers = new AtomUi.Pointers();

    protected AtomScreen(Component title) {
        super(title);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return this.pointers.input.press(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.pointers.input.drag(mouseX, mouseY, button, dragX, dragY)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return this.pointers.input.release(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return this.pointers.input.scroll(mouseX, mouseY, scrollX, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void removed() {
        this.pointers.removed();
        super.removed();
    }

    /** 先结束原版批次，再进入 Skija 通道。原版绘制不要放进这个回调。 */
    protected final void paintAtom(GuiGraphics graphics, BorrowedFramePainter painter) {
        graphics.flush();
        AtomUi.draw(painter);
    }
}
