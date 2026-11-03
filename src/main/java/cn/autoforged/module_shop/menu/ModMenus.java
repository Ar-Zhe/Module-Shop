package cn.autoforged.module_shop.menu;

import cn.autoforged.module_shop.ModuleShopMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, ModuleShopMod.MODID);

    public static final RegistryObject<MenuType<DepositMenu>> DEPOSIT =
            MENUS.register("deposit", () -> IForgeMenuType.<DepositMenu>create(
                    (id, inventory, data) -> DepositMenu.clientSide(id, inventory)));

    /** 管理员发放界面（物品槽 + 管理员背包）。 */
    public static final RegistryObject<MenuType<GrantMenu>> GRANT =
            MENUS.register("grant", () -> IForgeMenuType.<GrantMenu>create(
                    (id, inventory, data) -> GrantMenu.clientSide(id, inventory)));

    /**
     * 日程奖励界面（第八轮）：与发放界面**共用同一套容器逻辑**（3×9 物品槽 + 管理员背包），
     * 只是右侧不勾选玩家，而是显示「当前奖励」并可保存到某个日程日。
     */
    public static final RegistryObject<MenuType<GrantMenu>> SCHEDULE_REWARD =
            MENUS.register("schedule_reward", () -> IForgeMenuType.<GrantMenu>create(
                    (id, inventory, data) -> GrantMenu.scheduleClientSide(id, inventory)));

    /**
     * 兑换码奖励配置界面：同样复用发放容器（3×9 物品槽 + 玩家列表 + 星币），
     * 但「发送」变成「保存到兑换码草稿」，保存后回到兑换码生成器继续编辑。
     */
    public static final RegistryObject<MenuType<GrantMenu>> REDEEM_REWARD =
            MENUS.register("redeem_reward", () -> IForgeMenuType.<GrantMenu>create(
                    (id, inventory, data) -> GrantMenu.redeemClientSide(id, inventory)));

    /** 出货箱：每个玩家自己的空间（槽位数随等级变化，由打开时下发的数据决定）。 */
    public static final RegistryObject<MenuType<ShippingBinMenu>> SHIPPING =
            MENUS.register("shipping_bin", () -> IForgeMenuType.<ShippingBinMenu>create(
                    (id, inventory, data) -> ShippingBinMenu.clientSide(id, inventory,
                            Math.max(9, data != null && data.readableBytes() > 0 ? data.readVarInt() : 9))));

    /** 管理页「添加条目」的批量幽灵槽界面（最多 27 种物品）。 */
    public static final RegistryObject<MenuType<BatchAddMenu>> BATCH_ADD =
            MENUS.register("batch_add", () -> IForgeMenuType.<BatchAddMenu>create(
                    (id, inventory, data) -> BatchAddMenu.clientSide(id, inventory)));

    private ModMenus() {
    }

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
