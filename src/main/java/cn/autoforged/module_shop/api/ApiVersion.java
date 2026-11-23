package cn.autoforged.module_shop.api;

/**
 * 公共 API 的版本信息。
 *
 * <p><b>模组版本与 API 版本相互独立</b>：模组升级（如 1.0.0 → 1.16.0）时 API 版本可能不变；
 * 只有接口签名/语义发生**不兼容**变化时才提升主版本号。
 *
 * <p>兼容约定：
 * <ul>
 *     <li>同一主版本内**只增不改**：新增方法会保留旧方法（至少一个大版本标 {@code @Deprecated}）；</li>
 *     <li>接口不暴露内部实现类（{@code ShopManager} / {@code ShopSavedData} / {@code PlayerAccount}）；</li>
 *     <li>API 层不改变存档结构与网络协议。</li>
 * </ul>
 *
 * <p><b>兼容范围</b>（同一主版本内只增不改，附属对旧版本编译后在新版本上照样能跑）：
 * <ul>
 *     <li>{@code cn.autoforged.module_shop.api.**}（不含 {@code api.internal}）；</li>
 *     <li>附属页面用的 UI 套件：{@code client.ui.AtomUi}（REGULAR / BOLD / available）、
 *     {@code client.ui.render.Draw / Text / SkiaFrame}、{@code client.ui.widget.Button / SkiaTextField /
 *     Motion / ScrollController / UiComponent} 的 public 成员。</li>
 * </ul>
 * 万一附属仍和新版本不兼容（例如它用了上面以外的类），商店会停用该附属页面并打开它的回退界面，不会崩溃。
 */
public final class ApiVersion {
    /** 当前 API 版本（语义化版本）。 */
    public static final String API_VERSION = "1.5.0";
    /** 主版本号：外部可用它做依赖检查。 */
    public static final int API_MAJOR = 1;
    /**
     * 次版本号：1.1 起新增客户端扩展点 {@link cn.autoforged.module_shop.api.client.ShopModules}；
     * 1.2 起 {@link cn.autoforged.module_shop.api.client.ShopTheme} 支持皮肤（渐变端点、柔光、弹窗底板等）；
     * 1.3 起新增 UI v2 物品层；
     * 1.5 起新增双账本/实体货币公共 API（{@link cn.autoforged.module_shop.api.view.CurrencyUnit} /
     * {@link cn.autoforged.module_shop.api.view.CurrencyTierView} 及 {@link EconomyApi} / {@link RewardApi} /
     * {@link cn.autoforged.module_shop.api.client.AddonPageHost} 实体货币与附属默认货币扩展）。
     */
    public static final int API_MINOR = 5;

    private ApiVersion() {
    }

    private static String runtime() {
        return API_VERSION;
    }

    /** 运行期主版本号（解析 {@link #runtime()} 字符串以防止 javac 编译期常量内联）。 */
    public static int runtimeMajor() {
        return parseVersionPart(runtime(), 0, API_MAJOR);
    }

    /** 运行期次版本号（解析 {@link #runtime()} 字符串以防止 javac 编译期常量内联）。 */
    public static int runtimeMinor() {
        return parseVersionPart(runtime(), 1, API_MINOR);
    }

    /** 检查运行期 API 是否与 {@code major} 同主版本且次版本不低于 {@code minor}。 */
    public static boolean atLeast(int major, int minor) {
        return runtimeMajor() == major && runtimeMinor() >= minor;
    }

    private static int parseVersionPart(String version, int index, int fallback) {
        if (version == null || version.isBlank()) {
            return fallback;
        }
        String[] parts = version.trim().split("\\.");
        if (index < 0 || index >= parts.length) {
            return fallback;
        }
        String digits = parts[index].replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 供外部打印/日志用。 */
    public static String describe() {
        return "module_shop_api " + runtime();
    }
}