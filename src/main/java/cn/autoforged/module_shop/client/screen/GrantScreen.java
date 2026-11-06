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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 管理员发放界面：左侧 27 个物品槽（真实槽位，可放入要发放的物品），
 * 右侧勾选玩家（支持搜索 / 翻页 / 一键全体），底部填写金额，点“发送”后：
 * 物品以邮件形式进入玩家邮箱（玩家在邮箱界面领取），金额按配置直接到账。
 * <p>
 * 界面只提交“发给谁 / 发多少钱”，物品以服务端容器内容为准，客户端无法伪造。
 */
public class GrantScreen extends AbstractContainerScreen<GrantMenu> implements SkiaContainerBackground.Painter {

    @Override
    public boolean usesSkiaBackground() {
        return this.skia;
    }

    @Override
    public void paintSkiaBackground(GuiGraphics g, int mouseX, int mouseY) {
        skBg(g, this.leftPos, this.topPos, mouseX, mouseY);
    }

    private static final int PANEL_W = 336;
    private static final int PANEL_H = 236;
    private static final int LIST_X = 182;
    private static final int LIST_W = 146;
    private static final int LIST_Y = 40;
    private static final int ROW_H = 20;
    private static final int PAGE_SIZE = 6;
    private static final int COLOR_ONLINE = 0x55FF55;

    /** 已勾选的玩家 UUID；发送时若等于全部目标则改发 all 标记，节省包体。 */
    private final Set<UUID> selected = new HashSet<>();
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<ClientShopData.GrantTarget> pageTargets = new ArrayList<>();
    private EditBox amountBox;
    private EditBox searchBox;
    private int page;

    public GrantScreen(GrantMenu menu, Inventory inventory, Component title) {
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
        this.skia = cn.autoforged.module_shop.client.ui.AtomUi.available();
        if (this.skia) {
            skInit(x, y);
            return;
        }

        this.amountBox = new EditBox(this.font, x + 44, y + 74, 100, 16,
                Component.translatable("gui.module_shop.grant.amount"));
        this.amountBox.setMaxLength(12);
        // 只允许数字，避免管理员输入非法金额。
        this.amountBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,12}"));
        this.amountBox.setHint(Component.translatable("gui.module_shop.grant.amount_hint"));
        this.addRenderableWidget(this.amountBox);

        this.searchBox = new EditBox(this.font, x + LIST_X, y + 18, LIST_W, 16,
                Component.translatable("gui.module_shop.grant.search_hint"));
        this.searchBox.setMaxLength(32);
        this.searchBox.setHint(Component.translatable("gui.module_shop.grant.search_hint"));
        this.searchBox.setResponder(text -> this.page = 0);
        this.addRenderableWidget(this.searchBox);

        this.addRenderableWidget(new ShopButton(x + 8, y + 96, 78, 16,
                Component.translatable("gui.module_shop.grant.all"), b -> selectAll()));
        this.addRenderableWidget(new ShopButton(x + 90, y + 96, 78, 16,
                Component.translatable("gui.module_shop.grant.none"), b -> this.selected.clear()));

        this.addRenderableWidget(new ShopButton(x + LIST_X, y + 162, 40, 16,
                Component.translatable("gui.module_shop.page_prev"),
                b -> this.page = Math.max(0, this.page - 1)));
        this.addRenderableWidget(new ShopButton(x + LIST_X + LIST_W - 40, y + 162, 40, 16,
                Component.translatable("gui.module_shop.page_next"), b -> this.page++));

        this.addRenderableWidget(new ShopButton(x + LIST_X, y + 184, LIST_W, 20,
                Component.translatable("gui.module_shop.grant.send"), b -> sendGrant()));

        // 界面打开后主动请求一次目标列表，避免“先发包后开界面”的时序问题导致列表为空。
        CompoundTag request = new CompoundTag();
        request.putString("action", "admin_grant_request");
        ServerboundShopActionPayload.send(request);
    }

    /** 服务端下发新的目标列表后由 ClientPayloadHandler 调用（界面已打开时原地刷新）。 */
    public void onDataUpdated() {
        // 名单刷新后清掉已经不存在的选择，避免把无效 UUID 发给服务端。
        Set<UUID> live = new HashSet<>();
        for (ClientShopData.GrantTarget target : ClientShopData.grantTargets) {
            if (target.uuid != null) {
                live.add(target.uuid);
            }
        }
        this.selected.retainAll(live);
        this.page = Math.max(0, this.page);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
        // 只选中当前搜索结果里可见的玩家：管理员搜了某个名字再点“全体玩家”却发给全服，是很容易踩的坑。
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

    private void sendGrant() {
        // 始终发送显式 UUID 列表：客户端持有的名单只是“打开界面那一刻”的快照。
        // 若改发 all 标记，服务端会按当前全表展开，把管理员从未在界面上看到的玩家（例如刚登录的）也一起发放。
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "admin_grant_send");
        tag.putLong("amount", parseAmount());
        tag.putBoolean("all", false);
        ListTag targets = new ListTag();
        for (UUID id : this.selected) {
            targets.add(StringTag.valueOf(id.toString()));
        }
        tag.put("targets", targets);
        ServerboundShopActionPayload.send(tag);
    }

    // =========================================================== 渲染

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        if (this.skia) {
            return;   // Skija 底板在 paintSkiaBackground 里画（见 SkiaContainerBackground）
        }
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                Bricks.slot(g, x + 8 + col * 18, y + 18 + row * 18);
            }
        }
        // 玩家背包区域沿用统一的积木底图（3 行 + 快捷栏）。
        g.blit(Bricks.PLAYER_SLOTS, x + 7, y + GrantMenu.INVENTORY_Y - 1, 0, 0, 162, 76, 162, 76);
        // 右侧目标列表的浅色底，便于和面板区分。
        g.fill(x + LIST_X - 3, y + 15, x + LIST_X + LIST_W + 3, y + 205, 0x30101114);
        // 列表与文字都画在 renderBg 里：这样物品悬浮提示（super.render 之后绘制）永远在最上层。
        drawLabelsAndTargets(g, x, y, mouseX, mouseY);
    }

    private void drawLabelsAndTargets(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        this.rowRects.clear();
        // 计数用“当前搜索结果”的数量：用全表长度会出现“已选 5 / 200”却只看到 2 行的怪现象。
        List<ClientShopData.GrantTarget> visibleTargets = filtered();
        g.drawString(this.font, Component.translatable("gui.module_shop.grant.amount_label"), x + 8, y + 78,
                IOStyle.TEXT_2, true);
        g.drawString(this.font,
                Component.translatable("gui.module_shop.grant.selected", this.selected.size(),
                        visibleTargets.size()),
                x + 8, y + 119, IOStyle.ORANGE, true);
        g.drawString(this.font, Component.translatable("gui.module_shop.grant.hint"), x + 8, y + 131,
                IOStyle.TEXT_2, true);

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
                g.fill(x + LIST_X, rowY, x + LIST_X + 2, rowY + ROW_H - 2, IOStyle.ORANGE);
            }
            drawCheckbox(g, x + LIST_X + 4, rowY + 5, checked);
            Skins.drawHead(g, target.uuid, x + LIST_X + 17, rowY + 1, 16);
            g.drawString(this.font, target.name, x + LIST_X + 37, rowY + 5, IOStyle.TEXT, false);
            g.drawString(this.font,
                    Component.translatable(target.online
                            ? "gui.module_shop.grant.online"
                            : "gui.module_shop.grant.offline"),
                    x + LIST_X + LIST_W - 28, rowY + 5, target.online ? COLOR_ONLINE : IOStyle.TEXT_2, true);
            this.rowRects.add(new int[]{x + LIST_X, rowY, LIST_W, ROW_H - 2, index});
            this.pageTargets.add(target);
        }
        if (targets.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.grant.empty"),
                    x + LIST_X + LIST_W / 2, y + 100, IOStyle.TEXT_2);
        }
        if (pages > 1) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.page", this.page + 1, pages),
                    x + LIST_X + LIST_W / 2, y + 166, IOStyle.TEXT_2);
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
            g.fill(x + 2, y + 2, x + 7, y + 7, IOStyle.ORANGE);
        }
    }

    // =========================================================== 交互

    /** 鼠标滚轮翻页（右侧玩家列表）。 */
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
        if (this.skia && button == 0 && skClick(mouseX, mouseY)) {
            return true;
        }
        // rowRects 与 pageTargets 在同一轮渲染里按相同顺序填充，用下标直接对应，避免翻页瞬间点错人。
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

    // =========================================================== Skija（只换绘制，点击沿用旧矩形）

    private boolean skia;

    private void skInit(int x, int y) {
        String keepAmount = this.amountBox == null ? "" : this.amountBox.getValue();
        String keepSearch = this.searchBox == null ? "" : this.searchBox.getValue();
        this.amountBox = new EditBox(this.font, x + 48, y + 78, 92, 10,
                Component.translatable("gui.module_shop.grant.amount"));
        this.amountBox.setBordered(false);
        this.amountBox.setMaxLength(12);
        this.amountBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,12}"));
        this.amountBox.setValue(keepAmount);
        this.addRenderableWidget(this.amountBox);
        this.searchBox = new EditBox(this.font, x + LIST_X + 4, y + 22, LIST_W - 8, 10,
                Component.translatable("gui.module_shop.grant.search_hint"));
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(32);
        this.searchBox.setValue(keepSearch);
        this.searchBox.setResponder(text -> this.page = 0);
        this.addRenderableWidget(this.searchBox);
        CompoundTag request = new CompoundTag();
        request.putString("action", "admin_grant_request");
        ServerboundShopActionPayload.send(request);
    }

    private boolean skClick(double mx, double my) {
        int x = this.leftPos;
        int y = this.topPos;
        if (IOStyle.inside(x + 8, y + 96, 78, 16, mx, my)) {
            Bricks.click();
            selectAll();
            return true;
        }
        if (IOStyle.inside(x + 90, y + 96, 78, 16, mx, my)) {
            Bricks.click();
            this.selected.clear();
            return true;
        }
        int pages = Math.max(1, (filtered().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (pages > 1 && IOStyle.inside(x + LIST_X, y + 162, 40, 16, mx, my)) {
            Bricks.click();
            this.page = Math.max(0, this.page - 1);
            return true;
        }
        if (pages > 1 && IOStyle.inside(x + LIST_X + LIST_W - 40, y + 162, 40, 16, mx, my)) {
            Bricks.click();
            this.page = Math.min(pages - 1, this.page + 1);
            return true;
        }
        if (IOStyle.inside(x + LIST_X, y + 184, LIST_W, 20, mx, my)) {
            Bricks.click();
            sendGrant();
            return true;
        }
        return false;
    }

    private void skBg(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        this.rowRects.clear();
        this.pageTargets.clear();
        List<ClientShopData.GrantTarget> targets = filtered();
        int pages = Math.max(1, (targets.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.page = Mth.clamp(this.page, 0, pages - 1);
        cn.autoforged.module_shop.client.ui.render.SkiaFrame.draw(g, c -> {
            SkiaDialog.bare(c, x, y, PANEL_W, PANEL_H);
            cn.autoforged.module_shop.client.ui.render.Text.drawLine(c, this.title.getString(),
                    cn.autoforged.module_shop.client.ui.AtomUi.BOLD, cn.autoforged.module_shop.client.ui.render.Text.BODY,
                    IOStyle.TEXT, x + 8, y + 9, 160, cn.autoforged.module_shop.client.ui.render.Text.Align.LEFT);
            for (int i = 0; i < 27; i++) {
                SkiaDialog.slotFrame(c, x + 8 + (i % 9) * 18, y + 18 + (i / 9) * 18);
            }
            SkiaDialog.small(c, Component.translatable("gui.module_shop.grant.amount_label").getString(), x + 8,
                    y + 82, 34, IOStyle.TEXT_2);
            SkiaDialog.fieldFrame(c, x + 44, y + 74, 100, 16, this.amountBox.isFocused()); SkiaDialog.themeBox(this.amountBox);
            if (this.amountBox.getValue().isEmpty() && !this.amountBox.isFocused()) {
                SkiaDialog.small(c, Component.translatable("gui.module_shop.grant.amount_hint").getString(), x + 48,
                        y + 82, 92, IOStyle.TEXT_3);
            }
            cn.autoforged.module_shop.client.ui.widget.Button.Kind n =
                    cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL;
            SkiaDialog.flatButton(c, x + 8, y + 96, 78, 16,
                    Component.translatable("gui.module_shop.grant.all").getString(), n, true, mouseX, mouseY);
            SkiaDialog.flatButton(c, x + 90, y + 96, 78, 16,
                    Component.translatable("gui.module_shop.grant.none").getString(), n, true, mouseX, mouseY);
            SkiaDialog.text(c, Component.translatable("gui.module_shop.grant.selected", this.selected.size(),
                    targets.size()).getString(), x + 8, y + 123, 166, IOStyle.ORANGE);
            SkiaDialog.paragraph(c, Component.translatable("gui.module_shop.grant.hint").getString(), x + 8, y + 131,
                    166, cn.autoforged.module_shop.client.ui.render.Text.SMALL, IOStyle.TEXT_3, 2, false);
            SkiaDialog.small(c, this.playerInventoryTitle.getString(), x + 8, y + GrantMenu.INVENTORY_Y - 6, 160,
                    IOStyle.TEXT_3);
            SkiaDialog.playerSlots(c, x + 8, y + GrantMenu.INVENTORY_Y);
            // 右侧玩家列表
            SkiaDialog.card(c, x + LIST_X - 4, y + 14, LIST_W + 8, 192);
            SkiaDialog.fieldFrame(c, x + LIST_X, y + 18, LIST_W, 16, this.searchBox.isFocused()); SkiaDialog.themeBox(this.searchBox);
            if (this.searchBox.getValue().isEmpty() && !this.searchBox.isFocused()) {
                SkiaDialog.small(c, Component.translatable("gui.module_shop.grant.search_hint").getString(),
                        x + LIST_X + 4, y + 26, LIST_W - 8, IOStyle.TEXT_3);
            }
            for (int i = 0; i < PAGE_SIZE; i++) {
                int index = this.page * PAGE_SIZE + i;
                if (index >= targets.size()) {
                    break;
                }
                ClientShopData.GrantTarget target = targets.get(index);
                int rowY = y + LIST_Y + i * ROW_H;
                boolean checked = this.selected.contains(target.uuid);
                boolean hover = IOStyle.inside(x + LIST_X, rowY, LIST_W, ROW_H - 2, mouseX, mouseY);
                cn.autoforged.module_shop.client.ui.render.Draw.fill(c, x + LIST_X, rowY, LIST_W, ROW_H - 2, 3,
                        checked ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                cn.autoforged.module_shop.client.ui.render.Draw.stroke(c, x + LIST_X + 4, rowY + 4.5F, 9, 9, 2, 1,
                        checked ? IOStyle.BLUE : IOStyle.BORDER);
                if (checked) {
                    cn.autoforged.module_shop.client.ui.render.Draw.fill(c, x + LIST_X + 4, rowY + 4.5F, 9, 9, 2,
                            IOStyle.BLUE);
                    cn.autoforged.module_shop.client.ui.render.Draw.line(c, x + LIST_X + 6, rowY + 9, x + LIST_X + 8,
                            rowY + 11, 1.3F, 0xFFFFFFFF);
                    cn.autoforged.module_shop.client.ui.render.Draw.line(c, x + LIST_X + 8, rowY + 11, x + LIST_X + 11.5F,
                            rowY + 7, 1.3F, 0xFFFFFFFF);
                }
                String status = Component.translatable(target.online ? "gui.module_shop.grant.online"
                        : "gui.module_shop.grant.offline").getString();
                float sw = cn.autoforged.module_shop.client.ui.render.Text.drawLine(c, status,
                        cn.autoforged.module_shop.client.ui.AtomUi.REGULAR,
                        cn.autoforged.module_shop.client.ui.render.Text.SMALL,
                        target.online ? IOStyle.GREEN : IOStyle.TEXT_3, x + LIST_X, rowY + 9, LIST_W - 4,
                        cn.autoforged.module_shop.client.ui.render.Text.Align.RIGHT);
                SkiaDialog.text(c, target.name, x + LIST_X + 36, rowY + 9, LIST_W - 40 - sw - 4, IOStyle.TEXT);
                this.rowRects.add(new int[]{x + LIST_X, rowY, LIST_W, ROW_H - 2, index});
                this.pageTargets.add(target);
            }
            if (targets.isEmpty()) {
                SkiaDialog.center(c, Component.translatable("gui.module_shop.grant.empty").getString(), x + LIST_X,
                        y + 100, LIST_W, IOStyle.TEXT_2);
            }
            if (pages > 1) {
                SkiaDialog.flatButton(c, x + LIST_X, y + 162, 40, 16, "‹", n, this.page > 0, mouseX, mouseY);
                SkiaDialog.flatButton(c, x + LIST_X + LIST_W - 40, y + 162, 40, 16, "›", n, this.page < pages - 1,
                        mouseX, mouseY);
                SkiaDialog.center(c, Component.translatable("gui.module_shop.page", this.page + 1, pages).getString(),
                        x + LIST_X, y + 170, LIST_W, IOStyle.TEXT_2);
            }
            SkiaDialog.flatButton(c, x + LIST_X, y + 184, LIST_W, 20,
                    Component.translatable("gui.module_shop.grant.send").getString(),
                    cn.autoforged.module_shop.client.ui.widget.Button.Kind.ACCENT, true, mouseX, mouseY);
        });
        // 头像：Skija 之后用原版画（已 flush，层序正确）
        for (int i = 0; i < this.pageTargets.size(); i++) {
            int rowY = y + LIST_Y + i * ROW_H;
            Skins.drawHead(g, this.pageTargets.get(i).uuid, x + LIST_X + 17, rowY + 1, 16);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        if (!this.skia) {
            super.renderLabels(g, mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        // ESC 返回上一级界面（Nav 返回栈），没有上一级时才真正关闭。
        Nav.back();
    }
}
