package cn.autoforged.module_shop.client.ui.render;

import cn.autoforged.module_shop.client.ui.AtomUi;
import com.atom.lib.font.FontId;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;

/**
 * 带 Minecraft 颜色代码（§0-§f、§l 粗体、§r 重置）的文字排版与绘制。
 *
 * <p>{@link Text} 会把 § 代码当作非法字符清洗掉；邮件标题/正文、服务器配置文案里常带颜色，
 * 需要保留时用这里：先解析成“同样式的文字段”，再按宽度换行（中文/全角逐字可断、拉丁单词整体不断、
 * 超长单词硬断），最后每行把同样式的相邻片段合并后分段绘制。
 *
 * <p>换行结果建议由调用方按 (文本, 宽度, 字号) 缓存：排版需要逐段测量，比直接绘制贵。
 */
public final class StyledText {
    /** 一段同样式的文字。 */
    public record Run(String text, int color, boolean bold) {
    }

    /** 排好的一行：若干段 + 行宽。 */
    public record Line(List<Run> runs, float width) {
    }

    private static final int[] COLORS = {
            0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA, 0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
            0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF, 0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF,
    };

    /** 日间主题下的同名颜色：原版的亮黄、亮绿、白色在白底上几乎看不见，换成同色相的深色。 */
    private static final int[] COLORS_LIGHT = {
            0xFF000000, 0xFF0000AA, 0xFF00800F, 0xFF00808A, 0xFFAA0000, 0xFF9A1C9A, 0xFFB86E00, 0xFF6B7484,
            0xFF4A4A4A, 0xFF3446D6, 0xFF1C8F3A, 0xFF0C849C, 0xFFD13A3A, 0xFFB03AB3, 0xFFA07A00, 0xFF111620,
    };

    /** 按当前主题（含切换过渡）取 § 颜色。 */
    private static int color(int idx) {
        return Draw.lerp(COLORS[idx], COLORS_LIGHT[idx],
                cn.autoforged.module_shop.client.screen.IOStyle.themeProgress());
    }

    private StyledText() {
    }

    // ------------------------------------------------------------------ 解析

    /** 解析 § 代码；baseColor 为没有颜色代码时（及 §r 之后）的颜色。 */
    public static List<Run> parse(String raw, int baseColor) {
        List<Run> runs = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return runs;
        }
        int color = baseColor;
        boolean bold = false;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§' && i + 1 < raw.length()) {
                char code = Character.toLowerCase(raw.charAt(++i));
                int idx = "0123456789abcdef".indexOf(code);
                if (idx >= 0 || code == 'r' || code == 'l') {
                    flush(runs, buf, color, bold);
                    if (idx >= 0) {
                        color = color(idx);
                        bold = false; // 与原版一致：颜色代码会清除格式
                    } else if (code == 'r') {
                        color = baseColor;
                        bold = false;
                    } else {
                        bold = true;
                    }
                }
                // 其它格式码（§k §m §n §o）忽略
                continue;
            }
            buf.append(c);
        }
        flush(runs, buf, color, bold);
        return runs;
    }

    private static void flush(List<Run> runs, StringBuilder buf, int color, boolean bold) {
        if (buf.length() > 0) {
            runs.add(new Run(Text.sanitize(buf.toString()), color, bold));
            buf.setLength(0);
        }
    }

    /** 去掉所有 § 代码后的纯文本。 */
    public static String plain(String raw) {
        StringBuilder sb = new StringBuilder();
        for (Run r : parse(raw, 0)) {
            sb.append(r.text);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 排版

    /** 按宽度换行。maxLines ≤ 0 表示不限；超出时最后一行以省略号结尾。 */
    public static List<Line> wrap(List<Run> runs, float width, float size, int maxLines) {
        List<Line> lines = new ArrayList<>();
        List<Run> current = new ArrayList<>();
        float lineW = 0.0F;
        for (Run run : runs) {
            for (String token : tokens(run.text)) {
                if (token.equals("\n")) {
                    lines.add(new Line(merge(current), lineW));
                    current = new ArrayList<>();
                    lineW = 0.0F;
                    continue;
                }
                float tw = measure(token, run.bold, size);
                if (lineW + tw > width && !current.isEmpty()) {
                    lines.add(new Line(merge(current), lineW));
                    current = new ArrayList<>();
                    lineW = 0.0F;
                    if (token.equals(" ")) {
                        continue; // 行首不留空格
                    }
                }
                if (tw > width && current.isEmpty()) {
                    // 超长单词：按字符硬断
                    for (int i = 0; i < token.length(); ) {
                        int cp = token.codePointAt(i);
                        String ch = new String(Character.toChars(cp));
                        float cw = measure(ch, run.bold, size);
                        if (lineW + cw > width && !current.isEmpty()) {
                            lines.add(new Line(merge(current), lineW));
                            current = new ArrayList<>();
                            lineW = 0.0F;
                        }
                        current.add(new Run(ch, run.color, run.bold));
                        lineW += cw;
                        i += Character.charCount(cp);
                    }
                    continue;
                }
                current.add(new Run(token, run.color, run.bold));
                lineW += tw;
            }
        }
        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(new Line(merge(current), lineW));
        }
        if (maxLines > 0 && lines.size() > maxLines) {
            List<Line> cut = new ArrayList<>(lines.subList(0, maxLines));
            Line last = cut.remove(cut.size() - 1);
            cut.add(ellipsize(last.runs, width, size));
            return cut;
        }
        return lines;
    }

    /** 单行：超宽时截断并加省略号。 */
    public static Line singleLine(List<Run> runs, float width, float size) {
        List<Run> flat = new ArrayList<>();
        for (Run r : runs) {
            flat.add(new Run(r.text.replace('\n', ' '), r.color, r.bold));
        }
        float total = 0.0F;
        for (Run r : flat) {
            total += measure(r.text, r.bold, size);
        }
        return total <= width ? new Line(merge(flat), total) : ellipsize(flat, width, size);
    }

    private static Line ellipsize(List<Run> runs, float width, float size) {
        List<Run> out = new ArrayList<>();
        float ellW = measure("…", false, size);
        float used = 0.0F;
        int lastColor = runs.isEmpty() ? 0xFFFFFFFF : runs.get(0).color;
        outer:
        for (Run r : runs) {
            lastColor = r.color;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.text.length(); ) {
                int cp = r.text.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                float cw = measure(ch, r.bold, size);
                if (used + cw + ellW > width) {
                    if (sb.length() > 0) {
                        out.add(new Run(sb.toString(), r.color, r.bold));
                    }
                    break outer;
                }
                sb.append(ch);
                used += cw;
                i += Character.charCount(cp);
            }
            if (sb.length() > 0) {
                out.add(new Run(sb.toString(), r.color, r.bold));
            }
        }
        out.add(new Run("…", lastColor, false));
        return new Line(merge(out), used + ellW);
    }

    /** 同一行里相邻、同样式的片段合并成一段（减少绘制次数）。 */
    private static List<Run> merge(List<Run> runs) {
        List<Run> out = new ArrayList<>();
        for (Run r : runs) {
            if (!out.isEmpty()) {
                Run prev = out.get(out.size() - 1);
                if (prev.color == r.color && prev.bold == r.bold) {
                    out.set(out.size() - 1, new Run(prev.text + r.text, r.color, r.bold));
                    continue;
                }
            }
            out.add(r);
        }
        return out;
    }

    /** 断行单元：换行符、空格、中文/全角单字、拉丁单词。 */
    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == ' ' || isCjk(cp)) {
                if (word.length() > 0) {
                    out.add(word.toString());
                    word.setLength(0);
                }
                out.add(new String(Character.toChars(cp)));
            } else {
                word.appendCodePoint(cp);
            }
        }
        if (word.length() > 0) {
            out.add(word.toString());
        }
        return out;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F) || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static FontId font(boolean bold) {
        return bold ? AtomUi.BOLD : AtomUi.REGULAR;
    }

    private static float measure(String text, boolean bold, float size) {
        return text.isEmpty() ? 0.0F : Text.width(text, font(bold), size);
    }

    // ------------------------------------------------------------------ 绘制

    /** 一行的高度（与 Text 单行布局一致）。 */
    public static float lineHeight(float size) {
        return Text.line("国", AtomUi.REGULAR, size, 0xFFFFFFFF, 0).size().height();
    }

    /** 画一行，y 为行顶部。 */
    public static void draw(Canvas canvas, Line line, float x, float y, float size) {
        float cx = x;
        for (Run r : line.runs) {
            if (r.text.isEmpty()) {
                continue;
            }
            var layout = Text.line(r.text, font(r.bold), size, r.color, 0);
            Text.draw(canvas, layout, cx, y);
            cx += layout.size().width();
        }
    }

    /** 单行便捷绘制：解析、截断、以 centerY 垂直居中。返回绘制宽度。 */
    public static float drawLine(Canvas canvas, String raw, int baseColor, float size,
                                 float x, float centerY, float maxWidth) {
        Line line = singleLine(parse(raw, baseColor), maxWidth, size);
        draw(canvas, line, x, centerY - lineHeight(size) / 2.0F, size);
        return line.width;
    }
}
