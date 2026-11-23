package cn.autoforged.module_shop.integration.kubejs;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

/**
 * KubeJS 2001（1.20.1 Forge）可选插件入口：只在带 -PkubejsJar 编译时进 jar，无 KubeJS 时不被扫描。
 * 在 server / startup / client 脚本全局作用域暴露 {@code ModuleShop} 对象。
 */
public class ModuleShopKubeJsPlugin extends KubeJSPlugin {

    @Override
    public void registerBindings(BindingsEvent bindings) {
        bindings.add("ModuleShop", new ModuleShopBindings());
    }
}
