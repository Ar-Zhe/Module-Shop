package cn.autoforged.module_shop.client.ui.widget;

/**
 * 与帧率无关的动画小工具。
 *
 * <p>{@link #approach} 是指数趋近：给定“时间常数”，任何帧率下动画速度都一致；
 * 足够接近目标时直接吸附，避免无限拖尾。
 */
public final class Motion {
    /** 悬停高亮的时间常数（毫秒）。 */
    public static final float HOVER_MS = 60.0F;
    /** 按下/选中切换的时间常数（毫秒）。 */
    public static final float PRESS_MS = 40.0F;

    private Motion() {
    }

    /**
     * 让 current 以时间常数 tauMs 指数趋近 target。
     *
     * @param dtMs 本帧时长（毫秒），内部限制在 0..50，切后台回来不会跳变
     */
    public static float approach(float current, float target, float dtMs, float tauMs) {
        if (tauMs <= 0.0F) {
            return target;
        }
        float dt = Math.max(0.0F, Math.min(50.0F, dtMs));
        float next = target + (current - target) * (float) Math.exp(-dt / tauMs);
        return Math.abs(next - target) < 0.002F ? target : next;
    }

    public static float easeOutCubic(float t) {
        float u = 1.0F - Math.max(0.0F, Math.min(1.0F, t));
        return 1.0F - u * u * u;
    }

    /** 帧时钟：每个界面持有一个，每帧调用一次 {@link #tick()} 取得 dt（毫秒）。 */
    public static final class Clock {
        private long last;

        public float tick() {
            long now = System.nanoTime();
            float dt = last == 0L ? 0.0F : (now - last) / 1_000_000.0F;
            last = now;
            return dt;
        }
    }
}
