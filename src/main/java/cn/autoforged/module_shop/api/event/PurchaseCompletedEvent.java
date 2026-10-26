package cn.autoforged.module_shop.api.event;

import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/**
 * 一次购买已完成（成功后发布，不可取消）。
 *
 * @param playerId 买家
 * @param source 来源：{@code daily} / {@code mall} / {@code api}
 * @param itemId 商品标识（如 {@code daily-0}、{@code mall-3}）
 * @param item 买到的物品（展示用快照）
 * @param count 数量
 * @param totalPaid 实际支付总额（含可能的折扣后价格，服务端为准）
 */
public final class PurchaseCompletedEvent extends Event {
    private final UUID playerId;
    private final String source;
    private final String itemId;
    private final ItemStack item;
    private final int count;
    private final long totalPaid;

    public PurchaseCompletedEvent(UUID playerId, String source, String itemId, ItemStack item, int count,
            long totalPaid) {
        this.playerId = playerId;
        this.source = source;
        this.itemId = itemId;
        this.item = item == null ? ItemStack.EMPTY : item.copy();
        this.count = count;
        this.totalPaid = totalPaid;
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

    public long totalPaid() {
        return this.totalPaid;
    }

    @Override
    public String toString() {
        return "Purchase(" + this.playerId + " " + this.source + " " + this.itemId + " x" + this.count
                + " paid=" + this.totalPaid + ")";
    }
}