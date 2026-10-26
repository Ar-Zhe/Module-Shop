package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.client.ClientShopData;
import cn.autoforged.server_shop_mod_1789689358.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 摊位列表：按序号排列所有拥有摊位的玩家（已按需求取消头像…保留头像），点击即可进入选购。
 * <p>
 * 第八轮：列表改成**整行滚动**（和收购 / 货币兑换列表同一套逻辑）——
 * 鼠标滚轮上下滑动、右侧一根滑块指示位置、底部还有内容时显示浮动小三角提示。
 */
public class AvatarScreen extends Screen {
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 210;
    private static final int ROW_H = 30;
    /** 一屏能显示几行（列表区高度固定，用整行滚动而不是分页）。 */
    private static final int VISIBLE_ROWS = 5;
    private final List<int[]> rowRects = new ArrayList<>();
    /** 列表顶行下标（滚轮 / 按钮整行滚动）。 */
    private int scrollRow;
    private int tickCounter;

    public AvatarScreen() {
        super(Component.translatable("gui.server_shop_mod.avatar.title"));
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public void tick() {
        super.tick();
        this.tickCounter++;
    }

    /** 可滚动的最大顶行下标。 */
    private int maxScroll() {
        return Math.max(0, ClientShopData.stallPlayers.size() - VISIBLE_ROWS);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.rowRects.clear();
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, this.title, x + PANEL_W / 2, y + 10, IOStyle.ORANGE);
        // 位置指示放在标题行右侧，避免和列表最后一行挤在一起。
        if (ClientShopData.stallPlayers.size() > VISIBLE_ROWS) {
            IOStyle.right(g, this.font, Component.translatable("gui.server_shop_mod.daily_pos",
                    Math.min(ClientShopData.stallPlayers.size(), this.scrollRow + VISIBLE_ROWS),
                    ClientShopData.stallPlayers.size()), x + PANEL_W - 10, y + 10, IOStyle.TEXT_2);
        }

        List<ClientShopData.StallPlayer> players = ClientShopData.stallPlayers;
        int total = players.size();
        this.scrollRow = Math.max(0, Math.min(this.scrollRow, maxScroll()));
        int listTop = y + 30;
        int listH = ROW_H * VISIBLE_ROWS;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int index = this.scrollRow + i;
            if (index >= total) {
                break;
            }
            ClientShopData.StallPlayer player = players.get(index);
            int rowY = listTop + i * ROW_H;
            g.fill(x + 10, rowY, x + PANEL_W - 16, rowY + ROW_H - 4, 0xFF232427);
            // 需求 1：摊位列表显示对应玩家头像。
            Skins.drawHead(g, player.uuid, x + 14, rowY + 4, 16);
            g.drawString(this.font, "#" + (index + 1), x + 36, rowY + 8, IOStyle.ORANGE, false);
            g.drawString(this.font, player.name, x + 58, rowY + 4, IOStyle.TEXT, false);
            g.drawString(this.font, Component.translatable("gui.server_shop_mod.avatar_items", player.count),
                    x + 58, rowY + 14, IOStyle.TEXT_2, true);
            this.rowRects.add(new int[]{x + 10, rowY, PANEL_W - 26, ROW_H - 4, index});
        }
        if (total == 0) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.server_shop_mod.avatar_empty"),
                    x + PANEL_W / 2, y + 90, IOStyle.TEXT_2);
        }
        // 右侧滑块：整行滚动时指示当前位置。
        if (total > VISIBLE_ROWS) {
            int trackX = x + PANEL_W - 14;
            g.fill(trackX, listTop, trackX + 2, listTop + listH, IOStyle.HAIR);
            int thumbH = Math.max(14, listH * VISIBLE_ROWS / Math.max(1, total));
            int thumbY = listTop + (listH - thumbH) * this.scrollRow / Math.max(1, maxScroll());
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, IOStyle.BLUE);
        }
        // 「底下还有内容」：底部浮动小三角；已下滑时顶部再补一个向上的静态小三角。
        if (this.scrollRow < maxScroll()) {
            drawScrollArrow(g, x + PANEL_W / 2 - 5, listTop + listH - 1, (int) Math.round(
                    Math.sin(this.tickCounter / 6.0D) * 2.0D), true);
        }
        if (this.scrollRow > 0) {
            drawScrollArrow(g, x + PANEL_W / 2 - 5, listTop - 8, 0, false);
        }
        drawButton(g, x + 6, y + PANEL_H - 24, 48, 16, Component.translatable("gui.server_shop_mod.back"));
        drawButton(g, x + 58, y + PANEL_H - 24, 80, 16, Component.translatable("gui.server_shop_mod.stall_manage"));
        if (total > VISIBLE_ROWS) {
            drawButton(g, x + 142, y + PANEL_H - 24, 44, 16, Component.translatable("gui.server_shop_mod.page_prev"));
            drawButton(g, x + 190, y + PANEL_H - 24, 44, 16, Component.translatable("gui.server_shop_mod.page_next"));
        }
    }

    /** 小三角箭头（down = 朝下并带浮动；否则朝上），与商店列表的滚动提示同一套画法。 */
    private void drawScrollArrow(GuiGraphics g, int ax, int ay, int offset, boolean down) {
        int top = ay + offset;
        if (down) {
            g.fill(ax, top, ax + 10, top + 1, IOStyle.BLUE);
            g.fill(ax + 2, top + 1, ax + 8, top + 2, IOStyle.BLUE);
            g.fill(ax + 4, top + 2, ax + 6, top + 3, IOStyle.BLUE);
        } else {
            g.fill(ax + 4, top, ax + 6, top + 1, IOStyle.BLUE);
            g.fill(ax + 2, top + 1, ax + 8, top + 2, IOStyle.BLUE);
            g.fill(ax, top + 2, ax + 10, top + 3, IOStyle.BLUE);
        }
    }

    /** 鼠标滚轮：整行上下滚动（与收购 / 货币兑换列表同一套逻辑）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            int next = Math.max(0, Math.min(maxScroll(), this.scrollRow + (scrollY > 0.0D ? -1 : 1)));
            if (next != this.scrollRow) {
                this.scrollRow = next;
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = left();
        int y = top();
        Bricks.click();
        if (mouseX >= x + 6 && mouseX < x + 54 && mouseY >= y + PANEL_H - 24 && mouseY < y + PANEL_H - 8) {
            // 返回 = 回到上一级界面（新开商店界面会把本界面留在返回栈里，之后按 ESC 会重新弹出来）。
            Nav.back();
            return true;
        }
        if (mouseX >= x + 58 && mouseX < x + 138 && mouseY >= y + PANEL_H - 24 && mouseY < y + PANEL_H - 8) {
            // 需求 7/8：进入自己的摊位上架管理界面 —— 回到商店的「玩家摊位」页即可
            // （不再新建一个商店界面，避免返回栈里堆出重复界面）。
            Nav.backToShop();
            return true;
        }
        int total = ClientShopData.stallPlayers.size();
        if (total > VISIBLE_ROWS && mouseX >= x + 142 && mouseX < x + 186
                && mouseY >= y + PANEL_H - 24 && mouseY < y + PANEL_H - 8) {
            this.scrollRow = Math.max(0, this.scrollRow - VISIBLE_ROWS);
            return true;
        }
        if (total > VISIBLE_ROWS && mouseX >= x + 190 && mouseX < x + 234
                && mouseY >= y + PANEL_H - 24 && mouseY < y + PANEL_H - 8) {
            this.scrollRow = Math.min(maxScroll(), this.scrollRow + VISIBLE_ROWS);
            return true;
        }
        for (int[] rect : this.rowRects) {
            if (mouseX >= rect[0] && mouseX < rect[0] + rect[2] && mouseY >= rect[1] && mouseY < rect[1] + rect[3]) {
                ClientShopData.StallPlayer player = ClientShopData.stallPlayers.get(rect[4]);
                if (player.uuid != null) {
                    // 玩家主动点摊位：允许服务端推送商品页（刚关过一次也要能再打开）。
                    Nav.rearm(StallBrowseScreen.class);
                    CompoundTag tag = new CompoundTag();
                    tag.putString("action", "open_stall");
                    tag.putString("owner", player.uuid.toString());
                    ServerboundShopActionPayload.send(tag);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label) {
        // 新 UI 皮肤：渐变底 + 内高光 + 悬停上浮 + 按下缩放 + 点击水波。
        double[] m = IOStyle.mouseGui();
        boolean hover = IOStyle.inside(x, y, w, h, m[0], m[1]);
        IOStyle.smallButton(g, x, y, w, h, label, this.font, hover, IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER,
                IOStyle.TEXT, (int) Math.round(m[0]), (int) Math.round(m[1]));
    }

    @Override
    public void onClose() {
        // ESC 返回上一级界面（Nav 返回栈），没有上一级时才真正关闭。
        Nav.back();
    }
}
