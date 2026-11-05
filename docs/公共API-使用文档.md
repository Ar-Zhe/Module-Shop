# Module Shop 公共 API · 开发者使用文档（API 1.1.0）

> 适用：`module_shop` **1.0.0**（MC 1.21.1 / NeoForge 21.1.248）
> API 版本：`1.1.0`（`ApiVersion.API_VERSION`，与模组版本独立；1.1.0 新增客户端扩展点 `ShopModules`，见第 10 节）

## 1. 接入方式

```groovy
// 你的模组 build.gradle：只编译期依赖，运行期由服务端提供
// 推荐只对「公共 API jar」编译（构建主体时自动产出 build/libs/module_shop-<版本>-api.jar，
// 只含 cn.autoforged.module_shop.api.**）：这样你的代码在编译期就碰不到内部实现。
compileOnly files('libs/module_shop-1.0.3-api.jar')

// neoforge.mods.toml：声明依赖，保证加载顺序
[[dependencies.yourmod]]
    modId="module_shop"
    type="required"
    versionRange="[1.0.0,)"
    ordering="AFTER"
    side="BOTH"
```

```java
import cn.autoforged.module_shop.api.ModuleShopApi;
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
| `INTERNAL_ERROR` | 内部异常（看服务端日志 `module_shop_api`） | 记录并反馈 |

## 5. 典型用法

### 5.1 每日任务结算（幂等）

```java
ApiResult<Void> r = ModuleShopApi.reward().grantOnce(
        player.getUUID(),
        "mytasks:daily-" + LocalDate.now() + ":" + taskId,   // 幂等键：一天一次
        List.of(new ItemStack(Items.DIAMOND, 3)),
        100L,
        "每日任务：" + taskId);
if (r.isOk()) { ... } else if (r.code() == ApiErrorCode.DUPLICATE_REQUEST) { /* 已领过 */ }
```

### 5.2 只发钱 / 只发物品

```java
ModuleShopApi.economy().add(uuid, 500, "活动奖励");
ModuleShopApi.items().giveAll(uuid, List.of(new ItemStack(Items.BREAD, 16)));
```

### 5.3 给离线玩家发奖励邮件

```java
ModuleShopApi.mail().sendItemMail(uuid, "离线奖励", "上线后领取", List.of(new ItemStack(Items.EMERALD, 5)));
```

### 5.4 记账（不动余额，仅丰富账单）

```java
ModuleShopApi.history().record(uuid, "my_shop_buy", "我的商店", -200, "买了把剑");
```

### 5.5 需要多线程时

```java
server.execute(() -> ModuleShopApi.economy().add(uuid, 100, "异步任务结算"));
```

### 5.6 受校验的购买（第四批开放）

```java
List<ShopItemView> offers = ModuleShopApi.shop().dailyShop(uuid);
ShopItemView first = offers.get(0);
ApiResult<Void> r = ModuleShopApi.shop().buyDaily(uuid, 0, 1, first.price());
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

事件发布在**业务成功之后**、状态已确定时；**不可取消**，监听器抛错只会写日志（`module_shop_api/events`），
不会影响已完成的经济事务。用独立总线 `ModuleShopApi.events()` 注册：

```java
ModuleShopApi.events().addListener((BalanceChangedEvent e) -> {
    if (e.delta() > 0) { /* 收入 */ }
});

ModuleShopApi.events().addListener((MailCreatedEvent e) -> { ... });
ModuleShopApi.events().addListener((RewardGrantedEvent e) -> { ... });
ModuleShopApi.events().addListener((SignInCompletedEvent e) -> { ... });
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

## 10. 客户端扩展点：把附属功能挂进商店侧栏（1.0.3+ / API 1.1.0）

附属模组可以在商店主界面左侧功能区注册一个条目。两种形式：

| 形式 | 方法 | 效果 |
| --- | --- | --- |
| **内容区页面（推荐）** | `ShopModules.registerPage(id, name, pageFactory, fallbackScreen)` | 和内置模块一样画在商店右侧内容区（AtomLib / Skija）；AtomLib 不可用时打开 `fallbackScreen` |
| 弹出式界面 | `ShopModules.register(id, name, screenFactory)` | 点击后以二级界面打开你的 Screen，返回 / ESC 回到商店 |

```java
import cn.autoforged.module_shop.api.client.*;

// 仅客户端：FMLClientSetupEvent 里注册
modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() ->
        ShopModules.registerPage("mymod_feature",                  // 全局唯一，建议带 modid 前缀
                () -> Component.translatable("tab.mymod.feature"),  // 侧栏显示名
                MyFeaturePage::new,                                 // AddonPage 实现（只创建一次，实例常驻）
                MyFeatureScreen::new)));                            // AtomLib 不可用时的回退界面

public final class MyFeaturePage implements AddonPage {
    private SkiaTextField input;

    @Override public void init(AddonPageHost host) {            // 每次商店界面 init 都会调用
        input = new SkiaTextField(host.screen(), host.font(), 32);
        host.registerInput(input.editBox());                    // 隐藏 EditBox 负责键盘与输入法
    }

    @Override public void render(Canvas canvas, AddonFrame f) {
        Text.drawLine(canvas, "Hello", ShopTheme.bold(), Text.TITLE, ShopTheme.text(),
                f.x(), f.y() + 10, f.w(), Text.Align.LEFT);
        input.bounds(f.x(), f.y() + 24, 120, 14).visible(true);
        input.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
    }

    @Override public boolean mouseClicked(AddonFrame f, double x, double y, int b) {
        return input.mouseClicked(x, y, b);
    }

    @Override public void cancelInput() { input.blur(); input.visible(false); }
}
```

| 类型 | 说明 |
| --- | --- |
| `AddonPage` | 页面：`init` / `render` / 鼠标事件 / `clickedOutside` / `cancelInput`（与内置页面同一套路由） |
| `AddonFrame` | 一帧的内容区几何（x / y / w / h / 鼠标 / 帧时长）；`listBottom()`、`barTop()` 与内置页面的底栏对齐 |
| `AddonPageHost` | `screen()`、`font()`、`registerInput(EditBox)`、`textTooltip(...)`、`balance()`、`currencyName()` |
| `ShopTheme` | 配色（随日 / 夜主题变化，**每帧取值**）与字体 `regular()` / `bold()`；`atomLibAvailable()` |
| **UI 套件** | `client.ui.render.Draw / Text / SkiaFrame`、`client.ui.widget.Button / SkiaTextField / Motion / ScrollController`：内置页面用的同一套控件，已打进公共 API jar |

| 规则 | 说明 |
| --- | --- |
| 只在客户端调用 | 专用服务器不要加载 `api.client` 与 UI 套件（它们引用了 `Screen` / Skija） |
| id | `[a-z0-9_]{1,48}`；与内置模块重名时内置优先，附属条目被忽略 |
| 排序 / 移除 | 新条目默认排在侧栏末尾；管理员可在「编辑布局」里拖动或移除，和内置模块一样持久化 |
| AtomLib | 只带 Windows 原生库；其它平台商店整体走旧版绘制，附属页面也随之改用回退界面 |
| 编译依赖 | `module_shop-<版本>-api.jar` + AtomLib jar + Skija jar（均 `compileOnly`）；可参考红包附属的 `build.gradle`：通过 `module_shop_version` 指定主体版本，API jar 放进 `libs/` 或用 `-Pmodule_shop_root` 指向本地主体仓库 |
| 服务端逻辑 | 扩展点只负责入口；收发数据用你自己的网络包，动钱 / 发物品走上面的公共 API |
| 版本检查 | `ApiVersion` 的常量会被 javac 内联成编译时的值；运行时判断请用 `ApiVersion.describe()` |
| **跨版本兼容** | API 与 UI 套件在同一主版本（1.x）内**只增不改**：附属对 1.0.3 编译，在之后的 1.x 主体上不用重新发布 |
| 出错兜底 | 附属页面抛异常或调用了不存在的方法（`LinkageError`）时，商店停用该页面、记日志并打开回退界面，商店本身不受影响 |

完整示例：[module-shop: Red envelope（拼手气红包）](https://github.com/ChatMiaoPT-Meow/Module-Shop-RedPacket)。
