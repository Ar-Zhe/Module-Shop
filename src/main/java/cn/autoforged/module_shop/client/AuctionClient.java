package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.client.screen.ShopScreen;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import cn.autoforged.module_shop.shop.auction.AuctionCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端拍卖行数据：大厅列表（全量 + 增量）、详情、我的拍卖、管理员配置，以及搜索 / 分类 / 排序状态。
 *
 * <p>服务端是唯一权威：这里的价格、剩余时间、最低出价都只用于展示；提交时带上「我看到的当前价」(expect)，
 * 服务端发现不一致会拒绝并推送最新数据。所有状态都是静态的，商店界面重建时不会丢。
 */
public final class AuctionClient {
    private AuctionClient() {
    }

    // ---- 版本 ----
    /** 服务端最新列表版本（随普通快照下发）。 */
    public static long serverVersion;
    /** 本地列表对应的版本（0 = 从未拉取）。 */
    public static long localVersion;
    private static long lastRequestMs;

    // ---- 设置（服务端配置的展示副本）----
    public static final class Settings {
        public boolean enabled = true;
        public List<Integer> durations = new ArrayList<>(List.of(60));
        public int defaultDuration = 60;
        public long minIncrement = 1L;
        public long minStart = 1L;
        public long maxStart;
        public boolean allowBuyout = true;
        public long feeFixed;
        public double feePercent;
        public double saleFeePercent;
        public int maxCount = 64;
        public int myActive;
        public int maxPerPlayer = 6;
        public boolean admin;
        public boolean blockCreative;

        public long listingFee(long start) {
            long byPercent = feePercent <= 0.0D ? 0L : (long) Math.ceil(start * feePercent / 100.0D);
            long sum = Math.max(0L, feeFixed) + Math.max(0L, byPercent);
            return sum < 0L ? Long.MAX_VALUE : sum;
        }

        public long saleFee(long price) {
            return saleFeePercent <= 0.0D || price <= 0L ? 0L
                    : Math.min(price, (long) Math.ceil(price * (saleFeePercent / 100.0D)));
        }
    }

    public static Settings settings = new Settings();

    // ---- 详情 ----
    public static final class Bid {
        public String name = "";
        public long amount;
        public long ago;
        public boolean me;
    }

    public static final class Detail {
        public long id;
        public boolean missing;
        public ClientShopData.AuctionView view;
        public String status = "active";
        public boolean canCancel;
        public boolean admin;
        public long balance;
        public long finalPrice;
        public String buyerName = "";
        public long endedAgo;
        public long receivedAt;
        public final List<Bid> bids = new ArrayList<>();
    }

    public static Detail detail;
    /** 每次收到详情 +1（界面据此知道有新数据）。 */
    public static int detailSeq;

    // ---- 我的拍卖 ----
    public static final class History {
        public long id;
        public ItemStack stack = ItemStack.EMPTY;
        public String status = "";
        public String role = "seller";
        public String seller = "";
        public String buyer = "";
        public long finalPrice;
        public long fee;
        public long net;
        public long listingFee;
        public long endedAt;
        public long startPrice;
        public long increment;
        public long buyout;
        public boolean allowBid = true;
        public int duration;
        public int bidCount;
        public String operator = "";
    }

    public static List<ClientShopData.AuctionView> mineSelling = new ArrayList<>();
    public static List<ClientShopData.AuctionView> mineBidding = new ArrayList<>();
    public static List<History> mineHistory = new ArrayList<>();
    public static int mineSeq;

    // ---- 管理员 ----
    public static CompoundTag admin;
    public static int adminSeq;

    // ---- 提交结果 ----
    public static int resultSeq;
    public static boolean lastOk;
    public static String lastOp = "";

    // ---- 大厅筛选（跨界面重建保留）----
    public static String search = "";
    public static AuctionCategory category = AuctionCategory.ALL;
    public static int sort;
    public static int page;
    public static final int SORT_COUNT = 7;

    /** 断开连接时清空（不同服务器的拍卖 id / 版本号互不相干）。 */
    public static void reset() {
        serverVersion = 0L;
        localVersion = 0L;
        lastRequestMs = 0L;
        settings = new Settings();
        detail = null;
        mineSelling = new ArrayList<>();
        mineBidding = new ArrayList<>();
        mineHistory = new ArrayList<>();
        admin = null;
        ClientShopData.auctions = new ArrayList<>();
        page = 0;
    }

    // =================================================================== 请求

    public static void send(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        tag.putString("action", action);
        ServerboundShopActionPayload.send(tag);
    }

    /** 带防重复请求号发送（上架 / 出价 / 一口价）。 */
    public static void sendOnce(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        long nonce;
        do {
            nonce = ThreadLocalRandom.current().nextLong();
        } while (nonce == 0L);
        tag.putLong("nonce", nonce);
        send(action, tag);
    }

    /** 大厅需要数据时调用（每帧调用也没关系：有节流，版本没变不重复请求）。 */
    public static void ensureList() {
        long now = System.currentTimeMillis();
        boolean stale = localVersion == 0L || (serverVersion != 0L && serverVersion != localVersion);
        if (stale && now - lastRequestMs > 1500L) {
            requestList();
        }
    }

    public static void requestList() {
        lastRequestMs = System.currentTimeMillis();
        CompoundTag tag = new CompoundTag();
        tag.putLong("version", localVersion);
        send("auction_list", tag);
    }

    // =================================================================== 接收

    /** @return true 表示这个包是拍卖行的 */
    public static boolean handle(String kind, CompoundTag data, HolderLookup.Provider regs) {
        switch (kind) {
            case "auction_list" -> {
                readSettings(data.getCompound("settings"));
                if (!data.getBoolean("unchanged")) {
                    List<ClientShopData.AuctionView> list = new ArrayList<>();
                    for (Tag t : data.getList("entries", Tag.TAG_COMPOUND)) {
                        list.add(readView((CompoundTag) t, regs));
                    }
                    ClientShopData.auctions = list;
                }
                localVersion = data.getLong("version");
                serverVersion = localVersion;
            }
            case "auction_delta" -> {
                List<ClientShopData.AuctionView> list = new ArrayList<>(ClientShopData.auctions);
                Set<Long> removed = new HashSet<>();
                for (Tag t : data.getList("remove", Tag.TAG_LONG)) {
                    removed.add(((net.minecraft.nbt.LongTag) t).getAsLong());
                }
                list.removeIf(v -> removed.contains(v.id));
                for (Tag t : data.getList("upsert", Tag.TAG_COMPOUND)) {
                    ClientShopData.AuctionView view = readView((CompoundTag) t, regs);
                    boolean replaced = false;
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).id == view.id) {
                            list.set(i, view);
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) {
                        list.add(view);
                    }
                }
                ClientShopData.auctions = list;
                settings.myActive = data.getInt("myActive");
                // 只有本地基于上一版本时增量才算「追上」，否则下次 ensureList 会全量重拉。
                long v = data.getLong("version");
                if (localVersion != 0L) {
                    localVersion = v;
                }
                serverVersion = v;
                // 「我的拍卖」也跟着同步正在进行的部分
                patchMine(list);
            }
            case "auction_detail" -> {
                Detail d = new Detail();
                d.id = data.getLong("id");
                d.missing = data.getBoolean("missing");
                d.receivedAt = System.currentTimeMillis();
                if (!d.missing) {
                    d.view = readView(data, regs);
                    d.status = data.getString("status");
                    d.canCancel = data.getBoolean("canCancel");
                    d.admin = data.getBoolean("admin");
                    d.balance = data.getLong("balance");
                    d.finalPrice = data.getLong("finalPrice");
                    d.buyerName = data.getString("buyerName");
                    d.endedAgo = data.getLong("endedAgo");
                    for (Tag t : data.getList("bids", Tag.TAG_COMPOUND)) {
                        CompoundTag b = (CompoundTag) t;
                        Bid bid = new Bid();
                        bid.name = b.getString("name");
                        bid.amount = b.getLong("amount");
                        bid.ago = b.getLong("ago");
                        bid.me = b.getBoolean("me");
                        d.bids.add(bid);
                    }
                }
                detail = d;
                detailSeq++;
            }
            case "auction_mine" -> {
                readSettings(data.getCompound("settings"));
                mineSelling = readViews(data.getList("selling", Tag.TAG_COMPOUND), regs);
                mineBidding = readViews(data.getList("bidding", Tag.TAG_COMPOUND), regs);
                List<History> history = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (Tag t : data.getList("history", Tag.TAG_COMPOUND)) {
                    CompoundTag c = (CompoundTag) t;
                    History h = new History();
                    h.id = c.getLong("id");
                    h.stack = ItemStack.parseOptional(regs, c.getCompound("item"));
                    h.status = c.getString("status");
                    h.role = c.getString("role");
                    h.seller = c.getString("seller");
                    h.buyer = c.getString("buyer");
                    h.finalPrice = c.getLong("finalPrice");
                    h.fee = c.getLong("fee");
                    h.net = c.getLong("net");
                    h.listingFee = c.getLong("listingFee");
                    h.endedAt = now - c.getLong("endedAgo");
                    h.startPrice = c.getLong("startPrice");
                    h.increment = c.getLong("increment");
                    h.buyout = c.getLong("buyout");
                    h.allowBid = c.getBoolean("allowBid");
                    h.duration = c.getInt("duration");
                    h.bidCount = c.getInt("bidCount");
                    h.operator = c.getString("operator");
                    history.add(h);
                }
                mineHistory = history;
                mineSeq++;
            }
            case "auction_admin" -> {
                admin = data.copy();
                adminSeq++;
            }
            case "auction_result" -> {
                lastOk = data.getBoolean("ok");
                lastOp = data.getString("op");
                resultSeq++;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void patchMine(List<ClientShopData.AuctionView> list) {
        List<ClientShopData.AuctionView> selling = new ArrayList<>();
        List<ClientShopData.AuctionView> bidding = new ArrayList<>();
        for (ClientShopData.AuctionView v : list) {
            if (v.own) {
                selling.add(v);
            } else if (v.iBid) {
                bidding.add(v);
            }
        }
        mineSelling = selling;
        mineBidding = bidding;
    }

    private static List<ClientShopData.AuctionView> readViews(ListTag tags, HolderLookup.Provider regs) {
        List<ClientShopData.AuctionView> out = new ArrayList<>();
        for (Tag t : tags) {
            out.add(readView((CompoundTag) t, regs));
        }
        return out;
    }

    private static void readSettings(CompoundTag s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        Settings n = new Settings();
        n.enabled = s.getBoolean("enabled");
        List<Integer> durations = new ArrayList<>();
        for (Tag t : s.getList("durations", Tag.TAG_INT)) {
            durations.add(((net.minecraft.nbt.IntTag) t).getAsInt());
        }
        if (!durations.isEmpty()) {
            n.durations = durations;
        }
        n.defaultDuration = s.getInt("defaultDuration");
        n.minIncrement = Math.max(1L, s.getLong("minIncrement"));
        n.minStart = Math.max(1L, s.getLong("minStart"));
        n.maxStart = s.getLong("maxStart");
        n.allowBuyout = s.getBoolean("allowBuyout");
        n.feeFixed = s.getLong("feeFixed");
        n.feePercent = s.getDouble("feePercent");
        n.saleFeePercent = s.getDouble("saleFeePercent");
        n.maxCount = Math.max(1, s.getInt("maxCount"));
        n.myActive = s.getInt("myActive");
        n.maxPerPlayer = s.getInt("maxPerPlayer");
        n.admin = s.getBoolean("admin");
        n.blockCreative = s.getBoolean("blockCreative");
        settings = n;
    }

    public static ClientShopData.AuctionView readView(CompoundTag c, HolderLookup.Provider regs) {
        ClientShopData.AuctionView view = new ClientShopData.AuctionView();
        view.id = c.getLong("id");
        view.stack = c.contains("item") ? ItemStack.parseOptional(regs, c.getCompound("item")) : ItemStack.EMPTY;
        view.seller = c.getString("seller");
        view.startPrice = c.getLong("startPrice");
        view.currentBid = c.getLong("currentBid");
        view.lastBid = c.getLong("lastBid");
        view.topBidder = c.getString("topBidder");
        view.remaining = c.getLong("remaining");
        view.receivedAt = System.currentTimeMillis();
        view.own = c.getBoolean("own");
        view.topMe = c.getBoolean("topMe");
        view.iBid = c.getBoolean("iBid");
        view.buyout = c.getLong("buyout");
        view.allowBid = !c.contains("allowBid") || c.getBoolean("allowBid");
        view.increment = Math.max(1L, c.getLong("increment"));
        view.minNext = c.getLong("minNext");
        view.bidders = c.getInt("bidders");
        view.bidCount = c.getInt("bidCount");
        view.extensions = c.getInt("extensions");
        view.createdAt = view.receivedAt - c.getLong("createdAgo");
        return view;
    }

    @javax.annotation.Nullable
    public static ClientShopData.AuctionView find(long id) {
        for (ClientShopData.AuctionView v : ClientShopData.auctions) {
            if (v.id == id) {
                return v;
            }
        }
        return null;
    }

    // =================================================================== 搜索 / 分类 / 排序

    public static AuctionCategory categoryOf(ClientShopData.AuctionView v) {
        if (v.category == null) {
            v.category = AuctionCategory.of(v.stack);
        }
        return v.category;
    }

    private static String searchText(ClientShopData.AuctionView v) {
        if (v.searchText == null) {
            StringBuilder sb = new StringBuilder();
            if (!v.stack.isEmpty()) {
                sb.append(v.stack.getHoverName().getString()).append(' ');
                var id = BuiltInRegistries.ITEM.getKey(v.stack.getItem());
                sb.append(id).append(' ').append(id.getPath().replace('_', ' ')).append(' ');
                sb.append(ShopScreen.mallModName(id.getNamespace())).append(' ');
                // 附魔书 / 带附魔的物品：附魔名也能搜到（例如「经验修补」「mending」）
                v.stack.getEnchantments().keySet().forEach(h -> sb.append(
                        net.minecraft.world.item.enchantment.Enchantment.getFullname(h, 1).getString()).append(' '));
                v.stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
                        net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).keySet().forEach(h -> sb.append(
                        net.minecraft.world.item.enchantment.Enchantment.getFullname(h, 1).getString()).append(' '));
            }
            sb.append(v.seller);
            v.searchText = sb.toString().toLowerCase(Locale.ROOT);
        }
        return v.searchText;
    }

    /** 关键词模糊匹配：按空格拆成多个词，每个词都要出现（名称 / 物品 id / 模组名 / 附魔 / 卖家任一处）。 */
    public static boolean matches(ClientShopData.AuctionView v, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String hay = searchText(v);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!word.isEmpty() && !hay.contains(word)) {
                return false;
            }
        }
        return true;
    }

    public static String sortKey(int sort) {
        return "gui.module_shop.auction.sort." + switch (sort) {
            case 1 -> "ending";
            case 2 -> "price_asc";
            case 3 -> "price_desc";
            case 4 -> "bid_high";
            case 5 -> "bid_low";
            case 6 -> "bidders";
            default -> "newest";
        };
    }

    /** 搜索 + 分类 + 排序后的大厅列表。 */
    public static List<ClientShopData.AuctionView> filtered() {
        List<ClientShopData.AuctionView> out = new ArrayList<>();
        for (ClientShopData.AuctionView v : ClientShopData.auctions) {
            if (category != AuctionCategory.ALL && categoryOf(v) != category) {
                continue;
            }
            if (!matches(v, search)) {
                continue;
            }
            out.add(v);
        }
        Comparator<ClientShopData.AuctionView> cmp = switch (sort) {
            case 1 -> Comparator.comparingLong(ClientShopData.AuctionView::remainingNow);
            case 2 -> Comparator.comparingLong(ClientShopData.AuctionView::effectivePrice);
            case 3 -> Comparator.comparingLong(ClientShopData.AuctionView::effectivePrice).reversed();
            case 4 -> Comparator.comparingLong((ClientShopData.AuctionView v) -> v.currentBid).reversed();
            // 「当前出价最低」：有人出价的按价升序在前，无人出价的排最后
            case 5 -> Comparator.comparingLong((ClientShopData.AuctionView v) -> v.currentBid > 0L ? v.currentBid
                    : Long.MAX_VALUE);
            case 6 -> Comparator.comparingInt((ClientShopData.AuctionView v) -> v.bidders).reversed();
            default -> Comparator.comparingLong((ClientShopData.AuctionView v) -> v.createdAt).reversed();
        };
        out.sort(cmp.thenComparingLong(v -> v.id));
        return out;
    }
}
