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
 * 定价文件导入界面：浏览 config 目录下的 .csv 文件，可导入到 pricing/ 目录。
 */
public class PricingImportScreen extends Screen {

    private final Screen parent;
    private final List<Path> csvFiles = new ArrayList<>();
    private int selectedIndex = -1;

    public PricingImportScreen(Screen parent) {
        super(Component.literal("导入定价文件"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 扫描 config 目录下所有 .csv 文件（排除 pricing/ 目录里的）
        Path configRoot = cn.hsc.shoputil.ShopUtilMod.configDir().getParent();
        try {
            try (Stream<Path> s = Files.walk(configRoot)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                 .filter(p -> !p.toString().contains("pricing"))
                 .forEach(csvFiles::add);
            }
        } catch (Exception e) {
            Log.warn("扫描定价文件失败: " + e.getMessage());
        }

        int cx = this.width / 2;
        int bottomY = this.height - 40;
        this.addRenderableWidget(Button.builder(Component.literal("导入选中文件"), b -> onImport())
                .bounds(cx - 280, bottomY, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(cx + 180, bottomY, 60, 20).build());
    }

    private void onImport() {
        if (selectedIndex < 0 || selectedIndex >= csvFiles.size()) return;
        Path src = csvFiles.get(selectedIndex);
        Path dstDir = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("pricing");
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
        g.drawCenteredString(this.font, "把 .csv 文件放到 config/ 目录后，在这里选择导入", cx, 28, 0xFFAAAAAA);

        int y = 45;
        for (int i = 0; i < csvFiles.size(); i++) {
            Path f = csvFiles.get(i);
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
        for (int i = 0; i < csvFiles.size(); i++) {
            int rowY = y + i * 18;
            if (mx >= cx - 280 && mx <= cx + 280 && my >= rowY && my <= rowY + 16) {
                selectedIndex = i;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
