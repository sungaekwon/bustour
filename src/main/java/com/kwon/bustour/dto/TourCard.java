package com.kwon.bustour.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 메인 3단 화면의 투어 "한 칸"에 필요한 값 전부
 *   투어 정보 + 코스(stops) + 출발 시간(처음 7일 firstDays / 나머지 moreDays)
 *
 * theme : 칸 색 이름 ("theme-1", "theme-2", "theme-3") → 화면에서 class 로 붙임
 *         CSS(common.css)의 .theme-1 { --tour-color: … } 가 머리 상자 · 시간 버튼 · 모달 색을 정함
 */
@Getter
@AllArgsConstructor
public class TourCard {
    private final Long id;
    private final String name;
    private final String description;
    private final int price;
    private final String durationText;
    private final String theme;
    private final List<CourseStopView> stops;
    private final List<ScheduleDay> firstDays;   // 처음 7일 (바로 보임)
    private final List<ScheduleDay> moreDays;    // 8일째부터 ("날짜 더 보기"를 누르면 보임)
}
