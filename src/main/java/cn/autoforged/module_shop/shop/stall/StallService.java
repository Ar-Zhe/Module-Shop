package cn.autoforged.module_shop.shop.stall;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.data.PlayerAccount;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.data.StallEntry;
import cn.autoforged.module_shop.network.payload.ClientboundShopSyncPayload;
import cn.autoforged.module_shop.shop.RequestGuard;
import cn.autoforged.module_shop.shop.ShopManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家商店的新增部分（在原有摊位系统之上，不替换任何原有流程）：
 * <ul>
 *     <li>{@code stall_market}：商店大厅——把所有玩家摊位上的商品汇总下发（带版本号，没变化只回「未变化」）；</li>
 *     <li>{@code stall_list}：从背包指定槽位直接上架（一次调用完成校验 → 收费 → 取物品 → 入库，物品不在内存里悬空）；</li>
 *     <li>{@code stall_restock}：补货——只收与商品<b>完全相同</b>（含附魔 / 耐久 / 自定义数据）的物品。</li>
 * </ul>
 * 原来的「存放槽 → 输入价格」上架、改价、下架、查看摊位、购买流程全部保留，购买改为按商品 id 定位。
 *
 * <p>商品 id 就是 {@link StallEntry#order}：上架时取自全局自增的 {@code listingCounter}，全服唯一、不随下架 / 排序变化。
 */
public final class StallService {
    /** 商品编码缓存（商品上架后物品本身不变，改价 / 补货只改数字）。key = order。 */
    private static final Map<Long, Tag> ITEM_CACHE = new HashMap<>();

    private StallService() {
    }

    public static void reset() {
        ITEM_CACHE.clear();
    }

    /** @return true 表示这个动作归本类处理。 */
    public static boolean handle(ServerPlayer player, String action, CompoundTag tag) {
        switch (action) {
            case "stall_market" -> sendMarket(player, tag.getLong("version"));
            case "stall_list" -> list(player, tag);
            case "stall_restock" -> restock(player, tag);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void msg(ServerPlayer player, String key, ChatFormatting color, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(color), false);
    }

    private static void result(ServerPlayer player, boolean ok, String op) {
        CompoundTag reply = new CompoundTag();
        reply.putString("kind", "stall_result");
        reply.putBoolean("ok", ok);
        reply.putString("op", op);
        ClientboundShopSyncPayload.send(player, reply);
    }

    // =================================================================== 查找

    /** 按商品 id（order）找某玩家摊位上的商品。 */
    @Nullable
    public static StallEntry find(PlayerAccount account, long id) {
        if (account == null) {
            return null;
        }
        for (StallEntry e : account.stall) {
            if (e.order == id) {
                return e;
            }
        }
        return null;
    }

    public static int indexOf(PlayerAccount account, long id) {
        for (int i = 0; i < account.stall.size(); i++) {
            if (account.stall.get(i).order == id) {
                return i;
            }
        }
        return -1;
    }

    /** 全服摊位内容的指纹（谁的哪件商品、价格、库存）。任何上架 / 改价 / 购买 / 下架都会让它变化。 */
    public static long marketHash(ShopSavedData data) {
        long h = 1125899906842597L;
        for (Map.Entry<UUID, PlayerAccount> pair : data.accounts().entrySet()) {
            if (pair.getValue().stall.isEmpty()) {
                continue;
            }
            long owner = pair.getKey().getMostSignificantBits() ^ pair.getKey().getLeastSignificantBits();
            for (StallEntry e : pair.getValue().stall) {
                h = 31 * h + owner;
                h = 31 * h + e.order;
                h = 31 * h + e.price;
                h = 31 * h + e.stock;
            }
        }
        return h == 0L ? 1L : h;
    }

    // =================================================================== 商店大厅

    private static void sendMarket(ServerPlayer player, long clientVersion) {
        ShopSavedData data = ShopManager.data(player.server);
        long version = marketHash(data);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "stall_market");
        tag.putLong("version", version);
        if (clientVersion == version) {
            tag.putBoolean("unchanged", true);
            ClientboundShopSyncPayload.send(player, tag);
            return;
        }
        HolderLookup.Provider regs = player.registryAccess();
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PlayerAccount> pair : data.accounts().entrySet()) {
            PlayerAccount account = pair.getValue();
            for (StallEntry e : account.stall) {
                if (e.stock <= 0 || e.stack.isEmpty()) {
                    continue; // 售罄的不进大厅（摊位里仍然能看到）
                }
                CompoundTag c = new CompoundTag();
                Tag item = ITEM_CACHE.computeIfAbsent(e.order, k -> e.stack.saveOptional(regs));
                c.put("item", item);
                c.putUUID("owner", pair.getKey());
                c.putString("ownerName", account.name == null ? "" : account.name);
                c.putLong("id", e.order);
                c.putLong("price", e.price);
                c.putInt("stock", e.stock);
                c.putInt("sold", e.sold);
                c.putBoolean("own", pair.getKey().equals(player.getUUID()));
                list.add(c);
            }
        }
        tag.put("entries", list);
        ClientboundShopSyncPayload.send(player, tag);
    }

    // =================================================================== 从背包上架

    private static boolean slotOk(int slot) {
        return (slot >= 0 && slot < 36) || slot == 40;
    }

    private static void list(ServerPlayer player, CompoundTag tag) {
        synchronized (ShopManager.LOCK) {
            if (RequestGuard.duplicate(player, tag)) {
                return;
            }
            Inventory inventory = player.getInventory();
            int slot = tag.getInt("slot");
            ItemStack inSlot = slotOk(slot) ? inventory.getItem(slot) : ItemStack.EMPTY;
            if (inSlot.isEmpty()
                    || !BuiltInRegistries.ITEM.getKey(inSlot.getItem()).toString().equals(tag.getString("item"))) {
                msg(player, "msg.module_shop.auction.err_slot_changed", ChatFormatting.RED);
                result(player, false, "list");
                return;
            }
            int max = Config.INSTANCE.stallMaxStock.get();
            int count = tag.getInt("count");
            // 必须真的拥有：数量不能超过这一格里实际有的
            if (count <= 0 || count > inSlot.getCount() || count > max) {
                msg(player, "msg.module_shop.stall.err_count", ChatFormatting.RED, Math.min(max, inSlot.getCount()));
                result(player, false, "list");
                return;
            }
            long price = parsePrice(tag.getString("price"));
            if (price <= 0L) {
                msg(player, "msg.module_shop.invalid_price", ChatFormatting.RED);
                result(player, false, "list");
                return;
            }
            PlayerAccount account = ShopManager.account(player);
            ShopSavedData data = ShopManager.data(player.server);
            if (account.stall.size() >= Config.INSTANCE.stallSlots.get()) {
                msg(player, "msg.module_shop.stall_full", ChatFormatting.RED);
                result(player, false, "list");
                return;
            }
            double feePercent = Config.INSTANCE.stallListingFeePercent.get();
            long fee = feePercent <= 0.0D ? 0L : (long) Math.ceil(price * feePercent / 100.0D);
            if (fee > 0L && account.balance < fee) {
                msg(player, "msg.module_shop.no_money", ChatFormatting.RED);
                result(player, false, "list");
                return;
            }
            // ---- 校验完毕：收费 → 取物品 → 入库，中间没有失败分支 ----
            if (fee > 0L && ShopManager.debit(account, fee, "fee") < 0L) {
                result(player, false, "list");
                return;
            }
            ItemStack taken = inSlot.split(count);
            inventory.setChanged();
            StallEntry fresh = new StallEntry(taken.copyWithCount(1), price, data.listingCounter++, count);
            fresh.listedGame = player.server.overworld().getGameTime();
            fresh.listedReal = System.currentTimeMillis();
            account.stall.add(fresh);
            data.setDirty();
            msg(player, "msg.module_shop.stall_listed", ChatFormatting.GREEN, fresh.stack.getHoverName(), price, count);
            result(player, true, "list");
            ShopManager.sendSnapshot(player, "stall", true);
            ShopManager.broadcastSnapshots(player.server);
        }
    }

    private static long parsePrice(String text) {
        try {
            long v = Long.parseLong(text == null ? "" : text.trim());
            return v <= 0L || v > Config.maxBalanceLimit() ? 0L : v;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // =================================================================== 补货

    /** 背包里与模板完全相同（物品 + 全部组件）的数量。 */
    public static int countMatching(Inventory inventory, ItemStack template) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack st = inventory.getItem(i);
            if (!st.isEmpty() && ItemStack.isSameItemSameComponents(st, template)) {
                n += st.getCount();
            }
        }
        return n;
    }

    private static int takeMatching(Inventory inventory, ItemStack template, int max) {
        int taken = 0;
        for (int i = 0; i < inventory.getContainerSize() && taken < max; i++) {
            ItemStack st = inventory.getItem(i);
            if (st.isEmpty() || !ItemStack.isSameItemSameComponents(st, template)) {
                continue;
            }
            int take = Math.min(max - taken, st.getCount());
            st.shrink(take);
            taken += take;
            if (st.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        if (taken > 0) {
            inventory.setChanged();
        }
        return taken;
    }

    private static void restock(ServerPlayer player, CompoundTag tag) {
        synchronized (ShopManager.LOCK) {
            if (RequestGuard.duplicate(player, tag)) {
                return;
            }
            PlayerAccount account = ShopManager.account(player);
            StallEntry entry = find(account, tag.getLong("id"));
            if (entry == null) {
                msg(player, "msg.module_shop.stall.err_gone", ChatFormatting.RED);
                return;
            }
            int max = Config.INSTANCE.stallMaxStock.get();
            int room = Math.max(0, max - entry.stock);
            if (room <= 0) {
                msg(player, "msg.module_shop.stall.err_stock_full", ChatFormatting.RED, max);
                return;
            }
            String raw = tag.getString("quantity").trim();
            int want;
            try {
                want = raw.isEmpty() ? room : Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                want = -1;
            }
            if (want <= 0) {
                msg(player, "msg.module_shop.stall.err_count", ChatFormatting.RED, room);
                return;
            }
            int taken = takeMatching(player.getInventory(), entry.stack, Math.min(want, room));
            if (taken <= 0) {
                msg(player, "msg.module_shop.stall.err_no_item", ChatFormatting.RED, entry.stack.getHoverName());
                return;
            }
            entry.stock += taken;
            ShopManager.data(player.server).setDirty();
            msg(player, "msg.module_shop.stall.restocked", ChatFormatting.GREEN, entry.stack.getHoverName(), taken,
                    entry.stock);
            ShopManager.sendSnapshot(player, "stall");
            ShopManager.broadcastSnapshots(player.server);
        }
    }
}
