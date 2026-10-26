package cn.autoforged.server_shop_mod_1789689358.api.view;

/** 只读：一笔经济流水。 */
public record TransactionInfo(long time, String type, String counterParty, long amount, long balanceAfter,
        String note) {
}