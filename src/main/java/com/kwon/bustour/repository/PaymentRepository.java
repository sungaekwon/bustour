package com.kwon.bustour.repository;

import com.kwon.bustour.domain.Payment;
import com.kwon.bustour.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * payment 조회 · 저장 (7단계)
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 이 결제 번호로 이미 확정한 결제가 있나 (UNIQUE 인덱스 uk_payment_key)
     *   ★ 더블클릭 보상 버그를 막는 "보상 전에 다시 확인"에서 쓴다 (PaymentService.compensateOrAccept)
     */
    Optional<Payment> findByPaymentKey(String paymentKey);

    /** 예약의 결제 (UNIQUE 인덱스 uk_payment_reservation) — 취소(환불)할 때 결제 번호를 알아야 함 */
    Optional<Payment> findByReservation_Id(Long reservationId);

    /**
     * 환불 표시: APPROVED 일 때만 → CANCELED (조건부 UPDATE, 예약 취소와 같은 트랜잭션)
     */
    @Modifying
    @Query("""
           UPDATE Payment p
              SET p.status = :canceled, p.canceledAt = :now
            WHERE p.id = :id AND p.status = :approved
           """)
    int markCanceled(@Param("id") Long paymentId, @Param("now") LocalDateTime now,
                     @Param("approved") PaymentStatus approved,
                     @Param("canceled") PaymentStatus canceled);
}
