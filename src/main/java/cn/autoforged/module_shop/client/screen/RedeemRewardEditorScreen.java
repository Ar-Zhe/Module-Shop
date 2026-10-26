package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.menu.GrantMenu;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 兑换码奖励配置（第八轮）：**完全沿用「管理员发放」的版式** ——
 * 左侧 3×9 真实物品槽（从自己背包放入，服务端权威、关闭时原样退还），
 * 右侧勾选玩家（搜索 / 翻页 / 全选 / 全不选）+ 星币金额，底部「保存 / 取消」。
 * <p>
 * **兑换范围**：不勾选任何玩家 = **全体玩家都能兑换**（默认）；勾了人就**只有被选中的玩家能兑换**。
 * <p>
 * 与发放界面的区别只有落点：点「保存」不是立刻发给玩家，而是写进**服务端兑换码草稿**，
 * 然后回到兑换码生成器；生成器上「配置奖励」按钮可以再点进来继续改（草稿会预填回槽位与勾选）。
 */
public class RedeemRewardEditorScreen extends AbstractContainerScreen<GrantMenu> {
    private static final int PANEL_W = 336;
    private static final int PANEL_H = 236;
    private static final int LIST_X = 182;
    private static final int LIST_W = 146;
    private static final int LIST_Y = 40;
    private static final int ROW_H = 20;
    private static final int PAGE_SIZE = 6;
    private static final int COLOR_TEXT = IOStyle.TEXT;
    private static final int COLOR_MUTED = IOStyle.TEXT_2;
    private static final int COLOR_GOLD = IOStyle.ORANGE;
    private static final int COLOR_ONLINE = 0x55FF55;

    /** 保存后要回到的界面（兑换码生成器）。 */
    private static Screen returnTo;

    /** 已勾选的玩家 UUID。 */
    private final Set<UUID> selected = new HashSet<>();
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<ClientShopData.GrantTarget> pageTargets = new ArrayList<>();
    private EditBox amountBox;
    private EditBox searchBox;
    private int page;

    public static void setReturnTo(Screen screen) {
        returnTo = screen;
    }

    public RedeemRewardEditorScreen(GrantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = GrantMenu.INVENTORY_Y - 12;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos;
        int y = this.topPos;
        // 草稿里的玩家 / 金额回填（服务端在打开界面前已把草稿推到客户端）。
        this.selected.clear();
        for (ClientShopData.GrantTarget target : ClientShopData.grantTargets) {
            if (target.uuid != null && ClientShopData.redeemDraftPlayers.contains(target.name)) {
                this.selected.add(target.uuid);
            }
        }

        // 金额框下移到物品槽下方（原来那两个「全体玩家 / 全不选」按钮已取消：
        // 现在**不勾选任何玩家 = 全体玩家都能兑换**，勾了人就只有那些人能兑换）。
        this.amountBox = new EditBox(this.font, x + 44, y + 96, 100, 16,
                Component.translatable("gui.module_shop.grant.amount"));
        this.amountBox.setMaxLength(12);
        this.amountBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,12}"));
        this.amountBox.setHint(Component.translatable("gui.module_shop.grant.amount_hint"));
        this.amountBox.setValue(Long.toString(Math.max(0L, ClientShopData.redeemDraftMoney)));
        this.addRenderableWidget(this.amountBox);

        this.searchBox = new EditBox(this.font, x + LIST_X, y + 18, LIST_W, 16,
                Component.translatable("gui.module_shop.grant.search_hint"));
        this.searchBox.setMaxLength(32);
        this.searchBox.setHint(Component.translatable("gui.module_shop.grant.search_hint"));
        this.searchBox.setResponder(text -> this.page = 0);
        this.addRenderableWidget(this.searchBox);

        // 全选 / 全不选挪到右侧玩家列表下面（原来挤在左边金额下方）。
        this.addRenderableWidget(new ShopButton(x + LIST_X, y + 182, 71, 16,
                Component.translatable("gui.module_shop.redeem.select_all"), b -> selectAll()));
        this.addRenderableWidget(new ShopButton(x + LIST_X + 75, y + 182, 71, 16,
                Component.translatable("gui.module_shop.redeem.select_none"), b -> this.selected.clear()));

        this.addRenderableWidget(new ShopButton(x + LIST_X, y + 162, 40, 16,
                Component.translatable("gui.module_shop.page_prev"),
                b -> this.page = Math.max(0, this.page - 1)));
        this.addRenderableWidget(new ShopButton(x + LIST_X + LIST_W - 40, y + 162, 40, 16,
                Component.translatable("gui.module_shop.page_next"), b -> this.page++));

        // 保存 / 取消再往下挪一行，给上面的全选 / 全不选让位。
        this.addRenderableWidget(new ShopButton(x + LIST_X, y + 202, 86, 20,
                Component.translatable("gui.module_shop.schedule.save"), b -> save()));
        this.addRenderableWidget(new ShopButton(x + LIST_X + 90, y + 202, 56, 20,
                Component.translatable("gui.module_shop.schedule.cancel"), b -> finish(false)));

        // 打开界面后主动请求一次目标列表，避免“先发包后开界面”的时序问题导致列表为空。
        CompoundTag request = new CompoundTag();
        request.putString("action", "admin_grant_request");
        ServerboundShopActionPayload.send(request);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 服务端下发新的目标列表后原地刷新：清掉已经不存在（改名/删号）的勾选。 */
    public void onDataUpdated() {
        Set<UUID> live = new HashSet<>();
        for (ClientShopData.GrantTarget target : ClientShopData.grantTargets) {
            if (target.uuid != null) {
                live.add(target.uuid);
            }
        }
        this.selected.retainAll(live);
    }

    // =========================================================== 数据

    private List<ClientShopData.GrantTarget> filtered() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase();
        List<ClientShopData.GrantTarget> result = new ArrayList<>();
        for (ClientShopData.GrantTarget target : ClientShopData.grantTargets) {
            if (target.uuid == null) {
                continue;
            }
            if (!query.isEmpty() && !target.name.toLowerCase().contains(query)) {
                continue;
            }
            result.add(target);
        }
        return result;
    }

    private void selectAll() {
        this.selected.clear();
        // 只选中当前搜索结果里可见的玩家（与发放界面一致，避免“搜了名字却发给全服”）。
        for (ClientShopData.GrantTarget target : filtered()) {
            if (target.uuid != null) {
                this.selected.add(target.uuid);
            }
        }
    }

    private long parseAmount() {
        String text = this.amountBox == null ? "" : this.amountBox.getValue().trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(text));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /** 保存草稿：物品以服务端容器为准，玩家 / 星币随包发送。 */
    private void save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "admin_redeem_reward_save");
        tag.putString("money", Long.toString(parseAmount()));
        ListTag targets = new ListTag();
        for (UUID id : this.selected) {
            targets.add(StringTag.valueOf(id.toString()));
        }
        tag.put("targets", targets);
        ServerboundShopActionPayload.send(tag);
        finish(true);
    }

    /** 关闭界面：先通知服务端（未保存时退还管理员加进去的物品），再回到兑换码生成器。 */
    private void finish(boolean saved) {
        if (!saved) {
            CompoundTag tag = new CompoundTag();
            tag.putString("action", "admin_redeem_reward_cancel");
            ServerboundShopActionPayload.send(tag);
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
        }
        Screen target = returnTo;
        returnTo = null;
        if (this.minecraft != null && target != null) {
            this.minecraft.setScreen(target);
        }
    }

    @Override
    public void onClose() {
        finish(false);
    }

    // =========================================================== 渲染

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                Bricks.slot(g, x + 8 + col * 18, y + 18 + row * 18);
            }
        }
        g.blit(Bricks.PLAYER_SLOTS, x + 7, y + GrantMenu.INVENTORY_Y - 1, 0, 0, 162, 76, 162, 76);
        g.fill(x + LIST_X - 3, y + 15, x + LIST_X + LIST_W + 3, y + 226, 0x30101114);
        drawLabelsAndTargets(g, x, y, mouseX, mouseY);
    }

    private void drawLabelsAndTargets(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        this.rowRects.clear();
        List<ClientShopData.GrantTarget> visibleTargets = filtered();
        // 左侧：标题 + 金额 + 已选说明（原来的「全体玩家 / 全不选」按钮已挪到右侧）。
        small(g, x + 8, y + 6, Component.translatable("gui.module_shop.redeem.reward_title"), COLOR_TEXT);
        g.drawString(this.font, Component.translatable("gui.module_shop.grant.amount_label"), x + 8, y + 100,
                COLOR_MUTED, true);
        // 已选 0 人 = 全体玩家可兑换（默认逻辑），所以这句话直接写成"全体玩家"更不容易误解。
        Component selectedText = this.selected.isEmpty()
                ? Component.translatable("gui.module_shop.redeem.all_players")
                : Component.translatable("gui.module_shop.grant.selected", this.selected.size(),
                        visibleTargets.size());
        g.drawString(this.font, IOStyle.ellipsize(this.font, selectedText, LIST_X - 16), x + 8, y + 119,
                this.selected.isEmpty() ? IOStyle.BLUE : COLOR_GOLD, true);

        List<ClientShopData.GrantTarget> targets = visibleTargets;
        int pages = Math.max(1, (targets.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.page = Mth.clamp(this.page, 0, pages - 1);
        this.pageTargets.clear();
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = this.page * PAGE_SIZE + i;
            if (index >= targets.size()) {
                break;
            }
            ClientShopData.GrantTarget target = targets.get(index);
            int rowY = y + LIST_Y + i * ROW_H;
            boolean checked = this.selected.contains(target.uuid);
            g.fill(x + LIST_X, rowY, x + LIST_X + LIST_W, rowY + ROW_H - 2, checked ? 0xFF39442F : 0xFF232427);
            if (checked) {
                g.fill(x + LIST_X, rowY, x + LIST_X + 2, rowY + ROW_H - 2, COLOR_GOLD);
            }
            drawCheckbox(g, x + LIST_X + 4, rowY + 5, checked);
            Skins.drawHead(g, target.uuid, x + LIST_X + 17, rowY + 1, 16);
            g.drawString(this.font, target.name, x + LIST_X + 37, rowY + 5, COLOR_TEXT, false);
            g.drawString(this.font,
                    Component.translatable(target.online
                            ? "gui.module_shop.grant.online"
                            : "gui.module_shop.grant.offline"),
                    x + LIST_X + LIST_W - 28, rowY + 5, target.online ? COLOR_ONLINE : COLOR_MUTED, true);
            this.rowRects.add(new int[]{x + LIST_X, rowY, LIST_W, ROW_H - 2, index});
            this.pageTargets.add(target);
        }
        if (targets.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.grant.empty"),
                    x + LIST_X + LIST_W / 2, y + 100, COLOR_MUTED);
        }
        if (pages > 1) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.page", this.page + 1, pages),
                    x + LIST_X + LIST_W / 2, y + 166, COLOR_MUTED);
        }
    }

    /** 自绘勾选框，避免依赖字体里的 ✔/☐ 字形。 */
    private static void drawCheckbox(GuiGraphics g, int x, int y, boolean checked) {
        g.fill(x, y, x + 9, y + 9, 0xFF101114);
        g.fill(x, y, x + 9, y + 1, 0xFF6A6A6A);
        g.fill(x, y + 8, x + 9, y + 9, 0xFF6A6A6A);
        g.fill(x, y, x + 1, y + 9, 0xFF6A6A6A);
        g.fill(x + 8, y, x + 9, y + 9, 0xFF6A6A6A);
        if (checked) {
            g.fill(x + 2, y + 2, x + 7, y + 7, COLOR_GOLD);
        }
    }

    private void small(GuiGraphics g, int x, int y, Component text) {
        small(g, x, y, text, COLOR_MUTED);
    }

    private void small(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    // =========================================================== 交互

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            int pages = Math.max(1, (filtered().size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int next = Mth.clamp(this.page + (scrollY > 0.0D ? -1 : 1), 0, pages - 1);
            if (next != this.page) {
                this.page = next;
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < this.rowRects.size(); i++) {
            int[] rect = this.rowRects.get(i);
            if (mouseX >= rect[0] && mouseX < rect[0] + rect[2] && mouseY >= rect[1] && mouseY < rect[1] + rect[3]) {
                ClientShopData.GrantTarget target = i < this.pageTargets.size() ? this.pageTargets.get(i) : null;
                if (target != null && target.uuid != null) {
                    Bricks.click();
                    if (!this.selected.remove(target.uuid)) {
                        this.selected.add(target.uuid);
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
