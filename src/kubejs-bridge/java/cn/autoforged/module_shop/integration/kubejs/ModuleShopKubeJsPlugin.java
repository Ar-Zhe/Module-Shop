package cn.autoforged.module_shop.integration.kubejs;

import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;

/**
 * KubeJS 桥接（**可选模块**）：把公共 API 暴露成脚本全局对象 {@code ModuleShop}。
 *
 * <p>这个类只在编译时可选依赖 KubeJS（`-PkubejsJar=...`），运行期是否加载由 KubeJS 自己决定
 * （它读 `kubejs.plugins.txt`）；没装 KubeJS 时这个类根本不会被加载，主模组不受影响。
 *
 * <pre>{@code
 * // kubejs/server_scripts/daily.js
 * ServerEvents.loaded(event => {
 *   const p = event.server.players[0]
 *   console.info('余额 = ' + ModuleShop.balance(p.uuid))
 *   ModuleShop.reward(p.uuid, 'daily:mining', 100, [], '每日任务')
 * })
 * }</pre>
 */
public class ModuleShopKubeJsPlugin implements KubeJSPlugin {
    @Override
    public void registerBindings(BindingRegistry bindings) {
        bindings.add("ModuleShop", new ModuleShopBindings());
    }
}