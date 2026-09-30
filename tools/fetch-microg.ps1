# ---------------------------------------------------------------------------
# fetch-microg.ps1 —— Windows 版 microG 组件拉取脚本
#
# 用法（在仓库根目录执行）：
#   powershell -ExecutionPolicy Bypass -File tools\fetch-microg.ps1
#   powershell -ExecutionPolicy Bypass -File tools\fetch-microg.ps1 -Huawei
#   powershell -ExecutionPolicy Bypass -File tools\fetch-microg.ps1 -Clean
# ---------------------------------------------------------------------------
param(
    [switch]$Huawei,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$Tag = "v0.3.16.252432"
$Gh  = "https://github.com/microg/GmsCore/releases/download"
$Root = Split-Path -Parent $PSScriptRoot
$Dest = Join-Path $Root "gms\src\main\assets\microg"

if ($Clean) {
    if (Test-Path $Dest) {
        Get-ChildItem -Path $Dest -Filter *.apk | Remove-Item -Force
    }
    Write-Host "已清空 $Dest"
    exit 0
}

New-Item -ItemType Directory -Force -Path $Dest | Out-Null

$Mirrors = @("", "https://ghfast.top/", "https://gh-proxy.com/")

function Fetch-Asset {
    param([string]$RemoteName, [string]$LocalName)

    $out = Join-Path $Dest $LocalName
    if ((Test-Path $out) -and ((Get-Item $out).Length -gt 0)) {
        Write-Host "跳过（已存在）: $LocalName"
        return
    }

    foreach ($prefix in $Mirrors) {
        $url = "$prefix$Gh/$Tag/$RemoteName"
        Write-Host "尝试: $url"
        try {
            $part = "$out.part"
            Invoke-WebRequest -Uri $url -OutFile $part -TimeoutSec 1800 -UseBasicParsing
            $bytes = [System.IO.File]::ReadAllBytes($part)
            if ($bytes.Length -gt 4 -and $bytes[0] -eq 0x50 -and $bytes[1] -eq 0x4B) {
                Move-Item -Force $part $out
                $mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
                Write-Host "  OK  $LocalName ($mb MB)"
                return
            }
            Write-Host "  x 内容不是 ZIP，换镜像"
            Remove-Item -Force $part -ErrorAction SilentlyContinue
        } catch {
            Write-Host "  x 失败：$($_.Exception.Message)"
            Remove-Item -Force "$out.part" -ErrorAction SilentlyContinue
        }
    }
    Write-Host "  !! $RemoteName 全部镜像失败，请手动下载后放到 $Dest"
}

if ($Huawei) {
    Fetch-Asset "com.google.android.gms-252432032-hw.apk" "com.google.android.gms.apk"
    Fetch-Asset "com.android.vending-84022632-hw.apk"   "com.android.vending.apk"
} else {
    Fetch-Asset "com.google.android.gms-252432032.apk" "com.google.android.gms.apk"
    Fetch-Asset "com.android.vending-84022632.apk"     "com.android.vending.apk"
}

Write-Host ""
Write-Host "完成。可选组件（GsfProxy）需从各自仓库手动下载。"
Write-Host "assets 中的 APK 已在 .gitignore 中排除。"
