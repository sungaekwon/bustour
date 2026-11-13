# 9단계 k6 부하테스트 (로컬)

## 준비
1. Workbench(3307): `db/05_k6_data.sql` 1번 → `USE bustour_k6;` 후 `02_tables.sql` → 05 의 2번부터
2. IntelliJ 실행 설정 복사 → VM options `-Dspring.profiles.active=k6` (bustour_k6 · 가짜 PG 300ms · 로그 warn)
3. k6 설치: `winget install k6` (PowerShell)

## 실행 (측정마다 05_k6_data.sql 2번부터 다시 실행 = 초기화)
| 스크립트 | 명령 | 기대 |
|---|---|---|
| 01 동시 예약 | `k6 run k6/01-reserve-spike.js` | reserve_ok = 100, sold_out = 400, other = 0, 점검 쿼리 일치 |
| 02 예약 + 결제 + 취소 | `k6 run -e RATE=50 -e DURATION=30s k6/02-reserve-pay.js` | RATE 50 → 100 → 200 → 400 으로 올리며 p95 · 처리량 · dropped 기록 |
| 03 메인 화면 | `k6 run -e RATE=200 k6/03-main-page.js` | 기상청 호출 로그 0~1번, p95 |

## 결과표 (채우기)
| 스크립트 | RATE/VUS | 성공 | 실패 | p95 reserve | p95 confirm · cancel | 처리량/s | dropped | 점검 쿼리 |
|---|---|---|---|---|---|---|---|---|
| 01 | 500 VU |  |  |  | - |  | - |  |
| 02 | 50 |  |  |  |  |  |  |  |
| 02 | 100 |  |  |  |  |  |  |  |
| 02 | 200 |  |  |  |  |  |  |  |
| 02 | 400 |  |  |  |  |  |  |  |
| 03 | 200 |  | - | - | - |  |  | - |

※ 02 는 매번 취소까지 해서 회원 500명을 돌려 쓴다. RATE 가 아주 높아 한 회원의 앞 흐름이 끝나기 전에 같은 회원이 다시 오면
   409(1인 1예약)가 실패에 섞인다 → 그때는 05 의 회원 수를 늘리거나 USERS 를 맞춘다.
※ 측정 끝나면 점검 쿼리: 모든 회차에서 remaining = capacity - 살아 있는 예약 인원.
