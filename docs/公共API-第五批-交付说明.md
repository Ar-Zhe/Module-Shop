# 公共 API · 第五批交付说明（拍卖出价 / 求购发布与撤销 / 摊位上架）

## 一、本批交付

| 接口 | 语义 | 复用内部方法 |
| --- | --- | --- |
| `ShopApi.auctions()` | 拍卖列表只读快照 | 读 `ShopSavedData.auctions` |
| `ShopApi.bid(uuid, auctionId, amount)` | 出价（需在线；复核最低价、不能拍自己的） | `ShopManager.placeBid` |
| `ShopApi.createDemand(uuid, wanted, money)` | 发起求购（需在线；托管金币 + 副手悬赏物） | `ShopManager.demandCreate` |
| `ShopApi.cancelDemand(uuid, demandId)` | 撤销自己的求购（托管退回） | `ShopManager.demandCancel` |
| `ShopApi.stallList(uuid, stallIndex, slot, count, price)` | 背包槽位物品上架摊位 | `ShopManager.stallSetPriceNew` |
| `AuctionView` | `auctionId, seller, sellerName, stack, startPrice, currentBid, topBidder, endTime, ended` | — |
| 事件 | `AuctionBidPlacedEvent` / `DemandCreatedEvent` / `StallListedEvent` | — |

内部方法可见性：`placeBid` / `demandCreate` / `demandCancel` / `stallSetPriceNew` 由 `private` → `public`（供 API 转发，逻辑未改）。

## 二、校验与成交判定

| 接口 | 校验顺序 | 成交判定 |
| --- | --- | --- |
| `bid` | 主线程 → 参数 → 拍卖存在 → 是否已结束 → 不能拍自己 → 最低价 → 余额 → 在线 | 余额减少 > 0 |
| `createDemand` | 主线程 → 配置开启 → 参数（空清单/负数/超 12 条）→ 账户 → 在线 | `demands` 数量增加 |
| `cancelDemand` | 主线程 → 参数 → 账户 → 存在且是自己的 → 在线 | `demands` 里该 id 消失 |
| `stallList` | 主线程 → 参数（下标/槽位/数量/价格）→ 账户 → 在线 → 背包槽位物品足够 | `account.stall` 数量增加 |

**校验失败不产生任何副作用**（实测余额不变）。

## 三、实测证据（真服务器 + KubeJS）

```
[API5] balance = 1378
[API5] auctions = 0
[API5] bid(unknown)        -> fail(INVALID_ARGUMENT: 拍卖不存在：999999)
[API5] bid(bad amount)     -> fail(INVALID_ARGUMENT: 出价必须 > 0)
[API5] createDemand(empty) -> fail(INVALID_ARGUMENT: 想要清单不能为空)
[API5] createDemand(neg)   -> fail(INVALID_ARGUMENT: 悬赏不能为负)
[API5] cancelDemand(bogus) -> fail(INVALID_ARGUMENT: 求购不存在：nope)
[API5] stallList(offline)  -> fail(PLAYER_OFFLINE)
[API5] stallList(bad cnt)  -> fail(INVALID_ARGUMENT: count 必须在 1..64 之间)
[API5] stallList(bad price)-> fail(INVALID_ARGUMENT: 价格必须 > 0)
[API5] balance after guards = 1378      ← 零副作用
```

服务端 `Done (5.677s)`，KubeJS `2/2 scripts, 0 errors`，模组报错 0 条。

## 四、已知限制

| 限制 | 说明 |
| --- | --- |
| 求购会**托管副手物品** | 与游戏内行为一致（副手放着的东西会被当作悬赏物），调用方需自行保证副手是预期物品 |
| `createDemand` 失败原因较粗 | 内部方法返回 void 且只弹提示；失败统一返回 `INTERNAL_ERROR` 并附排查建议（余额/条数上限/开关） |
| `stallList` 的 `slot` 是背包原始槽位 | 0~8 快捷栏、9~35 主背包、36~40 护甲/副手；调用方需自行选定 |
| 出价手续费 | 沿用内部规则（若有），API 不额外加价 |
| 未开放 | 拍卖上架/结算、求购交付（`demandFulfill`）、交易会话 —— 这些需要多步状态或界面确认 |