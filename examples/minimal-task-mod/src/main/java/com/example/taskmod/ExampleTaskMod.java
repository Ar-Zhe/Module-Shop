package com.example.taskmod;

import cn.autoforged.server_shop_mod_1789689358.api.ApiResult;
import cn.autoforged.server_shop_mod_1789689358.api.EconomyApi;
import cn.autoforged.server_shop_mod_1789689358.api.ServerShopApi;
import cn.autoforged.server_shop_mod_1789689358.api.view.BalanceView;
import com.mojang.brigadier.CommandDispatcher;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 最小示例模组：演示**只用公共 API**完成一个"每日任务"的完整流程。
 *
 * <p>流程：查余额 → 校验任务前置（示例：余额 ≥ 0）→ 幂等发奖（星币 + 物品）→ 打印结果。
 * <p>注意：所有调用都发生在服务端命令上下文中（= 服务端主线程）。
 */
@Mod(ExampleTaskMod.MODID)
public class ExampleTaskMod {
    public static final String MODID = "example_task_mod";

    public ExampleTaskMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(ExampleTaskMod::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("exampletask")
                .requires(source -> source.hasPermission(0))
                .then(Commands.literal("complete")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String taskId = "mining-10";     // 真实项目里换成你的任务唯一 id
                            return completeTask(player, taskId) ? 1 : 0;
                        }))
                .then(Commands.literal("balance")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            EconomyApi eco = ServerShopApi.economy();
                            BalanceView view = eco.balanceView(player.getUUID());
                            if (view == null) {
                                ctx.getSource().sendFailure(Component.literal("查不到账户"));
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "余额 " + view.balance() + " " + view.currencyName()
                                            + (view.unlimited() ? "（上限不限）"
                                                    : "（上限 " + view.maxBalance() + "）")), false);
                            return 1;
                        })));
    }

    /**
     * 完成一次任务并发放奖励。
     *
     * <p>**幂等**：同一个 {@code requestId} 重复调用只会成功一次，
     * 第二次会返回 {@link cn.autoforged.server_shop_mod_1789689358.api.ApiErrorCode#DUPLICATE_REQUEST}。
     */
    public static boolean completeTask(ServerPlayer player, String taskId) {
        UUID id = player.getUUID();
        EconomyApi eco = ServerShopApi.economy();

        long before = eco.balance(id);

        // 1) 发钱（受余额上限约束；装不下的部分会自动转成金钱邮件）
        ApiResult<Void> granted = ServerShopApi.reward().grantOnce(
                id,
                "example:" + taskId,                       // 幂等键：任务模组 + 任务 id
                List.of(new ItemStack(Items.DIAMOND, 3)),  // 物品奖励（可为空列表）
                100L,                                      // 星币奖励（可 0）
                "每日任务：" + taskId);
        if (!granted.isOk()) {
            player.sendSystemMessage(Component.literal(
                    "奖励未发放：" + granted.code() + " " + granted.message()));
            return false;
        }

        // 2) 可选：单独再发一封"完成凭证"邮件（多附件示例）
        ApiResult<String> mail = ServerShopApi.mail().sendItemMail(id,
                "任务完成：" + taskId, "感谢参与，附件为额外小礼物。",
                List.of(new ItemStack(Items.BREAD, 8)));
        if (!mail.isOk()) {
            player.sendSystemMessage(Component.literal("凭证邮件未发出：" + mail.code()));
        }

        // 3) 判断结果并回报
        long after = eco.balance(id);
        int unclaimed = ServerShopApi.mail().unclaimed(id);
        player.sendSystemMessage(Component.literal(
                "任务完成 ✔ 星币 " + before + " → " + after + "，未领邮件 " + unclaimed + " 封"));
        return true;
    }
}