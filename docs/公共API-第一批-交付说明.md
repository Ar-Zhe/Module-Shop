# 公共 API · 第一批交付说明

## 一、本批做了什么（阶段二 · 第一批）

| 交付物 | 位置 |
| --- | --- |
| API 核心（版本/结果/错误码/门面） | `src/main/java/.../api/ApiVersion.java`、`ApiResult.java`、`ApiErrorCode.java`、`ServerShopApi.java` |
| 只读视图 | `api/view/BalanceView.java`、`TransactionInfo.java`、`MailInfo.java`、`SignInView.java` |
| 接口 | `api/EconomyApi.java`、`ItemDeliveryApi.java`、`MailApi.java`、`HistoryApi.java`、`DailyApi.java`、`RewardApi.java` |
| 内部实现 | `api/internal/ApiImpl.java`（只组合既有能力：`credit` / `addItemOrMail` / `addMailDraft` / `TransactionLog.record`） |
| 幂等键持久化 | `ShopSavedData.apiRequests`（`LinkedHashSet<String>`，上限 4000，追加字段，老存档兼容） |
| 通用邮件模板 | `MailTemplates.Template.API_MAIL`（`Kind.SYSTEM`） |
| 开发者文档 | `docs/公共API-使用文档.md` |
| 最小示例模组 | `examples/minimal-task-mod/`（含 README、build.gradle、示例命令 `/exampletask`） |

## 二、对现有代码的改动（最小化）

| 文件 | 改动 | 风险 |
| --- | --- | --- |
| `ShopSavedData` | 追加 `apiRequests` 字段 + save/load + `trimApiRequests()` | 追加字段，老存档读成空集合 ✔ |
| `MailTemplates` | 追加 `API_MAIL` 模板 | 纯追加 ✔ |
| `ShopManager.nameOf` | `private` → `public`（API 实现需要显示名） | 只读工具，无行为变化 ✔ |
| 语言文件 | 追加 2 个键（`received_money` / `api_reward`） | 纯追加，中英各 847 键一致 ✔ |
| 经济规则 / 玩法 | **未改动** | — |

## 三、实测证据（测试服务器 + KubeJS 调用真实 API）

A. KubeJS 脚本（`ServerEvents.loaded`，服务端主线程）调用公共 API，日志实测：

```
[API] server_shop_api 1.0.0
[API] maxBalance = 0                       ← 配置为不限
[API] balance(before) = 1375
[API] add(5) -> ok(5)  balance=1380        ← 加钱成功、受上限
[API] take(5) -> ok(5)  balance=1375       ← 扣钱成功
[API] take(too much) -> fail(INSUFFICIENT_FUNDS)   ← 余额不足整笔失败 ✔
[API] grantOnce #1 -> ok(null)
[API] grantOnce #2(dup) -> fail(DUPLICATE_REQUEST) ← 幂等生效 ✔
[API] balance after grant = 1376
```

B. 模组自身流水视图（`/shop admin history <玩家名> 1`，权威核对）：

```
[10-22 22:06] External entry 奖励 +1 balance 1376 API 冒烟奖励
[10-22 22:06] External entry API -5 balance 1375 api smoke take
[10-22 22:06] External entry API +5 balance 1380 api smoke add
```

C. 服务端启动：`Done (6.087s)`，模组报错 0 条；冒烟脚本已删除、存档未污染（余额回到原值+1，测试用）。

## 四、关键设计决策

1. **`api` 包不 import 任何内部类**（除实现的 `internal` 子包），只暴露接口 + 不可变视图 + `ApiResult`；
2. **所有写操作复用现有实现**（`credit` / `addItemOrMail` / `addMailDraft` / `TransactionLog.record`），不复制经济规则；
3. **线程断言**：非主线程返回 `WRONG_THREAD`（不自作主张切线程，避免调用方语义混乱）；
4. **`take` 余额不足整笔失败**；`add` 溢出部分转金钱邮件（不丢弃）；
5. **幂等键持久化**（存档 `apiRequests`，上限 4000 条 LRU）→ 重启后仍防重复；
6. **`setBalance` 不进 API**；`ADMIN` 级操作只留命令；
7. API 版本 `1.0.0` 独立于模组版本；同 jar 内提供，外部 `compileOnly`。

## 五、已知限制

| 限制 | 说明 / 后续 |
| --- | --- |
| 事件未发布 | `ServerShopApi.events()` 已预留，第一批无事件（第二批） |
| `DailyApi` 只读 | 不允许外部伪造签到；奖励请用 `RewardApi` |
| 流水每人仅保留 50 条（可配 10~200） | 大跨度对账请用 `/shop bill` 导出 + 及时导出 |
| Shop/Exchange 执行类未开放 | 现有购买/收购收"界面下标"，需先重构为"物品+数量+期望价"（第四批） |
| 示例模组未在本仓库编译 | 独立 Gradle 工程；`compileOnly` 依赖本模组 jar，路径在 `gradle.properties` 的 `server_shop_jar` |
| KubeJS 兼容层 | 本轮未做（KubeJS 2101.x 可直接 `Java.loadClass` 调用 Java API，已在冒烟测试中验证可行） |

## 六、构建与校验

```bash
cd <仓库根目录>
./gradlew build        # 产物：build/libs/server_shop_mod-1.0.0.jar
```
SHA-256 见 `server-deploy/SHA256校验.txt`（每次部署同步更新）。