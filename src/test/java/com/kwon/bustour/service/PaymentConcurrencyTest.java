package com.kwon.bustour.service;

import com.kwon.bustour.dto.PaymentResult;
import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import com.kwon.bustour.pg.FakePgClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static com.kwon.bustour.support.TestDataHelper.SEOUL;
import static com.kwon.bustour.support.TestDataHelper.TOUR_PRICE;
import static com.kwon.bustour.support.TestDataHelper.cleanAll;
import static com.kwon.bustour.support.TestDataHelper.daysLater;
import static com.kwon.bustour.support.TestDataHelper.insertMembers;
import static com.kwon.bustour.support.TestDataHelper.insertSchedule;
import static com.kwon.bustour.support.TestDataHelper.insertTour;
import static com.kwon.bustour.support.TestDataHelper.logCount;
import static com.kwon.bustour.support.TestDataHelper.paymentCount;
import static com.kwon.bustour.support.TestDataHelper.paymentStatus;
import static com.kwon.bustour.support.TestDataHelper.remaining;
import static com.kwon.bustour.support.TestDataHelper.seatMismatch;
import static com.kwon.bustour.support.TestDataHelper.setExpiresAt;
import static com.kwon.bustour.support.TestDataHelper.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * =====================================================================
 * 결제 · 만료 · 포기 · 취소 동시성 테스트 (7단계 핵심)
 *
 * ★ 준비는 5단계와 같다: bustour_test 스키마 + JUnit 환경변수 DB_USERNAME, DB_PASSWORD
 *   @Transactional 을 붙이지 않는다 (여러 스레드가 각자 커밋하는 "진짜" 상황을 만들어야 하므로 — 질문 PDF Q34)
 *
 * ★ 가짜 PG(FakePgClient)로 상황 만들기
 *   setApproveHook : 승인 "도중"에 다른 일(만료 스케줄러)을 끼워 넣음 → 만료 vs 결제 경합을 매번 같은 순서로 재현
 *   setCancelFails : PG 취소 실패 → 보상 실패 기록 확인
 *   cancelCount 등  : PG 가 실제로 몇 번 불렸나 → "더블클릭인데 보상 취소가 불리지 않았다" 증명
 *
 * ★ 모든 테스트 끝에 점검 쿼리 (남은 자리 = 정원 - 살아 있는 예약 인원)
 * =====================================================================
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentConcurrencyTest {

    private static final long SCHEDULE_ID = 1L;          // 출발 5일 뒤 (취소 가능)
    private static final long SCHEDULE_SOON = 2L;        // 출발 2일 뒤 (취소 마감 지남)
    private static final int CAPACITY = 10;
    private static final long MEMBER = 1L;
    private static final long OTHER_MEMBER = 2L;

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired ReservationStateService stateService;
    @Autowired HoldExpiryScheduler scheduler;
    @Autowired FakePgClient pg;                           // 테스트 설정은 가짜 PG → 이 빈이 PgClient
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanAll(jdbc);
        insertTour(jdbc);
        insertMembers(jdbc, 20);
        insertSchedule(jdbc, SCHEDULE_ID, CAPACITY, daysLater(5), "OPEN");
        insertSchedule(jdbc, SCHEDULE_SOON, CAPACITY, daysLater(2), "OPEN");
        pg.reset();
    }

    @AfterEach
    void tearDown() {
        pg.reset();                                       // 다른 테스트 클래스에 영향이 없게 (같은 스프링 컨텍스트를 공유함)
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    /** 실제 예약 서비스로 PENDING 예약 만들기 (자리도 실제로 줄어듦) */
    private long reserve(long memberId, long scheduleId, int headcount) {
        return reservationService.reserve(memberId, scheduleId, headcount, UUID.randomUUID().toString())
                .getReservationId();
    }

    private String newKey() {
        return "fake_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 예약 + 결제까지 끝낸 확정 예약 */
    private long confirmed(long memberId, long scheduleId, int headcount) {
        long id = reserve(memberId, scheduleId, headcount);
        paymentService.confirm(memberId, id, newKey(), TOUR_PRICE * headcount);
        return id;
    }

    private ErrorCode codeOf(Runnable r) {
        BusinessException e = assertThrows(BusinessException.class, r::run);
        return e.getErrorCode();
    }

    /**
     * count 개의 일을 동시에 출발시키고 결과 이름별로 센다 (5단계와 같은 CountDownLatch 3개 방식)
     *   job 이 돌려준 글자 = 결과 이름 / BusinessException = ErrorCode 이름 / 그 밖의 예외 = 클래스 이름
     */
    private Map<String, Integer> runConcurrently(int count, IntFunction<Callable<String>> jobOf)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        Map<String, AtomicInteger> counter = new ConcurrentHashMap<>();

        for (int i = 0; i < count; i++) {
            Callable<String> job = jobOf.apply(i);
            pool.submit(() -> {
                ready.countDown();
                String result = "UNKNOWN";
                try {
                    start.await();
                    result = job.call();
                } catch (BusinessException e) {
                    result = e.getErrorCode().name();
                } catch (Exception e) {
                    result = e.getClass().getSimpleName();
                } finally {
                    // 세기를 done.countDown() "앞"에: 메인 스레드가 결과를 읽을 때 이미 세어져 있게
                    counter.computeIfAbsent(result, k -> new AtomicInteger()).incrementAndGet();
                    done.countDown();
                }
                return null;
            });
        }
        ready.await();
        start.countDown();                                // 동시에 출발
        assertTrue(done.await(30, TimeUnit.SECONDS), "30초 안에 끝나지 않음");
        pool.shutdown();

        Map<String, Integer> result = new TreeMap<>();
        counter.forEach((k, v) -> result.put(k, v.get()));
        System.out.println("결과: " + result);
        return result;
    }

    // =====================================================================
    // 결제 확정
    // =====================================================================

    @Test
    @DisplayName("① 정상 결제 → CONFIRMED + 결제 1건 + APPROVED 기록, PG 취소 없음")
    void confirm_success() {
        long id = reserve(MEMBER, SCHEDULE_ID, 2);

        PaymentResult r = paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE * 2);

        assertTrue(r.created());
        assertEquals("CONFIRMED", status(jdbc, id));
        assertEquals(1, paymentCount(jdbc, id));
        assertEquals("APPROVED", paymentStatus(jdbc, id));
        assertEquals(1, logCount(jdbc, id, "APPROVED"));
        assertEquals(0, pg.cancelCalls());
        assertEquals(CAPACITY - 2, remaining(jdbc, SCHEDULE_ID));
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("② ★ 더블클릭: 같은 결제 번호로 10번 동시 확정 → 결제 1건, 보상 취소 0번 (보상 버그 없음)")
    void confirm_sameKeyTenTimes_noCompensation() throws InterruptedException {
        long id = reserve(MEMBER, SCHEDULE_ID, 2);
        String key = newKey();
        pg.setApproveDelayMs(100);                        // PG 승인 구간을 겹치게 (모두 승인 중일 때 한 명이 먼저 확정)

        Map<String, Integer> result = runConcurrently(10, i -> () -> {
            PaymentResult r = paymentService.confirm(MEMBER, id, key, TOUR_PRICE * 2);
            return r.created() ? "PAID" : "PAID_ALREADY";
        });

        // 10번 모두 성공으로 응답 (1번은 새로 확정, 나머지는 "이미 확정됨")
        assertEquals(1, result.getOrDefault("PAID", 0));
        assertEquals(9, result.getOrDefault("PAID_ALREADY", 0));
        // ★ 핵심: 확정 실패(0행)한 9건이 보상하지 않았다 → PG 취소가 한 번도 불리지 않음
        assertEquals(0, pg.cancelCalls(), "같은 결제를 보상으로 취소하면 안 됨");
        assertFalse(pg.isCanceled(key));
        assertEquals("CONFIRMED", status(jdbc, id));
        assertEquals(1, paymentCount(jdbc, id));
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("③ 두 탭: 다른 결제 번호 5개 동시 확정 → 1건만 확정, 나머지 승인된 결제는 모두 보상 취소")
    void confirm_differentKeys_onlyOneAndRefundOthers() throws InterruptedException {
        long id = reserve(MEMBER, SCHEDULE_ID, 1);
        pg.setApproveDelayMs(100);

        Map<String, Integer> result = runConcurrently(5, i -> () -> {
            paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE);
            return "PAID";
        });

        assertEquals(1, result.getOrDefault("PAID", 0));
        int refunded = result.getOrDefault(ErrorCode.DUPLICATE_PAYMENT_REFUNDED.name(), 0);
        int rejectedBeforePg = result.getOrDefault(ErrorCode.ALREADY_PAID.name(), 0);   // 늦게 와서 PG 를 안 부른 요청
        assertEquals(4, refunded + rejectedBeforePg);
        // 승인됐는데 확정 못 한 결제 수 = 보상 취소된 결제 수 (돈이 두 번 나간 채 남지 않음)
        assertEquals(refunded, pg.canceledCount());
        assertEquals(refunded, logCount(jdbc, id, "CANCELED_BY_COMPENSATION"));
        assertEquals(1, paymentCount(jdbc, id));
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("④ ★ 만료 vs 결제: PG 승인 도중 만료되면 확정 0행 → 보상 취소, 자리는 한 번만 반환")
    void confirm_expiredDuringApprove_compensated() {
        long id = reserve(MEMBER, SCHEDULE_ID, 3);
        String key = newKey();
        // 승인 "도중"에 스케줄러가 돈 상황: 미래 시각(1시간 뒤)을 "지금"으로 넣어 이 예약을 만료시킴
        //   → 확정 UPDATE 는 status = 'PENDING' 조건이 안 맞아 0행 → 보상
        pg.setApproveHook(() -> stateService.expire(id, LocalDateTime.now(SEOUL).plusHours(1)));

        assertEquals(ErrorCode.PAYMENT_CANCELED_BY_SYSTEM,
                codeOf(() -> paymentService.confirm(MEMBER, id, key, TOUR_PRICE * 3)));

        assertEquals("EXPIRED", status(jdbc, id));
        assertEquals(0, paymentCount(jdbc, id));                      // 확정 안 됨 → 결제 행 없음
        assertTrue(pg.isCanceled(key), "승인된 결제는 보상으로 취소돼야 함");
        assertEquals(1, logCount(jdbc, id, "CANCELED_BY_COMPENSATION"));
        assertEquals(CAPACITY, remaining(jdbc, SCHEDULE_ID));         // 자리 3석 반환 (한 번만)
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("⑤ 결제가 먼저 끝나면 만료는 0행 → 아무것도 바뀌지 않음 (규칙 R11)")
    void expire_afterConfirm_doesNothing() {
        long id = confirmed(MEMBER, SCHEDULE_ID, 2);

        boolean expired = stateService.expire(id, LocalDateTime.now(SEOUL).plusHours(1));

        assertFalse(expired);
        assertEquals("CONFIRMED", status(jdbc, id));
        assertEquals(CAPACITY - 2, remaining(jdbc, SCHEDULE_ID));     // 자리가 잘못 돌아오지 않음
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("⑥ 만료 시각이 지난 PENDING 결제 → PG 를 부르지 않고 PAYMENT_EXPIRED")
    void confirm_afterExpiresAt_rejectedBeforePg() {
        long id = reserve(MEMBER, SCHEDULE_ID, 1);
        setExpiresAt(jdbc, id, LocalDateTime.now(SEOUL).minusMinutes(1));   // 스케줄러는 아직 안 돈 상태

        assertEquals(ErrorCode.PAYMENT_EXPIRED,
                codeOf(() -> paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE)));
        assertEquals(0, pg.approveCalls(), "돈이 움직이기 전에 걸러야 함");
        assertEquals("PENDING", status(jdbc, id));
    }

    @Test
    @DisplayName("⑦ 보상 실패: 만료 + PG 취소 실패 → COMPENSATION_FAILED 기록 (사람이 확인)")
    void confirm_compensationFails_logged() {
        long id = reserve(MEMBER, SCHEDULE_ID, 1);
        String key = newKey();
        pg.setApproveHook(() -> stateService.expire(id, LocalDateTime.now(SEOUL).plusHours(1)));
        pg.setCancelFails(true);

        assertEquals(ErrorCode.COMPENSATION_FAILED,
                codeOf(() -> paymentService.confirm(MEMBER, id, key, TOUR_PRICE)));

        assertEquals(1, logCount(jdbc, id, "COMPENSATION_FAILED"));
        // 결제 번호가 기록에 남아 있어야 PG 관리자 화면에서 찾아 취소할 수 있다
        assertEquals(key, jdbc.queryForObject(
                "SELECT payment_key FROM payment_log WHERE reservation_id = ? AND event = 'COMPENSATION_FAILED'",
                String.class, id));
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("⑧ 금액 조작 → PG 를 부르지 않고 AMOUNT_MISMATCH + 기록, 예약은 PENDING 그대로")
    void confirm_amountTampered() {
        long id = reserve(MEMBER, SCHEDULE_ID, 2);

        assertEquals(ErrorCode.AMOUNT_MISMATCH,
                codeOf(() -> paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE * 2 - 1000)));

        assertEquals(0, pg.approveCalls());
        assertEquals(1, logCount(jdbc, id, "AMOUNT_MISMATCH"));
        assertEquals("PENDING", status(jdbc, id));
    }

    @Test
    @DisplayName("⑨ 카드 거절 → PENDING 그대로, 다시 결제하면 성공")
    void confirm_declinedThenRetry() {
        long id = reserve(MEMBER, SCHEDULE_ID, 1);

        assertEquals(ErrorCode.PAYMENT_DECLINED, codeOf(() -> paymentService.confirm(MEMBER, id,
                FakePgClient.DECLINE_PREFIX + UUID.randomUUID().toString().replace("-", ""), TOUR_PRICE)));
        assertEquals("PENDING", status(jdbc, id));
        assertEquals(0, paymentCount(jdbc, id));

        paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE);
        assertEquals("CONFIRMED", status(jdbc, id));
    }

    @Test
    @DisplayName("⑩ 남의 예약 결제 · 포기 · 취소 → 모두 RESERVATION_NOT_FOUND (규칙 R19)")
    void othersReservation_notFound() {
        long pendingId = reserve(MEMBER, SCHEDULE_ID, 1);
        long confirmedId = confirmed(MEMBER, SCHEDULE_SOON, 1);       // 같은 회차는 1인 1예약이라 다른 회차로

        assertEquals(ErrorCode.RESERVATION_NOT_FOUND,
                codeOf(() -> paymentService.confirm(OTHER_MEMBER, pendingId, newKey(), TOUR_PRICE)));
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND,
                codeOf(() -> stateService.abandon(OTHER_MEMBER, pendingId)));
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND,
                codeOf(() -> paymentService.cancel(OTHER_MEMBER, confirmedId)));
    }

    // =====================================================================
    // 고객 취소 (전액 환불)
    // =====================================================================

    @Test
    @DisplayName("⑪ ★ 취소 버튼 2번 동시 → 1번만 취소 · 환불, 자리도 1번만 반환 (규칙 R12)")
    void cancel_twiceConcurrently_onlyOnce() throws InterruptedException {
        long id = confirmed(MEMBER, SCHEDULE_ID, 3);

        Map<String, Integer> result = runConcurrently(2, i -> () -> {
            paymentService.cancel(MEMBER, id);
            return "CANCELED";
        });

        assertEquals(1, result.getOrDefault("CANCELED", 0));
        assertEquals(1, result.getOrDefault(ErrorCode.ALREADY_CANCELED.name(), 0));
        assertEquals("CANCELED", status(jdbc, id));
        assertEquals("CANCELED", paymentStatus(jdbc, id));
        assertEquals(1, pg.canceledCount());                          // 같은 결제를 두 번 불러도 환불은 1번 (멱등성)
        assertEquals(1, logCount(jdbc, id, "REFUNDED"));
        assertEquals(CAPACITY, remaining(jdbc, SCHEDULE_ID));         // 3석이 한 번만 돌아옴
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("⑫ 출발 3일 전이 지난 예약 취소 → CANCEL_CLOSED, PG 환불 안 부름")
    void cancel_closed() {
        long id = confirmed(MEMBER, SCHEDULE_SOON, 1);

        assertEquals(ErrorCode.CANCEL_CLOSED, codeOf(() -> paymentService.cancel(MEMBER, id)));
        assertEquals(0, pg.cancelCalls());
        assertEquals("CONFIRMED", status(jdbc, id));
    }

    @Test
    @DisplayName("⑬ 환불(PG 취소) 실패 → REFUND_FAILED, 예약 · 자리는 그대로 (다시 시도 가능)")
    void cancel_refundFails_nothingChanged() {
        long id = confirmed(MEMBER, SCHEDULE_ID, 2);
        pg.setCancelFails(true);

        assertEquals(ErrorCode.REFUND_FAILED, codeOf(() -> paymentService.cancel(MEMBER, id)));
        assertEquals("CONFIRMED", status(jdbc, id));
        assertEquals(CAPACITY - 2, remaining(jdbc, SCHEDULE_ID));
        assertEquals(1, logCount(jdbc, id, "REFUND_FAILED"));

        pg.setCancelFails(false);                                     // PG 가 돌아옴 → 다시 누르면 성공
        paymentService.cancel(MEMBER, id);
        assertEquals("CANCELED", status(jdbc, id));
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    // =====================================================================
    // 포기 · 만료
    // =====================================================================

    @Test
    @DisplayName("⑭ ★ 포기 vs 만료 동시 → 한쪽만 성공, 자리는 1번만 반환")
    void abandon_vs_expire() throws InterruptedException {
        long id = reserve(MEMBER, SCHEDULE_ID, 4);
        LocalDateTime future = LocalDateTime.now(SEOUL).plusHours(1);

        Map<String, Integer> result = runConcurrently(2, i -> () -> {
            if (i == 0) {
                stateService.abandon(MEMBER, id);
                return "ABANDONED";
            }
            return stateService.expire(id, future) ? "EXPIRED" : "EXPIRE_SKIPPED";
        });

        int winners = result.getOrDefault("ABANDONED", 0) + result.getOrDefault("EXPIRED", 0);
        assertEquals(1, winners, "포기와 만료 중 하나만 성공해야 함");
        assertEquals(CAPACITY, remaining(jdbc, SCHEDULE_ID));         // 4석이 한 번만 돌아옴 (정원 초과 없음)
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("⑮ 만료 스케줄러: 시간이 지난 PENDING 3건만 만료, 남은 1건 · 확정 1건은 그대로")
    void scheduler_expiresOnlyDue() {
        long a = reserve(1L, SCHEDULE_ID, 1);
        long b = reserve(2L, SCHEDULE_ID, 1);
        long c = reserve(3L, SCHEDULE_ID, 1);
        long fresh = reserve(4L, SCHEDULE_ID, 1);
        long paid = confirmed(5L, SCHEDULE_ID, 1);
        LocalDateTime past = LocalDateTime.now(SEOUL).minusMinutes(1);
        setExpiresAt(jdbc, a, past);
        setExpiresAt(jdbc, b, past);
        setExpiresAt(jdbc, c, past);

        int expired = scheduler.expireDueHolds(LocalDateTime.now(SEOUL));

        assertEquals(3, expired);
        assertEquals("EXPIRED", status(jdbc, a));
        assertEquals("PENDING", status(jdbc, fresh));
        assertEquals("CONFIRMED", status(jdbc, paid));
        assertEquals(CAPACITY - 2, remaining(jdbc, SCHEDULE_ID));     // fresh 1 + paid 1 만 자리 차지
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("⑯ 만료 · 포기된 뒤 같은 회원이 같은 회차를 다시 예약할 수 있다 (active_key = NULL)")
    void reReserve_afterExpireAndAbandon() {
        long first = reserve(MEMBER, SCHEDULE_ID, 1);
        stateService.expire(first, LocalDateTime.now(SEOUL).plusHours(1));
        long second = reserve(MEMBER, SCHEDULE_ID, 1);                // 1인 1예약 UNIQUE 에 안 걸림
        stateService.abandon(MEMBER, second);
        long third = reserve(MEMBER, SCHEDULE_ID, 1);

        assertEquals("PENDING", status(jdbc, third));
        assertEquals(ErrorCode.NOT_ABANDONABLE, codeOf(() -> stateService.abandon(MEMBER, second)));
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }
}
