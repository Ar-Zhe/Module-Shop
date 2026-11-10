package cn.hsc.shoputil;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * 客户端初始化：注册按键、客户端事件。
 */
public final class ClientInit {

    public static void registerClient() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        cn.hsc.shoputil.client.ClientKeyBindings.register();
    }

    public static void onRegisterKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        event.register(cn.hsc.shoputil.client.ClientKeyBindings.TOGGLE_GUARD);
        event.register(cn.hsc.shoputil.client.ClientKeyBindings.EXPORT_CONFIG);
        event.register(cn.hsc.shoputil.client.ClientKeyBindings.OPEN_PRICING_GUI);
    }
}
