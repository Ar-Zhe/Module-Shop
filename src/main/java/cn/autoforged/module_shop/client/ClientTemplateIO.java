package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.config.ShopTemplate;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * 客户端侧的模板文件读写（导出 / 保存为默认 / 列出可导入文件）。
 *
 * <p>所有路径都从 {@link Minecraft#gameDirectory} 推出来，即
 * {@code &lt;game dir&gt;/config/moduleshop/}；整合包作者只要把
 * {@code shop_config.json} 放进这个目录，其他玩家开新世界就能拿到同一套商店。
 *
 * <p>这里**只碰文件**，不判断权限、不改世界数据 —— 导入必须回到服务端由管理员确认后才生效。
 */
public final class ClientTemplateIO {
    private ClientTemplateIO() {
    }

    public static Path configDir() {
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDir = minecraft == null ? Path.of(".") : minecraft.gameDirectory.toPath();
        return gameDir.resolve("config");
    }

    public static Path defaultFile() {
        return ShopTemplateStore.defaultFile(configDir());
    }

    public static Path exportsDir() {
        return ShopTemplateStore.exportsDir(configDir());
    }

    /** 读取默认模板（供界面显示"模板是否存在 / 名称 / 条目数"）。 */
    public static ShopTemplateStore.ReadResult readDefault() {
        return ShopTemplateStore.read(defaultFile());
    }

    /** 读取一个导出文件。 */
    public static ShopTemplateStore.ReadResult read(Path file) {
        return ShopTemplateStore.read(file);
    }

    /** 列出可导入的文件：默认模板（若存在）+ exports 目录（新的在前）。 */
    public static List<Path> listImportable() {
        List<Path> files = new java.util.ArrayList<>();
        Path def = defaultFile();
        if (Files.isRegularFile(def)) {
            files.add(def);
        }
        files.addAll(ShopTemplateStore.listExports(configDir()));
        return files;
    }

    /** 把服务端下发的模板写成导出文件，返回写出的路径（失败 {@code null}）。 */
    public static Path writeExport(ShopTemplate template, String prefix) {
        return ShopTemplateStore.exportTo(configDir(), template, prefix);
    }

    /** 把服务端下发的模板写成默认模板（`shop_config.json`）。 */
    public static String writeDefault(ShopTemplate template) {
        return ShopTemplateStore.write(defaultFile(), template);
    }

    /** 模板 JSON（发送给服务端前用）。 */
    public static String toJson(ShopTemplate template) {
        return template == null ? "" : template.toJson().toString();
    }

    /** 解析来自服务端 / 文件的 JSON。 */
    public static ShopTemplate parse(String json) {
        return ShopTemplate.fromJson(json);
    }

    // ------------------------------------------------------------------ 提示（服务端下发后）

    private static void chat(net.minecraft.network.chat.Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    /** 导出成功：写 exports/ 并告知路径。 */
    public static void notifyExported(String json, String name) {
        ShopTemplate template = ShopTemplate.fromJson(json == null ? "" : json);
        if (template == null) {
            chat(net.minecraft.network.chat.Component.translatable("msg.module_shop.cfg_bad_json"));
            return;
        }
        Path file = writeExport(template, "moduleshop_shop");
        if (file == null) {
            chat(net.minecraft.network.chat.Component.translatable("msg.module_shop.cfg_export_fail"));
            return;
        }
        chat(net.minecraft.network.chat.Component.translatable("msg.module_shop.cfg_exported",
                String.valueOf(file)));
    }

    /** 保存为默认模板：写 shop_config.json 并告知路径。 */
    public static void notifySavedDefault(String json, String name) {
        ShopTemplate template = ShopTemplate.fromJson(json == null ? "" : json);
        if (template == null) {
            chat(net.minecraft.network.chat.Component.translatable("msg.module_shop.cfg_bad_json"));
            return;
        }
        String error = writeDefault(template);
        if (error != null && !error.isEmpty()) {
            chat(net.minecraft.network.chat.Component.translatable("msg.module_shop.cfg_save_fail"));
            return;
        }
        chat(net.minecraft.network.chat.Component.translatable("msg.module_shop.cfg_saved_default",
                String.valueOf(defaultFile())));
    }

    /** 导入预览：交给确认界面；没有界面时只提示统计。 */
    public static void showPreview(net.minecraft.nbt.CompoundTag data) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof cn.autoforged.module_shop.client.screen.TemplatePreviewScreen preview) {
            preview.onPreview(data);
        } else {
            chat(net.minecraft.network.chat.Component.translatable("msg.module_shop.cfg_preview_chat",
                    data.getInt("total"), data.getInt("valid"), data.getInt("invalid")));
        }
    }
}
