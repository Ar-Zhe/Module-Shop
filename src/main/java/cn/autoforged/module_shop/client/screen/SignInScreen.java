package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 每日签到界面（需求 7）。
 * <p>
 * 上方是连续签到里程进度条（关闭累计签到时隐藏），中间是签到按钮（未签可点、已签显示「今日已完成签到」），
 * 下面是日历（可翻月/翻年、今日高亮、已签到格变绿、可补签、管理员可点格子设置活动日）；
 * 管理员在右下角额外看到「累计加成 / 自动签到 / 签到设置」三个按钮。
 */
public class SignInScreen extends Screen {
    private static final int PANEL_W = 380;
    /** 面板高度按常见 GUI 缩放（1920×1080 → 480×258）收紧到 246，标题不会再顶出面板。 */
    private static final int PANEL_H = 246;
    private static final int CELL_W = 48;
    /** 格子高度缩到 18：给底部「管理员按键 + 返回」腾出一行，避免任何按钮重叠。 */
    private static final int CELL_H = 18;
    private static final int COLS = 7;

    private final List<Region> regions = new ArrayList<>();
    private int pointerX;
    private int pointerY;
    private String hoverTip;
    /** 第八轮：鼠标悬停的日程（自绘悬浮窗：标题 → 正文 → 金额 → 物品图标）。 */
    private List<ClientShopData.ActivityView> hoverActs;
    /** 管理员编辑态：开启后点进度条改档位、点签到按钮切自动签到。 */
    private boolean editMode;

    public SignInScreen() {
        super(Component.translatable("gui.module_shop.signin.title"));
        if (ClientShopData.signInMonth == 0) {
            LocalDate now = LocalDate.now();
            ClientShopData.signInMonth = now.getYear() * 12 + now.getMonthValue() - 1;
        }
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Nav.back();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.regions.clear();
        this.pointerX = mouseX;
        this.pointerY = mouseY;
        this.hoverTip = null;
        this.hoverActs = null;
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.signin.title"),
                x + PANEL_W / 2, y + 8, IOStyle.TEXT);

        drawProgress(g, x, y);
        drawCheckInButton(g, x, y);
        drawCalendar(g, x, y, mouseX, mouseY);
        if (ClientShopData.canAdmin) {
            drawAdminRow(g, x, y);
        }
        drawBackRow(g, x, y);
        if (this.hoverActs != null && !this.hoverActs.isEmpty()) {
            drawActivityTip(g, mouseX, mouseY);
        } else if (this.hoverTip != null) {
            g.renderTooltip(this.font, Component.literal(this.hoverTip), mouseX, mouseY);
        }
    }

    /**
     * 日程悬浮窗（第八轮，按「从上到下」的格式）：
     * <ol>
     *     <li>最上面：日程标题</li>
     *     <li>中间：公告正文（0.8 倍小字号，自动换行）</li>
     *     <li>金额：0.8 倍小字号 + **金色**，放在物品上面</li>
     *     <li>最下面：奖励物品**图标**（带数量角标，悬停可看名字）</li>
     * </ol>
     */
    private void drawActivityTip(GuiGraphics g, int mouseX, int mouseY) {
        List<ClientShopData.ActivityView> acts = this.hoverActs;
        int pad = 7;
        int innerW = 132;
        int headH = 9;
        int smallH = 8;
        int iconH = 20;
        List<List<String>> notes = new ArrayList<>();
        List<List<net.minecraft.world.item.ItemStack>> itemLists = new ArrayList<>();
        for (ClientShopData.ActivityView act : acts) {
            notes.add(act.note == null || act.note.isEmpty() ? List.of() : wrapSmall(act.note, innerW));
            itemLists.add(parseItemIds(act.itemIds));
        }
        int height = pad;
        for (int i = 0; i < acts.size(); i++) {
            height += headH + 1;
            height += notes.get(i).size() * smallH;
            height += smallH + 1;
            if (!itemLists.get(i).isEmpty()) {
                height += iconH + 2;
            }
            if (i < acts.size() - 1) {
                height += 4;
            }
        }
        height += pad - 2;
        int width = innerW + pad * 2;
        int tx = mouseX + 10;
        int ty = mouseY + 10;
        if (tx + width > this.width - 4) {
            tx = Math.max(4, this.width - 4 - width);
        }
        if (ty + height > this.height - 4) {
            ty = Math.max(4, this.height - 4 - height);
        }
        IOStyle.rounded(g, tx, ty, width, height, 6, 0xF0100010);
        IOStyle.ring(g, tx, ty, width, height, 6, IOStyle.TAB_SEL_BORDER);
        int cx = tx + pad;
        int cy = ty + pad;
        for (int i = 0; i < acts.size(); i++) {
            ClientShopData.ActivityView act = acts.get(i);
            IOStyle.text(g, this.font, Component.literal(act.title == null || act.title.isEmpty()
                    ? Component.translatable("gui.module_shop.signin.activity").getString() : act.title),
                    cx, cy, IOStyle.TEXT);
            cy += headH + 1;
            for (String line : notes.get(i)) {
                small(g, cx, cy, Component.literal(line), IOStyle.TEXT_2, false);
                cy += smallH;
            }
            String moneyLine = act.money > 0L ? ClientShopData.money(act.money) : "";
            if (act.hour >= 0) {
                moneyLine = moneyLine.isEmpty() ? String.format("%02d:00", act.hour)
                        : moneyLine + "  " + String.format("%02d:00", act.hour);
            }
            if (!moneyLine.isEmpty()) {
                small(g, cx, cy, Component.literal(moneyLine), IOStyle.ORANGE, false);
            }
            cy += smallH + 1;
            List<net.minecraft.world.item.ItemStack> items = itemLists.get(i);
            for (int k = 0; k < items.size() && k < 6; k++) {
                int ix = cx + k * 20;
                Bricks.slot(g, ix, cy + 1);
                g.renderItem(items.get(k), ix, cy + 1);
                g.renderItemDecorations(this.font, items.get(k), ix, cy + 1);
                if (mouseX >= ix && mouseX < ix + 18 && mouseY >= cy + 1 && mouseY < cy + 19) {
                    g.renderTooltip(this.font, items.get(k), mouseX, mouseY);
                }
            }
            if (items.size() > 6) {
                small(g, cx + 6 * 20 + 2, cy + 6, Component.literal("+" + (items.size() - 6)), IOStyle.TEXT_2, false);
            }
            if (!items.isEmpty()) {
                cy += iconH + 2;
            }
        }
    }

    /** 0.8 倍字号下的按宽度换行。 */
    private List<String> wrapSmall(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                String candidate = line.toString() + paragraph.charAt(i);
                if (this.font.width(candidate) * 0.8F > width && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder();
                }
                line.append(paragraph.charAt(i));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** 把「id xN,id2 xM」解析成物品（用于悬浮窗图标；解析失败的直接跳过）。 */
    private static List<net.minecraft.world.item.ItemStack> parseItemIds(String text) {
        List<net.minecraft.world.item.ItemStack> list = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return list;
        }
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
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(raw);
            if (id == null) {
                continue;
            }
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            list.add(new net.minecraft.world.item.ItemStack(item, count));
        }
        return list;
    }

    /** 连续签到里程进度条 + 每日可得金额。 */
    /** 里程进度条（需求 ①）：细长一条 + 里程小圆，到达的里程小圆点亮。 */
    private void drawProgress(GuiGraphics g, int x, int y) {
        int px = x + 16;
        int pw = PANEL_W - 32;
        int py = y + 20;
        int barH = 5;
        IOStyle.rounded(g, px, py, pw, barH, barH / 2, IOStyle.SEG_TRACK);
        int[] days = ClientShopData.signInMileDays;
        int maxDay = days.length > 0 ? days[days.length - 1] : 7;
        float ratio = maxDay <= 0 ? 0F : Math.min(1F, ClientShopData.signInStreak / (float) maxDay);
        if (ClientShopData.signInCumulative && ratio > 0F) {
            IOStyle.rounded(g, px, py, Math.max(barH, (int) (pw * ratio)), barH, barH / 2, IOStyle.GREEN);
        }
        for (int i = 0; i < days.length; i++) {
            int dx = px + (maxDay <= 0 ? 0 : pw * days[i] / maxDay);
            dx = Math.min(px + pw - 5, Math.max(px + 5, dx));
            boolean reached = ClientShopData.signInStreak >= days[i];
            if (reached) {
                IOStyle.rounded(g, dx - 6, py - 4, 12, 12, 6, IOStyle.withAlpha(IOStyle.GREEN, 70));
                IOStyle.rounded(g, dx - 4, py - 2, 8, 8, 4, IOStyle.GREEN);
            } else {
                IOStyle.rounded(g, dx - 4, py - 2, 8, 8, 4, IOStyle.TOGGLE_OFF);
                IOStyle.ring(g, dx - 4, py - 2, 8, 8, 4, IOStyle.HAIR);
            }
            // 第八轮：鼠标移到里程点 → 显示该里程对应的「连续签到加成」。
            if (IOStyle.inside(dx - 8, py - 8, 16, 16, this.pointerX, this.pointerY)) {
                this.hoverTip = Component.translatable("gui.module_shop.signin.streak_bonus_tip",
                        ClientShopData.money(tierBonusAt(days[i]))).getString();
            }
            small(g, dx, y + 28, Component.literal(days[i] + "d"), reached ? IOStyle.GREEN : IOStyle.TEXT_2, true);
            long money = i < ClientShopData.signInMileMoney.length ? ClientShopData.signInMileMoney[i] : 0L;
            small(g, dx, y + 37, Component.literal(ClientShopData.money(money)),
                    reached ? IOStyle.GREEN_DARK : IOStyle.TEXT_2, true);
        }
    }

    /** 某里程天数对应的累计签到加成（取不超过该天数的最高档位）。 */
    private long tierBonusAt(int day) {
        long bonus = 0L;
        if (!ClientShopData.signInCumulative) {
            return 0L;
        }
        for (int i = 0; i < ClientShopData.signInTierDays.length; i++) {
            if (day >= ClientShopData.signInTierDays[i]) {
                bonus = Math.max(bonus, i < ClientShopData.signInTierBonus.length
                        ? ClientShopData.signInTierBonus[i] : 0L);
            }
        }
        return bonus;
    }

    /** 0.8 倍小字号；{@code centered} = 以 x 为水平中心。 */
    private void small(GuiGraphics g, int x, int y, Component text, int color, boolean centered) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, centered ? -this.font.width(text) / 2 : 0, 0, color, false);
        g.pose().popPose();
    }

    private long currentTierBonus() {
        long bonus = 0L;
        if (!ClientShopData.signInCumulative) {
            return 0L;
        }
        for (int i = 0; i < ClientShopData.signInTierDays.length; i++) {
            if (ClientShopData.signInStreak >= ClientShopData.signInTierDays[i]) {
                bonus = Math.max(bonus, i < ClientShopData.signInTierBonus.length
                        ? ClientShopData.signInTierBonus[i] : 0L);
            }
        }
        return bonus;
    }

    /** 签到按钮：已签到显示「今日已完成签到」并禁用。 */
    /** 签到按钮：已签到显示「今日已完成签到」并禁用（放在进度条下方，不与日历重叠）。 */
    private void drawCheckInButton(GuiGraphics g, int x, int y) {
        int bw = 150;
        int bh = 22;
        int bx = x + (PANEL_W - bw) / 2;
        int by = y + 48;
        boolean done = ClientShopData.signInDone;
        IOStyle.button(g, bx, by, bw, bh,
                Component.translatable(done ? "gui.module_shop.signin.done" : "gui.module_shop.signin.do"),
                this.font, this.pointerX, this.pointerY, !done && ClientShopData.signInEnabled,
                done ? IOStyle.Btn.GRAY : IOStyle.Btn.PRIMARY);
        if (IOStyle.inside(bx, by, bw, bh, this.pointerX, this.pointerY)) {
            // 悬浮提示：本次签到可获得多少星币（基础 + 当前累计加成）。
            g.renderTooltip(this.font, Component.translatable("gui.module_shop.signin.gain_tip",
                    ClientShopData.money(ClientShopData.signInBase + currentTierBonus())), this.pointerX,
                    this.pointerY);
        }
        if (ClientShopData.canAdmin && this.editMode) {
            addRegion(bx, by, bw, bh, "admin_signin_auto_toggle", null, null);
        } else if (!done && ClientShopData.signInEnabled) {
            addRegion(bx, by, bw, bh, "sign_in", null, null);
        }
    }

    /** 日历：年份在左、月份在右（各自带翻页），今日高亮、已签到变绿、补签、日程颜色小条。 */
    private void drawCalendar(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int gridX = x + 16;
        int headY = y + 76;
        int weekY = y + 86;
        int cellY = weekY + 12;
        YearMonth ym = YearMonth.of(ClientShopData.signInMonth / 12, ClientShopData.signInMonth % 12 + 1);
        LocalDate today = LocalDate.now();
        java.util.Locale locale = Minecraft.getInstance().getLanguageManager().getJavaLocale();
        String yearText = Integer.toString(ym.getYear());
        int yx = gridX;
        drawArrow(g, yx, headY - 3, "<<", "year_prev");
        IOStyle.text(g, this.font, Component.literal(yearText), yx + 16, headY, IOStyle.TEXT);
        int yArrowX = yx + 16 + this.font.width(yearText) + 2;
        drawArrow(g, yArrowX, headY - 3, ">>", "year_next");
        String monthText = ym.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, locale);
        int mTail = 16 + this.font.width(monthText) + 2 + 12;
        int mx = Math.max(yArrowX + 14, gridX + COLS * CELL_W - mTail);
        drawArrow(g, mx, headY - 3, "<", "month_prev");
        IOStyle.text(g, this.font, Component.literal(monthText), mx + 16, headY, IOStyle.TEXT);
        drawArrow(g, mx + 16 + this.font.width(monthText) + 2, headY - 3, ">", "month_next");
        for (int c = 0; c < COLS; c++) {
            String dow = java.time.DayOfWeek.of(c + 1).getDisplayName(java.time.format.TextStyle.NARROW, locale);
            IOStyle.centerAt(g, this.font, Component.literal(dow), gridX + c * CELL_W + CELL_W / 2, weekY,
                    IOStyle.TEXT_2);
        }
        int firstOffset = ym.atDay(1).getDayOfWeek().getValue() - 1;
        int length = ym.lengthOfMonth();
        for (int d = 1; d <= length; d++) {
            LocalDate date = ym.atDay(d);
            int idx = firstOffset + d - 1;
            int cx = gridX + (idx % COLS) * CELL_W;
            int cy = cellY + (idx / COLS) * CELL_H;
            boolean isToday = date.equals(today);
            boolean signed = ClientShopData.signInDays.contains(date.toEpochDay());
            boolean future = date.isAfter(today);
            boolean canMakeup = !signed && date.isBefore(today)
                    && date.toEpochDay() >= today.toEpochDay() - ClientShopData.signInMakeupDays;
            int bg = signed ? IOStyle.GREEN_TINT : future ? IOStyle.DISABLED_BG : IOStyle.CARD;
            IOStyle.rounded(g, cx + 1, cy + 1, CELL_W - 3, CELL_H - 3, 5, bg);
            if (isToday) {
                IOStyle.ring(g, cx + 1, cy + 1, CELL_W - 3, CELL_H - 3, 5, IOStyle.BLUE);
            }
            IOStyle.centerAt(g, this.font, Component.literal(Integer.toString(d)), cx + CELL_W / 2, cy + 3,
                    signed ? IOStyle.GREEN_DARK : future ? IOStyle.DISABLED_TEXT : IOStyle.TEXT);
            List<ClientShopData.ActivityView> acts = activitiesOf(date.toEpochDay());
            for (int k = 0; k < acts.size() && k < 3; k++) {
                int barW = Math.max(4, (CELL_W - 8) / Math.min(3, Math.max(1, acts.size())));
                IOStyle.rounded(g, cx + 4 + k * barW, cy + CELL_H - 6, barW - 1, 3, 1,
                        acts.get(k).color == 0 ? IOStyle.ORANGE : acts.get(k).color);
            }
            boolean hover = IOStyle.inside(cx + 1, cy + 1, CELL_W - 3, CELL_H - 3, mouseX, mouseY);
            if (hover) {
                if (!acts.isEmpty()) {
                    // 第八轮：日程改用自绘悬浮窗（从上到下：标题 → 正文 → 金额 → 物品图标）。
                    this.hoverActs = acts;
                } else if (canMakeup) {
                    this.hoverTip = Component.translatable("gui.module_shop.signin.makeup_tip",
                            ClientShopData.money(ClientShopData.signInMakeupCost)).getString();
                } else if (isToday) {
                    this.hoverTip = Component.translatable(signed
                            ? "gui.module_shop.signin.done" : "gui.module_shop.signin.do").getString();
                }
            }
            if (ClientShopData.canAdmin && this.editMode) {
                CompoundTag newExtra = new CompoundTag();
                newExtra.putLong("day", date.toEpochDay());
                addRegion(cx + 1, cy + 1, CELL_W - 3, CELL_H - 3, "activity_new", null, newExtra);
            } else if (canMakeup) {
                CompoundTag extra = new CompoundTag();
                extra.putLong("day", date.toEpochDay());
                addRegion(cx + 1, cy + 1, CELL_W - 3, CELL_H - 3, "sign_makeup", null, extra);
            }
            if (ClientShopData.canAdmin && !acts.isEmpty()) {
                CompoundTag editExtra = new CompoundTag();
                editExtra.putLong("day", date.toEpochDay());
                addRegion(cx + CELL_W - 10, cy + 1, 9, 9, "activity_edit", "activity_clear", editExtra);
            }
        }
    }

    /** 某天的全部日程。 */
    private static List<ClientShopData.ActivityView> activitiesOf(long day) {
        List<ClientShopData.ActivityView> list = new ArrayList<>();
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day) {
                list.add(view);
            }
        }
        return list;
    }
    private void drawArrow(GuiGraphics g, int x, int y, String label, String action) {
        double[] m = IOStyle.mouseGui();
        boolean hover = IOStyle.inside(x, y, 12, 12, m[0], m[1]);
        IOStyle.smallButton(g, x, y, 12, 12, Component.literal(label), this.font, hover, IOStyle.GRAY_BTN,
                IOStyle.GRAY_BTN_HOVER, IOStyle.TEXT, (int) Math.round(m[0]), (int) Math.round(m[1]));
        addRegion(x, y, 12, 12, action, null, null);
    }

    /** 管理员：连续签到设置 / 累计加成 / 自动签到 / 启动编辑（整行上移一行，给底部「返回」让位）。 */
    private void drawAdminRow(GuiGraphics g, int x, int y) {
        int by = y + PANEL_H - 36;
        int bw = 88;
        addRegion(x + 12, by, bw, 14, "admin_signin_cumulative_toggle", null, null);
        drawSmallButton(g, x + 12, by, bw, Component.translatable("gui.module_shop.signin.cumulative"),
                ClientShopData.signInCumulative);
        addRegion(x + 12 + bw + 6, by, bw, 14, "admin_signin_auto_toggle", null, null);
        drawSmallButton(g, x + 12 + bw + 6, by, bw, Component.translatable("gui.module_shop.signin.auto"),
                ClientShopData.signInAuto);
        addRegion(x + 12 + (bw + 6) * 2, by, bw, 14, "admin_signin_set", null, null);
        drawSmallButton(g, x + 12 + (bw + 6) * 2, by, bw,
                Component.translatable("gui.module_shop.signin.settings"), false);
        int editW = 66;
        int editX = x + PANEL_W - 12 - editW;
        addRegion(editX, by, editW, 14, "toggle_edit", null, null);
        drawSmallButton(g, editX, by, editW, Component.translatable("gui.module_shop.signin.edit_start"),
                this.editMode);
    }

    /** 右下角返回按钮（所有人可见，单独一行，不与管理员按键重叠）。 */
    private void drawBackRow(GuiGraphics g, int x, int y) {
        int by = y + PANEL_H - 18;
        int bw = 72;
        int bx = x + PANEL_W - 14 - bw;
        IOStyle.button(g, bx, by, bw, 16, Component.translatable("gui.module_shop.back"), this.font,
                this.pointerX, this.pointerY, true, IOStyle.Btn.GRAY);
        addRegion(bx, by, bw, 16, "back", null, null);
    }
    private void drawSmallButton(GuiGraphics g, int x, int y, int w, Component label, boolean on) {
        double[] m = IOStyle.mouseGui();
        boolean hover = IOStyle.inside(x, y, w, 14, m[0], m[1]);
        IOStyle.smallButton(g, x, y, w, 14, label, this.font, hover,
                on ? IOStyle.GREEN_TINT : IOStyle.GRAY_BTN,
                on ? IOStyle.GREEN_TINT_HOVER : IOStyle.GRAY_BTN_HOVER,
                on ? IOStyle.GREEN_DARK : IOStyle.TEXT, (int) Math.round(m[0]), (int) Math.round(m[1]));
    }

    private void addRegion(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
        this.regions.add(new Region(x, y, w, h, action, rightAction, extra));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (Region region : this.regions) {
            if (!IOStyle.inside(region.x, region.y, region.w, region.h, mouseX, mouseY)) {
                continue;
            }
            if (button == 0 && region.action != null) {
                Bricks.click();
                onAction(region.action, region.extra);
                return true;
            }
            if (button == 1 && region.rightAction != null) {
                Bricks.click();
                onAction(region.rightAction, region.extra);
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onAction(String action, CompoundTag extra) {
        switch (action) {
            case "sign_in" -> send("sign_in", null);
            case "sign_makeup" -> {
                long makeupDay = extra == null ? 0L : extra.getLong("day");
                if (makeupDay > 0L) {
                    // 第三项：补签前二次确认（显示日期与花费），避免误点扣星币。
                    Nav.open(new ShopConfirmScreen(
                            Component.translatable("gui.module_shop.signin.makeup_title"),
                            Component.translatable("gui.module_shop.signin.makeup_tip",
                                    ClientShopData.money(ClientShopData.signInMakeupCost),
                                    java.time.LocalDate.ofEpochDay(makeupDay).toString()),
                            ok -> {
                                if (ok) {
                                    CompoundTag tag = new CompoundTag();
                                    tag.putLong("day", makeupDay);
                                    send("sign_makeup", tag);
                                }
                            }));
                }
            }
            case "admin_signin_cumulative_toggle" -> send("admin_signin_cumulative_toggle", null);
            case "admin_signin_auto_toggle" -> send("admin_signin_auto_toggle", null);
            case "admin_signin_set" -> openSettings();
            case "toggle_edit" -> this.editMode = !this.editMode;
            case "back" -> onClose();
            case "activity_new" -> openActivityEdit(extra);
            case "activity_edit" -> openActivityEdit(extra);
            case "activity_clear" -> send("admin_signin_day_clear", extra);
            case "month_prev" -> ClientShopData.signInMonth--;
            case "month_next" -> ClientShopData.signInMonth++;
            case "year_prev" -> ClientShopData.signInMonth -= 12;
            case "year_next" -> ClientShopData.signInMonth += 12;
            default -> send(action, extra);
        }
    }

    /** 管理员：一次填写每日奖励、补签花费/范围、累计档位与里程奖励。 */
    private void openSettings() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "input");
        tag.putString("action", "admin_signin_set");
        tag.putString("title", "gui.module_shop.signin.settings");
        ListTag fields = new ListTag();
        for (String f : new String[]{"base", "makeupCost", "makeupDays", "tiers", "miles"}) {
            fields.add(StringTag.valueOf(f));
        }
        tag.put("fields", fields);
        tag.putString("default_base", Long.toString(ClientShopData.signInBase));
        tag.putString("default_makeupCost", Long.toString(ClientShopData.signInMakeupCost));
        tag.putString("default_makeupDays", Integer.toString(ClientShopData.signInMakeupDays));
        tag.putString("default_tiers", pairs(ClientShopData.signInTierDays, ClientShopData.signInTierBonus));
        tag.putString("default_miles", pairs(ClientShopData.signInMileDays, ClientShopData.signInMileMoney));
        Nav.open(new ShopInputScreen(tag));
    }

    private static String pairs(int[] days, long[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < days.length && i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(days[i]).append(':').append(values[i]);
        }
        return sb.toString();
    }

    /** 取某天的活动日配置（没有返回 null）。 */
    private static ClientShopData.ActivityView activityOf(long day) {
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day) {
                return view;
            }
        }
        return null;
    }

    /** 管理员：打开独立的日程编辑界面（颜色 / 名称 / 正文 / 定时发放 / 物品 / 离线领取）。 */
    private void openActivityEdit(CompoundTag extra) {
        long day = extra == null ? ClientShopData.signInToday : extra.getLong("day");
        Nav.open(new ScheduleEditScreen(day));
    }

    private void send(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        tag.putString("action", action);
        ServerboundShopActionPayload.send(tag);
    }

    private record Region(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
    }
}
