package cn.autoforged.module_shop.shop;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.data.PlayerAccount;
import cn.autoforged.module_shop.data.TransactionEntry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * 交易/资产流水：所有“钱或物品发生转移”的地方都往这里写一条，管理员用
 * {@code /shop admin history <玩家>} 翻页查看。
 * <p>
 * 只记录文本摘要，条目数由 {@link Config#transactionHistorySize} 限制（超出丢最旧的）。
 */
public final class TransactionLog {
    public static final String BUY = "buy";
    public static final String SELL = "sell";
    public static final String STALL_BUY = "stall_buy";
    public static final String STALL_SELL = "stall_sell";
    public static final String AUCTION_BID = "auction_bid";
    public static final String AUCTION_REFUND = "auction_refund";
    public static final String AUCTION_WIN = "auction_win";
    public static final String AUCTION_SELL = "auction_sell";
    public static final String TRANSFER_IN = "transfer_in";
    public static final String TRANSFER_OUT = "transfer_out";
    public static final String TRADE_IN = "trade_in";
    public static final String TRADE_OUT = "trade_out";
    public static final String MAIL_ITEM = "mail_item";
    public static final String MAIL_MONEY = "mail_money";
    public static final String ADMIN_MONEY = "admin_money";
    public static final String ADMIN_ITEM = "admin_item";
    /** 付费刷新每日商店。 */
    public static final String SHOP_REFRESH = "shop_refresh";
    /** 收购清单全部完成奖励。 */
    public static final String BUYBACK_BONUS = "buyback_bonus";
    /** 货币兑换：物品换成星币（余额增加）。 */
    public static final String EXCHANGE_BUY = "exchange_buy";
    /** 货币取款：星币换成物品（余额减少）。 */
    public static final String EXCHANGE_WITHDRAW = "exchange_withdraw";
    /** 每日签到奖励。 */
    public static final String SIGN_IN = "sign_in";
    /** 补签花费。 */
    public static final String SIGN_MAKEUP = "sign_makeup";
    /** 兑换码兑换。 */
    public static final String REDEEM = "redeem";
    /**
     * 外部账目：由**其它插件 / 模组**通过 {@code /shop wallet log} 或
     * {@link #recordExternal} 写进来的收支（不改变本模组余额，只记账）。
     */
    public static final String EXTERNAL = "external";

    /** 管理员查看时每页条数。 */
    public static final int PAGE_SIZE = 8;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private TransactionLog() {
    }

    /** 记录一条流水（服务端各资产变动点调用）。 */
    public static void record(MinecraftServer server, UUID owner, String type, String counterParty,
            long amount, long balanceAfter, String note) {
        if (server == null || owner == null) {
            return;
        }
        PlayerAccount account = ShopManager.data(server).account(owner);
        TransactionEntry entry = new TransactionEntry();
        entry.time = System.currentTimeMillis();
        entry.type = type == null ? "" : type;
        entry.counterParty = counterParty == null ? "" : counterParty;
        entry.amount = amount;
        entry.balanceAfter = balanceAfter;
        entry.note = note == null ? "" : note;
        account.transactions.add(entry);
        int cap = Config.INSTANCE.transactionHistorySize.get();
        while (account.transactions.size() > cap) {
            account.transactions.remove(0);
        }
        ShopManager.data(server).setDirty();
    }

    /** 物品说明文本（名称×数量），空栈返回空串。 */
    public static String itemText(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString() + "×" + stack.getCount();
    }

    /**
     * 记一笔**外部账目**（其它插件 / 模组扣的或加的钱）。
     * <p>
     * 只写账单，不动本模组余额 —— 别的插件有自己的经济系统，本模组无法直接改写它，
     * 但可以通过这个入口把它们的收支同步到玩家账单里，玩家看到的就是一份完整账目。
     *
     * @param amount 正数 = 收入，负数 = 支出
     * @param reason 事由（显示在账单备注里）
     * @param source 来源插件 / 系统名（显示在账单里，可为空）
     */
    public static void recordExternal(MinecraftServer server, UUID owner, long amount, String reason, String source) {
        if (server == null || owner == null || amount == 0L) {
            return;
        }
        PlayerAccount account = ShopManager.data(server).account(owner);
        record(server, owner, EXTERNAL, source == null ? "" : source, amount, account.balance,
                reason == null ? "" : reason);
    }

    /**
     * 渲染成聊天行：第一行是表头（玩家名 / 页码 / 条数 / 当前余额），其后是该页流水（最新在上）。
     *
     * @param page 从 1 开始
     */
    public static List<Component> format(PlayerAccount account, String name, int page) {
        List<Component> lines = new ArrayList<>();
        List<TransactionEntry> all = account.transactions;
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(1, page), pages);
        lines.add(Component.translatable("msg.module_shop.history.header",
                name, current, pages, all.size(), account.balance).withStyle(ChatFormatting.GOLD));
        if (all.isEmpty()) {
            lines.add(Component.translatable("msg.module_shop.history.empty").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        int to = all.size() - (current - 1) * PAGE_SIZE;
        int from = Math.max(0, to - PAGE_SIZE);
        for (int i = to - 1; i >= from; i--) {
            TransactionEntry entry = all.get(i);
            String time = entry.time > 0L
                    ? TIME_FORMAT.format(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()))
                    : "-";
            String amountText = entry.amount == 0L
                    ? ""
                    : (entry.amount > 0L ? "+" + entry.amount : Long.toString(entry.amount));
            Component line = Component.translatable("msg.module_shop.history.line",
                    time,
                    Component.translatable("gui.module_shop.history." + entry.type).getString(),
                    entry.counterParty,
                    amountText,
                    entry.balanceAfter,
                    entry.note);
            lines.add(line.copy().withStyle(entry.amount > 0L ? ChatFormatting.GREEN
                    : entry.amount < 0L ? ChatFormatting.RED : ChatFormatting.GRAY));
        }
        if (pages > 1) {
            lines.add(Component.translatable("msg.module_shop.history.footer", current, pages)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }
}
