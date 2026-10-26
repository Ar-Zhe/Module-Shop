package cn.autoforged.server_shop_mod_1789689358.network;

import cn.autoforged.server_shop_mod_1789689358.ServerShopMod;
import cn.autoforged.server_shop_mod_1789689358.network.payload.ClientboundShopSyncPayload;
import cn.autoforged.server_shop_mod_1789689358.network.payload.ServerboundShopActionPayload;
import cn.autoforged.server_shop_mod_1789689358.shop.ShopManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = ServerShopMod.MODID)
public final class ModNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger("server_shop_mod/network");

    /** 客户端只有一个连接，一份组装器就够。 */
    private static final ChunkAssembler CLIENT_ASSEMBLER = new ChunkAssembler();
    /** 服务端要按玩家分开（多人同时开商店 / 导入模组时不能串包）。 */
    private static final Map<UUID, ChunkAssembler> SERVER_ASSEMBLERS = new ConcurrentHashMap<>();

    private ModNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // 版本从 1 提到 2：新旧 jar 混用时 NeoForge 会直接报「通道版本不一致」，
        // 而不是让旧客户端去解码新格式（那会变成一条看不懂的 custom_payload 解码崩溃）。
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToClient(
                ClientboundShopSyncPayload.TYPE,
                ClientboundShopSyncPayload.STREAM_CODEC,
                ModNetwork::handleSync);
        registrar.playToServer(
                ServerboundShopActionPayload.TYPE,
                ServerboundShopActionPayload.STREAM_CODEC,
                ModNetwork::handleAction);
    }

    // 客户端专用处理类只能通过 enqueueWork 内的 lambda 触达，避免专用服务器解析到 Screen。
    private static void handleSync(ClientboundShopSyncPayload payload, IPayloadContext context) {
        // 先在网络线程把分片拼起来（纯字节拷贝，不碰游戏状态），拼不齐就直接返回。
        byte[] raw = CLIENT_ASSEMBLER.accept(payload.syncId(), payload.index(), payload.total(), payload.data());
        if (raw == null) {
            return;
        }
        CompoundTag tag = decompress(raw, "clientbound");
        if (tag == null) {
            return;
        }
        context.enqueueWork(() -> cn.autoforged.server_shop_mod_1789689358.client.ClientPayloadHandler
                .handleSync(tag, context.player()));
    }

    private static void handleAction(ServerboundShopActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        UUID key = player.getUUID();
        ChunkAssembler assembler = SERVER_ASSEMBLERS.computeIfAbsent(key, k -> new ChunkAssembler());
        byte[] raw = assembler.accept(payload.syncId(), payload.index(), payload.total(), payload.data());
        if (raw == null) {
            return;
        }
        if (assembler.isEmpty()) {
            SERVER_ASSEMBLERS.remove(key, assembler);
        }
        CompoundTag tag = decompress(raw, "serverbound");
        if (tag != null) {
            ShopManager.handleAction(player, tag);
        }
    }

    /**
     * 解压 + 解析 NBT。
     *
     * <p>失败（数据被截断 / 超过自定义上限 / 不是合法 NBT）时**只记日志并丢掉这一包**：
     * 以前这种异常会在解码阶段直接把玩家踢下线（Network Protocol Error），
     * 现在最多是这一次操作没生效，玩家重开界面/重试即可，不会掉线。
     */
    private static CompoundTag decompress(byte[] raw, String direction) {
        try {
            return NbtTransport.decompress(raw);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("模块商店：忽略一个无法解析的 {} 数据包（{} 字节）：{}", direction, raw.length, e.toString());
            return null;
        }
    }
}
