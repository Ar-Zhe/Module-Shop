package cn.autoforged.module_shop.api.view;

/**
 * 只读：一个活动日（日程）。
 *
 * @param day 日期序号（`epochDay` 口径，与签到一致）
 * @param title 标题（已本地化前的原始文本）
 * @param note 说明
 * @param money 当日发放的金币
 * @param rewardCount 附件奖励件数（含礼包）
 * @param hour 指定小时（-1 = 全天可领）
 */
public record ActivityView(long day, String title, String note, long money, int rewardCount, int hour) {
}