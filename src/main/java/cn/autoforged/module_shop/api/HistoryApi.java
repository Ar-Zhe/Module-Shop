package cn.autoforged.module_shop.api;

import cn.autoforged.module_shop.api.view.TransactionInfo;
import java.util.List;
import java.util.UUID;

/**
 * 经济流水 API。
 *
 * <p>注意：商店**升级前不保留无限历史**，每人只保留 {@code transactionHistorySize} 条（默认 50）。
 */
public interface HistoryApi {
    /** 最近的流水（**新的在前**），最多 {@code limit} 条。 */
    List<TransactionInfo> history(UUID target, int limit);

    /**
     * 只记账、不动余额（供外部经济系统把自己的收支写进玩家账单）。
     *
     * @param amount 正数=收入，负数=支出
     */
    ApiResult<Void> record(UUID target, String type, String counterParty, long amount, String note);
}