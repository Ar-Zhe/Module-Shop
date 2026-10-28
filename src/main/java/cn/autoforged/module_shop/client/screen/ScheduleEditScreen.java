package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 日程编辑 · 主界面（第八轮版式修正）。
 * <p>
 * 版式约定（三处此前的重叠/重影问题都在这里修掉）：
 * <ul>
 *     <li>分区标题画在分区盒子**上方的空白行**，不再压住字段标签；</li>
 *     <li>字段采用「左标签 + 右输入框」，标签与输入框横向分离，纵向永不重叠；</li>
 *     <li>输入框用 {@code setBordered(false)} 关掉自带黑底，改画自己的底框 + 描边（聚焦变蓝），
 *     并且**在面板之后**再画一次控件，保证输入框不会面板盖住（此前根本看不见）。</li>
 * </ul>
 * 底部按钮：{@code [日程奖励][删除该日程]} …… {@code [保存][取消]}。
 */
public class ScheduleEditScreen extends Screen {
    private static final int PANEL_W = 396;
    private static final int PANEL_H = 216;
    private static final int[] PALETTE = {
            0xFFFF9800, 0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA,
            0xFF00ACC1, 0xFFFDD835, 0xFFD81B60, 0xFFECEFF1, 0xFF757575,
    };

    private final long day;
    private final List<Region> regions = new ArrayList<>();
    private EditBox titleBox;
    private EditBox bodyBox;
    private int color;
    private final boolean existed;
    private int pointerX;
    private int pointerY;

    public ScheduleEditScreen(long day) {
        super(Component.translatable("gui.module_shop.signin.activity"));
        this.day = day;
        ClientShopData.ActivityView act = find(day);
        this.color = act == null || act.color == 0 ? PALETTE[0] : act.color;
        // 「删除该日程」只看**正式日程**是否存在（草稿是新拉的，新日期也有草稿）。
        this.existed = published(day) != null;
        // 让服务端为本管理员建一份草稿：之后所有界面都只改草稿，点「保存」才发布。
        CompoundTag open = new CompoundTag();
        open.putLong("day", day);
        open.putString("action", "admin_signin_draft_open");
        ServerboundShopActionPayload.send(open);
    }

    /** 正式（已发布）日程里有没有这一天。 */
    private static ClientShopData.ActivityView published(long day) {
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day) {
                return view;
            }
        }
        return null;
    }

    /** 优先读草稿（正在编辑的值），没有草稿才读正式日程。 */
    private static ClientShopData.ActivityView find(long day) {
        ClientShopData.ActivityView draft = ClientShopData.activityDraft;
        if (draft != null && draft.day == day) {
            return draft;
        }
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day) {
                return view;
            }
        }
        return null;
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
        ClientShopData.ActivityView act = find(this.day);
        int x = left();
        int y = top();
        this.titleBox = addBox(x + 79, y + 49, PANEL_W - 79 - 21, act == null ? "" : act.title);
        this.bodyBox = addBox(x + 79, y + 75, PANEL_W - 79 - 21, act == null ? "" : act.note);
    }

    private EditBox addBox(int x, int y, int w, String preset) {
        EditBox box = new EditBox(this.font, x, y, w, 18, Component.empty());
        box.setMaxLength(240);
        // 关掉自带黑底，改用下面的 frame() 画统一底框。
        box.setBordered(false);
        if (preset != null && !preset.isEmpty()) {
            box.setValue(preset);
        }
        this.addRenderableWidget(box);
        return box;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.regions.clear();
        this.pointerX = mouseX;
        this.pointerY = mouseY;
        // 先画背景（super.render 会绘制背景与控件，面板要在它之后才能盖住背景）。
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.signin.activity"),
                x + PANEL_W / 2, y + 10, IOStyle.TEXT);
        IOStyle.centerAt(g, this.font, Component.literal(LocalDate.ofEpochDay(this.day).toString()),
                x + PANEL_W / 2, y + 22, IOStyle.TEXT_2);
        // ---------- 内容区 ----------
        small(g, x + 16, y + 31, Component.translatable("gui.module_shop.schedule.section_content"));
        section(g, x + 12, y + 40, PANEL_W - 24, 64);
        small(g, x + 22, y + 52, Component.translatable("gui.module_shop.field.title"));
        frame(g, x + 78, y + 46, PANEL_W - 78 - 20, 20, focused(this.titleBox));
        small(g, x + 22, y + 78, Component.translatable("gui.module_shop.field.note"));
        frame(g, x + 78, y + 72, PANEL_W - 78 - 20, 20, focused(this.bodyBox));
        // ---------- 外观区（已缩小） ----------
        small(g, x + 16, y + 111, Component.translatable("gui.module_shop.schedule.section_color"));
        section(g, x + 12, y + 120, PANEL_W - 24, 34);
        small(g, x + 22, y + 133, Component.translatable("gui.module_shop.schedule.label_color"));
        int sw = 18;
        for (int i = 0; i < PALETTE.length; i++) {
            int sx = x + 78 + i * (sw + 3);
            int sy = y + 128;
            IOStyle.rounded(g, sx, sy, sw, sw, sw / 2, PALETTE[i]);
            if ((this.color & 0xFFFFFF) == (PALETTE[i] & 0xFFFFFF)) {
                IOStyle.ring(g, sx - 2, sy - 2, sw + 4, sw + 4, (sw + 4) / 2, IOStyle.BLUE);
            }
            addRegion(sx, sy, sw, sw, "color:" + i, null, null);
        }
        // ---------- 底部按钮 ----------
        int by = y + 184;
        boolean ready = ready();
        btn(g, x + 14, by, 92, 22, "gui.module_shop.schedule.reward_btn", "reward", IOStyle.Btn.TINT, true);
        if (this.existed) {
            btn(g, x + 112, by, 88, 22, "gui.module_shop.schedule.delete_btn", "delete", IOStyle.Btn.DANGER,
                    true);
        }
        btn(g, x + PANEL_W - 14 - 84, by, 84, 22, "gui.module_shop.schedule.save", "save",
                ready ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY, ready);
        btn(g, x + PANEL_W - 14 - 84 - 8 - 76, by, 76, 22, "gui.module_shop.schedule.cancel", "cancel",
                IOStyle.Btn.GRAY, true);
        if (!ready) {
            small(g, x + 16, by - 15, Component.translatable("gui.module_shop.schedule.need_title"), IOStyle.RED);
        }
        // ---------- 最后重画输入框，确保浮在面板之上 ----------
        renderBox(g, this.titleBox, mouseX, mouseY, partialTick);
        renderBox(g, this.bodyBox, mouseX, mouseY, partialTick);
    }

    private static boolean focused(EditBox box) {
        return box != null && box.isFocused();
    }

    private void renderBox(GuiGraphics g, EditBox box, int mouseX, int mouseY, float partialTick) {
        if (box != null) {
            box.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void section(GuiGraphics g, int x, int y, int w, int h) {
        IOStyle.rounded(g, x, y, w, h, 6, IOStyle.SLOT_INNER);
        IOStyle.ring(g, x, y, w, h, 6, IOStyle.HAIR);
    }

    /** 输入框可视底框：聚焦时蓝色描边，让玩家一眼看出这里可以输入。 */
    private void frame(GuiGraphics g, int x, int y, int w, int h, boolean focus) {
        IOStyle.rounded(g, x, y, w, h, 5, IOStyle.FIELD_BG);
        IOStyle.ring(g, x, y, w, h, 5, focus ? IOStyle.BLUE : IOStyle.TAB_BORDER);
    }

    private void small(GuiGraphics g, int x, int y, Component text) {
        small(g, x, y, text, IOStyle.TEXT_2);
    }

    /** 统一 0.8 倍小字号标签（全模组尺寸一致，避免大字压框）。 */
    private void small(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private void btn(GuiGraphics g, int x, int y, int w, int h, String key, String action, IOStyle.Btn style,
            boolean enabled) {
        IOStyle.button(g, x, y, w, h, Component.translatable(key), this.font, this.pointerX, this.pointerY, enabled,
                style);
        if (enabled) {
            addRegion(x, y, w, h, action, null, null);
        }
    }

    private void addRegion(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
        this.regions.add(new Region(x, y, w, h, action, rightAction, extra));
    }

    private boolean ready() {
        return this.titleBox != null && !this.titleBox.getValue().isBlank();
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
        if (action.startsWith("color:")) {
            int idx = Integer.parseInt(action.substring("color:".length()));
            if (idx >= 0 && idx < PALETTE.length) {
                this.color = PALETTE[idx];
            }
            return;
        }
        switch (action) {
            case "save" -> save();
            case "delete" -> {
                CompoundTag tag = new CompoundTag();
                tag.putLong("day", this.day);
                tag.putString("action", "admin_signin_day_clear");
                ServerboundShopActionPayload.send(tag);
                Nav.back();
            }
            case "cancel" -> {
                // 取消 = 丢掉草稿，正式日程保持不变（以前这里是「什么都不做」，草稿会残留）。
                CompoundTag discard = new CompoundTag();
                discard.putLong("day", this.day);
                discard.putString("action", "admin_signin_day_discard");
                ServerboundShopActionPayload.send(discard);
                Nav.back();
            }
            case "reward" -> {
                // 进奖励界面**不再**提交标题/正文/颜色：那些等本界面点「保存」时才发布。
                Nav.open(new ScheduleRewardScreen(this.day, this.color, this.titleBox.getValue(),
                        this.bodyBox.getValue()));
            }
            default -> {
            }
        }
    }

    /** 保存：标题/正文/颜色由本界面写（其余字段由「日程奖励设置」写，互不覆盖）。 */
    private void save() {
        sendDay();
        Nav.back();
    }

    /** 把标题/正文/颜色发给服务端；只带这几个键，服务端对未携带的字段保持原值。 */
    private void sendDay() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("day", this.day);
        tag.putString("title", this.titleBox.getValue().trim());
        tag.putString("note", this.bodyBox.getValue().trim());
        tag.putString("color", String.format("#%06X", this.color & 0xFFFFFF));
        tag.putString("action", "admin_signin_day_set");
        ServerboundShopActionPayload.send(tag);
    }

    private record Region(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
    }
}
