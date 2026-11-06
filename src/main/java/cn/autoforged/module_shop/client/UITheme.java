package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.client.screen.IOStyle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/**
 * 客户端界面皮肤（日间 / 夜间 / 柔彩系列：樱糖 / 薄荷 / 雾蓝 / 奶茶 / 夜樱），保存在 config/module_shop_ui_theme.txt。
 * 旧版本只存了 {@code dark=true/false}，读取时兼容。
 */
public final class UITheme {
   private static final String FILE_NAME = "module_shop_ui_theme.txt";
   private static boolean loaded = false;
   private static String skin = "dark";

   private UITheme() {
   }

   private static Path configPath() {
      return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
   }

   public static synchronized void ensureLoaded() {
      if (!loaded) {
         loaded = true;
         String saved = null;

         try {
            Path path = configPath();
            if (Files.exists(path)) {
               for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                  String trimmed = line.trim();
                  if (trimmed.startsWith("skin=")) {
                     saved = trimmed.substring("skin=".length()).trim();
                  } else if (saved == null && trimmed.startsWith("dark=")) {
                     saved = Boolean.parseBoolean(trimmed.substring("dark=".length()).trim()) ? "dark" : "light";
                  }
               }
            }
         } catch (Exception ignored) {
         }

         // 旧绘制路径只做过暗色：AtomLib 不可用时固定夜间
         if (saved == null || !cn.autoforged.module_shop.client.ui.AtomUi.available()) {
            saved = "dark";
         }
         IOStyle.applySkin(saved);
         skin = IOStyle.currentSkin();
      }
   }

   public static String skin() {
      ensureLoaded();
      return skin;
   }

   public static boolean isDark() {
      ensureLoaded();
      return IOStyle.dark;
   }

   /** 旧接口：在日间 / 夜间之间切换。 */
   public static void toggle() {
      setSkin(isDark() ? "light" : "dark");
   }

   public static void setDark(boolean value) {
      setSkin(value ? "dark" : "light");
   }

   /** 切换皮肤（播放过渡）并保存。 */
   public static void setSkin(String id) {
      ensureLoaded();
      IOStyle.animateSkin(id);
      skin = IOStyle.currentSkin();

      try {
         Path path = configPath();
         Files.createDirectories(path.getParent());
         Files.writeString(path, "# Module Shop UI skin (client side): "
               + "light / dark / rose / sage / haze / oat / dusk\nskin=" + skin + System.lineSeparator(),
               StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }
   }
}
