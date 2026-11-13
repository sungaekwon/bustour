package com.kwon.bustour.dto;

import com.kwon.bustour.domain.Reservation;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 예약 요청 결과 (6단계에서 JSON 으로 응답)
 *
 * created : 이번 요청으로 새로 만들었으면 true
 *           같은 요청 키로 다시 온 요청(더블클릭, 재전송)이면 false = 처음 결과를 그대로 돌려준 것 (멱등성)
 */
@Getter
@AllArgsConstructor
public class ReservationResult {
    private final Long reservationId;
    private final Long scheduleId;
    private final int headcount;
    private final int amount;
    private final String status;
    private final LocalDateTime expiresAt;
    private final boolean created;

    /**
     * 엔티티 → 결과
     * r.getSchedule().getId() : LAZY 대리 객체라도 id 는 이미 알고 있어서 SELECT 가 나가지 않음
     */
    public static ReservationResult of(Reservation r, boolean created) {
        return new ReservationResult(r.getId(), r.getSchedule().getId(), r.getHeadcount(),
                r.getAmount(), r.getStatus().name(), r.getExpiresAt(), created);
    }
}
