package cn.autoforged.module_shop.client;


import net.minecraftforge.fml.common.Mod;
import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent.ClientTickEvent;

/** 客户端按键处理（游戏事件总线）。 */
@Mod.EventBusSubscriber(modid = ModuleShopMod.MODID, value = Dist.CLIENT)
public final class ClientKeyHandler {
    private ClientKeyHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        while (ClientEvents.openShopKey().consumeClick()) {
            if (minecraft.screen == null) {
                CompoundTag tag = new CompoundTag();
                tag.putString("action", "request_data");
                tag.putString("tab", "daily");
                ServerboundShopActionPayload.send(tag);
            }
        }
    }
}
