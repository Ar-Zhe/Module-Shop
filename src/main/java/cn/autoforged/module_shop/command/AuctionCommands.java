package cn.autoforged.module_shop.command;

import cn.autoforged.module_shop.data.AuctionEntry;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.shop.ShopManager;
import cn.autoforged.module_shop.shop.auction.AuctionService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** /shop auction … 管理命令（权限等级 2）。输出为纯文本，控制台也能用。 */
final class AuctionCommands {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final int PAGE = 10;

    private AuctionCommands() {
    }

    private static void line(CommandSourceStack source, String text, ChatFormatting color) {
        source.sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }

    private static String item(AuctionEntry e) {
        return e.stack.isEmpty() ? "<物品数据缺失>" : e.stack.getHoverName().getString() + "×" + e.stack.getCount();
    }

    static int list(CommandSourceStack source) {
        ShopSavedData data = ShopManager.data(source.getServer());
        long now = System.currentTimeMillis();
        line(source, "== 进行中的拍卖（" + data.auctions.size() + "）==", ChatFormatting.GOLD);
        int shown = 0;
        for (AuctionEntry e : data.auctions) {
            if (shown++ >= 50) {
                line(source, "……仅显示前 50 条", ChatFormatting.GRAY);
                break;
            }
            long left = Math.max(0L, e.endTime - now) / 1000L;
            line(source, "#" + e.id + " " + item(e) + " 卖家 " + e.sellerName
                    + " 当前 " + e.currentBid + (e.hasBid() ? "（" + e.topBidderName + "）" : "（无人出价，起拍 "
                    + e.startPrice + "）") + " 托管 " + e.escrow + " 剩余 " + (left / 60) + "分" + (left % 60) + "秒",
                    ChatFormatting.WHITE);
        }
        return data.auctions.size();
    }

    static int history(CommandSourceStack source, int page) {
        ShopSavedData data = ShopManager.data(source.getServer());
        List<AuctionEntry> all = data.auctionHistory;
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        int p = Math.min(page, pages);
        line(source, "== 拍卖记录 第 " + p + "/" + pages + " 页（共 " + all.size() + " 条）==", ChatFormatting.GOLD);
        for (int i = all.size() - 1 - (p - 1) * PAGE, n = 0; i >= 0 && n < PAGE; i--, n++) {
            AuctionEntry e = all.get(i);
            line(source, TIME.format(Instant.ofEpochMilli(e.settledAt)) + " #" + e.id + " [" + e.status + "] "
                    + item(e) + " 卖家 " + e.sellerName
                    + (e.buyer != null ? " 买家 " + e.buyerName + " 成交 " + e.finalPrice + " 手续费 " + e.fee : "")
                    + (e.operator.isEmpty() ? "" : " 操作者 " + e.operator)
                    + (e.escrow != 0L ? " ⚠托管 " + e.escrow : ""),
                    e.escrow != 0L || AuctionEntry.BROKEN.equals(e.status) ? ChatFormatting.RED : ChatFormatting.WHITE);
        }
        return all.size();
    }

    static int anomalies(CommandSourceStack source) {
        ShopSavedData data = ShopManager.data(source.getServer());
        List<String> list = data.auctionAnomalies;
        line(source, "== 拍卖异常记录（" + list.size() + "）==", ChatFormatting.GOLD);
        if (list.isEmpty()) {
            line(source, "没有异常记录", ChatFormatting.GREEN);
        }
        for (int i = list.size() - 1, n = 0; i >= 0 && n < 20; i--, n++) {
            line(source, list.get(i), ChatFormatting.RED);
        }
        return list.size();
    }

    static int audit(CommandSourceStack source) {
        List<String> result = AuctionService.audit(source.getServer(), source.getTextName());
        for (int i = 0; i < result.size(); i++) {
            line(source, result.get(i), i == 0 ? ChatFormatting.GOLD : ChatFormatting.RED);
        }
        if (result.size() <= 1) {
            line(source, "审计通过：托管资金与拍卖状态一致", ChatFormatting.GREEN);
        }
        return result.size() - 1;
    }

    static int force(CommandSourceStack source, long id, boolean cancel) {
        boolean found = AuctionService.adminCommand(source.getServer(), id, cancel, source.getTextName());
        if (!found) {
            source.sendFailure(Component.literal("找不到进行中的拍卖 #" + id + "（可能已经结束）"));
            return 0;
        }
        line(source, (cancel ? "已强制撤销拍卖 #" : "已强制结束拍卖 #") + id
                + (cancel ? "（出价已全额退还，物品已退回卖家邮箱）" : "（已按当前状态结算）"), ChatFormatting.GREEN);
        return 1;
    }
}
