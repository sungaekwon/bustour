-- =====================================================================
-- 01_create_db_user.sql : 스키마 2개 + 앱 전용 계정 만들기
--
-- ★ 실행: Workbench에서 root 계정으로 접속 → 이 파일 열기 → 번개 아이콘(전체 실행)
--   한 번만 실행하면 된다. (IF NOT EXISTS 라서 두 번 실행해도 오류는 안 남)
--
-- 스키마 2개를 두는 이유
--   bustour      : 개발용. 샘플 데이터를 넣고 화면에서 확인
--   bustour_test : JUnit 전용. 동시성 테스트가 데이터를 지우고 다시 넣어도 개발 데이터는 안전
-- =====================================================================

-- utf8mb4 : 한글 + 이모지까지 저장 (그냥 utf8은 MySQL에서 3바이트까지라 이모지가 깨짐)
-- utf8mb4_0900_ai_ci : MySQL 8 기본 정렬 규칙 (ai = 악센트 무시, ci = 대소문자 무시)
CREATE DATABASE IF NOT EXISTS bustour
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS bustour_test
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS bustour_k6
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  
  
-- 앱 전용 계정 (root로 앱을 돌리지 않는다: 실수로 다른 스키마까지 지울 수 있음)
-- ★ 비밀번호를 바꾸고 실행하세요. 같은 값을 인텔리제이 환경변수 DB_PASSWORD 에 넣습니다.
-- 'localhost' = 이 PC에서 접속할 때만 허용
--   (앱이 연결할 때 Access denied 가 나면 아래 두 줄을 'bustour'@'127.0.0.1' 로 한 번 더 실행)
CREATE USER IF NOT EXISTS 'bustour'@'localhost' IDENTIFIED BY '여기에_비밀번호';


-- 이 계정은 두 스키마 안에서만 모든 권한 (다른 스키마는 못 봄)
-- ※ 로컬 개발용. 서버에서는 DROP·ALTER 를 빼고 SELECT, INSERT, UPDATE, DELETE 만 주는 게 안전
GRANT ALL PRIVILEGES ON bustour.*      TO 'bustour'@'localhost';
GRANT ALL PRIVILEGES ON bustour_test.* TO 'bustour'@'localhost';
GRANT ALL PRIVILEGES ON bustour_k6.* TO 'bustour'@'localhost';

-- 확인: 아래 결과에 bustour, bustour_test 가 보이면 성공
SHOW DATABASES LIKE 'bustour%';
SHOW GRANTS FOR 'bustour'@'localhost';
