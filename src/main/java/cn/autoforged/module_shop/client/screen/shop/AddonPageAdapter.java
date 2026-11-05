package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPage;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.client.ClientShopData;
import io.github.humbleui.skija.Canvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 把附属模组的 {@link AddonPage}（公共 API）接到内部的 {@link ShopPage} 路由上：
 * 绘制、鼠标、输入框登记、切页时取消输入全部复用内置页面的那一套。
 *
 * <p><b>兼容兜底</b>：附属是对某个版本的模块商店编译的。主体升级后如果附属页面出错
 * （抛异常，或 {@link LinkageError}：调用了已不存在的方法 / 类），这里只记一次日志、把页面标记为失效，
 * 并改为打开附属注册的回退界面 —— 商店本身绝不跟着崩。失效标记全局保留到重启客户端。
 */
public final class AddonPageAdapter implements ShopPage {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("module_shop/addon");
    private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final String id;
    private final AddonPage page;
    private final java.util.function.Supplier<? extends Screen> fallback;
    private Host host;

    public AddonPageAdapter(String id, AddonPage page, java.util.function.Supplier<? extends Screen> fallback) {
        this.id = id;
        this.page = page;
        this.fallback = fallback;
    }

    /** 这个附属页面是否已因出错被停用（之后改走回退界面）。 */
    public static boolean failed(String id) {
        return FAILED.contains(id);
    }

    private boolean broken() {
        return FAILED.contains(this.id);
    }

    /** 记录失败：只在第一次记日志并切到回退界面。 */
    private void fail(Throwable error) {
        if (!FAILED.add(this.id)) {
            return;
        }
        LOGGER.error("[module_shop] 附属页面 {} 出错，已停用并改用回退界面（多半是附属与当前模块商店版本不兼容）",
                this.id, error);
        try {
            this.page.cancelInput();
        } catch (Throwable ignored) {
        }
        if (this.fallback != null) {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                try {
                    cn.autoforged.module_shop.client.screen.Nav.open(this.fallback.get());
                } catch (Throwable fallbackError) {
                    LOGGER.error("[module_shop] 附属 {} 的回退界面也打不开", this.id, fallbackError);
                }
            });
        }
    }

    @Override
    public String id() {
        return this.id;
    }

    private Host host(ShopHost shop) {
        if (this.host == null || this.host.shop != shop) {
            this.host = new Host(shop);
        }
        return this.host;
    }

    private AddonFrame frame(PageFrame f) {
        return new AddonFrame(host(f.host()), f.x(), f.y(), f.w(), f.h(), f.mouseX(), f.mouseY(), f.dtMs());
    }

    @Override
    public void init(ShopHost shop) {
        if (broken()) {
            return;
        }
        try {
            this.page.init(host(shop));
        } catch (Throwable error) {
            fail(error);
        }
    }

    @Override
    public void render(Canvas canvas, PageFrame f) {
        if (broken()) {
            return;
        }
        int saved = canvas.save();
        try {
            this.page.render(canvas, frame(f));
        } catch (Throwable error) {
            fail(error);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mouseX, double mouseY, int button) {
        if (broken()) {
            return false;
        }
        try {
            return this.page.mouseClicked(frame(f), mouseX, mouseY, button);
        } catch (Throwable error) {
            fail(error);
            return true;
        }
    }

    @Override
    public void clickedOutside() {
        if (broken()) {
            return;
        }
        try {
            this.page.clickedOutside();
        } catch (Throwable error) {
            fail(error);
        }
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mouseX, double mouseY, int button) {
        if (broken()) {
            return false;
        }
        try {
            return this.page.mouseReleased(frame(f), mouseX, mouseY, button);
        } catch (Throwable error) {
            fail(error);
            return true;
        }
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (broken()) {
            return false;
        }
        try {
            return this.page.mouseDragged(frame(f), mouseX, mouseY, button, dragX, dragY);
        } catch (Throwable error) {
            fail(error);
            return true;
        }
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mouseX, double mouseY, double scrollY) {
        if (broken()) {
            return false;
        }
        try {
            return this.page.mouseScrolled(frame(f), mouseX, mouseY, scrollY);
        } catch (Throwable error) {
            fail(error);
            return true;
        }
    }

    @Override
    public void cancelInput() {
        if (broken()) {
            return;
        }
        try {
            this.page.cancelInput();
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static final class Host implements AddonPageHost {
        private final ShopHost shop;

        Host(ShopHost shop) {
            this.shop = shop;
        }

        @Override
        public Screen screen() {
            return this.shop.screen();
        }

        @Override
        public Font font() {
            return this.shop.font();
        }

        @Override
        public void registerInput(EditBox box) {
            this.shop.registerInput(box);
        }

        @Override
        public void textTooltip(Component text) {
            this.shop.textTooltip(text);
        }

        @Override
        public long balance() {
            return ClientShopData.balance;
        }

        @Override
        public String currencyName() {
            return ClientShopData.currencyName();
        }
    }
}
