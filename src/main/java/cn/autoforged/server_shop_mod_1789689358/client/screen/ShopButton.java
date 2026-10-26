package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.client.ClickSound;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * 用模组统一点击音的原版按钮。
 * <p>
 * 原版 {@link Button} 点下去播的是 {@code minecraft:ui.button.click}；商店里凡是用了原版按钮的界面
 * （发放界面、数量/金额输入、拍卖出价、上架确认、各种确认框）都换成这个类，
 * 这样**商店里所有点击声都是同一个音效**（默认即模组自带的 sounds/fy.ogg，可在设置里更换）。
 */
public class ShopButton extends Button {
    public ShopButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    public void playDownSound(SoundManager handler) {
        ClickSound.play();
    }
}
