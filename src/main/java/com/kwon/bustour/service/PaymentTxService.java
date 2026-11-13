package com.kwon.bustour.service;

import com.kwon.bustour.domain.Payment;
import com.kwon.bustour.domain.PaymentLog;
import com.kwon.bustour.domain.PaymentLogEvent;
import com.kwon.bustour.domain.PaymentStatus;
import com.kwon.bustour.domain.Reservation;
import com.kwon.bustour.domain.ReservationStatus;
import com.kwon.bustour.dto.PaymentTarget;
import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import com.kwon.bustour.pg.PgApproval;
import com.kwon.bustour.repository.PaymentLogRepository;
import com.kwon.bustour.repository.PaymentRepository;
import com.kwon.bustour.repository.ReservationRepository;
import com.kwon.bustour.repository.TourScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * =====================================================================
 * 결제의 "DB 부분"만 모은 클래스 (7단계) — 메서드 하나 = 짧은 트랜잭션 하나
 *
 * ★ 왜 PaymentService 와 나눴나 (규칙 R17: 외부 결제 호출은 트랜잭션 밖)
 *   PaymentService.confirm 은 [읽기 트랜잭션] → PG 승인(밖) → [확정 트랜잭션] 순서로 움직인다.
 *   트랜잭션을 메서드 단위로 따로 열려면 @Transactional 메서드가 "다른 빈"에 있어야 한다.
 *   같은 클래스 안에서 this.confirmTx() 로 부르면 프록시를 거치지 않아 @Transactional 이 무시된다 (자기 호출 함정).
 *
 *   PaymentService (트랜잭션 없음)
 *     ├ loadMine()        ← 이 클래스: 읽기 트랜잭션 (짧음)
 *     ├ PG 승인 300ms     ← 트랜잭션 · 커넥션 · 락 없음
 *     └ confirm()         ← 이 클래스: 쓰기 트랜잭션 (짧음)
 * =====================================================================
 */
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentLogRepository paymentLogRepository;
    private final TourScheduleRepository scheduleRepository;

    /**
     * 내 예약 + 결제 읽기 (PG 를 부르기 전 빠른 확인용)
     * @throws BusinessException RESERVATION_NOT_FOUND (없거나 남의 것, 규칙 R19)
     */
    @Transactional(readOnly = true)
    public PaymentTarget loadMine(Long memberId, Long reservationId) {
        Reservation r = reservationRepository.findMine(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        Payment p = paymentRepository.findByReservation_Id(reservationId).orElse(null);
        return PaymentTarget.of(r, p);
    }

    /**
     * ★ 결제 확정 (PG 승인이 끝난 뒤, 새 트랜잭션)
     *
     *   ① 예약 조건부 UPDATE : PENDING + 만료 전일 때만 CONFIRMED  (예약 행 X 락)
     *   ② 결제 INSERT        : 외래키 검사로 예약 행에 S 락이 필요하지만 ①에서 X 를 쥐고 있어 바로 통과 (R21)
     *   ③ 기록 INSERT (APPROVED) : 같은 트랜잭션 → 확정과 기록이 함께 커밋
     *
     * @return true = 확정함 / false = ①이 0행 (그 사이 만료 · 포기 · 다른 결제로 확정됨) → 부른 쪽이 보상 판단
     * @throws org.springframework.dao.DataIntegrityViolationException ② UNIQUE 위반 (부른 쪽이 false 와 같게 처리)
     */
    @Transactional
    public boolean confirm(Long memberId, Long reservationId, PgApproval approval, String orderId, LocalDateTime now) {
        int updated = reservationRepository.confirmIfPayable(reservationId, memberId, now,
                ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
        if (updated == 0) {
            return false;
        }
        Reservation ref = reservationRepository.getReferenceById(reservationId);
        // saveAndFlush : INSERT 를 지금 실행 → UNIQUE 위반이면 여기서 예외 → 트랜잭션 롤백 → ①도 취소
        paymentRepository.saveAndFlush(Payment.approved(ref, approval.paymentKey(), orderId,
                approval.amount(), approval.approvedAt()));
        paymentLogRepository.save(PaymentLog.of(ref, approval.paymentKey(), PaymentLogEvent.APPROVED,
                "결제 확정", now));
        return true;
    }

    /**
     * 이 결제 번호로 확정된 결제가 있으면 그 예약 번호 (보상 전에 다시 확인하기, 더블클릭 버그 방지)
     *
     * ★ 새 읽기 트랜잭션 = 새 스냅샷 → 방금 다른 요청이 커밋한 결제도 보인다
     *   (confirm 트랜잭션은 이미 끝났으므로, 그 트랜잭션의 옛 스냅샷에 갇히지 않음 — 질문 PDF Q12 함정 회피)
     */
    @Transactional(readOnly = true)
    public Optional<Long> findReservationIdByPaymentKey(String paymentKey) {
        return paymentRepository.findByPaymentKey(paymentKey).map(p -> p.getReservation().getId());
    }

    /** 지금 예약 상태 (보상 후 안내 문장 고르기용) */
    @Transactional(readOnly = true)
    public ReservationStatus currentStatus(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .map(Reservation::getStatus)
                .orElse(null);
    }

    /**
     * ★ 고객 취소의 DB 부분 (PG 환불이 성공한 뒤, 새 트랜잭션)
     *
     *   ① 회차 remaining + n     : 회차 X 락 먼저 (락 순서 "회차 → 예약", 데드락 방지)
     *   ② 예약 조건부 UPDATE     : CONFIRMED 일 때만 CANCELED (규칙 R12 취소는 한 번만)
     *   ③ 결제 조건부 UPDATE     : APPROVED 일 때만 CANCELED
     *   ④ 기록 REFUNDED
     *
     * @throws BusinessException ALREADY_CANCELED (②가 0행 = 동시에 누른 다른 취소가 먼저 끝남 → 롤백으로 ①도 취소)
     */
    @Transactional
    public void cancel(PaymentTarget t, Long memberId, LocalDateTime now) {
        int restored = scheduleRepository.increaseRemaining(t.scheduleId(), t.headcount());
        int changed = reservationRepository.cancelIfConfirmed(t.reservationId(), memberId, now,
                ReservationStatus.CONFIRMED, ReservationStatus.CANCELED);
        if (changed == 0) {
            throw new BusinessException(ErrorCode.ALREADY_CANCELED);
        }
        // ②를 먼저 확인하는 이유: 두 번째 취소는 ①이 정원 초과로 0행일 수 있는데, 그건 "이미 취소됨"이지 버그가 아님
        if (restored == 0) {
            throw new IllegalStateException("자리 반환 실패 (정원 초과): reservation=" + t.reservationId());
        }
        paymentRepository.markCanceled(t.paymentId(), now, PaymentStatus.APPROVED, PaymentStatus.CANCELED);
        paymentLogRepository.save(PaymentLog.of(reservationRepository.getReferenceById(t.reservationId()),
                t.paymentKey(), PaymentLogEvent.REFUNDED, "고객 취소 · 전액 환불 " + t.amount() + "원", now));
    }
}
