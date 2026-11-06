package cn.autoforged.module_shop.shop.auction;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.data.AuctionEntry;
import cn.autoforged.module_shop.data.NotificationType;
import cn.autoforged.module_shop.data.PlayerAccount;
import cn.autoforged.module_shop.data.ShopNbt;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.network.payload.ClientboundShopSyncPayload;
import cn.autoforged.module_shop.shop.NotificationManager;
import cn.autoforged.module_shop.shop.ShopManager;
import cn.autoforged.module_shop.shop.TransactionLog;
import cn.autoforged.module_shop.shop.mail.MailTemplates;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 拍卖行业务（服务端权威）。
 *
 * <h3>原子性与崩溃恢复</h3>
 * 所有资产变动（余额、托管、物品、邮件）都发生在服务端主线程的同一次调用里，且都存放在同一个
 * {@link ShopSavedData} 中（物品交付走邮箱，同样在这份存档里）。存档在 tick 边界整体写盘，
 * 所以磁盘上永远只会看到「操作前」或「操作后」的完整状态，不存在「扣了钱没发货」的中间态。
 * 唯一跨存档的动作是上架时从背包取物品（玩家数据单独存盘）：取物品放在所有校验之后、紧接着入库。
 *
 * <h3>结算幂等</h3>
 * 结算第一步就把拍卖从进行中列表移走，找不到即视为已结算；已结束的记录只进 {@code auctionHistory}，
 * 不会被 tick 再次处理。服务器重启后，到期未结算的拍卖在第一次 tick 时照常结算。
 *
 * <h3>资金托管</h3>
 * 出价即扣款、钱记在 {@link AuctionEntry#escrow}；被超价时同一次调用里全额退回（余额装不下的部分转金额邮件）。
 * {@link #audit} 校验「进行中：托管 = 当前价；已结束：托管 = 0」，不一致写入异常日志。
 */
public final class AuctionService {
    /** 同一玩家最近处理过的请求号（防网络重发 / 连点导致重复出价、重复上架）。 */
    private static final Map<UUID, Deque<Long>> NONCES = new HashMap<>();
    private static final int NONCE_KEEP = 64;
    /** 打开过拍卖大厅的在线玩家：拍卖变化时只给他们推增量。 */
    private static final Set<UUID> SUBSCRIBERS = new LinkedHashSet<>();
    /** 正在看某场拍卖详情的玩家 → 拍卖 id。 */
    private static final Map<UUID, Long> DETAIL_VIEWERS = new HashMap<>();
    /** 本 tick 内变化 / 移除的拍卖，tick 末尾合并推送。 */
    private static final Set<Long> DIRTY = new LinkedHashSet<>();
    private static final Set<Long> REMOVED = new LinkedHashSet<>();
    /** 卖家「有新出价」聊天提醒的节流（拍卖 id → 上次提醒时间）。 */
    private static final Map<Long, Long> SELLER_NOTIFY_AT = new HashMap<>();
    private static final long SELLER_NOTIFY_GAP_MS = 30_000L;
    /** 拍品编码缓存（拍品上架后不再变化，只编码一次）。 */
    private static final Map<Long, Tag> ITEM_CACHE = new HashMap<>();

    private static int tickCounter;
    private static boolean auditedOnStart;

    private AuctionService() {
    }

    // =================================================================== 动作分发

    /** @return true 表示这个动作归拍卖行处理（无论成功与否）。 */
    public static boolean handle(ServerPlayer player, String action, CompoundTag tag) {
        switch (action) {
            case "auction_list" -> sendList(player, tag.getLong("version"));
            case "auction_detail" -> openDetail(player, tag.getLong("id"));
            case "auction_detail_close" -> DETAIL_VIEWERS.remove(player.getUUID());
            case "auction_mine" -> sendMine(player);
            case "auction_create" -> create(player, tag);
            case "bid" -> bid(player, tag);
            case "auction_buyout" -> buyout(player, tag);
            case "auction_cancel" -> cancelBySeller(player, tag.getLong("id"));
            case "admin_auction_cancel" -> adminCancel(player, tag.getLong("id"));
            case "admin_auction_end" -> adminEnd(player, tag.getLong("id"));
            case "admin_auction_config" -> sendAdminConfig(player);
            case "admin_auction_config_set" -> saveAdminConfig(player, tag);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 服务器启动时清空静态缓存（单人游戏切换存档时，不同存档可能有相同的拍卖 id）。 */
    public static void reset() {
        NONCES.clear();
        SUBSCRIBERS.clear();
        DETAIL_VIEWERS.clear();
        DIRTY.clear();
        REMOVED.clear();
        SELLER_NOTIFY_AT.clear();
        ITEM_CACHE.clear();
        auditedOnStart = false;
        tickCounter = 0;
    }

    public static void onLogout(ServerPlayer player) {
        UUID id = player.getUUID();
        SUBSCRIBERS.remove(id);
        DETAIL_VIEWERS.remove(id);
        NONCES.remove(id);
    }

    // =================================================================== 工具

    private static ShopSavedData data(MinecraftServer server) {
        return ShopManager.data(server);
    }

    @Nullable
    public static AuctionEntry findActive(ShopSavedData data, long id) {
        for (AuctionEntry entry : data.auctions) {
            if (entry.id == id) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    private static AuctionEntry findHistory(ShopSavedData data, long id) {
        for (int i = data.auctionHistory.size() - 1; i >= 0; i--) {
            if (data.auctionHistory.get(i).id == id) {
                return data.auctionHistory.get(i);
            }
        }
        return null;
    }

    private static void msg(ServerPlayer player, String key, ChatFormatting color, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(color), false);
    }

    private static void err(ServerPlayer player, String key, Object... args) {
        msg(player, key, ChatFormatting.RED, args);
        // 让客户端界面解除「提交中」状态
        CompoundTag reply = new CompoundTag();
        reply.putString("kind", "auction_result");
        reply.putBoolean("ok", false);
        ClientboundShopSyncPayload.send(player, reply);
    }

    private static void ok(ServerPlayer player, String op) {
        CompoundTag reply = new CompoundTag();
        reply.putString("kind", "auction_result");
        reply.putBoolean("ok", true);
        reply.putString("op", op);
        ClientboundShopSyncPayload.send(player, reply);
    }

    /** 请求号去重：同一个 nonce 只处理一次。nonce=0（旧客户端 / API 调用）不参与去重。 */
    private static boolean duplicate(ServerPlayer player, CompoundTag tag) {
        long nonce = tag.getLong("nonce");
        if (nonce == 0L) {
            return false;
        }
        Deque<Long> seen = NONCES.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        if (seen.contains(nonce)) {
            return true;
        }
        seen.addLast(nonce);
        while (seen.size() > NONCE_KEEP) {
            seen.removeFirst();
        }
        return false;
    }

    /** 解析金额：非数字 / 负数 / 超过余额上限 → -1（非法）。空串 → 0。 */
    private static long parseAmount(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(s);
            if (value < 0L || value > Config.maxBalanceLimit()) {
                return -1L;
            }
            return value;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static long configIncrement() {
        return Math.max(1L, Config.INSTANCE.auctionMinIncrement.get());
    }

    private static String itemName(AuctionEntry entry) {
        return entry.stack.isEmpty() ? "?" : entry.stack.getHoverName().getString();
    }

    private static long balanceOf(ShopSavedData data, @Nullable UUID id) {
        PlayerAccount account = id == null ? null : data.peek(id);
        return account == null ? 0L : account.balance;
    }

    private static void changed(ShopSavedData data, AuctionEntry entry) {
        data.auctionVersion++;
        data.setDirty();
        DIRTY.add(entry.id);
    }

    private static void removedFromList(ShopSavedData data, long id) {
        data.auctionVersion++;
        data.setDirty();
        DIRTY.remove(id);
        REMOVED.add(id);
        ITEM_CACHE.remove(id);
        SELLER_NOTIFY_AT.remove(id);
    }

    /**
     * 退款：先进余额（饱和），装不下的部分转金额邮件，绝不销毁。流水与托管由调用方负责。
     *
     * @return 实际进入余额的金额
     */
    private static long refund(MinecraftServer server, UUID owner, long amount, AuctionEntry entry, String note) {
        if (owner == null || amount <= 0L) {
            return 0L;
        }
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(owner);
        long credited = ShopManager.credit(account, amount, Config.maxBalanceLimit());
        if (credited < amount) {
            ShopManager.addMoneyMail(server, owner, amount - credited, entry.stack.copy(),
                    ShopManager.SOURCE_AUCTION_REFUND, "拍卖会系统", null);
        }
        TransactionLog.record(server, owner, TransactionLog.AUCTION_REFUND, "拍卖#" + entry.id,
                amount, account.balance, itemName(entry) + " " + note);
        data.setDirty();
        return credited;
    }

    // =================================================================== 上架

    private static void create(ServerPlayer player, CompoundTag tag) {
        synchronized (ShopManager.LOCK) {
            if (duplicate(player, tag)) {
                return;
            }
            if (!Config.INSTANCE.auctionEnabled.get()) {
                err(player, "msg.module_shop.auction.err_disabled");
                return;
            }
            if (Config.INSTANCE.auctionBlockCreative.get() && player.isCreative()) {
                err(player, "msg.module_shop.auction.err_creative");
                return;
            }
            Inventory inventory = player.getInventory();
            int slot = tag.getInt("slot");
            boolean slotOk = (slot >= 0 && slot < 36) || slot == 40;
            ItemStack inSlot = slotOk ? inventory.getItem(slot) : ItemStack.EMPTY;
            String expectedId = tag.getString("item");
            if (inSlot.isEmpty() || !BuiltInRegistries.ITEM.getKey(inSlot.getItem()).toString().equals(expectedId)) {
                // 界面打开后背包变了（物品被移走 / 换了位置）：拒绝，让玩家重新选择。
                err(player, "msg.module_shop.auction.err_slot_changed");
                return;
            }
            int maxCount = Math.min(Config.INSTANCE.auctionMaxItemCount.get(), inSlot.getMaxStackSize());
            int count = tag.getInt("count");
            if (count <= 0 || count > inSlot.getCount() || count > maxCount) {
                err(player, "msg.module_shop.auction.err_count", Math.min(maxCount, inSlot.getCount()));
                return;
            }
            boolean allowBid = !tag.contains("allowBid") || tag.getBoolean("allowBid");
            long start = parseAmount(tag.getString("start"));
            long increment = parseAmount(tag.getString("increment"));
            long buyout = parseAmount(tag.getString("buyout"));
            if (start < 0L || increment < 0L || buyout < 0L) {
                err(player, "msg.module_shop.auction.err_amount");
                return;
            }
            if (buyout > 0L && !Config.INSTANCE.auctionAllowBuyout.get()) {
                err(player, "msg.module_shop.auction.err_buyout_disabled");
                return;
            }
            if (!allowBid) {
                // 只允许一口价：起拍价无意义，统一按一口价展示与校验。
                if (buyout <= 0L) {
                    err(player, "msg.module_shop.auction.err_need_buyout");
                    return;
                }
                start = buyout;
            }
            long minStart = Config.INSTANCE.auctionMinStartPrice.get();
            long maxStart = Config.INSTANCE.auctionMaxStartPrice.get();
            if (start < minStart || (maxStart > 0L && start > maxStart)) {
                err(player, "msg.module_shop.auction.err_start_range", minStart,
                        maxStart > 0L ? Long.toString(maxStart) : "∞");
                return;
            }
            long minInc = configIncrement();
            if (increment == 0L) {
                increment = minInc;
            }
            if (increment < minInc) {
                err(player, "msg.module_shop.auction.err_increment", minInc);
                return;
            }
            if (allowBid && buyout > 0L && buyout <= start) {
                err(player, "msg.module_shop.auction.err_buyout_low");
                return;
            }
            int minutes = tag.getInt("duration");
            if (!AuctionRules.durationOptions().contains(minutes)) {
                err(player, "msg.module_shop.auction.err_duration");
                return;
            }
            ShopSavedData data = data(player.server);
            if (data.auctionsOf(player.getUUID()).size() >= Config.INSTANCE.maxConcurrentAuctions.get()) {
                err(player, "msg.module_shop.auction_limit");
                return;
            }
            if (data.auctions.size() >= Config.INSTANCE.maxAuctionsGlobal.get()) {
                err(player, "msg.module_shop.auction.err_global_limit");
                return;
            }
            ItemStack candidate = inSlot.copyWithCount(count);
            String rule = AuctionRules.check(candidate, player.registryAccess());
            if (rule != null) {
                err(player, rule);
                return;
            }
            long fee = AuctionRules.listingFee(start);
            PlayerAccount account = ShopManager.account(player);
            if (fee > 0L && account.balance < fee) {
                err(player, "msg.module_shop.auction.err_fee", fee);
                return;
            }
            // ---- 校验全部通过，下面不再有失败分支：先收费、再取物品、立刻入库 ----
            if (fee > 0L && ShopManager.debit(account, fee, "auction_listing") < 0L) {
                err(player, "msg.module_shop.auction.err_fee", fee);
                return;
            }
            ItemStack taken = inSlot.split(count);
            inventory.setChanged();
            AuctionEntry entry = new AuctionEntry();
            entry.id = data.nextAuctionId++;
            entry.seller = player.getUUID();
            entry.sellerName = player.getGameProfile().getName();
            entry.stack = taken;
            entry.startPrice = start;
            entry.minIncrement = increment;
            entry.buyoutPrice = buyout;
            entry.allowBid = allowBid;
            entry.createdAt = System.currentTimeMillis();
            entry.durationMs = minutes * 60_000L;
            entry.endTime = entry.createdAt + entry.durationMs;
            entry.listingFee = fee;
            entry.txId = UUID.randomUUID().toString();
            data.auctions.add(entry);
            changed(data, entry);
            if (fee > 0L) {
                TransactionLog.record(player.server, player.getUUID(), TransactionLog.AUCTION_LISTING,
                        "拍卖#" + entry.id, -fee, account.balance, itemName(entry) + "×" + count);
            }
            msg(player, "msg.module_shop.auction_created", ChatFormatting.GREEN, entry.stack.getHoverName(),
                    allowBid ? start : buyout);
            ok(player, "create");
            ShopManager.sendSnapshot(player, "auction", true);
        }
    }

    /**
     * 旧流程（存放槽 → 输入起拍价）上架：物品已从背包取走。走同一套规则；不合法时原样退回。
     *
     * @return true 表示已上架；false 时物品已退回
     */
    public static boolean createLegacy(ServerPlayer player, ItemStack stack, long price) {
        synchronized (ShopManager.LOCK) {
            ShopSavedData data = data(player.server);
            String error = null;
            if (!Config.INSTANCE.auctionEnabled.get()) {
                error = "msg.module_shop.auction.err_disabled";
            } else if (Config.INSTANCE.auctionBlockCreative.get() && player.isCreative()) {
                error = "msg.module_shop.auction.err_creative";
            } else if (price < Config.INSTANCE.auctionMinStartPrice.get()
                    || (Config.INSTANCE.auctionMaxStartPrice.get() > 0L
                    && price > Config.INSTANCE.auctionMaxStartPrice.get())) {
                error = "msg.module_shop.invalid_price";
            } else if (data.auctionsOf(player.getUUID()).size() >= Config.INSTANCE.maxConcurrentAuctions.get()) {
                error = "msg.module_shop.auction_limit";
            } else if (data.auctions.size() >= Config.INSTANCE.maxAuctionsGlobal.get()) {
                error = "msg.module_shop.auction.err_global_limit";
            } else if (stack.getCount() > Config.INSTANCE.auctionMaxItemCount.get()) {
                error = "msg.module_shop.auction.err_count";
            } else {
                error = AuctionRules.check(stack, player.registryAccess());
            }
            long fee = AuctionRules.listingFee(price);
            PlayerAccount account = ShopManager.account(player);
            if (error == null && fee > 0L && ShopManager.debit(account, fee, "auction_listing") < 0L) {
                error = "msg.module_shop.auction.err_fee";
            }
            if (error != null) {
                ShopManager.addItemOrMail(player, stack, ShopManager.SOURCE_AUCTION_UNSOLD);
                Object arg = "msg.module_shop.auction.err_count".equals(error)
                        ? Config.INSTANCE.auctionMaxItemCount.get() : fee;
                msg(player, error, ChatFormatting.RED, arg);
                return false;
            }
            AuctionEntry entry = new AuctionEntry();
            entry.id = data.nextAuctionId++;
            entry.seller = player.getUUID();
            entry.sellerName = player.getGameProfile().getName();
            entry.stack = stack;
            entry.startPrice = price;
            entry.minIncrement = configIncrement();
            entry.createdAt = System.currentTimeMillis();
            entry.durationMs = Config.INSTANCE.auctionDurationSeconds.get() * 1000L;
            entry.endTime = entry.createdAt + entry.durationMs;
            entry.listingFee = fee;
            entry.txId = UUID.randomUUID().toString();
            data.auctions.add(entry);
            changed(data, entry);
            if (fee > 0L) {
                TransactionLog.record(player.server, player.getUUID(), TransactionLog.AUCTION_LISTING,
                        "拍卖#" + entry.id, -fee, account.balance, itemName(entry));
            }
            msg(player, "msg.module_shop.auction_created", ChatFormatting.GREEN, stack.getHoverName(), price);
            return true;
        }
    }

    // =================================================================== 出价

    public static void bid(ServerPlayer player, CompoundTag tag) {
        synchronized (ShopManager.LOCK) {
            if (duplicate(player, tag)) {
                return;
            }
            if (!Config.INSTANCE.auctionEnabled.get()) {
                err(player, "msg.module_shop.auction.err_disabled");
                return;
            }
            ShopSavedData data = data(player.server);
            AuctionEntry entry = findActive(data, tag.getLong("id"));
            long now = System.currentTimeMillis();
            if (entry == null || now >= entry.endTime) {
                err(player, "msg.module_shop.auction_gone_msg");
                return;
            }
            if (!entry.allowBid) {
                err(player, "msg.module_shop.auction.err_no_bidding");
                return;
            }
            if (player.getUUID().equals(entry.seller)) {
                err(player, "msg.module_shop.bid_own");
                return;
            }
            if (player.getUUID().equals(entry.topBidder)) {
                err(player, "msg.module_shop.auction.err_already_top");
                return;
            }
            // 客户端看到的当前价与服务端不一致（别人刚出过价）：拒绝，避免玩家按过时的价格误出价。
            if (tag.contains("expect") && tag.getLong("expect") != entry.currentBid) {
                err(player, "msg.module_shop.auction.err_price_changed");
                sendDetail(player, entry, null);
                return;
            }
            long amount = parseAmount(tag.getString("amount"));
            if (amount <= 0L) {
                err(player, "msg.module_shop.auction.err_amount");
                return;
            }
            long minimum = entry.minimumNext(configIncrement());
            if (amount < minimum) {
                err(player, "msg.module_shop.bid_too_low", minimum);
                return;
            }
            if (entry.buyoutPrice > 0L && amount >= entry.buyoutPrice) {
                err(player, "msg.module_shop.auction.err_bid_over_buyout", entry.buyoutPrice);
                return;
            }
            PlayerAccount bidder = ShopManager.account(player);
            if (bidder.balance < amount) {
                err(player, "msg.module_shop.no_money");
                return;
            }
            // ---- 扣款 → 退还上一位 → 更新托管，同一调用内完成 ----
            if (ShopManager.debit(bidder, amount, "auction_bid") < 0L) {
                err(player, "msg.module_shop.no_money");
                return;
            }
            UUID previous = entry.topBidder;
            long previousEscrow = entry.escrow;
            if (previous != null && previousEscrow > 0L) {
                refund(player.server, previous, previousEscrow, entry, "被超价退款");
            }
            entry.escrow = amount;
            entry.lastBid = entry.currentBid;
            entry.currentBid = amount;
            entry.topBidder = player.getUUID();
            entry.topBidderName = player.getGameProfile().getName();
            entry.addBid(player.getUUID(), entry.topBidderName, amount, now);
            boolean extended = applyAntiSnipe(entry, now);
            changed(data, entry);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.AUCTION_BID,
                    entry.sellerName == null ? "" : entry.sellerName, -amount, bidder.balance,
                    itemName(entry) + " 拍卖#" + entry.id);

            msg(player, "msg.module_shop.bid_placed", ChatFormatting.GREEN, amount, entry.stack.getHoverName());
            if (extended) {
                msg(player, "msg.module_shop.auction.extended", ChatFormatting.AQUA,
                        Config.INSTANCE.antiSnipeExtensionSeconds.get());
            }
            ok(player, "bid");
            ShopManager.sendSnapshot(player, "auction", true);
            notifyOutbid(player.server, entry, previous, previousEscrow, amount);
            notifySellerBid(player.server, entry, amount);
        }
    }

    /** 防截拍：剩余时间不足阈值时延长；受最大次数与「总时长 ≤ 原时长 2 倍」双重封顶。 */
    private static boolean applyAntiSnipe(AuctionEntry entry, long now) {
        if (!Config.INSTANCE.antiSnipe.get()) {
            return false;
        }
        long threshold = Config.INSTANCE.antiSnipeSeconds.get() * 1000L;
        long extension = Config.INSTANCE.antiSnipeExtensionSeconds.get() * 1000L;
        int maxTimes = Config.INSTANCE.antiSnipeMaxExtensions.get();
        if (extension <= 0L || entry.endTime - now >= threshold) {
            return false;
        }
        if (maxTimes > 0 && entry.extensions >= maxTimes) {
            return false;
        }
        long base = entry.durationMs > 0L ? entry.durationMs : Config.INSTANCE.auctionDurationSeconds.get() * 1000L;
        long hardEnd = entry.createdAt + base * 2L;
        long newEnd = Math.min(entry.endTime + extension, Math.max(entry.endTime, hardEnd));
        if (newEnd <= entry.endTime) {
            return false;
        }
        entry.endTime = newEnd;
        entry.extensions++;
        entry.endingNotified = false;
        return true;
    }

    private static void notifyOutbid(MinecraftServer server, AuctionEntry entry, @Nullable UUID previous,
            long previousPrice, long newPrice) {
        if (previous == null) {
            return;
        }
        NotificationManager.notify(server, previous, NotificationType.AUCTION);
        ServerPlayer online = server.getPlayerList().getPlayer(previous);
        if (online != null) {
            online.displayClientMessage(Component.translatable("msg.module_shop.outbid",
                    entry.stack.getHoverName(), previousPrice, newPrice).withStyle(ChatFormatting.YELLOW), false);
            online.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.8F, 0.8F);
            ShopManager.sendSnapshot(online, "auction", true);
        }
    }

    /** 卖家「有新出价」：红点每次都加，聊天提示同一场拍卖 30 秒内只发一条（防高频竞价刷屏）。 */
    private static void notifySellerBid(MinecraftServer server, AuctionEntry entry, long amount) {
        if (entry.seller == null) {
            return;
        }
        NotificationManager.notify(server, entry.seller, NotificationType.AUCTION);
        ServerPlayer seller = server.getPlayerList().getPlayer(entry.seller);
        long now = System.currentTimeMillis();
        Long last = SELLER_NOTIFY_AT.get(entry.id);
        if (seller != null && (last == null || now - last >= SELLER_NOTIFY_GAP_MS)) {
            SELLER_NOTIFY_AT.put(entry.id, now);
            seller.displayClientMessage(Component.translatable("msg.module_shop.auction.seller_new_bid",
                    entry.stack.getHoverName(), amount, entry.bidders.size()).withStyle(ChatFormatting.GOLD), false);
        }
    }

    // =================================================================== 一口价

    private static void buyout(ServerPlayer player, CompoundTag tag) {
        synchronized (ShopManager.LOCK) {
            if (duplicate(player, tag)) {
                return;
            }
            if (!Config.INSTANCE.auctionEnabled.get()) {
                err(player, "msg.module_shop.auction.err_disabled");
                return;
            }
            ShopSavedData data = data(player.server);
            // ① 拍卖仍然有效（两人同时一口价：先处理的那位把它移出列表，后到的这里就找不到了）
            AuctionEntry entry = findActive(data, tag.getLong("id"));
            if (entry == null || System.currentTimeMillis() >= entry.endTime) {
                err(player, "msg.module_shop.auction.err_sold");
                return;
            }
            if (entry.buyoutPrice <= 0L) {
                err(player, "msg.module_shop.auction.err_no_buyout");
                return;
            }
            if (player.getUUID().equals(entry.seller)) {
                err(player, "msg.module_shop.bid_own");
                return;
            }
            if (tag.contains("expect") && tag.getLong("expect") != entry.buyoutPrice) {
                err(player, "msg.module_shop.auction.err_price_changed");
                return;
            }
            long price = entry.buyoutPrice;
            boolean selfTop = player.getUUID().equals(entry.topBidder);
            // 自己就是领先者：已托管的出价抵扣一口价，只需补差额。
            long due = selfTop ? Math.max(0L, price - entry.escrow) : price;
            PlayerAccount buyer = ShopManager.account(player);
            // ② 余额检查
            if (buyer.balance < due) {
                err(player, "msg.module_shop.no_money");
                return;
            }
            // ③ 扣买家
            if (due > 0L && ShopManager.debit(buyer, due, "auction_buyout") < 0L) {
                err(player, "msg.module_shop.no_money");
                return;
            }
            // ④ 退还原领先者
            if (!selfTop && entry.topBidder != null && entry.escrow > 0L) {
                UUID previous = entry.topBidder;
                long previousEscrow = entry.escrow;
                refund(player.server, previous, previousEscrow, entry, "一口价成交退款");
                notifyOutbid(player.server, entry, previous, previousEscrow, price);
            } else if (selfTop && entry.escrow > price) {
                // 理论上不会出现（出价不能达到一口价），防御性地把多出的部分退回。
                refund(player.server, player.getUUID(), entry.escrow - price, entry, "一口价差额退款");
            }
            entry.escrow = price;
            entry.topBidder = player.getUUID();
            entry.topBidderName = player.getGameProfile().getName();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.AUCTION_BUYOUT,
                    entry.sellerName == null ? "" : entry.sellerName, -due, buyer.balance,
                    itemName(entry) + " 拍卖#" + entry.id);
            // ⑤⑥⑦⑧ 交付物品、结算货款、记录流水、结束拍卖
            settle(player.server, entry, AuctionEntry.BUYOUT, "");
            ok(player, "buyout");
            ShopManager.sendSnapshot(player, "auction", true);
        }
    }

    // =================================================================== 撤销 / 管理员

    private static void cancelBySeller(ServerPlayer player, long id) {
        synchronized (ShopManager.LOCK) {
            ShopSavedData data = data(player.server);
            AuctionEntry entry = findActive(data, id);
            if (entry == null) {
                err(player, "msg.module_shop.auction.err_already_ended");
                return;
            }
            if (!player.getUUID().equals(entry.seller)) {
                return;
            }
            if (entry.hasBid()) {
                err(player, "msg.module_shop.auction.err_cancel_has_bid");
                return;
            }
            settle(player.server, entry, AuctionEntry.CANCELLED, player.getGameProfile().getName());
            ok(player, "cancel");
            ShopManager.sendSnapshot(player, "auction", true);
        }
    }

    private static void adminCancel(ServerPlayer player, long id) {
        if (!player.hasPermissions(2)) {
            return;
        }
        synchronized (ShopManager.LOCK) {
            AuctionEntry entry = findActive(data(player.server), id);
            if (entry == null) {
                err(player, "msg.module_shop.auction.err_already_ended");
                return;
            }
            settle(player.server, entry, AuctionEntry.ADMIN_CANCELLED, player.getGameProfile().getName());
            msg(player, "msg.module_shop.auction.admin_cancelled", ChatFormatting.GREEN, id);
            ok(player, "admin");
        }
    }

    private static void adminEnd(ServerPlayer player, long id) {
        if (!player.hasPermissions(2)) {
            return;
        }
        synchronized (ShopManager.LOCK) {
            AuctionEntry entry = findActive(data(player.server), id);
            if (entry == null) {
                err(player, "msg.module_shop.auction.err_already_ended");
                return;
            }
            entry.operator = player.getGameProfile().getName();
            settleExpired(player.server, entry);
            msg(player, "msg.module_shop.auction.admin_ended", ChatFormatting.GREEN, id);
            ok(player, "admin");
        }
    }

    /** 命令用：按 id 强制撤销 / 结束。返回是否找到。 */
    public static boolean adminCommand(MinecraftServer server, long id, boolean cancel, String operator) {
        synchronized (ShopManager.LOCK) {
            AuctionEntry entry = findActive(data(server), id);
            if (entry == null) {
                return false;
            }
            if (cancel) {
                settle(server, entry, AuctionEntry.ADMIN_CANCELLED, operator);
            } else {
                entry.operator = operator;
                settleExpired(server, entry);
            }
            return true;
        }
    }

    // =================================================================== 结算

    /** 到期（或管理员强制结束）：有出价 → 成交；无人出价 → 流拍；物品损坏 → 退款。 */
    private static void settleExpired(MinecraftServer server, AuctionEntry entry) {
        if (entry.stack.isEmpty()) {
            settle(server, entry, AuctionEntry.BROKEN, entry.operator);
        } else if (entry.hasBid()) {
            settle(server, entry, AuctionEntry.SOLD, entry.operator);
        } else {
            settle(server, entry, AuctionEntry.UNSOLD, entry.operator);
        }
    }

    /**
     * 结束一场拍卖并完成全部资产转移。幂等：拍卖不在进行中列表里时什么都不做。
     * 出错时记录资金异常（托管额保留在记录里，审计能看到），不会把拍卖留在进行中列表被反复结算。
     */
    private static void settle(MinecraftServer server, AuctionEntry entry, String outcome, String operator) {
        ShopSavedData data = data(server);
        if (!data.auctions.remove(entry)) {
            return;
        }
        removedFromList(data, entry.id);
        entry.status = outcome;
        entry.settledAt = System.currentTimeMillis();
        if (operator != null && !operator.isEmpty()) {
            entry.operator = operator;
        }
        try {
            switch (outcome) {
                case AuctionEntry.SOLD, AuctionEntry.BUYOUT -> settleSale(server, data, entry, outcome);
                case AuctionEntry.UNSOLD -> returnItem(server, entry, MailTemplates.Template.AUCTION_UNSOLD,
                        ShopManager.SOURCE_AUCTION_UNSOLD, "msg.module_shop.auction_expired", ChatFormatting.YELLOW);
                case AuctionEntry.CANCELLED -> {
                    returnItem(server, entry, MailTemplates.Template.AUCTION_CANCELLED,
                            ShopManager.SOURCE_AUCTION_UNSOLD, "msg.module_shop.auction.cancelled",
                            ChatFormatting.YELLOW);
                    TransactionLog.record(server, entry.seller, TransactionLog.AUCTION_CANCEL, "拍卖#" + entry.id,
                            0L, balanceOf(data, entry.seller), itemName(entry));
                }
                case AuctionEntry.ADMIN_CANCELLED -> {
                    refundEscrow(server, entry, "管理员撤销退款");
                    returnItem(server, entry, MailTemplates.Template.AUCTION_ADMIN_CANCELLED,
                            ShopManager.SOURCE_AUCTION_UNSOLD, "msg.module_shop.auction.admin_cancelled_seller",
                            ChatFormatting.RED);
                    TransactionLog.record(server, entry.seller, TransactionLog.AUCTION_CANCEL,
                            "管理员 " + entry.operator, 0L, balanceOf(data, entry.seller), itemName(entry));
                }
                case AuctionEntry.BROKEN -> {
                    refundEscrow(server, entry, "物品损坏退款");
                    data.auctionAnomaly("拍卖#" + entry.id + " 物品数据无法解析（卖家 " + entry.sellerName
                            + "），已退还出价，原始物品数据保留在历史记录中");
                }
                default -> data.auctionAnomaly("拍卖#" + entry.id + " 未知结算类型 " + outcome);
            }
        } catch (RuntimeException error) {
            data.auctionAnomaly("拍卖#" + entry.id + " 结算异常（" + outcome + "，托管 " + entry.escrow + "）："
                    + error);
        }
        addHistory(data, entry);
        notifyLosers(server, entry);
        data.setDirty();
    }

    private static void refundEscrow(MinecraftServer server, AuctionEntry entry, String note) {
        if (entry.topBidder != null && entry.escrow > 0L) {
            long amount = entry.escrow;
            entry.escrow = 0L;
            refund(server, entry.topBidder, amount, entry, note);
            ServerPlayer online = server.getPlayerList().getPlayer(entry.topBidder);
            if (online != null) {
                online.displayClientMessage(Component.translatable("msg.module_shop.auction_broken_refund", amount)
                        .withStyle(ChatFormatting.GOLD), false);
            }
            NotificationManager.notify(server, entry.topBidder, NotificationType.AUCTION);
        }
    }

    private static void settleSale(MinecraftServer server, ShopSavedData data, AuctionEntry entry, String outcome) {
        if (entry.stack.isEmpty()) {
            entry.status = AuctionEntry.BROKEN;
            refundEscrow(server, entry, "物品损坏退款");
            data.auctionAnomaly("拍卖#" + entry.id + " 成交时物品数据缺失，已退款");
            return;
        }
        long price = entry.escrow;
        if (price != (AuctionEntry.BUYOUT.equals(outcome) ? entry.buyoutPrice : entry.currentBid)) {
            data.auctionAnomaly("拍卖#" + entry.id + " 托管额 " + entry.escrow + " 与成交价不一致（当前价 "
                    + entry.currentBid + "，一口价 " + entry.buyoutPrice + "），按托管额结算");
        }
        long fee = AuctionRules.saleFee(price);
        long net = price - fee;
        UUID winner = entry.topBidder;
        entry.buyer = winner;
        entry.buyerName = entry.topBidderName;
        entry.finalPrice = price;
        entry.fee = fee;
        entry.net = net;
        // 托管资金转出：net 给卖家，fee 作为系统回收。先清零托管，再发信（发信不会失败，最坏进超额邮箱）。
        entry.escrow = 0L;
        String winnerName = ShopManager.nameOf(server, winner);
        MailTemplates.Template wonTemplate = AuctionEntry.BUYOUT.equals(outcome)
                ? MailTemplates.Template.AUCTION_BUYOUT_WON : MailTemplates.Template.AUCTION_WON;
        ShopManager.addMailDraft(server, winner, MailTemplates.build(wonTemplate,
                        MailTemplates.map("player", winnerName,
                                "item", MailTemplates.itemText(entry.stack, true),
                                "price", Long.toString(price)))
                .source(ShopManager.SOURCE_AUCTION_WON)
                .addItem(entry.stack.copy()));
        if (net > 0L) {
            ShopManager.addMailDraft(server, entry.seller, MailTemplates.build(MailTemplates.Template.AUCTION_SOLD,
                            MailTemplates.map("player", ShopManager.nameOf(server, entry.seller),
                                    "item", MailTemplates.itemText(entry.stack, true),
                                    "price", Long.toString(price),
                                    "fee", Long.toString(fee),
                                    "income", Long.toString(net)))
                    .source(ShopManager.SOURCE_AUCTION_SOLD)
                    .sender(entry.buyerName)
                    .money(net)
                    .moneyDisplay(entry.stack.copy()));
        }
        TransactionLog.record(server, winner, TransactionLog.AUCTION_WIN, entry.sellerName, 0L,
                balanceOf(data, winner), itemName(entry) + "×" + entry.stack.getCount() + " 拍卖#" + entry.id
                        + "（成交价 " + price + "，物品进邮箱）");
        TransactionLog.record(server, entry.seller, TransactionLog.AUCTION_SELL, entry.buyerName, net,
                balanceOf(data, entry.seller), itemName(entry) + " 拍卖#" + entry.id + "（成交 " + price
                        + "，手续费 " + fee + "，货款存邮箱）");
        NotificationManager.notify(server, entry.seller, NotificationType.AUCTION);
        NotificationManager.notify(server, winner, NotificationType.AUCTION);
        ServerPlayer seller = server.getPlayerList().getPlayer(entry.seller);
        if (seller != null) {
            seller.displayClientMessage(Component.translatable("msg.module_shop.auction_sold_fee",
                    entry.stack.getHoverName(), price, fee, net).withStyle(ChatFormatting.GREEN), false);
            seller.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.4F);
        }
        ServerPlayer winnerPlayer = server.getPlayerList().getPlayer(winner);
        if (winnerPlayer != null) {
            winnerPlayer.displayClientMessage(Component.translatable("msg.module_shop.auction_won",
                    entry.stack.getHoverName()).withStyle(ChatFormatting.GREEN), false);
            winnerPlayer.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.6F);
        }
    }

    /** 把物品退回卖家：在线 → 背包优先、放不下进邮箱；离线 → 邮箱（邮箱满也会超额保存，不会丢）。 */
    private static void returnItem(MinecraftServer server, AuctionEntry entry, MailTemplates.Template template,
            String source, String chatKey, ChatFormatting color) {
        if (entry.stack.isEmpty() || entry.seller == null) {
            return;
        }
        ServerPlayer seller = server.getPlayerList().getPlayer(entry.seller);
        if (seller != null && AuctionEntry.CANCELLED.equals(entry.status)) {
            // 主动撤销时玩家就在线、就在界面上：直接进背包更顺手（放不下的部分由 addItemOrMail 转邮件）。
            ShopManager.addItemOrMail(seller, entry.stack.copy(), source);
        } else {
            ShopManager.addMailDraft(server, entry.seller, MailTemplates.build(template,
                            MailTemplates.map("player", ShopManager.nameOf(server, entry.seller),
                                    "item", MailTemplates.itemText(entry.stack, true),
                                    "id", Long.toString(entry.id)))
                    .source(source)
                    .addItem(entry.stack.copy()));
        }
        NotificationManager.notify(server, entry.seller, NotificationType.AUCTION);
        if (seller != null) {
            seller.displayClientMessage(Component.translatable(chatKey, entry.stack.getHoverName())
                    .withStyle(color), false);
        }
    }

    /** 拍卖结束后告诉曾经出过价但没拿到的人（他们的钱在被超价时已退回）。 */
    private static void notifyLosers(MinecraftServer server, AuctionEntry entry) {
        boolean sold = AuctionEntry.SOLD.equals(entry.status) || AuctionEntry.BUYOUT.equals(entry.status);
        for (UUID bidder : entry.bidders) {
            if (bidder.equals(entry.buyer)) {
                continue;
            }
            ServerPlayer online = server.getPlayerList().getPlayer(bidder);
            if (online != null) {
                online.displayClientMessage(Component.translatable(sold ? "msg.module_shop.auction.lost"
                                : "msg.module_shop.auction.ended_no_winner", entry.stack.getHoverName())
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
    }

    private static void addHistory(ShopSavedData data, AuctionEntry entry) {
        data.auctionHistory.add(entry);
        int limit = Config.INSTANCE.auctionHistoryLimit.get();
        Iterator<AuctionEntry> it = data.auctionHistory.iterator();
        int excess = data.auctionHistory.size() - limit;
        while (excess > 0 && it.hasNext()) {
            AuctionEntry old = it.next();
            // 损坏 / 异常的记录保留给管理员处理，不自动清掉
            if (AuctionEntry.BROKEN.equals(old.status) || old.escrow != 0L) {
                continue;
            }
            it.remove();
            excess--;
        }
    }

    // =================================================================== tick

    public static void tick(MinecraftServer server) {
        if (!auditedOnStart) {
            auditedOnStart = true;
            audit(server, null);
        }
        tickCounter++;
        if (tickCounter % 10 == 0) {
            synchronized (ShopManager.LOCK) {
                ShopSavedData data = data(server);
                long now = System.currentTimeMillis();
                long soonMs = Config.INSTANCE.auctionEndingSoonSeconds.get() * 1000L;
                for (AuctionEntry entry : new ArrayList<>(data.auctions)) {
                    if (now >= entry.endTime) {
                        settleExpired(server, entry);
                    } else if (soonMs > 0L && !entry.endingNotified && entry.endTime - now <= soonMs) {
                        entry.endingNotified = true;
                        data.setDirty();
                        notifyEndingSoon(server, entry, entry.endTime - now);
                    }
                }
            }
        }
        flushDeltas(server);
    }

    private static void notifyEndingSoon(MinecraftServer server, AuctionEntry entry, long remainMs) {
        long minutes = Math.max(1L, (remainMs + 59_999L) / 60_000L);
        for (UUID target : new UUID[]{entry.seller, entry.topBidder}) {
            ServerPlayer online = target == null ? null : server.getPlayerList().getPlayer(target);
            if (online != null) {
                online.displayClientMessage(Component.translatable(target.equals(entry.seller)
                                ? "msg.module_shop.auction.ending_seller" : "msg.module_shop.auction.ending_leader",
                        entry.stack.getHoverName(), minutes).withStyle(ChatFormatting.GOLD), false);
            }
        }
    }

    // =================================================================== 审计

    /**
     * 资金托管审计：进行中拍卖「托管 = 当前价」，已结束记录「托管 = 0」。不一致写入异常日志（不自动改账，交给管理员判断）。
     *
     * @return 发现的问题行（命令里展示）
     */
    public static List<String> audit(MinecraftServer server, @Nullable String operator) {
        List<String> problems = new ArrayList<>();
        synchronized (ShopManager.LOCK) {
            ShopSavedData data = data(server);
            long escrowTotal = 0L;
            for (AuctionEntry e : data.auctions) {
                long expected = e.hasBid() ? e.currentBid : 0L;
                if (e.escrow != expected) {
                    problems.add("拍卖#" + e.id + " 托管 " + e.escrow + " ≠ 当前价 " + expected);
                }
                if (e.stack.isEmpty()) {
                    problems.add("拍卖#" + e.id + " 物品数据无法解析（到期将退款并保留原始数据）");
                }
                escrowTotal += e.escrow;
            }
            for (AuctionEntry e : data.auctionHistory) {
                if (e.escrow != 0L) {
                    problems.add("记录#" + e.id + "（" + e.status + "）仍有托管 " + e.escrow);
                }
            }
            if (!data.auctionCorrupt.isEmpty()) {
                problems.add("有 " + data.auctionCorrupt.size() + " 条拍卖数据无法读取，已隔离保存在存档 auctionCorrupt 中");
            }
            for (String p : problems) {
                data.auctionAnomaly("[审计" + (operator == null ? "·启动" : "·" + operator) + "] " + p);
            }
            if (operator != null) {
                problems.add(0, "进行中 " + data.auctions.size() + " 场，托管总额 " + escrowTotal
                        + "，历史 " + data.auctionHistory.size() + " 条");
            }
        }
        return problems;
    }

    // =================================================================== 下发：列表 / 增量

    private static CompoundTag publicTag(AuctionEntry e, ServerPlayer viewer, HolderLookup.Provider regs, long now) {
        CompoundTag c = new CompoundTag();
        Tag item = ITEM_CACHE.get(e.id);
        if (item == null && !e.stack.isEmpty()) {
            item = e.stack.saveOptional(regs);
            if (e.active()) {
                ITEM_CACHE.put(e.id, item);
            }
        }
        if (item != null) {
            c.put("item", item);
        }
        UUID me = viewer.getUUID();
        c.putLong("id", e.id);
        c.putString("seller", e.sellerName == null ? "" : e.sellerName);
        c.putLong("startPrice", e.startPrice);
        c.putLong("currentBid", e.currentBid);
        c.putLong("lastBid", e.lastBid);
        c.putString("topBidder", e.topBidderName == null ? "" : e.topBidderName);
        c.putLong("remaining", Math.max(0L, e.endTime - now));
        c.putBoolean("own", me.equals(e.seller));
        c.putBoolean("topMe", me.equals(e.topBidder));
        c.putBoolean("iBid", e.bidders.contains(me));
        c.putLong("buyout", e.buyoutPrice);
        c.putBoolean("allowBid", e.allowBid);
        c.putLong("increment", e.increment(configIncrement()));
        c.putLong("minNext", e.minimumNext(configIncrement()));
        c.putInt("bidders", e.bidders.size());
        c.putInt("bidCount", e.bidCount);
        c.putLong("createdAgo", Math.max(0L, now - e.createdAt));
        c.putInt("extensions", e.extensions);
        return c;
    }

    private static CompoundTag settings(ServerPlayer player, ShopSavedData data) {
        CompoundTag s = new CompoundTag();
        s.putBoolean("enabled", Config.INSTANCE.auctionEnabled.get());
        ListTag durations = new ListTag();
        for (int m : AuctionRules.sortedDurations()) {
            durations.add(IntTag.valueOf(m));
        }
        s.put("durations", durations);
        s.putInt("defaultDuration", AuctionRules.durationOptions().get(0));
        s.putLong("minIncrement", configIncrement());
        s.putLong("minStart", Config.INSTANCE.auctionMinStartPrice.get());
        s.putLong("maxStart", Config.INSTANCE.auctionMaxStartPrice.get());
        s.putBoolean("allowBuyout", Config.INSTANCE.auctionAllowBuyout.get());
        s.putLong("feeFixed", Config.INSTANCE.auctionListingFeeFixed.get());
        s.putDouble("feePercent", Config.INSTANCE.auctionListingFeePercent.get());
        s.putDouble("saleFeePercent", Config.INSTANCE.auctionFeePercent.get());
        s.putInt("maxCount", Config.INSTANCE.auctionMaxItemCount.get());
        s.putInt("myActive", data.auctionsOf(player.getUUID()).size());
        s.putInt("maxPerPlayer", Config.INSTANCE.maxConcurrentAuctions.get());
        s.putBoolean("admin", player.hasPermissions(2));
        s.putBoolean("blockCreative", Config.INSTANCE.auctionBlockCreative.get());
        return s;
    }

    private static void sendList(ServerPlayer player, long clientVersion) {
        SUBSCRIBERS.add(player.getUUID());
        ShopSavedData data = data(player.server);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "auction_list");
        tag.putLong("version", data.auctionVersion);
        tag.put("settings", settings(player, data));
        if (clientVersion == data.auctionVersion) {
            tag.putBoolean("unchanged", true);
        } else {
            ListTag list = new ListTag();
            long now = System.currentTimeMillis();
            HolderLookup.Provider regs = player.registryAccess();
            for (AuctionEntry e : data.auctions) {
                list.add(publicTag(e, player, regs, now));
            }
            tag.put("entries", list);
        }
        ClientboundShopSyncPayload.send(player, tag);
    }

    /** tick 末尾：把本 tick 变化的拍卖合并成一个增量包，发给订阅者；详情页观看者另发最新详情。 */
    private static void flushDeltas(MinecraftServer server) {
        if (DIRTY.isEmpty() && REMOVED.isEmpty()) {
            return;
        }
        ShopSavedData data = data(server);
        List<AuctionEntry> changedEntries = new ArrayList<>();
        for (long id : DIRTY) {
            AuctionEntry e = findActive(data, id);
            if (e != null) {
                changedEntries.add(e);
            }
        }
        long now = System.currentTimeMillis();
        for (Iterator<UUID> it = SUBSCRIBERS.iterator(); it.hasNext(); ) {
            ServerPlayer p = server.getPlayerList().getPlayer(it.next());
            if (p == null) {
                it.remove();
                continue;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("kind", "auction_delta");
            tag.putLong("version", data.auctionVersion);
            ListTag upsert = new ListTag();
            for (AuctionEntry e : changedEntries) {
                upsert.add(publicTag(e, p, p.registryAccess(), now));
            }
            tag.put("upsert", upsert);
            ListTag removed = new ListTag();
            for (long id : REMOVED) {
                removed.add(LongTag.valueOf(id));
            }
            tag.put("remove", removed);
            tag.putInt("myActive", data.auctionsOf(p.getUUID()).size());
            ClientboundShopSyncPayload.send(p, tag);
        }
        for (Map.Entry<UUID, Long> viewer : new ArrayList<>(DETAIL_VIEWERS.entrySet())) {
            if (DIRTY.contains(viewer.getValue()) || REMOVED.contains(viewer.getValue())) {
                ServerPlayer p = server.getPlayerList().getPlayer(viewer.getKey());
                if (p == null) {
                    DETAIL_VIEWERS.remove(viewer.getKey());
                    continue;
                }
                AuctionEntry e = findActive(data, viewer.getValue());
                sendDetail(p, e != null ? e : findHistory(data, viewer.getValue()), viewer.getValue());
            }
        }
        DIRTY.clear();
        REMOVED.clear();
    }

    // =================================================================== 下发：详情

    private static void openDetail(ServerPlayer player, long id) {
        ShopSavedData data = data(player.server);
        AuctionEntry entry = findActive(data, id);
        if (entry == null) {
            entry = findHistory(data, id);
        }
        DETAIL_VIEWERS.put(player.getUUID(), id);
        sendDetail(player, entry, id);
    }

    private static void sendDetail(ServerPlayer player, @Nullable AuctionEntry entry, @Nullable Long id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "auction_detail");
        if (entry == null) {
            tag.putLong("id", id == null ? 0L : id);
            tag.putBoolean("missing", true);
            ClientboundShopSyncPayload.send(player, tag);
            return;
        }
        long now = System.currentTimeMillis();
        CompoundTag c = publicTag(entry, player, player.registryAccess(), now);
        tag.merge(c);
        tag.putString("kind", "auction_detail");
        tag.putString("status", entry.status);
        tag.putBoolean("canCancel", entry.active() && player.getUUID().equals(entry.seller) && !entry.hasBid());
        tag.putBoolean("admin", player.hasPermissions(2));
        tag.putLong("balance", ShopManager.account(player).balance);
        tag.putLong("finalPrice", entry.finalPrice);
        tag.putString("buyerName", entry.buyerName == null ? "" : entry.buyerName);
        tag.putLong("endedAgo", entry.settledAt > 0L ? Math.max(0L, now - entry.settledAt) : 0L);
        // 竞价记录：只下发名字 / 金额 / 距今多久，不下发 UUID 等其它数据。
        ListTag bids = new ListTag();
        for (int i = entry.bids.size() - 1; i >= 0; i--) {
            AuctionEntry.Bid bid = entry.bids.get(i);
            CompoundTag b = new CompoundTag();
            b.putString("name", bid.name() == null ? "" : bid.name());
            b.putLong("amount", bid.amount());
            b.putLong("ago", Math.max(0L, now - bid.time()));
            b.putBoolean("me", player.getUUID().equals(bid.bidder()));
            bids.add(b);
        }
        tag.put("bids", bids);
        ClientboundShopSyncPayload.send(player, tag);
    }

    // =================================================================== 下发：我的拍卖

    private static void sendMine(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        UUID me = player.getUUID();
        long now = System.currentTimeMillis();
        HolderLookup.Provider regs = player.registryAccess();
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "auction_mine");
        ListTag selling = new ListTag();
        ListTag bidding = new ListTag();
        for (AuctionEntry e : data.auctions) {
            if (me.equals(e.seller)) {
                selling.add(publicTag(e, player, regs, now));
            } else if (e.bidders.contains(me)) {
                bidding.add(publicTag(e, player, regs, now));
            }
        }
        tag.put("selling", selling);
        tag.put("bidding", bidding);
        ListTag history = new ListTag();
        int count = 0;
        for (int i = data.auctionHistory.size() - 1; i >= 0 && count < 150; i--) {
            AuctionEntry e = data.auctionHistory.get(i);
            boolean asSeller = me.equals(e.seller);
            boolean asBuyer = me.equals(e.buyer);
            if (!asSeller && !asBuyer) {
                continue;
            }
            CompoundTag c = ShopNbt.saveStack(e.stack, regs);
            c.putLong("id", e.id);
            c.putString("status", e.status);
            c.putString("role", asSeller ? "seller" : "buyer");
            c.putString("seller", e.sellerName == null ? "" : e.sellerName);
            c.putString("buyer", e.buyerName == null ? "" : e.buyerName);
            c.putLong("finalPrice", e.finalPrice);
            c.putLong("fee", e.fee);
            c.putLong("net", e.net);
            c.putLong("listingFee", e.listingFee);
            c.putLong("endedAgo", Math.max(0L, now - e.settledAt));
            c.putLong("startPrice", e.startPrice);
            c.putLong("increment", e.minIncrement);
            c.putLong("buyout", e.buyoutPrice);
            c.putBoolean("allowBid", e.allowBid);
            c.putInt("duration", (int) Math.max(1L, e.durationMs / 60_000L));
            c.putInt("bidCount", e.bidCount);
            c.putString("operator", e.operator == null ? "" : e.operator);
            history.add(c);
            count++;
        }
        tag.put("history", history);
        tag.put("settings", settings(player, data));
        ClientboundShopSyncPayload.send(player, tag);
    }

    // =================================================================== 管理员配置

    private static void sendAdminConfig(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            return;
        }
        Config c = Config.INSTANCE;
        ShopSavedData data = data(player.server);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "auction_admin");
        tag.putBoolean("enabled", c.auctionEnabled.get());
        tag.putInt("maxGlobal", c.maxAuctionsGlobal.get());
        tag.putInt("maxPerPlayer", c.maxConcurrentAuctions.get());
        tag.putLong("feeFixed", c.auctionListingFeeFixed.get());
        tag.putDouble("feePercent", c.auctionListingFeePercent.get());
        tag.putDouble("saleFeePercent", c.auctionFeePercent.get());
        tag.putLong("minIncrement", c.auctionMinIncrement.get());
        tag.putLong("minStart", c.auctionMinStartPrice.get());
        tag.putLong("maxStart", c.auctionMaxStartPrice.get());
        tag.putBoolean("allowBuyout", c.auctionAllowBuyout.get());
        tag.putBoolean("antiSnipe", c.antiSnipe.get());
        tag.putInt("antiSnipeSeconds", c.antiSnipeSeconds.get());
        tag.putInt("antiSnipeExtension", c.antiSnipeExtensionSeconds.get());
        tag.putInt("antiSnipeMax", c.antiSnipeMaxExtensions.get());
        tag.putString("durations", String.join(",", c.auctionDurationOptions.get()));
        tag.putString("blacklist", String.join(",", c.auctionBlacklist.get()));
        tag.putBoolean("allowBound", c.auctionAllowBound.get());
        tag.putBoolean("allowCustomData", c.auctionAllowCustomData.get());
        tag.putBoolean("blockCreative", c.auctionBlockCreative.get());
        tag.putInt("endingSoon", c.auctionEndingSoonSeconds.get());
        long escrow = 0L;
        for (AuctionEntry e : data.auctions) {
            escrow += e.escrow;
        }
        tag.putInt("statActive", data.auctions.size());
        tag.putInt("statHistory", data.auctionHistory.size());
        tag.putLong("statEscrow", escrow);
        tag.putInt("statCorrupt", data.auctionCorrupt.size());
        ListTag anomalies = new ListTag();
        for (int i = data.auctionAnomalies.size() - 1, n = 0; i >= 0 && n < 60; i--, n++) {
            anomalies.add(StringTag.valueOf(data.auctionAnomalies.get(i)));
        }
        tag.put("anomalies", anomalies);
        ClientboundShopSyncPayload.send(player, tag);
    }

    private static List<String> csv(String text, boolean numeric) {
        List<String> out = new ArrayList<>();
        for (String part : (text == null ? "" : text).split("[,，\\s]+")) {
            String s = part.trim();
            if (s.isEmpty() || out.contains(s)) {
                continue;
            }
            if (numeric) {
                try {
                    int v = Integer.parseInt(s);
                    if (v <= 0 || v > 60 * 24 * 30) {
                        continue;
                    }
                } catch (NumberFormatException ex) {
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    private static void saveAdminConfig(ServerPlayer player, CompoundTag t) {
        if (!player.hasPermissions(2)) {
            return;
        }
        Config c = Config.INSTANCE;
        List<String> durations = csv(t.getString("durations"), true);
        if (durations.isEmpty()) {
            err(player, "msg.module_shop.auction.err_duration");
            return;
        }
        c.auctionEnabled.set(t.getBoolean("enabled"));
        c.maxAuctionsGlobal.set(clampInt(t.getInt("maxGlobal"), 1, 5000));
        c.maxConcurrentAuctions.set(clampInt(t.getInt("maxPerPlayer"), 1, 100));
        c.auctionListingFeeFixed.set(clampLong(t.getLong("feeFixed"), 0L, 1_000_000_000L));
        c.auctionListingFeePercent.set(clampDouble(t.getDouble("feePercent"), 0.0D, 100.0D));
        c.auctionFeePercent.set(clampDouble(t.getDouble("saleFeePercent"), 0.0D, 100.0D));
        c.auctionMinIncrement.set(clampLong(t.getLong("minIncrement"), 1L, Long.MAX_VALUE));
        c.auctionMinStartPrice.set(clampLong(t.getLong("minStart"), 1L, Long.MAX_VALUE));
        c.auctionMaxStartPrice.set(clampLong(t.getLong("maxStart"), 0L, Long.MAX_VALUE));
        c.auctionAllowBuyout.set(t.getBoolean("allowBuyout"));
        c.antiSnipe.set(t.getBoolean("antiSnipe"));
        c.antiSnipeSeconds.set(clampInt(t.getInt("antiSnipeSeconds"), 0, 3600));
        c.antiSnipeExtensionSeconds.set(clampInt(t.getInt("antiSnipeExtension"), 0, 86400));
        c.antiSnipeMaxExtensions.set(clampInt(t.getInt("antiSnipeMax"), 0, 1000));
        c.auctionDurationOptions.set(durations);
        c.auctionBlacklist.set(csv(t.getString("blacklist"), false));
        c.auctionAllowBound.set(t.getBoolean("allowBound"));
        c.auctionAllowCustomData.set(t.getBoolean("allowCustomData"));
        c.auctionBlockCreative.set(t.getBoolean("blockCreative"));
        c.auctionEndingSoonSeconds.set(clampInt(t.getInt("endingSoon"), 0, 86400));
        Config.SPEC.save();
        data(player.server).auctionVersion++;
        msg(player, "msg.module_shop.auction.admin_saved", ChatFormatting.GREEN);
        ok(player, "admin_config");
        sendAdminConfig(player);
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampLong(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampDouble(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }
}
