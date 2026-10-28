package cn.autoforged.module_shop.data;

/**
 * 需求 10：商店各类“交易请求/事项”通知类型。
 * 服务端权威维护每名玩家各类型的未读数量，客户端据此在对应按钮上渲染红点/角标。
 */
public enum NotificationType {
    MAIL("mail"),
    AUCTION("auction"),
    STALL("stall"),
    /** 每日商店：新的一天刷新了货架。 */
    SHOP("shop"),
    /** 每日收购：新的一天刷新了收购清单（和每日商店分开计数，红点不会一起亮）。 */
    BUYBACK("buyback"),
    WALLET("wallet"),
    TRADE("trade");

    private final String id;

    NotificationType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static NotificationType byId(String id) {
        for (NotificationType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
