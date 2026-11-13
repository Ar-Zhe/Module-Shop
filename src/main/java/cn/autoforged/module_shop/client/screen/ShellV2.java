package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientNotifications;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.shop.PageFrame;
import cn.autoforged.module_shop.client.screen.shop.ShopPage;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.data.NotificationType;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
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
    private static float railScroll;

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
    private float railContentH;

    private record Hit(float x, float y, float w, float h, Runnable click, Component tip) {
        boolean in(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    ShellV2(ShopScreen screen) {
        this.screen = screen;
    }

    // =========================================================== 几何

    /** UI v2 的界面缩放：1 个画布单位 = 多少物理像素（按窗口大小，0.25 步进）。二级界面也用它。 */
    static float uiScale() {
        var win = Minecraft.getInstance().getWindow();
        float fw = Math.max(1, win.getWidth());
        float fh = Math.max(1, win.getHeight());
        float s = Math.min(fw * 0.94F / 740.0F, fh * 0.92F / 400.0F);
        // 0.25 步进；下限 1.5（窗口很小时降到 1，保证面板放得下），字不会小到看不清
        float min = Math.min(1.5F, Math.max(1.0F, fw / 560.0F));
        return Math.max(min, (float) Math.floor(s * 4.0F) / 4.0F);
    }

    void layout() {
        var win = Minecraft.getInstance().getWindow();
        float fw = Math.max(1, win.getWidth());
        float fh = Math.max(1, win.getHeight());
        this.ui = uiScale();
        this.vw = fw / this.ui;
        this.vh = fh / this.ui;
        this.pw = Math.min(this.vw * 0.96F, 800.0F);
        this.ph = Math.min(this.vh * 0.94F, 440.0F);
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
        return !this.skinOpen && this.mx >= x && this.mx < x + w && this.my >= y && this.my < y + h;
    }

    // =========================================================== 绘制

    void render(GuiGraphics g, int mouseX, int mouseY) {
        layout();
        this.dtMs = this.clock.tick();
        this.mx = toUnits(mouseX);
        this.my = toUnits(mouseY);
        this.hits.clear();
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
        boolean blockPage = this.skinOpen;
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
        }

        // 提示：物品原版 tooltip（同时供 JEI 快捷键）优先，其次是文字提示。
        // GUI 缩放大时原版提示框会比界面大一倍：按界面自己的缩放把它缩小（原版定位仍会把它夹在屏幕内）。
        ItemStack hovered = this.screen.pageItems.hovered();
        this.screen.jeiHover = hovered;
        float tk = Math.min(1.0F, this.ui * 0.85F / (float) gs);
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
        Draw.gradientH(c, x + 7, cy - 6.5F, 13, 13, 3.5F, IOStyle.BLUE, IOStyle.ACCENT_2);
        String title = shopTitle();
        V2.bold(c, title.substring(0, Math.min(1, title.length())), 7.0F, 0xFFFFFFFF, x + 7, cy, 13, Text.Align.CENTER);
        float tx = x + 26;
        float tw = V2.text(c, title + "  ›  ", V2.FS, IOStyle.TEXT_3, tx, cy, 220, Text.Align.LEFT);
        V2.bold(c, currentLabel(), V2.FS, IOStyle.TEXT, tx + tw, cy, 160, Text.Align.LEFT);

        // 右侧：关闭 / 皮肤 / 视图切换 / 提醒设置 / 账单，再往左是管理员按钮与指标
        float bx = x + this.pw - 7 - V2.CTRL;
        float by = cy - V2.CTRL / 2;
        bx = iconBtn(c, "close", V2.Icon.CLOSE, bx, by, () -> this.screen.onAction("close_ui", null), null, false);
        bx = iconBtn(c, "skin", V2.Icon.SKIN, bx, by, () -> this.skinOpen = !this.skinOpen,
                Component.translatable("gui.module_shop.skin.tip"), this.skinOpen);
        if (this.skinOpen) {
            this.skinMenu = new float[]{bx + 4 + V2.CTRL, by + V2.CTRL + 3};
        }
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
            for (String action : new String[]{"admin_open", "sidebar_edit"}) {
                String label = Component.translatable("admin_open".equals(action)
                        ? "gui.module_shop.admin_open" : "gui.module_shop.layout_edit").getString();
                float w = V2.buttonWidth(label);
                rx -= w + 4;
                boolean sel = "admin_open".equals(action) && "admin".equals(this.screen.currentTab);
                float h = hover("pill." + action, over(rx, by, w, V2.CTRL));
                V2.button(c, label, sel ? V2.Btn.PRIMARY : V2.Btn.NORMAL, rx, by, w, V2.CTRL, h);
                String a = action;
                this.hits.add(new Hit(rx, by, w, V2.CTRL, () -> this.screen.onAction(a, null), null));
            }
            rx -= 4;
        }
        // 指标：余额（点开账单）、今日收入 / 支出（账单数据到了才显示）
        List<String[]> stats = new ArrayList<>();
        if (ClientShopData.billUpdatedAt > 0) {
            stats.add(new String[]{Component.translatable("gui.module_shop.v2.today_out").getString(),
                    "−" + V2.money(ClientShopData.billTodayOut), "r"});
            stats.add(new String[]{Component.translatable("gui.module_shop.v2.today_in").getString(),
                    V2.signed(ClientShopData.billTodayIn), "g"});
        }
        stats.add(new String[]{Component.translatable("gui.module_shop.v2.balance").getString(),
                V2.money(ClientShopData.balance) + " " + ClientShopData.currencyName(), ""});
        for (String[] st : stats) {
            float w = Math.max(V2.width(st[0], V2.FS_XS, false), V2.width(st[1], V2.FS + 0.5F, true)) + 14;
            rx -= w;
            boolean balance = st[2].isEmpty();
            if (balance) {
                float h = hover("stat.balance", over(rx, y, w, TOP));
                if (h > 0.01F) {
                    Draw.fill(c, rx + 2, y + 2, w - 2, TOP - 4, 3, Draw.alpha(IOStyle.ROW_HOVER, h));
                }
                this.hits.add(new Hit(rx, y, w, TOP, () -> this.screen.onAction("open_bill", null),
                        Component.translatable("gui.module_shop.bill.title")));
            }
            Draw.fill(c, rx, cy - 7, 0.5F, 14, 0, IOStyle.BORDER_DIM);
            V2.text(c, st[0], V2.FS_XS, IOStyle.TEXT_3, rx + 7, cy - 4.0F, w - 8, Text.Align.LEFT);
            int color = "r".equals(st[2]) ? IOStyle.RED : "g".equals(st[2]) ? IOStyle.GREEN : IOStyle.TEXT;
            V2.bold(c, st[1], V2.FS + 0.5F, color, rx + 7, cy + 3.5F, w - 8, Text.Align.LEFT);
        }
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

    /** 内置模块的分组（附属与未知 id 归「扩展」）。 */
    private static String groupOf(String id) {
        return switch (id) {
            case "daily", "buyback", "mall", "shipping" -> "shop";
            case "auction", "stall", "demand" -> "market";
            case "mail", "signin", "exchange" -> "mine";
            case "trade", "transfer" -> "social";
            default -> "ext";
        };
    }

    private static final String[] GROUPS = {"shop", "market", "mine", "social", "ext"};

    private static boolean builtIn(String id) {
        return !"ext".equals(groupOf(id));
    }

    /** 侧栏条目第二行的实时状态（文字, 颜色）；没有可说的返回 null。 */
    private static Object[] statusOf(String id) {
        return switch (id) {
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
        float profileH = 26.0F;
        float listBottom = y + h - profileH - 4;
        int saved = c.save();
        Draw.clip(c, x, y, RAIL, listBottom - y);
        float maxScroll = Math.max(0, this.railContentH - (listBottom - y));
        railScroll = Math.max(0, Math.min(maxScroll, railScroll));
        float cy = y + 4 - railScroll;
        float start = cy;
        for (String gname : GROUPS) {
            List<String> ids = groups.get(gname);
            if (ids.isEmpty()) {
                continue;
            }
            V2.text(c, Component.translatable("gui.module_shop.v2.group." + gname).getString(), V2.FS_XS,
                    IOStyle.TEXT_3, x + 8, cy + 6, RAIL - 12, Text.Align.LEFT);
            cy += 12;
            for (String id : ids) {
                cy = drawNav(c, id, x, cy, listBottom);
            }
            cy += 3;
        }
        this.railContentH = cy - start + 2;
        c.restoreToCount(saved);
        if (maxScroll > 0) {
            float track = listBottom - y - 4;
            float th = Math.max(10, track * (listBottom - y) / this.railContentH);
            Draw.fill(c, x + RAIL - 3, y + 2 + (track - th) * railScroll / maxScroll, 1.5F, th, 0.75F, IOStyle.BORDER);
        }
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
        float right = x + RAIL - 8;
        NotificationType type = ShopScreen.badgeOf(id);
        int count = type == null ? 0 : ClientNotifications.count(type);
        if (count > 0) {
            V2.badge(c, count, right - 4, nameCy);
            right -= 12;
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

    private void drawProfile(Canvas c, float x, float y, float w, float h) {
        float hv = hover("profile", over(x, y, w, h));
        Draw.fill(c, x, y, w, h, 4, Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hv));
        Draw.stroke(c, x, y, w, h, 4, 0.5F, IOStyle.BORDER_DIM);
        String name = ClientShopData.playerName == null || ClientShopData.playerName.isEmpty()
                ? Minecraft.getInstance().getUser().getName() : ClientShopData.playerName;
        V2.bold(c, name, V2.FS_S, IOStyle.TEXT, x + 6, y + 8, w - 12, Text.Align.LEFT);
        V2.text(c, V2.money(ClientShopData.balance) + " " + ClientShopData.currencyName(), V2.FS_XS, IOStyle.ORANGE,
                x + 6, y + 18, w - 12, Text.Align.LEFT);
        this.hits.add(new Hit(x, y, w, h, () -> this.screen.onAction("open_bill", null),
                Component.translatable("gui.module_shop.bill.title")));
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

    // =========================================================== 输入

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        float x = toUnits(mouseX);
        float y = toUnits(mouseY);
        if (this.skinOpen) {
            if (button == 0) {
                for (Hit hit : this.hits) {
                    if (hit.in(x, y) && this.skinMenu != null && this.skinMenu.length > 2
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
        ShopPage page = this.screen.activePage();
        PageFrame frame = frame(x, y);
        boolean inContent = frame.contains(x, y);
        if (!inContent) {
            if (page != null) {
                page.clickedOutside();
            }
            if (button == 0) {
                for (Hit hit : this.hits) {
                    if (hit.in(x, y)) {
                        Bricks.click();
                        hit.click().run();
                        return true;
                    }
                }
            }
            return x >= this.px && x < this.px + this.pw && y >= this.py && y < this.py + this.ph;
        }
        return page != null && page.mouseClicked(frame, x, y, button);
    }

    boolean mouseReleased(double mouseX, double mouseY, int button) {
        ShopPage page = this.screen.activePage();
        float x = toUnits(mouseX);
        float y = toUnits(mouseY);
        return page != null && page.mouseReleased(frame(x, y), x, y, button);
    }

    boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        ShopPage page = this.screen.activePage();
        float x = toUnits(mouseX);
        float y = toUnits(mouseY);
        return page != null && page.mouseDragged(frame(x, y), x, y, button, toUnits(dragX), toUnits(dragY));
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (this.skinOpen) {
            return true;
        }
        float x = toUnits(mouseX);
        float y = toUnits(mouseY);
        if (x >= this.px && x < this.px + RAIL && y >= this.py + TOP && y < this.py + this.ph) {
            railScroll -= (float) scrollY * 18.0F;
            return true;
        }
        ShopPage page = this.screen.activePage();
        PageFrame frame = frame(x, y);
        return page != null && frame.contains(x, y) && page.mouseScrolled(frame, x, y, scrollY);
    }

    /** Esc：先收起皮肤菜单。 */
    boolean escape() {
        if (this.skinOpen) {
            this.skinOpen = false;
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
