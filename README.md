# Module Shop · 模块商店

**English** — A full server-side shop & economy mod for **Minecraft 1.21.1 / NeoForge 21.1.x** (tested on Arclight):
daily shop with paid refresh, daily buyback with a completion bonus, auction house, player stalls, player trading,
wallet transfers, mailbox, **currency exchange (items ⇄ Star Coins)**, admin grants (GUI), transaction history,
per-IP alt-account control and a day/dark GUI theme.
Fully server-authoritative, bilingual GUI (English / 简体中文).
→ **Full description: [DESCRIPTION.md](DESCRIPTION.md)**

**中文** — 一个面向 **NeoForge 1.21.1 / Arclight 服务端** 的完整经济系统模组：每日商店（含付费刷新）、
每日收购（含全清奖励）、拍卖行、玩家摊位、玩家交易与转账、邮箱、**货币兑换（物品 ⇄ 星币）**、
管理员发放（图形界面）、交易流水、同 IP 多账号管控、日间/暗色界面主题。数值全部服务端权威，界面中英双语。
→ **完整描述见 [DESCRIPTION.md](DESCRIPTION.md)**

> 本模组即 **Module-Shop（模块商店）**。项目早期曾用过其它 modid 的旧版本，旧存档请继续使用旧版本。

[![Build](https://github.com/Ar-Zhe/Module-Shop/actions/workflows/build.yml/badge.svg)](https://github.com/Ar-Zhe/Module-Shop/actions/workflows/build.yml)

---

## 功能一览

| 模块 | 说明 |
| --- | --- |
| **每日商店** | 卡牌翻牌查看商品；3 列 × 2 行；每件商品独立限购；普通档每日最多 3 个；可用星币**付费刷新**（默认第 1 次 200、第 2 次 500，每日 2 次，价格与次数可配） |
| **每日收购** | 每日随机最多 6 项收购需求；按物品显示收购价与剩余可收数量；**当日清单全部卖满额外奖励 1000 星币**（可配） |
| **购物城** | 管理员上架的**常驻货架**（不会每日重抽）：两行六列一页 12 件，显示价格与库存，**品质只用 1px 颜色细边框表示**；支持**搜索**（物品名 / 模组名）、**排序**（价格 / 库存 升降序）、**按模组分类**（下拉，按首字母排序）、**只收藏**（本地收藏，★）与**最近购买**记录；库存实时扣减、售罄变灰；管理员可单件上架，也可**一次导入整个模组的全部物品**（统一价格 / 库存 / 品质） |
| **拍卖行** | 上架拍卖、**出价即从余额扣款**、**被超价自动全额退回**（余额装不下时自动转金额邮件）、到期自动结算（买家收物品邮件、卖家收货款、按比例手续费）、无人出价原物退回 |
| **玩家摊位** | 上架 / 改价 / 下架 / 浏览购买，支持库存与售出统计；可配置上架手续费 |
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

## 公共 API（1.0.0 起）

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

三条硬规则：**只在服务端主线程调用**、**只用 `ApiResult` 判断成败**、**写操作必须带幂等键**。

三种接入方式：

1. **Java**：`compileOnly` 依赖本模组 jar（见 [API 使用文档](docs/公共API-使用文档.md)）；
2. **KubeJS**：脚本里直接用全局 `ModuleShop`（示例：[examples/kubejs](examples/kubejs)）；
3. **HTTP / REST**：可选、**默认关闭**，需显式开启并设置 token（默认只监听 `127.0.0.1`、默认只读）。

> API 版本 `1.0.0` 与模组版本独立；同一主版本内只增不改。

## 安装

1. 服务端：把 `module_shop-1.0.0.jar` 放进 `mods/`，重启。
2. 客户端：**玩家也必须安装同一个 jar**（商店界面是客户端代码）。
3. 客户端与服务端都启动后执行 `/shop` 打开界面；管理员用 `/shop admin` 打开设置页，
   或在商店界面**右下角**点小按钮「商店设置」。

> 版本要求：Minecraft 1.21.1 / NeoForge 21.1.x（已在 Arclight 1.21.1 混合服务端实测）。
>
> 配置文件必须是**不带 BOM 的 UTF-8**：带 BOM 时 NeoForge 会报 `Invalid bare key` 并把配置重置为默认值。

## 构建

```bash
./gradlew build          # Windows: gradlew.bat build
# 产物：build/libs/module_shop-1.0.0.jar

# 需要一个带 KubeJS 桥接（脚本全局 ModuleShop）的 jar：
./gradlew build -PkubejsJar=/path/to/kubejs-neoforge-2101.x-build.xxx.jar
```

需要 JDK 21。

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
```
> 付费刷新：`dailyRefreshCosts` 是**每日商店**的价格表，`buybackRefreshCosts` 是**每日收购**的价格表，
> 两者各自独立，**列表长度 = 每天可刷新次数**（例如 `["300"]` = 每天只能刷 1 次、每次 300 星币）。

## 文档

`docs/` 目录下是中文说明：

* **`模组介绍.md` —— ⭐ 模组总体介绍（中英双语：功能一览 / 核心特点 / 适合谁）**
* **`使用教程.md` —— ⭐ 完整使用教程（玩家功能 / 管理员配置 / 指令 / 配置项 / 常见问题）**
* **`更新日志-1.14到1.15.md` —— ⭐ 1.14 → 1.15 版本更新日志（中英双语：掉线崩溃修复 / 购物城定时补货 / 侧栏平滑滚动）**
* **`更新日志-1.13到1.14.md` —— 1.13 → 1.14 版本更新日志（中英双语：新增功能 / 界面重做 / 修复 / 升级说明）**
* `发放功能说明.md` —— 管理员发钱发物品与邮箱领取
* `第二轮需求实现说明.md` —— 星币封顶策略 / 交易超时退回 / 邮箱与流水
* `第三轮需求实现说明.md` —— 界面页码保留 / 滚轮翻页 / 付费刷新 / 收购全清奖励
* `第四轮需求实现说明.md` —— 币种统一为星币 / 货币兑换模块 / 管理员入口移动 / 日间暗色主题
* `第五轮需求实现说明.md` —— 界面收尾与星币迁移 / 签到模块（里程·日历·补签·活动日）/ 刷新权重·保底·牌数与长条翻牌
* `第六轮需求实现说明.md` —— 签到界面重做与日程系统 / 统一暗色 / 上架返回修复 / 滚动提示
* `第七轮-邮箱系统与兑换码设计方案.md` —— 鸣潮风格邮箱、邮件模板与有效期、兑换码系统
* `第八轮优化说明.md` —— 邮箱合并与附件格子、日程奖励（物品发放式）、补签连续加成、界面版式与重叠修正、刷新拆分、点击音效等
* `代码审查报告.md` —— 全模组代码审查记录（含已修复问题与优化建议）
* `公共API-阶段一-审计报告.md` —— 现有功能/数据/线程/权限审计（API 设计依据）
* `公共API-使用文档.md` —— **开发者接入文档**（接口、返回码、事件、KubeJS、HTTP）
* `公共API-第一~七批-交付说明.md` —— 每批交付内容、实测证据与已知限制

## 许可

本项目使用 [MIT 许可证](LICENSE)。

> 本项目基于 NeoForge MDK 模板开发；`TEMPLATE_LICENSE.txt` 为上游模板自带说明。
