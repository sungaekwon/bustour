package com.kwon.bustour.domain;

/**
 * 결제 상태 (payment.status 컬럼, 7단계)
 *
 *   APPROVED : PG 승인 + 예약 확정까지 끝난 결제
 *   CANCELED : 환불됨 (결정: 출발 3일 전까지 취소 → 전액 환불)
 *
 * ★ "승인은 됐는데 확정이 실패한 결제"는 payment 행이 생기지 않는다.
 *   그 결제는 보상(PG 취소)으로 되돌리고, 기록은 payment_log 에만 남긴다.
 */
public enum PaymentStatus {
    APPROVED,
    CANCELED
}
