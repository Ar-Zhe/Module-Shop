package cn.autoforged.module_shop.client.screen.shop;

import io.github.humbleui.skija.Canvas;

/**
 * 商店主界面里的一个页面（一个侧栏模块），用 Skija 绘制。
 *
 * <p><b>迁移期的约定</b>：ShopScreen 按页签 id 查找已迁移的页面；找到且 AtomLib 可用时由页面绘制内容区，
 * 否则继续走旧的 GuiGraphics 绘制代码（非 Windows 等 AtomLib 不可用的环境也因此仍能正常使用）。
 *
 * <p>页面只负责内容区；侧栏、标题栏、底部的余额块仍由 ShopScreen 负责。
 * 业务动作一律通过 {@link ShopHost#action} 交回 ShopScreen 原有的动作分发，保证行为与旧界面完全一致。
 *
 * <p>ShopScreen 在每次服务端操作后都会被重建，页面对象也随之重建；需要跨重建保留的状态
 * （滚动位置、展开项等）请放在静态字段或 {@code ClientShopData} 里。
 */
public interface ShopPage {
    /** 对应的页签 id（与 ShopScreen.TAB_IDS 一致）。 */
    String id();

    /**
     * ShopScreen.init() 时调用（每次打开 / 窗口尺寸变化都会调用）。
     * 需要输入框的页面在这里创建并 {@code host.addWidget(...)} 隐藏的 EditBox。
     */
    default void init(ShopHost host) {
    }

    /** 绘制内容区。物品通过 {@code host.items()} 登记，Skija 绘制结束后由 ShopScreen 统一画出。 */
    void render(Canvas canvas, PageFrame frame);

    /** 内容区内的鼠标按下（ShopScreen 只把落在内容区里的点击交给页面）。 */
    default boolean mouseClicked(PageFrame frame, double mouseX, double mouseY, int button) {
        return false;
    }

    /** 点击落在内容区外（侧栏、标题栏等）：页面可借此让输入框失焦。不消费这次点击。 */
    default void clickedOutside() {
    }

    default boolean mouseReleased(PageFrame frame, double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(PageFrame frame, double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    /** scrollY：Minecraft 的滚轮量（正数 = 向上）。 */
    default boolean mouseScrolled(PageFrame frame, double mouseX, double mouseY, double scrollY) {
        return false;
    }

    /** 界面关闭 / 切走时调用：取消按下中的按钮、拖拽等未完成的操作。 */
    default void cancelInput() {
    }
}
