package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.data.MailEntry;
import cn.autoforged.module_shop.data.MailType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 邮件的展示规则（排序、标题、说明文字、时间、配色）。
 * 从 ShopScreen 中提取，新旧邮箱界面共用同一份逻辑。
 */
public final class MailFormat {
    private MailFormat() {
    }

    /** 邮箱排序：星标优先，其次按时间倒序（最新在上）。 */
    public static List<MailEntry> sorted() {
        List<MailEntry> list = new ArrayList<>(ClientShopData.mail);
        list.sort((a, b) -> {
            if (a.starred != b.starred) {
                return a.starred ? -1 : 1;
            }
            return Long.compare(b.sentAt, a.sentAt);
        });
        return list;
    }

    /** 当前选中的邮件（没有则返回第一封，列表为空返回 null）。 */
    public static MailEntry selected(List<MailEntry> mails) {
        for (MailEntry entry : mails) {
            if (entry.mailId.equals(ClientShopData.mailSelected)) {
                return entry;
            }
        }
        return mails.isEmpty() ? null : mails.get(0);
    }

    /** 未读：金额邮件看是否已查看，物品邮件看是否已领取。 */
    public static boolean unread(MailEntry entry) {
        return entry.type == MailType.MONEY ? !entry.isViewed : !entry.isClaimed;
    }

    /** 按邮件业务类型取配色（列表状态点与标题色）。 */
    public static int kindColor(MailEntry entry) {
        String kind = entry.kind == null ? "" : entry.kind;
        return switch (kind) {
            case "auction" -> 0xFFB388FF;
            case "stall" -> IOStyle.BLUE;
            case "trade" -> 0xFF4DD0E1;
            case "redeem" -> IOStyle.ORANGE;
            case "activity" -> IOStyle.GREEN;
            case "refund" -> IOStyle.TEXT_2;
            default -> IOStyle.ORANGE;
        };
    }

    /** 邮件标题：优先模板标题，否则按来源生成说明。 */
    public static String title(MailEntry entry) {
        if (entry.title != null && !entry.title.isEmpty()) {
            return entry.title;
        }
        return description(entry);
    }

    /** 把 & 颜色代码转成 §（客户端渲染用）。 */
    public static String colorize(String text) {
        return text == null ? "" : text.replace('&', '§');
    }

    /** 邮件时间文本。 */
    public static String time(MailEntry entry) {
        if (entry.sentAt <= 0L) {
            return "-";
        }
        return java.time.Instant.ofEpochMilli(entry.sentAt)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    /** 根据邮件来源生成说明文字（物品退还 / 星币到账 / 管理员发放）。 */
    public static String description(MailEntry entry) {
        // 一封邮件可带多件物品，说明文字用第一件代表（多件时加「等 N 件」）。
        List<ItemStack> items = entry.itemStacks();
        String item = items.isEmpty() ? "-" : items.get(0).getHoverName().getString();
        String itemWithCount = items.isEmpty() ? "-"
                : items.get(0).getHoverName().getString() + "×" + items.get(0).getCount()
                        + (items.size() > 1 ? " 等 " + items.size() + " 件" : "");
        String sender = entry.sender == null || entry.sender.isEmpty() ? "-" : entry.sender;
        if ("admin_grant".equals(entry.source)) {
            return entry.type == MailType.MONEY
                    ? Component.translatable("gui.module_shop.mail.desc.admin_grant_money", sender,
                            ClientShopData.money(entry.currencyAmount)).getString()
                    : Component.translatable("gui.module_shop.mail.desc.admin_grant_item", sender, itemWithCount)
                            .getString();
        }
        String key = switch (entry.source == null ? "" : entry.source) {
            case "stall_unsold" -> "gui.module_shop.mail.desc.stall_unsold";
            case "stall_bought" -> "gui.module_shop.mail.desc.stall_bought";
            case "auction_refund" -> "gui.module_shop.mail.desc.auction_refund";
            case "auction_unsold" -> "gui.module_shop.mail.desc.auction_unsold";
            case "auction_won" -> "gui.module_shop.mail.desc.auction_won";
            case "stall_sold" -> "gui.module_shop.mail.desc.stall_sold";
            case "auction_sold" -> "gui.module_shop.mail.desc.auction_sold";
            default -> "gui.module_shop.mail.desc.default";
        };
        if (entry.type == MailType.MONEY) {
            return Component.translatable(key, sender, item, ClientShopData.money(entry.currencyAmount)).getString();
        }
        return Component.translatable(key, item).getString();
    }
}
