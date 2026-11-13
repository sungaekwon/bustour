package com.kwon.bustour.dto;

import java.time.LocalDateTime;

/**
 * 결제 화면(/payments/{id}) 한 장에 필요한 값 (7단계)
 *
 * @param payable       지금 결제할 수 있나 (PENDING + 만료 전) → 가짜 결제창 버튼을 보여 줄지
 * @param remainSeconds 남은 결제 시간(초, 서버 시계로 계산)
 * @param notice        결제할 수 없을 때 이유 문장 ("이미 결제가 끝난 예약입니다." …)
 */
public record CheckoutView(
        Long reservationId,
        String tourName,
        LocalDateTime startAt,
        int headcount,
        int amount,
        String orderId,
        boolean payable,
        long remainSeconds,
        String notice) {
}
