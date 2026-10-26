package cn.autoforged.server_shop_mod_1789689358.menu;

import cn.autoforged.server_shop_mod_1789689358.shop.ShippingRules;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 出货箱的容器：上面是玩家自己的出货箱槽位（按等级 9 / 18 / 27 格，每个玩家独立），
 * 下面是玩家背包 —— 与末影箱一样，别人看不到也拿不到。
 *
 * <p>槽位只接受**兑换规则里写了**的物品：不在规则里的物品 {@link Slot#mayPlace} 直接返回 false
 * （界面上表现为放不进去），服务端在快速移动时还会额外发一条说明
 * （{@link cn.autoforged.server_shop_mod_1789689358.shop.ShopManager} 里的出货箱逻辑）。
 */
public class ShippingBinMenu extends AbstractContainerMenu {
    /** 出货箱区域相对面板的坐标（客户端界面按同一套坐标画背景）。 */
    public static final int BIN_X = 8;
    public static final int BIN_Y = 38;
    /** 出货箱与背包之间的间距。 */
    public static final int BIN_PAD = 14;

    private final Container bin;
    private final int binSlots;

    public ShippingBinMenu(int id, Inventory playerInventory, Container bin, int binSlots) {
        super(ModMenus.SHIPPING.get(), id);
        this.bin = bin;
        this.binSlots = binSlots;
        int rows = Math.max(1, (binSlots + 8) / 9);
        for (int i = 0; i < binSlots; i++) {
            int col = i % 9;
            int row = i / 9;
            this.addSlot(new SellableSlot(bin, i, BIN_X + col * 18, BIN_Y + row * 18));
        }
        int invY = BIN_Y + rows * 18 + BIN_PAD;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, 9 + row * 9 + col, BIN_X + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, BIN_X + col * 18, invY + 58));
        }
    }

    /** 客户端用：只用来画界面，容器内容由服务端同步。 */
    public static ShippingBinMenu clientSide(int id, Inventory inventory, int binSlots) {
        return new ShippingBinMenu(id, inventory,
                new net.minecraft.world.SimpleContainer(Math.max(1, binSlots)), binSlots);
    }

    public Container bin() {
        return bin;
    }

    public int binSlots() {
        return binSlots;
    }

    /** 背包区域起始 y（客户端画玩家背包底图用）。 */
    public int inventoryY() {
        return BIN_Y + Math.max(1, (binSlots + 8) / 9) * 18 + BIN_PAD;
    }

    /** 只能放"兑换规则里写了"的物品。 */
    private static final class SellableSlot extends Slot {
        SellableSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return ShippingRules.canSell(stack);
        }
    }

    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType type, Player player) {
        // 手上拿着「不在兑换规则里」的物品去点出货箱格子：取消这次操作并说明原因
        // （否则玩家只看到"放不进去"，完全不知道为什么）。
        if (slotId >= 0 && slotId < this.binSlots && !this.getCarried().isEmpty()
                && !ShippingRules.canSell(this.getCarried())
                && player instanceof net.minecraft.server.level.ServerPlayer server) {
            cn.autoforged.server_shop_mod_1789689358.shop.ShopManager.notifyShippingRejected(server);
            return;
        }
        super.clicked(slotId, button, type, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index < this.binSlots) {
            // 出货箱 → 背包
            if (!this.moveItemStackTo(stack, this.binSlots, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 背包 → 出货箱：不在规则里的物品直接拒绝，并给玩家一条说明。
            if (!ShippingRules.canSell(stack)) {
                if (player instanceof net.minecraft.server.level.ServerPlayer server) {
                    cn.autoforged.server_shop_mod_1789689358.shop.ShopManager.notifyShippingRejected(server);
                }
                return ItemStack.EMPTY;
            }
            if (!this.moveItemStackTo(stack, 0, this.binSlots, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
