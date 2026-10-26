package cn.autoforged.server_shop_mod_1789689358.data;

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
    public long time;

    public MallPurchase() {
    }

    public MallPurchase(String playerName, ItemStack stack, int count, long unitPrice, long time) {
        this.playerName = playerName;
        this.stack = stack.copyWithCount(1);
        this.count = count;
        this.unitPrice = unitPrice;
        this.total = unitPrice * count;
        this.time = time;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        tag.putString("player", playerName == null ? "" : playerName);
        tag.putInt("count", count);
        tag.putLong("unit", unitPrice);
        tag.putLong("total", total);
        tag.putLong("time", time);
        return tag;
    }

    public static MallPurchase load(CompoundTag tag, HolderLookup.Provider registries) {
        MallPurchase record = new MallPurchase();
        record.stack = ShopNbt.loadStack(tag, registries);
        record.playerName = tag.getString("player");
        record.count = Math.max(1, tag.getInt("count"));
        record.unitPrice = tag.getLong("unit");
        record.total = tag.contains("total") ? tag.getLong("total") : record.unitPrice * record.count;
        record.time = tag.getLong("time");
        return record;
    }
}
