package cn.autoforged.module_shop.client.ui;

import net.minecraft.world.item.ItemStack;

/**
 * 物品名的显示清洗。
 *
 * <p>背景：不少食物 / 营养类模组会往物品名里塞**私有区字符（PUA）**当小图标（饥饿值、饱食度之类）。
 * 这些字符只有它们自带的字体有字形，我们的 Skija 文字渲染器没有 → 界面上会变成一串「□」方块。
 *
 * <p>所以商店界面里显示物品名时统一走 {@link #display(ItemStack)}：把不可渲染的图标字符去掉，
 * 只保留真正的名称文本。**不修改物品本身的数据**，只影响显示。
 */
public final class ItemNames {
    private ItemNames() {
    }

    /** 物品名（已清洗图标字符）。 */
    public static String display(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return clean(stack.getHoverName().getString());
    }

    /**
     * 去掉无法渲染的字符：
     * 控制字符、私有区（PUA）图标、变体选择符、零宽字符、BOM、以及常见的「图标空格」。
     */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            int len = Character.charCount(cp);
            i += len;
            if (isIconCodepoint(cp)) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        // 去掉因此产生的多余空格（图标前后常带空格）
        return out.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    private static boolean isIconCodepoint(int cp) {
        if (Character.isISOControl(cp)) {
            return true;
        }
        // 私有使用区：绝大多数模组图标都塞在这里
        if (cp >= 0xE000 && cp <= 0xF8FF) {
            return true;
        }
        if (cp >= 0xF0000 && cp <= 0xFFFFD) {
            return true;
        }
        if (cp >= 0x100000 && cp <= 0x10FFFD) {
            return true;
        }
        // 变体选择符 / 零宽字符 / BOM
        if (cp >= 0xFE00 && cp <= 0xFE0F) {
            return true;
        }
        return cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x200E || cp == 0x200F
                || cp == 0x2060 || cp == 0xFEFF;
    }
}
