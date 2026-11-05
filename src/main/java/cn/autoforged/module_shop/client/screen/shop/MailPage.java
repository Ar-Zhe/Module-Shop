package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.StyledText;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.data.MailEntry;
import cn.autoforged.module_shop.data.MailType;
import com.atom.lib.design.atomchat.SkiaDraw;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 邮箱页（Skija 版）：左侧邮件列表，右侧邮件详情。
 *
 * <ul>
 *     <li>列表：星标置顶、按时间倒序；未读邮件带业务类型色点与标题色；左键选中、右键加/去星标；
 *     平滑滚动（替代旧版翻页）。右上角「兑换码」入口；</li>
 *     <li>详情：标题（支持 & 颜色代码）+ 星标按钮、发件人、时间、正文（中文逐字换行，过长可滚动）、
 *     附件格子（两行可见，多于两行可滚动）或金额、到期提示；底部「删除」「领取/查看」；</li>
 *     <li>底部操作条：全部领取、删除已读。</li>
 * </ul>
 * 动作沿用旧界面：mail_select / mail_star / claim_mail / view_mail / mail_delete（未领取会先二次确认）/
 * open_redeem / claim_all_mail / delete_read_mail。
 */
public final class MailPage implements ShopPage {
    private static final float LIST_W = 140.0F;
    private static final float GAP = 6.0F;
    private static final float HEADER_H = 18.0F;
    /** 行高：标题 + 发件人两行小字，再留出上下边距（21 时发件人会压到选中框下边框）。 */
    private static final float ROW_H = 24.0F;
    private static final float ATT_H = 46.0F;
    private static final float CELL = 20.0F;

    /** 跨界面重建保留的滚动位置（服务端每次操作后会重建 ShopScreen）。 */
    private static float savedListScroll;

    private final ScrollController listScroll = new ScrollController();
    private final ScrollController bodyScroll = new ScrollController();
    private final ScrollController attachScroll = new ScrollController();
    private boolean listRestored;
    private String bodyMailId = "";
    private final Map<String, Float> rowHover = new HashMap<>();
    private float starHover;

    private final Button redeem = smallButton(Button.Kind.NORMAL, "gui.module_shop.redeem.button");
    private final Button delete = smallButton(Button.Kind.DANGER, "gui.module_shop.mail.delete");
    private final Button claim = new Button(Button.Kind.ACCENT, () -> claimLabel(), null)
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button claimAll = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.mail_claim_all").getString(), null).radius(3.0F);
    private final Button deleteRead = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.mail_delete_read").getString(), null).radius(3.0F);

    // 本帧几何（绘制与输入共用）
    private final List<Object[]> rowHits = new ArrayList<>();
    private float listX, listTop, listBottom;
    private float bodyX, bodyY, bodyW, bodyH;
    private float attX, attY, attW;
    private float starX, starY;
    private MailEntry shown;

    // 正文排版缓存
    private String wrapKey = "";
    private List<StyledText.Line> wrapLines = List.of();

    private static Button smallButton(Button.Kind kind, String key) {
        return new Button(kind, () -> Component.translatable(key).getString(), null).radius(2.0F).fontSize(Text.SMALL);
    }

    private MailEntry selectedForLabel;

    private String claimLabel() {
        MailEntry sel = selectedForLabel;
        if (sel == null) {
            return "";
        }
        boolean done = !MailFormat.unread(sel);
        String key = done ? "gui.module_shop.mail.claimed_done"
                : (sel.type == MailType.MONEY ? "gui.module_shop.mail.view" : "gui.module_shop.mail.claim");
        return Component.translatable(key).getString();
    }

    @Override
    public String id() {
        return "mail";
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, PageFrame f) {
        List<MailEntry> mails = MailFormat.sorted();
        if (ClientShopData.mailSelected.isEmpty() && !mails.isEmpty()) {
            ClientShopData.mailSelected = mails.get(0).mailId;
        }
        float paneTop = f.y();
        float paneBottom = f.barTop() - 6.0F;
        float paneH = paneBottom - paneTop;

        drawList(canvas, f, mails, f.x(), paneTop, LIST_W, paneH);
        drawDetail(canvas, f, MailFormat.selected(mails), f.x() + LIST_W + GAP, paneTop, f.w() - LIST_W - GAP, paneH);

        // 底部操作条
        float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
        claimAll.bounds(f.x() + 4.0F, barMid - 8.0F, 96.0F, 16.0F);
        deleteRead.bounds(f.x() + 104.0F, barMid - 8.0F, 88.0F, 16.0F);
        claimAll.onClick(() -> f.host().action("claim_all_mail", null));
        deleteRead.onClick(() -> f.host().action("delete_read_mail", null));
        claimAll.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        deleteRead.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
    }

    private void drawList(Canvas canvas, PageFrame f, List<MailEntry> mails, float x, float y, float w, float h) {
        card(canvas, x, y, w, h);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.mail_all", mails.size(),
                        Config.INSTANCE.maxMailboxSize.get()).getString(),
                AtomUi.BOLD, Text.SMALL, IOStyle.ORANGE, x + 8.0F, y + 10.0F, w - 74.0F, Text.Align.LEFT);
        redeem.bounds(x + w - 58.0F, y + 4.0F, 56.0F, 13.0F);
        redeem.onClick(() -> f.host().action("open_redeem", null));
        redeem.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());

        listX = x;
        listTop = y + HEADER_H;
        listBottom = y + h - 2.0F;
        float viewH = listBottom - listTop;
        listScroll.setContent(mails.size() * ROW_H, viewH);
        if (!listRestored) {
            listScroll.scrollTo(savedListScroll, false);
            listRestored = true;
        }
        listScroll.update();
        savedListScroll = listScroll.offset();

        rowHits.clear();
        boolean inList = f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= listTop && f.mouseY() < listBottom;
        float rowW = w - 4.0F - (listScroll.canScroll() ? 4.0F : 0.0F);
        int saved = canvas.save();
        try {
            Draw.clip(canvas, x, listTop, w, viewH);
            float off = listScroll.offset();
            int first = Math.max(0, (int) (off / ROW_H));
            for (int i = first; i < mails.size(); i++) {
                float ry = listTop + i * ROW_H - off;
                if (ry > listBottom) {
                    break;
                }
                MailEntry m = mails.get(i);
                boolean selected = m.mailId.equals(ClientShopData.mailSelected);
                boolean hover = inList && !listScroll.isDragging() && f.mouseX() < x + 2.0F + rowW
                        && f.mouseY() >= ry && f.mouseY() < ry + ROW_H - 2.0F;
                float ht = Motion.approach(rowHover.getOrDefault(m.mailId, 0.0F), hover ? 1.0F : 0.0F,
                        f.dtMs(), Motion.HOVER_MS);
                rowHover.put(m.mailId, ht);
                if (selected) {
                    Draw.fill(canvas, x + 2.0F, ry, rowW, ROW_H - 2.0F, 3.0F, IOStyle.BLUE_TINT);
                    Draw.stroke(canvas, x + 2.0F, ry, rowW, ROW_H - 2.0F, 3.0F, 1.0F, IOStyle.TAB_SEL_BORDER);
                } else if (ht > 0.01F) {
                    Draw.fill(canvas, x + 2.0F, ry, rowW, ROW_H - 2.0F, 3.0F, Draw.alpha(IOStyle.ROW_HOVER, ht));
                }
                boolean unread = MailFormat.unread(m);
                int kind = MailFormat.kindColor(m);
                Draw.fill(canvas, x + 7.0F, ry + 5.5F, 4.0F, 4.0F, 2.0F, unread ? kind : IOStyle.HAIR);
                float textW = rowW - 16.0F - (m.starred ? 12.0F : 4.0F);
                StyledText.drawLine(canvas, MailFormat.colorize(MailFormat.title(m)),
                        unread ? kind : IOStyle.TEXT_2, Text.SMALL, x + 16.0F, ry + 7.5F, textW);
                String sender = m.sender == null || m.sender.isEmpty() ? "-" : m.sender;
                Text.drawLine(canvas, sender, AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_3,
                        x + 16.0F, ry + 15.0F, rowW - 18.0F, Text.Align.LEFT);
                if (m.starred) {
                    Text.drawLine(canvas, "★", AtomUi.REGULAR, Text.SMALL, IOStyle.ORANGE,
                            x + 2.0F + rowW - 11.0F, ry + 7.5F, 9.0F, Text.Align.CENTER);
                }
                rowHits.add(new Object[]{ry, m.mailId});
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        listScroll.drawBar(canvas, x, listTop, w, viewH, f.mouseX(), f.mouseY(), f.dtMs());
        if (listScroll.hasMoreBelow()) {
            SkiaDraw.drawVerticalGradient(canvas, x + 1.0F, listBottom - 8.0F, w - 2.0F, 8.0F, 0.0F,
                    Draw.alpha(IOStyle.CARD, 0.0F), IOStyle.CARD);
        }
    }

    private void drawDetail(Canvas canvas, PageFrame f, MailEntry sel, float x, float y, float w, float h) {
        card(canvas, x, y, w, h);
        shown = sel;
        selectedForLabel = sel;
        if (sel == null) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop.mail_empty").getString(), AtomUi.REGULAR,
                    Text.SMALL, IOStyle.TEXT_2, x, y + h / 2.0F, w, Text.Align.CENTER);
            delete.visible(false);
            claim.visible(false);
            return;
        }
        // 标题 + 星标按钮
        StyledText.drawLine(canvas, MailFormat.colorize(MailFormat.title(sel)), IOStyle.TEXT, Text.BODY,
                x + 8.0F, y + 11.0F, w - 36.0F);
        starX = x + w - 21.0F;
        starY = y + 4.0F;
        boolean starHit = f.mouseX() >= starX && f.mouseX() < starX + 15.0F
                && f.mouseY() >= starY && f.mouseY() < starY + 15.0F;
        starHover = Motion.approach(starHover, starHit ? 1.0F : 0.0F, f.dtMs(), Motion.HOVER_MS);
        int starBg = sel.starred ? IOStyle.BLUE_TINT : Draw.lerp(IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER, starHover);
        Draw.fill(canvas, starX, starY, 15.0F, 15.0F, 3.0F, starBg);
        Text.drawLine(canvas, "★", AtomUi.REGULAR, Text.BODY, sel.starred ? IOStyle.ORANGE : IOStyle.TEXT_2,
                starX, starY + 7.5F, 15.0F, Text.Align.CENTER);

        String sender = sel.sender == null || sel.sender.isEmpty() ? "-" : sel.sender;
        Text.drawLine(canvas, Component.translatable("gui.module_shop.mail.from", sender).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, x + 8.0F, y + 24.0F, w - 16.0F, Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.module_shop.mail.time", MailFormat.time(sel)).getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, x + 8.0F, y + 32.0F, w - 16.0F, Text.Align.LEFT);

        // 布局：正文在上，附件区与底部按钮在下
        float btnY = y + h - 20.0F;
        attX = x + 6.0F;
        attW = w - 12.0F;
        attY = btnY - 6.0F - ATT_H;
        bodyX = x + 8.0F;
        bodyY = y + 40.0F;
        bodyW = w - 16.0F;
        bodyH = Math.max(10.0F, attY - 13.0F - bodyY);

        if (!sel.mailId.equals(bodyMailId)) {
            bodyMailId = sel.mailId;
            bodyScroll.reset();
            attachScroll.reset();
        }
        drawBody(canvas, f, sel);
        drawAttachments(canvas, f, sel);

        // 删除 / 领取
        delete.bounds(x + 6.0F, btnY, 46.0F, 14.0F).visible(true);
        claim.bounds(x + w - 62.0F, btnY, 54.0F, 14.0F).visible(true);
        boolean done = !MailFormat.unread(sel);
        claim.enabled(!done);
        CompoundTag idTag = new CompoundTag();
        idTag.putString("mail_id", sel.mailId);
        delete.onClick(() -> f.host().action("mail_delete", idTag));
        claim.onClick(() -> f.host().action(sel.type == MailType.MONEY ? "view_mail" : "claim_mail", idTag));
        delete.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        claim.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
    }

    private void drawBody(Canvas canvas, PageFrame f, MailEntry sel) {
        String raw = sel.content == null || sel.content.isEmpty()
                ? MailFormat.description(sel) : MailFormat.colorize(sel.content);
        String key = sel.mailId + "|" + bodyW + "|" + raw;
        if (!key.equals(wrapKey)) {
            wrapKey = key;
            wrapLines = StyledText.wrap(StyledText.parse(raw, IOStyle.TEXT), bodyW - 6.0F, Text.SMALL, 0);
        }
        float lineH = StyledText.lineHeight(Text.SMALL);
        bodyScroll.setContent(wrapLines.size() * lineH, bodyH);
        bodyScroll.update();
        int saved = canvas.save();
        try {
            Draw.clip(canvas, bodyX, bodyY, bodyW, bodyH);
            float off = bodyScroll.offset();
            for (int i = 0; i < wrapLines.size(); i++) {
                float ly = bodyY + i * lineH - off;
                if (ly + lineH < bodyY) {
                    continue;
                }
                if (ly > bodyY + bodyH) {
                    break;
                }
                StyledText.draw(canvas, wrapLines.get(i), bodyX, ly, Text.SMALL);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        bodyScroll.drawBar(canvas, bodyX, bodyY, bodyW + 4.0F, bodyH, f.mouseX(), f.mouseY(), f.dtMs());
    }

    private void drawAttachments(Canvas canvas, PageFrame f, MailEntry sel) {
        Text.drawLine(canvas, Component.translatable("gui.module_shop.mail.attachment").getString(),
                AtomUi.REGULAR, Text.SMALL, IOStyle.TEXT_2, attX + 2.0F, attY - 6.0F, 60.0F, Text.Align.LEFT);
        if (sel.expireAt > 0L) {
            long days = Math.max(0L, (sel.expireAt - System.currentTimeMillis()) / 86_400_000L);
            Text.drawLine(canvas, Component.translatable("gui.module_shop.mail.expire", days).getString(),
                    AtomUi.REGULAR, Text.SMALL, IOStyle.RED, attX + 60.0F, attY - 6.0F, attW - 62.0F,
                    Text.Align.RIGHT);
        }
        Draw.fill(canvas, attX, attY, attW, ATT_H, 3.0F, IOStyle.SLOT_INNER);
        List<ItemStack> items = sel.itemStacks();
        if (items.isEmpty()) {
            String text = sel.currencyAmount > 0L ? ClientShopData.money(sel.currencyAmount)
                    : Component.translatable("gui.module_shop.mail.no_body").getString();
            Text.drawLine(canvas, text, sel.currencyAmount > 0L ? AtomUi.BOLD : AtomUi.REGULAR,
                    sel.currencyAmount > 0L ? Text.BODY : Text.SMALL,
                    sel.currencyAmount > 0L ? IOStyle.ORANGE : IOStyle.TEXT_2,
                    attX + 6.0F, attY + ATT_H / 2.0F, attW - 12.0F, Text.Align.LEFT);
            return;
        }
        float innerX = attX + 4.0F;
        float innerY = attY + 3.0F;
        float innerH = ATT_H - 6.0F;
        // 列数按格宽取整，再把剩余宽度平均分到格子之间：整行铺满，不会在右侧留下一截空白
        float usableW = attW - 8.0F - 6.0F; // 左右内边距 + 右侧滚动条位置
        int cols = Math.max(1, (int) ((usableW + 2.0F) / CELL));
        float step = cols > 1 ? (usableW - 18.0F) / (cols - 1) : CELL;
        int rows = (items.size() + cols - 1) / cols;
        attachScroll.setContent(rows * CELL, innerH);
        attachScroll.update();
        int saved = canvas.save();
        f.host().items().pushClip(attX, innerY, attW, innerH);
        try {
            Draw.clip(canvas, attX, innerY, attW, innerH);
            float off = attachScroll.offset();
            for (int i = 0; i < items.size(); i++) {
                float cx = innerX + (i % cols) * step;
                float cy = innerY + (i / cols) * CELL - off;
                if (cy + CELL < innerY || cy > innerY + innerH) {
                    continue;
                }
                Draw.fill(canvas, cx, cy, 18.0F, 18.0F, 2.0F, IOStyle.CARD);
                Draw.stroke(canvas, cx, cy, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
                f.host().items().add(items.get(i), cx + 1.0F, cy + 1.0F);
            }
        } finally {
            f.host().items().popClip();
            canvas.restoreToCount(saved);
        }
        attachScroll.drawBar(canvas, attX, innerY, attW, innerH, f.mouseX(), f.mouseY(), f.dtMs());
    }

    private static void card(Canvas canvas, float x, float y, float w, float h) {
        Draw.fill(canvas, x, y, w, h, 3.0F, IOStyle.CARD);
        Draw.stroke(canvas, x, y, w, h, 3.0F, 1.0F, IOStyle.BORDER_DIM);
    }

    // ------------------------------------------------------------------ 输入

    private List<Button> buttons() {
        List<Button> list = new ArrayList<>(List.of(redeem, claimAll, deleteRead));
        if (shown != null) {
            list.add(delete);
            list.add(claim);
        }
        return list;
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button == 0) {
            if (listScroll.pressBar(mx, my, listX, listTop, LIST_W, listBottom - listTop)
                    || bodyScroll.pressBar(mx, my, bodyX, bodyY, bodyW + 4.0F, bodyH)
                    || (shown != null && attachScroll.pressBar(mx, my, attX, attY + 3.0F, attW, ATT_H - 6.0F))) {
                return true;
            }
        }
        for (Button b : buttons()) {
            if (b.isVisible() && b.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        // 详情右上角星标
        if (shown != null && button == 0 && mx >= starX && mx < starX + 15.0F && my >= starY && my < starY + 15.0F) {
            ClickSound.play();
            f.host().action("mail_star", idTag(shown.mailId));
            return true;
        }
        // 列表行：左键选中，右键星标
        if (mx >= listX && mx < listX + LIST_W && my >= listTop && my < listBottom) {
            for (Object[] hit : rowHits) {
                float ry = (float) hit[0];
                if (my >= ry && my < ry + ROW_H - 2.0F) {
                    ClickSound.play();
                    f.host().action(button == 1 ? "mail_star" : "mail_select", idTag((String) hit[1]));
                    return true;
                }
            }
        }
        return false;
    }

    private static CompoundTag idTag(String mailId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("mail_id", mailId);
        return tag;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = listScroll.releaseBar() | bodyScroll.releaseBar() | attachScroll.releaseBar();
        for (Button b : buttons()) {
            handled |= b.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return listScroll.dragBar(my, listBottom - listTop)
                || bodyScroll.dragBar(my, bodyH)
                || attachScroll.dragBar(my, ATT_H - 6.0F);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        // 鼠标在哪个区域就滚哪个：附件 → 正文 → 列表
        if (shown != null && mx >= attX && mx < attX + attW && my >= attY && my < attY + ATT_H
                && attachScroll.canScroll()) {
            attachScroll.wheel(scrollY);
            return true;
        }
        if (mx >= bodyX && mx < bodyX + bodyW && my >= bodyY && my < bodyY + bodyH && bodyScroll.canScroll()) {
            bodyScroll.wheel(scrollY);
            return true;
        }
        if (mx >= listX && mx < listX + LIST_W && my >= listTop && my < listBottom) {
            listScroll.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void cancelInput() {
        listScroll.releaseBar();
        bodyScroll.releaseBar();
        attachScroll.releaseBar();
        for (Button b : List.of(redeem, delete, claim, claimAll, deleteRead)) {
            b.cancelPress();
        }
    }
}
