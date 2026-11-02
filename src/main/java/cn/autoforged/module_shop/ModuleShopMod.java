package cn.autoforged.module_shop;

import cn.autoforged.module_shop.menu.ModMenus;
import cn.autoforged.module_shop.sound.ModSounds;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 模组入口。
 *
 * <p>Forge 1.20.1 要求 mod 主类有**无参构造**（构造参数注入是 1.20.2+ 才有的），
 * 所以事件总线通过 {@link FMLJavaModLoadingContext} 取，配置在构造里注册。
 */
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