package cn.autoforged.module_shop.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 大体积 NBT 的传输工具：**先 gzip 压缩，再按 {@link #CHUNK} 分片**（分片重组见 {@link ChunkAssembler}）。
 *
 * <p>背景（真实事故）：原版 {@code FriendlyByteBuf.readNbt()} 用的是
 * {@code NbtAccounter.create(2097152)} —— 也就是**单个 NBT 超过 2 MiB 就直接抛
 * 「Tried to read NBT tag that was too big」**，客户端会被踢下线并生成
 * 「Minecraft Network Protocol Error Report」。购物城整包导入上千件物品后，
 * {@code shop_sync} 快照（mall + adminMall + mail + 商店/收购池…）很容易超 2 MiB，
 * 于是玩家一开商店就掉线。
 *
 * <p>这里不再使用 {@code ByteBufCodecs.COMPOUND_TAG}（它走的就是 2 MiB 的 readNbt），
 * 而是自己压缩成字节数组下发/上报，客户端用**自定义上限**的 {@link NbtAccounter} 解压，
 * 体积再大也不会触发原版上限；同时压缩率很高（商店数据里有大量重复物品 id），
 * 实际带宽比原来更省。
 */
public final class NbtTransport {
    /** 单片最大字节数：必须远小于单帧上限（原版长度前缀是 3 字节 VarInt，最大 2097151 字节）。 */
    public static final int CHUNK = 512 * 1024;
    /** 解压时的内存上限：正常快照远小于此值，只用来兜住异常/恶意包。 */
    public static final long MAX_TAG_BYTES = 64L * 1024L * 1024L;

    private NbtTransport() {
    }

    /** NBT -> gzip 字节（{@link NbtIo#writeCompressed} 内部已含 gzip，这里不要再套一层）。 */
    public static byte[] compress(CompoundTag tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            NbtIo.writeCompressed(tag, out);
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真的 IO 失败；真出问题说明 NBT 本身异常，交给上层记录。
            throw new IllegalStateException("模块商店：压缩同步数据失败", e);
        }
        return out.toByteArray();
    }

    /** gzip 字节 -> NBT；解压失败/超限时抛 {@link IOException}，由调用方忽略这一包（不踢人）。 */
    public static CompoundTag decompress(byte[] raw) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(raw)) {
            return NbtIo.readCompressed(in, NbtAccounter.create(MAX_TAG_BYTES));
        }
    }

    /** 需要几个包（空数据也算 1 个，保证对端一定收到一次）。 */
    public static int chunkCount(int length) {
        return Math.max(1, (length + CHUNK - 1) / CHUNK);
    }

    /** 第 index 片（单包时直接复用原数组，不做拷贝）。 */
    public static byte[] slice(byte[] raw, int index) {
        int from = index * CHUNK;
        int to = Math.min(raw.length, from + CHUNK);
        if (from <= 0 && to >= raw.length) {
            return raw;
        }
        return Arrays.copyOfRange(raw, from, to);
    }
}
