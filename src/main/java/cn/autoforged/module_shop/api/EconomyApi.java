package cn.autoforged.module_shop.api;

import cn.autoforged.module_shop.api.view.BalanceView;
import java.util.UUID;

/**
 * 经济 API：**所有**货币变化都必须经过这里（服务端权威、受上限约束、写流水）。
 *
 * <p>线程：必须在**服务端主线程**调用（内部会做线程断言，非主线程直接返回 {@link ApiErrorCode#WRONG_THREAD}）。
 *
 * <p>权限级别：
 * <ul>
 *     <li>查询：{@code SYSTEM}（任意模组）</li>
 *     <li>{@code add} / {@code take}：{@code SYSTEM}（受上限、流水、幂等约束）</li>
 *     <li>{@code setBalance}：**不开放**（保留给 {@code /shop admin} 命令）</li>
 * </ul>
 */
public interface EconomyApi {
    /** 余额（离线玩家同样可查）；玩家不存在返回 0。 */
    long balance(UUID target);

    /** 余额视图（含上限与货币名）；玩家不存在返回 {@code null}。 */
    BalanceView balanceView(UUID target);

    /** 余额上限：{@code 0} 表示不限。 */
    long maxBalance();

    /**
     * 加钱（受余额上限约束，装不下的部分转**金钱邮件**）。
     *
     * @return 成功时 value = **实际入账金额**（可能小于请求值）
     */
    ApiResult<Long> add(UUID target, long amount, String reason);

    /**
     * 扣钱：**余额不足时整笔失败**（不会扣到 0）。
     *
     * @return 成功时 value = 实际扣除金额（等于请求值）
     */
    ApiResult<Long> take(UUID target, long amount, String reason);
}