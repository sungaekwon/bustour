-- =====================================================================
-- 04_check_rules.sql : 샘플 데이터 점검 + DB가 규칙을 막는지 직접 확인 (1단계 완료 기준)
--
-- ★ 실행 방법: 전체 실행(번개)이 아니라 "한 문장씩" 실행
--   커서를 문장 위에 두고 Ctrl + Enter  (번개 아이콘 옆, 커서 있는 문장만 실행)
--   일부러 오류를 내는 문장이 있어서, 전체 실행하면 첫 오류에서 멈춘다.
--
-- ★ 2번은 START TRANSACTION ~ ROLLBACK 으로 감싸서 데이터가 바뀌지 않는다
--   MySQL은 오류가 난 "문장만" 취소하고 트랜잭션은 계속 열려 있다 → 마지막 ROLLBACK으로 전부 되돌림
--   ⚠ ROLLBACK을 빼먹고 창을 닫으면? 커밋되지 않은 변경은 연결이 끊길 때 자동으로 롤백된다.
-- =====================================================================

USE bustour;
SET time_zone = '+09:00';


-- =====================================================================
-- 1. 점검 쿼리 : 남은 자리와 실제 예약 인원 합계가 다른 회차 (결과 0행 = 정상)
--    LEFT JOIN : 예약이 0건인 회차도 검사 (예약은 없는데 remaining이 줄어 있으면 그것도 잡음)
-- =====================================================================
SELECT s.id,
       s.capacity,
       s.remaining,
       s.capacity - s.remaining      AS booked_by_counter,   -- 숫자 컬럼이 말하는 예약 인원
       COALESCE(SUM(r.headcount), 0) AS booked_by_rows       -- 예약 행을 실제로 더한 인원
  FROM tour_schedule s
  LEFT JOIN reservation r
         ON r.schedule_id = s.id
        AND r.status IN ('PENDING', 'CONFIRMED')            -- PENDING도 자리를 차감했으므로 포함
 GROUP BY s.id, s.capacity, s.remaining
HAVING booked_by_counter <> booked_by_rows;


-- 테스트에 쓸 회차 번호 찾기
SET @s_last = (SELECT id FROM tour_schedule WHERE tour_id = 3 AND capacity = 5 LIMIT 1);       -- 남은 자리 1
SET @s_soon = (SELECT id FROM tour_schedule WHERE tour_id = 2
                 AND start_at BETWEEN NOW() AND NOW() + INTERVAL 1 HOUR LIMIT 1);             -- 30분 뒤 출발
SET @s_t3_d4 = (SELECT id FROM tour_schedule WHERE tour_id = 3
                 AND start_at = TIMESTAMP(CURDATE() + INTERVAL 4 DAY, '10:00:00'));           -- r5, r6, r7 회차
SELECT @s_last, @s_soon, @s_t3_d4;   -- 세 값이 모두 NULL이 아니어야 함


-- =====================================================================
-- 2. 규칙 확인 (한 문장씩 Ctrl + Enter)
-- =====================================================================
START TRANSACTION;

-- ---------------------------------------------------------------------
-- R1 정원 초과 금지: 남은 1자리에 2명 → 원자적 UPDATE가 0행
--   (앱에서는 0행이면 409 SOLD_OUT)
-- ---------------------------------------------------------------------
UPDATE tour_schedule
   SET remaining = remaining - 2
 WHERE id = @s_last AND remaining >= 2 AND status = 'OPEN';
-- 기대: 0 row(s) affected

-- R1 마지막 방어선: 조건을 "빠뜨린" 잘못된 차감 → CHECK가 막음
UPDATE tour_schedule SET remaining = remaining - 2 WHERE id = @s_last;
-- 기대: Error Code: 3819. Check constraint 'chk_schedule_remaining' is violated.

-- ---------------------------------------------------------------------
-- R2 예약 마감(출발 1시간 전): 30분 뒤 출발 회차는 자리가 있어도 0행
--   앱에서는 NOW() 대신 Clock으로 만든 "지금 + 1시간"을 파라미터로 넘긴다 (R22)
-- ---------------------------------------------------------------------
UPDATE tour_schedule
   SET remaining = remaining - 1
 WHERE id = @s_soon AND remaining >= 1 AND status = 'OPEN'
   AND start_at > NOW() + INTERVAL 1 HOUR;
-- 기대: 0 row(s) affected

-- ---------------------------------------------------------------------
-- R5 1인 1회차 1예약: 김부산(1번)은 @s_t3_d4 에 이미 CONFIRMED(r7)가 있음 → 또 PENDING 넣기
-- ---------------------------------------------------------------------
INSERT INTO reservation (schedule_id, member_id, headcount, unit_price, amount, status,
                         request_key, expires_at, created_at)
VALUES (@s_t3_d4, 1, 1, 10000, 10000, 'PENDING', UUID(), NOW() + INTERVAL 10 MINUTE, NOW());
-- 기대: Error Code: 1062. Duplicate entry '…-1-1' for key 'reservation.uk_active_member'
--       ('회차번호-회원번호-active_key' 모양. 취소·만료 행 2개(r5, r6)는 NULL이라 안 걸린 것)

-- 같은 회원이라도 "다른 회차"면 성공 (규칙은 회차마다)
INSERT INTO reservation (schedule_id, member_id, headcount, unit_price, amount, status,
                         request_key, expires_at, created_at)
VALUES (@s_last, 2, 1, 10000, 10000, 'PENDING', 'req-key-same-0000-0000-000000000000',
        NOW() + INTERVAL 10 MINUTE, NOW());
-- 기대: 1 row(s) affected  (이바다가 남은 자리 1 회차에 PENDING. 실제 앱이라면 차감 UPDATE를 먼저 함)

-- ---------------------------------------------------------------------
-- R6 같은 요청은 한 번만: 방금과 같은 request_key 로 또 넣기
-- ---------------------------------------------------------------------
INSERT INTO reservation (schedule_id, member_id, headcount, unit_price, amount, status,
                         request_key, expires_at, created_at)
VALUES (@s_soon, 2, 1, 12000, 12000, 'PENDING', 'req-key-same-0000-0000-000000000000',
        NOW() + INTERVAL 10 MINUTE, NOW());
-- 기대: Error Code: 1062. Duplicate entry 'req-key-same-…' for key 'reservation.uk_request_key'

-- ---------------------------------------------------------------------
-- R3 인원 1~10명: 11명
-- ---------------------------------------------------------------------
INSERT INTO reservation (schedule_id, member_id, headcount, unit_price, amount, status,
                         request_key, expires_at, created_at)
VALUES (@s_soon, 1, 11, 12000, 132000, 'PENDING', UUID(), NOW() + INTERVAL 10 MINUTE, NOW());
-- 기대: Error Code: 3819. Check constraint 'chk_reservation_headcount' is violated.

-- ---------------------------------------------------------------------
-- R15 금액: amount ≠ unit_price × headcount
-- ---------------------------------------------------------------------
INSERT INTO reservation (schedule_id, member_id, headcount, unit_price, amount, status,
                         request_key, expires_at, created_at)
VALUES (@s_soon, 1, 2, 12000, 100, 'PENDING', UUID(), NOW() + INTERVAL 10 MINUTE, NOW());
-- 기대: Error Code: 3819. Check constraint 'chk_reservation_amount' is violated.

-- ---------------------------------------------------------------------
-- 계산 컬럼 active_key 에 직접 값 넣기
-- ---------------------------------------------------------------------
INSERT INTO reservation (schedule_id, member_id, headcount, unit_price, amount, status,
                         request_key, expires_at, created_at, active_key)
VALUES (@s_soon, 1, 1, 12000, 12000, 'PENDING', UUID(), NOW() + INTERVAL 10 MINUTE, NOW(), 1);
-- 기대: Error Code: 3105. The value specified for generated column 'active_key' … is not allowed.

-- ---------------------------------------------------------------------
-- R12 취소는 한 번만: r1(CONFIRMED)을 "지금 CONFIRMED일 때만" 취소 → 두 번 실행
-- ---------------------------------------------------------------------
UPDATE reservation SET status = 'CANCELED', canceled_at = NOW()
 WHERE id = 1 AND member_id = 1 AND status = 'CONFIRMED';
-- 기대 (1번째): 1 row(s) affected  → 앱은 이때만 자리를 돌려줌
UPDATE reservation SET status = 'CANCELED', canceled_at = NOW()
 WHERE id = 1 AND member_id = 1 AND status = 'CONFIRMED';
-- 기대 (2번째): 0 row(s) affected  → 자리를 또 돌려주지 않음

-- 취소하면 active_key 가 자동으로 NULL 로 바뀌는지
SELECT id, status, active_key FROM reservation WHERE id = 1;
-- 기대: CANCELED | NULL

-- ---------------------------------------------------------------------
-- R19 내 예약만: 이바다(2번)가 김부산의 예약(r2)을 취소하려 함
-- ---------------------------------------------------------------------
UPDATE reservation SET status = 'CANCELED', canceled_at = NOW()
 WHERE id = 2 AND member_id = 2 AND status = 'CONFIRMED';
-- 기대: 0 row(s) affected  → 앱은 404 (남의 예약이 있다는 사실도 알려주지 않음)

-- ---------------------------------------------------------------------
-- R11 만료와 결제가 동시에: r4(PENDING, 만료 지남)를 결제 확정 시도 → 만료 시각 조건 때문에 0행
-- ---------------------------------------------------------------------
UPDATE reservation SET status = 'CONFIRMED', confirmed_at = NOW()
 WHERE id = 4 AND status = 'PENDING' AND expires_at > NOW();
-- 기대: 0 row(s) affected  → 앱은 결제를 보상 취소
UPDATE reservation SET status = 'EXPIRED'
 WHERE id = 4 AND status = 'PENDING' AND expires_at <= NOW();
-- 기대: 1 row(s) affected  → 앱은 이때만 자리 반환

-- ---------------------------------------------------------------------
-- R16 같은 결제 두 번: 이미 있는 payment_key
-- ---------------------------------------------------------------------
INSERT INTO payment (reservation_id, payment_key, amount, status, approved_at)
VALUES (3, 'test_pk_0001', 24000, 'APPROVED', NOW());
-- 기대: Error Code: 1062. Duplicate entry 'test_pk_0001' for key 'payment.uk_payment_key'

-- ---------------------------------------------------------------------
-- R14 삭제 대신 상태: 예약이 있는 회차 삭제 시도 → 외래키가 막음
-- ---------------------------------------------------------------------
DELETE FROM tour_schedule WHERE id = @s_last;
-- 기대: Error Code: 1451. Cannot delete or update a parent row: a foreign key constraint fails

-- ---------------------------------------------------------------------
-- 끝: 전부 되돌리기
-- ---------------------------------------------------------------------
ROLLBACK;

-- 되돌려졌는지 확인: r1이 다시 CONFIRMED, 점검 쿼리(1번)도 다시 0행
SELECT id, status, active_key FROM reservation WHERE id IN (1, 4);


-- =====================================================================
-- 3. (참고) 화면에서 쓸 조회 미리 해 보기
-- =====================================================================
-- 메인: 판매 중인 투어만 (숨김 4번은 안 나와야 함)
SELECT id, name, price, duration_min FROM tour WHERE status = 'ON_SALE' ORDER BY id;

-- 코스: 1번 투어 정류장 순서대로
SELECT seq, place_name FROM course_stop WHERE tour_id = 1 ORDER BY seq;

-- 회차: 1번 투어의 예약 가능한 회차 (OPEN + 출발 1시간 전보다 앞) + 남은 자리
SELECT id, start_at, capacity, remaining, status
  FROM tour_schedule
 WHERE tour_id = 1 AND start_at > NOW() + INTERVAL 1 HOUR
 ORDER BY start_at;

-- (참고) 회차 목록이 인덱스를 타는지: key 칸에 uk_schedule_tour_start 가 보이면 OK
EXPLAIN SELECT id, start_at, remaining FROM tour_schedule
 WHERE tour_id = 1 AND start_at > NOW() ORDER BY start_at;
