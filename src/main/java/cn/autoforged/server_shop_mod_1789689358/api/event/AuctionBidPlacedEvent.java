package cn.autoforged.server_shop_mod_1789689358.api.event;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/** 一次拍卖出价已成功（成功后发布，不可取消）。 */
public final class AuctionBidPlacedEvent extends Event {
    private final UUID playerId;
    private final long auctionId;
    private final long amount;

    public AuctionBidPlacedEvent(UUID playerId, long auctionId, long amount) {
        this.playerId = playerId;
        this.auctionId = auctionId;
        this.amount = amount;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public long auctionId() {
        return this.auctionId;
    }

    public long amount() {
        return this.amount;
    }

    @Override
    public String toString() {
        return "AuctionBid(" + this.playerId + " auction=" + this.auctionId + " amount=" + this.amount + ")";
    }
}