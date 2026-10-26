package cn.autoforged.module_shop.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片重组（配合 {@link NbtTransport}）：把一次同步拆成的好几个包拼回完整字节。
 *
 * <p>要点：
 * <ul>
 *   <li>同一组的每个包带 {@code syncId}（一次发送一个自增 id）+ {@code index}/{@code total}；</li>
 *   <li>网络线程按顺序投递，这里仍然按 index 落位，乱序也能拼；</li>
 *   <li>服务端要为**每个玩家**各留一份（多人同时操作时不能串包），客户端只需一份；</li>
 *   <li>超时（{@link #TIMEOUT_MS}）或超量（{@link #MAX_TOTAL}）就整组丢弃 ——
 *       宁可这次不同步（下一次快照会重发），也不要因为半截数据把客户端搞崩。</li>
 * </ul>
 */
public final class ChunkAssembler {
    /** 同时最多缓存几组没收齐的分片，超过就先丢最旧的。 */
    private static final int MAX_GROUPS = 4;
    /** 一组分片最多等多久（毫秒）。 */
    private static final long TIMEOUT_MS = 30_000L;
    /** 一组分片拼起来最多多少字节（压缩后）。 */
    private static final long MAX_TOTAL = 64L * 1024L * 1024L;

    private final Map<Integer, Group> groups = new ConcurrentHashMap<>();

    /**
     * 收下一片。
     *
     * @return 集齐时返回完整字节；否则返回 {@code null}（调用方直接忽略这一包，什么都不用做）
     */
    public byte[] accept(int id, int index, int total, byte[] part) {
        if (part == null || total <= 0 || index < 0 || index >= total) {
            return null;
        }
        if (total == 1) {
            // 单包（绝大多数操作都是）：不占缓存，直接给出去。
            return part.length > MAX_TOTAL ? null : part;
        }
        long now = System.currentTimeMillis();
        this.groups.entrySet().removeIf(e -> now - e.getValue().stamp > TIMEOUT_MS);
        if (this.groups.size() >= MAX_GROUPS && !this.groups.containsKey(id)) {
            evictOldest();
        }
        Group group = this.groups.computeIfAbsent(id, k -> new Group(total, now));
        synchronized (group) {
            if (group.parts.length != total) {
                // 同一个 id 被复用了（上一次没收齐）：丢弃旧的，重新开始。
                this.groups.remove(id);
                return null;
            }
            group.stamp = now;
            if (group.parts[index] == null) {
                group.parts[index] = part;
                group.received++;
                group.size += part.length;
            }
            if (group.size > MAX_TOTAL) {
                this.groups.remove(id);
                return null;
            }
            if (group.received < total) {
                return null;
            }
            this.groups.remove(id);
            byte[] all = new byte[(int) group.size];
            int offset = 0;
            for (byte[] piece : group.parts) {
                if (piece == null) {
                    // 理论上到不了这里（received == total 就说明每一片都有）；保险起见放弃这一组。
                    return null;
                }
                System.arraycopy(piece, 0, all, offset, piece.length);
                offset += piece.length;
            }
            return all;
        }
    }

    /** 是否已经没有在等的分片（服务端据此把玩家的缓存条目清掉，避免长期占内存）。 */
    public boolean isEmpty() {
        return this.groups.isEmpty();
    }

    private void evictOldest() {
        int oldestId = Integer.MIN_VALUE;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<Integer, Group> entry : this.groups.entrySet()) {
            if (entry.getValue().stamp < oldest) {
                oldest = entry.getValue().stamp;
                oldestId = entry.getKey();
            }
        }
        if (oldestId != Integer.MIN_VALUE) {
            this.groups.remove(oldestId);
        }
    }

    private static final class Group {
        private final byte[][] parts;
        private long stamp;
        private int received;
        private long size;

        private Group(int total, long stamp) {
            this.parts = new byte[total][];
            this.stamp = stamp;
        }
    }
}
