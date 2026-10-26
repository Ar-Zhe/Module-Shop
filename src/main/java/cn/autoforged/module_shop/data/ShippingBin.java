package cn.autoforged.module_shop.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 单个玩家的「出货箱」（参考星露谷售货箱，每个玩家一份独立空间，类似末影箱）。
 *
 * <ul>
 *     <li>{@link #slots}：箱内物品，长度 = 当前等级的槽位数；</li>
 *     <li>{@link #level}：1 起，最大 = 配置的等级数（默认 3 级，最大 27 格）；</li>
 *     <li>{@link #soldDay}/{@link #soldToday}：**现实日期**的当日已结算金额（0 点刷新）；</li>
 *     <li>{@link #ruleUsed}：每条规则的当日已结算份数（规则里写了 {@code x数量} 时才用）；</li>
 *     <li>{@link #lastSettleGameDay}：上一次结算的游戏日，保证每天早上只结算一次。</li>
 * </ul>
 */
public final class ShippingBin {
    public int level = 1;
    public final List<ItemStack> slots = new ArrayList<>();
    public long soldDay = Long.MIN_VALUE;
    public long soldToday;
    public final Map<Integer, Integer> ruleUsed = new HashMap<>();
    public long lastSettleGameDay = Long.MIN_VALUE;
    /** 上一次结算的结果（界面展示用）。 */
    public long lastPayout;
    public int lastCount;
    /** 结算时玩家不在线 → 登录后补一条提示，别让人不知道钱是哪来的。 */
    public boolean pendingNotice;

    /**
     * 按槽位数把列表补/裁到目标长度（开箱、结算、升级时调用）。
     *
     * <p><b>缩容时绝不销毁物品</b>：以前这里直接 {@code slots.remove(...)} 砍掉末尾槽位，
     * 只要配置里的 {@code shippingLevelSlots} 被改小（或玩家的 {@code level} 高于当前配置的等级数），
     * 每次开箱 / 结算都会把多出来的槽位连物品一起删掉 —— 就是玩家反馈的「出货箱吞东西」。
     * 现在改成：先把超出范围的物品**并进前面的空位 / 同种物品**，实在放不下就**原样留在列表里**
     * （宁可多留几格数据，也绝不销毁玩家的东西；这些物品在下次结算时照样会被卖掉换成钱）。
     */
    public void resize(int size) {
        while (slots.size() < size) {
            slots.add(ItemStack.EMPTY);
        }
        if (slots.size() <= size) {
            return;
        }
        // 缩容：先把超出部分尽量塞回前面的格子
        List<ItemStack> keep = new ArrayList<>();
        for (int i = size; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (stack != null && !stack.isEmpty()) {
                keep.add(stack);
            }
        }
        slots.subList(size, slots.size()).clear();
        for (ItemStack stack : keep) {
            if (!mergeIntoExisting(stack)) {
                slots.add(stack);   // 没地方放：留在列表里（不显示、但保存着，结算时照样卖）
            }
        }
    }

    /** 把一叠物品并进已有的同种物品或空格（找不到位置返回 false）。 */
    private boolean mergeIntoExisting(ItemStack stack) {
        int max = Math.max(1, stack.getMaxStackSize());
        for (int i = 0; i < slots.size(); i++) {
            ItemStack slot = slots.get(i);
            if (slot == null || slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) {
                continue;
            }
            int room = max - slot.getCount();
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, stack.getCount());
            slot.setCount(slot.getCount() + move);
            stack.setCount(stack.getCount() - move);
            if (stack.isEmpty()) {
                return true;
            }
        }
        if (!stack.isEmpty()) {
            for (int i = 0; i < slots.size(); i++) {
                ItemStack slot = slots.get(i);
                if (slot == null || slot.isEmpty()) {
                    slots.set(i, stack.copy());
                    return true;
                }
            }
        }
        return stack.isEmpty();
    }

    /** 箱内非空物品件数（按堆叠数量算）。 */
    public int itemCount() {
        int total = 0;
        for (ItemStack stack : slots) {
            if (stack != null && !stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public boolean isEmpty() {
        for (ItemStack stack : slots) {
            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("level", level);
        ListTag list = new ListTag();
        for (ItemStack stack : slots) {
            list.add(stack == null || stack.isEmpty() ? new CompoundTag() : ShopNbt.saveStack(stack, registries));
        }
        tag.put("slots", list);
        tag.putLong("soldDay", soldDay);
        tag.putLong("soldToday", soldToday);
        CompoundTag used = new CompoundTag();
        for (Map.Entry<Integer, Integer> entry : ruleUsed.entrySet()) {
            used.putInt(Integer.toString(entry.getKey()), entry.getValue() == null ? 0 : entry.getValue());
        }
        tag.put("ruleUsed", used);
        tag.putLong("lastSettleGameDay", lastSettleGameDay);
        tag.putLong("lastPayout", lastPayout);
        tag.putInt("lastCount", lastCount);
        tag.putBoolean("pendingNotice", pendingNotice);
        return tag;
    }

    public static ShippingBin load(CompoundTag tag, HolderLookup.Provider registries) {
        ShippingBin bin = new ShippingBin();
        bin.level = Math.max(1, tag.getInt("level"));
        ListTag list = tag.getList("slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            bin.slots.add(entry.isEmpty() ? ItemStack.EMPTY : ShopNbt.loadStack(entry, registries));
        }
        bin.soldDay = tag.contains("soldDay") ? tag.getLong("soldDay") : Long.MIN_VALUE;
        bin.soldToday = Math.max(0L, tag.getLong("soldToday"));
        CompoundTag used = tag.getCompound("ruleUsed");
        for (String key : used.getAllKeys()) {
            try {
                bin.ruleUsed.put(Integer.parseInt(key), used.getInt(key));
            } catch (NumberFormatException ignored) {
                // 旧存档 / 手改过的键直接忽略。
            }
        }
        bin.lastSettleGameDay = tag.contains("lastSettleGameDay")
                ? tag.getLong("lastSettleGameDay") : Long.MIN_VALUE;
        bin.lastPayout = tag.getLong("lastPayout");
        bin.lastCount = tag.getInt("lastCount");
        bin.pendingNotice = tag.getBoolean("pendingNotice");
        return bin;
    }
}
