package com.kwon.bustour.dto;

/**
 * 결제 확정 결과 (7단계, JSON 응답용)
 *
 * @param created true = 이번 요청으로 확정 / false = 같은 결제 번호로 이미 확정돼 있던 것 (더블클릭 · 재전송, 멱등성)
 */
public record PaymentResult(Long reservationId, int amount, String paymentKey, boolean created) {
}
