package cn.autoforged.module_shop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;

/** 统一的 NBT 读写工具，保证 ItemStack 的完整组件数据被序列化。 */
public final class ShopNbt {
    private ShopNbt() {
    }

    /**
     * 批量下发期间的编码缓存（见 {@link #beginBatch}）：按 ItemStack **对象身份**记住编码结果。
     * 只在服务端主线程、一次全服快照广播的范围内存在，平时为 {@code null}，行为与以前完全一样。
     */
    @Nullable
    private static IdentityHashMap<ItemStack, Tag> batchCache;
    @Nullable
    private static HolderLookup.Provider batchRegistries;

    /**
     * 开始一次批量下发：之后同一个 ItemStack 对象只编码一次。
     *
     * <p>全服广播时每个玩家的快照里都有同一份购物城货架 / 求购单 / 拍卖物品，
     * 以前每个玩家都把这几千件物品重新走一遍 Codec 编码，这是快照里最贵的部分。
     * 编码结果只会被写进马上要压缩发出去的快照里，没有人会再修改它，所以可以安全地共享同一个 Tag。
     * 必须与 {@link #endBatch} 成对使用（try/finally）。
     */
    public static void beginBatch(HolderLookup.Provider registries) {
        batchCache = new IdentityHashMap<>();
        batchRegistries = registries;
    }

    public static void endBatch() {
        batchCache = null;
        batchRegistries = null;
    }

    public static CompoundTag saveStack(ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (stack != null && !stack.isEmpty()) {
            IdentityHashMap<ItemStack, Tag> cache = batchCache;
            if (cache != null && registries == batchRegistries) {
                Tag encoded = cache.get(stack);
                if (encoded == null) {
                    encoded = stack.saveOptional(registries);
                    cache.put(stack, encoded);
                }
                tag.put("item", encoded);
            } else {
                tag.put("item", stack.saveOptional(registries));
            }
        }
        return tag;
    }

    public static ItemStack loadStack(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || !tag.contains("item", Tag.TAG_COMPOUND)) {
            return ItemStack.EMPTY;
        }
        return ItemStack.parseOptional(registries, tag.getCompound("item"));
    }

    /**
     * 需求四：容器类物品（潜影盒、精妙背包等）深拷贝。
     * 普通 {@link ItemStack#copy()} 与源栈共享组件对象（例如容器内容物列表），
     * 这里强制走一次 NBT 序列化/反序列化，保证内容物、自定义 NBT、标签与属性数据
     * 完全独立且不丢失，任一方向后续修改都不会影响另一方。
     */
    public static ItemStack deepCopy(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return loadStack(saveStack(stack, registries), registries);
    }

    public static ListTag newList() {
        return new ListTag();
    }
}
