package cn.autoforged.server_shop_mod_1789689358.api.event;

import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/** 一件物品已上架到玩家摊位（成功后发布，不可取消）。 */
public final class StallListedEvent extends Event {
    private final UUID playerId;
    private final int stallIndex;
    private final ItemStack stack;
    private final int count;
    private final long price;

    public StallListedEvent(UUID playerId, int stallIndex, ItemStack stack, int count, long price) {
        this.playerId = playerId;
        this.stallIndex = stallIndex;
        this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
        this.count = count;
        this.price = price;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public int stallIndex() {
        return this.stallIndex;
    }

    public ItemStack stack() {
        return this.stack.copy();
    }

    public int count() {
        return this.count;
    }

    public long price() {
        return this.price;
    }

    @Override
    public String toString() {
        return "StallListed(" + this.playerId + " idx=" + this.stallIndex + " x" + this.count
                + " price=" + this.price + ")";
    }
}