package cn.autoforged.server_shop_mod_1789689358.api.view;

import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 只读：一场拍卖。
 *
 * @param auctionId 拍卖 id
 * @param seller 卖家 UUID
 * @param sellerName 卖家名
 * @param stack 拍品（展示用快照）
 * @param startPrice 起拍价
 * @param currentBid 当前价
 * @param topBidder 当前最高出价者（可能为 null）
 * @param endTime 结束时间（epoch millis）
 * @param ended 是否已到期（未结算也算）
 */
public record AuctionView(long auctionId, UUID seller, String sellerName, ItemStack stack, long startPrice,
        long currentBid, UUID topBidder, long endTime, boolean ended) {
}