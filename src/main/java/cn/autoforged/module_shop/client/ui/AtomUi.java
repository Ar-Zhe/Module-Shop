package cn.autoforged.module_shop.client.ui;

import cn.autoforged.module_shop.ModuleShopMod;
import com.atom.lib.AtomLibRuntime;
import com.atom.lib.RuntimeHandle;
import com.atom.lib.design.atomchat.Design;
import com.atom.lib.font.FontId;
import com.atom.lib.font.FontRegistry;
import com.atom.lib.text.TextService;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;

/**
 * Module-Shop 与 AtomLib 之间唯一的“入口”：持有本模组的 AtomLib 运行时、字体表与文字服务。
 *
 * <p><b>线程</b>：全部在渲染线程上创建与使用（AtomLib 的约定）。
 * <p><b>可用性</b>：第一次使用时才初始化原生库；失败（例如非 Windows、缺少原生库、版本不符）
 * 时记录一次日志并返回 {@code false}，调用方据此走回退界面，而不是让游戏崩溃。
 * <p><b>生命周期</b>：会话期间常驻；客户端退出时由 {@link #shutdown()} 关闭。渲染桥本身归 AtomLib 所有，这里不关闭。
 *
 * <p>只能被 client 包中的代码引用；专用服务器不会加载这个类。
 */
public final class AtomUi {
    /** 正文字体（AtomLib 自带的 Noto Sans SC 子集，常规字重）。 */
    public static final FontId REGULAR = FontRegistry.DEFAULT_REGULAR;
    /** 标题字体（粗体）。 */
    public static final FontId BOLD = FontRegistry.DEFAULT_BOLD;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static RuntimeHandle runtime;
    private static FontRegistry fonts;
    private static TextService text;
    private static boolean attempted;
    private static Throwable failure;

    private AtomUi() {
    }

    /** 尝试初始化（只尝试一次）。返回 AtomLib 是否可用。 */
    public static synchronized boolean available() {
        if (text != null) {
            return true;
        }
        if (attempted) {
            return false;
        }
        attempted = true;
        try {
            RenderSystem.assertOnRenderThread();
            RuntimeHandle handle = AtomLibRuntime.open(ModuleShopMod.MODID);
            FontRegistry registry = new FontRegistry(handle);
            List<FontId> registered = registry.registerDefaults();
            if (!registered.contains(REGULAR) || !registered.contains(BOLD)) {
                throw new IllegalStateException("AtomLib 默认字体缺失：" + registered);
            }
            runtime = handle;
            fonts = registry;
            text = new TextService(registry);
            Design.host(ShopDesign.INSTANCE);
            LOGGER.info("[module_shop] AtomLib UI 已就绪");
            // 读取保存的日间 / 暗色设置（不论最先打开的是商店还是出货箱等次级界面）
            cn.autoforged.module_shop.client.UITheme.ensureLoaded();
            return true;
        } catch (Throwable t) {
            failure = t;
            LOGGER.error("[module_shop] AtomLib 初始化失败，商店界面将使用旧版绘制", t);
            closeQuietly();
            return false;
        }
    }

    /** 初始化失败的原因（没失败或还没尝试时为 null）。 */
    public static synchronized Throwable failure() {
        return failure;
    }

    public static synchronized TextService text() {
        // 还没初始化就先初始化：附属的界面可能比商店先打开（例如收到红包详情时还没开过商店），
        // 不能因为“没人先调 available()”就崩游戏。
        if (text == null && !available()) {
            throw new IllegalStateException("AtomLib 不可用；调用前先检查 AtomUi.available()");
        }
        return text;
    }

    public static synchronized FontRegistry fonts() {
        if (fonts == null && !available()) {
            throw new IllegalStateException("AtomLib 不可用；调用前先检查 AtomUi.available()");
        }
        return fonts;
    }

    /** 客户端退出时调用（渲染线程）。 */
    public static synchronized void shutdown() {
        closeQuietly();
    }

    private static void closeQuietly() {
        try {
            if (text != null) {
                text.close();
            }
        } catch (Throwable ignored) {
        }
        // FontRegistry 被 runtime 托管，关闭 runtime 即可一并释放。
        try {
            if (runtime != null) {
                runtime.close();
            }
        } catch (Throwable ignored) {
        }
        text = null;
        fonts = null;
        runtime = null;
    }
}
