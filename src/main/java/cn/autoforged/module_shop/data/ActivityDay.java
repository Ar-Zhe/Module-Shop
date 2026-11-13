package cn.autoforged.module_shop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 管理员设置的活动日（需求 7：签到日历）。
 * <p>
 * 玩家在签到日历里把鼠标移到该日期会看到金色边框与公告标题/正文；
 * 当天登录还会按这里的配置发放星币（可选择对离线玩家补发）。
 */
public final class ActivityDay {
    /** 日期（epochDay）。 */
    public long day;
    /** 公告标题。 */
    public String title = "";
    /** 公告正文（鼠标悬浮显示）。 */
    public String note = "";
    /** 当天登录发放的星币（0 = 不发）。 */
    public long money;
    /** 是否给离线玩家补发。 */
    public boolean forOffline;
    /** 错过定时点后是否补发（true = 当天任意时刻登录都补发一次）。 */
    public boolean catchUp = true;
    /** 当天额外发放的物品（可为空）。 */
    public net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.EMPTY;
    /** 已经领过物品的玩家 UUID。 */
    public final java.util.Set<java.util.UUID> itemGranted = new java.util.HashSet<>();
    /** 日程颜色（ARGB；0 = 默认橙色）。 */
    public int color;
    /** 定时发放的现实时间点（0-23；-1 = 不定时，登录即发）。 */
    public int hour = -1;
    /** 定时发放的额外物品（可为空）。 */
    public net.minecraft.world.item.ItemStack gift = net.minecraft.world.item.ItemStack.EMPTY;
    /** 已经领过定时物品的玩家 UUID。 */
    public final java.util.Set<java.util.UUID> giftGranted = new java.util.HashSet<>();
    /** 已经发放过的玩家 UUID（避免重复发）。 */
    public final java.util.Set<java.util.UUID> granted = new java.util.HashSet<>();
    /** 第八轮：当天发放的**奖励物品列表**（替代原来的单件 item/gift）。 */
    public final java.util.List<net.minecraft.world.item.ItemStack> rewards = new java.util.ArrayList<>();
    /** 已经领过奖励物品的玩家 UUID。 */
    public final java.util.Set<java.util.UUID> rewardsGranted = new java.util.HashSet<>();

    /** 当天要发放的全部物品（新列表优先，老存档回退到 item / gift）。 */
    public java.util.List<net.minecraft.world.item.ItemStack> rewardStacks() {
        java.util.List<net.minecraft.world.item.ItemStack> list = new java.util.ArrayList<>();
        for (net.minecraft.world.item.ItemStack stack : rewards) {
            if (stack != null && !stack.isEmpty()) {
                list.add(stack);
            }
        }
        if (list.isEmpty()) {
            if (stack != null && !stack.isEmpty()) {
                list.add(stack);
            }
            if (gift != null && !gift.isEmpty()) {
                list.add(gift);
            }
        }
        return list;
    }

    /** 玩家是否已经领过奖励物品（新集合优先，老存档回退到 itemGranted / giftGranted）。 */
    public boolean itemsGrantedTo(java.util.UUID id) {
        if (rewardsGranted.contains(id)) {
            return true;
        }
        return rewards.isEmpty() && (itemGranted.contains(id) || giftGranted.contains(id));
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("day", day);
        tag.putString("title", title == null ? "" : title);
        tag.putString("note", note == null ? "" : note);
        tag.putLong("money", money);
        tag.putBoolean("forOffline", forOffline);
        tag.putBoolean("catchUp", catchUp);
        CompoundTag itemTag = cn.autoforged.module_shop.data.ShopNbt.saveStack(stack, registries);
        tag.put("item", itemTag);
        net.minecraft.nbt.ListTag itemList = new net.minecraft.nbt.ListTag();
        for (java.util.UUID id : itemGranted) {
            itemList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("itemGranted", itemList);
        tag.putInt("color", color);
        tag.putInt("hour", hour);
        tag.put("gift", cn.autoforged.module_shop.data.ShopNbt.saveStack(gift, registries));
        net.minecraft.nbt.ListTag giftList = new net.minecraft.nbt.ListTag();
        for (java.util.UUID id : giftGranted) {
            giftList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("giftGranted", giftList);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (java.util.UUID id : granted) {
            list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("granted", list);
        // 第八轮：奖励物品列表（服务端权威；老存档的 item / gift 在 load 时并入这里）。
        net.minecraft.nbt.ListTag rewardList = new net.minecraft.nbt.ListTag();
        for (net.minecraft.world.item.ItemStack stack : rewards) {
            if (stack != null && !stack.isEmpty()) {
                rewardList.add(cn.autoforged.module_shop.data.ShopNbt.saveStack(stack, registries));
            }
        }
        tag.put("rewards", rewardList);
        net.minecraft.nbt.ListTag rewardGrantedList = new net.minecraft.nbt.ListTag();
        for (java.util.UUID id : rewardsGranted) {
            rewardGrantedList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("rewardsGranted", rewardGrantedList);
        return tag;
    }

    public static ActivityDay load(CompoundTag tag, HolderLookup.Provider registries) {
        ActivityDay day = new ActivityDay();
        day.day = tag.getLong("day");
        day.title = tag.getString("title");
        day.note = tag.getString("note");
        day.money = tag.getLong("money");
        day.forOffline = tag.getBoolean("forOffline");
        day.catchUp = !tag.contains("catchUp") || tag.getBoolean("catchUp");
        day.stack = cn.autoforged.module_shop.data.ShopNbt.loadStack(tag.getCompound("item"), registries);
        net.minecraft.nbt.ListTag itemList = tag.getList("itemGranted", 8);
        for (int i = 0; i < itemList.size(); i++) {
            try {
                day.itemGranted.add(java.util.UUID.fromString(itemList.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        net.minecraft.nbt.ListTag list = tag.getList("granted", 8);
        for (int i = 0; i < list.size(); i++) {
            try {
                day.granted.add(java.util.UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        day.hour = tag.contains("hour") ? tag.getInt("hour") : -1;
        day.color = tag.getInt("color");
        day.gift = cn.autoforged.module_shop.data.ShopNbt.loadStack(tag.getCompound("gift"), registries);
        net.minecraft.nbt.ListTag giftList = tag.getList("giftGranted", 8);
        for (int i = 0; i < giftList.size(); i++) {
            try {
                day.giftGranted.add(java.util.UUID.fromString(giftList.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        // 第八轮：奖励物品列表；老存档（只有 item / gift）自动并入列表，避免升级后奖励丢失。
        net.minecraft.nbt.ListTag rewardList = tag.getList("rewards", 10);
        for (int i = 0; i < rewardList.size(); i++) {
            net.minecraft.world.item.ItemStack stack = cn.autoforged.module_shop.data.ShopNbt
                    .loadStack(rewardList.getCompound(i), registries);
            if (!stack.isEmpty()) {
                day.rewards.add(stack);
            }
        }
        if (day.rewards.isEmpty()) {
            if (!day.stack.isEmpty()) {
                day.rewards.add(day.stack.copy());
            }
            if (!day.gift.isEmpty()) {
                day.rewards.add(day.gift.copy());
            }
            // 老存档的“已领物品”集合并入新集合（避免升级后重复发一遍）。
            if (!day.rewards.isEmpty()) {
                day.rewardsGranted.addAll(day.itemGranted);
                day.rewardsGranted.addAll(day.giftGranted);
            }
        }
        net.minecraft.nbt.ListTag rewardGrantedList = tag.getList("rewardsGranted", 8);
        for (int i = 0; i < rewardGrantedList.size(); i++) {
            try {
                day.rewardsGranted.add(java.util.UUID.fromString(rewardGrantedList.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return day;
    }
}
