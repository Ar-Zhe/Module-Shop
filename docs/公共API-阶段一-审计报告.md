# 公共 API 现状审计报告（阶段一 · 只读）

> 项目：`server_shop_mod_1789689358` **1.0.0**
> 环境：Minecraft **1.21.1** · NeoForge **21.1.248** · Java **21** · ModDevGradle **2.0.144** · 网络通道版本 `"2"`
> 审计方式：**只读源码 + 构建配置**，未修改任何 `.java`。所有结论后标注了出处（类/行号），无法从源码确认的一律标注「待确认」。
> 审计时间基准：本仓库（93 个 `.java`，`ShopManager.java` 6207 行）

---

## 0. 结论速览（先看这一段）

| 结论 | 说明 |
| --- | --- |
| **目前没有公共 API** | 没有任何 `api` 包；没有对外发布的 NeoForge 事件（全项目只有 `@Mod` 入口、`@EventBusSubscriber` 自用监听、菜单/音效注册，无 `EVENT_BUS.post`）；没有 KubeJS 桥接 |
| **现有入口全部是"内部实现"** | 业务逻辑集中在 `ShopManager`（6207 行、33 个 `public static` 方法）与 `ShopSavedData`/`PlayerAccount`（可变实体直接暴露），外部调用会绕过校验 |
| **最大风险** | `ShopManager.data(server)` 与 `account(player)` 直接返回**可变存档对象**；用它改余额/背包 = 绕过上限、流水、通知、持久化标记 |
| **最适合先封装的能力** | ①余额查询 ②受校验的加/扣钱（已有 `credit`/`recordExternal`）③物品投递（`addItemOrMail`/`addMail`）④金额邮件（`addMoneyMail`）⑤流水记录（`TransactionLog.record`） |
| **需要先重构的能力** | ①商店购买/收购/兑换（与 GUI 动作包 `handleAction` 强耦合）②交易（TradeManager 全流程绑 UI）③签到/活动（发放逻辑混在 tick 与界面里） |
| **线程约束** | 所有写操作必须在**服务端主线程**（`ServerLevel`/`MinecraftServer` 的 tick 线程）执行；现有代码只有 `ShopManager.LOCK`（`synchronized`）做进程内互斥，**不提供跨线程安全** |
| **建议形态** | 先做 **Java 公共 API**（独立 `api` 包，语义化版本 `server_shop_api_version`），KubeJS 桥接与 HTTP **暂不做**（需另行评估） |

---

## 1. 审计范围与方法

| 项目 | 内容 |
| --- | --- |
| 读取的文件 | `gradle.properties`、`build.gradle`、`src/main/templates/META-INF/neoforge.mods.toml`、`ServerShopMod`、`ShopManager`、`ShopSavedData`、`PlayerAccount`、`MailEntry`、`MailTemplates`、`TradeManager`、`TransactionLog`、`ModNetwork`、`ShopCommands`、`ShopServerEvents`、`Config`、`GrantManager`、`NotificationManager`、`SignInData`、`ActivityDay`、`BillExporter` |
| 未覆盖 | 客户端界面类只做"是否耦合经济逻辑"的判断，未逐行审计（本轮目标是服务端业务接口） |
| 判定标准 | 「可直接封装」= 现有方法语义自洽、参数是原语/不可变数据、有明确失败路径；「需重构」= 依赖玩家 GUI 状态、依赖动作包、或与 tick 时序耦合 |

---

## 2. 功能模块矩阵（真实入口 / 数据归属 / 持久化）

> 说明：所有金额单位都是 `long`「星币」；余额上限见 §4.1。

| # | 功能模块 | 真实入口（源码位置） | 核心逻辑 / 数据所有者 | 持久化位置 | 可否直接封装 |
| --- | --- | --- | --- | --- | --- |
| 1 | 钱包 / 余额查询 | `ShopManager.balanceOf(ServerPlayer)`（L5679）；快照下发 `sendSnapshot` → `tag.putLong("balance")` | `PlayerAccount.balance`（L14） | `ShopSavedData.accounts`（世界存档 `server_shop_mod_data`） | ✅ 可直接封装（补一个"按 UUID 查"的重载） |
| 2 | 货币增加 | `ShopManager.credit(PlayerAccount, amount, max)`（L5595）；`adminGive`（L5620） | `PlayerAccount.balance` + 上限 `Config.maxBalanceLimit()`（`maxBalance<=1` 视为不限） | 同上 | ⚠️ 需包装：`credit` 收 `PlayerAccount`（可变实体），且**不写流水、不通知、不 setDirty** |
| 3 | 货币扣除 | 无统一方法；各业务自己 `account.balance -= x`（如 `buyMall` L1755、`demandCreate`） | 同上 | 同上 | ❌ 必须重构：扣款散落各处，无统一"够不够/上限/流水/通知" |
| 4 | 设置余额 | `adminSetBalance`（L5637）、`adminSetBalanceByName`（L5654，控制台用） | 同上 | 同上 | ⚠️ 管理员语义，不建议进公共 API（或仅暴露给"受信任"级别） |
| 5 | 转账 | `handleAction` 内 `transfer`（L1311 分发） | `PlayerAccount.balance` + `lastTransferTick`（防刷） | 同上 | ❌ 与动作包/界面耦合 |
| 6 | 流水 / 账单 | `TransactionLog.record(server, uuid, type, counterParty, amount, balanceAfter, note)`（L68）；`recordExternal`（`ShopManager` L5945 按玩家名）；查看 `historyLines`（L5608）/ 界面 `BillScreen`；导出 `BillExporter`（新，CSV） | `PlayerAccount.transactions`（每人上限 `transactionHistorySize`，默认 50） | 同上 | ✅ `TransactionLog.record` 语义清晰，适合作为 API 的底层；`BillExporter` 已是现成只读导出 |
| 7 | 每日商店 | `ShopSavedData.shopPool` + `PlayerAccount.dailyShop`；购买在 `handleAction`（`buy_daily` 分支）/ `buyMall` 同族逻辑；刷新 `refreshDaily`（L5716）、`ensureDaily`（L141） | 商品池全局、个人当日报价 | 同上 | ⚠️ 查询可封装；**执行购买需重构**（现在收 `index` + 数量，靠界面下标） |
| 8 | 购物城（常驻） | `ShopSavedData.mallPool`（`MallItem`）；购买 `buyMall(ServerPlayer, int index, int requested)`（L1741，`private`）；补货/刷新 tick（L2099/L2169） | `MallItem.stock/stockMax/price` | 同上 | ⚠️ 同上：把 `index` 换成"物品+数量+期望单价"才适合外部调用 |
| 9 | 每日收购 | `ShopSavedData.buybackPool` + `PlayerAccount.dailyBuyback`；出售 `sellBuyback`（`private`） | 同上 | 同上 | ⚠️ 同上 |
| 10 | 玩家摊位 | `PlayerAccount.stall`（`List<StallEntry>`）；上架 `stallSetPriceNew`（`private`）、下架 `removeStall`、购买 `stallBuy`；到期 `tickStallExpire`（L3293） | 每个玩家自己的摊位条目 | 同上 | ⚠️ 查询可封装；执行需重构（上架依赖"手里拿的物品"流程） |
| 11 | 拍卖会 | `ShopSavedData.auctions`（`AuctionEntry`）；出价 `placeBid`、结算 `tickAuctions`（L621） | 全局拍卖列表 | 同上 | ⚠️ 同摊位 |
| 12 | 玩家间交易 | `TradeManager`：`request/openFor/acceptRequest/addItem/setMoney/toggleAgree/confirm/cancel/tick`（L45–332） | 会话状态在 `TradeManager` 内存 `Map`，物品在双方"交易暂存" | **不持久化**（服务器重启即失效，`shutdown` 处理） | ❌ 全流程绑 UI + 会话；只适合提供"完成事件"，不适合作为调用接口 |
| 13 | 求购（悬赏） | `ShopSavedData.demands`（`DemandEntry`）；发布 `demandCreate`、交付 `demandFulfill`、撤销 `demandCancel`、到期 `tickDemandExpire`（均 `private`） | 求购条目 + 托管悬赏 | 同上 | ⚠️ 发布依赖客户端选物界面；交付/查询可封装 |
| 14 | 邮箱 | `PlayerAccount.mailbox`（`MailEntry`）；`addMailDraft`（L423，**唯一正确入口**：受 `maxMailboxSize` 限制 + 合并同渠道 + 红点）；`addMail(server, uuid, stack, source[, sender])`（L6003/6008）；`addMoneyMail`（L6056）；领取在 `handleAction("claim_mail")`；清理 `purgeExpiredMails`（L400） | `MailEntry`（物品/金额/过期/`isClaimed`） | 同上 | ✅ `addMail` / `addMoneyMail` / `addMailDraft` 已是"投递兜底"语义，适合封装 |
| 15 | 物品发放（在线/离线） | `addItemOrMail(ServerPlayer, stack, source)`（L5984，**在线**：进背包→满则邮件）；`addMail(server, uuid, …)`（**离线**）；管理员发放 `GrantManager`（容器界面） | 背包 + 邮箱 | 背包（玩家 NBT）/ 邮箱（存档） | ✅ 组合封装成 `ItemDelivery` 即可 |
| 16 | 签到 | `ShopSavedData.signIns`（`Map<UUID, SignInData>`，字段：`lastSignDay/streak/bestStreak/signedDays/claimedMilestones`）；`autoSignInIfNeeded(ServerPlayer)`（L4427，public）；补签/领取在 `handleAction` | 个人签到数据 | 同上 | ⚠️ 查询可封装；"发放签到奖励"混在方法内部，需抽出"只读状态 + 只发奖励"两个能力 |
| 17 | 日程 / 活动奖励 | `ShopSavedData.activityDays`（`ActivityDay`：`money/stack/rewards/granted/itemGranted/giftGranted`）；`grantActivityDay(ServerPlayer)`（L5066，public）、`grantActivityDayOffline(server)`（L5148） | 活动日 + 每人已领标记 | 同上 | ⚠️ 已内建"每人只领一次"标记，**是幂等性的良好基础**；但入口依赖 `ActivityDay` 实体 |
| 18 | 兑换码 | `handleAction` 的 `redeem_*` 分支 + `RedeemCode`（`ShopSavedData` 内）；生成器是客户端界面（`RedeemGeneratorScreen`） | 兑换码 + 领取记录 | 同上 | ⚠️ 只适合"查询/核销"级别，生成流程与 UI 绑定 |
| 19 | 货币兑换 | `ShopSavedData.exchangePool`（`ExchangeEntry`）；请求在 `handleAction("exchange_deposit_gui")` 等 | 兑换项 + 个人持有 | 同上 | ⚠️ 与界面输入框强耦合 |
| 20 | 出货箱 | `PlayerAccount.shipping`（`ShippingBin`）+ `ShippingRules`（文本规则，`config` 文件 `shipping_rules.txt`）；`openShippingBin`（菜单）、结算 `tickShipping`（L2057） | 个人出货箱 + 全局规则 | 存档 + 配置文件 | ❌ 菜单/容器耦合 |
| 21 | 通知红点 | `NotificationManager.notify(server, uuid, NotificationType)` | `PlayerAccount.notifications` | 同上 | ⚠️ 可给"受信任"级别用 |
| 22 | 配置 / 持久化 | `Config`（`ModConfigSpec`，COMMON）、`ShopSavedData extends SavedData`（`FILE_ID=server_shop_mod_data`）、`ConfigMigration`（一次性迁移） | — | `config/server_shop_mod_1789689358-common.toml`、世界 `data/server_shop_mod_data.dat` | ✅ 只读查询（上限、货币名）可封装 |

**模块是否"真的存在"的核实结论**：上表 22 项全部在源码中找到对应实现（路径已标行号），**没有"只写在文档里"的功能**。

---

## 3. 现有可复用入口（哪些能直接用、哪些不能）

### 3.1 已经具备"公共 API 雏形"的方法

| 方法 | 语义 | 缺口 |
| --- | --- | --- |
| `ShopManager.credit(account, amount, max)` | 受上限约束的加钱，返回**实际入账额** | 需要 `PlayerAccount`；不写流水、不通知、不 `setDirty()` |
| `ShopManager.addItemOrMail(player, stack, source)` | 在线投递：背包 → 满转邮箱 | 只支持在线 `ServerPlayer` |
| `ShopManager.addMail(server, uuid, stack, source[, sender])` | 离线/强制邮件投递（含"邮件满→掉地/保留"兜底） | 每次一封，无多附件版本 |
| `ShopManager.addMailDraft(server, uuid, draft)` | **推荐入口**：多附件、同渠道合并、上限、红点 | 需要 `MailTemplates.DraftMail`（内部类） |
| `ShopManager.addMoneyMail(...)` | 金额邮件（用于余额上限溢出） | 参数较多（display/sender/uuid） |
| `TransactionLog.record(...)` | 写流水（含对手方、交易后余额） | 需要自己算 `balanceAfter` |
| `ShopManager.recordExternal(server, playerName, amount, reason, source)` | **按玩家名**记账（KubeJS/外部系统最省事的入口） | 只按名字，**不能改物品/邮件** |
| `ShopManager.balanceOf(player)` / `historyLines(server, name, page)` | 查询 | 只按在线玩家 / 按名字翻页 |
| `ShopManager.data(server)` / `account(player)` | 取存档/账户 | ⚠️ **返回可变对象，禁止作为 API** |

### 3.2 明确**不适合**作为公共接口（耦合 GUI / 动作包 / 命令）

| 方法 | 原因 |
| --- | --- |
| `ShopManager.handleAction(ServerPlayer, CompoundTag)`（L1311） | 客户端动作包总入口，内部 60+ 分支靠 `index`/`tab`/客户端状态；外部调用等于伪造客户端 |
| `openInput(...)` / `sendSnapshot(...)` / `sendSnapshotOpen(...)` / `broadcastSnapshots(...)` | 纯网络协议；外部调用会打断玩家界面（历史 bug：会自动抢界面） |
| `setAdminEditType`、`GrantManager`（容器菜单）、`openShippingBin` | 依赖容器/界面会话 |
| `TradeManager.*`（除 `tick`/`onLogout`/`shutdown`） | 全部是"玩家点击流程"的步骤 |
| `ShopManager.refreshDaily`、`adminGive`、`adminSetBalance*` | 管理员/维护语义；`adminGive` 只在有 `ServerPlayer` 时可用 |

---

## 4. 现有机制细节（API 必须遵守的行为契约）

### 4.1 余额与上限
- 上限：`Config.maxBalance`（默认 `9999999999`），`maxBalanceLimit()` 里 **`<=1` 视为不限（`Long.MAX_VALUE`）**（L437）。
- `credit()` 用 `room = max - balance`、`add = min(amount, room)` 做饱和，**金额溢出安全**（`buyMall`、`sellBuyback` 也都用 `safeMultiply` 防溢出）。
- 溢出（装不下的钱）**不会丢弃**：转 `addMoneyMail` 金钱邮件。

### 4.2 物品投递与兜底
- 在线：`addItemOrMail` → `Inventory.add` 失败部分 → 邮件（`BAG_FULL` 模板）。
- 离线或邮箱优先：`addMail(server, uuid, …)`；**邮箱满且无已读可清 → 在线掉落脚下 / 离线仍强行保留**（不销毁）。
- 多物品一次投递：`addMailDraft` + `DraftMail.addItem/addItems` + 同渠道合并（一次发放只发一封）。

### 4.3 流水
- 每笔流水字段：`time / type / counterParty / amount / balanceAfter / note`，**每人最多保留 `transactionHistorySize`（默认 50）**，超出丢最旧。
- 类型常量集中在 `TransactionLog`（buy/sell/stall_*/auction_*/transfer_*/trade_*/mail_*/admin_*/sign_in/redeem/external…）。
- 已支持导出 CSV（`BillExporter`，`/shop bill`）。

### 4.4 权限
- 全项目 **25 处 `player.hasPermissions(2)`**（管理员）；命令侧用 `requires(source -> source.hasPermission(2))`。
- **没有"受信任模组"概念**：目前"谁调用都行"的只有 public 方法本身（等价于"内部 API"）。

### 4.5 并发 / 线程
- `ShopManager.LOCK`（`synchronized`，被 `GrantManager` 等复用）只做**进程内互斥**。
- **没有** `server.execute(...)` 之类的线程切换；现有 tick 入口（`ShopServerEvents.onServerTick`）保证在主线程。
- 结论：**API 必须在服务端主线程被调用**；若检测到非主线程，应拒绝或自行 `server.execute` 排队（这是设计选择，需确认）。

### 4.6 存档
- `ShopSavedData extends SavedData`，`FILE_ID = server_shop_mod_data`，挂在 overworld 的 `DimensionDataStorage`。
- **所有写入必须 `data.setDirty()`**（很多 API 需要帮调用方做这一步）。
- 老存档兼容靠「字段缺失取默认值」的 `load` 惯例（例：`StallEntry.load` 缺 `stock` 用物品数量回退）。

---

## 5. 风险清单（按严重度）

| # | 风险 | 触发方式 | 影响 | 建议 |
| --- | --- | --- | --- | --- |
| R1 | **绕过上限/流水** | 外部直接 `data(server).account(uuid).balance += x` | 刷币、账单缺失、与 GUI 显示不一致 | API 不暴露 `PlayerAccount`/`ShopSavedData`；只给 value object |
| R2 | **重复发奖** | 任务结算被重试 / 玩家重复点击 / 服务器回滚重放 | 白给奖励 | 引入**幂等键**（`requestId`）+ 可选去重表（见 §7.4） |
| R3 | **重复扣款 / 半成功** | 先扣钱后发物品，中途异常 | 钱没了物品没到（本轮刚修过求购同类 bug） | 统一"预留-提交-回滚"或"先交付后扣款"顺序；给结果对象 |
| R4 | **数据竞争** | 别的模组在异步线程调用 API | 存档损坏/并发修改异常 | API 入口做**主线程断言**（`server.isSameThread()`），否则拒绝并返回失败 |
| R5 | **权限绕过** | 外部模组直接调管理员方法（`adminSetBalance`、`adminGive`） | 等于给所有模组 GM 权限 | 分级：`Player`（需 `ServerPlayer` 且校验）／`System`（仅服务端主线程+显式可信标记）／`Admin` 不进 API |
| R6 | **界面被抢** | 外部调用 `sendSnapshot*` | 玩家正在看别的界面被弹走（历史 bug） | 不暴露任何 `sendSnapshot*` |
| R7 | **邮件结构被改** | 外部直接改 `MailEntry`/删邮件 | 玩家资产被吞 / 领取状态错乱 | 只暴露 `MailAPI.send*` 与只读 `MailInfo` |
| R8 | **兼容性** | 新增 API 时改动 `PlayerAccount`/`ShopSavedData` 字段或网络协议 | 老存档/老客户端失效 | API 层**不改存档结构与协议**；仅在必要时"追加字段 + 默认值" |
| R9 | **版本错配** | 外部模组依赖 ABI，商店升级后方法签名变 | `NoSuchMethodError` | 独立 `apiVersion`（语义化），接口保持向后兼容；破坏性变更走大版本 |
| R10 | **性能** | 高频查询（每 tick 查余额/商品）走存档 Map | 可接受，但事务性操作含 I/O（邮件/掉落） | 查询 API 允许高频；写操作提示"每 tick 调用请自行缓存" |

---

## 6. 现有"公共接口 / 事件 / 脚本集成"核实结果

| 检查项 | 结果 |
| --- | --- |
| `api` 包 / 对外接口 | **无**（包只有 admin/client/command/data/event/menu/network/shop/sound） |
| 对外 NeoForge 事件（`EVENT_BUS.post`） | **无**（只有自用 `@EventBusSubscriber` 监听 `ServerTick/PlayerLoggedIn/PlayerLoggedOut/RegisterCommands/ServerStopping`） |
| KubeJS / 脚本桥接 | **无**（源码零命中） |
| HTTP / REST | **无** |
| 命令接口（可被命令方块/RCON 调用） | 有：`/shop`、`/shop mailbox|stall|admin …`、`/shop bill` —— **这是当前唯一"外部可调用"的通道** |
| JEI 集成 | 有（`compileOnly` 软依赖，`JeiBridge`，仅客户端配方查询） |

---

## 7. 建议的 API 架构（阶段二草案，等确认后实施）

### 7.1 包结构（新增，与内部实现隔离）

```
cn.autoforged.server_shop_mod_1789689358.api            ← 公共 API（稳定）
├── ServerShopApi                // 门面：获取各子 API
├── ApiVersion                   // API_VERSION = "1.0.0"，METHODS_ABI 说明
├── EconomyApi                   // 查询 / 受校验增减 / 上限
├── ItemDeliveryApi              // 物品投递（在线/离线）
├── MailApi                      // 物品邮件 / 金额邮件 / 多附件邮件 / 只读查询
├── RewardApi                    // 任务/活动奖励的"一次性"发放（幂等）
├── DailyApi                     // 签到状态 / 活动查询（只读）+ 受规则发奖
├── ShopApi                      // 商品/库存/价格只读查询（执行类先不做）
├── HistoryApi                   // 流水查询 / 记录
├── ApiResult<T>                 // 统一结果：ok / code / message / value
├── ApiErrorCode                 // 枚举：UNKNOWN_PLAYER / NO_PERMISSION / LIMIT_EXCEEDED / WRONG_THREAD /
│                                //       DUPLICATE_REQUEST / INVALID_ARGUMENT / MAILBOX_FULL / INTERNAL_ERROR
└── view/                        // 只读视图（不可变）
    ├── BalanceView (balance, maxBalance, currencyName)
    ├── MailInfo (mailId, kind, title, hasItems, money, claimed, sentAt, expireAt)
    ├── SignInView (lastSignDay, streak, bestStreak, signedToday)
    ├── ShopItemView (itemId, displayName, price, stock, unlimited)
    └── TransactionInfo (time, type, counterParty, amount, balanceAfter, note)
```

**原则**：`api` 包**不 import** `data`/`shop` 内部类（除返回值 DTO 自身）；实现放在 `api.internal`（或 `shop` 包内的 `*ApiImpl`），只通过 `api` 暴露接口。

### 7.2 第一批接口清单（建议先只做这些）

| 接口 | 方法（草案） | 返回 | 备注 |
| --- | --- | --- | --- |
| `EconomyApi` | `long balance(UUID)` / `BalanceView balanceView(UUID)` | 数值/视图 | 只读；离线可查 |
| | `long maxBalance()` | 上限（不限返回 0） | 与 `maxBalanceForClient()` 一致 |
| | `ApiResult<Long> add(UUID target, long amount, String reason)` | 实际入账额 | 受上限约束；写流水；`setDirty` |
| | `ApiResult<Long> take(UUID target, long amount, String reason)` | 实际扣除额 | **余额不足直接失败**（不扣成 0） |
| | `ApiResult<Void> setBalance(UUID target, long amount, String reason)` | — | 仅"系统级"（见 §7.3 权限分级）；建议默认不开放 |
| `ItemDeliveryApi` | `ApiResult<Void> give(UUID target, ItemStack stack)` | — | 在线进背包/满进邮箱；离线进邮箱 |
| | `ApiResult<Void> giveAll(UUID target, List<ItemStack>)` | — | 一次投递，合并成一封邮件 |
| `MailApi` | `ApiResult<String> sendItemMail(UUID target, String title, String body, List<ItemStack> items)` | mailId | 走 `addMailDraft` |
| | `ApiResult<String> sendMoneyMail(UUID target, long amount, String title, String body)` | mailId | 走 `addMoneyMail` |
| | `List<MailInfo> mailbox(UUID target)` / `int unclaimed(UUID target)` | 只读 | 不返回可变 `MailEntry` |
| `HistoryApi` | `List<TransactionInfo> history(UUID target, int limit)` | 只读 | 倒序 |
| | `ApiResult<Void> record(UUID target, String type, String counterParty, long amount, String note)` | — | 只写流水**不动余额**（给"外部经济"记账用） |
| `DailyApi` | `SignInView signIn(UUID)` | 只读 | |
| | `boolean canSignToday(UUID)` | 只读 | |
| `RewardApi` | `ApiResult<Void> grantOnce(UUID target, String requestId, List<ItemStack> items, long money, String reason)` | — | **幂等**：同 `requestId`+`target` 只发一次 |

### 7.3 权限分级（核心设计）

| 级别 | 谁能用 | 允许的操作 |
| --- | --- | --- |
| `PLAYER` | 拿到某个 `ServerPlayer` 的调用方（等于"该玩家的行为"） | 只读查询；扣自己的钱（受上限/余额校验） |
| `SYSTEM` | **任意模组**，但必须：①服务端主线程 ②显式传入 `requestId`（写操作） ③目标必须真实存在 | 给奖励、发邮件、记流水、加钱（受上限） |
| `ADMIN` | **不提供**（保留给 `/shop` 命令与内部） | 设置余额、强制移除邮件、改商品池 |

> 理由：模组之间没有可靠"身份"，所以**不做**"你是模组所以给你管理员"。`SYSTEM` 只开放"发奖励/记账"这类不会破坏规则的能力，且全部受上限、幂等、主线程约束。

### 7.4 幂等与事务

- **写操作必须带 `requestId`**（`RewardApi.grantOnce` 强制），实现用 `Map<UUID, Set<String>>`（或存档内 `claimedOnce` 集合，注意体积上限；建议 LRU+持久化 2000 条）。
- 事务顺序统一为 **"先校验 → 扣/预留 → 交付 → 记账 → setDirty"**；任何一步失败 → 回滚已扣部分（本轮求购 bug 的教训）。
- 结果对象明确：`ApiResult` 携带 `ApiErrorCode`，**禁止静默返回 void**。

### 7.5 版本与兼容

- 模组版本（`1.0.0`）与 API 版本（`ApiVersion.API_VERSION = "1.0.0"`）**独立**；
- 只增不改：新方法追加，旧方法保留 `@Deprecated` 至少一个大版本；
- API 层**不触碰存档结构与网络协议**（快照/动作包保持原样）；
- 模块元数据可追加 `provides`/`custom` 段声明 API 版本（不改现有字段语义）。

### 7.6 事件 API（第四批再决定）

建议**只做"已成功之后"的通知事件**（不可取消）：`BalanceChangedEvent`、`PurchaseCompletedEvent`、`MailCreatedEvent`、`RewardGrantedEvent`、`SignInCompletedEvent`。
- 用独立 `IEventBus`（`ServerShopApi.EVENT_BUS`）或 `NeoForge.EVENT_BUS`（需确认），监听器异常一律 `try/catch` 包裹后记录日志，**不得影响已完成的事务**；
- **不提供可取消事件**（避免回滚语义复杂化），如确需"可否决"另开明确设计。

---

## 8. 三种集成形式评估

| 形式 | 结论 | 理由 / 前置条件 |
| --- | --- | --- |
| **A. Java 公共 API** | ✅ **先做** | 零新依赖；`compileOnly` 依赖本模组即可；控制粒度最好 |
| **B. KubeJS 桥接** | ⏸ **暂缓** | 需先确认目标 KubeJS 版本（1.21.1 为 KubeJS 2101.x，事件 API 为 `ServerEvents`/`StartupEvents`）+ 是否要独立兼容模块；建议在 Java API 稳定后，用一个**独立可选模块**（`compileOnly` KubeJS）实现，不打进主 mod |
| **C. HTTP / REST** | ⏸ **暂缓（默认不做）** | 需要独立鉴权、监听范围（默认 127.0.0.1）、限速、审计、防重放；**禁止默认公网暴露经济写操作**。如要做，建议独立子项目 + 明确开关 |

---

## 9. 测试与验收计划（阶段四用，先列）

| 分类 | 用例 |
| --- | --- |
| 在线/离线 | 在线投递、离线邮件、离线后上线领取 |
| 余额边界 | 0 余额扣款失败、接近上限加钱（部分入账+金钱邮件）、`Long.MAX_VALUE` 饱和、负数参数拒绝、极大数值溢出 |
| 背包容量 | 有空槽 / 只能堆叠 / 完全装满（转邮件） |
| 邮箱 | 正常 / 已满（有已读可清）/ 已满且无已读（在线掉落、离线保留） |
| 参数与权限 | 无效 UUID、空物品、负数量、非主线程调用、超越级别的操作 |
| 幂等 | 同一 `requestId` 连续 2 次、服务端崩溃重启后再调 |
| 持久化 | 调用后重启服务端，余额/邮件/流水仍在；`setDirty` 生效 |
| 失败一致性 | 故意让交付失败（背包+邮箱都满），确认**没有扣款** |
| 回归 | 商店买/卖、摊位、拍卖、交易、签到、邮箱、管理员发放、求购交付、出货箱结算 |

---

## 10. 待你确认的问题（影响 API 形态）

1. **`RewardApi` 的幂等键**：由调用方提供 `requestId`（我推荐），还是由商店侧根据"玩家+任务ID"自动生成？
2. **`SYSTEM` 级别是否允许"加钱"**？我倾向允许（受上限+流水），但如果你希望"钱只能由商店自己发"，我就只开放"物品+邮件+流水"。
3. **`EconomyApi.take` 语义**：余额不足时**整笔失败**（我推荐），还是"扣到 0 为止"？
4. **是否要 `setBalance` 进公共 API**？我建议**不进**（保留给命令/管理员）。
5. **事件总线**：独立 `IEventBus`（干净、外部需显式注册）还是 `NeoForge.EVENT_BUS`（省事、与其他模组风格一致）？
6. **KubeJS**：现在就要，还是先只做 Java API？（做的话我按 2101.x 的 `ServerEvents` 写独立兼容模块）
7. **API 版本号**：`1.0.0` 起步可以吗？以及是否要"API 独立 jar"（我现在倾向同一个 jar 里带 `api` 包，外部 `compileOnly` 即可）。

---

## 11. 第一批实施内容（确认后我会做）

1. 新增 `api` 包：`ApiVersion`、`ApiResult`、`ApiErrorCode`、`ServerShopApi` 门面 + `view/*` 只读视图；
2. `EconomyApi`（balance / maxBalance / add / take）+ `HistoryApi`（history / record）；
3. `ItemDeliveryApi`（give / giveAll）+ `MailApi`（sendItemMail / sendMoneyMail / mailbox / unclaimed）；
4. 内部实现类 `ApiImpl`（只调用现有 `credit`/`addItemOrMail`/`addMailDraft`/`TransactionLog.record`，**不复制规则**）；
5. 主线程断言 + 幂等键基础设施（先只给 `RewardApi` 用，第二批落地）；
6. 单元/集成测试（用测试服务器 + RCON + 存档检查，沿用本项目现有验证手法）；
7. 示例模组（`examples/` 下的最小 NeoForge 工程，`compileOnly` 依赖本模组）。

> 第 2–7 项都要等你确认上面 7 个问题后才动；本轮**未修改任何源码**，只新增了本审计文档。
