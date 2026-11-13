package com.kwon.bustour.pg;

/**
 * PG 가 승인을 거절함 (카드 거절 · 한도 초과 · 잔액 부족) — 7단계
 *   돈이 나가지 않았으므로 보상(취소)할 것이 없다 → 예약은 PENDING 그대로, 다시 결제 가능
 */
public class PgDeclinedException extends PgException {

    public PgDeclinedException(String message) {
        super(message);
    }
}
