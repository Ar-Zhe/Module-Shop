package cn.hsc.shoputil.client;

import cn.hsc.shoputil.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 定价文件选择界面（参考 Iris 光影选择界面）：
 * 列出 config/shop_util/pricing/ 下所有 CSV 文件，可选中使用，可一键重置为空。
 */
public class PricingSelectScreen extends Screen {

    private final Screen parent;
    private final List<Path> files = new ArrayList<>();
    private int selectedIndex = -1;
    private String currentFile = "";
    private int scrollOffset = 0;

    public PricingSelectScreen(Screen parent) {
        super(Component.literal("选择定价配置文件"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Path dir = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("pricing");
        try {
            Files.createDirectories(dir);
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                 .forEach(files::add);
            }
        } catch (Exception e) {
            Log.warn("列出定价文件失败: " + e.getMessage());
        }
        currentFile = cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.get();
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getFileName().toString().equals(currentFile)) {
                selectedIndex = i;
                break;
            }
        }

        int cx = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.literal("确认使用"), b -> onConfirm())
                .bounds(cx - 150, this.height - 40, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("重置为空（不注入）"), b -> onReset())
                .bounds(cx - 40, this.height - 40, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(cx + 90, this.height - 40, 60, 20).build());
    }

    private void onConfirm() {
        if (selectedIndex >= 0 && selectedIndex < files.size()) {
            String name = files.get(selectedIndex).getFileName().toString();
            cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.set(name);
            cn.hsc.shoputil.ShopUtilConfig.save();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§a已选择定价文件: " + name + "，正在应用..."), false);
                // 发送重载命令到服务端
                mc.getConnection().sendCommand("shoputil reload");
            }
        }
        onClose();
    }

    private void onReset() {
        cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.set("");
        cn.hsc.shoputil.ShopUtilConfig.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§e已重置为不注入定价"), false);
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
        g.drawCenteredString(this.font, this.title, cx, 20, 0xFFFFFFFF);
        g.drawCenteredString(this.font, "当前: " + (currentFile.isEmpty() ? "（不注入）" : currentFile), cx, 35, 0xFFAAAAAA);

        int y = 55;
        int maxVisible = (this.height - 120) / 22;
        int start = Math.max(0, scrollOffset);
        int end = Math.min(files.size(), start + maxVisible);

        for (int i = start; i < end; i++) {
            Path f = files.get(i);
            String name = f.getFileName().toString();
            boolean selected = (i == selectedIndex);
            int rowY = y + (i - start) * 22;
            int bg = selected ? 0xFF4488FF : 0x40FFFFFF;
            g.fill(cx - 180, rowY, cx + 180, rowY + 18, bg);
            g.drawString(this.font, name, cx - 170, rowY + 4, 0xFFFFFFFF);
        }

    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int cx = this.width / 2;
        int y = 55;
        int maxVisible = (this.height - 120) / 22;
        int start = Math.max(0, scrollOffset);
        int end = Math.min(files.size(), start + maxVisible);
        for (int i = start; i < end; i++) {
            int rowY = y + (i - start) * 22;
            if (mx >= cx - 180 && mx <= cx + 180 && my >= rowY && my <= rowY + 18) {
                selectedIndex = i;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int maxVisible = (this.height - 120) / 22;
        int maxScroll = Math.max(0, files.size() - maxVisible);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) dy));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
