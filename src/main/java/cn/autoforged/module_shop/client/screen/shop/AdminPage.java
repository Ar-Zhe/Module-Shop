package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.AdminSecurityScreen;
import cn.autoforged.module_shop.client.screen.AnnouncementAdminScreen;
import cn.autoforged.module_shop.client.screen.BrandSettingsScreen;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.PhysicalCurrencyConfigScreen;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.ConfiguredOffer;
import cn.autoforged.module_shop.data.DemandEntry;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 管理面板（商店设置，Skija 版）。只有管理员能进（由 /shop admin 打开）。
 *
 * <ul>
 *     <li>顶部：「选择模块编辑」下拉 + 当前编辑模块 +「发放物品」「交易记录」；</li>
 *     <li>条目列表（每日商店 / 收购 / 购物城 / 出货箱规则）：一页 5 条，左键编辑、右键删除；
 *     出货箱格式错误的规则整行标红并显示原文（左右键都直接删掉）；</li>
 *     <li>摊位：下架时间说明；求购：设置摘要 + 全部求购（点一行强制撤销）；签到：与签到页共用的月历
 *     （「编辑日程」态下点日期新建 / 修改日程，右键右上角小方块清空）；</li>
 *     <li>底部第一行：各模块的设置按钮；第二行：翻页、页码、收购全清奖励金额、操作提示。</li>
 * </ul>
 *
 * 所有按钮都走 ShopScreen 原有的动作分发（{@link ShopHost#action}），行为与旧界面完全一致。
 */
public final class AdminPage implements ShopPage {
    private static final int PAGE_SIZE = 5;
    /** UI v2：条目表（整池滚动，替代 5 条一页）。 */
    private static final ListTable OFFERS = new ListTable();
    private static final ListTable DEMAND_TABLE = new ListTable();
    private static String searchText = "";
    private String offersMode = "";
    private static final float MENU_ROW = 14.0F;

    private record AdminRow(int origIndex, ConfiguredOffer offer) {}
    private record DemandRow(int origIndex, DemandEntry entry) {}

    private final SignInCalendar viewCalendar = new SignInCalendar(false, false);
    private final SignInCalendar editCalendar = new SignInCalendar(true, false);
    private SkiaTextField search;
    private final Button moduleBtn = small(() -> Component.translatable("gui.module_shop.admin_select_module")
            .getString());
    private final Button grantBtn = small(() -> Component.translatable("gui.module_shop.admin_grant_open").getString());
    private final Button annBtn = small(() -> Component.translatable("gui.module_shop.admin_announcements").getString());
    private final Button physBtn = small(() -> Component.translatable("gui.module_shop.admin_phys_currency").getString());
    private final Button brandBtn = small(() -> ClientHomeCurrencyState.tr("gui.module_shop.brand.btn", "品牌图标"));
    private final Button securityBtn = small(() -> {
        if (ClientShopData.adminLockEnabled && !ClientShopData.adminUnlocked) {
            return ClientHomeCurrencyState.tr("gui.module_shop.auth.btn_locked", "🔒 解锁");
        }
        if (ClientShopData.adminLockEnabled) {
            return ClientHomeCurrencyState.tr("gui.module_shop.auth.btn_on", "密码锁·已解锁");
        }
        return ClientHomeCurrencyState.tr("gui.module_shop.auth.btn_off", "密码锁");
    });
    private final Button historyBtn = small(() -> Component.translatable("gui.module_shop.admin_history").getString());
    private final Button cfgBtn = small(() -> Component.translatable("gui.module_shop.cfg.button").getString());
    private final Button prevBtn = small(() -> Component.translatable("gui.module_shop.page_prev").getString());
    private final Button nextBtn = small(() -> Component.translatable("gui.module_shop.page_next").getString());
    /** 底部第一行的模块设置按钮（每帧按当前模块重新配置）。 */
    private final List<Button> actions = new ArrayList<>();

    private boolean menuOpen;

    // 本帧几何
    private float menuX, menuY, menuW, menuH;
    private float listX, listY, listW;
    private List<ConfiguredOffer> pool = List.of();
    private List<AdminRow> filteredOffers = List.of();
    private List<DemandRow> filteredDemands = List.of();

    private static Button small(Supplier<String> label) {
        return new Button(Button.Kind.NORMAL, label, null).radius(2.0F).fontSize(Text.SMALL);
    }

    @Override
    public String id() {
        return "admin";
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 64)
                .placeholder(Component.translatable("gui.module_shop.search.placeholder").getString())
                .clearable(true)
                .fontSize(V2.FS_S)
                .onChange(text -> {
                    searchText = text == null ? "" : text;
                    OFFERS.scrollToTop();
                    DEMAND_TABLE.scrollToTop();
                });
        search.setValue(searchText);
        host.registerInput(search.editBox());
    }

    private SignInCalendar calendar(ShopHost host) {
        return host.signInEditing() ? editCalendar : viewCalendar;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        ShopHost host = f.host();
        String mode = host.adminMode();
        if (!mode.equals(offersMode)) {
            offersMode = mode;
            OFFERS.scrollToTop();
            DEMAND_TABLE.scrollToTop();
        }
        boolean mall = "mall".equals(mode);
        boolean ship = "shipping".equals(mode);
        boolean stalls = "stall".equals(mode);
        boolean demands = "demand".equals(mode);
        boolean signin = "signin".equals(mode);
        boolean buyback = "buyback".equals(mode);
        boolean shop = !mall && !ship && !stalls && !demands && !signin && !buyback;
        float cx = f.x();
        float cy = f.y();
        float cw = f.w();

        // ---- 顶部：模块下拉 / 当前模块 / 管理动作按钮（窄屏自动换行，绝不把按钮文字挤成单字省略号）----
        float ddW = Math.max(70.0F, Text.width(Component.translatable("gui.module_shop.admin_select_module")
                .getString(), AtomUi.REGULAR, Text.SMALL) + 24.0F);
        moduleBtn.kind(menuOpen ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> menuOpen = !menuOpen)
                .bounds(cx + 4.0F, cy + 2.0F, ddW, 14.0F);
        moduleBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        // 下拉小三角（直接画，不依赖字体里有没有 ▾）
        float tx = cx + 4.0F + ddW - 9.0F;
        float ty = cy + 8.0F;
        int tri = menuOpen ? 0xFFFFFFFF : IOStyle.TEXT_2;
        for (int r = 0; r < 3; r++) {
            Draw.fill(canvas, tx - 2.5F + r * 0.83F, ty + r, 5.0F - r * 1.66F, 1.0F, 0.0F, tri);
        }
        float currentX = cx + 4.0F + ddW + 6.0F;
        String currentLabel = Component.translatable("gui.module_shop.admin_current", host.moduleName(mode)).getString();
        float currentNeedW = Math.max(66.0F, Text.width(currentLabel, AtomUi.REGULAR, Text.SMALL) + 4.0F);
        boolean lockedOut = ClientShopData.adminLockEnabled && !ClientShopData.adminUnlocked;
        securityBtn.kind(lockedOut ? Button.Kind.DANGER
                : (ClientShopData.adminLockEnabled ? Button.Kind.ACCENT : Button.Kind.NORMAL));
        Button[] topButtons = {historyBtn, cfgBtn, securityBtn, brandBtn, physBtn, annBtn, grantBtn};
        Runnable[] topActions = {
                () -> ClientAdminAuth.guard(() -> host.action("admin_history_gui", null)),
                () -> ClientAdminAuth.guard(() -> host.action("cfg_open", null)),
                () -> ClientAdminAuth.guard(() -> net.minecraft.client.Minecraft.getInstance().execute(
                        () -> Nav.open(new AdminSecurityScreen(host.screen())))),
                () -> ClientAdminAuth.guard(() -> net.minecraft.client.Minecraft.getInstance().execute(
                        () -> Nav.open(new BrandSettingsScreen(host.screen())))),
                () -> ClientAdminAuth.guard(() -> net.minecraft.client.Minecraft.getInstance().execute(
                        () -> Nav.open(new PhysicalCurrencyConfigScreen()))),
                () -> ClientAdminAuth.guard(() -> net.minecraft.client.Minecraft.getInstance().execute(
                        () -> Nav.open(new AnnouncementAdminScreen()))),
                () -> ClientAdminAuth.guard(() -> host.action("admin_grant_open", null))
        };
        float[] widths = new float[topButtons.length];
        float totalTopW = (topButtons.length - 1) * 4.0F;
        for (int i = 0; i < topButtons.length; i++) {
            float bw = Math.max(38.0F, Text.width(topButtons[i].labelText(), AtomUi.REGULAR, Text.SMALL) + 10.0F);
            widths[i] = bw;
            totalTopW += bw;
        }
        boolean singleTopRow = currentX + currentNeedW + 8.0F + totalTopW <= cx + cw - 4.0F;
        float topAreaBottom;
        if (singleTopRow) {
            float cursorX = cx + cw - 4.0F;
            for (int i = 0; i < topButtons.length; i++) {
                float bw = widths[i];
                cursorX -= bw;
                topButtons[i].onClick(topActions[i]).bounds(cursorX, cy + 2.0F, bw, 14.0F);
                topButtons[i].render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
                if (i < topButtons.length - 1) {
                    cursorX -= 4.0F;
                }
            }
            if (cursorX - 6.0F - currentX > 40.0F) {
                Text.drawLine(canvas, currentLabel, AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                        currentX, cy + 9.0F, cursorX - 6.0F - currentX, Text.Align.LEFT);
            }
            topAreaBottom = cy + 18.0F;
        } else {
            float curAvail = Math.max(40.0F, (cx + cw - 6.0F) - currentX);
            Text.drawLine(canvas, currentLabel, AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                    currentX, cy + 9.0F, curAvail, Text.Align.LEFT);
            float rowY2 = cy + 18.0F;
            float rowAvail = Math.max(80.0F, cw - 8.0F);
            float compactGap = 3.0F;
            float sumCompact = (topButtons.length - 1) * compactGap;
            for (float w : widths) {
                sumCompact += w;
            }
            float scaleW = sumCompact > rowAvail ? rowAvail / sumCompact : 1.0F;
            float cursorX = cx + 4.0F;
            for (int i = 0; i < topButtons.length; i++) {
                float bw = Math.max(30.0F, (float) Math.floor(widths[i] * scaleW));
                topButtons[i].onClick(topActions[i]).bounds(cursorX, rowY2, bw, 14.0F);
                topButtons[i].render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
                cursorX += bw + compactGap;
            }
            topAreaBottom = cy + 34.0F;
        }

        boolean searchable = !stalls && !signin;
        String query = searchText == null ? "" : searchText.trim();
        pool = (stalls || demands || signin) ? List.of()
                : (mall ? ClientShopData.adminMall
                        : (ship ? ClientShopData.adminShipping
                                : (shop ? ClientShopData.adminShop : ClientShopData.adminBuyback)));
        if (!pool.isEmpty()) {
            List<AdminRow> rows = new ArrayList<>(pool.size());
            for (int i = 0; i < pool.size(); i++) {
                ConfiguredOffer offer = pool.get(i);
                if (query.isEmpty() || matchesAdminOffer(offer, ship, i, query)) {
                    rows.add(new AdminRow(i, offer));
                }
            }
            filteredOffers = rows;
        } else {
            filteredOffers = List.of();
        }
        if (demands) {
            List<DemandEntry> allDemands = ClientShopData.demands;
            List<DemandRow> dRows = new ArrayList<>(allDemands.size());
            for (int i = 0; i < allDemands.size(); i++) {
                DemandEntry e = allDemands.get(i);
                if (query.isEmpty() || matchesDemand(e, query)) {
                    dRows.add(new DemandRow(i, e));
                }
            }
            filteredDemands = dRows;
        } else {
            filteredDemands = List.of();
        }

        if (searchable && search != null) {
            float sy = topAreaBottom + 1.0F;
            float searchW = Math.min(220.0F, Math.max(110.0F, cw * 0.42F));
            search.visible(true).bounds(cx + 4.0F, sy, searchW, V2.CTRL);
            search.render(canvas, menuOpen ? -1 : f.mouseX(), menuOpen ? -1 : f.mouseY(), f.dtMs());
            float noteX = cx + 4.0F + searchW + 8.0F;
            float noteW = Math.max(0.0F, cx + cw - 6.0F - noteX);
            if (noteW > 24.0F) {
                int totalCnt = demands ? ClientShopData.demands.size() : pool.size();
                int matchCnt = demands ? filteredDemands.size() : filteredOffers.size();
                if (!query.isEmpty() && totalCnt > 0) {
                    V2.text(canvas, Component.translatable("gui.module_shop.search.match_count", matchCnt, totalCnt)
                                    .getString(), V2.FS_S, IOStyle.TEXT_2, noteX, sy + V2.CTRL / 2.0F, noteW,
                            Text.Align.RIGHT);
                }
            }
            topAreaBottom = sy + V2.CTRL + 2.0F;
        } else if (search != null) {
            search.visible(false);
            if (search.isFocused()) {
                search.blur();
            }
        }

        // ---- 内容 ----
        int pages = Math.max(1, (pool.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        host.clampAdminPage(pages);
        listX = cx + 4.0F;
        listY = topAreaBottom + 2.0F;
        listW = cw - 8.0F;
        if (signin) {
            calendar(host).hoverEnabled(!menuOpen).render(canvas, f, cx, topAreaBottom + 1.0F, cw);
        } else if (!pool.isEmpty()) {
            renderOffers(canvas, f, mode, mall, ship, shop, query);
        } else if (stalls) {
            renderStallHint(canvas, f, topAreaBottom + 8.0F);
        } else if (demands) {
            renderDemands(canvas, f, query);
        } else {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.admin_empty").getString(),
                    cx, listY + 38.0F, cw);
        }

        // ---- 底部两行 ----
        float rowA = f.barTop() - 16.0F;
        float rowB = f.barTop() + 1.0F;
        configureActions(host, mode, mall, ship, stalls, demands, signin);
        int columns = Math.max(1, actions.size());
        float btnW = Math.max(44.0F, (cw - 8.0F - (columns - 1) * 6.0F) / columns);
        for (int i = 0; i < actions.size(); i++) {
            Button b = actions.get(i);
            b.bounds(cx + 4.0F + i * (btnW + 6.0F), rowA, btnW, 14.0F);
            b.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }
        if (signin) {
            prevBtn.visible(false);
            nextBtn.visible(false);
            String line = host.signInEditing()
                    ? Component.translatable("gui.module_shop.signin.edit_mode").getString()
                    : Component.translatable("gui.module_shop.signin.admin_line1",
                            PriceDisplay.formatPlain(ClientShopData.signInBaseSpec),
                            PriceDisplay.formatPlain(ClientShopData.signInMakeupSpec),
                            ClientShopData.signInMakeupDays).getString();
            Text.drawLine(canvas, line, AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                    cx + 4.0F, rowB + 7.0F, cw - 10.0F, Text.Align.LEFT);
        } else {
            if (pool.isEmpty() && !demands) {
                renderPager(canvas, f, pages, rowB);
            } else {
                prevBtn.visible(false);
                nextBtn.visible(false);
                int totalCnt = demands ? ClientShopData.demands.size() : pool.size();
                int matchCnt = demands ? filteredDemands.size() : filteredOffers.size();
                String footText = !query.isEmpty() && totalCnt > 0
                        ? Component.translatable("gui.module_shop.search.match_count", matchCnt, totalCnt).getString()
                        : Component.translatable("gui.module_shop.v2.admin.count", totalCnt).getString();
                V2.text(canvas, footText, V2.FS_S, IOStyle.TEXT_2, cx + 4.0F, rowB + 7.0F, cw - 10.0F, Text.Align.LEFT);
            }
        }

        // ---- 「选择模块编辑」下拉：最后画，盖在列表之上 ----
        if (menuOpen) {
            renderMenu(canvas, f, cx + 4.0F, cy + 18.0F, mode);
        }
    }

    private static boolean matchesAdminOffer(ConfiguredOffer offer, boolean ship, int origIndex, String query) {
        if (PageKit.matchesItemQuery(offer.stack, query)) {
            return true;
        }
        String rarity = ship ? "" : Component.translatable(offer.rarity.translationKey()).getString();
        String price = PriceDisplay.formatPlain(offer.resolvePriceSpec());
        String raw = ship && origIndex < ClientShopData.adminShippingRaw.size()
                ? ClientShopData.adminShippingRaw.get(origIndex) : "";
        String err = ship && origIndex < ClientShopData.adminShippingErrors.size()
                ? ClientShopData.adminShippingErrors.get(origIndex) : "";
        return PageKit.matchesQuery(query, rarity, price, raw, err);
    }

    private static boolean matchesDemand(DemandEntry e, String query) {
        if (PageKit.matchesQuery(query, e.requesterName, e.summary().getString(), DemandPage.rewardLine(e))) {
            return true;
        }
        if (PageKit.matchesItemQuery(e.rewardItem, query)) {
            return true;
        }
        for (DemandEntry.WantLine line : e.wants) {
            if (PageKit.matchesItemQuery(line.stack, query)) {
                return true;
            }
        }
        return false;
    }

    /** 底部第一行的按钮：按当前模块配置标签、选中态与动作（列表本身复用，避免每帧新建按钮丢动画）。 */
    private void configureActions(ShopHost host, String mode, boolean mall, boolean ship, boolean stalls,
            boolean demands, boolean signin) {
        List<Object[]> specs = new ArrayList<>();
        if (signin) {
            specs.add(new Object[]{"gui.module_shop.signin.setting_short", "admin_signin_set", false});
            specs.add(new Object[]{"gui.module_shop.signin.cumulative", "admin_signin_cumulative_toggle",
                    ClientShopData.signInCumulative});
            specs.add(new Object[]{"gui.module_shop.signin.auto", "admin_signin_auto_toggle", ClientShopData.signInAuto});
            specs.add(new Object[]{"gui.module_shop.signin.schedule", "admin_signin_schedule", host.signInEditing()});
        } else if (demands) {
            specs.add(new Object[]{"gui.module_shop.demand.settings", "demand_settings", ClientShopData.demandEnabled});
            specs.add(new Object[]{"gui.module_shop.demand.expire", "demand_expire",
                    !"permanent".equals(ClientShopData.demandExpireMode)});
        } else if (stalls) {
            specs.add(new Object[]{"gui.module_shop.stall.expire", "stall_expire",
                    !"permanent".equals(ClientShopData.stallExpireMode)});
        } else {
            specs.add(new Object[]{"gui.module_shop.admin_add", "admin_add", false});
            if (mall) {
                specs.add(new Object[]{"gui.module_shop.mall.import_mod", "mall_import", false});
                specs.add(new Object[]{"gui.module_shop.mall.restock", "mall_restock", ClientShopData.mallRestockOn});
                specs.add(new Object[]{"gui.module_shop.mall.refresh_btn", "mall_refresh",
                        ClientShopData.mallStockPersonal || !"off".equals(ClientShopData.mallRefreshMode)});
            } else if (ship) {
                specs.add(new Object[]{"gui.module_shop.shipping.daily_limits", "shipping_limits", false});
                specs.add(new Object[]{"gui.module_shop.admin_system_costs", "admin_system_costs", false});
            } else {
                specs.add(new Object[]{"gui.module_shop.admin_gen", "admin_gen_set", false});
                specs.add(new Object[]{"gui.module_shop.admin_system_costs", "admin_system_costs", false});
            }
        }
        while (actions.size() < specs.size()) {
            actions.add(small(() -> ""));
        }
        while (actions.size() > specs.size()) {
            actions.remove(actions.size() - 1).cancelPress();
        }
        for (int i = 0; i < specs.size(); i++) {
            String key = (String) specs.get(i)[0];
            String action = (String) specs.get(i)[1];
            boolean on = (Boolean) specs.get(i)[2];
            Button b = actions.get(i);
            String label = "gui.module_shop.admin_system_costs".equals(key)
                    ? ClientHomeCurrencyState.tr(key, "系统费用设置")
                    : Component.translatable(key).getString();
            b.label(label);
            b.kind(on ? Button.Kind.ACCENT : Button.Kind.NORMAL);
            b.onClick(() -> ClientAdminAuth.guard(() -> {
                CompoundTag extra = null;
                if ("admin_add".equals(action) || "admin_system_costs".equals(action)) {
                    extra = new CompoundTag();
                    extra.putString("mode", mode);
                }
                host.action(action, extra);
            }));
        }
    }

    private void renderOffers(Canvas canvas, PageFrame f, String mode, boolean mall, boolean ship, boolean shop,
                              String query) {
        float top = listY;
        float bottom = f.barTop() - 20.0F;
        float x = f.x();
        float w = f.w();
        String removeLabel = Component.translatable("gui.module_shop.v2.remove").getString();
        String editLabel = Component.translatable("gui.module_shop.v2.edit").getString();
        float btnColW = Math.max(78.0F, V2.buttonWidth(removeLabel) + V2.buttonWidth(editLabel) + 8.0F);
        float numW = Math.max(24.0F, V2.width(String.valueOf(Math.max(100, pool.size())), V2.FS_XS, false) + 6.0F);
        boolean tight = w < 520.0F;
        float rarityW = ship ? (tight ? 38.0F : 44.0F) : (tight ? 46.0F : 58.0F);
        float priceW = tight ? 72.0F : 88.0F;
        float amtW = tight ? 64.0F : 82.0F;
        float[][] cols = V2.columns(x, w, 10, 6, 14, 0, rarityW, priceW, amtW, numW, btnColW);
        String[] heads = {"", Component.translatable("gui.module_shop.v2.col.item").getString(),
                ship ? "" : Component.translatable("gui.module_shop.v2.col.rarity").getString(),
                Component.translatable("gui.module_shop.v2.col.price").getString(),
                Component.translatable(mall ? "gui.module_shop.v2.col.stock" : ship ? "gui.module_shop.v2.col.daily_cap"
                        : "gui.module_shop.v2.col.amount").getString(), "#", ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT};
        OFFERS.layout(x, top, w, bottom - top, cols, heads, al, null);
        if (filteredOffers.isEmpty()) {
            V2.tableHead(canvas, x, top, w, cols, heads, al, -1, false);
            V2.empty(canvas, Component.translatable(query.isEmpty() ? "gui.module_shop.admin_empty"
                    : "gui.module_shop.search.empty").getString(), x, top + V2.HEAD, w, Math.max(24.0F, bottom - top - V2.HEAD));
            return;
        }
        OFFERS.render(canvas, menuOpen ? null : f.host().items(), menuOpen ? -1 : f.mouseX(), menuOpen ? -1 : f.mouseY(), f.dtMs(),
                filteredOffers.size(), (cv, rowIdx, ry, hover) -> {
            AdminRow row = filteredOffers.get(rowIdx);
            int origIndex = row.origIndex();
            ConfiguredOffer offer = row.offer();
            float cy = ry + V2.ROW / 2.0F;
            String error = ship && origIndex < ClientShopData.adminShippingErrors.size()
                    ? ClientShopData.adminShippingErrors.get(origIndex) : "";
            float right = cols[6][0] + cols[6][1];
            if (ship && !error.isEmpty()) {
                String raw = origIndex < ClientShopData.adminShippingRaw.size()
                        ? ClientShopData.adminShippingRaw.get(origIndex) : "";
                Draw.fill(cv, x, ry, w, V2.ROW, 0, Draw.alpha(IOStyle.RED, 0.12F));
                Draw.fill(cv, x, ry, 1.5F, V2.ROW, 0, IOStyle.RED);
                V2.text(cv, raw, V2.FS_S, IOStyle.RED, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
                V2.text(cv, Component.translatable("gui.module_shop.shipping.row_broken").getString(), V2.FS_XS, IOStyle.RED,
                        cols[2][0], cy, cols[4][0] + cols[4][1] - cols[2][0], Text.Align.LEFT);
                OFFERS.button(cv, rowIdx, "remove", removeLabel, V2.Btn.DANGER, right, ry, hover);
                return;
            }
            Draw.fill(cv, cols[0][0] - 5, ry + 3, 1.5F, V2.ROW - 6, 0.75F, 0xFF000000 | offer.rarity.color());
            if (!menuOpen) {
                f.host().items().add(offer.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, true);
            }
            V2.text(cv, PageKit.plainName(offer.stack), V2.FS, IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            if (!ship) {
                V2.bold(cv, Component.translatable(offer.rarity.translationKey()).getString(), V2.FS_S,
                        0xFF000000 | offer.rarity.color(), cols[2][0], cy, cols[2][1], Text.Align.LEFT);
            }
            PriceDisplay.drawCell(cv, menuOpen ? null : f.host().items(), offer.resolvePriceSpec(),
                    cols[3][0], cy, cols[3][1], Text.Align.RIGHT, IOStyle.ORANGE, false);
            String amount = mall && offer.amount < 0 ? "∞" : amountText(offer, mall, ship, shop);
            if (mall && origIndex >= 0 && origIndex < ClientShopData.adminMallPermanent.size()
                    && Boolean.TRUE.equals(ClientShopData.adminMallPermanent.get(origIndex))) {
                amount = amount + " · 永限";
            }
            V2.text(cv, amount.isEmpty() ? "—" : amount, V2.FS_S, IOStyle.TEXT_2, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
            V2.text(cv, String.valueOf(origIndex + 1), V2.FS_XS, IOStyle.TEXT_3, cols[5][0], cy, cols[5][1], Text.Align.RIGHT);
            float bx = OFFERS.button(cv, rowIdx, "remove", removeLabel, V2.Btn.DANGER, right, ry, hover);
            OFFERS.button(cv, rowIdx, "edit", editLabel, V2.Btn.NORMAL, bx, ry, hover);
        });
    }

    /**
     * 数量列：购物城是库存（-1 = 无限）、出货箱是每日份数（0 = 不限，留空），
     * 每日商店 / 收购显示实际生效的上限（未配置时用默认值）。
     */
    private static String amountText(ConfiguredOffer offer, boolean mall, boolean ship, boolean shop) {
        if (mall) {
            return offer.amount < 0
                    ? Component.translatable("gui.module_shop.mall.stock_unlimited").getString()
                    : Component.translatable("gui.module_shop.admin_stock", offer.amount).getString();
        }
        if (ship) {
            return offer.amount <= 0 ? ""
                    : Component.translatable("gui.module_shop.admin_daily", offer.amount).getString();
        }
        int effective = shop ? offer.amountOr(offer.rarity.purchaseLimit())
                : offer.amountOr(Config.INSTANCE.buybackCapacity.get());
        return Component.translatable("gui.module_shop.admin_amount", effective).getString();
    }

    /** 摊位：没有可编辑的条目，只说明下架时间设置。 */
    private void renderStallHint(Canvas canvas, PageFrame f, float topY) {
        float x = f.x() + 8.0F;
        float w = f.w() - 16.0F;
        Component current = "permanent".equals(ClientShopData.stallExpireMode) || ClientShopData.stallExpireAmount <= 0
                ? Component.translatable("gui.module_shop.choice.expire_mode.permanent")
                : Component.translatable("gui.module_shop.stall.expire_value",
                        Component.translatable("gui.module_shop.choice.expire_mode." + ClientShopData.stallExpireMode),
                        ClientShopData.stallExpireAmount);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.admin_stall").getString(), AtomUi.BOLD,
                Text.BODY, IOStyle.TEXT, x, topY, w, Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.admin_stall_hint").getString(), AtomUi.REGULAR,
                Text.SMALL, IOStyle.TEXT_2, x, topY + 15.0F, w, Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.stall.expire_current", current).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE, x, topY + 28.0F, w, Text.Align.LEFT);
    }

    /** 求购：设置摘要 + 全部求购列表（完整滚动，支持搜索与拖动滚动条，点击行强制撤销）。 */
    private void renderDemands(Canvas canvas, PageFrame f, String query) {
        List<DemandEntry> list = ClientShopData.demands;
        Component summary = Component.translatable("gui.module_shop.demand.admin_summary",
                list.size(), ClientShopData.demandSlots, (int) Math.round(ClientShopData.demandFeePercent),
                ClientShopData.demandMaxReward <= 0L ? Component.translatable("gui.module_shop.demand.no_limit")
                        : Component.literal(ClientShopData.money(ClientShopData.demandMaxReward)),
                "permanent".equals(ClientShopData.demandExpireMode) || ClientShopData.demandExpireAmount <= 0
                        ? Component.translatable("gui.module_shop.choice.expire_mode.permanent")
                        : Component.translatable("gui.module_shop.stall.expire_value",
                                Component.translatable("gui.module_shop.choice.expire_mode."
                                        + ClientShopData.demandExpireMode),
                                ClientShopData.demandExpireAmount));
        float sumY = listY + 4.0F;
        Text.drawLine(canvas, summary.getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                f.x() + 6.0F, sumY, f.w() - 12.0F, Text.Align.LEFT);
        float top = listY + 12.0F;
        float bottom = f.barTop() - 20.0F;
        float x = f.x();
        float w = f.w();
        String removeLabel = Component.translatable("gui.module_shop.v2.remove").getString();
        float btnW = Math.max(40.0F, V2.buttonWidth(removeLabel) + 6.0F);
        float[][] cols = V2.columns(x, w, 10, 6, 14, 0, 108, 72, btnW);
        String[] heads = {"", Component.translatable("gui.module_shop.v2.col.item").getString(),
                Component.translatable("gui.module_shop.v2.col.reward").getString(),
                Component.translatable("gui.module_shop.v2.col.player").getString(), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT};
        DEMAND_TABLE.layout(x, top, w, Math.max(24.0F, bottom - top), cols, heads, al, null);
        if (list.isEmpty()) {
            V2.tableHead(canvas, x, top, w, cols, heads, al, -1, false);
            V2.empty(canvas, Component.translatable("gui.module_shop.demand.empty").getString(),
                    x, top + V2.HEAD, w, Math.max(24.0F, bottom - top - V2.HEAD));
            return;
        }
        if (filteredDemands.isEmpty()) {
            V2.tableHead(canvas, x, top, w, cols, heads, al, -1, false);
            V2.empty(canvas, Component.translatable("gui.module_shop.search.empty").getString(),
                    x, top + V2.HEAD, w, Math.max(24.0F, bottom - top - V2.HEAD));
            return;
        }
        DEMAND_TABLE.render(canvas, menuOpen ? null : f.host().items(), menuOpen ? -1 : f.mouseX(), menuOpen ? -1 : f.mouseY(),
                f.dtMs(), filteredDemands.size(), (cv, rowIdx, ry, hover) -> {
            DemandEntry e = filteredDemands.get(rowIdx).entry();
            float cy = ry + V2.ROW / 2.0F;
            ItemStack want = e.wants.isEmpty() ? ItemStack.EMPTY : e.wants.get(0).display();
            if (!menuOpen) {
                f.host().items().add(want, cols[0][0], ry + (V2.ROW - V2.ICON) / 2.0F, V2.ICON, true);
            }
            V2.text(cv, e.summary().getString(), V2.FS, IOStyle.TEXT, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            V2.text(cv, DemandPage.rewardLine(e), V2.FS_S, IOStyle.ORANGE, cols[2][0], cy, cols[2][1], Text.Align.LEFT);
            String who = e.requesterName == null || e.requesterName.isBlank() ? "?" : e.requesterName;
            V2.text(cv, who, V2.FS_S, IOStyle.TEXT_2, cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
            DEMAND_TABLE.button(cv, rowIdx, "cancel", removeLabel, V2.Btn.DANGER, cols[4][0] + cols[4][1], ry, hover);
        });
    }

    /** 底栏第二行：翻页 → 页码 → 收购全清奖励金额 → 操作提示，从左往右按实际宽度依次排。 */
    private void renderPager(Canvas canvas, PageFrame f, int pages, float rowB) {
        ShopHost host = f.host();
        float labelY = rowB + 7.0F;
        float cursor = f.x() + 4.0F;
        prevBtn.visible(pages > 1);
        nextBtn.visible(pages > 1);
        if (pages > 1) {
            prevBtn.enabled(host.adminPage() > 0).bounds(cursor, rowB, 42.0F, 14.0F);
            prevBtn.onClick(() -> host.action("admin_prev", null));
            prevBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            nextBtn.enabled(host.adminPage() < pages - 1).bounds(cursor + 46.0F, rowB, 42.0F, 14.0F);
            nextBtn.onClick(() -> host.action("admin_next", null));
            nextBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            cursor += 94.0F;
            String page = Component.translatable("gui.module_shop.page", host.adminPage() + 1, pages).getString();
            cursor += Text.drawLine(canvas, page, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, cursor, labelY,
                    Text.width(page, AtomUi.REGULAR, Text.SMALL) + 1.0F, Text.Align.LEFT) + 12.0F;
        }
        String bonus = Component.translatable("gui.module_shop.buyback_bonus_value",
                PriceDisplay.formatPlain(ClientShopData.buybackBonusSpec)).getString();
        cursor += Text.drawLine(canvas, bonus, AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE, cursor, labelY,
                Text.width(bonus, AtomUi.REGULAR, Text.SMALL) + 1.0F, Text.Align.LEFT) + 12.0F;
        float right = f.x() + f.w() - 6.0F;
        if (right - cursor > 20.0F) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.admin_hint").getString(), AtomUi.REGULAR,
                    Text.SMALL, IOStyle.TEXT_2, cursor, labelY, right - cursor, Text.Align.LEFT);
        }
    }

    private void renderMenu(Canvas canvas, PageFrame f, float x, float y, String mode) {
        List<String> modules = f.host().adminModules();
        float w = moduleBtn.width();
        for (String id : modules) {
            w = Math.max(w, Text.width(f.host().moduleName(id).getString(), AtomUi.REGULAR, Text.SMALL) + 30.0F);
        }
        menuX = x;
        menuY = y;
        menuW = w;
        menuH = modules.size() * MENU_ROW + 4.0F;
        Draw.fill(canvas, x + 1.0F, y + 2.0F, menuW, menuH, 3.0F, IOStyle.SHADOW_2);
        Draw.fill(canvas, x, y, menuW, menuH, 3.0F, IOStyle.CARD);
        Draw.stroke(canvas, x, y, menuW, menuH, 3.0F, 1.0F, IOStyle.BORDER);
        for (int i = 0; i < modules.size(); i++) {
            String id = modules.get(i);
            float ry = y + 2.0F + i * MENU_ROW;
            boolean current = id.equals(mode);
            boolean hover = f.mouseX() >= x + 2.0F && f.mouseX() < x + menuW - 2.0F
                    && f.mouseY() >= ry && f.mouseY() < ry + MENU_ROW;
            if (current || hover) {
                Draw.fill(canvas, x + 2.0F, ry, menuW - 4.0F, MENU_ROW, 2.0F,
                        current ? IOStyle.BLUE_TINT : IOStyle.ROW_HOVER);
            }
            Text.drawLine(canvas, f.host().moduleName(id).getString(), AtomUi.REGULAR, Text.SMALL,
                    current ? IOStyle.TEXT : IOStyle.TEXT_2, x + 8.0F, ry + MENU_ROW / 2.0F, menuW - 24.0F,
                    Text.Align.LEFT);
            if (current) {
                Draw.fill(canvas, x + menuW - 11.0F, ry + 5.0F, 4.0F, 4.0F, 1.0F, IOStyle.BLUE);
            }
        }
    }

    // ------------------------------------------------------------------ 输入

    private List<Button> buttons() {
        List<Button> all = new ArrayList<>(actions);
        all.add(moduleBtn);
        all.add(grantBtn);
        all.add(annBtn);
        all.add(physBtn);
        all.add(brandBtn);
        all.add(securityBtn);
        all.add(cfgBtn);
        all.add(historyBtn);
        all.add(prevBtn);
        all.add(nextBtn);
        return all;
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        ShopHost host = f.host();
        // 下拉展开：点选项切换模块，点别处只收起
        if (menuOpen) {
            if (button == 0 && mx >= menuX && mx < menuX + menuW && my >= menuY + 2.0F && my < menuY + menuH - 2.0F) {
                int row = (int) ((my - menuY - 2.0F) / MENU_ROW);
                List<String> modules = host.adminModules();
                if (row >= 0 && row < modules.size()) {
                    ClickSound.play();
                    CompoundTag pick = new CompoundTag();
                    pick.putString("id", modules.get(row));
                    ClientAdminAuth.guard(() -> host.action("admin_module_pick", pick));
                }
                menuOpen = false;
                return true;
            }
            if (!moduleBtn.isMouseOver(mx, my)) {
                menuOpen = false;
                return true;
            }
        }
        if (search != null && search.isVisible() && search.mouseClicked(mx, my, button)) {
            return true;
        }
        if (search != null && search.isFocused()) {
            search.blur();
        }
        for (Button b : buttons()) {
            if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        String mode = host.adminMode();
        if ("signin".equals(mode)) {
            if (host.signInEditing() && ClientShopData.adminLockEnabled && !ClientShopData.adminUnlocked) {
                if (button == 0 || button == 1) {
                    ClientAdminAuth.guard(() -> calendar(host).mouseClicked(f, mx, my, button));
                    return true;
                }
                return false;
            }
            return calendar(host).mouseClicked(f, mx, my, button);
        }
        if ("demand".equals(mode)) {
            if (button == 0 && DEMAND_TABLE.pressBar(mx, my)) {
                return true;
            }
            ListTable.Hit hit = DEMAND_TABLE.click(mx, my);
            if (hit == null || hit.row() < 0 || hit.row() >= filteredDemands.size()) {
                return hit != null;
            }
            if (button == 0) {
                ClickSound.play();
                CompoundTag extra = new CompoundTag();
                extra.putString("id", filteredDemands.get(hit.row()).entry().id);
                ClientAdminAuth.guard(() -> host.action("demand_admin_cancel", extra));
                return true;
            }
            return false;
        }
        // 条目表：左键编辑（「删除」按钮 / 右键 = 删除；格式错误的出货箱规则：左右键都删除）
        if (!filteredOffers.isEmpty()) {
            if (button == 0 && OFFERS.pressBar(mx, my)) {
                return true;
            }
            ListTable.Hit hit = OFFERS.click(mx, my);
            if (hit == null || hit.row() < 0 || hit.row() >= filteredOffers.size()) {
                return hit != null;
            }
            if (button != 0 && button != 1) {
                return false;
            }
            int origIndex = filteredOffers.get(hit.row()).origIndex();
            boolean broken = "shipping".equals(mode) && origIndex < ClientShopData.adminShippingErrors.size()
                    && !ClientShopData.adminShippingErrors.get(origIndex).isEmpty();
            boolean remove = button == 1 || "remove".equals(hit.button());
            CompoundTag extra = new CompoundTag();
            extra.putString("mode", mode);
            extra.putInt("index", origIndex);
            ClickSound.play();
            String action = broken ? (button == 0 ? "admin_remove" : "admin_remove_broken")
                    : (remove ? "admin_remove" : "admin_edit_gui");
            ClientAdminAuth.guard(() -> host.action(action, extra));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = OFFERS.releaseBar() | DEMAND_TABLE.releaseBar();
        if (search != null) {
            handled |= search.mouseReleased(mx, my, button);
        }
        for (Button b : buttons()) {
            handled |= b.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        if ("demand".equals(f.host().adminMode())) {
            return DEMAND_TABLE.dragBar(my);
        }
        return !filteredOffers.isEmpty() && OFFERS.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (menuOpen || "signin".equals(f.host().adminMode())) {
            return true;
        }
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        if ("demand".equals(f.host().adminMode()) && !filteredDemands.isEmpty()) {
            return DEMAND_TABLE.wheel(mx, my, scrollY, page);
        }
        // 条目表：平滑滚动（其余模块没有列表）
        if (!filteredOffers.isEmpty()) {
            return OFFERS.wheel(mx, my, scrollY, page);
        }
        return true;
    }

    @Override
    public void clickedOutside() {
        menuOpen = false;
        if (search != null && search.isFocused()) {
            search.blur();
        }
    }

    @Override
    public void cancelInput() {
        menuOpen = false;
        if (search != null) {
            search.visible(false);
        }
        OFFERS.releaseBar();
        DEMAND_TABLE.releaseBar();
        for (Button b : buttons()) {
            b.cancelPress();
        }
    }
}
