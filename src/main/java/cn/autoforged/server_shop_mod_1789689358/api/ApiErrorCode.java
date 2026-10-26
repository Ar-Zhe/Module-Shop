package cn.autoforged.server_shop_mod_1789689358.api;

/** 公共 API 的失败原因（调用方据此判断，不要靠解析文案）。 */
public enum ApiErrorCode {
    /** 成功（{@link ApiResult#ok()} 时不会用到）。 */
    NONE,
    /** 参数不合法（null / 负数 / 空字符串 / 数量越界）。 */
    INVALID_ARGUMENT,
    /** 找不到该玩家（UUID 无对应账户，且不是在线玩家）。 */
    UNKNOWN_PLAYER,
    /** 调用线程不对：必须在服务端主线程调用。 */
    WRONG_THREAD,
    /** 余额不足（{@link EconomyApi#take} 语义：整笔失败，绝不扣到 0）。 */
    INSUFFICIENT_FUNDS,
    /** 余额上限限制（加钱时被截断，实际入账见返回值）。 */
    LIMIT_EXCEEDED,
    /** 邮箱已满且没有兜底空间。 */
    MAILBOX_FULL,
    /** 幂等键重复：这次请求之前已经成功执行过。 */
    DUPLICATE_REQUEST,
    /** 该能力在本机未启用（如求购/签到被配置关闭）。 */
    DISABLED,
    /** 权限不足（超越调用级别）。 */
    NO_PERMISSION,
    /** 该操作要求玩家在线（涉及背包/界面），离线玩家会收到此码。 */
    PLAYER_OFFLINE,
    /** 价格已变化（调用方心里的价格与服务端不一致），请重新查询后再操作。 */
    PRICE_CHANGED,
    /** 已售罄 / 已收满（库存或收购份额不足）。 */
    SOLD_OUT,
    /** 内部异常（详情见服务端日志）。 */
    INTERNAL_ERROR
}