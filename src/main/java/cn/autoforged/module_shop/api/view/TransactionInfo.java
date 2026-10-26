package cn.autoforged.module_shop.api.view;

/** 只读：一笔经济流水。 */
public record TransactionInfo(long time, String type, String counterParty, long amount, long balanceAfter,
        String note) {
}