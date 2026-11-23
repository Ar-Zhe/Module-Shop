package cn.autoforged.module_shop.api.view;

import net.minecraft.world.item.ItemStack;

/**
 * 公共 API 货币单位视图（虚拟主货币 / 指定档位实体货币）。
 *
 * @since API 1.5
 */
public record CurrencyUnit(boolean physical, String tierKey, String displayName, long unitValue, ItemStack icon) {
    public CurrencyUnit {
        tierKey = tierKey == null ? "" : tierKey;
        displayName = (displayName == null || displayName.isBlank()) ? "星币" : displayName;
        unitValue = Math.max(1L, unitValue);
        if (icon == null || icon.isEmpty()) {
            icon = ItemStack.EMPTY;
        } else {
            ItemStack copy = icon.copy();
            copy.setCount(1);
            icon = copy;
        }
    }

    public static CurrencyUnit star(String displayName) {
        return new CurrencyUnit(false, "", displayName, 1L, ItemStack.EMPTY);
    }

    public static CurrencyUnit physical(String tierKey, String displayName, long unitValue, ItemStack icon) {
        return new CurrencyUnit(true, tierKey, displayName, unitValue, icon);
    }
}
