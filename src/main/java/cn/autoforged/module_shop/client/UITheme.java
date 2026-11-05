package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.client.screen.IOStyle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

public final class UITheme {
   private static final String FILE_NAME = "module_shop_ui_theme.txt";
   private static boolean loaded = false;
   private static boolean dark = true;

   private UITheme() {
   }

   private static Path configPath() {
      return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("module_shop_ui_theme.txt");
   }

   public static synchronized void ensureLoaded() {
      if (!loaded) {
         loaded = true;
         boolean saved = false;

         try {
            Path path = configPath();
            if (Files.exists(path)) {
               for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                  String trimmed = line.trim();
                  if (trimmed.startsWith("dark=")) {
                     dark = Boolean.parseBoolean(trimmed.substring("dark=".length()).trim());
                     saved = true;
                  }
               }
            }
         } catch (Exception var5) {
         }

         // 测试分支：恢复日间 / 暗色切换（默认暗色）。旧绘制路径只做过暗色，AtomLib 不可用时固定暗色。
         if (!saved || !cn.autoforged.module_shop.client.ui.AtomUi.available()) {
            dark = true;
         }
         IOStyle.applyTheme(dark);
      }
   }

   public static boolean isDark() {
      ensureLoaded();
      return dark;
   }

   public static void toggle() {
      setDark(!isDark());
   }

   public static void setDark(boolean value) {
      ensureLoaded();
      dark = value;
      IOStyle.animateTheme(dark);

      try {
         Path path = configPath();
         Files.createDirectories(path.getParent());
         Files.writeString(path, "# Module Shop UI theme (client side)\ndark=" + dark + System.lineSeparator(), StandardCharsets.UTF_8);
      } catch (Exception var2) {
      }
   }
}
