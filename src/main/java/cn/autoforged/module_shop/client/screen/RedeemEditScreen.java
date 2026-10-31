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
 * 编辑兑换码（第八轮，仅管理员）：改**时效 / 每人限领 / 总上限 / 标题 / 公告正文**，
 * 以及通过「配置奖励内容」按钮改物品、星币与指定玩家（复用发放容器，见 {@link RedeemRewardEditorScreen}）。
 * <p>
 * 「已经领取的玩家不用管」：服务端更新时**保留 usedBy / usedCount**，已领过的人不会重新拿到奖励，
 * 也不会被重置成「没领过」。
 */
public class RedeemEditScreen extends Screen {
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 250;

    private final CompoundTag data;
    private final String code;
    /**
     * 输入框内容的**本地草稿**（纯界面状态，不进存档）。去「奖励配置」容器界面再回来时，
     * 本界面会被重新 {@code init()}、输入框全部重建，靠它把刚改的内容填回去。
     */
    private static CompoundTag inputStash;
    private static String inputStashCode = "";
    private final List<Region> regions = new ArrayList<>();
    private EditBox perPlayerBox;
    private EditBox totalBox;
    private EditBox titleBox;
    private EditBox noteBox;
    private int days;
    private String hint = "";
    private boolean error;
    private int pointerX;
    private int pointerY;

    public RedeemEditScreen(CompoundTag data) {
        super(Component.translatable("gui.module_shop.redeem.edit.title"));
        this.data = data.copy();
        this.code = data.getString("code");
        // 时效回填：按剩余时间折算成 7 / 30 / 0（永久），并允许管理员重新选。
        long remain = data.getLong("expireAt") - System.currentTimeMillis();
        if (data.getLong("expireAt") <= 0L) {
            this.days = 0;
        } else if (remain <= 8L * 86_400_000L) {
            this.days = 7;
        } else {
            this.days = 30;
        }
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
        // 取消/关闭 = 放弃这次编辑：本地草稿丢掉，也通知服务端丢掉草稿（兑换码本身不动）。
        inputStash = null;
        inputStashCode = "";
        send("admin_redeem_draft_discard", null);
        Nav.back();
    }

    @Override
    protected void init() {
        super.init();
        int x = left();
        int y = top();
        this.perPlayerBox = box(x + 92, y + 82, 56);
        this.totalBox = box(x + 218, y + 82, 56);
        this.titleBox = box(x + 92, y + 164, 294);
        this.noteBox = box(x + 92, y + 188, 294);
        this.perPlayerBox.setValue(Integer.toString(Math.max(0, this.data.getInt("perPlayer"))));
        this.totalBox.setValue(Integer.toString(Math.max(0, this.data.getInt("total"))));
        this.titleBox.setValue(this.data.getString("title"));
        this.noteBox.setValue(this.data.getString("note"));
        // 从「奖励配置」回来时，界面会被重建：用本地草稿把刚才改的内容填回去。
        restoreInputStash();
        this.setInitialFocus(this.titleBox);
        // 拉一次奖励内容：编辑界面上的摘要显示的是这个兑换码当前的物品 / 星币 / 玩家。
        CompoundTag request = new CompoundTag();
        request.putString("code", this.code);
        send("admin_redeem_preview", request);
    }

    private EditBox box(int x, int y, int w) {
        EditBox b = new EditBox(this.font, x + 4, y + 3, w - 8, 12, Component.empty());
        b.setMaxLength(120);
        b.setBordered(false);
        this.addRenderableWidget(b);
        return b;
    }

    private void field(GuiGraphics g, int x, int y, int w) {
        IOStyle.rounded(g, x, y, w, 18, 5, IOStyle.FIELD_BG);
        IOStyle.ring(g, x, y, w, 18, 5, IOStyle.TAB_BORDER);
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
        field(g, x + 89, y + 79, 62);
        field(g, x + 215, y + 79, 62);
        field(g, x + 89, y + 161, 300);
        field(g, x + 89, y + 185, 300);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.redeem.edit.title"),
                x + PANEL_W / 2, y + 8, IOStyle.TEXT);
        IOStyle.rounded(g, x + PANEL_W - 22, y + 6, 16, 14, 5, IOStyle.GRAY_BTN);
        IOStyle.centerAt(g, this.font, Component.literal("x"), x + PANEL_W - 14, y + 9, IOStyle.TEXT);
        addRegion(x + PANEL_W - 22, y + 6, 16, 14, "close", null, null);
        small(g, x + 14, y + 32, Component.translatable("gui.module_shop.redeem.edit.code", this.code),
                IOStyle.ORANGE);
        int used = this.data.getInt("used");
        small(g, x + 220, y + 32,
                Component.translatable("gui.module_shop.redeem.edit.claimed", used), IOStyle.TEXT_2);
        small(g, x + 14, y + 58, Component.translatable("gui.module_shop.field.days"));
        dayButton(g, x + 92, y + 54, 56, 7, "7");
        dayButton(g, x + 152, y + 54, 56, 30, "30");
        dayButton(g, x + 212, y + 54, 56, 0, "∞");
        small(g, x + 14, y + 84, Component.translatable("gui.module_shop.field.perPlayer"));
        small(g, x + 164, y + 84, Component.translatable("gui.module_shop.field.total"));
        // 奖励内容（点按钮进入物品发放式界面；回来时摘要自动刷新）
        small(g, x + 14, y + 110, Component.translatable("gui.module_shop.redeem.section_reward"));
        IOStyle.rounded(g, x + 12, y + 118, PANEL_W - 24, 38, 6, IOStyle.SLOT_INNER);
        IOStyle.ring(g, x + 12, y + 118, PANEL_W - 24, 38, 6, IOStyle.HAIR);
        IOStyle.button(g, x + 20, y + 126, 116, 22,
                Component.translatable("gui.module_shop.redeem.reward_btn"), this.font,
                this.pointerX, this.pointerY, true, IOStyle.Btn.TINT);
        addRegion(x + 20, y + 126, 116, 22, "reward_edit", null, null);
        String items = ClientShopData.redeemDraftItems == null || ClientShopData.redeemDraftItems.isEmpty()
                ? Component.translatable("gui.module_shop.redeem.reward_none").getString()
                : ClientShopData.redeemDraftItems;
        small(g, x + 146, y + 124, Component.translatable("gui.module_shop.redeem.reward_items_line",
                IOStyle.ellipsize(this.font, Component.literal(items), 300).getString()), IOStyle.TEXT);
        // 玩家一栏：一个都没勾 = 全体玩家都能兑换（默认）。
        Component summary = ClientShopData.redeemDraftPlayers.isEmpty()
                ? Component.translatable("gui.module_shop.redeem.reward_summary_all",
                        ClientShopData.money(ClientShopData.redeemDraftMoney))
                : Component.translatable("gui.module_shop.redeem.reward_summary",
                        ClientShopData.money(ClientShopData.redeemDraftMoney),
                        ClientShopData.redeemDraftPlayers.size());
        small(g, x + 146, y + 136, summary,
                ClientShopData.redeemDraftMoney > 0L ? IOStyle.ORANGE : IOStyle.TEXT_2);
        small(g, x + 14, y + 166, Component.translatable("gui.module_shop.field.title"));
        small(g, x + 14, y + 190, Component.translatable("gui.module_shop.field.note"));
        // 底部：保存修改 / 取消 + 说明
        int by = y + PANEL_H - 28;
        IOStyle.button(g, x + 12, by, 88, 20, Component.translatable("gui.module_shop.schedule.cancel"),
                this.font, this.pointerX, this.pointerY, true, IOStyle.Btn.GRAY);
        addRegion(x + 12, by, 88, 20, "cancel", null, null);
        IOStyle.button(g, x + PANEL_W - 132, by, 120, 20,
                Component.translatable("gui.module_shop.redeem.edit.save"), this.font, this.pointerX,
                this.pointerY, true, IOStyle.Btn.PRIMARY);
        addRegion(x + PANEL_W - 132, by, 120, 20, "save", null, null);
        small(g, x + 108, by + 6, Component.translatable("gui.module_shop.redeem.edit.hint"), IOStyle.TEXT_3);
        if (!this.hint.isEmpty()) {
            small(g, x + 14, by - 12, Component.literal(this.hint),
                    this.error ? IOStyle.RED : IOStyle.GREEN);
        }
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

    private void dayButton(GuiGraphics g, int x, int y, int w, int value, String labelText) {
        boolean on = this.days == value;
        IOStyle.rounded(g, x, y, w, 18, 5, on ? IOStyle.BLUE_TINT : IOStyle.GRAY_BTN);
        if (on) {
            IOStyle.ring(g, x, y, w, 18, 5, IOStyle.TAB_SEL_BORDER);
        }
        IOStyle.centerAt(g, this.font, Component.literal(labelText), x + w / 2, y + 5,
                on ? IOStyle.BLUE : IOStyle.TEXT);
        addRegion(x, y, w, 18, "days:" + value, null, null);
    }

    private void addRegion(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
        this.regions.add(new Region(x, y, w, h, action, rightAction, extra));
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
        if (action.startsWith("days:")) {
            this.days = Integer.parseInt(action.substring("days:".length()));
            return;
        }
        switch (action) {
            case "close", "cancel" -> onClose();
            case "reward_edit" -> openRewardEditor();
            case "save" -> save();
            default -> {
            }
        }
    }

    /** 进「奖励配置」界面：带上 code，服务端就知道是在改这个兑换码而不是在配新码。 */
    private void openRewardEditor() {
        saveInputStash();
        RedeemRewardEditorScreen.setReturnTo(this);
        CompoundTag tag = new CompoundTag();
        tag.putString("code", this.code);
        send("admin_redeem_reward_open", tag);
    }

    /** 把输入框内容存进本地草稿（只是界面状态，不发布、不落库）。 */
    private void saveInputStash() {
        CompoundTag tag = new CompoundTag();
        tag.putString("days", Integer.toString(this.days));
        tag.putString("perPlayer", this.perPlayerBox.getValue());
        tag.putString("total", this.totalBox.getValue());
        tag.putString("title", this.titleBox.getValue());
        tag.putString("note", this.noteBox.getValue());
        inputStash = tag;
        inputStashCode = this.code;
    }

    /** 从本地草稿回填输入框（只回填同一个兑换码的草稿）。 */
    private void restoreInputStash() {
        if (inputStash == null || !this.code.equals(inputStashCode)) {
            return;
        }
        try {
            this.days = Integer.parseInt(inputStash.getString("days"));
        } catch (NumberFormatException ignored) {
            // 草稿里的天数坏了就保持原值
        }
        String perPlayer = inputStash.getString("perPlayer");
        String total = inputStash.getString("total");
        if (!perPlayer.isEmpty()) {
            this.perPlayerBox.setValue(perPlayer);
        }
        if (!total.isEmpty()) {
            this.totalBox.setValue(total);
        }
        this.titleBox.setValue(inputStash.getString("title"));
        this.noteBox.setValue(inputStash.getString("note"));
    }

    private void save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("code", this.code);
        tag.putString("days", Integer.toString(this.days));
        tag.putString("perPlayer", this.perPlayerBox.getValue().trim());
        tag.putString("total", this.totalBox.getValue().trim());
        tag.putString("title", this.titleBox.getValue().trim());
        tag.putString("note", this.noteBox.getValue().trim());
        send("admin_redeem_update", tag);
        // 保存 = 已发布，本地草稿丢弃。
        inputStash = null;
        inputStashCode = "";
        // 保存后回到列表（列表会自动刷新成新数据；服务端也会发一条聊天提示）。
        Nav.back();
    }

    private void send(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        tag.putString("action", action);
        ServerboundShopActionPayload.send(tag);
    }

    private record Region(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
    }
}
