package cn.autoforged.server_shop_mod_1789689358.client.screen;

import net.minecraft.world.item.ItemStack;

/**
 * 「鼠标悬停的是哪件物品」——给 JEI 桥接读的接口。
 * <p>
 * 本模组的界面都是自绘 {@link net.minecraft.client.gui.screens.Screen}（不是原版容器界面），
 * JEI 默认找不到鼠标下的物品，所以由界面自己上报：绘制时把「正在显示 tooltip 的那件物品」记下来，
 * 按键事件发生时直接读这个字段即可（键事件发生在两帧之间，读到的就是玩家看到的那件）。
 * <p>
 * 这个接口**不引用任何 JEI 类型**，没装 JEI 时也照常编译与运行。
 */
public interface JeiHoverSource {
    /** 返回当前鼠标位置下悬停的物品（没有则返回 {@link ItemStack#EMPTY}）。 */
    ItemStack jeiHoveredStack(double mouseX, double mouseY);
}
