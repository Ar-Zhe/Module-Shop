package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import com.atom.lib.design.atomchat.SkiaDraw;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 货币兑换页（Skija 版）。
 *
 * <p>一行一种货币：「该货币 = 多少星币」；点击行头展开，出现「兑换（物品→星币）」「取款（星币→物品）」，
 * 管理员另有「改价」「删除」。同一时间只展开一行。底部操作条：管理员「添加货币」+ 操作提示。
 *
 * <p>所有动作沿用旧界面的动作名，经 {@link ShopHost#action} 交给 ShopScreen 原有的分发处理
 * （exchange_open / exchange_deposit_gui / exchange_withdraw_gui / exchange_edit /
 * admin_exchange_remove / admin_exchange_add）。
 */
public final class ExchangePage implements ShopPage {
    private static final float ROW_H = 22.0F;
    private static final float OPEN_H = 46.0F;
    private static final float GAP = 4.0F;
    private static final float HEADER_HIT_H = 20.0F;
    private static final float SCROLLBAR_ROOM = 6.0F;
    private static final float BTN_W = 38.0F;
    private static final float BTN_H = 12.0F;

    /** 滚动位置跨界面重建保留（服务端每次操作后都会重建 ShopScreen）。 */
    private static float savedScroll;

    private final ScrollController scroll = new ScrollController();
    private boolean scrollRestored;
    private float[] openT = new float[0];
    private float[] hoverT = new float[0];

    private final Button deposit = small(Button.Kind.NORMAL, "gui.module_shop.exchange.deposit");
    private final Button withdraw = small(Button.Kind.NORMAL, "gui.module_shop.exchange.withdraw");
    private final Button edit = small(Button.Kind.NORMAL, "gui.module_shop.exchange.edit");
    private final Button remove = small(Button.Kind.DANGER, "gui.module_shop.exchange.remove");
    private final Button add = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.exchange.add").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);

    /** 本帧行头的命中区（与绘制同一份几何）。 */
    private final List<float[]> rowHits = new ArrayList<>();

    private ShopHost host;
    /** 展开行的按钮本帧是否真的画出来了（行被滚出视野时按钮不可点）。 */
    private boolean rowButtonsShown;

    private static Button small(Button.Kind kind, String key) {
        return new Button(kind, () -> Component.translatable(key).getString(), null)
                .radius(2.0F).fontSize(Text.SMALL);
    }

    @Override
    public String id() {
        return "exchange";
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        this.host = f.host();
        List<ClientShopData.ExchangeView> list = ClientShopData.exchange;
        ensureCapacity(list.size());
        rowHits.clear();
        rowButtonsShown = false;
        int open = ClientShopData.lastExchangeOpen;

        float listX = f.x();
        float listY = f.y() + 2.0F;
        float listW = f.w();
        float listH = f.listBottom() - listY;

        if (list.isEmpty()) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.exchange.empty").getString(),
                    AtomUi.REGULAR, Text.BODY, IOStyle.TEXT_2, listX, f.y() + 44.0F, listW, Text.Align.CENTER);
        } else {
            // 展开动画：每行高度在 ROW_H 与 OPEN_H 之间插值
            float content = 0.0F;
            for (int i = 0; i < list.size(); i++) {
                openT[i] = Motion.approach(openT[i], i == open ? 1.0F : 0.0F, f.dtMs(), Motion.PRESS_MS);
                content += rowHeight(i) + (i + 1 < list.size() ? GAP : 0.0F);
            }
            scroll.setContent(content, listH);
            if (!scrollRestored) {
                scroll.scrollTo(savedScroll, false);
                scrollRestored = true;
            }
            scroll.update();
            savedScroll = scroll.offset();

            float rowW = listW - (scroll.canScroll() ? SCROLLBAR_ROOM : 0.0F);
            boolean inList = f.mouseX() >= listX && f.mouseX() < listX + listW
                    && f.mouseY() >= listY && f.mouseY() < listY + listH;
            int saved = canvas.save();
            f.host().items().pushClip(listX, listY, listW, listH);
            try {
                Draw.clip(canvas, listX - 1.0F, listY - 1.0F, listW + 2.0F, listH + 2.0F);
                float y = listY - scroll.offset();
                for (int i = 0; i < list.size(); i++) {
                    float h = rowHeight(i);
                    if (y + h >= listY && y <= listY + listH) {
                        drawRow(canvas, f, i, list.get(i), listX, y, rowW, h, inList);
                    }
                    y += h + GAP;
                }
            } finally {
                f.host().items().popClip();
                canvas.restoreToCount(saved);
            }
            scroll.drawBar(canvas, listX, listY, listW, listH, f.mouseX(), f.mouseY(), f.dtMs());
            if (scroll.hasMoreBelow()) {
                SkiaDraw.drawVerticalGradient(canvas, listX, listY + listH - 10.0F, rowW, 10.0F, 0.0F,
                        Draw.alpha(IOStyle.CONTENT, 0.0F), IOStyle.CONTENT);
            }
        }
        drawBottomBar(canvas, f);
    }

    private float rowHeight(int i) {
        return ROW_H + (OPEN_H - ROW_H) * Motion.easeOutCubic(openT[i]);
    }

    private void drawRow(Canvas canvas, PageFrame f, int index, ClientShopData.ExchangeView entry,
                         float x, float y, float w, float h, boolean inList) {
        float t = openT[index];
        boolean headerHover = inList && f.mouseX() >= x && f.mouseX() < x + w
                && f.mouseY() >= y && f.mouseY() < y + HEADER_HIT_H;
        hoverT[index] = Motion.approach(hoverT[index], headerHover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
        rowHits.add(new float[]{x, y, w, HEADER_HIT_H, index});

        // 卡片：收起 = 普通卡片（悬停变亮），展开 = 强调色淡底 + 强调色边框 + 左侧指示条
        int fill = Draw.lerp(Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hoverT[index]), IOStyle.BLUE_TINT, t);
        int border = Draw.lerp(Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER, hoverT[index]),
                IOStyle.TAB_SEL_BORDER, t);
        Draw.fill(canvas, x, y, w, h - 2.0F, 3.0F, fill);
        Draw.stroke(canvas, x, y, w, h - 2.0F, 3.0F, 1.0F, border);
        if (t > 0.01F) {
            float barH = (h - 14.0F) * t;
            Draw.fill(canvas, x + 2.0F, y + (h - 2.0F - barH) / 2.0F, 2.0F, barH, 1.0F, Draw.alpha(IOStyle.BLUE, t));
        }

        // 物品槽 + 物品
        float slotX = x + 6.0F;
        float slotY = y + 2.0F;
        Draw.fill(canvas, slotX, slotY, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
        Draw.stroke(canvas, slotX, slotY, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
        f.host().items().add(entry.stack, slotX + 1.0F, slotY + 1.0F);

        // 名称（左）与汇率（右，金色）；箭头随展开旋转
        String rate = Component.translatable("gui.module_shop.exchange.rate",
                ClientShopData.money(entry.value)).getString();
        float rateW = Text.width(rate, AtomUi.BOLD, Text.BODY);
        float headerMid = y + 11.0F;
        float nameX = slotX + 24.0F;
        float rateRight = x + w - 18.0F;
        Text.drawLine(canvas, entry.stack.getHoverName().getString(), AtomUi.REGULAR, Text.BODY, IOStyle.TEXT,
                nameX, headerMid, Math.max(10.0F, rateRight - rateW - 8.0F - nameX), Text.Align.LEFT);
        Text.drawLine(canvas, rate, AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                rateRight - rateW, headerMid, rateW + 1.0F, Text.Align.LEFT);
        drawChevron(canvas, x + w - 10.0F, headerMid, t, Draw.lerp(IOStyle.TEXT_3, IOStyle.TEXT_2, hoverT[index]));

        // 展开内容：持有量 / 可取上限 + 操作按钮（随展开淡入）
        if (t > 0.05F && index == ClientShopData.lastExchangeOpen) {
            int layer = Draw.beginOpacity(canvas, Math.min(1.0F, (t - 0.05F) / 0.6F));
            try {
                float infoY = y + 34.0F;
                long affordable = entry.value > 0L ? ClientShopData.balance / entry.value : 0L;
                String owned = Component.translatable("gui.module_shop.exchange.owned", entry.owned).getString();
                String can = Component.translatable("gui.module_shop.exchange.can_withdraw", affordable).getString();
                float ow = Text.drawLine(canvas, owned, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                        x + 8.0F, infoY, 80.0F, Text.Align.LEFT);
                Text.drawLine(canvas, can, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                        x + 8.0F + ow + 10.0F, infoY, 90.0F, Text.Align.LEFT);

                float btnY = y + 28.0F;
                float right = x + w - 6.0F;
                List<Button> buttons = visibleButtons();
                for (int b = buttons.size() - 1; b >= 0; b--) {
                    right -= BTN_W;
                    buttons.get(b).bounds(right, btnY, BTN_W, BTN_H);
                    right -= 4.0F;
                }
                bindButtons(index);
                for (Button b : buttons) {
                    b.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
                }
                rowButtonsShown = true;
            } finally {
                canvas.restoreToCount(layer);
            }
        }
    }

    /** 右侧的折叠箭头：收起朝下（∨），展开朝上（∧）。 */
    private static void drawChevron(Canvas canvas, float cx, float cy, float t, int color) {
        float s = 2.5F;
        float dir = 1.0F - 2.0F * Motion.easeOutCubic(t); // 1 = 朝下，-1 = 朝上
        Draw.line(canvas, cx - s, cy - s * 0.5F * dir, cx, cy + s * 0.5F * dir, 1.2F, color);
        Draw.line(canvas, cx, cy + s * 0.5F * dir, cx + s, cy - s * 0.5F * dir, 1.2F, color);
    }

    private void drawBottomBar(Canvas canvas, PageFrame f) {
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        float hintX = f.x() + 6.0F;
        add.visible(ClientShopData.canAdmin);
        if (ClientShopData.canAdmin) {
            add.bounds(f.x() + 4.0F, barMid - 7.0F, 78.0F, 14.0F)
                    .visible(true);
            add.onClick(() -> host.action("admin_exchange_add", null));
            add.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            hintX = f.x() + 4.0F + 78.0F + 8.0F;
        }
        Text.drawLine(canvas, Component.translatable("gui.module_shop.exchange.hint").getString(), AtomUi.REGULAR,
                Text.SMALL, IOStyle.TEXT_2, hintX, barMid, f.x() + f.w() - 6.0F - hintX, Text.Align.LEFT);
    }

    private List<Button> visibleButtons() {
        return ClientShopData.canAdmin ? List.of(deposit, withdraw, edit, remove) : List.of(deposit, withdraw);
    }

    /** 四个按钮只服务于当前展开的那一行：每帧把它们的动作绑定到该行下标。 */
    private void bindButtons(int index) {
        deposit.onClick(() -> host.action("exchange_deposit_gui", indexTag(index)));
        withdraw.onClick(() -> host.action("exchange_withdraw_gui", indexTag(index)));
        edit.onClick(() -> host.action("exchange_edit", indexTag(index)));
        remove.onClick(() -> host.action("admin_exchange_remove", indexTag(index)));
    }

    private static CompoundTag indexTag(int index) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", index);
        return tag;
    }

    private void ensureCapacity(int n) {
        if (openT.length != n) {
            float[] o = new float[n];
            float[] h = new float[n];
            int open = ClientShopData.lastExchangeOpen;
            for (int i = 0; i < n; i++) {
                // 首次显示（或列表变化）时直接到位，不播放展开动画
                o[i] = i < openT.length ? openT[i] : (i == open ? 1.0F : 0.0F);
                h[i] = i < hoverT.length ? hoverT[i] : 0.0F;
            }
            openT = o;
            hoverT = h;
        }
    }

    // ------------------------------------------------------------------ 输入

    private List<Button> activeButtons() {
        List<Button> list = new ArrayList<>();
        if (rowButtonsShown && ClientShopData.lastExchangeOpen >= 0) {
            list.addAll(visibleButtons());
        }
        if (ClientShopData.canAdmin) {
            list.add(add);
        }
        return list;
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        float listY = f.y() + 2.0F;
        if (button == 0 && scroll.pressBar(mx, my, f.x(), listY, f.w(), f.listBottom() - listY)) {
            return true;
        }
        // 按钮优先（展开行里的按钮位于行内，必须先于“点行头收起”判断）
        for (Button b : activeButtons()) {
            if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        if (button == 0 && my >= listY && my < f.listBottom()) {
            for (float[] hit : rowHits) {
                if (mx >= hit[0] && mx < hit[0] + hit[2] && my >= hit[1] && my < hit[1] + hit[3]) {
                    cn.autoforged.module_shop.client.ClickSound.play();
                    host.action("exchange_open", indexTag((int) hit[4]));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = scroll.releaseBar();
        for (Button b : activeButtons()) {
            handled |= b.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        float listY = f.y() + 2.0F;
        return scroll.dragBar(my, f.listBottom() - listY);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (my < f.listBottom()) {
            scroll.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void cancelInput() {
        scroll.releaseBar();
        for (Button b : List.of(deposit, withdraw, edit, remove, add)) {
            b.cancelPress();
        }
    }
}
