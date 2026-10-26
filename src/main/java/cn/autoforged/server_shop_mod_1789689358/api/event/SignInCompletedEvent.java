package cn.autoforged.server_shop_mod_1789689358.api.event;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/** 一次签到已完成（成功后发布，不可取消）。 */
public final class SignInCompletedEvent extends Event {
    private final UUID playerId;
    private final long day;
    private final int streak;
    private final long money;

    public SignInCompletedEvent(UUID playerId, long day, int streak, long money) {
        this.playerId = playerId;
        this.day = day;
        this.streak = streak;
        this.money = money;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public long day() {
        return this.day;
    }

    public int streak() {
        return this.streak;
    }

    public long money() {
        return this.money;
    }

    @Override
    public String toString() {
        return "SignIn(" + this.playerId + " day=" + this.day + " streak=" + this.streak + " money=" + this.money + ")";
    }
}