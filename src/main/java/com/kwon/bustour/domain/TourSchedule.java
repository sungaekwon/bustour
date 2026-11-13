package com.kwon.bustour.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * tour_schedule 테이블 (회차 = 투어 + 출발 시각)
 *
 * ★ 규칙 R23: remaining 은 이 엔티티로 고치지 않는다 (setter 없음)
 *   변경 감지(dirty checking)로 이 엔티티를 저장하면 Hibernate는 기본으로 "모든 컬럼"을 UPDATE 한다.
 *   → 엔티티를 읽었을 때의 옛 remaining 으로 덮어써서, 그 사이 다른 요청이 줄인 자리가 사라진다(갱신 손실).
 *   → 자리 차감 · 반환은 5단계에서 리포지토리의 원자적 UPDATE(@Modifying 쿼리)로만 한다.
 *
 * ★ version 컬럼 없음: 원자적 UPDATE는 낙관적 락(@Version)을 쓰지 않는다.
 */
@Entity
@Table(name = "tour_schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TourSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id")
    private Tour tour;

    // DATETIME → LocalDateTime (시간대 정보 없는 "한국 시각" 그대로)
    private LocalDateTime startAt;        // → start_at

    private int capacity;                 // 정원

    private int remaining;                // 남은 자리 (읽기 전용으로 사용)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private ScheduleStatus status;

    /** 매진인가: 상태가 아니라 계산으로 판단 */
    public boolean isSoldOut() {
        return remaining == 0;
    }

    /**
     * 화면에 "예약하기" 버튼을 보여 줘도 되나 (안내용)
     * ※ 진짜 판단은 5단계의 원자적 UPDATE(WHERE status='OPEN' AND remaining >= ? AND start_at > ?)가 한다.
     *   화면을 보고 있는 사이에 매진될 수 있기 때문 (확인 후 행동 문제).
     *   출발 시각 조건(1시간 전 마감)은 서비스가 조회할 때 이미 걸러서 여기서는 보지 않는다.
     */
    public boolean isBookable() {
        return status == ScheduleStatus.OPEN && remaining > 0;
    }
}
