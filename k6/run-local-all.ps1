# =====================================================================
# run-local-all.ps1 : 로컬 재측정 전체를 한 번에 (각 3회) — [추가]2026.11.22권성애
#   실서버 재측정과 같은 순서 · 같은 조건 → 결과를 나란히 비교
#
# 순서
#   0 예열(풀 20)                            → local\00-warmup
#   1 01 동시 예약 500명 → 100석  × 3        → local\01-spike-pool20
#   2 02 RATE 50  (풀 20)          × 3        → local\02-rate50-pool20
#   3 02 RATE 100 (풀 20)          × 3        → local\02-rate100-pool20
#   4 02 RATE 100 회차 2 · 3        × 3        → local\02-rate100-rows2-pool20
#   ─ 여기서 멈춤: 인텔리제이에서 K6_POOL=30 으로 바꾸고 재시작 → Enter
#   5 예열(풀 30)                            → local\00-warmup
#   6 02 RATE 100 (풀 30)          × 3        → local\02-rate100-pool30
#   7 03 메인 화면 RATE 200 (초기화 없음) × 3 → local\03-main-rate200-pool30
#
# 실행 (프로젝트 폴더, PowerShell):
#   powershell -ExecutionPolicy Bypass -File k6\run-local-all.ps1
#   powershell -ExecutionPolicy Bypass -File k6\run-local-all.ps1 -From 5     ← 중간(5번)부터 다시
#   powershell -ExecutionPolicy Bypass -File k6\run-local-all.ps1 -DbPass 비밀번호   ← 로컬 root 비밀번호가 있으면
#
# ★ 시작 전 인텔리제이: VM options -Dspring.profiles.active=k6 , 환경변수 K6_POOL=20 → 실행
# ★ 로컬은 CPU 크레딧 제한이 없어 쉬는 시간 90초 (MySQL 뒷정리용). 모든 측정 같은 시간
# =====================================================================
[CmdletBinding()]
param(
    [int]$From = 0,                           # 몇 번 단계부터 할지 (중간에 멈췄을 때)
    [int]$Rounds = 3,                         # 종류마다 몇 번
    [int]$Rest = 90,                          # 측정 뒤 쉬는 초
    [string]$OutRoot = "results_20261122\local",
    [string]$DbPass = "",
    [string]$Mgmt = "http://localhost:9282"
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$one  = "$here\run-local.ps1"

# 지금 앱의 커넥션 풀 최대 크기 (Actuator hikaricp.connections.max)
function PoolMax {
    try { ((Invoke-RestMethod "$Mgmt/actuator/metrics/hikaricp.connections.max").measurements | Where-Object statistic -eq VALUE).value }
    catch { "알 수 없음 (앱이 꺼졌거나 9282 가 안 열림)" }
}

# 풀 크기가 기대값인지 확인 → 아니면 Enter 를 누를 때까지 기다림
function NeedPool([int]$want) {
    while ((PoolMax) -ne $want) {
        Read-Host "지금 풀 = $(PoolMax). 인텔리제이 환경변수 K6_POOL=$want 로 바꾸고 재시작한 뒤 Enter"
    }
    Write-Host "[풀 확인] $want"
}

# 한 종류를 $Rounds 번 (매번 뒤 $Rest 초 쉼). @opt = run-local.ps1 에 넘길 옵션 묶음
function Many([string]$sub, [hashtable]$opt) {
    for ($r = 1; $r -le $Rounds; $r++) {
        try { & $one @opt -Round $r -OutDir "$OutRoot\$sub" -DbPass $DbPass }
        catch { Write-Host "[실패] $sub round$r : $_  → 다음 회차 계속" }
        Start-Sleep -Seconds $Rest
    }
}
function Warm([string]$label) {
    & $one -Rate 50 -Label $label -OutDir "$OutRoot\00-warmup" -DbPass $DbPass
    Start-Sleep -Seconds 60
}

$start = Get-Date
if ($From -le 4) { NeedPool 20 }
if ($From -le 0) { Warm "warmup" }
if ($From -le 1) { Many "01-spike-pool20"          @{ Script = "01-reserve-spike.js"; Label = "pool20" } }
if ($From -le 2) { Many "02-rate50-pool20"         @{ Rate = 50;  Label = "pool20" } }
if ($From -le 3) { Many "02-rate100-pool20"        @{ Rate = 100; Label = "pool20" } }
if ($From -le 4) { Many "02-rate100-rows2-pool20"  @{ Rate = 100; Schedules = "2,3"; Label = "rows2" } }

NeedPool 30                                          # ← 여기서 사람이 풀을 바꿈
if ($From -le 5) { Warm "warmup30" }
if ($From -le 6) { Many "02-rate100-pool30"        @{ Rate = 100; Label = "pool30" } }
if ($From -le 7) { Many "03-main-rate200-pool30"   @{ Script = "03-main-page.js"; Rate = 200; NoReset = $true; Label = "pool30" } }

Write-Host ""
Write-Host "전체 끝. 걸린 시간 $([int]((Get-Date) - $start).TotalMinutes)분, 결과: $here\$OutRoot"
Write-Host "인텔리제이 K6_POOL 은 원래대로 (20) 돌려 두세요."
