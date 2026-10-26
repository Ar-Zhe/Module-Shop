package cn.autoforged.module_shop;

import cn.autoforged.module_shop.menu.ModMenus;
import cn.autoforged.module_shop.sound.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(ModuleShopMod.MODID)
public class ModuleShopMod {
    public static final String MODID = "module_shop";

    public ModuleShopMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModMenus.register(modEventBus);
        ModSounds.register(modEventBus);
    }
}
