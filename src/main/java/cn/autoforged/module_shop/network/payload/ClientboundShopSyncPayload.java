package cn.autoforged.module_shop.network.payload;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.network.NbtTransport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端 -> 客户端。kind=snapshot 时同步整个商店界面数据；kind=input 时打开数值/文本输入界面。
 *
 * <p><b>注意</b>：一个逻辑包可能被拆成多个物理包（见 {@link NbtTransport} / {@code ChunkAssembler}）——
 * NBT 先 gzip 再按 512 KiB 切片，每片一个包，共用同一个 {@code syncId}，
 * 客户端集齐后才解析。这样再大的快照也不会撞上原版 {@code readNbt} 的 2 MiB 上限
 * （旧写法会让玩家一开商店就掉线，报「Failed to decode packet clientbound/minecraft:custom_payload」）。
 */
public record ClientboundShopSyncPayload(int syncId, int index, int total, byte[] data) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientboundShopSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModuleShopMod.MODID, "shop_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundShopSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ClientboundShopSyncPayload::syncId,
                    ByteBufCodecs.VAR_INT, ClientboundShopSyncPayload::index,
                    ByteBufCodecs.VAR_INT, ClientboundShopSyncPayload::total,
                    ByteBufCodecs.BYTE_ARRAY, ClientboundShopSyncPayload::data,
                    ClientboundShopSyncPayload::new);

    /** 一次发送的组 id（客户端只认同一组的片）。 */
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    /** 下发一份 NBT（自动压缩 + 分包）。所有原来的 {@code PacketDistributor.sendToPlayer(player, ...)} 都改走这里。 */
    public static void send(ServerPlayer player, CompoundTag tag) {
        if (player == null || tag == null) {
            return;
        }
        byte[] raw = NbtTransport.compress(tag);
        int total = NbtTransport.chunkCount(raw.length);
        int id = NEXT_ID.incrementAndGet() & 0x7FFFFFFF;
        for (int i = 0; i < total; i++) {
            PacketDistributor.sendToPlayer(player, new ClientboundShopSyncPayload(id, i, total,
                    NbtTransport.slice(raw, i)));
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
