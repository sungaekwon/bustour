-- =====================================================================
-- 03_sample_data.sql : 개발용 샘플 데이터 (bustour 스키마에만 넣는다. bustour_test 에는 넣지 않음)
--
-- ★ 실행: 02_tables.sql 실행 직후 → 이 파일 전체 실행
--   다시 넣고 싶으면 02_tables.sql 부터 다시 (DROP 후 CREATE) → 03 실행
--
-- ★ 날짜를 고정하지 않았다
--   '2026-11-10 10:00' 처럼 고정하면 일주일 뒤에는 모든 회차가 "이미 출발함"이 된다.
--   → CURDATE()(오늘), NOW()(지금) 기준으로 계산 = 실행하는 날마다 "앞으로 14일" 회차가 생김
--   ※ R22(SQL에서 NOW() 금지)는 "앱 코드" 규칙이다. 한 번 실행하고 끝나는 샘플 스크립트에서는 써도 된다.
--
-- ★ 남은 자리(remaining)는 맨 마지막에 예약 인원으로 다시 계산한다
--   손으로 맞추면 틀리기 쉽다 → 04_check.sql 의 점검 쿼리가 0행이어야 정상
--
-- 로그인 비밀번호: 샘플 회원 3명 모두  test1234!
-- =====================================================================

-- [추가]2026.11.14권성애 — 이 파일을 utf8mb4(한글)로 읽으라고 MySQL 에 알려 주기
--   서버의 docker MySQL 은 처음 뜰 때 이 파일을 실행하는 프로그램이 latin1(서유럽 문자)로 읽어서
--   한글이 두 번 변환된 채 저장됐다 (화면에 í•´ìš´ëŒ€ 처럼 보임 = "해운대")
--   SET NAMES utf8mb4 = 이 접속에서 주고받는 글자는 utf8mb4 라고 선언 → 어떤 실행기로 돌려도 한글이 그대로 들어감
--   (Workbench 는 원래 utf8mb4 라 로컬에서는 차이 없음)
SET NAMES utf8mb4;

USE bustour;

-- 이 접속(세션)에서 NOW(), CURDATE()를 한국 시각으로
-- (로컬 MySQL은 이미 한국 시각이라 차이 없음. 서버 DB(UTC)에 넣을 때를 대비)
SET time_zone = '+09:00';

-- Workbench의 "Safe Updates" 기능은 WHERE에 키가 없는 UPDATE를 막는다 (오류 1175)
-- 아래 9번(남은 자리 다시 계산)은 모든 회차를 고치므로 잠시 끈다. 맨 끝에서 다시 켠다.
SET SQL_SAFE_UPDATES = 0;


-- ---------------------------------------------------------------------
-- 1. member : 일반 회원 2명 + 관리자 1명
--   password_hash 앞의 {bcrypt} = "어떤 방식으로 암호화했는지" 표시
--   Spring Security의 DelegatingPasswordEncoder(기본 추천)가 이 표시를 보고 BCrypt로 비교한다.
--   ※ 나중에 BCryptPasswordEncoder 를 직접 쓰기로 바꾸면 {bcrypt} 를 빼야 한다.
--   같은 비밀번호라 해시를 하나만 만들어 3명이 같이 씀 (salt가 해시 안에 들어 있어서 그래도 로그인됨)
-- ---------------------------------------------------------------------
INSERT INTO member (id, name, phone, password_hash, role, created_at) VALUES
  (1, '김부산', '01011112222', '{bcrypt}$2a$10$eFhC77Fx5.7LM.8rb5KNMewjxESZvm.8Vw6udgxIWiZYTJZrEltsW', 'USER',  NOW() - INTERVAL 30 DAY),
  (2, '이바다', '01033334444', '{bcrypt}$2a$10$eFhC77Fx5.7LM.8rb5KNMewjxESZvm.8Vw6udgxIWiZYTJZrEltsW', 'USER',  NOW() - INTERVAL 10 DAY),
  (3, '관리자', '01000000000', '{bcrypt}$2a$10$eFhC77Fx5.7LM.8rb5KNMewjxESZvm.8Vw6udgxIWiZYTJZrEltsW', 'ADMIN', NOW() - INTERVAL 60 DAY);


-- ---------------------------------------------------------------------
-- 2. tour : 판매 중 3개 + 숨김 1개 (숨김은 목록에서 빠지는지 테스트용)
--   id를 직접 넣은 이유: 아래 코스·회차에서 tour_id = 1, 2, 3 으로 가리키기 쉽게
--   (AUTO_INCREMENT 컬럼에 값을 직접 넣어도 되고, 다음 번호는 자동으로 5부터 이어짐)
-- ---------------------------------------------------------------------
INSERT INTO tour (id, name, description, price, duration_min, image_key, status) VALUES
  (1, '해운대·광안리 야경 투어',
      '황령산 전망대에서 부산 야경을 내려다보고, 광안대교 불빛과 해운대 밤바다를 따라 달리는 저녁 코스입니다.',
      15000, 180, 'tours/1/main.jpg', 'ON_SALE'),
  (2, '태종대·영도 바다 투어',
      '바다 절벽 위 골목 흰여울문화마을과 태종대 해안 절경, 국립해양박물관을 둘러보는 영도 한 바퀴 코스입니다.',
      12000, 210, 'tours/2/main.jpg', 'ON_SALE'),
  (3, '원도심 골목 투어',
      '168계단과 부산타워, 국제시장과 자갈치시장, 감천문화마을까지 부산의 옛 골목과 시장을 걷는 코스입니다.',
      10000, 180, 'tours/3/main.jpg', 'ON_SALE'),
  (4, '송정·기장 해안 투어 (시즌 종료)',
      '여름 시즌 한정 코스입니다. 현재는 운영하지 않습니다.',
      13000, 200, 'tours/4/main.jpg', 'HIDDEN');


-- ---------------------------------------------------------------------
-- 3. course_stop : 투어마다 정류장 (부산역 출발 → … → 부산역 도착)
--   좌표는 지도 기준 근사값 (지도 표시용. 정확한 승하차 위치가 필요하면 나중에 수정)
-- ---------------------------------------------------------------------
INSERT INTO course_stop (tour_id, seq, place_name, description, lat, lng, image_key) VALUES
  -- 1. 해운대·광안리 야경
  (1, 1, '부산역 (출발)',            '부산역 광장 버스 승강장에서 출발합니다.',                       35.1150000, 129.0416000, NULL),
  (1, 2, '황령산 전망쉼터',          '부산 도심과 광안대교가 한눈에 보이는 야경 명소입니다.',          35.1574000, 129.0821000, NULL),
  (1, 3, '광안리해수욕장',           '광안대교 조명을 가장 가까이에서 볼 수 있는 해변입니다.',          35.1532000, 129.1186000, NULL),
  (1, 4, '동백섬 (누리마루)',        '해안 산책로를 따라 걸으며 해운대 마천루 야경을 봅니다.',          35.1531000, 129.1525000, NULL),
  (1, 5, '해운대해수욕장',           '자유 시간 30분. 해변 산책 후 버스로 돌아옵니다.',               35.1588000, 129.1603000, NULL),
  (1, 6, '부산역 (도착)',            '출발했던 승강장에서 하차합니다.',                               35.1150000, 129.0416000, NULL),
  -- 2. 태종대·영도 바다
  (2, 1, '부산역 (출발)',            '부산역 광장 버스 승강장에서 출발합니다.',                       35.1150000, 129.0416000, NULL),
  (2, 2, '흰여울문화마을',           '바다 절벽 위에 자리한 하얀 골목 마을입니다.',                   35.0783000, 129.0445000, NULL),
  (2, 3, '태종대',                  '해안 절벽과 등대 전망대를 둘러봅니다. 자유 시간 60분.',          35.0532000, 129.0866000, NULL),
  (2, 4, '국립해양박물관',           '바다와 배의 역사를 전시한 박물관입니다.',                       35.0786000, 129.0805000, NULL),
  (2, 5, '부산역 (도착)',            '출발했던 승강장에서 하차합니다.',                               35.1150000, 129.0416000, NULL),
  -- 3. 원도심 골목
  (3, 1, '부산역 (출발)',            '부산역 광장 버스 승강장에서 출발합니다.',                       35.1150000, 129.0416000, NULL),
  (3, 2, '초량 이바구길 (168계단)',  '산복도로 마을로 이어지는 가파른 계단길입니다.',                 35.1194000, 129.0378000, NULL),
  (3, 3, '용두산공원 (부산타워)',    '원도심 한가운데의 공원과 전망 타워입니다.',                     35.1008000, 129.0324000, NULL),
  (3, 4, '국제시장·BIFF광장',        '먹거리 골목과 시장을 자유롭게 구경합니다.',                     35.1012000, 129.0281000, NULL),
  (3, 5, '자갈치시장',               '바다 앞 수산시장입니다.',                                       35.0967000, 129.0306000, NULL),
  (3, 6, '감천문화마을',             '알록달록한 계단식 마을입니다. 자유 시간 40분.',                 35.0975000, 129.0108000, NULL),
  (3, 7, '부산역 (도착)',            '출발했던 승강장에서 하차합니다.',                               35.1150000, 129.0416000, NULL),
  -- 4. 송정·기장 (숨김)
  (4, 1, '해운대 블루라인파크 (미포)', '해변열차 정거장입니다.',                                       35.1599000, 129.1712000, NULL),
  (4, 2, '송정해수욕장',             '서핑으로 유명한 해변입니다.',                                   35.1786000, 129.2001000, NULL),
  (4, 3, '해동용궁사',               '바닷가 바위 위에 지어진 절입니다.',                             35.1883000, 129.2233000, NULL);


-- ---------------------------------------------------------------------
-- 4. tour_schedule : 앞으로 14일치 회차 (정원 40 = 버스 1대)
--   WITH RECURSIVE : 1부터 14까지 숫자를 만드는 "재귀 CTE" (반복문 대신)
--     d(n) 첫 줄: SELECT 1            → n = 1
--          다음 : SELECT n + 1 ...    → n = 2, 3, … 14 에서 멈춤 (WHERE n < 14)
--   TIMESTAMP(날짜, '19:00:00') : 날짜 + 시각 → DATETIME
--   remaining 은 일단 정원과 같게 넣고, 예약을 넣은 뒤 맨 마지막에 다시 계산한다.
-- ---------------------------------------------------------------------
INSERT INTO tour_schedule (tour_id, start_at, capacity, remaining, status)
WITH RECURSIVE d (n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM d WHERE n < 14
)
SELECT 1, TIMESTAMP(CURDATE() + INTERVAL n DAY, '19:00:00'), 40, 40, 'OPEN' FROM d   -- 야경: 매일 19시
UNION ALL
SELECT 2, TIMESTAMP(CURDATE() + INTERVAL n DAY, '10:00:00'), 40, 40, 'OPEN' FROM d   -- 영도: 10시
UNION ALL
SELECT 2, TIMESTAMP(CURDATE() + INTERVAL n DAY, '14:00:00'), 40, 40, 'OPEN' FROM d   --       14시
UNION ALL
SELECT 3, TIMESTAMP(CURDATE() + INTERVAL n DAY, '10:00:00'), 40, 40, 'OPEN' FROM d   -- 원도심: 10시
UNION ALL
SELECT 3, TIMESTAMP(CURDATE() + INTERVAL n DAY, '14:00:00'), 40, 40, 'OPEN' FROM d;  --         14시

-- 숨김 투어(4번)의 회차: 규칙대로 CLOSED (숨길 때 앞으로 남은 회차도 운영 중지)
INSERT INTO tour_schedule (tour_id, start_at, capacity, remaining, status) VALUES
  (4, TIMESTAMP(CURDATE() + INTERVAL 1 DAY, '10:00:00'), 40, 40, 'CLOSED'),
  (4, TIMESTAMP(CURDATE() + INTERVAL 2 DAY, '10:00:00'), 40, 40, 'CLOSED'),
  (4, TIMESTAMP(CURDATE() + INTERVAL 3 DAY, '10:00:00'), 40, 40, 'CLOSED');


-- ---------------------------------------------------------------------
-- 5. 테스트용 특수 회차
--   @변수 : 이 접속(세션) 안에서만 쓰는 변수. 아래 예약 INSERT에서 회차 번호를 가리킬 때 씀
--   LAST_INSERT_ID() : 방금 INSERT로 생긴 AUTO_INCREMENT 번호
-- ---------------------------------------------------------------------

-- (가) 남은 자리 1개: 정원 5 + 아래에서 4명 확정 예약 → remaining 1 (R1 마지막 1자리)
--      출발 2일 뒤 = 취소 마감(3일 전)이 이미 지남 (R13)
INSERT INTO tour_schedule (tour_id, start_at, capacity, remaining, status)
VALUES (3, TIMESTAMP(CURDATE() + INTERVAL 2 DAY, '11:30:00'), 5, 5, 'OPEN');
SET @s_last = LAST_INSERT_ID();

-- (나) 30분 뒤 출발: 결정 "예약 마감 = 출발 1시간 전" → 이미 예약 불가 (R2)
--      DATE_FORMAT(…, '%Y-%m-%d %H:%i:00') : 초를 00으로 잘라 보기 좋게
INSERT INTO tour_schedule (tour_id, start_at, capacity, remaining, status)
VALUES (2, DATE_FORMAT(NOW() + INTERVAL 30 MINUTE, '%Y-%m-%d %H:%i:00'), 40, 40, 'OPEN');

-- (다) 2시간 전에 이미 출발한 회차 (R2 지난 회차)
INSERT INTO tour_schedule (tour_id, start_at, capacity, remaining, status)
VALUES (2, DATE_FORMAT(NOW() - INTERVAL 2 HOUR, '%Y-%m-%d %H:%i:00'), 40, 40, 'OPEN');

-- (라) 운영 중지 회차: 야경 투어 3일 뒤 → CLOSED (R2)
UPDATE tour_schedule SET status = 'CLOSED'
 WHERE tour_id = 1 AND start_at = TIMESTAMP(CURDATE() + INTERVAL 3 DAY, '19:00:00');

-- 예약을 넣을 회차 번호 찾아 두기
SET @s_t1_d5  = (SELECT id FROM tour_schedule WHERE tour_id = 1 AND start_at = TIMESTAMP(CURDATE() + INTERVAL 5 DAY, '19:00:00'));
SET @s_t1_d6  = (SELECT id FROM tour_schedule WHERE tour_id = 1 AND start_at = TIMESTAMP(CURDATE() + INTERVAL 6 DAY, '19:00:00'));
SET @s_t2_d1a = (SELECT id FROM tour_schedule WHERE tour_id = 2 AND start_at = TIMESTAMP(CURDATE() + INTERVAL 1 DAY, '10:00:00'));
SET @s_t2_d1b = (SELECT id FROM tour_schedule WHERE tour_id = 2 AND start_at = TIMESTAMP(CURDATE() + INTERVAL 1 DAY, '14:00:00'));
SET @s_t3_d4  = (SELECT id FROM tour_schedule WHERE tour_id = 3 AND start_at = TIMESTAMP(CURDATE() + INTERVAL 4 DAY, '10:00:00'));


-- ---------------------------------------------------------------------
-- 6. reservation : 상태별 예약
--   active_key 는 적지 않는다 (계산 컬럼 → 적으면 오류 3105)
--   amount = unit_price × headcount 가 아니면 CHECK 오류
--   UUID() : MySQL이 만드는 36자 랜덤 키 (화면의 crypto.randomUUID()와 같은 모양)
-- ---------------------------------------------------------------------
INSERT INTO reservation
  (id, schedule_id, member_id, headcount, unit_price, amount, status, request_key,
   expires_at, confirmed_at, canceled_at, created_at)
VALUES
  -- r1 김부산: 야경 5일 뒤, 2명 확정 → 취소 가능 (출발 3일 전보다 앞)
  (1, @s_t1_d5, 1, 2, 15000, 30000, 'CONFIRMED', UUID(),
      NOW() - INTERVAL 1 DAY + INTERVAL 10 MINUTE, NOW() - INTERVAL 1 DAY + INTERVAL 3 MINUTE, NULL, NOW() - INTERVAL 1 DAY),

  -- r2 김부산: 남은 자리 1개 회차에 4명 확정 → 출발 2일 뒤라 취소 마감 지남 (R13)
  (2, @s_last, 1, 4, 10000, 40000, 'CONFIRMED', UUID(),
      NOW() - INTERVAL 2 DAY + INTERVAL 10 MINUTE, NOW() - INTERVAL 2 DAY + INTERVAL 5 MINUTE, NULL, NOW() - INTERVAL 2 DAY),

  -- r3 이바다: 결제 대기 중 (만료 10분 전) → "이어서 결제 / 포기하기" 화면
  --   ※ 스크립트 실행 후 10분이 지나면 이 예약도 만료 대상이 된다 (화면 확인은 바로 할 것)
  (3, @s_t2_d1a, 2, 2, 12000, 24000, 'PENDING', UUID(),
      NOW() + INTERVAL 10 MINUTE, NULL, NULL, NOW()),

  -- r4 이바다: 결제 대기인데 만료 시각이 지남 → 만료 스케줄러가 EXPIRED로 바꾸고 자리 반환해야 함 (R10)
  --   아직 PENDING이라 자리는 차감된 상태로 계산됨
  (4, @s_t2_d1b, 2, 3, 12000, 36000, 'PENDING', UUID(),
      NOW() - INTERVAL 20 MINUTE, NULL, NULL, NOW() - INTERVAL 30 MINUTE),

  -- r5, r6, r7 김부산: 같은 회차(원도심 4일 뒤)에 취소 → 만료 → 확정
  --   active_key = NULL, NULL, 1 → 유니크에 안 걸림 (취소 후 재예약 가능 = active_key의 존재 이유)
  (5, @s_t3_d4, 1, 1, 10000, 10000, 'CANCELED', UUID(),
      NOW() - INTERVAL 5 DAY + INTERVAL 10 MINUTE, NOW() - INTERVAL 5 DAY + INTERVAL 2 MINUTE,
      NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 5 DAY),
  (6, @s_t3_d4, 1, 1, 10000, 10000, 'EXPIRED', UUID(),
      NOW() - INTERVAL 3 DAY + INTERVAL 10 MINUTE, NULL, NULL, NOW() - INTERVAL 3 DAY),
  (7, @s_t3_d4, 1, 1, 10000, 10000, 'CONFIRMED', UUID(),
      NOW() - INTERVAL 1 HOUR + INTERVAL 10 MINUTE, NOW() - INTERVAL 1 HOUR + INTERVAL 4 MINUTE, NULL, NOW() - INTERVAL 1 HOUR),

  -- r8 이바다: 결제 승인은 됐는데 그 사이 만료 → 보상 취소도 실패한 경우 (관리자 확인 대상)
  --   확정 트랜잭션이 실패해서 payment 행은 없고 payment_log 에만 남는다
  (8, @s_t1_d6, 2, 2, 15000, 30000, 'EXPIRED', UUID(),
      NOW() - INTERVAL 2 DAY + INTERVAL 10 MINUTE, NULL, NULL, NOW() - INTERVAL 2 DAY);


-- ---------------------------------------------------------------------
-- 7. payment : 확정된 예약(r1, r2, r7)은 APPROVED, 취소된 예약(r5)은 CANCELED(환불됨)
--   payment_key 는 PG사가 주는 값. 여기서는 test_pk_번호 로 흉내
-- ---------------------------------------------------------------------
INSERT INTO payment (reservation_id, payment_key, order_id, amount, status, approved_at, canceled_at) VALUES
  (1, 'test_pk_0001', 'R-1', 30000, 'APPROVED', NOW() - INTERVAL 1 DAY + INTERVAL 3 MINUTE, NULL),
  (2, 'test_pk_0002', 'R-2', 40000, 'APPROVED', NOW() - INTERVAL 2 DAY + INTERVAL 5 MINUTE, NULL),
  (5, 'test_pk_0005', 'R-5', 10000, 'CANCELED', NOW() - INTERVAL 5 DAY + INTERVAL 2 MINUTE, NOW() - INTERVAL 4 DAY),
  (7, 'test_pk_0007', 'R-7', 10000, 'APPROVED', NOW() - INTERVAL 1 HOUR + INTERVAL 4 MINUTE, NULL);


-- ---------------------------------------------------------------------
-- 8. payment_log : 결제 흐름 기록
-- ---------------------------------------------------------------------
INSERT INTO payment_log (reservation_id, payment_key, event, message, created_at) VALUES
  (1, 'test_pk_0001', 'APPROVED', NULL, NOW() - INTERVAL 1 DAY + INTERVAL 3 MINUTE),
  (2, 'test_pk_0002', 'APPROVED', NULL, NOW() - INTERVAL 2 DAY + INTERVAL 5 MINUTE),
  (5, 'test_pk_0005', 'APPROVED', NULL, NOW() - INTERVAL 5 DAY + INTERVAL 2 MINUTE),
  (5, 'test_pk_0005', 'REFUNDED', '사용자 취소 (출발 3일 전, 전액 환불)', NOW() - INTERVAL 4 DAY),
  (7, 'test_pk_0007', 'APPROVED', NULL, NOW() - INTERVAL 1 HOUR + INTERVAL 4 MINUTE),
  (8, 'test_pk_0008', 'APPROVED', NULL, NOW() - INTERVAL 2 DAY + INTERVAL 11 MINUTE),
  (8, 'test_pk_0008', 'COMPENSATION_FAILED', '예약 확정 실패(만료) 후 결제 취소 요청도 실패 - 관리자 확인 필요',
      NOW() - INTERVAL 2 DAY + INTERVAL 11 MINUTE);


-- ---------------------------------------------------------------------
-- 9. ★ 남은 자리 다시 계산: remaining = 정원 - 살아 있는 예약 인원 합계
--   PENDING도 자리를 차감한 상태이므로 포함 (CONFIRMED + PENDING)
--   이렇게 하면 손으로 계산할 필요 없이 항상 맞는다
-- ---------------------------------------------------------------------
UPDATE tour_schedule s
   SET s.remaining = s.capacity - (
         SELECT COALESCE(SUM(r.headcount), 0)
           FROM reservation r
          WHERE r.schedule_id = s.id
            AND r.status IN ('PENDING', 'CONFIRMED'));

SET SQL_SAFE_UPDATES = 1;   -- 다시 켜기 (실수로 전체 UPDATE·DELETE 하는 것을 막아 줌)


-- ---------------------------------------------------------------------
-- 10. 확인
-- ---------------------------------------------------------------------
-- 테이블별 행 수
SELECT 'member' AS t, COUNT(*) AS cnt FROM member
UNION ALL SELECT 'tour', COUNT(*) FROM tour
UNION ALL SELECT 'course_stop', COUNT(*) FROM course_stop
UNION ALL SELECT 'tour_schedule', COUNT(*) FROM tour_schedule
UNION ALL SELECT 'reservation', COUNT(*) FROM reservation
UNION ALL SELECT 'payment', COUNT(*) FROM payment
UNION ALL SELECT 'payment_log', COUNT(*) FROM payment_log;

-- 예약 상태와 active_key 를 한눈에 (계산 컬럼이 어떻게 보이는지)
SELECT r.id, m.name, t.name AS tour, s.start_at, r.headcount, r.status, r.active_key, r.expires_at
  FROM reservation r
  JOIN member m        ON m.id = r.member_id
  JOIN tour_schedule s ON s.id = r.schedule_id
  JOIN tour t          ON t.id = s.tour_id
 ORDER BY r.id;
