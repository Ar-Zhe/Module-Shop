# 公共 API · 第二批交付说明（事件 API）

## 一、本批交付

| 交付物 | 说明 |
| --- | --- |
| `api/event/BalanceChangedEvent` | 余额变化（**所有加钱路径** + API 扣钱） |
| `api/event/MailCreatedEvent` | 邮件创建（**所有发信路径**：`addMailDraft`） |
| `api/event/RewardGrantedEvent` | 幂等奖励发放成功 |
| `api/event/SignInCompletedEvent` | 签到完成（含自动签到） |
| `api/event/ApiEvents` | 安全发布器：监听器异常只写日志，绝不回抛业务 |
| 事件接线 | `ShopManager.credit` / `ShopManager.addMailDraft`（两处 `storeMail`）/ `doSignIn` / `ApiImpl.take` / `ApiImpl.Reward` |
| 运行时反查字段 | `PlayerAccount.uuidForEvents`（**不写存档**，由 `ShopSavedData.account(uuid)` 填充） |

## 二、设计决策

1. **只发布不可取消的"成功通知"**：避免监听器否决/回滚带来的数据不一致（经济事务一旦成功就必须落地）；
2. **集中在最少的点上发布**：加钱用 `credit()`（几乎覆盖全部收入），发信用 `addMailDraft()`（覆盖全部邮件），
   这样商店自身业务也能被外部统一感知，而不需要改每一处业务代码；
3. **异常隔离**：`ApiEvents.post` 包了 `try/catch(Throwable)`，监听器抛错只记录，业务照常完成；
4. **事件类继承 NeoForge `Event`**：外部可用标准 `addListener(...)` 注册，无需额外依赖。

## 三、实测证据（真服务器 + KubeJS 调用）

```
[API2] start balance = 1376
[API2] add -> ok(7)
[API2] take -> ok(7)
[API2] grantOnce -> ok(null)
[API2] sendMoneyMail -> ok(api-139176022134700)
[API2] unclaimed = 1
[API2] end balance = 1379

[API-event] BalanceChanged(df8c3037-… 1383->1376 d=-7 batch2 take)
[API-event] BalanceChanged(df8c3037-… 1376->1379 d=3 credit)
[API-event] RewardGranted(df8c3037-… batch2-0 money=3 items=0)
[API-event] MailCreated(df8c3037-… api money=11 items=0)
```

服务端 `Done (5.866s)`，模组报错 0 条；冒烟脚本已删除。

## 四、已知限制

| 限制 | 说明 |
| --- | --- |
| 商店内部**扣款**不发事件 | 购买/收购是 `account.balance -= x` 内联实现，不走 `credit`。要全覆盖需先统一 `debit()` 入口（后续批次） |
| 事件日志为 INFO | 便于排错；生产环境若嫌吵可调到 DEBUG（在 `ApiEvents` 一行改） |
| 无「购买完成 / 拍卖成交 / 交易完成」事件 | 需要先把这些业务从 `handleAction` 里抽成可复用方法（第四批） |
| 事件总线不是 `NeoForge.EVENT_BUS` | 用 `ServerShopApi.events()` 独立总线，避免与 NeoForge 原生事件混淆 |