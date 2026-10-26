# Module Shop 公共 API · 开发者使用文档（API 1.0.0）

> 适用：`server_shop_mod_1789689358` **1.0.0**（MC 1.21.1 / NeoForge 21.1.248）
> API 版本：`1.0.0`（`ApiVersion.API_VERSION`，与模组版本独立）

## 1. 接入方式

```groovy
// 你的模组 build.gradle：只编译期依赖，运行期由服务端提供
compileOnly files('libs/server_shop_mod-1.0.0.jar')

// neoforge.mods.toml：声明依赖，保证加载顺序
[[dependencies.yourmod]]
    modId="server_shop_mod_1789689358"
    type="required"
    versionRange="[1.0.0,)"
    ordering="AFTER"
    side="BOTH"
```

```java
import cn.autoforged.server_shop_mod_1789689358.api.ServerShopApi;
```

## 2. 三条硬规则

| 规则 | 说明 |
| --- | --- |
| **服务端主线程** | 所有写操作必须在服务端主线程调用；否则返回 `WRONG_THREAD`。常用位置：命令、`ServerTickEvent.Post`、`PlayerLoggedInEvent` 等 |
| **只用 `ApiResult` 判断成败** | 不要看 `message()` 文案做逻辑；用 `isOk()` + `code()` |
| **写操作要幂等** | 奖励类调用必须用 `RewardApi.grantOnce(...)` 并传稳定的 `requestId` |

## 3. 接口总览

| 门面 | 方法 | 返回值 | 说明 |
| --- | --- | --- | --- |
| `economy()` | `balance(UUID)` | `long` | 余额（离线可查） |
| | `balanceView(UUID)` | `BalanceView` | 含上限与货币名；不存在返回 `null` |
| | `maxBalance()` | `long` | `0` = 不限 |
| | `add(UUID, long, String reason)` | `ApiResult<Long>` | 实际入账额；溢出转金钱邮件 |
| | `take(UUID, long, String reason)` | `ApiResult<Long>` | **余额不足整笔失败** |
| `items()` | `give(UUID, ItemStack)` | `ApiResult<Void>` | 在线进背包 / 满进邮箱；离线进邮箱 |
| | `giveAll(UUID, List<ItemStack>)` | `ApiResult<Void>` | 一次投递，合并成一封邮件 |
| `mail()` | `sendItemMail(UUID, title, body, List<ItemStack>)` | `ApiResult<String>` | 物品邮件 |
| | `sendMoneyMail(UUID, long, title, body)` | `ApiResult<String>` | 金额邮件（领取后到账） |
| | `mailbox(UUID)` | `List<MailInfo>` | 只读快照，新→旧 |
| | `unclaimed(UUID)` | `int` | 未领取数量 |
| `history()` | `history(UUID, int limit)` | `List<TransactionInfo>` | 新→旧，limit ≤ 200 |
| | `record(UUID, type, counterParty, long amount, String note)` | `ApiResult<Void>` | **只记账不动余额** |
| `daily()` | `signIn(UUID)` | `SignInView` | 签到状态（只读） |
| | `canSignToday(UUID)` | `boolean` | |
| | `activities()` | `List<ActivityView>` | 活动日程（只读，按日期升序） |
| `reward()` | `grantOnce(UUID, requestId, List<ItemStack>, long money, String reason)` | `ApiResult<Void>` | **幂等发奖** |
| `shop()` | `dailyShop(UUID)` / `mall()` / `buyback(UUID)` | `List<ShopItemView>` | **只读**：商品/价格/库存/个人剩余量 |
| | `buyDaily(UUID, index, count, expectedPrice)` | `ApiResult<Void>` | 购买每日商店（**需在线**；价格复核） |
| | `buyMall(UUID, index, count, expectedPrice)` | `ApiResult<Void>` | 购买购物城（同上） |
| | `sellBuyback(UUID, index, count)` | `ApiResult<Void>` | 卖给每日收购（**需在线**） |
| | `auctions()` | `List<AuctionView>` | 拍卖列表（只读） |
| | `bid(UUID, auctionId, amount)` | `ApiResult<Void>` | 拍卖出价（**需在线**，服务端复核最低价） |
| | `createDemand(UUID, List<ItemStack> wanted, money)` | `ApiResult<String>` | 发起求购（**需在线**，会托管金币+副手物） |
| | `cancelDemand(UUID, demandId)` | `ApiResult<Void>` | 撤销自己的求购（**需在线**） |
| | `stallList(UUID, stallIndex, slot, count, price)` | `ApiResult<Void>` | 背包槽位物品上架到摊位（**需在线**） |

## 4. 返回码

| 码 | 含义 | 调用方该怎么处理 |
| --- | --- | --- |
| `NONE` | 成功 | — |
| `INVALID_ARGUMENT` | 参数非法（空 UUID / 负数 / 空 requestId） | 修正参数，**不要重试** |
| `UNKNOWN_PLAYER` | 该 UUID 没有账户 | 确认玩家进过服务器 |
| `WRONG_THREAD` | 非主线程 | 用 `server.execute(...)` 排队到主线程后重试 |
| `INSUFFICIENT_FUNDS` | 余额不足（`take`） | 提示玩家，不要重试 |
| `LIMIT_EXCEEDED` | 已达余额上限 | 可改发邮件奖励 |
| `DUPLICATE_REQUEST` | 幂等键已用过 | **视为成功**（之前已发过），不要换 key 重发 |
| `MAILBOX_FULL` | 邮箱满且无兜底 | 提示玩家清邮箱 |
| `PLAYER_OFFLINE` | 该操作要求玩家在线（购买/出售/领取类） | 玩家上线后重试 |
| `PRICE_CHANGED` | 单价与你查询到的不一致 | **重新查询**后再调用 |
| `SOLD_OUT` | 售罄 / 收购份额已满 / 未成交 | 重新查询库存 |
| `INTERNAL_ERROR` | 内部异常（看服务端日志 `server_shop_api`） | 记录并反馈 |

## 5. 典型用法

### 5.1 每日任务结算（幂等）

```java
ApiResult<Void> r = ServerShopApi.reward().grantOnce(
        player.getUUID(),
        "mytasks:daily-" + LocalDate.now() + ":" + taskId,   // 幂等键：一天一次
        List.of(new ItemStack(Items.DIAMOND, 3)),
        100L,
        "每日任务：" + taskId);
if (r.isOk()) { ... } else if (r.code() == ApiErrorCode.DUPLICATE_REQUEST) { /* 已领过 */ }
```

### 5.2 只发钱 / 只发物品

```java
ServerShopApi.economy().add(uuid, 500, "活动奖励");
ServerShopApi.items().giveAll(uuid, List.of(new ItemStack(Items.BREAD, 16)));
```

### 5.3 给离线玩家发奖励邮件

```java
ServerShopApi.mail().sendItemMail(uuid, "离线奖励", "上线后领取", List.of(new ItemStack(Items.EMERALD, 5)));
```

### 5.4 记账（不动余额，仅丰富账单）

```java
ServerShopApi.history().record(uuid, "my_shop_buy", "我的商店", -200, "买了把剑");
```

### 5.5 需要多线程时

```java
server.execute(() -> ServerShopApi.economy().add(uuid, 100, "异步任务结算"));
```

### 5.6 受校验的购买（第四批开放）

```java
List<ShopItemView> offers = ServerShopApi.shop().dailyShop(uuid);
ShopItemView first = offers.get(0);
ApiResult<Void> r = ServerShopApi.shop().buyDaily(uuid, 0, 1, first.price());
// 失败码：PLAYER_OFFLINE / PRICE_CHANGED / SOLD_OUT / INSUFFICIENT_FUNDS / INVALID_ARGUMENT
```

校验顺序（全部在动账之前）：**参数 → 下标/数量 → 价格复核 → 库存 → 在线 → 余额**。
校验失败**不会**产生任何扣款；成功后发布 `PurchaseCompletedEvent` / `SellCompletedEvent`。

## 6. 不要做的事

- ❌ 不要 `import`/调用 `ShopManager`、`ShopSavedData`、`PlayerAccount`（内部实现，无兼容承诺）
- ❌ 不要直接改 `PlayerAccount.balance`（绕过上限/流水/持久化）
- ❌ 不要在客户端调用（API 只服务端有效，读到的都是 0/null）
- ❌ 不要用同一个 `requestId` 发不同奖励（会互相拦住）
- ❌ 不要为了重试而**换** `requestId`（那就失去幂等保护了）

## 7. 权限模型

| 级别 | 谁能用 | 可做的事 |
| --- | --- | --- |
| `PLAYER` | 玩家自身行为 | 只读查询、`take` 自己的钱 |
| `SYSTEM` | 任意模组（服务端主线程） | `add` / `give` / 邮件 / 记账 / `grantOnce` —— 全部受上限、幂等约束 |
| `ADMIN` | **不开放** | 改商品池、设余额、删邮件等仍只走 `/shop admin` |

> 「你是模组」≠「你是管理员」：API 里没有管理级写操作。

## 8. 版本与兼容

- 同一主版本内**只增不改**；破坏性变更提升主版本（`API_MAJOR`）；
- API 层不改变存档结构与网络协议；
- 幂等键持久化在存档里（最多 4000 条，超出丢最旧）→ **服务器重启后仍然防重复**。

## 9. 事件 API（第二批已实现）

事件发布在**业务成功之后**、状态已确定时；**不可取消**，监听器抛错只会写日志（`server_shop_api/events`），
不会影响已完成的经济事务。用独立总线 `ServerShopApi.events()` 注册：

```java
ServerShopApi.events().addListener((BalanceChangedEvent e) -> {
    if (e.delta() > 0) { /* 收入 */ }
});

ServerShopApi.events().addListener((MailCreatedEvent e) -> { ... });
ServerShopApi.events().addListener((RewardGrantedEvent e) -> { ... });
ServerShopApi.events().addListener((SignInCompletedEvent e) -> { ... });
```

| 事件 | 触发点（覆盖范围） | 字段 |
| --- | --- | --- |
| `BalanceChangedEvent` | `ShopManager.credit(...)`（**所有加钱路径**：商店/邮件/签到/管理员发放/API）+ `EconomyApi.take(...)` | `playerId, oldBalance, newBalance, delta, reason` |
| `MailCreatedEvent` | `addMailDraft(...)` 之后（**所有发信路径**，含同渠道合并之前） | `playerId, source, money, itemCount, title` |
| `RewardGrantedEvent` | `RewardApi.grantOnce(...)` 成功 | `playerId, requestId, money, itemCount, reason` |
| `PurchaseCompletedEvent` | `shop().buyDaily/buyMall` 成交（第四批） | `playerId, source, itemId, item, count, totalPaid` |
| `SellCompletedEvent` | `shop().sellBuyback` 成交（第四批） | `playerId, source, itemId, item, count, totalEarned` |
| `AuctionBidPlacedEvent` | `shop().bid` 成功（第五批） | `playerId, auctionId, amount` |
| `DemandCreatedEvent` | `shop().createDemand` 成功（第五批） | `playerId, demandId, rewardMoney, lineCount` |
| `StallListedEvent` | `shop().stallList` 成功（第五批） | `playerId, stallIndex, stack, count, price` |
| `SignInCompletedEvent` | 签到成功（含自动签到） | `playerId, day, streak, money` |

> 已知边界：商店内部**扣钱**（购买/收购）不走 `credit`，目前**不**发布 `BalanceChangedEvent`；
> 第二批只覆盖「加钱」与 API 扣钱。若需要覆盖全部扣款，需要把各业务扣款统一到一个 `debit()` 入口（列入后续批次）。