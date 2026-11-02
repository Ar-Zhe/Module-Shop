package cn.autoforged.module_shop.network.payload;

import cn.autoforged.module_shop.network.ModNetwork;
import cn.autoforged.module_shop.network.NbtTransport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端 -> 客户端。kind=snapshot 时同步整个商店界面数据；kind=input 时打开数值/文本输入界面。
 *
 * <p><b>注意</b>：一个逻辑包可能被拆成多个物理包（见 {@link NbtTransport} / {@code ChunkAssembler}）——
 * NBT 先 gzip 再按 512 KiB 切片，每片一个包，共用同一个 {@code syncId}，
 * 客户端集齐后才解析。这样再大的快照也不会撞上原版 {@code readNbt} 的 2 MiB 上限
 * （旧写法会让玩家一开商店就掉线）。
 *
 * <p>Forge 1.20.1 没有 {@code StreamCodec}，编解码手写在 {@link #encode}/{@link #decode} 里。
 */
public record ClientboundShopSyncPayload(int syncId, int index, int total, byte[] data) {

    /** 一次发送的组 id（客户端只认同一组的片）。 */
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    /** 下发一份 NBT（自动压缩 + 分包）。 */
    public static void send(ServerPlayer player, CompoundTag tag) {
        if (player == null || tag == null) {
            return;
        }
        byte[] raw = NbtTransport.compress(tag);
        int total = NbtTransport.chunkCount(raw.length);
        int id = NEXT_ID.incrementAndGet() & 0x7FFFFFFF;
        for (int i = 0; i < total; i++) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new ClientboundShopSyncPayload(id, i, total, NbtTransport.slice(raw, i)));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(syncId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    public static ClientboundShopSyncPayload decode(FriendlyByteBuf buf) {
        int syncId = buf.readVarInt();
        int index = buf.readVarInt();
        int total = buf.readVarInt();
        byte[] data = buf.readByteArray();
        return new ClientboundShopSyncPayload(syncId, index, total, data);
    }
}
