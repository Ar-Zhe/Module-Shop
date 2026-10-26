package cn.autoforged.server_shop_mod_1789689358.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

/**
 * 兑换码（第七轮）：服务端权威数据，玩家用 {@code /shop} 邮箱里的「兑换码」按钮输入兑换。
 * <p>
 * 支持：永久 / 限时（{@link #expireAt}，0 = 永久）、全服通用或指定玩家白名单、
 * 每人限领次数（0 = 不限）、总使用上限（0 = 不限）、物品 + 星币奖励、自定义邮件标题与备注。
 */
public final class RedeemCode {
    /** 兑换码（统一大写、去空格）。 */
    public String code = "";
    /** 到期时间戳（epoch 毫秒；0 = 永久）。 */
    public long expireAt;
    /** true = 全服通用；false = 仅 {@link #players} 里的玩家可用。 */
    public boolean global = true;
    /** 指定玩家白名单。 */
    public final Set<UUID> players = new HashSet<>();
    /** 每人可兑换次数（0 = 不限）。 */
    public int perPlayerLimit = 1;
    /** 总使用上限（0 = 不限）。 */
    public int totalLimit;
    /** 已被使用的总次数。 */
    public int usedCount;
    /** 每个玩家已使用的次数。 */
    public final Map<UUID, Integer> usedBy = new HashMap<>();
    /** 物品奖励（深拷贝落盘）。 */
    public final List<ItemStack> items = new ArrayList<>();
    /** 星币奖励。 */
    public long money;
    /** 邮件标题（空 = 用模板标题）。 */
    public String title = "";
    /** 备注：附加到邮件正文末尾。 */
    public String note = "";
    /** 生成者（玩家名）。 */
    public String creator = "";
    public long createdAt;

    public boolean expired(long now) {
        return expireAt > 0L && expireAt <= now;
    }

    /** 该玩家还能兑换几次（0 = 已用完）。 */
    public int remainingFor(UUID id) {
        if (perPlayerLimit <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, perPlayerLimit - usedBy.getOrDefault(id, 0));
    }

    public boolean exhausted() {
        return totalLimit > 0 && usedCount >= totalLimit;
    }

    public boolean usableBy(UUID id, long now) {
        if (expired(now) || exhausted()) {
            return false;
        }
        if (!global && !players.contains(id)) {
            return false;
        }
        return remainingFor(id) > 0;
    }

    public boolean hasReward() {
        return !items.isEmpty() || money > 0L;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("code", code);
        tag.putLong("expireAt", expireAt);
        tag.putBoolean("global", global);
        ListTag playerList = new ListTag();
        for (UUID id : players) {
            playerList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("players", playerList);
        tag.putInt("perPlayerLimit", perPlayerLimit);
        tag.putInt("totalLimit", totalLimit);
        tag.putInt("usedCount", usedCount);
        CompoundTag used = new CompoundTag();
        for (Map.Entry<UUID, Integer> entry : usedBy.entrySet()) {
            used.putInt(entry.getKey().toString(), entry.getValue());
        }
        tag.put("usedBy", used);
        ListTag itemList = new ListTag();
        for (ItemStack stack : items) {
            itemList.add(ShopNbt.saveStack(stack, registries));
        }
        tag.put("items", itemList);
        tag.putLong("money", money);
        tag.putString("title", title == null ? "" : title);
        tag.putString("note", note == null ? "" : note);
        tag.putString("creator", creator == null ? "" : creator);
        tag.putLong("createdAt", createdAt);
        return tag;
    }

    public static RedeemCode load(CompoundTag tag, HolderLookup.Provider registries) {
        RedeemCode code = new RedeemCode();
        code.code = tag.getString("code");
        code.expireAt = tag.getLong("expireAt");
        code.global = !tag.contains("global") || tag.getBoolean("global");
        ListTag playerList = tag.getList("players", 8);
        for (int i = 0; i < playerList.size(); i++) {
            try {
                code.players.add(UUID.fromString(playerList.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        code.perPlayerLimit = tag.contains("perPlayerLimit") ? tag.getInt("perPlayerLimit") : 1;
        code.totalLimit = tag.getInt("totalLimit");
        code.usedCount = tag.getInt("usedCount");
        CompoundTag used = tag.getCompound("usedBy");
        for (String key : used.getAllKeys()) {
            try {
                code.usedBy.put(UUID.fromString(key), used.getInt(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag itemList = tag.getList("items", 10);
        for (int i = 0; i < itemList.size(); i++) {
            ItemStack stack = ShopNbt.loadStack(itemList.getCompound(i), registries);
            if (!stack.isEmpty()) {
                code.items.add(stack);
            }
        }
        code.money = tag.getLong("money");
        code.title = tag.getString("title");
        code.note = tag.getString("note");
        code.creator = tag.getString("creator");
        code.createdAt = tag.getLong("createdAt");
        return code;
    }

    /** 生成一个形如 {@code 8X9K-2M4P-Q7RT} 的随机码（去除易混字符，不带任何固定前缀）。 */
    public static String randomCode(java.util.Random random) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int group = 0; group < 3; group++) {
            if (group > 0) {
                sb.append('-');
            }
            for (int i = 0; i < 4; i++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
        }
        return sb.toString();
    }
}
