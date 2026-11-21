package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** 页面能用到的 ShopScreen 能力（由 ShopScreen 实现）。 */
public interface ShopHost {
    /**
     * 执行一个商店动作：走 ShopScreen 原有的动作分发（客户端处理的动作就地处理，其余发给服务端），
     * 与旧界面里点击区域触发的效果完全一致。
     */
    void action(String action, CompoundTag extra);

    /** 本帧的页面物品层（Skija 结束后由 ShopScreen 画出并处理 tooltip / JEI）。 */
    ItemOverlay items();

    /** 原版字体（物品数量等原版绘制用）。 */
    Font font();

    /** 本帧要显示的文字提示（悬停按钮时的说明等），在所有内容之后由 ShopScreen 统一画出。 */
    void textTooltip(Component text);

    /** 所在的 Screen（SkiaTextField 需要它来管理焦点）。 */
    Screen screen();

    /** 注册一个不绘制的原版输入框（SkiaTextField 的隐藏 EditBox），使其能接收键盘与输入法。 */
    void registerInput(EditBox box);

    // ---- 管理面板用的只读状态（这些状态仍归 ShopScreen 所有，切换走原有动作） ----

    /** 管理面板当前编辑的模块 id（shop / buyback / mall / shipping / stall / demand / signin）。 */
    default String adminMode() {
        return "shop";
    }

    /** 管理面板条目列表的当前页（从 0 开始）。 */
    default int adminPage() {
        return 0;
    }

    /** 把管理面板页码夹紧到有效范围（条目被删少了之后不停在空页上）。 */
    default void clampAdminPage(int pages) {
    }

    /** 签到模块是否处于「编辑日程」态（点日历日期 = 新建 / 修改日程）。 */
    default boolean signInEditing() {
        return false;
    }

    /** 管理面板可选的模块 id（按下拉顺序）。 */
    default java.util.List<String> adminModules() {
        return java.util.List.of();
    }

    /** 模块的显示名。 */
    default Component moduleName(String id) {
        return Component.literal(id);
    }
}
