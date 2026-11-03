# 变更记录

遵循 [语义化版本](https://semver.org/lang/zh-CN/)。模组版本与 API 版本独立维护（当前 API `1.0.0`）。

## [1.1.1] · 1.20.1 / Forge 分支

### 修复

* **客户端进游戏即崩溃**：`ClientEvents.OPEN_SHOP` 为 `null` 时按 tick 访问导致
  `NullPointerException: Cannot invoke "KeyMapping.m_90859_()"`。
  根因是 Forge 1.20.1 的 `@Mod.EventBusSubscriber` **默认订阅游戏总线**，而
  `RegisterKeyMappingsEvent` / `FMLClientSetupEvent` 是 **MOD 总线**事件 —— 按键注册与容器界面注册
  两件事都没执行到。现在拆出 `ClientModEvents`（显式 `bus = MOD`）专门注册按键与 6 个容器界面，
  并给按键加了懒创建兜底，任何加载顺序都不会再崩。
* **容器界面（发放物品 / 添加条目 / 放入物品 / 出货箱 / 兑换码 / 日程奖励）无法打开**：同一个根因，
  界面注册没跑到；已随上面一起修好。
## [1.1.0] · 1.20.1 / Forge 分支

面向 **Minecraft 1.20.1 + Forge 47.4.9 + Java 17** 的移植版本，功能与 1.0.2 对齐。

### 与 1.21.1 版本的差异（纯平台适配，无玩法改动）

* 加载器 NeoForge → **Forge 47.x**：包名 `net.neoforged.*` → `net.minecraftforge.*`，
  `DeferredHolder` → `RegistryObject`，`ModConfigSpec` → `ForgeConfigSpec`，
  mod 主类改为**无参构造**（构造注入是 1.20.2+ 的特性）。
* 网络层用 **`SimpleChannel`** 重写（协议版本 `2`），保留原来的「gzip 压缩 + 512 KiB 分片」传输，
  大快照与整包导入不会撞上原版 2 MiB NBT 上限。
* 1.20.1 物品序列化仍是 NBT：`ItemStack.save(CompoundTag)` / `ItemStack.of(tag)`，
  同物品判定用 `isSameItemSameTags`（1.21 是数据组件）。
* 皮肤绘制用 `getSkinTextureLocation()`（`PlayerSkin` 是 1.20.2+ 才有的类型）。
* 容器界面在 `FMLClientSetupEvent` 里用 `MenuScreens.register` 注册
  （Forge 1.20.1 没有 `RegisterMenuScreensEvent`）。
* 滚轮事件为 3 参、`Screen#renderBackground(GuiGraphics)` 单参。
* **KubeJS 桥接**改为可选源集：`-PkubejsJar=<kubejs-forge jar>` 才编译，
  API 适配 KubeJS 2001（`KubeJSPlugin` 是 class 要 `extends`、`registerBindings(BindingsEvent)`）。

### 验证状态

* 编译 0 错误；`runServer` 启动 `Done (6.144s)`、模组 0 报错；
* `runClient` 正常进游戏，6 个容器界面注册无误；
* 待人工点验：12 个模块界面、网络分片联调、KubeJS 运行期验证。

## [1.0.2]

### 修复

* **普通玩家无法上架物品 / 拍卖上架**：`openDeposit(...)` 上被误加了一条「仅管理员」校验，
  导致摊位「上架物品」、拍卖「上架」、交易「放入物品」对普通玩家静默无响应（点了没反应）。
  现在只保留空值保护，玩家流程恢复正常；`admin_add` 等真正的管理操作仍各自校验权限。
## [1.0.1]

### 新功能

* **签到 / 交易 / 转账 变成正式模块**：侧栏最上方三个入口，页内提供「打开界面」按钮；签到设置（奖励、补签、累计档位、里程、累计加成、自动签到）全部收进「商店设置 → 选择模块 → 签到」。
* **改名系统**：编辑布局模式下可右键改名——左上角**商店主标题**、侧栏**模块名**、以及**货币名称**（右键余额，全服生效，留空恢复默认）。
* **货币名全局替换**：界面与聊天里写死的「星币」会自动跟随管理员设置的货币名（`CurrencyNames` 客户端包装层）。
* **通知红点**：每日商店 / 每日收购分别计数，不再互相点亮。
* **签到里程分轮**：每 7 天一轮，新一轮可重新领取 3/5/7 天里程奖励，断签重建也能再领。
* **购物城**：条目支持独立配置（见 `MallItem` 扩展）。

### 修复

* 余额悬停提示：去掉 `\n` 拼接造成的乱码，现在**只显示「点击查看账单明细」**（编辑布局时多一行右键提示）。
* 商店设置页与标题的右键改名此前不生效（条件写错），现在可用。
* 侧栏新增模块被追加到滚动区外、看起来「没加上」的问题。

### 工程

* 新增 `tools/check-ui.ps1`（模块/render/语言键/菜单界面一致性自检）与 `tools/deploy.ps1`（**游戏运行时不覆盖客户端 jar**，并自检四处哈希）。
## [1.0.0]

### 新增 · 公共 API（`cn.autoforged.module_shop.api`）

* `ModuleShopApi` 门面：`economy()` / `items()` / `mail()` / `history()` / `daily()` / `reward()` / `shop()` / `events()`
* 统一结果 `ApiResult` + 错误码 `ApiErrorCode`（不靠文案判断成败）
* 只读视图：`BalanceView` / `TransactionInfo` / `MailInfo` / `SignInView` / `ShopItemView` / `AuctionView` / `ActivityView`
* **幂等发奖** `RewardApi.grantOnce`：`requestId` 落存档，**重启后仍防重复**
* 事件 API（业务成功后通知、不可取消）：余额变更 / 邮件创建 / 奖励发放 / 签到完成 / 购买成交 / 出售成交 / 拍卖出价 / 求购发布 / 摊位上架
* 受校验的执行接口：`buyDaily` / `buyMall` / `sellBuyback` / `bid` / `createDemand` / `cancelDemand` / `stallList`（价格复核、限购、库存、在线、余额逐项校验，失败零副作用）
* **统一扣款入口** `ShopManager.debit(...)`：商店 14 处内联扣款改为走它，扣款也会发 `BalanceChangedEvent`

### 新增 · 集成

* **KubeJS 桥接**（可选构建）：脚本里直接用全局 `ModuleShop`（`-PkubejsJar=<jar>` 构建）
* **HTTP / REST**（可选、**默认关闭**）：9 个端点；Bearer token、默认只听 `127.0.0.1`、默认只读、每 IP 限速、审计日志、写操作强制 `requestId` 防重放

### 修复

* 求购交付：物品**没有投递给发布者**（钱扣了、东西消失）—— 现在在线入背包、离线进带附件的邮件
* 求购交付扣物品**非原子**：任一条失败会把已扣物品全部退回
* 商店界面「关不掉」：零点刷新/邮件推送会用快照把界面弹回来 —— 现在只有 `/shop` 这类主动请求才弹界面
* 求购「想要 %S ×%S」乱码、求购列表字号偏大、求购选物界面图标重叠（改 18px 行距 + 滚轮 + 裁剪）
* 出货箱重开吞物品、购物城二次购买失效、侧栏滚动、模块重命名等（详见 `docs/第八轮优化说明.md`）

### 工具

* `/shop bill <玩家|all> [起日] [止日]`：把交易流水导出成 CSV（含**玩家间对账**表，UTF-8 BOM，Excel 直接打开）

## [1.14.0] 及更早

见 `docs/更新日志-1.14到1.15.md` 与各轮 `第 N 轮需求实现说明.md`。