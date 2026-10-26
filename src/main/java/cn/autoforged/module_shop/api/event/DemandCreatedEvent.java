package cn.autoforged.module_shop.api.event;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/** 一次求购已成功发布（成功后发布，不可取消）。 */
public final class DemandCreatedEvent extends Event {
    private final UUID playerId;
    private final String demandId;
    private final long rewardMoney;
    private final int lineCount;

    public DemandCreatedEvent(UUID playerId, String demandId, long rewardMoney, int lineCount) {
        this.playerId = playerId;
        this.demandId = demandId == null ? "" : demandId;
        this.rewardMoney = rewardMoney;
        this.lineCount = lineCount;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public String demandId() {
        return this.demandId;
    }

    public long rewardMoney() {
        return this.rewardMoney;
    }

    public int lineCount() {
        return this.lineCount;
    }

    @Override
    public String toString() {
        return "DemandCreated(" + this.playerId + " id=" + this.demandId + " money=" + this.rewardMoney
                + " lines=" + this.lineCount + ")";
    }
}