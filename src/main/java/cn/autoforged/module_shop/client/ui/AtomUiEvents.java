package cn.autoforged.module_shop.client.ui;

import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.client.ui.demo.AtomDemoScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** AtomLib UI 的客户端事件：演示命令与退出时释放资源。 */
@EventBusSubscriber(modid = ModuleShopMod.MODID, value = Dist.CLIENT)
public final class AtomUiEvents {
    private AtomUiEvents() {
    }

    /** {@code /moduleshop_atomlib_demo}：打开阶段 1 演示页（纯客户端命令，不发往服务器）。 */
    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("moduleshop_atomlib_demo").executes(context -> {
            Minecraft minecraft = Minecraft.getInstance();
            // 推迟到下一次任务处理：此时聊天界面已经关闭，不会把演示页顶掉。
            minecraft.tell(() -> minecraft.setScreen(new AtomDemoScreen()));
            return 1;
        }));
    }

    /** 客户端退出（渲染线程，GL 上下文仍有效）：释放本模组的 AtomLib 字体与运行时。 */
    @SubscribeEvent
    public static void onShutdown(GameShuttingDownEvent event) {
        AtomUi.shutdown();
    }
}
