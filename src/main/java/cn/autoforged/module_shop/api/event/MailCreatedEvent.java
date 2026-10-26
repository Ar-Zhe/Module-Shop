package cn.autoforged.module_shop.api.event;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/**
 * 一封邮件已创建（**成功之后**发布，不可取消）。
 *
 * <p>触发点：{@code ShopManager.addMailDraft(...)} —— 覆盖商店/API/交易返还/拍卖/摊位等**所有**发信路径。
 */
public final class MailCreatedEvent extends Event {
    private final UUID playerId;
    private final String source;
    private final long money;
    private final int itemCount;
    private final String title;

    public MailCreatedEvent(UUID playerId, String source, long money, int itemCount, String title) {
        this.playerId = playerId;
        this.source = source == null ? "" : source;
        this.money = money;
        this.itemCount = itemCount;
        this.title = title == null ? "" : title;
    }

    public UUID playerId() {
        return this.playerId;
    }

    /** 来源标记（例如 api / stall_unsold / demand_deliver）。 */
    public String source() {
        return this.source;
    }

    public long money() {
        return this.money;
    }

    public int itemCount() {
        return this.itemCount;
    }

    public String title() {
        return this.title;
    }

    @Override
    public String toString() {
        return "MailCreated(" + this.playerId + " " + this.source + " money=" + this.money
                + " items=" + this.itemCount + ")";
    }
}