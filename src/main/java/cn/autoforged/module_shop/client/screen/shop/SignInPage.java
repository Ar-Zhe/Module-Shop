package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import io.github.humbleui.skija.Canvas;
import net.minecraft.network.chat.Component;

/**
 * 签到页（Skija 版）。
 *
 * <ul>
 *     <li>顶部里程进度条（开了累计加成或配置了里程奖励时显示）：细轨 + 里程圆点，到达变绿，
 *     悬停圆点看该里程奖励；右端「第 N 轮」；</li>
 *     <li>月历（与管理面板共用 {@link SignInCalendar}）：补签期内的过去日期可点击补签；</li>
 *     <li>底栏：今日签到按钮（悬停显示可得星币）+ 连续天数与今日收益。</li>
 * </ul>
 */
public final class SignInPage implements ShopPage {
    private final SignInCalendar calendar = new SignInCalendar(false, true);
    private final Button signIn = new Button(Button.Kind.ACCENT, () -> Component.translatable(
            ClientShopData.signInDone ? "gui.module_shop.signin.done" : "gui.module_shop.signin.do").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);

    @Override
    public String id() {
        return "signin";
    }

    @Override
    public void render(Canvas canvas, PageFrame f) {
        float topY = f.y() + 2.0F;
        // 关了「累计加成」也要画：里程奖励和累计加成是两回事，没这条玩家看不到本轮进度
        if (ClientShopData.signInCumulative || hasMilestone()) {
            drawProgress(canvas, f, topY);
            topY += 19.0F;
        }
        calendar.render(canvas, f, f.x(), topY, f.w());
        drawFooter(canvas, f);
    }

    /** 里程进度条：4px 细轨 + 每个里程一个 5px 圆点，下面 d 数小字（太密时跳过标签避免压字）。 */
    private void drawProgress(Canvas canvas, PageFrame f, float y) {
        int cycle = Math.max(1, ClientShopData.signInCycleDays);
        int streak = ClientShopData.signInStreak;
        int roundDay = streak <= 0 ? 0 : (streak - 1) % cycle + 1;
        int round = streak <= 0 ? 1 : (streak - 1) / cycle + 1;
        String roundText = Component.translatable("gui.module_shop.signin.round", round).getString();
        float roundW = Text.width(roundText, AtomUi.REGULAR, Text.SMALL);
        float px = f.x() + 6.0F;
        float pw = Math.max(40.0F, f.w() - 12.0F - roundW - 8.0F);
        float barY = y + 3.0F;
        float barH = 4.0F;
        float ratio = Math.min(1.0F, roundDay / (float) cycle);
        Draw.fill(canvas, px, barY, pw, barH, 2.0F, IOStyle.SEG_TRACK);
        if (ratio > 0.0F) {
            Draw.fill(canvas, px, barY, Math.max(barH, pw * ratio), barH, 2.0F, IOStyle.GREEN);
        }
        float lastRight = Float.NEGATIVE_INFINITY;
        int[] days = ClientShopData.signInMileDays;
        Component tip = null;
        for (int i = 0; i < days.length; i++) {
            // 档位天数大于一轮长度的永远到不了，不画
            if (days[i] <= 0 || days[i] > cycle) {
                continue;
            }
            float dx = Math.max(px + 5.0F, Math.min(px + pw - 5.0F, px + pw * days[i] / (float) cycle));
            boolean reached = roundDay >= days[i];
            float dotY = barY + (barH - 5.0F) / 2.0F;
            Draw.fill(canvas, dx - 2.5F, dotY, 5.0F, 5.0F, 2.5F, reached ? IOStyle.GREEN : IOStyle.TOGGLE_OFF);
            if (!reached) {
                Draw.stroke(canvas, dx - 2.5F, dotY, 5.0F, 5.0F, 2.5F, 1.0F, IOStyle.HAIR);
            }
            String label = days[i] + "d";
            float lw = Text.width(label, AtomUi.REGULAR, Text.SMALL);
            float lx = Math.max(f.x() + 2.0F, Math.min(px + pw - lw, dx - lw / 2.0F));
            if (lx > lastRight + 3.0F) {
                Text.drawLine(canvas, label, AtomUi.REGULAR, Text.SMALL, reached ? IOStyle.GREEN : IOStyle.TEXT_2,
                        lx, barY + 11.0F, lw + 1.0F, Text.Align.LEFT);
                lastRight = lx + lw;
            }
            if (f.mouseX() >= dx - 6.0F && f.mouseX() < dx + 6.0F && f.mouseY() >= dotY - 2.0F
                    && f.mouseY() < dotY + 7.0F) {
                long money = i < ClientShopData.signInMileMoney.length ? ClientShopData.signInMileMoney[i] : 0L;
                tip = Component.translatable("gui.module_shop.signin.mile_tip", days[i],
                        ClientShopData.money(money), ClientShopData.money(tierBonusAt(days[i])));
            }
        }
        // 右端「第 N 轮」：每满一轮，里程档位重新可领
        Text.drawLine(canvas, roundText, AtomUi.REGULAR, Text.SMALL, IOStyle.BLUE,
                f.x() + f.w() - 4.0F - roundW, barY + barH / 2.0F, roundW + 1.0F, Text.Align.LEFT);
        if (tip != null) {
            f.host().textTooltip(tip);
        }
    }

    private void drawFooter(Canvas canvas, PageFrame f) {
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        boolean done = ClientShopData.signInDone;
        boolean can = !done && ClientShopData.signInEnabled;
        float btnW = 76.0F;
        signIn.kind(can ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> f.host().action("sign_in", null))
                .bounds(f.x() + 4.0F, barMid - 7.0F, btnW, 14.0F);
        signIn.enabled(can);
        signIn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        long gain = ClientShopData.signInBase + tierBonusAt(ClientShopData.signInStreak);
        if (signIn.isMouseOver(f.mouseX(), f.mouseY())) {
            f.host().textTooltip(Component.translatable("gui.module_shop.signin.gain_tip",
                    ClientShopData.money(gain)));
        }
        float statusX = f.x() + 4.0F + btnW + 8.0F;
        String status = Component.translatable(done ? "gui.module_shop.signin.status_done"
                : "gui.module_shop.signin.status", ClientShopData.signInStreak, ClientShopData.money(gain)).getString();
        Text.drawLine(canvas, status, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, statusX, barMid,
                f.x() + f.w() - 6.0F - statusX, Text.Align.LEFT);
    }

    /** 有没有「本轮内能达成、且有奖励」的里程档位。 */
    private static boolean hasMilestone() {
        int cycle = Math.max(1, ClientShopData.signInCycleDays);
        for (int i = 0; i < ClientShopData.signInMileDays.length; i++) {
            int day = ClientShopData.signInMileDays[i];
            long money = i < ClientShopData.signInMileMoney.length ? ClientShopData.signInMileMoney[i] : 0L;
            if (day > 0 && day <= cycle && money > 0L) {
                return true;
            }
        }
        return false;
    }

    /** 指定天数对应的累计签到加成（取不超过该天数的最高档位；关闭累计签到时为 0）。 */
    static long tierBonusAt(int day) {
        if (!ClientShopData.signInCumulative) {
            return 0L;
        }
        long bonus = 0L;
        for (int i = 0; i < ClientShopData.signInTierDays.length; i++) {
            if (day >= ClientShopData.signInTierDays[i]) {
                bonus = Math.max(bonus, i < ClientShopData.signInTierBonus.length ? ClientShopData.signInTierBonus[i] : 0L);
            }
        }
        return bonus;
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (signIn.isEnabled() && signIn.mouseClicked(mx, my, button)) {
            return true;
        }
        return calendar.mouseClicked(f, mx, my, button);
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return signIn.mouseReleased(mx, my, button);
    }

    @Override
    public void cancelInput() {
        signIn.cancelPress();
    }
}
