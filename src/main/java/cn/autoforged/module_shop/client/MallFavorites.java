package cn.autoforged.module_shop.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * 购物城的「收藏」清单（客户端本地保存，不上传服务端）。
 *
 * <p>只记物品的注册名（{@code minecraft:diamond}），所以同一件物品在货架上被重新上架 / 改价后收藏依然在；
 * 文件放在 {@code config/module_shop_mall_favorites.txt}，一行一个 id，和服务端存档无关。
 */
public final class MallFavorites {
    private static final String FILE_NAME = "module_shop_mall_favorites.txt";
    private static final Set<String> FAVORITES = new LinkedHashSet<>();
    private static boolean loaded;

    private MallFavorites() {
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
                        FAVORITES.add(trimmed);
                    }
                }
            }
        } catch (Exception ignored) {
            // 读不出来就当没有收藏，不影响界面。
        }
    }

    public static boolean contains(String itemId) {
        ensureLoaded();
        return itemId != null && FAVORITES.contains(itemId);
    }

    public static boolean toggle(String itemId) {
        ensureLoaded();
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        boolean now;
        if (FAVORITES.remove(itemId)) {
            now = false;
        } else {
            FAVORITES.add(itemId);
            now = true;
        }
        save();
        return now;
    }

    public static int size() {
        ensureLoaded();
        return FAVORITES.size();
    }

    private static void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            StringBuilder text = new StringBuilder("# Module Shop mall favorites (client side)\n");
            for (String id : FAVORITES) {
                text.append(id).append('\n');
            }
            Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 存不下来只影响下次启动的收藏列表。
        }
    }
}
