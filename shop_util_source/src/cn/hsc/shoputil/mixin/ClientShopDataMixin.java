package cn.hsc.shoputil.mixin;

import cn.hsc.shoputil.client.GuardState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端同步商店数据后，未解锁时强制 canAdmin=false。
 * 目标类用字符串指定（不在本模组常量池里硬引用商店本体类），字段用 @Shadow 直接绑到本体字段。
 */
@Mixin(targets = "cn.autoforged.module_shop.client.ClientShopData", remap = false)
public class ClientShopDataMixin {

    @Shadow
    private static boolean canAdmin;

    @Inject(method = "update", at = @At("TAIL"), remap = false)
    private static void afterUpdate(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider, CallbackInfo ci) {
        if (!GuardState.isUnlocked()) {
            canAdmin = false;
        }
    }
}
