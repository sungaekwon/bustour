package com.kwon.bustour.dto;

import com.kwon.bustour.domain.Payment;
import com.kwon.bustour.domain.PaymentStatus;
import com.kwon.bustour.domain.Reservation;
import com.kwon.bustour.domain.ReservationStatus;

import java.time.LocalDateTime;

/**
 * 결제 · 취소 전에 읽어 두는 "예약 + 결제" 값 묶음 (7단계)
 *
 * ★ 엔티티가 아니라 값(record)으로 꺼내는 이유
 *   PaymentService 는 트랜잭션 밖에서 PG 를 부른다 (규칙 R17).
 *   엔티티를 트랜잭션 밖으로 들고 나가면 지연 로딩이 안 되고(open-in-view: false), 값이 언제 것인지 헷갈린다.
 *   → 필요한 값만 복사해 두고, 상태를 "바꾸는" 판단은 나중에 조건부 UPDATE 가 최신 값으로 한다.
 *     (여기 값은 "PG 를 부를 만한가" 미리 거르는 용도 = 빠른 실패)
 *
 * @param paymentKey    이미 확정된 결제가 있으면 그 결제 번호, 없으면 null
 */
public record PaymentTarget(
        Long reservationId,
        Long scheduleId,
        String tourName,
        LocalDateTime startAt,
        int headcount,
        int amount,
        ReservationStatus status,
        LocalDateTime expiresAt,
        boolean everConfirmed,       // confirmed_at 이 있나 = 한 번이라도 결제됐었나 (취소 · 환불된 예약 구분)
        String orderId,
        Long paymentId,
        String paymentKey,
        PaymentStatus paymentStatus) {

    /**
     * 주문 번호 만들기: "BT" + 예약번호 + "-" + 요청 키 앞 8자리   예) BT12-a1b2c3d4
     *   토스 규칙(영문 · 숫자 · -, _ 로 6~64자)에 맞고, 예약마다 다르다.
     */
    public static String orderIdOf(Reservation r) {
        return "BT" + r.getId() + "-" + r.getRequestKey().substring(0, 8);
    }

    /** 트랜잭션 안에서 엔티티 → 값 (r 은 회차 · 투어까지 JOIN FETCH 된 상태) */
    public static PaymentTarget of(Reservation r, Payment p) {
        return new PaymentTarget(
                r.getId(), r.getSchedule().getId(), r.getSchedule().getTour().getName(),
                r.getSchedule().getStartAt(), r.getHeadcount(), r.getAmount(),
                r.getStatus(), r.getExpiresAt(), r.getConfirmedAt() != null, orderIdOf(r),
                p != null ? p.getId() : null,
                p != null ? p.getPaymentKey() : null,
                p != null ? p.getStatus() : null);
    }
}
