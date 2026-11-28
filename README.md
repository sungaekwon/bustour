# 부산한바퀴 — 부산 버스투어 예약·결제 서비스

> Spring Boot 4로 만든 개인 프로젝트입니다. 좌석이 한정된 투어에 예약이 한꺼번에 몰려도 **초과 판매 없이** 예약·결제·취소가 맞아떨어지도록 설계했고, 로컬과 실제 서버(AWS Lightsail)에서 부하테스트로 확인했습니다.

| | |
|---|---|
| 🌐 서비스 | https://bustour.trave2026.kro.kr |
| 📁 포트폴리오 (부하테스트 결과 포함) | https://portfolio.trave2026.kro.kr |
| 🔑 체험 계정 | 휴대폰 `01033334444` · 비밀번호 `test1234!` |
| | (토스 **테스트 결제**라 실제 돈이 나가지 않습니다) |
| 📅 기간 | 2026.11.06 ~ 2026.11.24 · 개인 |
| | (기획 · 개발 · 배포 · 부하테스트 전부) |


## 목차
1. [기술 스택](#기술-스택)
2. [주요 기능](#주요-기능)
3. [동시성 설계 — 초과 판매를 막는 방법](#동시성-설계--초과-판매를-막는-방법)
4. [결제 흐름과 실패 대응](#결제-흐름과-실패-대응)
5. [부하테스트 결과](#부하테스트-결과)
6. [배포 구조](#배포-구조)
7. [테스트](#테스트)
8. [폴더 구조](#폴더-구조)
9. [로컬에서 실행하기](#로컬에서-실행하기)
10. [보안 규칙](#보안-규칙)


## 기술 스택

| 분류 | 기술 |
|---|---|
| Backend | Java 21, Spring Boot 4, Spring Data JPA (Hibernate), Spring Security, Spring Cache (Caffeine) |
| Frontend | Thymeleaf, HTML, CSS, JavaScript |
| Database | MySQL 8.0 |
| 외부 API | 토스페이먼츠 (결제 · 테스트 키), 기상청 단기·중기예보 (공공데이터포털) |
| DevOps | Docker, Docker Compose, GitHub Actions, GitHub Container Registry, Nginx, Let's Encrypt, AWS Lightsail (Ubuntu) |
| Test | JUnit 5, k6 (부하테스트), Spring Boot Actuator, MySQL 락 통계 |


## 주요 기능

| 화면 | 내용 |
|---|---|
| 메인 | 투어 3개를 한 화면에 나란히, 코스 · 날짜별 출발 시간 · 남은 좌석, 부산 7일 날씨 띠 |
| 예약 | 메인에서 모달로 인원 선택 → 좌석 즉시 확보 (1예약 최대 10명, 출발 1시간 전 마감) |
| 결제 | 토스페이먼츠 결제창 → 서버에서 승인 · 금액 검증 → 예약 확정 |
| 내 예약 | 결제 대기 / 확정 / 취소 목록, "이어서 결제 · 포기하기", 출발 3일 전까지 취소(전액 환불) |
| 자동 반환 | 10분 안에 결제하지 않은 예약은 스케줄러가 좌석을 되돌림 |
| 날씨 | 기상청 API 결과를 1시간 캐시 → 화면을 아무리 열어도 기상청 호출은 1번 |
| 회원 | 이름 · 휴대폰 · 비밀번호로 가입 · 로그인 (Spring Security, CSRF) |


## 동시성 설계 — 초과 판매를 막는 방법

### 1. 좌석 차감은 UPDATE 한 문장 (조건부 UPDATE)

"남은 좌석 읽기 → 계산 → 저장"으로 나누면, 동시에 들어온 요청이 같은 값을 읽고 덮어써 **갱신 손실**(좌석이 덜 줄어드는 문제)이 생깁니다.
그래서 확인과 차감을 **한 문장**으로 합쳤습니다. 좌석이 모자라면 0행이 바뀌고, 그 결과를 "매진"으로 처리합니다.

```sql
UPDATE tour_schedule
   SET remaining = remaining - :n
 WHERE id = :id
   AND remaining >= :n      -- 모자라면 0행 = 매진
```

> 이 방식을 고르기 전에, 별도 학습 프로젝트에서 같은 예약 기능을 **15가지 방식**(락 없음 · 비관적 락 · 낙관적 락 · 조건부 UPDATE 등)으로 만들어 JUnit과 k6로 비교했고, 조건부 UPDATE가 가장 정확하고 빨랐습니다.

### 2. 같은 요청은 한 번만 (멱등성)

- 예약 모달을 열 때 **요청 키(UUID)** 를 만들고, 인원을 바꾸면 새 키를 만듭니다.
- DB에 `UNIQUE(request_key)` 를 두어, 더블클릭이나 재전송이 와도 예약은 1건만 생깁니다.
- 좌석 UPDATE를 먼저 하고 예약 INSERT를 나중에 해서, 외래 키 공유 락과 엮인 데드락을 피했습니다.

### 3. 기다리는 시간에 상한

- 락 · 커넥션 대기를 3초로 제한하고, 넘으면 **503**으로 빨리 응답합니다 (요청 키 덕분에 다시 눌러도 안전).
- MySQL에서는 JPA의 lock timeout 힌트가 적용되지 않아, 커넥션을 만들 때 `innodb_lock_wait_timeout` 을 설정했습니다.


## 결제 흐름과 실패 대응

```
[예약 확보 PENDING] ─▶ [토스 결제창] ─▶ [서버: PG 승인] ─▶ [DB 확정 CONFIRMED]
        │                                      │                 │
   10분 지나면                             승인 뒤 DB 확정 실패 → 재시도 → 재확인 → 그래도 실패면 PG 취소(보상)
   좌석 자동 반환
```

- PG 호출은 DB 트랜잭션 **밖**에서 해서, 결제 응답을 기다리는 동안 행 락을 잡고 있지 않습니다.
- 확정됐는지 알 수 없을 때는 무작정 취소하지 않고 `COMPENSATION_FAILED` 로 기록해 사람이 확인합니다.
- 취소는 PG 환불 → DB 취소 순서이고, DB 취소가 실패해도 환불은 한 번만 일어납니다.


## 부하테스트 결과

k6로 예약 → 결제 → 취소 흐름을 실서버(Lightsail 2GB)에 보내고, Actuator와 MySQL 락 통계로 어디서 기다리는지 함께 봤습니다. 결제는 가짜 PG(`PG_TYPE=fake`)로 바꿔 토스 서버에는 부하를 주지 않았습니다.

| 측정 | 처리량 (건/초) | 비교 | 관찰 |
|---|---|---|---|
| 기본 (커넥션 풀 20) | 38.4 | 기준 | 락 대기 줄 19 = 풀 − 1, 커넥션 받기 620ms ≫ 쥐고 있는 시간 84ms |
| 회차를 2행으로 나눔 | 55.0 | **+43%** | 한 행에 몰리던 락이 나뉨 |
| 커넥션 풀 30 | 37.8 | −2% | 풀을 늘려도 락 앞 줄만 길어짐 |

- **모든 측정에서 초과 판매 0건**, 점검 쿼리로 `remaining = 정원 − 살아 있는 예약 인원` 을 확인했습니다.
- 로컬에서는 커넥션 풀 부족이 원인처럼 보였지만, 실서버에서는 **모든 예약이 회차 한 행의 락 앞에서 기다리고 있었다**는 것을 확인했습니다. 그래서 풀 크기는 20으로 유지했습니다.
- 그래프와 단계별 결과는 [포트폴리오 페이지](https://portfolio.trave2026.kro.kr)에 있습니다.


## 배포 구조

```
GitHub (main push)
   │  GitHub Actions: Gradle 빌드 → Docker 이미지 → ghcr.io 에 push → SSH로 서버에서 pull · up -d
   ▼
AWS Lightsail (Ubuntu, 2GB)
   인터넷 ──443──▶ [nginx] ──▶ [app : Spring Boot] ──▶ [mysql 8.0]
                       ▲
                 [certbot] 인증서 발급 · 갱신
```

- 밖으로 열린 포트는 nginx의 80 · 443뿐이고, 앱과 MySQL은 Docker 내부 네트워크에서만 보입니다.
- DB는 매일 새벽 `scripts/backup.sh` 로 백업합니다 (`mysqldump --single-transaction`, 7일 보관).


## 테스트

| 테스트 | 확인하는 것 |
|---|---|
| `ReservationServiceConcurrencyTest` | 남은 1자리에 100명 동시 → 1명만 성공 · 같은 요청 키로 10번 동시 → 예약 1건 · 1인 1예약 · 예약 마감 |
| `PaymentConcurrencyTest` | 더블클릭(같은 결제 번호 10번) → 결제 1건, 두 탭 동시 결제 → 1건만 확정 · 나머지 보상 취소, 만료 vs 결제 |
| `PaymentFailureRecoveryTest` | 승인 뒤 확정 실패 → 보상, 재시도 성공, 취소 중 DB 실패 → 환불 1번 |
| `WeatherCacheTest` · `WeatherSummaryTest` · `KmaWeatherClientTest` | 캐시로 기상청 호출 1번, 오류는 캐시하지 않음 |
| `ReservationApiTest` | 로그인 없음 401 · CSRF 없음 403 · 같은 요청 키로 다시 → 200 같은 예약 번호 · 입력 검증 400 |
| `k6/01~03` | 동시 예약 500명, 예약+결제+취소, 메인 화면 |

```bash
./gradlew test
```


## 폴더 구조

```
bustour
├─ src/main/java/com/kwon/bustour
│   ├─ config        캐시 · 스케줄러 · 시큐리티 · 시간 설정
│   ├─ domain        엔티티 (Tour, TourSchedule, Reservation, Payment …)
│   ├─ dto           화면 · API 용 데이터
│   ├─ repository    JPA 리포지토리 (조건부 UPDATE 쿼리)
│   ├─ service       예약 · 결제 · 취소 · 만료 스케줄러 · 날씨
│   ├─ pg            결제 연동 (TossPgClient / FakePgClient)
│   ├─ weather       기상청 API 클라이언트
│   ├─ security      로그인
│   └─ web           컨트롤러 · API 예외 처리
├─ db                테이블 · 샘플 데이터 · 점검 쿼리 · k6 데이터 SQL
├─ k6                부하테스트 스크립트
├─ nginx             리버스 프록시 설정
├─ scripts           DB 백업
├─ Dockerfile · docker-compose.yml
└─ .github/workflows/deploy.yml
```


## 로컬에서 실행하기

```bash
# 1. 환경변수 파일 만들기 (값은 직접 채우기, GitHub 에 올리지 않음)
cp .env.example .env

# 2. 결제를 가짜 PG 로 쓰려면 .env 에서
PG_TYPE=fake

# 3. 실행
docker compose up -d
```


## 보안 규칙

- 비밀 값(`DB_PASSWORD`, `TOSS_SECRET_KEY`, `TOSS_CLIENT_KEY`, `KMA_SERVICE_KEY` 등)은 **환경변수로만** 넣고, yml · GitHub에는 넣지 않습니다. 저장소에는 이름만 적은 `.env.example` 만 있습니다.
- 토스 키가 `test_` 로 시작하지 않으면 앱이 뜨지 않게 해서, 실제 결제 키가 잘못 들어가는 것을 막았습니다.
- 카드 정보는 저장하지 않습니다 (결제창은 토스가 처리).
- 부하테스트는 반드시 `PG_TYPE=fake` 로 실행합니다.


---
## 만든 사람

| | |
|---|---|
| 👤 이름 | 권성애 (백엔드 웹 개발자) |
| ✉️ 이메일 | rnjstjddo88@naver.com |
| 📁 포트폴리오 | https://portfolio.trave2026.kro.kr |
| 👥 팀 프로젝트 — 국내여행 소개 및 가이드예약결제 (GitHub) | https://github.com/sungaekwon/travel.git |
| 🌐 팀 프로젝트 — 국내여행 소개 및 가이드예약결제 (사이트) | https://trave2026.kro.kr/travel |
