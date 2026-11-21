package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.PriceSpecEditor;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;

/**
 * 管理员系统定额费用配置弹窗：统一可视化配置
 * <ul>
 *     <li>每日商店付费刷新各次费用（{@code dailyRefreshSpecs}）</li>
 *     <li>每日收购付费刷新各次费用（{@code buybackRefreshSpecs}）</li>
 *     <li>出货箱等级升级费用（{@code shippingUpgradeSpecs}）</li>
 * </ul>
 * 每条均支持通过 {@link PriceSpecEditor} 配成星币 / 实体货币 / 二选一 / 组合同付。
 */
public class SystemCostsAdminScreen extends SkiaDialog {
    private static final float DLG_W = 406.0F;
    private static final float DLG_H = 258.0F;

    private static final int TAB_SHOP = 0;
    private static final int TAB_BUYBACK = 1;
    private static final int TAB_SHIPPING = 2;

    private final List<PriceSpec> shopSpecs = new ArrayList<>();
    private final List<PriceSpec> buybackSpecs = new ArrayList<>();
    private final List<PriceSpec> shippingSpecs = new ArrayList<>();

    private int activeTab;
    private int selectedIndex;
    private boolean syncingEditor;

    private PriceSpecEditor editor;
    private float edX;
    private float edY;
    private float edW;
    private float edH;

    private final ScrollController listScroll = new ScrollController();
    private EditBox fallbackBox;
    private String fallbackError = "";

    public SystemCostsAdminScreen(@Nullable String initialMode) {
        super(Component.literal(ClientHomeCurrencyState.tr(
                "gui.module_shop.sys_costs.title", "系统费用设置（双独立账本）")));
        copySpecs(ClientShopData.dailyRefreshSpecs, this.shopSpecs);
        copySpecs(ClientShopData.buybackRefreshSpecs, this.buybackSpecs);
        copySpecs(ClientShopData.shippingUpgradeSpecs, this.shippingSpecs);
        if ("buyback".equals(initialMode)) {
            this.activeTab = TAB_BUYBACK;
        } else if ("shipping".equals(initialMode)) {
            this.activeTab = TAB_SHIPPING;
        } else {
            this.activeTab = TAB_SHOP;
        }
        clampSelection();
    }

    private static void copySpecs(List<PriceSpec> src, List<PriceSpec> dst) {
        dst.clear();
        if (src == null) {
            return;
        }
        for (PriceSpec spec : src) {
            if (spec != null) {
                dst.add(spec);
            }
        }
    }

    private List<PriceSpec> activeList() {
        return switch (this.activeTab) {
            case TAB_BUYBACK -> this.buybackSpecs;
            case TAB_SHIPPING -> this.shippingSpecs;
            default -> this.shopSpecs;
        };
    }

    private void clampSelection() {
        List<PriceSpec> list = activeList();
        if (list.isEmpty()) {
            this.selectedIndex = -1;
        } else {
            this.selectedIndex = Math.max(0, Math.min(this.selectedIndex, list.size() - 1));
        }
    }

    private void loadEditorFromSelection() {
        if (this.editor == null) {
            return;
        }
        clampSelection();
        List<PriceSpec> list = activeList();
        this.syncingEditor = true;
        try {
            if (this.selectedIndex >= 0 && this.selectedIndex < list.size()) {
                this.editor.enabled(true);
                this.editor.visible(true);
                this.editor.setInitial(list.get(this.selectedIndex));
            } else {
                this.editor.setInitial(PriceSpec.ZERO);
                this.editor.enabled(false);
            }
        } finally {
            this.syncingEditor = false;
        }
    }

    private void commitEditorToSelection() {
        if (this.syncingEditor || this.editor == null) {
            return;
        }
        List<PriceSpec> list = activeList();
        if (this.selectedIndex < 0 || this.selectedIndex >= list.size()) {
            return;
        }
        PriceSpec built = this.editor.buildSpec();
        if (built != null) {
            list.set(this.selectedIndex, built);
        }
    }

    private void switchTab(int tab) {
        if (tab == this.activeTab) {
            return;
        }
        if (skia()) {
            commitEditorToSelection();
        } else {
            commitFallbackText();
        }
        this.activeTab = tab;
        this.selectedIndex = 0;
        clampSelection();
        this.listScroll.reset();
        if (skia()) {
            loadEditorFromSelection();
        } else {
            loadFallbackText();
        }
    }

    private void selectRow(int idx) {
        commitEditorToSelection();
        this.selectedIndex = idx;
        clampSelection();
        loadEditorFromSelection();
    }

    private void addTier() {
        commitEditorToSelection();
        List<PriceSpec> list = activeList();
        PriceSpec seed = list.isEmpty() ? PriceSpec.star(100L) : list.get(list.size() - 1);
        list.add(seed);
        this.selectedIndex = list.size() - 1;
        loadEditorFromSelection();
    }

    private void removeTier() {
        List<PriceSpec> list = activeList();
        if (this.selectedIndex < 0 || this.selectedIndex >= list.size()) {
            return;
        }
        list.remove(this.selectedIndex);
        if (this.selectedIndex >= list.size()) {
            this.selectedIndex = list.size() - 1;
        }
        loadEditorFromSelection();
    }

    private void moveTier(int delta) {
        commitEditorToSelection();
        List<PriceSpec> list = activeList();
        int next = this.selectedIndex + delta;
        if (this.selectedIndex < 0 || next < 0 || next >= list.size()) {
            return;
        }
        PriceSpec tmp = list.get(this.selectedIndex);
        list.set(this.selectedIndex, list.get(next));
        list.set(next, tmp);
        this.selectedIndex = next;
        loadEditorFromSelection();
    }

    private void resetActiveTabToConfig() {
        activeList().clear();
        this.selectedIndex = -1;
        if (skia()) {
            loadEditorFromSelection();
        } else {
            loadFallbackText();
        }
    }

    private String rowTitle(int index) {
        if (this.activeTab == TAB_SHIPPING) {
            return ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.ship_lvl", "Lv.%s → Lv.%s",
                    index + 1, index + 2);
        }
        return ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.refresh_nth", "第 %s 次刷新", index + 1);
    }

    private void saveAndClose() {
        if (skia()) {
            if (this.selectedIndex >= 0 && this.editor != null && this.editor.validateError() != null) {
                return;
            }
            commitEditorToSelection();
        } else if (!commitFallbackText()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "admin_system_costs_set");
        tag.put("dailyRefreshSpecs", toSpecList(this.shopSpecs));
        tag.put("buybackRefreshSpecs", toSpecList(this.buybackSpecs));
        tag.put("shippingUpgradeSpecs", toSpecList(this.shippingSpecs));
        ServerboundShopActionPayload.send(tag);
        Nav.back();
    }

    private static ListTag toSpecList(List<PriceSpec> specs) {
        ListTag list = new ListTag();
        for (PriceSpec spec : specs) {
            if (spec != null) {
                list.add(spec.toTag());
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ Vanilla Fallback

    @Override
    protected void init() {
        if (skiaInit()) {
            return;
        }
        int pw = 300;
        int ph = 160;
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;
        this.addRenderableWidget(new ShopButton(px + 12, py + 28, 88, 18,
                Component.literal(ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.tab_shop", "商店刷新费")),
                b -> switchTab(TAB_SHOP)));
        this.addRenderableWidget(new ShopButton(px + 106, py + 28, 88, 18,
                Component.literal(ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.tab_buyback", "收购刷新费")),
                b -> switchTab(TAB_BUYBACK)));
        this.addRenderableWidget(new ShopButton(px + 200, py + 28, 88, 18,
                Component.literal(ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.tab_shipping", "出货箱升级")),
                b -> switchTab(TAB_SHIPPING)));

        this.fallbackBox = new EditBox(this.font, px + 14, py + 72, pw - 28, 18,
                Component.literal("costs"));
        this.fallbackBox.setMaxLength(512);
        loadFallbackText();
        this.addRenderableWidget(this.fallbackBox);

        int by = py + ph - 30;
        this.addRenderableWidget(new ShopButton(px + 14, by, 84, 20,
                Component.literal(ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.reset", "恢复默认")),
                b -> resetActiveTabToConfig()));
        this.addRenderableWidget(new ShopButton(px + pw - 190, by, 84, 20,
                Component.translatable("gui.module_shop.confirm"),
                b -> saveAndClose()));
        this.addRenderableWidget(new ShopButton(px + pw - 98, by, 84, 20,
                Component.translatable("gui.module_shop.cancel"),
                b -> onClose()));
    }

    private void loadFallbackText() {
        if (this.fallbackBox == null) {
            return;
        }
        List<PriceSpec> list = activeList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i).toRuleText());
        }
        this.fallbackBox.setValue(sb.toString());
        this.fallbackError = "";
    }

    private boolean commitFallbackText() {
        if (this.fallbackBox == null) {
            return true;
        }
        String raw = this.fallbackBox.getValue().trim();
        List<PriceSpec> parsed = new ArrayList<>();
        if (!raw.isEmpty()) {
            String[] parts = raw.split(",");
            for (String p : parts) {
                String token = p.trim();
                if (token.isEmpty()) {
                    continue;
                }
                PriceSpec spec = PriceSpec.parseRuleText(token);
                if (spec == null) {
                    this.fallbackError = ClientHomeCurrencyState.tr(
                            "gui.module_shop.sys_costs.parse_err", "格式无效：%s", token);
                    return false;
                }
                parsed.add(spec);
            }
        }
        List<PriceSpec> dst = activeList();
        dst.clear();
        dst.addAll(parsed);
        this.fallbackError = "";
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        if (skia()) {
            return;
        }
        int pw = 300;
        int ph = 160;
        Bricks.panel(g, (this.width - pw) / 2, (this.height - ph) / 2, pw, ph);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
        int pw = 300;
        int ph = 160;
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;
        IOStyle.centerAt(g, this.font, this.title, px + pw / 2, py + 10, IOStyle.ORANGE);
        g.drawString(this.font, Component.literal(ClientHomeCurrencyState.tr(
                "gui.module_shop.sys_costs.fallback_hint", "逗号分隔各阶规则（留空 = 恢复 Config 默认）")),
                px + 14, py + 56, IOStyle.TEXT_2, true);
        if (!this.fallbackError.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.literal(this.fallbackError),
                    px + pw / 2, py + 100, IOStyle.RED);
        }
    }

    // ------------------------------------------------------------------ Skija

    @Override
    protected void initSkia() {
        this.editor = new PriceSpecEditor(this, this.font, PriceSpecEditor.Scope.OP)
                .allowZero(true)
                .items(this.items)
                .onChange(this::commitEditorToSelection);
        this.editor.registerInputs(this::addWidget);
        loadEditorFromSelection();
    }

    @Override
    protected void paint(Canvas c) {
        float w = DLG_W;
        float h = DLG_H;
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        float innerX = x + PAD;
        float innerW = w - PAD * 2.0F;
        panel(c, x, y, w, h, this.title.getString());

        // 1. 顶部模式页签
        float tabY = y + HEADER_H + 6.0F;
        float tabGap = 6.0F;
        float tabW = (innerW - tabGap * 2.0F) / 3.0F;
        String[] tabLabels = {
                ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.tab_shop", "每日商店刷新"),
                ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.tab_buyback", "每日收购刷新"),
                ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.tab_shipping", "出货箱升级")
        };
        for (int i = 0; i < 3; i++) {
            final int tabIdx = i;
            chip(c, "tab:" + i, tabLabels[i], innerX + i * (tabW + tabGap), tabY, tabW, 18.0F,
                    this.activeTab == i, IOStyle.BLUE, () -> switchTab(tabIdx));
        }

        // 2. 主体双栏区
        float bodyY = tabY + 24.0F;
        float footY = y + h - PAD - 20.0F;
        float bodyH = footY - bodyY - 8.0F;
        float leftW = 160.0F;
        float colGap = 10.0F;
        float rightX = innerX + leftW + colGap;
        float rightW = innerW - leftW - colGap;

        // 2A. 左侧序号列表 + 增删改序操作栏
        card(c, innerX, bodyY, leftW, bodyH);
        float ctrlBarH = 18.0F;
        float listH = bodyH - ctrlBarH - 10.0F;
        List<PriceSpec> list = activeList();
        float rowH = 22.0F;

        if (list.isEmpty()) {
            small(c, ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.empty_list",
                    "当前无覆盖项\n将使用 Config 默认值\n点击下方「+ 新增」覆盖"),
                    innerX + 8.0F, bodyY + 24.0F, leftW - 16.0F, IOStyle.TEXT_3);
        } else {
            float oy = beginList(c, this.listScroll, innerX + 4.0F, bodyY + 4.0F,
                    leftW - 8.0F, listH, list.size() * rowH);
            float rowW = leftW - 14.0F;
            for (int i = 0; i < list.size(); i++) {
                float ry = oy + i * rowH;
                if (ry + rowH < bodyY || ry > bodyY + 4.0F + listH) {
                    continue;
                }
                boolean sel = i == this.selectedIndex;
                boolean hov = over(innerX + 4.0F, ry, rowW, rowH - 2.0F);
                int bg = sel ? IOStyle.BLUE_TINT : (hov ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR);
                Draw.fill(c, innerX + 4.0F, ry, rowW, rowH - 2.0F, 3.0F, bg);
                if (sel) {
                    Draw.stroke(c, innerX + 4.0F, ry, rowW, rowH - 2.0F, 3.0F, 1.0F, IOStyle.BLUE);
                }
                String lbl = rowTitle(i);
                float lw = V2.text(c, lbl, V2.FS_S, sel ? IOStyle.BLUE : IOStyle.TEXT,
                        innerX + 9.0F, ry + (rowH - 2.0F) * 0.5F, 64.0F, Text.Align.LEFT);
                float priceX = innerX + 12.0F + lw;
                float priceW = innerX + 4.0F + rowW - 5.0F - priceX;
                PriceDisplay.drawCell(c, this.items, list.get(i), priceX, ry + (rowH - 2.0F) * 0.5F,
                        Math.max(24.0F, priceW), Text.Align.RIGHT, IOStyle.ORANGE, false);
                final int rowIdx = i;
                hit(innerX + 4.0F, ry, rowW, rowH - 2.0F, () -> selectRow(rowIdx), null);
            }
            endList(c);
        }

        // 左栏底部小操作条：[+ 新增] [↑] [↓] [删除]
        float actY = bodyY + bodyH - ctrlBarH - 4.0F;
        float addW = 54.0F;
        float tinyW = 24.0F;
        float delW = leftW - 8.0F - addW - tinyW * 2.0F - 9.0F;
        boolean hasSel = this.selectedIndex >= 0 && this.selectedIndex < list.size();
        button(c, "add_tier", ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.add", "+ 新增"),
                innerX + 4.0F, actY, addW, ctrlBarH, Button.Kind.ACCENT, true, this::addTier);
        button(c, "up_tier", "↑", innerX + 4.0F + addW + 3.0F, actY, tinyW, ctrlBarH,
                Button.Kind.NORMAL, hasSel && this.selectedIndex > 0, () -> moveTier(-1));
        button(c, "dn_tier", "↓", innerX + 4.0F + addW + tinyW + 6.0F, actY, tinyW, ctrlBarH,
                Button.Kind.NORMAL, hasSel && this.selectedIndex < list.size() - 1, () -> moveTier(1));
        button(c, "del_tier", ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.del", "删"),
                innerX + 4.0F + addW + tinyW * 2.0F + 9.0F, actY, delW, ctrlBarH,
                Button.Kind.DANGER, hasSel, this::removeTier);

        // 2B. 右侧配价区
        card(c, rightX, bodyY, rightW, bodyH);
        float rx = rightX + 10.0F;
        float rw = rightW - 20.0F;
        if (hasSel) {
            String edTitle = ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.editing",
                    "正在配置：%s", rowTitle(this.selectedIndex));
            text(c, edTitle, rx, bodyY + 14.0F, rw, IOStyle.TEXT);
            this.edX = rx;
            this.edY = bodyY + 26.0F;
            this.edW = rw;
            this.editor.visible(true);
            this.editor.bounds(this.edX, this.edY, this.edW);
            this.edH = this.editor.preferredHeight();
            this.editor.render(c, this.items, this.mouseX, this.mouseY, this.dtMs);
            Component edTip = this.editor.hoveredTooltip();
            if (edTip != null) {
                tip(edTip);
            }
            String err = this.editor.validateError();
            if (err != null && !err.isBlank()) {
                small(c, err, rx, this.edY + this.edH + 10.0F, rw, IOStyle.RED);
            } else {
                small(c, ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.free_note",
                                "提示：金额留 0 表示免费；清空左表并保存可恢复 Config 默认"),
                        rx, this.edY + this.edH + 10.0F, rw, IOStyle.TEXT_3);
            }
        } else {
            this.editor.visible(false);
            this.edW = 0.0F;
            this.edH = 0.0F;
            center(c, ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.pick_hint",
                    "请先点击左侧「+ 新增」添加档位，或直接保存以恢复 Config 默认"), rx, bodyY + bodyH * 0.5F, rw, IOStyle.TEXT_3);
        }

        // 3. 底栏操作按钮
        boolean editorValid = !hasSel || this.editor.validateError() == null;
        button(c, "reset", ClientHomeCurrencyState.tr("gui.module_shop.sys_costs.reset_btn", "恢复Config默认"),
                innerX, footY, 108.0F, 20.0F, Button.Kind.NORMAL, !list.isEmpty(), this::resetActiveTabToConfig);
        button(c, "cancel", Component.translatable("gui.module_shop.cancel").getString(),
                innerX + innerW - 178.0F, footY, 82.0F, 20.0F, Button.Kind.NORMAL, true, this::onClose);
        button(c, "save", Component.translatable("gui.module_shop.schedule.save").getString(),
                innerX + innerW - 90.0F, footY, 90.0F, 20.0F, Button.Kind.ACCENT, editorValid, this::saveAndClose);
    }

    private double toDialogX(double guiX) {
        Minecraft mc = Minecraft.getInstance();
        int winW = Math.max(1, mc.getWindow().getGuiScaledWidth());
        return guiX * this.width / winW;
    }

    private double toDialogY(double guiY) {
        Minecraft mc = Minecraft.getInstance();
        int winH = Math.max(1, mc.getWindow().getGuiScaledHeight());
        return guiY * this.height / winH;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (skia() && this.editor != null) {
            double sx = toDialogX(mx);
            double sy = toDialogY(my);
            if (!(sx >= this.edX && sx < this.edX + this.edW && sy >= this.edY && sy < this.edY + this.edH)) {
                this.editor.blur();
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        return this.editor != null && this.selectedIndex >= 0 && this.editor.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (skia() && this.editor != null && this.selectedIndex >= 0) {
            if (this.editor.mouseDragged(toDialogX(mx), toDialogY(my), button, toDialogX(dx), toDialogY(dy))) {
                return true;
            }
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (skia() && this.editor != null && this.selectedIndex >= 0) {
            if (this.editor.mouseReleased(toDialogX(mx), toDialogY(my), button)) {
                return true;
            }
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    protected boolean onScroll(double mx, double my, double amount) {
        return this.editor != null && this.selectedIndex >= 0 && this.editor.mouseScrolled(mx, my, amount);
    }

    @Override
    public void onClose() {
        Nav.back();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
