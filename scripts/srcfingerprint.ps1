# ============================================================
#  engine/src 源码指纹（权威算法，任何人可复现）
#  算法：engine/src 下 *.hpp + *.cpp 按【文件名升序】
#        → 每行 "文件名:小写sha256"
#        → 以 LF 连接、无尾换行 → UTF-8 bytes → 整体 SHA256
#  已用 PowerShell 与 Node 两套独立实现交叉验证一致。
#  ⚠ 路径基于脚本自身位置解析，与调用者的当前目录无关。
# ============================================================
$ErrorActionPreference = 'Stop'

# 脚本所在目录 -> 仓库根
# 注意：Windows PowerShell 5.1 在 `-File` 调用下 $PSScriptRoot 可能为空，
# 因此这里用 $MyInvocation.MyCommand.Path 兜底，保证与调用者当前目录无关。
$selfPath = $MyInvocation.MyCommand.Path
if (-not $selfPath) { $selfPath = $PSCommandPath }
if (-not $selfPath) { $selfPath = $MyInvocation.MyCommand.Definition }
if (-not $selfPath) {
  Write-Error '无法确定脚本自身路径'
  exit 2
}
$repoRoot = Split-Path -Parent (Split-Path -Parent $selfPath)
$srcDir = Join-Path $repoRoot 'engine\src'

$files = @(Get-ChildItem -Path $srcDir -File | Where-Object { $_.Extension -eq '.hpp' -or $_.Extension -eq '.cpp' } | Sort-Object Name)
if (-not $files -or $files.Count -eq 0) {
  Write-Error "找不到源码：$srcDir"
  exit 2
}

# 用 .NET 直接算 SHA256（不依赖 Get-FileHash，兼容受限语言模式 / 旧版 PowerShell）
function Get-Sha256Hex([byte[]]$data) {
  $s = [System.Security.Cryptography.SHA256]::Create()
  $hash = $s.ComputeHash($data)
  $s.Dispose()
  return (($hash) | ForEach-Object { $_.ToString('x2') }) -join ''
}

$sb = New-Object System.Text.StringBuilder
$first = $true
foreach ($f in $files) {
  $h = Get-Sha256Hex ([System.IO.File]::ReadAllBytes($f.FullName))
  if (-not $first) { [void]$sb.Append("`n") }
  [void]$sb.Append($f.Name + ':' + $h)
  $first = $false
}

$bytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
$fp = Get-Sha256Hex $bytes

Write-Host "FILES=$($files.Count)"
Write-Host "SOURCE_FINGERPRINT=$fp"
Write-Host "LEN=$($bytes.Length)"
