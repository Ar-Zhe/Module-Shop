# 最小示例模组：调用 Module Shop 公共 API

演示「每日任务」如何**只用公共 API**完成：查余额 → 幂等发奖（星币 + 物品）→ 发奖励邮件 → 判断结果。

## 一、怎么编译

```bash
cd examples/minimal-task-mod
# 先把模块商店编译好，得到 build/libs/server_shop_mod-1.0.0.jar
./gradlew build
```

`build.gradle` 里关键就一行（只编译期依赖，运行期由服务端提供）：

```groovy
compileOnly files(project.findProperty('server_shop_jar'))
```

如果路径不同，改 `gradle.properties` 里的 `server_shop_jar`。

## 二、怎么试

1. 把 `example-task-mod-1.0.0.jar` + `server_shop_mod-1.0.0.jar` 都放进服务端 `mods/`；
2. 进游戏执行：
   - `/exampletask balance` → 显示「余额 1375 星币（上限不限）」
   - `/exampletask complete` → 发 100 星币 + 3 钻石，并收到一封附件邮件
   - **再执行一次** `/exampletask complete` → 提示 `DUPLICATE_REQUEST`（幂等生效，不会重复发奖）

## 三、代码要点

| 要点 | 位置 |
| --- | --- |
| 门面入口 | `ServerShopApi.economy()` / `.reward()` / `.mail()` / `.items()` / `.history()` / `.daily()` |
| 幂等发奖 | `reward().grantOnce(uuid, requestId, items, money, reason)` |
| 结果判断 | `ApiResult.isOk()` / `.code()` / `.message()` / `.value()` |
| 余额视图 | `economy().balanceView(uuid)` → `BalanceView(balance, maxBalance, currencyName)` |
| 邮件 | `mail().sendItemMail(...)` / `sendMoneyMail(...)` / `unclaimed(uuid)` |

**不要**直接引用 `ShopManager`、`ShopSavedData`、`PlayerAccount` —— 那些是内部实现，随时可能变。