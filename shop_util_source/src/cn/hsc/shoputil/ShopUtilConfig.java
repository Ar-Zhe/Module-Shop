package cn.hsc.shoputil;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件：可被 Configd / Cloth Config 等模组直接读取修改。
 * 修改后立即生效（部分设置下次打开商店时生效）。
 */
public final class ShopUtilConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    // ── 商店守卫（密码门）──
    public static final ModConfigSpec.ConfigValue<String> PASSWORD;
    public static final ModConfigSpec.BooleanValue GUARD_ENABLED;

    // ── 商店设置同步 ──
    public static final ModConfigSpec.BooleanValue SYNC_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SYNC_EXCLUDE_FIELDS;

    // ── 购物城定价注入 ──
    public static final ModConfigSpec.BooleanValue PRICING_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> PRICING_FILE;
    public static final ModConfigSpec.ConfigValue<String> SYNC_PROFILE;

    // ── 标签页记忆 ──
    public static final ModConfigSpec.BooleanValue TAB_MEMORY_ENABLED;

    // ── 界面风格 ──
    public static final ModConfigSpec.EnumValue<UiStyle> UI_STYLE;

    public enum UiStyle {
        OPAQUE,   // 完全不透明深色背景（避开视差模糊）
        SIMPLE    // 简约风，透明无背景（画在最上层）
    }

    static {
        BUILDER.push("商店守卫（密码门）");
        GUARD_ENABLED = BUILDER
                .comment("是否启用密码门功能")
                .define("guard_enabled", true);
        PASSWORD = BUILDER
                .comment("管理员密码（修改后立即生效）")
                .define("password", "000000");
        BUILDER.pop();

        BUILDER.push("商店设置同步");
        SYNC_ENABLED = BUILDER
                .comment("新存档自动同步商店设置")
                .define("sync_enabled", true);
        SYNC_EXCLUDE_FIELDS = BUILDER
                .comment("同步时排除的字段（逗号分隔）")
                .define("sync_exclude", "mallPool,mallRecent,accounts,signIns,auctions,demands,apiRequests");
        BUILDER.pop();

        BUILDER.push("购物城定价注入");
        PRICING_ENABLED = BUILDER
                .comment("新存档自动注入购物城定价")
                .define("pricing_enabled", true);
        PRICING_FILE = BUILDER
                .comment("使用的定价文件名（在 config/shop_util/pricing/ 目录下）")
                .define("pricing_file", "default.csv");
        SYNC_PROFILE = BUILDER
                .comment("使用的参考配置文件名（在 config/shop_util/profiles/ 目录下）")
                .define("sync_profile", "default.dat");
        BUILDER.pop();

        BUILDER.push("标签页记忆");
        TAB_MEMORY_ENABLED = BUILDER
                .comment("关闭商店后下次打开是否自动回到上次停留的标签页")
                .define("tab_memory_enabled", true);
        BUILDER.pop();

        BUILDER.push("界面风格");
        UI_STYLE = BUILDER
                .comment("界面风格：OPAQUE=不透明深色背景（避开视差模糊），SIMPLE=简约透明风")
                .defineEnum("ui_style", UiStyle.OPAQUE);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * 手动保存配置到磁盘（P界面修改后调用）。
     */
    public static void save() {
        SPEC.save();
    }
}
