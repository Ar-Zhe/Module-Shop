package cn.autoforged.module_shop.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 客户端界面字体与面板整体视觉缩放倍率（85% ~ 135%，步进 5%，默认 100%）。
 *
 * <p>持久化到 {@code config/module_shop_ui_font.txt}，与 {@link UITheme} / {@link UIViewMode} 一样纯客户端生效，
 * 修改后下一帧实时重排并结合视口边界夹紧，防止高倍率下出现文字溢出或重叠。
 */
public final class UIFontScale {
    public static final int MIN_PERCENT = 85;
    public static final int MAX_PERCENT = 135;
    public static final int DEFAULT_PERCENT = 100;
    public static final int STEP_PERCENT = 5;

    private static final int[] PRESETS = {85, 100, 110, 120, 135};
    private static final String FILE_NAME = "module_shop_ui_font.txt";

    private static volatile int percent = DEFAULT_PERCENT;
    private static volatile boolean loaded = false;

    private UIFontScale() {
    }

    private static Path configPath() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get();
            return dir == null ? null : dir.resolve(FILE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 首次读取客户端字号缩放配置（重复调用无开销）。 */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (UIFontScale.class) {
            if (loaded) {
                return;
            }
            loaded = true;
            Path path = configPath();
            if (path == null || !Files.isRegularFile(path)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    if (trimmed.startsWith("percent=")) {
                        int parsed = Integer.parseInt(trimmed.substring("percent=".length()).trim());
                        percent = clampAndSnap(parsed);
                        return;
                    }
                    if (trimmed.startsWith("scale=")) {
                        float parsed = Float.parseFloat(trimmed.substring("scale=".length()).trim());
                        percent = clampAndSnap(Math.round(parsed * 100.0F));
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static int percent() {
        ensureLoaded();
        return percent;
    }

    public static float scale() {
        return percent() / 100.0F;
    }

    public static String label() {
        return percent() + "%";
    }

    public static boolean isDefault() {
        return percent() == DEFAULT_PERCENT;
    }

    public static int[] presets() {
        return PRESETS.clone();
    }

    public static void setPercent(int next) {
        ensureLoaded();
        int clamped = clampAndSnap(next);
        if (percent == clamped) {
            return;
        }
        percent = clamped;
        save();
    }

    /** 按步进档位调整（deltaSteps = +1 / -1 即 +5% / -5%）。 */
    public static void step(int deltaSteps) {
        if (deltaSteps == 0) {
            return;
        }
        setPercent(percent() + deltaSteps * STEP_PERCENT);
    }

    public static void reset() {
        setPercent(DEFAULT_PERCENT);
    }

    public static int clampAndSnap(int rawPercent) {
        int clamped = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, rawPercent));
        int offset = clamped - MIN_PERCENT;
        int snapped = MIN_PERCENT + Math.round(offset / (float) STEP_PERCENT) * STEP_PERCENT;
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, snapped));
    }

    private static synchronized void save() {
        Path path = configPath();
        if (path == null) {
            return;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String content = "# Module Shop UI font & scale (client side): "
                    + MIN_PERCENT + "~" + MAX_PERCENT + " (step " + STEP_PERCENT + ", default " + DEFAULT_PERCENT + ")\n"
                    + "percent=" + percent + System.lineSeparator();
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
