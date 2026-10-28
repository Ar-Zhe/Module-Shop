<#
.SYNOPSIS
  安全部署：检查游戏是否在运行，**绝不在游戏运行时覆盖客户端 jar**（这是历次"界面打不开"的根因）。

.DESCRIPTION
  自动取 build\libs 下最新的 module_shop-*.jar，覆盖服务端/发布目录/客户端。
  检测到游戏在跑时，客户端只放到 mods-pending，等关掉后再跑一次。
  用法： pwsh tools\deploy.ps1
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$jarFile = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'module_shop-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jarFile) { Write-Host "先构建： .\gradlew.bat build" -ForegroundColor Red; exit 1 }
$jar = $jarFile.FullName
$jarName = $jarFile.Name
$sha = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
Write-Host "jar: $jarName  SHA-256=$sha"

$serverDir = 'H:\DeepSeek\MinecraftServer-1.21.1-NeoForge\mods'
$deployDir = 'H:\DeepSeek\ServerShopMod\server-deploy\mods'
$jarDir    = 'H:\DeepSeek\ServerShopMod\jar'
$clientDir = 'C:\Users\Ar_zhe\AppData\Roaming\red.ghs.axolotl\profiles\商店模组测试 1.0.0\mods'
$pendingDir = 'C:\Users\Ar_zhe\AppData\Roaming\red.ghs.axolotl\profiles\商店模组测试 1.0.0\mods-pending'

# 服务端三处：带 [模块商店] 前缀（测试服/发布包），jar 目录不带前缀
$serverTargets = @(
    [pscustomobject]@{ Dir = $serverDir; Name = "[模块商店]$jarName" },
    [pscustomobject]@{ Dir = $deployDir; Name = "[模块商店]$jarName" },
    [pscustomobject]@{ Dir = $jarDir;    Name = $jarName }
)
foreach ($t in $serverTargets) {
    New-Item -ItemType Directory -Force -Path $t.Dir | Out-Null
    # 先删掉同目录里的旧版本 jar（避免同一 modId 放两个 jar）
    [System.IO.Directory]::GetFiles($t.Dir) |
        Where-Object { $_ -match 'module_shop-[\d\.]+\.jar$' -and (Split-Path $_ -Leaf) -ne $t.Name } |
        ForEach-Object { [System.IO.File]::Delete($_); Write-Host "  删除旧版: $(Split-Path $_ -Leaf)" }
    [System.IO.File]::Copy($jar, (Join-Path $t.Dir $t.Name), $true)
    Write-Host "  已更新: $(Join-Path $t.Dir $t.Name)"
}
Set-Content -LiteralPath 'H:\DeepSeek\ServerShopMod\server-deploy\SHA256校验.txt' -Encoding UTF8 `
    -Value "Module-Shop $jarName  SHA-256：`r`n$sha"

# 客户端：游戏在跑就绝不覆盖
$running = Get-Process javaw -ErrorAction SilentlyContinue |
    Where-Object { $_.StartTime -gt (Get-Date).AddHours(-12) }
if ($running) {
    New-Item -ItemType Directory -Force -Path $pendingDir | Out-Null
    [System.IO.File]::Copy($jar, (Join-Path $pendingDir $jarName), $true)
    Write-Host ""
    Write-Host "!! 检测到游戏正在运行，**没有覆盖客户端 jar**（热替换会让发放物品/添加条目/上架等界面报 NoClassDefFoundError）" -ForegroundColor Yellow
    Write-Host "   已放到: $pendingDir" -ForegroundColor Yellow
    Write-Host "   请先完全关闭游戏，再执行： pwsh tools\deploy.ps1" -ForegroundColor Yellow
    exit 2
}
[System.IO.Directory]::GetFiles($clientDir) |
    Where-Object { $_ -match 'module_shop-[\d\.]+\.jar$' -and (Split-Path $_ -Leaf) -ne $jarName } |
    ForEach-Object { [System.IO.File]::Delete($_); Write-Host "  删除客户端旧版: $(Split-Path $_ -Leaf)" }
[System.IO.File]::Copy($jar, (Join-Path $clientDir $jarName), $true)
Write-Host "  客户端已更新（游戏未运行）: $(Join-Path $clientDir $jarName)"

# 自检：四处哈希一致
$all = @((Join-Path $serverDir "[模块商店]$jarName"), (Join-Path $deployDir "[模块商店]$jarName"),
         (Join-Path $jarDir $jarName), (Join-Path $clientDir $jarName))
$hashes = $all | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash } | Sort-Object -Unique
if ($hashes.Count -eq 1) { Write-Host "四处哈希一致 ✔" -ForegroundColor Green }
else { Write-Host "!! 哈希不一致，请检查" -ForegroundColor Red; exit 3 }