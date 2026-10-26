package cn.autoforged.server_shop_mod_1789689358.api.view;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** 只读：一封邮件的公开信息（不接受外部修改邮件本体）。 */
public record MailInfo(String mailId, String title, String sender, long money, List<ItemStack> items,
        boolean claimed, boolean viewed, long sentAt, long expireAt) {
    /** 是否还有可领取的东西。 */
    public boolean pending() {
        return !this.claimed && (this.money > 0L || !this.items.isEmpty());
    }
}