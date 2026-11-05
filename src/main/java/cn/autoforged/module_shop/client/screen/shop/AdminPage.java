package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
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
    private static final float ROW_H = 22.0F;
    private static final float ROW_CARD = 20.0F;
    private static final float MENU_ROW = 14.0F;

    private final SignInCalendar viewCalendar = new SignInCalendar(false, false);
    private final SignInCalendar editCalendar = new SignInCalendar(true, false);
    private final Button moduleBtn = small(() -> Component.translatable("gui.module_shop.admin_select_module")
            .getString());
    private final Button grantBtn = small(() -> Component.translatable("gui.module_shop.admin_grant_open").getString());
    private final Button historyBtn = small(() -> Component.translatable("gui.module_shop.admin_history").getString());
    private final Button prevBtn = small(() -> Component.translatable("gui.module_shop.page_prev").getString());
    private final Button nextBtn = small(() -> Component.translatable("gui.module_shop.page_next").getString());
    /** 底部第一行的模块设置按钮（每帧按当前模块重新配置）。 */
    private final List<Button> actions = new ArrayList<>();

    private boolean menuOpen;
    private final float[] rowHoverT = new float[PAGE_SIZE];

    // 本帧几何
    private float menuX, menuY, menuW, menuH;
    private float listX, listY, listW;
    private List<ConfiguredOffer> pool = List.of();
    private List<DemandEntry> demandRows = List.of();
    private float demandY;

    private static Button small(Supplier<String> label) {
        return new Button(Button.Kind.NORMAL, label, null).radius(2.0F).fontSize(Text.SMALL);
    }

    @Override
    public String id() {
        return "admin";
    }

    private SignInCalendar calendar(ShopHost host) {
        return host.signInEditing() ? editCalendar : viewCalendar;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        ShopHost host = f.host();
        String mode = host.adminMode();
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

        // ---- 顶部：模块下拉 / 当前模块 / 发放物品 / 交易记录 ----
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
        float topBtnW = 62.0F;
        float historyX = cx + cw - 4.0F - topBtnW;
        historyBtn.onClick(() -> host.action("admin_history_gui", null)).bounds(historyX, cy + 2.0F, topBtnW, 14.0F);
        historyBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float grantX = historyX - topBtnW - 4.0F;
        grantBtn.onClick(() -> host.action("admin_grant_open", null)).bounds(grantX, cy + 2.0F, topBtnW, 14.0F);
        grantBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        float currentX = cx + 4.0F + ddW + 8.0F;
        if (grantX - 6.0F - currentX > 40.0F) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.admin_current", host.moduleName(mode))
                            .getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                    currentX, cy + 9.0F, grantX - 6.0F - currentX, Text.Align.LEFT);
        }

        // ---- 内容 ----
        pool = (stalls || demands || signin) ? List.of()
                : (mall ? ClientShopData.adminMall
                        : (ship ? ClientShopData.adminShipping
                                : (shop ? ClientShopData.adminShop : ClientShopData.adminBuyback)));
        int pages = Math.max(1, (pool.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        host.clampAdminPage(pages);
        listX = cx + 4.0F;
        listY = cy + 21.0F;
        listW = cw - 8.0F;
        demandRows = List.of();
        if (signin) {
            calendar(host).hoverEnabled(!menuOpen).render(canvas, f, cx, cy + 20.0F, cw);
        } else if (!pool.isEmpty()) {
            renderOffers(canvas, f, mode, mall, ship, shop);
        } else if (stalls) {
            renderStallHint(canvas, f);
        } else if (demands) {
            renderDemands(canvas, f);
        } else {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.admin_empty").getString(),
                    cx, cy + 60.0F, cw);
        }

        // ---- 底部两行 ----
        float rowA = f.barTop() - 16.0F;
        float rowB = f.barTop() + 1.0F;
        configureActions(host, mode, mall, ship, stalls, demands, signin);
        int columns = mall ? 5 : (stalls ? 2 : (demands ? 3 : (signin ? 4 : 3)));
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
                            ClientShopData.money(ClientShopData.signInBase),
                            ClientShopData.money(ClientShopData.signInMakeupCost),
                            ClientShopData.signInMakeupDays).getString();
            Text.drawLine(canvas, line, AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                    cx + 4.0F, rowB + 7.0F, cw - 10.0F, Text.Align.LEFT);
        } else {
            renderPager(canvas, f, pages, rowB);
        }

        // ---- 「选择模块编辑」下拉：最后画，盖在列表之上 ----
        if (menuOpen) {
            renderMenu(canvas, f, cx + 4.0F, cy + 18.0F, mode);
        }
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
            } else {
                specs.add(new Object[]{"gui.module_shop.admin_gen", "admin_gen_set", false});
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
            b.label(Component.translatable(key).getString());
            b.kind(on ? Button.Kind.ACCENT : Button.Kind.NORMAL);
            b.onClick(() -> {
                CompoundTag extra = null;
                if ("admin_add".equals(action)) {
                    extra = new CompoundTag();
                    extra.putString("mode", mode);
                }
                host.action(action, extra);
            });
        }
    }

    private void renderOffers(Canvas canvas, PageFrame f, String mode, boolean mall, boolean ship, boolean shop) {
        int page = f.host().adminPage();
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= pool.size()) {
                rowHoverT[i] = 0.0F;
                continue;
            }
            ConfiguredOffer offer = pool.get(index);
            float y = listY + i * ROW_H;
            boolean hover = !menuOpen && f.mouseX() >= listX && f.mouseX() < listX + listW
                    && f.mouseY() >= y && f.mouseY() < y + ROW_CARD;
            rowHoverT[i] = Motion.approach(rowHoverT[i], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
            // 出货箱的规则行：格式错误的那条整行标红，并显示它的原文
            String error = ship && index < ClientShopData.adminShippingErrors.size()
                    ? ClientShopData.adminShippingErrors.get(index) : "";
            if (ship && !error.isEmpty()) {
                String raw = index < ClientShopData.adminShippingRaw.size()
                        ? ClientShopData.adminShippingRaw.get(index) : "";
                Draw.fill(canvas, listX, y, listW, ROW_CARD, 2.0F, Draw.alpha(IOStyle.RED_TINT, 1.0F));
                Draw.stroke(canvas, listX, y, listW, ROW_CARD, 2.0F, 1.0F, IOStyle.RED);
                Text.drawLine(canvas, raw, AtomUi.REGULAR, Text.BODY, IOStyle.RED, listX + 6.0F, y + 6.0F,
                        listW - 40.0F, Text.Align.LEFT);
                Text.drawLine(canvas, Component.translatable("gui.module_shop.shipping.row_broken").getString(),
                        AtomUi.REGULAR, Text.SMALL, IOStyle.RED, listX + 6.0F, y + 15.0F, listW - 12.0F,
                        Text.Align.LEFT);
                continue;
            }
            PageKit.hoverCard(canvas, listX, y, listW, ROW_CARD, rowHoverT[i]);
            Draw.fill(canvas, listX + 3.0F, y + 4.0F, 3.0F, ROW_CARD - 8.0F, 1.0F, 0xFF000000 | offer.rarity.color());
            Draw.fill(canvas, listX + 10.0F, y + 1.0F, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
            Draw.stroke(canvas, listX + 10.0F, y + 1.0F, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
            if (!menuOpen) {
                f.host().items().add(offer.stack, listX + 11.0F, y + 2.0F);
            }
            // 列：名称 | 品质 | 价格 | 数量（按内容区宽度等比分布，任何宽度都不压字）
            float colName = listX + 34.0F;
            float colRarity = listX + listW * 0.40F;
            float colPrice = listX + listW * 0.56F;
            float colAmount = listX + listW * 0.76F;
            Text.drawLine(canvas, PageKit.plainName(offer.stack), AtomUi.REGULAR, Text.BODY, IOStyle.TEXT,
                    colName, y + ROW_CARD / 2.0F, colRarity - 6.0F - colName, Text.Align.LEFT);
            if (!ship) {
                // 出货箱的条目是兑换规则，没有品质可言，这一列留空
                Text.drawLine(canvas, Component.translatable(offer.rarity.translationKey()).getString(),
                        AtomUi.REGULAR, Text.SMALL, 0xFF000000 | offer.rarity.color(),
                        colRarity, y + ROW_CARD / 2.0F, colPrice - 6.0F - colRarity, Text.Align.LEFT);
            }
            Text.drawLine(canvas, ClientShopData.money(offer.price), AtomUi.BOLD, Text.SMALL, IOStyle.ORANGE,
                    colPrice, y + ROW_CARD / 2.0F, colAmount - 6.0F - colPrice, Text.Align.LEFT);
            Text.drawLine(canvas, amountText(offer, mall, ship, shop), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                    colAmount, y + ROW_CARD / 2.0F, listX + listW - 6.0F - colAmount, Text.Align.LEFT);
            if (hover && f.mouseX() >= listX + 10.0F && f.mouseX() < listX + 28.0F) {
                f.host().items().setHovered(offer.stack);
            }
        }
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
    private void renderStallHint(Canvas canvas, PageFrame f) {
        float x = f.x() + 8.0F;
        float w = f.w() - 16.0F;
        Component current = "permanent".equals(ClientShopData.stallExpireMode) || ClientShopData.stallExpireAmount <= 0
                ? Component.translatable("gui.module_shop.choice.expire_mode.permanent")
                : Component.translatable("gui.module_shop.stall.expire_value",
                        Component.translatable("gui.module_shop.choice.expire_mode." + ClientShopData.stallExpireMode),
                        ClientShopData.stallExpireAmount);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.admin_stall").getString(), AtomUi.BOLD,
                Text.BODY, IOStyle.TEXT, x, f.y() + 30.0F, w, Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.admin_stall_hint").getString(), AtomUi.REGULAR,
                Text.SMALL, IOStyle.TEXT_2, x, f.y() + 45.0F, w, Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.stall.expire_current", current).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE, x, f.y() + 58.0F, w, Text.Align.LEFT);
    }

    /** 求购：设置摘要 + 全部求购（最多 5 条，点一行确认强制撤销）。 */
    private void renderDemands(Canvas canvas, PageFrame f) {
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
        Text.drawLine(canvas, summary.getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                f.x() + 6.0F, f.y() + 25.0F, f.w() - 12.0F, Text.Align.LEFT);
        if (list.isEmpty()) {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.demand.empty").getString(),
                    f.x(), f.y() + 54.0F, f.w());
            return;
        }
        demandRows = list.subList(0, Math.min(PAGE_SIZE, list.size()));
        demandY = f.y() + 34.0F;
        for (int i = 0; i < demandRows.size(); i++) {
            DemandEntry e = demandRows.get(i);
            float y = demandY + i * 20.0F;
            boolean hover = !menuOpen && f.mouseX() >= listX && f.mouseX() < listX + listW
                    && f.mouseY() >= y && f.mouseY() < y + 18.0F;
            rowHoverT[i] = Motion.approach(rowHoverT[i], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
            PageKit.hoverCard(canvas, listX, y, listW, 18.0F, rowHoverT[i]);
            ItemStack want = e.wants.isEmpty() ? ItemStack.EMPTY : e.wants.get(0).display();
            if (!menuOpen) {
                f.host().items().add(want, listX + 2.0F, y + 1.0F);
            }
            String who = e.requesterName == null || e.requesterName.isBlank() ? "?" : e.requesterName;
            float whoW = Text.width(who, AtomUi.REGULAR, Text.SMALL);
            float rewardX = listX + listW * 0.45F;
            Text.drawLine(canvas, e.summary().getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT,
                    listX + 22.0F, y + 9.0F, rewardX - 6.0F - (listX + 22.0F), Text.Align.LEFT);
            Text.drawLine(canvas, Component.translatable("gui.module_shop.demand.reward", DemandPage.rewardLine(e))
                            .getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                    rewardX, y + 9.0F, listX + listW - 14.0F - whoW - rewardX, Text.Align.LEFT);
            Text.drawLine(canvas, who, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                    listX + listW - 6.0F - whoW, y + 9.0F, whoW + 1.0F, Text.Align.LEFT);
        }
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
                ClientShopData.money(ClientShopData.buybackBonus)).getString();
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
                    host.action("admin_module_pick", pick);
                }
                menuOpen = false;
                return true;
            }
            if (!moduleBtn.isMouseOver(mx, my)) {
                menuOpen = false;
                return true;
            }
        }
        for (Button b : buttons()) {
            if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        String mode = host.adminMode();
        if ("signin".equals(mode)) {
            return calendar(host).mouseClicked(f, mx, my, button);
        }
        if ("demand".equals(mode)) {
            for (int i = 0; i < demandRows.size(); i++) {
                float y = demandY + i * 20.0F;
                if (button == 0 && mx >= listX && mx < listX + listW && my >= y && my < y + 18.0F) {
                    ClickSound.play();
                    CompoundTag extra = new CompoundTag();
                    extra.putString("id", demandRows.get(i).id);
                    host.action("demand_admin_cancel", extra);
                    return true;
                }
            }
            return false;
        }
        // 条目行：左键编辑、右键删除（格式错误的出货箱规则：左右键都删除）
        int page = host.adminPage();
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= pool.size()) {
                break;
            }
            float y = listY + i * ROW_H;
            if (mx < listX || mx >= listX + listW || my < y || my >= y + ROW_CARD) {
                continue;
            }
            if (button != 0 && button != 1) {
                return false;
            }
            boolean broken = "shipping".equals(mode) && index < ClientShopData.adminShippingErrors.size()
                    && !ClientShopData.adminShippingErrors.get(index).isEmpty();
            CompoundTag extra = new CompoundTag();
            extra.putString("mode", mode);
            extra.putInt("index", index);
            ClickSound.play();
            String action = broken ? (button == 0 ? "admin_remove" : "admin_remove_broken")
                    : (button == 0 ? "admin_edit_gui" : "admin_remove");
            host.action(action, extra);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = false;
        for (Button b : buttons()) {
            handled |= b.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (menuOpen || "signin".equals(f.host().adminMode())) {
            return true;
        }
        // 条目列表：滚轮翻页（与底栏翻页按钮同一动作）
        f.host().action(scrollY > 0 ? "admin_prev" : "admin_next", null);
        return true;
    }

    @Override
    public void clickedOutside() {
        menuOpen = false;
    }

    @Override
    public void cancelInput() {
        menuOpen = false;
        for (Button b : buttons()) {
            b.cancelPress();
        }
    }
}
