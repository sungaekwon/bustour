#!/usr/bin/env bash
# =====================================================================
# k6-stats.sh : 실서버 부하테스트 때 "서버 안" 수치를 자동으로 기록 — [추가]2026.11.22권성애
#
# 지난번(11-14)에 손으로 치던 것을 대신 한다
#   · MySQL 락 대기  : SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'   (측정 전 · 후 차이)
#   · 락 대기 줄     : Innodb_row_lock_current_waits                  (1초마다)
#   · 커넥션 풀      : Actuator hikaricp.connections.acquire · usage  (측정 전 · 후 차이 → 평균)
#                      hikaricp.connections.pending · active           (1초마다 → 최댓값)
#   · 503 건수       : 앱 로그 "Connection is not available"          (측정 시작 시각부터만)
#   · 정합성 점검    : 회차별 (정원 - 남은 자리) = 살아 있는 예약 인원 합
#
# 사용 (서버 ~/bustour 에서 — 보통은 PC 의 k6/run-server.ps1 이 ssh 로 대신 부름)
#   bash scripts/k6-stats.sh reset          bustour_k6 초기화 (db/05_k6_data.sql)
#   bash scripts/k6-stats.sh before NAME    측정 전 값 저장 + 1초 기록 시작
#   bash scripts/k6-stats.sh after  NAME    1초 기록 멈춤 + 측정 후 값 → 차이를 NAME-stats.json
#   bash scripts/k6-stats.sh check  NAME    정합성 점검 → NAME-check.tsv
#
# 결과 폴더: ~/bustour/k6/results_server/   (.gitignore 의 k6/results*/ 로 깃허브 제외)
#
# ★ Actuator 는 관리 포트 9282 (application.yml management.server.port)
#   nginx 는 8282 만 넘기므로 밖에서는 안 닿는다 → 같은 Docker 네트워크의 nginx 컨테이너 안 wget 으로 읽음
# =====================================================================
set -euo pipefail                      # 오류 나면 멈춤 · 없는 변수 쓰면 오류 · 파이프 중간 실패도 오류
cd "$(dirname "$0")/.."                # 어디서 불러도 ~/bustour 기준

OUT=k6/results_server
mkdir -p "$OUT"
CMD=${1:-}
NAME=${2:-test}

# ---------------------------------------------------------------------
# 도우미 함수
# ---------------------------------------------------------------------

# MySQL 에 root 로 SQL 한 문장 실행 (-N 제목 줄 없이, -B 탭으로 구분)
#   sh -c '… "$0"' "SQL" : 컨테이너 안 sh 에게 SQL 을 첫 번째 인자($0)로 넘김 → 따옴표가 섞여도 안 깨짐
#   $2 = DB 이름(선택). 2>/dev/null : "명령줄 비밀번호는 위험" 경고 숨김
sql() {
  docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B '"${2:-}"' -e "$0"' "$1" 2>/dev/null
}

# Actuator 숫자 하나 가져오기 (JSON 문자열). 앱 이미지에는 curl 이 없어 nginx(alpine)의 wget 을 빌림
metric() {
  docker compose exec -T nginx wget -qO- "http://app:9282/actuator/metrics/$1" 2>/dev/null || echo '{}'
}

# JSON 에서 "statistic":"COUNT","value":12.0 의 12.0 꺼내기 (jq 없이). 없으면 0
val() {
  local v
  v=$(echo "$1" | sed -n "s/.*\"statistic\":\"$2\",\"value\":\([0-9.eE+-]*\).*/\1/p" | head -1)
  echo "${v:-0}"
}

# 누적값 한 번 찍기 → key=value 여러 줄
snap() {
  sql "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'" | awk '{print $1"="$2}'
  local a u
  a=$(metric hikaricp.connections.acquire)
  u=$(metric hikaricp.connections.usage)
  echo "acq_count=$(val "$a" COUNT)"
  echo "acq_total=$(val "$a" TOTAL_TIME)"     # 초 단위 합계
  echo "use_count=$(val "$u" COUNT)"
  echo "use_total=$(val "$u" TOTAL_TIME)"
}

# 앱이 k6 프로필인지 확인 — 아니면 운영 DB(bustour) · 토스 결제에 부하가 갈 수 있어 멈춤
guard() {
  local p
  p=$(docker compose exec -T app printenv SPRING_PROFILES_ACTIVE 2>/dev/null || true)
  if [[ "$p" != *k6* ]]; then
    echo "[중지] 앱 프로필이 '$p' 입니다. .env 를 SPRING_PROFILES_ACTIVE=prod,k6 로 바꾸고 docker compose up -d app" >&2
    exit 1
  fi
}

# ---------------------------------------------------------------------
# 명령별 처리
# ---------------------------------------------------------------------
case "$CMD" in

   reset)
      guard
      # 05 의 1번(CREATE · GRANT)은 주석이라 실행 안 됨 → 2번(DELETE) 부터 = 초기화
      # [추가]2026.11.22권성애 오류는 보이게, "비밀번호 경고" 한 줄만 숨김
      #   (예전 2>/dev/null 은 경고와 함께 ERROR 1062 같은 진짜 오류까지 버려서 원인을 못 봤음)
      docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" bustour_k6' < db/05_k6_data.sql \
        2> >(grep -v "Using a password" >&2)
      echo "[reset] bustour_k6 초기화 완료: 회원 $(sql 'SELECT COUNT(*) FROM member' bustour_k6)명"
      ;;

  before)
    guard
    date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/$NAME.start"     # 503 로그를 이 시각부터만 세려고
    snap > "$OUT/$NAME.before"
    # 1초 기록을 "서버에서" 따로 돌림 (ssh 가 끊겨도 계속: setsid nohup)
    setsid nohup bash "$0" _watch "$NAME" > /dev/null 2>&1 < /dev/null &
    echo $! > "$OUT/$NAME.pid"
    echo "[before] $NAME 기록 시작"
    ;;

  _watch)   # before 가 부르는 내부용: 1초마다 한 줄
    set +e                                               # 한 번 실패해도 기록은 계속
    printf "time\tlock_waiting_now\tpool_pending\tpool_active\n" > "$OUT/$NAME-watch.tsv"
    while true; do
      w=$(sql "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_current_waits'" | awk '{print $2}')
      p=$(val "$(metric hikaricp.connections.pending)" VALUE)
      a=$(val "$(metric hikaricp.connections.active)" VALUE)
      printf "%s\t%s\t%s\t%s\n" "$(date +%H:%M:%S)" "${w:-}" "$p" "$a" >> "$OUT/$NAME-watch.tsv"
      sleep 1
    done
    ;;

  after)
    [[ -f "$OUT/$NAME.pid" ]] && kill "$(cat "$OUT/$NAME.pid")" 2>/dev/null || true
    rm -f "$OUT/$NAME.pid"
    snap > "$OUT/$NAME.after"
    n503=$(docker compose logs app --since "$(cat "$OUT/$NAME.start")" 2>/dev/null | grep -c "Connection is not available" || true)

    # before / after 를 짝지어 차이 계산 → JSON
    awk -F= -v name="$NAME" -v n503="${n503:-0}" -v wfile="$OUT/$NAME-watch.tsv" '
      FNR == NR { b[$1] = $2; next }            # 첫 파일(before)
      { a[$1] = $2 }                             # 둘째 파일(after)
      END {
        # 1초 기록에서 최댓값
        while ((getline line < wfile) > 0) {
          split(line, c, "\t")
          if (c[1] == "time") continue
          if (c[2] + 0 > wmax) wmax = c[2] + 0
          if (c[3] + 0 > pmax) pmax = c[3] + 0
          if (c[4] + 0 > amax) amax = c[4] + 0
        }
        lw = a["Innodb_row_lock_waits"] - b["Innodb_row_lock_waits"]
        lt = a["Innodb_row_lock_time"]  - b["Innodb_row_lock_time"]
        ac = a["acq_count"] - b["acq_count"];  at = (a["acq_total"] - b["acq_total"]) * 1000
        uc = a["use_count"] - b["use_count"];  ut = (a["use_total"] - b["use_total"]) * 1000
        printf "{\n"
        printf "  \"name\": \"%s\",\n", name
        printf "  \"lockWaits\": %d,\n", lw                                   # 락을 기다린 횟수
        printf "  \"lockTimeMs\": %d,\n", lt                                  # 기다린 시간 합계
        printf "  \"lockAvgMs\": %.2f,\n", (lw > 0 ? lt / lw : 0)
        printf "  \"lockWaitingNowMax\": %d,\n", wmax                         # 한순간 락 대기 줄 최대
        printf "  \"acquireCount\": %d,\n", ac
        printf "  \"acquireAvgMs\": %.2f,\n", (ac > 0 ? at / ac : 0)          # 커넥션 받기까지 평균
        printf "  \"usageCount\": %d,\n", uc
        printf "  \"usageAvgMs\": %.2f,\n", (uc > 0 ? ut / uc : 0)            # 커넥션 쥐고 있던 평균
        printf "  \"poolPendingMax\": %d,\n", pmax                            # 풀 앞 대기 줄 최대
        printf "  \"poolActiveMax\": %d,\n", amax
        printf "  \"noConnection503\": %d\n", n503                            # 커넥션 3초 초과 → 503
        printf "}\n"
      }' "$OUT/$NAME.before" "$OUT/$NAME.after" > "$OUT/$NAME-stats.json"
    cat "$OUT/$NAME-stats.json"
    ;;

  check)
    printf "schedule\tcapacity\tremaining\tby_counter\tby_rows\n" > "$OUT/$NAME-check.tsv"
    sql "SELECT s.id, s.capacity, s.remaining, s.capacity - s.remaining,
                COALESCE(SUM(r.headcount), 0)
           FROM tour_schedule s
           LEFT JOIN reservation r ON r.schedule_id = s.id AND r.status IN ('PENDING', 'CONFIRMED')
          GROUP BY s.id, s.capacity, s.remaining ORDER BY s.id" bustour_k6 >> "$OUT/$NAME-check.tsv"
    cat "$OUT/$NAME-check.tsv"
    if awk -F'\t' 'NR > 1 && $4 != $5 { bad = 1 } END { exit bad }' "$OUT/$NAME-check.tsv"; then
      echo "[check] 정합성 OK (모든 회차: 정원 - 남은 자리 = 예약 인원)"
    else
      echo "[check] 정합성 깨짐 — 위 표에서 by_counter 와 by_rows 가 다른 회차 확인"
    fi
    ;;

  *)
    echo "사용: bash scripts/k6-stats.sh reset | before NAME | after NAME | check NAME"
    exit 1
    ;;
esac
