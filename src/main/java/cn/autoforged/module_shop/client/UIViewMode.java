package cn.autoforged.module_shop.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/**
 * 客户端商店前端视图模式（列表视图 / 方块视图），仅作用于 6 个主交互前端页：
 * 每日商店 / 每日收购 / 购物城 / 拍卖会 / 玩家摊位 / 玩家求购。
 * 不影响管理后台或其他页面；与 {@link UITheme} 一样按客户端本地文件持久化。
 */
public final class UIViewMode {
    private static volatile boolean gridView = false;
    private static volatile boolean loaded = false;

    private UIViewMode() {
    }

    /** 首次打开商店前读取已保存的视图模式；重复调用安全。 */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
            gridView = "grid".equalsIgnoreCase(raw);
        } catch (IOException ignored) {
        }
    }

    /** 当前是否为方块（卡片网格）视图。 */
    public static boolean isGrid() {
        ensureLoaded();
        return gridView;
    }

    /** 切换视图模式并写回配置文件。 */
    public static void toggle() {
        setGrid(!isGrid());
    }

    /** 设置视图模式并写回配置文件。 */
    public static void setGrid(boolean grid) {
        loaded = true;
        if (gridView == grid) {
            return;
        }
        gridView = grid;
        save();
    }

    private static void save() {
        Path path = file();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, (gridView ? "grid" : "list") + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static Path file() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            return null;
        }
        return mc.gameDirectory.toPath().resolve("config").resolve("module_shop_ui_view.txt");
    }
}
