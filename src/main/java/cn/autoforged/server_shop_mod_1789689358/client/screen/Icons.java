package cn.autoforged.server_shop_mod_1789689358.client.screen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 侧栏小图标（手绘，OneConfig 那种 10×10 线性图标观感）。
 * <p>
 * 全部用 {@code g.fill} 画几何形状：不依赖字体字形、不引入贴图，任何 GUI 缩放下都清晰；
 * 统一在 (x, y) 左上角、10×10 的框内绘制，颜色由调用方给（选中时用白色）。
 */
public final class Icons {
    /** 图标标准边长。 */
    public static final int SIZE = 10;

    private Icons() {
    }

    /** 按模块 id 取图标。 */
    public static void module(GuiGraphics g, String tabId, int x, int y, int color) {
        switch (tabId) {
            case "daily" -> shop(g, x, y, color);
            case "buyback" -> coins(g, x, y, color);
            case "auction" -> gavel(g, x, y, color);
            case "stall" -> stall(g, x, y, color);
            case "mail" -> envelope(g, x, y, color);
            case "exchange" -> swap(g, x, y, color);
            case "admin" -> gear(g, x, y, color);
            default -> dot(g, x, y, color);
        }
    }

    /** 商店：小房子（屋顶 + 门面）。 */
    public static void shop(GuiGraphics g, int x, int y, int c) {
        // 屋顶（两条斜边）
        for (int i = 0; i < 5; i++) {
            g.fill(x + 5 - i, y + i, x + 6 - i, y + i + 1, c);
            g.fill(x + 4 + i, y + i, x + 5 + i, y + i + 1, c);
        }
        // 主体
        ring(g, x + 1, y + 4, 8, 6, 1, c);
    }

    /** 收购：两枚叠起来的硬币。 */
    public static void coins(GuiGraphics g, int x, int y, int c) {
        ring(g, x, y + 3, 7, 6, 2, c);
        ring(g, x + 3, y + 1, 7, 6, 2, c);
    }

    /** 拍卖：小锤子。 */
    public static void gavel(GuiGraphics g, int x, int y, int c) {
        g.fill(x + 1, y + 1, x + 7, y + 4, c);
        g.fill(x + 3, y, x + 6, y + 1, c);
        g.fill(x + 5, y + 4, x + 6, y + 9, c);
        g.fill(x + 3, y + 9, x + 7, y + 10, c);
    }

    /** 摊位：带遮阳棚的货摊。 */
    public static void stall(GuiGraphics g, int x, int y, int c) {
        // 遮阳棚（条纹）
        for (int i = 0; i < 5; i++) {
            g.fill(x + i * 2, y + 1, x + i * 2 + 1, y + 4, c);
        }
        g.fill(x, y + 4, x + 10, y + 5, c);
        // 台面与支架
        g.fill(x + 1, y + 5, x + 9, y + 7, c);
        g.fill(x + 1, y + 7, x + 2, y + 10, c);
        g.fill(x + 8, y + 7, x + 9, y + 10, c);
    }

    /** 邮箱：信封（封盖折线）。 */
    public static void envelope(GuiGraphics g, int x, int y, int c) {
        ring(g, x, y + 2, 10, 7, 1, c);
        for (int i = 0; i < 4; i++) {
            g.fill(x + 1 + i, y + 3 + i, x + 2 + i, y + 4 + i, c);
            g.fill(x + 8 - i, y + 3 + i, x + 9 - i, y + 4 + i, c);
        }
    }

    /** 兑换：上下双箭头。 */
    public static void swap(GuiGraphics g, int x, int y, int c) {
        g.fill(x, y + 2, x + 8, y + 3, c);
        g.fill(x + 6, y, x + 7, y + 2, c);
        g.fill(x + 7, y + 1, x + 8, y + 3, c);
        g.fill(x + 2, y + 7, x + 10, y + 8, c);
        g.fill(x + 2, y + 7, x + 3, y + 9, c);
        g.fill(x + 3, y + 8, x + 4, y + 10, c);
    }

    // ---- 12×12 像素图标（用字符矩阵定义，绝不依赖贴图或字体；# = 实心，. = 空） ----

    /** 设置齿轮（12×12）：外圈 + 8 个齿 + 中心孔。 */
    /** 12×12 铃铛（# = 亮像素）。 */
    private static final String[] BELL_12 = {
            "     ##     ",
            "     ##     ",
            "    ####    ",
            "   ######   ",
            "   ##  ##   ",
            "  ########  ",
            "  ########  ",
            "  ########  ",
            " ########## ",
            " ########## ",
            "    ####    ",
            "     ##     "
    };

    private static final String[] GEAR_12 = {
        "...##..##...",
        "...##..##...",
        ".####..####.",
        ".##########.",
        ".####..####.",
        "###..##..###",
        "###..##..###",
        ".####..####.",
        ".##########.",
        ".####..####.",
        "...##..##...",
        "...##..##...",
    };

    /** 垃圾桶（12×12）：桶盖 + 桶身 + 两条竖纹。 */
    private static final String[] TRASH_12 = {
        "....####....",
        "..########..",
        "############",
        "..########..",
        "..########..",
        "..#.#..#.#..",
        "..#.#..#.#..",
        "..#.#..#.#..",
        "..#.#..#.#..",
        "..#.#..#.#..",
        "..########..",
        "............",
    };

    /** 设置：12×12 像素齿轮。 */
    /**
     * 铃铛：提醒音效设置按钮用（12×12 像素画，参考像素风铃铛）。
     * <pre>
     *      #
     *     ###
     *    #####
     *   ##.#.##
     *   #######
     *   #######
     *   #######
     *  #########
     *  #########
     *     ###
     *      #
     * </pre>
     */
    public static void bell(GuiGraphics g, int x, int y, int c) {
        matrix(g, BELL_12, x, y, c);
    }
    public static void gear(GuiGraphics g, int x, int y, int c) {
        matrix(g, GEAR_12, x, y, c);
    }

    /** 垃圾桶：拖动模块到「删除区」时显示。 */
    public static void trash(GuiGraphics g, int x, int y, int c) {
        matrix(g, TRASH_12, x, y, c);
    }

    /** 按字符矩阵画像素图标。 */
    private static void matrix(GuiGraphics g, String[] rows, int x, int y, int c) {
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == '#') {
                    g.fill(x + col, y + row, x + col + 1, y + row + 1, c);
                }
            }
        }
    }

    /** 通用：实心圆点（未知模块的兜底）。 */
    public static void dot(GuiGraphics g, int x, int y, int c) {
        roundedDot(g, x + 5, y + 5, 3, c);
    }

    private static void roundedDot(GuiGraphics g, int cx, int cy, int r, int c) {
        for (int py = -r; py <= r; py++) {
            for (int px = -r; px <= r; px++) {
                if (px * px + py * py <= r * r) {
                    g.fill(cx + px, cy + py, cx + px + 1, cy + py + 1, c);
                }
            }
        }
    }

    /** 细描边圆角矩形（只有 1px 边框）。 */
    private static void ring(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        // 上下边
        g.fill(x + r, y, x + w - r, y + 1, c);
        g.fill(x + r, y + h - 1, x + w - r, y + h, c);
        // 左右边
        g.fill(x, y + r, x + 1, y + h - r, c);
        g.fill(x + w - 1, y + r, x + w, y + h - r, c);
        // 四角
        for (int i = 0; i < r; i++) {
            g.fill(x + r - i - 1, y + i, x + r - i, y + i + 1, c);
            g.fill(x + w - r + i, y + i, x + w - r + i + 1, y + i + 1, c);
            g.fill(x + r - i - 1, y + h - i - 1, x + r - i, y + h - i, c);
            g.fill(x + w - r + i, y + h - i - 1, x + w - r + i + 1, y + h - i, c);
        }
    }
}
