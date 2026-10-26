package cn.autoforged.module_shop.data;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 邮箱中的一封邮件。
 * <p>
 * 字段与需求文档的 MailData 对应：mailId 为唯一标识，type 区分物品/金额，
 * sender/senderUUID 记录发件人，stack 为物品类邮件的完整 ItemStack（含 NBT/组件），
 * currencyAmount 为金额类邮件的星币数额，isClaimed/isViewed 记录状态，sentAt 为现实时间戳。
 * <p>
 * 物品类邮件在 isClaimed=true 后仍需玩家手动删除；金额类邮件在 isViewed=true 后同理。
 * 变灰逻辑由客户端 Screen 渲染时根据这两个标记判断，绝不改写 ItemStack 本身。
 */
public final class MailEntry {
    public String mailId = "";
    public MailType type = MailType.ITEM;
    public ItemStack stack = ItemStack.EMPTY;
    public String source = "admin";
    /** 邮件必须带发送者（系统邮件为空字符串）。 */
    public String sender = "";
    /** 发件人 UUID（系统邮件为 null）。 */
    public UUID senderUUID;
    /** 金额类邮件携带的星币数额（物品类为 0）。 */
    public long currencyAmount;
    /** 物品类邮件是否已领取。 */
    public boolean isClaimed;
    /** 金额类邮件是否已查看/领取。 */
    public boolean isViewed;
    /** 现实时间戳（epoch 毫秒），用于排序与界面显示。 */
    public long sentAt;
    public long receivedDay;
    // ===== 第七轮：模板化邮件字段（旧存档缺省时按旧行为）=====
    /** 邮件标题（支持 & 颜色代码；为空时界面按 source/type 生成默认标题）。 */
    public String title = "";
    /** 邮件正文（支持 & 颜色代码与 \n 换行）。 */
    public String content = "";
    /** 业务类型（auction/stall/trade/redeem/activity/system/refund），用于 UI 图标与配色。 */
    public String kind = "";
    /** 到期时间戳（epoch 毫秒；0 = 永久）。到期由每日维护删除（未领取也删）。 */
    public long expireAt;
    /** 是否星标（置顶显示）。 */
    public boolean starred;
    // ===== 第八轮：一封邮件可以装多件物品 =====
    /**
     * 附件物品列表（同一渠道的奖励合并到同一封邮件里，不再"一件物品一封邮件"）。
     * 旧存档/旧代码只写 {@link #stack} 时，读取用 {@link #itemStacks()} 回退。
     */
    public final java.util.List<ItemStack> stacks = new java.util.ArrayList<>();

    /** 邮件里的全部物品（新列表优先，老存档回退到单件 stack）。 */
    public java.util.List<ItemStack> itemStacks() {
        java.util.List<ItemStack> list = new java.util.ArrayList<>();
        for (ItemStack s : stacks) {
            if (s != null && !s.isEmpty()) {
                list.add(s);
            }
        }
        if (list.isEmpty() && stack != null && !stack.isEmpty()) {
            list.add(stack);
        }
        return list;
    }

    /** 邮件里是否还有物品。 */
    public boolean hasItems() {
        for (ItemStack s : stacks) {
            if (s != null && !s.isEmpty()) {
                return true;
            }
        }
        return stack != null && !stack.isEmpty();
    }

    /** 邮件展示用名字（标题为空时退回第一件物品名 / 金额）。 */
    public String displayName() {
        if (title != null && !title.isEmpty()) {
            return title;
        }
        java.util.List<ItemStack> items = itemStacks();
        if (!items.isEmpty()) {
            return items.get(0).getHoverName().getString();
        }
        return currencyAmount > 0L ? Long.toString(currencyAmount) : "-";
    }

    /** 往邮件里再放一件物品：同种物品先叠加，叠不下再占一格。 */
    public void addStack(ItemStack add) {
        if (add == null || add.isEmpty()) {
            return;
        }
        // 老字段 stack 里已有物品时先并入列表，否则下面的同步会把第一件覆盖掉。
        if (stacks.isEmpty() && stack != null && !stack.isEmpty()) {
            stacks.add(stack.copy());
        }
        ItemStack rest = add.copy();
        for (ItemStack exist : stacks) {
            if (exist.isEmpty() || !ItemStack.isSameItemSameComponents(exist, rest)) {
                continue;
            }
            int room = exist.getMaxStackSize() - exist.getCount();
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, rest.getCount());
            exist.grow(move);
            rest.shrink(move);
            if (rest.isEmpty()) {
                break;
            }
        }
        if (!rest.isEmpty()) {
            stacks.add(rest.copy());
        }
        syncLegacyStack();
    }

    /** 附件变动后同步旧字段（旧代码/旧界面读 stack）。 */
    public void syncLegacyStack() {
        stack = stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0);
    }

    /** 用给定列表整体替换邮件附件（领取后回写剩余物品用；会同时清掉旧字段）。 */
    public void setItems(java.util.List<ItemStack> items) {
        stacks.clear();
        stack = ItemStack.EMPTY;
        for (ItemStack s : items) {
            addStack(s);
        }
        syncLegacyStack();
    }

    public MailEntry() {
    }

    public MailEntry(ItemStack stack, String source, long receivedDay) {
        this.stack = stack;
        this.source = source;
        this.receivedDay = receivedDay;
        this.sentAt = System.currentTimeMillis();
        this.mailId = UUID.randomUUID().toString();
    }

    public MailEntry(ItemStack stack, String source, String sender, long receivedDay) {
        this(stack, source, receivedDay);
        this.sender = sender == null ? "" : sender;
    }

    /** 构造一封物品类邮件（stack 需为调用方已 copy 的完整物品）。 */
    public static MailEntry item(ItemStack stack, String source, String sender, UUID senderUUID, long receivedDay) {
        MailEntry entry = new MailEntry(stack, source, sender == null ? "" : sender, receivedDay);
        entry.type = MailType.ITEM;
        entry.senderUUID = senderUUID;
        return entry;
    }

    /** 构造一封金额类邮件；display 为展示用图标（可为空，仅用于界面显示，不可领取）。 */
    public static MailEntry money(long amount, ItemStack display, String source, String sender,
            UUID senderUUID, long receivedDay) {
        MailEntry entry = new MailEntry();
        entry.mailId = UUID.randomUUID().toString();
        entry.type = MailType.MONEY;
        entry.currencyAmount = Math.max(0L, amount);
        entry.stack = display == null ? ItemStack.EMPTY : display.copy();
        entry.source = source;
        entry.sender = sender == null ? "" : sender;
        entry.senderUUID = senderUUID;
        entry.receivedDay = receivedDay;
        entry.sentAt = System.currentTimeMillis();
        return entry;
    }

    /** 需求：物品类邮件是否还没被领取。 */
    public boolean pendingItem() {
        return type == MailType.ITEM && !isClaimed;
    }

    /** 需求：金额类邮件是否还没被查看/领取。 */
    public boolean pendingMoney() {
        return type == MailType.MONEY && !isViewed;
    }

    /** 未处理（删除前需要二次确认）。 */
    public boolean pending() {
        return pendingItem() || pendingMoney();
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        tag.putString("mailId", mailId);
        tag.putString("type", type.id());
        tag.putString("source", source);
        tag.putString("sender", sender);
        if (senderUUID != null) {
            tag.putUUID("senderUUID", senderUUID);
        }
        tag.putLong("currencyAmount", currencyAmount);
        tag.putBoolean("isClaimed", isClaimed);
        tag.putBoolean("isViewed", isViewed);
        tag.putLong("sentAt", sentAt);
        tag.putLong("receivedDay", receivedDay);
        tag.putString("title", title == null ? "" : title);
        tag.putString("content", content == null ? "" : content);
        tag.putString("kind", kind == null ? "" : kind);
        tag.putLong("expireAt", expireAt);
        tag.putBoolean("starred", starred);
        // 第八轮：附件列表（同时保留 stack 字段，旧存档/旧逻辑仍可读到第一件）。
        net.minecraft.nbt.ListTag stackList = new net.minecraft.nbt.ListTag();
        for (ItemStack s : stacks) {
            if (s != null && !s.isEmpty()) {
                stackList.add(ShopNbt.saveStack(s, registries));
            }
        }
        tag.put("stacks", stackList);
        return tag;
    }

    public static MailEntry load(CompoundTag tag, HolderLookup.Provider registries) {
        MailEntry entry = new MailEntry();
        entry.stack = ShopNbt.loadStack(tag, registries);
        // 旧存档没有 mailId 时现补一个，保证每封邮件都有稳定身份。
        entry.mailId = tag.contains("mailId") ? tag.getString("mailId") : UUID.randomUUID().toString();
        entry.type = MailType.byId(tag.contains("type") ? tag.getString("type") : "item");
        entry.source = tag.getString("source");
        entry.sender = tag.contains("sender") ? tag.getString("sender") : "";
        if (tag.hasUUID("senderUUID")) {
            entry.senderUUID = tag.getUUID("senderUUID");
        }
        entry.currencyAmount = tag.contains("currencyAmount") ? tag.getLong("currencyAmount") : 0L;
        entry.isClaimed = tag.getBoolean("isClaimed");
        entry.isViewed = tag.getBoolean("isViewed");
        // 旧存档没有 sentAt 时退回当日时间，保证时间戳始终有效。
        entry.sentAt = tag.contains("sentAt") ? tag.getLong("sentAt") : 0L;
        entry.receivedDay = tag.getLong("receivedDay");
        entry.title = tag.contains("title") ? tag.getString("title") : "";
        entry.content = tag.contains("content") ? tag.getString("content") : "";
        entry.kind = tag.contains("kind") ? tag.getString("kind") : "";
        entry.expireAt = tag.contains("expireAt") ? tag.getLong("expireAt") : 0L;
        entry.starred = tag.getBoolean("starred");
        // 第八轮：附件列表；老存档只有单件 stack 时自动并入列表。
        net.minecraft.nbt.ListTag stackList = tag.getList("stacks", 10);
        for (int i = 0; i < stackList.size(); i++) {
            ItemStack s = ShopNbt.loadStack(stackList.getCompound(i), registries);
            if (!s.isEmpty()) {
                entry.stacks.add(s);
            }
        }
        if (entry.stacks.isEmpty() && !entry.stack.isEmpty()) {
            entry.stacks.add(entry.stack.copy());
        }
        return entry;
    }
}
