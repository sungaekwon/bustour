-- =====================================================================
-- 02_tables.sql : 테이블 7개 만들기
--
-- ★ 실행 방법 (두 번 실행)
--   1) 맨 위 USE bustour;      그대로 → 전체 실행 (개발용)
--   2) USE bustour_test; 로 바꿔서 → 전체 실행 (JUnit용, 샘플 데이터는 넣지 않음)
--
-- ⚠ 맨 앞의 DROP TABLE 은 "있으면 지우고 새로 만들기"다. 넣어 둔 데이터가 전부 사라진다.
--   테이블을 고쳐서 다시 만들 때만 실행할 것.
--
-- ★ 순서가 중요하다
--   만들 때: 부모 → 자식 (member, tour → course_stop, tour_schedule → reservation → payment, payment_log)
--   지울 때: 자식 → 부모 (외래키가 가리키는 부모를 먼저 지우면 오류)
--
-- 이름 규칙
--   uk_ = UNIQUE KEY(중복 금지 인덱스), idx_ = 일반 인덱스, fk_ = 외래키, chk_ = CHECK 제약
--   시각 컬럼은 ~_at, 외래키 컬럼은 테이블명_id
--
-- 시각 컬럼에 DEFAULT CURRENT_TIMESTAMP 를 쓰지 않은 이유 (규칙 R22)
--   DB의 "지금"은 DB 서버 시간대(서버 DB는 UTC일 수 있음)라서, 앱이 Clock(Asia/Seoul)으로 넣는다.
-- =====================================================================

USE bustour;

DROP TABLE IF EXISTS payment_log;
DROP TABLE IF EXISTS payment;
DROP TABLE IF EXISTS reservation;
DROP TABLE IF EXISTS tour_schedule;
DROP TABLE IF EXISTS course_stop;
DROP TABLE IF EXISTS tour;
DROP TABLE IF EXISTS member;


-- ---------------------------------------------------------------------
-- member : 회원
-- ---------------------------------------------------------------------
CREATE TABLE member (
  id            BIGINT       NOT NULL AUTO_INCREMENT,   -- 회원 번호
  name          VARCHAR(50)  NOT NULL,                  -- 이름
  phone         VARCHAR(20)  NOT NULL,                  -- 휴대폰 = 로그인 아이디. 숫자만 저장 (01012345678)
                                                        --   하이픈 섞인 값과 다른 값으로 취급되므로 서버에서 정규화 후 저장
  password_hash VARCHAR(100) NOT NULL,                  -- BCrypt 결과 60자 + 앞의 {bcrypt} 8자 = 68자 → 여유 있게 100
  role          VARCHAR(20)  NOT NULL DEFAULT 'USER',   -- USER / ADMIN
  created_at    DATETIME     NOT NULL,                  -- 가입 시각 (앱이 넣음)
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_phone (phone),                   -- R9 휴대폰 하나에 계정 하나
  CONSTRAINT chk_member_role CHECK (role IN ('USER', 'ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ---------------------------------------------------------------------
-- tour : 투어 종류 (해운대 야경, 태종대 …)
-- ---------------------------------------------------------------------
CREATE TABLE tour (
  id           BIGINT       NOT NULL AUTO_INCREMENT,    -- 투어 번호
  name         VARCHAR(100) NOT NULL,                   -- 투어 이름
  description  TEXT         NOT NULL,                   -- 투어 소개 (긴 글)
  price        INT          NOT NULL,                   -- 1인 가격 (원). 결정: 투어 단위 가격 하나, 무료 투어 없음
  duration_min INT          NOT NULL,                   -- 소요 시간 (분)
  image_key    VARCHAR(255) NULL,                       -- 대표 사진의 S3 키 (예: tours/1/main.jpg). URL 전체가 아니라 키만
  status       VARCHAR(20)  NOT NULL DEFAULT 'ON_SALE', -- ON_SALE(판매 중) / HIDDEN(숨김: 목록에서 빠짐)
                                                        --   숨길 때 앞으로 남은 회차도 CLOSED로 바꿈 → 예약은 회차가 막음
  PRIMARY KEY (id),
  CONSTRAINT chk_tour_price    CHECK (price > 0),
  CONSTRAINT chk_tour_duration CHECK (duration_min > 0),
  CONSTRAINT chk_tour_status   CHECK (status IN ('ON_SALE', 'HIDDEN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ---------------------------------------------------------------------
-- course_stop : 코스 정류장 (투어 1개 : 정류장 N개)
-- ---------------------------------------------------------------------
CREATE TABLE course_stop (
  id          BIGINT        NOT NULL AUTO_INCREMENT,    -- 정류장 번호
  tour_id     BIGINT        NOT NULL,                   -- 어느 투어의 정류장인지
  seq         INT           NOT NULL,                   -- 방문 순서 1, 2, 3 …
  place_name  VARCHAR(100)  NOT NULL,                   -- 장소 이름
  description VARCHAR(500)  NULL,                       -- 장소 설명
  lat         DECIMAL(10,7) NULL,                       -- 위도 (소수 7자리 ≈ 1cm. DOUBLE은 저장 오차가 생길 수 있음)
  lng         DECIMAL(10,7) NULL,                       -- 경도
  image_key   VARCHAR(255)  NULL,                       -- 장소 사진의 S3 키 (없으면 NULL)
  PRIMARY KEY (id),
  UNIQUE KEY uk_stop_tour_seq (tour_id, seq),           -- R8 같은 투어에 같은 순서 번호 금지
                                                        --   tour_id가 맨 앞 → 외래키용 인덱스로도 재사용됨
  CONSTRAINT fk_stop_tour FOREIGN KEY (tour_id) REFERENCES tour (id),
  CONSTRAINT chk_stop_seq CHECK (seq > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ---------------------------------------------------------------------
-- tour_schedule : 회차 = 투어 + 출발 시각 (좌석을 회차별 행에 둠 → 락이 회차마다 나뉨)
-- ---------------------------------------------------------------------
CREATE TABLE tour_schedule (
  id        BIGINT      NOT NULL AUTO_INCREMENT,        -- 회차 번호
  tour_id   BIGINT      NOT NULL,                       -- 어느 투어의 회차인지
  start_at  DATETIME    NOT NULL,                       -- 출발 시각 (한국 시각)
  capacity  INT         NOT NULL,                       -- 정원 (버스 좌석 수)
  remaining INT         NOT NULL,                       -- 남은 자리. ★ 원자적 UPDATE로만 바꾼다 (R1, R23)
  status    VARCHAR(20) NOT NULL DEFAULT 'OPEN',        -- OPEN / CLOSED(운영 중지)
                                                        --   매진은 상태가 아니라 remaining = 0 으로 판단 (진실의 원천 하나)
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule_tour_start (tour_id, start_at), -- R7 같은 투어 같은 출발 시각 회차 1개
                                                         --   투어별 회차 목록 조회 + 외래키 인덱스로도 쓰임
  CONSTRAINT fk_schedule_tour FOREIGN KEY (tour_id) REFERENCES tour (id),
  CONSTRAINT chk_schedule_capacity  CHECK (capacity > 0),
  -- ★ 마지막 방어선: 조건 없는 차감(음수)이나 이중 취소(정원 초과)를 "조용히 저장" 대신 오류(3819)로
  CONSTRAINT chk_schedule_remaining CHECK (remaining BETWEEN 0 AND capacity),
  CONSTRAINT chk_schedule_status    CHECK (status IN ('OPEN', 'CLOSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ---------------------------------------------------------------------
-- reservation : 예약
--   상태 흐름: PENDING(결제 대기, 자리 차감, 10분) → CONFIRMED / EXPIRED / CANCELED
--   결정: 무료 투어 없음 → 모든 예약은 PENDING으로 시작 → expires_at 은 항상 값이 있음(NOT NULL)
-- ---------------------------------------------------------------------
CREATE TABLE reservation (
  id           BIGINT      NOT NULL AUTO_INCREMENT,     -- 예약 번호
  schedule_id  BIGINT      NOT NULL,                    -- 어느 회차인지
  member_id    BIGINT      NOT NULL,                    -- 누가 예약했는지
  headcount    INT         NOT NULL,                    -- 인원. 결정: 최대 10명
  unit_price   INT         NOT NULL,                    -- 예약 시점의 1인 가격 (tour.price 복사 = 스냅샷)
                                                        --   HOLD 중에 관리자가 가격을 바꿔도 결제 금액 검증이 안 틀어짐 (R15)
  amount       INT         NOT NULL,                    -- 결제할 총금액 = unit_price × headcount
  status       VARCHAR(20) NOT NULL,                    -- PENDING / CONFIRMED / EXPIRED / CANCELED
  request_key  CHAR(36)    NOT NULL,                    -- 멱등성 키: 예약 화면을 열 때 UUID 1번 생성 (R6)
  expires_at   DATETIME    NOT NULL,                    -- HOLD 만료 시각 = 만든 시각 + 10분
  confirmed_at DATETIME    NULL,                        -- 결제 확정 시각
  canceled_at  DATETIME    NULL,                        -- 취소 시각 (결정: 출발 3일 전까지, 전액 환불)
  created_at   DATETIME    NOT NULL,                    -- 예약 시각

  -- ★ 계산 컬럼: 사람이 넣지 않고 MySQL이 status를 보고 계산 (엑셀 수식 칸)
  --   살아 있는 예약(PENDING, CONFIRMED) = 1, 취소·만료 = NULL
  --   유니크 인덱스는 NULL끼리 같다고 보지 않는다 → 취소·만료 기록은 몇 개든 OK, 살아 있는 예약은 1개만
  --   VIRTUAL = 테이블에 저장하지 않고 읽을 때 계산 (인덱스 안에는 값이 저장됨)
  --   INSERT·UPDATE 문에 이 컬럼을 쓰면 오류 3105 → JPA 엔티티에는 매핑하지 않는다
  active_key   TINYINT GENERATED ALWAYS AS (IF(status IN ('PENDING', 'CONFIRMED'), 1, NULL)) VIRTUAL,

  PRIMARY KEY (id),
  -- R5 한 사람은 한 회차에 살아 있는 예약 1개 (취소 후 재예약 가능)
  --   "내가 이 회차를 이미 예약했나?" 조회(PENDING 이어서 결제 화면)에도 이 인덱스를 씀
  UNIQUE KEY uk_active_member (schedule_id, member_id, active_key),
  UNIQUE KEY uk_request_key (request_key),              -- R6 같은 요청은 한 번만
  KEY idx_member_created (member_id, created_at),       -- 마이페이지 "내 예약" (최신순) + member 외래키 인덱스
  KEY idx_status_expires (status, expires_at),          -- 만료 스케줄러: WHERE status='PENDING' AND expires_at <= ?
  CONSTRAINT fk_reservation_schedule FOREIGN KEY (schedule_id) REFERENCES tour_schedule (id),
  CONSTRAINT fk_reservation_member   FOREIGN KEY (member_id)   REFERENCES member (id),
  CONSTRAINT chk_reservation_headcount CHECK (headcount BETWEEN 1 AND 10),   -- R3 (DTO 검증과 이중으로)
  CONSTRAINT chk_reservation_amount    CHECK (amount = unit_price * headcount AND unit_price > 0),
  CONSTRAINT chk_reservation_status    CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED', 'CANCELED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ---------------------------------------------------------------------
-- payment : 결제 (예약 1건 : 승인된 결제 1건)
-- ---------------------------------------------------------------------
CREATE TABLE payment (
  id             BIGINT       NOT NULL AUTO_INCREMENT,  -- 결제 번호
  reservation_id BIGINT       NOT NULL,                 -- 어느 예약의 결제인지
  payment_key    VARCHAR(200) NOT NULL,                 -- PG사가 주는 결제 번호
  order_id       VARCHAR(64)  NULL,                     -- PG에 보내는 주문 번호 (보류: PG사 정할 때 형식 확정)
  amount         INT          NOT NULL,                 -- 승인된 금액 (= reservation.amount 이어야 함)
  status         VARCHAR(20)  NOT NULL,                 -- APPROVED(승인) / CANCELED(환불됨)
  approved_at    DATETIME     NOT NULL,                 -- 승인 시각
  canceled_at    DATETIME     NULL,                     -- 환불 시각 (결정: 전액 환불만 → 환불 금액 컬럼 불필요)
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_key (payment_key),              -- R16 같은 결제를 두 번 확정 금지 (웹훅 두 번 와도 1번)
  UNIQUE KEY uk_payment_reservation (reservation_id),   -- 예약 1건에 결제 1건 + 외래키 인덱스
  CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
  CONSTRAINT chk_payment_amount CHECK (amount > 0),
  CONSTRAINT chk_payment_status CHECK (status IN ('APPROVED', 'CANCELED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ---------------------------------------------------------------------
-- payment_log : 결제 기록 (추가만 하고 수정·삭제하지 않음)
--   보상 처리 추적용. 서비스에서 REQUIRES_NEW(별도 트랜잭션)로 넣어 바깥이 롤백돼도 남게 한다 (R18)
-- ---------------------------------------------------------------------
CREATE TABLE payment_log (
  id             BIGINT       NOT NULL AUTO_INCREMENT,  -- 기록 번호
  reservation_id BIGINT       NOT NULL,                 -- 어느 예약의 기록인지
  payment_key    VARCHAR(200) NULL,                     -- 결제 번호 (payment 행이 없어도 남겨야 해서 여기에도 둠)
  event          VARCHAR(40)  NOT NULL,                 -- AMOUNT_MISMATCH / APPROVED / CANCELED_BY_COMPENSATION
                                                        -- / COMPENSATION_FAILED / REFUNDED / REFUND_FAILED
  message        VARCHAR(500) NULL,                     -- 실패 이유 등 (카드 정보는 절대 저장하지 않음)
  created_at     DATETIME     NOT NULL,                 -- 기록 시각
  PRIMARY KEY (id),
  KEY idx_log_reservation (reservation_id, created_at), -- 예약별 기록을 시간순으로 + 외래키 인덱스
  CONSTRAINT fk_log_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
  CONSTRAINT chk_log_event CHECK (event IN ('AMOUNT_MISMATCH', 'APPROVED', 'CANCELED_BY_COMPENSATION',
                                            'COMPENSATION_FAILED', 'REFUNDED', 'REFUND_FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ---------------------------------------------------------------------
-- 확인: 테이블 7개가 보이면 성공
-- ---------------------------------------------------------------------
SHOW TABLES;

-- (선택) 외래키가 인덱스를 따로 만들었는지 확인해 보기
--   uk_active_member 처럼 외래키 컬럼이 맨 앞인 인덱스가 있으면 MySQL이 그걸 재사용한다.
--   재사용할 수 없다고 판단하면 fk 이름과 같은 인덱스를 자동으로 하나 더 만든다.
SHOW INDEX FROM reservation;
