package cn.autoforged.module_shop.api;

import cn.autoforged.module_shop.api.view.MailInfo;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 邮箱 API：发物品邮件 / 金额邮件 / 多附件邮件；**只读**查询状态。
 *
 * <p>不允许外部修改、删除玩家邮件（防吞资产）；领取仍走游戏内邮箱界面。
 *
 * <p>线程：服务端主线程。
 */
public interface MailApi {
    /** 发一封物品邮件，返回邮件 id。 */
    ApiResult<String> sendItemMail(UUID target, String title, String body, List<ItemStack> items);

    /** 发一封金额邮件（玩家在邮箱里领取后到账）。 */
    ApiResult<String> sendMoneyMail(UUID target, long amount, String title, String body);

    /** 该玩家全部邮件（只读快照，按时间倒序）。 */
    List<MailInfo> mailbox(UUID target);

    /** 未领取的邮件数量。 */
    int unclaimed(UUID target);
}