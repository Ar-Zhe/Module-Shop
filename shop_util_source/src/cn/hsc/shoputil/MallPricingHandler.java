package cn.hsc.shoputil;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 购物城定价注入：新存档同步后，从选中的 CSV 文件读定价灌入购物城商品池。
 */
public final class MallPricingHandler {

    private MallPricingHandler() {}

    public static void applyPricing(MinecraftServer server) {
        if (!ShopUtilConfig.PRICING_ENABLED.get()) return;
        String fileName = ShopUtilConfig.PRICING_FILE.get();
        if (fileName == null || fileName.isEmpty()) {
            Log.info("定价文件为空，跳过注入");
            return;
        }

        Path csv = configDir().resolve("pricing").resolve(fileName);
        if (!Files.isRegularFile(csv)) {
            Log.warn("定价文件不存在: " + csv);
            return;
        }

        List<PlanEntry> entries = parseCsv(csv);
        Log.info("解析CSV: " + csv + " → " + entries.size() + " 条记录");
        if (entries.isEmpty()) {
            Log.info("定价文件为空，跳过");
            return;
        }

        try {
            Class<?> shopClass = Class.forName("cn.autoforged.module_shop.data.ShopSavedData");
            Method get = shopClass.getMethod("get", MinecraftServer.class);
            Object shop = get.invoke(null, server);

            // 遍历类继承链找 mallPool 字段
            Field mallPoolField = null;
            Class<?> cls = shopClass;
            while (cls != null && mallPoolField == null) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getName().equals("mallPool")) {
                        mallPoolField = f;
                        break;
                    }
                }
                cls = cls.getSuperclass();
            }

            if (mallPoolField == null) {
                Log.warn("未找到 mallPool 字段，定价注入失败");
                return;
            }

            mallPoolField.setAccessible(true);
            // mallPool 是 final 字段，不能重新赋值，要清空原 list 再 addAll
            @SuppressWarnings("unchecked")
            List<Object> pool = (List<Object>) mallPoolField.get(shop);
            pool.clear();
            int count = 0;
            int fail = 0;
            int sortIndex = 1;
            for (PlanEntry e : entries) {
                Object itemStack = createItemStack(e.itemId, 1);
                if (itemStack == null) { fail++; continue; }
                // MallItem(ItemStack item, long price, Rarity rarity, int dailyLimit)
                Class<?> rarityClass = Class.forName("cn.autoforged.module_shop.data.Rarity");
                Object rarity = Enum.valueOf((Class<Enum>) rarityClass, e.rarity.toUpperCase());
                Class<?> mallItemClass = Class.forName("cn.autoforged.module_shop.data.MallItem");
                Constructor<?> ctor = mallItemClass.getConstructor(ItemStack.class, long.class, rarityClass, int.class);
                Object mallItem = ctor.newInstance(itemStack, e.price, rarity, e.dailyLimit);
                // 照着老模组设置 stockMax 和 sort
                try {
                    java.lang.reflect.Field stockMaxField = mallItemClass.getField("stockMax");
                    stockMaxField.setInt(mallItem, e.dailyLimit);
                    java.lang.reflect.Field sortField = mallItemClass.getField("sort");
                    sortField.setInt(mallItem, sortIndex++);
                } catch (Exception ignored) {}
                pool.add(mallItem);
                count++;
            }

            // 标记 SavedData 为需要保存
            try {
                Method setDirty = shop.getClass().getMethod("setDirty");
                setDirty.invoke(shop);
            } catch (Exception ignored) {}

            Log.info("购物城定价注入完成: " + count + " 件成功, " + fail + " 件失败");
        } catch (Throwable t) {
            Log.error("购物城定价注入失败", t);
        }
    }

    private static Object createItemStack(String itemId, int count) {
        try {
            // 照着老版 mall_pricing 的方式，最简单可靠
            Class<?> rlClass = Class.forName("net.minecraft.resources.ResourceLocation");
            Method tryParse = rlClass.getMethod("tryParse", String.class);
            Object rl = tryParse.invoke(null, itemId);
            if (rl == null) {
                Log.warn("物品ID解析失败: " + itemId);
                return null;
            }
            Class<?> registryClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            java.lang.reflect.Field itemField = registryClass.getDeclaredField("ITEM");
            Object itemRegistry = itemField.get(null);
            Method getMethod = itemRegistry.getClass().getMethod("get", rlClass);
            Object item = getMethod.invoke(itemRegistry, rl);
            if (item == null) {
                Log.warn("物品不存在: " + itemId);
                return null;
            }
            Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack");
            // 老模组用的是单参数构造函数 new ItemStack(item)
            Constructor<?> ctor = stackClass.getConstructor(Class.forName("net.minecraft.world.level.ItemLike"));
            return ctor.newInstance(item);
        } catch (Exception e) {
            Log.warn("createItemStack 失败: " + itemId + " → " + e.getMessage());
            return null;
        }
    }

    private static List<PlanEntry> parseCsv(Path csv) {
        List<PlanEntry> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(csv), StandardCharsets.UTF_8))) {
            String line;
            reader.readLine(); // 跳过表头
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length < 4) continue;
                try {
                    PlanEntry e = new PlanEntry();
                    e.itemId = parts[0].trim();
                    e.price = Long.parseLong(parts[1].trim());
                    e.rarity = parts[2].trim();
                    e.dailyLimit = Integer.parseInt(parts[3].trim());
                    list.add(e);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.error("读取定价CSV失败: " + csv, e);
        }
        return list;
    }

    private static Path configDir() {
        return ShopUtilMod.configDir();
    }

    private static class PlanEntry {
        String itemId;
        long price;
        String rarity;
        int dailyLimit;
    }
}
