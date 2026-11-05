package cn.autoforged.module_shop.client.ui.demo;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.JeiHoverSource;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.client.ui.widget.UiComponent;
import com.atom.lib.design.atomchat.SkiaDraw;
import com.atom.lib.text.TextLayout;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * 迁移演示页：验证 AtomLib 渲染链路与基础组件在游戏内的表现。
 *
 * <p>打开方式：客户端命令 {@code /moduleshop_atomlib_demo}。
 * 不连接任何商店数据，纯展示；正式界面迁移完成后删除。
 */
public final class AtomDemoScreen extends Screen implements JeiHoverSource {
    private static final float HEADER_H = 26.0F;
    private static final float SIDEBAR_W = 92.0F;
    private static final float PAD = 8.0F;
    private static final float ROW_H = 26.0F;
    private static final float ROW_GAP = 3.0F;
    private static final int ROWS = 40;
    private static final float CELL = 22.0F;
    private static final float CELL_GAP = 2.0F;
    private static final float SEARCH_H = 18.0F;

    private static final String[] TAB_NAMES = {"卡片列表", "物品与输入", "按钮", "文字", "运行信息"};
    private static final int TAB_LIST = 0;
    private static final int TAB_ITEMS = 1;
    private static final int TAB_BUTTONS = 2;
    private static final int TAB_TEXT = 3;

    private final Motion.Clock clock = new Motion.Clock();
    private final ScrollController listScroll = new ScrollController();
    private final ScrollController itemScroll = new ScrollController();
    private final List<Button> tabs = new ArrayList<>();
    private final List<Button> demoButtons = new ArrayList<>();
    private final Button closeButton = new Button(Button.Kind.NORMAL, "×", this::onClose);
    private final float[] rowHover = new float[ROWS];

    private final ItemOverlay pageItems = new ItemOverlay();
    private final ItemOverlay modalItems = new ItemOverlay();
    private final List<ItemStack> allItems = new ArrayList<>();
    private final List<ItemStack> filtered = new ArrayList<>();
    private SkiaTextField search;

    // 弹窗（验证“弹窗盖住页面物品”的层序）
    private ItemStack modalStack = ItemStack.EMPTY;
    /** 弹窗正在显示的物品：关闭后保留到退场动画结束，避免内容先消失。 */
    private ItemStack modalShown = ItemStack.EMPTY;
    private float modalT;
    private static final float MODAL_W = 200.0F;
    private static final float MODAL_H = 96.0F;
    private static final float MODAL_SLIDE = 8.0F;
    private final Button modalConfirm = new Button(Button.Kind.ACCENT, "确认购买", this::closeModal);
    private final Button modalCancel = new Button(Button.Kind.NORMAL, "取消", this::closeModal);

    private int tab;
    private int selectedRow = -1;
    private int clicks;
    private float frameMs;

    // 每帧布局结果（GUI 像素）
    private float px;
    private float py;
    private float pw;
    private float ph;

    public AtomDemoScreen() {
        super(Component.literal("AtomLib Demo"));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int index = i;
            tabs.add(new Button(Button.Kind.TAB, TAB_NAMES[i], () -> selectTab(index)));
        }
        demoButtons.add(new Button(Button.Kind.NORMAL, "普通按钮", () -> clicks++));
        demoButtons.add(new Button(Button.Kind.ACCENT, "主要操作", () -> clicks++));
        demoButtons.add(new Button(Button.Kind.DANGER, "危险操作", () -> clicks++));
        demoButtons.add((Button) new Button(Button.Kind.NORMAL, "已禁用", () -> clicks++).enabled(false));
        closeButton.fontSize(Text.TITLE);
        buildItemList();
    }

    @Override
    protected void init() {
        if (search == null) {
            search = new SkiaTextField(this, font, 64)
                    .placeholder("搜索物品（名称或 ID），支持中文输入法")
                    .onChange(text -> applyFilter());
        }
        // init() 会清空子组件，每次都要重新挂上隐藏的 EditBox
        addWidget(search.editBox());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void selectTab(int index) {
        tab = index;
        if (search != null && index != TAB_ITEMS) {
            search.blur();
        }
    }

    // ------------------------------------------------------------------ 物品数据

    private void buildItemList() {
        int i = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            // 一部分物品带数量、一部分工具带耐久损耗，用来检查装饰层（数量、耐久条）
            if (stack.isDamageableItem()) {
                stack.setDamageValue(stack.getMaxDamage() / 3 * (1 + i % 2));
            } else if (i % 4 == 0 && stack.getMaxStackSize() > 1) {
                stack.setCount(Math.min(stack.getMaxStackSize(), 1 + (i * 7) % 64));
            }
            allItems.add(stack);
            i++;
        }
        filtered.addAll(allItems);
    }

    private void applyFilter() {
        String q = search == null ? "" : search.value().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (ItemStack stack : allItems) {
            if (q.isEmpty()
                    || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(q)) {
                filtered.add(stack);
            }
        }
        itemScroll.scrollTo(0, false);
    }

    // ------------------------------------------------------------------ 布局

    private void layout() {
        pw = Math.min(400.0F, width - 24.0F);
        ph = Math.min(250.0F, height - 24.0F);
        px = (width - pw) / 2.0F;
        py = (height - ph) / 2.0F;

        closeButton.bounds(px + pw - 22.0F, py + 5.0F, 16.0F, 16.0F);
        float ty = py + HEADER_H + PAD;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).bounds(px + PAD, ty, SIDEBAR_W - PAD * 2, 20.0F).visible(true);
            tabs.get(i).selected(i == tab);
            ty += 23.0F;
        }
        float cx = contentX();
        float cy = contentY();
        for (int i = 0; i < demoButtons.size(); i++) {
            demoButtons.get(i).bounds(cx, cy + 22.0F + i * 26.0F, 110.0F, 20.0F);
        }
        listScroll.setContent(ROWS * (ROW_H + ROW_GAP) - ROW_GAP, contentH());

        search.bounds(cx, cy, contentW(), SEARCH_H).visible(tab == TAB_ITEMS);
        int cols = gridCols();
        int rows = (filtered.size() + cols - 1) / cols;
        itemScroll.setContent(rows * (CELL + CELL_GAP), gridH());

        // 弹窗内的按钮与卡片使用同一个位置（含入场位移），命中区域与画面一致
        float mx = modalX();
        float my = modalY();
        modalCancel.bounds(mx + MODAL_W - 12.0F - 70.0F - 6.0F - 70.0F, my + MODAL_H - 30.0F, 70.0F, 20.0F);
        modalConfirm.bounds(mx + MODAL_W - 12.0F - 70.0F, my + MODAL_H - 30.0F, 70.0F, 20.0F);
    }

    private float modalX() {
        return (width - MODAL_W) / 2.0F;
    }

    /** 弹窗顶部 y：入场时从下方 MODAL_SLIDE 处滑上来，退场时反向。 */
    private float modalY() {
        return (height - MODAL_H) / 2.0F + (1.0F - Motion.easeOutCubic(modalT)) * MODAL_SLIDE;
    }

    private float contentX() {
        return px + SIDEBAR_W + PAD;
    }

    private float contentY() {
        return py + HEADER_H + PAD;
    }

    private float contentW() {
        return pw - SIDEBAR_W - PAD * 2;
    }

    private float contentH() {
        return ph - HEADER_H - PAD * 2 - 12.0F;
    }

    private float gridY() {
        return contentY() + SEARCH_H + 6.0F;
    }

    private float gridH() {
        return contentH() - SEARCH_H - 6.0F;
    }

    private int gridCols() {
        return Math.max(1, (int) ((contentW() - 8.0F + CELL_GAP) / (CELL + CELL_GAP)));
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        float dt = clock.tick();
        frameMs = frameMs == 0 ? dt : frameMs + (dt - frameMs) * 0.05F;

        if (!AtomUi.available()) {
            renderFallback(graphics);
            return;
        }
        boolean modal = !modalStack.isEmpty();
        modalT = Motion.approach(modalT, modal ? 1.0F : 0.0F, dt, Motion.PRESS_MS);
        if (!modal && modalT <= 0.0F) {
            modalShown = ItemStack.EMPTY; // 退场动画结束才清掉显示内容
        }
        layout(); // 在 modalT 更新之后布局：弹窗按钮与卡片同一帧、同一位置
        listScroll.update();
        itemScroll.update();
        // 弹窗打开时，页面不响应悬停（鼠标坐标传到画面外）
        float pageMx = modal ? -1000.0F : mouseX;
        float pageMy = modal ? -1000.0F : mouseY;

        // 层 1：Skija 页面 → 页面物品
        pageItems.begin(mouseX, mouseY, !modal);
        SkiaFrame.draw(graphics, canvas -> paint(canvas, pageMx, pageMy, dt));
        pageItems.flush(graphics, font);

        // 层 2：Skija 弹窗 → 弹窗物品（弹窗会盖住页面物品）
        modalItems.begin(mouseX, mouseY, modal);
        if (modalT > 0.01F) {
            SkiaFrame.draw(graphics, canvas -> paintModal(canvas, mouseX, mouseY, dt));
            modalItems.flush(graphics, font);
        }

        // 层 3：原版 tooltip
        if (modal) {
            modalItems.renderTooltip(graphics, font, mouseX, mouseY);
        } else {
            pageItems.renderTooltip(graphics, font, mouseX, mouseY);
        }
    }

    private void paint(Canvas canvas, float mx, float my, float dt) {
        // 面板：投影 + 底色 + 1px 边框
        SkiaDraw.drawRoundedShadow(canvas, px, py, pw, ph, 4.0F, 10.0F, IOStyle.SHADOW_TINT);
        Draw.fill(canvas, px, py, pw, ph, 4.0F, IOStyle.SHEET);
        Draw.fill(canvas, px, py + HEADER_H, SIDEBAR_W, ph - HEADER_H, 0.0F, IOStyle.SIDEBAR);
        Draw.hairline(canvas, px, py + HEADER_H, pw, IOStyle.HAIR);
        Draw.fill(canvas, px + SIDEBAR_W, py + HEADER_H, 1.0F, ph - HEADER_H, 0.0F, IOStyle.HAIR);
        Draw.stroke(canvas, px, py, pw, ph, 4.0F, 1.0F, IOStyle.BORDER);

        // 标题栏
        Text.drawLine(canvas, "AtomLib 渲染演示", AtomUi.BOLD, Text.TITLE, IOStyle.TEXT,
                px + PAD, py + HEADER_H / 2.0F, pw - 120.0F, Text.Align.LEFT);
        Text.drawLine(canvas, "阶段 2 · 物品图标 + 输入框", AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                px + pw - 30.0F - 130.0F, py + HEADER_H / 2.0F, 130.0F, Text.Align.RIGHT);
        closeButton.render(canvas, mx, my, dt);

        for (Button b : tabs) {
            b.render(canvas, mx, my, dt);
        }

        switch (tab) {
            case TAB_LIST -> paintList(canvas, mx, my, dt);
            case TAB_ITEMS -> paintItems(canvas, mx, my, dt);
            case TAB_BUTTONS -> paintButtons(canvas, mx, my, dt);
            case TAB_TEXT -> paintText(canvas);
            default -> paintInfo(canvas);
        }

        String hint = tab == TAB_ITEMS ? "悬停看提示 · 装了 JEI 可按 R/U · 点击物品打开弹窗"
                : "滚轮滚动 · 拖动滚动条 · Esc 关闭";
        Text.drawLine(canvas, hint, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                contentX(), py + ph - 9.0F, contentW(), Text.Align.RIGHT);
    }

    private void paintList(Canvas canvas, float mx, float my, float dt) {
        float x = contentX();
        float y = contentY();
        float w = contentW();
        float h = contentH();
        boolean inside = mx >= x && mx < x + w && my >= y && my < y + h;
        int saved = canvas.save();
        pageItems.pushClip(x, y, w, h);
        try {
            Draw.clip(canvas, x, y, w, h);
            float off = listScroll.offset();
            int first = Math.max(0, (int) (off / (ROW_H + ROW_GAP)));
            for (int i = first; i < ROWS; i++) {
                float ry = y + i * (ROW_H + ROW_GAP) - off;
                if (ry > y + h) {
                    break;
                }
                float rw = w - 8.0F; // 给滚动条留位置
                boolean hover = inside && !listScroll.isDragging() && mx < x + rw && my >= ry && my < ry + ROW_H;
                rowHover[i] = Motion.approach(rowHover[i], hover ? 1.0F : 0.0F, dt, Motion.HOVER_MS);
                boolean sel = i == selectedRow;
                int fill = sel ? IOStyle.BLUE_TINT : Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, rowHover[i]);
                int border = sel ? IOStyle.BLUE : Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER, rowHover[i]);
                Draw.fill(canvas, x, ry, rw, ROW_H, 3.0F, fill);
                Draw.stroke(canvas, x, ry, rw, ROW_H, 3.0F, 1.0F, border);
                Draw.fill(canvas, x + 4.0F, ry + 4.0F, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
                Draw.stroke(canvas, x + 4.0F, ry + 4.0F, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
                if (!allItems.isEmpty()) {
                    pageItems.add(allItems.get((i * 37) % allItems.size()), x + 5.0F, ry + 5.0F);
                }
                Text.drawLine(canvas, "示例商品 #" + (i + 1) + (i % 7 == 3 ? "（名字特别长的商品会被省略号截断）" : ""),
                        AtomUi.REGULAR, Text.BODY, IOStyle.TEXT, x + 27.0F, ry + ROW_H / 2.0F, rw - 27.0F - 60.0F,
                        Text.Align.LEFT);
                Text.drawLine(canvas, (i + 1) * 15 + " 星币", AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                        x + rw - 62.0F, ry + ROW_H / 2.0F, 56.0F, Text.Align.RIGHT);
            }
        } finally {
            pageItems.popClip();
            canvas.restoreToCount(saved);
        }
        listScroll.drawBar(canvas, x, y, w, h, mx, my, dt);
        if (listScroll.hasMoreBelow()) {
            SkiaDraw.drawVerticalGradient(canvas, x, y + h - 10.0F, w - 8.0F, 10.0F, 0.0F,
                    Draw.alpha(IOStyle.SHEET, 0.0F), IOStyle.SHEET);
        }
    }

    private void paintItems(Canvas canvas, float mx, float my, float dt) {
        search.render(canvas, mx, my, dt);
        float x = contentX();
        float y = gridY();
        float w = contentW();
        float h = gridH();
        if (filtered.isEmpty()) {
            Text.drawLine(canvas, "没有找到「" + search.value() + "」相关的物品", AtomUi.REGULAR, Text.BODY,
                    IOStyle.TEXT_3, x, y + h / 2.0F, w, Text.Align.CENTER);
            return;
        }
        int cols = gridCols();
        int saved = canvas.save();
        pageItems.pushClip(x, y, w, h);
        try {
            Draw.clip(canvas, x, y, w, h);
            float off = itemScroll.offset();
            int firstRow = Math.max(0, (int) (off / (CELL + CELL_GAP)));
            for (int i = firstRow * cols; i < filtered.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                float cx = x + col * (CELL + CELL_GAP);
                float cy = y + row * (CELL + CELL_GAP) - off;
                if (cy > y + h) {
                    break;
                }
                boolean hover = mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL
                        && my >= y && my < y + h && !itemScroll.isDragging();
                Draw.fill(canvas, cx, cy, CELL, CELL, 2.0F, hover ? IOStyle.ROW_HOVER : IOStyle.SLOT_INNER);
                Draw.stroke(canvas, cx, cy, CELL, CELL, 2.0F, 1.0F, hover ? IOStyle.BORDER_HOVER : IOStyle.SLOT_BORDER);
                pageItems.add(filtered.get(i), cx + 3.0F, cy + 3.0F);
            }
        } finally {
            pageItems.popClip();
            canvas.restoreToCount(saved);
        }
        itemScroll.drawBar(canvas, x, y, w, h, mx, my, dt);
        Text.drawLine(canvas, "共 " + filtered.size() + " 个", AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                x, py + ph - 9.0F, 80.0F, Text.Align.LEFT);
    }

    private void paintModal(Canvas canvas, float mx, float my, float dt) {
        float a = modalT;
        // 遮罩单独淡入（铺满全屏）
        Draw.fill(canvas, 0, 0, width, height, 0, Draw.alpha(0x88000000, a));
        // 弹窗本体：整体一个透明度图层 + 同一个位移，卡片、文字、按钮完全同步
        float x = modalX();
        float y = modalY();
        int layer = Draw.beginOpacity(canvas, a);
        try {
            SkiaDraw.drawRoundedShadow(canvas, x, y, MODAL_W, MODAL_H, 4.0F, 12.0F, IOStyle.SHADOW_TINT);
            Draw.fill(canvas, x, y, MODAL_W, MODAL_H, 4.0F, IOStyle.CONTENT);
            Draw.stroke(canvas, x, y, MODAL_W, MODAL_H, 4.0F, 1.0F, IOStyle.BORDER);
            Text.drawLine(canvas, "购买确认", AtomUi.BOLD, Text.TITLE, IOStyle.TEXT,
                    x + 12.0F, y + 14.0F, MODAL_W - 24.0F, Text.Align.LEFT);
            if (!modalShown.isEmpty()) {
                Draw.fill(canvas, x + 12.0F, y + 28.0F, 22.0F, 22.0F, 2.0F, IOStyle.SLOT_INNER);
                Draw.stroke(canvas, x + 12.0F, y + 28.0F, 22.0F, 22.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
                Text.drawLine(canvas, modalShown.getHoverName().getString() + " × " + modalShown.getCount(),
                        AtomUi.REGULAR, Text.BODY, IOStyle.TEXT, x + 40.0F, y + 39.0F, MODAL_W - 52.0F,
                        Text.Align.LEFT);
                // 原版物品无法跟随 Skija 图层半透明，只在弹窗基本完全显示时出现，避免“物品先出现/后消失”
                if (a > 0.9F) {
                    modalItems.add(modalShown, x + 15.0F, y + 31.0F);
                }
            }
            modalCancel.render(canvas, mx, my, dt);
            modalConfirm.render(canvas, mx, my, dt);
        } finally {
            canvas.restoreToCount(layer);
        }
    }

    private void paintButtons(Canvas canvas, float mx, float my, float dt) {
        Text.drawLine(canvas, "四种按钮样式（已点击 " + clicks + " 次）", AtomUi.REGULAR, Text.BODY, IOStyle.TEXT_2,
                contentX(), contentY() + 7.0F, contentW(), Text.Align.LEFT);
        for (Button b : demoButtons) {
            b.render(canvas, mx, my, dt);
        }
    }

    private void paintText(Canvas canvas) {
        float x = contentX();
        float y = contentY();
        float w = contentW();
        String para = "模块商店是面向 NeoForge 1.21.1 的服务端经济系统：每日商店、每日收购、拍卖行、玩家摊位、"
                + "交易与转账、邮箱、签到。这一段用来检查中文自动换行、标点处理和行距；超过三行的部分会以省略号结尾，"
                + "而不是溢出到面板外面。Mixed English text should wrap together with 中文 correctly.";
        TextLayout wrapped = Text.wrapped(para, AtomUi.REGULAR, Text.BODY, IOStyle.TEXT, w, 3);
        Text.draw(canvas, wrapped, x, y);
        float ny = y + wrapped.size().height() + 10.0F;
        float[] sizes = {Text.SMALL, Text.BODY, Text.TITLE, 14.0F};
        for (float s : sizes) {
            TextLayout l = Text.line(s + "px  字号示例 Aa 123 ¥€", AtomUi.REGULAR, s, IOStyle.TEXT_2, w);
            Text.draw(canvas, l, x, ny);
            ny += l.size().height() + 3.0F;
        }
        Text.drawLine(canvas, "§c清洗测试：§l格式码§r 与控制字符\u0007会被移除", AtomUi.REGULAR, Text.BODY,
                IOStyle.GREEN, x, ny + 6.0F, w, Text.Align.LEFT);
    }

    private void paintInfo(Canvas canvas) {
        var window = minecraft.getWindow();
        String[] lines = {
                "GUI 缩放：" + window.getGuiScale(),
                "GUI 尺寸：" + width + " × " + height,
                "帧缓冲：" + window.getWidth() + " × " + window.getHeight(),
                String.format("平均帧时长：%.1f ms", frameMs),
                "AtomLib：0.1.0-beta（与 AtomChat 同版本）",
                "坐标约定：1 单位 = 1 GUI 像素",
        };
        float y = contentY() + 4.0F;
        for (String s : lines) {
            Text.drawLine(canvas, s, AtomUi.REGULAR, Text.BODY, IOStyle.TEXT, contentX(), y + 5.0F, contentW(),
                    Text.Align.LEFT);
            y += 15.0F;
        }
    }

    /** AtomLib 不可用时的原版回退：说明原因，而不是黑屏或崩溃。 */
    private void renderFallback(GuiGraphics graphics) {
        Throwable cause = AtomUi.failure();
        graphics.drawCenteredString(font, "AtomLib 不可用，无法显示演示页", width / 2, height / 2 - 10, 0xFFE5645F);
        graphics.drawCenteredString(font, cause == null ? "" : String.valueOf(cause), width / 2, height / 2 + 4,
                0xFF9AA4B5);
    }

    // ------------------------------------------------------------------ 弹窗

    private void openModal(ItemStack stack) {
        modalStack = stack;
        modalShown = stack;
        search.blur();
        ClickSound.play();
    }

    private void closeModal() {
        modalStack = ItemStack.EMPTY;
        modalConfirm.cancelPress();
        modalCancel.cancelPress();
    }

    // ------------------------------------------------------------------ JEI

    @Override
    public ItemStack jeiHoveredStack(double mouseX, double mouseY) {
        if (search != null && search.isFocused()) {
            return ItemStack.EMPTY; // 正在打字：R/U 是输入的字母，不能被 JEI 截走
        }
        return modalStack.isEmpty() ? pageItems.hovered() : modalItems.hovered();
    }

    // ------------------------------------------------------------------ 输入

    private List<UiComponent> interactive() {
        List<UiComponent> list = new ArrayList<>();
        if (!modalStack.isEmpty()) {
            list.add(modalCancel);
            list.add(modalConfirm);
            return list; // 弹窗打开时只有弹窗可交互
        }
        list.addAll(tabs);
        list.add(closeButton);
        if (tab == TAB_BUTTONS) {
            list.addAll(demoButtons);
        }
        if (tab == TAB_ITEMS && search != null) {
            list.add(search);
        }
        return list;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!AtomUi.available()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        for (UiComponent c : interactive()) {
            if (c.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        if (!modalStack.isEmpty()) {
            return true; // 点在弹窗外：吞掉，不穿透到页面
        }
        if (tab == TAB_LIST && button == 0) {
            float x = contentX();
            float y = contentY();
            float w = contentW();
            float h = contentH();
            if (listScroll.pressBar(mouseX, mouseY, x, y, w, h)) {
                return true;
            }
            if (mouseX >= x && mouseX < x + w - 8.0F && mouseY >= y && mouseY < y + h) {
                int row = (int) ((mouseY - y + listScroll.offset()) / (ROW_H + ROW_GAP));
                float within = (float) ((mouseY - y + listScroll.offset()) % (ROW_H + ROW_GAP));
                if (row >= 0 && row < ROWS && within < ROW_H) {
                    selectedRow = row == selectedRow ? -1 : row;
                    ClickSound.play();
                    return true;
                }
            }
        }
        if (tab == TAB_ITEMS && button == 0) {
            if (itemScroll.pressBar(mouseX, mouseY, contentX(), gridY(), contentW(), gridH())) {
                return true;
            }
            ItemStack hit = pageItems.hovered();
            if (!hit.isEmpty()) {
                openModal(hit);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = listScroll.releaseBar() | itemScroll.releaseBar();
        for (UiComponent c : interactive()) {
            handled |= c.mouseReleased(mouseX, mouseY, button);
        }
        return handled || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (listScroll.dragBar(mouseY, contentH()) || itemScroll.dragBar(mouseY, gridH())) {
            return true;
        }
        if (search != null && search.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!modalStack.isEmpty()) {
            return true;
        }
        if (mouseX >= contentX() && mouseX < contentX() + contentW()
                && mouseY >= contentY() && mouseY < contentY() + contentH()) {
            if (tab == TAB_LIST) {
                listScroll.wheel(scrollY);
                return true;
            }
            if (tab == TAB_ITEMS) {
                itemScroll.wheel(scrollY);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // Esc 逐层退出：先关弹窗，再让输入框失焦，最后才关闭界面
            if (!modalStack.isEmpty()) {
                closeModal();
                return true;
            }
            if (search != null && search.isFocused()) {
                search.blur();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        listScroll.releaseBar();
        itemScroll.releaseBar();
        closeButton.cancelPress();
        tabs.forEach(Button::cancelPress);
        demoButtons.forEach(Button::cancelPress);
        super.removed();
    }
}
