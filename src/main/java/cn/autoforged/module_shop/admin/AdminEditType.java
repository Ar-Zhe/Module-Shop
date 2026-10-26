package cn.autoforged.module_shop.admin;

/**
 * 需求 11：管理员当前正在编辑的模块。
 * 以前每日商店与收购商店的配置混在一起，服务端无法根据槽位判断物品该写入哪个列表；
 * 现在由服务端记录该状态，放入物品时按此归属写入 DailyShopConfig / BuybackConfig。
 */
public enum AdminEditType {
    DAILY_SHOP,
    BUYBACK,
    /** 购物城（新增模块）：常驻货架，库存是真库存。 */
    MALL,
    /** 出货箱（新增模块）：可编辑的是「兑换规则」（物品 + 单价 + 每日份数）。 */
    SHIPPING;

    public String id() {
        return switch (this) {
            case DAILY_SHOP -> "shop";
            case BUYBACK -> "buyback";
            case MALL -> "mall";
            case SHIPPING -> "shipping";
        };
    }

    public static AdminEditType byId(String id) {
        if ("buyback".equalsIgnoreCase(id)) {
            return BUYBACK;
        }
        if ("mall".equalsIgnoreCase(id)) {
            return MALL;
        }
        if ("shipping".equalsIgnoreCase(id)) {
            return SHIPPING;
        }
        return DAILY_SHOP;
    }
}
