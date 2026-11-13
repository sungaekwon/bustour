package com.kwon.bustour.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 하루치 회차 묶음 (화면의 한 줄: "11월 7일 (토)  [10:00] [14:00]")
 */
@Getter
@AllArgsConstructor
public class ScheduleDay {
    private final LocalDate date;
    private final List<ScheduleView> schedules;   // 그날의 회차들 (출발 시각 순)
}
