package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.PriceSpecEditor;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.menu.GrantMenu;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * 管理员发放界面：左侧 3×9 真实物品槽与背包，右侧为双独立账本配价组件 + 在线/历史玩家名单。
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

    private static final int PANEL_W = 438;
    private static final int PANEL_H = 236;
    private static final int LIST_X = 180;
    private static final int LIST_W = 250;
    private static final int ROW_H = 18;
    private static final int COLOR_ONLINE = 0x55FF55;

    private final Set<UUID> selected = new HashSet<>();
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<ClientShopData.GrantTarget> pageTargets = new ArrayList<>();
    private EditBox amountBox;
    private EditBox searchBox;
    private int page;
    private String fallbackError = "";

    private final ItemOverlay currencyItems = new ItemOverlay();
    private PriceSpecEditor amountEditor;
    private long lastFrameNs;

    public GrantScreen(GrantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = GrantMenu.INVENTORY_Y - 12;
    }

    private int skEditorReserveH() {
        return ClientHomeCurrencyState.physEnabled ? 72 : 26;
    }

    private int skPageSize() {
        return ClientHomeCurrencyState.physEnabled ? 4 : 6;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos;
        int y = this.topPos;
        this.skia = AtomUi.available();
        if (this.skia) {
            skInit(x, y);
            return;
        }
        this.amountEditor = null;
        this.amountBox = new EditBox(this.font, x + LIST_X, y + 20, LIST_W, 16,
                Component.translatable("gui.module_shop.grant.amount"));
        this.amountBox.setMaxLength(64);
        this.amountBox.setHint(Component.translatable("gui.module_shop.grant.amount_hint"));
        this.addRenderableWidget(this.amountBox);

        this.searchBox = new EditBox(this.font, x + LIST_X, y + 42, LIST_W, 16,
                Component.translatable("gui.module_shop.grant.search_hint"));
        this.searchBox.setMaxLength(32);
        this.searchBox.setHint(Component.translatable("gui.module_shop.grant.search_hint"));
        this.searchBox.setResponder(text -> this.page = 0);
        this.addRenderableWidget(this.searchBox);

        this.addRenderableWidget(new ShopButton(x + 8, y + 78, 78, 16,
                Component.translatable("gui.module_shop.grant.all"), b -> selectAll()));
        this.addRenderableWidget(new ShopButton(x + 90, y + 78, 78, 16,
                Component.translatable("gui.module_shop.grant.none"), b -> this.selected.clear()));

        this.addRenderableWidget(new ShopButton(x + LIST_X, y + 186, 40, 16,
                Component.translatable("gui.module_shop.page_prev"),
                b -> this.page = Math.max(0, this.page - 1)));
        this.addRenderableWidget(new ShopButton(x + LIST_X + LIST_W - 40, y + 186, 40, 16,
                Component.translatable("gui.module_shop.page_next"), b -> this.page++));

        this.addRenderableWidget(new ShopButton(x + LIST_X, y + 205, LIST_W, 20,
                Component.translatable("gui.module_shop.grant.send"), b -> sendGrant()));

        CompoundTag request = new CompoundTag();
        request.putString("action", "admin_grant_request");
        ServerboundShopActionPayload.send(request);
    }

    public void onDataUpdated() {
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

    private List<ClientShopData.GrantTarget> filtered() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim();
        List<ClientShopData.GrantTarget> result = new ArrayList<>();
        for (ClientShopData.GrantTarget target : ClientShopData.grantTargets) {
            if (target.uuid == null) {
                continue;
            }
            if (!cn.autoforged.module_shop.client.screen.shop.PageKit.matchesQuery(query, target.name)) {
                continue;
            }
            result.add(target);
        }
        return result;
    }

    private void selectAll() {
        this.selected.clear();
        for (ClientShopData.GrantTarget target : filtered()) {
            if (target.uuid != null) {
                this.selected.add(target.uuid);
            }
        }
    }

    private PriceSpec resolveSendSpec() {
        if (this.skia && this.amountEditor != null) {
            if (this.amountEditor.mode() == PriceSpec.CurrencyMode.STAR_ONLY
                    && this.amountEditor.starField().value().isBlank()) {
                return PriceSpec.ZERO;
            }
            return this.amountEditor.buildSpec();
        }
        String raw = this.amountBox == null ? "" : this.amountBox.getValue().trim();
        if (raw.isEmpty()) {
            return PriceSpec.ZERO;
        }
        return PriceSpec.parseRuleText(raw);
    }

    private void sendGrant() {
        PriceSpec spec = resolveSendSpec();
        if (spec == null) {
            if (!this.skia) {
                this.fallbackError = ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.parse_err",
                        "格式无效：%s", this.amountBox == null ? "" : this.amountBox.getValue());
            }
            return;
        }
        this.fallbackError = "";
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "admin_grant_send");
        tag.putLong("amount", spec.supportsStar() ? spec.starPrice : 0L);
        tag.put("priceSpec", spec.toTag());
        tag.putBoolean("all", false);
        ListTag targets = new ListTag();
        for (UUID id : this.selected) {
            targets.add(StringTag.valueOf(id.toString()));
        }
        tag.put("targets", targets);
        ServerboundShopActionPayload.send(tag);
    }

    // =========================================================== Vanilla 渲染

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        if (this.skia) {
            return;
        }
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                Bricks.slot(g, x + 8 + col * 18, y + 18 + row * 18);
            }
        }
        g.blit(Bricks.PLAYER_SLOTS, x + 7, y + GrantMenu.INVENTORY_Y - 1, 0, 0, 162, 76, 162, 76);
        g.fill(x + LIST_X - 4, y + 14, x + LIST_X + LIST_W + 4, y + 228, 0x30101114);
        drawLabelsAndTargets(g, x, y);
    }

    private void drawLabelsAndTargets(GuiGraphics g, int x, int y) {
        this.rowRects.clear();
        List<ClientShopData.GrantTarget> visibleTargets = filtered();
        g.drawString(this.font, Component.translatable("gui.module_shop.grant.amount_label"), x + LIST_X, y + 10,
                IOStyle.TEXT_2, true);
        g.drawString(this.font,
                Component.translatable("gui.module_shop.grant.selected", this.selected.size(),
                        visibleTargets.size()),
                x + 8, y + 102, IOStyle.ORANGE, true);
        g.drawString(this.font, Component.translatable("gui.module_shop.grant.hint"), x + 8, y + 116,
                IOStyle.TEXT_2, true);
        if (!this.fallbackError.isEmpty()) {
            g.drawString(this.font, Component.literal(this.fallbackError), x + 8, y + 130, IOStyle.RED, true);
        }

        int pageSize = 6;
        int listY = y + 64;
        int pages = Math.max(1, (visibleTargets.size() + pageSize - 1) / pageSize);
        this.page = Mth.clamp(this.page, 0, pages - 1);
        this.pageTargets.clear();
        for (int i = 0; i < pageSize; i++) {
            int index = this.page * pageSize + i;
            if (index >= visibleTargets.size()) {
                break;
            }
            ClientShopData.GrantTarget target = visibleTargets.get(index);
            int rowY = listY + i * ROW_H;
            boolean checked = this.selected.contains(target.uuid);
            g.fill(x + LIST_X, rowY, x + LIST_X + LIST_W, rowY + ROW_H - 2, checked ? 0xFF39442F : 0xFF232427);
            if (checked) {
                g.fill(x + LIST_X, rowY, x + LIST_X + 2, rowY + ROW_H - 2, IOStyle.ORANGE);
            }
            drawCheckbox(g, x + LIST_X + 4, rowY + 4, checked);
            Skins.drawHead(g, target.uuid, x + LIST_X + 17, rowY + 1, 15);
            g.drawString(this.font, target.name, x + LIST_X + 36, rowY + 4, IOStyle.TEXT, false);
            g.drawString(this.font,
                    Component.translatable(target.online
                            ? "gui.module_shop.grant.online"
                            : "gui.module_shop.grant.offline"),
                    x + LIST_X + LIST_W - 28, rowY + 4, target.online ? COLOR_ONLINE : IOStyle.TEXT_2, true);
            this.rowRects.add(new int[]{x + LIST_X, rowY, LIST_W, ROW_H - 2, index});
            this.pageTargets.add(target);
        }
        if (visibleTargets.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.grant.empty"),
                    x + LIST_X + LIST_W / 2, listY + 44, IOStyle.TEXT_2);
        }
        if (pages > 1) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.page", this.page + 1, pages),
                    x + LIST_X + LIST_W / 2, y + 190, IOStyle.TEXT_2);
        }
    }

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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && this.searchBox != null
                && this.searchBox.isFocused() && !this.searchBox.getValue().isEmpty()) {
            this.searchBox.setValue("");
            this.page = 0;
            return true;
        }
        if (this.getFocused() instanceof EditBox eb && eb.isFocused()
                && (eb.keyPressed(keyCode, scanCode, modifiers) || eb.canConsumeInput())) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.skia && this.amountEditor != null
                && this.amountEditor.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (scrollY != 0.0D) {
            int ps = this.skia ? skPageSize() : 6;
            int pages = Math.max(1, (filtered().size() + ps - 1) / ps);
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
        if (this.skia) {
            if (this.amountEditor != null && this.amountEditor.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (button == 0 && skClick(mouseX, mouseY)) {
                return true;
            }
        }
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

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.skia && this.amountEditor != null
                && this.amountEditor.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.skia && this.amountEditor != null
                && this.amountEditor.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // =========================================================== Skija

    private boolean skia;

    private void skInit(int x, int y) {
        String keepSearch = this.searchBox == null ? "" : this.searchBox.getValue();
        PriceSpec preserved = this.amountEditor != null ? this.amountEditor.buildSpec() : PriceSpec.ZERO;
        this.amountEditor = new PriceSpecEditor(this, this.font, PriceSpecEditor.Scope.OP)
                .allowZero(true)
                .items(this.currencyItems);
        this.amountEditor.setInitial(preserved != null ? preserved : PriceSpec.ZERO);
        this.amountEditor.registerInputs(this::addWidget);

        int searchY = y + 18 + skEditorReserveH() + 3;
        this.searchBox = new EditBox(this.font, x + LIST_X + 4, searchY + 4, LIST_W - 22, 10,
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
        int searchY = y + 18 + skEditorReserveH() + 3;
        if (this.searchBox != null && !this.searchBox.getValue().isEmpty()
                && IOStyle.inside(x + LIST_X + LIST_W - 16, searchY, 16, 16, mx, my)) {
            Bricks.click();
            this.searchBox.setValue("");
            this.searchBox.setFocused(true);
            this.setFocused(this.searchBox);
            this.page = 0;
            return true;
        }
        if (IOStyle.inside(x + 8, y + 78, 78, 16, mx, my)) {
            Bricks.click();
            selectAll();
            return true;
        }
        if (IOStyle.inside(x + 90, y + 78, 78, 16, mx, my)) {
            Bricks.click();
            this.selected.clear();
            return true;
        }
        int ps = skPageSize();
        int pages = Math.max(1, (filtered().size() + ps - 1) / ps);
        int pagerY = y + 185;
        if (pages > 1 && IOStyle.inside(x + LIST_X, pagerY, 40, 15, mx, my)) {
            Bricks.click();
            this.page = Math.max(0, this.page - 1);
            return true;
        }
        if (pages > 1 && IOStyle.inside(x + LIST_X + LIST_W - 40, pagerY, 40, 15, mx, my)) {
            Bricks.click();
            this.page = Math.min(pages - 1, this.page + 1);
            return true;
        }
        if (IOStyle.inside(x + LIST_X, y + 203, LIST_W, 20, mx, my)) {
            Bricks.click();
            sendGrant();
            return true;
        }
        return false;
    }

    private void skBg(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float dt = this.lastFrameNs == 0L ? 16.0F : Math.min(100.0F, (now - this.lastFrameNs) / 1_000_000.0F);
        this.lastFrameNs = now;

        this.rowRects.clear();
        this.pageTargets.clear();
        List<ClientShopData.GrantTarget> targets = filtered();
        int ps = skPageSize();
        int pages = Math.max(1, (targets.size() + ps - 1) / ps);
        this.page = Mth.clamp(this.page, 0, pages - 1);

        int edTopY = y + 18;
        int reserveH = skEditorReserveH();
        int searchY = edTopY + reserveH + 3;
        int listY = searchY + 19;

        this.currencyItems.begin(mouseX, mouseY, true);
        SkiaFrame.draw(g, c -> {
            SkiaDialog.bare(c, x, y, PANEL_W, PANEL_H);
            Text.drawLine(c, this.title.getString(), AtomUi.BOLD, Text.BODY,
                    IOStyle.TEXT, x + 8, y + 9, 160, Text.Align.LEFT);
            for (int i = 0; i < 27; i++) {
                SkiaDialog.slotFrame(c, x + 8 + (i % 9) * 18, y + 18 + (i / 9) * 18);
            }
            Button.Kind n = Button.Kind.NORMAL;
            SkiaDialog.flatButton(c, x + 8, y + 78, 78, 16,
                    Component.translatable("gui.module_shop.grant.all").getString(), n, true, mouseX, mouseY);
            SkiaDialog.flatButton(c, x + 90, y + 78, 78, 16,
                    Component.translatable("gui.module_shop.grant.none").getString(), n, true, mouseX, mouseY);
            SkiaDialog.text(c, Component.translatable("gui.module_shop.grant.selected", this.selected.size(),
                    targets.size()).getString(), x + 8, y + 104, 162, IOStyle.ORANGE);
            SkiaDialog.paragraph(c, Component.translatable("gui.module_shop.grant.hint").getString(), x + 8, y + 114,
                    162, Text.SMALL, IOStyle.TEXT_3, 2, false);
            SkiaDialog.small(c, this.playerInventoryTitle.getString(), x + 8, y + GrantMenu.INVENTORY_Y - 6, 160,
                    IOStyle.TEXT_3);
            SkiaDialog.playerSlots(c, x + 8, y + GrantMenu.INVENTORY_Y);

            // 右栏卡片：顶部双账本配价组件 + 搜索框 + 玩家勾选列表 + 发送
            SkiaDialog.card(c, x + LIST_X - 4, y + 14, LIST_W + 8, 212);
            this.amountEditor.visible(true);
            this.amountEditor.bounds(x + LIST_X, edTopY, LIST_W);
            this.amountEditor.render(c, this.currencyItems, mouseX, mouseY, dt);

            String edErr = (this.amountEditor.mode() == PriceSpec.CurrencyMode.STAR_ONLY
                    && this.amountEditor.starField().value().isBlank()) ? null : this.amountEditor.validateError();
            if (edErr != null) {
                SkiaDialog.small(c, edErr, x + 8, y + 135, 162, IOStyle.RED);
            }

            SkiaDialog.fieldFrame(c, x + LIST_X, searchY, LIST_W, 16, this.searchBox.isFocused());
            SkiaDialog.themeBox(this.searchBox);
            if (!this.searchBox.getValue().isEmpty()) {
                boolean ch = IOStyle.inside(x + LIST_X + LIST_W - 16, searchY, 16, 16, mouseX, mouseY);
                Draw.cross(c, x + LIST_X + LIST_W - 8, searchY + 8, 2.8F, 1.2F,
                        ch ? IOStyle.TEXT : IOStyle.TEXT_3);
            }
            if (this.searchBox.getValue().isEmpty() && !this.searchBox.isFocused()) {
                SkiaDialog.small(c, Component.translatable("gui.module_shop.grant.search_hint").getString(),
                        x + LIST_X + 4, searchY + 8, LIST_W - 8, IOStyle.TEXT_3);
            }

            for (int i = 0; i < ps; i++) {
                int index = this.page * ps + i;
                if (index >= targets.size()) {
                    break;
                }
                ClientShopData.GrantTarget target = targets.get(index);
                int rowY = listY + i * ROW_H;
                boolean checked = this.selected.contains(target.uuid);
                boolean hover = IOStyle.inside(x + LIST_X, rowY, LIST_W, ROW_H - 2, mouseX, mouseY);
                Draw.fill(c, x + LIST_X, rowY, LIST_W, ROW_H - 2, 3,
                        checked ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                Draw.stroke(c, x + LIST_X + 4, rowY + 3.5F, 9, 9, 2, 1,
                        checked ? IOStyle.BLUE : IOStyle.BORDER);
                if (checked) {
                    Draw.fill(c, x + LIST_X + 4, rowY + 3.5F, 9, 9, 2, IOStyle.BLUE);
                    Draw.line(c, x + LIST_X + 6, rowY + 8, x + LIST_X + 8, rowY + 10, 1.3F, 0xFFFFFFFF);
                    Draw.line(c, x + LIST_X + 8, rowY + 10, x + LIST_X + 11.5F, rowY + 6, 1.3F, 0xFFFFFFFF);
                }
                String status = Component.translatable(target.online ? "gui.module_shop.grant.online"
                        : "gui.module_shop.grant.offline").getString();
                float sw = Text.drawLine(c, status, AtomUi.REGULAR, Text.SMALL,
                        target.online ? IOStyle.GREEN : IOStyle.TEXT_3, x + LIST_X, rowY + 8, LIST_W - 4,
                        Text.Align.RIGHT);
                SkiaDialog.text(c, target.name, x + LIST_X + 35, rowY + 8, LIST_W - 40 - sw - 3.5F, IOStyle.TEXT);
                this.rowRects.add(new int[]{x + LIST_X, rowY, LIST_W, ROW_H - 2, index});
                this.pageTargets.add(target);
            }
            if (targets.isEmpty()) {
                SkiaDialog.center(c, Component.translatable("gui.module_shop.grant.empty").getString(), x + LIST_X,
                        listY + ps * ROW_H * 0.5F, LIST_W, IOStyle.TEXT_2);
            }
            int pagerY = y + 185;
            if (pages > 1) {
                SkiaDialog.flatButton(c, x + LIST_X, pagerY, 40, 15, "‹", n, this.page > 0, mouseX, mouseY);
                SkiaDialog.flatButton(c, x + LIST_X + LIST_W - 40, pagerY, 40, 15, "›", n, this.page < pages - 1,
                        mouseX, mouseY);
                SkiaDialog.center(c, Component.translatable("gui.module_shop.page", this.page + 1, pages).getString(),
                        x + LIST_X, pagerY + 7.5F, LIST_W, IOStyle.TEXT_2);
            }
            SkiaDialog.flatButton(c, x + LIST_X, y + 203, LIST_W, 20,
                    Component.translatable("gui.module_shop.grant.send").getString(),
                    Button.Kind.ACCENT, edErr == null, mouseX, mouseY);
        });
        this.currencyItems.flush(g, this.font);
        for (int i = 0; i < this.pageTargets.size(); i++) {
            int rowY = listY + i * ROW_H;
            Skins.drawHead(g, this.pageTargets.get(i).uuid, x + LIST_X + 16, rowY + 1, 14);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            this.renderTooltip(g, mouseX, mouseY);
        } else if (this.skia && !this.currencyItems.renderTooltip(g, this.font, mouseX, mouseY)
                && this.amountEditor != null && this.amountEditor.hoveredTooltip() != null) {
            g.renderTooltip(this.font, this.amountEditor.hoveredTooltip(), mouseX, mouseY);
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
        Nav.back();
    }
}
