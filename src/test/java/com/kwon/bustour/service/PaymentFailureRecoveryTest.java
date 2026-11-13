package com.kwon.bustour.service;

import com.kwon.bustour.domain.ReservationStatus;
import com.kwon.bustour.dto.PaymentTarget;
import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import com.kwon.bustour.pg.FakePgClient;
import com.kwon.bustour.pg.PgApproval;
import com.kwon.bustour.repository.PaymentLogRepository;
import com.kwon.bustour.repository.PaymentRepository;
import com.kwon.bustour.repository.ReservationRepository;
import com.kwon.bustour.repository.TourScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.kwon.bustour.support.TestDataHelper.TOUR_PRICE;
import static com.kwon.bustour.support.TestDataHelper.cleanAll;
import static com.kwon.bustour.support.TestDataHelper.daysLater;
import static com.kwon.bustour.support.TestDataHelper.insertMembers;
import static com.kwon.bustour.support.TestDataHelper.insertSchedule;
import static com.kwon.bustour.support.TestDataHelper.insertTour;
import static com.kwon.bustour.support.TestDataHelper.logCount;
import static com.kwon.bustour.support.TestDataHelper.paymentCount;
import static com.kwon.bustour.support.TestDataHelper.remaining;
import static com.kwon.bustour.support.TestDataHelper.seatMismatch;
import static com.kwon.bustour.support.TestDataHelper.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * =====================================================================
 * 9단계 부하테스트로 발견한 빈틈 — "PG 를 부른 뒤 DB 단계가 실패"할 때 복구되는지 (보상 · 재시도)
 *
 * ★ 발견: RATE 200 에서 커넥션 풀이 바닥나 (active=10, waiting=187)
 *   "Connection is not available, request timed out after 3000ms" → 503.
 *   이게 PG 승인 "뒤" 확정 트랜잭션에서 나면, 예전 코드는 보상하지 않았다 (돈은 승인, 예약은 미확정).
 *
 * ★ 어떻게 재현하나: DB 를 진짜로 바닥내지 않고, PaymentTxService 를 상속한 "가끔 실패하는" 빈으로 바꿔 끼운다
 *   FlakyPaymentTxService : 정해 둔 횟수만큼 CannotCreateTransactionException(= 커넥션 못 받음)을 던지고, 그다음은 진짜 동작
 *   @Primary : 같은 타입 빈이 2개일 때 이쪽을 주입 → PaymentService 가 이 빈을 쓴다
 *   @Transactional 을 다시 붙인 이유: 덮어쓴 메서드도 트랜잭션 안에서 실행되게 (원래와 같은 조건)
 * =====================================================================
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentFailureRecoveryTest {

    private static final long SCHEDULE_ID = 1L;          // 출발 5일 뒤 (취소 가능)
    private static final int CAPACITY = 10;
    private static final long MEMBER = 1L;

    @TestConfiguration
    static class FlakyConfig {
        @Bean
        @Primary
        FlakyPaymentTxService flakyPaymentTxService(ReservationRepository r, PaymentRepository p,
                                                    PaymentLogRepository l, TourScheduleRepository s) {
            return new FlakyPaymentTxService(r, p, l, s);
        }
    }

    /**
     * 정해 둔 횟수만큼 "커넥션을 못 받는" 예외를 던지는 PaymentTxService
     *
     * ★ 테스트에서 필드(flaky.failConfirm)를 직접 건드리면 안 되고, 꼭 public 메서드로 바꾼다
     *   @Transactional 이 붙은 클래스라 스프링이 주입하는 것은 이 객체가 아니라 "프록시(대리 객체)"다.
     *   프록시는 메서드 호출만 진짜 객체로 넘겨 주고, 필드는 자기 것(초기화 안 된 null)을 갖고 있다
     *   → flaky.failConfirm.set(...) 은 프록시의 null 필드를 건드려 NullPointerException
     *   → flaky.failConfirm(3) 처럼 메서드로 부르면 진짜 객체의 필드가 바뀐다
     */
    static class FlakyPaymentTxService extends PaymentTxService {
        private final AtomicInteger failConfirm = new AtomicInteger();   // 확정을 몇 번 실패시킬지
        private final AtomicInteger failRecheck = new AtomicInteger();   // 보상 전 재확인을 몇 번 실패시킬지
        private final AtomicInteger failCancel = new AtomicInteger();    // 취소 DB 부분을 몇 번 실패시킬지
        private final AtomicInteger confirmCalls = new AtomicInteger();

        FlakyPaymentTxService(ReservationRepository r, PaymentRepository p,
                              PaymentLogRepository l, TourScheduleRepository s) {
            super(r, p, l, s);
        }

        public void reset() {
            failConfirm.set(0);
            failRecheck.set(0);
            failCancel.set(0);
            confirmCalls.set(0);
        }

        public void failConfirm(int times) { failConfirm.set(times); }
        public void failRecheck(int times) { failRecheck.set(times); }
        public void failCancel(int times) { failCancel.set(times); }
        public int confirmCalls() { return confirmCalls.get(); }

        private static CannotCreateTransactionException busy() {
            return new CannotCreateTransactionException("테스트: Connection is not available, request timed out after 3000ms");
        }

        @Override
        @Transactional
        public boolean confirm(Long memberId, Long reservationId, PgApproval approval, String orderId, LocalDateTime now) {
            confirmCalls.incrementAndGet();
            if (failConfirm.getAndDecrement() > 0) throw busy();
            return super.confirm(memberId, reservationId, approval, orderId, now);
        }

        @Override
        @Transactional(readOnly = true)
        public Optional<Long> findReservationIdByPaymentKey(String paymentKey) {
            if (failRecheck.getAndDecrement() > 0) throw busy();
            return super.findReservationIdByPaymentKey(paymentKey);
        }

        @Override
        @Transactional
        public void cancel(PaymentTarget t, Long memberId, LocalDateTime now) {
            if (failCancel.getAndDecrement() > 0) throw busy();
            super.cancel(t, memberId, now);
        }
    }

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired FlakyPaymentTxService flaky;
    @Autowired FakePgClient pg;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanAll(jdbc);
        insertTour(jdbc);
        insertMembers(jdbc, 5);
        insertSchedule(jdbc, SCHEDULE_ID, CAPACITY, daysLater(5), "OPEN");
        pg.reset();
        flaky.reset();
    }

    private long reserve(int headcount) {
        return reservationService.reserve(MEMBER, SCHEDULE_ID, headcount, UUID.randomUUID().toString())
                .getReservationId();
    }

    private String newKey() {
        return "fake_" + UUID.randomUUID().toString().replace("-", "");
    }

    private ErrorCode codeOf(Runnable r) {
        return assertThrows(BusinessException.class, r::run).getErrorCode();
    }

    @Test
    @DisplayName("① ★ PG 승인 뒤 확정이 3번 모두 실패(커넥션 없음) → 보상(PG 취소), 예약은 결제 대기 그대로 → 다시 결제 성공")
    void confirmFailsAfterApprove_compensated() {
        long id = reserve(2);
        String key = newKey();
        flaky.failConfirm(PaymentService.DB_ATTEMPTS);           // 재시도까지 모두 실패

        assertEquals(ErrorCode.PAYMENT_CANCELED_SERVER_BUSY,
                codeOf(() -> paymentService.confirm(MEMBER, id, key, TOUR_PRICE * 2)));

        assertEquals(PaymentService.DB_ATTEMPTS, flaky.confirmCalls());   // 3번 시도했다
        assertTrue(pg.isCanceled(key), "승인된 결제는 보상으로 취소돼야 함 (돈이 남지 않게)");
        assertEquals(1, logCount(jdbc, id, "CANCELED_BY_COMPENSATION"));
        assertEquals("PENDING", status(jdbc, id));                     // 예약은 그대로 → 다시 결제 가능
        assertEquals(0, paymentCount(jdbc, id));

        paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE * 2);  // 서버가 돌아오면 새 결제로 성공
        assertEquals("CONFIRMED", status(jdbc, id));
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("② 확정이 1번만 실패하고 재시도에서 성공 → 확정, 보상 없음")
    void confirmFailsOnce_retrySucceeds() {
        long id = reserve(1);
        flaky.failConfirm(1);

        assertTrue(paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE).created());

        assertEquals(2, flaky.confirmCalls());
        assertEquals("CONFIRMED", status(jdbc, id));
        assertEquals(0, pg.cancelCalls(), "재시도로 확정했으니 PG 취소가 없어야 함");
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("③ 확정도 재확인도 실패 → 확정됐는지 모르니 PG 를 취소하지 않고 COMPENSATION_FAILED 기록 (대사)")
    void confirmAndRecheckFail_noBlindCancel() {
        long id = reserve(1);
        String key = newKey();
        flaky.failConfirm(PaymentService.DB_ATTEMPTS);
        flaky.failRecheck(PaymentService.DB_ATTEMPTS);

        assertEquals(ErrorCode.COMPENSATION_FAILED,
                codeOf(() -> paymentService.confirm(MEMBER, id, key, TOUR_PRICE)));

        assertEquals(0, pg.cancelCalls(), "확실하지 않으면 취소하지 않는다 (같은 결제의 다른 요청이 확정했을 수 있음)");
        assertEquals(1, logCount(jdbc, id, "COMPENSATION_FAILED"));
        assertEquals(key, jdbc.queryForObject(
                "SELECT payment_key FROM payment_log WHERE reservation_id = ? AND event = 'COMPENSATION_FAILED'",
                String.class, id));                                    // 결제 번호가 남아야 PG 내역과 대조 가능
    }

    @Test
    @DisplayName("④ ★ 취소: PG 환불 뒤 DB 취소가 3번 모두 실패 → CANCEL_RETRY_NEEDED + 기록, 다시 누르면 맞춰짐 (환불은 1번)")
    void cancelDbFailsAfterRefund_retryLater() {
        long id = reserve(3);
        paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE * 3);
        flaky.failCancel(PaymentService.DB_ATTEMPTS);

        assertEquals(ErrorCode.CANCEL_RETRY_NEEDED, codeOf(() -> paymentService.cancel(MEMBER, id)));
        assertEquals("CONFIRMED", status(jdbc, id));                   // DB 는 아직 확정
        assertEquals(1, pg.canceledCount());                           // 돈은 이미 돌려줌
        assertEquals(1, logCount(jdbc, id, "REFUND_FAILED"));
        assertEquals(CAPACITY - 3, remaining(jdbc, SCHEDULE_ID));

        paymentService.cancel(MEMBER, id);                             // 서버가 돌아온 뒤 다시 누름
        assertEquals("CANCELED", status(jdbc, id));
        assertEquals(1, pg.canceledCount(), "같은 결제를 다시 취소해도 환불은 1번 (멱등성)");
        assertEquals(CAPACITY, remaining(jdbc, SCHEDULE_ID));
        assertEquals(0, seatMismatch(jdbc, SCHEDULE_ID));
    }

    @Test
    @DisplayName("⑤ 취소 DB 가 1번만 실패하고 재시도에서 성공 → 취소 완료")
    void cancelDbFailsOnce_retrySucceeds() {
        long id = reserve(1);
        paymentService.confirm(MEMBER, id, newKey(), TOUR_PRICE);
        flaky.failCancel(1);

        assertEquals(TOUR_PRICE, paymentService.cancel(MEMBER, id));
        assertEquals("CANCELED", status(jdbc, id));
        assertFalse(logCount(jdbc, id, "REFUND_FAILED") > 0);
        assertEquals(ReservationStatus.CANCELED.name(), status(jdbc, id));
    }
}