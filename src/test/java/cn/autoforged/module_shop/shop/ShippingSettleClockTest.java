package cn.autoforged.module_shop.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShippingSettleClockTest {

    @Test
    void normalDaylightCycleStaysOnDayTimeAndIgnoresSingleTimeSet() {
        ShippingSettleClock clock = new ShippingSettleClock();
        ShippingSettleClock.Sample s0 = clock.sample(true, 1000L, 1000L);
        assertFalse(s0.fallbackGameTime());
        assertEquals(0L, s0.cycleDay());

        // 正常推进
        for (int i = 1; i <= 200; i++) {
            ShippingSettleClock.Sample s = clock.sample(true, 1000L + i, 1000L + i);
            assertFalse(s.fallbackGameTime());
        }

        // 管理员手动 /time set day 一次不应误触发永昼兜底
        ShippingSettleClock.Sample afterOneSet = clock.sample(true, 1000L, 1201L);
        assertFalse(afterOneSet.fallbackGameTime());
        ShippingSettleClock.Sample next = clock.sample(true, 1001L, 1202L);
        assertFalse(next.fallbackGameTime());
    }

    @Test
    void gameruleDoDaylightCycleFalseImmediatelyFallsBackAndRecovers() {
        ShippingSettleClock clock = new ShippingSettleClock();
        ShippingSettleClock.Sample s0 = clock.sample(false, 6000L, 48_500L);
        assertTrue(s0.fallbackGameTime());
        assertTrue(s0.modeChanged());
        assertEquals(2L, s0.cycleDay());
        assertEquals(500L, s0.timeOfDay());

        // 恢复正常昼夜循环，连续前进 100 tick 后自动切回 dayTime
        ShippingSettleClock.Sample recovered = null;
        for (int i = 1; i <= 100; i++) {
            recovered = clock.sample(true, 6000L + i, 48_500L + i);
        }
        assertFalse(recovered.fallbackGameTime());
        assertTrue(recovered.modeChanged());
        assertEquals(0L, recovered.cycleDay());
    }

    @Test
    void frozenDayTimeModTriggersFallbackAfterThreshold() {
        ShippingSettleClock clock = new ShippingSettleClock();
        clock.sample(true, 6000L, 10_000L);

        // dayTime 锁死在 6000，gameTime 推进 99 tick 尚未触发
        ShippingSettleClock.Sample before = clock.sample(true, 6000L, 10_099L);
        assertFalse(before.fallbackGameTime());

        // 第 100 tick 触发永昼兜底，使用 gameTime 计算周期
        ShippingSettleClock.Sample trigger = clock.sample(true, 6000L, 10_100L);
        assertTrue(trigger.fallbackGameTime());
        assertTrue(trigger.modeChanged());
        assertEquals(10_100L, trigger.clockTick());
    }

    @Test
    void daytimeLoopCommandBlockTriggersFallbackAndRecoversOnlyOnNewDay() {
        ShippingSettleClock clock = new ShippingSettleClock();
        clock.sample(true, 1000L, 1000L);
        clock.sample(true, 12_000L, 12_000L);
        // 第 1 次拉回白天（不误判）
        assertFalse(clock.sample(true, 1000L, 12_001L).fallbackGameTime());
        clock.sample(true, 12_000L, 23_001L);
        // 第 2 次拉回白天且游戏日始终没进位 -> 判定循环锁永昼
        ShippingSettleClock.Sample locked = clock.sample(true, 1000L, 23_002L);
        assertTrue(locked.fallbackGameTime());
        assertTrue(locked.modeChanged());

        // 只要游戏日仍未跨过 0 -> 1，即便短暂推进 200 tick 也不抖动切回
        for (int i = 1; i <= 200; i++) {
            assertTrue(clock.sample(true, 1000L + i, 23_002L + i).fallbackGameTime());
        }

        // 真正跨入下一个游戏日后允许恢复
        clock.sample(true, 24_000L, 40_000L);
        ShippingSettleClock.Sample recovered = null;
        for (int i = 1; i <= 100; i++) {
            recovered = clock.sample(true, 24_000L + i, 40_000L + i);
        }
        assertFalse(recovered.fallbackGameTime());
    }

    @Test
    void remainingTicksHandlesNormalAndWrapAround() {
        assertEquals(2000L, ShippingSettleClock.remainingTicks(22_000L, 0L));
        assertEquals(0L, ShippingSettleClock.remainingTicks(24_000L, 0L));
        assertEquals(1000L, ShippingSettleClock.remainingTicks(5000L, 6000L));
        assertEquals(23_000L, ShippingSettleClock.remainingTicks(7000L, 6000L));
    }
}
