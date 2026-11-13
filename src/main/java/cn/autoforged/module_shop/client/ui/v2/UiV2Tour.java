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
        if (System.getProperty("moduleshop.tourgrid") != null) {
            cn.autoforged.module_shop.client.UIViewMode.setGrid(Boolean.getBoolean("moduleshop.tourgrid"));
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
        if (Boolean.getBoolean("moduleshop.tourfake") && (only.isEmpty() || List.of(only.split(",")).contains("stall"))) {
            add("stall-hall", () -> {
                cn.autoforged.module_shop.client.StallClient.hallView = true;
                mc.setScreen(new ShopScreen("stall"));
            });
            add("stall-mine", () -> {
                cn.autoforged.module_shop.client.StallClient.hallView = false;
                mc.setScreen(new ShopScreen("stall"));
            });
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

    /** 测试世界里商店为空时：在客户端塞一批演示商品（只在 tourfake=true 下用于截图）。 */
    private static void seedFakeData() {
        var data = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        int i = 0;
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (item == net.minecraft.world.item.Items.AIR || (i++ % 9) != 4) {
                continue;
            }
            data.add(new net.minecraft.world.item.ItemStack(item));
            if (data.size() >= 120) {
                break;
            }
        }
        if (cn.autoforged.module_shop.client.ClientShopData.mall.isEmpty()) {
            for (int j = 0; j < data.size(); j++) {
                var st = data.get(j);
                var v = new cn.autoforged.module_shop.client.ClientShopData.MallView();
                int h = Math.abs(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).hashCode());
                v.shelf = j;
                v.stack = st;
                v.rarity = cn.autoforged.module_shop.data.Rarity.values()[h % 4];
                v.price = 1 + h % (v.rarity.ordinal() == 0 ? 9000 : 400);
                v.stock = h % 13 == 0 ? 0 : h % 4 == 0 ? -1 : 1 + h % 300;
                v.sort = v.shelf;
                cn.autoforged.module_shop.client.ClientShopData.mall.add(v);
            }
        }
        if (cn.autoforged.module_shop.client.ClientShopData.dailyShop.isEmpty()) {
            for (int j = 0; j < Math.min(10, data.size()); j++) {
                var e = new cn.autoforged.module_shop.data.DailyShopEntry(
                        data.get(j), 40L + j * 35L, cn.autoforged.module_shop.data.Rarity.values()[j % 4]);
                e.limit = 5;
                e.bought = j == 3 ? 5 : j % 3;
                cn.autoforged.module_shop.client.ClientShopData.dailyShop.add(e);
                if (j < 6) {
                    cn.autoforged.module_shop.client.ClientShopData.dailyFlipped.add(j);
                }
            }
        }
        if (cn.autoforged.module_shop.client.ClientShopData.dailyBuyback.isEmpty()) {
            for (int j = 0; j < Math.min(10, data.size()); j++) {
                var e = new cn.autoforged.module_shop.data.DailyBuybackEntry(data.get(j), 25L + j * 15L);
                e.rarity = cn.autoforged.module_shop.data.Rarity.values()[j % 4];
                e.limit = 32;
                e.sold = j == 2 ? 32 : j * 3;
                cn.autoforged.module_shop.client.ClientShopData.dailyBuyback.add(e);
            }
        }
        if (cn.autoforged.module_shop.client.ClientShopData.auctions.isEmpty()) {
            long now = System.currentTimeMillis();
            for (int j = 0; j < Math.min(8, data.size()); j++) {
                var a = new cn.autoforged.module_shop.client.ClientShopData.AuctionView();
                a.id = j + 1L;
                a.stack = data.get(j);
                a.seller = j == 0 ? "Me" : "Player" + j;
                a.startPrice = 100L + j * 50L;
                a.currentBid = j % 2 == 0 ? 150L + j * 60L : 0L;
                a.minNext = (a.currentBid > 0 ? a.currentBid : a.startPrice) + 20L;
                a.buyout = j % 3 == 0 ? 0L : 600L + j * 100L;
                a.bidders = j % 3;
                a.bidCount = j % 5;
                a.topBidder = a.currentBid > 0 ? (j == 2 ? "Me" : "Bidder" + j) : "";
                a.own = j == 0;
                a.topMe = j == 2;
                a.iBid = j == 4;
                a.remaining = (20L + j * 15L) * 60_000L;
                a.receivedAt = now;
                cn.autoforged.module_shop.client.ClientShopData.auctions.add(a);
            }
        }
        if (cn.autoforged.module_shop.client.StallClient.market.isEmpty()) {
            for (int j = 0; j < Math.min(10, data.size()); j++) {
                var it = new cn.autoforged.module_shop.client.StallClient.Item();
                it.id = j + 1L;
                it.owner = java.util.UUID.nameUUIDFromBytes(("stall" + j).getBytes());
                it.ownerName = "Vendor" + j;
                it.stack = data.get(j);
                it.price = 50L + j * 20L;
                it.stock = j == 4 ? 0 : 12 + j;
                it.sold = j * 2;
                it.own = j == 0;
                cn.autoforged.module_shop.client.StallClient.market.add(it);
            }
        }
        if (cn.autoforged.module_shop.client.ClientShopData.stall.isEmpty()) {
            cn.autoforged.module_shop.client.StallClient.slots = 12;
            cn.autoforged.module_shop.client.StallClient.maxStock = 64;
            for (int j = 0; j < Math.min(4, data.size()); j++) {
                var se = new cn.autoforged.module_shop.data.StallEntry(data.get(j), 80L + j * 25L, j + 1L, j == 2 ? 0 : 16 + j * 8);
                se.sold = j * 5;
                cn.autoforged.module_shop.client.ClientShopData.stall.add(se);
            }
        }
        if (cn.autoforged.module_shop.client.ClientShopData.demands.isEmpty()) {
            cn.autoforged.module_shop.client.ClientShopData.demandEnabled = true;
            for (int j = 0; j < Math.min(6, data.size()); j++) {
                var d = new cn.autoforged.module_shop.data.DemandEntry();
                d.id = "dem" + j;
                d.requester = java.util.UUID.nameUUIDFromBytes(("req" + j).getBytes());
                d.requesterName = "Buyer" + j;
                d.wants.add(new cn.autoforged.module_shop.data.DemandEntry.WantLine(data.get(j), 4 + j));
                if (j % 2 == 0 && j + 1 < data.size()) {
                    d.wants.add(new cn.autoforged.module_shop.data.DemandEntry.WantLine(data.get(j + 1), 2));
                }
                d.rewardMoney = 200L + j * 80L;
                cn.autoforged.module_shop.client.ClientShopData.demands.add(d);
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
