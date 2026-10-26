package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.client.ClientShopData;
import cn.autoforged.server_shop_mod_1789689358.network.payload.ServerboundShopActionPayload;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 账单（点主界面顶部余额进入）—— 参照支付软件的收支明细：
 * <ul>
 *     <li>顶部汇总卡：当前余额 + 今日支出 / 今日收入 + 本月支出 / 本月收入；</li>
 *     <li>筛选：全部 / 支出 / 收入（三个小页签）；</li>
 *     <li>列表：按天分组（今天 / 昨天 / 日期），每天一行小标题显示当天收支合计；</li>
 *     <li>每行：类型名 + 金额（收入绿 / 支出红），第二行是备注与对方、右侧时间与变动后余额；</li>
 *     <li>滚轮整行滚动 + 右侧滑块 + "下面还有"提示；界面停留时会定时自动刷新；</li>
 *     <li>外部插件通过 {@code /shop wallet log} 记进来的收支也会出现在这里（类型显示为"外部账目"）。</li>
 * </ul>
 */
public class BillScreen extends Screen {
    private static final int PANEL_W = 392;
    private static final int PANEL_H = 252;
    private static final int ROW_H = 24;
    private static final int DAY_H = 14;
    private static final int LIST_TOP = 78;
    private static final int LIST_BOTTOM = PANEL_H - 30;
    private static final int FILTER_ALL = 0;
    private static final int FILTER_OUT = 1;
    private static final int FILTER_IN = 2;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd");

    /** 列表里的一行：要么是日期小标题，要么是一条明细。 */
    private record Row(boolean dayHeader, String dayText, long dayIn, long dayOut, ClientShopData.BillEntry entry) {
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<int[]> tabs = new ArrayList<>();
    private int scrollRow;
    private int filter = FILTER_ALL;
    private int tickCounter;
    private int pointerX;
    private int pointerY;

    public BillScreen() {
        super(Component.translatable("gui.server_shop_mod.bill.title"));
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
    public void tick() {
        super.tick();
        this.tickCounter++;
        // 每 2 秒刷新一次：买卖、领取、转账之后账单能立刻跟着变。
        if (this.tickCounter % 40 == 0) {
            requestBill();
        }
    }

    private void requestBill() {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "bill_request");
        ServerboundShopActionPayload.send(tag);
    }

    @Override
    public void onClose() {
        Nav.back();
    }

    /** 当前筛选下的明细列表（最新在前）。 */
    private List<ClientShopData.BillEntry> filtered() {
        List<ClientShopData.BillEntry> list = new ArrayList<>();
        for (ClientShopData.BillEntry entry : ClientShopData.bill) {
            if (this.filter == FILTER_OUT && entry.amount >= 0L) {
                continue;
            }
            if (this.filter == FILTER_IN && entry.amount <= 0L) {
                continue;
            }
            list.add(entry);
        }
        return list;
    }

    /** 把明细按天分组，生成「日期标题 + 明细行」的扁平列表。 */
    private void buildRows() {
        this.rows.clear();
        List<ClientShopData.BillEntry> list = filtered();
        Map<String, List<ClientShopData.BillEntry>> days = new LinkedHashMap<>();
        for (ClientShopData.BillEntry entry : list) {
            days.computeIfAbsent(dayKey(entry.time), k -> new ArrayList<>()).add(entry);
        }
        LocalDate today = LocalDate.now();
        for (Map.Entry<String, List<ClientShopData.BillEntry>> day : days.entrySet()) {
            long in = 0L;
            long out = 0L;
            for (ClientShopData.BillEntry entry : day.getValue()) {
                if (entry.amount > 0L) {
                    in += entry.amount;
                } else {
                    out += -entry.amount;
                }
            }
            String label = day.getKey();
            try {
                LocalDate date = LocalDate.parse(day.getKey());
                if (date.equals(today)) {
                    label = Component.translatable("gui.server_shop_mod.bill.today").getString();
                } else if (date.equals(today.minusDays(1))) {
                    label = Component.translatable("gui.server_shop_mod.bill.yesterday").getString();
                } else {
                    label = DATE_FORMAT.format(date);
                }
            } catch (Exception ignored) {
            }
            this.rows.add(new Row(true, label, in, out, null));
            for (ClientShopData.BillEntry entry : day.getValue()) {
                this.rows.add(new Row(false, "", 0L, 0L, entry));
            }
        }
    }

    private static String dayKey(long time) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(time <= 0L ? System.currentTimeMillis() : time),
                ZoneId.systemDefault()).toString();
    }

    private int visibleHeight() {
        return LIST_BOTTOM - LIST_TOP;
    }

    private int maxScroll() {
        int used = 0;
        for (Row row : this.rows) {
            used += row.dayHeader() ? DAY_H : ROW_H;
        }
        return Math.max(0, used - visibleHeight());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.pointerX = mouseX;
        this.pointerY = mouseY;
        this.tabs.clear();
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.server_shop_mod.bill.title"), x + PANEL_W / 2, y + 8,
                IOStyle.ORANGE);
        drawSummary(g, x, y);
        drawFilterTabs(g, x, y, mouseX, mouseY);
        buildRows();
        this.scrollRow = Math.max(0, Math.min(this.scrollRow, maxScroll()));
        drawList(g, x, y);
        drawFooter(g, x, y, mouseX, mouseY);
    }

    /** 顶部汇总卡：余额 + 今日 / 本月收支。 */
    private void drawSummary(GuiGraphics g, int x, int y) {
        int cardY = y + 22;
        IOStyle.rounded(g, x + 10, cardY, PANEL_W - 20, 34, 6, IOStyle.SLOT_INNER);
        IOStyle.ring(g, x + 10, cardY, PANEL_W - 20, 34, 6, IOStyle.HAIR);
        small(g, x + 18, cardY + 6, Component.translatable("gui.server_shop_mod.bill.balance"));
        IOStyle.text(g, this.font, Component.literal(ClientShopData.money(ClientShopData.billBalance)), x + 18,
                cardY + 16, IOStyle.ORANGE);
        // 右侧：今日 / 本月，各两行（支出 / 收入）
        int colX = x + PANEL_W - 18;
        small(g, colX - 92, cardY + 4, Component.translatable("gui.server_shop_mod.bill.today_in",
                ClientShopData.money(ClientShopData.billTodayIn)), IOStyle.GREEN, true);
        small(g, colX - 92, cardY + 13, Component.translatable("gui.server_shop_mod.bill.today_out",
                ClientShopData.money(ClientShopData.billTodayOut)), IOStyle.RED, true);
        small(g, colX - 92, cardY + 22, Component.translatable("gui.server_shop_mod.bill.month",
                ClientShopData.money(ClientShopData.billMonthOut),
                ClientShopData.money(ClientShopData.billMonthIn)), IOStyle.TEXT_2, true);
    }

    /** 全部 / 支出 / 收入 三个筛选页签。 */
    private void drawFilterTabs(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        String[] keys = {"gui.server_shop_mod.bill.filter_all", "gui.server_shop_mod.bill.filter_out",
                "gui.server_shop_mod.bill.filter_in"};
        int tabW = 56;
        int tabH = 16;
        int tx = x + 10;
        for (int i = 0; i < keys.length; i++) {
            boolean on = this.filter == i;
            boolean hover = IOStyle.inside(tx, y + 60, tabW, tabH, mouseX, mouseY);
            IOStyle.rounded(g, tx, y + 60, tabW, tabH, 8, on ? IOStyle.BLUE : (hover ? IOStyle.GRAY_BTN_HOVER
                    : IOStyle.GRAY_BTN));
            IOStyle.centerAt(g, this.font, Component.translatable(keys[i]), tx + tabW / 2, y + 64,
                    on ? 0xFFFFFFFF : IOStyle.TEXT);
            this.tabs.add(new int[]{tx, y + 60, tabW, tabH, i});
            tx += tabW + 6;
        }
        small(g, tx + 4, y + 64, Component.translatable("gui.server_shop_mod.bill.count",
                ClientShopData.billSize, ClientShopData.billLimit));
    }

    /** 明细列表（日期标题 + 每行一条）。 */
    private void drawList(GuiGraphics g, int x, int y) {
        int listTop = y + LIST_TOP;
        int listBottom = y + LIST_BOTTOM;
        // 列表顶部画一条分隔线：它就是这个列表的**上边框**，下面的内容一律裁在这条线以内。
        // （以前滚动时只判断"行的下半截还在不在"，于是最上面那半行会被画到页签、汇总卡上；
        //   现在同时加 scissor 裁剪 + 正确的可见性判断，任何情况下都不会越界。）
        g.fill(x + 10, listTop - 3, x + PANEL_W - 10, listTop - 2, IOStyle.HAIR);
        if (this.rows.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.server_shop_mod.bill.empty"),
                    x + PANEL_W / 2, listTop + 30, IOStyle.TEXT_2);
            return;
        }
        int cursor = listTop - this.scrollRow;
        int contentBottom = listTop - this.scrollRow;
        for (Row row : this.rows) {
            contentBottom += row.dayHeader() ? DAY_H : ROW_H;
        }
        g.enableScissor(x + 10, listTop, x + PANEL_W - 10, listBottom);
        for (Row row : this.rows) {
            int height = row.dayHeader() ? DAY_H : ROW_H;
            // 只要与可视区有交集就画（被裁掉的部分由 scissor 负责）。
            if (cursor + height > listTop && cursor < listBottom) {
                if (row.dayHeader()) {
                    drawDayHeader(g, x, cursor, row);
                } else {
                    drawEntry(g, x, cursor, row.entry());
                }
            }
            cursor += height;
        }
        g.disableScissor();
        // 右侧滑块
        int used = contentBottom - listTop + this.scrollRow;
        if (used > visibleHeight()) {
            int trackX = x + PANEL_W - 12;
            g.fill(trackX, listTop, trackX + 2, listBottom, IOStyle.HAIR);
            int thumbH = Math.max(14, visibleHeight() * visibleHeight() / Math.max(1, used));
            int thumbY = listTop + (visibleHeight() - thumbH) * this.scrollRow / Math.max(1, maxScroll());
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, IOStyle.BLUE);
        }
        // 下面还有内容：浮动小三角
        if (this.scrollRow < maxScroll()) {
            int ax = x + PANEL_W / 2 - 5;
            int ay = listBottom + 1 + (int) Math.round(Math.sin(this.tickCounter / 6.0D) * 1.5D);
            g.fill(ax, ay, ax + 10, ay + 1, IOStyle.BLUE);
            g.fill(ax + 2, ay + 1, ax + 8, ay + 2, IOStyle.BLUE);
            g.fill(ax + 4, ay + 2, ax + 6, ay + 3, IOStyle.BLUE);
        }
    }

    private void drawDayHeader(GuiGraphics g, int x, int y, Row row) {
        g.fill(x + 10, y + 2, x + PANEL_W - 10, y + DAY_H - 2, 0x30101114);
        IOStyle.text(g, this.font, Component.literal(row.dayText()), x + 14, y + 4, IOStyle.TEXT_2);
        String detail = Component.translatable("gui.server_shop_mod.bill.day_sum",
                row.dayOut() > 0L ? "-" + ClientShopData.money(row.dayOut()) : "-",
                row.dayIn() > 0L ? "+" + ClientShopData.money(row.dayIn()) : "-").getString();
        IOStyle.right(g, this.font, Component.literal(detail), x + PANEL_W - 14, y + 4, IOStyle.TEXT_3);
    }

    private void drawEntry(GuiGraphics g, int x, int y, ClientShopData.BillEntry entry) {
        boolean income = entry.amount > 0L;
        boolean external = "external".equals(entry.type);
        // 类型色块（收入绿 / 支出红，外部账目用蓝色区分）
        int barColor = external ? IOStyle.BLUE : (income ? IOStyle.GREEN : IOStyle.RED);
        IOStyle.rounded(g, x + 12, y + 3, 3, ROW_H - 8, 1, barColor);
        IOStyle.text(g, this.font, typeName(entry), x + 20, y + 3, IOStyle.TEXT);
        String amountText = (income ? "+" : "") + ClientShopData.money(entry.amount);
        IOStyle.right(g, this.font, Component.literal(amountText), x + PANEL_W - 14, y + 3,
                income ? IOStyle.GREEN : IOStyle.RED);
        // 第二行：备注 / 对方
        StringBuilder sub = new StringBuilder();
        if (entry.note != null && !entry.note.isEmpty()) {
            sub.append(entry.note);
        }
        if (entry.party != null && !entry.party.isEmpty()) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(entry.party);
        }
        String subText = sub.length() == 0 ? "-" : sub.toString();
        IOStyle.text(g, this.font, IOStyle.ellipsize(this.font, Component.literal(subText), PANEL_W - 150), x + 20,
                y + 13, IOStyle.TEXT_2);
        // 右侧：时间 + 变动后余额（外部账目不动本模组余额，显示 "—"）
        String time = entry.time > 0L
                ? TIME_FORMAT.format(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault())) : "-";
        String right = external ? time + "  —" : time + "  " + Component.translatable(
                "gui.server_shop_mod.bill.balance_after", ClientShopData.money(entry.balanceAfter)).getString();
        IOStyle.right(g, this.font, Component.literal(right), x + PANEL_W - 14, y + 13, IOStyle.TEXT_3);
        g.fill(x + 12, y + ROW_H - 1, x + PANEL_W - 12, y + ROW_H, IOStyle.HAIR);
    }

    private Component typeName(ClientShopData.BillEntry entry) {
        String key = "gui.server_shop_mod.history." + (entry.type == null ? "" : entry.type);
        Component name = Component.translatable(key);
        // 没有对应翻译时退回到类型原文，避免界面上出现裸 key。
        return name.getString().startsWith("gui.") ? Component.literal(entry.type) : name;
    }

    /** 底部：返回 + 刷新 + 更新时间。 */
    private void drawFooter(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int by = y + PANEL_H - 24;
        IOStyle.button(g, x + 10, by, 64, 18, Component.translatable("gui.server_shop_mod.back"), this.font, mouseX,
                mouseY, true, IOStyle.Btn.GRAY);
        IOStyle.button(g, x + PANEL_W - 74, by, 64, 18, Component.translatable("gui.server_shop_mod.bill.refresh"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.TINT);
        if (ClientShopData.billUpdatedAt > 0L) {
            long ago = Math.max(0L, (System.currentTimeMillis() - ClientShopData.billUpdatedAt) / 1000L);
            IOStyle.centerAt(g, this.font, Component.translatable("gui.server_shop_mod.bill.updated", ago),
                    x + PANEL_W / 2, by + 5, IOStyle.TEXT_3);
        }
    }

    private void small(GuiGraphics g, int x, int y, Component text, int color, boolean centered) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, centered ? -this.font.width(text) / 2 : 0, 0, color, false);
        g.pose().popPose();
    }

    private void small(GuiGraphics g, int x, int y, Component text) {
        small(g, x, y, text, IOStyle.TEXT_2, false);
    }

    /** 以「行」为单位滚动（日期标题 / 明细行高度不同，逐行跳可以避免出现被裁掉一半的条目）。 */
    private void scrollBy(int dir) {
        List<Integer> offsets = new ArrayList<>();
        int offset = 0;
        for (Row row : this.rows) {
            offsets.add(offset);
            offset += row.dayHeader() ? DAY_H : ROW_H;
        }
        int max = Math.max(0, offset - visibleHeight());
        int next = this.scrollRow;
        if (dir > 0) {
            for (int value : offsets) {
                if (value > this.scrollRow) {
                    next = value;
                    break;
                }
            }
            if (next == this.scrollRow) {
                next = max;
            }
        } else {
            for (int i = offsets.size() - 1; i >= 0; i--) {
                if (offsets.get(i) < this.scrollRow) {
                    next = offsets.get(i);
                    break;
                }
            }
        }
        this.scrollRow = Math.max(0, Math.min(max, next));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            scrollBy(scrollY > 0.0D ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = left();
        int y = top();
        // 筛选页签
        for (int[] tab : this.tabs) {
            if (IOStyle.inside(tab[0], tab[1], tab[2], tab[3], mouseX, mouseY)) {
                Bricks.click();
                this.filter = tab[4];
                this.scrollRow = 0;
                return true;
            }
        }
        if (IOStyle.inside(x + 10, y + PANEL_H - 24, 64, 18, mouseX, mouseY)) {
            Bricks.click();
            onClose();
            return true;
        }
        if (IOStyle.inside(x + PANEL_W - 74, y + PANEL_H - 24, 64, 18, mouseX, mouseY)) {
            Bricks.click();
            requestBill();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
