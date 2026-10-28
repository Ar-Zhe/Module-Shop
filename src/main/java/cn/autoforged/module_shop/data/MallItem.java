package cn.autoforged.module_shop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 购物城（新增模块）上架的一件商品：物品 + 价格 + 品质 + 库存。
 *
 * <p>与每日商店的 {@link ConfiguredOffer} 区别在于**库存语义**：
 * 每日商店的 {@code amount} 是"每天的可购买上限"，0 表示交给默认值；
 * 购物城是常驻货架，库存就是真库存，所以单独一个类，语义不会互相污染：
 * <ul>
 *     <li>{@code stock < 0}：无限库存（界面显示 ∞）；</li>
 *     <li>{@code stock == 0}：已售罄（商品仍在货架上，但不能买）；</li>
 *     <li>{@code stock > 0}：剩余件数，每次购买扣减。</li>
 * </ul>
 *
 * <p>{@link #stockMax} 是**补货目标**：管理员设置库存时一并记下这个数，
 * 到「补货设置」里的时刻就把 {@code stock} 补回 {@code stockMax}（无限库存不参与补货）。
 */
public final class MallItem {
    public ItemStack stack = ItemStack.EMPTY;
    public long price;
    public Rarity rarity = Rarity.COMMON;
    /** 库存：-1 = 无限；0 = 售罄；>0 = 剩余数量。 */
    public int stock = -1;
    /** 补货目标（定时补货补到这个数）：<0 = 无限库存、不参与补货。 */
    public int stockMax = -1;
    /** 上架顺序（用于"默认排序"，同一次整模组导入保持注册表顺序）。 */
    public int sort = 0;
    /**
     * 「个人库存」模式下**每个玩家已经买走的件数**（玩家 → 已买）。
     *
     * <p>个人库存时 {@link #stock} 表示「每个玩家各自的上限」，不再被购买直接扣减；
     * 某玩家还剩几件 = {@code stock - 已买}（见 {@link #remainingFor}）。
     * 全服库存模式完全不看这张表，它只是留在存档里，切回个人库存时继续生效。
     * 刷新 / 补货（{@link #clearPersonalUsed}）时整张表清空 = 每个人的库存都恢复。
     */
    public final java.util.Map<java.util.UUID, Integer> personalUsed = new java.util.LinkedHashMap<>();

    public MallItem() {
    }

    public MallItem(ItemStack stack, long price, Rarity rarity, int stock) {
        this.stack = stack;
        this.price = price;
        this.rarity = rarity;
        setStock(stock);
    }

    /** 设置库存（同时把「补货目标」记成同一个数：管理员填多少，定时补货就补回多少）。 */
    public void setStock(int value) {
        this.stock = value;
        this.stockMax = value < 0 ? -1 : value;
    }

    public boolean unlimited() {
        return stock < 0;
    }

    public boolean soldOut() {
        return stock == 0;
    }

    /** 还能买几件（无限库存返回 {@link Integer#MAX_VALUE}）。 */
    public int available() {
        return stock < 0 ? Integer.MAX_VALUE : Math.max(0, stock);
    }

    /**
     * 「个人库存」模式下某个玩家还剩几件：无限库存返回 {@code -1}；
     * 其余返回 {@code [0, stock]}（管理员事后把库存调小时不会出现负数）。
     */
    public int remainingFor(java.util.UUID playerId) {
        if (stock < 0) {
            return -1;
        }
        int used = playerId == null ? 0 : this.personalUsed.getOrDefault(playerId, 0);
        return Math.max(0, stock - used);
    }

    /** 记某个玩家在「个人库存」模式下买走了 {@code count} 件。 */
    public void addPersonalUsed(java.util.UUID playerId, int count) {
        if (playerId == null || count <= 0) {
            return;
        }
        this.personalUsed.merge(playerId, count, Integer::sum);
    }

    /** 清空所有玩家的已买记录（刷新 / 补货时调用 = 每个人的库存都补回原量）。 */
    public void clearPersonalUsed() {
        this.personalUsed.clear();
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        tag.putLong("price", price);
        tag.putString("rarity", rarity.id());
        tag.putInt("stock", stock);
        tag.putInt("stockMax", stockMax);
        tag.putInt("sort", sort);
        // 个人库存：每个玩家买了多少（空表不写，全服库存模式下这一段永远是空的）
        if (!personalUsed.isEmpty()) {
            ListTag usedList = new ListTag();
            for (java.util.Map.Entry<java.util.UUID, Integer> entry : personalUsed.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                CompoundTag used = new CompoundTag();
                used.putUUID("player", entry.getKey());
                used.putInt("used", entry.getValue());
                usedList.add(used);
            }
            if (!usedList.isEmpty()) {
                tag.put("personalUsed", usedList);
            }
        }
        return tag;
    }

    /**
     * 下发给**某个玩家**的货架数据：与 {@link #save} 相同，但
     * <ul>
     *     <li>永远不带 {@code personalUsed}（别人买走了多少既没必要下发，也能省流量）；</li>
     *     <li>「个人库存」模式时把 {@code stock} 换成**该玩家自己的剩余量**。</li>
     * </ul>
     * 客户端只认 {@code stock} 字段，所以界面代码不需要区分两种模式。
     */
    public CompoundTag saveForView(HolderLookup.Provider registries, java.util.UUID viewer, boolean personal) {
        CompoundTag tag = save(registries);
        tag.remove("personalUsed");
        if (personal) {
            tag.putInt("stock", remainingFor(viewer));
        }
        return tag;
    }

    public static MallItem load(CompoundTag tag, HolderLookup.Provider registries) {
        MallItem item = new MallItem();
        item.stack = ShopNbt.loadStack(tag, registries);
        item.price = tag.getLong("price");
        item.rarity = Rarity.byId(tag.getString("rarity"));
        // 老存档没有 stock 字段时按"无限"处理（读成 0 会变成整店售罄）。
        item.stock = tag.contains("stock") ? tag.getInt("stock") : -1;
        // 老存档没有 stockMax：把当前库存当成补货目标（第一次补货时补回这个数）。
        item.stockMax = tag.contains("stockMax") ? tag.getInt("stockMax") : item.stock;
        item.sort = tag.getInt("sort");
        // 个人库存的已买记录（老存档没有这一段 = 还没人买过）
        ListTag usedList = tag.getList("personalUsed", Tag.TAG_COMPOUND);
        for (int i = 0; i < usedList.size(); i++) {
            CompoundTag used = usedList.getCompound(i);
            if (!used.hasUUID("player")) {
                continue;
            }
            int count = used.getInt("used");
            if (count > 0) {
                item.personalUsed.merge(used.getUUID("player"), count, Integer::sum);
            }
        }
        return item;
    }
}
