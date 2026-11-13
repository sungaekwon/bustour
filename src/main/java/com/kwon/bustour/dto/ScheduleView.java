package com.kwon.bustour.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 시간 버튼 하나 (회차 하나)
 *   statusText : "37석" / "매진" / "운영 중지" (화면은 판단하지 않고 보여 주기만)
 *   bookable   : 버튼을 누를 수 있게 할지 (안내용. 진짜 판단은 5단계 원자적 UPDATE)
 */
@Getter
@AllArgsConstructor
public class ScheduleView {
    private final Long id;
    private final LocalDateTime startAt;
    private final int capacity;
    private final int remaining;
    private final String statusText;
    private final boolean bookable;      // Lombok: boolean 이라 getter 이름은 isBookable() → 화면에서 ${s.bookable}
}
