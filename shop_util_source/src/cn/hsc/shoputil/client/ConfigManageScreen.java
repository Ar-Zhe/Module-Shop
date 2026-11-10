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
 * 商店配置管理界面：三个标签页。
 * 0=定价文件, 1=参考配置, 2=商店守卫
 */
public class ConfigManageScreen extends Screen {

    private final Screen parent;
    private int tab = 0;

    private final List<Path> pricingFiles = new ArrayList<>();
    private int pricingSelected = -1;

    private final List<Path> refFiles = new ArrayList<>();
    private int refSelected = -1;

    public ConfigManageScreen(Screen parent) {
        super(Component.literal("商店配置管理"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        reloadLists();
        int cx = this.width / 2;
        int bottomY = this.height - 40;

        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(cx + 180, bottomY, 60, 20).build());

        if (tab == 0) {
            this.addRenderableWidget(Button.builder(Component.literal("使用选中定价"), b -> onUsePricing())
                    .bounds(cx - 280, bottomY, 120, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("重置为空"), b -> onResetPricing())
                    .bounds(cx - 150, bottomY, 90, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("导出定价"), b -> onExportPricing())
                    .bounds(cx - 50, bottomY, 80, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("导入CSV"), b -> onOpenPricingImport())
                    .bounds(cx + 40, bottomY, 90, 20).build());
        } else if (tab == 1) {
            this.addRenderableWidget(Button.builder(Component.literal("导出当前配置"), b -> onExportRef())
                    .bounds(cx - 280, bottomY, 130, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("导入外部文件"), b -> onOpenImport())
                    .bounds(cx - 140, bottomY, 120, 20).build());
        } else if (tab == 2) {
            // 商店守卫标签的按钮
            String lockLabel = GuardState.isUnlocked() ? "§c锁定管理员功能" : "§a解锁管理员功能";
            this.addRenderableWidget(Button.builder(Component.literal(lockLabel), b -> onToggleGuard())
                    .bounds(cx - 280, bottomY, 150, 20).build());
            String syncLabel = cn.hsc.shoputil.ShopUtilConfig.SYNC_ENABLED.get() ? "§a同步：已开启" : "§c同步：已关闭";
            this.addRenderableWidget(Button.builder(Component.literal(syncLabel), b -> onToggleSync())
                    .bounds(cx - 120, bottomY, 120, 20).build());
        }
    }

    private void reloadLists() {
        pricingFiles.clear(); refFiles.clear();
        pricingSelected = -1; refSelected = -1;

        Path pricingDir = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("pricing");
        try {
            Files.createDirectories(pricingDir);
            try (Stream<Path> s = Files.list(pricingDir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv")).forEach(pricingFiles::add);
            }
        } catch (Exception e) { Log.warn("列出定价文件失败: " + e.getMessage()); }
        String curPricing = cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.get();
        for (int i = 0; i < pricingFiles.size(); i++) {
            if (pricingFiles.get(i).getFileName().toString().equals(curPricing)) { pricingSelected = i; break; }
        }

        Path refDir = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("profiles");
        try {
            Files.createDirectories(refDir);
            try (Stream<Path> s = Files.list(refDir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dat")).forEach(refFiles::add);
            }
        } catch (Exception e) { Log.warn("列出参考配置失败: " + e.getMessage()); }
        String curProfile = cn.hsc.shoputil.ShopUtilConfig.SYNC_PROFILE.get();
        for (int i = 0; i < refFiles.size(); i++) {
            if (refFiles.get(i).getFileName().toString().equals(curProfile)) { refSelected = i; break; }
        }
    }

    private void onUsePricing() {
        if (pricingSelected >= 0 && pricingSelected < pricingFiles.size()) {
            String name = pricingFiles.get(pricingSelected).getFileName().toString();
            cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.set(name);
            cn.hsc.shoputil.ShopUtilConfig.save();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§a已选择定价文件: " + name + "，正在应用..."), false);
                mc.getConnection().sendCommand("shoputil reload");
            }
        }
    }

    private void onResetPricing() {
        cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.set("");
        cn.hsc.shoputil.ShopUtilConfig.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§e已重置为不注入定价"), false);
    }

    private void onExportPricing() {
        try {
            String current = cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.get();
            if (current.isEmpty()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.displayClientMessage(Component.literal("§c当前未选择定价文件"), false);
                return;
            }
            Path src = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("pricing").resolve(current);
            if (!java.nio.file.Files.isRegularFile(src)) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.displayClientMessage(Component.literal("§c定价文件不存在: " + current), false);
                return;
            }
            // 复制到 pricing/export_时间戳.csv
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            Path dst = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("pricing").resolve("export_" + stamp + ".csv");
            java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("§a已导出到: config/shop_util/pricing/export_" + stamp + ".csv"), false);
        } catch (Exception e) {
            Log.error("导出定价失败", e);
        }
    }

    private void onOpenPricingImport() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new PricingImportScreen(this));
    }

    private void onExportRef() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.connection.sendCommand("shoputil export");
            mc.player.displayClientMessage(Component.literal("§a正在导出当前存档配置..."), false);
        }
        onClose();
    }

    private void onOpenImport() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfigImportScreen(this));
    }

    private void onToggleGuard() {
        Minecraft mc = Minecraft.getInstance();
        if (GuardState.isUnlocked()) {
            GuardState.unlocked = false;
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("§c[商店守卫] 已锁定管理员功能"), false);
            mc.setScreen(null); // 关闭商店界面刷新UI
        } else {
            mc.setScreen(new PasswordScreen(mc.screen));
        }
    }

    private void onToggleSync() {
        boolean cur = cn.hsc.shoputil.ShopUtilConfig.SYNC_ENABLED.get();
        cn.hsc.shoputil.ShopUtilConfig.SYNC_ENABLED.set(!cur);
        cn.hsc.shoputil.ShopUtilConfig.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§e商店同步已" + (!cur ? "开启" : "关闭")), false);
        }
        rebuildWidgets();
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
        g.drawCenteredString(this.font, this.title, cx, 8, 0xFFFFFFFF);

        // 三个标签按钮
        int tabY = 20;
        int tabW = 100;
        int tabH = 16;
        int[] tabXs = { cx - tabW - 5, cx + 5, cx + tabW + 15 };
        String[] tabNames = { "定价文件", "参考配置", "商店守卫" };
        int[] tabColors = { 0xFF4488FF, 0xFF44AA55, 0xFFAA5544 };
        for (int t = 0; t < 3; t++) {
            int bg = (tab == t) ? tabColors[t] : 0x40000000;
            g.fill(tabXs[t], tabY, tabXs[t] + tabW, tabY + tabH, bg);
            g.drawCenteredString(this.font, tabNames[t], tabXs[t] + tabW / 2, tabY + 4, 0xFFFFFFFF);
        }

        int y = tabY + tabH + 10;

        if (tab == 0) {
            g.drawString(this.font, "当前定价: " + (cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.get().isEmpty() ? "（不注入）" : cn.hsc.shoputil.ShopUtilConfig.PRICING_FILE.get()), cx - 280, y, 0xFFAAAAAA);
            y += 12;
            for (int i = 0; i < pricingFiles.size(); i++) {
                String name = pricingFiles.get(i).getFileName().toString();
                boolean sel = (i == pricingSelected);
                g.fill(cx - 280, y, cx + 280, y + 16, sel ? 0xFF4488FF : 0x40FFFFFF);
                g.drawString(this.font, name, cx - 270, y + 3, 0xFFFFFFFF);
                y += 18;
            }
        } else if (tab == 1) {
            g.drawString(this.font, "当前使用: " + cn.hsc.shoputil.ShopUtilConfig.SYNC_PROFILE.get(), cx - 280, y, 0xFFAAAAAA);
            y += 12;
            for (int i = 0; i < refFiles.size(); i++) {
                String name = refFiles.get(i).getFileName().toString();
                boolean sel = (i == refSelected);
                g.fill(cx - 280, y, cx + 280, y + 16, sel ? 0xFF44AA55 : 0x40FFFFFF);
                g.drawString(this.font, name + (sel ? "  ← 当前使用" : ""), cx - 270, y + 3, 0xFFFFFFFF);
                y += 18;
            }
        } else if (tab == 2) {
            // 商店守卫状态
            boolean unlocked = GuardState.isUnlocked();
            String status = unlocked ? "§a● 已解锁（管理员功能可用）" : "§c● 已锁定（需要密码解锁）";
            g.drawString(this.font, status, cx - 280, y, 0xFFFFFFFF);
            y += 16;
            g.drawString(this.font, "同步开关: " + (cn.hsc.shoputil.ShopUtilConfig.SYNC_ENABLED.get() ? "§a开启" : "§c关闭"), cx - 280, y, 0xFFFFFFFF);
            y += 16;
            g.drawString(this.font, "密码: " + cn.hsc.shoputil.ShopUtilConfig.PASSWORD.get(), cx - 280, y, 0xFFAAAAAA);
            y += 16;
            g.drawString(this.font, "（密码可在 Configd 模组里修改）", cx - 280, y, 0xFF888888);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int cx = this.width / 2;
        int tabY = 20;
        int tabW = 100;
        int tabH = 16;
        int[] tabXs = { cx - tabW - 5, cx + 5, cx + tabW + 15 };

        for (int t = 0; t < 3; t++) {
            if (mx >= tabXs[t] && mx <= tabXs[t] + tabW && my >= tabY && my <= tabY + tabH) {
                if (tab != t) { tab = t; rebuildWidgets(); }
                return true;
            }
        }

        int y = tabY + tabH + 22;
        if (tab == 0) {
            for (int i = 0; i < pricingFiles.size(); i++) {
                int rowY = y + i * 18;
                if (mx >= cx - 280 && mx <= cx + 280 && my >= rowY && my <= rowY + 16) {
                    pricingSelected = i; return true;
                }
            }
        } else if (tab == 1) {
            for (int i = 0; i < refFiles.size(); i++) {
                int rowY = y + i * 18;
                if (mx >= cx - 280 && mx <= cx + 280 && my >= rowY && my <= rowY + 16) {
                    refSelected = i;
                    String name = refFiles.get(i).getFileName().toString();
                    cn.hsc.shoputil.ShopUtilConfig.SYNC_PROFILE.set(name);
                    cn.hsc.shoputil.ShopUtilConfig.save();
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal("§a已切换参考配置: " + name + "，正在应用..."), false);
                        mc.getConnection().sendCommand("shoputil reload");
                    }
                    return true;
                }
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
