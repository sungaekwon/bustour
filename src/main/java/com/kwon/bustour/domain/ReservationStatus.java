package com.kwon.bustour.domain;

/**
 * 예약 상태 (reservation.status 컬럼)
 *
 *   PENDING   : 결제 대기 (자리를 잡아 둔 상태, 10분 HOLD)  ─┬→ CONFIRMED (결제 완료)
 *                                                             ├→ EXPIRED   (10분 지남, 자리 반환)
 *                                                             └→ CANCELED  (포기하기, 자리 반환)
 *   CONFIRMED : 확정                                          ──→ CANCELED  (출발 3일 전까지 취소, 전액 환불)
 *
 * ★ "살아 있는 예약" = PENDING, CONFIRMED → DB 계산 컬럼 active_key = 1
 *   UNIQUE(schedule_id, member_id, active_key) 로 한 사람이 한 회차에 1개만 (규칙 R5)
 * ★ 상태는 "지금 이 상태일 때만 바꾸기"(조건부 UPDATE)로 바꾼다 → 7단계
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    EXPIRED,
    CANCELED
}
