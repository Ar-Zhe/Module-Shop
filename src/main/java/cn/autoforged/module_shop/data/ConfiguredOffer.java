package cn.autoforged.module_shop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 管理员配置的商品/收购项。
 * 需求 6/7：除价格、品质外，还可为该条目单独配置“数量”：
 * 每日商店代表该物品的可购买上限；每日收购代表该物品可收购的次数上限。
 */
public final class ConfiguredOffer {
    public ItemStack stack = ItemStack.EMPTY;
    public long price;
    public PriceSpec priceSpec = PriceSpec.ZERO;
    public Rarity rarity = Rarity.COMMON;
    /** 需求 6/7：数量（可购买/可收购上限）。0 表示未配置，使用对应场景的默认值。 */
    public int amount;
    /** 购物城管理视角标记：每人永久限购（不随刷新补货）。 */
    public boolean permanentLimit;

    public ConfiguredOffer() {
    }

    public ConfiguredOffer(ItemStack stack, long price, Rarity rarity) {
        this(stack, PriceSpec.star(price), rarity);
    }

    public ConfiguredOffer(ItemStack stack, PriceSpec priceSpec, Rarity rarity) {
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

    /** 需求 6/7：返回配置数量，未配置（<=0）时退回到调用方给出的默认值。 */
    public int amountOr(int fallback) {
        return amount > 0 ? amount : fallback;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        resolvePriceSpec().writeToParent(tag, "priceSpec", "price");
        tag.putString("rarity", rarity.id());
        tag.putInt("amount", amount);
        if (permanentLimit) {
            tag.putBoolean("permanentLimit", true);
        }
        return tag;
    }

    public static ConfiguredOffer load(CompoundTag tag, HolderLookup.Provider registries) {
        ConfiguredOffer offer = new ConfiguredOffer();
        offer.stack = ShopNbt.loadStack(tag, registries);
        offer.setPriceSpec(PriceSpec.readFromParent(tag, "priceSpec", "price"));
        offer.rarity = Rarity.byId(tag.getString("rarity"));
        offer.amount = tag.getInt("amount");
        offer.permanentLimit = tag.getBoolean("permanentLimit");
        return offer;
    }
}
