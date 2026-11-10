package cn.hsc.shoputil.mixin;

import cn.hsc.shoputil.client.GuardState;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第二道防线：未解锁时拦截管理类网络包。
 */
@Mixin(targets = "cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload", remap = false)
public class ServerboundShopActionPayloadMixin {

    @Inject(method = "send", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onSend(CompoundTag data, CallbackInfo ci) {
        if (GuardState.isUnlocked()) return;
        String action = data.getString("action");
        if (isAdminAction(action)) {
            ci.cancel();
        }
    }

    private static boolean isAdminAction(String action) {
        if (action == null || action.isEmpty()) return false;
        String a = action.toLowerCase();
        // 只拦截明确的管理员操作；不拦截通用的 refresh / regen_daily / gen_redpacket 等玩家或功能操作
        return a.contains("admin")
            || a.contains("signinsettings") || a.contains("demandsettings") || a.contains("shippinglimits")
            || a.contains("gen_item") || a.contains("grant") || a.contains("layout")
            || a.contains("addmodule") || a.contains("removemodule") || a.contains("applylayout")
            || a.contains("editsettings") || a.contains("clearstall") || a.contains("mallimport");
    }
}
