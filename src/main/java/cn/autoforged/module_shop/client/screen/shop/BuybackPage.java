package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.data.DailyBuybackEntry;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 每日收购页（Skija 版）。
 *
 * <ul>
 *     <li>一行一个收购项：左侧品质色条 + 物品 + 名称，右侧收购价（金色）与还能收购的数量（卖完变红）；
 *     整行悬停显示物品 tooltip，点击打开数量输入（sell_buyback_gui）；平滑滚动；</li>
 *     <li>底部操作条：全清奖励进度（左）、管理员「全清奖励」开关与「设置」、付费「刷新收购」（悬停显示次数与价格）。</li>
 * </ul>
 */
public final class BuybackPage implements ShopPage {
    private static final float ROW_H = 26.0F;
    private static final float CARD_H = 22.0F;

    private static float savedScroll;

    private final ScrollController scroll = new ScrollController();
    private boolean restored;
    private float[] hoverT = new float[0];
    private float toggleT = -1.0F;

    private final Button refresh = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.refresh_buyback").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button bonusSet = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.buyback_bonus_set").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);

    // 本帧几何
    private float listX, listY, listW, listH;
    private float toggleX, toggleY;

    @Override
    public String id() {
        return "buyback";
    }

    @Override
    public void render(Canvas canvas, PageFrame f) {
        List<DailyBuybackEntry> list = ClientShopData.dailyBuyback;
        listX = f.x();
        listY = f.y() + 4.0F;
        listW = f.w();
        listH = f.listBottom() - listY;

        if (list.isEmpty()) {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.empty").getString(),
                    f.x(), f.y() + 44.0F, f.w());
        } else {
            if (hoverT.length != list.size()) {
                hoverT = new float[list.size()];
            }
            scroll.setContent(list.size() * ROW_H - (ROW_H - CARD_H), listH);
            if (!restored) {
                scroll.scrollTo(savedScroll, false);
                restored = true;
            }
            scroll.update();
            savedScroll = scroll.offset();

            float rowW = listW - 2.0F - (scroll.canScroll() ? 6.0F : 0.0F);
            boolean inList = f.mouseX() >= listX && f.mouseX() < listX + rowW
                    && f.mouseY() >= listY && f.mouseY() < listY + listH;
            int saved = canvas.save();
            f.host().items().pushClip(listX, listY, listW, listH);
            try {
                Draw.clip(canvas, listX - 1.0F, listY, listW + 2.0F, listH);
                float off = scroll.offset();
                for (int i = Math.max(0, (int) (off / ROW_H)); i < list.size(); i++) {
                    float y = listY + i * ROW_H - off;
                    if (y > listY + listH) {
                        break;
                    }
                    DailyBuybackEntry e = list.get(i);
                    boolean hover = inList && !scroll.isDragging() && f.mouseY() >= y && f.mouseY() < y + CARD_H;
                    hoverT[i] = Motion.approach(hoverT[i], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
                    float x = listX + 2.0F;
                    PageKit.hoverCard(canvas, x, y, rowW, CARD_H, hoverT[i]);
                    // 品质色条
                    Draw.fill(canvas, x + 3.0F, y + 4.0F, 2.5F, CARD_H - 8.0F, 1.0F, IOStyle.rarity(e.rarity));
                    PageKit.slot(canvas, f.host(), e.stack, x + 9.0F, y + 2.0F);
                    if (hover) {
                        f.host().items().setHovered(e.stack); // 整行悬停都显示该物品的 tooltip
                    }
                    // 右侧：收购价 / 还能收购
                    String price = Component.translatable("gui.module_shop.buyback_price",
                            ClientShopData.money(e.price)).getString();
                    int remaining = e.remaining();
                    String remain = Component.translatable("gui.module_shop.buyback_remaining", remaining).getString();
                    float rightW = Math.max(Text.width(price, AtomUi.BOLD, Text.BODY),
                            Text.width(remain, AtomUi.REGULAR, Text.SMALL));
                    float rightX = x + rowW - 8.0F - rightW;
                    Text.drawLine(canvas, price, AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                            rightX, y + 7.5F, rightW, Text.Align.RIGHT);
                    Text.drawLine(canvas, remain, AtomUi.REGULAR, Text.SMALL,
                            remaining > 0 ? IOStyle.TEXT_2 : IOStyle.RED, rightX, y + 16.0F, rightW, Text.Align.RIGHT);
                    // 中间：物品名（超宽省略，不压到右侧）
                    Text.drawLine(canvas, PageKit.plainName(e.stack), AtomUi.REGULAR, Text.BODY, IOStyle.TEXT,
                            x + 32.0F, y + CARD_H / 2.0F, Math.max(20.0F, rightX - 8.0F - (x + 32.0F)), Text.Align.LEFT);
                }
            } finally {
                f.host().items().popClip();
                canvas.restoreToCount(saved);
            }
            scroll.drawBar(canvas, listX, listY, listW, listH, f.mouseX(), f.mouseY(), f.dtMs());
            if (scroll.hasMoreBelow()) {
                PageKit.fadeBottom(canvas, listX, listY + listH, rowW + 2.0F, IOStyle.CONTENT);
            }
        }
        drawBottomBar(canvas, f);
    }

    private void drawBottomBar(Canvas canvas, PageFrame f) {
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        // 右侧：刷新收购（可刷新时用强调色）
        boolean canRefresh = ClientShopData.buybackRefreshCost >= 0L;
        float refreshX = f.x() + f.w() - 4.0F - 58.0F;
        refresh.kind(canRefresh ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> f.host().action("refresh_daily_buyback", null))
                .bounds(refreshX, barMid - 7.0F, 58.0F, 14.0F);
        refresh.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        if (refresh.isMouseOver(f.mouseX(), f.mouseY())) {
            int left = Math.max(0, ClientShopData.buybackRefreshMax - ClientShopData.buybackRefreshUsed);
            f.host().textTooltip(canRefresh
                    ? Component.translatable("gui.module_shop.refresh_buyback_tooltip",
                            ClientShopData.money(ClientShopData.buybackRefreshCost), left,
                            ClientShopData.buybackRefreshMax)
                    : Component.translatable("gui.module_shop.refresh_buyback_tooltip_none",
                            ClientShopData.buybackRefreshMax));
        }

        // 管理员：全清奖励开关 + 设置
        float leftLimit = refreshX;
        bonusSet.visible(ClientShopData.canAdmin);
        if (ClientShopData.canAdmin) {
            float setX = refreshX - 8.0F - 34.0F;
            bonusSet.onClick(() -> f.host().action("buyback_bonus_set", null))
                    .bounds(setX, barMid - 7.0F, 34.0F, 14.0F);
            bonusSet.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            toggleX = setX - 8.0F - 24.0F;
            toggleY = barMid - 6.0F;
            drawToggle(canvas, f, toggleX, toggleY, ClientShopData.buybackBonusEnabled);
            leftLimit = toggleX;
        }

        // 左侧：全清奖励进度
        if (ClientShopData.buybackBonusEnabled && ClientShopData.buybackBonus > 0L) {
            String line = ClientShopData.buybackBonusDone
                    ? Component.translatable("gui.module_shop.buyback_bonus_done",
                            ClientShopData.money(ClientShopData.buybackBonus)).getString()
                    : Component.translatable("gui.module_shop.buyback_bonus_progress",
                            ClientShopData.money(ClientShopData.buybackBonus), ClientShopData.buybackLeft).getString();
            Text.drawLine(canvas, line, AtomUi.REGULAR, Text.SMALL,
                    ClientShopData.buybackBonusDone ? IOStyle.TEXT_2 : IOStyle.ORANGE,
                    f.x() + 4.0F, barMid, leftLimit - 8.0F - (f.x() + 4.0F), Text.Align.LEFT);
        }
    }

    /** 小开关（24×12）：开 = 绿色、滑块在右；切换时滑块平滑移动。 */
    private void drawToggle(Canvas canvas, PageFrame f, float x, float y, boolean on) {
        toggleT = toggleT < 0.0F ? (on ? 1.0F : 0.0F) : Motion.approach(toggleT, on ? 1.0F : 0.0F, f.dtMs(), 50.0F);
        Draw.fill(canvas, x, y, 24.0F, 12.0F, 6.0F, Draw.lerp(IOStyle.TOGGLE_OFF, IOStyle.GREEN, toggleT));
        if (toggleT < 0.99F) {
            Draw.stroke(canvas, x, y, 24.0F, 12.0F, 6.0F, 1.0F, Draw.alpha(IOStyle.TOGGLE_OFF_RING, 1.0F - toggleT));
        }
        float knob = 8.0F;
        Draw.fill(canvas, x + 2.0F + (24.0F - 4.0F - knob) * toggleT, y + 2.0F, knob, knob, knob / 2.0F, IOStyle.KNOB);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button == 0 && scroll.pressBar(mx, my, listX, listY, listW, listH)) {
            return true;
        }
        if (refresh.mouseClicked(mx, my, button) || (bonusSet.isVisible() && bonusSet.mouseClicked(mx, my, button))) {
            return true;
        }
        if (button == 0 && ClientShopData.canAdmin && mx >= toggleX && mx < toggleX + 24.0F
                && my >= toggleY && my < toggleY + 12.0F) {
            ClickSound.play();
            f.host().action("admin_buyback_bonus_toggle", null);
            return true;
        }
        if (button == 0 && mx >= listX && mx < listX + listW - 6.0F && my >= listY && my < listY + listH) {
            float local = (float) (my - listY + scroll.offset());
            int index = (int) (local / ROW_H);
            if (index >= 0 && index < ClientShopData.dailyBuyback.size() && local - index * ROW_H < CARD_H) {
                ClickSound.play();
                CompoundTag tag = new CompoundTag();
                tag.putInt("index", index);
                f.host().action("sell_buyback_gui", tag);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return scroll.releaseBar() | refresh.mouseReleased(mx, my, button) | bonusSet.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return scroll.dragBar(my, listH);
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
        refresh.cancelPress();
        bonusSet.cancelPress();
    }
}
