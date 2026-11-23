package cn.autoforged.module_shop.client.ui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextSanitizeTest {

    @Test
    @DisplayName("Text.sanitize 清洗 §#RRGGBB、§xRGB、§+Emoji 代理对、结尾孤立 § 与 PUA 私用区")
    void sanitizeStripsHexColorsSurrogatesTrailingSectionAndPua() {
        // 1. 十六进制色码 §#RRGGBB（大小写均剥离，不完整或非十六进制仅跳过 §#）
        assertEquals("传说饰品", Text.sanitize("§#FFAA00传说饰品"));
        assertEquals("冰霜法杖", Text.sanitize("§#1a2b3c冰霜§#DDEEFF法杖"));
        assertEquals("12345短", Text.sanitize("§#12345短"));
        assertEquals("GGGGGG非十六进制", Text.sanitize("§#GGGGGG非十六进制"));

        // 2. Spigot/BungeeCord §x§R§R§G§G§B§B
        assertEquals("传说", Text.sanitize("§x§f§f§a§a§0§0传说"));

        // 3. § 后紧跟 2 char UTF-16 代理对（Emoji / 扩展平面图标）：跳过完整码点，不留孤立低代理项 '?'
        assertEquals("传奇剑", Text.sanitize("§\uD83D\uDD25传奇剑"));
        assertEquals("扩展剑", Text.sanitize("§\uDB80\uDC00扩展剑"));
        assertEquals("火焰\uD83D\uDD25剑", Text.sanitize("火焰\uD83D\uDD25剑"));

        // 4. 结尾孤立 § 与空输入
        assertEquals("传奇剑", Text.sanitize("传奇剑§"));
        assertEquals("", Text.sanitize("§"));
        assertEquals("", Text.sanitize(""));
        assertEquals("", Text.sanitize(null));

        // 5. PUA 私用区（BMP + 补充平面）、变体选择符与零宽字符过滤
        assertEquals("牛肉干", Text.sanitize("\uE001\uF8FF牛肉\uFE0F\u200B\uFEFF干\uDB80\uDC00\uDBC0\uDC00"));
    }
}
