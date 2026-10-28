<#
.SYNOPSIS
  界面一致性检查：在构建前把「加模块漏东西」这类问题挡下来。

.DESCRIPTION
  检查四件事：
    1. 每个 TAB_IDS 模块都有 render 分支；
    2. 每个模块都有中英语言键 tab.module_shop.<id>；
    3. 每个 addRegion("action") 都有对应的 onAction 处理（或已知的转发动作）；
    4. 每个 ModMenus 注册项都有客户端 screen 注册。
  用法： pwsh tools\check-ui.ps1
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$screen = Join-Path $root 'src\main\java\cn\autoforged\module_shop\client\screen\ShopScreen.java'
$menus  = Join-Path $root 'src\main\java\cn\autoforged\module_shop\menu\ModMenus.java'
$client = Join-Path $root 'src\main\java\cn\autoforged\module_shop\client\ClientEvents.java'
$zh     = Join-Path $root 'src\main\resources\assets\module_shop\lang\zh_cn.json'
$en     = Join-Path $root 'src\main\resources\assets\module_shop\lang\en_us.json'

$text = [System.IO.File]::ReadAllText($screen, [System.Text.Encoding]::UTF8)
$problems = 0
function Fail($msg) { Write-Host "  [x] $msg" -ForegroundColor Red; $script:problems++ }
function Ok($msg)   { Write-Host "  [v] $msg" -ForegroundColor Green }

# 1) TAB_IDS → render 分支
$m = [regex]::Match($text, 'TAB_IDS = \{([^}]+)\}')
if (-not $m.Success) { Fail '找不到 TAB_IDS'; }
else {
    $ids = [regex]::Matches($m.Groups[1].Value, '"([a-z_]+)"') | ForEach-Object { $_.Groups[1].Value }
    Write-Host "模块数 = $($ids.Count)：$($ids -join ', ')"
    foreach ($id in $ids) {
        if ($text -notmatch ('case "' + [regex]::Escape($id) + '" ->')) { Fail "模块 $id 缺少 render 分支" }
    }
    Ok '所有模块都有 render 分支'
    # 2) 语言键
    $zhJson = Get-Content $zh -Raw -Encoding UTF8 | ConvertFrom-Json
    $enJson = Get-Content $en -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($id in $ids) {
        $key = "tab.module_shop.$id"
        if (-not $zhJson.PSObject.Properties.Name.Contains($key)) { Fail "zh_cn 缺少 $key" }
        if (-not $enJson.PSObject.Properties.Name.Contains($key)) { Fail "en_us 缺少 $key" }
    }
    Ok '模块语言键齐全'
    if ($zhJson.PSObject.Properties.Name.Count -ne $enJson.PSObject.Properties.Name.Count) {
        Fail "中英语言键数量不一致：zh=$($zhJson.PSObject.Properties.Name.Count) en=$($enJson.PSObject.Properties.Name.Count)"
    } else { Ok "语言键数量一致（$($zhJson.PSObject.Properties.Name.Count)）" }
}

# 3) addRegion 的 action 是否都有处理
$actions = [regex]::Matches($text, 'addRegion\([^,]+,\s*[^,]+,\s*[^,]+,\s*[^,]+,\s*"([a-z_0-9]+)"') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
$missing = @()
$serverText = [System.IO.File]::ReadAllText((Join-Path $root 'src\main\java\cn\autoforged\module_shop\shop\ShopManager.java'), [System.Text.Encoding]::UTF8)
foreach ($a in $actions) {
    $pat = 'case "' + [regex]::Escape($a) + '"'
    if ($text -notmatch $pat -and $serverText -notmatch $pat) { $missing += $a }
}
if ($missing.Count -gt 0) {
    Write-Host "未找到 case 的 action（可能是只读提示区域，需人工确认）：" -ForegroundColor Yellow
    $missing | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
} else { Ok "所有 addRegion action 都有处理分支（$($actions.Count) 个）" }

# 4) ModMenus → 客户端 screen
$menuKeys = [regex]::Matches([System.IO.File]::ReadAllText($menus, [System.Text.Encoding]::UTF8), 'MENUS\.register\("([a-z_]+)"') |
    ForEach-Object { $_.Groups[1].Value }
$clientText = [System.IO.File]::ReadAllText($client, [System.Text.Encoding]::UTF8)
$menuConsts = @{ 'deposit'='DEPOSIT'; 'grant'='GRANT'; 'schedule_reward'='SCHEDULE_REWARD'; 'redeem_reward'='REDEEM_REWARD'; 'shipping_bin'='SHIPPING'; 'batch_add'='BATCH_ADD' }
foreach ($k in $menuKeys) {
    $const = $menuConsts[$k]
    if (-not $const) { Write-Host "  ? 未登记的菜单 $k（请在脚本里补映射）" -ForegroundColor Yellow; continue }
    if ($clientText -notmatch ('ModMenus\.' + $const + '\.get\(\)')) { Fail "菜单 $k 没有客户端界面注册（会打不开）" }
}
Ok "菜单-界面注册齐全（$($menuKeys.Count) 个菜单）"

Write-Host ""
if ($problems -eq 0) { Write-Host '结论：通过' -ForegroundColor Green; exit 0 }
else { Write-Host "结论：$problems 个问题" -ForegroundColor Red; exit 1 }