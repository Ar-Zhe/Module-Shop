package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 出货箱页（Skija 版）：概览 + 入口。物品本体在容器界面（ShippingBinScreen）里，这里不放物品。
 *
 * <ul>
 *     <li>卡片一「本次结算」：等级（分段指示）/ 槽位、箱内件数、本次可得（大号金色）、今日已结算（有上限时带进度条）；</li>
 *     <li>卡片二「结算时间」：每天结算时刻、额度重置时间、距下次结算的倒计时；</li>
 *     <li>按钮：打开出货箱（shipping_open）、升级（shipping_upgrade，满级或不可升级时禁用）。</li>
 * </ul>
 */
public final class ShippingPage implements ShopPage {
    private static final float PAD = 8.0F;

    private final Button open = new Button(Button.Kind.ACCENT,
            () -> Component.translatable("gui.module_shop.shipping.open").getString(), null).radius(3.0F);
    private final Button upgrade = new Button(Button.Kind.NORMAL, () -> upgradeLabel(), null).radius(3.0F);

    /** 今日额度进度条的显示值（平滑过渡）。 */
    private float quotaShown = -1.0F;

    @Override
    public String id() {
        return "shipping";
    }

    private static boolean canUpgrade() {
        return ClientShopData.shipUpgradeCost >= 0L && ClientShopData.shipLevel < ClientShopData.shipMaxLevel;
    }

    private static String upgradeLabel() {
        return canUpgrade()
                ? Component.translatable("gui.module_shop.shipping.upgrade_cost",
                        ClientShopData.money(ClientShopData.shipUpgradeCost)).getString()
                : Component.translatable("gui.module_shop.shipping.max").getString();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        float x = f.x() + 2.0F;
        float w = f.w() - 4.0F;
        if (!ClientShopData.shipEnabled) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.shipping.disabled").getString(),
                    AtomUi.REGULAR, Text.BODY, IOStyle.TEXT_2, x, f.y() + 64.0F, w, Text.Align.CENTER);
            open.visible(false);
            upgrade.visible(false);
            return;
        }

        // ---------- 卡片一：本次结算 ----------
        float aY = f.y() + 2.0F;
        float aH = 62.0F;
        card(canvas, x, aY, w, aH);
        String level = Component.translatable("gui.module_shop.shipping.level",
                ClientShopData.shipLevel, ClientShopData.shipMaxLevel, ClientShopData.shipSlots).getString();
        Text.drawLine(canvas, level, AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                x + PAD, aY + 10.0F, w - PAD * 2 - 60.0F, Text.Align.LEFT);
        drawLevelPips(canvas, x + w - PAD, aY + 10.0F);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.shipping.items",
                        ClientShopData.shipItems).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, x + PAD, aY + 23.0F, w - PAD * 2, Text.Align.LEFT);
        String pending = Component.translatable("gui.module_shop.shipping.pending",
                ClientShopData.money(ClientShopData.shipValue)).getString();
        Text.drawLine(canvas, pending, AtomUi.BOLD, Text.TITLE, IOStyle.ORANGE,
                x + PAD, aY + 40.0F, w * 0.55F, Text.Align.LEFT);

        String today = ClientShopData.shipLimit > 0L
                ? Component.translatable("gui.module_shop.shipping.today",
                        ClientShopData.money(ClientShopData.shipSoldToday),
                        ClientShopData.money(ClientShopData.shipLimit)).getString()
                : Component.translatable("gui.module_shop.shipping.today_unlimited",
                        ClientShopData.money(ClientShopData.shipSoldToday)).getString();
        float todayW = w * 0.45F - PAD;
        Text.drawLine(canvas, today, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                x + w - PAD - todayW, aY + 40.0F, todayW, Text.Align.RIGHT);
        if (ClientShopData.shipLimit > 0L) {
            float ratio = Math.min(1.0F, ClientShopData.shipSoldToday / (float) ClientShopData.shipLimit);
            quotaShown = quotaShown < 0.0F ? ratio : Motion.approach(quotaShown, ratio, f.dtMs(), 120.0F);
            float barX = x + PAD;
            float barW = w - PAD * 2;
            float barY = aY + aH - 9.0F;
            Draw.fill(canvas, barX, barY, barW, 3.0F, 1.5F, IOStyle.SLOT_INNER);
            // 接近上限时变成橙色，提醒今天快卖不动了
            int color = quotaShown >= 0.9F ? IOStyle.ORANGE : IOStyle.BLUE;
            Draw.fill(canvas, barX, barY, barW * quotaShown, 3.0F, 1.5F, color);
        }

        // ---------- 卡片二：结算时间 ----------
        float bY = aY + aH + 4.0F;
        float bH = 44.0F;
        card(canvas, x, bY, w, bH);
        String settle = Component.translatable("gui.module_shop.shipping.settle_time",
                String.format("%02d:%02d", (ClientShopData.shipSettleTick / 1000 + 6) % 24,
                        (ClientShopData.shipSettleTick % 1000) * 60 / 1000)).getString();
        Text.drawLine(canvas, settle, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                x + PAD, bY + 13.0F, w * 0.55F, Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.shipping.reset_line",
                        ClientShopData.shipResetTimeText).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, x + PAD, bY + 29.0F, w * 0.55F, Text.Align.LEFT);
        float nextW = w * 0.45F - PAD;
        Text.drawLine(canvas, nextSettleText(), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                x + w - PAD - nextW, bY + bH / 2.0F, nextW, Text.Align.RIGHT);

        // ---------- 按钮 ----------
        float btnY = bY + bH + 6.0F;
        float btnW = (w - 6.0F) / 2.0F;
        open.bounds(x, btnY, btnW, 20.0F).visible(true);
        upgrade.bounds(x + btnW + 6.0F, btnY, btnW, 20.0F).visible(true);
        upgrade.enabled(canUpgrade());
        open.onClick(() -> f.host().action("shipping_open", null));
        upgrade.onClick(() -> f.host().action("shipping_upgrade", null));
        open.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        upgrade.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());

        // ---------- 底栏提示 ----------
        Text.drawLine(canvas, Component.translatable("gui.module_shop.shipping.hint",
                        ClientShopData.currencyName()).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, f.x() + 4.0F, f.barTop() + PageFrame.BAR_H / 2.0F,
                f.w() - 10.0F, Text.Align.LEFT);
    }

    private static void card(Canvas canvas, float x, float y, float w, float h) {
        Draw.fill(canvas, x, y, w, h, 3.0F, IOStyle.CARD);
        Draw.stroke(canvas, x, y, w, h, 3.0F, 1.0F, IOStyle.BORDER_DIM);
    }

    /** 等级分段指示：每级一小段，已达到的等级点亮；右对齐到 right。 */
    private static void drawLevelPips(Canvas canvas, float right, float centerY) {
        int max = Math.max(1, Math.min(10, ClientShopData.shipMaxLevel));
        float pipW = 10.0F;
        float gap = 2.0F;
        float total = max * pipW + (max - 1) * gap;
        float px = right - total;
        for (int i = 0; i < max; i++) {
            boolean lit = i < ClientShopData.shipLevel;
            Draw.fill(canvas, px + i * (pipW + gap), centerY - 1.5F, pipW, 3.0F, 1.5F,
                    lit ? IOStyle.ORANGE : IOStyle.SLOT_BORDER);
        }
    }

    /** 客户端估算：距离下一次结算还有多少游戏内时间（与旧界面同一算法）。 */
    private static String nextSettleText() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return "";
        }
        long timeOfDay = level.getDayTime() % 24000L;
        long start = ClientShopData.shipSettleTick;
        long remain = timeOfDay <= start ? start - timeOfDay : 24000L - timeOfDay + start;
        long minutes = Math.max(0L, remain) * 60L / 1000L; // 1000 刻 = 1 游戏小时
        if (remain <= 0L || minutes < 1L) {
            return Component.translatable("gui.module_shop.shipping.settling").getString();
        }
        return Component.translatable("gui.module_shop.shipping.next_in", minutes / 60L, minutes % 60L).getString();
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        for (Button b : List.of(open, upgrade)) {
            if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return open.mouseReleased(mx, my, button) | upgrade.mouseReleased(mx, my, button);
    }

    @Override
    public void cancelInput() {
        open.cancelPress();
        upgrade.cancelPress();
    }
}
