package cn.autoforged.module_shop.api;

import cn.autoforged.module_shop.api.view.SignInView;
import java.util.UUID;

/**
 * 签到 / 活动只读 API。
 *
 * <p>**不提供**"伪造签到""重复领取"的入口：签到与活动奖励的发放仍由商店自己的规则驱动，
 * 外部系统请使用 {@link RewardApi#grantOnce} 下发自己的奖励。
 */
public interface DailyApi {
    /** 签到状态；玩家不存在返回 {@code null}。 */
    SignInView signIn(UUID target);

    /** 今天是否还能签到（已签过 / 功能关闭 → false）。 */
    boolean canSignToday(UUID target);

    /** 未来的活动日程（按日期升序，只读）；无活动返回空列表。 */
    java.util.List<cn.autoforged.module_shop.api.view.ActivityView> activities();
}