#!/usr/bin/env bash
# =====================================================================
# backup.sh : bustour DB 백업 (10단계 ⑧) — [추가]2026.11.14권성애
#
# 하는 일
#   1) mysql 컨테이너 안에서 mysqldump 로 bustour 스키마 전체를 SQL 로 뽑기
#   2) gzip 으로 압축해 ~/bustour/backups/bustour_날짜_시각.sql.gz 로 저장
#   3) 7일 지난 백업 파일은 지우기 (디스크가 차는 것 방지)
#
# 실행 (서버)
#   손으로   : ~/bustour/scripts/backup.sh                 ← 배포 · 테이블 변경 직전에 한 번
#   크론으로 : crontab -e 에 아래 한 줄 (매일 새벽 3시, 결과는 backups/backup.log 에 이어 쓰기)
#     0 3 * * * /home/ubuntu/bustour/scripts/backup.sh >> /home/ubuntu/bustour/backups/backup.log 2>&1
#
# 되돌리기(복구) — ⚠ 지금 DB 내용이 백업 시점으로 바뀐다
#   gunzip -c backups/bustour_20261114_0300.sql.gz \
#     | docker compose exec -T mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" bustour'
#
# ※ 백업 파일은 이 서버 안에만 있다 → 서버가 통째로 망가지면 같이 사라진다
#   중요해지면 Lightsail 자동 스냅샷을 켜거나, 가끔 PC 로 내려받기 (scp)
# =====================================================================

# 안전 장치
#   -e : 명령 하나라도 실패하면 바로 멈춤
#   -u : 정의 안 된 변수를 쓰면 오류 (오타 방지)
#   -o pipefail : A | B 에서 A(mysqldump)가 실패해도 실패로 본다
#                 (없으면 뒤의 gzip 만 성공해도 "성공"으로 착각 → 빈 백업 파일이 남음)
set -euo pipefail

# 이 스크립트가 있는 폴더(scripts)의 한 단계 위 = 프로젝트 루트(~/bustour) 로 이동
#   크론은 어느 폴더에서 실행될지 모르기 때문에, docker-compose.yml 과 .env 가 있는 곳으로 직접 간다
cd "$(dirname "$0")/.."

BACKUP_DIR="backups"
KEEP_DAYS=7                                        # 며칠치 보관할지
STAMP="$(date +%Y%m%d_%H%M)"                       # 예) 20261114_0300 (서버 시간대 = Asia/Seoul)
FILE="${BACKUP_DIR}/bustour_${STAMP}.sql.gz"
TMP="${FILE}.tmp"                                  # 다 만들어지기 전에는 .tmp 이름으로

mkdir -p "$BACKUP_DIR"

echo "[$(date '+%F %T')] 백업 시작 → ${FILE}"

# ---------------------------------------------------------------------
# mysqldump 옵션
#   --single-transaction        : 테이블을 잠그지 않고 "한 시점의 스냅샷(MVCC)"으로 읽기
#                                 → 백업하는 동안에도 예약 · 결제가 막히지 않음 (InnoDB 전용)
#   --default-character-set=utf8mb4 : 한글을 utf8mb4 로 뽑기 (한글 깨짐 방지)
#   --routines --triggers       : 저장 프로시저 · 트리거도 같이 (지금은 없지만 생기면 빠지지 않게)
#   --no-tablespaces            : 테이블스페이스 정보는 빼기 (PROCESS 권한 경고 방지)
#   -uroot -p"$MYSQL_ROOT_PASSWORD" : 비밀번호는 컨테이너 안 환경변수에서 (이 파일에 비밀번호를 쓰지 않음)
#   bustour                     : 백업할 스키마
#
# 작은따옴표 '...' 안의 $MYSQL_ROOT_PASSWORD 는 서버가 아니라 "컨테이너 안의 sh" 가 푼다
# exec : sh 대신 mysqldump 가 직접 실행 (프로세스 하나 덜 씀)
# 2>/dev/null 이 아니라 그대로 두는 이유: 오류 문구가 backup.log 에 남아야 원인을 찾을 수 있음
# ---------------------------------------------------------------------
docker compose exec -T mysql sh -c \
  'exec mysqldump --single-transaction --default-character-set=utf8mb4 --routines --triggers --no-tablespaces -uroot -p"$MYSQL_ROOT_PASSWORD" bustour' \
  | gzip > "$TMP"

# 끝까지 성공했을 때만 진짜 이름으로 바꾸기 (중간에 실패한 반쪽 파일이 "백업"으로 남지 않게)
mv "$TMP" "$FILE"

# 크기 확인 (압축 후에도 수 KB 이상이어야 정상)
SIZE="$(du -h "$FILE" | cut -f1)"
echo "[$(date '+%F %T')] 백업 완료: ${FILE} (${SIZE})"

# ---------------------------------------------------------------------
# 오래된 백업 지우기
#   -name  : bustour_*.sql.gz 만 (다른 파일은 건드리지 않음)
#   -mtime +N : 마지막 수정이 N일보다 오래된 것  → KEEP_DAYS-1 을 넣어 "7개 정도" 남게
#   -print : 지운 파일 이름을 로그에 남김
# ---------------------------------------------------------------------
find "$BACKUP_DIR" -name 'bustour_*.sql.gz' -mtime +$((KEEP_DAYS - 1)) -print -delete

echo "[$(date '+%F %T')] 정리 끝 (최근 ${KEEP_DAYS}일 보관)"
