package cn.autoforged.module_shop.api;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 物品投递 API：在线进背包、背包满转邮箱；**离线直接进邮箱**；绝不静默吞掉。
 *
 * <p>线程：服务端主线程。
 */
public interface ItemDeliveryApi {
    /** 给一件物品。 */
    ApiResult<Void> give(UUID target, ItemStack stack);

    /** 给多件物品（一次发放 → 最多合并成一封邮件）。 */
    ApiResult<Void> giveAll(UUID target, List<ItemStack> stacks);
}