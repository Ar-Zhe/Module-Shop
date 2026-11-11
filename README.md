# Module Shop · 模块商店

**English** — A full server-side shop & economy mod for **Minecraft 1.21.1 / NeoForge 21.1.x** (tested on Arclight):
daily shop with paid refresh, daily buyback with a completion bonus, auction house, player stalls, player trading,
wallet transfers, mailbox, **currency exchange (items ⇄ Star Coins)**, admin grants (GUI), transaction history,
per-IP alt-account control and a day/dark GUI theme.
Fully server-authoritative, bilingual GUI (English / 简体中文).
→ **Full description: [DESCRIPTION.md](DESCRIPTION.md)**

**中文** — 一个面向 **NeoForge 1.21.1 ** 的完整经济系统模组：每日商店（含付费刷新）、
每日收购（含全清奖励）、拍卖行、玩家摊位、玩家交易与转账、邮箱、**货币兑换（物品 ⇄ 星币）**、
管理员发放（图形界面）、交易流水、同 IP 多账号管控、日间/暗色界面主题。数值全部服务端权威，界面中英双语。
→ **完整描述见 [DESCRIPTION.md](DESCRIPTION.md)**

> 本模组大量使用AI代码，前身为 **Module-Shop（模块商店）**。项目早期曾用过其它 modid 的旧版本，旧存档请继续使用旧版本。

[![Build](https://github.com/Ar-Zhe/Module-Shop/actions/workflows/build.yml/badge.svg)](https://github.com/Ar-Zhe/Module-Shop/actions/workflows/build.yml)

> **1.0.3 起界面改用 [AtomLib](https://github.com/NoWordz/AtomLib)（Skija）绘制**：12 个页面全部重绘（翻牌动画、签到月历、
> 购物城、管理面板等），支持中文输入法；AtomLib 只带 Windows 原生库，其它平台自动回退到旧版界面，功能不受影响。
> 同时开放了**附属模组接口**：附属可以把自己的功能挂进商店侧栏、直接画在内容区（示例：[module-shop: Red envelope](https://github.com/ChatMiaoPT-Meow/Module-Shop-RedPacket)）。
>
> **1.0.4**：外壳与 22 个次级界面也改用 AtomLib 绘制，加入动效；新增**界面皮肤**（日间 / 夜间 / 樱糖 / 薄荷 / 雾蓝 / 奶茶 / 夜樱，
> 带渐变与柔光，右上角切换），主面板与弹窗改为半透明。
>
> **1.0.5**：拍卖行全面升级（大厅搜索 / 分类 / 排序、详情页、一口价、防截拍、资金托管与审计、管理界面）；
> 玩家商店升级（商店大厅、摊位详情、从背包上架、补货）；修复出货箱倒计时结束后不出货。
>
> **1.0.6**：商店配置导出 / 导入 / 跨世界共享；兼容 AtomLib 0.4.0（运行中遇到前置库不兼容会自动退回旧版界面）；修复拍卖行在窄窗口下部分 UI 块超出面板。

---

## 功能一览

| 模块 | 说明 |
| --- | --- |
| **每日商店** | 卡牌翻牌查看商品；3 列 × 2 行；每件商品独立限购；普通档每日最多 3 个；可用星币**付费刷新**（默认第 1 次 200、第 2 次 500，每日 2 次，价格与次数可配） |
| **每日收购** | 每日随机最多 6 项收购需求；按物品显示收购价与剩余可收数量；**当日清单全部卖满额外奖励 1000 星币**（可配） |
| **购物城** | 管理员上架的**常驻货架**（不会每日重抽）：两行六列一页 12 件，显示价格与库存，**品质只用 1px 颜色细边框表示**；支持**搜索**（物品名 / 模组名）、**排序**（价格 / 库存 升降序）、**按模组分类**（下拉，按首字母排序）、**只收藏**（本地收藏，★）与**最近购买**记录；库存实时扣减、售罄变灰；管理员可单件上架，也可**一次导入整个模组的全部物品**（统一价格 / 库存 / 品质） |
| **拍卖行** | 独立上架界面（直接从背包选物品，数量 / 起拍价 / 加价幅度 / 一口价 / 时长 / 是否允许竞价）、**搜索（名称 / 物品ID / 模组 / 附魔 / 卖家）+ 9 种分类 + 7 种排序 + 分页**、详情页（完整属性、竞价记录）、**一口价**、**防截拍自动延时**、无人出价可撤销、**我的拍卖**（出售中 / 我的竞拍 / 已成交 / 已流拍 / 已撤销 + 重新上架）；出价资金托管，被超价同一时刻全额退回；结算幂等、重启续结算；上架费 / 成交费 / 黑名单（含容器内物品）/ 绑定与特殊 NBT 限制；管理员界面与 `/shop auction` 审计命令。详见 [docs/拍卖行.md](docs/拍卖行.md) |
| **玩家商店（摊位）** | 我的商店（从背包上架 / 改价 / 补货 / 下架 / 交易记录）、**商店大厅**（全服商品搜索 / 分类 / 排序 / 分页）、查看摊位（摊位详情：物品格子、数量调整、总价、购买）；按商品 id 交易、价格核对、请求去重。详见 [docs/玩家商店.md](docs/玩家商店.md) |
| **玩家交易** | 双方放入物品与星币 → 双方同意 → 倒计时 → 双方确认后原子交换；闲置 10 分钟自动结束并按"背包 → 邮箱 → 掉落"退回 |
| **钱包** | 余额查询、玩家间转账（同 IP 拦截、冷却、余额上限饱和处理，不溢出不变负） |
| **邮箱** | 物品邮件与金额邮件；一键领取 / 清理已读；背包满自动转邮箱；**同渠道邮件自动合并**、附件用可滚动的物品格子展示；默认上限 100 封（满时自动清理最早的已读邮件） |
| **货币兑换** | 管理员配置「1 个某模组货币 = N 星币」（幽灵槽放入物品，自定义汇率）；玩家在长方形列表里**点击展开** → 「兑换」（物品 → 星币）或「取款」（星币 → 物品，背包放不下自动进邮箱）；支持滚轮浏览、背包持有量 / 最多可取数量提示，全部计入交易流水 |
| **界面风格** | 全界面统一**暗色扁平风**（参考 OneConfig 深色主题）：直角面板 + **1px 硬边框**，**无发光 / 渐变 / 阴影 / 水波**，单一强调色 `#2B4BFF`、选中项整条实心；面板与导航用**原版 Minecraft 像素字体**，内容区保留 0.8 倍小字；鼠标滚轮整行滚动 + 右侧滑块 + 「下面还有」提示；界面点击音效可在设置里更换并试听 |
| **签到与日程** | 连续签到进度条（里程点悬停显示加成）、日历补签（二次确认）、管理员可编辑的**日程（活动日）**：标题 / 正文 / 颜色 / 定时发放 / 补发离线 / 错过即补发，奖励用「物品发放式」界面配置（多件物品 + 星币），通过邮箱发放 |
| **账单** | 点主界面顶部**余额**进入：当前余额 + 今日/本月收支汇总，全部/支出/收入筛选，明细按天分组（每行含类型、金额、备注、时间与变动后余额）；滚轮整行滚动 + 滑块 + 「下面还有」提示，停留时每 2 秒自动刷新；其它插件的收支可通过 `/shop wallet log` 记入 |
| **JEI 联动** | 装了 JEI 时，鼠标悬停在商店里的物品图标上直接按 **R**（看这件物品怎么来的）/ **U**（看它能用来做什么）打开 JEI 配方页；自绘界面也支持，认玩家自己的按键绑定；不装 JEI 时行为完全不变（纯软依赖，**服务器无需安装**） |
| **管理员发放** | `/shop admin money <金额> [目标...]` 发星币（不填目标 = 所有已知玩家，含离线；目标可写玩家名 / UUID / `all` / `@a`），`/shop admin money set <目标> <金额>` 直接改余额（0 = 清零），`/shop admin send` 打开图形发放界面：放入物品 + 勾选玩家或全体 + 可选金额 → 发送，玩家在邮箱领取；也可用 `/shop wallet give <在线玩家> <金额>` 只发一个人 |
| **交易流水** | 所有资产变动都记流水（买卖 / 摊位 / 拍卖 / 转账 / 交易 / 邮箱 / 管理员发放 / 刷新商店 / 刷新收购 / 全清奖励），管理员用 `/shop admin history <玩家>` 翻页查看 |

## 公共 API（当前 1.2.0）

模组现在对外提供**稳定的公共 API**，供其他模组、KubeJS 脚本与外部服务调用：

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 经济 | `ModuleShopApi.economy()` | 查余额 / 受上限加钱 / 余额不足整笔失败的扣钱 |
| 物品投递 | `ModuleShopApi.items()` | 在线进背包、满转邮箱；离线直接进邮箱，绝不吞奖励 |
| 邮箱 | `ModuleShopApi.mail()` | 物品邮件 / 金额邮件 / 多附件邮件 + 只读查询 |
| 流水 | `ModuleShopApi.history()` | 查询最近流水；也可「只记账」把外部收支写进玩家账单 |
| 签到·活动 | `ModuleShopApi.daily()` | 只读查询（不允许伪造签到） |
| 幂等发奖 | `ModuleShopApi.reward()` | 同一个 `requestId` 对同一玩家只发一次，**重启后仍防重复** |
| 商店 | `ModuleShopApi.shop()` | 只读商品/价格/库存 + 受校验的购买/出售 + 拍卖出价 / 求购发布 / 摊位上架 |
| 事件 | `ModuleShopApi.events()` | 余额变更 / 邮件创建 / 奖励发放 / 签到 / 成交 …（业务成功后通知，不可取消） |
| **附属页面**（1.1.0） | `api.client.ShopModules` | 客户端：附属模组在商店侧栏注册条目，`registerPage` 画在内容区（`AddonPage` + `ShopTheme` + UI 套件），`register` 弹出自己的界面 |
| **皮肤**（1.2.0） | `api.client.ShopTheme` | 客户端：跟随玩家选择的界面皮肤取色；渐变端点、柔光，以及和主体一致的弹窗底板 `dialogPanel` |

三条硬规则：**只在服务端主线程调用**、**只用 `ApiResult` 判断成败**、**写操作必须带幂等键**。

三种接入方式：

1. **Java**：`compileOnly` 依赖 `module_shop-<版本>-api.jar`（只含公共 API 与 UI 套件，碰不到内部实现；见 [API 使用文档](docs/公共API-使用文档.md)）；
2. **KubeJS**：脚本里直接用全局 `ModuleShop`（示例：[examples/kubejs](examples/kubejs)）；
3. **HTTP / REST**：可选、**默认关闭**，需显式开启并设置 token（默认只监听 `127.0.0.1`、默认只读）。

> API 版本 `1.2.0` 与模组版本独立；同一主版本内只增不改（UI 套件同样适用）。附属页面出错时商店会停用该页面并改用附属的回退界面，不会崩溃。

## 安装

1. 服务端：把 `module_shop-1.0.6.jar` 放进 `mods/`，重启（服务端**不需要** AtomLib）。
2. 客户端：**玩家也必须安装同一个 jar**（商店界面是客户端代码），另外安装前置
   [AtomLib 0.1.0-beta](https://github.com/NoWordz/AtomLib/releases/tag/v0.1.0-beta-beta3)（`atomlib-1.21.1-neoforge-0.1.0-beta.jar`）。
   AtomLib 只支持 Windows x64 / ARM64；macOS / Linux 客户端会自动使用旧版界面。
3. 客户端与服务端都启动后执行 `/shop` 打开界面；管理员用 `/shop admin` 打开设置页，
   或在商店界面**右上角**点「商店设置」。界面皮肤在右上角的太阳 / 月亮按钮里切换（每个玩家各自保存）。

> 版本要求：Minecraft 1.21.1 / NeoForge 21.1.x（已在 Arclight 1.21.1 混合服务端实测）。
>
> 配置文件必须是**不带 BOM 的 UTF-8**：带 BOM 时 NeoForge 会报 `Invalid bare key` 并把配置重置为默认值。

## 构建

```bash
./gradlew build          # Windows: gradlew.bat build
# 产物：build/libs/module_shop-1.0.6.jar      主模组
#       build/libs/module_shop-1.0.6-api.jar  公共 API + UI 套件（给附属编译用）
# 首次构建会自动下载 AtomLib 发布包到 .atomlib/ 并做 SHA-256 校验（版本与校验值见 gradle.properties）

# 需要一个带 KubeJS 桥接（脚本全局 ModuleShop）的 jar：
./gradlew build -PkubejsJar=/path/to/kubejs-neoforge-2101.x-build.xxx.jar
```

需要 JDK 21。

## 使用的库

| 库 | 版本 | 用途 | 依赖方式 | 许可 |
| --- | --- | --- | --- | --- |
| NeoForge | 21.1.248 | 模组加载器、事件、网络、配置 | 运行环境 | LGPL-2.1 |
| Minecraft | 1.21.1 | 游戏本体 | 运行环境 | — |
| **AtomLib** | 0.1.0-beta（发布标签 `v0.1.0-beta-beta3`） | 客户端 UI：Minecraft ↔ Skija 渲染桥（`MinecraftRenderBridge`）、字体与文字排版（`FontRegistry` / `TextService`）、设计包 `design.atomchat`（`SkiaDraw`、`Design` 主题注入） | **客户端必需前置**；构建时从 GitHub Release 下载并校验，**不打包进本模组** | Apache-2.0 |
| Skija | 0.143.18 | Skia 的 Java 绑定，所有新界面的 `Canvas` 绘制 | 运行时由 AtomLib 以 Jar-in-Jar 内置（只带 Windows x64 / ARM64 原生库）；编译期作为 AtomLib 的传递依赖从 Maven Central 取 | Apache-2.0 |
| Noto Sans SC（子集） | 随 AtomLib | 界面中文字体（常规 / 粗体） | 由 AtomLib 内置 | OFL-1.1 |
| AtomChat 组件（移植） | — | `UiComponent`、`ScrollController`、`SkiaTextField`（隐藏 EditBox + Skija 绘制的输入框思路）、帧编排 `SkiaFrame` | 源码移植进 `client/ui/`，保留出处注释 | MIT |
| JEI API | 19.8.4.110 | 商店里悬停物品按 R / U 查配方 | `compileOnly` 软依赖，不装 JEI 时完全不影响 | MIT |
| KubeJS | 2101.7.x | 脚本全局 `ModuleShop` 桥接 | 可选，`-PkubejsJar=...` 时才编译 | LGPL-3.0 |
| ModDevGradle | 2.0.144 | 构建插件 | Gradle | LGPL-2.1 |
| Parchment | 2024.11.17 | 参数名映射（只影响开发） | Gradle | CC0 |

界面相关的 AtomLib / Skija 调用全部收口在 `client/ui/`（薄适配层），各页面在 `client/screen/shop/`，
迁移方案与进度见 [docs/atomlib-migration.md](docs/atomlib-migration.md)。

## 常用指令

```text
/shop                              打开商店
/shop mailbox | /shop stall | /shop trade open

/shop admin                        商店设置页（2 级权限）
/shop admin reload                 热重载商品池并同步所有在线玩家
/shop admin refresh <玩家>          刷新某玩家的每日商店与收购
/shop admin money <金额>            给所有已知玩家（在线 + 历史离线）各发这么多星币
/shop admin money <金额> <目标...>   只发给指定玩家：可写玩家名（离线也行）、UUID，或 all / @a 表示全体
/shop admin money set <目标> <金额>  把某玩家余额**直接设为**该值（写 0 = 清零，离线也可）
/shop admin send                   打开发放界面（物品 + 星币，邮箱领取）
/shop admin history <玩家> [页码]   查看交易流水
/shop auction list | history [页] | anomalies | audit       拍卖行：进行中 / 历史 / 异常 / 资金审计
/shop auction cancel <id> | end <id>                        强制撤销（全额退款）/ 强制结束（按当前状态结算）
/shop wallet balance | pay <玩家> <金额> | give <玩家> <金额>
```

> 发钱要点：单次金额不能超过 `maxBalance`（超出会直接拒绝）；余额到顶的部分不会发放（会提示"余额上限"）；
> 离线玩家同样入账，下次登录即可看到；每一笔都会写进交易流水，可用 `/shop admin history <玩家>` 查。
> `money` 是**加一笔**，`money set` 是**改成这个数**：想清空自己的余额就是 `/shop admin money set 你的ID 0`。

## 配置

配置文件：`config/module_shop-common.toml`（首次启动自动生成，注释为中文）。
常用项：

```toml
[wallet]        currencyName = "星币"      maxBalance = 9999999999
[daily_shop]    dailyShopSlots = 6         dailyShopCommonLimit = 3
                dailyRefreshCosts = ["200", "500"]
[daily_buyback] buybackSlots = 6           buybackAllBonus = 1000
                buybackRefreshCosts = ["200", "500"]
[mailbox]       maxMailboxSize = 100       transactionHistorySize = 50
[trade]         tradeRequestExpireSeconds = 60   tradeSessionTimeoutSeconds = 600
[auction]       auctionEnabled = true      maxAuctionsGlobal = 500   maxConcurrentAuctions = 6
                auctionDurationOptions = ["30","60","180","360","720","1440","2880"]
                auctionListingFeeFixed = 0  auctionListingFeePercent = 0.0  auctionFeePercent = 5.0
                antiSnipe = true  antiSnipeSeconds = 60  antiSnipeExtensionSeconds = 30  antiSnipeMaxExtensions = 10
                auctionBlacklist = [...]   auctionAllowBound = false  auctionAllowCustomData = true
```
> 付费刷新：`dailyRefreshCosts` 是**每日商店**的价格表，`buybackRefreshCosts` 是**每日收购**的价格表，
> 两者各自独立，**列表长度 = 每天可刷新次数**（例如 `["300"]` = 每天只能刷 1 次、每次 300 星币）。

## 文档

所有文档都在 [`docs/`](docs/README.md) 目录里：

| 文档 | 内容 |
| --- | --- |
| [使用教程.md](docs/使用教程.md) | 服主上手：安装、模块、权限、常用流程 |
| [模组介绍.md](docs/模组介绍.md) | 面向玩家的功能说明 |
| [拍卖行.md](docs/拍卖行.md) | 拍卖行：玩法、配置、资金托管与安全设计、管理命令、测试清单 |
| [玩家商店.md](docs/玩家商店.md) | 玩家商店：三个入口、摊位详情、交易安全、测试清单 |
| [公共API-使用文档.md](docs/公共API-使用文档.md) | 开发者接入：接口、返回码、事件、KubeJS、HTTP、附属页面（第 10 节） |
| [atomlib-migration.md](docs/atomlib-migration.md) | AtomLib 界面迁移方案、进度与游戏内 QA 清单 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |

另外还有两份可直接使用的文案：`DESCRIPTION.md`（完整介绍，适合发帖）与本文件（README）。

## 许可

本项目使用 [MIT 许可证](LICENSE)。

> 本项目基于 NeoForge MDK 模板开发；`TEMPLATE_LICENSE.txt` 为上游模板自带说明。
>
> 第三方：AtomLib（Apache-2.0）不随本模组分发，由玩家单独安装；`client/ui/` 中移植自 AtomChat 的组件遵循 MIT 许可，
> 源文件内保留了出处说明。AtomLib 自带的 Skija / Skia / Noto Sans SC 等许可见 AtomLib jar 的 `META-INF/licenses/`。
