package com.kwon.bustour.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 내 예약 화면의 한 줄
 *
 *   statusText   : 화면에 보일 상태 글자 ("결제 대기", "예약 확정", "결제 시간 지남" …)
 *   badge        : 상태 표시 모양 class ("badge-wait", "badge-ok", "badge-no")
 *   pending      : 지금 결제할 수 있는 PENDING 인가 (만료 시각 전) → 남은 시간 · 결제 버튼
 *   remainSeconds: 결제 남은 시간(초). 서버 시계로 계산 (사용자 PC 시계가 틀려도 정확)
 *   cancelable   : (7단계) 지금 취소할 수 있나 = 확정 + 출발 3일 전보다 앞
 *   cancelUntil  : (7단계) 확정 예약의 취소 마감 시각 (= 출발 3일 전). 확정이 아니면 null
 */
@Getter
@AllArgsConstructor
public class MyReservationView {
    private final Long id;
    private final String tourName;
    private final LocalDateTime startAt;
    private final int headcount;
    private final int amount;
    private final String statusText;
    private final String badge;
    private final boolean pending;
    private final long remainSeconds;
    private final boolean cancelable;
    private final LocalDateTime cancelUntil;
    private final LocalDateTime createdAt;
}
