# 变更记录

遵循 [语义化版本](https://semver.org/lang/zh-CN/)。模组版本与 API 版本独立维护（当前 API `1.3.0`）。

## [1.6.0]

构建产物：`Module-Shop-1.6.0-for-NeoForge-1.21.1.jar` 与 `module_shop-1.6.0-api.jar`；旧存档 / 配置直接兼容，保留历史全部版本 jar。

### 新功能与修复

- **附属页面键盘与热键隔离支持（100% 二进制向后兼容）**：`AddonPage`、`ShopPage`、`AddonPageAdapter` 新增带默认实现的 `keyPressed`、`keyReleased`、`charTyped` 与 `isInputFocused()`；`ShopScreen` 在 `EditBox` 聚焦或 `activePage().isInputFocused()` 时阻止 `1~9` 切页热键穿透，并完整转发键盘与字符输入事件给活跃页面。
- **购物城单品「每人永久限购（不随刷新补货）」**：`MallItem`、`ConfiguredOffer`、`ShopTemplate.MallEntry` 新增 `permanentLimit` 标记与持久化；永久限购商品按玩家个人独立扣减限购额度，`ShopManager.restockMall`（含定时补货与周期刷新）及全服/个人库存模式切换均跳过永久限购商品的库存重置与已购记录清理，并在管理后台、玩家列表与卡片视图中直观展示。
- **货币兑换可配置限额与服务端拦截**：
  - `ExchangeEntry`、`ExchangePage` 与 `ShopManager` 新增单条目每日存入（物品→星币）及每日取出（星币→物品）上限配置、`PlayerAccount` 按日持久化已用额度及服务端超限拦截。
  - `PhysicalCurrencyConfig`、`PhysicalCurrencyConfigScreen`、`PhysicalCurrencyExchangeScreen` 与 `PhysicalCurrencyEngine` 支持管理员可视化配置单次阶梯兑换产出上限（替代硬编码 2304）与每人每日阶梯兑换面值上限，并在服务端严格拦截超限兑换。
- **出货箱动态支持 4~5+ 级与每日额度**：新增 `Config.shippingLevels(ShopSavedData)` 联动 `shippingLevelSlots` 与 `shippingUpgradeSpecs` 长度；`SystemCostsAdminScreen` 保存 4~5+ 级出货箱升级费用或 `adminShippingLimits` 保存多级额度时自动扩展配置槽位（按每级 +9 格至 54 格上限）与每日额度列表；解除 `adminShippingLimits` 8 级及语言表 4 级限制，`ShopInputScreen` 支持任意等级 `limitN` 的中文回退标签与滚动编辑。

## [1.5.1]

构建产物：`Module-Shop-1.5.1-for-NeoForge-1.21.1.jar` 与 `module_shop-1.5.1-api.jar`；PATCH 修复，旧存档 / 配置直接兼容，保留历史全部版本 jar。

### 修复

- **实体货币 + 星币并行时无法用星币发布求购**：「发布求购」界面（`DemandCreateScreen`）把点击转发给悬赏编辑器 `PriceSpecEditor` 后，又调用了 `setFocused(null)`，把刚交给「星币价格」输入框的 Screen 焦点立刻清空（连带 `EditBox.setFocused(false)`），键盘 / 字符事件再也送不进输入框，星币悬赏永远停在默认 `0`；而实体币数量默认就是 `1`，所以看起来「只能用实体货币求购」。现已移除该清空，只让搜索框 / 数量框失焦，星币框可正常点击、退格、输入。
- `PriceSpecEditor`：再次点击已选中的账本按钮（如默认的「仅星币」）时，直接把焦点交给对应输入框，便于立刻修改金额。
- 后端 `demandCreate` / `demandFulfill` / `demandCancel` 复核：`STAR_ONLY` 悬赏在并行模式下按星币账本校验余额、托管（含手续费）、交付打款、撤单退款，全链路无需改动；新增单测 `UpgradeRegressionTest.parallelCurrencyStarOnlyDemandEscrowUsesStarLedgerOnly` 与根因回归守卫 `demandCreateScreenKeepsPriceEditorFieldFocusAfterClick`。

## [1.5.0]

构建产物：`Module-Shop-1.5.0-for-NeoForge-1.21.1.jar` 与 `module_shop-1.5.0-api.jar`；旧存档与旧配置可直接兼容升级，保留历史全部版本 jar。

### 新功能与安全加固

* **#1 商店自定义左上角品牌图标与标题持久化**：支持上传 PNG/JPG/WebP 作为左上角商店品牌图标（`BrandSettingsScreen` + `ShopMediaStore` 纯 Java SHA-256 媒体落盘，专服零 AWT/ImageIO/Skija 依赖）或恢复默认首字方块，同步修复商店名称与图标 ID 在 `ShopSavedData`、`admin_layout_commit`、`brand_default.json` 及配置模板导出中的全链路持久化，换包重启不重置。
* **#4 公告图片上传与图文混排**：`AnnouncementEntry` 升级 `AnnouncementBlock`（文本/图片段落混合编排、宽度比例、对齐与图注，完全向下兼容旧版 `content` 字段），支持管理端本地选图/拖入传图（`ClientImageUploader` + `ClientMediaCache` 按需分块拉取缓存）、右侧实时滚动预览、`AnnouncementBlockRenderer` 原生展示与灯箱放大预览（`LightboxScreen`），并保持非 Skija 回退安全。
* **#5 整合包与专服管理员密码锁（`AdminAuthService`）**：默认关闭，开启后采用加盐 `PBKDF2WithHmacSHA256`（120,000 次迭代、`MessageDigest.isEqual` 常量时间比对、5 次错密递进冷却、不落盘明文且不向客户端同步哈希）与 `OP ∧ 内存解锁会话` 门禁（`AdminAuthScreen` / `AdminSecurityScreen`），全面收口 UI、模板、拍卖、发放、出货箱与玩家聊天指令写入口，同时保留服务端控制台/指令方块/FTB Quests/KubeJS 免密执行及专服控制台 `/shop auth disable` 防死锁口令，支持整合包模板安全携带哈希。
* **#6 大容量 `.mshop` / `.json.gz` 模板分片传输与导入导出**：新增携带嵌入式媒体（`assets/<sha256>`）的 `.mshop` 容器包及 Gzip 压缩 `.json.gz` 格式（兼容 UTF-8 BOM `.json`），`TemplateTransferManager` 与 `ClientTemplateIO` 全面切换至分块二进制上传/下载/预览协议（`ConfigExportScreen` 支持分模块勾选导出），彻底消除原版 NBT 字符串大小上限限制。
* **#7 出货箱单日限额配置与今日已用联动**：`ShippingRules`、`ShopManager`、`ShippingBinScreen` 与 `ShippingPage` 支持在新增/批量新增/编辑规则时配置单日出货份数上限（留空或 `0` 表示不限），并在前台联动展示与扣减「今日已出货 / 单日上限」余量。
* **#8 TACZ / 饰品 `DataComponents` 全链路完整保留**：`ShopTemplate`（`FORMAT_VERSION = 3`）及 `TemplateService`、`ShopManager.adminMallImport` 在跨档保存、模板导出/导入及服务端判重中完整序列化与比对物品注册表感知的 `DataComponents` SNBT 与 `ItemStack.isSameItemSameComponents`，确保同物品 ID 不同枪械/饰品独立共存无损迁移。

### 修复与体验升级

* **#2 修复布局隐藏「出货箱」重登/重启后自动恢复 bug**：`SidebarLayout` 新增 `hidden` 列表区分「管理员刻意隐藏」与「版本升级新增模块」，删除 `shipping` 并提交后重登及重启服务端均不会再次自动补回。
* **#3 根治污染 `AtomChat` 全局皮肤问题**：`AtomUi` 彻底移除全局 `Design.host(...)` 注入，避免与 `AtomChat` 等同用 `AtomLib` 的模组相互覆盖界面皮肤。
* **#9 全模组可鼠标拖动滚动条与点轨直达**：升级 `ScrollController`（`THUMB_MIN = 18.0F`、扩展命中热区、可滚常驻浅色可读、`pressBar` 按点击比例直达并立即支持续拖）及 `ListTable` / `CardGrid` 右侧安全槽，全面接通 `AdminPage`、`ShellV2` 侧栏、`ShopScreen` 布局侧栏、`MallPage`/`AuctionPage`/`StallPage` 左侧分类、`MailPage` 正文、`AnnouncementAdminScreen` 预览区、`ShippingBinScreen` 主清单/分类下拉、`AuctionCreateScreen` 与 `SkiaDialog` 全弹窗拖拽/释放闭环。
* **#10 修复浮点截断与窄屏挤压**：修复 `PriceDisplay`（单段富余直接按整列宽绘制 + 多段 `0.5F` 浮点容差）与 `Text.line` 导致的 `1,200 → 1,2…` 误截断，升级 `V2.columns` 与各页签/弹窗在 `85% ~ 135%` 字号缩放及窄屏下的自适应列宽保护与换行排版。
* **#11 管理后台及全界面多词可清空搜索**：新增 `SkiaTextField.clearable(true)`（右侧 `×` 一键清空 + 聚焦按 `ESC` 先清空再退屏）与 `PageKit.matchesQuery` / `matchesItemQuery`（支持中文译名、`modid:path`、下划线路径与模组名多词 AND 检索），接入 `AdminPage`（带原始 `origIndex` 安全映射）、所有 `ShopPage` 页签及次级弹窗（`ExchangePage` / `StallPage` / `AnnouncementAdminScreen` 均严格保留过滤前原始下标）。
* **#12 购物城 / 拍卖行 / 摊位左侧分类搜索智能收敛与独立滚动条**：启用搜索或附加筛选时自动收敛隐藏 `0` 命中分类（保留「全部」并在原选分类归零时平滑回退），同时为左侧分类栏接入独立裁剪区域与可拖动滚动条，避免高模组列表截断。

## [1.4.0]

构建产物：`Module-Shop-1.4.0-for-NeoForge-1.21.1.jar` 与 `module_shop-1.4.0-api.jar`；旧存档与旧配置可直接兼容升级，保留历史全部版本 jar。

### 新功能与重构

* **双独立并行账本（`PriceSpec` 四模式定价与结算）**：星币（`STAR_ONLY`）与实体货币（`PHYS_ONLY`）彻底分离为两套独立账本，支持 `EITHER`（二选一双通道，支持前端切换默认快捷支付偏好）与 `BOTH`（组合同付），绝不跨账本混扣折抵；实体货币链内仍完整支持多阶梯兑换与破整找零。
* **全场景配价与直显升级**：
  * **通用UI控件与排版**：新增 `PriceDisplay`（基于 `ItemOverlay` 小图标 + 文本紧凑渲染，支持四模式与自适应缩放）与 `PriceSpecEditor`（四模式可视化配价组件）。
  * **顶栏与个人主页双账本并列直显**：`ShellV2` 顶栏与左下角资料卡、`ProfileHomePage` 同步并列展示星币余额与背包各阶实体货币持有图标及数量（点击直达实体货币阶梯兑换台）；`ExchangePage` 保持基础版星币物品双向兑换，并明确与实体货币账本独立提示。
  * **全模块接入双账本**：每日商店、每日收购、购物城、出货箱（含升级费用与结算产出）、玩家摊位（上架与大厅选购）、拍卖行（币种选择、出价托管与按档位结算手续费）、求购广场（星币/实体币/物品悬赏托管与交货结算）、每日签到与连签里程碑、活动日奖励配置、兑换码奖励草稿与发放，以及新增系统定额费用配置界面 `SystemCostsAdminScreen`（动态覆盖每日商店/收购刷新阶梯费、出货箱升级费并支持恢复 Config 默认值）。
* **客户端兼容性修复**：修复装有 `AtomChat` 时聊天栏输入 `/shop admin` 因非聊天/停顿界面判定导致未能正常顶开管理界面的问题（`ClientPayloadHandler.canOpenOverCurrentScreen`）。

## [1.3.1]

构建产物：`Module-Shop-1.3.1-for-NeoForge-1.21.1.jar` 与 `module_shop-1.3.1-api.jar`；保留历史全部版本 jar。

### 修复与架构收口

* **物理端隔离解耦（出货箱实体货币客户端 hook）**：`CurrencySettlement` 新增纯回调注入接口 `setClientBlockedHook(Predicate<ItemStack>)`，保持服务端核心包零 `client.*` 类引用；客户端由 `ClientEvents` 在客户端初始化时注册 `ClientHomeCurrencyState::isBlockingPhysicalShippingItem`，兼顾专用服物理端安全与联机客户端出货箱槽位精准拦截预判。

## [1.3.0]

构建产物：`Module-Shop-1.3.0-for-NeoForge-1.21.1.jar` 与 `module_shop-1.3.0-api.jar`；旧存档与旧配置可直接升级（实体货币默认保持关闭）。

### 新功能与体验优化

* **服务器个人主页（默认首页）**：打开商店默认展示「个人主页」，左侧保留主导航栏并支持 1.21.1 原生 3D 玩家模型（`InventoryScreen.renderEntityInInventoryFollowsMouse`）跟随鼠标转头、显示玩家 ID、LuckPerms 称号徽章、累计在线时长、星币核心资产、实体货币折算与账单入口；右侧支持服务器公告置顶轮播（悬停暂停、管理后台 `AnnouncementAdminScreen` 热编辑保存 `announcements.json`）及快捷签到按钮（签到后原地显示连签天数）。
* **可自定义分层实体货币系统（`physical_currency.json`）**：默认关闭，支持管理员在 `PhysicalCurrencyConfigScreen` 可视化配置最多 16 级升阶兑换链与自定义组件（支持背包拾取保留组件、热更新同步）及三种结算模式（`HYBRID_PHYSICAL_FIRST`、`HYBRID_VIRTUAL_FIRST`、`PHYSICAL_ONLY`）；接入每日商店/收购、购物城、摊位、拍卖、求购、出货箱与面对面交易的破整找零、背包满邮箱兜底、拍卖/求购实体托管等价退还及防套利保护，并提供玩家快捷凑整/拆零兑换窗口 `PhysicalCurrencyExchangeScreen`。
* **界面全局字号缩放与自适应布局**：新增 `UIFontScale`（`85% ~ 135%` 步进调整并持久化）与右上角顶栏调节菜单，同步优化 `ShellV2`、`AdminPage`、`ProfileHomePage` 与二级弹窗在窄视口及不同字号档位下的自适应排布。

## [1.2.0]

公共 API 仍为 `1.2.0`，旧存档、旧版 `exports/*.json` 与 `shop_config.json` 可直接兼容升级。

### 配置管理重做与体验优化

* **统一 AtomLib UI（含非 Skia 兜底）**：重构「配置管理」界面（`ConfigManagerScreen`）、「导入预览」二级确认界面（`TemplatePreviewScreen`），并新增「导出配置命名弹窗」（`ConfigExportScreen`）；统一仅保留「导入 / 导出 / 返回」三个按钮（移除列表删除项），点击「返回」或按 ESC 逐级回到上一层商店界面。
* **修复二级确认弹窗不弹出 bug**：修正 `ClientTemplateIO.showPreview` 界面类型判断失配问题，并解除 `Nav.DISMISSED` 冷却拦截，确保每次点击列表配置都能稳定弹出「替换导入 / 合并导入 / 取消」确认框。
* **扁平化目录与中文导出命名**：导出配置与默认模板统一存放在扁平目录 `config/moduleshop/`（同时兼容扫描旧版 `exports/*.json`，自动跳过 `backups/` 与系统内置非模板 JSON）。导出弹窗支持中文名与实时文件名预览：留空保存为 `商店配置_<时间戳>.json`；输入 `shop_config` 直接写为默认模板 `shop_config.json` 并自动备份旧文件。
* **单机客户端落盘 vs 联机专用服直存同步**：单人游戏下导出直接写入客户端 `config/moduleshop/` 并原地刷新；联机专用服下导出直接保存在服务端配置目录，列表同时融合展示【服务端】与【本地】配置，管理员上传并确认导入本地配置时会同步保存到服务端目录。
* **一键导入打开文件夹 + 自动刷新**：点击「导入」按钮自动创建并打开本地配置文件夹，并每 20 刻轮询文件指纹变动自动刷新列表。

## [1.1.1]

公共 API 仍为 `1.2.0`，旧存档与旧配置可直接升级。

### 新功能

* **列表 / 方块视图切换**：主界面右上角（换肤按钮左侧）新增视图切换按钮，支持一键切换「列表视图」与「方块卡片视图」，偏好保存在客户端 `config/module_shop_ui_view.txt`。
* **统一紧凑方块卡片网格（`CardGrid`）**：为每日商店、每日收购、购物城、拍卖行、玩家摊位（大厅与我的商店）和求购广场 6 个前台交互页提供方块卡片模式，统一使用高密度小方格卡片规范（居中物品图标 + 上下角标状态/限购 + 名称与价格 + 底部细进度条），并完整保留原有列表视图逻辑、筛选分类、排序栏、快捷键操作与管理员界面不变。

## [1.0.6]

公共 API 仍为 `1.2.0`，旧存档与旧配置可直接升级。

### 新功能

* 商店配置导出 / 导入 / 跨世界共享（上游 PR #11）：商品池、收购池、购物城货架、兑换规则、刷新权重与签到参数可导出为模板文件，
  在其他世界导入（服务端执行，需要 OP 权限）；新世界首次启动时可自动套用默认模板。详见 `docs/商店配置共享-设计与分析.md`。

### 前置库兼容

* 编译改用 AtomLib 0.4.0；运行期依赖范围仍为 `[0.1.0-beta,)`，0.1.0-beta 与 0.4.0 都能正常使用。
* AtomLib 以后更新若改掉了本模组用到的接口（运行中抛出 `LinkageError`），不再崩溃：本次游戏自动关闭 AtomLib 绘制、
  关掉当前界面并提示，重新打开后使用旧版界面。

### 修复

* 拍卖大厅顶栏在窄窗口下超出内容区；拍卖详情和上架界面的输入框、操作按钮宽度不再越过面板。

## [1.0.5]

本版本合并了「拍卖行升级」「玩家商店升级」「出货箱不出货」修复与「容器界面底板」修复。公共 API 仍为 `1.2.0`，旧存档与旧配置可直接升级。

### 界面修复

* 装了 ModernUI 等界面美化模组时，出货箱 / 物品发放 / 存入等容器界面的面板被一层暗色背景盖住、整块发灰：
  容器界面的底板改在 `ContainerScreenEvent.Render.Background`（最低优先级）里绘制。
* 附属模组的 AtomLib 界面在玩家还没打开过商店时弹出（例如红包详情）可能因 AtomLib 尚未初始化而崩溃：现在首次使用时自动初始化。

### 出货箱修复

* **倒计时结束后不出货**：原来只在 06:00 后 180 刻的窗口内结算，`/time add`、`/time set noon` 或服务器卡顿会整天错过；现在到点后的第一次 tick 就结算（每天仍只结算一次）。
* 游戏时间被调回（`/time set day`、`/time set 0` 等）后，箱子记录的上次结算日比今天大，会被一直当成「已结算」；现在检测到时间倒退会重置。
* 单人游戏切换存档时，上一个存档的「今天已结算」标记不会清空，同一游戏日的新存档整天不出货；现在服务器启动时重置。
* 额度 / 规则当日份数用完时会提示原因（物品仍留在箱子里），不再一声不吭。

### 玩家商店升级

#### 新功能

* 摊位页顶部分为「我的商店 / 商店大厅」两个视图，并保留原有的「查看摊位」入口。
* **商店大厅**：汇总全服摊位上有货的商品，可搜索、分类、按最新 / 价格排序、分页；点商品直接进入卖家摊位并预选这件商品。
* **摊位详情**：顶部显示店名、店主、营业状态和商品数；商品以物品格子卡片展示并分页；右侧是商品详情、购买数量（− / 输入 / + / 最大）、总价实时计算和购买按钮。
* **我的商店**：从背包选物品上架、补货（只收完全相同的物品）、改价（售罄的也能改）、下架；交易记录可只看摊位买卖。
* 新配置 `stallMaxStock`（单件库存上限，默认 64，原来写死为 64）。

#### 安全

* 购买、改价、补货、下架改为按商品 id 定位。原来按下标定位，卖家下架其它商品时可能导致**买错商品、扣错价格**。
* 购买时核对买家看到的单价；数量超过库存直接拒绝，不再悄悄少卖；每次请求带请求号去重；改价和下架也放进全局锁。

#### 修复

* 摊位交易流水备注显示为 `translation{...}` 乱码。
* 卖家流水备注写着「货款存邮箱」，实际货款已直接进钱包。
* 补货会把身上穿着的同款盔甲一起扣走（合并时排查发现）：补货只从主背包和副手取物品。

### 拍卖行升级

#### 新功能

* **拍卖大厅**：搜索（名称 / 物品 ID / 模组 / 附魔 / 卖家，多关键词）、9 种分类、7 种排序、自适应分页（滚轮翻页），
  每行显示当前价 / 最低下次出价 / 一口价 / 竞拍人数 / 倒计时 / 领先者，状态色条区分「我的 / 我领先 / 我被超过」。
* **详情页**：完整物品属性、竞价记录、出价、一口价（二次确认）、撤销、管理员强制结束 / 撤销。
* **独立上架界面**：从背包直接选物品，设置数量、起拍价、加价幅度、一口价、时长、是否允许竞价，实时显示手续费。
* **我的拍卖**：出售中 / 我的竞拍 / 已成交 / 已流拍 / 已撤销，可重新上架（只带出参数，物品须重新从背包选择）。
* **一口价**、**防截拍延时**（次数上限 + 总时长 2 倍封顶）、**上架费**（固定 + 百分比）、起拍价范围、全服 / 个人数量上限。
* **物品限制**：黑名单（ID / #标签 / 模组:*，潜影盒与收纳袋里的物品同样检查）、绑定物品、特殊 NBT、NBT 体积上限、禁止创造模式上架。
* **通知**：上架、被出价（30 秒合并一次）、被超价、成交、落选、流拍、撤销、即将结束提醒；红点与邮件沿用现有系统。
* **管理**：大厅「管理」界面（全部配置 + 运行统计 + 异常记录），`/shop auction list|history|anomalies|audit|cancel|end`。

#### 安全与可靠性

* 出价资金托管（`escrow`）+ 启动时 / 命令审计；请求号防重复提交；`expect` 防按过时价格出价；结算幂等、重启续结算。
* 上架改为一次调用完成「校验 → 扣费 → 取物品 → 入库」，物品不再暂存在内存里。
* 损坏的拍品数据原样保留，单条坏数据隔离保存，不影响整个存档读取；自增 id 落后时自动修正。
* 旧出价界面不再读客户端本地配置的加价幅度，改用服务端下发的最低出价。

#### 修复

* **邮箱已满时，离线玩家收到的物品邮件会直接消失**，在线玩家的物品会掉在地上：现在改为超额存入邮箱（对所有模块生效）。

#### 性能

* 拍卖列表不再随每次商店快照全量下发：按需拉取（带版本号）+ 增量推送，拍品编码缓存。

#### 兼容

* 旧拍卖存档、旧配置键全部保留；`antiSnipeExtensionSeconds` 新默认 30（已有配置文件里的 0 不会被改动）。公共 API 未变。

## [1.0.4]

### 新功能

* **商店外壳改用 AtomLib 绘制**：面板、侧栏、玩家信息块、标题栏与页面一起用 Skija 画，几何与点击区域不变。
* **22 个次级界面迁移到 AtomLib**：发放物品、兑换码编辑、上架 / 交易 / 拍卖出价、通知设置等弹窗与容器界面统一新画法；
  AtomLib 不可用时仍回退旧版界面。
* **动效**：侧栏选中块弹簧滑动、换页淡入上滑、按钮按下回弹、弹窗缩放弹出、开关滑块拉伸、悬停移入快移出慢、柔和投影。
* **界面皮肤**：右上角按钮打开皮肤菜单，可选日间、夜间与柔彩系列（樱糖 / 薄荷 / 雾蓝 / 奶茶 / 夜樱）。
  皮肤带渐变与柔光，切换时整个界面约 0.4 秒平滑过渡；选择保存在客户端 `config/module_shop_ui_theme.txt`。
* 主面板与弹窗改为半透明，透出背后的模糊画面。
* 「编辑布局」改用新画法，跟随皮肤配色。
* **公共 API 1.1.0 → 1.2.0（只增不改）**：`ShopTheme` 新增皮肤相关取色（`skin` / `lightness` / 各组渐变端点 / 柔光）
  与绘制方法 `dialogPanel` / `accentFill` / `contentFill`，附属页面与弹窗可以和主体皮肤保持一致。

### 修复

* 商品「品质」选项里选中的那一项（默认「普通」）整块透明、看起来像少了一个选项。
* 切换模块时左下角头像、侧栏红点跟着页面一起上浮。
* 侧栏通知角标改为圆角胶囊，日间主题下不再是深色数字。
* 货币兑换的物品格顶出卡片边缘。
* 日间主题下次级文字、边框、邮件标题里的亮黄 / 亮绿颜色代码看不清。
* 皮肤菜单被页面上的物品图标压住。

### 开发

* 新增《游戏内排查清单》（`docs/ingame-checklist.md`）。

## [1.0.3]

### 新功能

* **界面整体迁移到 AtomLib（Skija）**：12 个页面全部重绘；AtomLib 不可用（非 Windows）时自动回退旧版绘制。
* **附属模组扩展点 `ShopModules`（客户端）**：附属模组可以把自己的功能挂进商店左侧功能区。
  推荐 `registerPage`：附属页面像内置模块一样画在右侧内容区（`AddonPage` / `AddonFrame` / `AddonPageHost` / `ShopTheme`
  + UI 套件）；也可以用 `register` 注册弹出式界面。管理员可在「编辑布局」里拖动 / 移除附属条目。
  见《公共API-使用文档》第 10 节。公共 API 版本 1.0.0 → **1.1.0**（只增不改）。
* **公共 API 独立 jar**：构建时额外产出 `module_shop-<版本>-api.jar`（只含 `api` 包、不含 `api.internal`），
  附属模组对它编译即可，从构建层面隔离内部实现。
* 第一个使用该接口的附属：[module-shop: Red envelope（拼手气红包）](https://github.com/ChatMiaoPT-Meow/Module-Shop-RedPacket)，独立仓库 / 独立 jar。

### 修复（界面迁移过程中的 QA 问题）

* 购物城「最近购买」视图下，货架视图的按钮仍可被点中（误跳回货架 / 切收藏 / 翻页）：两个视图各自只保留自己的按钮。
* Skija 输入框背后的隐藏 EditBox 在不显示时仍能被点中并抢走焦点（数字键切页失效、打字悄悄改购物城搜索）：不显示时同步隐藏并失焦。
* 购物城右键星标不再触发批量购买；下拉只在选项上命中，点外面收起时搜索框一并失焦。
* 每日商店为空时不再显示「刷新商店」；长条布局的绘制 / 悬停 / 点击范围统一。
* 管理面板：签到模式展开模块下拉时日历不再响应悬停；出货箱错误规则的左右键动作与旧版一致。

### 变更

* 撤下早期的 AtomLib 试验实现（Yoga 布局壳层），改为「AtomLib 设计包 + 自有组件」路线；确认框与数量 / 金额输入框暂时恢复原版样式，后续用新组件重做。
* AtomLib 不再提交进仓库，构建时从 GitHub Release 下载并做 SHA-256 校验（`gradle/atomlib.gradle`）。
* 服务端逻辑（商店、数据、网络协议）与 1.0.2 相同，存档兼容。

## [未发布]

### 优化

* **全服快照广播合并**：`broadcastSnapshots` 改为打标记，在服务端 tick 末尾统一下发一次；
  同一 tick 内多次触发（摊位 / 求购到期、库存刷新、一次操作里连调）不再重复给每个玩家序列化 + 压缩整份快照。
* **广播内物品编码复用**：同一次广播里共享物品（购物城货架、求购、拍卖…）的 ItemStack 只走一次 Codec 编码。
* **购物城快照不再序列化个人已买记录**：`MallItem.saveForView` 以前先整表写出 `personalUsed` 再删掉。
* **邮件到期清理**从每秒一次降为每分钟一次（有效期按天计，精度足够）。
* **玩家上报包上限收紧**：服务端解析客户端上报时，压缩后 / 解压后上限从 64 MiB 降为 8 MiB；
  玩家下线时清理其未收齐的分片缓存。

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
