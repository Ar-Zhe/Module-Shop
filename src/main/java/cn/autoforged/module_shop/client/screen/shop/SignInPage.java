package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import io.github.humbleui.skija.Canvas;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 签到（UI v2）：顶部指标条（连签 / 历史最长 / 本月已签 / 里程 / 补签），左边紧凑月历（与管理面板共用
 * {@link SignInCalendar}，补签点日期），右边是本月逐日清单（状态 · 基础奖励 · 累计加成 · 活动 · 补签 / 签到）。
 */
public final class SignInPage implements ShopPage {
    private static final float STATS_H = 36.0F;
    private static final float CAL_W = 196.0F;
    private static final ListTable TABLE = new ListTable();
    private final SignInCalendar calendar = new SignInCalendar(false, true);
    private float[] signRect;
    private List<LocalDate> days = new ArrayList<>();

    @Override
    public String id() {
        return "signin";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private static boolean signed(long epochDay) {
        return ClientShopData.signInDays.contains(epochDay);
    }

    private static boolean canMakeup(long epochDay) {
        long today = ClientShopData.signInToday;
        return ClientShopData.signInMakeupDays > 0 && epochDay < today && epochDay >= today - ClientShopData.signInMakeupDays
                && !signed(epochDay);
    }

    private static List<ClientShopData.ActivityView> activitiesOf(long day) {
        List<ClientShopData.ActivityView> out = new ArrayList<>();
        for (ClientShopData.ActivityView a : ClientShopData.activityDays) {
            if (a.day == day) {
                out.add(a);
            }
        }
        return out;
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        float x = f.x();
        float y = f.y();
        drawToolbar(c, f);
        drawStats(c, f, x, y + V2.BAR, f.w());
        float top = y + V2.BAR + STATS_H;
        // 左：月历
        Draw.fill(c, x + CAL_W - 0.5F, top, 0.5F, f.h() - V2.BAR - STATS_H, 0, IOStyle.BORDER_DIM);
        calendar.render(c, f, x + 6, top + 6, CAL_W - 12);
        // 右：本月逐日
        YearMonth ym = SignInCalendar.shownMonth();
        days = new ArrayList<>();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            days.add(ym.atDay(d));
        }
        float bottom = y + f.h() - V2.FOOT;
        drawTable(c, f, x + CAL_W, top, f.w() - CAL_W, bottom - top);
        V2.foot(c, x + CAL_W, bottom, f.w() - CAL_W, tr("gui.module_shop.v2.signin.month", ym.getYear(), ym.getMonthValue()),
                tr("gui.module_shop.v2.signin.hint"));
    }

    private void drawToolbar(Canvas c, PageFrame f) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.signin"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        boolean done = ClientShopData.signInDone;
        boolean can = !done && ClientShopData.signInEnabled;
        long gain = ClientShopData.signInBase + SignInPageTier.at(ClientShopData.signInStreak + (done ? 0 : 1));
        String label = done ? tr("gui.module_shop.signin.done") : tr("gui.module_shop.v2.signin.do", V2.money(gain));
        float w = V2.buttonWidth(label) + 10;
        float bx = x + f.w() - 8 - w;
        V2.button(c, label, can ? V2.Btn.PRIMARY : V2.Btn.DISABLED, bx, by, w, V2.CTRL, over(f, bx, by, w, V2.CTRL) ? 1 : 0);
        signRect = can ? new float[]{bx, by, w, V2.CTRL} : null;
        List<String> notes = new ArrayList<>();
        if (ClientShopData.signInCumulative && ClientShopData.signInTierDays.length > 0) {
            StringBuilder sb = new StringBuilder(tr("gui.module_shop.v2.signin.tiers"));
            for (int i = 0; i < ClientShopData.signInTierDays.length; i++) {
                long b = i < ClientShopData.signInTierBonus.length ? ClientShopData.signInTierBonus[i] : 0;
                sb.append(' ').append(ClientShopData.signInTierDays[i]).append(tr("gui.module_shop.v2.signin.day_unit")).append(" +").append(b);
            }
            notes.add(sb.toString());
        }
        if (ClientShopData.signInAuto) {
            notes.add(tr("gui.module_shop.v2.signin.auto"));
        }
        V2.text(c, String.join("  ·  ", notes), V2.FS_XS, IOStyle.TEXT_3, x + 8 + tw + 12, cy, bx - 10 - (x + 8 + tw + 12),
                Text.Align.RIGHT);
    }

    private void drawStats(Canvas c, PageFrame f, float x, float y, float w) {
        Draw.fill(c, x, y, w, STATS_H, 0, Draw.alpha(IOStyle.CARD, 0.6F));
        Draw.hairline(c, x, y + STATS_H - 0.5F, w, IOStyle.BORDER_DIM);
        YearMonth ym = YearMonth.now();
        int monthSigned = 0;
        for (long d : ClientShopData.signInDays) {
            LocalDate ld = LocalDate.ofEpochDay(d);
            if (ld.getYear() == ym.getYear() && ld.getMonth() == ym.getMonth()) {
                monthSigned++;
            }
        }
        int cycle = Math.max(1, ClientShopData.signInCycleDays);
        int streak = ClientShopData.signInStreak;
        int round = streak <= 0 ? 1 : (streak - 1) / cycle + 1;
        int inCycle = streak <= 0 ? 0 : (streak - 1) % cycle + 1;
        String[][] cells = {
                {tr("gui.module_shop.v2.signin.streak"), tr("gui.module_shop.v2.signin.days", streak), ""},
                {tr("gui.module_shop.v2.signin.best"), tr("gui.module_shop.v2.signin.days", Math.max(ClientShopData.signInBest, streak)), ""},
                {tr("gui.module_shop.v2.signin.this_month"), monthSigned + " / " + LocalDate.now().getDayOfMonth(), ""},
                {tr("gui.module_shop.v2.signin.mile"), tr("gui.module_shop.v2.signin.round", round, inCycle, cycle), ""},
                {tr("gui.module_shop.v2.signin.makeup"), ClientShopData.signInMakeupDays > 0
                        ? V2.money(ClientShopData.signInMakeupCost) : tr("gui.module_shop.v2.signin.makeup_off"),
                        ClientShopData.signInMakeupDays > 0 ? tr("gui.module_shop.v2.signin.makeup_range", ClientShopData.signInMakeupDays) : ""}};
        float cw = w / cells.length;
        for (int i = 0; i < cells.length; i++) {
            float cx = x + i * cw;
            if (i > 0) {
                Draw.fill(c, cx, y + 6, 0.5F, STATS_H - 12, 0, IOStyle.BORDER_DIM);
            }
            V2.text(c, cells[i][0], V2.FS_XS, IOStyle.TEXT_3, cx + 9, y + 9, cw - 14, Text.Align.LEFT);
            V2.bold(c, cells[i][1], V2.FS + 1, i == 0 ? IOStyle.GREEN : IOStyle.TEXT, cx + 9, y + 21, cw - 14, Text.Align.LEFT);
            if (!cells[i][2].isEmpty()) {
                V2.text(c, cells[i][2], V2.FS_XS, IOStyle.TEXT_3, cx + 9, y + 30, cw - 14, Text.Align.LEFT);
            }
            if (i == 3) {
                float pw = (cw - 22) / cycle;
                for (int k = 0; k < cycle && k < 31; k++) {
                    Draw.fill(c, cx + 9 + k * pw, y + 29, Math.max(1, pw - 1.5F), 2.5F, 1,
                            k < inCycle ? IOStyle.BLUE : IOStyle.BORDER_DIM);
                }
            }
        }
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float[][] cols = V2.columns(x, w, 8, 6, 40, 30, 56, 40, 52, 0, 52);
        String[] heads = {tr("gui.module_shop.v2.col.date"), tr("gui.module_shop.v2.col.weekday"), tr("gui.module_shop.v2.col.state"),
                tr("gui.module_shop.v2.col.base"), tr("gui.module_shop.v2.col.tier"), tr("gui.module_shop.v2.col.activity"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.LEFT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h, cols, heads, al, null);
        long today = ClientShopData.signInToday != 0 ? ClientShopData.signInToday : LocalDate.now().toEpochDay();
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), days.size(), (cv, row, ry, hover) -> {
            LocalDate d = days.get(row);
            long ed = d.toEpochDay();
            float cy = ry + V2.ROW / 2;
            boolean isToday = ed == today;
            if (isToday) {
                TABLE.selected(cv, ry);
            }
            boolean weekend = d.getDayOfWeek().getValue() >= 6;
            V2.bold(cv, String.format("%02d-%02d", d.getMonthValue(), d.getDayOfMonth()), V2.FS_S, IOStyle.TEXT, cols[0][0], cy,
                    cols[0][1], Text.Align.LEFT);
            V2.text(cv, d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()), V2.FS_S,
                    weekend ? IOStyle.RED : IOStyle.TEXT_2, cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            String state;
            int sc;
            if (signed(ed)) {
                state = tr("gui.module_shop.v2.signin.st_done");
                sc = IOStyle.GREEN;
            } else if (isToday) {
                state = tr("gui.module_shop.v2.signin.st_today");
                sc = IOStyle.BLUE;
            } else if (ed > today) {
                state = tr("gui.module_shop.v2.signin.st_future");
                sc = IOStyle.TEXT_3;
            } else if (canMakeup(ed)) {
                state = tr("gui.module_shop.v2.signin.st_makeup");
                sc = IOStyle.ORANGE;
            } else {
                state = tr("gui.module_shop.v2.signin.st_missed");
                sc = IOStyle.TEXT_3;
            }
            V2.tag(cv, state, sc, cols[2][0], cy);
            V2.text(cv, V2.money(ClientShopData.signInBase), V2.FS_S, ed > today ? IOStyle.TEXT_3 : IOStyle.TEXT, cols[3][0], cy,
                    cols[3][1], Text.Align.RIGHT);
            // 累计加成：已签的日子按当时（近似为今天的连签推算）不好还原，这里只对今天及以后给出预计值
            if (ed >= today && ClientShopData.signInCumulative) {
                int dayStreak = ClientShopData.signInStreak + (int) (ed - today) + (ClientShopData.signInDone ? 0 : 1);
                long bonus = SignInPageTier.at(dayStreak);
                V2.text(cv, bonus > 0 ? "+" + V2.money(bonus) : "—", V2.FS_S, bonus > 0 ? IOStyle.GREEN : IOStyle.TEXT_3,
                        cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
            } else {
                V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
            }
            List<ClientShopData.ActivityView> acts = activitiesOf(ed);
            if (!acts.isEmpty()) {
                ClientShopData.ActivityView a = acts.get(0);
                float ax = cols[5][0];
                Draw.fill(cv, ax, cy - 2, 4, 4, 2, a.color != 0 ? (0xFF000000 | a.color) : IOStyle.BLUE);
                StringBuilder sb = new StringBuilder(a.title == null ? "" : a.title);
                if (a.money > 0) {
                    sb.append("  +").append(V2.money(a.money));
                }
                if (a.itemText != null && !a.itemText.isEmpty()) {
                    sb.append("  ").append(a.itemText);
                }
                if (acts.size() > 1) {
                    sb.append("  ").append(tr("gui.module_shop.v2.signin.more_acts", acts.size() - 1));
                }
                V2.text(cv, sb.toString(), V2.FS_S, IOStyle.TEXT, ax + 7, cy, cols[5][1] - 7, Text.Align.LEFT);
            } else {
                V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[5][0], cy, cols[5][1], Text.Align.LEFT);
            }
            float right = cols[6][0] + cols[6][1];
            if (isToday && !ClientShopData.signInDone && ClientShopData.signInEnabled) {
                TABLE.button(cv, row, "sign", tr("gui.module_shop.v2.signin.sign"), V2.Btn.PRIMARY, right, ry, hover);
            } else if (canMakeup(ed)) {
                TABLE.button(cv, row, "makeup", tr("gui.module_shop.v2.signin.makeup_btn"), V2.Btn.NORMAL, right, ry, hover);
            }
        });
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button == 0 && signRect != null && mx >= signRect[0] && mx < signRect[0] + signRect[2] && my >= signRect[1]
                && my < signRect[1] + signRect[3]) {
            ClickSound.play();
            f.host().action("sign_in", null);
            return true;
        }
        if (calendar.mouseClicked(f, mx, my, button)) {
            return true;
        }
        if (button == 0 && TABLE.pressBar(mx, my)) {
            return true;
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null || hit.button() == null || hit.row() < 0 || hit.row() >= days.size()) {
            return hit != null;
        }
        ClickSound.play();
        if ("sign".equals(hit.button())) {
            f.host().action("sign_in", null);
        } else if ("makeup".equals(hit.button())) {
            CompoundTag extra = new CompoundTag();
            extra.putLong("day", days.get(hit.row()).toEpochDay());
            f.host().action("signin_makeup", extra);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return TABLE.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        return TABLE.wheel(mx, my, scrollY, false);
    }

    @Override
    public void cancelInput() {
        TABLE.releaseBar();
    }

    /** 累计签到档位（与旧版 tierBonusAt 同一规则；AdminPage 等处也会用到）。 */
    static final class SignInPageTier {
        static long at(int day) {
            return tierBonusAt(day);
        }
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

    /** 旧版里程判定（AdminPage 用）。 */
    static boolean hasMilestone() {
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
}
