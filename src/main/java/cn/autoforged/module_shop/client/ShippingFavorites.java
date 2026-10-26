package cn.autoforged.module_shop.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * 出货箱的「标记物品」清单（客户端本地保存，和服务端的兑换规则无关）。
 *
 * <p>只记物品注册名，存在 {@code config/module_shop_shipping_favorites.txt}，
 * 用于在出货箱右侧的「可卖物品」清单里快速筛出自己关心的那几种。
 */
public final class ShippingFavorites {
    private static final String FILE_NAME = "module_shop_shipping_favorites.txt";
    private static final Set<String> MARKED = new LinkedHashSet<>();
    private static boolean loaded;

    private ShippingFavorites() {
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = configPath();
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        MARKED.add(trimmed);
                    }
                }
            }
        } catch (Exception ignored) {
            // 读不出来就当没有标记，不影响界面。
        }
    }

    public static boolean contains(String itemId) {
        ensureLoaded();
        return itemId != null && MARKED.contains(itemId);
    }

    public static void toggle(String itemId) {
        ensureLoaded();
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        if (!MARKED.remove(itemId)) {
            MARKED.add(itemId);
        }
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            StringBuilder text = new StringBuilder("# Module Shop shipping bin marks (client side)\n");
            for (String id : MARKED) {
                text.append(id).append('\n');
            }
            Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 存不下来只影响下次启动的标记列表。
        }
    }

    public static int size() {
        ensureLoaded();
        return MARKED.size();
    }
}
