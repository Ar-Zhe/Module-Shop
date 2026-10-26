package cn.autoforged.module_shop.api.event;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/**
 * 余额已发生变化（**业务成功之后**发布，不可取消）。
 *
 * <p>触发点：{@code ShopManager.credit(...)}（所有加钱路径：商店/邮件/签到/管理员发放/API）
 * 与 {@code EconomyApi.take(...)}（扣钱路径）。
 *
 * <p>监听器**不能**通过本事件回滚交易；异常会被捕获并记录，不影响已完成的事务。
 */
public final class BalanceChangedEvent extends Event {
    private final UUID playerId;
    private final long oldBalance;
    private final long newBalance;
    private final long delta;
    private final String reason;

    public BalanceChangedEvent(UUID playerId, long oldBalance, long newBalance, long delta, String reason) {
        this.playerId = playerId;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.delta = delta;
        this.reason = reason == null ? "" : reason;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public long oldBalance() {
        return this.oldBalance;
    }

    public long newBalance() {
        return this.newBalance;
    }

    /** 变化量：正=收入，负=支出。 */
    public long delta() {
        return this.delta;
    }

    public String reason() {
        return this.reason;
    }

    @Override
    public String toString() {
        return "BalanceChanged(" + this.playerId + " " + this.oldBalance + "->" + this.newBalance
                + " d=" + this.delta + " " + this.reason + ")";
    }
}