package cn.autoforged.server_shop_mod_1789689358.api;

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
 */
public final class ApiVersion {
    /** 当前 API 版本（语义化版本）。 */
    public static final String API_VERSION = "1.0.0";
    /** 主版本号：外部可用它做依赖检查。 */
    public static final int API_MAJOR = 1;

    private ApiVersion() {
    }

    private static String runtime() {
        return API_VERSION;
    }

    /** 供外部打印/日志用。 */
    public static String describe() {
        return "server_shop_api " + runtime();
    }
}