package cn.hsc.shoputil.mixin;

import cn.hsc.shoputil.client.GuardState;
import cn.hsc.shoputil.client.PasswordScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在商店界面左上角画一个可拖动的锁定按钮，和商店界面同开同关。
 */
@Mixin(targets = "cn.autoforged.module_shop.client.screen.ShopScreen", remap = false)
public abstract class ShopScreenMixin {

    private static int guardBtnX = 8;
    private static int guardBtnY = 8;
    private static final int GUARD_BTN_SIZE = 20;
    private static boolean dragging = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void afterRender(GuiGraphics g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        try {
            boolean unlocked = GuardState.isUnlocked();
            int bg = unlocked ? 0x804CAF50 : 0x80F44336;
            g.fill(guardBtnX, guardBtnY, guardBtnX + GUARD_BTN_SIZE, guardBtnY + GUARD_BTN_SIZE, bg);
            g.fill(guardBtnX, guardBtnY, guardBtnX + GUARD_BTN_SIZE, guardBtnY + 1, 0xFFFFFFFF);
            g.fill(guardBtnX, guardBtnY + GUARD_BTN_SIZE - 1, guardBtnX + GUARD_BTN_SIZE, guardBtnY + GUARD_BTN_SIZE, 0xFFFFFFFF);
            g.fill(guardBtnX, guardBtnY, guardBtnX + 1, guardBtnY + GUARD_BTN_SIZE, 0xFFFFFFFF);
            g.fill(guardBtnX + GUARD_BTN_SIZE - 1, guardBtnY, guardBtnX + GUARD_BTN_SIZE, guardBtnY + GUARD_BTN_SIZE, 0xFFFFFFFF);
            String icon = unlocked ? "🔓" : "🔒";
            g.drawString(Minecraft.getInstance().font, icon, guardBtnX + 5, guardBtnY + 5, 0xFFFFFFFF);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void onMouseClicked(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (button != 0) return;
            if (mx >= guardBtnX && mx <= guardBtnX + GUARD_BTN_SIZE
                    && my >= guardBtnY && my <= guardBtnY + GUARD_BTN_SIZE) {
                // 开始拖动
                dragging = true;
                dragOffsetX = (int) (mx - guardBtnX);
                dragOffsetY = (int) (my - guardBtnY);
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true, remap = false)
    private void onMouseDragged(double mx, double my, int button, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (dragging) {
                guardBtnX = (int) (mx - dragOffsetX);
                guardBtnY = (int) (my - dragOffsetY);
                // 限制在屏幕范围内
                guardBtnX = Math.max(0, Math.min(guardBtnX, Minecraft.getInstance().getWindow().getGuiScaledWidth() - GUARD_BTN_SIZE));
                guardBtnY = Math.max(0, Math.min(guardBtnY, Minecraft.getInstance().getWindow().getGuiScaledHeight() - GUARD_BTN_SIZE));
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true, remap = false)
    private void onMouseReleased(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (dragging) {
                dragging = false;
                // 拖动结束后保存位置（不频繁，只在松手时存一次）
                savePosition();
                // 判断是否是短按（没有移动多少），如果是短按则切换解锁/锁定
                if (Math.abs(mx - guardBtnX - dragOffsetX) < 5 && Math.abs(my - guardBtnY - dragOffsetY) < 5) {
                    Minecraft mc = Minecraft.getInstance();
                    if (GuardState.isUnlocked()) {
                        GuardState.unlocked = false;
                        if (mc.player != null) {
                            mc.player.displayClientMessage(Component.literal("§c[商店守卫] 已锁定管理员功能"), false);
                        }
                        mc.setScreen(null);
                    } else {
                        mc.setScreen(new PasswordScreen(mc.screen));
                    }
                }
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {}
    }

    private static void savePosition() {
        try {
            java.nio.file.Path file = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("guard_button_position.txt");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, guardBtnX + "," + guardBtnY);
        } catch (Exception ignored) {}
    }

    private static void loadPosition() {
        try {
            java.nio.file.Path file = cn.hsc.shoputil.ShopUtilMod.configDir().resolve("guard_button_position.txt");
            if (java.nio.file.Files.isRegularFile(file)) {
                String s = java.nio.file.Files.readString(file).trim();
                String[] parts = s.split(",");
                if (parts.length == 2) {
                    guardBtnX = Integer.parseInt(parts[0]);
                    guardBtnY = Integer.parseInt(parts[1]);
                }
            }
        } catch (Exception ignored) {}
    }

    private static boolean positionLoaded = false;

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void afterInit(CallbackInfo ci) {
        if (!positionLoaded) {
            loadPosition();
            positionLoaded = true;
        }
        // 恢复上次停留的标签页
        restoreTab((Object) this);
    }

    /**
     * 记录关闭界面时停留的标签页，供下次自动恢复。
     */
    @Inject(method = "removed", at = @At("HEAD"), remap = false)
    private void onRemoved(CallbackInfo ci) {
        try {
            if (!cn.hsc.shoputil.ShopUtilConfig.TAB_MEMORY_ENABLED.get()) return;
            Object self = (Object) this;
            java.lang.reflect.Field f = self.getClass().getDeclaredField("currentTab");
            f.setAccessible(true);
            Object v = f.get(self);
            if (!(v instanceof String tab) || tab.isEmpty()) return;

            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("shop_tab_memory");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("tab.txt"), tab);
        } catch (Exception ignored) {}
    }

    private static void restoreTab(Object self) {
        try {
            if (!cn.hsc.shoputil.ShopUtilConfig.TAB_MEMORY_ENABLED.get()) return;
            java.nio.file.Path file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                    .get().resolve("shop_tab_memory").resolve("tab.txt");
            if (!java.nio.file.Files.exists(file)) return;
            String savedTab = java.nio.file.Files.readString(file).trim();
            if (savedTab.isEmpty()) return;
            if (self == null) return;

            // ① 该标签页必须是本体已注册的页
            java.lang.reflect.Field pagesField = self.getClass().getDeclaredField("pages");
            pagesField.setAccessible(true);
            Object pagesObj = pagesField.get(self);
            if (!(pagesObj instanceof java.util.Map<?, ?> pages)) return;
            if (!pages.containsKey(savedTab)) return;

            // ② 该标签页必须在"当前实际可见"的列表里
            //    （挡住未安装前置、服务端未启用、被玩家隐藏的页；列表为空视为数据未就绪，跳过校验）
            try {
                java.lang.reflect.Method visibleTabs = self.getClass().getDeclaredMethod("visibleTabs");
                visibleTabs.setAccessible(true);
                Object visObj = visibleTabs.invoke(self);
                if (visObj instanceof java.util.List<?> vis && !vis.isEmpty() && !vis.contains(savedTab)) {
                    return;
                }
            } catch (Throwable ignored) {
                // 本体没有这个方法时不做可见性校验，退回注册表判断
            }

            // 直接设置 currentTab 字段，不调 selectTab
            // 原因：selectTab 在 init 阶段调用会触发 ShopPage 的 onShow / markFresh 等生命周期，
            // 打乱本体 daily / signin / mall 页的数据刷新状态（服务端推新数据时这些页会误以为已最新，跳过刷新）。
            // 只改字段值，本体 render 时自然会用新值渲染，不干扰服务端数据同步。
            java.lang.reflect.Field f = self.getClass().getDeclaredField("currentTab");
            f.setAccessible(true);
            f.set(self, savedTab);
        } catch (Exception ignored) {}
    }
}
