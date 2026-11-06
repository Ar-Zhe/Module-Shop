package cn.autoforged.module_shop.shop.auction;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.BrushItem;
import net.neoforged.neoforge.common.Tags;

/**
 * 拍卖行商品分类。按物品的实际属性判断（类继承 + NeoForge 通用标签 + 数据组件），
 * 模组物品只要继承了原版基类或打了 {@code c:} 通用标签就能被正确归类，其余进「其他」。
 *
 * <p>判断顺序有讲究：附魔书 / 药水 / 食物这类「特征明确」的先判；武器在工具之前
 * （{@code c:tools} 同时包含武器）；方块放在材料之后（矿物块等也有材料标签时算材料更合理的情况很少，
 * 这里让方块优先于「其他」即可）。客户端与服务端共用这一份实现。
 */
public enum AuctionCategory {
    ALL("all"),
    BLOCK("block"),
    WEAPON("weapon"),
    TOOL("tool"),
    ARMOR("armor"),
    FOOD("food"),
    POTION("potion"),
    BOOK("book"),
    MATERIAL("material"),
    OTHER("other");

    private final String id;

    AuctionCategory(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String langKey() {
        return "gui.module_shop.auction.cat." + id;
    }

    public static AuctionCategory byId(String id) {
        for (AuctionCategory c : values()) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return ALL;
    }

    public static AuctionCategory of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return OTHER;
        }
        Item item = stack.getItem();
        if (item == Items.ENCHANTED_BOOK) {
            return BOOK;
        }
        if (item instanceof PotionItem || item instanceof TippedArrowItem || stack.is(Tags.Items.POTIONS)) {
            return POTION;
        }
        if (stack.has(DataComponents.FOOD) || stack.is(Tags.Items.FOODS)) {
            return FOOD;
        }
        if (item instanceof SwordItem || item instanceof BowItem || item instanceof CrossbowItem
                || item instanceof TridentItem || item instanceof MaceItem
                || stack.is(Tags.Items.MELEE_WEAPON_TOOLS) || stack.is(Tags.Items.RANGED_WEAPON_TOOLS)) {
            return WEAPON;
        }
        if (item instanceof ArmorItem || item instanceof ShieldItem || item instanceof ElytraItem
                || stack.is(Tags.Items.ARMORS)) {
            return ARMOR;
        }
        if (item instanceof DiggerItem || item instanceof ShearsItem || item instanceof FishingRodItem
                || item instanceof FlintAndSteelItem || item instanceof BrushItem || stack.is(Tags.Items.TOOLS)) {
            return TOOL;
        }
        if (stack.is(Tags.Items.INGOTS) || stack.is(Tags.Items.GEMS) || stack.is(Tags.Items.NUGGETS)
                || stack.is(Tags.Items.DUSTS) || stack.is(Tags.Items.RAW_MATERIALS) || stack.is(Tags.Items.RODS)
                || stack.is(Tags.Items.LEATHERS) || stack.is(Tags.Items.STRINGS) || stack.is(Tags.Items.DYES)
                || stack.is(Tags.Items.FEATHERS) || stack.is(Tags.Items.BONES) || stack.is(Tags.Items.SLIME_BALLS)
                || stack.is(Tags.Items.ENDER_PEARLS) || stack.is(Tags.Items.GUNPOWDERS) || stack.is(Tags.Items.BRICKS)
                || item == Items.PAPER || item == Items.FLINT || item == Items.BLAZE_POWDER
                || item == Items.NETHER_STAR || item == Items.PHANTOM_MEMBRANE || item == Items.EXPERIENCE_BOTTLE) {
            return MATERIAL;
        }
        if (item instanceof BlockItem) {
            return BLOCK;
        }
        return OTHER;
    }
}
