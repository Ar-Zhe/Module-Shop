package cn.autoforged.server_shop_mod_1789689358.command;

import cn.autoforged.server_shop_mod_1789689358.Config;
import cn.autoforged.server_shop_mod_1789689358.shop.GrantManager;
import cn.autoforged.server_shop_mod_1789689358.shop.BillExporter;
import cn.autoforged.server_shop_mod_1789689358.shop.ShopManager;
import cn.autoforged.server_shop_mod_1789689358.shop.TradeManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ShopCommands {
    private ShopCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("shop")
                .executes(ctx -> {
                    ShopManager.sendSnapshotOpen(ctx.getSource().getPlayerOrException(), "daily");
                    return 1;
                })
                .then(Commands.literal("mailbox").executes(ctx -> {
                    ShopManager.sendSnapshotOpen(ctx.getSource().getPlayerOrException(), "mail");
                    return 1;
                }))
                .then(Commands.literal("stall").executes(ctx -> {
                    ShopManager.sendSnapshotOpen(ctx.getSource().getPlayerOrException(), "stall");
                    return 1;
                }))
                .then(Commands.literal("admin")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            ShopManager.sendSnapshotOpen(ctx.getSource().getPlayerOrException(), "admin");
                            return 1;
                        })
                        // 需求 12：指定某玩家刷新其每日商店货物与收购商品种类。
                        .then(Commands.literal("refresh")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            ShopManager.refreshDaily(target);
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "msg.server_shop_mod.refreshed", target.getGameProfile().getName())
                                                    .withStyle(ChatFormatting.GREEN), true);
                                            return 1;
                                        })))
                        // 管理员发钱：不填目标 = 所有已知玩家（在线 + 历史离线），填目标则只发这些玩家。
                        .then(Commands.literal("money")
                                // /shop admin money set <玩家> <金额>：把余额**直接设为**该值（0 = 清空）。
                                .then(Commands.literal("set")
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                                                        .executes(ctx -> setMoney(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "target"),
                                                                LongArgumentType.getLong(ctx, "amount"))))))
                                .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                        .executes(ctx -> giveMoneyAll(ctx.getSource(),
                                                LongArgumentType.getLong(ctx, "amount")))
                                        // 目标写玩家名（在线/离线都行，离线按账户表名字匹配），也可以写 all 或 @a 表示全体。
                                        .then(Commands.argument("targets", StringArgumentType.greedyString())
                                                .executes(ctx -> giveMoneyNames(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "targets"),
                                                        LongArgumentType.getLong(ctx, "amount"))))))
                        // 管理员发放物品：打开发放界面（放入物品 + 勾选玩家/全体 → 发送，物品在邮箱领取）。
                        .then(Commands.literal("send")
                                .executes(ctx -> {
                                    GrantManager.open(ctx.getSource().getPlayerOrException());
                                    return 1;
                                }))
                        // 管理员查看某玩家的交易流水：/shop admin history <玩家> [页码]
                        .then(Commands.literal("history")
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> history(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "target"), 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> history(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "target"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))))))
                        // 同 IP 多账号登录限制已按需求移除（不再有 /shop admin ip 子命令）。
                // 账单导出（管理员）：/shop bill <玩家> [起日] [止日]  或  /shop bill all
                .then(Commands.literal("bill")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("all")
                                .executes(ctx -> billAll(ctx.getSource(), null, null))
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .executes(ctx -> billAll(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "from"), null))
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .executes(ctx -> billAll(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "from"),
                                                        StringArgumentType.getString(ctx, "to"))))))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> billPlayer(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "target"), null, null))
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .executes(ctx -> billPlayer(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "target"),
                                                StringArgumentType.getString(ctx, "from"), null))
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .executes(ctx -> billPlayer(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "target"),
                                                        StringArgumentType.getString(ctx, "from"),
                                                        StringArgumentType.getString(ctx, "to")))))))
                // 需求 3：点击交易邀请提示后执行，把自己的交易界面打开。
                .then(Commands.literal("trade")
                        .then(Commands.literal("open").executes(ctx -> {
                            TradeManager.openFor(ctx.getSource().getPlayerOrException());
                            return 1;
                        })))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            ShopManager.reload(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.translatable("msg.server_shop_mod.reloaded")
                                    .withStyle(ChatFormatting.GREEN), true);
                            return 1;
                        }))
                .then(Commands.literal("wallet")
                        .then(Commands.literal("balance").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                    "msg.server_shop_mod.balance",
                                    ShopManager.balanceOf(player),
                                    Config.INSTANCE.currencyName.get()), false);
                            return 1;
                        }))
                        .then(Commands.literal("pay")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(ctx -> pay(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
                                                        IntegerArgumentType.getInteger(ctx, "amount"))))))
                        .then(Commands.literal("give")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
                                                        IntegerArgumentType.getInteger(ctx, "amount"))))))
                        // 外部账目：其它插件 / 模组扣的或加的钱也写进玩家账单（只记账，不动本模组余额）。
                        // 用法：/shop wallet log <玩家名> <金额(负=扣款)> [事由] [来源]
                        .then(Commands.literal("log")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .then(Commands.argument("amount", LongArgumentType.longArg())
                                                .executes(ctx -> logExternal(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "target"),
                                                        LongArgumentType.getLong(ctx, "amount"), "", ""))
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(ctx -> logExternal(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "target"),
                                                                LongArgumentType.getLong(ctx, "amount"),
                                                                StringArgumentType.getString(ctx, "reason"), "")))))));
    }

    /** /shop wallet log <玩家> <金额> [事由]：其它插件 / 模组把扣款或入账写进该玩家账单（只记账）。 */
    private static int logExternal(CommandSourceStack source, String target, long amount, String reason,
            String sourceName) {
        if (amount == 0L) {
            source.sendFailure(Component.translatable("msg.server_shop_mod.bill_log_zero"));
            return 0;
        }
        String plugin = sourceName == null || sourceName.isBlank() ? "外部插件" : sourceName;
        if (ShopManager.recordExternal(source.getServer(), target, amount, reason, plugin)) {
            source.sendSuccess(() -> Component.translatable("msg.server_shop_mod.bill_log_ok",
                    target, amount).withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        source.sendFailure(Component.translatable("msg.server_shop_mod.grant_unknown", target));
        return 0;
    }

    private static int pay(CommandSourceStack source, ServerPlayer target, int amount) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "do_transfer");
        tag.putString("target", target.getGameProfile().getName());
        tag.putString("amount", Integer.toString(amount));
        ShopManager.handleAction(player, tag);
        return 1;
    }

    private static int give(CommandSourceStack source, ServerPlayer target, int amount) {
        ShopManager.adminGive(target, amount);
        source.sendSuccess(() -> Component.translatable("msg.server_shop_mod.gave", amount, target.getGameProfile().getName())
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** /shop admin money <金额>：给所有已知玩家（在线 + 历史离线）各发 amount 星币。 */
    private static int giveMoneyAll(CommandSourceStack source, long amount) {
        if (rejectHugeAmount(source, amount)) {
            return 0;
        }
        GrantManager.MoneyResult result = GrantManager.giveMoney(source.getServer(),
                GrantManager.allKnownTargets(source.getServer()), amount);
        source.sendSuccess(() -> Component.translatable("msg.server_shop_mod.grant_money_all",
                result.total(), amount, result.offline()).withStyle(ChatFormatting.GREEN), true);
        if (result.clamped() > 0) {
            source.sendSuccess(() -> Component.translatable("msg.server_shop_mod.grant_clamped", result.clamped())
                    .withStyle(ChatFormatting.GOLD), false);
        }
        return result.total();
    }

    /** /shop admin money <金额> <目标...>：目标为玩家名（在线/离线均可，离线按账户表名字匹配），all / @a 表示全体。 */
    private static int giveMoneyNames(CommandSourceStack source, String text, long amount) {
        if (rejectHugeAmount(source, amount)) {
            return 0;
        }
        GrantManager.ResolveResult resolved = GrantManager.resolveTargets(source.getServer(), text);
        if (!resolved.unknown().isEmpty()) {
            source.sendFailure(Component.translatable("msg.server_shop_mod.grant_unknown",
                    String.join("、", resolved.unknown())));
        }
        if (resolved.ids().isEmpty()) {
            if (resolved.unknown().isEmpty()) {
                source.sendFailure(Component.translatable("msg.server_shop_mod.grant_no_target"));
            }
            return 0;
        }
        GrantManager.MoneyResult result = GrantManager.giveMoney(source.getServer(), resolved.ids(), amount);
        source.sendSuccess(() -> Component.translatable("msg.server_shop_mod.grant_money_targets",
                result.total(), amount).withStyle(ChatFormatting.GREEN), true);
        if (result.clamped() > 0) {
            source.sendSuccess(() -> Component.translatable("msg.server_shop_mod.grant_clamped", result.clamped())
                    .withStyle(ChatFormatting.GOLD), false);
        }
        return result.total();
    }

    /** /shop admin money set <玩家> <金额>：把余额直接设为该值（0 = 清空），在线/离线都可。 */
    private static int setMoney(CommandSourceStack source, String target, long amount) {
        String operator = source.getEntity() instanceof net.minecraft.world.entity.player.Player player
                ? player.getGameProfile().getName() : "控制台";
        if (!ShopManager.adminSetBalanceByName(source.getServer(), target, amount, operator)) {
            source.sendFailure(Component.translatable("msg.server_shop_mod.grant_unknown", target));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.server_shop_mod.admin_set_balance_ok",
                target, amount).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * 拒绝超过余额上限的发放金额。
     * 单个玩家最多只能装下 maxBalance，更大的数字没有意义；提前拒绝比“发一半”更直观
     * （入账侧也做了饱和保护，避免 before + amount 溢出把余额写成负数）。
     */
    private static boolean rejectHugeAmount(CommandSourceStack source, long amount) {
        long max = Config.maxBalanceLimit();
        if (amount > max) {
            source.sendFailure(Component.translatable("msg.server_shop_mod.grant_amount_too_large", max));
            return true;
        }
        return false;
    }

    /** /shop admin history <玩家> [页码]：查看某玩家的交易流水（控制台/RCON 也能用）。 */
    private static int history(CommandSourceStack source, String target, int page) {
        List<Component> lines = ShopManager.historyLines(source.getServer(), target, page);
        if (lines.isEmpty()) {
            source.sendFailure(Component.translatable("msg.server_shop_mod.grant_unknown", target));
            return 0;
        }
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return lines.size();
    }

    /** /shop bill <玩家> [起日] [止日]：把该玩家的流水导出成 CSV（服务端目录 config/server_shop_mod/bills）。 */
    private static int billPlayer(CommandSourceStack source, String target, String from, String to) {
        try {
            BillExporter.Result result = BillExporter.exportPlayer(source.getServer(), target,
                    BillExporter.parseDay(from), BillExporter.parseDay(to));
            if (result.path() == null) {
                source.sendFailure(Component.literal(result.message()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("账单已导出：" + result.message()
                    + " → " + result.path().toAbsolutePath()), true);
            return Math.max(1, result.rows());
        } catch (Exception ex) {
            source.sendFailure(Component.literal("导出失败：" + ex.getMessage()));
            return 0;
        }
    }

    /** /shop bill all [起日] [止日]：导出全服流水 + 玩家间对账表。 */
    private static int billAll(CommandSourceStack source, String from, String to) {
        try {
            BillExporter.Result result = BillExporter.exportAll(source.getServer(),
                    BillExporter.parseDay(from), BillExporter.parseDay(to));
            source.sendSuccess(() -> Component.literal("全服账单已导出：" + result.message()
                    + " → " + result.path().toAbsolutePath()), true);
            return Math.max(1, result.rows());
        } catch (Exception ex) {
            source.sendFailure(Component.literal("导出失败：" + ex.getMessage()));
            return 0;
        }
    }
}
