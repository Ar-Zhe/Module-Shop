package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 兑换码输入弹窗（第七轮 P4）：半透明深色面板 + 单个输入框。
 * <p>
 * 交互：输入框自动聚焦、支持 Ctrl+V、输入内容自动大写去首尾空格；回车 = 确认；
 * 确认后按钮置灰显示「兑换中...」防连点；失败时输入框变红并左右抖动、下方显示红色原因；
 * 成功时显示奖励提示并短暂停留后自动关闭（奖励已进邮箱）。
 */
public class RedeemCodeScreen extends Screen {
    private static final int PANEL_W = 280;
    private static final int PANEL_H = 136;
    /** 当前打开的弹窗（用于接收服务端回传结果）。 */
    private static RedeemCodeScreen open;

    private EditBox box;
    private final java.util.List<Region> regions = new java.util.ArrayList<>();
    private String hint = "";
    private boolean error;
    private boolean pending;
    private boolean success;
    private int shakeTicks;
    /** 粘贴按钮命中区（输入框右侧的小图标）。 */
    private final int[] pasteRect = new int[4];

    public RedeemCodeScreen() {
        super(Component.translatable("gui.module_shop.redeem.title"));
    }

    @Override
    protected void init() {
        super.init();
        open = this;
        int x = (this.width - PANEL_W) / 2;
        int y = (this.height - PANEL_H) / 2;
        // 输入框右侧留出粘贴按钮的位置（28px）。
        this.box = new EditBox(this.font, x + 22, y + 41, PANEL_W - 44 - 28, 18,
                Component.translatable("gui.module_shop.redeem.hint"));
        this.box.setMaxLength(48);
        this.box.setHint(Component.translatable("gui.module_shop.redeem.hint"));
        this.box.setBordered(false);
        this.addRenderableWidget(this.box);
        this.setInitialFocus(this.box);
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
    public void tick() {
        super.tick();
        if (this.shakeTicks > 0) {
            this.shakeTicks--;
        }
        if (this.success && this.shakeTicks <= 0) {
            // 成功提示展示约 1.5 秒后自动关闭
            this.successTicks++;
            if (this.successTicks > 30) {
                onClose();
            }
        }
    }

    private int successTicks;

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        if (this.pending || this.success) {
            return;
        }
        // 只保留大写字母 / 数字 / 连字符，避免粘贴时带入空格或不可见字符。
        String code = this.box.getValue().trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9\\-]", "");
        this.box.setValue(code);
        if (code.isEmpty()) {
            this.error = true;
            this.hint = Component.translatable("msg.module_shop.redeem.empty").getString();
            this.shakeTicks = 8;
            return;
        }
        this.pending = true;
        this.error = false;
        this.hint = Component.translatable("gui.module_shop.redeem.ok").getString();
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "redeem_code");
        tag.putString("code", code);
        ServerboundShopActionPayload.send(tag);
    }

    /** 服务端回传结果（ok + reason），由 ClientPayloadHandler 调用。 */
    public static void applyResult(CompoundTag data) {
        if (open == null) {
            return;
        }
        open.pending = false;
        if (data.getBoolean("ok")) {
            open.error = false;
            open.success = true;
            open.successTicks = 0;
            open.hint = Component.translatable("gui.module_shop.redeem.success").getString();
        } else {
            open.error = true;
            open.success = false;
            open.shakeTicks = 8;
            open.hint = Component.translatable("msg.module_shop.redeem." + data.getString("reason")).getString();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.regions.clear();
        int x = (this.width - PANEL_W) / 2;
        int y = (this.height - PANEL_H) / 2;
        // 抖动：失败时整个面板左右晃动
        int shake = this.shakeTicks > 0 ? (this.shakeTicks % 2 == 0 ? -2 : 2) : 0;
        x += shake;
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0xE6101114);
        IOStyle.ring(g, x, y, PANEL_W, PANEL_H, 10, this.error ? IOStyle.RED : IOStyle.HAIR);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.redeem.title"),
                x + PANEL_W / 2, y + 12, IOStyle.TEXT);
        if (this.box != null) {
            this.box.setX(x + 22);
        }
        // 输入框描边（错误时红色）
        IOStyle.ring(g, x + 20, y + 39, PANEL_W - 40 - 26, 22, 5, this.error ? IOStyle.RED : IOStyle.TAB_BORDER);
        // 粘贴按钮：输入框右侧的小图标（悬停显示「粘贴」）
        boolean pasteHover = IOStyle.inside(x + PANEL_W - 44, y + 39, 24, 22, mouseX, mouseY);
        IOStyle.rounded(g, x + PANEL_W - 44, y + 39, 24, 22, 5,
                pasteHover ? IOStyle.BLUE_TINT : IOStyle.FIELD_BG);
        IOStyle.ring(g, x + PANEL_W - 44, y + 39, 24, 22, 5,
                pasteHover ? IOStyle.TAB_SEL_BORDER : IOStyle.TAB_BORDER);
        drawPasteIcon(g, x + PANEL_W - 38, y + 44, pasteHover ? IOStyle.BLUE : IOStyle.TEXT_2);
        this.pasteRect[0] = x + PANEL_W - 44;
        this.pasteRect[1] = y + 39;
        this.pasteRect[2] = 24;
        this.pasteRect[3] = 22;
        addRegion(x + PANEL_W - 44, y + 39, 24, 22, "paste", null, null);
        IOStyle.button(g, x + 20, y + PANEL_H - 52, 110, 18,
                Component.translatable(this.pending ? "gui.module_shop.redeem.ok" : "gui.module_shop.confirm"),
                this.font, mouseX, mouseY, !this.pending && !this.success, IOStyle.Btn.PRIMARY);
        IOStyle.button(g, x + PANEL_W - 130, y + PANEL_H - 52, 110, 18,
                Component.translatable("gui.module_shop.cancel"), this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        // 管理员入口：固定在弹窗**左下角**（与确认/取消分行，不再互相压住）。
        if (cn.autoforged.module_shop.client.ClientShopData.canAdmin) {
            IOStyle.rounded(g, x + 16, y + PANEL_H - 24, 104, 16, 5, IOStyle.BLUE_TINT);
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.redeem.generator"),
                    x + 68, y + PANEL_H - 20, IOStyle.BLUE);
            addRegion(x + 16, y + PANEL_H - 24, 104, 16, "open_generator", null, null);
        }
        if (!this.hint.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.literal(this.hint), x + PANEL_W / 2, y + PANEL_H - 66,
                    this.error ? IOStyle.RED : this.success ? IOStyle.GREEN : IOStyle.TEXT_2);
        }
        // 悬停提示最后画，永远在最上层。
        if (pasteHover) {
            g.renderTooltip(this.font, Component.translatable("gui.module_shop.redeem.paste"), mouseX, mouseY);
        }
    }

    /** 手绘小剪贴板图标（10×11）：外框 + 顶部夹子 + 右下角内页，风格与模组其它图标一致。 */
    private void drawPasteIcon(GuiGraphics g, int x, int y, int color) {
        IOStyle.ring(g, x, y + 1, 9, 10, 2, color);
        g.fill(x + 3, y, x + 6, y + 1, color);
        IOStyle.rounded(g, x + 4, y + 5, 6, 6, 1, IOStyle.withAlpha(IOStyle.BLUE, 200));
        IOStyle.ring(g, x + 4, y + 5, 6, 6, 1, color);
    }

    /** 粘贴系统剪贴板内容到输入框（只保留大写字母 / 数字 / 连字符）。 */
    private void pasteFromClipboard() {
        String clip = net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip == null || clip.isEmpty()) {
            return;
        }
        String cleaned = clip.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9\\-]", "");
        if (!cleaned.isEmpty()) {
            this.box.setValue(cleaned);
            this.error = false;
            this.hint = "";
        }
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
            if (button == 0 && "open_generator".equals(region.action)) {
                Bricks.click();
                Nav.open(new RedeemGeneratorScreen());
                return true;
            }
            if (button == 0 && "paste".equals(region.action)) {
                Bricks.click();
                pasteFromClipboard();
                return true;
            }
            return true;
        }
        int x = (this.width - PANEL_W) / 2;
        int y = (this.height - PANEL_H) / 2;
        if (button == 0 && mouseX >= x + 20 && mouseX < x + 130 && mouseY >= y + PANEL_H - 52
                && mouseY < y + PANEL_H - 34) {
            Bricks.click();
            confirm();
            return true;
        }
        if (button == 0 && mouseX >= x + PANEL_W - 130 && mouseX < x + PANEL_W - 20
                && mouseY >= y + PANEL_H - 52 && mouseY < y + PANEL_H - 34) {
            Bricks.click();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private record Region(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
    }
}
