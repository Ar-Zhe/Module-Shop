package cn.autoforged.module_shop.menu;

import cn.autoforged.module_shop.ModuleShopMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, ModuleShopMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<DepositMenu>> DEPOSIT =
            MENUS.register("deposit", () -> IMenuTypeExtension.<DepositMenu>create(
                    (id, inventory, data) -> DepositMenu.clientSide(id, inventory)));

    /** 管理员发放界面（物品槽 + 管理员背包）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<GrantMenu>> GRANT =
            MENUS.register("grant", () -> IMenuTypeExtension.<GrantMenu>create(
                    (id, inventory, data) -> GrantMenu.clientSide(id, inventory)));

    /**
     * 日程奖励界面（第八轮）：与发放界面**共用同一套容器逻辑**（3×9 物品槽 + 管理员背包），
     * 只是右侧不勾选玩家，而是显示「当前奖励」并可保存到某个日程日。
     */
    public static final DeferredHolder<MenuType<?>, MenuType<GrantMenu>> SCHEDULE_REWARD =
            MENUS.register("schedule_reward", () -> IMenuTypeExtension.<GrantMenu>create(
                    (id, inventory, data) -> GrantMenu.scheduleClientSide(id, inventory)));

    /**
     * 兑换码奖励配置界面：同样复用发放容器（3×9 物品槽 + 玩家列表 + 星币），
     * 但「发送」变成「保存到兑换码草稿」，保存后回到兑换码生成器继续编辑。
     */
    public static final DeferredHolder<MenuType<?>, MenuType<GrantMenu>> REDEEM_REWARD =
            MENUS.register("redeem_reward", () -> IMenuTypeExtension.<GrantMenu>create(
                    (id, inventory, data) -> GrantMenu.redeemClientSide(id, inventory)));

    /** 出货箱：每个玩家自己的空间（槽位数随等级变化，由打开时下发的数据决定）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<ShippingBinMenu>> SHIPPING =
            MENUS.register("shipping_bin", () -> IMenuTypeExtension.<ShippingBinMenu>create(
                    (id, inventory, data) -> ShippingBinMenu.clientSide(id, inventory,
                            Math.max(9, data.readVarInt()))));

    /** 管理页「添加条目」的批量幽灵槽界面（最多 27 种物品）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<BatchAddMenu>> BATCH_ADD =
            MENUS.register("batch_add", () -> IMenuTypeExtension.<BatchAddMenu>create(
                    (id, inventory, data) -> BatchAddMenu.clientSide(id, inventory)));

    private ModMenus() {
    }

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
