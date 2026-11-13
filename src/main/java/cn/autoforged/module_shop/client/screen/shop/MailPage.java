package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.StyledText;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.data.MailEntry;
import cn.autoforged.module_shop.data.MailType;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 邮箱（UI v2）：左边邮件表（色点 · 星标 · 标题 · 发件人 · 附件 · 时间 · 状态，可按 全部 / 未处理 / 待领取 / 星标 筛选），
 * 右边是选中邮件的详情（正文可滚动、附件格子、星标 / 删除 / 领取）。筛选纯客户端，动作字符串不变。
 */
public final class MailPage implements ShopPage {
    private static final float DETAIL_W = 230.0F;
    private static final ListTable TABLE = new ListTable();
    private static int filter;
    private final ScrollController bodyScroll = new ScrollController();

    private List<MailEntry> shown = List.of();
    private float[][] segRects = new float[0][2];
    private float segY;
    private final List<float[]> btnRects = new ArrayList<>();
    private final List<String> btnActions = new ArrayList<>();
    private String bodyKey = "";
    private List<StyledText.Line> bodyLines = List.of();
    private String bodyMailId = "";

    @Override
    public String id() {
        return "mail";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static boolean pendingItems(MailEntry m) {
        return MailFormat.unread(m) && m.type == MailType.ITEM && !m.itemStacks().isEmpty();
    }

    private static CompoundTag idTag(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("mail_id", id);
        return tag;
    }

    private void addBtn(float[] r, String action) {
        btnRects.add(r);
        btnActions.add(action);
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        btnRects.clear();
        btnActions.clear();
        List<MailEntry> all = MailFormat.sorted();
        int unread = 0;
        int pending = 0;
        int starred = 0;
        List<MailEntry> list = new ArrayList<>();
        for (MailEntry m : all) {
            boolean u = MailFormat.unread(m);
            boolean p = pendingItems(m);
            if (u) {
                unread++;
            }
            if (p) {
                pending++;
            }
            if (m.starred) {
                starred++;
            }
            if (filter == 0 || (filter == 1 && u) || (filter == 2 && p) || (filter == 3 && m.starred)) {
                list.add(m);
            }
        }
        shown = list;
        if (ClientShopData.mailSelected.isEmpty() && !list.isEmpty()) {
            ClientShopData.mailSelected = list.get(0).mailId;
        }
        drawToolbar(c, f, all.size(), unread, pending, starred);
        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        float tw = f.w() - DETAIL_W;
        drawTable(c, f, f.x(), top, tw, bottom - top);
        V2.foot(c, f.x(), bottom, tw, tr("gui.module_shop.v2.mail.count", all.size(), unread),
                tr("gui.module_shop.v2.mail.hint"));
        MailEntry sel = MailFormat.selected(all);
        drawDetail(c, f, sel, f.x() + tw, top, DETAIL_W, f.y() + f.h() - top);
    }

    private void drawToolbar(Canvas c, PageFrame f, int all, int unread, int pending, int starred) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.mail"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        String[] seg = {tr("gui.module_shop.v2.mall.all") + "  " + all, tr("gui.module_shop.v2.mail.unread") + "  " + unread,
                tr("gui.module_shop.v2.mail.pending") + "  " + pending, tr("gui.module_shop.v2.mail.starred") + "  " + starred};
        segY = by;
        segRects = V2.segmented(c, seg, filter, x + 8 + tw + 10, by, null);
        float right = x + f.w() - 8;
        String[][] btns = {{"claim_all_mail", "gui.module_shop.v2.mail.claim_all"}, {"delete_read_mail", "gui.module_shop.v2.mail.delete_read"},
                {"open_redeem", "gui.module_shop.v2.mail.redeem"}};
        for (int i = 0; i < btns.length; i++) {
            String label = tr(btns[i][1]);
            float w = V2.buttonWidth(label);
            right -= w;
            boolean enabled = i != 0 || pending > 0;
            V2.button(c, label, !enabled ? V2.Btn.DISABLED : i == 0 ? V2.Btn.PRIMARY : V2.Btn.NORMAL, right, by, w, V2.CTRL,
                    over(f, right, by, w, V2.CTRL) ? 1 : 0);
            if (enabled) {
                addBtn(new float[]{right, by, w, V2.CTRL}, btns[i][0]);
            }
            right -= 5;
        }
    }

    private void drawTable(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float[][] cols = V2.columns(x, w, 8, 5, 8, 10, 0, 64, 62, 58, 40);
        String[] heads = {"", "", tr("gui.module_shop.v2.col.title"), tr("gui.module_shop.v2.col.sender"),
                tr("gui.module_shop.v2.col.attach"), tr("gui.module_shop.v2.col.time"), tr("gui.module_shop.v2.col.state")};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT,
                Text.Align.LEFT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h, cols, heads, al, null);
        if (shown.isEmpty()) {
            V2.tableHead(c, x, y, w, cols, heads, al, -1, false);
            V2.empty(c, tr("gui.module_shop.mail_empty"), x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
            MailEntry m = shown.get(row);
            float cy = ry + V2.ROW / 2;
            if (m.mailId.equals(ClientShopData.mailSelected)) {
                TABLE.selected(cv, ry);
            }
            boolean u = MailFormat.unread(m);
            Draw.fill(cv, cols[0][0] + 1, cy - 2, 4, 4, 2, u ? MailFormat.kindColor(m) : Draw.alpha(MailFormat.kindColor(m), 0.35F));
            V2.text(cv, m.starred ? "★" : "☆", V2.FS_S, m.starred ? IOStyle.ORANGE : IOStyle.TEXT_3, cols[1][0], cy, cols[1][1],
                    Text.Align.LEFT);
            StyledText.drawLine(cv, MailFormat.colorize(MailFormat.title(m)), u ? IOStyle.TEXT : IOStyle.TEXT_2, V2.FS,
                    cols[2][0], cy, cols[2][1]);
            String sender = m.sender == null || m.sender.isEmpty() ? "—" : m.sender;
            V2.text(cv, sender, V2.FS_S, IOStyle.TEXT_2, cols[3][0], cy, cols[3][1], Text.Align.LEFT);
            if (m.type == MailType.MONEY) {
                V2.bold(cv, "+" + V2.money(m.currencyAmount), V2.FS_S, IOStyle.ORANGE, cols[4][0], cy, cols[4][1], Text.Align.LEFT);
            } else {
                List<ItemStack> items = m.itemStacks();
                float ix = cols[4][0];
                for (int i = 0; i < items.size() && i < 3; i++) {
                    f.host().items().add(items.get(i), ix, ry + 2.5F, 10, false);
                    ix += 11;
                }
                if (items.size() > 3) {
                    V2.text(cv, "+" + (items.size() - 3), V2.FS_XS, IOStyle.TEXT_3, ix + 1, cy, 20, Text.Align.LEFT);
                }
                if (items.isEmpty()) {
                    V2.text(cv, "—", V2.FS_S, IOStyle.TEXT_3, cols[4][0], cy, cols[4][1], Text.Align.LEFT);
                }
            }
            V2.text(cv, MailFormat.time(m), V2.FS_XS, IOStyle.TEXT_3, cols[5][0], cy, cols[5][1], Text.Align.LEFT);
            String state = u ? tr(m.type == MailType.MONEY ? "gui.module_shop.v2.mail.st_unviewed" : "gui.module_shop.v2.mail.st_pending")
                    : tr(m.type == MailType.MONEY ? "gui.module_shop.v2.mail.st_viewed" : "gui.module_shop.v2.mail.st_claimed");
            if (u) {
                float tw = V2.tagWidth(state);
                V2.tag(cv, state, IOStyle.ORANGE, cols[6][0] + cols[6][1] - tw + 3, cy);
            } else {
                V2.text(cv, state, V2.FS_XS, IOStyle.TEXT_3, cols[6][0], cy, cols[6][1], Text.Align.RIGHT);
            }
        });
    }

    private void drawDetail(Canvas c, PageFrame f, MailEntry sel, float x, float y, float w, float h) {
        Draw.fill(c, x, y, 0.5F, h, 0, IOStyle.BORDER_DIM);
        Draw.fill(c, x + 0.5F, y, w - 0.5F, h, 0, Draw.alpha(IOStyle.CARD, 0.45F));
        if (sel == null) {
            V2.empty(c, tr("gui.module_shop.mail_empty"), x, y, w, h);
            return;
        }
        float px = x + 10;
        float pw = w - 20;
        float cy = y + 12;
        StyledText.drawLine(c, MailFormat.colorize(MailFormat.title(sel)), IOStyle.TEXT, V2.FS + 1, px, cy, pw - 20);
        // 星标
        float sx = x + w - 24;
        boolean so = over(f, sx, cy - 7, 14, 14);
        Draw.fill(c, sx, cy - 7, 14, 14, 3, sel.starred ? Draw.alpha(IOStyle.ORANGE, 0.2F) : so ? IOStyle.ROW_HOVER : 0);
        V2.text(c, "★", V2.FS, sel.starred ? IOStyle.ORANGE : IOStyle.TEXT_3, sx, cy, 14, Text.Align.CENTER);
        addBtn(new float[]{sx, cy - 7, 14, 14}, "mail_star");
        cy += 14;
        String sender = sel.sender == null || sel.sender.isEmpty() ? "—" : sel.sender;
        String[][] kv = {{tr("gui.module_shop.v2.col.sender"), sender}, {tr("gui.module_shop.v2.col.time"), MailFormat.time(sel)},
                {tr("gui.module_shop.v2.mail.expire"), sel.expireAt > 0
                        ? tr("gui.module_shop.v2.mail.expire_days", Math.max(0L, (sel.expireAt - System.currentTimeMillis()) / 86_400_000L))
                        : tr("gui.module_shop.v2.mail.never")}};
        for (String[] e : kv) {
            V2.text(c, e[0], V2.FS_S, IOStyle.TEXT_3, px, cy, 60, Text.Align.LEFT);
            V2.text(c, e[1], V2.FS_S, IOStyle.TEXT, px, cy, pw, Text.Align.RIGHT);
            cy += 10;
        }
        Draw.hairline(c, px, cy, pw, IOStyle.BORDER_DIM);
        cy += 5;
        // 正文（可滚动）
        float attH = 52;
        float btnY = y + h - 22;
        float bodyBottom = btnY - attH - 8;
        String raw = sel.content == null || sel.content.isEmpty() ? MailFormat.description(sel) : MailFormat.colorize(sel.content);
        String key = sel.mailId + "|" + pw + "|" + raw;
        if (!key.equals(bodyKey)) {
            bodyKey = key;
            bodyLines = StyledText.wrap(StyledText.parse(raw, IOStyle.TEXT_2), pw - 4, V2.FS_S, 0);
        }
        if (!sel.mailId.equals(bodyMailId)) {
            bodyMailId = sel.mailId;
            bodyScroll.reset();
        }
        float lineH = StyledText.lineHeight(V2.FS_S) + 1;
        float bodyH = Math.max(10, bodyBottom - cy);
        bodyScroll.setContent(bodyLines.size() * lineH, bodyH);
        bodyScroll.update();
        int saved = c.save();
        Draw.clip(c, px, cy, pw, bodyH);
        float off = bodyScroll.offset();
        for (int i = 0; i < bodyLines.size(); i++) {
            float ly = cy + i * lineH - off;
            if (ly + lineH < cy || ly > cy + bodyH) {
                continue;
            }
            StyledText.draw(c, bodyLines.get(i), px, ly, V2.FS_S);
        }
        c.restoreToCount(saved);
        bodyScroll.drawBar(c, px, cy, pw + 4, bodyH, f.mouseX(), f.mouseY(), f.dtMs());
        // 附件
        float ay = bodyBottom + 4;
        V2.section(c, tr("gui.module_shop.mail.attachment"), px, ay, 80);
        ay += 6;
        Draw.fill(c, px, ay, pw, attH - 8, 3, IOStyle.SLOT_INNER);
        if (sel.type == MailType.MONEY) {
            V2.bold(c, "+" + V2.money(sel.currencyAmount) + " " + ClientShopData.currencyName(), V2.FS + 1, IOStyle.ORANGE, px,
                    ay + (attH - 8) / 2, pw, Text.Align.CENTER);
        } else {
            List<ItemStack> items = sel.itemStacks();
            float slot = 19;
            int perRow = Math.max(1, (int) ((pw - 6) / (slot + 2)));
            for (int i = 0; i < items.size() && i < perRow * 2; i++) {
                float sxx = px + 3 + (i % perRow) * (slot + 2);
                float syy = ay + 3 + (i / perRow) * (slot + 2);
                Draw.stroke(c, sxx, syy, slot, slot, 2, 0.5F, IOStyle.SLOT_BORDER);
                f.host().items().add(items.get(i), sxx + 1.5F, syy + 1.5F, 16, true);
            }
            if (items.isEmpty()) {
                V2.text(c, "—", V2.FS_S, IOStyle.TEXT_3, px, ay + (attH - 8) / 2, pw, Text.Align.CENTER);
            }
        }
        // 按钮
        boolean done = !MailFormat.unread(sel);
        String dl = tr("gui.module_shop.v2.remove");
        float dw = V2.buttonWidth(dl);
        V2.button(c, dl, V2.Btn.DANGER, px, btnY, dw, V2.CTRL, over(f, px, btnY, dw, V2.CTRL) ? 1 : 0);
        addBtn(new float[]{px, btnY, dw, V2.CTRL}, "mail_delete");
        String cl = tr(sel.type == MailType.MONEY ? "gui.module_shop.v2.mail.view" : "gui.module_shop.v2.mail.claim");
        float cw = V2.buttonWidth(cl) + 16;
        float cx = px + pw - cw;
        V2.button(c, done ? tr("gui.module_shop.v2.mail.done") : cl, done ? V2.Btn.DISABLED : V2.Btn.PRIMARY, cx, btnY, cw,
                V2.CTRL, over(f, cx, btnY, cw, V2.CTRL) ? 1 : 0);
        if (!done) {
            addBtn(new float[]{cx, btnY, cw, V2.CTRL}, sel.type == MailType.MONEY ? "view_mail" : "claim_mail");
        }
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < segRects.length; i++) {
                if (mx >= segRects[i][0] && mx < segRects[i][0] + segRects[i][1] && my >= segY && my < segY + V2.CTRL) {
                    ClickSound.play();
                    filter = i;
                    TABLE.scrollToTop();
                    return true;
                }
            }
            for (int i = 0; i < btnRects.size(); i++) {
                float[] r = btnRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    String action = btnActions.get(i);
                    ClickSound.play();
                    boolean perMail = action.startsWith("mail_") || action.equals("view_mail") || action.equals("claim_mail");
                    f.host().action(action, perMail ? idTag(ClientShopData.mailSelected) : null);
                    return true;
                }
            }
            if (TABLE.pressBar(mx, my)) {
                return true;
            }
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null || hit.row() < 0 || hit.row() >= shown.size()) {
            return hit != null;
        }
        MailEntry m = shown.get(hit.row());
        float[][] cols = TABLE.cols();
        boolean onStar = mx >= cols[1][0] && mx < cols[1][0] + cols[1][1] + 2;
        ClickSound.play();
        f.host().action(button == 1 || onStar ? "mail_star" : "mail_select", idTag(m.mailId));
        return true;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return TABLE.releaseBar() | bodyScroll.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (mx >= f.x() + f.w() - DETAIL_W) {
            bodyScroll.wheel(scrollY);
            return true;
        }
        return TABLE.wheel(mx, my, scrollY, net.minecraft.client.gui.screens.Screen.hasShiftDown());
    }

    @Override
    public void cancelInput() {
        TABLE.releaseBar();
    }
}
