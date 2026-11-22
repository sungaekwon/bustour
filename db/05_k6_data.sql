-- =====================================================================
-- 05_k6_data.sql : 9단계 k6 부하테스트용 데이터 (스키마 bustour_k6)
--
-- ★ 왜 따로 스키마인가: 측정 전에 매번 비우고 새로 넣는다 → 개발 데이터(bustour)를 지키려고
-- ★ 실행 순서 (Workbench, 3307)
--   1) CREATE DATABASE bustour_k6 ... (아래 1번)
--   2) USE bustour_k6; 02_tables.sql 실행 (테이블 7개)
--   3) 이 파일의 2번부터 실행 (측정할 때마다 다시 실행 = 초기화)
-- ★ 회원 500명: 휴대폰 0108888 0001 ~ 0500, 비밀번호 모두 test1234! ([추가]2026.11.14 cost 4 해시 — 아래 4번)
-- =====================================================================

-- 1. 스키마 + 권한 (처음 한 번)
-- CREATE DATABASE IF NOT EXISTS bustour_k6 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- GRANT ALL PRIVILEGES ON bustour_k6.* TO 'bustour'@'localhost';
-- [추가]2026.11.14권성애 — 서버(docker MySQL)에서는 계정이 'bustour'@'%' (어느 컨테이너에서든)
-- GRANT ALL PRIVILEGES ON bustour_k6.* TO 'bustour'@'%';

-- 2. 초기화 (측정마다)
USE bustour_k6;
SET SQL_SAFE_UPDATES = 0;
DELETE FROM payment_log;
DELETE FROM payment;
DELETE FROM reservation;
DELETE FROM tour_schedule;
DELETE FROM course_stop;
DELETE FROM tour;
DELETE FROM member;
SET SQL_SAFE_UPDATES = 1;

-- 3. 투어 1개 + 회차 2개
--   회차 1: 정원 100석 (동시 예약 → 정원 초과 0건 확인용)
--   회차 2: 정원 5000석 (예약 + 결제 흐름 측정용, 자리가 모자라지 않게)
INSERT INTO tour (id, name, description, price, duration_min, status)
VALUES (1, 'k6 측정 투어', '부하테스트용', 10000, 180, 'ON_SALE');
INSERT INTO tour_schedule (id, tour_id, start_at, capacity, remaining, status) VALUES
  (1, 1, TIMESTAMP(CURDATE() + INTERVAL 10 DAY, '10:00:00'), 100, 100, 'OPEN'),
  (2, 1, TIMESTAMP(CURDATE() + INTERVAL 10 DAY, '14:00:00'), 5000, 5000, 'OPEN');

-- 3. 투어 1개 + 회차 3개
--   회차 1: 정원 100석 (동시 예약 → 정원 초과 0건 확인용)
--   회차 2: 정원 5000석 (예약 + 결제 흐름 측정용, 자리가 모자라지 않게)
--   회차 3: 정원 5000석 (행 나누기 실험 SCHEDULES=2,3 용) [추가]2026.11.22권성애 — 초기화마다 자동으로 생기게
INSERT INTO tour (id, name, description, price, duration_min, status)
VALUES (1, 'k6 측정 투어', '부하테스트용', 10000, 180, 'ON_SALE');
INSERT INTO tour_schedule (id, tour_id, start_at, capacity, remaining, status) VALUES
(1, 1, TIMESTAMP(CURDATE() + INTERVAL 10 DAY, '10:00:00'), 100, 100, 'OPEN'),
(2, 1, TIMESTAMP(CURDATE() + INTERVAL 10 DAY, '14:00:00'), 5000, 5000, 'OPEN'),
(3, 1, TIMESTAMP(CURDATE() + INTERVAL 10 DAY, '19:00:00'), 5000, 5000, 'OPEN');
-- 4. 회원 500명 (재귀 CTE 로 1~500)
-- [추가]2026.11.14권성애 — 보류했던 개선 ①: 비밀번호 해시를 cost 10 → 4
--   $2a$10$… 의 10 = 해시를 2의 10제곱(1024)번 반복 → 로그인 1번에 수십 ms 의 CPU
--   $2a$04$… 의 4  = 2의 4제곱(16)번 → 약 64배 가벼움 (비밀번호는 그대로 test1234!)
--   setup 에서 500명이 로그인할 때 서버 CPU 를 해시 계산에 다 써 버리는 것을 막는다
--   ★ bustour_k6(테스트 전용)에만 쓴다. 실제 회원은 앱이 cost 10 으로 만든다 (가벼운 해시 = 깨기도 쉬움)
--   스프링의 BCrypt 는 해시 안에 적힌 cost 를 읽어 비교하므로 앱 코드는 고칠 것이 없다
SET SESSION cte_max_recursion_depth = 1000;
INSERT INTO member (id, name, phone, password_hash, role, created_at)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 500)
SELECT i, CONCAT('k6회원', i), CONCAT('0108888', LPAD(i, 4, '0')),
       '{bcrypt}$2a$04$M2fnWUENJ0dPxE/ZPm2sAOvDDAHJqm/lpUJyBNtgNYFOvw67ZPuoG', 'USER', NOW()   -- [추가]2026.11.14권성애 cost 4
  FROM n;

-- 5. 측정 뒤 점검 (0 이어야 정상: 남은 자리 = 정원 - 살아 있는 예약 인원)
-- SELECT s.id, s.capacity, s.remaining,
--        (SELECT COALESCE(SUM(headcount),0) FROM reservation r
--          WHERE r.schedule_id = s.id AND r.status IN ('PENDING','CONFIRMED')) AS 예약인원
--   FROM tour_schedule s;

SELECT COUNT(*) FROM bustour_k6.member;                                  -- 500
SELECT id, capacity, remaining, start_at FROM bustour_k6.tour_schedule;  -- 1: 100/100, 2: 5000/5000 (10일 뒤)