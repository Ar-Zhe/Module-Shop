package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.ModuleShopMod;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * 真正的 JEI 插件（**唯一引用 JEI 类型的类**）。
 * <p>
 * 只有装了 JEI 才会被 JEI 的注解扫描发现并加载，所以本模组在没有 JEI 的服务器/客户端上完全不受影响
 * （本类不会被加载，也就不会因为缺类而报错）。
 * <p>
 * 作用只有两件事：把「展示某物品的配方 / 用途」和「JEI 的快捷键判定」交给
 * {@link JeiBridge}，由本模组的自绘界面在按键时调用。
 */
@JeiPlugin
public class JeiPluginImpl implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(ModuleShopMod.MODID, "jei_bridge");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiBridge.install(
                (stack, recipes) -> {
                    // OUTPUT = 「这件物品是怎么来的」（配方产物），INPUT = 「这件物品能用来做什么」。
                    IFocus<net.minecraft.world.item.ItemStack> focus = runtime.getJeiHelpers()
                            .getFocusFactory()
                            .createFocus(recipes ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT,
                                    VanillaTypes.ITEM_STACK, stack);
                    runtime.getRecipesGui().show(focus);
                },
                new JeiBridge.KeyMatcher() {
                    @Override
                    public boolean matchesShowRecipe(InputConstants.Key key) {
                        return runtime.getKeyMappings().getShowRecipe().isActiveAndMatches(key);
                    }

                    @Override
                    public boolean matchesShowUses(InputConstants.Key key) {
                        return runtime.getKeyMappings().getShowUses().isActiveAndMatches(key);
                    }
                });
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiBridge.uninstall();
    }
}
