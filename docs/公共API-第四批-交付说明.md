# 公共 API · 第四批交付说明（受校验的购买/出售 + 成交事件）

## 一、本批交付

| 交付物 | 说明 |
| --- | --- |
| `ShopApi.buyDaily(uuid, index, count, expectedPrice)` | 每日商店购买（复用内部 `buyShop`，**价格复核**） |
| `ShopApi.buyMall(uuid, index, count, expectedPrice)` | 购物城购买（复用 `buyMall`） |
| `ShopApi.sellBuyback(uuid, index, count)` | 卖给每日收购（复用 `sellBuyback`） |
| `PurchaseCompletedEvent` | 成交后发布：`playerId, source, itemId, item, count, totalPaid` |
| `SellCompletedEvent` | 成交后发布：`playerId, source, itemId, item, count, totalEarned` |
| 新错误码 | `PLAYER_OFFLINE`、`PRICE_CHANGED`、`SOLD_OUT` |
| 内部方法可见性 | `buyShop` / `buyMall` / `sellBuyback` 由 `private` → `public`（供 API 层复用，逻辑未改） |

## 二、校验顺序（全部在动账之前）

```
① 主线程          → WRONG_THREAD
② 参数/账户        → INVALID_ARGUMENT / UNKNOWN_PLAYER
③ 下标 / 数量 1..64 → INVALID_ARGUMENT
④ 价格复核         → PRICE_CHANGED
⑤ 库存 / 收购份额   → SOLD_OUT
⑥ 玩家在线         → PLAYER_OFFLINE
⑦ 余额            → INSUFFICIENT_FUNDS
⑧ 执行（内部方法）→ 成交后发布事件
```

**校验失败绝不产生任何扣款或物品变动**（实测余额不变）。

## 三、实测证据（真服务器 + KubeJS）

```
[API4] balance = 1378
[API4] offline buy  -> fail(PLAYER_OFFLINE)
[API4] bad index    -> fail(INVALID_ARGUMENT: 商品下标越界：999)
[API4] bad count    -> fail(INVALID_ARGUMENT: count 必须在 1..64 之间)
[API4] wrong price  -> fail(PRICE_CHANGED: 价格已变化：你看到 125，当前 2)
[API4] offline sell -> fail(PLAYER_OFFLINE)
[API4] balance after all guards = 1378      ← 全程零副作用
```

服务端 `Done (5.412s)`，KubeJS `2/2 scripts, 0 errors`，模组报错 0 条。

## 四、设计决策

1. **不让外部实现经济规则**：执行类接口只是"带门禁的转发"，真正的扣款/发货仍走内部既有方法（价格重算、限购、库存、邮件兜底全在内部）；
2. **价格复核强制可选但推荐**：`expectedPrice != 当前价` 直接 `PRICE_CHANGED`，避免"看到 2 却按 200 扣"；
3. **成交判定用余额差分**：内部方法返回 void 且会自己弹提示，API 通过「余额是否变化」判断是否真的成交，未成交返回 `SOLD_OUT`（不重复扣款）；
4. **参数校验前置**：便宜的参数检查放最前，离线也能得到明确错误码（便于脚本/任务模组调试）。

## 五、已知限制

| 限制 | 说明 |
| --- | --- |
| 购买必须玩家在线 | 物品要进背包/邮箱；离线投递请用 `ItemDeliveryApi.give` |
| 每日商店/收购需当日已刷新 | API 不会替你付费刷新（`refreshDaily` 有费用规则） |
| `index` 语义是"当日列表下标" | 列表随刷新变化；**务必先 `dailyShop()` 再取下标**，并在调用时传 `expectedPrice` |
| 未开放 | 摊位上架/拍卖出价/求购发布/货币兑换（这些流程依赖界面会话与多步输入） |