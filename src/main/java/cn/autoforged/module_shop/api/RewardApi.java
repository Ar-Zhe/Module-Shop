package cn.autoforged.module_shop.api;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 奖励发放 API（**幂等**）：给任务 / 成就 / 活动结算用。
 *
 * <p>同一个 {@code requestId} 对同一个玩家只会成功一次；重复调用返回
 * {@link ApiErrorCode#DUPLICATE_REQUEST}，不会重复发奖。
 *
 * <p>线程：服务端主线程。
 */
public interface RewardApi {
    /**
     * 发放一次奖励：金币（可 0）+ 物品（可空）+ 一封说明邮件。
     *
     * @param requestId 幂等键（建议“任务模组ID:任务ID:玩家UUID”或你自己的随机 UUID），不能为空
     * @param reason    写进流水与邮件正文的原因，例如「每日任务：采矿 10 个」
     */
    ApiResult<Void> grantOnce(UUID target, String requestId, List<ItemStack> items, long money, String reason);
}