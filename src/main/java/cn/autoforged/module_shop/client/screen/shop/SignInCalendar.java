package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import io.github.humbleui.skija.Canvas;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 签到月历（Skija 版）：<b>签到页与管理面板「商店设置 → 签到」共用</b>，两处永远同步。
 *
 * <ul>
 *     <li>年 / 月导航（«  年份  »　‹  月份  ›）；</li>
 *     <li>日期格：已签到绿色、未来灰色、今天蓝色描边；底部最多 3 条日程色条；悬停显示日程详情 / 补签说明；</li>
 *     <li>玩家态（{@code allowMakeup}）：点补签期内的过去日期 = 补签（确认框由 ShopScreen 弹出）；</li>
 *     <li>管理员编辑态（{@code editable}）：点日期 = 新建 / 修改那天的日程，右键右上角小方块 = 清空那天。</li>
 * </ul>
 *
 * 动作全部交给 {@link ShopHost#action}，与旧界面的点击区域完全一致。
 */
public final class SignInCalendar {
    public static final float CELL_H = 16.0F;
    private static final float ARROW = 12.0F;
    private static final float NAV_H = 14.0F;
    private static final float WEEK_H = 10.0F;

    private record Hit(float x, float y, float w, float h, String action, long day, boolean corner) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final boolean editable;
    private final boolean allowMakeup;
    private final List<Hit> hits = new ArrayList<>();
    /** false 时不做悬停（被下拉等浮层盖住时）：不高亮、不显示提示。 */
    private boolean hoverEnabled = true;
    private final float[] hoverT = new float[31];

    public SignInCalendar(boolean editable, boolean allowMakeup) {
        this.editable = editable;
        this.allowMakeup = allowMakeup;
    }

    /** 当前显示的年月（ClientShopData.signInMonth = 年 × 12 + 月 - 1；未初始化时取本月）。 */
    public static YearMonth shownMonth() {
        if (ClientShopData.signInMonth == 0) {
            LocalDate now = LocalDate.now();
            ClientShopData.signInMonth = now.getYear() * 12 + now.getMonthValue() - 1;
        }
        return YearMonth.of(ClientShopData.signInMonth / 12, ClientShopData.signInMonth % 12 + 1);
    }

    /** 被浮层（例如管理面板的模块下拉）盖住时关掉悬停效果与提示。 */
    public SignInCalendar hoverEnabled(boolean enabled) {
        this.hoverEnabled = enabled;
        return this;
    }

    /** 绘制日历，返回底边 y。 */
    public float render(Canvas canvas, PageFrame f, float x, float topY, float w) {
        hits.clear();
        YearMonth ym = shownMonth();
        LocalDate today = LocalDate.now();
        Locale locale = Minecraft.getInstance().getLanguageManager().getJavaLocale();
        float gridX = x + 4.0F;
        float cellW = Math.max(22.0F, (float) Math.floor((w - 8.0F) / 7.0F));
        float gridW = cellW * 7.0F;

        // ---- 年月导航：年份在左、月份在右 ----
        float yearX = gridX;
        arrow(canvas, f, yearX, topY, "<<", "signin_year_prev");
        String year = Integer.toString(ym.getYear());
        float yearW = Text.width(year, AtomUi.BOLD, Text.BODY);
        Text.drawLine(canvas, year, AtomUi.BOLD, Text.BODY, IOStyle.TEXT, yearX + ARROW + 3.0F, topY + ARROW / 2.0F,
                yearW + 2.0F, Text.Align.LEFT);
        arrow(canvas, f, yearX + ARROW + 3.0F + yearW + 4.0F, topY, ">>", "signin_year_next");

        String month = ym.getMonth().getDisplayName(TextStyle.SHORT, locale);
        float monthW = Text.width(month, AtomUi.BOLD, Text.BODY);
        float monthTail = ARROW + 3.0F + monthW + 4.0F + ARROW;
        float monthX = Math.max(gridX + 88.0F, gridX + gridW - monthTail);
        arrow(canvas, f, monthX, topY, "<", "signin_month_prev");
        Text.drawLine(canvas, month, AtomUi.BOLD, Text.BODY, IOStyle.TEXT, monthX + ARROW + 3.0F, topY + ARROW / 2.0F,
                monthW + 2.0F, Text.Align.LEFT);
        arrow(canvas, f, monthX + ARROW + 3.0F + monthW + 4.0F, topY, ">", "signin_month_next");

        // ---- 星期 ----
        float weekY = topY + NAV_H;
        for (int c = 0; c < 7; c++) {
            String dow = DayOfWeek.of(c + 1).getDisplayName(TextStyle.NARROW, locale);
            Text.drawLine(canvas, dow, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, gridX + c * cellW,
                    weekY + WEEK_H / 2.0F, cellW, Text.Align.CENTER);
        }

        // ---- 日期格 ----
        float cellY = weekY + WEEK_H;
        int firstOffset = ym.atDay(1).getDayOfWeek().getValue() - 1;
        int length = ym.lengthOfMonth();
        Component tip = null;
        for (int d = 1; d <= length; d++) {
            LocalDate date = ym.atDay(d);
            long epochDay = date.toEpochDay();
            int idx = firstOffset + d - 1;
            float cx = gridX + (idx % 7) * cellW + 1.0F;
            float cy = cellY + (idx / 7) * CELL_H + 1.0F;
            float cw = cellW - 2.0F;
            float ch = CELL_H - 2.0F;
            boolean isToday = date.equals(today);
            boolean signed = ClientShopData.signInDays.contains(epochDay);
            boolean future = date.isAfter(today);
            boolean canMakeup = !signed && date.isBefore(today)
                    && epochDay >= today.toEpochDay() - ClientShopData.signInMakeupDays;
            List<ClientShopData.ActivityView> acts = activitiesOf(epochDay);
            boolean adminEdit = editable && ClientShopData.canAdmin;
            boolean clickable = adminEdit || (allowMakeup && canMakeup);
            boolean hover = hoverEnabled && f.mouseX() >= cx && f.mouseX() < cx + cw
                    && f.mouseY() >= cy && f.mouseY() < cy + ch;
            hoverT[d - 1] = Motion.approach(hoverT[d - 1], hover && clickable ? 1.0F : 0.0F, f.dtMs(),
                    Motion.HOVER_MS);

            int bg = signed ? IOStyle.GREEN_TINT : future ? IOStyle.DISABLED_BG : IOStyle.CARD;
            Draw.fill(canvas, cx, cy, cw, ch, 3.0F, Draw.lerp(bg, IOStyle.ROW_HOVER, hoverT[d - 1] * 0.6F));
            if (isToday) {
                Draw.stroke(canvas, cx, cy, cw, ch, 3.0F, 1.0F, IOStyle.BLUE);
            } else if (hoverT[d - 1] > 0.01F) {
                Draw.stroke(canvas, cx, cy, cw, ch, 3.0F, 1.0F, Draw.alpha(IOStyle.BORDER_HOVER, hoverT[d - 1]));
            }
            Text.drawLine(canvas, Integer.toString(d), AtomUi.REGULAR, Text.BODY,
                    signed ? IOStyle.GREEN : future ? IOStyle.DISABLED_TEXT : IOStyle.TEXT,
                    cx, cy + 6.0F, cw, Text.Align.CENTER);
            // 日程：底部彩色短线（最多 3 条），颜色由管理员在日程编辑里定
            int shown = Math.min(3, acts.size());
            if (shown > 0) {
                float barW = Math.max(3.0F, (cw - 6.0F) / shown);
                for (int k = 0; k < shown; k++) {
                    int color = acts.get(k).color == 0 ? IOStyle.ORANGE : (0xFF000000 | acts.get(k).color);
                    Draw.fill(canvas, cx + 3.0F + k * barW, cy + ch - 3.0F, barW - 1.0F, 2.0F, 1.0F, color);
                }
            }
            if (adminEdit && !acts.isEmpty()) {
                // 右上角小方块：右键清空这一天
                Draw.stroke(canvas, cx + cw - 6.0F, cy + 1.0F, 5.0F, 5.0F, 1.0F, 1.0F, IOStyle.TEXT_3);
                hits.add(new Hit(cx + cw - 7.0F, cy, 7.0F, 7.0F, "signin_activity_clear", epochDay, true));
            }
            if (adminEdit) {
                hits.add(new Hit(cx, cy, cw, ch, "signin_activity_edit", epochDay, false));
            } else if (allowMakeup && canMakeup) {
                hits.add(new Hit(cx, cy, cw, ch, "signin_makeup", epochDay, false));
            }
            if (hover) {
                if (!acts.isEmpty()) {
                    tip = activityTip(acts);
                } else if (allowMakeup && canMakeup) {
                    tip = Component.translatable("gui.module_shop.signin.makeup_tip",
                            ClientShopData.money(ClientShopData.signInMakeupCost), date.toString());
                } else if (isToday) {
                    tip = Component.translatable(signed ? "gui.module_shop.signin.done" : "gui.module_shop.signin.do");
                }
            }
        }
        if (tip != null) {
            f.host().textTooltip(tip);
        }
        return cellY + (float) ((firstOffset + length + 6) / 7) * CELL_H;
    }

    private void arrow(Canvas canvas, PageFrame f, float x, float y, String label, String action) {
        boolean hover = hoverEnabled && f.mouseX() >= x && f.mouseX() < x + ARROW
                && f.mouseY() >= y && f.mouseY() < y + ARROW;
        Draw.fill(canvas, x, y, ARROW, ARROW, 2.0F, hover ? IOStyle.GRAY_BTN_HOVER : IOStyle.GRAY_BTN);
        Draw.stroke(canvas, x, y, ARROW, ARROW, 2.0F, 1.0F, hover ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
        Text.drawLine(canvas, label, AtomUi.BOLD, Text.BODY, IOStyle.TEXT, x, y + ARROW / 2.0F, ARROW,
                Text.Align.CENTER);
        hits.add(new Hit(x, y, ARROW, ARROW, action, 0L, false));
    }

    /** 处理点击；命中返回 true。 */
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        for (Hit hit : hits) {
            if (!hit.contains(mx, my)) {
                continue;
            }
            if (hit.corner) {
                // 右上角小方块只响应右键（清空）；左键落到下面的整格（编辑）
                if (button != 1) {
                    continue;
                }
            } else if (button != 0) {
                continue;
            }
            ClickSound.play();
            CompoundTag extra = null;
            if (hit.day != 0L || hit.action.startsWith("signin_activity") || "signin_makeup".equals(hit.action)) {
                extra = new CompoundTag();
                extra.putLong("day", hit.day);
            }
            f.host().action(hit.action, extra);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 日程提示

    private static List<ClientShopData.ActivityView> activitiesOf(long day) {
        List<ClientShopData.ActivityView> list = new ArrayList<>();
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day) {
                list.add(view);
            }
        }
        return list;
    }

    /** 某天全部日程的多行提示：标题（粗体）、正文（灰）、星币（金）、物品（灰）。 */
    private static Component activityTip(List<ClientShopData.ActivityView> acts) {
        MutableComponent out = Component.empty();
        for (int k = 0; k < acts.size(); k++) {
            ClientShopData.ActivityView act = acts.get(k);
            if (k > 0) {
                out.append("\n\n");
            }
            out.append(Component.literal(act.title).withStyle(ChatFormatting.BOLD));
            if (act.note != null && !act.note.isEmpty()) {
                out.append("\n").append(Component.literal(act.note).withStyle(ChatFormatting.GRAY));
            }
            if (act.money > 0L) {
                out.append("\n").append(Component.translatable("gui.module_shop.signin.activity_money",
                        ClientShopData.money(act.money)).withStyle(ChatFormatting.GOLD));
            }
            String items = itemNames(act.itemText);
            if (!items.isEmpty()) {
                out.append("\n").append(Component.translatable("gui.module_shop.signin.activity_items", items)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        return out;
    }

    /** 把「id xN,id2 xM」解析成「物品名×N、…」（解析不出来的直接跳过）。 */
    private static String itemNames(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : text.split(",")) {
            String raw = piece.trim();
            if (raw.isEmpty()) {
                continue;
            }
            int count = 1;
            int idx = raw.lastIndexOf('x');
            if (idx > 0) {
                try {
                    count = Math.max(1, Math.min(64, Integer.parseInt(raw.substring(idx + 1).trim())));
                    raw = raw.substring(0, idx).trim();
                } catch (NumberFormatException ignored) {
                }
            }
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(new ItemStack(item).getHoverName().getString()).append("×").append(count);
        }
        return sb.toString();
    }
}
