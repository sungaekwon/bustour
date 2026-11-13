package com.kwon.bustour.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * course_stop 테이블 (코스 정류장: 투어 1개 : 정류장 N개)
 */
@Entity
@Table(name = "course_stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ★ N : 1 관계 (정류장 여러 개가 투어 하나에 속함)
    //   @JoinColumn(name = "tour_id") : 이 테이블의 tour_id 컬럼이 tour.id 를 가리킨다 (외래키)
    //   fetch = LAZY : 정류장을 읽을 때 투어는 "아직" 읽지 않음 → getTour().getName() 을 부르는 순간 SELECT
    //   ※ @ManyToOne 의 기본값은 EAGER(즉시 로딩)라서, 쓸데없는 JOIN · N+1을 막으려고 LAZY를 직접 적는다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id")
    private Tour tour;

    private int seq;                      // 방문 순서 1, 2, 3 … (UNIQUE(tour_id, seq))

    private String placeName;             // → place_name

    private String description;

    // DECIMAL(10,7) → BigDecimal (double은 소수 저장 오차가 있음)
    @Column(precision = 10, scale = 7)
    private BigDecimal lat;               // 위도

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;               // 경도

    private String imageKey;
}
