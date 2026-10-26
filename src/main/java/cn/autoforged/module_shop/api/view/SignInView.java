package cn.autoforged.module_shop.api.view;

/** 只读：签到状态。 */
public record SignInView(boolean signedToday, int streak, int bestStreak, long lastSignDay,
        long baseDailyMoney) {
}