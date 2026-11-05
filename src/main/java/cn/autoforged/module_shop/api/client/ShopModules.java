package cn.autoforged.module_shop.api.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 客户端扩展点：让**附属模组**把自己的功能挂进商店主界面的左侧功能区（侧栏）。
 *
 * <p>注册后，侧栏会多出一个条目（排在内置模块后面，管理员可以在「编辑布局」里拖动排序或移除）。两种形式：
 * <ul>
 *     <li>{@link #registerPage}（推荐）：内容区页面，和内置模块一样画在商店右侧（AtomLib / Skija）；</li>
 *     <li>{@link #register}：弹出式，点击后以「二级界面」打开你的 Screen，返回 / ESC 回到商店。</li>
 * </ul>
 *
 * <pre>{@code
 * // 仅客户端：在 FMLClientSetupEvent 里注册
 * ShopModules.register("my_addon_feature",
 *         () -> Component.translatable("tab.my_addon.feature"),
 *         MyFeatureScreen::new);
 * }</pre>
 *
 * <ul>
 *     <li>只在客户端调用（专用服务器上不要加载这个类）；</li>
 *     <li>{@code id} 全局唯一，建议带上你的 modid 前缀，不能与内置模块重名；</li>
 *     <li>界面里要「返回商店」时调用 {@link #back()}。</li>
 * </ul>
 */
public final class ShopModules {
    /**
     * 一个附属条目：{@code page} 非空 = 内容区页面（AtomLib 可用时画在商店右侧）；
     * {@code screen} = 弹出式界面（只注册了界面，或 AtomLib 不可用时的回退）。两者至少有一个。
     */
    public static final class Entry {
        private final String id;
        private final Supplier<Component> name;
        private final Supplier<? extends Screen> screen;
        private final Supplier<? extends AddonPage> pageFactory;
        private AddonPage page;

        Entry(String id, Supplier<Component> name, Supplier<? extends Screen> screen,
                Supplier<? extends AddonPage> pageFactory) {
            this.id = Objects.requireNonNull(id, "id");
            this.name = Objects.requireNonNull(name, "name");
            if (screen == null && pageFactory == null) {
                throw new IllegalArgumentException("page 与 screen 至少要提供一个");
            }
            this.screen = screen;
            this.pageFactory = pageFactory;
        }

        public String id() {
            return this.id;
        }

        public Supplier<Component> name() {
            return this.name;
        }

        /** 弹出式界面 / 回退界面；没有返回 {@code null}。 */
        public Supplier<? extends Screen> screen() {
            return this.screen;
        }

        public boolean hasPage() {
            return this.pageFactory != null;
        }

        /** 页面实例（第一次调用时创建，之后一直复用）；没有页面返回 {@code null}。 */
        public synchronized AddonPage page() {
            if (this.page == null && this.pageFactory != null) {
                this.page = this.pageFactory.get();
            }
            return this.page;
        }
    }

    private static final Map<String, Entry> ENTRIES = Collections.synchronizedMap(new LinkedHashMap<>());

    private ShopModules() {
    }

    /**
     * 注册一个**弹出式**侧栏条目：点击后以二级界面打开 {@code screen}，返回 / ESC 回到商店。
     *
     * @param id     条目 id（{@code [a-z0-9_]{1,48}}，建议 {@code modid_xxx}）
     * @param name   显示名（每帧取值，可以用 translatable 跟随语言）
     * @param screen 点击后打开的界面
     */
    public static void register(String id, Supplier<Component> name, Supplier<? extends Screen> screen) {
        put(new Entry(checkId(id), name, Objects.requireNonNull(screen, "screen"), null));
    }

    /**
     * 注册一个**内容区页面**条目（推荐）：点击后像内置模块一样在商店右侧内容区绘制 {@link AddonPage}。
     *
     * @param page     页面工厂（只调用一次，实例常驻）
     * @param fallback AtomLib 不可用时打开的回退界面（可为 {@code null}：那时条目点了没反应）
     */
    public static void registerPage(String id, Supplier<Component> name, Supplier<? extends AddonPage> page,
            Supplier<? extends Screen> fallback) {
        put(new Entry(checkId(id), name, fallback, Objects.requireNonNull(page, "page")));
    }

    private static String checkId(String id) {
        if (id == null || !id.matches("[a-z0-9_]{1,48}")) {
            throw new IllegalArgumentException("Invalid shop module id: " + id);
        }
        return id;
    }

    private static void put(Entry entry) {
        ENTRIES.put(entry.id(), entry);
    }

    /** 已注册的附属条目（注册顺序）。 */
    public static List<Entry> entries() {
        synchronized (ENTRIES) {
            return List.copyOf(new ArrayList<>(ENTRIES.values()));
        }
    }

    /** 按 id 查条目；没有返回 {@code null}。 */
    public static Entry get(String id) {
        return id == null ? null : ENTRIES.get(id);
    }

    /** 从附属的弹出式界面返回上一级（通常就是商店主界面）。 */
    public static void back() {
        cn.autoforged.module_shop.client.screen.Nav.back();
    }
}
