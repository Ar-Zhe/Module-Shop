package cn.autoforged.module_shop.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家求购（悬赏）单。
 *
 * <h2>玩法</h2>
 * 玩家 A 想要某些物品，就发布一条求购：用「物品搜索界面」挑最多 **12 种** 物品并分别填数量，
 * 把悬赏**先托管**在系统里（金币和/或物品）。任何其他玩家凑齐**全部**清单后点「交付」，
 * 物品当场交给 A、悬赏当场付给交付者。因为悬赏是**发布时就扣下**的，所以不存在赖账问题；
 * 撤销 / 到期时托管的东西会原样退回（背包或邮箱）。
 *
 * <h2>字段说明</h2>
 * <ul>
 *     <li>{@link #wants}：想要的物品清单（最多 {@link #MAX_LINES} 条，每条的 stack 数量固定 1，真实数量在 line.count）；</li>
 *     <li>{@link #rewardMoney}：悬赏金币（发布时已从余额扣除，托管在这里）；</li>
 *     <li>{@link #rewardItem}：悬赏物品（发布时已从背包扣除，{@code count} 就是悬赏数量）；</li>
 *     <li>{@link #createdGame} / {@link #createdReal}：发布时间（游戏刻 / 现实毫秒），下架时间按它们算。</li>
 * </ul>
 */
public final class DemandEntry {
    /** 一条求购最多要求几种物品。 */
    public static final int MAX_LINES = 12;

    /** 想要物品清单里的一行：物品模板（数量恒为 1）+ 想要的数量。 */
    public static final class WantLine {
        public ItemStack stack = ItemStack.EMPTY;
        public int count = 1;

        public WantLine() {
        }

        public WantLine(ItemStack stack, int count) {
            this.stack = stack;
            this.count = Math.max(1, Math.min(64, count));
        }

        /** 界面显示用的栈（带正确数量）。 */
        public ItemStack display() {
            ItemStack copy = stack.copy();
            copy.setCount(Math.max(1, count));
            return copy;
        }

        public CompoundTag save(HolderLookup.Provider registries) {
            CompoundTag tag = ShopNbt.saveStack(stack, registries);
            tag.putInt("count", count);
            return tag;
        }

        public static WantLine load(CompoundTag tag, HolderLookup.Provider registries) {
            WantLine line = new WantLine();
            line.stack = ShopNbt.loadStack(tag, registries);
            line.count = Math.max(1, tag.getInt("count"));
            return line;
        }
    }

    /** 短 id（界面与动作包都用它，避免暴露列表下标）。 */
    public String id = "";
    public UUID requester;
    public String requesterName = "";
    /** 想要的物品清单（1~12 条）。 */
    public final List<WantLine> wants = new ArrayList<>();
    public long rewardMoney;
    public ItemStack rewardItem = ItemStack.EMPTY;
    public long createdGame;
    public long createdReal;
    /** 累计成交量（成交后条目会删除，这里只用于管理员统计展示）。 */
    public int dealCount;

    public DemandEntry() {
    }

    /** 是否还有悬赏可付（至少金币或物品之一）。 */
    public boolean hasReward() {
        return this.rewardMoney > 0L || (this.rewardItem != null && !this.rewardItem.isEmpty());
    }

    /** 清单种类数。 */
    public int lineCount() {
        return this.wants.size();
    }

    /** 清单总件数（用于摘要与"是否为空"判断）。 */
    public int totalCount() {
        int total = 0;
        for (WantLine line : this.wants) {
            total += Math.max(0, line.count);
        }
        return total;
    }

    /** 是否是一条有效求购（清单非空且每条都有物品）。 */
    public boolean valid() {
        if (this.wants.isEmpty() || this.wants.size() > MAX_LINES) {
            return false;
        }
        for (WantLine line : this.wants) {
            if (line.stack == null || line.stack.isEmpty() || line.count <= 0) {
                return false;
            }
        }
        return hasReward();
    }

    /** 清单一句话摘要：单条 = 「物品 ×N」，多条 = 「物品 ×N 等 M 种」。 */
    public net.minecraft.network.chat.Component summary() {
        if (this.wants.isEmpty()) {
            return net.minecraft.network.chat.Component.literal("-");
        }
        WantLine first = this.wants.get(0);
        if (this.wants.size() == 1) {
            return net.minecraft.network.chat.Component.translatable("gui.module_shop.demand.want_one",
                    first.stack.getHoverName(), first.count);
        }
        return net.minecraft.network.chat.Component.translatable("gui.module_shop.demand.want_many",
                first.stack.getHoverName(), first.count, this.wants.size());
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        if (requester != null) {
            tag.putUUID("requester", requester);
        }
        tag.putString("requesterName", requesterName == null ? "" : requesterName);
        ListTag list = new ListTag();
        for (WantLine line : wants) {
            if (line.stack != null && !line.stack.isEmpty()) {
                list.add(line.save(registries));
            }
        }
        tag.put("wants", list);
        // 兼容旧字段（只写第一条，方便老版本/旧逻辑读）
        if (!wants.isEmpty()) {
            tag.put("want", ShopNbt.saveStack(wants.get(0).stack, registries));
            tag.putInt("wantCount", wants.get(0).count);
        }
        tag.putLong("rewardMoney", rewardMoney);
        tag.put("rewardItem", ShopNbt.saveStack(rewardItem, registries));
        tag.putLong("createdGame", createdGame);
        tag.putLong("createdReal", createdReal);
        tag.putInt("dealCount", dealCount);
        return tag;
    }

    public static DemandEntry load(CompoundTag tag, HolderLookup.Provider registries) {
        DemandEntry entry = new DemandEntry();
        entry.id = tag.getString("id");
        entry.requester = tag.contains("requester") ? tag.getUUID("requester") : null;
        entry.requesterName = tag.getString("requesterName");
        ListTag list = tag.getList("wants", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && entry.wants.size() < MAX_LINES; i++) {
            WantLine line = WantLine.load(list.getCompound(i), registries);
            if (line.stack != null && !line.stack.isEmpty()) {
                entry.wants.add(line);
            }
        }
        // 旧存档兼容：只有 want + wantCount 的条目
        if (entry.wants.isEmpty() && tag.contains("want", Tag.TAG_COMPOUND)) {
            ItemStack legacy = ShopNbt.loadStack(tag.getCompound("want"), registries);
            if (!legacy.isEmpty()) {
                entry.wants.add(new WantLine(legacy, Math.max(1, tag.getInt("wantCount"))));
            }
        }
        entry.rewardMoney = tag.getLong("rewardMoney");
        entry.rewardItem = ShopNbt.loadStack(tag.getCompound("rewardItem"), registries);
        entry.createdGame = tag.getLong("createdGame");
        entry.createdReal = tag.getLong("createdReal");
        entry.dealCount = tag.getInt("dealCount");
        return entry;
    }
}
