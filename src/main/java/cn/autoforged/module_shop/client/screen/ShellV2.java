package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.PhysTierView;
import cn.autoforged.module_shop.client.ClientNotifications;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.UIFontScale;
import cn.autoforged.module_shop.client.media.ClientMediaCache;
import cn.autoforged.module_shop.client.screen.shop.PageFrame;
import cn.autoforged.module_shop.client.screen.shop.ShopPage;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.data.NotificationType;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import com.atom.lib.design.atomchat.SkiaDraw;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.RRect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * UI v2 外壳：顶栏 + 分组纯文字侧栏 + 内容区（页面照旧是 {@link ShopPage}）。
 *
 * <p><b>自有缩放</b>：不跟随 Minecraft 的 GUI 缩放，按窗口物理像素决定 1 个画布单位 = {@link #ui} 个像素，
 * 让 1080p「自动缩放」下也能一屏放下二十多行。页面拿到的 {@link PageFrame} 与鼠标坐标都是这个单位；
 * 物品层用 pose 缩放对齐，隐藏输入框经 {@link SkiaFrame#unitsToGui()} 换算，三者坐标一致。
 *
 * <p>只在 AtomLib 可用且不在「编辑布局」时启用；其余情况 ShopScreen 走原来的绘制。
 * 所有业务动作仍走 {@link ShopScreen#onAction}，与旧界面行为完全一致。
 */
final class ShellV2 {
    static final float TOP = 24.0F;
    static final float RAIL = 84.0F;
    private static final float PAGE_SWITCH_MS = 180.0F;
    /** 账单数据（今日收支）最多每 60 秒向服务端请求一次。 */
    private static long lastBillRequest;
    /** 侧栏滚动（跨界面重建保留）。 */
    private static final ScrollController RAIL_SCROLL = new ScrollController();

    private final ShopScreen screen;
    private final Motion.Clock clock = new Motion.Clock();
    private final Map<String, Float> anim = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private float dtMs;

    float ui = 2.0F;
    private float vw;
    private float vh;
    float px;
    float py;
    float pw;
    float ph;
    private float mx;
    private float my;
    private boolean skinOpen;
    private float[] skinMenu;
    private boolean fontOpen;
    private boolean fontDragging;
    private float[] fontMenu;
    private float[] fontBtnRect;
    private float[] fontSliderRect;
    private float railContentH;
    private float railListX, railListY, railListW, railListH;

    private record Hit(float x, float y, float w, float h, Runnable click, Runnable rightClick, Component tip) {
        Hit(float x, float y, float w, float h, Runnable click, Component tip) {
            this(x, y, w, h, click, null, tip);
        }

        boolean in(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    ShellV2(ShopScreen screen) {
        this.screen = screen;
    }

    // =========================================================== 几何

    /** 100% 字号倍率下的基准 UI 缩放：适当抬高常规桌面分辨率清晰度（如 1080p 由 2.25 提至 ~2.50）。 */
    static float baseUiScale() {
        var win = Minecraft.getInstance().getWindow();
        float fw = Math.max(1, win.getWidth());
        float fh = Math.max(1, win.getHeight());
        float s = Math.min(fw * 0.94F / 720.0F, fh * 0.92F / 390.0F);
        float min = Math.min(1.5F, Math.max(1.0F, fw / 560.0F));
        return Math.max(min, s);
    }

    /** UI v2 的界面缩放：1 个画布单位 = 多少物理像素（融合客户端字号倍率并做 vw>=560, vh>=320 下限保护）。 */
    static float uiScale() {
        var win = Minecraft.getInstance().getWindow();
        float fw = Math.max(1, win.getWidth());
        float fh = Math.max(1, win.getHeight());
        float maxSafe = Math.min(fw / 560.0F, fh / 320.0F);
        float minSafe = Math.min(1.0F, maxSafe);
        float desired = baseUiScale() * UIFontScale.scale();
        return Math.max(minSafe, Math.min(maxSafe, desired));
    }

    void layout() {
        var win = Minecraft.getInstance().getWindow();
        float fw = Math.max(1, win.getWidth());
        float fh = Math.max(1, win.getHeight());
        this.ui = uiScale();
        this.vw = fw / this.ui;
        this.vh = fh / this.ui;
        float maxPw = Math.max(100.0F, this.vw - 16.0F / this.ui);
        float maxPh = Math.max(100.0F, this.vh - 16.0F / this.ui);
        this.pw = Math.min(Math.min(this.vw * 0.96F, maxPw), 800.0F);
        this.ph = Math.min(Math.min(this.vh * 0.94F, maxPh), 440.0F);
        this.px = (float) Math.floor((this.vw - this.pw) / 2.0F);
        this.py = (float) Math.floor((this.vh - this.ph) / 2.0F);
    }

    float contentX() {
        return this.px + RAIL;
    }

    float contentY() {
        return this.py + TOP;
    }

    float contentW() {
        return this.pw - RAIL;
    }

    float contentH() {
        return this.ph - TOP;
    }

    private float toUnits(double gui) {
        return (float) (gui * Minecraft.getInstance().getWindow().getGuiScale() / this.ui);
    }

    PageFrame frame(float mouseX, float mouseY) {
        return new PageFrame(this.screen, contentX(), contentY(), contentW(), contentH(), mouseX, mouseY, this.dtMs);
    }

    private float hover(String key, boolean on) {
        float tau = on ? Motion.HOVER_MS : Motion.HOVER_OUT_MS;
        float v = Motion.approach(this.anim.getOrDefault(key, 0.0F), on ? 1.0F : 0.0F, this.dtMs, tau);
        this.anim.put(key, v);
        return v;
    }

    private boolean over(float x, float y, float w, float h) {
        return !this.skinOpen && !this.fontOpen && this.mx >= x && this.mx < x + w && this.my >= y && this.my < y + h;
    }

    // =========================================================== 绘制

    void render(GuiGraphics g, int mouseX, int mouseY) {
        UIFontScale.ensureLoaded();
        layout();
        this.dtMs = this.clock.tick();
        this.mx = toUnits(mouseX);
        this.my = toUnits(mouseY);
        this.hits.clear();
        if (!this.skinOpen) {
            this.skinMenu = null;
        }
        if (!this.fontOpen) {
            this.fontMenu = null;
            this.fontSliderRect = null;
        }
        this.fontBtnRect = null;
        maybeRequestBill();
        IOStyle.tickTheme();

        ShopPage page = this.screen.activePage();
        if (page != this.screen.lastPage) {
            if (this.screen.lastPage != null) {
                this.screen.lastPage.cancelInput();
                this.screen.pageSwitchNanos = System.nanoTime();
            }
            this.screen.lastPage = page;
        }
        boolean blockPage = this.skinOpen || this.fontOpen;
        PageFrame frame = blockPage ? frame(-10000, -10000) : frame(this.mx, this.my);
        this.screen.pageItems.begin(this.mx, this.my, !blockPage);
        float switchT = this.screen.pageSwitchNanos == 0L ? 1.0F
                : Math.min(1.0F, (System.nanoTime() - this.screen.pageSwitchNanos) / 1_000_000.0F / PAGE_SWITCH_MS);
        float switchE = Motion.easeOutCubic(switchT);
        float slide = 5.0F * (1.0F - switchE);

        SkiaFrame.drawScaled(g, this.ui, c -> {
            drawPanel(c);
            this.screen.pageItems.markFixed();
            int saved = c.save();
            Draw.clip(c, contentX(), contentY(), contentW(), contentH());
            int layer = Draw.beginOpacity(c, 0.3F + 0.7F * switchE);
            try {
                c.translate(0.0F, slide);
                if (page != null) {
                    page.render(c, frame);
                } else {
                    V2.empty(c, Component.translatable("gui.module_shop.v2.no_page").getString(),
                            contentX(), contentY(), contentW(), contentH());
                }
            } finally {
                c.restoreToCount(layer);
                c.restoreToCount(saved);
            }
        });
        double gs = Minecraft.getInstance().getWindow().getGuiScale();
        float k = (float) (this.ui / gs);
        g.pose().pushPose();
        g.pose().scale(k, k, 1.0F);
        this.screen.pageItems.setClipScale(k);
        this.screen.pageItems.flush(g, Minecraft.getInstance().font, slide > 0.01F ? slide : 0.0F);
        this.screen.pageItems.setClipScale(1.0F);
        g.pose().popPose();
        if (this.skinOpen) {
            SkiaFrame.drawScaled(g, this.ui, this::drawSkinMenu);
        } else if (this.fontOpen) {
            SkiaFrame.drawScaled(g, this.ui, this::drawFontMenu);
        }

        // 提示：物品原版 tooltip（同时供 JEI 快捷键）优先，其次是文字提示。
        // GUI 缩放大时原版提示框会比界面大一倍：按界面自己的缩放把它缩小（原版定位仍会把它夹在屏幕内）。
        ItemStack hovered = this.screen.pageItems.hovered();
        this.screen.jeiHover = hovered;
        float tk = Math.min(Math.max(1.0F, k), this.ui * 0.85F / (float) gs);
        int tmx = Math.round(mouseX / tk);
        int tmy = Math.round(mouseY / tk);
        g.pose().pushPose();
        g.pose().scale(tk, tk, 1.0F);
        if (!hovered.isEmpty()) {
            g.renderTooltip(Minecraft.getInstance().font, hovered, tmx, tmy);
        } else if (this.screen.pendingTextTip != null) {
            g.renderTooltip(Minecraft.getInstance().font,
                    Minecraft.getInstance().font.split(this.screen.pendingTextTip, 260), tmx, tmy);
        }
        g.pose().popPose();
        this.screen.pendingTextTip = null;
    }

    private void maybeRequestBill() {
        long now = System.currentTimeMillis();
        if (now - lastBillRequest > 60_000L) {
            lastBillRequest = now;
            CompoundTag tag = new CompoundTag();
            tag.putString("action", "bill_request");
            ServerboundShopActionPayload.send(tag);
        }
    }

    private void drawPanel(Canvas c) {
        Draw.outerShadow(c, this.px, this.py, this.pw, this.ph, 6.0F, 14.0F, 3.0F, IOStyle.SHADOW_TINT);
        int saved = c.save();
        c.clipRRect(RRect.makeXYWH(this.px, this.py, this.pw, this.ph, 6.0F), true);
        Draw.fill(c, this.px, this.py, this.pw, this.ph, 0, IOStyle.CONTENT);
        drawTop(c);
        drawRail(c);
        c.restoreToCount(saved);
        Draw.stroke(c, this.px, this.py, this.pw, this.ph, 6.0F, 0.75F, IOStyle.BORDER_DIM);
    }

    // ---------------------------------------------------------------- 顶栏

    private String shopTitle() {
        String t = ClientShopData.shopTitle;
        return t == null || t.isBlank() ? Component.translatable("gui.module_shop.title").getString() : t;
    }

    private String currentLabel() {
        if ("admin".equals(this.screen.currentTab)) {
            return Component.translatable("gui.module_shop.admin_open").getString();
        }
        return ShopScreen.tabLabel(this.screen.currentTab).getString();
    }

    private void drawTop(Canvas c) {
        float x = this.px;
        float y = this.py;
        float cy = y + TOP / 2.0F;
        Draw.fill(c, x, y, this.pw, TOP, 0, IOStyle.SIDEBAR);
        Draw.hairline(c, x, y + TOP - 0.5F, this.pw, IOStyle.BORDER_DIM);
        Image brandImg = ClientShopData.brandIconId == null || ClientShopData.brandIconId.isBlank()
                ? null : ClientMediaCache.getSkiaImage(ClientShopData.brandIconId);
        String title = shopTitle();
        if (brandImg != null) {
            SkiaDraw.drawRoundedImage(c, brandImg, x + 7.0F, cy - 6.5F, 13.0F, 13.0F, 3.5F, SamplingMode.LINEAR);
        } else {
            Draw.gradientH(c, x + 7, cy - 6.5F, 13, 13, 3.5F, IOStyle.BLUE, IOStyle.ACCENT_2);
            V2.bold(c, title.substring(0, Math.min(1, title.length())), 7.0F, 0xFFFFFFFF, x + 7, cy, 13, Text.Align.CENTER);
        }
        float tx = x + 26;

        // 先自右向左排布工具按钮、字号按钮、管理入口与指标，再将剩余宽度分配给左侧面包屑，确保任何字号与窗口尺寸下零重叠
        float bx = x + this.pw - 7 - V2.CTRL;
        float by = cy - V2.CTRL / 2;
        bx = iconBtn(c, "close", V2.Icon.CLOSE, bx, by, () -> this.screen.onAction("close_ui", null), null, false);
        float skinX = bx;
        bx = iconBtn(c, "skin", V2.Icon.SKIN, bx, by, () -> {
            this.skinOpen = !this.skinOpen;
            this.fontOpen = false;
            this.fontDragging = false;
        }, Component.translatable("gui.module_shop.skin.tip"), this.skinOpen);
        if (this.skinOpen) {
            this.skinMenu = new float[]{skinX + V2.CTRL, by + V2.CTRL + 3};
        }

        // 字号缩放胶囊按钮：固定宽度避免调节时顶栏图标左右抖动；左键展开浮层、右键重置 100%、悬停滚轮微调
        float fontW = Math.max(28.0F, V2.width("Aa 135%", V2.FS_XS, true) + 8.0F);
        float fontX = bx + V2.CTRL - fontW;
        bx = drawFontScaleButton(c, fontX, by, cy, fontW);

        boolean grid = cn.autoforged.module_shop.client.UIViewMode.isGrid();
        bx = iconBtn(c, "view_mode", grid ? V2.Icon.VIEW_GRID : V2.Icon.VIEW_LIST, bx, by,
                cn.autoforged.module_shop.client.UIViewMode::toggle,
                Component.translatable(grid ? "gui.module_shop.view.to_list" : "gui.module_shop.view.to_grid"), grid);
        bx = iconBtn(c, "bell", V2.Icon.BELL, bx, by, () -> this.screen.onAction("notif_settings", null),
                Component.translatable("gui.module_shop.notify.settings_title"), false);
        bx = iconBtn(c, "bill", V2.Icon.BILL, bx, by, () -> this.screen.onAction("open_bill", null),
                Component.translatable("gui.module_shop.bill.title"), false);
        float rx = bx + V2.CTRL - 2;
        if (ClientShopData.canAdmin) {
            boolean locked = ClientShopData.isAdminLocked();
            for (String action : new String[]{"admin_open", "sidebar_edit"}) {
                String label = Component.translatable("admin_open".equals(action)
                        ? "gui.module_shop.admin_open" : "gui.module_shop.layout_edit").getString();
                float lockExtra = locked ? 7.5F : 0.0F;
                float w = V2.buttonWidth(label) + lockExtra;
                if (rx - w - 4 < tx + 44.0F) {
                    continue;
                }
                rx -= w + 4;
                boolean hovered = over(rx, by, w, V2.CTRL);
                boolean sel = !locked && "admin_open".equals(action) && "admin".equals(this.screen.currentTab);
                float h = hover("pill." + action, hovered);
                if (locked) {
                    Draw.fill(c, rx, by, w, V2.CTRL, V2.R,
                            Draw.lerp(IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER, h));
                    Draw.stroke(c, rx, by, w, V2.CTRL, V2.R, 0.55F,
                            Draw.lerp(Draw.alpha(IOStyle.ORANGE, 0.55F), IOStyle.ORANGE, h));
                    drawPadlock(c, rx + 5.0F, cy, IOStyle.ORANGE);
                    V2.bold(c, label, V2.FS_S, IOStyle.TEXT, rx + 5.0F + lockExtra, cy,
                            w - 10.0F - lockExtra, Text.Align.LEFT);
                } else {
                    V2.button(c, label, sel ? V2.Btn.PRIMARY : V2.Btn.NORMAL, rx, by, w, V2.CTRL, h);
                }
                String a = action;
                Component tip = locked ? Component.translatable("msg.module_shop.auth_required") : null;
                this.hits.add(new Hit(rx, by, w, V2.CTRL, () -> this.screen.onAction(a, null), tip));
                if (locked && hovered) {
                    this.screen.pendingTextTip = tip;
                }
            }
            rx -= 4;
        }
        // 指标优先级：余额 > 实体货币账本 > 二选一默认偏好 > 今日收支；窄窗口/高字号下优先折叠今日收支，保住双账本并列
        record TopEntry(String kind, String title, String value, String tag, float width,
                        List<PhysTierView> physSubset, int physTotal) {}
        List<TopEntry> priorityStats = new ArrayList<>();
        String balTitle = Component.translatable("gui.module_shop.v2.balance").getString();
        String balVal = V2.money(ClientShopData.balance) + " " + ClientShopData.currencyName();
        float balW = Math.max(V2.width(balTitle, V2.FS_XS, false), V2.width(balVal, V2.FS + 0.5F, true)) + 14.0F;
        priorityStats.add(new TopEntry("balance", balTitle, balVal, "", balW, List.of(), 0));

        if (ClientHomeCurrencyState.physEnabled) {
            List<PhysTierView> allTiers = ClientHomeCurrencyState.physTiers;
            String physTitle = ClientHomeCurrencyState.tr("gui.module_shop.v2.phys_ledger", "实体货币");
            float minW = V2.width(physTitle, V2.FS_XS, false) + 14.0F;
            if (allTiers.isEmpty()) {
                String none = ClientHomeCurrencyState.tr("gui.module_shop.v2.phys_none", "未配置");
                float pw = Math.max(minW, V2.width(none, V2.FS, false) + 14.0F);
                priorityStats.add(new TopEntry("phys", physTitle, none, "", pw, List.of(), 0));
            } else {
                List<PhysTierView> pick = pickDisplayTiers(allTiers, 3);
                float rowW = 0.0F;
                for (int i = 0; i < pick.size(); i++) {
                    PhysTierView tv = pick.get(i);
                    String cnt = "×" + PriceDisplay.formatNumber(tv.ownedCount, true);
                    rowW += (i > 0 ? 4.0F : 0.0F) + 8.5F + 1.0F + V2.width(cnt, V2.FS_S, true);
                }
                if (allTiers.size() > pick.size()) {
                    rowW += 3.0F + V2.width("+" + (allTiers.size() - pick.size()), V2.FS_XS, false);
                }
                float pw = Math.max(minW, Math.min(168.0F, rowW + 14.0F));
                priorityStats.add(new TopEntry("phys", physTitle, "", "", pw, pick, allTiers.size()));
            }

            boolean prefPhys = ClientHomeCurrencyState.CHANNEL_PHYS.equals(ClientHomeCurrencyState.preferredEitherChannel);
            String prefLabel = prefPhys
                    ? ClientHomeCurrencyState.tr("gui.module_shop.v2.either_pref_phys", "默认:实体")
                    : ClientHomeCurrencyState.tr("gui.module_shop.v2.either_pref_star", "默认:星币");
            float prefW = V2.width(prefLabel, V2.FS_XS, true) + 12.0F;
            priorityStats.add(new TopEntry("pref", prefLabel, "", "", prefW + 6.0F, List.of(), 0));
        }

        if (ClientShopData.billUpdatedAt > 0) {
            String inTitle = Component.translatable("gui.module_shop.v2.today_in").getString();
            String inVal = V2.signed(ClientShopData.billTodayIn);
            float inW = Math.max(V2.width(inTitle, V2.FS_XS, false), V2.width(inVal, V2.FS + 0.5F, true)) + 14.0F;
            priorityStats.add(new TopEntry("in", inTitle, inVal, "g", inW, List.of(), 0));

            String outTitle = Component.translatable("gui.module_shop.v2.today_out").getString();
            String outVal = "−" + V2.money(ClientShopData.billTodayOut);
            float outW = Math.max(V2.width(outTitle, V2.FS_XS, false), V2.width(outVal, V2.FS + 0.5F, true)) + 14.0F;
            priorityStats.add(new TopEntry("out", outTitle, outVal, "r", outW, List.of(), 0));
        }

        float minTitleLeft = tx + 68.0F;
        List<TopEntry> drawStats = new ArrayList<>();
        float usedW = 0.0F;
        for (TopEntry st : priorityStats) {
            if (rx - (usedW + st.width()) < minTitleLeft) {
                continue;
            }
            usedW += st.width();
            drawStats.add(st);
        }
        for (int i = drawStats.size() - 1; i >= 0; i--) {
            TopEntry st = drawStats.get(i);
            float w = st.width();
            rx -= w;
            if ("balance".equals(st.kind())) {
                boolean hovered = over(rx, y, w, TOP);
                float h = hover("stat.balance", hovered);
                if (h > 0.01F) {
                    Draw.fill(c, rx + 2, y + 2, w - 2, TOP - 4, 3, Draw.alpha(IOStyle.ROW_HOVER, h));
                }
                Component tip = Component.translatable("gui.module_shop.bill.title");
                this.hits.add(new Hit(rx, y, w, TOP, () -> this.screen.onAction("open_bill", null), tip));
                if (hovered) {
                    this.screen.pendingTextTip = tip;
                }
                Draw.fill(c, rx, cy - 7, 0.5F, 14, 0, IOStyle.BORDER_DIM);
                V2.text(c, st.title(), V2.FS_XS, IOStyle.TEXT_3, rx + 7, cy - 4.0F, w - 8, Text.Align.LEFT);
                V2.bold(c, st.value(), V2.FS + 0.5F, IOStyle.TEXT, rx + 7, cy + 3.5F, w - 8, Text.Align.LEFT);
            } else if ("phys".equals(st.kind())) {
                boolean hovered = over(rx, y, w, TOP);
                float h = hover("stat.phys", hovered);
                if (h > 0.01F) {
                    Draw.fill(c, rx + 2, y + 2, w - 2, TOP - 4, 3, Draw.alpha(IOStyle.ROW_HOVER, h));
                }
                Component tip = buildPhysTooltip();
                this.hits.add(new Hit(rx, y, w, TOP,
                        () -> {
                            ClickSound.play();
                            Nav.open(new PhysicalCurrencyExchangeScreen());
                        }, tip));
                if (hovered) {
                    this.screen.pendingTextTip = tip;
                }
                Draw.fill(c, rx, cy - 7, 0.5F, 14, 0, IOStyle.BORDER_DIM);
                V2.text(c, st.title(), V2.FS_XS, IOStyle.TEXT_3, rx + 7, cy - 4.0F, w - 8, Text.Align.LEFT);
                if (st.physSubset().isEmpty()) {
                    V2.text(c, st.value(), V2.FS_S, IOStyle.TEXT_3, rx + 7, cy + 3.5F, w - 8, Text.Align.LEFT);
                } else {
                    float px = rx + 7;
                    float maxRight = rx + w - 4;
                    float valCy = cy + 4.0F;
                    for (int k = 0; k < st.physSubset().size() && px + 10.0F < maxRight; k++) {
                        PhysTierView tv = st.physSubset().get(k);
                        if (!tv.itemStack.isEmpty()) {
                            this.screen.pageItems.addNoHover(tv.itemStack.copyWithCount(1),
                                    px, valCy - 4.25F, 8.5F, false);
                        }
                        px += 9.5F;
                        String cnt = "×" + PriceDisplay.formatNumber(tv.ownedCount, true);
                        float cw = V2.width(cnt, V2.FS_S, true);
                        float room = Math.max(4.0F, maxRight - px);
                        V2.bold(c, cnt, V2.FS_S, tv.ownedCount > 0 ? IOStyle.GREEN : IOStyle.TEXT_3,
                                px, valCy, room, Text.Align.LEFT);
                        px += Math.min(cw, room) + 4.0F;
                    }
                    if (st.physTotal() > st.physSubset().size() && px + 6.0F < maxRight) {
                        V2.text(c, "+" + (st.physTotal() - st.physSubset().size()), V2.FS_XS, IOStyle.TEXT_3,
                                px - 1.0F, valCy, maxRight - px, Text.Align.LEFT);
                    }
                }
            } else if ("pref".equals(st.kind())) {
                float pw = w - 6.0F;
                float px = rx + 3.0F;
                float pyBtn = cy - 7.0F;
                boolean prefPhys = ClientHomeCurrencyState.CHANNEL_PHYS.equals(ClientHomeCurrencyState.preferredEitherChannel);
                boolean hovered = over(px, pyBtn, pw, 14.0F);
                float hv = hover("stat.pref", hovered);
                int bg = Draw.lerp(prefPhys ? Draw.alpha(IOStyle.GREEN, 0.14F) : Draw.alpha(IOStyle.BLUE, 0.14F),
                        IOStyle.ROW_HOVER, hv);
                int bd = prefPhys ? Draw.alpha(IOStyle.GREEN, 0.45F) : Draw.alpha(IOStyle.BLUE, 0.45F);
                Draw.fill(c, px, pyBtn, pw, 14.0F, 3.0F, bg);
                Draw.stroke(c, px, pyBtn, pw, 14.0F, 3.0F, 0.6F, bd);
                V2.bold(c, st.title(), V2.FS_XS, prefPhys ? IOStyle.GREEN : IOStyle.BLUE,
                        px + 2.0F, cy, pw - 4.0F, Text.Align.CENTER);
                Component tip = Component.literal(ClientHomeCurrencyState.tr(
                        "gui.module_shop.v2.either_pref_tip",
                        "二选一双通道商品左键快捷支付默认通道（点击切换）：当前「%s」",
                        prefPhys ? ClientHomeCurrencyState.tr("gui.module_shop.price_editor.mode_phys", "实体货币")
                                : ClientShopData.currencyName()));
                this.hits.add(new Hit(px, pyBtn, pw, 14.0F, () -> {
                    ClickSound.play();
                    ClientHomeCurrencyState.togglePreferredEitherChannel();
                }, tip));
                if (hovered) {
                    this.screen.pendingTextTip = tip;
                }
            } else {
                Draw.fill(c, rx, cy - 7, 0.5F, 14, 0, IOStyle.BORDER_DIM);
                V2.text(c, st.title(), V2.FS_XS, IOStyle.TEXT_3, rx + 7, cy - 4.0F, w - 8, Text.Align.LEFT);
                int color = "r".equals(st.tag()) ? IOStyle.RED : "g".equals(st.tag()) ? IOStyle.GREEN : IOStyle.TEXT;
                V2.bold(c, st.value(), V2.FS + 0.5F, color, rx + 7, cy + 3.5F, w - 8, Text.Align.LEFT);
            }
        }

        // 左侧面包屑：严格限制在 [tx, rx - 8] 内，绝不与右侧指标重叠
        float titleAvail = Math.max(0.0F, rx - 8.0F - tx);
        String pageLabel = currentLabel();
        float brandTextW = 0.0F;
        if (titleAvail >= 82.0F) {
            float titleMaxW = Math.min(220.0F, titleAvail * 0.58F);
            brandTextW = Math.min(V2.width(title, V2.FS, false), titleMaxW);
            float tw = V2.text(c, title + "  ›  ", V2.FS, IOStyle.TEXT_3, tx, cy,
                    titleMaxW, Text.Align.LEFT);
            V2.bold(c, pageLabel, V2.FS, IOStyle.TEXT, tx + tw, cy, Math.max(0.0F, titleAvail - tw), Text.Align.LEFT);
        } else if (titleAvail > 4.0F) {
            V2.bold(c, pageLabel, V2.FS, IOStyle.TEXT, tx, cy, titleAvail, Text.Align.LEFT);
        }
        if (ClientShopData.canAdmin) {
            float brandHitX = x + 5.0F;
            float brandHitY = y + 3.0F;
            float brandHitH = TOP - 6.0F;
            float brandHitW = brandTextW > 0.0F ? (tx - brandHitX + brandTextW + 2.0F) : 17.0F;
            boolean brandOver = over(brandHitX, brandHitY, brandHitW, brandHitH);
            if (brandOver) {
                Draw.stroke(c, x + 7.0F, cy - 6.5F, 13.0F, 13.0F, 3.5F, 0.75F, IOStyle.BLUE);
                this.screen.pendingTextTip = Component.translatable("gui.module_shop.brand.title");
            }
            Runnable openBrand = () -> ClientAdminAuth.guard(() -> Nav.open(new BrandSettingsScreen(this.screen)));
            this.hits.add(new Hit(brandHitX, brandHitY, brandHitW, brandHitH,
                    openBrand, openBrand, Component.translatable("gui.module_shop.brand.title")));
        }
    }

    private static void drawPadlock(Canvas c, float x, float cy, int color) {
        Draw.stroke(c, x + 1.0F, cy - 3.0F, 3.0F, 2.6F, 1.4F, 0.65F, color);
        Draw.fill(c, x + 0.3F, cy - 1.0F, 4.4F, 3.4F, 0.8F, color);
    }

    private float drawFontScaleButton(Canvas c, float x, float y, float cy, float w) {
        boolean on = over(x, y, w, V2.CTRL);
        float h = hover("icon.font", on);
        boolean active = this.fontOpen;
        Draw.fill(c, x, y, w, V2.CTRL, V2.R, active ? Draw.alpha(IOStyle.BLUE, 0.2F)
                : Draw.lerp(IOStyle.CARD, IOStyle.GRAY_BTN_HOVER, h));
        Draw.stroke(c, x, y, w, V2.CTRL, V2.R, 0.5F,
                Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, Math.max(h, active ? 1.0F : 0.0F)));
        int baseColor = UIFontScale.isDefault() ? IOStyle.TEXT_2 : IOStyle.BLUE;
        int color = Draw.lerp(baseColor, IOStyle.TEXT, Math.max(h, active ? 1.0F : 0.0F));
        V2.bold(c, "Aa " + UIFontScale.label(), V2.FS_XS, color, x + 2, cy, w - 4, Text.Align.CENTER);
        this.fontBtnRect = new float[]{x, y, w, V2.CTRL};
        Component tip = Component.translatable("gui.module_shop.font_scale.tip", UIFontScale.label());
        this.hits.add(new Hit(x, y, w, V2.CTRL,
                () -> {
                    this.fontOpen = !this.fontOpen;
                    this.skinOpen = false;
                    this.fontDragging = false;
                },
                UIFontScale::reset,
                tip));
        if (on) {
            this.screen.pendingTextTip = tip;
        }
        if (this.fontOpen) {
            this.fontMenu = new float[]{x + w, y + V2.CTRL + 3};
        }
        return x - V2.CTRL - 4;
    }

    private float iconBtn(Canvas c, String key, V2.Icon icon, float x, float y, Runnable click, Component tip,
                          boolean active) {
        boolean on = over(x, y, V2.CTRL, V2.CTRL);
        float h = hover("icon." + key, on);
        V2.iconButton(c, icon, x, y, h, active);
        this.hits.add(new Hit(x, y, V2.CTRL, V2.CTRL, click, tip));
        if (on && tip != null) {
            this.screen.pendingTextTip = tip;
        }
        return x - V2.CTRL - 4;
    }

    // ---------------------------------------------------------------- 侧栏

    /** 内置模块的分组（个人主页置顶独立分组；附属与未知 id 归「扩展」）。 */
    private static String groupOf(String id) {
        return switch (id) {
            case "home" -> "home";
            case "daily", "buyback", "mall", "shipping" -> "shop";
            case "auction", "stall", "demand" -> "market";
            case "mail", "signin", "exchange" -> "mine";
            case "trade", "transfer" -> "social";
            default -> "ext";
        };
    }

    private static final String[] GROUPS = {"home", "shop", "market", "mine", "social", "ext"};

    private static boolean builtIn(String id) {
        return !"ext".equals(groupOf(id));
    }

    /** 侧栏条目第二行的实时状态（文字, 颜色）；没有可说的返回 null。 */
    private static Object[] statusOf(String id) {
        return switch (id) {
            case "home" -> ClientShopData.signInEnabled && !ClientShopData.signInDone
                    ? st("gui.module_shop.v2.st.home_todo", IOStyle.ORANGE, ClientShopData.signInStreak)
                    : null;
            case "daily" -> ClientShopData.dailyShop.isEmpty() ? null
                    : st("gui.module_shop.v2.st.daily", IOStyle.TEXT_3, ClientShopData.dailyShop.size());
            case "buyback" -> ClientShopData.dailyBuyback.isEmpty() ? null
                    : st("gui.module_shop.v2.st.buyback", ClientShopData.buybackLeft > 0 ? IOStyle.TEXT_3 : IOStyle.GREEN,
                    ClientShopData.buybackLeft);
            case "mall" -> st("gui.module_shop.v2.st.mall", IOStyle.TEXT_3, ClientShopData.mall.size());
            case "shipping" -> ClientShopData.shipValue <= 0 ? null
                    : st("gui.module_shop.v2.st.shipping", IOStyle.GREEN, V2.money(ClientShopData.shipValue));
            case "stall" -> ClientShopData.stallPlayers.isEmpty() ? null
                    : st("gui.module_shop.v2.st.stall", IOStyle.TEXT_3, ClientShopData.stallPlayers.size());
            case "demand" -> ClientShopData.demands.isEmpty() ? null
                    : st("gui.module_shop.v2.st.demand", IOStyle.TEXT_3, ClientShopData.demands.size());
            case "mail" -> ClientShopData.mail.isEmpty() ? null
                    : st("gui.module_shop.v2.st.mail", IOStyle.TEXT_3, ClientShopData.mail.size());
            case "exchange" -> ClientShopData.exchange.isEmpty() ? null
                    : st("gui.module_shop.v2.st.exchange", IOStyle.TEXT_3, ClientShopData.exchange.size());
            case "signin" -> !ClientShopData.signInEnabled ? null : ClientShopData.signInDone
                    ? st("gui.module_shop.v2.st.signin_done", IOStyle.GREEN, ClientShopData.signInStreak)
                    : st("gui.module_shop.v2.st.signin_todo", IOStyle.RED, ClientShopData.signInStreak);
            case "trade" -> !ClientShopData.tradeRequests.isEmpty()
                    ? st("gui.module_shop.v2.st.trade_req", IOStyle.RED, ClientShopData.tradeRequests.size())
                    : st("gui.module_shop.v2.st.online", IOStyle.TEXT_3, ClientShopData.onlinePlayers.size());
            default -> null;
        };
    }

    private static Object[] st(String key, int color, Object arg) {
        return new Object[]{Component.translatable(key, arg).getString(), color};
    }

    private void drawRail(Canvas c) {
        float x = this.px;
        float y = this.py + TOP;
        float h = this.ph - TOP;
        Draw.fill(c, x, y, RAIL, h, 0, IOStyle.SIDEBAR);
        Draw.fill(c, x + RAIL - 0.5F, y, 0.5F, h, 0, IOStyle.BORDER_DIM);

        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String gname : GROUPS) {
            groups.put(gname, new ArrayList<>());
        }
        for (String id : this.screen.visibleTabs()) {
            groups.get(groupOf(id)).add(id);
        }
        float profileH = profileHeight();
        float listBottom = y + h - profileH - 4;
        float listH = Math.max(20.0F, listBottom - y);
        this.railListX = x;
        this.railListY = y;
        this.railListW = RAIL;
        this.railListH = listH;
        RAIL_SCROLL.setContent(this.railContentH, listH);
        RAIL_SCROLL.update();
        int saved = c.save();
        Draw.clip(c, x, y, RAIL, listH);
        float cy = y + 4 - RAIL_SCROLL.offset();
        float start = cy;
        for (String gname : GROUPS) {
            List<String> ids = groups.get(gname);
            if (ids.isEmpty()) {
                continue;
            }
            V2.text(c, Component.translatable("gui.module_shop.v2.group." + gname).getString(), V2.FS_XS,
                    IOStyle.TEXT_3, x + 8, cy + 6, RAIL - 14, Text.Align.LEFT);
            cy += 12;
            for (String id : ids) {
                cy = drawNav(c, id, x, cy, listBottom);
            }
            cy += 3;
        }
        this.railContentH = cy - start + 2;
        c.restoreToCount(saved);
        RAIL_SCROLL.setContent(this.railContentH, listH);
        RAIL_SCROLL.drawBar(c, x, y, RAIL, listH, this.mx, this.my, this.dtMs);
        drawProfile(c, x + 4, y + h - profileH - 3, RAIL - 8, profileH);
    }

    private float drawNav(Canvas c, String id, float x, float cy, float listBottom) {
        Object[] status = statusOf(id);
        float rh = status == null ? 14.0F : 21.0F;
        boolean sel = id.equals(this.screen.currentTab);
        boolean visible = cy + rh > this.py + TOP && cy < listBottom;
        float hv = hover("nav." + id, visible && over(x + 3, cy, RAIL - 6, rh) && this.my < listBottom);
        if (sel) {
            Draw.fill(c, x + 3, cy, RAIL - 6, rh, 3, Draw.alpha(IOStyle.BLUE, 0.18F));
            Draw.fill(c, x, cy + 4, 1.75F, rh - 8, 0.8F, IOStyle.BLUE);
        } else if (hv > 0.01F) {
            Draw.fill(c, x + 3, cy, RAIL - 6, rh, 3, Draw.alpha(IOStyle.ROW_HOVER, hv));
        }
        String name = ShopScreen.tabLabel(id).getString();
        float nameCy = cy + 7.0F;
        float right = x + RAIL - (RAIL_SCROLL.canScroll() ? 9.0F : 8.0F);
        NotificationType type = ShopScreen.badgeOf(id);
        int count = type == null ? 0 : ClientNotifications.count(type);
        if (count > 0) {
            V2.badge(c, count, right - 4, nameCy);
            right -= count >= 100 ? 18 : count >= 10 ? 15 : 12;
        } else if (!builtIn(id)) {
            float tw = V2.width(Component.translatable("gui.module_shop.v2.addon").getString(), 4.5F, false) + 5;
            Draw.stroke(c, right - tw, nameCy - 4, tw, 8, 1.5F, 0.5F, IOStyle.BORDER);
            V2.text(c, Component.translatable("gui.module_shop.v2.addon").getString(), 4.5F, IOStyle.TEXT_3,
                    right - tw, nameCy, tw, Text.Align.CENTER);
            right -= tw + 3;
        }
        if (sel) {
            V2.bold(c, name, V2.FS, IOStyle.TEXT, x + 9, nameCy, right - x - 9, Text.Align.LEFT);
        } else {
            V2.text(c, name, V2.FS, Draw.lerp(IOStyle.TEXT_2, IOStyle.TEXT, hv), x + 9, nameCy, right - x - 9, Text.Align.LEFT);
        }
        if (status != null) {
            V2.text(c, (String) status[0], V2.FS_XS, (Integer) status[1], x + 9, cy + 15.0F, RAIL - 16, Text.Align.LEFT);
        }
        if (visible) {
            this.hits.add(new Hit(x + 3, Math.max(cy, this.py + TOP), RAIL - 6, Math.min(rh, listBottom - cy),
                    () -> this.screen.selectTab(id), null));
        }
        return cy + rh + 1;
    }

    private static float profileHeight() {
        if (!ClientHomeCurrencyState.physEnabled || ClientHomeCurrencyState.physTiers.isEmpty()) {
            return 26.0F;
        }
        return ClientHomeCurrencyState.physTiers.size() > 2 ? 49.0F : 38.0F;
    }

    private static List<PhysTierView> pickDisplayTiers(List<PhysTierView> all, int limit) {
        List<PhysTierView> out = new ArrayList<>();
        for (PhysTierView tv : all) {
            if (tv.ownedCount > 0 && out.size() < limit) {
                out.add(tv);
            }
        }
        if (out.size() < limit) {
            for (PhysTierView tv : all) {
                if (!out.contains(tv) && out.size() < limit) {
                    out.add(tv);
                }
            }
        }
        // 保持原始阶梯顺序展示，阅读更直观
        out.sort(java.util.Comparator.comparingInt(all::indexOf));
        return out;
    }

    private static Component buildPhysTooltip() {
        List<PhysTierView> tiers = ClientHomeCurrencyState.physTiers;
        if (tiers.isEmpty()) {
            return Component.literal(ClientHomeCurrencyState.tr(
                    "gui.module_shop.v2.phys_tip_empty", "实体货币（暂未配置档位，点击管理/查看）"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ClientHomeCurrencyState.tr("gui.module_shop.v2.phys_tip_head", "实体货币账本（点击打开阶梯兑换台）"));
        for (PhysTierView tv : tiers) {
            sb.append('\n').append("• ").append(tv.displayName()).append(" ×").append(tv.ownedCount);
        }
        return Component.literal(sb.toString());
    }

    private void drawProfile(Canvas c, float x, float y, float w, float h) {
        boolean showPhys = ClientHomeCurrencyState.physEnabled && !ClientHomeCurrencyState.physTiers.isEmpty();
        float topH = showPhys ? 23.5F : h;
        boolean overTop = over(x, y, w, topH);
        float hv = hover("profile", over(x, y, w, h));
        Draw.fill(c, x, y, w, h, 4, Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hv));
        Draw.stroke(c, x, y, w, h, 4, 0.5F, IOStyle.BORDER_DIM);
        String name = ClientShopData.playerName == null || ClientShopData.playerName.isEmpty()
                ? Minecraft.getInstance().getUser().getName() : ClientShopData.playerName;
        V2.bold(c, name, V2.FS_S, IOStyle.TEXT, x + 6, y + 7.5F, w - 12, Text.Align.LEFT);
        V2.text(c, V2.money(ClientShopData.balance) + " " + ClientShopData.currencyName(), V2.FS_XS, IOStyle.ORANGE,
                x + 6, y + 17.5F, w - 12, Text.Align.LEFT);
        Component billTip = Component.translatable("gui.module_shop.bill.title");
        this.hits.add(new Hit(x, y, w, topH, () -> this.screen.onAction("open_bill", null), billTip));
        if (overTop) {
            this.screen.pendingTextTip = billTip;
        }

        if (showPhys) {
            Draw.hairline(c, x + 5, y + 23.5F, w - 10, IOStyle.BORDER_DIM);
            List<PhysTierView> tiers = ClientHomeCurrencyState.physTiers;
            List<PhysTierView> shown = pickDisplayTiers(tiers, 4);
            float gx = x + 5.0F;
            float gy = y + 25.0F;
            float colW = (w - 10.0F - 2.0F) * 0.5F;
            float rowH = 11.0F;
            for (int i = 0; i < shown.size(); i++) {
                PhysTierView tv = shown.get(i);
                int col = i % 2;
                int row = i / 2;
                float sx = gx + col * (colW + 2.0F);
                float sy = gy + row * rowH;
                float scy = sy + rowH * 0.5F;
                if (!tv.itemStack.isEmpty()) {
                    this.screen.pageItems.addNoHover(tv.itemStack.copyWithCount(1),
                            sx, scy - 4.0F, 8.0F, false);
                }
                String txt = (i == 3 && tiers.size() > 4)
                        ? ("+" + (tiers.size() - 3))
                        : ("×" + PriceDisplay.formatNumber(tv.ownedCount, true));
                V2.bold(c, txt, V2.FS_XS, tv.ownedCount > 0 ? IOStyle.GREEN : IOStyle.TEXT_3,
                        sx + 9.0F, scy, Math.max(6.0F, colW - 9.0F), Text.Align.LEFT);
            }
            boolean overBottom = over(x, y + 23.5F, w, h - 23.5F);
            Component physTip = buildPhysTooltip();
            this.hits.add(new Hit(x, y + 23.5F, w, h - 23.5F, () -> {
                ClickSound.play();
                Nav.open(new PhysicalCurrencyExchangeScreen());
            }, physTip));
            if (overBottom) {
                this.screen.pendingTextTip = physTip;
            }
        }
    }

    // ---------------------------------------------------------------- 皮肤菜单

    private void drawSkinMenu(Canvas c) {
        if (this.skinMenu == null) {
            return;
        }
        List<IOStyle.Skin> skins = IOStyle.SKINS;
        float w = 74;
        float rowH = 13;
        float h = skins.size() * rowH + 6;
        float x = this.skinMenu[0] - w;
        float y = this.skinMenu[1];
        Draw.shadow(c, x, y, w, h, 5, 8, 2, IOStyle.SHADOW_TINT);
        Draw.fill(c, x, y, w, h, 5, IOStyle.SHEET);
        Draw.stroke(c, x, y, w, h, 5, 0.6F, IOStyle.BORDER);
        float ry = y + 3;
        for (IOStyle.Skin skin : skins) {
            boolean cur = skin.id().equals(IOStyle.currentSkin());
            boolean on = this.mx >= x && this.mx < x + w && this.my >= ry && this.my < ry + rowH;
            if (cur) {
                Draw.fill(c, x + 3, ry, w - 6, rowH, 3, Draw.alpha(IOStyle.BLUE, 0.2F));
            } else if (on) {
                Draw.fill(c, x + 3, ry, w - 6, rowH, 3, IOStyle.ROW_HOVER);
            }
            int swatch = IOStyle.skinColor(skin.id(), "BLUE");
            Draw.fill(c, x + 8, ry + 3.5F, 6, 6, 3, swatch);
            V2.text(c, Component.translatable("gui.module_shop.skin." + skin.id()).getString(), V2.FS_S,
                    cur ? IOStyle.TEXT : IOStyle.TEXT_2, x + 18, ry + rowH / 2, w - 22, Text.Align.LEFT);
            String id = skin.id();
            this.hits.add(0, new Hit(x, ry, w, rowH, () -> this.screen.onAction("skin_pick:" + id, null), null));
            ry += rowH;
        }
        this.skinMenu = new float[]{this.skinMenu[0], this.skinMenu[1], x, y, w, h};
    }

    // ---------------------------------------------------------------- 字号浮层

    private void drawFontMenu(Canvas c) {
        if (this.fontMenu == null) {
            return;
        }
        float w = 136.0F;
        float h = 66.0F;
        float x = Math.max(this.px + 4.0F, Math.min(this.px + this.pw - w - 4.0F, this.fontMenu[0] - w));
        float y = Math.min(this.py + this.ph - h - 4.0F, this.fontMenu[1]);
        Draw.shadow(c, x, y, w, h, 5, 8, 2, IOStyle.SHADOW_TINT);
        Draw.fill(c, x, y, w, h, 5, IOStyle.SHEET);
        Draw.stroke(c, x, y, w, h, 5, 0.6F, IOStyle.BORDER);

        // 标题行 + 右侧重置按钮
        float headerY = y + 6.0F;
        V2.bold(c, Component.translatable("gui.module_shop.font_scale").getString(), V2.FS_S, IOStyle.TEXT,
                x + 8.0F, headerY + 5.5F, w - 50.0F, Text.Align.LEFT);
        float resetW = 30.0F;
        float resetH = 11.0F;
        float resetX = x + w - 8.0F - resetW;
        boolean canReset = !UIFontScale.isDefault();
        boolean resetOn = canReset && this.mx >= resetX && this.mx < resetX + resetW
                && this.my >= headerY && this.my < headerY + resetH;
        V2.button(c, Component.translatable("gui.module_shop.font_scale.reset").getString(),
                canReset ? V2.Btn.NORMAL : V2.Btn.DISABLED, resetX, headerY, resetW, resetH, resetOn ? 1.0F : 0.0F);
        if (canReset) {
            this.hits.add(0, new Hit(resetX, headerY, resetW, resetH, UIFontScale::reset, null));
        }

        // 步进行：[-] + [百分比交互滑条] + [+]
        int cur = UIFontScale.percent();
        float sy = y + 23.0F;
        float bw = 16.0F;
        float bh = V2.CTRL;
        boolean canDec = cur > UIFontScale.MIN_PERCENT;
        boolean canInc = cur < UIFontScale.MAX_PERCENT;
        boolean decOn = canDec && this.mx >= x + 8.0F && this.mx < x + 8.0F + bw
                && this.my >= sy && this.my < sy + bh;
        V2.button(c, "−", canDec ? V2.Btn.NORMAL : V2.Btn.DISABLED, x + 8.0F, sy, bw, bh, decOn ? 1.0F : 0.0F);
        if (canDec) {
            this.hits.add(0, new Hit(x + 8.0F, sy, bw, bh, () -> UIFontScale.step(-1), null));
        }

        float incX = x + w - 8.0F - bw;
        boolean incOn = canInc && this.mx >= incX && this.mx < incX + bw && this.my >= sy && this.my < sy + bh;
        V2.button(c, "+", canInc ? V2.Btn.NORMAL : V2.Btn.DISABLED, incX, sy, bw, bh, incOn ? 1.0F : 0.0F);
        if (canInc) {
            this.hits.add(0, new Hit(incX, sy, bw, bh, () -> UIFontScale.step(1), null));
        }

        float sx = x + 8.0F + bw + 4.0F;
        float sw = incX - 4.0F - sx;
        boolean sliderOn = this.mx >= sx && this.mx < sx + sw && this.my >= sy && this.my < sy + bh;
        Draw.fill(c, sx, sy, sw, bh, V2.R,
                this.fontDragging || sliderOn ? IOStyle.GRAY_BTN_HOVER : IOStyle.CARD);
        Draw.stroke(c, sx, sy, sw, bh, V2.R, 0.5F,
                this.fontDragging ? IOStyle.BLUE : sliderOn ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
        V2.bold(c, UIFontScale.label(), V2.FS_S, IOStyle.TEXT, sx, sy + 5.5F, sw, Text.Align.CENTER);
        float norm = (cur - UIFontScale.MIN_PERCENT)
                / (float) Math.max(1, UIFontScale.MAX_PERCENT - UIFontScale.MIN_PERCENT);
        float barX = sx + 5.0F;
        float barW = sw - 10.0F;
        Draw.fill(c, barX, sy + 11.0F, barW, 2.0F, 1.0F, IOStyle.BORDER_DIM);
        Draw.fill(c, barX, sy + 11.0F, barW * norm, 2.0F, 1.0F, IOStyle.BLUE);
        Draw.fill(c, barX + barW * norm - 2.0F, sy + 10.0F, 4.0F, 4.0F, 2.0F, IOStyle.TEXT);
        this.fontSliderRect = new float[]{sx, sy, sw, bh};

        // 预设快捷档位（85% / 100% / 110% / 120% / 135%）
        int[] presets = UIFontScale.presets();
        float py = y + 43.0F;
        float gap = 3.0F;
        float chipW = (w - 16.0F - gap * Math.max(0, presets.length - 1)) / Math.max(1, presets.length);
        for (int i = 0; i < presets.length; i++) {
            int p = presets[i];
            float cx = x + 8.0F + i * (chipW + gap);
            boolean sel = cur == p;
            boolean overChip = this.mx >= cx && this.mx < cx + chipW && this.my >= py && this.my < py + 15.0F;
            if (sel) {
                Draw.fill(c, cx, py, chipW, 15.0F, 3.0F, Draw.alpha(IOStyle.BLUE, 0.22F));
                Draw.stroke(c, cx, py, chipW, 15.0F, 3.0F, 0.6F, IOStyle.BLUE);
                V2.bold(c, p + "%", V2.FS_XS, IOStyle.TEXT, cx, py + 7.5F, chipW, Text.Align.CENTER);
            } else {
                Draw.fill(c, cx, py, chipW, 15.0F, 3.0F,
                        overChip ? IOStyle.ROW_HOVER : IOStyle.GRAY_BTN);
                Draw.stroke(c, cx, py, chipW, 15.0F, 3.0F, 0.5F,
                        overChip ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                V2.text(c, p + "%", V2.FS_XS, overChip ? IOStyle.TEXT : IOStyle.TEXT_2,
                        cx, py + 7.5F, chipW, Text.Align.CENTER);
            }
            this.hits.add(0, new Hit(cx, py, chipW, 15.0F, () -> UIFontScale.setPercent(p), null));
        }

        this.fontMenu = new float[]{this.fontMenu[0], this.fontMenu[1], x, y, w, h};
    }

    private void applyFontSliderAt(float unitX) {
        if (this.fontSliderRect == null) {
            return;
        }
        float barX = this.fontSliderRect[0] + 5.0F;
        float barW = Math.max(1.0F, this.fontSliderRect[2] - 10.0F);
        float t = Math.max(0.0F, Math.min(1.0F, (unitX - barX) / barW));
        int next = Math.round(UIFontScale.MIN_PERCENT
                + t * (UIFontScale.MAX_PERCENT - UIFontScale.MIN_PERCENT));
        UIFontScale.setPercent(next);
    }

    private static boolean inRect(float px, float py, float[] r) {
        return r != null && r.length >= 4 && px >= r[0] && px < r[0] + r[2] && py >= r[1] && py < r[1] + r[3];
    }

    // =========================================================== 输入

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        float x = toUnits(mouseX);
        float y = toUnits(mouseY);
        if (this.skinOpen) {
            if (button == 0) {
                for (Hit hit : this.hits) {
                    if (hit.click() != null && hit.in(x, y) && this.skinMenu != null && this.skinMenu.length > 2
                            && x >= this.skinMenu[2] && x < this.skinMenu[2] + this.skinMenu[4]
                            && y >= this.skinMenu[3] && y < this.skinMenu[3] + this.skinMenu[5]) {
                        Bricks.click();
                        hit.click().run();
                        return true;
                    }
                }
            }
            this.skinOpen = false;
            return true;
        }
        if (this.fontOpen) {
            if (this.fontMenu != null && this.fontMenu.length > 2
                    && x >= this.fontMenu[2] && x < this.fontMenu[2] + this.fontMenu[4]
                    && y >= this.fontMenu[3] && y < this.fontMenu[3] + this.fontMenu[5]) {
                if (button == 0) {
                    if (inRect(x, y, this.fontSliderRect)) {
                        Bricks.click();
                        this.fontDragging = true;
                        applyFontSliderAt(x);
                        return true;
                    }
                    for (Hit hit : this.hits) {
                        if (hit.click() != null && hit.in(x, y)
                                && hit.x() >= this.fontMenu[2] && hit.x() < this.fontMenu[2] + this.fontMenu[4]
                                && hit.y() >= this.fontMenu[3] && hit.y() < this.fontMenu[3] + this.fontMenu[5]) {
                            Bricks.click();
                            hit.click().run();
                            return true;
                        }
                    }
                }
                return true;
            }
            this.fontOpen = false;
            this.fontDragging = false;
            if (inRect(x, y, this.fontBtnRect)) {
                Bricks.click();
                if (button == 1) {
                    UIFontScale.reset();
                }
            }
            return true;
        }
        ShopPage page = this.screen.activePage();
        PageFrame frame = frame(x, y);
        boolean inContent = frame.contains(x, y);
        if (!inContent) {
            if (page != null) {
                page.clickedOutside();
            }
            if (button == 0 && this.railListH > 0.0F
                    && RAIL_SCROLL.pressBar(x, y, this.railListX, this.railListY, this.railListW, this.railListH)) {
                Bricks.click();
                return true;
            }
            if (button == 0 || button == 1) {
                for (Hit hit : this.hits) {
                    if (!hit.in(x, y)) {
                        continue;
                    }
                    Runnable action = button == 1 ? hit.rightClick() : hit.click();
                    if (action != null) {
                        Bricks.click();
                        action.run();
                        return true;
                    }
                }
            }
            return x >= this.px && x < this.px + this.pw && y >= this.py && y < this.py + this.ph;
        }
        return page != null && page.mouseClicked(frame, x, y, button);
    }

    boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.fontDragging && button == 0) {
            this.fontDragging = false;
            return true;
        }
        if (button == 0 && RAIL_SCROLL.releaseBar()) {
            return true;
        }
        ShopPage page = this.screen.activePage();
        float x = toUnits(mouseX);
        float y = toUnits(mouseY);
        return page != null && page.mouseReleased(frame(x, y), x, y, button);
    }

    boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        float x = toUnits(mouseX);
        float y = toUnits(mouseY);
        if (this.fontDragging && this.fontOpen && button == 0) {
            applyFontSliderAt(x);
            return true;
        }
        if (button == 0 && RAIL_SCROLL.isDragging() && RAIL_SCROLL.dragBar(y, this.railListH)) {
            return true;
        }
        ShopPage page = this.screen.activePage();
        return page != null && page.mouseDragged(frame(x, y), x, y, button, toUnits(dragX), toUnits(dragY));
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (this.skinOpen) {
            return true;
        }
        float x = toUnits(mouseX);
        float y = toUnits(mouseY);
        if (this.fontOpen || (!this.skinOpen && inRect(x, y, this.fontBtnRect))) {
            if (scrollY != 0.0D) {
                UIFontScale.step(scrollY > 0 ? 1 : -1);
            }
            return true;
        }
        if (x >= this.px && x < this.px + RAIL && y >= this.py + TOP && y < this.py + this.ph) {
            RAIL_SCROLL.wheel(scrollY);
            return true;
        }
        ShopPage page = this.screen.activePage();
        PageFrame frame = frame(x, y);
        return page != null && frame.contains(x, y) && page.mouseScrolled(frame, x, y, scrollY);
    }

    /** Esc：先收起皮肤菜单或字号调节浮层。 */
    boolean escape() {
        RAIL_SCROLL.releaseBar();
        if (this.skinOpen) {
            this.skinOpen = false;
            return true;
        }
        if (this.fontOpen) {
            this.fontOpen = false;
            this.fontDragging = false;
            return true;
        }
        return false;
    }

    /** 当前鼠标所在的提示（顶栏按钮）。 */
    void hoverTips() {
        for (Hit hit : this.hits) {
            if (hit.tip() != null && hit.in(this.mx, this.my)) {
                this.screen.pendingTextTip = hit.tip();
                return;
            }
        }
    }
}
