package cn.autoforged.module_shop.network.payload;

import cn.autoforged.module_shop.network.ModNetwork;
import cn.autoforged.module_shop.network.NbtTransport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端 -> 服务端。所有商店操作都通过这个包，data 里至少包含 "action"。
 *
 * <p>与 {@link ClientboundShopSyncPayload} 一样是**压缩 + 可分包**的：
 * 「整包导入模组」这类操作会把上千件物品的 NBT 一次性发上来，同样会撞上原版 2 MiB 的
 * {@code readNbt} 上限（旧写法会让玩家在导入时被踢下线），所以两边都走 {@link NbtTransport}。
 *
 * <p>Forge 1.20.1 没有 {@code StreamCodec}，编解码手写在 {@link #encode}/{@link #decode} 里。
 */
public record ServerboundShopActionPayload(int syncId, int index, int total, byte[] data) {

    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    /** 上报一份 NBT（自动压缩 + 分包）。 */
    public static void send(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        byte[] raw = NbtTransport.compress(tag);
        int total = NbtTransport.chunkCount(raw.length);
        int id = NEXT_ID.incrementAndGet() & 0x7FFFFFFF;
        for (int i = 0; i < total; i++) {
            ModNetwork.CHANNEL.sendToServer(
                    new ServerboundShopActionPayload(id, i, total, NbtTransport.slice(raw, i)));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(syncId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    public static ServerboundShopActionPayload decode(FriendlyByteBuf buf) {
        int syncId = buf.readVarInt();
        int index = buf.readVarInt();
        int total = buf.readVarInt();
        byte[] data = buf.readByteArray();
        return new ServerboundShopActionPayload(syncId, index, total, data);
    }
}
