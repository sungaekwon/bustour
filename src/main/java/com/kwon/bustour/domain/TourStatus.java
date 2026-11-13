package com.kwon.bustour.domain;

/**
 * 투어 상태 (tour.status 컬럼)
 *   ON_SALE : 판매 중 → 목록에 보임
 *   HIDDEN  : 숨김 (시즌 종료, 준비 중 …) → 목록과 상세에서 안 보임
 *
 * DB에는 이름 그대로 'ON_SALE', 'HIDDEN' 글자로 저장 (@Enumerated(EnumType.STRING))
 * ※ 숨겨도 예약을 막는 건 회차(ScheduleStatus.CLOSED)가 맡는다 (규칙 R14)
 */
public enum TourStatus {
    ON_SALE,
    HIDDEN
}
