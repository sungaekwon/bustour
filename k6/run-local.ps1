# =====================================================================
# run-local.ps1 : 로컬(내 PC) 부하테스트 한 번 — [추가]2026.11.22권성애
#   run-server.ps1 의 로컬판. 서버 ssh 대신 내 PC 의 MySQL(3307) 과 Actuator(localhost:9282) 를 직접 읽는다
#   (방식은 동시성 테스트 프로젝트 run-all.ps1 과 같음: mysql.exe 로 락 통계, Invoke-RestMethod 로 Actuator)
#
# 하는 일 (순서)
#   ① 앱이 k6 프로필(bustour_k6)인지 확인 → 아니면 멈춤
#   ② bustour_k6 초기화 (db\05_k6_data.sql)
#   ③ 측정 전 값 저장 + 1초 기록 시작 (락 대기 줄 · 풀 pending · active)
#   ④ k6 실행 → 요약 json · 그래프 html
#   ⑤ 기록 멈춤 + 차이 계산 → stats.json   ⑥ 정합성 점검 → check.tsv
#
# 실행 (프로젝트 폴더, PowerShell) — 보통은 run-local-all.ps1 이 대신 부름
#   powershell -ExecutionPolicy Bypass -File k6\run-local.ps1 -Rate 100 -Label pool20 -Round 1 -OutDir results_20261122\local\02-rate100-pool20
#
# ★ 인텔리제이 실행 설정: VM options -Dspring.profiles.active=k6 , 환경변수 K6_POOL=20 (없으면 k6.yml 기본 30)
# ★ 서버판과 다른 점: 503 건수 = 로그 grep 대신 Actuator hikaricp.connections.timeout (커넥션 3초 초과 횟수, 같은 뜻)
# =====================================================================
[CmdletBinding()]                     # 모르는 매개변수가 오면 에러로 알려 줌
param(
    [string]$Script = "02-reserve-pay.js",
    [int]$Rate = 100,                         # 02 · 03 : 초당 요청 수
    [string]$Duration = "30s",
    [string]$Schedules = "",                  # 02 : "2" 또는 "2,3"
    [int]$Vus = 0,                            # 01 : 동시 사용자 (0 = 스크립트 기본 500)
    [int]$Users = 0,                          # 02 : 돌려 쓸 회원 수 (0 = 기본 500)
    [string]$Label = "",                      # 이름 꼬리표 예) pool20, rows2
    [int]$Round = 0,                          # 회차 (0 이면 이름에 안 붙임)
    [string]$Base = "http://localhost:8282",  # 앱
    [string]$Mgmt = "http://localhost:9282",  # Actuator 관리 포트
    [string]$MySql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [int]$DbPort = 3307,
    [string]$DbPass = "",                     # 로컬 root 비밀번호가 있으면 (비우면 비밀번호 없이)
    [string]$OutDir = "results_20261122\local",
    [switch]$NoReset                          # 03 메인 화면처럼 초기화가 필요 없을 때
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path      # k6 폴더
$root = Split-Path -Parent $here                              # 프로젝트 폴더 (db\05_k6_data.sql 이 있는 곳)
$dir  = "$here\$OutDir"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
if ($DbPass -ne "") { $env:MYSQL_PWD = $DbPass }              # mysql.exe 가 읽는 비밀번호 환경변수 (명령줄에 안 남김)

# ---- 측정 이름: local-꼬리표-스크립트-rate-회차-round ----
$scriptName = [System.IO.Path]::GetFileNameWithoutExtension($Script)
$parts = @("local")
if ($Label -ne "") { $parts += $Label }
$parts += $scriptName
if ($Script -notlike "01*") { $parts += "rate$Rate" }
if ($Schedules -ne "") { $parts += "s" + ($Schedules -replace ",", "_") }
if ($Round -gt 0) { $parts += "round$Round" }
$name = $parts -join "-"
$p = "$dir\$name"                                             # 결과 파일 앞부분

# ---- 도우미 ----
function Sql([string]$q, [string]$db = "") {                  # MySQL 한 문장 → 탭 구분 줄들
    $a = @("-u", "root", "-h", "127.0.0.1", "-P", $DbPort, "-N", "-B")
    if ($db -ne "") { $a += $db }
    & $MySql @a -e $q
}
function Metric([string]$n, [string]$stat) {                  # Actuator 숫자 하나 (없으면 0)
    try {
        $m = (Invoke-RestMethod "$Mgmt/actuator/metrics/$n").measurements
        $v = ($m | Where-Object statistic -eq $stat).value
        if ($null -eq $v) { 0 } else { [double]$v }
    } catch { 0 }
}
function Snap {                                               # 누적값 한 번 찍기
    $s = [ordered]@{}
    Sql "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'" | ForEach-Object { $k, $v = $_ -split "`t"; $s[$k] = [long]$v }
    $s.acq_count = Metric "hikaricp.connections.acquire" "COUNT"
    $s.acq_total = Metric "hikaricp.connections.acquire" "TOTAL_TIME"    # 초 단위 합계
    $s.use_count = Metric "hikaricp.connections.usage" "COUNT"
    $s.use_total = Metric "hikaricp.connections.usage" "TOTAL_TIME"
    $s.timeout   = Metric "hikaricp.connections.timeout" "COUNT"         # 커넥션 3초 초과 = 503
    $s
}

Write-Host ""
Write-Host "########## $name ##########"

# ① 안전장치: bustour_k6 를 쓰는 k6 프로필인지 (/ping 응답에 DB 이름이 들어 있음)
$ping = Invoke-RestMethod "$Base/ping" | ConvertTo-Json -Compress
if ($ping -notmatch "bustour_k6") { throw "[중지] 앱이 bustour_k6 가 아닙니다: $ping  → 인텔리제이 VM options -Dspring.profiles.active=k6" }

# ② 초기화 (PowerShell 에는 < 가 없어서 mysql 의 source 명령으로 파일 실행)
if (-not $NoReset) {
    $sqlFile = ("$root\db\05_k6_data.sql" -replace "\\", "/")
    Sql "source $sqlFile" "bustour_k6" | Out-Null
    Write-Host "[reset] 회원 $(Sql 'SELECT COUNT(*) FROM member' 'bustour_k6')명"
}

# ③ 측정 전 값 + 1초 기록 (따로 도는 작업 Start-Job)
$before = Snap
$watch = Start-Job -ArgumentList $MySql, $DbPort, $Mgmt, "$p-watch.tsv" -ScriptBlock {
    param($m, $port, $mg, $f)
    "time`tlock_waiting_now`tpool_pending`tpool_active" | Set-Content -Encoding UTF8 $f
    while ($true) {
        $w = (& $m -u root -h 127.0.0.1 -P $port -N -B -e "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_current_waits'") -split "`t" | Select-Object -Last 1
        try { $pd = ((Invoke-RestMethod "$mg/actuator/metrics/hikaricp.connections.pending").measurements | Where-Object statistic -eq VALUE).value } catch { $pd = "" }
        try { $ac = ((Invoke-RestMethod "$mg/actuator/metrics/hikaricp.connections.active").measurements | Where-Object statistic -eq VALUE).value } catch { $ac = "" }
        "$(Get-Date -Format HH:mm:ss)`t$w`t$pd`t$ac" | Add-Content -Encoding UTF8 $f
        Start-Sleep -Seconds 1
    }
}

# ④ k6
$env:K6_WEB_DASHBOARD = "true"
$env:K6_WEB_DASHBOARD_OPEN = "false"
$env:K6_WEB_DASHBOARD_PERIOD = "1s"
$env:K6_WEB_DASHBOARD_EXPORT = "$p-k6.html"
$k6args = @("run", "-e", "BASE=$Base", "-e", "RATE=$Rate", "-e", "DURATION=$Duration", "--summary-export", "$p-k6.json")
if ($Schedules -ne "") { $k6args += @("-e", "SCHEDULES=$Schedules") }
if ($Vus -gt 0)        { $k6args += @("-e", "VUS=$Vus") }
if ($Users -gt 0)      { $k6args += @("-e", "USERS=$Users") }
$k6args += "$here\$Script"

try {
    & k6 @k6args
}
finally {
    # ⑤ 기록 멈춤 + 차이 → stats.json (서버판과 같은 이름의 항목 → 나란히 비교 가능)
    Stop-Job $watch; Remove-Job $watch
    $after = Snap
    $before | ConvertTo-Json | Set-Content -Encoding UTF8 "$p.before.json"
    $after  | ConvertTo-Json | Set-Content -Encoding UTF8 "$p.after.json"

    $rows = Import-Csv "$p-watch.tsv" -Delimiter "`t"
    function MaxOf($col) { ($rows | ForEach-Object { if ($_.$col -ne "") { [double]$_.$col } else { 0 } } | Measure-Object -Maximum).Maximum }
    $lw = $after.Innodb_row_lock_waits - $before.Innodb_row_lock_waits
    $lt = $after.Innodb_row_lock_time - $before.Innodb_row_lock_time
    $ac = $after.acq_count - $before.acq_count; $at = ($after.acq_total - $before.acq_total) * 1000
    $uc = $after.use_count - $before.use_count; $ut = ($after.use_total - $before.use_total) * 1000
    [ordered]@{
        name              = $name
        lockWaits         = $lw
        lockTimeMs        = $lt
        lockAvgMs         = $(if ($lw -gt 0) { [math]::Round($lt / $lw, 2) } else { 0 })
        lockWaitingNowMax = MaxOf "lock_waiting_now"
        acquireCount      = $ac
        acquireAvgMs      = $(if ($ac -gt 0) { [math]::Round($at / $ac, 2) } else { 0 })
        usageCount        = $uc
        usageAvgMs        = $(if ($uc -gt 0) { [math]::Round($ut / $uc, 2) } else { 0 })
        poolPendingMax    = MaxOf "pool_pending"
        poolActiveMax     = MaxOf "pool_active"
        noConnection503   = $after.timeout - $before.timeout        # 로컬은 Actuator timeout 차이
    } | ConvertTo-Json | Set-Content -Encoding UTF8 "$p-stats.json"
    Get-Content "$p-stats.json"

    # ⑥ 정합성 (회차별 정원 - 남은 자리 = 살아 있는 예약 인원)
    if (-not $NoReset) {
        "schedule`tcapacity`tremaining`tby_counter`tby_rows" | Set-Content -Encoding UTF8 "$p-check.tsv"
        Sql "SELECT s.id, s.capacity, s.remaining, s.capacity - s.remaining, COALESCE(SUM(r.headcount), 0) FROM tour_schedule s LEFT JOIN reservation r ON r.schedule_id = s.id AND r.status IN ('PENDING', 'CONFIRMED') GROUP BY s.id, s.capacity, s.remaining ORDER BY s.id" "bustour_k6" |
            Add-Content -Encoding UTF8 "$p-check.tsv"
        $bad = Import-Csv "$p-check.tsv" -Delimiter "`t" | Where-Object { $_.by_counter -ne $_.by_rows }
        if ($bad) { Write-Host "[check] 정합성 깨짐 — $p-check.tsv 확인" } else { Write-Host "[check] 정합성 OK" }
    }
}
Write-Host "끝. 결과: $p-*"
