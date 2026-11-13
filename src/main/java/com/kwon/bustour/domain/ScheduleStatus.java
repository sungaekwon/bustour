package com.kwon.bustour.domain;

/**
 * 회차 상태 (tour_schedule.status 컬럼)
 *   OPEN   : 운영
 *   CLOSED : 운영 중지
 *
 * ★ 매진(SOLD_OUT)은 상태로 두지 않는다 → remaining == 0 으로 계산
 *   (같은 사실을 두 곳에 저장하면 하나를 빠뜨렸을 때 어긋남 = 진실의 원천은 하나)
 */
public enum ScheduleStatus {
    OPEN,
    CLOSED
}
