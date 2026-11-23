package cn.autoforged.module_shop.api.view;

import net.minecraft.world.item.ItemStack;

/**
 * 实体货币档位只读视图（含稳定 key、面值、晋级比例、图标及玩家当前持有量）。
 *
 * @since API 1.5
 */
public record CurrencyTierView(String tierKey, String label, String itemId, long value, int stepRatio,
                               ItemStack icon, int ownedCount) {
    public CurrencyTierView {
        tierKey = tierKey == null ? "" : tierKey;
        label = label == null ? "" : label;
        itemId = itemId == null ? "" : itemId;
        value = Math.max(1L, value);
        stepRatio = Math.max(1, stepRatio);
        if (icon == null || icon.isEmpty()) {
            icon = ItemStack.EMPTY;
        } else {
            ItemStack copy = icon.copy();
            copy.setCount(1);
            icon = copy;
        }
        ownedCount = Math.max(0, ownedCount);
    }
}
