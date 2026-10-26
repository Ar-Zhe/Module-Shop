package cn.autoforged.server_shop_mod_1789689358.api.event;

import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/**
 * 一次出售/收购已完成（成功后发布，不可取消）。
 *
 * @param playerId 卖家
 * @param source 来源：{@code buyback} / {@code api}
 * @param itemId 收购项标识（如 {@code buyback-0}）
 * @param item 卖出的物品（展示用快照）
 * @param count 数量
 * @param totalEarned 实际到账总额
 */
public final class SellCompletedEvent extends Event {
    private final UUID playerId;
    private final String source;
    private final String itemId;
    private final ItemStack item;
    private final int count;
    private final long totalEarned;

    public SellCompletedEvent(UUID playerId, String source, String itemId, ItemStack item, int count,
            long totalEarned) {
        this.playerId = playerId;
        this.source = source;
        this.itemId = itemId;
        this.item = item == null ? ItemStack.EMPTY : item.copy();
        this.count = count;
        this.totalEarned = totalEarned;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public String source() {
        return this.source;
    }

    public String itemId() {
        return this.itemId;
    }

    public ItemStack item() {
        return this.item.copy();
    }

    public int count() {
        return this.count;
    }

    public long totalEarned() {
        return this.totalEarned;
    }

    @Override
    public String toString() {
        return "Sell(" + this.playerId + " " + this.source + " " + this.itemId + " x" + this.count
                + " earned=" + this.totalEarned + ")";
    }
}