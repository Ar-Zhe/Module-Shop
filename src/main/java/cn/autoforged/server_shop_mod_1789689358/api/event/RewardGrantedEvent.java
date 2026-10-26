package cn.autoforged.server_shop_mod_1789689358.api.event;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/** 一次「幂等奖励」已发放成功（成功后发布，不可取消）。 */
public final class RewardGrantedEvent extends Event {
    private final UUID playerId;
    private final String requestId;
    private final long money;
    private final int itemCount;
    private final String reason;

    public RewardGrantedEvent(UUID playerId, String requestId, long money, int itemCount, String reason) {
        this.playerId = playerId;
        this.requestId = requestId == null ? "" : requestId;
        this.money = money;
        this.itemCount = itemCount;
        this.reason = reason == null ? "" : reason;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public String requestId() {
        return this.requestId;
    }

    public long money() {
        return this.money;
    }

    public int itemCount() {
        return this.itemCount;
    }

    public String reason() {
        return this.reason;
    }

    @Override
    public String toString() {
        return "RewardGranted(" + this.playerId + " " + this.requestId + " money=" + this.money
                + " items=" + this.itemCount + ")";
    }
}