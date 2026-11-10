package cn.hsc.shoputil.client;

import cn.hsc.shoputil.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 配置文件导入界面：浏览 config 目录下的 .dat 文件，可导入到 profiles/ 目录。
 */
public class ConfigImportScreen extends Screen {

    private final Screen parent;
    private final List<Path> datFiles = new ArrayList<>();
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    public ConfigImportScreen(Screen parent) {
        super(Component.literal("导入参考配置文件"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 扫描 config 目录下所有 .dat 文件（递归）
        Path configRoot = cn.hsc.shoputil.ShopUtilMod.configDir().getParent(); // config/
        try {
            try (Stream<Path> s = Files.walk(configRoot)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dat"))
                 .filter(p -> !p.toString().contains("profiles")) // 排除已导入的
                 .forEach(datFiles::add);
            }
        } catch (Exception e) {
            Log.warn("扫描配置文件失败: " + e.getMessage());
        }

        int cx = this.width / 2;
        int bottomY = this.height - 40;
        this.addRenderableWidget(Button.builder(Component.literal("导入选中文件"), b -> onImport())
                .bounds(cx - 280, bottomY, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(cx + 180, bottomY, 60, 20).build());
    }

    private void onImport() {
        if (selectedIndex < 0 || selectedIndex >= datFiles.size()) return;
        Path src = datFiles.get(selectedIndex);
        Path dstDir = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("profiles");
        try {
            Files.createDirectories(dstDir);
            Path dst = dstDir.resolve(src.getFileName());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§a已导入: " + src.getFileName()), false);
            }
        } catch (Exception e) {
            Log.error("导入失败", e);
        }
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (cn.hsc.shoputil.ShopUtilConfig.UI_STYLE.get() == cn.hsc.shoputil.ShopUtilConfig.UiStyle.OPAQUE) {
            g.fill(0, 0, this.width, this.height, 0xFF101018);
        } else {
            super.renderBackground(g, mouseX, mouseY, partial);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, 15, 0xFFFFFFFF);
        g.drawCenteredString(this.font, "把 .dat 文件放到 config/ 目录后，在这里选择导入", cx, 28, 0xFFAAAAAA);

        int y = 45;
        int maxVisible = (this.height - 100) / 18;
        int start = Math.max(0, scrollOffset);
        int end = Math.min(datFiles.size(), start + maxVisible);

        for (int i = start; i < end; i++) {
            Path f = datFiles.get(i);
            String rel = f.getParent().getFileName().toString() + "/" + f.getFileName();
            boolean selected = (i == selectedIndex);
            int bg = selected ? 0xFF4488FF : 0x40FFFFFF;
            g.fill(cx - 280, y, cx + 280, y + 16, bg);
            g.drawString(this.font, rel, cx - 270, y + 3, 0xFFFFFFFF);
            y += 18;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int cx = this.width / 2;
        int y = 45;
        int maxVisible = (this.height - 100) / 18;
        int start = Math.max(0, scrollOffset);
        int end = Math.min(datFiles.size(), start + maxVisible);
        for (int i = start; i < end; i++) {
            int rowY = y + (i - start) * 18;
            if (mx >= cx - 280 && mx <= cx + 280 && my >= rowY && my <= rowY + 16) {
                selectedIndex = i;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int maxVisible = (this.height - 100) / 18;
        int maxScroll = Math.max(0, datFiles.size() - maxVisible);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) dy));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
