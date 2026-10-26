package cn.autoforged.server_shop_mod_1789689358.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        tag.putLong("price", price);
        tag.putString("rarity", rarity.id());
        tag.putInt("stock", stock);
        tag.putInt("stockMax", stockMax);
        tag.putInt("sort", sort);
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
        return item;
    }
}
