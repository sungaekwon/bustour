package com.kwon.bustour.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 투어 상세의 코스 정류장 한 줄 */
@Getter
@AllArgsConstructor
public class CourseStopView {
    private final int seq;
    private final String placeName;
    private final String description;
}
