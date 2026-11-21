package cn.autoforged.module_shop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 购物城的"最近购买"记录（最新的一条在列表最后，界面按倒序显示）。
 * 只留展示需要的字段，不保存整份 NBT 日志之外的东西。
 */
public final class MallPurchase {
    public String playerName = "";
    public ItemStack stack = ItemStack.EMPTY;
    public int count = 1;
    public long unitPrice;
    public long total;
    public PriceSpec unitSpec = PriceSpec.ZERO;
    public PriceSpec totalSpec = PriceSpec.ZERO;
    public long time;

    public MallPurchase() {
    }

    public MallPurchase(String playerName, ItemStack stack, int count, long unitPrice, long time) {
        this(playerName, stack, count, PriceSpec.star(unitPrice), PriceSpec.star(unitPrice).scale(Math.max(1, count)), time);
    }

    public MallPurchase(String playerName, ItemStack stack, int count, PriceSpec unitSpec, PriceSpec totalSpec, long time) {
        this.playerName = playerName;
        this.stack = stack.copyWithCount(1);
        this.count = Math.max(1, count);
        this.unitSpec = unitSpec == null ? PriceSpec.ZERO : unitSpec;
        this.totalSpec = totalSpec == null ? this.unitSpec.scale(this.count) : totalSpec;
        this.unitPrice = this.unitSpec.supportsStar() ? this.unitSpec.starPrice : 0L;
        this.total = this.totalSpec.supportsStar() ? this.totalSpec.starPrice : 0L;
        this.time = time;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        tag.putString("player", playerName == null ? "" : playerName);
        tag.putInt("count", count);
        unitSpec.writeToParent(tag, "unitSpec", "unit");
        totalSpec.writeToParent(tag, "totalSpec", "total");
        tag.putLong("time", time);
        return tag;
    }

    public static MallPurchase load(CompoundTag tag, HolderLookup.Provider registries) {
        MallPurchase record = new MallPurchase();
        record.stack = ShopNbt.loadStack(tag, registries);
        record.playerName = tag.getString("player");
        record.count = Math.max(1, tag.getInt("count"));
        record.unitSpec = PriceSpec.readFromParent(tag, "unitSpec", "unit");
        if (tag.contains("totalSpec") || tag.contains("total")) {
            record.totalSpec = PriceSpec.readFromParent(tag, "totalSpec", "total");
        } else {
            record.totalSpec = record.unitSpec.scale(record.count);
        }
        record.unitPrice = record.unitSpec.supportsStar() ? record.unitSpec.starPrice : 0L;
        record.total = record.totalSpec.supportsStar() ? record.totalSpec.starPrice : 0L;
        record.time = tag.getLong("time");
        return record;
    }
}
