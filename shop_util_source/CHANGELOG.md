# shop_util 更新日志

> 版本号固定为 **1.1.1**，以下为该版本历次构建的内容，按时间倒序排列。
> 每次替换 mods 目录里的 `shop_util-1.1.1.jar` 即生效，配置文件自动兼容、不删旧数据。

---

## 2026-11-10 构建（61317 字节）

### 新增
- **标签页记忆开关**：在 Configd 里新增「标签页记忆」分组，配置项 `tab_memory_enabled`（默认开启）。关闭后彻底不读写 `config/shop_tab_memory/tab.txt`，不影响本体行为。

### 兼容性
- 验证与 `module_shop-1.0.5` 完全兼容：所有 mixin 目标方法签名、`canAdmin` 字段、`ShopSavedData.mallPool` 结构、`MallItem` 字段与构造方法均未变化，无需改 jar。

---

## 2026-11-09 构建（61016 字节）

### 新增功能
- **合并 `shop_tab_memory`**：关闭商店界面时把当前标签页 id 写入 `config/shop_tab_memory/tab.txt`，下次打开在 `init()` 末尾自动 `selectTab` 恢复。
  - 恢复前做双重校验：① 标签页必须在本体 `pages` 注册表内；② 必须在 `visibleTabs()` 返回的当前可见列表内（避免把玩家带到无权查看的管理员页、或前置模组未安装时才出现的扩展页）。
  - 新增标签页不影响记忆：按 id 字符串切换，不依赖序号。

### 加固与降冲突
- `mixins.json` 的 `required` 由 `true` 改为 `false`，目标对不上时跳过而非启动失败。
- 全部 mixin 改为字符串 `targets` + `remap=false`，编译后全 jar 零条 `cn/autoforged/**` 常量池硬引用。
- 去掉 mixin `priority: 1000`，不抢占优先级。
- `ClientShopDataMixin` 用 `@Shadow` 原生读取 `canAdmin`，替代反射，既可靠又不产生常量池引用。
- `render` / `mouseClicked` / `mouseDragged` / `mouseReleased` 四个注入点全部包 `try/catch(Throwable)`，自身绘制异常不顺着商店渲染栈崩游戏。
- 整个 jar 仅保留一个 `@Mod` 类（`ShopUtilMod`），消除悬空入口点。

### 修复
- **崩溃根因修复**：第一版 jar 拼装时混入了带 `@Mod("shop_tab_memory")` 的类但 `mods.toml` 未声明，触发 NeoForge `dangling_entrypoint`，mod 容器初始化失败，连带把 Quark 的静态初始化推迟到 `RenderBuffers` 阶段、抛出 `Where is minecraft???!`。改为源码级单 mixin 合并后解决。

### 其他
- 整合包内默认密码保留 `200703`，jar 内默认值为 `000000`（新整合包首次启动生效）。

---

## 2026-11-08 构建（三模组合并初版）

由三个独立附属模组合并为一个 `shop_util-1.1.1.jar`，整合包内只保留这一个 jar。

### 功能模块

**商店守卫（密码门）**
- 商店界面左上角画一个锁定按钮，与商店同开同关。
- 短按切换解锁/锁定；长按可拖动位置，位置自动保存到 `config/shop_util/guard_button_position.txt`。
- 未解锁时 `canAdmin` 强制为 false，玩家无法进入管理员配置页。
- 解锁密码从 `config/shop_util/admins.txt` 与 toml 读取。
- 解锁成功后自动关闭商店界面刷新 UI；聊天栏提示。
- 手动锁定时同样自动关闭商店界面刷新 UI，无需再次输密码。

**商店设置跨存档同步**
- 新存档创建时自动把 `config/shop_util/` 下的商店配置注入新存档。
- P 键打开可视化同步界面：把当前存档的配置推回主 config 目录，覆盖预设。
- 排除字段可配置（`mallPool,mallRecent,accounts,signIns,auctions,demands,apiRequests` 等默认不覆盖）。

**购物城定价注入**
- 读取 `config/shop_util/pricing/default.csv`，按物品 id 批量改写 `ShopSavedData.mallPool` 内的价格、稀有度、库存、排序。
- 内置 `default_pricing.csv` 与 `default_reference.dat`，新整合包自动释放；已存在的配置文件不覆盖。
- 定价方案与参考配置方案分两个抽屉，各自支持导入外部文件、导出当前方案、切换当前方案。
- 已存档也可手动应用定价（P 界面选择方案后立即生效）。
- 下次上线仍保留上次选择的方案（持久化记录）。

### 可配置项（Configd 识别）
```toml
[商店守卫（密码门）]
    guard_enabled = true
    password = "000000"

[商店设置同步]
    sync_enabled = true
    sync_exclude = "mallPool,mallRecent,accounts,signIns,auctions,demands,apiRequests"

[购物城定价注入]
    pricing_enabled = true
    pricing_file = "default.csv"
    sync_profile = "default.dat"

[界面风格]
    ui_style = "OPAQUE"   # OPAQUE=不透明深色背景 / SIMPLE=简约透明风
```

### 界面
- 所有自绘按钮/输入框/列表画在最高层，避开 ModernUI/SmoothGui 视差模糊遮挡。
- SIMPLE 风格：无背景、纯文字与边框，贴近商店本体视觉。
- 导入界面参考 Iris 光影包选择器：已导入文件以列表形式隔离展示，可手动切换当前方案、一键重置为空、导出到文件夹并聊天栏提示路径。

---

## 文件结构（整合包 config 目录）

```
config/
├── shop_util-common.toml          # 主配置（Configd 直接修改）
├── shop_util/
│   ├── admins.txt                  # 管理员白名单
│   ├── guard_button_position.txt   # 锁定按钮位置
│   ├── pricing/
│   │   └── default.csv             # 定价表
│   └── profiles/
│       ├── default.dat             # 内置参考配置（不自动应用）
│       └── export_*.dat            # 玩家手动导出的方案
└── shop_tab_memory/
    └── tab.txt                     # 标签页记忆（由标签页记忆开关控制）
```

---

## 已知边界（不崩，静默降级）

- 本体若改标签页 id、删除某页、重构 `pages`/`selectTab` 结构：标签页记忆静默失效，回退默认首页，游戏正常。
- 本体若未安装前置模块（如红包页）：对应标签页不会被恢复，`visibleTabs()` 校验会挡住。
- `module_shop` 本体的「关闭商店再打开回第一页」是其自身 `init()` 可见性校验误判导致，非本模组 bug；本模组通过 `tab.txt` 落盘 + TAIL `selectTab` 绕过该问题。
