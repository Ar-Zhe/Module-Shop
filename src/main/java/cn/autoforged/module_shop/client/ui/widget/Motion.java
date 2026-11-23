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

    /** 悬停移出的时间常数：比移入慢一倍，鼠标划过时留下一点“余韵”，不会一闪就灭。 */
    public static final float HOVER_OUT_MS = 130.0F;

    /** 悬停专用：移入快（{@link #HOVER_MS}）、移出慢（{@link #HOVER_OUT_MS}）。 */
    public static float hover(float current, boolean on, float dtMs) {
        return approach(current, on ? 1.0F : 0.0F, dtMs, on ? HOVER_MS : HOVER_OUT_MS);
    }

    /** 轻微回弹的缓出（结尾冲过 1 一点再回来），用于弹窗出现、图标切换。 */
    public static float easeOutBack(float t) {
        float x = Math.max(0.0F, Math.min(1.0F, t)) - 1.0F;
        float s = 1.4F;
        return 1.0F + x * x * ((s + 1.0F) * x + s);
    }

    /**
     * 弹簧（欠阻尼）：用于按下缩放的回弹。每帧 {@link #step} 一次，按真实时间积分，帧率无关。
     */
    public static final class Spring {
        private float value;
        private float velocity;

        public Spring(float initial) {
            this.value = initial;
        }

        /** stiffness 越大越快，damping 越小越弹；返回当前值。 */
        public float step(float target, float dtMs, float stiffness, float damping) {
            float remaining = Math.max(0.0F, Math.min(50.0F, dtMs)) / 1000.0F;
            // 分小步积分，避免低帧率时发散
            while (remaining > 0.0F) {
                float h = Math.min(remaining, 1.0F / 240.0F);
                float force = -stiffness * (value - target) - damping * velocity;
                velocity += force * h;
                value += velocity * h;
                remaining -= h;
            }
            if (Math.abs(value - target) < 0.0005F && Math.abs(velocity) < 0.005F) {
                value = target;
                velocity = 0.0F;
            }
            return value;
        }

        public float value() {
            return value;
        }
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
