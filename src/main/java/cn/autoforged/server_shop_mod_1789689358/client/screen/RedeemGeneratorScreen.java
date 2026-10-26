package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.client.ClientShopData;
import cn.autoforged.server_shop_mod_1789689358.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 兑换码生成器（第八轮版式重做，仅管理员）。
 * <p>
 * 只负责「码」本身：兑换码 + 🎲、时效、限领与总上限、标题与公告正文；
 * **奖励内容（物品 / 星币 / 玩家）不再在这页堆一堆输入框**，而是右上「配置奖励」按钮进入
 * {@link RedeemRewardEditorScreen}（与「管理员发放」完全同一套物品槽 + 玩家勾选 + 金额），
 * 保存后回到本页显示摘要，可随时再点进去改。
 * <p>
 * 面板底图在 {@link #renderBackground} 里绘制：否则面板会盖住原版输入框的背景与描边，
 * 看起来就是「输入框没有区域、只有几行字」。
 */
public class RedeemGeneratorScreen extends Screen {
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 250;
    private static RedeemGeneratorScreen open;

    private final List<Region> regions = new ArrayList<>();
    private EditBox codeBox;
    private EditBox perPlayerBox;
    private EditBox totalBox;
    private EditBox titleBox;
    private EditBox noteBox;
    private int days = 30;
    private String hint = "";
    private boolean error;
    private int pointerX;
    private int pointerY;

    public RedeemGeneratorScreen() {
        super(Component.translatable("gui.server_shop_mod.redeem.generator"));
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
        int x = left();
        int y = top();
        // 输入框一律「有区域」：自己画一层字段底 + 描边，原版 EditBox 的深色底在暗色面板上几乎看不见。
        this.codeBox = box(x + 92, y + 30, 178);
        initCodeBox(this.codeBox);
        this.perPlayerBox = box(x + 92, y + 82, 56);
        this.totalBox = box(x + 218, y + 82, 56);
        this.titleBox = box(x + 92, y + 164, 294);
        this.noteBox = box(x + 92, y + 188, 294);
        this.perPlayerBox.setValue("1");
        this.totalBox.setValue("0");
        this.setInitialFocus(this.codeBox);
        // 打开时拉一次草稿摘要（服务端权威），保证摘要与真实配置一致。
        send("admin_redeem_draft_request", null);
    }

    private EditBox box(int x, int y, int w) {
        EditBox b = new EditBox(this.font, x + 4, y + 3, w - 8, 12, Component.empty());
        b.setMaxLength(120);
        // 关掉原版描边：字段外观统一由本界面的 field(...) 负责（暗色底 + 圆角描边）。
        b.setBordered(false);
        this.addRenderableWidget(b);
        return b;
    }

    /**
     * 兑换码输入框：手输时自动大写、只留字母数字，并**每 4 位自动补一个连字符**
     * （形如 {@code AB12-CD34-EF56}），与随机码格式保持一致。
     */
    private void initCodeBox(EditBox b) {
        b.setMaxLength(19);
        b.setResponder(text -> {
            String formatted = formatCode(text);
            if (!formatted.equals(text)) {
                b.setValue(formatted);
            }
        });
    }

    /** 规范化兑换码文本：大写、去非法字符、每 4 位插一个 '-'（最多 16 位有效字符）。 */
    static String formatCode(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 16);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(cleaned.charAt(i));
        }
        return sb.toString();
    }

    /** 字段底框（在输入框下方先画）：所有输入框都有明确的「可输入区域」。 */
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
        // 顺序说明（这套界面里很关键）：原版 super.render 先跑（窗口背景 + 输入框控件），
        // 之后画的**面板会盖住输入框自带的黑底**，但盖不住文字（文字走另一个渲染批次）；
        // 所以字段底框必须画在面板之后，输入框文字自然还在最上层 —— 外观就是「面板上的圆角输入区」。
        super.render(g, mouseX, mouseY, partialTick);
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        field(g, x + 89, y + 27, 184);
        field(g, x + 89, y + 79, 62);
        field(g, x + 215, y + 79, 62);
        field(g, x + 89, y + 161, 300);
        field(g, x + 89, y + 185, 300);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.server_shop_mod.redeem.generator"),
                x + PANEL_W / 2, y + 8, IOStyle.TEXT);
        IOStyle.rounded(g, x + PANEL_W - 22, y + 6, 16, 14, 5, IOStyle.GRAY_BTN);
        IOStyle.centerAt(g, this.font, Component.literal("x"), x + PANEL_W - 14, y + 9, IOStyle.TEXT);
        addRegion(x + PANEL_W - 22, y + 6, 16, 14, "close", null, null);
        // 已生成兑换码列表（左上角）
        IOStyle.rounded(g, x + 12, y + 6, 56, 14, 5, IOStyle.BLUE_TINT);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.server_shop_mod.redeem.list"),
                x + 40, y + 9, IOStyle.BLUE);
        addRegion(x + 12, y + 6, 56, 14, "open_list", null, null);

        // ---------- 第一块：兑换码本身 ----------
        small(g, x + 14, y + 32, Component.translatable("gui.server_shop_mod.field.code"));
        IOStyle.rounded(g, x + 273, y + 27, 22, 18, 5, IOStyle.BLUE_TINT);
        IOStyle.centerAt(g, this.font, Component.literal("🎲"), x + 284, y + 32, IOStyle.TEXT);
        addRegion(x + 273, y + 27, 22, 18, "random", null, null);
        small(g, x + 14, y + 58, Component.translatable("gui.server_shop_mod.field.days"));
        dayButton(g, x + 92, y + 54, 56, 7, "7");
        dayButton(g, x + 152, y + 54, 56, 30, "30");
        dayButton(g, x + 212, y + 54, 56, 0, "∞");
        small(g, x + 14, y + 84, Component.translatable("gui.server_shop_mod.field.perPlayer"));
        small(g, x + 164, y + 84, Component.translatable("gui.server_shop_mod.field.total"));

        // ---------- 第二块：奖励内容（一个按钮 + 摘要） ----------
        small(g, x + 14, y + 110, Component.translatable("gui.server_shop_mod.redeem.section_reward"));
        IOStyle.rounded(g, x + 12, y + 118, PANEL_W - 24, 38, 6, IOStyle.SLOT_INNER);
        IOStyle.ring(g, x + 12, y + 118, PANEL_W - 24, 38, 6, IOStyle.HAIR);
        boolean readyReward = hasReward();
        IOStyle.button(g, x + 20, y + 126, 116, 22,
                Component.translatable("gui.server_shop_mod.redeem.reward_btn"), this.font,
                this.pointerX, this.pointerY, true,
                readyReward ? IOStyle.Btn.PRIMARY : IOStyle.Btn.TINT);
        addRegion(x + 20, y + 126, 116, 22, "reward_edit", null, null);
        // 摘要：物品一行、星币与玩家一行（超长自动截断，不会压到按钮）。
        String items = ClientShopData.redeemDraftItems == null || ClientShopData.redeemDraftItems.isEmpty()
                ? Component.translatable("gui.server_shop_mod.redeem.reward_none").getString()
                : ClientShopData.redeemDraftItems;
        small(g, x + 146, y + 124, Component.translatable("gui.server_shop_mod.redeem.reward_items_line",
                IOStyle.ellipsize(this.font, Component.literal(items), 300).getString()),
                readyReward ? IOStyle.TEXT : IOStyle.TEXT_2);
        // 玩家一栏：一个都没勾 = 全体玩家都能兑换（默认），摘要里直接写"全体玩家"。
        Component summary = ClientShopData.redeemDraftPlayers.isEmpty()
                ? Component.translatable("gui.server_shop_mod.redeem.reward_summary_all",
                        ClientShopData.money(ClientShopData.redeemDraftMoney))
                : Component.translatable("gui.server_shop_mod.redeem.reward_summary",
                        ClientShopData.money(ClientShopData.redeemDraftMoney),
                        ClientShopData.redeemDraftPlayers.size());
        small(g, x + 146, y + 136, summary,
                ClientShopData.redeemDraftMoney > 0L ? IOStyle.ORANGE : IOStyle.TEXT_2);

        // ---------- 第三块：标题 / 公告正文 ----------
        small(g, x + 14, y + 166, Component.translatable("gui.server_shop_mod.field.title"));
        small(g, x + 14, y + 190, Component.translatable("gui.server_shop_mod.field.note"));

        // ---------- 底部按钮与提示 ----------
        boolean ready = ready();
        int by = y + PANEL_H - 28;
        IOStyle.button(g, x + 12, by, 88, 20, Component.translatable("gui.server_shop_mod.redeem.clear"),
                this.font, this.pointerX, this.pointerY, true, IOStyle.Btn.GRAY);
        addRegion(x + 12, by, 88, 20, "clear", null, null);
        IOStyle.button(g, x + PANEL_W - 132, by, 120, 20,
                Component.translatable("gui.server_shop_mod.redeem.generate"),
                this.font, this.pointerX, this.pointerY, ready,
                ready ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
        if (ready) {
            addRegion(x + PANEL_W - 132, by, 120, 20, "generate", null, null);
        }
        // 提示行在按钮上方独立一行（原来它压在「公告正文」输入框上，看起来就是一团乱字）。
        small(g, x + 108, by + 6, Component.translatable("gui.server_shop_mod.redeem.mail_hint"),
                IOStyle.TEXT_3);
        if (!this.hint.isEmpty()) {
            small(g, x + 14, by - 12, Component.literal(this.hint),
                    this.error ? IOStyle.RED : IOStyle.GREEN);
        }
    }

    /** 0.8 倍小字号（左对齐），与签到/日程设置的排版一致。 */
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

    /** 必填校验：兑换码非空 + 草稿里至少有物品或星币（防呆：否则生成按钮置灰）。 */
    private boolean ready() {
        return !this.codeBox.getValue().isBlank() && hasReward();
    }

    private boolean hasReward() {
        return !isBlank(ClientShopData.redeemDraftItems) || ClientShopData.redeemDraftMoney > 0L;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isEmpty() || "-".equals(text.trim());
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
            case "random" -> this.codeBox.setValue(cn.autoforged.server_shop_mod_1789689358.data.RedeemCode
                    .randomCode(new java.util.Random()));
            case "clear" -> clear();
            case "close" -> onClose();
            case "open_list" -> Nav.open(new RedeemListScreen());
            case "reward_edit" -> openRewardEditor();
            case "generate" -> generate();
            default -> {
            }
        }
    }

    /**
     * 进入「奖励配置」界面：容器界面必须由服务端打开（槽位才是服务端权威的），
     * 所以这里先记住返回目标再发包，服务端 openMenu 后客户端会自动切到编辑界面。
     */
    private void openRewardEditor() {
        cn.autoforged.server_shop_mod_1789689358.client.screen.RedeemRewardEditorScreen.setReturnTo(this);
        send("admin_redeem_reward_open", null);
    }

    private void clear() {
        for (EditBox box : new EditBox[]{this.codeBox, this.titleBox, this.noteBox}) {
            box.setValue("");
        }
        this.perPlayerBox.setValue("1");
        this.totalBox.setValue("0");
        this.days = 30;
        this.hint = "";
        this.error = false;
    }

    private void generate() {
        if (!ready()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("code", this.codeBox.getValue().trim().toUpperCase(java.util.Locale.ROOT));
        tag.putString("days", Integer.toString(this.days));
        tag.putString("perPlayer", this.perPlayerBox.getValue().trim());
        tag.putString("total", this.totalBox.getValue().trim());
        tag.putString("title", this.titleBox.getValue().trim());
        tag.putString("note", this.noteBox.getValue().trim());
        send("admin_redeem_create", tag);
        this.error = false;
        this.hint = Component.translatable("gui.server_shop_mod.redeem.creating").getString();
    }

    private void send(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        tag.putString("action", action);
        ServerboundShopActionPayload.send(tag);
    }

    /** 服务端回传生成结果：成功则复制到剪贴板并提示，失败（重码等）红字提示。 */
    public static void applyResult(CompoundTag data) {
        if (open == null) {
            return;
        }
        if (data.getBoolean("ok")) {
            String code = data.getString("code");
            Minecraft.getInstance().keyboardHandler.setClipboard(code);
            open.error = false;
            open.hint = Component.translatable("gui.server_shop_mod.redeem.generated", code).getString();
        } else {
            open.error = true;
            open.hint = Component.translatable("msg.server_shop_mod.redeem." + data.getString("reason")).getString();
        }
    }

    private record Region(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
    }
}
