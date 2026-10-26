package cn.autoforged.module_shop.api.view;

/** 只读：玩家余额视图。 */
public record BalanceView(long balance, long maxBalance, String currencyName) {
    /** 上限是否不限（{@code maxBalance <= 0} 表示不限）。 */
    public boolean unlimited() {
        return this.maxBalance <= 0L;
    }
}