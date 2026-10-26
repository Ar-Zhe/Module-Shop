package cn.autoforged.module_shop.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 单个玩家的签到数据（需求 7：签到模块）。
 * <p>
 * 用「epochDay」记录日期（本地时区当天），因此跨时区/跨夏令时也不会错位；
 * 连续天数 streak 在断签（当天 &gt; 上次签到 + 1 天）时归零重算。
 */
public final class SignInData {
    /** 最后一次签到日（epochDay；0 = 从未签到）。 */
    public long lastSignDay;
    /** 当前连续签到天数。 */
    public int streak;
    /** 历史最长连续天数。 */
    public int bestStreak;
    /** 已签到/已补签的日期集合（epochDay），只保留最近 400 天。 */
    public final Set<Long> signedDays = new LinkedHashSet<>();
    /** 本轮连续签到已经领过的里程奖励天数（避免重复领取）。 */
    public final Set<Integer> claimedMilestones = new LinkedHashSet<>();

    public boolean signedOn(long day) {
        return signedDays.contains(day);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("lastSignDay", lastSignDay);
        tag.putInt("streak", streak);
        tag.putInt("bestStreak", bestStreak);
        ListTag days = new ListTag();
        long[] arr = signedDays.stream().mapToLong(Long::longValue).toArray();
        tag.putLongArray("signedDays", arr);
        int[] claimed = claimedMilestones.stream().mapToInt(Integer::intValue).toArray();
        tag.putIntArray("claimedMilestones", claimed);
        return tag;
    }

    public static SignInData load(CompoundTag tag, HolderLookup.Provider registries) {
        SignInData data = new SignInData();
        data.lastSignDay = tag.getLong("lastSignDay");
        data.streak = tag.getInt("streak");
        data.bestStreak = tag.getInt("bestStreak");
        for (long day : tag.getLongArray("signedDays")) {
            data.signedDays.add(day);
        }
        for (int day : tag.getIntArray("claimedMilestones")) {
            data.claimedMilestones.add(day);
        }
        data.trim();
        return data;
    }

    /** 只保留最近 400 天，避免长年累月把存档撑大。 */
    public void trim() {
        if (signedDays.size() <= 400) {
            return;
        }
        List<Long> sorted = new ArrayList<>(signedDays);
        sorted.sort(Long::compareTo);
        while (sorted.size() > 400) {
            signedDays.remove(sorted.remove(0));
        }
    }

    /** 供界面使用的已签到日期数组（最近 62 天，足够覆盖日历前后一个月）。 */
    public long[] recentDays(long today) {
        return signedDays.stream().filter(d -> d >= today - 62 && d <= today + 31)
                .mapToLong(Long::longValue).sorted().toArray();
    }

    /** 判断某天是否在合法补签范围内（今天之前、且不超过允许天数）。 */
    public boolean canMakeup(long day, long today, int rangeDays) {
        return day < today && day >= today - rangeDays && !signedOn(day);
    }

    /** 兼容旧接口名的空实现，占位用。 */
    public static SignInData empty() {
        return new SignInData();
    }

    /** NBT 标签名（供 SavedData 使用）。 */
    public static final String TAG = "signIn";

    static {
        // 保持 Tag 引用，避免 import 被优化掉时误报（编译期常量，无运行时开销）。
        assert Tag.TAG_COMPOUND == 10;
    }
}
