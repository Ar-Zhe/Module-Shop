# Module-Shop → AtomLib UI 重构方案（草案 v2）

> 基于 2026-11-04 下载的源码：`Ar-Zhe/Module-Shop`（v1.0.2）、`NoWordz/AtomLib`（main，0.3.0 候选）、`NoWordz/AtomChat-Mod`（main）。
> v2 变化：参考 AtomChat（与 AtomLib 同源、已发布并经游戏验证的消费者）后，推荐路线从“0.3.0 控件 + Yoga”改为“beta3 设计包 + 移植 AtomChat 的已验证组件”。

## 1. 现状

### Module-Shop 客户端 UI
- 位置：`src/main/java/cn/autoforged/module_shop/client/`，约 **16,000 行**。
- 全部即时模式绘制：`GuiGraphics.fill / drawString` 手算坐标；点击用 `Region(x,y,w,h,action,extra)` 命中，`onActionInner(String action, CompoundTag)` 大 switch 分发并发包。
- `ShopScreen.java` 单文件 **5,135 行**：侧边栏（可拖拽排序/隐藏模块）、资料块、全部页签（每日商店翻牌、回收、拍卖、摊位、求购、邮箱、签到日历、转账选人、管理面板、兑换、商城、出货箱）。
- 样式：`IOStyle`（iOS 风格日/夜两套色板）→ `Theme` → `UITheme`。**与 README 写的“扁平直角 #2B4BFF”不一致**，需确认基准。
- 自定义控件：`IOSButton`、`IOEditBox`、`Nav`、`Icons`、`Bricks`、`Skins`；约 30 个次级 Screen。
- 集成：JEI（R/U）、物品图标 + 原版 tooltip 随处可见。

### AtomChat 怎么用 AtomLib（关键参考）
- **依赖的是已发布的 `0.1.0-beta`（beta3）**，不是 0.3.0 候选。版本锁在 `versions/atomlib.json`，Maven 输入包可直接下载（`releases/download/v0.1.0-beta-beta3/atomlib-input-0.1.0-beta.zip`，约 100MB，已确认可访问）+ SHA-256 校验（`gradle/atomchat-atomlib.gradle`）。
- **只用 AtomLib 的两部分**：渲染桥 `MinecraftRenderBridge`（`drawFrame` / `vanillaPass` / `withContext`）和设计包 `com.atom.lib.design.atomchat`（`UiTokens` 45 处、`SkiaDraw` 21 处、`AppIcons` 10 处、`UiCards`、`DesignHost`）。
- **没有用 Yoga，也没有用 controls 控件**。UI 框架是 AtomChat 自己的：`UiComponent`（绝对坐标组件基类）、`UiLayout`、`ScrollController`、`ConfirmationDialog`、`ToggleSwitch`、`SpringAnim` 等。
- AtomChat 是 **MIT** 协议，Module-Shop 也是 MIT → 可以移植代码，保留版权声明即可。

## 2. 两条路线对比

| | **路线 A：AtomChat 同款（推荐）** | 路线 B：0.3.0 控件 + Yoga |
|---|---|---|
| AtomLib 版本 | 0.1.0-beta（已发布） | 0.3.0（未发布，需本地构建，打包脚本要 Python） |
| 依赖获取 | 下载 release 包，和 AtomChat 同一方式 | `build/test-maven` 本地产物 |
| 布局 | 自己算坐标（与现有代码思路一致，迁移成本低） | Yoga flex，**无绝对定位**，现有布局要全部重写 |
| 控件 | 移植 AtomChat 组件 | ActionButton/Toggle/Slider/SegmentedChoice/Disclosure/ModalDialog |
| 游戏内验证 | **AtomChat 已发布并实测（Windows x64）** | 文档自述多项 NOT_RUN |
| 与玩家环境 | 和 AtomChat 用同一个 AtomLib jar，玩家可共装 | 若 AtomChat 仍锁 beta3，两者可能版本冲突 |

推荐 A。B 的控件以后稳定了可以逐个替换进来，不冲突。

## 3. 缺口 → AtomChat 里的现成解法

| Module-Shop 需要 | 解法（出处） |
|---|---|
| **文本输入**（搜索、数量、价格、兑换码） | **隐藏原版 `EditBox` 承载文本/光标/粘贴/IME，Skija 画可见输入框，光标位置映射回 EditBox 让输入法候选窗对齐**。`ComposerInput` / `ComposerPointer`（通过 `Field` 接口隔离 MC 类型，可离线测试）。Module-Shop 的输入比聊天简单（单行），可做精简版 `SkiaTextField` |
| **物品图标** | `ItemIconRenderer`（`layers/mapping/official/.../render/`，385 行）：原版渲染到离屏纹理 → Skija 借用 Image，带缓存与失效处理，**游戏内已用于聊天物品链接**。图标在 Skija 层里，能被弹窗正确遮挡 |
| **tooltip** | Skija 之后走原版 `renderTooltip`（AtomChat 的悬停提示就是这样） |
| **多个滚动区域**（侧边栏 + 内容） | `ScrollController`：纯 Java 状态机，无 MC/Skija 类型，每个列表一个实例，带平滑滚动、滚动条淡入/拖拽 |
| 确认弹窗 | `ConfirmationDialog` + `ConfirmationInput/Layout/State` |
| 开关、按压缩放、涟漪、弹簧动画 | `ToggleSwitch`、`PressScale`、`ClickRipples`、`SpringAnim / UiSpring` |
| 帧编排 | `SkiaGraphics.draw(preUi, density, painter)`：`UiPixelSnap.beginFrame` → `bridge.vanillaPass(preUi)`（图标准备等原版 GL 工作）→ `bridge.drawFrame`（Skija 绘制，canvas 统一缩放）→ 之后原版叠加（隐藏 EditBox 定位、tooltip） |
| 主题色 | `Design.host(DesignHost)` 注入强调色、圆角、卡片色 → 用它把 `IOStyle` 日/夜色板接进来 |
| 字体 | AtomChat `SkiaFontRenderer`（826 行）+ AtomLib 自带 Noto Sans SC 子集 |
| JEI R/U | 保留 `JeiHoverSource`，悬停物品从新组件的命中结果取 |
| 侧边栏拖拽排序 | 无现成组件，保留现有拖拽逻辑，只换绘制 |

## 4. 必须遵守的约束
1. **客户端隔离**：`com.atom.lib.*` 与 Skija 只能出现在 `client` 包，仅 `Dist.CLIENT` 加载；`neoforge.mods.toml` 中 AtomLib 依赖声明 `side="CLIENT"`。Arclight 专服不装 AtomLib 必须能启动（AtomChat 同样如此）。
2. **平台**：AtomLib 只带 Windows x64/ARM64 原生库（AtomChat 也已放弃 Linux/macOS）。需决定：只支持 Windows，还是保留旧 UI 回退。
3. **AtomLib 缺失检测**：AtomChat 有 `compat/AtomLibDependencyCheck`（反射校验桥方法签名），可照搬，缺失或版本不符时给出明确提示而不是崩溃。
4. **薄适配层**：所有 AtomLib/Skija 调用收口在 `client/ui/`，将来升级 0.3.0 只改这一层。

## 5. 目标结构

```
client/
  ui/                          ← 新增
    render/
      SkiaFrame.java           帧编排（移植 SkiaGraphics 精简版）
      ItemIcons.java           移植 ItemIconRenderer
      Fonts.java / TextDraw    字体注册与文字测量绘制
      PixelSnap.java
    widget/
      UiComponent.java         组件基类（移植）
      Button / Card / ItemCard / ListRow / SidebarTab / Toggle
      SkiaTextField.java       隐藏 EditBox 方案
      ScrollController.java    移植
      ConfirmDialog.java       移植
    ShopDesign.java            IOStyle 色板 → DesignHost
    AtomLibCheck.java          依赖检测
  screen/
    shop/                      ← 拆分 ShopScreen
      ShopScreen.java          外壳：侧边栏 + 头部 + 当前页
      ShopPage.java            接口：layout(), paint(canvas), mouse*/key*, onAction()
      DailyPage / BuybackPage / AuctionPage / StallPage / DemandPage / MailPage
      SignInPage / TransferPage / AdminPage / ExchangePage / MallPage / ShippingPage
    dialogs/                   次级 Screen
```

`send(action, extra)` / `ClientShopData` / 网络包 **不动**，服务端零改动。

## 6. 分阶段计划

| 阶段 | 内容 | 验证 |
|---|---|---|
| 0 ✅ | 按 AtomChat 方式接入 beta3：下载输入包 → 本地 maven 目录 → `build.gradle` 加 client 依赖 + SHA 校验；`neoforge.mods.toml` 声明依赖 | `gradlew build` 通过 ✅；专服不装 AtomLib 能启动（待验） |
| 1 ✅ | 基础设施：`AtomUi`、`SkiaFrame`、`Text`、`Draw`、`ShopDesign`、`UiComponent`、`Button`、`ScrollController`、`Motion`；演示页 `/moduleshop_atomlib_demo` | 2026-11-04 游戏内验收通过：Skija 绘制、中文文字、滚动、悬停正常 |
| 2 ✅ | 物品图标层 `ItemOverlay`（原版绘制 + 分层，弹窗可盖住物品）、`SkiaTextField`（隐藏 EditBox）、输入法定位 `ImePosition`（IMM32）、整体透明度图层 `Draw.beginOpacity` | 2026-11-04 游戏内验收通过：物品/数量/耐久/tooltip、裁剪、滚动不抖、弹窗层序与动画同步、中文输入、候选窗位于输入框下方。已知小问题：拼音预览比文字低约 3 GUI 像素（输入法自身窗口的内边距，影响不大） |
| 3 | **逐页“抽出 + 迁移”一步完成**（原计划先纯结构拆分、再逐页重画，每页要改两遍，已调整）：`ShopScreen` 加页面注册表，已迁移页面由 `ShopPage` 用 Skija 绘制，未迁移页面继续走旧代码，新旧并存、每步都可玩。页面通过 `ShopHost.action` 复用旧的动作分发，行为不变。AtomLib 不可用时自动回退旧绘制 | 每页一次提交，游戏内验收 |
| 3.1 ✅ | 页面机制（`ShopPage` / `ShopHost` / `PageFrame`）+ 货币兑换 `ExchangePage`：展开/收起动画、像素级平滑滚动（跨界面重建保留位置）、行内按钮、物品 tooltip | 2026-11-04 游戏内验收通过 |
| 3.2 ✅ | 出货箱 `ShippingPage`（等级分段指示、每日额度进度条） | 2026-11-04 游戏内验收通过 |
| 3.3 ✅ | 邮箱 `MailPage` + `MailFormat`（展示规则提取共用）+ `StyledText`（§ 颜色代码排版）；列表/正文/附件三区独立滚动 | 2026-11-04 游戏内验收通过 |
| 3.4 ✅ | 每日收购 `BuybackPage`、拍卖会 `AuctionPage`、我的摊位 `StallPage`、玩家求购 `DemandPage`、交易/转账 `PlayerPickerPage`；公共 `PageKit`；ShopScreen 只把内容区点击交给页面 | 2026-11-04 游戏内验收通过（交易/转账需联机才能看到玩家列表，待联机时确认） |
| 3.5 | 每日商店 `DailyPage`（卡牌 / 长条两种布局、翻牌动画：横向翻转 + 半程换面 + 压暗 + 扫光，物品图标随牌压缩）、签到 `SignInPage` + 共用月历 `SignInCalendar`（补签、日程色条与悬停详情、里程进度条）、购物城 `MallPage`（Skija 搜索框、排序 / 分类下拉、收藏星标、最近购买、翻页）、管理面板 `AdminPage`（模块下拉、条目列表左键编辑 / 右键删除、出货箱错误规则标红、摊位 / 求购 / 签到子页、模块设置按钮）。过滤 / 排序 / 模组名等仍复用 ShopScreen 的同一份实现；管理面板通过 `ShopHost` 读取当前模块与页码 | 编译通过；**待游戏内验收** |
| 4 | 外壳（侧边栏/标题栏/资料块）换 Skija（所有页面迁移完之后） | |
| 5 | （已并入阶段 3） | |
| 6 | 次级 Screen 迁移（确认框 → `ConfirmDialog`，表单 → `SkiaTextField`） | |
| 7 | 删除旧 `IOStyle` 绘制代码、`IOSButton`、`IOEditBox` 等 | |

## 7. 需要你拍板的问题
1. 路线：A（beta3 + AtomChat 组件，推荐）还是 B（0.3.0 + Yoga 控件）？
2. 视觉基准：现有代码的 iOS 日/夜风格、README 的扁平直角风格，还是直接用 AtomChat 外观（`UiTokens` 默认值）？
3. 字体：原版像素字体 vs Noto Sans SC 矢量字体？
4. 非 Windows 玩家：只支持 Windows，还是保留旧 UI 回退？
