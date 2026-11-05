package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.client.ui.widget.UiComponent;
import com.atom.lib.text.TextLayout;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 次级界面（确认框、输入框、账单、交易……）的 Skija 底座。
 *
 * <h2>新旧并存</h2>
 * 每个次级界面在同一个类里同时保留旧绘制：AtomLib 可用时走 Skija（{@link #initSkia} / {@link #paint}），
 * 不可用时（非 Windows 等）完全走原来的代码。子类原有的 {@code init / render / mouseXxx / keyPressed}
 * 覆写只需在第一行加一句守卫：
 * <pre>
 *   protected void init() { if (skiaInit()) return; ...旧代码... }
 *   public void render(...) { if (skiaRender(g, mx, my, pt)) return; ...旧代码... }
 *   public boolean mouseClicked(...) { if (skia()) return skiaClicked(mx, my, b); ...旧代码... }
 * </pre>
 * 没有覆写的方法由本类自己分流。
 *
 * <h2>即时模式控件</h2>
 * {@link #button}、{@link #hit}、{@link #hover} 在 {@link #paint} 里边画边登记，点击判定用上一帧登记的区域；
 * 按钮的外观与动画复用 {@link Button}（按 key 缓存实例）。输入框用 {@link SkiaTextField}，
 * 在 {@link #initSkia} 里用 {@link #field} 创建。
 */
public abstract class SkiaDialog extends Screen {
    private static final float OPEN_MS = 160.0F;
    private static final float SCALE_MS = 260.0F;

    /** 弹窗标准尺寸：标题区高度、内边距。 */
    protected static final float HEADER_H = 26.0F;
    protected static final float PAD = 12.0F;

    protected final ItemOverlay items = new ItemOverlay();
    private final List<UiComponent> comps = new ArrayList<>();
    private final Map<String, Button> buttonCache = new HashMap<>();
    private final List<Button> frameButtons = new ArrayList<>();
    private final Map<Button, float[]> buttonClip = new IdentityHashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> frameHits = new ArrayList<>();
    private final Map<String, Float> anims = new HashMap<>();
    private final List<float[]> hitClips = new ArrayList<>();

    private boolean skia;
    private long openNanos;
    private long lastNanos;
    private List<Component> pendingTip;

    /** 本帧鼠标位置与时长（paint 期间有效）。 */
    protected float mouseX;
    protected float mouseY;
    protected float dtMs;

    private record Hit(float x, float y, float w, float h, float[] clip, Runnable left, Runnable right) {
        boolean contains(double mx, double my) {
            if (mx < x || mx >= x + w || my < y || my >= y + h) {
                return false;
            }
            return clip == null || (mx >= clip[0] && mx < clip[0] + clip[2] && my >= clip[1] && my < clip[1] + clip[3]);
        }
    }

    protected SkiaDialog(Component title) {
        super(title);
    }

    /** 当前是否走 Skija 绘制（init 时决定）。 */
    protected final boolean skia() {
        return this.skia;
    }

    // ------------------------------------------------------------------ 子类实现

    /** Skija 模式下的初始化（创建输入框等）；旧控件不要在这里加。 */
    protected void initSkia() {
    }

    /** Skija 模式下的绘制：面板、文字、即时控件都在这里画。 */
    protected abstract void paint(Canvas canvas);

    /** 组件（输入框）之后再画的内容，例如下拉菜单。 */
    protected void paintOver(Canvas canvas) {
    }

    /** 即时控件之外的点击（返回 true 表示已处理）。 */
    protected boolean onClick(double mx, double my, int button) {
        return false;
    }

    /** 滚轮（返回 true 表示已处理）。 */
    protected boolean onScroll(double mx, double my, double amount) {
        return false;
    }

    /** 回车：默认不处理。 */
    protected boolean onEnter() {
        return false;
    }

    // ------------------------------------------------------------------ 分流（子类守卫调用）

    /** 在子类 init() 第一行调用：Skija 模式下完成初始化并返回 true。 */
    protected final boolean skiaInit() {
        this.comps.clear();
        this.skia = AtomUi.available();
        if (this.openNanos == 0L) {
            this.openNanos = System.nanoTime();
        }
        if (this.skia) {
            initSkia();
        }
        return this.skia;
    }

    @Override
    protected void init() {
        skiaInit();
    }

    /** 在子类 render() 第一行调用：Skija 模式下完成整帧绘制并返回 true。 */
    protected final boolean skiaRender(GuiGraphics g, int mx, int my, float partialTick) {
        if (!this.skia) {
            return false;
        }
        this.renderBackground(g, mx, my, partialTick);
        long now = System.nanoTime();
        this.dtMs = this.lastNanos == 0L ? 16.0F : Math.min(100.0F, (now - this.lastNanos) / 1_000_000.0F);
        this.lastNanos = now;
        this.mouseX = mx;
        this.mouseY = my;
        IOStyle.tickTheme();
        float openMs = (now - this.openNanos) / 1_000_000.0F;
        float open = Motion.easeOutCubic(Math.min(1.0F, openMs / OPEN_MS));
        // 打开时从 97% 轻微放大到 100%（带一点回弹），以屏幕中心为原点；物品层用同样的变换保持对齐
        float scale = 0.97F + 0.03F * Motion.easeOutBack(Math.min(1.0F, openMs / SCALE_MS));
        float pivotX = this.width / 2.0F;
        float pivotY = this.height / 2.0F;
        this.items.begin(mx, my, true);
        this.pendingTip = null;
        this.frameButtons.clear();
        this.buttonClip.clear();
        this.frameHits.clear();
        this.hitClips.clear();
        this.frameLists.clear();
        this.listSaves.clear();
        SkiaFrame.draw(g, canvas -> {
            int saved = Draw.beginOpacity(canvas, open);
            try {
                if (scale != 1.0F) {
                    canvas.translate(pivotX, pivotY);
                    canvas.scale(scale, scale);
                    canvas.translate(-pivotX, -pivotY);
                }
                paint(canvas);
                for (UiComponent comp : this.comps) {
                    comp.render(canvas, mx, my, this.dtMs);
                }
                paintOver(canvas);
            } finally {
                canvas.restoreToCount(saved);
            }
        });
        this.hits.clear();
        this.hits.addAll(this.frameHits);
        this.lists.clear();
        this.lists.addAll(this.frameLists);
        if (scale != 1.0F) {
            g.pose().pushPose();
            g.pose().translate(pivotX, pivotY, 0.0F);
            g.pose().scale(scale, scale, 1.0F);
            g.pose().translate(-pivotX, -pivotY, 0.0F);
            this.items.flush(g, this.font);
            g.pose().popPose();
        } else {
            this.items.flush(g, this.font);
        }
        if (!this.items.renderTooltip(g, this.font, mx, my) && this.pendingTip != null) {
            List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
            for (Component line : this.pendingTip) {
                lines.addAll(this.font.split(line, 220));
            }
            g.renderTooltip(this.font, lines, mx, my);
        }
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        if (skiaRender(g, mx, my, partialTick)) {
            return;
        }
        super.render(g, mx, my, partialTick);
    }

    /** Skija 模式下的点击分流（子类 mouseClicked 守卫里调用）。 */
    protected final boolean skiaClicked(double mx, double my, int button) {
        if (listPress(mx, my, button)) {
            return true;
        }
        for (int i = this.comps.size() - 1; i >= 0; i--) {
            if (this.comps.get(i).mouseClicked(mx, my, button)) {
                return true;
            }
        }
        for (int i = this.frameButtonsSnapshot().size() - 1; i >= 0; i--) {
            Button b = this.frameButtonsSnapshot().get(i);
            float[] clip = this.buttonClip.get(b);
            if (clip != null && !(mx >= clip[0] && mx < clip[0] + clip[2] && my >= clip[1] && my < clip[1] + clip[3])) {
                continue;
            }
            if (b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (!hit.contains(mx, my)) {
                continue;
            }
            Runnable action = button == 1 ? hit.right : button == 0 ? hit.left : null;
            if (action != null) {
                ClickSound.play();
                action.run();
                return true;
            }
        }
        return onClick(mx, my, button);
    }

    private List<Button> frameButtonsSnapshot() {
        return this.frameButtons;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (this.skia) {
            return skiaClicked(mx, my, button);
        }
        return super.mouseClicked(mx, my, button);
    }

    /** Skija 模式下的松开分流。 */
    protected final boolean skiaReleased(double mx, double my, int button) {
        boolean handled = false;
        if (this.draggingList != null) {
            this.draggingList.sc.releaseBar();
            this.draggingList = null;
            handled = true;
        }
        for (UiComponent comp : this.comps) {
            handled |= comp.mouseReleased(mx, my, button);
        }
        for (Button b : new ArrayList<>(this.frameButtons)) {
            handled |= b.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (this.skia) {
            return skiaReleased(mx, my, button);
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (this.skia) {
            if (this.draggingList != null) {
                this.draggingList.sc.dragBar(my, this.draggingList.h);
                return true;
            }
            for (UiComponent comp : this.comps) {
                if (comp.mouseDragged(mx, my, button, dx, dy)) {
                    return true;
                }
            }
            return false;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (this.skia) {
            for (UiComponent comp : this.comps) {
                if (comp.mouseScrolled(mx, my, scrollY)) {
                    return true;
                }
            }
            if (onScroll(mx, my, scrollY)) {
                return true;
            }
            for (int i = this.lists.size() - 1; i >= 0; i--) {
                if (this.lists.get(i).contains(mx, my)) {
                    this.lists.get(i).sc.wheel(scrollY);
                    return true;
                }
            }
            return false;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.skia && (keyCode == 257 || keyCode == 335) && onEnter()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ 组件

    /** 创建一个 Skija 输入框（只在 initSkia 里调用）。位置在 paint 里用 bounds 每帧设置。 */
    protected final SkiaTextField field(int maxLength, String placeholder) {
        SkiaTextField field = new SkiaTextField(this, this.font, maxLength).placeholder(placeholder);
        this.comps.add(field);
        this.addWidget(field.editBox());
        return field;
    }

    // ------------------------------------------------------------------ 即时控件

    /** 之后登记的按钮 / 点击区只在该矩形内有效（滚动列表用）；Skija 裁剪需调用方自己做。 */
    protected final void pushHitClip(float x, float y, float w, float h) {
        this.hitClips.add(new float[]{x, y, w, h});
    }

    protected final void popHitClip() {
        if (!this.hitClips.isEmpty()) {
            this.hitClips.remove(this.hitClips.size() - 1);
        }
    }

    private float[] currentClip() {
        return this.hitClips.isEmpty() ? null : this.hitClips.get(this.hitClips.size() - 1);
    }

    /** 画一个按钮并登记点击（松开时触发，带统一点击音）。 */
    protected final Button button(Canvas canvas, String key, String label, float x, float y, float w, float h,
                                  Button.Kind kind, boolean enabled, Runnable action) {
        Button b = this.buttonCache.computeIfAbsent(key, k -> new Button(kind, label, null));
        b.kind(kind).label(label).onClick(action);
        b.bounds(x, y, w, h);
        b.enabled(enabled);
        b.visible(true);
        b.render(canvas, this.mouseX, this.mouseY, this.dtMs);
        this.frameButtons.add(b);
        float[] clip = currentClip();
        if (clip != null) {
            this.buttonClip.put(b, clip);
        }
        return b;
    }

    /** 以 label + 位置作为 key 的便捷重载。 */
    protected final Button button(Canvas canvas, String label, float x, float y, float w, float h,
                                  Button.Kind kind, boolean enabled, Runnable action) {
        return button(canvas, label + "@" + Math.round(x) + "," + Math.round(y), label, x, y, w, h, kind, enabled,
                action);
    }

    /** 登记一块点击区（按下时触发；right 可为 null）。 */
    protected final void hit(float x, float y, float w, float h, Runnable left, Runnable right) {
        this.frameHits.add(new Hit(x, y, w, h, currentClip(), left, right));
    }

    /** 鼠标是否在矩形内（考虑当前点击裁剪区）。 */
    protected final boolean over(float x, float y, float w, float h) {
        if (this.mouseX < x || this.mouseX >= x + w || this.mouseY < y || this.mouseY >= y + h) {
            return false;
        }
        float[] clip = currentClip();
        return clip == null || (this.mouseX >= clip[0] && this.mouseX < clip[0] + clip[2]
                && this.mouseY >= clip[1] && this.mouseY < clip[1] + clip[3]);
    }

    /** 按 key 记忆的 0..1 动画值（悬停、选中等），每帧趋近 on ? 1 : 0。 */
    protected final float anim(String key, boolean on, float tauMs) {
        float cur = this.anims.getOrDefault(key, on ? 0.0F : 0.0F);
        float next = Motion.approach(cur, on ? 1.0F : 0.0F, this.dtMs, tauMs);
        this.anims.put(key, next);
        return next;
    }

    protected final float hover(String key, float x, float y, float w, float h) {
        boolean on = over(x, y, w, h);
        return anim("hover:" + key, on, on ? Motion.HOVER_MS : Motion.HOVER_OUT_MS);
    }

    /**
     * 选项块（品质、开关、二选一……）：选中时用 accent 描边 + 淡底，悬停边框点亮。点击区一并登记。
     */
    protected final void chip(Canvas canvas, String key, String label, float x, float y, float w, float h,
                              boolean selected, int accent, Runnable action) {
        float hv = hover("chip:" + key, x, y, w, h);
        float sel = anim("chipSel:" + key, selected, Motion.PRESS_MS);
        int fill = Draw.lerp(Draw.lerp(IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER, hv), Draw.alpha(accent, 0.22F), sel);
        int border = Draw.lerp(Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, hv), accent, sel);
        Draw.fill(canvas, x, y, w, h, 3.0F, fill);
        Draw.stroke(canvas, x, y, w, h, 3.0F, sel > 0.5F ? 1.2F : 1.0F, border);
        int textColor = Draw.lerp(IOStyle.TEXT_2, selected ? accent : IOStyle.TEXT, Math.max(hv, sel));
        Text.drawLine(canvas, label, AtomUi.REGULAR, Text.BODY, textColor, x + 3, y + h / 2.0F, w - 6,
                Text.Align.CENTER);
        hit(x, y, w, h, action, null);
    }

    /** 开关（胶囊滑块），点击区一并登记。尺寸 22×12。 */
    protected final void toggle(Canvas canvas, String key, float x, float y, boolean on, Runnable action) {
        float w = 22.0F;
        float h = 12.0F;
        float t = anim("toggle:" + key, on, 70.0F);
        float hv = hover("toggle:" + key, x, y, w, h);
        int track = Draw.lerp(Draw.lerp(IOStyle.TOGGLE_OFF, IOStyle.GRAY_BTN_HOVER, hv), IOStyle.BLUE, t);
        Draw.fill(canvas, x, y, w, h, h / 2.0F, track);
        Draw.stroke(canvas, x, y, w, h, h / 2.0F, 1.0F, Draw.lerp(IOStyle.TOGGLE_OFF_RING, IOStyle.BLUE, t));
        float knob = h - 4.0F;
        // 滑块移动途中横向拉长一点（像被拖着走），到位后恢复圆形
        float stretch = 4.0F * t * (1.0F - t) * 2.0F;
        float kx = x + 2.0F + (w - 4.0F - knob) * t - stretch * t;
        Draw.shadow(canvas, kx, y + 2.0F, knob + stretch, knob, knob / 2.0F, 1.5F, 0.5F, IOStyle.SHADOW_2);
        Draw.fill(canvas, kx, y + 2.0F, knob + stretch, knob, knob / 2.0F, IOStyle.KNOB);
        hit(x - 2, y - 2, w + 4, h + 4, action, null);
    }

    // ------------------------------------------------------------------ 滚动列表

    private record ListArea(cn.autoforged.module_shop.client.ui.widget.ScrollController sc,
                            float x, float y, float w, float h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<ListArea> lists = new ArrayList<>();
    private final List<ListArea> frameLists = new ArrayList<>();
    private final java.util.ArrayDeque<int[]> listSaves = new java.util.ArrayDeque<>();
    private ListArea draggingList;

    /**
     * 开始一个平滑滚动列表：裁剪画布、物品与点击区到列表矩形内，返回内容原点 y（= y - 滚动量）。
     * 必须与 {@link #endList} 配对。滚轮、滚动条拖拽由本类处理。
     */
    protected final float beginList(Canvas canvas, cn.autoforged.module_shop.client.ui.widget.ScrollController sc,
                                    float x, float y, float w, float h, float contentH) {
        sc.setContent(contentH, h);
        sc.update();
        ListArea area = new ListArea(sc, x, y, w, h);
        this.frameLists.add(area);
        int saved = canvas.save();
        Draw.clip(canvas, x, y, w, h);
        this.listSaves.push(new int[]{saved});
        pushHitClip(x, y, w, h);
        this.items.pushClip(x, y, w, h);
        return y - sc.offset();
    }

    protected final void endList(Canvas canvas) {
        int[] saved = this.listSaves.poll();
        if (saved != null) {
            canvas.restoreToCount(saved[0]);
        }
        popHitClip();
        this.items.popClip();
        ListArea area = this.frameLists.get(this.frameLists.size() - 1);
        area.sc.drawBar(canvas, area.x, area.y, area.w, area.h, this.mouseX, this.mouseY, this.dtMs);
    }

    private boolean listPress(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        for (ListArea area : this.lists) {
            if (area.sc.pressBar(mx, my, area.x, area.y, area.w, area.h)) {
                this.draggingList = area;
                return true;
            }
        }
        return false;
    }

    /** 物品（原版绘制，Skija 之后统一画）。 */
    protected final void item(ItemStack stack, float x, float y) {
        this.items.add(stack, x, y);
    }

    /** 本帧要显示的文字提示（物品 tooltip 优先）。 */
    protected final void tip(Component... lines) {
        this.pendingTip = List.of(lines);
    }

    protected final void tip(List<Component> lines) {
        this.pendingTip = lines;
    }

    // ------------------------------------------------------------------ 绘制小工具

    /** 标准弹窗面板：底色 + 边框 + 标题（粗体居中）+ 标题下分隔线。 */
    protected final void panel(Canvas canvas, float x, float y, float w, float h, String title) {
        Draw.shadow(canvas, x, y, w, h, 0.0F, 10.0F, 3.0F, IOStyle.SHADOW_TINT);
        Draw.fill(canvas, x, y, w, h, 0.0F, IOStyle.SHEET);
        Draw.stroke(canvas, x, y, w, h, 0.0F, 1.0F, IOStyle.BORDER);
        if (title != null) {
            Text.drawLine(canvas, title, AtomUi.BOLD, Text.TITLE, IOStyle.TEXT, x + PAD, y + HEADER_H / 2.0F,
                    w - PAD * 2, Text.Align.CENTER);
            Draw.fill(canvas, x + 1, y + HEADER_H, w - 2, 1.0F, 0.0F, IOStyle.BORDER_DIM);
        }
    }

    /** 容器槽位底框：(x, y) 为物品左上角（与原版 Slot.x/y 一致），框画在外扩 1px 的 18×18 上。 */
    public static void slotFrame(Canvas canvas, float x, float y) {
        Draw.fill(canvas, x - 1, y - 1, 18, 18, 2.0F, IOStyle.FIELD_BG);
        Draw.stroke(canvas, x - 1, y - 1, 18, 18, 2.0F, 1.0F, IOStyle.BORDER_DIM);
    }

    /** 玩家背包 3×9 + 快捷栏（快捷栏下移 4px），(x, y) 为第一格物品左上角。 */
    public static void playerSlots(Canvas canvas, float x, float y) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slotFrame(canvas, x + col * 18, y + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            slotFrame(canvas, x + col * 18, y + 58);
        }
    }

    /**
     * 无状态按钮外观（容器界面用：它们的点击判定沿用旧代码的矩形，这里只负责画）。
     * 配色与 {@link Button} 一致，悬停即时切换。
     */
    public static void flatButton(Canvas canvas, float x, float y, float w, float h, String label,
                                  Button.Kind kind, boolean enabled, double mx, double my) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int fill;
        int border;
        int textColor;
        if (!enabled) {
            fill = IOStyle.DISABLED_BG;
            border = IOStyle.BORDER_DIM;
            textColor = IOStyle.DISABLED_TEXT;
        } else if (kind == Button.Kind.ACCENT) {
            fill = hover ? IOStyle.BLUE_HOVER : IOStyle.BLUE;
            border = fill;
            textColor = 0xFFFFFFFF;
        } else if (kind == Button.Kind.DANGER) {
            fill = hover ? IOStyle.RED_TINT_HOVER : IOStyle.RED_TINT;
            border = hover ? IOStyle.RED : IOStyle.RED_TINT_HOVER;
            textColor = IOStyle.RED;
        } else {
            fill = hover ? IOStyle.GRAY_BTN_HOVER : IOStyle.GRAY_BTN;
            border = hover ? IOStyle.BORDER_HOVER : IOStyle.BORDER;
            textColor = IOStyle.TEXT;
        }
        Draw.fill(canvas, x, y, w, h, 3.0F, fill);
        Draw.stroke(canvas, x, y, w, h, 3.0F, 1.0F, border);
        Text.drawLine(canvas, label, AtomUi.REGULAR, Text.BODY, textColor, x + 3, y + h / 2.0F, w - 6,
                Text.Align.CENTER);
    }

    /** 输入框底（容器界面里的原版无边框 EditBox 画在它上面）。 */
    public static void fieldFrame(Canvas canvas, float x, float y, float w, float h, boolean focused) {
        if (focused) {
            Draw.stroke(canvas, x - 1.5F, y - 1.5F, w + 3, h + 3, 4.5F, 1.5F, Draw.alpha(IOStyle.FOCUS, 1.0F));
        }
        Draw.fill(canvas, x, y, w, h, 3.0F, IOStyle.FIELD_BG);
        Draw.stroke(canvas, x, y, w, h, 3.0F, 1.0F, focused ? IOStyle.BLUE : IOStyle.BORDER_DIM);
    }

    /** 容器界面里的原版无边框输入框：文字颜色跟随主题（日间主题下默认的浅灰字会看不见）。每帧调用。 */
    public static void themeBox(net.minecraft.client.gui.components.EditBox box) {
        if (box != null) {
            box.setTextColor(IOStyle.TEXT);
            box.setTextColorUneditable(IOStyle.TEXT_2);
        }
    }

    /** 不带标题的弹窗面板（容器界面用）。 */
    public static void bare(Canvas canvas, float x, float y, float w, float h) {
        IOStyle.tickTheme();
        Draw.shadow(canvas, x, y, w, h, 0.0F, 10.0F, 3.0F, IOStyle.SHADOW_TINT);
        Draw.fill(canvas, x, y, w, h, 0.0F, IOStyle.SHEET);
        Draw.stroke(canvas, x, y, w, h, 0.0F, 1.0F, IOStyle.BORDER);
    }

    /** 内容卡片（列表区、信息区的底）。 */
    protected static void card(Canvas canvas, float x, float y, float w, float h) {
        Draw.fill(canvas, x, y, w, h, 3.0F, IOStyle.CARD);
        Draw.stroke(canvas, x, y, w, h, 3.0F, 1.0F, IOStyle.BORDER_DIM);
    }

    protected static float text(Canvas canvas, String s, float x, float centerY, float maxW, int color) {
        return Text.drawLine(canvas, s, AtomUi.REGULAR, Text.BODY, color, x, centerY, maxW, Text.Align.LEFT);
    }

    protected static float small(Canvas canvas, String s, float x, float centerY, float maxW, int color) {
        return Text.drawLine(canvas, s, AtomUi.REGULAR, Text.SMALL, color, x, centerY, maxW, Text.Align.LEFT);
    }

    protected static float center(Canvas canvas, String s, float x, float centerY, float w, int color) {
        return Text.drawLine(canvas, s, AtomUi.REGULAR, Text.BODY, color, x, centerY, w, Text.Align.CENTER);
    }

    protected static float right(Canvas canvas, String s, float x, float centerY, float w, int color) {
        return Text.drawLine(canvas, s, AtomUi.REGULAR, Text.BODY, color, x, centerY, w, Text.Align.RIGHT);
    }

    /** 自动换行的段落，返回占用高度。 */
    protected static float paragraph(Canvas canvas, String s, float x, float y, float w, float size, int color,
                                     int maxLines, boolean centered) {
        if (s == null || s.isEmpty()) {
            return 0.0F;
        }
        TextLayout layout = Text.wrapped(s, AtomUi.REGULAR, size, color, w, maxLines);
        float lw = layout.size().width();
        Text.draw(canvas, layout, centered ? x + (w - lw) / 2.0F : x, y);
        return layout.size().height();
    }

    protected static float paragraphHeight(String s, float w, float size, int maxLines) {
        if (s == null || s.isEmpty()) {
            return 0.0F;
        }
        return Text.wrapped(s, AtomUi.REGULAR, size, 0xFFFFFFFF, w, maxLines).size().height();
    }
}
