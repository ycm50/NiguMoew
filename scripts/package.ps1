<#
.SYNOPSIS
  把构建产物打包成"解压即玩"的 Windows 发行包。

.DESCRIPTION
  - 产出 dist\nigu-meow-<version>-win64.zip
  - 内含：引擎 + MinGW 运行时 DLL + 前端 jar + **随包 JRE**（用户无需自己装 Java）
  - 同时产出 SHA256SUMS.txt
  - 本脚本与 .github/workflows/release.yml 的打包步骤逻辑一致，本地跑通即可确信 CI 也能跑通。

.PARAMETER Version
  版本号（X.Y.Z）。必填。

.PARAMETER SkipJre
  跳过随包 JRE（体积更小，但用户需自备 Java 17）。

.EXAMPLE
  pwsh -File scripts\package.ps1 -Version 1.0.0
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$SkipJre
)

$ErrorActionPreference = 'Stop'

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "版本号必须是 X.Y.Z 形式，收到：$Version"
}

# 仓库根（基于脚本自身位置，与调用者当前目录无关）
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$distRoot = Join-Path $repoRoot 'dist'
$pkgName = "nigu-meow-$Version"
$stage = Join-Path $distRoot $pkgName

Write-Host "=== 打包 拟股喵喵 v$Version ===" -ForegroundColor Cyan
Write-Host "仓库根 : $repoRoot"

# ---- 1. 检查必需产物 ----
$exe = Join-Path $repoRoot 'build\engine\trade_sim.exe'
$jar = Join-Path $repoRoot 'build\frontend\nigu-meow.jar'
foreach ($f in @($exe, $jar)) {
    if (-not (Test-Path $f)) {
        throw "缺少构建产物：$f`n请先运行 scripts\build.cmd"
    }
}

# ---- 2. 准备暂存目录 ----
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
foreach ($d in @('engine', 'frontend', 'runtime')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $stage $d) | Out-Null
}

# ---- 3. 引擎 + MinGW 运行时 DLL ----
Copy-Item $exe (Join-Path $stage 'engine') -Force
Write-Host "[OK] engine\trade_sim.exe"

$gxx = (Get-Command g++ -ErrorAction SilentlyContinue).Source
$mingwBin = if ($gxx) { Split-Path -Parent $gxx } else { $null }
$dlls = @('libgcc_s_seh-1.dll', 'libstdc++-6.dll', 'libwinpthread-1.dll')
$copied = 0
if ($mingwBin) {
    foreach ($dll in $dlls) {
        $src = Join-Path $mingwBin $dll
        if (Test-Path $src) {
            Copy-Item $src (Join-Path $stage 'engine') -Force
            Write-Host "[OK] 运行时 $dll"
            $copied++
        }
    }
}
if ($copied -eq 0) {
    Write-Warning "未复制到 MinGW 运行时 DLL；若目标机器没装 MSYS2，引擎可能无法启动。"
}

# ---- 4. 前端 jar ----
Copy-Item $jar (Join-Path $stage 'frontend') -Force
Write-Host "[OK] frontend\nigu-meow.jar"

# ---- 5. 随包 JRE ----
if (-not $SkipJre) {
    $javaHome = $env:JAVA_HOME
    if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
        $cand = 'A:\jdk-17.0.12'
        if (Test-Path (Join-Path $cand "bin\java.exe")) { $javaHome = $cand }
    }
    if ($javaHome -and (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
        $jreTarget = Join-Path $stage 'runtime\jre'
        Copy-Item $javaHome $jreTarget -Recurse -Force
        # 发行包不需要开发工具，删掉可省一半体积
        foreach ($dev in @('bin\javac.exe', 'include', 'jmods', 'lib\src.zip')) {
            $p = Join-Path $jreTarget $dev
            if (Test-Path $p) { Remove-Item $p -Recurse -Force -ErrorAction SilentlyContinue }
        }
        Write-Host "[OK] runtime\jre (随包 JRE)"
    } else {
        Write-Warning "未找到 JDK，跳过随包 JRE（用户需自备 Java 17）。"
    }
} else {
    Write-Host "[--] 按参数跳过随包 JRE"
}

# ---- 6. 启动脚本（发行版专用：优先使用随包 JRE）----
$distLauncher = Join-Path $repoRoot 'scripts\play-dist.bat'
if (Test-Path $distLauncher) {
    Copy-Item $distLauncher (Join-Path $stage 'play.bat') -Force
    Write-Host "[OK] play.bat (发行版专用)"
} else {
    Write-Warning "缺少 scripts\play-dist.bat，将退回开发版启动脚本。"
    $dev = Join-Path $repoRoot 'play.bat'
    if (Test-Path $dev) { Copy-Item $dev $stage -Force }
}

# ---- 6b. 文档 ----
foreach ($f in @('README.md', 'USAGE.md', 'LICENSE')) {
    $src = Join-Path $repoRoot $f
    if (Test-Path $src) { Copy-Item $src $stage -Force; Write-Host "[OK] $f" }
}

# ---- 7. 版本信息 ----
@(
  "拟股喵喵 v$Version",
  "============================================",
  "纯模拟交易游戏，不涉及任何真实资金。",
  "",
  "快速开始：双击 play.bat",
  "详细说明：USAGE.md",
  "============================================"
) | Out-File -Encoding utf8 (Join-Path $stage "VERSION.txt")

# ---- 8. 压缩 + 校验和 ----
$zip = Join-Path $distRoot "$pkgName-win64.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash
$hash + "  $pkgName-win64.zip" | Out-File -Encoding ascii (Join-Path $distRoot "SHA256SUMS.txt")

$sizeMb = [math]::Round((Get-Item $zip).Length / 1MB, 2)
Write-Host ""
Write-Host "=== 完成 ===" -ForegroundColor Green
Write-Host "文件   : dist\$pkgName-win64.zip"
Write-Host "大小   : $sizeMb MB"
Write-Host "SHA256 : $hash"