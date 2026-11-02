package cn.autoforged.module_shop.sound;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.data.Rarity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/**
 * 自定义音效。每日商店翻牌时按稀有度播放对应文件：
 * legendary=传说.ogg、epic=史诗.ogg、rare=稀有.ogg、common=普通.ogg。
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, ModuleShopMod.MODID);

    public static final RegistryObject<SoundEvent> RARITY_LEGENDARY = sound("rarity_legendary");
    public static final RegistryObject<SoundEvent> RARITY_EPIC = sound("rarity_epic");
    public static final RegistryObject<SoundEvent> RARITY_RARE = sound("rarity_rare");
    public static final RegistryObject<SoundEvent> RARITY_COMMON = sound("rarity_common");
    /** 需求 10：界面点击音效，对应 sounds/fy.ogg。 */
    public static final RegistryObject<SoundEvent> UI_CLICK = sound("ui_click");

    private ModSounds() {
    }

    private static RegistryObject<SoundEvent> sound(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(
                        new ResourceLocation(ModuleShopMod.MODID, name)));
    }

    public static SoundEvent forRarity(Rarity rarity) {
        return switch (rarity) {
            case LEGENDARY -> RARITY_LEGENDARY.get();
            case EPIC -> RARITY_EPIC.get();
            case RARE -> RARITY_RARE.get();
            case COMMON -> RARITY_COMMON.get();
        };
    }

    public static void register(IEventBus modEventBus) {
        SOUND_EVENTS.register(modEventBus);
    }
}
