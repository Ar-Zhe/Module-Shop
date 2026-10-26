# 公共 API · 第三批交付说明（统一扣款 + 只读商店查询 + 活动查询）

## 一、本批交付

| 交付物 | 说明 |
| --- | --- |
| **统一扣款入口** `ShopManager.debit(account, amount, reason)` | 余额不足**不扣**并返回 `-1`；扣款成功发布 `BalanceChangedEvent`；不负责 `setDirty()`（沿用调用方） |
| 扣款路径改造 | 商店购买(2)/刷新付费(2)/购物城购买/出货箱升级/拍卖出价/摊位手续费/求购托管/摊位购买/转账/兑换取出/补签 **共 14 处**改为走 `debit(...)` |
| `ShopApi`（只读） | `dailyShop(uuid)` / `mall()` / `buyback(uuid)` → `List<ShopItemView>` |
| `ShopItemView` | `id, stack, price, stock, unlimited` + `available()` |
| `DailyApi.activities()` | 活动日程只读：`ActivityView(day, title, note, money, rewardCount, hour)` |

## 二、事件覆盖范围（相比第二批的改进）

| 场景 | 第二批 | 第三批 |
| --- | --- | --- |
| 加钱（credit） | ✅ | ✅ |
| API 扣钱（take） | ✅ | ✅ |
| **商店购买** | ❌ | ✅（走 debit） |
| **每日商店/收购刷新付费** | ❌ | ✅ |
| **购物城购买 / 出货箱升级 / 兑换取出 / 补签** | ❌ | ✅ |
| **拍卖出价 / 摊位手续费 / 摊位购买 / 求购托管 / 转账** | ❌ | ✅ |
| **玩家交易扣钱** | ❌ | ✅ |
| 邮件创建 | ✅ | ✅ |
| 幂等奖励 / 签到 | ✅ | ✅ |

> 说明：`debit()` 在余额不足时返回 `-1`，各业务点保留原有"先校验再扣"的判断；
> 新增的 `-1` 分支只是并发兜底（正常不会触发），行为与改造前一致。

## 三、实测证据

```
[API3] mall items = 0
[API3] daily items = 6
[API3]   daily[0] daily-0 price=2 stock=10
[API3] buyback items = 6
[API3]   buyback[0] buyback-0 price=55 stock=16
[API3] activities = 0
[API3] balance = 1379
[API3] take(1) -> ok(1)

[API-event] BalanceChanged(df8c3037-… 1379->1378 d=-1 batch3 debit test)
```

服务端 `Done (6.384s)`；KubeJS `2/2 scripts, 0 errors`；模组报错 0 条。

## 四、已知限制

| 限制 | 说明 |
| --- | --- |
| **执行类接口仍未开放**（购买/出售/兑换/上架） | 现有实现依赖客户端界面下标与动作包；开放会绕过价格重算与限购校验。计划在重构出"物品+数量+期望价"入口后再放开（第四批） |
| `ShopApi.dailyShop/buyback` 依赖"当日已刷新" | 未刷新时返回**上一次生成**的列表；API 不会替你触发刷新（刷新有付费规则） |
| `mall` 测试服为空 | 该测试服未配置购物城商品池（`mallPool` 为空），逻辑正常 |
| 扣款事件 reason 是粗粒度字符串 | 目前用 `purchase/fee/auction_bid/stall_buy/transfer/demand_escrow/trade` 区分；细化到具体商品需业务层补 |