package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import cn.autoforged.module_shop.shop.auction.AuctionCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端玩家商店数据：商店大厅（全服摊位商品汇总）、筛选状态、上架参数，以及「从大厅点进摊位时预选哪件商品」。
 * 价格 / 库存只用于展示，购买时带上「我看到的价格」由服务端核对。
 */
public final class StallClient {
    private StallClient() {
    }

    public static final class Item {
        public UUID owner;
        public String ownerName = "";
        public long id;
        public ItemStack stack = ItemStack.EMPTY;
        public long price;
        public int stock;
        public int sold;
        public boolean own;
        public AuctionCategory category;
        public String searchText;
    }

    public static List<Item> market = new ArrayList<>();
    public static long serverVersion;
    public static long localVersion;
    private static long lastRequestMs;

    // 服务端设置（随快照下发）
    public static int maxStock = 64;
    public static int slots = 12;
    public static double feePercent;

    // 大厅筛选（跨界面重建保留）
    public static String search = "";
    public static AuctionCategory category = AuctionCategory.ALL;
    /** 0 = 最新上架，1 = 价格从低到高，2 = 价格从高到低。 */
    public static int sort;
    public static int page;
    /** 摊位页当前视图：false = 我的商店，true = 商店大厅。 */
    public static boolean hallView;

    /** 从大厅点进某个摊位时要预选的商品 id（-1 = 不预选）。 */
    public static long pendingSelect = -1L;

    public static int resultSeq;
    public static boolean lastOk;
    public static String lastOp = "";

    public static void reset() {
        market = new ArrayList<>();
        serverVersion = 0L;
        localVersion = 0L;
        lastRequestMs = 0L;
        page = 0;
        pendingSelect = -1L;
    }

    public static void send(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        tag.putString("action", action);
        ServerboundShopActionPayload.send(tag);
    }

    public static void sendOnce(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        long nonce;
        do {
            nonce = ThreadLocalRandom.current().nextLong();
        } while (nonce == 0L);
        tag.putLong("nonce", nonce);
        send(action, tag);
    }

    public static void ensureMarket() {
        long now = System.currentTimeMillis();
        boolean stale = localVersion == 0L || (serverVersion != 0L && serverVersion != localVersion);
        if (stale && now - lastRequestMs > 1500L) {
            lastRequestMs = now;
            CompoundTag tag = new CompoundTag();
            tag.putLong("version", localVersion);
            send("stall_market", tag);
        }
    }

    /** 快照里带来的版本号与设置。 */
    public static void fromSnapshot(CompoundTag tag) {
        if (tag.contains("stallVersion")) {
            serverVersion = tag.getLong("stallVersion");
        }
        if (tag.contains("stallMaxStock")) {
            maxStock = Math.max(1, tag.getInt("stallMaxStock"));
            slots = Math.max(1, tag.getInt("stallSlots"));
            feePercent = tag.getDouble("stallFeePercent");
        }
    }

    public static boolean handle(String kind, CompoundTag data, HolderLookup.Provider regs) {
        switch (kind) {
            case "stall_market" -> {
                if (!data.getBoolean("unchanged")) {
                    List<Item> list = new ArrayList<>();
                    for (Tag t : data.getList("entries", Tag.TAG_COMPOUND)) {
                        CompoundTag c = (CompoundTag) t;
                        Item it = new Item();
                        it.owner = c.hasUUID("owner") ? c.getUUID("owner") : null;
                        it.ownerName = c.getString("ownerName");
                        it.id = c.getLong("id");
                        it.stack = ItemStack.parseOptional(regs, c.getCompound("item"));
                        it.price = c.getLong("price");
                        it.stock = c.getInt("stock");
                        it.sold = c.getInt("sold");
                        it.own = c.getBoolean("own");
                        list.add(it);
                    }
                    market = list;
                }
                localVersion = data.getLong("version");
                serverVersion = localVersion;
            }
            case "stall_result" -> {
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

    private static String searchText(Item it) {
        if (it.searchText == null) {
            StringBuilder sb = new StringBuilder();
            if (!it.stack.isEmpty()) {
                sb.append(it.stack.getHoverName().getString()).append(' ');
                var id = BuiltInRegistries.ITEM.getKey(it.stack.getItem());
                sb.append(id).append(' ').append(id.getPath().replace('_', ' ')).append(' ');
            }
            sb.append(it.ownerName);
            it.searchText = sb.toString().toLowerCase(Locale.ROOT);
        }
        return it.searchText;
    }

    public static AuctionCategory categoryOf(Item it) {
        if (it.category == null) {
            it.category = AuctionCategory.of(it.stack);
        }
        return it.category;
    }

    public static List<Item> filtered() {
        List<Item> out = new ArrayList<>();
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        for (Item it : market) {
            if (category != AuctionCategory.ALL && categoryOf(it) != category) {
                continue;
            }
            if (!q.isEmpty()) {
                String hay = searchText(it);
                boolean ok = true;
                for (String word : q.split("\\s+")) {
                    if (!hay.contains(word)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
            }
            out.add(it);
        }
        Comparator<Item> cmp = switch (sort) {
            case 1 -> Comparator.comparingLong((Item i) -> i.price);
            case 2 -> Comparator.comparingLong((Item i) -> i.price).reversed();
            default -> Comparator.comparingLong((Item i) -> i.id).reversed(); // id 是上架顺序号，越大越新
        };
        out.sort(cmp.thenComparingLong(i -> i.id));
        return out;
    }

    public static String sortKey(int s) {
        return "gui.module_shop.stall.sort." + (s == 1 ? "price_asc" : s == 2 ? "price_desc" : "newest");
    }
}
