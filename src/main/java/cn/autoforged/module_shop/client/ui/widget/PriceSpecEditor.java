package cn.autoforged.module_shop.client.ui.widget;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.PhysTierView;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.PriceSpec.CurrencyMode;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 可嵌入任意 {@code SkiaDialog} / Skija 面板的通用双独立账本配价组件。
 *
 * <ul>
 *     <li><b>OP 模式</b>（{@code opMode = true}）：支持四态（仅星币 / 仅实体币 / 二选一双通道 / 组合同付）；</li>
 *     <li><b>玩家模式</b>（{@code opMode = false}）：支持二态（星币 / 实体货币）；</li>
 *     <li><b>关闭实体货币时</b>：自动退化为单一紧凑的星币输入框，不额外占用空间。</li>
 * </ul>
 */
public final class PriceSpecEditor {
    public enum Scope { OP, PLAYER }

    private static final float MODE_H = 15.0F;
    private static final float MODE_GAP = 4.0F;
    private static final float STRIP_H = 24.0F;
    private static final float STRIP_GAP = 4.0F;
    private static final float FIELD_LABEL_H = 9.0F;
    private static final float FIELD_H = 15.0F;
    private static final float STACK_MIN_TWO_COL_W = 186.0F;

    private static final class PillRect {
        float x;
        float y;
        float w;
        float h;
        final Runnable action;

        PillRect(float x, float y, float w, float h, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final SkiaTextField starField;
    private final SkiaTextField physField;
    private boolean opMode;
    private boolean allowZero;
    private boolean visible = true;
    private boolean enabled = true;
    private boolean syncing;

    private CurrencyMode mode = CurrencyMode.STAR_ONLY;
    private String selectedTierKey = "";
    private float tierScrollX;
    private float tierMaxScrollX;
    private float stripX;
    private float stripY;
    private float stripW;
    private float stripH;

    private float x;
    private float y;
    private float width = 200.0F;

    @Nullable
    private ItemOverlay boundItems;
    @Nullable
    private Runnable changeListener;
    @Nullable
    private Component hoveredTooltip;

    private final List<PillRect> hits = new ArrayList<>();
    private final List<PillRect> stripHits = new ArrayList<>();

    public PriceSpecEditor(Screen screen, Font font, boolean opMode) {
        this.opMode = opMode;
        this.starField = new SkiaTextField(screen, font, 14)
                .placeholder(tr("gui.module_shop.price_editor.star_ph", "输入星币数量"))
                .fontSize(V2.FS);
        this.physField = new SkiaTextField(screen, font, 10)
                .placeholder(tr("gui.module_shop.price_editor.phys_ph", "输入实体币枚数"))
                .fontSize(V2.FS);
        this.starField.editBox().setFilter(s -> s.isEmpty() || s.matches("[0-9]{0,14}"));
        this.physField.editBox().setFilter(s -> s.isEmpty() || s.matches("[0-9]{0,10}"));
        this.starField.onChange(v -> notifyChanged());
        this.physField.onChange(v -> notifyChanged());
        ensureDefaultTierSelected();
    }

    public PriceSpecEditor(Screen screen, Font font, Scope scope) {
        this(screen, font, scope == Scope.OP);
    }

    // ------------------------------------------------------------------ 链式配置

    public PriceSpecEditor setInitial(@Nullable PriceSpec initial) {
        this.syncing = true;
        try {
            if (initial == null) {
                this.mode = CurrencyMode.STAR_ONLY;
                this.starField.setValue("");
                this.physField.setValue("1");
                this.selectedTierKey = "";
                ensureDefaultTierSelected();
            } else {
                this.mode = clampMode(initial.mode);
                if (initial.starPrice > 0L || (this.allowZero && this.mode == CurrencyMode.STAR_ONLY && initial.starPrice == 0L)) {
                    this.starField.setValue(Long.toString(initial.starPrice));
                } else {
                    this.starField.setValue("");
                }
                this.selectedTierKey = resolveMatchingTierKey(initial.physTierKey);
                this.physField.setValue(initial.physCount > 0L ? Long.toString(initial.physCount) : "1");
            }
            syncFieldVisibility();
        } finally {
            this.syncing = false;
        }
        return this;
    }

    public PriceSpecEditor allowZero(boolean allowZero) {
        this.allowZero = allowZero;
        return this;
    }

    public PriceSpecEditor opMode(boolean opMode) {
        this.opMode = opMode;
        this.mode = clampMode(this.mode);
        syncFieldVisibility();
        return this;
    }

    public PriceSpecEditor starPlaceholder(@Nullable String placeholder) {
        this.starField.placeholder(placeholder == null ? "" : placeholder);
        return this;
    }

    public PriceSpecEditor physPlaceholder(@Nullable String placeholder) {
        this.physField.placeholder(placeholder == null ? "" : placeholder);
        return this;
    }

    public PriceSpecEditor onChange(@Nullable Runnable listener) {
        this.changeListener = listener;
        return this;
    }

    public PriceSpecEditor items(@Nullable ItemOverlay items) {
        this.boundItems = items;
        return this;
    }

    public PriceSpecEditor visible(boolean visible) {
        this.visible = visible;
        syncFieldVisibility();
        return this;
    }

    public PriceSpecEditor enabled(boolean enabled) {
        this.enabled = enabled;
        this.starField.enabled(enabled);
        this.physField.enabled(enabled);
        return this;
    }

    public void registerInputs(Consumer<EditBox> registrar) {
        if (registrar != null) {
            registrar.accept(this.starField.editBox());
            registrar.accept(this.physField.editBox());
        }
    }

    public void blur() {
        this.starField.blur();
        this.physField.blur();
    }

    public boolean isFocused() {
        return this.starField.isFocused() || this.physField.isFocused();
    }

    public SkiaTextField starField() {
        return this.starField;
    }

    public SkiaTextField physField() {
        return this.physField;
    }

    // ------------------------------------------------------------------ 布局尺寸

    public float preferredHeight() {
        if (!physSelectable()) {
            return FIELD_LABEL_H + FIELD_H;
        }
        CurrencyMode effective = clampMode(this.mode);
        float h = MODE_H + MODE_GAP;
        if (effective.supportsPhys()) {
            h += STRIP_H + STRIP_GAP;
        }
        if (effective == CurrencyMode.EITHER || effective == CurrencyMode.BOTH) {
            if (this.width >= STACK_MIN_TWO_COL_W) {
                h += FIELD_LABEL_H + FIELD_H;
            } else {
                h += (FIELD_LABEL_H + FIELD_H) * 2.0F + 4.0F;
            }
        } else {
            h += FIELD_LABEL_H + FIELD_H;
        }
        return h;
    }

    public PriceSpecEditor bounds(float x, float y, float w) {
        this.x = x;
        this.y = y;
        this.width = Math.max(80.0F, w);
        layoutFields();
        return this;
    }

    private void layoutFields() {
        CurrencyMode effective = clampMode(this.mode);
        if (!physSelectable()) {
            this.starField.bounds(this.x, this.y + FIELD_LABEL_H, this.width, FIELD_H);
            syncFieldVisibility();
            return;
        }
        float curY = this.y + MODE_H + MODE_GAP;
        if (effective.supportsPhys()) {
            curY += STRIP_H + STRIP_GAP;
        }
        switch (effective) {
            case STAR_ONLY -> this.starField.bounds(this.x, curY + FIELD_LABEL_H, this.width, FIELD_H);
            case PHYS_ONLY -> this.physField.bounds(this.x, curY + FIELD_LABEL_H, this.width, FIELD_H);
            case EITHER, BOTH -> {
                if (this.width >= STACK_MIN_TWO_COL_W) {
                    float gap = 6.0F;
                    float leftW = (this.width - gap) * 0.5F;
                    float rightW = this.width - gap - leftW;
                    this.starField.bounds(this.x, curY + FIELD_LABEL_H, leftW, FIELD_H);
                    this.physField.bounds(this.x + leftW + gap, curY + FIELD_LABEL_H, rightW, FIELD_H);
                } else {
                    this.starField.bounds(this.x, curY + FIELD_LABEL_H, this.width, FIELD_H);
                    float y2 = curY + FIELD_LABEL_H + FIELD_H + 4.0F;
                    this.physField.bounds(this.x, y2 + FIELD_LABEL_H, this.width, FIELD_H);
                }
            }
        }
        syncFieldVisibility();
    }

    private void syncFieldVisibility() {
        CurrencyMode effective = clampMode(this.mode);
        boolean showStar = this.visible && effective.supportsStar();
        boolean showPhys = this.visible && physSelectable() && effective.supportsPhys();
        this.starField.visible(showStar);
        this.physField.visible(showPhys);
    }

    // ------------------------------------------------------------------ 绘制

    public void render(Canvas c, float mx, float my, float dt) {
        render(c, this.boundItems, mx, my, dt);
    }

    public void render(Canvas c, @Nullable ItemOverlay items, float mx, float my, float dt) {
        this.hoveredTooltip = null;
        this.hits.clear();
        this.stripHits.clear();
        this.stripW = 0.0F;
        if (!this.visible) {
            syncFieldVisibility();
            return;
        }

        ensureDefaultTierSelected();
        this.mode = clampMode(this.mode);
        layoutFields();

        if (!physSelectable()) {
            String starLabel = starFieldTitle();
            V2.text(c, starLabel, V2.FS_XS, IOStyle.TEXT_2, this.x, this.y + 4.5F, this.width, Text.Align.LEFT);
            this.starField.render(c, mx, my, dt);
            return;
        }

        float curY = this.y;
        paintModeBar(c, mx, my, curY);
        curY += MODE_H + MODE_GAP;

        if (this.mode.supportsPhys()) {
            paintTierStrip(c, items, mx, my, curY);
            curY += STRIP_H + STRIP_GAP;
        }

        paintInputs(c, mx, my, dt, curY);
    }

    private void paintModeBar(Canvas c, float mx, float my, float rowY) {
        CurrencyMode[] modes = availableModes();
        float gap = 3.0F;
        float btnW = Math.max(24.0F, (this.width - gap * (modes.length - 1)) / modes.length);
        for (int i = 0; i < modes.length; i++) {
            CurrencyMode m = modes[i];
            float bx = this.x + i * (btnW + gap);
            boolean sel = this.mode == m;
            boolean hov = this.enabled && mx >= bx && mx < bx + btnW && my >= rowY && my < rowY + MODE_H;
            int bg = sel ? Draw.alpha(IOStyle.BLUE, 0.22F)
                    : (hov ? IOStyle.ROW_HOVER : IOStyle.GRAY_BTN);
            int border = sel ? IOStyle.BLUE : (hov ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
            int textCol = sel ? IOStyle.TEXT : (hov ? IOStyle.TEXT : IOStyle.TEXT_2);
            Draw.fill(c, bx, rowY, btnW, MODE_H, 3.0F, bg);
            Draw.stroke(c, bx, rowY, btnW, MODE_H, 3.0F, sel ? 1.0F : 0.6F, border);
            Text.drawLine(c, modeLabel(m), sel ? AtomUi.BOLD : AtomUi.REGULAR,
                    modes.length >= 4 && this.width < 230.0F ? V2.FS_XS : V2.FS_S,
                    textCol, bx + 2.0F, rowY + MODE_H * 0.5F, btnW - 4.0F, Text.Align.CENTER);
            if (this.enabled) {
                this.hits.add(new PillRect(bx, rowY, btnW, MODE_H, () -> selectMode(m)));
            }
        }
    }

    private void paintTierStrip(Canvas c, @Nullable ItemOverlay items, float mx, float my, float rowY) {
        this.stripX = this.x;
        this.stripY = rowY;
        this.stripW = this.width;
        this.stripH = STRIP_H;

        Draw.fill(c, this.stripX, this.stripY, this.stripW, this.stripH, 3.0F, IOStyle.FIELD_BG);
        Draw.stroke(c, this.stripX, this.stripY, this.stripW, this.stripH, 3.0F, 0.7F, IOStyle.BORDER_DIM);

        List<PhysTierView> tiers = ClientHomeCurrencyState.physTiers;
        if (tiers.isEmpty()) {
            this.tierMaxScrollX = 0.0F;
            this.tierScrollX = 0.0F;
            Text.drawLine(c, tr("gui.module_shop.price_editor.no_tiers", "请先在实体货币配置中添加可用档位"),
                    AtomUi.REGULAR, V2.FS_S, IOStyle.ORANGE,
                    this.stripX + 6.0F, this.stripY + this.stripH * 0.5F, this.stripW - 12.0F, Text.Align.CENTER);
            return;
        }

        float pad = 2.5F;
        float gap = 3.0F;
        float innerW = Math.max(20.0F, this.stripW - pad * 2.0F);
        float[] widths = new float[tiers.size()];
        float naturalSum = 0.0F;
        for (int i = 0; i < tiers.size(); i++) {
            PhysTierView tv = tiers.get(i);
            float nameW = Text.width(tv.displayName(), AtomUi.BOLD, V2.FS_S);
            float countW = Text.width("×" + tv.ownedCount, AtomUi.REGULAR, V2.FS_XS);
            widths[i] = Math.max(54.0F, Math.min(96.0F, 20.0F + Math.max(nameW, countW)));
            naturalSum += widths[i] + (i > 0 ? gap : 0.0F);
        }
        // 当所有实体币卡片总宽小于内宽时，均分展开铺满整行；溢出时允许横向滚轮翻看
        if (naturalSum < innerW && tiers.size() <= 5) {
            float extraEach = (innerW - naturalSum) / tiers.size();
            for (int i = 0; i < widths.length; i++) {
                widths[i] += extraEach;
            }
            naturalSum = innerW;
        }

        this.tierMaxScrollX = Math.max(0.0F, naturalSum - innerW);
        this.tierScrollX = Mth.clamp(this.tierScrollX, 0.0F, this.tierMaxScrollX);

        float pillH = this.stripH - pad * 2.0F;
        float py = this.stripY + pad;

        int saved = c.save();
        Draw.clip(c, this.stripX + pad, this.stripY + 1.0F, innerW, this.stripH - 2.0F);
        if (items != null) {
            items.pushClip(this.stripX + pad, this.stripY + 1.0F, innerW, this.stripH - 2.0F);
        }

        float cursorX = this.stripX + pad - this.tierScrollX;
        for (int i = 0; i < tiers.size(); i++) {
            PhysTierView tv = tiers.get(i);
            float pw = widths[i];
            if (cursorX + pw >= this.stripX + pad && cursorX <= this.stripX + pad + innerW) {
                boolean sel = tierMatches(tv, this.selectedTierKey);
                boolean hov = this.enabled && mx >= Math.max(this.stripX + pad, cursorX)
                        && mx < Math.min(this.stripX + pad + innerW, cursorX + pw)
                        && my >= py && my < py + pillH;
                int bg = sel ? Draw.alpha(IOStyle.BLUE, 0.18F)
                        : (hov ? IOStyle.ROW_HOVER : Draw.alpha(IOStyle.CARD, 0.9F));
                int border = sel ? IOStyle.BLUE : (hov ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                Draw.fill(c, cursorX, py, pw, pillH, 2.5F, bg);
                Draw.stroke(c, cursorX, py, pw, pillH, 2.5F, sel ? 1.0F : 0.6F, border);

                if (items != null && !tv.itemStack.isEmpty()) {
                    items.addNoHover(tv.itemStack.copyWithCount(1), cursorX + 3.0F, py + (pillH - 12.0F) * 0.5F,
                            12.0F, false);
                }
                float tx = cursorX + 17.0F;
                float tw = Math.max(8.0F, pw - 19.0F);
                Text.drawLine(c, tv.displayName(), sel ? AtomUi.BOLD : AtomUi.REGULAR, V2.FS_S,
                        sel ? IOStyle.TEXT : IOStyle.TEXT_2, tx, py + 6.0F, tw, Text.Align.LEFT);
                Text.drawLine(c, "×" + tv.ownedCount, AtomUi.REGULAR, V2.FS_XS,
                        tv.ownedCount > 0 ? IOStyle.GREEN : IOStyle.TEXT_3,
                        tx, py + 14.0F, tw, Text.Align.LEFT);

                if (hov) {
                    this.hoveredTooltip = Component.literal(tv.displayName() + " · "
                            + tr("gui.module_shop.phys_ex.unit_val", "单枚面值 %s", ClientShopData.money(tv.value))
                            + " · " + tr("gui.module_shop.phys_ex.owned_tip", "持有 %s", tv.ownedCount));
                }
                if (this.enabled) {
                    String chosenKey = tv.tierKey();
                    this.stripHits.add(new PillRect(cursorX, py, pw, pillH, () -> selectTier(chosenKey)));
                }
            }
            cursorX += pw + gap;
        }

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(saved);

        // 溢出横向滚动提示条
        if (this.tierMaxScrollX > 0.5F) {
            float barW = innerW * Math.min(1.0F, innerW / Math.max(innerW + 1.0F, naturalSum));
            float barX = this.stripX + pad + (innerW - barW) * (this.tierScrollX / this.tierMaxScrollX);
            Draw.fill(c, barX, this.stripY + this.stripH - 2.0F, Math.max(12.0F, barW), 1.5F, 0.75F, IOStyle.BLUE);
        }
    }

    private void paintInputs(Canvas c, float mx, float my, float dt, float curY) {
        if (this.mode == CurrencyMode.STAR_ONLY) {
            V2.text(c, starFieldTitle(), V2.FS_XS, IOStyle.TEXT_2,
                    this.x, curY + 4.5F, this.width, Text.Align.LEFT);
            this.starField.render(c, mx, my, dt);
            return;
        }
        if (this.mode == CurrencyMode.PHYS_ONLY) {
            V2.text(c, physFieldTitle(), V2.FS_XS, IOStyle.TEXT_2,
                    this.x, curY + 4.5F, this.width, Text.Align.LEFT);
            this.physField.render(c, mx, my, dt);
            return;
        }
        if (this.width >= STACK_MIN_TWO_COL_W) {
            float gap = 6.0F;
            float leftW = (this.width - gap) * 0.5F;
            float rightW = this.width - gap - leftW;
            V2.text(c, starFieldTitle(), V2.FS_XS, IOStyle.TEXT_2, this.x, curY + 4.5F, leftW, Text.Align.LEFT);
            V2.text(c, physFieldTitle(), V2.FS_XS, IOStyle.TEXT_2,
                    this.x + leftW + gap, curY + 4.5F, rightW, Text.Align.LEFT);
            this.starField.render(c, mx, my, dt);
            this.physField.render(c, mx, my, dt);
        } else {
            V2.text(c, starFieldTitle(), V2.FS_XS, IOStyle.TEXT_2, this.x, curY + 4.5F, this.width, Text.Align.LEFT);
            this.starField.render(c, mx, my, dt);
            float y2 = curY + FIELD_LABEL_H + FIELD_H + 4.0F;
            V2.text(c, physFieldTitle(), V2.FS_XS, IOStyle.TEXT_2, this.x, y2 + 4.5F, this.width, Text.Align.LEFT);
            this.physField.render(c, mx, my, dt);
        }
    }

    // ------------------------------------------------------------------ 交互事件

    public boolean mouseClicked(double mx, double my, int button) {
        if (!this.visible || !this.enabled) {
            return false;
        }
        if (button == 0) {
            for (int i = this.hits.size() - 1; i >= 0; i--) {
                PillRect r = this.hits.get(i);
                if (r.contains(mx, my)) {
                    ClickSound.play();
                    r.action.run();
                    return true;
                }
            }
            if (this.stripW > 0.0F && mx >= this.stripX && mx < this.stripX + this.stripW
                    && my >= this.stripY && my < this.stripY + this.stripH) {
                for (int i = this.stripHits.size() - 1; i >= 0; i--) {
                    PillRect r = this.stripHits.get(i);
                    if (r.contains(mx, my)) {
                        ClickSound.play();
                        r.action.run();
                        return true;
                    }
                }
            }
        }
        boolean starHit = this.starField.isVisible() && this.starField.mouseClicked(mx, my, button);
        if (starHit) {
            this.physField.blur();
            return true;
        }
        boolean physHit = this.physField.isVisible() && this.physField.mouseClicked(mx, my, button);
        if (physHit) {
            this.starField.blur();
            return true;
        }
        if (this.starField.isFocused()) {
            this.starField.blur();
        }
        if (this.physField.isFocused()) {
            this.physField.blur();
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = false;
        if (this.starField.isVisible()) {
            handled |= this.starField.mouseReleased(mx, my, button);
        }
        if (this.physField.isVisible()) {
            handled |= this.physField.mouseReleased(mx, my, button);
        }
        return handled;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (this.starField.isVisible() && this.starField.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return this.physField.isVisible() && this.physField.mouseDragged(mx, my, button, dx, dy);
    }

    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!this.visible || this.stripW <= 0.0F || this.tierMaxScrollX <= 0.0F) {
            return false;
        }
        if (mx >= this.stripX && mx < this.stripX + this.stripW
                && my >= this.stripY && my < this.stripY + this.stripH) {
            this.tierScrollX = Mth.clamp(this.tierScrollX - (float) scrollY * 26.0F, 0.0F, this.tierMaxScrollX);
            return true;
        }
        return false;
    }

    public @Nullable Component hoveredTooltip() {
        return this.hoveredTooltip;
    }

    // ------------------------------------------------------------------ 取值与校验

    public CurrencyMode mode() {
        return clampMode(this.mode);
    }

    public String selectedTierKey() {
        ensureDefaultTierSelected();
        return this.selectedTierKey;
    }

    public @Nullable PhysTierView selectedTier() {
        return ClientHomeCurrencyState.findTier(selectedTierKey());
    }

    public long starValueOr(long fallback) {
        Long parsed = parseNonNegativeLong(this.starField.value());
        return parsed == null ? fallback : parsed;
    }

    public long physCountOr(long fallback) {
        Long parsed = parseNonNegativeLong(this.physField.value());
        return parsed == null ? fallback : parsed;
    }

    public @Nullable String validateError() {
        CurrencyMode m = clampMode(this.mode);
        if (m == CurrencyMode.STAR_ONLY) {
            Long star = parseNonNegativeLong(this.starField.value());
            if (star == null) {
                return tr("gui.module_shop.price_editor.err_star_invalid", "请输入合法的星币整数");
            }
            if (!this.allowZero && star <= 0L) {
                return tr("gui.module_shop.price_editor.err_star_pos", "星币价格必须大于 0");
            }
            return null;
        }
        if (!physSelectable()) {
            return tr("gui.module_shop.price_editor.err_phys_disabled", "实体货币未启用");
        }
        if (ClientHomeCurrencyState.physTiers.isEmpty()) {
            return tr("gui.module_shop.price_editor.no_tiers", "请先在实体货币配置中添加可用档位");
        }
        if (selectedTier() == null) {
            return tr("gui.module_shop.price_editor.err_pick_tier", "请选择一种实体货币档位");
        }
        Long phys = parseNonNegativeLong(this.physField.value());
        if (phys == null || phys <= 0L) {
            return tr("gui.module_shop.price_editor.err_phys_pos", "实体货币数量必须大于 0");
        }
        if (m == CurrencyMode.EITHER || m == CurrencyMode.BOTH) {
            Long star = parseNonNegativeLong(this.starField.value());
            if (star == null || star <= 0L) {
                return tr("gui.module_shop.price_editor.err_star_pos", "星币价格必须大于 0");
            }
        }
        return null;
    }

    public @Nullable PriceSpec buildSpec() {
        if (validateError() != null) {
            return null;
        }
        CurrencyMode m = clampMode(this.mode);
        if (m == CurrencyMode.STAR_ONLY) {
            Long star = parseNonNegativeLong(this.starField.value());
            return star == null ? null : PriceSpec.star(star);
        }
        PhysTierView tier = selectedTier();
        Long phys = parseNonNegativeLong(this.physField.value());
        if (tier == null || phys == null || phys <= 0L) {
            return null;
        }
        if (m == CurrencyMode.PHYS_ONLY) {
            return PriceSpec.phys(tier.tierKey(), phys);
        }
        Long star = parseNonNegativeLong(this.starField.value());
        if (star == null || star <= 0L) {
            return null;
        }
        return PriceSpec.of(m, star, tier.tierKey(), phys);
    }

    // ------------------------------------------------------------------ 内部辅助

    private boolean physSelectable() {
        return ClientHomeCurrencyState.physEnabled;
    }

    private CurrencyMode[] availableModes() {
        if (this.opMode) {
            return new CurrencyMode[]{CurrencyMode.STAR_ONLY, CurrencyMode.PHYS_ONLY,
                    CurrencyMode.EITHER, CurrencyMode.BOTH};
        }
        return new CurrencyMode[]{CurrencyMode.STAR_ONLY, CurrencyMode.PHYS_ONLY};
    }

    private CurrencyMode clampMode(@Nullable CurrencyMode raw) {
        CurrencyMode m = raw == null ? CurrencyMode.STAR_ONLY : raw;
        if (!physSelectable()) {
            return CurrencyMode.STAR_ONLY;
        }
        if (!this.opMode && (m == CurrencyMode.EITHER || m == CurrencyMode.BOTH)) {
            return m == CurrencyMode.BOTH && m.supportsPhys() ? CurrencyMode.PHYS_ONLY : CurrencyMode.STAR_ONLY;
        }
        return m;
    }

    private void selectMode(CurrencyMode next) {
        CurrencyMode clamped = clampMode(next);
        if (this.mode == clamped) {
            // 再次点击已选中的账本（如默认就是「仅星币」）：直接把焦点交给对应输入框，方便立刻改数字。
            focusFieldFor(clamped);
            return;
        }
        this.mode = clamped;
        ensureDefaultTierSelected();
        layoutFields();
        focusFieldFor(clamped);
        notifyChanged();
    }

    private void focusFieldFor(CurrencyMode m) {
        if (m.supportsStar() && this.starField.isVisible()) {
            this.starField.focus();
            this.physField.blur();
        } else if (m.supportsPhys() && this.physField.isVisible()) {
            this.physField.focus();
            this.starField.blur();
        }
    }

    private void selectTier(String key) {
        this.selectedTierKey = key == null ? "" : key;
        if (this.physField.isVisible()) {
            this.physField.focus();
            this.starField.blur();
        }
        notifyChanged();
    }

    private void ensureDefaultTierSelected() {
        List<PhysTierView> tiers = ClientHomeCurrencyState.physTiers;
        if (tiers.isEmpty()) {
            this.selectedTierKey = "";
            return;
        }
        if (ClientHomeCurrencyState.findTier(this.selectedTierKey) == null) {
            this.selectedTierKey = tiers.get(0).tierKey();
        }
    }

    private String resolveMatchingTierKey(@Nullable String rawKey) {
        PhysTierView match = ClientHomeCurrencyState.findTier(rawKey);
        if (match != null) {
            return match.tierKey();
        }
        List<PhysTierView> tiers = ClientHomeCurrencyState.physTiers;
        return tiers.isEmpty() ? "" : tiers.get(0).tierKey();
    }

    private static boolean tierMatches(PhysTierView tv, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return key.equals(tv.tierKey())
                || key.equals(tv.id)
                || key.equalsIgnoreCase(tv.uniqueItemKey())
                || (!key.contains("|") && key.equalsIgnoreCase(tv.itemId));
    }

    private String starFieldTitle() {
        String unit = ClientShopData.currencyName();
        return unit.isEmpty()
                ? tr("gui.module_shop.price_editor.star_lbl", "星币价格")
                : tr("gui.module_shop.price_editor.star_lbl_unit", "星币价格（%s）", unit);
    }

    private String physFieldTitle() {
        PhysTierView tier = selectedTier();
        if (tier != null) {
            return tr("gui.module_shop.price_editor.phys_lbl_tier", "%s 数量", tier.displayName());
        }
        return tr("gui.module_shop.price_editor.phys_lbl", "实体货币数量");
    }

    private static String modeLabel(CurrencyMode mode) {
        return switch (mode) {
            case STAR_ONLY -> tr("gui.module_shop.price_editor.mode_star", "仅星币");
            case PHYS_ONLY -> tr("gui.module_shop.price_editor.mode_phys", "仅实体币");
            case EITHER -> tr("gui.module_shop.price_editor.mode_either", "二选一双通道");
            case BOTH -> tr("gui.module_shop.price_editor.mode_both", "组合同付");
        };
    }

    private void notifyChanged() {
        if (!this.syncing && this.changeListener != null) {
            this.changeListener.run();
        }
    }

    private static @Nullable Long parseNonNegativeLong(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            long val = Long.parseLong(trimmed);
            return val < 0L ? null : val;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String tr(String key, String fallback, Object... args) {
        return ClientHomeCurrencyState.tr(key, fallback, args);
    }
}
