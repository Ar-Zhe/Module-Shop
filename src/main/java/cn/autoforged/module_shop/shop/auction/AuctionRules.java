package cn.autoforged.module_shop.shop.auction;

import cn.autoforged.module_shop.Config;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 上架前的物品合法性检查（服务端权威）。
 *
 * <p>检查项：黑名单（物品 / 标签 / 整个模组，<b>容器与收纳袋里装的物品同样递归检查</b>，
 * 防止把违禁品装进潜影盒绕过）、绑定物品、自定义数据（特殊 NBT）、序列化体积上限。
 */
public final class AuctionRules {
    /** 容器套容器的最大递归深度（原版潜影盒不能装潜影盒，模组物品可能可以）。 */
    private static final int MAX_DEPTH = 4;
    /** 1.20.1 原版物品常用标准 NBT 键：判定 {@code auctionAllowCustomData=false} 时不误伤原版附魔/耐久/改名物品。 */
    private static final Set<String> VANILLA_TAG_KEYS = Set.of(
            "Damage", "Enchantments", "StoredEnchantments", "RepairCost", "display",
            "CustomModelData", "Potion", "CustomPotionEffects", "CustomPotionColor",
            "AttributeModifiers", "HideFlags", "Unbreakable", "CanDestroy", "CanPlaceOn",
            "BlockEntityTag", "Items", "Pages", "author", "title", "filtered_title",
            "generation", "resolved", "Fireworks", "Explosion", "BucketVariantTag",
            "EntityTag", "SkullOwner", "Trim", "Decorations", "map", "map_scale_direction",
            "LodestonePos", "LodestoneDimension", "LodestoneTracked", "Charged",
            "ChargedProjectiles", "Instrument", "Recipes"
    );

    private AuctionRules() {
    }

    /** @return 违规原因的语言键；合法时返回 null。 */
    @Nullable
    public static String check(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return "msg.module_shop.auction.err_empty";
        }
        String nested = checkRecursive(stack, 0);
        if (nested != null) {
            return nested;
        }
        int limit = Config.INSTANCE.auctionMaxItemNbtBytes.get();
        try {
            CompoundTag encoded = stack.save(new CompoundTag());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.write(encoded, new DataOutputStream(baos));
            if (baos.size() > limit) {
                return "msg.module_shop.auction.err_nbt_size";
            }
        } catch (RuntimeException | IOException broken) {
            // 连序列化都失败的物品（数据异常）一律拒绝，避免存进存档后读不出来。
            return "msg.module_shop.auction.err_nbt_broken";
        }
        return null;
    }

    @Nullable
    private static String checkRecursive(ItemStack stack, int depth) {
        if (isBlacklisted(stack)) {
            return depth == 0 ? "msg.module_shop.auction.err_blacklist" : "msg.module_shop.auction.err_blacklist_inner";
        }
        if (!Config.INSTANCE.auctionAllowBound.get() && isBound(stack)) {
            return "msg.module_shop.auction.err_bound";
        }
        if (!Config.INSTANCE.auctionAllowCustomData.get() && hasCustomData(stack)) {
            return "msg.module_shop.auction.err_custom_data";
        }
        if (depth >= MAX_DEPTH) {
            return null;
        }
        for (ItemStack inner : innerItems(stack)) {
            String err = checkRecursive(inner, depth + 1);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private static List<ItemStack> innerItems(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        CompoundTag blockEntity = BlockItem.getBlockEntityData(stack);
        if (blockEntity != null && blockEntity.contains("Items", Tag.TAG_LIST)) {
            collectItems(blockEntity.getList("Items", Tag.TAG_COMPOUND), out);
        }
        CompoundTag root = stack.getTag();
        if (root != null && (stack.getItem() instanceof BundleItem || root.contains("Items", Tag.TAG_LIST))
                && root.contains("Items", Tag.TAG_LIST)) {
            collectItems(root.getList("Items", Tag.TAG_COMPOUND), out);
        }
        return out;
    }

    private static void collectItems(ListTag list, List<ItemStack> out) {
        for (int i = 0; i < list.size(); i++) {
            ItemStack inner = ItemStack.of(list.getCompound(i));
            if (!inner.isEmpty()) {
                out.add(inner);
            }
        }
    }

    private static boolean hasCustomData(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return false;
        }
        for (String key : tag.getAllKeys()) {
            if (!VANILLA_TAG_KEYS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlacklisted(ItemStack stack) {
        Item item = stack.getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        String idText = id.toString();
        for (String raw : Config.INSTANCE.auctionBlacklist.get()) {
            String rule = raw == null ? "" : raw.trim();
            if (rule.isEmpty()) {
                continue;
            }
            if (rule.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(rule.substring(1));
                if (tagId != null && stack.is(TagKey.create(Registries.ITEM, tagId))) {
                    return true;
                }
            } else if (rule.endsWith(":*")) {
                if (id.getNamespace().equals(rule.substring(0, rule.length() - 2))) {
                    return true;
                }
            } else if (rule.equals(idText)) {
                return true;
            }
        }
        return false;
    }

    /** 绑定物品：带「绑定诅咒」附魔，或物品 NBT 顶层含有配置的绑定标记键。 */
    public static boolean isBound(ItemStack stack) {
        if (EnchantmentHelper.hasBindingCurse(stack)) {
            return true;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return false;
        }
        for (String marker : Config.INSTANCE.auctionBoundMarkers.get()) {
            if (marker != null && !marker.isBlank() && tag.contains(marker.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 可选的拍卖时长（分钟，去重、过滤非法值；第一个为默认）。配置全部非法时退回 [60]。 */
    public static List<Integer> durationOptions() {
        List<Integer> out = new ArrayList<>();
        for (String raw : Config.INSTANCE.auctionDurationOptions.get()) {
            try {
                int minutes = Integer.parseInt(raw.trim());
                if (minutes > 0 && minutes <= 60 * 24 * 30 && !out.contains(minutes)) {
                    out.add(minutes);
                }
            } catch (NumberFormatException ignored) {
                // 跳过非法项
            }
        }
        if (out.isEmpty()) {
            out.add(60);
        }
        return out;
    }

    /** 时长选项排序后的展示顺序（界面用升序排列，默认值单独下发）。 */
    public static List<Integer> sortedDurations() {
        return new ArrayList<>(new TreeSet<>(durationOptions()));
    }

    /** 上架手续费 = 固定 + 起拍价 × 百分比（向上取整，饱和不溢出）。 */
    public static long listingFee(long startPrice) {
        long fixed = Math.max(0L, Config.INSTANCE.auctionListingFeeFixed.get());
        double percent = Config.INSTANCE.auctionListingFeePercent.get();
        long byPercent = percent <= 0.0D ? 0L : (long) Math.min(Long.MAX_VALUE / 2, Math.ceil(startPrice * percent / 100.0D));
        long sum = fixed + byPercent;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /** 成交手续费 = 成交价 × 百分比（向上取整，不超过成交价）。 */
    public static long saleFee(long price) {
        double percent = Config.INSTANCE.auctionFeePercent.get();
        if (percent <= 0.0D || price <= 0L) {
            return 0L;
        }
        return Math.min(price, (long) Math.ceil(price * (percent / 100.0D)));
    }
}
