package cn.autoforged.server_shop_mod_1789689358.api;

import org.jetbrains.annotations.Nullable;

/**
 * 统一结果类型：**永远不要用 void / boolean 静默表示失败**。
 *
 * @param <T> 成功时的返回值类型（无返回值用 {@code Void}）
 */
public final class ApiResult<T> {
    private final boolean ok;
    private final ApiErrorCode code;
    private final String message;
    @Nullable
    private final T value;

    private ApiResult(boolean ok, ApiErrorCode code, String message, @Nullable T value) {
        this.ok = ok;
        this.code = code;
        this.message = message == null ? "" : message;
        this.value = value;
    }

    public static <T> ApiResult<T> ok(@Nullable T value) {
        return new ApiResult<>(true, ApiErrorCode.NONE, "", value);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(true, ApiErrorCode.NONE, "", null);
    }

    public static <T> ApiResult<T> fail(ApiErrorCode code, String message) {
        return new ApiResult<>(false, code, message, null);
    }

    public boolean isOk() {
        return this.ok;
    }

    public ApiErrorCode code() {
        return this.code;
    }

    /** 失败原因的简短说明（日志用；不要拿它做逻辑判断）。 */
    public String message() {
        return this.message;
    }

    @Nullable
    public T value() {
        return this.value;
    }

    /** 成功时返回值，失败时抛 {@link IllegalStateException}（只在"确定成功"的地方用）。 */
    public T require() {
        if (!this.ok) {
            throw new IllegalStateException("API 调用失败: " + this.code + " " + this.message);
        }
        return this.value;
    }

    @Override
    public String toString() {
        return this.ok ? "ok(" + this.value + ")" : "fail(" + this.code + ": " + this.message + ")";
    }
}