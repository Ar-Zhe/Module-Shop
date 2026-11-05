package cn.autoforged.module_shop.api.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 商店界面提供给附属页面的能力。 */
public interface AddonPageHost {
    /** 所在的 Screen（创建 SkiaTextField 要用）。 */
    Screen screen();

    /** 原版字体。 */
    Font font();

    /** 登记一个隐藏的原版输入框（SkiaTextField 背后的 EditBox），让它能接收键盘与输入法。 */
    void registerInput(EditBox box);

    /** 本帧的文字提示（悬停说明），所有内容画完后统一显示。 */
    void textTooltip(Component text);

    /** 客户端已知的当前玩家余额（服务端快照同步，仅供显示）。 */
    long balance();

    /** 当前货币名（管理员可改名，已处理默认名的翻译）。 */
    String currencyName();
}
