package cn.hsc.shoputil;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 商店设置同步：新存档自动从 reference.dat 同步设置（购物城由定价模块负责）。
 * 按 K 键或 /shoputil export 导出当前存档配置到 reference.dat。
 */
public final class ShopSyncHandler {

    private static final String MARKER = ".shop_sync_done";

    private ShopSyncHandler() {}

    public static void onServerStarted(ServerStartedEvent event) {
        try {
            copyOnNewWorld(event.getServer());
        } catch (Throwable t) {
            Log.error("同步设置失败，已跳过", t);
        }
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static List<String> getExcludeFields() {
        String s = ShopUtilConfig.SYNC_EXCLUDE_FIELDS.get();
        return Arrays.asList(s.split(","));
    }

    public static synchronized void copyOnNewWorld(MinecraftServer server) {
        if (!ShopUtilConfig.SYNC_ENABLED.get()) {
            Log.info("同步功能已禁用，跳过");
            return;
        }

        Path marker = markerPath(server);
        if (Files.exists(marker)) {
            Log.info("本存档已同步过设置，跳过");
            return;
        }

        Path reference = configDir().resolve("profiles").resolve(ShopUtilConfig.SYNC_PROFILE.get());
        if (!Files.isRegularFile(reference)) {
            Log.info("参考配置文件不存在: " + reference + "，跳过同步");
            return;
        }

        CompoundTag data;
        try {
            CompoundTag outer = NbtIo.readCompressed(reference, NbtAccounter.unlimitedHeap());
            data = outer.getCompound("data");
        } catch (Exception e) {
            Log.error("读取参考文件失败: " + reference, e);
            return;
        }

        Object ref, current;
        try {
            Class<?> shopClass = Class.forName("cn.autoforged.module_shop.data.ShopSavedData");
            Method load = shopClass.getMethod("load", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
            ref = load.invoke(null, data, server.registryAccess());
            Method get = shopClass.getMethod("get", MinecraftServer.class);
            current = get.invoke(null, server);
        } catch (Throwable t) {
            Log.error("加载 module_shop 数据失败，跳过", t);
            return;
        }

        List<String> exclude = getExcludeFields();
        List<String> copied = new ArrayList<>();
        for (Field f : current.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (exclude.contains(f.getName())) continue;
            try {
                f.setAccessible(true);
                f.set(current, f.get(ref));
                copied.add(f.getName());
            } catch (Throwable ignored) {}
        }

        try { ((SavedData) current).setDirty(); } catch (Throwable ignored) {}
        try { Files.writeString(marker, "synced at " + new Date()); } catch (Exception ignored) {}
        Log.info("完成：同步了 " + copied.size() + " 个字段");

        // 同步完成后，执行定价注入
        MallPricingHandler.applyPricing(server);
    }

    public static synchronized String exportCurrent(MinecraftServer server) {
        try {
            Class<?> shopClass = Class.forName("cn.autoforged.module_shop.data.ShopSavedData");
            Method get = shopClass.getMethod("get", MinecraftServer.class);
            Object current = get.invoke(null, server);
            Method save = shopClass.getMethod("save", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
            CompoundTag data = (CompoundTag) save.invoke(current, new CompoundTag(), server.registryAccess());
            CompoundTag outer = new CompoundTag();
            outer.put("data", data);

            // 导出到 profiles/ 目录，用当前时间命名，不覆盖已有文件
            String fileName = "export_" + System.currentTimeMillis() + ".dat";
            Path outDir = configDir().resolve("profiles");
            Files.createDirectories(outDir);
            Path out = outDir.resolve(fileName);
            NbtIo.writeCompressed(outer, out);
            long size = Files.size(out);
            return "已导出当前配置到 profiles/" + fileName + " (" + size + " 字节)\n路径: " + out.toAbsolutePath();
        } catch (Throwable t) {
            Log.error("导出失败", t);
            return "导出失败: " + t.getMessage();
        }
    }

    private static Path markerPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MARKER);
    }

    private static Path configDir() {
        return ShopUtilMod.configDir();
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(net.minecraft.commands.Commands.literal("shoputil")
                .then(net.minecraft.commands.Commands.literal("export").requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            String r = exportCurrent(ctx.getSource().getServer());
                            ctx.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal("[shop_util] " + r));
                            return 1;
                        }))
                .then(net.minecraft.commands.Commands.literal("reload").requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            // 重新应用参考配置
                            applyReferenceProfile(server);
                            // 重新应用定价
                            MallPricingHandler.applyPricing(server);
                            ctx.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal("[shop_util] 已重新应用配置"));
                            return 1;
                        })));
    }

    /**
     * 手动应用参考配置（不检查 marker，强制覆盖）。
     */
    public static synchronized void applyReferenceProfile(MinecraftServer server) {
        String profileName = ShopUtilConfig.SYNC_PROFILE.get();
        if (profileName == null || profileName.isEmpty()) {
            Log.info("参考配置文件为空，跳过");
            return;
        }
        Path reference = configDir().resolve("profiles").resolve(profileName);
        if (!Files.isRegularFile(reference)) {
            Log.warn("参考配置文件不存在: " + reference);
            return;
        }

        CompoundTag data;
        try {
            CompoundTag outer = NbtIo.readCompressed(reference, NbtAccounter.unlimitedHeap());
            data = outer.getCompound("data");
        } catch (Exception e) {
            Log.error("读取参考文件失败: " + reference, e);
            return;
        }

        Object ref, current;
        try {
            Class<?> shopClass = Class.forName("cn.autoforged.module_shop.data.ShopSavedData");
            Method load = shopClass.getMethod("load", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
            ref = load.invoke(null, data, server.registryAccess());
            Method get = shopClass.getMethod("get", MinecraftServer.class);
            current = get.invoke(null, server);
        } catch (Throwable t) {
            Log.error("加载 module_shop 数据失败，跳过", t);
            return;
        }

        List<String> exclude = getExcludeFields();
        List<String> copied = new ArrayList<>();
        for (Field f : current.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (exclude.contains(f.getName())) continue;
            try {
                f.setAccessible(true);
                f.set(current, f.get(ref));
                copied.add(f.getName());
            } catch (Throwable ignored) {}
        }

        try { ((SavedData) current).setDirty(); } catch (Throwable ignored) {}
        Log.info("手动应用参考配置完成: 同步了 " + copied.size() + " 个字段");
    }
}
