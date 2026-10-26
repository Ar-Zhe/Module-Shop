package cn.autoforged.server_shop_mod_1789689358.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 管理页「添加条目」的**批量**界面：上方 3×9 = 27 个**幽灵槽**（可一次放入最多 27 种物品），
 * 点「确认」后统一设置价格 / 品质 / 数量，然后把它们一次性加进当前模块的清单。
 *
 * <p>幽灵槽的含义和单件添加时一致：槽里只是**模板副本**，玩家放进来的原物在关闭界面时会原样退还
 * （见 {@link #removed(Player)}），所以随便放、不会丢东西。
 */
public class BatchAddMenu extends AbstractContainerMenu {
    /** 幽灵槽数量（3 行 × 9 列）。 */
    public static final int GHOST_SLOTS = 27;
    /** 幽灵槽起点与背包起点（客户端界面按同一套坐标画背景）。 */
    public static final int SLOT_X = 8;
    public static final int SLOT_Y = 32;
    public static final int INVENTORY_Y = 124;

    private final Container ghosts;

    public BatchAddMenu(int id, Inventory playerInventory, Container ghosts) {
        super(ModMenus.BATCH_ADD.get(), id);
        this.ghosts = ghosts;
        for (int i = 0; i < GHOST_SLOTS; i++) {
            this.addSlot(new GhostSlot(ghosts, i, SLOT_X + (i % 9) * 18, SLOT_Y + (i / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, 9 + row * 9 + col,
                        SLOT_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, SLOT_X + col * 18, INVENTORY_Y + 58));
        }
    }

    public static BatchAddMenu clientSide(int id, Inventory playerInventory) {
        return new BatchAddMenu(id, playerInventory, new SimpleContainer(GHOST_SLOTS));
    }

    public Container ghosts() {
        return ghosts;
    }

    /** 已放入几种物品。 */
    public int filled() {
        int count = 0;
        for (int i = 0; i < GHOST_SLOTS; i++) {
            if (!ghosts.getItem(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player.level().isClientSide) {
            return;
        }
        // 幽灵槽只是模板：把放进去的原物全部退还（掉线时直接掉在脚下，避免"加了但没保存"）。
        boolean disconnected = player instanceof net.minecraft.server.level.ServerPlayer server
                && server.hasDisconnected();
        for (int i = 0; i < GHOST_SLOTS; i++) {
            ItemStack stack = ghosts.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ghosts.setItem(i, ItemStack.EMPTY);
            if (disconnected) {
                player.drop(stack.copy(), false);
                continue;
            }
            ItemStack remaining = stack.copy();
            player.getInventory().add(remaining);
            if (!remaining.isEmpty()) {
                player.drop(remaining, false);
            }
        }
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
        if (index < GHOST_SLOTS) {
            if (!this.moveItemStackTo(stack, GHOST_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, 0, GHOST_SLOTS, false)) {
            return ItemStack.EMPTY;
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
        return player.isAlive();
    }

    /** 只能放入 / 读取，不能取出（避免借管理界面刷出真实物品）。 */
    public static final class GhostSlot extends Slot {
        public GhostSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty();
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean isFake() {
            return true;
        }
    }
}
