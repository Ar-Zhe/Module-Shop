package cn.autoforged.module_shop.api.client;

import io.github.humbleui.skija.Canvas;

/**
 * 附属模组的「内容区页面」：点侧栏条目后，直接画在商店右侧内容区（和内置模块一样用 AtomLib / Skija 绘制）。
 *
 * <p>用 {@link ShopModules#registerPage} 注册。商店只在 AtomLib 可用时使用页面；不可用时（例如非 Windows）
 * 打开注册时给的回退界面。
 *
 * <ul>
 *     <li><b>实例常驻</b>：每个注册只创建一个页面实例，商店界面被服务端快照重建时实例保留，表单内容不会丢；</li>
 *     <li><b>{@link #init}</b> 在每次商店界面 init 时调用（打开 / 改窗口大小 / 被重建）：输入框（SkiaTextField）
 *     要在这里重新创建并 {@link AddonPageHost#registerInput} 登记；</li>
 *     <li>绘制工具（UI 套件）：{@code client.ui.render.Draw / Text}、{@code client.ui.widget.Button / SkiaTextField /
 *     Motion / ScrollController}，配色与字体用 {@link ShopTheme}。这些类都在公共 API jar 里。</li>
 * </ul>
 */
public interface AddonPage {
    /** 商店界面 init 时调用（可能多次）。 */
    default void init(AddonPageHost host) {
    }

    /** 绘制内容区（GUI 像素坐标，canvas 已按 GUI 缩放）。 */
    void render(Canvas canvas, AddonFrame frame);

    /** 内容区内的鼠标按下。 */
    default boolean mouseClicked(AddonFrame frame, double mouseX, double mouseY, int button) {
        return false;
    }

    /** 点击落在内容区外（侧栏、标题栏）：可借此让输入框失焦。 */
    default void clickedOutside() {
    }

    default boolean mouseReleased(AddonFrame frame, double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(AddonFrame frame, double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    /** scrollY：Minecraft 的滚轮量（正数 = 向上）。 */
    default boolean mouseScrolled(AddonFrame frame, double mouseX, double mouseY, double scrollY) {
        return false;
    }

    /** 切走 / 关闭界面时调用：取消按下中的按钮、让输入框失焦并隐藏。 */
    default void cancelInput() {
    }
}
