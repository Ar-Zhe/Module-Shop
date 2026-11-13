package cn.autoforged.module_shop.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 一笔拍卖挂单（进行中）或一条拍卖记录（已结束，存放在 {@code ShopSavedData.auctionHistory}）。
 *
 * <h3>资金托管（escrow）</h3>
 * 出价时星币立刻从出价者余额扣走，由这笔拍卖「托管」，{@link #escrow} 记录托管金额。
 * 不变式：进行中的拍卖 {@code escrow == (hasBid() ? currentBid : 0)}；已结束的记录 {@code escrow == 0}
 * （托管资金在结算时全部转给卖家 / 退还出价者 / 作为手续费回收）。审计见 AuctionService.audit。
 *
 * <h3>存档兼容</h3>
 * 旧字段（id / seller / sellerName / startPrice / currentBid / lastBid / topBidder / topBidderName / endTime）
 * 名字与含义都不变；新字段缺失时一律取安全默认值：状态 = 进行中，托管 = 当前价（旧版同样是出价即扣款）。
 */
public final class AuctionEntry {
    // ---- 状态 ----
    public static final String ACTIVE = "active";
    /** 到期成交。 */
    public static final String SOLD = "sold";
    /** 一口价成交。 */
    public static final String BUYOUT = "buyout";
    /** 到期无人出价，流拍。 */
    public static final String UNSOLD = "unsold";
    /** 卖家主动撤销。 */
    public static final String CANCELLED = "cancelled";
    /** 管理员强制撤销（有出价时全额退款）。 */
    public static final String ADMIN_CANCELLED = "admin_cancelled";
    /** 物品数据损坏 / 所属模组被移除，无法成交，已退款；原始数据保留在 rawItem 里供管理员恢复。 */
    public static final String BROKEN = "broken";

    /** 每场拍卖最多保留多少条出价记录（只影响展示，出价人数另有 bidders 集合统计）。 */
    public static final int MAX_BIDS = 40;

    public long id;
    public UUID seller;
    public String sellerName = "";
    public ItemStack stack = ItemStack.EMPTY;
    public long startPrice;
    public long currentBid;
    /** 需求 3：上一次出价金额，用于竞拍界面把被超过的价格划掉展示。0 表示还没有人出过价。 */
    public long lastBid;
    public UUID topBidder;
    public String topBidderName = "";
    public long endTime;

    // ---- 以下为升级新增字段（缺失时取默认值）----
    public String status = ACTIVE;
    /** 一口价（0 = 不支持一口价）。 */
    public long buyoutPrice;
    /** 本场最低加价幅度（0 = 旧数据，按全局配置）。 */
    public long minIncrement;
    /** 是否允许竞价；false = 只能一口价购买。 */
    public boolean allowBid = true;
    public long createdAt;
    public long durationMs;
    /** 防截拍已延长的次数。 */
    public int extensions;
    /** 当前托管的出价资金。 */
    public long escrow;
    /** 上架时实际收取的手续费（记录用，不退）。 */
    public long listingFee;
    /** 交易唯一标识（上架时生成），流水与审计都带它。 */
    public String txId = "";
    /** 出价记录（最新在最后，最多 {@link #MAX_BIDS} 条）。 */
    public final List<Bid> bids = new ArrayList<>();
    /** 出价总次数（不受记录条数上限影响）。 */
    public int bidCount;
    /** 参与过出价的玩家（去重），用于「我的竞拍」与竞拍人数。 */
    public final Set<UUID> bidders = new LinkedHashSet<>();
    /** 「即将结束」提醒是否已发过。 */
    public boolean endingNotified;

    // ---- 结算结果（已结束时有效）----
    public long settledAt;
    public long finalPrice;
    /** 成交手续费（从卖家所得中扣除）。 */
    public long fee;
    /** 卖家实际到账。 */
    public long net;
    public UUID buyer;
    public String buyerName = "";
    /** 管理员强制操作时记录操作者。 */
    public String operator = "";
    /** 卖家已从「我的拍卖 → 历史」里用它重新上架过（只是界面提示，不涉及资产）。 */
    public boolean relisted;

    /** 物品解析失败时保留的原始物品数据（原样写回存档，模组装回来后还能恢复）。 */
    public CompoundTag rawItem;

    public record Bid(UUID bidder, String name, long amount, long time) {
    }

    public AuctionEntry() {
    }

    public boolean hasBid() {
        return topBidder != null;
    }

    public boolean active() {
        return ACTIVE.equals(status);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = ShopNbt.saveStack(stack, registries);
        if ((stack == null || stack.isEmpty()) && rawItem != null) {
            tag.put("item", rawItem.copy());
        }
        tag.putLong("id", id);
        if (seller != null) {
            tag.putUUID("seller", seller);
        }
        tag.putString("sellerName", sellerName);
        tag.putLong("startPrice", startPrice);
        tag.putLong("currentBid", currentBid);
        tag.putLong("lastBid", lastBid);
        if (topBidder != null) {
            tag.putUUID("topBidder", topBidder);
        }
        tag.putString("topBidderName", topBidderName);
        tag.putLong("endTime", endTime);

        tag.putString("status", status == null ? ACTIVE : status);
        tag.putLong("buyoutPrice", buyoutPrice);
        tag.putLong("minIncrement", minIncrement);
        tag.putBoolean("allowBid", allowBid);
        tag.putLong("createdAt", createdAt);
        tag.putLong("durationMs", durationMs);
        tag.putInt("extensions", extensions);
        tag.putLong("escrow", escrow);
        tag.putLong("listingFee", listingFee);
        tag.putString("txId", txId == null ? "" : txId);
        ListTag bidList = new ListTag();
        for (Bid bid : bids) {
            CompoundTag b = new CompoundTag();
            if (bid.bidder() != null) {
                b.putUUID("u", bid.bidder());
            }
            b.putString("n", bid.name() == null ? "" : bid.name());
            b.putLong("a", bid.amount());
            b.putLong("t", bid.time());
            bidList.add(b);
        }
        tag.put("bids", bidList);
        tag.putInt("bidCount", bidCount);
        ListTag bidderList = new ListTag();
        for (UUID uuid : bidders) {
            bidderList.add(NbtUtils.createUUID(uuid));
        }
        tag.put("bidders", bidderList);
        tag.putBoolean("endingNotified", endingNotified);
        tag.putLong("settledAt", settledAt);
        tag.putLong("finalPrice", finalPrice);
        tag.putLong("fee", fee);
        tag.putLong("net", net);
        if (buyer != null) {
            tag.putUUID("buyer", buyer);
        }
        tag.putString("buyerName", buyerName == null ? "" : buyerName);
        tag.putString("operator", operator == null ? "" : operator);
        tag.putBoolean("relisted", relisted);
        return tag;
    }

    /**
     * @param legacyDurationMs 旧存档没有创建时间时，用「结束时间 − 当前配置时长」推算
     */
    public static AuctionEntry load(CompoundTag tag, HolderLookup.Provider registries, long legacyDurationMs) {
        AuctionEntry entry = new AuctionEntry();
        entry.stack = ShopNbt.loadStack(tag, registries);
        if (entry.stack.isEmpty() && tag.contains("item", Tag.TAG_COMPOUND)) {
            // 物品解析失败（模组被移除 / 数据损坏）：原始数据保留下来，绝不在下次保存时抹掉。
            entry.rawItem = tag.getCompound("item").copy();
        }
        entry.id = tag.getLong("id");
        if (tag.hasUUID("seller")) {
            entry.seller = tag.getUUID("seller");
        }
        entry.sellerName = tag.getString("sellerName");
        entry.startPrice = Math.max(0L, tag.getLong("startPrice"));
        entry.currentBid = Math.max(0L, tag.getLong("currentBid"));
        entry.lastBid = tag.contains("lastBid") ? Math.max(0L, tag.getLong("lastBid")) : 0L;
        if (tag.hasUUID("topBidder")) {
            entry.topBidder = tag.getUUID("topBidder");
        }
        entry.topBidderName = tag.getString("topBidderName");
        entry.endTime = tag.getLong("endTime");

        boolean legacy = !tag.contains("status", Tag.TAG_STRING);
        entry.status = legacy ? ACTIVE : tag.getString("status");
        entry.buyoutPrice = Math.max(0L, tag.getLong("buyoutPrice"));
        entry.minIncrement = Math.max(0L, tag.getLong("minIncrement"));
        entry.allowBid = !tag.contains("allowBid") || tag.getBoolean("allowBid");
        entry.createdAt = tag.getLong("createdAt");
        entry.durationMs = tag.getLong("durationMs");
        if (entry.createdAt <= 0L) {
            entry.durationMs = Math.max(0L, legacyDurationMs);
            entry.createdAt = entry.endTime - entry.durationMs;
        }
        entry.extensions = Math.max(0, tag.getInt("extensions"));
        // 旧版本出价即扣款、退款在超价时完成，所以托管额 = 当前价（有人出价时）。
        entry.escrow = tag.contains("escrow") ? Math.max(0L, tag.getLong("escrow"))
                : (entry.topBidder != null && entry.active() ? entry.currentBid : 0L);
        entry.listingFee = Math.max(0L, tag.getLong("listingFee"));
        entry.txId = tag.getString("txId");
        ListTag bidList = tag.getList("bids", Tag.TAG_COMPOUND);
        for (int i = 0; i < bidList.size(); i++) {
            CompoundTag b = bidList.getCompound(i);
            entry.bids.add(new Bid(b.hasUUID("u") ? b.getUUID("u") : null, b.getString("n"),
                    Math.max(0L, b.getLong("a")), b.getLong("t")));
        }
        entry.bidCount = Math.max(tag.getInt("bidCount"), entry.bids.size());
        ListTag bidderList = tag.getList("bidders", Tag.TAG_INT_ARRAY);
        for (Tag t : bidderList) {
            try {
                entry.bidders.add(NbtUtils.loadUUID(t));
            } catch (IllegalArgumentException ignored) {
                // 坏掉的一条跳过，不影响其余数据
            }
        }
        if (legacy && entry.topBidder != null) {
            // 旧数据只知道当前最高出价者：至少把他算进竞拍者，并补一条出价记录用于展示。
            entry.bidders.add(entry.topBidder);
            entry.bids.add(new Bid(entry.topBidder, entry.topBidderName, entry.currentBid, entry.createdAt));
            entry.bidCount = Math.max(entry.bidCount, 1);
        }
        entry.endingNotified = tag.getBoolean("endingNotified");
        entry.settledAt = tag.getLong("settledAt");
        entry.finalPrice = Math.max(0L, tag.getLong("finalPrice"));
        entry.fee = Math.max(0L, tag.getLong("fee"));
        entry.net = Math.max(0L, tag.getLong("net"));
        if (tag.hasUUID("buyer")) {
            entry.buyer = tag.getUUID("buyer");
        }
        entry.buyerName = tag.getString("buyerName");
        entry.operator = tag.getString("operator");
        entry.relisted = tag.getBoolean("relisted");
        return entry;
    }

    /** 兼容旧调用（不推算创建时间）。 */
    public static AuctionEntry load(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag, registries, 0L);
    }

    /** 本场实际生效的最低加价幅度。 */
    public long increment(long configured) {
        return Math.max(1L, Math.max(minIncrement, configured));
    }

    /** 下一次出价的最低金额（无人出价时为起拍价）。溢出时返回 Long.MAX_VALUE（谁也出不起）。 */
    public long minimumNext(long configuredIncrement) {
        if (!hasBid()) {
            return Math.max(1L, startPrice);
        }
        long inc = increment(configuredIncrement);
        return currentBid > Long.MAX_VALUE - inc ? Long.MAX_VALUE : currentBid + inc;
    }

    public void addBid(UUID bidder, String name, long amount, long time) {
        bids.add(new Bid(bidder, name, amount, time));
        while (bids.size() > MAX_BIDS) {
            bids.remove(0);
        }
        bidCount++;
        if (bidder != null) {
            bidders.add(bidder);
        }
    }
}
