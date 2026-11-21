package cn.autoforged.module_shop;

import cn.autoforged.module_shop.menu.ModMenus;
import cn.autoforged.module_shop.sound.ModSounds;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ModuleShopMod.MODID)
public class ModuleShopMod {
    public static final String MODID = "module_shop";

    public ModuleShopMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        cn.autoforged.module_shop.network.ModNetwork.register();
        ModMenus.register(modEventBus);
        ModSounds.register(modEventBus);
    }
}
