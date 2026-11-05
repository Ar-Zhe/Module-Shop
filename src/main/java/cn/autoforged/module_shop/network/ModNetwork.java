package cn.autoforged.module_shop.network;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.network.payload.ClientboundShopSyncPayload;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import cn.autoforged.module_shop.shop.ShopManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络层（Forge 1.20.1）。
 *
 * <p>原来是 NeoForge 的 payload 系统（{@code PayloadRegistrar} + {@code StreamCodec}），
 * 移植到 Forge 需要用 {@link SimpleChannel}：每个包自己实现 {@code encode/decode}，
 * 处理函数拿 {@code NetworkEvent.Context} 手动 {@code enqueueWork} 切回主线程。
 *
 * <p>协议版本固定为 {@value #PROTOCOL}：新旧 jar 混用时 {@link SimpleChannel} 会直接断开
 * （"channel version mismatch"），而不是让旧客户端去解码新格式导致协议错误踢人。
 */
public final class ModNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger("module_shop/network");

    /** 协议版本（改包结构时必须同步加一）。 */
    public static final String PROTOCOL = "2";

    /** 客户端只有一个连接，一份组装器就够。 */
    private static final ChunkAssembler CLIENT_ASSEMBLER = new ChunkAssembler();
    /** 服务端要按玩家分开（多人同时开商店 / 导入模组时不能串包）。 */
    private static final Map<UUID, ChunkAssembler> SERVER_ASSEMBLERS = new ConcurrentHashMap<>();

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(ModuleShopMod.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private ModNetwork() {
    }

    /** 由 mod 构造函数在 FMLCommonSetup 之前调用一次。 */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++,
                ClientboundShopSyncPayload.class,
                ClientboundShopSyncPayload::encode,
                ClientboundShopSyncPayload::decode,
                (payload, ctxSupplier) -> {
                    var ctx = ctxSupplier.get();
                    var sender = ctx.getSender();
                    byte[] raw = CLIENT_ASSEMBLER.accept(payload.syncId(), payload.index(),
                            payload.total(), payload.data());
                    ctx.setPacketHandled(true);
                    if (raw == null) {
                        return;
                    }
                    CompoundTag tag = decompress(raw, "clientbound", NbtTransport.MAX_TAG_BYTES);
                    if (tag == null) {
                        return;
                    }
                    // 客户端处理类（Screen 等）只能在主线程触达，避免专用服务器解析到客户端类。
                    ctx.enqueueWork(() -> cn.autoforged.module_shop.client.ClientPayloadHandler
                            .handleSync(tag));
                });
        CHANNEL.registerMessage(id++,
                ServerboundShopActionPayload.class,
                ServerboundShopActionPayload::encode,
                ServerboundShopActionPayload::decode,
                (payload, ctxSupplier) -> {
                    var ctx = ctxSupplier.get();
                    ctx.setPacketHandled(true);
                    ServerPlayer player = ctx.getSender();
                    if (player == null) {
                        return;
                    }
                    UUID key = player.getUUID();
                    ChunkAssembler assembler = SERVER_ASSEMBLERS.computeIfAbsent(key,
                            k -> new ChunkAssembler(NbtTransport.SERVERBOUND_MAX_BYTES));
                    byte[] raw = assembler.accept(payload.syncId(), payload.index(),
                            payload.total(), payload.data());
                    if (raw == null) {
                        return;
                    }
                    if (assembler.isEmpty()) {
                        SERVER_ASSEMBLERS.remove(key, assembler);
                    }
                    CompoundTag tag = decompress(raw, "serverbound", NbtTransport.SERVERBOUND_MAX_BYTES);
                    if (tag != null) {
                        ctx.enqueueWork(() -> ShopManager.handleAction(player, tag));
                    }
                });
    }

    /** 玩家下线：丢掉他还没收齐的上报分片（否则半截数据会一直挂在静态表里，直到下次他再发包）。 */
    public static void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            SERVER_ASSEMBLERS.remove(playerId);
        }
    }

    /**
     * 解压 + 解析 NBT。
     *
     * <p>失败（数据被截断 / 超过自定义上限 / 不是合法 NBT）时**只记日志并丢掉这一包**：
     * 以前这种异常会在解码阶段直接把玩家踢下线（Network Protocol Error），
     * 现在最多是这一次操作没生效，玩家重开界面/重试即可，不会掉线。
     */
    private static CompoundTag decompress(byte[] raw, String direction, long maxTagBytes) {
        try {
            return NbtTransport.decompress(raw, maxTagBytes);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("模块商店：忽略一个无法解析的 {} 数据包（{} 字节）：{}",
                    direction, raw.length, e.toString());
            return null;
        }
    }
}
