# 公共 API · 第六批交付说明（KubeJS 桥接）

## 一、本批交付

| 交付物 | 说明 |
| --- | --- |
| `src/kubejs-bridge/java/.../ModuleShopKubeJsPlugin` | 实现 KubeJS `KubeJSPlugin#registerBindings`，注册全局 `ModuleShop` |
| `src/kubejs-bridge/java/.../ModuleShopBindings` | 脚本友好包装：查询返回数字/字符串，写操作返回 `true` 或 `"FAIL:CODE 说明"` |
| `src/kubejs-bridge/resources/kubejs.plugins.txt` | KubeJS 插件发现清单（KubeJS 扫描所有 mod 的该文件） |
| `build.gradle` 可选源码集 | 只有 `-PkubejsJar=<jar>` 时才编译桥接，**主构建零新增依赖** |
| `examples/kubejs/module_shop_api.js` + `README.md` | 现成脚本与用法说明 |

## 二、为什么做成"可选源码集"

* KubeJS 是**可选**软依赖：不装它的服不该被拖入任何依赖；
* 所以桥接源码放在独立 source root，**默认不参与编译**；发布时用 `-PkubejsJar=...` 构建的 jar 才含桥接；
* 不传参数构建的 jar 完全不含 KubeJS 相关类（`gradlew build` 仍然干净可复现）。

## 三、实测证据（真服务器 + KubeJS 2101.7.2-build.368）

```
[API6] ModuleShop 类型 = object
[API6] version = 1.0.0
[API6] balance = 1378  text=1378 星币
[API6] unclaimedMails = 1
[API6] dailyShopSize = 6  mallSize = 0
[API6] signIn = signed=false streak=0 best=0
[API6] add -> true
[API6] take too much -> FAIL:INSUFFICIENT_FUNDS 余额不足：需要 99999999，现有 1382
[API6] reward -> true
[API6] reward dup -> FAIL:DUPLICATE_REQUEST 该奖励已经发放过：kubejs-1
[API6] record -> true
[API6] lastTx = kubejs -1 -> 1384 (记账测试)
[API6] final balance = 1384
```

余额轨迹：1378 +4（add）= 1382，+2（reward）= 1384，`record -1` **不动余额** → 1384 ✔
服务端 `Done (5.195s)`，`Looking for KubeJS plugins...` 找到并加载了本模组插件，模组报错 0 条。

## 四、已知限制

| 限制 | 说明 |
| --- | --- |
| 桥接需要 KubeJS 2101.x | 该版本才提供 `registerBindings(BindingRegistry)`；更老版本（如 1.19 的 `BindingsEvent`）不兼容 |
| 未桥接时也能用 | KubeJS 可用 `Java.loadClass('...api.ModuleShopApi')`；桥接只是更顺手 |
| 物品参数 | 脚本传 `Item.of(...)` 或 Java `ItemStack`；纯字符串 id 需自行转 |
| 发布 jar 必须带桥接标志 | `gradlew build`（不带 `-PkubejsJar`）产出的 jar 不含 `ModuleShop` 全局 |