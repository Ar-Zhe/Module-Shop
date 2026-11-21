package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.client.screen.GrantScreen;
import cn.autoforged.module_shop.client.screen.ShopInputScreen;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.ShopScreen;
import cn.autoforged.module_shop.client.screen.StallBrowseScreen;
import cn.autoforged.module_shop.client.screen.TradeRequestScreen;
import cn.autoforged.module_shop.client.screen.TradeScreen;
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
        // 拍卖行的独立数据包（列表 / 增量 / 详情 / 我的拍卖 / 管理配置 / 提交结果）：只更新缓存，界面每帧读取。
        if (kind.startsWith("auction_")
                && AuctionClient.handle(kind, data, player.level().registryAccess())) {
            return;
        }
        // 玩家商店大厅数据 / 上架结果（注意 stall_browse 仍走下面原有的摊位浏览分支）
        if (("stall_market".equals(kind) || "stall_result".equals(kind))
                && StallClient.handle(kind, data, player.level().registryAccess())) {
            return;
        }
        if ("redeem_list".equals(kind)) {
            cn.autoforged.module_shop.client.screen.RedeemListScreen.applyList(data);
            return;
        }
        if ("redeem_created".equals(kind)) {
            cn.autoforged.module_shop.client.screen.RedeemGeneratorScreen.applyResult(data);
            return;
        }
        if ("redeem_result".equals(kind)) {
            cn.autoforged.module_shop.client.screen.RedeemCodeScreen.applyResult(data);
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
                    instanceof cn.autoforged.module_shop.client.screen.RedeemRewardEditorScreen editor) {
                editor.onDataUpdated();
            }
        } else if ("redeem_draft".equals(kind)) {
            // 兑换码奖励草稿：缓存起来供生成器显示摘要、奖励编辑界面回填；不触碰余额等快照字段。
            ClientShopData.applyRedeemDraft(data);
        } else if ("redeem_claimed".equals(kind)) {
            // 兑换码领取名单（列表界面「查看」弹窗）。
            ClientShopData.applyRedeemClaimed(data);
        } else if ("cfg_open_ui".equals(kind)) {
            // 服务端下发的「打开配置管理界面」：走 push 版（带界面栈，关闭能回到商店）。
            cn.autoforged.module_shop.client.screen.Nav.openPushed(
                    new cn.autoforged.module_shop.client.screen.ConfigManagerScreen());
        } else if (kind.startsWith("cfg_")) {
            // 配置模板（列表 / 分片下载 / 导出 / 预览 / 诊断）统一委托给 ClientTemplateIO。
            ClientTemplateIO.handleServerPayload(kind, data);
        } else if ("media_data".equals(kind)) {
            cn.autoforged.module_shop.client.media.ClientMediaCache.handleMediaData(data);
        } else if ("media_uploaded".equals(kind)) {
            cn.autoforged.module_shop.client.media.ClientMediaCache.handleMediaUploaded(data);
        } else if ("admin_auth_result".equals(kind)) {
            cn.autoforged.module_shop.client.ClientAdminAuth.handleAuthResult(data);
        } else if ("admin_auth_prompt".equals(kind)) {
            cn.autoforged.module_shop.client.ClientAdminAuth.handleAuthPrompt(data);
        } else if ("notify".equals(kind)) {
            // 需求 10：服务端权威下发未读数量，客户端渲染红点并（可能）播放提示音。
            cn.autoforged.module_shop.client.ClientNotifications.update(data);
        } else {
            ClientShopData.update(data, player.level().registryAccess());
            boolean silent = data.getBoolean("silent");
            // 只有服务端明确要求「打开界面」（/shop 等玩家主动请求）时才弹出；
            // 其余推送（午夜刷新 / 邮件合并 / 自动签到发奖…）一律**只更新数据**，
            // 否则玩家关掉商店后会被下一次推送又弹回来，看起来像"关不掉"。
            boolean open = data.getBoolean("open");
            String defaultTab = data.getString("defaultTab");
            if (minecraft.screen instanceof ShopScreen shopScreen) {
                shopScreen.onDataUpdated();
                if (open && !defaultTab.isEmpty()) {
                    CompoundTag switchTag = new CompoundTag();
                    switchTag.putString("tab", defaultTab);
                    shopScreen.action("set_tab", switchTag);
                    if ("admin".equals(defaultTab)) {
                        CompoundTag editTypeTag = new CompoundTag();
                        editTypeTag.putString("action", "admin_set_edit_type");
                        editTypeTag.putString("type", ClientShopData.lastAdminMode == null || ClientShopData.lastAdminMode.isEmpty()
                                ? "shop" : ClientShopData.lastAdminMode);
                        cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload.send(editTypeTag);
                    }
                }
            } else if (open && canOpenOverCurrentScreen(minecraft.screen)) {
                // 当玩家无界面或停留在原版 ChatScreen / AtomChat 聊天输入框（命令回车尚未彻底卸屏）时允许拉起商店界面；
                // 仍拦住日程编辑 / 输入弹窗等业务界面以防误顶掉编辑中的表单。
                Nav.open(new ShopScreen(defaultTab));
            }
        }
    }

    private static boolean canOpenOverCurrentScreen(@javax.annotation.Nullable net.minecraft.client.gui.screens.Screen screen) {
        return screen == null || Nav.isChatScreen(screen);
    }
}
