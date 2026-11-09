#Requires -Version 5.1
<#
  静态自检：本机没有 JDK / Android SDK，编译验证交给 CI。
  这里把“编译前一定能查出来的错”全部查掉：XML/JSON/JS 语法、资源引用、组件注册、包名路径。
  注意：全部文件按 UTF-8 显式读取（PS 5.1 的 Get-Content 会按 ANSI 解码中文资源）。
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'android/app/src/main'
$res = Join-Path $src 'res'
$java = Join-Path $src 'java'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function ReadText([string]$p) { [System.IO.File]::ReadAllText($p, $utf8) }

$fail = New-Object System.Collections.ArrayList
function Ok([string]$m) { Write-Host "  OK   $m" -ForegroundColor Green }
function Bad([string]$m) { [void]$fail.Add($m); Write-Host "  FAIL $m" -ForegroundColor Red }
function Head([string]$m) { Write-Host "`n== $m ==" -ForegroundColor Cyan }

Head '1. 必需文件'
$required = @(
    'config/app-config.json',
    'android/settings.gradle.kts',
    'android/build.gradle.kts',
    'android/gradle.properties',
    'android/app/build.gradle.kts',
    'android/app/src/main/AndroidManifest.xml',
    'android/app/src/main/assets/autopilot.js',
    'README.md'
)
foreach ($f in $required) {
    if (Test-Path (Join-Path $root $f)) { Ok $f } else { Bad "缺少 $f" }
}

Head '2. XML 可解析'
Get-ChildItem $src -Recurse -Filter *.xml | ForEach-Object {
    try {
        [void][xml](ReadText $_.FullName)
        Ok $_.Name
    } catch {
        Bad "$($_.Name): $($_.Exception.Message)"
    }
}

Head '3. JSON 可解析'
$cfgPath = Join-Path $root 'config/app-config.json'
try {
    $cfg = ReadText $cfgPath | ConvertFrom-Json
    Ok "app-config.json (events=$($cfg.schedule.events.Count) items=$($cfg.items.Count))"
} catch {
    Bad "app-config.json : $($_.Exception.Message)"
}

$assetsCfg = Join-Path $src 'assets/app-config.json'
if (Test-Path $assetsCfg) {
    $a = (ReadText $assetsCfg) -replace '\s+', ''
    $b = (ReadText $cfgPath) -replace '\s+', ''
    if ($a -eq $b) { Ok 'assets/app-config.json 与 config 同步' }
    else { Bad 'assets/app-config.json 与 config 不一致' }
} else {
    Write-Host '  SKIP assets/app-config.json 尚未生成（Gradle preBuild 会复制）' -ForegroundColor DarkYellow
}

Head '4. JS 语法'
$jsFiles = @()
$jsFiles += Get-ChildItem (Join-Path $src 'assets') -Filter *.js -ErrorAction SilentlyContinue
$jsFiles += Get-ChildItem (Join-Path $root 'sim') -Recurse -Include *.mjs, *.js -ErrorAction SilentlyContinue
foreach ($f in $jsFiles) {
    $out = & node --check $f.FullName 2>&1
    if ($LASTEXITCODE -eq 0) { Ok $f.Name } else { Bad "$($f.Name): $out" }
}

Head '5. Manifest 关键项'
$mfRaw = ReadText (Join-Path $src 'AndroidManifest.xml')
$must = @(
    'android.permission.INTERNET',
    'android.permission.WAKE_LOCK',
    'android.permission.SCHEDULE_EXACT_ALARM',
    'android.permission.USE_EXACT_ALARM',
    'android.permission.USE_FULL_SCREEN_INTENT',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
    'android.permission.RECEIVE_BOOT_COMPLETED',
    '.ui.MainActivity',
    '.ui.SniperActivity',
    '.ui.LoginActivity',
    '.ui.LogActivity',
    '.core.SniperService',
    '.core.PrewarmReceiver',
    '.core.BootReceiver',
    'foregroundServiceType="specialUse"'
)
foreach ($m in $must) {
    if ($mfRaw.Contains($m)) { Ok $m } else { Bad "Manifest 缺少 $m" }
}

Head '6. Kotlin 包名与目录一致'
$pkgBad = 0
Get-ChildItem $java -Recurse -Filter *.kt | ForEach-Object {
    $rel = $_.DirectoryName.Substring($java.Length).TrimStart('\', '/') -replace '\\', '/'
    $expect = 'package ' + ($rel -replace '/', '.')
    $first = (Get-Content $_.FullName -TotalCount 5 | Where-Object { $_ -match '^package ' } | Select-Object -First 1)
    if ($null -eq $first) { Bad "$($_.Name) 没有 package 声明"; $pkgBad++ }
    elseif ($first.Trim() -ne $expect) { Bad "$($_.Name) 声明 $($first.Trim()) 应为 $expect"; $pkgBad++ }
}
if ($pkgBad -eq 0) { Ok '全部 .kt 包名正确' }

Head '7. 资源引用（R.id / R.string / R.layout / R.drawable）'
$ktAll = (Get-ChildItem $java -Recurse -Filter *.kt | ForEach-Object { ReadText $_.FullName }) -join "`n"

$xmlIds = @()
Get-ChildItem $res -Recurse -Filter *.xml | ForEach-Object {
    $txt = ReadText $_.FullName
    [regex]::Matches($txt, '@\+id/([A-Za-z0-9_]+)') | ForEach-Object { $xmlIds += $_.Groups[1].Value }
}
$xmlIds = $xmlIds | Sort-Object -Unique

[regex]::Matches($ktAll, 'R\.id\.([A-Za-z0-9_]+)') | ForEach-Object {
    $id = $_.Groups[1].Value
    if ($xmlIds -notcontains $id) { Bad "R.id.$id 在资源中不存在" }
}
Ok "R.id 引用 $(([regex]::Matches($ktAll, 'R\.id\.').Count)) 处"

$strXml = [xml](ReadText (Join-Path $res 'values/strings.xml'))
$strNames = @($strXml.resources.string | ForEach-Object { $_.name })
[regex]::Matches($ktAll, 'R\.string\.([A-Za-z0-9_]+)') | ForEach-Object {
    $n = $_.Groups[1].Value
    if ($strNames -notcontains $n) { Bad "R.string.$n 不存在" }
}
Ok "R.string 引用 $(([regex]::Matches($ktAll, 'R\.string\.').Count)) 处"

$layouts = (Get-ChildItem (Join-Path $res 'layout') -Filter *.xml | ForEach-Object { $_.BaseName })
[regex]::Matches($ktAll, 'R\.layout\.([A-Za-z0-9_]+)') | ForEach-Object {
    $n = $_.Groups[1].Value
    if ($layouts -notcontains $n) { Bad "R.layout.$n 不存在" }
}
Ok "R.layout 引用 $(([regex]::Matches($ktAll, 'R\.layout\.').Count)) 处"

$drawables = (Get-ChildItem (Join-Path $res 'drawable') -Filter *.xml | ForEach-Object { $_.BaseName })
[regex]::Matches($ktAll, 'R\.drawable\.([A-Za-z0-9_]+)') | ForEach-Object {
    $n = $_.Groups[1].Value
    if ($drawables -notcontains $n) { Bad "R.drawable.$n 不存在" }
}
Ok "R.drawable 引用 $(([regex]::Matches($ktAll, 'R\.drawable\.').Count)) 处"

Head '8. JS 桥契约'
$ap = ReadText (Join-Path $src 'assets/autopilot.js')
if ($ap.Contains('__LCS_NATIVE')) { Ok 'autopilot.js 使用 __LCS_NATIVE' } else { Bad 'autopilot.js 未使用 __LCS_NATIVE' }
if ($ktAll.Contains('"__LCS_NATIVE"')) { Ok 'Kotlin 注册 __LCS_NATIVE' } else { Bad 'Kotlin 未注册 __LCS_NATIVE' }
foreach ($api in @('NS.init', 'NS.arm', 'NS.stop', 'NS.probe')) {
    if ($ap.Contains($api)) { Ok $api } else { Bad "autopilot.js 缺少 $api" }
}

Head '9. 配置契约'
if ($cfg.tz -ne 'Asia/Shanghai') { Bad "tz 必须固定 Asia/Shanghai，当前 $($cfg.tz)" } else { Ok 'tz=Asia/Shanghai' }
foreach ($e in $cfg.schedule.events) {
    if ($e.time -notmatch '^\d{2}:\d{2}:\d{2}$') { Bad "事件 $($e.id) time 格式错误" }
    if ($e.dow -lt 1 -or $e.dow -gt 7) { Bad "事件 $($e.id) dow 越界" }
}
$ids = $cfg.schedule.events | ForEach-Object { $_.id }
if (($ids | Sort-Object -Unique).Count -ne $ids.Count) { Bad '事件 id 有重复' } else { Ok "事件 $($ids.Count) 个，id 唯一" }
foreach ($it in $cfg.items) {
    if ([string]::IsNullOrWhiteSpace($it.sku)) { Bad "商品 $($it.alias) 缺 sku" }
    if ($it.url -notmatch 'item\.m\.jd\.com') { Bad "商品 $($it.alias) url 不是 m.jd.com" }
}
Ok "商品 $($cfg.items.Count) 个"

Head '10. Node 仿真测试'
Push-Location $root
try {
    $out = & node --test 'sim/schedule.test.mjs' 'sim/autopilot.test.mjs' 2>&1
    if ($LASTEXITCODE -eq 0) {
        $passLine = ($out | Select-String -Pattern 'pass \d+' | Select-Object -Last 1).Line
        Ok "sim 测试通过（$passLine）"
    } else {
        Bad 'sim 测试失败'
        $out | Select-Object -Last 15 | ForEach-Object { Write-Host "    $_" }
    }
} finally {
    Pop-Location
}

Write-Host ''
if ($fail.Count -gt 0) {
    Write-Host "共 $($fail.Count) 项失败" -ForegroundColor Red
    $fail | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host '全部通过' -ForegroundColor Green
exit 0
