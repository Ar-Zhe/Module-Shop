package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.sound.ModSounds;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * 界面点击音效（客户端本地设置）。
 * <p>
 * 默认沿用模组自带的 {@code module_shop:ui_click}（sounds/fy.ogg），
 * 也可以换成几款更"轻"的原版音效（木质轻点 / 石质清脆 / 机械咔哒 / 轻打点 / 清脆铃 / 紫水晶 / 经验球 / 翻书），
 * 在「提醒音效设置」界面点一下即切换并**当场试听**，选择保存在 {@code config/module_shop_click_sound.txt}。
 */
public final class ClickSound {
    /** 一款可选音效：显示名 key、声音 id（{@code custom} = 模组自带）、音量、音高。 */
    public record Option(String key, String id, float volume, float pitch) {
    }

    /** 可选音效列表（第一个是模组自带音效）。 */
    public static final Option[] OPTIONS = {
            new Option("custom", "custom", 1.0F, 1.0F),
            new Option("wooden", "minecraft:block.wooden_button.click_on", 0.45F, 1.35F),
            new Option("ui", "minecraft:ui.button.click", 0.40F, 1.20F),
            new Option("stone", "minecraft:block.stone_button.click_on", 0.35F, 1.55F),
            new Option("lever", "minecraft:block.lever.click", 0.30F, 1.70F),
            new Option("hat", "minecraft:block.note_block.hat", 0.40F, 1.70F),
            new Option("chime", "minecraft:block.note_block.chime", 0.35F, 1.50F),
            new Option("amethyst", "minecraft:block.amethyst_block.chime", 0.30F, 1.70F),
            new Option("orb", "minecraft:entity.experience_orb.pickup", 0.30F, 1.60F),
            new Option("book", "minecraft:item.book.page_turn", 0.45F, 1.25F),
    };

    private static final String FILE_NAME = "module_shop_click_sound.txt";
    /** 配置版本：改动默认点击音效时 +1，老配置会自动切回新默认音。 */
    private static final int CONFIG_VERSION = 2;
    private static boolean loaded;
    private static int index;

    private ClickSound() {
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        int version = 0;
        int saved = -1;
        try {
            Path path = configPath();
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("sound=")) {
                        saved = indexOf(trimmed.substring("sound=".length()).trim());
                    } else if (trimmed.startsWith("version=")) {
                        try {
                            version = Integer.parseInt(trimmed.substring("version=".length()).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (version < CONFIG_VERSION) {
            // 默认点击音效换过（模组自带的 fy.ogg 已替换成新的点击音），老配置一律切回新默认音。
            index = 0;
            save();
            return;
        }
        if (saved >= 0) {
            index = saved;
        }
    }

    private static int indexOf(String name) {
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i].key().equalsIgnoreCase(name) || OPTIONS[i].id().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public static Option current() {
        ensureLoaded();
        return OPTIONS[Math.max(0, Math.min(index, OPTIONS.length - 1))];
    }

    public static String currentKey() {
        return current().key();
    }

    /** 切换到下一款并返回当前款（设置界面点击时用）。 */
    public static Option next() {
        ensureLoaded();
        setIndex((index + 1) % OPTIONS.length);
        return current();
    }

    public static void setIndex(int value) {
        index = Math.max(0, Math.min(value, OPTIONS.length - 1));
        save();
    }

    private static void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, "# Module Shop UI click sound (client side)" + System.lineSeparator()
                    + "version=" + CONFIG_VERSION + System.lineSeparator()
                    + "sound=" + current().key() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** 播放当前点击音效。 */
    public static void play() {
        ensureLoaded();
        Option option = current();
        try {
            SoundEvent event;
            if ("custom".equals(option.id())) {
                event = ModSounds.UI_CLICK.get();
            } else {
                ResourceLocation id = ResourceLocation.tryParse(option.id());
                event = id == null ? null : BuiltInRegistries.SOUND_EVENT.get(id);
            }
            if (event == null) {
                return;
            }
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(event, option.pitch(), option.volume()));
        } catch (Exception ignored) {
        }
    }

    /** 设置界面显示的本地化名字。 */
    public static net.minecraft.network.chat.Component displayName() {
        return net.minecraft.network.chat.Component.translatable(
                "gui.module_shop.clicksound." + currentKey());
    }
}
