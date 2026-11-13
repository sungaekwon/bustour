package com.kwon.bustour.service;

import com.kwon.bustour.dto.ReservationResult;
import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * =====================================================================
 * 예약 동시성 테스트 (5단계 핵심) — "동시에 100명"은 화면을 눌러서는 재현할 수 없어서 JUnit 으로 증명
 *
 * ★ 실행 전 준비
 *   1. bustour_test 스키마에 02_tables.sql 로 테이블을 만들어 두기 (샘플 데이터는 필요 없음)
 *   2. JUnit 실행 설정에 환경변수 DB_USERNAME, DB_PASSWORD (세팅 PDF 3장 ②)
 *   → @ActiveProfiles("test") 가 application-test.yml 을 읽어 bustour_test 를 쓴다 (개발 데이터 안전)
 *
 * ★ 이 테스트 클래스에 @Transactional 을 붙이지 않은 이유 (중요)
 *   테스트에 @Transactional 을 붙이면 @BeforeEach 에서 넣은 데이터가 "커밋되지 않은 채" 남는다.
 *   다른 스레드(= 다른 DB 연결)는 커밋 안 된 데이터를 못 보므로 회차가 없다고 실패한다.
 *   그리고 서비스의 트랜잭션도 테스트 트랜잭션에 합쳐져서, 실제처럼 "요청마다 따로 커밋"이 되지 않는다.
 *   → 대신 @BeforeEach 에서 매번 테이블을 비우고 새로 넣는다.
 *
 * ★ 동시에 출발시키는 방법 (CountDownLatch = 카운트가 0이 될 때까지 기다리는 문)
 *   ready : 모든 스레드가 "준비됨"을 알릴 때까지 메인이 기다림
 *   start : 메인이 0으로 만드는 순간 모든 스레드가 "동시에" 출발
 *   done  : 모든 스레드가 끝날 때까지 메인이 기다림
 * =====================================================================
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservationServiceConcurrencyTest {

    private static final int MEMBER_COUNT = 100;
    private static final int TOUR_PRICE = 10000;
    private static final long SCHEDULE_ID = 1L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    ReservationService reservationService;

    @Autowired
    JdbcTemplate jdbc;      // 테스트 데이터 준비 · 결과 확인은 SQL 로 직접 (엔티티 · 캐시를 거치지 않은 "진짜 DB 값")

    /** 매 테스트 전: 테이블 비우고 투어 1개 + 회원 100명 넣기 */
    @BeforeEach
    void setUp() {
        // 지우는 순서: 자식 → 부모 (외래키)
        jdbc.update("DELETE FROM payment_log");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM tour_schedule");
        jdbc.update("DELETE FROM course_stop");
        jdbc.update("DELETE FROM tour");
        jdbc.update("DELETE FROM member");

        jdbc.update("INSERT INTO tour (id, name, description, price, duration_min, status) "
                + "VALUES (1, '테스트 투어', '동시성 테스트용', ?, 180, 'ON_SALE')", TOUR_PRICE);

        // 회원 1~100번 (비밀번호는 로그인하지 않으므로 아무 값)
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= MEMBER_COUNT; i++) {
            rows.add(new Object[]{i, "회원" + i, String.format("0109999%04d", i)});
        }
        jdbc.batchUpdate("INSERT INTO member (id, name, phone, password_hash, role, created_at) "
                + "VALUES (?, ?, ?, '{noop}x', 'USER', NOW())", rows);
    }

    /** 회차 넣기 (출발 시각 · 정원 · 상태를 테스트마다 다르게) */
    private void insertSchedule(long id, int capacity, LocalDateTime startAt, String status) {
        jdbc.update("INSERT INTO tour_schedule (id, tour_id, start_at, capacity, remaining, status) "
                + "VALUES (?, 1, ?, ?, ?, ?)", id, startAt, capacity, capacity, status);
    }

    private LocalDateTime threeDaysLater() {
        return LocalDateTime.now(SEOUL).plusDays(3).withNano(0);
    }

    private int remaining(long scheduleId) {
        return jdbc.queryForObject("SELECT remaining FROM tour_schedule WHERE id = ?", Integer.class, scheduleId);
    }

    private int reservationCount(long scheduleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE schedule_id = ?", Integer.class, scheduleId);
    }

    /**
     * ★ 점검 쿼리 (설계 노트 Q10): 남은 자리 = 정원 - 살아 있는 예약 인원 합계 가 맞는지
     *   동시성 버그가 있으면 이 둘이 어긋난다 (예: 자리는 줄었는데 예약이 없음 = 롤백이 안 됨)
     */
    private void assertSeatsConsistent(long scheduleId) {
        Integer mismatch = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tour_schedule s
                 WHERE s.id = ?
                   AND s.capacity - s.remaining <> (
                       SELECT COALESCE(SUM(r.headcount), 0) FROM reservation r
                        WHERE r.schedule_id = s.id AND r.status IN ('PENDING', 'CONFIRMED'))
                """, Integer.class, scheduleId);
        assertEquals(0, mismatch, "남은 자리와 예약 인원 합계가 어긋남");
    }

    /**
     * count 개의 스레드로 job 을 "동시에" 실행하고, 결과를 종류별로 센다
     *   결과 이름: "OK"(새로 예약), "OK_SAME"(같은 요청 키 → 처음 결과), ErrorCode 이름, 그 밖의 예외 클래스 이름
     *
     * @param job i 번째 스레드가 할 일 (i = 0 ~ count-1). 결과 이름을 돌려주게 아래 run() 에서 감쌈
     */
    private Map<String, Integer> runConcurrently(int count, IntConsumer job) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(count);   // 스레드 count 개 (모두 동시에 대기할 수 있게)
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        Map<String, AtomicInteger> counter = new ConcurrentHashMap<>();  // 여러 스레드가 동시에 세므로 Concurrent

        for (int i = 0; i < count; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                String result = "UNKNOWN";
                try {
                    start.await();                    // 출발 신호까지 대기
                    job.accept(idx);
                    result = lastResult.get();
                } catch (BusinessException e) {
                    result = e.getErrorCode().name(); // SOLD_OUT, DUPLICATE_RESERVATION …
                } catch (Exception e) {
                    result = e.getClass().getSimpleName();   // 예상 못 한 예외 (락 대기 초과 등)
                } finally {
                    // ★ 7단계 수정: 세기를 done.countDown() "앞"으로
                    //   (전에는 countDown 뒤에 세서, 마지막 스레드가 세기 전에 메인이 결과를 읽을 틈이 있었음)
                    counter.computeIfAbsent(result, k -> new AtomicInteger()).incrementAndGet();
                    done.countDown();
                }
                return null;
            });
        }
        ready.await();          // 모두 준비될 때까지
        start.countDown();      // 동시에 출발!
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished, "60초 안에 끝나지 않음");

        Map<String, Integer> result = new TreeMap<>();   // 이름순 정렬해서 보기 좋게
        counter.forEach((k, v) -> result.put(k, v.get()));
        System.out.println("[결과] " + result);
        return result;
    }

    /** 스레드마다 "이번 호출 결과"를 담아 두는 칸 (ThreadLocal = 스레드별로 따로 있는 변수) */
    private final ThreadLocal<String> lastResult = new ThreadLocal<>();

    private void reserveAndRecord(long memberId, long scheduleId, int headcount, String key) {
        ReservationResult r = reservationService.reserve(memberId, scheduleId, headcount, key);
        lastResult.set(r.isCreated() ? "OK" : "OK_SAME");
    }

    // =====================================================================
    // 1. 정원 (R1)
    // =====================================================================

    @Test
    @DisplayName("남은 1자리에 100명이 동시에 → 1명만 성공, 99명 SOLD_OUT, 남은 자리 0")
    void lastSeat_100people() throws InterruptedException {
        insertSchedule(SCHEDULE_ID, 1, threeDaysLater(), "OPEN");

        Map<String, Integer> result = runConcurrently(MEMBER_COUNT,
                i -> reserveAndRecord(i + 1, SCHEDULE_ID, 1, UUID.randomUUID().toString()));

        assertEquals(1, result.getOrDefault("OK", 0));
        assertEquals(99, result.getOrDefault("SOLD_OUT", 0));
        assertEquals(0, remaining(SCHEDULE_ID));
        assertEquals(1, reservationCount(SCHEDULE_ID));
        assertSeatsConsistent(SCHEDULE_ID);
    }

    @Test
    @DisplayName("정원 10에 100명이 1명씩 동시에 → 정확히 10명 성공 (초과 예약 0)")
    void capacity10_100people() throws InterruptedException {
        insertSchedule(SCHEDULE_ID, 10, threeDaysLater(), "OPEN");

        Map<String, Integer> result = runConcurrently(MEMBER_COUNT,
                i -> reserveAndRecord(i + 1, SCHEDULE_ID, 1, UUID.randomUUID().toString()));

        assertEquals(10, result.getOrDefault("OK", 0));
        assertEquals(90, result.getOrDefault("SOLD_OUT", 0));
        assertEquals(0, remaining(SCHEDULE_ID));
        assertSeatsConsistent(SCHEDULE_ID);
    }

    @Test
    @DisplayName("정원 10에 3명씩 4팀이 동시에 → 3팀(9명)만 성공, 남은 자리 1 (여러 명 예약도 정확히)")
    void groupHeadcount() throws InterruptedException {
        insertSchedule(SCHEDULE_ID, 10, threeDaysLater(), "OPEN");

        Map<String, Integer> result = runConcurrently(4,
                i -> reserveAndRecord(i + 1, SCHEDULE_ID, 3, UUID.randomUUID().toString()));

        assertEquals(3, result.getOrDefault("OK", 0));
        assertEquals(1, result.getOrDefault("SOLD_OUT", 0));
        assertEquals(1, remaining(SCHEDULE_ID));
        assertSeatsConsistent(SCHEDULE_ID);
    }

    // =====================================================================
    // 2. 1인 1회차 1예약 (R5)
    // =====================================================================

    @Test
    @DisplayName("같은 회원이 다른 요청 키로 10번 동시에 → 1건만 성공, 나머지 DUPLICATE_RESERVATION, 자리는 1만 줄어듦")
    void sameMember_differentKeys() throws InterruptedException {
        insertSchedule(SCHEDULE_ID, 40, threeDaysLater(), "OPEN");

        Map<String, Integer> result = runConcurrently(10,
                i -> reserveAndRecord(1L, SCHEDULE_ID, 1, UUID.randomUUID().toString()));

        assertEquals(1, result.getOrDefault("OK", 0));
        assertEquals(9, result.getOrDefault("DUPLICATE_RESERVATION", 0));
        // ★ 실패한 9건도 UPDATE(자리 차감)는 했다가 INSERT 실패로 롤백 → 자리가 돌아왔는지
        assertEquals(39, remaining(SCHEDULE_ID));
        assertEquals(1, reservationCount(SCHEDULE_ID));
        assertSeatsConsistent(SCHEDULE_ID);
    }

    @Test
    @DisplayName("예약이 취소 · 만료된 회차에는 같은 회원이 다시 예약할 수 있다 (active_key = NULL)")
    void rebookAfterCanceled() {
        insertSchedule(SCHEDULE_ID, 40, threeDaysLater(), "OPEN");
        ReservationResult first = reservationService.reserve(1L, SCHEDULE_ID, 1, UUID.randomUUID().toString());

        // 7단계 전이라 취소 기능이 없으므로 SQL 로 직접 취소 + 자리 반환
        jdbc.update("UPDATE reservation SET status = 'CANCELED', canceled_at = NOW() WHERE id = ?", first.getReservationId());
        jdbc.update("UPDATE tour_schedule SET remaining = remaining + 1 WHERE id = ?", SCHEDULE_ID);

        ReservationResult second = reservationService.reserve(1L, SCHEDULE_ID, 1, UUID.randomUUID().toString());
        assertTrue(second.isCreated());
        assertEquals(2, reservationCount(SCHEDULE_ID));     // 취소 1건 + 새 예약 1건
        assertSeatsConsistent(SCHEDULE_ID);
    }

    // =====================================================================
    // 3. 멱등성: 같은 요청은 한 번만 (R6)
    // =====================================================================

    @Test
    @DisplayName("같은 요청 키로 두 번 (순서대로) → 같은 예약 번호, 두 번째는 created=false, 자리는 1만 줄어듦")
    void sameKey_sequential() {
        insertSchedule(SCHEDULE_ID, 40, threeDaysLater(), "OPEN");
        String key = UUID.randomUUID().toString();

        ReservationResult first = reservationService.reserve(1L, SCHEDULE_ID, 2, key);
        ReservationResult second = reservationService.reserve(1L, SCHEDULE_ID, 2, key);

        assertTrue(first.isCreated());
        assertFalse(second.isCreated());
        assertEquals(first.getReservationId(), second.getReservationId());
        assertEquals(38, remaining(SCHEDULE_ID));
    }

    @Test
    @DisplayName("같은 요청 키로 10번 동시에 (더블클릭 폭풍) → 예약은 1건, 자리는 1만 줄어듦")
    void sameKey_concurrent() throws InterruptedException {
        insertSchedule(SCHEDULE_ID, 40, threeDaysLater(), "OPEN");
        String key = UUID.randomUUID().toString();

        Map<String, Integer> result = runConcurrently(10,
                i -> reserveAndRecord(1L, SCHEDULE_ID, 1, key));

        // 결과는 섞여서 나온다:
        //   OK 1건 + (먼저 커밋된 뒤 도착 → OK_SAME) + (동시에 ①을 통과 → INSERT 에서 uk_request_key → DUPLICATE_REQUEST)
        //   어느 쪽이든 "예약 1건, 자리 1개" 만 맞으면 된다.
        assertEquals(1, result.getOrDefault("OK", 0));
        assertEquals(10, result.getOrDefault("OK", 0) + result.getOrDefault("OK_SAME", 0)
                + result.getOrDefault("DUPLICATE_REQUEST", 0) + result.getOrDefault("DUPLICATE_RESERVATION", 0));
        assertEquals(1, reservationCount(SCHEDULE_ID));
        assertEquals(39, remaining(SCHEDULE_ID));
        assertSeatsConsistent(SCHEDULE_ID);
    }

    @Test
    @DisplayName("남의 요청 키를 보내면 → REQUEST_KEY_CONFLICT (남의 예약 정보를 돌려주지 않음)")
    void otherMembersKey() {
        insertSchedule(SCHEDULE_ID, 40, threeDaysLater(), "OPEN");
        String key = UUID.randomUUID().toString();
        reservationService.reserve(1L, SCHEDULE_ID, 1, key);

        BusinessException e = assertThrows(BusinessException.class,
                () -> reservationService.reserve(2L, SCHEDULE_ID, 1, key));
        assertEquals(ErrorCode.REQUEST_KEY_CONFLICT, e.getErrorCode());
    }

    // =====================================================================
    // 4. 마감 · 운영 중지 · 인원 (R2 · R3)
    // =====================================================================

    @Test
    @DisplayName("출발 30분 전 회차 (예약 마감 1시간 전 지남) → BOOKING_CLOSED, 자리 그대로")
    void bookingClosed() {
        insertSchedule(SCHEDULE_ID, 40, LocalDateTime.now(SEOUL).plusMinutes(30).withNano(0), "OPEN");

        BusinessException e = assertThrows(BusinessException.class,
                () -> reservationService.reserve(1L, SCHEDULE_ID, 1, UUID.randomUUID().toString()));
        assertEquals(ErrorCode.BOOKING_CLOSED, e.getErrorCode());
        assertEquals(40, remaining(SCHEDULE_ID));
    }

    @Test
    @DisplayName("운영 중지(CLOSED) 회차 → SCHEDULE_CLOSED")
    void scheduleClosed() {
        insertSchedule(SCHEDULE_ID, 40, threeDaysLater(), "CLOSED");

        BusinessException e = assertThrows(BusinessException.class,
                () -> reservationService.reserve(1L, SCHEDULE_ID, 1, UUID.randomUUID().toString()));
        assertEquals(ErrorCode.SCHEDULE_CLOSED, e.getErrorCode());
    }

    @Test
    @DisplayName("남은 2자리에 3명 → SOLD_OUT + '지금 남은 자리 2석' 안내 (방법 B: 최신 값으로 이유 판단)")
    void notEnoughSeats() {
        insertSchedule(SCHEDULE_ID, 2, threeDaysLater(), "OPEN");

        BusinessException e = assertThrows(BusinessException.class,
                () -> reservationService.reserve(1L, SCHEDULE_ID, 3, UUID.randomUUID().toString()));
        assertEquals(ErrorCode.SOLD_OUT, e.getErrorCode());
        // FOR SHARE 로 읽은 최신 남은 자리가 문장에 들어갔는지
        assertTrue(e.getMessage().contains("2석"), "안내 문장: " + e.getMessage());
        assertEquals(2, remaining(SCHEDULE_ID));          // 부분 예약 없음 (자리 그대로)
    }

    @Test
    @DisplayName("다른 사람이 먼저 가져가 매진되면, 늦게 온 요청은 '매진되었습니다' (스냅샷이 아닌 최신 값 0)")
    void soldOutMessageUsesLatestValue() {
        insertSchedule(SCHEDULE_ID, 1, threeDaysLater(), "OPEN");
        reservationService.reserve(1L, SCHEDULE_ID, 1, UUID.randomUUID().toString());   // 마지막 1자리를 1번 회원이

        BusinessException e = assertThrows(BusinessException.class,
                () -> reservationService.reserve(2L, SCHEDULE_ID, 1, UUID.randomUUID().toString()));
        assertEquals(ErrorCode.SOLD_OUT, e.getErrorCode());
        assertEquals("매진되었습니다.", e.getMessage());
    }

    @Test
    @DisplayName("인원 0명 · 11명 → INVALID_HEADCOUNT")
    void invalidHeadcount() {
        insertSchedule(SCHEDULE_ID, 40, threeDaysLater(), "OPEN");

        assertEquals(ErrorCode.INVALID_HEADCOUNT, assertThrows(BusinessException.class,
                () -> reservationService.reserve(1L, SCHEDULE_ID, 0, UUID.randomUUID().toString())).getErrorCode());
        assertEquals(ErrorCode.INVALID_HEADCOUNT, assertThrows(BusinessException.class,
                () -> reservationService.reserve(1L, SCHEDULE_ID, 11, UUID.randomUUID().toString())).getErrorCode());
    }

    // =====================================================================
    // 5. 금액 스냅샷 (R15)
    // =====================================================================

    @Test
    @DisplayName("예약 뒤 투어 가격이 바뀌어도 예약 금액은 예약할 때 가격 그대로")
    void priceSnapshot() {
        insertSchedule(SCHEDULE_ID, 40, threeDaysLater(), "OPEN");
        ReservationResult r = reservationService.reserve(1L, SCHEDULE_ID, 2, UUID.randomUUID().toString());

        jdbc.update("UPDATE tour SET price = 99000 WHERE id = 1");   // HOLD 중에 관리자가 가격 변경

        Integer amount = jdbc.queryForObject("SELECT amount FROM reservation WHERE id = ?", Integer.class, r.getReservationId());
        assertEquals(TOUR_PRICE * 2, amount);
    }
}
