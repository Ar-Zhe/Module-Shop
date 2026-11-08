package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 导入预览：显示配置名称、条目总数、有效 / 无效 / 冲突数量，
 * 由玩家选择「替换导入 / 合并导入 / 取消」，确认后才把 JSON 发给服务端应用。
 */
public class TemplatePreviewScreen extends Screen {
    private final String json;
    private final CompoundTag preview;
    private final Screen parent;

    public TemplatePreviewScreen(String json, CompoundTag preview, Screen parent) {
        super(Component.translatable("gui.module_shop.cfg.preview_title"));
        this.json = json;
        this.preview = preview;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 + 20;
        addRenderableWidget(Button.builder(Component.translatable("gui.module_shop.cfg.replace"),
                button -> apply("replace")).bounds(cx - 160, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.module_shop.cfg.merge"),
                button -> apply("merge")).bounds(cx - 50, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.common.cancel"),
                button -> this.minecraft.setScreen(this.parent)).bounds(cx + 60, y, 100, 20).build());
    }

    private void apply(String mode) {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "cfg_import_apply");
        tag.putString("mode", mode);
        tag.putString("json", this.json);
        ServerboundShopActionPayload.send(tag);
        this.minecraft.setScreen(null);
    }

    /** 已经在预览页时又收到预览（一般不会发生）：只刷新数字。 */
    public void onPreview(CompoundTag data) {
        // 保持当前页即可
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 60;
        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.module_shop.cfg.preview_name", preview.getString("name")), cx, y + 16, 0xAAAAAA);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.module_shop.cfg.preview_total", preview.getInt("total")), cx, y + 30, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.module_shop.cfg.preview_valid", preview.getInt("valid")), cx, y + 42, 0x55FF55);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.module_shop.cfg.preview_invalid", preview.getInt("invalid")), cx, y + 54,
                preview.getInt("invalid") > 0 ? 0xFFAA00 : 0xAAAAAA);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.module_shop.cfg.preview_conflicts", preview.getInt("conflicts")), cx, y + 66,
                0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
