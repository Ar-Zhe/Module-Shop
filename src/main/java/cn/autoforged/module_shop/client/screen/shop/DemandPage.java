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
import cn.autoforged.module_shop.data.DemandEntry;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家求购页（Skija 版）。
 *
 * <ul>
 *     <li>每条求购：想要的物品、清单概要、悬赏（金色）、发布者与剩余时间；点击展开 / 收起（同一时间只展开一条，带动画）；</li>
 *     <li>展开：两列列出每种物品「需要 / 背包里有」与 √ ×；自己的求购显示「撤销」，别人的显示「交付」（数量全够才能点）；</li>
 *     <li>底部：发起求购（demand_create）、只看我的 / 看全部、提示文字。</li>
 * </ul>
 * 过滤与展开状态保存在本页（跨界面重建保留），交付 / 撤销沿用旧动作 demand_fulfill_go / demand_cancel_go。
 */
public final class DemandPage implements ShopPage {
    private static final float HEAD_H = 21.0F;
    private static final float GAP = 3.0F;
    private static final float LINE_H = 16.0F;

    private static boolean onlyMine;
    private static String expandedId;
    private static float savedScroll;

    private final ScrollController scroll = new ScrollController();
    private boolean restored;
    private final Map<String, Float> openT = new HashMap<>();
    private final Map<String, Float> hoverT = new HashMap<>();

    private final Button create = new Button(Button.Kind.ACCENT,
            () -> Component.translatable("gui.module_shop.demand.create").getString(), null).radius(3.0F);
    private final Button filter = new Button(Button.Kind.NORMAL,
            () -> Component.translatable(onlyMine ? "gui.module_shop.demand.filter_all"
                    : "gui.module_shop.demand.filter_mine").getString(), null).radius(3.0F);
    private final Button action = new Button(Button.Kind.ACCENT, "", null).radius(2.0F).fontSize(Text.SMALL);

    private float listX, listY, listW, listH;
    /** 本帧各条的行头区域：{y, id}。 */
    private final List<Object[]> heads = new ArrayList<>();
    private boolean actionShown;

    @Override
    public String id() {
        return "demand";
    }

    private static UUID self() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getUUID();
    }

    private static List<DemandEntry> visible() {
        UUID me = self();
        List<DemandEntry> list = new ArrayList<>();
        for (DemandEntry e : ClientShopData.demands) {
            if (onlyMine && (me == null || !me.equals(e.requester))) {
                continue;
            }
            list.add(e);
        }
        return list;
    }

    private static float detailHeight(DemandEntry e) {
        int rows = (Math.max(1, e.wants.size()) + 1) / 2;
        return 6.0F + rows * LINE_H + 20.0F;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        List<DemandEntry> list = visible();
        listX = f.x();
        listY = f.y() + 2.0F;
        listW = f.w();
        listH = f.listBottom() - listY;
        heads.clear();
        actionShown = false;

        if (!ClientShopData.demandEnabled) {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.demand.disabled").getString(),
                    f.x(), f.y() + 54.0F, f.w());
        } else if (list.isEmpty()) {
            PageKit.emptyText(canvas, Component.translatable(onlyMine ? "gui.module_shop.demand.empty_mine"
                    : "gui.module_shop.demand.empty").getString(), f.x(), f.y() + 54.0F, f.w());
        } else {
            float content = 0.0F;
            for (DemandEntry e : list) {
                float t = Motion.approach(openT.getOrDefault(e.id, 0.0F),
                        e.id != null && e.id.equals(expandedId) ? 1.0F : 0.0F, f.dtMs(), Motion.PRESS_MS);
                openT.put(e.id, t);
                content += HEAD_H + detailHeight(e) * Motion.easeOutCubic(t) + GAP;
            }
            scroll.setContent(content - GAP, listH);
            if (!restored) {
                scroll.scrollTo(savedScroll, false);
                restored = true;
            }
            scroll.update();
            savedScroll = scroll.offset();
            float rowW = listW - 2.0F - (scroll.canScroll() ? 6.0F : 0.0F);
            int saved = canvas.save();
            f.host().items().pushClip(listX, listY, listW, listH);
            try {
                Draw.clip(canvas, listX - 1.0F, listY, listW + 2.0F, listH);
                float y = listY - scroll.offset();
                UUID me = self();
                for (DemandEntry e : list) {
                    float t = Motion.easeOutCubic(openT.getOrDefault(e.id, 0.0F));
                    float detailH = detailHeight(e) * t;
                    if (y + HEAD_H + detailH >= listY && y <= listY + listH) {
                        drawEntry(canvas, f, e, me != null && me.equals(e.requester), listX + 2.0F, y, rowW, t);
                    }
                    y += HEAD_H + detailH + GAP;
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

        // 底部
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        create.onClick(() -> f.host().action("demand_create", null)).bounds(f.x() + 4.0F, barMid - 8.0F, 76.0F, 16.0F);
        filter.onClick(() -> {
            onlyMine = !onlyMine;
            scroll.scrollTo(0.0F, false);
        }).bounds(f.x() + 86.0F, barMid - 8.0F, 76.0F, 16.0F);
        create.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        filter.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        Text.drawLine(canvas, Component.translatable("gui.module_shop.demand.hint").getString(), AtomUi.REGULAR,
                Text.SMALL, IOStyle.TEXT_2, f.x() + 168.0F, barMid, f.w() - 172.0F, Text.Align.LEFT);
    }

    private void drawEntry(Canvas canvas, PageFrame f, DemandEntry e, boolean mine, float x, float y, float w,
                           float t) {
        boolean hover = !scroll.isDragging() && f.mouseX() >= x && f.mouseX() < x + w
                && f.mouseY() >= Math.max(y, listY) && f.mouseY() < Math.min(y + HEAD_H, listY + listH);
        float ht = Motion.approach(hoverT.getOrDefault(e.id, 0.0F), hover ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
        hoverT.put(e.id, ht);
        heads.add(new Object[]{y, e.id});

        // 整条求购是一张卡片：行头 + 展开区在同一个边框里，展开时卡片整体变高
        float cardH = HEAD_H - 1.0F + detailHeight(e) * t;
        int bg = Draw.lerp(Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, ht), IOStyle.BLUE_TINT, t);
        int border = Draw.lerp(Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER, ht), IOStyle.TAB_SEL_BORDER, t);
        Draw.fill(canvas, x, y, w, cardH, 3.0F, bg);
        Draw.stroke(canvas, x, y, w, cardH, 3.0F, 1.0F, border);
        if (t > 0.01F) {
            // 左侧强调色指示条（与货币兑换展开行一致）+ 行头与清单之间的分隔线
            float barH = (cardH - 10.0F) * t;
            Draw.fill(canvas, x + 1.5F, y + (cardH - barH) / 2.0F, 2.0F, barH, 1.0F, Draw.alpha(IOStyle.BLUE, t));
            Draw.fill(canvas, x + 8.0F, y + HEAD_H - 1.0F, w - 16.0F, 1.0F, 0.0F, Draw.alpha(IOStyle.HAIR, t));
        }
        ItemStack want = e.wants.isEmpty() ? ItemStack.EMPTY : e.wants.get(0).display();
        if (!want.isEmpty()) {
            f.host().items().add(want, x + 3.0F, y + 2.0F);
        }
        if (hover) {
            f.host().items().setHovered(want);
        }
        String who = e.requesterName == null || e.requesterName.isBlank() ? "?" : e.requesterName;
        String remain = PageKit.remainText(ClientShopData.demandExpireMode, ClientShopData.demandExpireAmount,
                e.createdGame, e.createdReal);
        String rightText = remain == null ? who : who + "  " + remain;
        float rw = Math.min(w * 0.38F, Text.width(rightText, AtomUi.REGULAR, Text.SMALL));
        float textR = x + w - 14.0F - rw - 6.0F;
        Text.drawLine(canvas, e.summary().getString(), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT,
                x + 23.0F, y + 6.0F, textR - (x + 23.0F), Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.demand.reward", rewardLine(e)).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE, x + 23.0F, y + 14.0F, textR - (x + 23.0F),
                Text.Align.LEFT);
        Text.drawLine(canvas, rightText, AtomUi.REGULAR, Text.SMALL, mine ? IOStyle.ORANGE : IOStyle.TEXT_2,
                x + w - 14.0F - rw, y + HEAD_H / 2.0F, rw, Text.Align.RIGHT);
        // 展开箭头：▸ 收起 → ▾ 展开（旋转）
        float ax = x + w - 7.0F;
        float ay = y + HEAD_H / 2.0F;
        double ang = Math.toRadians(90.0 * t);
        float s = 2.5F;
        float c = (float) Math.cos(ang);
        float sn = (float) Math.sin(ang);
        int arrow = Draw.lerp(IOStyle.TEXT_3, IOStyle.TEXT_2, ht);
        Draw.line(canvas, ax + (-s * 0.5F) * c - (-s) * sn, ay + (-s * 0.5F) * sn + (-s) * c,
                ax + (s * 0.5F) * c, ay + (s * 0.5F) * sn, 1.2F, arrow);
        Draw.line(canvas, ax + (s * 0.5F) * c, ay + (s * 0.5F) * sn,
                ax + (-s * 0.5F) * c - s * sn, ay + (-s * 0.5F) * sn + s * c, 1.2F, arrow);

        if (t > 0.01F) {
            drawDetail(canvas, f, e, mine, x, y + HEAD_H, w, t);
        }
    }

    private void drawDetail(Canvas canvas, PageFrame f, DemandEntry e, boolean mine, float x, float y, float w,
                            float t) {
        // 清单画在卡片内部（不再单独一块框），按展开进度裁剪，并随展开淡入
        float h = detailHeight(e) * t;
        int saved = canvas.save();
        f.host().items().pushClip(x + 1.0F, y, w - 2.0F, Math.max(0.0F, h - 1.0F));
        try {
            Draw.clip(canvas, x + 1.0F, y, w - 2.0F, Math.max(0.0F, h - 1.0F));
            Draw.beginOpacity(canvas, Math.min(1.0F, t / 0.7F));
            float fullH = detailHeight(e);
            float colW = (w - 8.0F) / 2.0F;
            boolean allEnough = true;
            for (int i = 0; i < e.wants.size(); i++) {
                DemandEntry.WantLine line = e.wants.get(i);
                ItemStack template = line.stack.copy();
                template.setCount(1);
                int have = countInBag(template);
                boolean enough = have >= line.count;
                allEnough &= enough;
                String status = Component.translatable("gui.module_shop.demand.need_have", line.count, have)
                        .getString() + " " + (enough ? "√" : "×");
                if (e.wants.size() == 1) {
                    // 只求购一种物品：行头已经写了物品名与数量，这里只补“需要 / 拥有”，与行头文字左对齐
                    Text.drawLine(canvas, status, AtomUi.REGULAR, Text.SMALL, enough ? IOStyle.GREEN : IOStyle.RED,
                            x + 23.0F, y + 4.0F + LINE_H / 2.0F, w - 23.0F - 80.0F, Text.Align.LEFT);
                    continue;
                }
                float cx = x + 6.0F + (i % 2) * colW;
                float cy = y + 4.0F + (i / 2) * LINE_H;
                f.host().items().add(template, cx, cy, 16.0F, false);
                if (f.mouseX() >= cx && f.mouseX() < cx + colW - 4.0F && f.mouseY() >= cy && f.mouseY() < cy + LINE_H
                        && f.mouseY() < y + h) {
                    f.host().items().setHovered(line.stack); // 悬停看原版说明（含数量）
                }
                String tail = status;
                float tw = Text.width(tail, AtomUi.REGULAR, Text.SMALL);
                Text.drawLine(canvas, tail, AtomUi.REGULAR, Text.SMALL, enough ? IOStyle.GREEN : IOStyle.RED,
                        cx + colW - 6.0F - tw, cy + 8.0F, tw + 1.0F, Text.Align.LEFT);
                Text.drawLine(canvas, PageKit.plainName(line.stack), AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT,
                        cx + 19.0F, cy + 8.0F, Math.max(10.0F, colW - 6.0F - tw - 4.0F - 19.0F), Text.Align.LEFT);
            }
            // 右下角：自己的 → 撤销；别人的 → 交付（数量不够时禁用）
            if (e.id != null && e.id.equals(expandedId)) {
                CompoundTag tag = new CompoundTag();
                tag.putString("id", e.id);
                action.label(Component.translatable(mine ? "gui.module_shop.demand.cancel_action"
                                : "gui.module_shop.demand.fulfill_action").getString())
                        .kind(mine ? Button.Kind.DANGER : Button.Kind.ACCENT)
                        .onClick(() -> f.host().action(mine ? "demand_cancel_go" : "demand_fulfill_go", tag))
                        .bounds(x + w - 8.0F - 60.0F, y + fullH - 18.0F, 60.0F, 14.0F);
                action.enabled(mine || allEnough);
                boolean inView = y + fullH - 18.0F >= listY && y + fullH - 4.0F <= listY + listH && t > 0.95F;
                action.visible(inView);
                if (inView) {
                    action.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
                    actionShown = true;
                }
            }
        } finally {
            f.host().items().popClip();
            canvas.restoreToCount(saved);
        }
    }

    /** 悬赏内容的一行文字（金币 + 物品）。 */
    static String rewardLine(DemandEntry e) {
        String money = e.rewardMoney > 0L ? ClientShopData.money(e.rewardMoney) : null;
        String item = e.rewardItem != null && !e.rewardItem.isEmpty()
                ? e.rewardItem.getHoverName().getString() + " x" + e.rewardItem.getCount() : null;
        if (money != null && item != null) {
            return money + " + " + item;
        }
        return money != null ? money : (item != null ? item : "-");
    }

    /** 背包里有多少个「同物品同组件」的堆（与服务器清点方式一致）。 */
    private static int countInBag(ItemStack template) {
        var player = Minecraft.getInstance().player;
        if (player == null || template.isEmpty()) {
            return 0;
        }
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, template)) {
                total += s.getCount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button == 0 && scroll.pressBar(mx, my, listX, listY, listW, listH)) {
            return true;
        }
        if (create.mouseClicked(mx, my, button) || filter.mouseClicked(mx, my, button)) {
            return true;
        }
        if (actionShown && action.isVisible() && action.mouseClicked(mx, my, button)) {
            return true;
        }
        // 必须同时判断 x：只判断 y 时，点左侧页签（同一高度）会被误当成点了这一行
        if (button == 0 && mx >= listX && mx < listX + listW - 6.0F && my >= listY && my < listY + listH) {
            for (Object[] head : heads) {
                float hy = (float) head[0];
                if (my >= hy && my < hy + HEAD_H) {
                    String id = (String) head[1];
                    ClickSound.play();
                    expandedId = id != null && id.equals(expandedId) ? null : id;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return scroll.releaseBar() | create.mouseReleased(mx, my, button) | filter.mouseReleased(mx, my, button)
                | action.mouseReleased(mx, my, button);
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
        create.cancelPress();
        filter.cancelPress();
        action.cancelPress();
    }
}
