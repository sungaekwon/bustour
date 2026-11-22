# =====================================================================
# run-server.ps1 : 실서버(라이트세일) 부하테스트 한 번을 처음부터 끝까지 — [추가]2026.11.22권성애
#   (동시성 테스트 프로젝트의 run-all.ps1 과 같은 생각: 측정 + 수치 기록 + 결과 저장을 한 번에)
#
# 하는 일 (순서)
#   ① 서버: bustour_k6 초기화            (ssh → bash scripts/k6-stats.sh reset)
#   ② 서버: 측정 전 값 저장 + 1초 기록 시작 (before)
#   ③ 내 PC: k6 실행 → 요약 json · 그래프 html 저장
#   ④ 서버: 기록 멈춤 + 차이 계산 (after) → 정합성 점검 (check)
#   ⑤ 서버 결과 파일을 내 PC 로 가져옴 (scp)
#
# 실행 (프로젝트 폴더에서, PowerShell):
#   powershell -ExecutionPolicy Bypass -File k6\run-server.ps1 -Key C:\Users\win10\.ssh\라이트세일키.pem
#       ← 기본: 02-reserve-pay.js, RATE 100, 30초, 회차 2
#   ... -Rate 50
#   ... -Schedules 2,3 -Label rows2                 ← 행 나누기
#   ... -Label pool30 -Round 2                      ← 풀 30 (서버 .env K6_POOL=30 으로 바꾼 뒤)
#   ... -Script 01-reserve-spike.js                 ← 동시 예약 500명 → 100석
#   ... -Script 03-main-page.js -Rate 200 -NoReset  ← 메인 화면 (초기화 필요 없음)
#
# ※ ssh · scp 는 윈도우 10/11 에 기본으로 들어 있음 (OpenSSH)
# ※ 결과 폴더 k6\results_server_20261122 는 .gitignore 의 k6/results*/ 로 깃허브에 안 올라감
# =====================================================================
[CmdletBinding()]                     # 모르는 매개변수가 오면 에러로 알려 줌
param(
    [string]$Script = "02-reserve-pay.js",
    [int]$Rate = 100,                         # 02 · 03 : 초당 요청 수
    [string]$Duration = "30s",                # 02 · 03 : 계속할 시간
    [string]$Schedules = "",                  # 02 : 회차 번호 "2" 또는 "2,3" (비우면 스크립트 기본 2)
    [int]$Vus = 0,                            # 01 : 동시 사용자 (0이면 스크립트 기본 500)
    [int]$Users = 0,                          # 02 : 돌려 쓸 회원 수 (0이면 스크립트 기본 500)
    [string]$Label = "",                      # 파일 이름 앞 꼬리표 예) pool20, rows2
    [int]$Round = 0,                          # 회차 (0이면 이름에 안 붙임)
    [string]$Base = "https://bustour.trave2026.kro.kr",
    [string]$Server = "ubuntu@43.200.15.96",
    [string]$Key = "",                        # ssh 키 파일 경로 (MobaXterm 에 쓰는 라이트세일 키)
    [string]$OutDir = "results_server_20261122",
    [switch]$NoReset                          # 붙이면 ① 초기화를 건너뜀 (03 메인 화면 등)
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path      # 이 파일이 있는 폴더 (k6)
New-Item -ItemType Directory -Force -Path "$here\$OutDir" | Out-Null

# ---- 측정 이름: 꼬리표-스크립트-rate-회차-round  예) rows2-02-reserve-pay-rate100-s2_3-round1 ----
$scriptName = [System.IO.Path]::GetFileNameWithoutExtension($Script)
$parts = @()
if ($Label -ne "") { $parts += $Label }
$parts += $scriptName
if ($Script -notlike "01*") { $parts += "rate$Rate" }
if ($Schedules -ne "") { $parts += "s" + ($Schedules -replace ",", "_") }
if ($Round -gt 0) { $parts += "round$Round" }
$name = $parts -join "-"

# ---- ssh · scp 공통 옵션 ----
$sshOpt = @()
if ($Key -ne "") { $sshOpt += @("-i", $Key) }

function Remote([string]$cmd) {
    # 서버 ~/bustour 에서 명령 실행. bash 로 부르므로 실행 권한(chmod +x)이 없어도 됨
    & ssh @sshOpt $Server "cd ~/bustour && $cmd"
    if ($LASTEXITCODE -ne 0) { throw "서버 명령 실패: $cmd" }
}

Write-Host ""
Write-Host "########## $name ##########"

# ① 초기화
if (-not $NoReset) { Remote "bash scripts/k6-stats.sh reset" }

# ② 측정 전 값 + 1초 기록 시작
Remote "bash scripts/k6-stats.sh before $name"

# ③ k6 — 그래프 HTML 자동 저장 (run-all.ps1 과 같은 방식)
$env:K6_WEB_DASHBOARD = "true"
$env:K6_WEB_DASHBOARD_OPEN = "false"
$env:K6_WEB_DASHBOARD_PERIOD = "1s"
$env:K6_WEB_DASHBOARD_EXPORT = "$here\$OutDir\$name-k6.html"

$k6args = @("run", "-e", "BASE=$Base", "-e", "RATE=$Rate", "-e", "DURATION=$Duration",
            "--summary-export", "$here\$OutDir\$name-k6.json")
if ($Schedules -ne "") { $k6args += @("-e", "SCHEDULES=$Schedules") }
if ($Vus -gt 0)        { $k6args += @("-e", "VUS=$Vus") }
if ($Users -gt 0)      { $k6args += @("-e", "USERS=$Users") }
$k6args += "$here\$Script"

try {
    & k6 @k6args          # 기준(thresholds) 미달이면 k6 가 오류 코드로 끝나도 아래는 꼭 실행
}
finally {
    # ④ 기록 멈춤 + 차이 계산 + 정합성
    Remote "bash scripts/k6-stats.sh after $name"
    if (-not $NoReset) { Remote "bash scripts/k6-stats.sh check $name" }

    # ⑤ 서버 결과 → 내 PC  (stats.json · watch.tsv · check.tsv · before · after · start)
    & scp @sshOpt "${Server}:~/bustour/k6/results_server/$name*" "$here\$OutDir\"
}

Write-Host ""
Write-Host "끝. 결과 파일: $here\$OutDir\$name-*"
