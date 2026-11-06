package cn.autoforged.module_shop.client.screen.auction;

import cn.autoforged.module_shop.client.AuctionClient;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.AuctionBidScreen;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.data.AuctionEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** 拍卖行界面共用的小工具：入口路由、倒计时 / 状态的文字与颜色。 */
public final class AuctionUi {
    private AuctionUi() {
    }

    /** 打开详情：Skija 可用时用新详情页，否则回退到旧的出价界面。 */
    public static void openDetail(long id) {
        if (AtomUi.available()) {
            Nav.open(new AuctionDetailScreen(id));
            return;
        }
        ClientShopData.AuctionView view = AuctionClient.find(id);
        if (view != null) {
            Nav.open(new AuctionBidScreen(view));
        }
    }

    /** 打开上架界面：Skija 不可用时走旧的「存放槽 + 起拍价」流程。 */
    public static void openCreate(@javax.annotation.Nullable AuctionClient.History template) {
        if (AtomUi.available()) {
            Nav.open(new AuctionCreateScreen(template));
        } else {
            AuctionClient.send("create_auction", new CompoundTag());
        }
    }

    public static void openMine() {
        if (AtomUi.available()) {
            Nav.open(new AuctionMineScreen());
        }
    }

    public static void openAdmin() {
        if (AtomUi.available()) {
            Nav.open(new AuctionAdminScreen());
        }
    }

    public static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    public static String money(long amount) {
        return ClientShopData.money(amount);
    }

    /** 剩余时间：>1 天显示「x天x时」，否则 h:mm:ss。 */
    public static String remain(long ms) {
        if (ms <= 0L) {
            return tr("gui.module_shop.auction.ended");
        }
        long total = ms / 1000L;
        long days = total / 86400L;
        if (days > 0L) {
            return tr("gui.module_shop.auction.remain_days", days, (total % 86400L) / 3600L);
        }
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return h > 0L ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** 倒计时颜色：<1 分钟红，<10 分钟橙，其余次要文字色。 */
    public static int remainColor(long ms) {
        if (ms <= 60_000L) {
            return IOStyle.RED;
        }
        if (ms <= 600_000L) {
            return IOStyle.ORANGE;
        }
        return IOStyle.TEXT_2;
    }

    public static String ago(long ms) {
        long min = Math.max(0L, ms) / 60_000L;
        if (min < 1L) {
            return tr("gui.module_shop.auction.ago_now");
        }
        if (min < 60L) {
            return tr("gui.module_shop.auction.ago_min", min);
        }
        if (min < 1440L) {
            return tr("gui.module_shop.auction.ago_hour", min / 60L);
        }
        return tr("gui.module_shop.auction.ago_day", min / 1440L);
    }

    public static String status(String status) {
        return tr("gui.module_shop.auction.status." + (status == null || status.isEmpty() ? "active" : status));
    }

    public static int statusColor(String status) {
        return switch (status == null ? "" : status) {
            case AuctionEntry.SOLD, AuctionEntry.BUYOUT -> IOStyle.GREEN;
            case AuctionEntry.UNSOLD -> IOStyle.TEXT_2;
            case AuctionEntry.CANCELLED -> IOStyle.ORANGE;
            case AuctionEntry.ADMIN_CANCELLED, AuctionEntry.BROKEN -> IOStyle.RED;
            default -> IOStyle.BLUE;
        };
    }

    public static String duration(int minutes) {
        if (minutes % 1440 == 0) {
            return tr("gui.module_shop.auction.dur_day", minutes / 1440);
        }
        if (minutes % 60 == 0) {
            return tr("gui.module_shop.auction.dur_hour", minutes / 60);
        }
        return tr("gui.module_shop.auction.dur_min", minutes);
    }

    /** 解析玩家输入的金额：非法 / 负数返回 -1，空串返回 0。 */
    public static long parse(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            long v = Long.parseLong(s);
            return v < 0L ? -1L : v;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
