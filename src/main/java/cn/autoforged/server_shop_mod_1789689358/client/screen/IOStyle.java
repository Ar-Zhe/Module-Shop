package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.client.UITheme;
import cn.autoforged.server_shop_mod_1789689358.data.Rarity;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 模组统一视觉系统（第八轮重做的「新 UI」设计层）。
 *
 * <h2>设计方向</h2>
 * 冷调深色 · 单一强调色 · 玻璃质感 · 有生命的交互。
 * <ul>
 *     <li><b>底色</b>：带蓝调的近黑（{@code #0F1218}），面板是<b>纵向渐变</b> + <b>1px 内高光</b> + <b>冷调投影</b>
 *     （阴影带背景色相，而不是纯黑），做出"悬浮玻璃板"的层次；</li>
 *     <li><b>强调色只有一个</b>：去饱和的靛蓝 {@code #6E8BFF}，其余（绿/金/红/紫）只做语义；</li>
 *     <li><b>圆角分级</b>：面板 14 / 卡片 10 / 控件 8 / 小片 6，不再是"全都 8"；</li>
 *     <li><b>动效</b>：悬停上浮 + 边缘描边点亮、按下缩放 0.97 + 以点击点为圆心的水波扩散、
 *     页签选中色块渐变生长、金额变化时数字闪烁——全部用指数趋近做平滑，帧率波动也不会突变。</li>
 * </ul>
 *
 * <p>所有方法都保持原有签名（新增的都是重载/新方法），因此 15 个界面无需改动即可吃到新皮肤。
 */
public final class IOStyle {
   // =========================================================== 调色板（OneConfig 深色风格）
   // 参考 OneConfig（深色）：半透明近黑窗口 + 半透明白卡片 + 1px 浅描边 + 唯一强调色 #2B4BFF。
   public static boolean dark = false;
   public static int TEXT = 0xFFF3F5F9;
   public static int TEXT_2 = 0xFF9AA4B5;
   public static int TEXT_3 = 0xFF6C7585;
   /** 窗口底色（半透明近黑，透出后面的世界）。 */
   public static int SHEET = 0xFF0B0D13;
   /** 侧栏底色（比内容区更深一点，形成分区）。 */
   public static int SIDEBAR = 0xFF080A0F;
   /** 内容区底色。 */
   public static int CONTENT = 0xFF12151C;
   /** 卡片：半透明白（OneConfig 的卡片就是"白 8%~10%"叠在窗口上）。 */
   public static int CARD = 0xFF1A1E27;
   public static int HAIR = 0xFF262C3A;
   public static int HAIR_DARK = 0xFF262C3A;
   /** 唯一强调色（取自 OneConfig 的 Accent Color 默认值 #2B4BFF）。 */
   public static int BLUE = 0xFF2B4BFF;
   public static int BLUE_HOVER = 0xFF4C67FF;
   public static int BLUE_TINT = 0xFF1B2A6B;
   public static int BLUE_TINT_HOVER = 0xFF243689;
   public static int ORANGE = 0xFFE0B15E;
   public static int GREEN = 0xFF52C48A;
   public static int GREEN_HOVER = 0xFF6FD8A2;
   public static int GREEN_DARK = 0xFF3E9E6E;
   public static int GREEN_TINT = 0xFF16321F;
   public static int GREEN_TINT_HOVER = 0xFF1E4429;
   public static int RED = 0xFFE5645F;
   public static int RED_HOVER = 0xFFF07C77;
   public static int RED_TINT = 0xFF3A1D1C;
   public static int RED_TINT_HOVER = 0xFF4E2725;
   /** 普通按钮：半透明白药丸（OneConfig 未选中项就是这层白）。 */
   public static int GRAY_BTN = 0xFF262B36;
   public static int GRAY_BTN_HOVER = 0xFF333A48;
   public static int FIELD_BG = 0xFF0A0C11;
   public static int FIELD_GRAY = 0xFF262C3A;
   public static int DISABLED_TEXT = 0xFF6C7585;
   public static int DISABLED_BG = 0xFF171A21;
   public static int ROW_HOVER = 0xFF222735;
   public static int EPIC = 0xFFB07CE8;
   public static int SLOT_BORDER = 0xFF333A48;
   public static int SLOT_INNER = 0xFF0A0C11;
   public static int SEG_TRACK = 0xFF1A1E27;
   public static int SEG_SLIDER = 0xFF2B4BFF;
   public static int SEG_OFF = 0xFF9AA4B5;
   public static int TOGGLE_OFF = 0xFF262B36;
   public static int TOGGLE_OFF_RING = 0xFF3A4152;
   public static int KNOB = 0xFFF7F9FC;
   public static int KNOB_RING = 0x33000000;
   public static int TAB_BG = 0xFF1A1E27;
   public static int TAB_BG_HOVER = 0xFF222735;
   public static int TAB_BORDER = 0xFF262C3A;
   public static int TAB_BORDER_HOVER = 0xFF3A4152;
   public static int TAB_SEL_GLOW = 0x552B4BFF;
   public static int TAB_SEL_BORDER = 0xFF2B4BFF;
   public static int DONE_WASH = 0x8820242C;
   public static int SHADOW_1 = 0x33000000;
   public static int SHADOW_2 = 0x44000000;
   public static int SHADOW_3 = 0x55000000;

   // ---- 扁平直角风：1px 硬边框色 ----
   /** 常态边框。 */
   public static int BORDER = 0xFF3A4152;
   /** 悬停 / 强调边框。 */
   public static int BORDER_HOVER = 0xFF5A6BFF;
   /** 更暗的边框（禁用态 / 小控件）。 */
   public static int BORDER_DIM = 0xFF262C3A;

   // ---- 渐变端点 / 内高光 / 玻璃边缘 / 焦点 ----
   /** 面板纵向渐变（上亮下暗，幅度很小，主要靠卡片分层）。 */
   public static int SHEET_TOP = 0xFF0B0D13;
   public static int SHEET_BOTTOM = 0xFF0B0D13;
   /** 卡片纵向渐变。 */
   public static int CARD_TOP = 0xFF1A1E27;
   public static int CARD_BOTTOM = 0xFF1A1E27;
   /** 1px 内高光（模拟玻璃上边缘的反光）。 */
   public static int INNER_LIGHT = 0x1AFFFFFF;
   /** 冷调投影（带背景色相的深蓝黑）。 */
   public static int SHADOW_TINT = 0x66000000;
   /** 键盘焦点环。 */
   public static int FOCUS = 0x992B4BFF;

   private static final int WHITE = 0xFFFFFFFF;
   public static final int PANEL_R = 14;
   public static final int TOGGLE_W = 38;
   public static final int TOGGLE_H = 22;

   private IOStyle() {
   }

   static {
      // 默认按深色主题初始化；真实主题由 UITheme 读取客户端 config/server_shop_ui_theme.txt 后覆盖。
      applyTheme(true);
   }

   public static void applyTheme(boolean toDark) {
      dark = true;
      // 只保留这一套深色主题（OneConfig 风格）；衍生色统一在这里算，避免各处硬编码。
      BLUE_TINT = 0xFF1B2A6B;
      BLUE_TINT_HOVER = 0xFF243689;
      GREEN_TINT = 0xFF16321F;
      GREEN_TINT_HOVER = 0xFF1E4429;
      RED_TINT = 0xFF3A1D1C;
      RED_TINT_HOVER = 0xFF4E2725;
      ROW_HOVER = 0xFF222735;
   }

   // =========================================================== 基础工具

   public static float[] gearTint(boolean hover) {
      return hover ? new float[]{0.43F, 0.55F, 1.0F} : new float[]{0.70F, 0.74F, 0.82F};
   }

   public static int withAlpha(int rgb, int alpha) {
      return (alpha & 0xFF) << 24 | rgb & 16777215;
   }

   public static int mix(int a, int b, float t) {
      int aa = a >>> 24 & 0xFF;
      int ar = a >> 16 & 0xFF;
      int ag = a >> 8 & 0xFF;
      int ab = a & 0xFF;
      int ba = b >>> 24 & 0xFF;
      int br = b >> 16 & 0xFF;
      int bg = b >> 8 & 0xFF;
      int bb = b & 0xFF;
      int ca = Math.round((float) aa + (float) (ba - aa) * t);
      int cr = Math.round((float) ar + (float) (br - ar) * t);
      int cg = Math.round((float) ag + (float) (bg - ag) * t);
      int cb = Math.round((float) ab + (float) (bb - ab) * t);
      return ca << 24 | cr << 16 | cg << 8 | cb;
   }

   /** 缓出曲线（用于位移动画）。 */
   public static float easeOut(float t) {
      float x = Math.max(0.0F, Math.min(1.0F, t));
      return 1.0F - (1.0F - x) * (1.0F - x) * (1.0F - x);
   }

   public static boolean inside(int x, int y, int w, int h, double mx, double my) {
      return mx >= (double) x && mx < (double) (x + w) && my >= (double) y && my < (double) (y + h);
   }

   // =========================================================== 圆角 / 渐变底

   public static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, int argb) {
      rounded(g, x, y, w, h, r, argb, false);
   }

   public static void ring(GuiGraphics g, int x, int y, int w, int h, int r, int argb) {
      rounded(g, x, y, w, h, r, argb, true);
   }

   /** 纵向渐变圆角块（上浅下深）：面板与卡片用它做"受光面"。 */
   public static void gradient(GuiGraphics g, int x, int y, int w, int h, int r, int topColor, int bottomColor) {
      // 扁平风：不再做渐变，直接铺上色。
      rounded(g, x, y, w, h, r, topColor);
   }

   private static void gradientLegacy(GuiGraphics g, int x, int y, int w, int h, int r, int topColor, int bottomColor) {
      if (w <= 0 || h <= 0) {
         return;
      }
      int rows = h;
      for (int row = 0; row < rows; row++) {
         float t = rows <= 1 ? 0.0F : (float) row / (float) (rows - 1);
         int color = mix(topColor, bottomColor, t);
         int r2 = Math.max(0, Math.min(r, Math.min(w, h) / 2));
         float dy;
         if (row < r2) {
            dy = (float) r2 - ((float) row + 0.5F);
         } else if (row >= h - r2) {
            dy = (float) r2 - ((float) (h - 1 - row) + 0.5F);
         } else {
            dy = -1.0F;
         }
         if (dy < 0.0F) {
            g.fill(x, y + row, x + w, y + row + 1, color);
         } else {
            float insetF = (float) r2 - (float) Math.sqrt(Math.max(0.0F, (float) r2 * r2 - dy * dy));
            int inset = (int) Math.floor(insetF);
            g.fill(x + inset + 1, y + row, x + w - inset - 1, y + row + 1, color);
            float frac = insetF - (float) inset;
            if (frac > 0.01F) {
               int aa = Math.round((float) (color >>> 24 & 0xFF) * frac) << 24 | color & 16777215;
               g.fill(x + inset, y + row, x + inset + 1, y + row + 1, aa);
               g.fill(x + w - 1 - inset, y + row, x + w - inset, y + row + 1, aa);
            }
         }
      }
   }

   /** 顶部 1px 内高光（玻璃上边缘反光）。 */
   public static void topHighlight(GuiGraphics g, int x, int y, int w, int r, int alpha) {
      // 扁平风：取消内高光。
   }

   private static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, int argb, boolean outline) {
      // 直角扁平风：忽略圆角参数，一律画方块（描边就是 1px 硬边框）。
      if (w <= 0 || h <= 0) {
         return;
      }
      if (outline) {
         if (w == 1 && h == 1) {
            g.fill(x, y, x + 1, y + 1, argb);
            return;
         }
         g.fill(x, y, x + w, y + 1, argb);
         g.fill(x, y + h - 1, x + w, y + h, argb);
         g.fill(x, y + 1, x + 1, y + h - 1, argb);
         g.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
         return;
      }
      g.fill(x, y, x + w, y + h, argb);
   }

   /** 旧的逐行圆角实现（保留但不再使用；万一以后想回圆角风格可直接切回）。 */
   private static void roundedLegacy(GuiGraphics g, int x, int y, int w, int h, int r, int argb, boolean outline) {
      if (w > 0 && h > 0) {
         r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
         int alpha = argb >>> 24 & 0xFF;
         int rgb = argb & 16777215;

         for (int row = 0; row < h; row++) {
            float dy;
            if (row < r) {
               dy = (float) r - ((float) row + 0.5F);
            } else if (row >= h - r) {
               dy = (float) r - ((float) (h - 1 - row) + 0.5F);
            } else {
               dy = -1.0F;
            }

            if (dy < 0.0F) {
               if (outline) {
                  g.fill(x, y + row, x + 1, y + row + 1, argb);
                  g.fill(x + w - 1, y + row, x + w, y + row + 1, argb);
               } else {
                  g.fill(x, y + row, x + w, y + row + 1, argb);
               }
            } else {
               float insetF = (float) r - (float) Math.sqrt((double) Math.max(0.0F, (float) r * (float) r - dy * dy));
               int inset = (int) Math.floor((double) insetF);
               float frac = insetF - (float) inset;
               if (outline) {
                  g.fill(x + inset, y + row, x + inset + 2, y + row + 1, argb);
                  g.fill(x + w - inset - 2, y + row, x + w - inset, y + row + 1, argb);
               } else {
                  g.fill(x + inset + 1, y + row, x + w - inset - 1, y + row + 1, alpha << 24 | rgb);
                  if (frac > 0.01F) {
                     int aa = Math.round((float) alpha * frac);
                     int aaColor = aa << 24 | rgb;
                     g.fill(x + inset, y + row, x + inset + 1, y + row + 1, aaColor);
                     g.fill(x + w - 1 - inset, y + row, x + w - inset, y + row + 1, aaColor);
                  }
               }
            }
         }
      }
   }

   // =========================================================== 动效状态（全局，无需各界面接线）

   private static final Map<String, Float> HOVER_ANIM = new HashMap<>();
   private static final Map<String, Float> PRESS_ANIM = new HashMap<>();
   private static final Map<String, Long> RIPPLE_AT = new HashMap<>();
   private static final Map<String, int[]> RIPPLE_POS = new HashMap<>();
   private static final Map<String, Float> SELECT_ANIM = new HashMap<>();
   private static final Map<String, Long> FLASH_AT = new HashMap<>();
   /** 正处于"按住"状态的控件 key（用于识别按下瞬间以触发水波）。 */
   private static final java.util.Set<String> PRESSED = new java.util.HashSet<>();

   private static boolean leftDown() {
      Minecraft mc = Minecraft.getInstance();
      return mc != null && mc.mouseHandler != null && mc.mouseHandler.isLeftPressed();
   }

   /** 鼠标左键是否按住（自绘按钮做按下反馈用）。 */
   public static boolean mouseDown() {
      return leftDown();
   }

   /** 当前鼠标在 GUI 坐标系中的位置：{x, y}（自绘控件据此判断悬停，无需各界面自己传参）。 */
   public static double[] mouseGui() {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getWindow() == null || mc.mouseHandler == null) {
         return new double[]{-1.0D, -1.0D};
      }
      double guiW = mc.getWindow().getGuiScaledWidth();
      double guiH = mc.getWindow().getGuiScaledHeight();
      double x = mc.mouseHandler.xpos() * guiW / Math.max(1.0D, mc.getWindow().getScreenWidth());
      double y = mc.mouseHandler.ypos() * guiH / Math.max(1.0D, mc.getWindow().getScreenHeight());
      return new double[]{x, y};
   }

   /** 记录某控件的"按下瞬间"：第一次返回 true（用于触发水波），按住期间只返回一次 true。 */
   public static boolean markPressed(String key) {
      if (!leftDown()) {
         PRESSED.remove(key);
         return false;
      }
      return PRESSED.add(key);
   }

   /** 指数趋近：每帧朝目标靠拢，不受帧率突变影响（弹簧感）。 */
   private static float approach(Map<String, Float> map, String key, float target, float speed) {
      float current = map.getOrDefault(key, target);
      float next = current + (target - current) * speed;
      if (Math.abs(next - target) < 0.001F) {
         next = target;
      }
      map.put(key, next);
      return next;
   }

   /** 悬停进度 0→1（用于上浮、描边点亮）。 */
   public static float hoverProgress(String key, boolean hover) {
      return approach(HOVER_ANIM, key, hover ? 1.0F : 0.0F, 0.35F);
   }

   /** 按下进度 0→1（用于缩放 0.97 与压暗）。 */
   public static float pressProgress(String key, boolean pressed) {
      return approach(PRESS_ANIM, key, pressed ? 1.0F : 0.0F, pressed ? 0.55F : 0.30F);
   }

   /** 选中进度 0→1（用于页签选中块渐变生长）。 */
   public static float selectProgress(String key, boolean selected) {
      return approach(SELECT_ANIM, key, selected ? 1.0F : 0.0F, 0.30F);
   }

   /** 触发一次水波（点击瞬间调用；position 为点击处，用于确定圆心）。 */
   public static void ripple(String key, int mx, int my) {
      RIPPLE_AT.put(key, System.currentTimeMillis());
      RIPPLE_POS.put(key, new int[]{mx, my});
   }

   /** 触发一次"数值变化"闪烁。 */
   public static void flash(String key) {
      FLASH_AT.put(key, System.currentTimeMillis());
   }

   /** 闪烁进度 1→0（配合 flash 使用）。 */
   public static float flashProgress(String key, long durationMs) {
      Long at = FLASH_AT.get(key);
      if (at == null) {
         return 0.0F;
      }
      float t = (float) (System.currentTimeMillis() - at) / (float) durationMs;
      if (t >= 1.0F) {
         FLASH_AT.remove(key);
         return 0.0F;
      }
      return 1.0F - easeOut(t);
   }

   /**
    * 在圆角矩形内画一枚从点击点扩散的水波（300ms）。
    * 用 scissor 裁剪到按钮范围内，所以水波不会溢出控件。
    */
   public static void drawRipple(GuiGraphics g, String key, int x, int y, int w, int h, int r, int color) {
      // 扁平风：取消水波扩散。
      if (true) {
         return;
      }
      Long at = RIPPLE_AT.get(key);
      int[] pos = RIPPLE_POS.get(key);
      if (at == null || pos == null) {
         return;
      }
      float t = (float) (System.currentTimeMillis() - at) / 300.0F;
      if (t >= 1.0F) {
         RIPPLE_AT.remove(key);
         RIPPLE_POS.remove(key);
         return;
      }
      float grow = easeOut(t);
      float maxR = (float) Math.max(w, h) * 0.75F;
      float radius = Math.max(2.0F, maxR * grow);
      int alpha = Math.round(70.0F * (1.0F - t));
      if (alpha <= 2) {
         return;
      }
      int cx = Math.max(x, Math.min(x + w, pos[0]));
      int cy = Math.max(y, Math.min(y + h, pos[1]));
      g.enableScissor(x, y, x + w, y + h);
      int span = (int) radius;
      for (int row = -span; row <= span; row++) {
         int py = cy + row;
         if (py < y || py >= y + h) {
            continue;
         }
         float dx = radius * radius - (float) row * (float) row;
         if (dx <= 0.0F) {
            continue;
         }
         int half = (int) Math.sqrt(dx);
         g.fill(cx - half, py, cx + half, py + 1, withAlpha(color, alpha));
      }
      g.disableScissor();
   }

   // =========================================================== 面板 / 卡片 / 槽位
   // OneConfig 风格：窗口是一整块半透明圆角玻璃（左深右浅），卡片是"半透明白"的扁平方块 + 1px 浅描边。

   /** 悬浮玻璃面板：柔和外投影 → 纵向微渐变 → 细描边。 */
   public static void panel(GuiGraphics g, int x, int y, int w, int h) {
      UITheme.ensureLoaded();
      // 扁平直角风：纯色面板 + 1px 硬边框，无投影、无渐变、无高光。
      rounded(g, x, y, w, h, 0, SHEET);
      ring(g, x, y, w, h, 0, BORDER);
   }

   /** 窗口分区底：左（侧栏）比右（内容区）深一档，形成 OneConfig 的两栏观感。 */
   public static void sections(GuiGraphics g, int x, int y, int w, int h, int sidebarW) {
      // 扁平直角：左栏 / 右栏两块纯色，中间一条 1px 硬分隔线。
      g.fill(x + 1, y + 1, x + sidebarW, y + h - 1, SIDEBAR);
      g.fill(x + sidebarW, y + 1, x + w - 1, y + h - 1, CONTENT);
      g.fill(x + sidebarW, y + 1, x + sidebarW + 1, y + h - 1, BORDER);
   }

   public static void card(GuiGraphics g, int x, int y, int w, int h, int r) {
      rounded(g, x, y, w, h, 0, CARD);
      ring(g, x, y, w, h, 0, BORDER_DIM);
   }

   public static void cardInteractive(GuiGraphics g, int x, int y, int w, int h, int r, boolean hover) {
      float hp = hoverProgress("card:" + x + "," + y + "," + w + "," + h, hover);
      rounded(g, x, y, w, h, 0, mix(CARD, 0xFF2A3140, hp));
      ring(g, x, y, w, h, 0, hover ? BORDER_HOVER : BORDER_DIM);
   }

   /** 带鼠标聚光的卡片（悬停时边缘在光标附近最亮）。 */
   public static void cardInteractive(GuiGraphics g, int x, int y, int w, int h, int r, boolean hover, int mx,
         int my) {
      cardInteractive(g, x, y, w, h, r, hover);
      if (hover) {
         int rel = Math.max(0, Math.min(w - 24, mx - x - 12));
         int span = 24;
         for (int i = 0; i < span; i++) {
            float t = (float) i / (float) span;
            int alpha = Math.round(70.0F * (1.0F - Math.abs(t - 0.5F) * 2.0F));
            if (alpha <= 0) {
               continue;
            }
            int px = x + rel + i;
            g.fill(px, y, px + 1, y + 1, withAlpha(BLUE, alpha));
         }
      }
   }

   // =========================================================== OneConfig 专用组件

   /** 侧栏分组标题：小号、浅灰、全大写观感（OneConfig 的 "MODS & OPTIONS"）。 */
   public static void sectionLabel(GuiGraphics g, Font font, int x, int y, Component text) {
      text(g, font, text, x, y, TEXT_3);
   }

   /**
    * 侧栏列表项（OneConfig 的左栏条目）：图标 + 文字；选中 = **整条强调色实心** + 白字，
    * 悬停 = 半透明白。命中区、拖拽都由调用方管理。
    */
   public static void navItem(GuiGraphics g, int x, int y, int w, int h, Component label, boolean selected,
         boolean hover, boolean dragging) {
      String key = "nav:" + x + "," + y + "," + w + "," + h;
      float sp = selectProgress(key, selected);
      float hp = hoverProgress(key, hover);
      if (dragging) {
         rounded(g, x, y, w, h, 0, 0xFF000000);
         ring(g, x, y, w, h, 0, BORDER_HOVER);
         return;
      }
      if (sp > 0.01F) {
         rounded(g, x, y, w, h, 0, BLUE);
      }
      if (sp < 0.99F) {
         rounded(g, x, y, w, h, 0, hp > 0.01F ? ROW_HOVER : SIDEBAR);
      }
      // 自适应的 1px 硬边框：跟着条目矩形和状态走（选中 = 强调色、悬停 = 亮边框、普通 = 暗边框），
      // 拖拽中的描边由上面单独处理。
      ring(g, x, y, w, h, 0, sp > 0.5F ? BORDER_HOVER : (hp > 0.01F ? BORDER_HOVER : BORDER_DIM));
      int fg = mix(mix(TEXT, WHITE, hp), 0xFFFFFFFF, sp);
      text(g, Minecraft.getInstance().font, label, x + 9, y + (h - 8) / 2, fg);
   }

   /** 内容区顶部的分类药丸（OneConfig 的 All / Favorites / …）：选中 = 强调色实心。 */
   public static void pill(GuiGraphics g, Font font, int x, int y, int w, int h, Component label, boolean selected,
         boolean hover, boolean enabled) {
      if (selected) {
         rounded(g, x, y, w, h, 0, BLUE);
         ring(g, x, y, w, h, 0, BORDER_HOVER);
      } else {
         rounded(g, x, y, w, h, 0, hover ? GRAY_BTN_HOVER : GRAY_BTN);
         ring(g, x, y, w, h, 0, BORDER_DIM);
      }
      int fg = !enabled ? DISABLED_TEXT : (selected ? 0xFFFFFFFF : TEXT);
      center(g, font, label, x, y, w, h, fg);
   }

   /** 设置行（OneConfig 的 Preferences/主题页那种）：左侧图标 + 标题 + 副标题，右侧留给控件。 */
   public static void settingRow(GuiGraphics g, Font font, int x, int y, int w, int h, Component title,
         Component subtitle, boolean hover) {
      rounded(g, x, y, w, h, 0, hover ? 0xFF222735 : CARD);
      ring(g, x, y, w, h, 8, HAIR);
      text(g, font, title, x + 34, y + (subtitle == null ? (h - 8) / 2 : 7), TEXT);
      if (subtitle != null) {
         text(g, font, subtitle, x + 34, y + 20, TEXT_2);
      }
   }

   /** 卡片底部的强调色标题条（OneConfig 模组卡片底部的蓝条）。 */
   public static void labelBar(GuiGraphics g, Font font, int x, int y, int w, int h, Component label,
         boolean accent) {
      int color = accent ? BLUE : mix(BLUE, 0x00000000, 0.0F);
      rounded(g, x, y, w, h, 0, accent ? color : BLUE);
      ring(g, x, y, w, h, 0, BORDER_HOVER);
      center(g, font, label, x, y, w, h, 0xFFFFFFFF);
   }

   /** 小图标底座（设置行左侧那枚方形图标）。 */
   public static void iconBox(GuiGraphics g, int x, int y, int size, int bg) {
      rounded(g, x, y, size, size, 0, bg);
      ring(g, x, y, size, size, 0, BORDER_DIM);
   }

   /**
    * 毛玻璃面板：先把整块区域压成不透明深色（后面的内容彻底看不见），
    * 再点一层细密的「霜花」噪点 + 1px 硬边框 —— 观感是磨砂玻璃，而不是半透明遮罩。
    */
   public static void frosted(GuiGraphics g, int x, int y, int w, int h) {
      if (w <= 0 || h <= 0) {
         return;
      }
      g.fill(x, y, x + w, y + h, 0xF20D1017);
      for (int py = 0; py < h; py++) {
         int alpha = 14 - Math.min(10, py * 10 / Math.max(1, h));
         if (alpha <= 2) {
            continue;
         }
         for (int px = (py % 4 < 2 ? 0 : 2); px < w; px += 4) {
            g.fill(x + px, y + py, x + px + 1, y + py + 1, withAlpha(WHITE, alpha));
         }
      }
      ring(g, x, y, w, h, 0, BORDER);
   }

   /**
    * 「下面还有内容」提示：**直边倒置等腰三角形**（像素直边，不用圆弧）。
    * 由调用方在最后一层绘制，保证盖在内容与遮罩之上。
    */
   public static void triangleDown(GuiGraphics g, int centerX, int topY, int halfW, int h, int color) {
      if (halfW <= 0 || h <= 0) {
         return;
      }
      for (int row = 0; row < h; row++) {
         int half = Math.max(1, halfW - (int) Math.round((double) halfW * row / (double) h));
         g.fill(centerX - half, topY + row, centerX + half, topY + row + 1, color);
      }
   }

   public static void navBar(GuiGraphics g, int x, int y, int w, int h) {
      rounded(g, x, y, w, h, 12, CARD);
      g.fill(x, y + 12, x + w, y + h, CARD);
      g.fill(x + 1, y + h - 1, x + w - 1, y + h, HAIR);
   }

   /** 物品槽：外描边 + 内凹底 + 上缘暗影，做出"凹进去"的感觉。 */
   public static void slot(GuiGraphics g, int x, int y) {
      rounded(g, x - 1, y - 1, 18, 18, 5, SLOT_BORDER);
      rounded(g, x, y, 16, 16, 4, SLOT_INNER);
      g.fill(x + 2, y + 1, x + 14, y + 2, 0x22000000);
      g.fill(x + 1, y + 1, x + 2, y + 9, 0x16000000);
   }

   public static void avatarFrame(GuiGraphics g, int x, int y, int size, int r, int bg) {
      cutCorners(g, x, y, size, r, bg);
      ring(g, x, y, size, size, r, HAIR);
   }

   public static void cutCorners(GuiGraphics g, int x, int y, int size, int r, int bg) {
      for (int corner = 0; corner < 4; corner++) {
         for (int i = 0; i < r; i++) {
            for (int j = 0; j < r; j++) {
               float dx = (float) r - ((float) i + 0.5F);
               float dy = (float) r - ((float) j + 0.5F);
               if (dx * dx + dy * dy > (float) r * (float) r) {
                  int px = (corner & 1) == 0 ? x + i : x + size - 1 - i;
                  int py = (corner & 2) == 0 ? y + j : y + size - 1 - j;
                  g.fill(px, py, px + 1, py + 1, bg);
               }
            }
         }
      }
   }

   // =========================================================== 按钮（含交互动效）

   /**
    * 按钮：渐变底 + 内高光 + 冷投影；悬停上浮并点亮描边，按下缩放 0.97 并以点击点为圆心扩散水波。
    * 签名与旧版完全一致，所有界面无需改动。
    */
   public static void button(GuiGraphics g, int x, int y, int w, int h, Component label, Font font, double mx,
         double my, boolean enabled, IOStyle.Btn style) {
      boolean hover = enabled && inside(x, y, w, h, mx, my);
      boolean down = leftDown();
      String key = style + ":" + x + "," + y + "," + w + "," + h;
      // 按下瞬间触发水波：每个控件各自记一份"按住"状态，不依赖全局标志（同帧多个按钮也准确）。
      boolean pressedNow = hover && down;
      if (pressedNow && PRESSED.add(key)) {
         ripple(key, (int) Math.round(mx), (int) Math.round(my));
      } else if (!pressedNow) {
         PRESSED.remove(key);
      }
      float hp = hoverProgress(key, hover);
      float pp = pressProgress(key, pressedNow);

      int bg;
      int fg;
      switch (style) {
         case PRIMARY:
            bg = !enabled ? mix(BLUE, CARD, 0.45F) : (hover ? BLUE_HOVER : BLUE);
            fg = !enabled ? withAlpha(WHITE, 190) : 0xFFFFFFFF;
            break;
         case TINT:
            bg = !enabled ? DISABLED_BG : (hover ? BLUE_TINT_HOVER : BLUE_TINT);
            fg = !enabled ? DISABLED_TEXT : BLUE;
            break;
         case GRAY:
         default:
            bg = !enabled ? DISABLED_BG : (hover ? GRAY_BTN_HOVER : GRAY_BTN);
            fg = !enabled ? DISABLED_TEXT : TEXT;
            break;
         case DANGER:
            bg = !enabled ? mix(RED, CARD, 0.45F) : (hover ? RED_HOVER : RED);
            fg = 0xFFFFFFFF;
            break;
         case DANGER_TINT:
            bg = hover ? RED_TINT_HOVER : RED_TINT;
            fg = !enabled ? DISABLED_TEXT : RED;
            break;
         case SUCCESS:
            bg = !enabled ? mix(GREEN, CARD, 0.5F) : (hover ? GREEN_HOVER : GREEN);
            fg = 0xFFFFFFFF;
            break;
         case SUCCESS_TINT:
            bg = hover ? GREEN_TINT_HOVER : GREEN_TINT;
            fg = !enabled ? DISABLED_TEXT : GREEN;
            break;
         case GHOST:
            bg = hover ? GRAY_BTN : 0x00000000;
            fg = !enabled ? DISABLED_TEXT : BLUE;
      }

      // 扁平直角风：纯色底 + 1px 硬边框；按下时底色压暗一档（不做位移与缩放）。
      int drawY = y;
      int fill = pp > 0.5F ? mix(bg, 0xFF000000, 0.28F) : (hp > 0.5F ? mix(bg, WHITE, 0.08F) : bg);
      rounded(g, x, drawY, w, h, 0, fill);
      ring(g, x, drawY, w, h, 0, !enabled ? BORDER_DIM : (hover ? BORDER_HOVER : BORDER));
      if (label != null) {
         center(g, font, label, x, drawY, w, h, fg);
      }
   }

   /** 键盘焦点环（Tab 键导航时用）。 */
   public static void focusRing(GuiGraphics g, int x, int y, int w, int h, int r) {
      ring(g, x - 2, y - 2, w + 4, h + 4, r + 2, FOCUS);
   }

   /**
    * 自绘小按钮的统一皮肤（给那些自己画按钮的界面用）：
    * 渐变底 + 内高光 + 悬停上浮 + 按下缩放 + 点击水波。
    *
    * @param hovered  是否悬停（调用方已经算好）
    * @param base     常态底色
    * @param baseHover 悬停底色
    * @param fg       文字色
    */
   public static void smallButton(GuiGraphics g, int x, int y, int w, int h, Component label, Font font,
         boolean hovered, int base, int baseHover, int fg, int mx, int my) {
      int bg = hovered ? baseHover : base;
      int r = Math.min(7, h / 2);
      String key = "sbtn:" + x + "," + y + "," + w + "," + h;
      boolean pressedNow = hovered && leftDown();
      if (pressedNow && PRESSED.add(key)) {
         ripple(key, mx, my);
      } else if (!pressedNow) {
         PRESSED.remove(key);
      }
      float hp = hoverProgress(key, hovered);
      float pp = pressProgress(key, pressedNow);
      int lift = -Math.round(hp);
      int dy = y + lift;
      g.pose().pushPose();
      if (pp > 0.005F) {
         float cx = x + w / 2.0F;
         float cy = y + h / 2.0F;
         float scale = 1.0F - 0.05F * pp;
         g.pose().translate(cx, cy, 0.0F);
         g.pose().scale(scale, scale, 1.0F);
         g.pose().translate(-cx, -cy, 0.0F);
      }
      if (hp > 0.01F) {
         rounded(g, x, dy + 1, w, h, r, withAlpha(bg, Math.round(70.0F * hp)));
      }
      gradient(g, x, dy, w, h, r, mix(bg, WHITE, 0.05F + 0.05F * hp), mix(bg, 0xFF000000, 0.12F));
      topHighlight(g, x, dy, w, Math.min(3, r / 2), 12 + Math.round(24.0F * hp));
      ring(g, x, dy, w, h, r, mix(withAlpha(0xFF000000, 40), withAlpha(fg, 150), hp));
      drawRipple(g, key, x, dy, w, h, r, 0xFFFFFFFF);
      if (label != null) {
         center(g, font, label, x, dy + Math.round(pp), w, h, fg);
      }
      g.pose().popPose();
   }

   public static void segmentTrack(GuiGraphics g, int x, int y, int w, int h) {
      rounded(g, x, y, w, h, 8, SEG_TRACK);
      topHighlight(g, x, y, w, 4, 12);
   }

   public static void segmentItem(GuiGraphics g, int x, int y, int w, int h, Component label, Font font,
         boolean selected, boolean hover) {
      float sp = selectProgress("seg:" + x + "," + y + "," + w, selected);
      if (sp > 0.01F) {
         rounded(g, x + 1, y + 1, w - 2, h - 2, 7, mix(SEG_TRACK, SEG_SLIDER, sp));
         rounded(g, x + 2, y + h - 3, (int) ((w - 4) * sp), 2, 1, withAlpha(BLUE, Math.round(180.0F * sp)));
         center(g, font, label, x + 1, y + 1, w - 2, h - 2, mix(SEG_OFF, BLUE, sp));
      } else {
         center(g, font, label, x, y, w, h, hover ? TEXT : SEG_OFF);
      }
   }

   public static void toggle(GuiGraphics g, int x, int y, boolean on) {
      // 扁平直角开关：方槽 + 方块钮 + 1px 硬边框。
      rounded(g, x, y, 34, 18, 0, on ? BLUE : TOGGLE_OFF);
      ring(g, x, y, 34, 18, 0, on ? BORDER_HOVER : BORDER);
      int knob = 12;
      int knobX = on ? x + 34 - 2 - knob : x + 2;
      rounded(g, knobX, y + 3, knob, knob, 0, KNOB);
      ring(g, knobX, y + 3, knob, knob, 0, BORDER_DIM);
   }

   /**
    * 页签：选中块**渐变生长**（而不是瞬间出现），悬停轻微内缩并点亮描边，选中时左侧强调条拉伸。
    */
   public static void tab(GuiGraphics g, int x, int y, int w, int h, Component label, Font font, boolean selected,
         boolean hover) {
      String key = "tab:" + x + "," + y + "," + w + "," + h;
      float sp = selectProgress(key, selected);
      float hp = hoverProgress(key, hover);
      // OneConfig：选中项是一整块强调色实心（不是浅色底 + 描边）。
      if (sp > 0.01F) {
         rounded(g, x, y, w, h, 6, withAlpha(BLUE, Math.round(255.0F * sp)));
      }
      if (sp < 0.99F) {
         rounded(g, x, y, w, h, 6, mix(hover ? TAB_BG_HOVER : TAB_BG, 0x00000000, sp));
      }
      if (sp < 0.99F && hp > 0.01F) {
         ring(g, x, y, w, h, 6, withAlpha(WHITE, Math.round(40.0F * hp)));
      }
      text(g, font, label, x + 12, y + (h - 8) / 2, mix(TEXT, 0xFFFFFFFF, sp));
   }

   public static void themeButton(GuiGraphics g, int x, int y, int size, boolean isDark, boolean hover) {
      if (hover) {
         rounded(g, x, y, size, size, 8, dark ? withAlpha(WHITE, 46) : GRAY_BTN);
      }

      int cx = x + size / 2;
      int cy = y + size / 2;
      if (!isDark) {
         rounded(g, cx - 4, cy - 4, 8, 8, 4, 0xFFE0B15E);
         g.fill(cx, cy - 9, cx + 1, cy - 6, 0xFFE0B15E);
         g.fill(cx, cy + 6, cx + 1, cy + 9, 0xFFE0B15E);
         g.fill(cx - 9, cy, cx - 6, cy + 1, 0xFFE0B15E);
         g.fill(cx + 6, cy, cx + 9, cy + 1, 0xFFE0B15E);
         g.fill(cx - 7, cy - 7, cx - 6, cy - 6, 0xFFE0B15E);
         g.fill(cx + 6, cy - 7, cx + 7, cy - 6, 0xFFE0B15E);
         g.fill(cx - 7, cy + 6, cx - 6, cy + 7, 0xFFE0B15E);
         g.fill(cx + 6, cy + 6, cx + 7, cy + 7, 0xFFE0B15E);
      } else {
         int moon = 0xFFB9C4D8;
         float ocx = (float) cx - 0.5F;
         float ocy = (float) cy + 0.0F;
         float rOut = 5.0F;
         float icx = (float) cx + 1.8F;
         float icy = (float) cy - 1.8F;
         float rIn = 4.4F;

         for (int py = y + 4; py <= y + size - 4; py++) {
            for (int px = x + 4; px <= x + size - 4; px++) {
               float dxo = (float) px + 0.5F - ocx;
               float dyo = (float) py + 0.5F - ocy;
               float dxi = (float) px + 0.5F - icx;
               float dyi = (float) py + 0.5F - icy;
               if (dxo * dxo + dyo * dyo <= rOut * rOut && dxi * dxi + dyi * dyi >= rIn * rIn) {
                  g.fill(px, py, px + 1, py + 1, moon);
               }
            }
         }
      }
   }

   // =========================================================== 文字

   public static void text(GuiGraphics g, Font font, Component c, int x, int y, int color) {
      g.drawString(font, c, x, y, color, false);
   }

   public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
      g.drawString(font, s, x, y, color, false);
   }

   public static void center(GuiGraphics g, Font font, Component c, int x, int y, int w, int h, int color) {
      g.drawString(font, c, x + (w - font.width(c)) / 2, y + (h - 8) / 2, color, false);
   }

   public static void center(GuiGraphics g, Font font, String s, int x, int y, int w, int h, int color) {
      g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, color, false);
   }

   public static void centerAt(GuiGraphics g, Font font, Component c, int cx, int y, int color) {
      g.drawString(font, c, cx - font.width(c) / 2, y, color, false);
   }

   public static void centerAt(GuiGraphics g, Font font, String s, int cx, int y, int color) {
      g.drawString(font, s, cx - font.width(s) / 2, y, color, false);
   }

   public static void right(GuiGraphics g, Font font, Component c, int right, int y, int color) {
      g.drawString(font, c, right - font.width(c), y, color, false);
   }

   /** 金额等数值变化时的闪烁描边（配合 {@link #flash} 使用）。 */
   public static void flashGlow(GuiGraphics g, Font font, Component text, int x, int y, int baseColor, String key) {
      // 扁平风：取消发光，余额变化时只把文字提亮一档。
      float f = flashProgress(key, 600L);
      g.drawString(font, text, x, y, f > 0.01F ? mix(baseColor, WHITE, f * 0.6F) : baseColor, false);
   }

   /** 同上，但按 {@code scale} 倍字号绘制（左下角玩家信息块的余额用小一号字，保证不越出边框）。 */
   public static void flashGlowScaled(GuiGraphics g, Font font, Component text, int x, int y, float scale,
         int baseColor, String key) {
      float f = flashProgress(key, 600L);
      g.pose().pushPose();
      g.pose().translate(x, y, 0.0F);
      g.pose().scale(scale, scale, 1.0F);
      g.drawString(font, text, 0, 0, f > 0.01F ? mix(baseColor, WHITE, f * 0.6F) : baseColor, false);
      g.pose().popPose();
   }

   public static Component ellipsize(Font font, Component c, int maxWidth) {
      if (font.width(c) <= maxWidth) {
         return c;
      } else {
         String s = c.getString();
         String dots = "...";

         while (!s.isEmpty() && font.width(s + dots) > maxWidth) {
            s = s.substring(0, s.length() - 1);
         }

         return Component.literal(s + dots);
      }
   }

   public static int rarity(Rarity rarity) {
      return switch (rarity) {
         case LEGENDARY -> ORANGE;
         case EPIC -> EPIC;
         case RARE -> BLUE;
         case COMMON -> GREEN;
      };
   }

   public static int rarityTint(Rarity rarity) {
      return withAlpha(rarity(rarity) & 16777215, 34);
   }

   public static enum Btn {
      PRIMARY,
      TINT,
      GRAY,
      DANGER,
      DANGER_TINT,
      SUCCESS,
      SUCCESS_TINT,
      GHOST;

      private Btn() {
      }
   }
}
