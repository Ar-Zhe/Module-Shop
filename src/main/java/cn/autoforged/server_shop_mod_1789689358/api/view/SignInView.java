package cn.autoforged.server_shop_mod_1789689358.api.view;

/** 只读：签到状态。 */
public record SignInView(boolean signedToday, int streak, int bestStreak, long lastSignDay,
        long baseDailyMoney) {
}