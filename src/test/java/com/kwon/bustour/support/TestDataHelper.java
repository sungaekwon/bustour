package com.kwon.bustour.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 테스트 데이터 준비 도우미 (6단계 테스트부터 사용)
 *
 * ★ static 메서드 묶음: 테스트 클래스에서 TestDataHelper.cleanAll(jdbc) 처럼 부름
 *   (5단계 동시성 테스트는 이미 통과한 코드라 그대로 두고, 새 테스트부터 이 도우미를 쓴다)
 */
public final class TestDataHelper {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static final int TOUR_PRICE = 10000;

    private TestDataHelper() {
        // 객체를 만들지 않음 (static 메서드만)
    }

    /** 모든 테이블 비우기: 자식 → 부모 순서 (외래키) */
    public static void cleanAll(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM payment_log");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM tour_schedule");
        jdbc.update("DELETE FROM course_stop");
        jdbc.update("DELETE FROM tour");
        jdbc.update("DELETE FROM member");
    }

    /** 투어 1번 (가격 10,000원) */
    public static void insertTour(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tour (id, name, description, price, duration_min, status) "
                + "VALUES (1, '테스트 투어', '테스트용', ?, 180, 'ON_SALE')", TOUR_PRICE);
    }

    /** 회원 1 ~ count 번 (휴대폰 0109999xxxx, 비밀번호는 로그인 테스트를 하지 않으므로 아무 값) */
    public static void insertMembers(JdbcTemplate jdbc, int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(new Object[]{i, "회원" + i, String.format("0109999%04d", i)});
        }
        jdbc.batchUpdate("INSERT INTO member (id, name, phone, password_hash, role, created_at) "
                + "VALUES (?, ?, ?, '{noop}x', 'USER', NOW())", rows);
    }

    /** 회차 (투어 1번) */
    public static void insertSchedule(JdbcTemplate jdbc, long id, int capacity, LocalDateTime startAt, String status) {
        jdbc.update("INSERT INTO tour_schedule (id, tour_id, start_at, capacity, remaining, status) "
                + "VALUES (?, 1, ?, ?, ?, ?)", id, startAt, capacity, capacity, status);
    }

    public static LocalDateTime daysLater(int days) {
        return LocalDateTime.now(SEOUL).plusDays(days).withNano(0);
    }

    public static int remaining(JdbcTemplate jdbc, long scheduleId) {
        return jdbc.queryForObject("SELECT remaining FROM tour_schedule WHERE id = ?", Integer.class, scheduleId);
    }

    // ----- 7단계: 결제 테스트용 -----

    /** 예약 상태 ("PENDING", "CONFIRMED" …) */
    public static String status(JdbcTemplate jdbc, long reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId);
    }

    /** 만료 시각 바꾸기 (진짜 10분을 기다리지 않고 "시간이 지난 예약"을 만듦) */
    public static void setExpiresAt(JdbcTemplate jdbc, long reservationId, LocalDateTime expiresAt) {
        jdbc.update("UPDATE reservation SET expires_at = ? WHERE id = ?", expiresAt, reservationId);
    }

    public static int paymentCount(JdbcTemplate jdbc, long reservationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", Integer.class, reservationId);
    }

    public static String paymentStatus(JdbcTemplate jdbc, long reservationId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE reservation_id = ?", String.class, reservationId);
    }

    /** 결제 기록 개수 (event 별) */
    public static int logCount(JdbcTemplate jdbc, long reservationId, String event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_log WHERE reservation_id = ? AND event = ?",
                Integer.class, reservationId, event);
    }

    /**
     * ★ 점검 쿼리: 남은 자리 = 정원 - 살아 있는 예약(PENDING · CONFIRMED) 인원 합
     * @return 어긋난 회차 수 (0 이어야 정상)
     */
    public static int seatMismatch(JdbcTemplate jdbc, long scheduleId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM tour_schedule s
                 WHERE s.id = ?
                   AND s.capacity - s.remaining <> (
                       SELECT COALESCE(SUM(r.headcount), 0) FROM reservation r
                        WHERE r.schedule_id = s.id AND r.status IN ('PENDING', 'CONFIRMED'))
                """, Integer.class, scheduleId);
    }
}
