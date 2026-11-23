package cn.autoforged.module_shop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家当天的收购报价（含已售数量）。
 * 需求 6：每个收购项自带品质与可收购数量，供界面展示与限购校验。
 */
public final class DailyBuybackEntry {
    public ItemStack stack = ItemStack.EMPTY;
    public long price;
    public PriceSpec priceSpec = PriceSpec.ZERO;
    public Rarity rarity = Rarity.COMMON;
    public int sold;
    /** 需求 6/9：该物品当日的可收购数量上限。 */
    public int limit = 1;

    public DailyBuybackEntry() {
    }

    public DailyBuybackEntry(ItemStack stack, long price) {
        this(stack, PriceSpec.star(price));
    }

    public DailyBuybackEntry(ItemStack stack, PriceSpec priceSpec) {
        this.stack = stack;
        setPriceSpec(priceSpec);
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
        return Math.max(0, limit - sold);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        resolvePriceSpec().writeToParent(tag, "priceSpec", "price");
        tag.putString("rarity", rarity.id());
        tag.putInt("sold", sold);
        tag.putInt("limit", limit);
        return tag;
    }

    public static DailyBuybackEntry load(CompoundTag tag, HolderLookup.Provider registries) {
        DailyBuybackEntry entry = new DailyBuybackEntry();
        entry.stack = ShopNbt.loadStack(tag, registries);
        entry.setPriceSpec(PriceSpec.readFromParent(tag, "priceSpec", "price"));
        entry.rarity = Rarity.byId(tag.getString("rarity"));
        entry.sold = tag.getInt("sold");
        entry.limit = tag.contains("limit") ? tag.getInt("limit")
                : cn.autoforged.module_shop.Config.INSTANCE.buybackCapacity.get();
        return entry;
    }
}
