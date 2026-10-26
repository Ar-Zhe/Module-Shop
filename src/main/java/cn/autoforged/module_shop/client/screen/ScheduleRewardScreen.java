package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 日程奖励 · 设置界面（第八轮版式修正）：
 * **发放规则**（定时发放 / 补发离线玩家 / 错过即补发）＋ **一个「日程奖励」按钮**。
 * <p>
 * 奖励本身（星币 + 多件物品）由 {@link ScheduleRewardItemsScreen} 配置，那里**直接沿用「物品发放」的逻辑**：
 * 3×9 真实物品槽 + 金额，而不是在本界面里堆一堆「当天奖励 / 日程物品 / 额外物品」按钮。
 * <p>
 * 面板高度按常见 GUI 缩放（1920×1080 → 480×258）收紧到 216，标题不会再顶出面板外面。
 */
public class ScheduleRewardScreen extends Screen {
    private static final int PANEL_W = 396;
    private static final int PANEL_H = 216;
    /** 时间选择弹窗里的快捷时间（-1 = 不定时）。 */
    private static final int[] QUICK_HOURS = {-1, 0, 6, 12, 18, 20};

    private final long day;
    private final int color;
    private final String title;
    private final String body;
    private final List<Region> regions = new ArrayList<>();

    private boolean offline;
    private boolean catchUp = true;
    private int hour = -1;
    private boolean hourPickerOpen;
    private int pointerX;
    private int pointerY;

    public ScheduleRewardScreen(long day, int color, String title, String body) {
        super(Component.translatable("gui.module_shop.schedule.reward_title"));
        this.day = day;
        this.color = color;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        ClientShopData.ActivityView act = find(day);
        this.offline = act != null && act.forOffline;
        this.catchUp = act == null || act.catchUp;
        this.hour = act == null ? -1 : act.hour;
    }

    private static ClientShopData.ActivityView find(long day) {
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day) {
                return view;
            }
        }
        return null;
    }

    private long currentMoney() {
        ClientShopData.ActivityView act = find(this.day);
        return act == null ? 0L : act.money;
    }

    private String currentItems() {
        ClientShopData.ActivityView act = find(this.day);
        if (act == null || act.itemText == null || act.itemText.isEmpty()) {
            return "-";
        }
        return act.itemText;
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
    protected void init() {
        super.init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.regions.clear();
        this.pointerX = mouseX;
        this.pointerY = mouseY;
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.schedule.reward_title"),
                x + PANEL_W / 2, y + 8, IOStyle.TEXT);
        // ---------- 发放规则 ----------
        small(g, x + 16, y + 26, Component.translatable("gui.module_shop.schedule.section_rule"));
        section(g, x + 12, y + 34, PANEL_W - 24, 84);
        small(g, x + 22, y + 47, Component.translatable("gui.module_shop.schedule.hour_short"));
        IOStyle.rounded(g, x + 104, y + 42, 80, 20, 5, IOStyle.FIELD_BG);
        IOStyle.ring(g, x + 104, y + 42, 80, 20, 5, IOStyle.TAB_BORDER);
        IOStyle.centerAt(g, this.font, Component.literal(hourText()), x + 144, y + 48, IOStyle.TEXT);
        addRegion(x + 104, y + 42, 80, 20, "hour_pick", null, null);
        IOStyle.button(g, x + 190, y + 42, 76, 20, Component.translatable("gui.module_shop.schedule.pick_hour"),
                this.font, this.pointerX, this.pointerY, true, IOStyle.Btn.TINT);
        addRegion(x + 190, y + 42, 76, 20, "hour_pick", null, null);
        small(g, x + 272, y + 47, Component.translatable("gui.module_shop.schedule.hour_hint"));
        toggleRow(g, x, y + 70, "gui.module_shop.schedule.offline_short", this.offline, "toggle_offline");
        toggleRow(g, x, y + 94, "gui.module_shop.schedule.catchup", this.catchUp, "toggle_catchup");
        // ---------- 奖励：只有一个「日程奖励」按钮 ----------
        small(g, x + 16, y + 126, Component.translatable("gui.module_shop.schedule.section_reward"));
        section(g, x + 12, y + 134, PANEL_W - 24, 40);
        IOStyle.button(g, x + 22, y + 143, 110, 22,
                Component.translatable("gui.module_shop.schedule.reward_btn"), this.font, this.pointerX,
                this.pointerY, true, IOStyle.Btn.PRIMARY);
        addRegion(x + 22, y + 143, 110, 22, "reward_items", null, null);
        small(g, x + 144, y + 144, Component.translatable("gui.module_shop.schedule.reward_money_line",
                ClientShopData.money(currentMoney())), IOStyle.ORANGE);
        small(g, x + 144, y + 156, Component.literal(IOStyle.ellipsize(this.font,
                Component.literal(currentItems()), 296).getString()));
        // ---------- 底部按钮 ----------
        int by = y + PANEL_H - 32;
        IOStyle.button(g, x + 14, by, 88, 22, Component.translatable("gui.module_shop.schedule.cancel"),
                this.font, this.pointerX, this.pointerY, true, IOStyle.Btn.GRAY);
        addRegion(x + 14, by, 88, 22, "cancel", null, null);
        IOStyle.button(g, x + PANEL_W - 14 - 96, by, 96, 22,
                Component.translatable("gui.module_shop.schedule.save"), this.font, this.pointerX, this.pointerY,
                true, IOStyle.Btn.PRIMARY);
        addRegion(x + PANEL_W - 14 - 96, by, 96, 22, "save", null, null);
        // ---------- 弹窗 ----------
        if (this.hourPickerOpen) {
            this.regions.clear();
            drawHourPicker(g, x, y);
        }
    }

    /** 左标签 + 右开关的一行（标签与开关横向分离，永不重叠）。 */
    private void toggleRow(GuiGraphics g, int x, int y, String labelKey, boolean on, String action) {
        small(g, x + 22, y + 7, Component.translatable(labelKey));
        int tgW = 34;
        int tgH = 16;
        int tgX = x + 150;
        IOStyle.rounded(g, tgX, y, tgW, tgH, tgH / 2, on ? IOStyle.GREEN : IOStyle.TOGGLE_OFF);
        if (!on) {
            IOStyle.ring(g, tgX, y, tgW, tgH, tgH / 2, IOStyle.TOGGLE_OFF_RING);
        }
        int knob = tgH - 4;
        IOStyle.rounded(g, on ? tgX + tgW - 2 - knob : tgX + 2, y + 2, knob, knob, knob / 2, IOStyle.KNOB);
        addRegion(tgX, y, tgW, tgH, action, null, null);
        small(g, x + 194, y + 7, Component.translatable(on
                ? "gui.module_shop.schedule.on" : "gui.module_shop.schedule.off"),
                on ? IOStyle.GREEN : IOStyle.TEXT_2);
    }

    private void section(GuiGraphics g, int x, int y, int w, int h) {
        IOStyle.rounded(g, x, y, w, h, 6, IOStyle.SLOT_INNER);
        IOStyle.ring(g, x, y, w, h, 6, IOStyle.HAIR);
    }

    private void small(GuiGraphics g, int x, int y, Component text) {
        small(g, x, y, text, IOStyle.TEXT_2);
    }

    private void small(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private String hourText() {
        return this.hour < 0 ? Component.translatable("gui.module_shop.schedule.none").getString()
                : String.format("%02d:00", this.hour);
    }

    /** 时间选择弹窗：小时步进 + 快捷时间（不再手输数字）。 */
    private void drawHourPicker(GuiGraphics g, int x, int y) {
        int px = x + (PANEL_W - 280) / 2;
        int py = y + 62;
        IOStyle.rounded(g, px, py, 280, 110, 8, 0xF01A1C20);
        IOStyle.ring(g, px, py, 280, 110, 8, IOStyle.BLUE);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.schedule.pick_hour"), px + 140,
                py + 8, IOStyle.TEXT);
        IOStyle.button(g, px + 24, py + 26, 44, 20, Component.literal("-1h"), this.font, this.pointerX, this.pointerY,
                true, IOStyle.Btn.GRAY);
        addRegion(px + 24, py + 26, 44, 20, "hour_minus", null, null);
        IOStyle.rounded(g, px + 76, py + 26, 128, 20, 6, IOStyle.FIELD_BG);
        IOStyle.ring(g, px + 76, py + 26, 128, 20, 6, IOStyle.TAB_BORDER);
        IOStyle.centerAt(g, this.font, Component.literal(hourText()), px + 140, py + 32, IOStyle.TEXT);
        IOStyle.button(g, px + 212, py + 26, 44, 20, Component.literal("+1h"), this.font, this.pointerX,
                this.pointerY, true, IOStyle.Btn.GRAY);
        addRegion(px + 212, py + 26, 44, 20, "hour_plus", null, null);
        for (int i = 0; i < QUICK_HOURS.length; i++) {
            int h = QUICK_HOURS[i];
            int bx = px + 20 + i * 41;
            boolean on = this.hour == h;
            IOStyle.rounded(g, bx, py + 56, 37, 18, 5, on ? IOStyle.BLUE_TINT : IOStyle.GRAY_BTN);
            IOStyle.centerAt(g, this.font, Component.literal(h < 0
                    ? Component.translatable("gui.module_shop.schedule.none").getString()
                    : String.format("%02d:00", h)), bx + 18, py + 61, on ? IOStyle.BLUE : IOStyle.TEXT);
            addRegion(bx, py + 56, 37, 18, "quick:" + h, null, null);
        }
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.schedule.pick_hour_hint"),
                px + 140, py + 84, IOStyle.TEXT_2);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.schedule.esc_hint"), px + 140,
                py + 96, IOStyle.TEXT_3);
    }

    private void addRegion(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
        this.regions.add(new Region(x, y, w, h, action, rightAction, extra));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && this.hourPickerOpen) {
            this.hourPickerOpen = false;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (Region region : this.regions) {
            if (!IOStyle.inside(region.x, region.y, region.w, region.h, mouseX, mouseY)) {
                continue;
            }
            if (button == 0 && region.action != null) {
                Bricks.click();
                onAction(region.action);
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onAction(String action) {
        if (action.startsWith("quick:")) {
            this.hour = Integer.parseInt(action.substring("quick:".length()));
            this.hourPickerOpen = false;
            return;
        }
        switch (action) {
            case "toggle_offline" -> this.offline = !this.offline;
            case "toggle_catchup" -> this.catchUp = !this.catchUp;
            case "hour_pick" -> this.hourPickerOpen = !this.hourPickerOpen;
            case "hour_minus" -> this.hour = this.hour <= 0 ? 23 : this.hour - 1;
            case "hour_plus" -> this.hour = this.hour < 0 || this.hour >= 23 ? 0 : this.hour + 1;
            case "reward_items" -> openRewardItems();
            case "save" -> save();
            case "cancel" -> onClose();
            default -> {
            }
        }
    }

    /**
     * 打开「日程奖励」界面（物品发放式）。
     * <p>
     * 容器界面由**服务端**打开（这样物品槽才是服务端权威的），所以这里先记住日期与「返回哪个界面」。
     */
    private void openRewardItems() {
        ClientShopData.scheduleDay = this.day;
        ScheduleRewardItemsScreen.setReturnTo(this);
        CompoundTag tag = new CompoundTag();
        tag.putLong("day", this.day);
        tag.putString("action", "admin_signin_reward_open");
        ServerboundShopActionPayload.send(tag);
    }

    /**
     * 保存：只写**本界面负责的字段**（定时发放 / 补发离线 / 错过即补发）。
     * <p>
     * 标题、正文、颜色由「日程编辑」界面写，星币与物品由「日程奖励」容器界面写 ——
     * 每个界面只提交自己的键，服务端对未携带的字段保持原值，谁都不会把别人的编辑覆盖掉。
     */
    private void save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("day", this.day);
        tag.putString("hour", this.hour < 0 ? "" : Integer.toString(this.hour));
        tag.putString("offline", this.offline ? "true" : "false");
        tag.putString("catchUp", this.catchUp ? "true" : "false");
        tag.putString("action", "admin_signin_day_set");
        ServerboundShopActionPayload.send(tag);
        Nav.back();
    }

    private record Region(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
    }
}
