package cn.hsc.shoputil;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 商店整合附属模组：合并 mall_pricing + shop_sync + shop_guard 功能。
 * - 新存档自动注入购物城定价 + 商店设置同步
 * - G 键密码门锁定管理功能
 * - K 键导出当前配置到参考文件
 * - P 键打开定价文件选择界面
 * - 内置配置文件，可被 Configd/Cloth Config 等模组直接修改
 */
@Mod(ShopUtilMod.MOD_ID)
public class ShopUtilMod {

    public static final String MOD_ID = "shop_util";

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("shop_util");
    }

    public ShopUtilMod(IEventBus modBus, ModContainer container) {
        // 注册配置（自动生成 shop_util-common.toml，Configd 可识别）
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, ShopUtilConfig.SPEC);

        // 延迟初始化配置文件（不阻塞启动，首次使用时再创建）
        // 但目录创建还是要做，否则其他代码读文件会报错
        try {
            Files.createDirectories(configDir());
            Files.createDirectories(configDir().resolve("pricing"));
            Files.createDirectories(configDir().resolve("profiles"));
        } catch (Exception ignored) {}

        // 服务端事件：新存档同步
        NeoForge.EVENT_BUS.addListener(ShopSyncHandler::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ShopSyncHandler::onRegisterCommands);

        // 客户端注册
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(ClientInit::onRegisterKeyMappings);
            ClientInit.registerClient();
        }

        Log.info("商店整合附属已加载（G键解锁/锁定，K键导出配置，P键选择定价文件）");
    }
}
