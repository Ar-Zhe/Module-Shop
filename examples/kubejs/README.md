# KubeJS 桥接使用说明

## 一、前提

* 服务端装了 **KubeJS 2101.x**（1.21.1）；
* 用的商店 jar 是**带桥接**构建的（本仓库发布的 jar 默认带；自行构建时加 `-PkubejsJar=...`，见下）。

## 二、可用全局

```js
ModuleShop.version()                       // "1.0.0"
ModuleShop.balance(uuid)                   // 数字
ModuleShop.balanceText(uuid)               // "1378 星币"
ModuleShop.maxBalance()                    // 0 = 不限
ModuleShop.unclaimedMails(uuid)            // 未领邮件数
ModuleShop.signInText(uuid)                // "signed=false streak=0 best=0"
ModuleShop.dailyShopSize(uuid) / mallSize()
ModuleShop.mailboxLines(uuid)              // 每封邮件一行摘要
ModuleShop.shopLines(false, uuid)          // 每日商店；true = 购物城
ModuleShop.lastTransaction(uuid)           // 最近一条流水
ModuleShop.known(uuid)                     // 是否有账户

// 写操作：成功返回 true，失败返回 "FAIL:错误码 说明"
ModuleShop.add(uuid, 100, '每日任务')
ModuleShop.take(uuid, 50, '抽奖')
ModuleShop.give(uuid, Item.of('minecraft:diamond', 3))
ModuleShop.reward(uuid, 'task:daily-mining', 100, [Item.of('minecraft:diamond', 3)], '每日任务')
ModuleShop.mail(uuid, '标题', '正文', [Item.of('minecraft:bread', 8)])
ModuleShop.record(uuid, 'my_shop', '我的商店', -200, '买了把剑')   // 只记账
ModuleShop.buyDaily(uuid, 0, 1, -1)
ModuleShop.buyMall(uuid, 0, 1, -1)
ModuleShop.sellBuyback(uuid, 0, 1)
```

示例脚本见 `examples/kubejs/module_shop_api.js`。

## 三、带桥接的构建方式

```bash
./gradlew build -PkubejsJar=/path/to/kubejs-neoforge-2101.7.2-build.368.jar
```

* **不加** `-PkubejsJar`：构建出的 jar **不含**桥接（脚本仍可用 `Java.loadClass(...)` 调 Java API）；
* **加了**：jar 内含 `kubejs.plugins.txt` 与桥接类；没装 KubeJS 的服照样正常运行（这些文件不会被读取）。

## 四、为什么不用 `Java.loadClass` 就行

KubeJS（Rhino）可以直接实例化/调用 Java 类与静态方法，所以**即使没有桥接**也能：

```js
const Api = Java.loadClass('cn.autoforged.module_shop.api.ModuleShopApi')
Api.economy().add(player.uuid, 100, '每日任务')
```

桥接的价值在于：**方法更短、返回值已转成脚本友好形式**（`true` / `"FAIL:...`），不用自己判断 `ApiResult`。