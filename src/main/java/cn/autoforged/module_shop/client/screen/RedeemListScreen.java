package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 兑换码列表（第八轮改版，仅管理员）。
 * <p>
 * 版式与「货币兑换」列表一致：一行一张卡片，**点一下展开**，展开后右侧是
 * 「复制 / 编辑 / 查看 / 删除」四个操作：
 * <ul>
 *   <li>复制：把兑换码写进系统剪贴板（也可 Ctrl+C）；</li>
 *   <li>编辑：进入 {@link RedeemEditScreen} 改时效 / 限领 / 标题 / 公告与奖励内容（已领取的玩家不受影响）；</li>
 *   <li>查看：弹出领取名单（谁领了、各领了几次）；</li>
 *   <li>删除：删除该兑换码。</li>
 * </ul>
 * 数据由服务端 {@code admin_redeem_list} 下发（{@code kind=redeem_list}）。
 */
public class RedeemListScreen extends Screen {
    private static final int PANEL_W = 420;
    private static final int PANEL_H = 250;
    private static final int ROW_H = 22;
    private static final int EXPANDED_H = 46;
    private static final List<CompoundTag> CODES = new ArrayList<>();

    private static RedeemListScreen open;

    private final List<Region> regions = new ArrayList<>();
    private int scrollRow;
    private int pointerX;
    private int pointerY;
    /** 当前展开（选中）的兑换码（空 = 全部收起）。 */
    private String expanded = "";
    /** 复制成功后的短暂提示。 */
    private int copiedTicks;
    private String copiedText = "";
    /** 「查看」弹窗是否打开 + 正在查看的兑换码。 */
    private boolean claimedOpen;
    private String claimedCode = "";

    public RedeemListScreen() {
        super(Component.translatable("gui.module_shop.redeem.list.title"));
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
        open = null;
        Nav.back();
    }

    @Override
    protected void init() {
        super.init();
        open = this;
        send("admin_redeem_list", null);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.copiedTicks > 0) {
            this.copiedTicks--;
        }
    }

    /** 服务端回传列表。 */
    public static void applyList(CompoundTag data) {
        CODES.clear();
        ListTag list = data.getList("codes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CODES.add(list.getCompound(i));
        }
        if (open != null) {
            open.scrollRow = 0;
        }
    }

    /** 按兑换码取一条（编辑界面用）。 */
    static CompoundTag findByCode(String code) {
        for (CompoundTag c : CODES) {
            if (code.equals(c.getString("code"))) {
                return c.copy();
            }
        }
        return null;
    }

    private void copy(String code) {
        if (code == null || code.isEmpty()) {
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        this.expanded = code;
        this.copiedText = code;
        this.copiedTicks = 60;
    }

    /** 每行的展开高度（展开的那一行更高，多出来的一行放操作按钮）。 */
    private int heightOf(CompoundTag c) {
        return c.getString("code").equals(this.expanded) ? EXPANDED_H : ROW_H;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.regions.clear();
        this.pointerX = mouseX;
        this.pointerY = mouseY;
        int x = left();
        int y = top();
        super.render(g, mouseX, mouseY, partialTick);
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.redeem.list.title"),
                x + PANEL_W / 2, y + 8, IOStyle.TEXT);
        IOStyle.rounded(g, x + PANEL_W - 22, y + 6, 16, 14, 5, IOStyle.GRAY_BTN);
        IOStyle.centerAt(g, this.font, Component.literal("x"), x + PANEL_W - 14, y + 9, IOStyle.TEXT);
        addRegion(x + PANEL_W - 22, y + 6, 16, 14, "close", null, null);
        small(g, x + 14, y + 9, Component.translatable("gui.module_shop.redeem.list.hint"), IOStyle.TEXT_3);
        if (this.copiedTicks > 0) {
            // 复制提示放在标题右侧（标题居中，不能被压住）。
            small(g, x + PANEL_W - 166, y + 9,
                    Component.translatable("gui.module_shop.redeem.list.copied", this.copiedText),
                    IOStyle.GREEN);
        }
        if (CODES.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.redeem.list.empty"),
                    x + PANEL_W / 2, y + PANEL_H / 2, IOStyle.TEXT_2);
            return;
        }
        // 可视行数按「全收起」计算，展开只影响当前行后面的内容（不会被裁掉半行）。
        int listTop = y + 24;
        int listBottom = y + PANEL_H - 8;
        int perScreen = Math.max(1, (listBottom - listTop) / ROW_H);
        int maxScroll = Math.max(0, CODES.size() - perScreen);
        this.scrollRow = Math.min(this.scrollRow, maxScroll);
        int rowY = listTop;
        for (int i = this.scrollRow; i < CODES.size(); i++) {
            CompoundTag c = CODES.get(i);
            int h = heightOf(c);
            if (rowY + h > listBottom) {
                break;
            }
            drawRow(g, x, rowY, c, h, mouseX, mouseY);
            rowY += h + 2;
        }
        if (maxScroll > 0) {
            g.fill(x + PANEL_W - 4, listTop, x + PANEL_W - 2, listBottom, IOStyle.HAIR);
            int thumbH = Math.max(14, (listBottom - listTop) * perScreen / Math.max(1, CODES.size()));
            int thumbY = listTop + (listBottom - listTop - thumbH) * this.scrollRow / Math.max(1, maxScroll);
            g.fill(x + PANEL_W - 4, thumbY, x + PANEL_W - 2, thumbY + thumbH, IOStyle.BLUE);
        }
        if (this.claimedOpen) {
            drawClaimed(g, x, y, mouseX, mouseY);
        }
    }

    /** 一行兑换码卡片：收起时是信息 + 箭头，展开后多一行操作按钮。 */
    private void drawRow(GuiGraphics g, int x, int ry, CompoundTag c, int h, int mouseX, int mouseY) {
        String code = c.getString("code");
        boolean isOpen = code.equals(this.expanded);
        boolean hover = IOStyle.inside(x + 8, ry, PANEL_W - 16, h - 2, mouseX, mouseY);
        IOStyle.rounded(g, x + 8, ry, PANEL_W - 16, h - 2, 5,
                isOpen ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.CARD));
        if (isOpen) {
            IOStyle.ring(g, x + 8, ry, PANEL_W - 16, h - 2, 5, IOStyle.TAB_SEL_BORDER);
        }
        long expireAt = c.getLong("expireAt");
        String expire = expireAt <= 0L ? Component.translatable("gui.module_shop.redeem.list.none").getString()
                : new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(expireAt));
        String scope = Component.translatable("gui.module_shop.redeem.scope_players",
                c.getInt("players")).getString();
        int used = c.getInt("used");
        int total = c.getInt("total");
        String usedText = used + "/" + (total <= 0 ? "∞" : Integer.toString(total));
        String reward = ClientShopData.money(c.getLong("money")) + " " + c.getString("items");
        IOStyle.text(g, this.font, Component.literal(code), x + 14, ry + 6, IOStyle.ORANGE);
        small(g, x + 118, ry + 7, Component.literal(IOStyle.ellipsize(this.font,
                Component.literal(expire + "   " + scope + "   " + usedText + "   " + reward), 320).getString()),
                IOStyle.TEXT_2);
        // 展开箭头（收起 ▼ / 展开 ▲）
        int arrowX = x + PANEL_W - 20;
        int arrowY = ry + 8;
        if (isOpen) {
            g.fill(arrowX - 4, arrowY + 1, arrowX + 5, arrowY + 2, IOStyle.BLUE);
            g.fill(arrowX - 3, arrowY, arrowX + 4, arrowY + 1, IOStyle.BLUE);
            g.fill(arrowX - 2, arrowY - 1, arrowX + 3, arrowY, IOStyle.BLUE);
        } else {
            g.fill(arrowX - 4, arrowY - 1, arrowX + 5, arrowY, IOStyle.TEXT_2);
            g.fill(arrowX - 3, arrowY, arrowX + 4, arrowY + 1, IOStyle.TEXT_2);
            g.fill(arrowX - 2, arrowY + 1, arrowX + 3, arrowY + 2, IOStyle.TEXT_2);
        }
        // 整行可点：展开 / 收起。命中区只覆盖**第一行**（展开后第二行是操作按钮，
        // 否则整行区域会先把点击吃掉，按钮永远点不到）。
        int headerH = Math.min(h, ROW_H) - 2;
        addRegion(x + 8, ry, PANEL_W - 40, headerH, "row:" + code, null, null);
        if (!isOpen) {
            return;
        }
        // 展开后的第二行：右侧四个操作按钮。
        int bh = 16;
        int by = ry + h - bh - 3;
        int bx = x + PANEL_W - 12 - 56;
        actionButton(g, bx, by, 56, bh, "copy:" + code, "gui.module_shop.redeem.list.copy", IOStyle.Btn.TINT);
        bx -= 60;
        actionButton(g, bx, by, 56, bh, "edit:" + code, "gui.module_shop.redeem.list.edit", IOStyle.Btn.TINT);
        bx -= 60;
        actionButton(g, bx, by, 56, bh, "claimed:" + code, "gui.module_shop.redeem.list.view", IOStyle.Btn.TINT);
        bx -= 60;
        actionButton(g, bx, by, 56, bh, "delete:" + code, "gui.module_shop.redeem.list.delete", IOStyle.Btn.DANGER);
    }

    private void actionButton(GuiGraphics g, int x, int y, int w, int h, String action, String langKey,
            IOStyle.Btn style) {
        IOStyle.button(g, x, y, w, h, Component.translatable(langKey), this.font, this.pointerX, this.pointerY,
                true, style);
        addRegion(x, y, w, h, action, null, null);
    }

    /** 「查看」弹窗：列出领取过该兑换码的玩家。 */
    private void drawClaimed(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int w = 240;
        int h = 176;
        int px = x + (PANEL_W - w) / 2;
        int py = y + (PANEL_H - h) / 2;
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0x99000000);
        g.fill(px, py, px + w, py + h, 0xF01A1C20);
        IOStyle.ring(g, px, py, w, h, 8, IOStyle.BLUE);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.redeem.list.claimed_title",
                this.claimedCode), px + w / 2, py + 8, IOStyle.TEXT);
        if (!this.claimedCode.equals(ClientShopData.redeemClaimedCode)) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.redeem.list.loading"),
                    px + w / 2, py + h / 2, IOStyle.TEXT_2);
        } else if (ClientShopData.redeemClaimedNames.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.redeem.list.claimed_none"),
                    px + w / 2, py + h / 2, IOStyle.TEXT_2);
        } else {
            int perScreen = 7;
            int count = Math.min(perScreen, ClientShopData.redeemClaimedNames.size());
            for (int i = 0; i < count; i++) {
                int ly = py + 26 + i * 16;
                IOStyle.rounded(g, px + 10, ly, w - 20, 14, 4, IOStyle.CARD);
                IOStyle.text(g, this.font,
                        Component.literal(ClientShopData.redeemClaimedNames.get(i)), px + 16, ly + 3, IOStyle.TEXT);
                int times = ClientShopData.redeemClaimedCounts.get(i);
                small(g, px + w - 16 - 40, ly + 4,
                        Component.translatable("gui.module_shop.redeem.list.claimed_line", times), IOStyle.ORANGE);
            }
            if (ClientShopData.redeemClaimedNames.size() > perScreen) {
                small(g, px + 12, py + h - 16, Component.translatable("gui.module_shop.redeem.list.claimed_more",
                        ClientShopData.redeemClaimedNames.size() - perScreen), IOStyle.TEXT_3);
            }
        }
        int bw = 80;
        int bx = px + (w - bw) / 2;
        int by = py + h - 24;
        IOStyle.button(g, bx, by, bw, 18, Component.translatable("gui.module_shop.cancel"), this.font,
                mouseX, mouseY, true, IOStyle.Btn.GRAY);
        addRegion(bx, by, bw, 18, "claimed_close", null, null);
    }

    /** 0.8 倍小字号。 */
    private void small(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private void addRegion(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
        this.regions.add(new Region(x, y, w, h, action, rightAction, extra));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            int perScreen = Math.max(1, (PANEL_H - 32) / ROW_H);
            int maxScroll = Math.max(0, CODES.size() - perScreen);
            this.scrollRow = Math.max(0, Math.min(maxScroll, this.scrollRow + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_C
                && (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0) {
            copy(this.expanded.isEmpty() && !CODES.isEmpty() ? CODES.get(0).getString("code") : this.expanded);
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
                handleAction(region.action);
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleAction(String action) {
        if ("close".equals(action)) {
            onClose();
            return;
        }
        if ("claimed_close".equals(action)) {
            this.claimedOpen = false;
            return;
        }
        int colon = action.indexOf(':');
        if (colon < 0) {
            return;
        }
        String kind = action.substring(0, colon);
        String code = action.substring(colon + 1);
        switch (kind) {
            case "row" -> this.expanded = code.equals(this.expanded) ? "" : code;
            case "copy" -> copy(code);
            case "delete" -> {
                if (code.equals(this.expanded)) {
                    this.expanded = "";
                }
                CompoundTag tag = new CompoundTag();
                tag.putString("code", code);
                send("admin_redeem_delete", tag);
                send("admin_redeem_list", null);
            }
            case "claimed" -> {
                this.claimedCode = code;
                this.claimedOpen = true;
                ClientShopData.redeemClaimedCode = "";
                CompoundTag tag = new CompoundTag();
                tag.putString("code", code);
                send("admin_redeem_claimed", tag);
            }
            case "edit" -> {
                CompoundTag data = findByCode(code);
                if (data != null) {
                    Nav.open(new RedeemEditScreen(data));
                }
            }
            default -> {
            }
        }
    }

    private void send(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        tag.putString("action", action);
        ServerboundShopActionPayload.send(tag);
    }

    private record Region(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
    }
}
