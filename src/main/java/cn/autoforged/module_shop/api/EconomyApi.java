package cn.autoforged.module_shop.api;

import cn.autoforged.module_shop.api.view.BalanceView;
import cn.autoforged.module_shop.api.view.CurrencyTierView;
import cn.autoforged.module_shop.api.view.CurrencyUnit;
import java.util.List;
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

    /**
     * 当前虚拟主货币显示名（默认 {@code "星币"}）。
     *
     * @since API 1.5
     */
    default String currencyName() {
        return "星币";
    }

    /**
     * 当前是否已激活实体货币系统。
     *
     * @since API 1.5
     */
    default boolean isPhysicalCurrencyEnabled() {
        return false;
    }

    /**
     * 获取当前所有有效实体货币档位；若 {@code target} 在线则附带背包持有量，否则 {@code ownedCount = 0}。
     *
     * @since API 1.5
     */
    default List<CurrencyTierView> physicalTiers(UUID target) {
        return List.of();
    }

    /**
     * 返回当前附属模组默认应使用的货币单位（已启用实体货币且配置了有效附属默认档位时返回该实体币，否则返回虚拟主货币）。
     *
     * @since API 1.5
     */
    default CurrencyUnit activeCurrency() {
        return CurrencyUnit.star(currencyName());
    }

    /**
     * 解析历史单据记录的货币标识（供红包/盲仓等跨配置变更安全结算）：
     * {@code null}/空串/{@code "star"} 严格返回虚拟主货币；非空优先查找对应实体档位，若档位被删空且链内仍有档位则回退第 0 档，否则回退虚拟主货币。
     *
     * @since API 1.5
     */
    default CurrencyUnit resolveCurrency(String tierKeyOrEmpty) {
        return activeCurrency();
    }

    /**
     * 查询玩家在指定货币单位下的可用余额（实体币仅统计在线玩家背包按链内可精确支付的最大枚数，离线返回 0）。
     *
     * @since API 1.5
     */
    default long balance(UUID target, CurrencyUnit unit) {
        if (unit == null || !unit.physical()) {
            return balance(target);
        }
        return 0L;
    }

    /**
     * 查询玩家在当前附属默认货币（{@link #activeCurrency()}）下的可用余额。
     *
     * @since API 1.5
     */
    default long balanceActive(UUID target) {
        return balance(target, activeCurrency());
    }

    /**
     * 按指定货币单位发钱：虚拟主货币溢出（含原本已满）转金钱邮件保证整笔必达；实体币在线发背包（超限自动高阶压箱），满包或离线发物品邮件。
     *
     * @since API 1.5
     */
    default ApiResult<Long> add(UUID target, CurrencyUnit unit, long amount, String reason) {
        if (unit == null || !unit.physical()) {
            return add(target, amount, reason);
        }
        return ApiResult.fail(ApiErrorCode.DISABLED, "当前模块商店版本不支持实体货币 API");
    }

    /**
     * 按当前附属默认货币（{@link #activeCurrency()}）发钱。
     *
     * @since API 1.5
     */
    default ApiResult<Long> addActive(UUID target, long amount, String reason) {
        return add(target, activeCurrency(), amount, reason);
    }

    /**
     * 按指定货币单位扣钱：实体币要求玩家在线，不足或无法找零时整笔失败。
     *
     * @since API 1.5
     */
    default ApiResult<Long> take(UUID target, CurrencyUnit unit, long amount, String reason) {
        if (unit == null || !unit.physical()) {
            return take(target, amount, reason);
        }
        return ApiResult.fail(ApiErrorCode.DISABLED, "当前模块商店版本不支持实体货币 API");
    }

    /**
     * 按当前附属默认货币（{@link #activeCurrency()}）扣钱。
     *
     * @since API 1.5
     */
    default ApiResult<Long> takeActive(UUID target, long amount, String reason) {
        return take(target, activeCurrency(), amount, reason);
    }
}