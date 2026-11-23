package cn.autoforged.module_shop.client.ui.render;

import cn.autoforged.module_shop.client.ui.AtomUi;
import com.atom.lib.font.FontId;
import com.atom.lib.layout.Constraints;
import com.atom.lib.text.TextLayout;
import com.atom.lib.text.TextRequest;
import io.github.humbleui.skija.Canvas;

/**
 * Skija 文字的测量与绘制（单位：GUI 像素）。
 *
 * <p>AtomLib 的 {@link TextRequest} 只接受纯从左到右文本：遇到控制字符、双向文本会直接抛异常。
 * 商店里的文字可能来自玩家（摊位名、邮件、签名）或服务端配置，所以这里统一先 {@link #sanitize 清洗}，
 * 保证任何输入都不会让界面崩掉。
 *
 * <p>布局结果由 AtomLib 的 TextService 缓存，同一帧重复测量同一段文字代价很低。
 */
public final class Text {
    /** 正文字号（GUI 像素）。原版字体行高 9px，这里取接近的视觉大小。 */
    public static final float BODY = 8.0F;
    public static final float SMALL = 6.5F;
    public static final float TITLE = 10.0F;

    private static final int MAX_LENGTH = 32768;
    private static final float WIDTH_EPS = 0.05F;

    private Text() {
    }

    public enum Align { LEFT, CENTER, RIGHT }

    /** 单行布局（超宽时省略号截断）。maxWidth ≤ 0 表示不限宽。 */
    public static TextLayout line(String text, FontId font, float size, int color, float maxWidth) {
        Constraints.Axis width = maxWidth > 0 ? Constraints.Axis.atMost(maxWidth + WIDTH_EPS) : Constraints.Axis.undefined();
        return AtomUi.text().layout(new TextRequest(sanitize(text), font, size, color, 1, maxWidth > 0),
                new Constraints(width, Constraints.Axis.undefined()));
    }

    /** 多行布局（按宽度自动换行；maxLines 行后省略号截断）。 */
    public static TextLayout wrapped(String text, FontId font, float size, int color, float width, int maxLines) {
        return AtomUi.text().layout(new TextRequest(sanitize(text), font, size, color, Math.max(1, maxLines), true),
                new Constraints(Constraints.Axis.atMost(Math.max(1.0F, width)), Constraints.Axis.undefined()));
    }

    public static float width(String text, FontId font, float size) {
        return line(text, font, size, 0xFFFFFFFF, 0).size().width();
    }

    /** 把布局画在 (x, y)，y 为文字框顶部。 */
    public static void draw(Canvas canvas, TextLayout layout, float x, float y) {
        AtomUi.text().draw(canvas, layout, x, y);
    }

    /**
     * 单行绘制：在 [x, x+maxWidth] 内按 align 对齐，并以 centerY 垂直居中。
     * 返回实际绘制宽度。
     *
     * <p>版面算错时（负宽度、负高度）字不会被挤成竖条，而且**永远不会越出 x+maxWidth 右边界**：
     * 布局本身按宽度截断，这里再兜一层兜底，防止某个界面把宽度算成负数时文字跑到面板外面。
     */
    public static float drawLine(Canvas canvas, String text, FontId font, float size, int color,
                                 float x, float centerY, float maxWidth, Align align) {
        if (maxWidth <= 0.0F) {
            return 0.0F; // 没有可用的横向空间：不画，也不越界
        }
        TextLayout layout = line(text, font, size, color, maxWidth);
        float w = layout.size().width();
        float drawX = switch (align) {
            case LEFT -> x;
            case CENTER -> x + (maxWidth - w) / 2.0F;
            case RIGHT -> x + maxWidth - w;
        };
        float limit = x + maxWidth - w;
        if (drawX > limit) {
            drawX = limit;
        }
        if (drawX < x) {
            drawX = x;
        }
        draw(canvas, layout, drawX, centerY - layout.size().height() / 2.0F);
        return w;
    }

    /**
     * 去掉 AtomLib 不接受的内容：Minecraft § 格式码、控制字符（保留换行）、
     * 孤立代理项、从右到左字符与双向控制符（替换为 '?'），并限制长度。
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_LENGTH));
        int i = 0;
        while (i < raw.length() && out.length() < MAX_LENGTH - 2) {
            char c = raw.charAt(i);
            if (c == '§') { // § 格式码：支持 §#RRGGBB 与完整 Unicode 码点跳过（避免截断代理对）
                if (isHexColorAt(raw, i)) {
                    i += 8;
                } else {
                    i++;
                    if (i < raw.length()) {
                        i += Character.charCount(raw.codePointAt(i));
                    }
                }
                continue;
            }
            int cp = raw.codePointAt(i);
            int len = Character.charCount(cp);
            i += len;
            if (cp == '\n') {
                out.append('\n');
                continue;
            }
            if (cp == '\r' || cp == '\t') {
                out.append(' ');
                continue;
            }
            if (Character.isISOControl(cp)) {
                continue;
            }
            // 私有使用区（PUA）等「图标字符」：不少食物 / 营养模组会往物品名里塞自己字体的图标
            // （饥饿值、饱食度之类）。我们的字体没有这些字形，画出来就是一串「□」，
            // 所以在这里统一丢掉，只保留真正的文字。
            if (isIconGlyph(cp)) {
                continue;
            }
            if (len == 1 && Character.isSurrogate(c)) { // 孤立代理项
                out.append('?');
                continue;
            }
            byte dir = Character.getDirectionality(cp);
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)) {
                out.append('?');
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** 是否是「图标字符」（私有使用区 / 变体选择符 / 零宽字符）——这些在我们的字体里没有字形。 */
    private static boolean isIconGlyph(int cp) {
        if (cp >= 0xE000 && cp <= 0xF8FF) {
            return true;
        }
        if (cp >= 0xF0000 && cp <= 0xFFFFD) {
            return true;
        }
        if (cp >= 0x100000 && cp <= 0x10FFFD) {
            return true;
        }
        if (cp >= 0xFE00 && cp <= 0xFE0F) {
            return true;
        }
        return cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x200E || cp == 0x200F
                || cp == 0x2060 || cp == 0xFEFF;
    }

    private static boolean isHexColorAt(String raw, int index) {
        if (index + 7 >= raw.length() || raw.charAt(index + 1) != '#') {
            return false;
        }
        for (int k = index + 2; k < index + 8; k++) {
            char h = raw.charAt(k);
            boolean hex = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
