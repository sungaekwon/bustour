package com.kwon.bustour.service;

import com.kwon.bustour.domain.PaymentLogEvent;
import com.kwon.bustour.domain.PaymentStatus;
import com.kwon.bustour.domain.ReservationStatus;
import com.kwon.bustour.dto.CheckoutView;
import com.kwon.bustour.dto.PaymentResult;
import com.kwon.bustour.dto.PaymentTarget;
import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import com.kwon.bustour.pg.PgApproval;
import com.kwon.bustour.pg.PgClient;
import com.kwon.bustour.pg.PgDeclinedException;
import com.kwon.bustour.pg.PgException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * =====================================================================
 * 결제 서비스 (7단계 핵심): 결제 확정 · 보상 · 고객 취소(환불)
 *
 * ★ 이 클래스에는 @Transactional 이 없다 (일부러)
 *   PG 호출은 트랜잭션 밖에서 해야 하므로(규칙 R17), DB 일은 PaymentTxService 의 짧은 트랜잭션들로 나눴다.
 *
 * ★ 결제 확정 흐름 (confirm)
 *   ① [읽기 트랜잭션] 내 예약인가 · PENDING 인가 · 만료 전인가 · 금액이 같은가  → 아니면 PG 를 부르지 않고 끝
 *   ② [트랜잭션 밖]  PG 승인 (수백 ms) — 이 동안 커넥션 · 락을 쥐지 않음
 *   ③ [쓰기 트랜잭션] 조건부 UPDATE 로 확정 + 결제 INSERT
 *   ④ ③이 실패(0행)하면 → ★ 보상 전에 다시 확인 → 정말 확정 못 한 결제만 PG 취소(보상)
 *
 * ★ ④ "보상 전에 다시 확인"이 막는 버그 (더블클릭 보상 버그)
 *   같은 결제 번호로 요청 A, B 가 동시에 옴 → 둘 다 ②에서 승인(같은 결제, PG 는 한 번만 승인)
 *   → A 가 ③에서 확정 · 커밋 → B 의 ③은 이미 CONFIRMED 라 0행
 *   → B 가 "확정 실패니까 보상"하면 A 가 확정한 결제를 PG 에서 취소해 버린다 (예약은 확정, 돈은 환불 = 공짜 투어)
 *   → B 는 보상 전에 "이 결제 번호로 확정된 결제가 있나?"를 새 트랜잭션으로 확인 → 있으면 성공으로 응답
 *
 * ★ (9단계 부하테스트로 발견 → 수정) PG 승인 "뒤" DB 단계에서 예외가 나는 경우
 *   RATE 200 측정에서 커넥션 풀이 바닥나(active=10, waiting=187) 확정 트랜잭션이 커넥션을 3초 안에 못 받는 일이 생겼다.
 *   예전 코드는 0행 · UNIQUE 위반일 때만 보상해서, 이런 예외는 보상 없이 503 으로 끝났다
 *   → 실제 PG 였다면 "돈은 승인됐는데 예약은 확정 안 됨"이 남는다.
 *   지금: ① 잠깐 붐빈 것일 수 있으니 짧게 다시 시도(최대 3번) → ② 그래도 안 되면 무조건 "재확인 → 보상" 경로로.
 *         재확인조차 못 하면(DB 가 계속 안 됨) 함부로 취소하지 않고 기록 · 에러 로그로 남긴다(사람이 PG 내역과 대조 = 대사).
 *   고객 취소도 같다: PG 환불 뒤 DB 취소가 실패하면 다시 시도 → 안 되면 "다시 눌러 달라" 안내 + 기록.
 *
 * ★ 왜 PG 승인을 먼저 하고 DB 를 나중에 하나 (반대로 하면?)
 *   DB 를 먼저 CONFIRMED 로 바꾸고 PG 를 부르면, PG 가 실패했을 때 "돈은 안 냈는데 확정된 예약"이 생긴다.
 *   PG 먼저 → DB 확정 실패 시 보상(PG 취소)으로 되돌린다. 돈 문제는 "되돌릴 수 있는 쪽"을 먼저.
 * =====================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTxService txService;
    private final PaymentLogService paymentLogService;
    private final PgClient pgClient;
    private final Clock clock;

    /** DB 단계 재시도 횟수 (처음 1번 포함) — PG 를 이미 부른 뒤라 쉽게 포기하지 않는다 */
    static final int DB_ATTEMPTS = 3;
    /** 재시도 사이 쉬는 시간 기본값 (ms). 1번째 실패 후 200, 2번째 후 400 … (점점 길게) */
    static final long RETRY_WAIT_MS = 200;

    // =====================================================================
    // 결제 화면
    // =====================================================================

    /**
     * 결제 화면(/payments/{id})에 보여 줄 값
     * @throws BusinessException RESERVATION_NOT_FOUND
     */
    public CheckoutView checkout(Long memberId, Long reservationId) {
        PaymentTarget t = txService.loadMine(memberId, reservationId);
        LocalDateTime now = now();

        String notice = null;
        if (t.status() == ReservationStatus.CONFIRMED) {
            notice = ErrorCode.ALREADY_PAID.getMessage();
        } else if (t.status() != ReservationStatus.PENDING) {
            notice = ErrorCode.NOT_PAYABLE.getMessage();
        } else if (!t.expiresAt().isAfter(now)) {
            notice = ErrorCode.PAYMENT_EXPIRED.getMessage();
        }
        boolean payable = notice == null;
        long remainSeconds = payable ? Duration.between(now, t.expiresAt()).getSeconds() : 0;

        return new CheckoutView(t.reservationId(), t.tourName(), t.startAt(), t.headcount(), t.amount(),
                t.orderId(), payable, remainSeconds, notice);
    }

    // =====================================================================
    // 결제 확정
    // =====================================================================

    /**
     * 결제 확정 (POST /api/payments/confirm)
     *
     * @param memberId   로그인한 회원 (규칙 R19)
     * @param paymentKey PG 결제창이 준 결제 번호 (가짜 PG 는 화면이 만듦)
     * @param amount     화면이 보낸 금액 → 예약 금액과 "비교만" (규칙 R15)
     * @return created=true 새로 확정 / false 같은 결제 번호로 이미 확정됨 (더블클릭 · 재전송)
     */
    public PaymentResult confirm(Long memberId, Long reservationId, String paymentKey, int amount) {
        return confirm(memberId, reservationId, paymentKey, amount, null);
    }

    /**
     * 결제 확정 + 주문번호 확인 (토스: successUrl 로 돌아온 orderId 도 비교)
     *
     * @param orderId PG 가 돌려준 주문번호. null 이면 비교하지 않음 (가짜 PG)
     */
    public PaymentResult confirm(Long memberId, Long reservationId, String paymentKey, int amount, String orderId) {

        // ① 빠른 확인 (읽기 트랜잭션) — 안 되는 결제는 PG 를 부르기 전에 거른다 (돈이 움직이지 않게)
        PaymentTarget t = txService.loadMine(memberId, reservationId);

        // (토스) 돌아온 주문번호가 이 예약의 주문번호인가 — 다른 예약의 결제 정보를 끼워 넣는 것 막기
        if (orderId != null && !orderId.equals(t.orderId())) {
            writeLog(t.reservationId(), paymentKey, PaymentLogEvent.AMOUNT_MISMATCH,
                    "주문번호 불일치: 받은 값 " + orderId + " ≠ " + t.orderId());
            throw new BusinessException(ErrorCode.ORDER_MISMATCH);
        }

        // 같은 결제 번호로 이미 확정됨 → 처음 결과 그대로 (멱등성)
        if (paymentKey.equals(t.paymentKey())) {
            return new PaymentResult(t.reservationId(), t.amount(), paymentKey, false);
        }
        if (t.status() == ReservationStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.ALREADY_PAID);      // 다른 결제 번호로 또 결제 → PG 를 부르지 않음
        }
        if (t.status() != ReservationStatus.PENDING) {
            throw new BusinessException(ErrorCode.NOT_PAYABLE);       // 만료 · 포기된 예약
        }
        if (!t.expiresAt().isAfter(now())) {
            throw new BusinessException(ErrorCode.PAYMENT_EXPIRED);   // 스케줄러가 아직 안 돌았어도 시간이 지났으면 거절
        }
        // 금액 비교 (규칙 R15): 화면 값이 아니라 예약할 때 서버가 저장한 금액이 기준
        if (amount != t.amount()) {
            writeLog(t.reservationId(), paymentKey, PaymentLogEvent.AMOUNT_MISMATCH,
                    "요청 금액 " + amount + "원 ≠ 예약 금액 " + t.amount() + "원");
            throw new BusinessException(ErrorCode.AMOUNT_MISMATCH);
        }

        // ② PG 승인 (트랜잭션 밖, 규칙 R17)
        PgApproval approval;
        try {
            approval = pgClient.approve(paymentKey, t.orderId(), t.amount());
        } catch (PgDeclinedException e) {
            // 카드 거절: 돈이 안 나감 → 보상할 것 없음. 예약은 PENDING 그대로 → 다시 결제 가능
            throw new BusinessException(ErrorCode.PAYMENT_DECLINED, e.getMessage());
        } catch (PgException e) {
            // 연결 실패 · 시간 초과. (토스로 바꿀 때: 시간 초과는 "승인됐는지 모름"이므로
            //  결제 조회 API 로 상태를 확인한 뒤 승인됐으면 ③으로 이어가야 한다 → TossPgClient 에서 처리)
            log.warn("PG 승인 실패: reservation={}, paymentKey={}, {}", reservationId, paymentKey, e.getMessage());
            throw new BusinessException(ErrorCode.PG_ERROR);
        }

        // PG 가 다른 금액을 승인했다면(있어선 안 됨) 확정하지 않고 되돌린다
        if (approval.amount() != t.amount()) {
            compensate(t.reservationId(), paymentKey,
                    "승인 금액 " + approval.amount() + "원 ≠ 예약 금액 " + t.amount() + "원");
            throw new BusinessException(ErrorCode.AMOUNT_MISMATCH);
        }

        // ③ 확정 (쓰기 트랜잭션). now 는 "지금" 다시: PG 를 기다리는 동안 만료됐을 수 있다
        //   ★ PG 가 이미 승인했으므로 DB 가 잠깐 붐벼서 실패했다면 짧게 다시 시도한다 (보상보다 확정이 고객에게 낫다)
        Boolean confirmed = null;
        boolean dbFailed = false;
        for (int attempt = 1; attempt <= DB_ATTEMPTS && confirmed == null; attempt++) {
            try {
                confirmed = txService.confirm(memberId, reservationId, approval, t.orderId(), now());
            } catch (DataIntegrityViolationException e) {
                // 결제 UNIQUE 위반 = 다른 요청이 먼저 확정 (트랜잭션은 이미 롤백됨) → 0행과 같게 처리
                confirmed = false;
            } catch (RuntimeException e) {
                // 커넥션 대기 초과(503) · 락 대기 초과 등. 예외 = 롤백됐으므로 이 시도로 바뀐 것은 없다
                dbFailed = true;
                log.warn("[결제] PG 승인 뒤 확정 실패 (시도 {}/{}): reservation={}, paymentKey={}, {}",
                        attempt, DB_ATTEMPTS, reservationId, paymentKey, e.getMessage());
                if (attempt < DB_ATTEMPTS) {
                    pause(RETRY_WAIT_MS * attempt);
                }
            }
        }
        if (Boolean.TRUE.equals(confirmed)) {
            return new PaymentResult(t.reservationId(), t.amount(), paymentKey, true);
        }

        // ④ 확정 실패(0행이든 예외든) → 보상 전에 다시 확인
        return compensateOrAccept(t, paymentKey, dbFailed);
    }

    /**
     * ★ 보상 전에 다시 확인 (더블클릭 보상 버그 방지)
     *
     *   1. 이 결제 번호로 이 예약이 이미 확정돼 있다 → 같은 결제의 다른 요청이 먼저 확정한 것 → 성공 (보상 ✘)
     *   2. 아니다 → 이 결제는 정말 갈 곳이 없음 (만료 · 포기 · 다른 결제 번호로 이미 확정) → PG 취소(보상)
     */
    private PaymentResult compensateOrAccept(PaymentTarget t, String paymentKey, boolean dbFailed) {
        Optional<Long> owner;
        try {
            // 재확인도 DB 라 붐비면 실패할 수 있다 → 짧게 다시 시도
            owner = retry(() -> txService.findReservationIdByPaymentKey(paymentKey));
        } catch (RuntimeException e) {
            // ★ 확정됐는지조차 모름 → 함부로 PG 취소하지 않는다
            //   (같은 결제 번호의 다른 요청이 확정했을 수 있음 = 취소하면 공짜 투어 버그)
            //   결제 번호를 기록 · 에러 로그로 남겨 PG 승인 내역과 대조(대사)할 수 있게 한다
            log.error("★ 보상 판단 불가 — PG 승인 내역과 대조 필요: reservation={}, paymentKey={}, amount={}, {}",
                    t.reservationId(), paymentKey, t.amount(), e.getMessage());
            writeLog(t.reservationId(), paymentKey, PaymentLogEvent.COMPENSATION_FAILED,
                    "확정 여부 확인 실패(DB) — PG 승인 내역과 대조 필요: " + e.getMessage());
            throw new BusinessException(ErrorCode.COMPENSATION_FAILED);
        }
        if (owner.isPresent() && owner.get().equals(t.reservationId())) {
            log.info("같은 결제 번호가 이미 확정됨 → 보상하지 않음: reservation={}, paymentKey={}",
                    t.reservationId(), paymentKey);
            return new PaymentResult(t.reservationId(), t.amount(), paymentKey, false);
        }

        // 안내 문장을 고르려고 지금 상태를 봄 (못 읽어도 보상은 해야 하므로 실패하면 null)
        ReservationStatus nowStatus;
        try {
            nowStatus = txService.currentStatus(t.reservationId());
        } catch (RuntimeException e) {
            nowStatus = null;
        }
        String reason;
        ErrorCode code;
        if (nowStatus == ReservationStatus.CONFIRMED) {
            reason = "이미 다른 결제로 확정된 예약 → 중복 결제 취소";
            code = ErrorCode.DUPLICATE_PAYMENT_REFUNDED;
        } else if (dbFailed && nowStatus == ReservationStatus.PENDING) {
            reason = "PG 승인 뒤 DB 확정 실패(서버 붐빔) → 결제 취소";
            code = ErrorCode.PAYMENT_CANCELED_SERVER_BUSY;      // 예약은 결제 대기 그대로 → 다시 결제 가능
        } else {
            reason = "확정 실패(지금 상태 " + nowStatus + ") → 결제 취소";
            code = ErrorCode.PAYMENT_CANCELED_BY_SYSTEM;
        }

        compensate(t.reservationId(), paymentKey, reason);    // 실패하면 COMPENSATION_FAILED 예외
        throw new BusinessException(code);
    }

    /**
     * 보상 = 승인된 결제를 PG 에서 취소 + 기록 (규칙 R18)
     *   보상마저 실패하면: 돈은 나갔는데 예약은 확정 못 한 상태 → COMPENSATION_FAILED 기록 → 사람이 PG 관리자 화면에서 처리
     */
    private void compensate(Long reservationId, String paymentKey, String reason) {
        try {
            pgClient.cancel(paymentKey, reason);
        } catch (PgException e) {
            log.error("★ 보상 실패 — 사람이 확인해야 함: reservation={}, paymentKey={}, {}",
                    reservationId, paymentKey, e.getMessage());
            writeLog(reservationId, paymentKey, PaymentLogEvent.COMPENSATION_FAILED, reason + " / " + e.getMessage());
            throw new BusinessException(ErrorCode.COMPENSATION_FAILED);
        }
        writeLog(reservationId, paymentKey, PaymentLogEvent.CANCELED_BY_COMPENSATION, reason);
    }

    // =====================================================================
    // 고객 취소 (전액 환불)
    // =====================================================================

    /**
     * 확정된 예약 취소 + 전액 환불 (출발 3일 전까지, 규칙 R12 · R13)
     *
     * ★ 순서: PG 환불 먼저(트랜잭션 밖) → 성공하면 DB 취소 (새 트랜잭션)
     *   DB 를 먼저 바꾸고 환불이 실패하면 "자리는 풀렸는데 돈은 안 돌려준" 상태가 된다.
     *   환불을 먼저 하면, DB 취소가 실패해도(서버 꺼짐 등) 다시 누르면 된다:
     *   PG 취소는 같은 결제에 다시 불러도 성공(멱등성)이고, DB 는 아직 CONFIRMED 라 이번엔 바뀐다.
     *
     * @return 환불 금액
     */
    public int cancel(Long memberId, Long reservationId) {
        LocalDateTime now = now();
        PaymentTarget t = txService.loadMine(memberId, reservationId);

        // 빠른 확인 (진짜 판단은 DB 의 조건부 UPDATE 가 다시 함)
        if (t.status() == ReservationStatus.CANCELED && t.paymentStatus() == PaymentStatus.CANCELED) {
            throw new BusinessException(ErrorCode.ALREADY_CANCELED);
        }
        if (t.status() != ReservationStatus.CONFIRMED || t.paymentKey() == null) {
            throw new BusinessException(ErrorCode.NOT_CANCELABLE);
        }
        // 규칙 R13: 출발 3일 전까지 (DB CHECK 로는 '지금'을 쓸 수 없어서 서비스에서 비교)
        if (!now.isBefore(t.startAt().minusDays(ReservationService.CANCEL_CLOSE_DAYS))) {
            throw new BusinessException(ErrorCode.CANCEL_CLOSED);
        }

        // ① PG 환불 (트랜잭션 밖)
        try {
            pgClient.cancel(t.paymentKey(), "고객 요청 취소 (출발 3일 전, 전액 환불)");
        } catch (PgException e) {
            writeLog(t.reservationId(), t.paymentKey(), PaymentLogEvent.REFUND_FAILED, e.getMessage());
            throw new BusinessException(ErrorCode.REFUND_FAILED);   // DB 는 그대로 (확정 유지) → 다시 시도 가능
        }

        // ② DB 취소 (새 트랜잭션: 회차 → 예약 → 결제 → 기록)
        //   ★ (9단계 수정) 환불은 이미 됐으므로 DB 가 잠깐 붐벼 실패하면 짧게 다시 시도
        RuntimeException last = null;
        for (int attempt = 1; attempt <= DB_ATTEMPTS; attempt++) {
            try {
                txService.cancel(t, memberId, now());
                return t.amount();
            } catch (BusinessException e) {
                throw e;                                     // ALREADY_CANCELED 등 = 붐빔이 아니라 정해진 결과
            } catch (RuntimeException e) {
                last = e;
                log.warn("[취소] PG 환불 뒤 DB 취소 실패 (시도 {}/{}): reservation={}, {}",
                        attempt, DB_ATTEMPTS, reservationId, e.getMessage());
                if (attempt < DB_ATTEMPTS) {
                    pause(RETRY_WAIT_MS * attempt);
                }
            }
        }
        // 돈은 돌려줬는데 예약은 확정 그대로 → 다시 누르면 맞춰진다 (PG 취소는 멱등, DB 는 아직 CONFIRMED)
        log.error("★ 환불됐지만 예약 취소 못 함 — 다시 취소 필요: reservation={}, paymentKey={}, {}",
                reservationId, t.paymentKey(), last.getMessage());
        writeLog(t.reservationId(), t.paymentKey(), PaymentLogEvent.REFUND_FAILED,
                "PG 환불 완료 · DB 취소 실패(다시 취소하면 맞춰짐): " + last.getMessage());
        throw new BusinessException(ErrorCode.CANCEL_RETRY_NEEDED);
    }

    // =====================================================================

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** DB 읽기를 짧게 다시 시도 (붐빔으로 실패할 때) — 끝까지 안 되면 마지막 예외를 던짐 */
    private <T> T retry(Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= DB_ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException e) {
                last = e;
                if (attempt < DB_ATTEMPTS) {
                    pause(RETRY_WAIT_MS * attempt);
                }
            }
        }
        throw last;
    }

    /** 재시도 전 잠깐 쉬기 (요청 스레드만 쉼, DB 커넥션은 쥐고 있지 않음) */
    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 기록 저장 실패가 원래 처리(결제 · 보상)를 망치지 않게: 예외를 삼키고 에러 로그만 */
    private void writeLog(Long reservationId, String paymentKey, PaymentLogEvent event, String message) {
        try {
            paymentLogService.write(reservationId, paymentKey, event, message);
        } catch (RuntimeException e) {
            log.error("결제 기록 저장 실패: reservation={}, event={}, {}", reservationId, event, e.getMessage());
        }
    }
}
