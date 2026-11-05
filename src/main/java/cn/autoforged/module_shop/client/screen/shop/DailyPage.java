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
import cn.autoforged.module_shop.data.DailyShopEntry;
import io.github.humbleui.skija.Canvas;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 每日商店页（Skija 版）。
 *
 * <ul>
 *     <li>不超过 6 件：2 行 × 3 列卡牌，背面按品质着色，点击翻牌（以牌的左右中点为轴横向翻转，
 *     半程换面 + 转向压暗 + 翻面扫光）；已翻开的牌点击打开购买数量输入；</li>
 *     <li>超过 6 件：每行一个横向长条，同样的翻牌动画，平滑滚动，底栏显示「当前序号 / 总数」；</li>
 *     <li>底栏右侧：付费「刷新商店」（悬停显示本次花费与今日剩余次数，确认框由 ShopScreen 弹出）。</li>
 * </ul>
 *
 * <p>翻开状态以服务端为准（{@link ClientShopData#dailyFlipped}）；本页只负责动画。
 * ShopScreen 每次服务端操作后会重建，所以动画状态放在静态字段里，跨重建继续播放。
 */
public final class DailyPage implements ShopPage {
    private static final float CARD_W = 84.0F;
    private static final float CARD_H = 70.0F;
    private static final float GAP_X = 8.0F;
    private static final float GAP_Y = 10.0F;
    private static final int COLS = 3;
    private static final int GRID_MAX = 6;

    private static final float ROW_H = 34.0F;
    private static final float BAR_CARD_H = 30.0F;

    /** 一次翻牌的时长（毫秒），与旧界面 10 tick 一致。 */
    private static final float FLIP_MS = 500.0F;

    /** 翻牌动画：索引 → 起始时刻（纳秒）与点击瞬间的旧面（true = 正面）。 */
    private record FlipAnim(long startNanos, boolean from) {
    }

    private static final Map<Integer, FlipAnim> FLIPS = new HashMap<>();
    private static float savedScroll;

    /** 一帧的翻牌状态：横向缩放、这一帧画哪一面、进度 0..1（扫光用）。 */
    private record Flip(float scale, boolean front, float progress) {
    }

    private final ScrollController scroll = new ScrollController();
    private boolean restored;
    private float[] hoverT = new float[0];

    private final Button refresh = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.refresh_daily").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);

    // 本帧几何（输入命中用）
    private boolean listMode;
    private float gridX, gridY;
    private float listX, listY, listW, listH;
    private float rowW;

    @Override
    public String id() {
        return "daily";
    }

    // ------------------------------------------------------------------ 翻牌动画

    private static Flip flipState(int index, boolean serverFlipped) {
        FlipAnim anim = FLIPS.get(index);
        if (anim == null) {
            return new Flip(1.0F, serverFlipped, 1.0F);
        }
        float raw = (System.nanoTime() - anim.startNanos) / 1_000_000.0F / FLIP_MS;
        if (raw >= 1.0F || raw < 0.0F) {
            FLIPS.remove(index);
            return new Flip(1.0F, serverFlipped, 1.0F);
        }
        float eased = raw * raw * (3.0F - 2.0F * raw);
        float scale = Math.max(0.04F, Math.abs((float) Math.cos(eased * Math.PI)));
        // 前半段画旧面、后半段画新面（点击瞬间记录的方向，服务端快照晚到也不会翻回去）
        return new Flip(scale, raw < 0.5F ? anim.from : !anim.from, raw);
    }

    /** 透视压缩：牌越接近侧面，纵向也稍稍收窄一点。 */
    private static float squash(float scale) {
        return 1.0F - 0.10F * (1.0F - scale);
    }

    /** 转向时压暗的不透明度（0..~0.47）。 */
    private static float shade(float scale) {
        return 0.40F * (1.0F - scale);
    }

    /**
     * 开始翻牌变换：以 (hingeX, hingeY) 为中心横向缩放 scale、纵向缩放 squash。
     * 返回 save 计数，用 {@code canvas.restoreToCount} 结束。
     */
    private static int beginFlip(Canvas canvas, Flip flip, float hingeX, float hingeY) {
        int saved = canvas.save();
        if (flip.scale < 0.999F) {
            canvas.translate(hingeX, hingeY);
            canvas.scale(flip.scale, squash(flip.scale));
            canvas.translate(-hingeX, -hingeY);
        }
        return saved;
    }

    /** 翻面后半段扫过的一道光：从左扫到右并逐渐变淡（在翻牌变换内绘制）。 */
    private static void sheen(Canvas canvas, float x, float y, float w, float h, float progress) {
        if (progress < 0.5F || progress >= 1.0F) {
            return;
        }
        float t = (progress - 0.5F) * 2.0F;
        float peak = (1.0F - t) * 0.30F;
        if (peak <= 0.02F) {
            return;
        }
        float band = Math.max(16.0F, w / 6.0F);
        float start = x - band + (w + band * 2.0F) * t;
        int saved = canvas.save();
        try {
            Draw.clip(canvas, x, y + 3.0F, w, h - 6.0F);
            int steps = (int) band;
            float half = band / 2.0F;
            for (int i = 0; i < steps; i++) {
                float k = 1.0F - Math.abs((i - half) / Math.max(1.0F, half));
                float a = peak * k * k;
                if (a > 0.012F) {
                    Draw.fill(canvas, start + i, y + 3.0F, 1.0F, h - 6.0F, 0.0F, Draw.alpha(0xFFFFFFFF, a));
                }
            }
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    /** 物品图标随翻牌变换：返回变换后的左上角，并按横向缩放登记到物品层。 */
    private static void flippedItem(PageFrame f, DailyShopEntry e, Flip flip, float itemX, float itemY,
            float hingeX, float hingeY) {
        float sy = squash(flip.scale);
        float sx = flip.scale;
        float x = hingeX + (itemX - hingeX) * sx;
        float y = hingeY + (itemY - hingeY) * sy;
        f.host().items().add(e.stack, x, y, 16.0F * sy, sx / sy, true);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        List<DailyShopEntry> list = ClientShopData.dailyShop;
        int count = list.size();
        if (hoverT.length != count) {
            hoverT = new float[count];
        }
        listMode = count > GRID_MAX;
        if (count == 0) {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.empty").getString(),
                    f.x(), f.y() + 44.0F, f.w());
        } else if (listMode) {
            renderList(canvas, f, list);
        } else {
            renderGrid(canvas, f, list);
        }
        drawBottomBar(canvas, f, count);
    }

    private void renderGrid(Canvas canvas, PageFrame f, List<DailyShopEntry> list) {
        int count = list.size();
        int rows = (count + COLS - 1) / COLS;
        float totalW = COLS * CARD_W + (COLS - 1) * GAP_X;
        float totalH = rows * CARD_H + (rows - 1) * GAP_Y;
        gridX = f.x() + Math.max(0.0F, (f.w() - totalW) / 2.0F);
        // 底部留出操作条，卡牌整体在「内容区 - 底栏」里居中，绝不会压到按钮
        gridY = f.y() + Math.max(0.0F, (f.h() - PageFrame.BAR_H - totalH) / 2.0F);
        for (int i = 0; i < count; i++) {
            DailyShopEntry e = list.get(i);
            float x = gridX + (i % COLS) * (CARD_W + GAP_X);
            float y = gridY + (i / COLS) * (CARD_H + GAP_Y);
            boolean flipped = ClientShopData.dailyFlipped.contains(i);
            boolean hover = f.mouseX() >= x && f.mouseX() < x + CARD_W && f.mouseY() >= y && f.mouseY() < y + CARD_H;
            hoverT[i] = Motion.approach(hoverT[i], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
            Flip flip = flipState(i, flipped);
            float hx = x + CARD_W / 2.0F;
            float hy = y + CARD_H / 2.0F;
            int saved = beginFlip(canvas, flip, hx, hy);
            try {
                int rarity = IOStyle.rarity(e.rarity);
                if (flip.front) {
                    PageKit.hoverCard(canvas, x, y, CARD_W, CARD_H, hoverT[i]);
                    Draw.fill(canvas, x + 5.0F, y + 4.0F, 3.0F, 3.0F, 1.5F, rarity);
                    Draw.fill(canvas, x + (CARD_W - 18.0F) / 2.0F, y + 5.0F, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
                    Draw.stroke(canvas, x + (CARD_W - 18.0F) / 2.0F, y + 5.0F, 18.0F, 18.0F, 2.0F, 1.0F,
                            IOStyle.SLOT_BORDER);
                    flippedItem(f, e, flip, x + (CARD_W - 16.0F) / 2.0F, y + 6.0F, hx, hy);
                    Text.drawLine(canvas, ClientShopData.money(e.price), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                            x + 2.0F, y + 29.0F, CARD_W - 4.0F, Text.Align.CENTER);
                    int remaining = e.remaining();
                    Text.drawLine(canvas, Component.translatable("gui.module_shop.remaining", remaining).getString(),
                            AtomUi.REGULAR, Text.SMALL, remaining > 0 ? IOStyle.TEXT_2 : IOStyle.RED,
                            x + 2.0F, y + 38.5F, CARD_W - 4.0F, Text.Align.CENTER);
                    Text.drawLine(canvas, Component.translatable(e.rarity.translationKey()).getString(),
                            AtomUi.REGULAR, Text.SMALL, rarity, x + 2.0F, y + 46.5F, CARD_W - 4.0F,
                            Text.Align.CENTER);
                    // 底部强调色标题条（物品名）
                    Draw.fill(canvas, x + 3.0F, y + CARD_H - 15.0F, CARD_W - 6.0F, 12.0F, 2.0F, IOStyle.BLUE);
                    Text.drawLine(canvas, PageKit.plainName(e.stack), AtomUi.REGULAR, Text.SMALL, 0xFFFFFFFF,
                            x + 6.0F, y + CARD_H - 9.0F, CARD_W - 12.0F, Text.Align.CENTER);
                } else {
                    // 牌背面用品质配色：不用翻开就能看出品质
                    Draw.fill(canvas, x, y, CARD_W, CARD_H, 3.0F, IOStyle.CARD);
                    Draw.fill(canvas, x + 2.0F, y + 2.0F, CARD_W - 4.0F, CARD_H - 4.0F, 2.0F,
                            IOStyle.rarityTint(e.rarity));
                    Draw.stroke(canvas, x, y, CARD_W, CARD_H, 3.0F, 1.0F,
                            Draw.alpha(rarity, 0.40F + 0.35F * hoverT[i]));
                    Text.drawLine(canvas, "?", AtomUi.BOLD, Text.TITLE * 1.6F, rarity,
                            x, y + 28.0F, CARD_W, Text.Align.CENTER);
                    Text.drawLine(canvas, Component.translatable(e.rarity.translationKey()).getString(),
                            AtomUi.REGULAR, Text.SMALL, rarity, x + 2.0F, y + 56.0F, CARD_W - 4.0F,
                            Text.Align.CENTER);
                }
                float dark = shade(flip.scale);
                if (dark > 0.02F) {
                    Draw.fill(canvas, x, y, CARD_W, CARD_H, 3.0F, Draw.alpha(0xFF000000, dark));
                }
                sheen(canvas, x, y, CARD_W, CARD_H, flip.progress);
            } finally {
                canvas.restoreToCount(saved);
            }
            if (hover && flipped && flip.front) {
                f.host().items().setHovered(e.stack); // 整张牌悬停都显示物品 tooltip / JEI
            }
        }
    }

    private void renderList(Canvas canvas, PageFrame f, List<DailyShopEntry> list) {
        int count = list.size();
        listX = f.x();
        listY = f.y() + 4.0F;
        listW = f.w();
        listH = f.listBottom() - listY;
        scroll.setContent(count * ROW_H - (ROW_H - BAR_CARD_H), listH);
        if (!restored) {
            scroll.scrollTo(savedScroll, false);
            restored = true;
        }
        scroll.update();
        savedScroll = scroll.offset();

        rowW = listW - 2.0F - (scroll.canScroll() ? 6.0F : 0.0F);
        boolean inList = f.mouseX() >= listX + 2.0F && f.mouseX() < listX + 2.0F + rowW
                && f.mouseY() >= listY && f.mouseY() < listY + listH;
        int saved = canvas.save();
        f.host().items().pushClip(listX, listY, listW, listH);
        try {
            Draw.clip(canvas, listX - 1.0F, listY, listW + 2.0F, listH);
            float off = scroll.offset();
            for (int i = Math.max(0, (int) (off / ROW_H)); i < count; i++) {
                float y = listY + i * ROW_H - off;
                if (y > listY + listH) {
                    break;
                }
                drawBar(canvas, f, list.get(i), i, listX + 2.0F, y, rowW,
                        inList && !scroll.isDragging() && f.mouseY() >= y && f.mouseY() < y + BAR_CARD_H);
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

    /** 一条横向长条：品质色条 + 图标 + 名称 / 品质，右侧价格 / 剩余。 */
    private void drawBar(Canvas canvas, PageFrame f, DailyShopEntry e, int index, float x, float y, float w,
            boolean hover) {
        boolean flipped = ClientShopData.dailyFlipped.contains(index);
        hoverT[index] = Motion.approach(hoverT[index], hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
        Flip flip = flipState(index, flipped);
        float hx = x + w / 2.0F;
        float hy = y + BAR_CARD_H / 2.0F;
        int rarity = IOStyle.rarity(e.rarity);
        int saved = beginFlip(canvas, flip, hx, hy);
        try {
            if (flip.front) {
                PageKit.hoverCard(canvas, x, y, w, BAR_CARD_H, hoverT[index]);
                Draw.fill(canvas, x + 4.0F, y + 5.0F, 2.5F, BAR_CARD_H - 10.0F, 1.0F, rarity);
                Draw.fill(canvas, x + 11.0F, y + 6.0F, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
                Draw.stroke(canvas, x + 11.0F, y + 6.0F, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
                flippedItem(f, e, flip, x + 12.0F, y + 7.0F, hx, hy);
                float rightW = Math.min(120.0F, Math.max(80.0F, w / 3.0F));
                float priceX = x + w - rightW;
                float nameX = x + 36.0F;
                float nameW = Math.max(20.0F, priceX - 6.0F - nameX);
                Text.drawLine(canvas, PageKit.plainName(e.stack), AtomUi.REGULAR, Text.BODY, IOStyle.TEXT,
                        nameX, y + 10.0F, nameW, Text.Align.LEFT);
                Text.drawLine(canvas, Component.translatable(e.rarity.translationKey()).getString(), AtomUi.REGULAR,
                        Text.SMALL, rarity, nameX, y + 21.0F, nameW, Text.Align.LEFT);
                Text.drawLine(canvas, ClientShopData.money(e.price), AtomUi.BOLD, Text.BODY, IOStyle.ORANGE,
                        priceX, y + 10.0F, rightW - 8.0F, Text.Align.LEFT);
                int remaining = e.remaining();
                Text.drawLine(canvas, Component.translatable("gui.module_shop.remaining", remaining).getString(),
                        AtomUi.REGULAR, Text.SMALL, remaining > 0 ? IOStyle.TEXT_2 : IOStyle.RED,
                        priceX, y + 21.0F, rightW - 8.0F, Text.Align.LEFT);
            } else {
                Draw.fill(canvas, x, y, w, BAR_CARD_H, 3.0F, IOStyle.CARD);
                Draw.fill(canvas, x + 2.0F, y + 2.0F, w - 4.0F, BAR_CARD_H - 4.0F, 2.0F, IOStyle.rarityTint(e.rarity));
                Draw.stroke(canvas, x, y, w, BAR_CARD_H, 3.0F, 1.0F,
                        Draw.alpha(rarity, 0.40F + 0.35F * hoverT[index]));
                Text.drawLine(canvas, "?", AtomUi.BOLD, Text.TITLE, rarity, x + 12.0F, y + BAR_CARD_H / 2.0F,
                        14.0F, Text.Align.CENTER);
                Text.drawLine(canvas, Component.translatable(e.rarity.translationKey()).getString(), AtomUi.REGULAR,
                        Text.BODY, rarity, x + 32.0F, y + BAR_CARD_H / 2.0F, w - 40.0F, Text.Align.LEFT);
            }
            float dark = shade(flip.scale);
            if (dark > 0.02F) {
                Draw.fill(canvas, x, y, w, BAR_CARD_H, 3.0F, Draw.alpha(0xFF000000, dark));
            }
            sheen(canvas, x, y, w, BAR_CARD_H, flip.progress);
        } finally {
            canvas.restoreToCount(saved);
        }
        if (hover && flipped && flip.front) {
            f.host().items().setHovered(e.stack);
        }
    }

    private void drawBottomBar(Canvas canvas, PageFrame f, int count) {
        // 与旧版一致：商品为空时不显示「刷新商店」
        refresh.visible(count > 0);
        if (count == 0) {
            return;
        }
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        boolean canRefresh = ClientShopData.refreshCost >= 0L;
        float refreshX = f.x() + f.w() - 4.0F - 58.0F;
        refresh.kind(canRefresh ? Button.Kind.ACCENT : Button.Kind.NORMAL)
                .onClick(() -> f.host().action("refresh_daily_shop", null))
                .bounds(refreshX, barMid - 7.0F, 58.0F, 14.0F);
        refresh.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        if (refresh.isMouseOver(f.mouseX(), f.mouseY())) {
            int left = Math.max(0, ClientShopData.refreshMax - ClientShopData.refreshUsed);
            f.host().textTooltip(canRefresh
                    ? Component.translatable("gui.module_shop.refresh_tooltip",
                            ClientShopData.money(ClientShopData.refreshCost), left, ClientShopData.refreshMax)
                    : Component.translatable("gui.module_shop.refresh_tooltip_none", ClientShopData.refreshMax));
        }
        // 长条模式：刷新按钮左侧显示「当前序号 / 总数」（最后一条完整可见的行）
        if (listMode && count > 0) {
            int shown = Math.max(1, Math.min(count, (int) ((scroll.offset() + listH + (ROW_H - BAR_CARD_H)) / ROW_H)));
            String pos = Component.translatable("gui.module_shop.daily_pos", shown, count).getString();
            Text.drawLine(canvas, pos, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2,
                    f.x() + 4.0F, barMid, refreshX - 8.0F - (f.x() + 4.0F), Text.Align.RIGHT);
        }
    }

    // ------------------------------------------------------------------ 输入

    /** 点击一张牌：未翻开 → 记录翻牌动画并通知服务端；已翻开 → 打开购买数量输入。 */
    private void clickCard(PageFrame f, int index) {
        ClickSound.play();
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", index);
        if (ClientShopData.dailyFlipped.contains(index)) {
            f.host().action("buy_shop", extra);
        } else {
            if (!FLIPS.containsKey(index)) {
                FLIPS.put(index, new FlipAnim(System.nanoTime(), false));
            }
            f.host().action("flip_shop", extra);
        }
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (refresh.isVisible() && refresh.mouseClicked(mx, my, button)) {
            return true;
        }
        int count = ClientShopData.dailyShop.size();
        if (button != 0 || count == 0) {
            return false;
        }
        if (listMode) {
            if (scroll.pressBar(mx, my, listX, listY, listW, listH)) {
                return true;
            }
            if (mx >= listX + 2.0F && mx < listX + 2.0F + rowW && my >= listY && my < listY + listH) {
                float local = (float) (my - listY + scroll.offset());
                int index = (int) (local / ROW_H);
                if (index >= 0 && index < count && local - index * ROW_H < BAR_CARD_H) {
                    clickCard(f, index);
                    return true;
                }
            }
            return false;
        }
        for (int i = 0; i < count; i++) {
            float x = gridX + (i % COLS) * (CARD_W + GAP_X);
            float y = gridY + (i / COLS) * (CARD_H + GAP_Y);
            if (mx >= x && mx < x + CARD_W && my >= y && my < y + CARD_H) {
                clickCard(f, i);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return scroll.releaseBar() | refresh.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return listMode && scroll.dragBar(my, listH);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (listMode && my < f.listBottom()) {
            scroll.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void cancelInput() {
        scroll.releaseBar();
        refresh.cancelPress();
    }
}
