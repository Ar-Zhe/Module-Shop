package cn.autoforged.server_shop_mod_1789689358.client;

import cn.autoforged.server_shop_mod_1789689358.client.screen.GrantScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.ShopInputScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.Nav;
import cn.autoforged.server_shop_mod_1789689358.client.screen.ShopScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.StallBrowseScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.TradeRequestScreen;
import cn.autoforged.server_shop_mod_1789689358.client.screen.TradeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/** 客户端专用：接收服务端下发的快照/输入请求并打开对应界面。 */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    public static void handleSync(CompoundTag data, Player player) {
        Minecraft minecraft = Minecraft.getInstance();
        String kind = data.getString("kind");
        if ("redeem_list".equals(kind)) {
            cn.autoforged.server_shop_mod_1789689358.client.screen.RedeemListScreen.applyList(data);
            return;
        }
        if ("redeem_created".equals(kind)) {
            cn.autoforged.server_shop_mod_1789689358.client.screen.RedeemGeneratorScreen.applyResult(data);
            return;
        }
        if ("redeem_result".equals(kind)) {
            cn.autoforged.server_shop_mod_1789689358.client.screen.RedeemCodeScreen.applyResult(data);
            return;
        }
        if ("input".equals(kind)) {
            // 已经在输入界面里就不要再套一层（同一界面类型不重复入栈）。
            if (!(minecraft.screen instanceof ShopInputScreen)) {
                Nav.openPushed(new ShopInputScreen(data));
            }
        } else if ("stall_browse".equals(kind)) {
            // 已经在浏览同一摊位时就地刷新，避免重建界面导致翻页位置丢失。
            if (minecraft.screen instanceof StallBrowseScreen browse) {
                browse.updateEntries(data);
            } else {
                // 玩家刚关掉商品页的话不再弹回来（openPushed 会拦住）。
                Nav.openPushed(new StallBrowseScreen(data));
            }
        } else if ("trade".equals(kind)) {
            ClientShopData.updateTrade(data, player.level().registryAccess());
            if (minecraft.screen instanceof TradeScreen tradeScreen) {
                tradeScreen.onDataUpdated();
            } else if (data.getBoolean("forceOpen")) {
                // 需求 3：只有发起方/点击提示方才会被拉进交易界面。
                Nav.openPushed(new TradeScreen());
            }
        } else if ("trade_closed".equals(kind)) {
            ClientShopData.clearTrade();
            if (minecraft.screen instanceof TradeScreen || minecraft.screen instanceof TradeRequestScreen) {
                // 交易结束：回到上一级（交易选择 / 商店），而不是把玩家直接丢回世界。
                Nav.back();
            }
        } else if ("trade_requests".equals(kind)) {
            // 需求 1：对方发来的交易请求列表。
            ClientShopData.updateRequests(data);
            if (minecraft.screen instanceof TradeRequestScreen requestScreen) {
                requestScreen.onDataUpdated();
            } else if (data.getBoolean("forceOpen")) {
                Nav.openPushed(new TradeRequestScreen());
            }
        } else if ("admin_grant".equals(kind)) {
            // 管理员发放界面的可选玩家列表（在线 + 历史离线），界面已打开时原地刷新。
            ClientShopData.updateGrantTargets(data);
            if (minecraft.screen instanceof GrantScreen grantScreen) {
                grantScreen.onDataUpdated();
            } else if (minecraft.screen
                    instanceof cn.autoforged.server_shop_mod_1789689358.client.screen.RedeemRewardEditorScreen editor) {
                editor.onDataUpdated();
            }
        } else if ("redeem_draft".equals(kind)) {
            // 兑换码奖励草稿：缓存起来供生成器显示摘要、奖励编辑界面回填；不触碰余额等快照字段。
            ClientShopData.applyRedeemDraft(data);
        } else if ("redeem_claimed".equals(kind)) {
            // 兑换码领取名单（列表界面「查看」弹窗）。
            ClientShopData.applyRedeemClaimed(data);
        } else if ("notify".equals(kind)) {
            // 需求 10：服务端权威下发未读数量，客户端渲染红点并（可能）播放提示音。
            cn.autoforged.server_shop_mod_1789689358.client.ClientNotifications.update(data);
        } else {
            ClientShopData.update(data, player.level().registryAccess());
            boolean silent = data.getBoolean("silent");
            // 只有服务端明确要求「打开界面」（/shop 等玩家主动请求）时才弹出；
            // 其余推送（午夜刷新 / 邮件合并 / 自动签到发奖…）一律**只更新数据**，
            // 否则玩家关掉商店后会被下一次推送又弹回来，看起来像"关不掉"。
            boolean open = data.getBoolean("open");
            if (minecraft.screen instanceof ShopScreen shopScreen) {
                shopScreen.onDataUpdated();
            } else if (open && minecraft.screen == null) {
                // 只有玩家**当前没有打开任何界面**时才自动拉起商店主界面（例如 /shop）。
                // 之前这里是「只要当前不是商店界面就换成商店界面」——于是管理员在日程编辑 / 签到界面里
                // 一保存（服务端回推快照）就被直接弹出去，换成了商店界面：编辑内容看起来"没保存"、物品也没法添加。
                Nav.open(new ShopScreen(data.getString("defaultTab")));
            }
        }
    }
}
