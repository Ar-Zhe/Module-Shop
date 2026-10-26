package cn.autoforged.server_shop_mod_1789689358.api.view;

import net.minecraft.world.item.ItemStack;

/**
 * 只读：一件商品/收购项。
 *
 * @param id 模块内标识（如 {@code daily-3}、{@code mall-7}）
 * @param stack 展示用物品（**请当只读**，不要修改）
 * @param price 单价（星币）
 * @param stock 剩余数量；{@link #unlimited()} 为 true 时无意义
 * @param unlimited 是否无限量供应
 */
public record ShopItemView(String id, ItemStack stack, long price, int stock, boolean unlimited) {
    /** 是否还能买/卖。 */
    public boolean available() {
        return this.unlimited || this.stock > 0;
    }
}