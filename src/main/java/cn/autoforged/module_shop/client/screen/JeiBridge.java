package cn.autoforged.module_shop.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/**
 * JEI 软桥接（**本类不引用任何 JEI 类型**，所以没装 JEI 也能正常加载）。
 * <p>
 * 工作方式：真正的 JEI 代码在 {@code JeiPluginImpl}（带 {@code @JeiPlugin}，只有装了 JEI 才会被 JEI 扫描并加载）里，
 * 它在 JEI 就绪时把「怎么展示配方」和「怎么看 JEI 的快捷键」两件事注册到这里；
 * 本模组的界面只需在按键事件里调一次 {@link #handleKey(Screen, int)}。
 * 没装 JEI 时两个回调都是 null，方法直接返回 false，什么都不做。
 */
public final class JeiBridge {
    /** 展示配方：recipes=true 看「怎么合成/怎么来」，false 看「能用来做什么」。 */
    public interface Shower {
        void show(ItemStack stack, boolean recipes);
    }

    /** 判断刚按下的是不是 JEI 的「查看配方 / 查看用途」键。 */
    public interface KeyMatcher {
        boolean matchesShowRecipe(InputConstants.Key key);

        boolean matchesShowUses(InputConstants.Key key);
    }

    private static Shower shower;
    private static KeyMatcher matcher;

    private JeiBridge() {
    }

    /** 由 JEI 插件在 runtime 就绪时调用。 */
    public static void install(Shower showerImpl, KeyMatcher keyMatcher) {
        shower = showerImpl;
        matcher = keyMatcher;
    }

    /** JEI 卸载（例如切换资源包重载失败）时清掉，避免调用到失效的 runtime。 */
    public static void uninstall() {
        shower = null;
        matcher = null;
    }

    public static boolean available() {
        return shower != null && matcher != null;
    }

    /**
     * 界面按键时调用：鼠标正悬停着物品、且按下的正是 JEI 的配方/用途键时，直接打开 JEI 的对应界面。
     *
     * @return true = 这个按键已经被 JEI 消费掉（调用方应取消该按键事件）
     */
    public static boolean handleKey(Screen screen, int keyCode) {
        if (!available() || !(screen instanceof JeiHoverSource source)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return false;
        }
        // 鼠标的 GUI 坐标：ScreenEvent 里没有鼠标位置，这里从窗口坐标换算（JEI 内部也是这么做的）。
        double mx = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
                / Math.max(1, minecraft.getWindow().getScreenWidth());
        double my = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
                / Math.max(1, minecraft.getWindow().getScreenHeight());
        ItemStack stack = source.jeiHoveredStack(mx, my);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(keyCode);
        if (matcher.matchesShowRecipe(key)) {
            shower.show(stack, true);
            return true;
        }
        if (matcher.matchesShowUses(key)) {
            shower.show(stack, false);
            return true;
        }
        return false;
    }
}
