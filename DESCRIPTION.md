# Module Shop — Descriptions / 模块商店 · 描述文案

Copy-paste ready texts: GitHub repo **About**, mod metadata, README intro, release notes.
可直接复制：GitHub 仓库 About、模组元数据、README 开头、Release 说明。中英各一份。

---

## 1. GitHub 仓库 About（一句话，≤350 字符）

**EN（推荐）**
```
A full server-side shop & economy mod for NeoForge 1.21.1: daily shop with paid refresh, daily buyback with completion bonus, auction house, player stalls, player trading, mailbox, currency exchange (items ⇄ Star Coins), admin grants, transaction history, per-IP alt control and a day/dark GUI theme.
```

**ZH（推荐）**
```
NeoForge 1.21.1 服务端经济系统模组：每日商店（付费刷新）、每日收购（全清奖励）、拍卖行、玩家摊位、玩家交易、邮箱、货币兑换（物品 ⇄ 星币）、管理员发放、交易流水、同 IP 多账号管控、日间/暗色界面主题。
```

**GitHub Topics（英文关键词，逗号分隔）**
```
minecraft, minecraft-mod, neoforge, neoforge-mod, fabric-api-forge, economy, shop, auction-house, mailbox, minecraft-server, java, 1-21-1
```

---

## 2. 短描述（模组列表 / Modrinth 摘要 / Release 首行）

**EN**
```
Module Shop adds a complete economy to your Minecraft server: daily shop, daily buyback, auction house, player stalls, player trading, a mailbox for items and coins, a currency exchange that turns other mods' coins into Star Coins (and back), admin grants with a GUI, transaction history, per-IP alt-account control and a day/dark theme — all server-authoritative.
```

**ZH**
```
模块商店为你的服务器补上完整经济系统：每日商店、每日收购、拍卖行、玩家摊位、玩家交易、物品/星币邮箱、把其它模组货币换成星币（也能取回）的货币兑换、管理员图形化发放、交易流水、同 IP 多账号管控、日间/暗色主题——全部数值服务端权威。
```

---

## 3. 完整描述（README / 模组页面正文）

### English

**Module Shop** is a server-side economy mod for **Minecraft 1.21.1 / NeoForge 21.1.x** (tested on Arclight, the hybrid Bukkit + NeoForge server). Everything is validated on the server; the client mod only draws the GUI.

**Features**

* **Daily shop** — a 3×2 grid of face-down cards you flip to reveal; per-item purchase limits; at most 3 "Common" items per day; **paid refresh** (200 coins for the 1st, 500 for the 2nd, 2 per day, all configurable).
* **Daily buyback** — up to 6 random buy requests per day, each with its own price and quota; sell the whole list and get a **completion bonus** (1000 coins by default, once a day).
* **Auction house** — list an item, **bids are charged immediately**, and the previous top bidder is **refunded the moment they are outbid** (overflow goes to their mailbox, never destroyed). When the auction ends the winner receives the item by mail, the seller receives the coins minus a configurable fee, and an auction with no bids returns the item automatically.
* **Player stalls** — list, restock, reprice, remove and browse other players' stalls; per-item stock and sold counters; optional listing fee.
* **Player-to-player trading** — both sides put in items and coins, both agree, a short countdown runs, then both confirm and the swap is executed atomically. An idle trade ends automatically after 10 minutes and returns everything (inventory → mailbox → drop).
* **Wallet** — balance display, `/pay` transfers with a cooldown, same-IP protection and saturating arithmetic (balances never overflow or go negative).
* **Mailbox** — item and coin mails, claim one / claim all, delete read, automatic overflow to the mailbox; 100 mails by default, 45 per page with paging.
* **Currency exchange** — admins define "1 × <any mod's coin> = N Star Coins" (drop the coin into a ghost slot, then type the rate; left-click a row to reprice, right-click to remove). Players open the **Exchange** tab, click a rectangular row (`item = N Star Coins`) to expand it, then **Deposit** (items → Star Coins) or **Withdraw** (Star Coins → items; anything that does not fit goes to the mailbox). The list scrolls with the mouse wheel, shows how many you hold and how many you can withdraw, and every exchange is written to the transaction log.
* **Day / dark theme** — one small toggle in the top bar switches the whole panel background, text, buttons, lists, cards and tabs between a light and a dark palette.
* **Admin tools** — `/shop admin money` to grant coins (everyone, selected names, UUIDs or `@a`; offline players included), `/shop admin send` for a **GUI grant screen** (drop in items, tick players or "all", optionally add coins, hit send and it arrives in their mailbox), `/shop admin history` to inspect a player's transaction log, `/shop admin ip list|unbind` for alt control, plus `/shop admin reload` and `/shop admin refresh`.
* **Transaction history** — every asset movement is logged (shop, buyback, stalls, auctions, transfers, trades, mailbox, admin grants, shop refreshes, completion bonuses) as a compact text record, viewable in-game or over RCON.
* **Per-IP alt control** — optionally bind one IP to one account and kick other accounts logging in from it (admins are exempt by default).
* **Bilingual GUI** — full `zh_cn` and `en_us` translations; all messages and screens are localised.

**Installation**

1. Put `module_shop-1.14.0.jar` into your server's `mods/` folder and restart.
2. **Every client must install the same jar** (the shop GUI is client-side code).
3. Run `/shop` in game; administrators use `/shop admin` or the small **Settings** button in the bottom-right corner of the shop window.

**Requirements** — Minecraft 1.21.1, NeoForge 21.1.x (or a NeoForge-based hybrid server such as Arclight), Java 21.
**Note** — the config file must be UTF-8 **without** a BOM; NeoForge resets the config to defaults if it finds a BOM.

### 中文

**模块商店**是一套 **Minecraft 1.21.1 / NeoForge 21.1.x** 的服务端经济系统模组（已在 Arclight 这类 Bukkit + NeoForge 混合服务端实测）。所有数值与判定都由服务端负责，客户端模组只负责绘制界面。

**功能**

* **每日商店** — 3 列 × 2 行背面朝上的卡牌，翻开揭晓商品；每件商品有独立限购；普通档每天最多 3 个；可用星币**付费刷新**（默认第 1 次 200、第 2 次 500，每天 2 次，价格与次数都能配）。
* **每日收购** — 每天随机最多 6 项收购需求，各自有单价与可收数量；把当日清单**全部卖满**还能拿一笔**全清奖励**（默认 1000 星币，每天一次）。
* **拍卖行** — 上架即开始倒计时；**出价即从余额扣款**，**被超价的瞬间全额退回**（余额装不下时自动转成金额邮件，绝不销毁）；到期后买家收物品邮件、卖家收扣完手续费的货款，无人出价则原物退回。
* **玩家摊位** — 上架、补货、改价、下架、逛摊位购买；按商品统计库存与销量；可配置上架手续费。
* **玩家交易** — 双方放入物品与星币 → 双方同意 → 倒计时 → 双方确认后一次性原子交换；闲置 10 分钟自动结束并按「背包 → 邮箱 → 掉落」退还。
* **钱包** — 余额查询、`/pay` 转账（冷却、同 IP 拦截、饱和运算，余额不会溢出也不会变负）。
* **邮箱** — 物品邮件与金额邮件，单件领取 / 一键领取 / 清理已读；背包满自动转邮箱；默认上限 100 封，每页 45 封可翻页。
* **货币兑换** — 管理员配置「1 个某模组货币 = N 星币」（幽灵槽放入货币物品后填汇率；左键点击条目改价、右键删除）。玩家打开「货币兑换」页签，点击长方形条目（`物品 = N星币`）展开，再点**兑换**（物品 → 星币）或**取款**（星币 → 物品，背包放不下自动进邮箱）；列表支持滚轮浏览，并提示背包持有量与最多可取数量，每次兑换都写入交易流水。
* **日间 / 暗色主题** — 顶部栏一个小按钮即可切换整套配色：面板背景、文字、按钮、列表、卡片、页签全部跟随。
* **管理工具** — `/shop admin money` 发星币（全体 / 玩家名 / UUID / `@a`，含离线玩家）；`/shop admin send` 打开**图形化发放界面**（放入物品、勾选玩家或全体、可附加星币，发送后进对方邮箱）；`/shop admin history` 查看某个玩家的资产流水；`/shop admin ip list|unbind` 管同 IP 小号；另有 `/shop admin reload` 与 `/shop admin refresh`。
* **交易流水** — 所有资产变动都会留下紧凑的文本记录（商店、收购、摊位、拍卖、转账、交易、邮箱、管理员发放、刷新商店、全清奖励），游戏内或 RCON 都能翻页查看。
* **同 IP 多账号管控** — 可选地把一个 IP 绑定到一个账号，同 IP 的其它账号登录会被踢下线（管理员默认豁免）。
* **中英双语界面** — 完整 `zh_cn` / `en_us` 文案，界面与提示全部本地化。

**安装**

1. 把 `module_shop-1.14.0.jar` 放进服务端 `mods/`，重启。
2. **所有客户端都要装同一个 jar**（商店界面是客户端代码）。
3. 游戏内执行 `/shop` 打开界面；管理员用 `/shop admin`，或点商店界面**右下角**的小按钮「商店设置」。

**环境要求** — Minecraft 1.21.1、NeoForge 21.1.x（或 Arclight 这类 NeoForge 混合服务端）、Java 21。
**注意** — 配置文件必须是**不带 BOM 的 UTF-8**；带 BOM 时 NeoForge 会把配置重置为默认值。

---

## 4. Release 说明模板

**EN**
```markdown
## Module Shop v1.14.0 — first public release

Server-side economy for NeoForge 1.21.1: daily shop (with paid refresh), daily buyback (with completion bonus),
auction house, player stalls, player trading, wallet transfers, mailbox, **currency exchange (items ⇄ Star Coins)**,
admin grants, transaction history, per-IP alt control and a **day/dark GUI theme**.
Fully server-authoritative, bilingual GUI (currency shown as "Star Coins" in English clients).

**Install** — drop the jar into `mods/` on both server **and clients**, then restart.
**Requires** — Minecraft 1.21.1, NeoForge 21.1.x, Java 21.

See `docs/` for the Chinese manuals and `DESCRIPTION.md` for the full feature list.
```

**ZH**
```markdown
## 模块商店 v1.14.0 — 首个公开版

面向 NeoForge 1.21.1 的服务端经济系统：每日商店（付费刷新）、每日收购（全清奖励）、拍卖行、玩家摊位、
玩家交易、钱包转账、邮箱、**货币兑换（物品 ⇄ 星币）**、管理员发放、交易流水、同 IP 多账号管控、
**日间 / 暗色界面主题**。全部数值服务端权威，界面中英双语（英文客户端显示 Star Coins）。

**安装** —— 把 jar 同时放进**服务端和客户端**的 `mods/`，重启即可。
**要求** —— Minecraft 1.21.1、NeoForge 21.1.x、Java 21。

中文手册见 `docs/`，完整功能列表见 `DESCRIPTION.md`。
```
