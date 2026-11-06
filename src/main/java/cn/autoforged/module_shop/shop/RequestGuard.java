package cn.autoforged.module_shop.shop;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * 请求号去重（拍卖出价 / 一口价 / 上架、摊位购买 / 上架 / 补货共用）。
 *
 * <p>客户端每次提交带一个随机 {@code nonce}，同一玩家最近处理过的请求号不会再处理第二次，
 * 网络重发或连点都不会造成重复购买 / 重复出价。nonce = 0（旧客户端、公共 API、旧输入框流程）不参与去重。
 * 只在服务端主线程读写。
 */
public final class RequestGuard {
    private static final Map<UUID, Deque<Long>> NONCES = new HashMap<>();
    private static final int KEEP = 64;

    private RequestGuard() {
    }

    /** @return true 表示这是重复请求，调用方应直接忽略。 */
    public static boolean duplicate(ServerPlayer player, CompoundTag tag) {
        long nonce = tag.getLong("nonce");
        if (nonce == 0L || player == null) {
            return false;
        }
        Deque<Long> seen = NONCES.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        if (seen.contains(nonce)) {
            return true;
        }
        seen.addLast(nonce);
        while (seen.size() > KEEP) {
            seen.removeFirst();
        }
        return false;
    }

    public static void forget(UUID player) {
        NONCES.remove(player);
    }

    public static void clear() {
        NONCES.clear();
    }
}
