package cn.autoforged.server_shop_mod_1789689358.network.payload;

import cn.autoforged.server_shop_mod_1789689358.ServerShopMod;
import cn.autoforged.server_shop_mod_1789689358.network.NbtTransport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端 -> 服务端。所有商店操作都通过这个包，data 里至少包含 "action"。
 *
 * <p>与 {@link ClientboundShopSyncPayload} 一样是**压缩 + 可分包**的：
 * 「整包导入模组」这类操作会把上千件物品的 NBT 一次性发上来，同样会撞上原版 2 MiB 的
 * {@code readNbt} 上限（旧写法会让玩家在导入时被踢下线），所以两边都走 {@link NbtTransport}。
 */
public record ServerboundShopActionPayload(int syncId, int index, int total, byte[] data)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerboundShopActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ServerShopMod.MODID, "shop_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundShopActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ServerboundShopActionPayload::syncId,
                    ByteBufCodecs.VAR_INT, ServerboundShopActionPayload::index,
                    ByteBufCodecs.VAR_INT, ServerboundShopActionPayload::total,
                    ByteBufCodecs.BYTE_ARRAY, ServerboundShopActionPayload::data,
                    ServerboundShopActionPayload::new);

    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    /** 上报一份 NBT（自动压缩 + 分包）。所有原来的 {@code PacketDistributor.sendToServer(...)} 都改走这里。 */
    public static void send(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        byte[] raw = NbtTransport.compress(tag);
        int total = NbtTransport.chunkCount(raw.length);
        int id = NEXT_ID.incrementAndGet() & 0x7FFFFFFF;
        for (int i = 0; i < total; i++) {
            PacketDistributor.sendToServer(new ServerboundShopActionPayload(id, i, total,
                    NbtTransport.slice(raw, i)));
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
