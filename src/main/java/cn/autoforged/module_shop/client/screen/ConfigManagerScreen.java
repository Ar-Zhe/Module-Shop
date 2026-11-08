package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientTemplateIO;
import cn.autoforged.module_shop.config.ShopTemplate;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 「配置管理」界面（原版容器 GUI 风格，只有一个文件列表 + 几个按钮，不做文件浏览器）。
 *
 * <p>能做的事：导出当前世界配置、保存为默认模板、用默认模板初始化、从列表里选一个文件导入（先预览）。
 * 所有写世界数据的动作都会回到服务端校验权限，这里只负责收集意图。
 */
public class ConfigManagerScreen extends Screen {
    private static final int LIST_W = 220;
    private static final int ROW_H = 14;

    private final List<Path> files = new ArrayList<>();
    private final List<Button> fileButtons = new ArrayList<>();
    private int scroll;
    private Path selected;
    private String status = "";

    public ConfigManagerScreen() {
        super(Component.translatable("gui.module_shop.cfg.title"));
    }

    @Override
    protected void init() {
        refreshFiles();
        int cx = this.width / 2;
        int bottom = this.height - 28;

        addRenderableWidget(Button.builder(Component.translatable("gui.module_shop.cfg.export"),
                button -> action("cfg_export")).bounds(cx - 150, bottom, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.module_shop.cfg.save_default"),
                button -> action("cfg_save_default")).bounds(cx - 50, bottom, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.module_shop.cfg.init_from_default"),
                button -> action("cfg_init_from_default")).bounds(cx + 50, bottom, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.common.cancel"),
                button -> this.onClose()).bounds(cx - 50, bottom - 24, 100, 20).build());

        rebuildRows();
    }

    private void refreshFiles() {
        files.clear();
        files.addAll(ClientTemplateIO.listImportable());
        scroll = Math.max(0, Math.min(scroll, Math.max(0, files.size() - visibleRows())));
    }

    private int visibleRows() {
        return Math.max(3, (this.height - 120) / ROW_H);
    }

    private void rebuildRows() {
        fileButtons.forEach(this::removeWidget);
        fileButtons.clear();
        int left = this.width / 2 - LIST_W - 10;
        int top = 44;
        for (int i = 0; i < visibleRows() && scroll + i < files.size(); i++) {
            Path file = files.get(scroll + i);
            boolean isDefault = file.equals(ClientTemplateIO.defaultFile());
            String label = (isDefault ? "★ " : "") + shorten(file);
            Button button = Button.builder(Component.literal(label), b -> select(file))
                    .bounds(left, top + i * ROW_H, LIST_W, ROW_H - 2).build();
            if (file.equals(selected)) {
                button.setFGColor(0x55FF55);
            }
            fileButtons.add(button);
            addRenderableWidget(button);
        }
    }

    private static String shorten(Path file) {
        String name = file.getFileName().toString();
        return name.length() <= 30 ? name : name.substring(0, 27) + "…";
    }

    private void select(Path file) {
        this.selected = file;
        ShopTemplateStore.ReadResult read = ClientTemplateIO.read(file);
        if (!read.ok()) {
            this.status = Component.translatable("gui.module_shop.cfg.read_fail", read.error()).getString();
            rebuildRows();
            return;
        }
        this.status = Component.translatable("gui.module_shop.cfg.selected", file.getFileName().toString()).getString();
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "cfg_import_preview");
        tag.putString("json", read.template().toJson().toString());
        ServerboundShopActionPayload.send(tag);
        rebuildRows();
    }

    private void action(String action) {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", action);
        tag.putString("name", "");
        ServerboundShopActionPayload.send(tag);
    }

    /** 服务端预览回来后弹确认框。 */
    public void onPreview(CompoundTag data) {
        String json = data.getString("json");
        if (json.isBlank()) {
            return;
        }
        this.minecraft.setScreen(new TemplatePreviewScreen(json, data, this));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        graphics.drawString(this.font,
                Component.translatable("gui.module_shop.cfg.default_path",
                        ClientTemplateIO.defaultFile().toString()), 12, 26, 0xAAAAAA, false);
        int left = this.width / 2 - LIST_W - 10;
        graphics.drawString(this.font,
                Component.translatable("gui.module_shop.cfg.hint"), left, 36, 0xAAAAAA, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!status.isEmpty()) {
            graphics.drawString(this.font, status, 12, this.height - 14, 0xFFAA00, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (files.size() > visibleRows()) {
            scroll = Math.max(0, Math.min(files.size() - visibleRows(), scroll + (scrollY > 0 ? -1 : 1)));
            rebuildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
