package com.kwon.bustour.domain;

/**
 * 결제 기록 종류 (payment_log.event 컬럼, 7단계) — DB CHECK 의 목록과 같아야 한다
 *
 *   AMOUNT_MISMATCH          : 화면이 보낸 금액 ≠ 예약 금액 → PG 승인을 부르지 않고 거절 (조작 의심)
 *   APPROVED                 : 승인 + 예약 확정 성공
 *   CANCELED_BY_COMPENSATION : 승인은 됐는데 확정 실패(시간 지남 · 이미 결제됨) → PG 취소로 되돌림 (보상)
 *   COMPENSATION_FAILED      : 그 보상(PG 취소)마저 실패 → ★ 사람이 확인해야 함 (돈은 나갔는데 예약은 없음)
 *   REFUNDED                 : 고객 취소 → 환불 성공
 *   REFUND_FAILED            : 환불(PG 취소) 실패 → DB 는 그대로 (확정 상태), 다시 시도 가능
 */
public enum PaymentLogEvent {
    AMOUNT_MISMATCH,
    APPROVED,
    CANCELED_BY_COMPENSATION,
    COMPENSATION_FAILED,
    REFUNDED,
    REFUND_FAILED
}
