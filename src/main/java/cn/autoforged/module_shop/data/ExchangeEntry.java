package cn.autoforged.module_shop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 货币兑换条目：管理员配置“1 个该物品 = value 星币”。
 * <p>
 * 双向使用：
 * <ul>
 *   <li>兑换（物品 → 星币）：按 value 把玩家背包里的该物品折算成星币。</li>
 *   <li>取款（星币 → 物品）：按 value 扣除星币后把物品发给玩家（背包放不下则进邮箱）。</li>
 * </ul>
 * stack 只作为“模板/图标”保存，管理员放入的物品本身不会被消耗，删除条目也不退还。
 */
public final class ExchangeEntry {
    /** 模板物品（只取 1 个作为展示与匹配依据）。 */
    public ItemStack stack = ItemStack.EMPTY;
    /** 单个物品可兑换的星币数。 */
    public long value;

    public ExchangeEntry() {
    }

    public ExchangeEntry(ItemStack stack, long value) {
        this.stack = stack == null ? ItemStack.EMPTY : stack;
        this.value = value;
    }

    /** 归一化：模板恒为 1 个，避免把数量当成价格的一部分。 */
    public void normalize() {
        if (stack == null) {
            stack = ItemStack.EMPTY;
        }
        if (!stack.isEmpty()) {
            stack.setCount(1);
        }
    }

    public boolean valid() {
        return !stack.isEmpty() && value > 0L;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        tag.putLong("value", value);
        return tag;
    }

    public static ExchangeEntry load(CompoundTag tag, HolderLookup.Provider registries) {
        ExchangeEntry entry = new ExchangeEntry();
        entry.stack = ShopNbt.loadStack(tag, registries);
        entry.value = tag.getLong("value");
        entry.normalize();
        return entry;
    }
}
