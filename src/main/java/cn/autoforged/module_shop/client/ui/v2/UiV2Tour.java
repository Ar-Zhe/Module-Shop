package cn.autoforged.module_shop.client.ui.v2;

import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.ShopScreen;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.glfw.GLFW;

/**
 * 开发期 UI v2 巡检（-Dmoduleshop.uitour=true 才启用）：进世界后先 /shop 拉一次数据，
 * 然后在几组「窗口大小 × GUI 缩放」下逐个打开商店各页签截图到 {@code run/autoshot/screenshots/v2-*.png}。
 * -Dmoduleshop.uiproto=true 时改为截原型界面；-Dmoduleshop.tourquit=true 跑完自动退出。
 * -Dmoduleshop.tourtabs=mall,daily 只截指定页签。
 */
public final class UiV2Tour {
    /** {窗口宽, 窗口高, GUI 缩放（0 = 自动）}。 */
    private static final int[][] PASSES = {{1920, 1080, 0}, {1280, 720, 0}, {854, 480, 0}};
    private static final String[] TABS = {"daily", "buyback", "mall", "shipping", "auction", "stall", "demand", "mail",
            "exchange", "signin", "trade", "transfer", "admin"};
    private static final StringBuilder LOG = new StringBuilder();
    private static final List<Runnable> STEPS = new ArrayList<>();
    private static final List<String> NAMES = new ArrayList<>();
    private static int tick = -1;
    private static int pass;
    private static int step;
    private static int wait;
    private static boolean primed;
    private static boolean seeded;
    private static boolean finished;
    private static String pendingShot;

    private UiV2Tour() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("moduleshop.uitour");
    }

    public static void tick(Minecraft mc) {
        if (finished || mc.player == null || mc.level == null) {
            return;
        }
        if (++tick < 80) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (!primed) {
            // 先正常打开一次商店，让服务端推送快照（各页数据）
            primed = true;
            mc.player.connection.sendCommand("shop");
            wait = 60;
            return;
        }
        if (!seeded && Boolean.getBoolean("moduleshop.tourfake")) {
            seeded = true;
            seedFakeData();
        }
        if (pendingShot != null) {
            capture(mc, pendingShot);
            pendingShot = null;
            wait = 3;
            return;
        }
        if (step == 0) {
            int[] p = PASSES[pass];
            if (mc.getWindow().isFullscreen()) {
                mc.getWindow().toggleFullScreen();
            }
            mc.setScreen(null);
            GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), p[0], p[1]);
            mc.options.guiScale().set(p[2]);
            mc.resizeDisplay();
            buildSteps(mc);
            step = 1;
            wait = 30;
            return;
        }
        int index = step - 1;
        if (index < STEPS.size()) {
            String name = NAMES.get(index);
            try {
                STEPS.get(index).run();
            } catch (Throwable t) {
                LOG.append("step ").append(name).append(" failed: ").append(t).append('\n');
            }
            pendingShot = String.format("v2-p%d-%s", pass, name);
            step++;
            wait = Boolean.getBoolean("moduleshop.uiproto") ? 70 : (name.startsWith("module_shop_") ? 80 : 24);
            return;
        }
        mc.setScreen(null);
        pass++;
        step = 0;
        if (pass >= PASSES.length) {
            finish(mc);
        }
    }

    private static void buildSteps(Minecraft mc) {
        STEPS.clear();
        NAMES.clear();
        if (Boolean.getBoolean("moduleshop.uiproto")) {
            add("proto", () -> mc.setScreen(new ShopV2ProtoScreen()));
            return;
        }
        String only = System.getProperty("moduleshop.tourtabs", "");
        for (String tab : TABS) {
            if (!only.isEmpty() && !List.of(only.split(",")).contains(tab)) {
                continue;
            }
            add(tab, () -> mc.setScreen(new ShopScreen(tab)));
        }
        // 附属页面（ShopModules 注册的内容区页面）
        for (var entry : cn.autoforged.module_shop.api.client.ShopModules.entries()) {
            String id = entry.id();
            if (!only.isEmpty() && !List.of(only.split(",")).contains(id)) {
                continue;
            }
            add(id, () -> mc.setScreen(new ShopScreen(id)));
        }
        if (only.isEmpty()) {
            add("dlg-bill", () -> mc.setScreen(new cn.autoforged.module_shop.client.screen.BillScreen()));
            add("dlg-confirm", () -> mc.setScreen(new cn.autoforged.module_shop.client.screen.ShopConfirmScreen(
                    net.minecraft.network.chat.Component.literal("确认购买"),
                    net.minecraft.network.chat.Component.literal("购买 钻石 ×8，共 960 星币？"), ok -> {
                    })));
            add("light", () -> {
                IOStyle.applySkin("light");
                mc.setScreen(new ShopScreen("mall"));
            });
            add("dark", () -> {
                IOStyle.applySkin("dark");
                mc.setScreen(new ShopScreen("mall"));
            });
        }
    }

    /** 测试世界里购物城是空的：在客户端塞一批演示商品（只用于截图，购买会被服务端拒绝）。 */
    private static void seedFakeData() {
        if (!cn.autoforged.module_shop.client.ClientShopData.mall.isEmpty()) {
            return;
        }
        int i = 0;
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (item == net.minecraft.world.item.Items.AIR || (i++ % 9) != 4) {
                continue;
            }
            var v = new cn.autoforged.module_shop.client.ClientShopData.MallView();
            int h = Math.abs(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).hashCode());
            v.shelf = cn.autoforged.module_shop.client.ClientShopData.mall.size();
            v.stack = new net.minecraft.world.item.ItemStack(item);
            v.rarity = cn.autoforged.module_shop.data.Rarity.values()[h % 4];
            v.price = 1 + h % (v.rarity.ordinal() == 0 ? 9000 : 400);
            v.stock = h % 13 == 0 ? 0 : h % 4 == 0 ? -1 : 1 + h % 300;
            v.sort = v.shelf;
            cn.autoforged.module_shop.client.ClientShopData.mall.add(v);
            if (cn.autoforged.module_shop.client.ClientShopData.mall.size() >= 120) {
                break;
            }
        }
        LOG.append("seeded mall=").append(cn.autoforged.module_shop.client.ClientShopData.mall.size()).append("\n");
    }

    private static void add(String name, Runnable r) {
        NAMES.add(name);
        STEPS.add(r);
    }

    private static void capture(Minecraft mc, String name) {
        File dir = new File(mc.gameDirectory, "autoshot");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        var win = mc.getWindow();
        LOG.append(name).append("  window=").append(win.getWidth()).append('x').append(win.getHeight())
                .append(" gui=").append((int) win.getGuiScale())
                .append(" screen=").append(mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()).append('\n');
        Screenshot.grab(dir, name + ".png", mc.getMainRenderTarget(), c -> {
        });
    }

    private static void finish(Minecraft mc) {
        finished = true;
        File dir = new File(mc.gameDirectory, "autoshot");
        dir.mkdirs();
        try {
            Files.writeString(new File(dir, "v2-report.txt").toPath(), LOG.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        if (Boolean.getBoolean("moduleshop.tourquit")) {
            mc.stop();
        }
    }
}
