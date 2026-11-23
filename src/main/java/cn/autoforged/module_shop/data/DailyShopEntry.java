package cn.autoforged.module_shop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家当天的每日商店报价（含已购数量）。
 * 需求 11：购买上限改为“每个物品各自一个上限”，而非同一品质共用一个上限。
 */
public final class DailyShopEntry {
    public ItemStack stack = ItemStack.EMPTY;
    public long price;
    public PriceSpec priceSpec = PriceSpec.ZERO;
    public Rarity rarity = Rarity.COMMON;
    public int bought;
    /** 需求 7/11：该物品当日的可购买上限（来自管理员配置的数量，按物品区分）。 */
    public int limit = 1;

    public DailyShopEntry() {
    }

    public DailyShopEntry(ItemStack stack, long price, Rarity rarity) {
        this(stack, PriceSpec.star(price), rarity);
    }

    public DailyShopEntry(ItemStack stack, PriceSpec priceSpec, Rarity rarity) {
        this.stack = stack;
        setPriceSpec(priceSpec);
        this.rarity = rarity;
    }

    public void setPriceSpec(PriceSpec spec) {
        this.priceSpec = spec == null ? PriceSpec.ZERO : spec;
        this.price = this.priceSpec.supportsStar() ? this.priceSpec.starPrice : 0L;
    }

    public PriceSpec resolvePriceSpec() {
        if (priceSpec == null) {
            return PriceSpec.star(price);
        }
        if (priceSpec.mode == PriceSpec.CurrencyMode.STAR_ONLY && priceSpec.starPrice != price) {
            return PriceSpec.star(price);
        }
        return priceSpec;
    }

    public int remaining() {
        return Math.max(0, limit - bought);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        resolvePriceSpec().writeToParent(tag, "priceSpec", "price");
        tag.putString("rarity", rarity.id());
        tag.putInt("bought", bought);
        tag.putInt("limit", limit);
        return tag;
    }

    public static DailyShopEntry load(CompoundTag tag, HolderLookup.Provider registries) {
        DailyShopEntry entry = new DailyShopEntry();
        entry.stack = ShopNbt.loadStack(tag, registries);
        entry.setPriceSpec(PriceSpec.readFromParent(tag, "priceSpec", "price"));
        entry.rarity = Rarity.byId(tag.getString("rarity"));
        entry.bought = tag.getInt("bought");
        // 兼容旧存档：没有 limit 时退回品质默认上限，保证已有数据仍可玩。
        entry.limit = tag.contains("limit") ? tag.getInt("limit") : entry.rarity.purchaseLimit();
        return entry;
    }
}
