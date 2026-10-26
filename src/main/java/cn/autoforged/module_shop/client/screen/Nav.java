package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 界面层级与返回栈（第八轮按需求重做）。
 *
 * <h2>层级划分</h2>
 * <ul>
 *     <li><b>一级界面</b>：只有商店主界面 {@link ShopScreen}（每日商店 / 收购 / 拍卖 / 摊位 / 邮箱 / 兑换等
 *     都是它的页签，切页签不算换界面）。在一级界面按 ESC = 关掉整个界面。</li>
 *     <li><b>二级界面</b>：从一级界面打开的界面 —— 签到、交易选择、转账选择、玩家摊位列表、兑换码、拍卖出价、
 *     提醒音效设置等。返回 / ESC = <b>回到一级界面</b>。</li>
 *     <li><b>三级界面</b>：从二级界面再打开的界面 —— 某个摊位的商品页、数量/金额输入、交易界面、交易请求列表、
 *     日程编辑、日程奖励设置、日程奖励（物品发放式容器）等。返回 / ESC = <b>回到它的上一级</b>；
 *     上一级若已不在栈里，就直接回一级界面。</li>
 * </ul>
 *
 * <h2>两条硬规则</h2>
 * <ol>
 *     <li><b>不会来回循环</b>：入栈时先把「同一界面类型」的旧记录清掉，且最多保留 {@link #MAX_DEPTH} 层；
 *         返回时一定跳到**不同**的界面，绝不会又跳回刚关掉的那一个。</li>
 *     <li><b>关掉的界面不会被服务端快照"复活"</b>：玩家关闭某类界面时会被记入「已关闭」集合，
 *         之后服务端推送的同类界面（{@link #openPushed}）会被忽略，直到玩家自己重新点开（{@link #rearm}）。</li>
 * </ol>
 */
public final class Nav {
    /** 最多记住 3 层（一级 + 二级 + 三级）。 */
    private static final int MAX_DEPTH = 3;
    private static final Deque<Screen> STACK = new ArrayDeque<>();
    /** 玩家已经主动关掉的界面类型 → 关闭时刻（毫秒）：服务端再推同类界面时不再弹回来。 */
    private static final Map<Class<?>, Long> DISMISSED = new HashMap<>();
    /**
     * 「已关闭」保护期（毫秒）：只拦关闭后**紧跟着**到达的推送，
     * 超过这个时间的推送视为玩家自己发起的新流程（例如点聊天里的「打开交易」链接）。
     */
    private static final long DISMISS_GUARD_MS = 5000L;
    /** 一级界面（商店主界面）实例缓存：返回时复用它，页签与滚动位置都还在。 */
    private static Screen root;

    private Nav() {
    }

    /** 玩家自己点开的界面：允许打开，并解除该类型的「已关闭」标记。 */
    public static void open(Screen next) {
        if (next == null) {
            return;
        }
        DISMISSED.remove(next.getClass());
        push(next);
    }

    /**
     * 服务端推送要打开的界面（金额输入 / 摊位浏览 / 交易等）：
     * 玩家刚把这一类型的界面关掉（{@link #DISMISS_GUARD_MS} 内）就不再弹回来，
     * 避免"退出之后服务端又把它弄回来"。
     */
    public static void openPushed(Screen next) {
        if (next == null) {
            return;
        }
        Long at = DISMISSED.get(next.getClass());
        if (at != null && System.currentTimeMillis() - at < DISMISS_GUARD_MS) {
            return;
        }
        push(next);
    }

    /** 玩家重新发起了某个流程：解除该类型的「已关闭」标记，允许服务端再次推送这个界面。 */
    public static void rearm(Class<?> type) {
        if (type != null) {
            DISMISSED.remove(type);
        }
    }

    private static void markClosed(Screen screen) {
        if (screen != null) {
            DISMISSED.put(screen.getClass(), System.currentTimeMillis());
        }
    }

    private static void push(Screen next) {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        if (next instanceof ShopScreen) {
            root = next;
        }
        if (current != null && current != next) {
            // 同一类型的旧记录先清掉：返回时不会又跳回刚关掉的那个界面。
            STACK.removeIf(s -> s == current || s.getClass() == current.getClass());
            STACK.push(current);
            while (STACK.size() > MAX_DEPTH) {
                STACK.removeLast();
            }
        }
        mc.setScreen(next);
    }

    /** 返回上一级；一级界面（商店主界面）上则关闭整个界面。 */
    public static void back() {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        markClosed(current);
        Screen previous = null;
        while (!STACK.isEmpty()) {
            Screen candidate = STACK.pop();
            boolean sameType = current != null && candidate != null
                    && candidate.getClass() == current.getClass();
            if (candidate != null && candidate != current && !sameType) {
                previous = candidate;
                break;
            }
        }
        if (previous != null) {
            mc.setScreen(previous);
            return;
        }
        if (current instanceof ShopScreen) {
            // 一级界面按 ESC = 关掉界面（回到游戏）。
            mc.setScreen(null);
            return;
        }
        // 没有上一级了：回一级界面，绝不把玩家直接丢回世界。
        mc.setScreen(shopOrDefault());
    }

    /** 回到商店主界面（一级）：优先返回栈里的商店界面，其次用缓存的实例（或按上次页签新建）。 */
    public static void backToShop() {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        markClosed(current);
        while (!STACK.isEmpty()) {
            Screen previous = STACK.pop();
            if (previous instanceof ShopScreen) {
                root = previous;
                mc.setScreen(previous);
                return;
            }
        }
        mc.setScreen(shopOrDefault());
    }

    /** 一级界面（商店主界面）实例。 */
    public static Screen shopOrDefault() {
        if (root == null) {
            root = new ShopScreen(ClientShopData.lastTab);
        }
        return root;
    }

    public static void clear() {
        STACK.clear();
        DISMISSED.clear();
        root = null;
    }
}
