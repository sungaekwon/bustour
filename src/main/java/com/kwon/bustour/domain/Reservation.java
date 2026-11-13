package com.kwon.bustour.domain;

import jakarta.persistence.Column;
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
 * reservation 테이블 (예약)
 *
 * ★ active_key 컬럼은 매핑하지 않는다
 *   MySQL 이 status 를 보고 계산하는 컬럼(GENERATED)이라 INSERT/UPDATE 에 넣으면 오류 3105.
 *   엔티티에 필드가 없으면 JPA 는 그 컬럼을 아예 모른다 → INSERT 문에 안 들어감.
 *   (ddl-auto: validate 는 "엔티티 필드 → 컬럼이 있나"만 검사하므로, 테이블에 컬럼이 더 있어도 괜찮다)
 *
 * ★ setter 없음. 만들 때는 hold(...) 로만, 상태 변경은 ReservationRepository 의 조건부 UPDATE 로만 (7단계)
 *   (confirmIfPayable · expireIfPending · abandonIfPending · cancelIfConfirmed — 모두 "지금 이 상태일 때만")
 */
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private TourSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    private int headcount;                // 1~10 (CHECK)

    private int unitPrice;                // 예약 시점의 1인 가격 (스냅샷, 규칙 R15)

    private int amount;                   // unitPrice × headcount (CHECK)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private ReservationStatus status;

    // DB 타입이 CHAR(36) 이라 명시 (안 쓰면 validate 가 VARCHAR 를 기대해서 타입이 다르다고 할 수 있음)
    @Column(columnDefinition = "CHAR(36)")
    private String requestKey;            // 멱등성 키 (UNIQUE, 규칙 R6)

    private LocalDateTime expiresAt;      // HOLD 만료 = 만든 시각 + 10분

    private LocalDateTime confirmedAt;    // 결제 확정 시각 (7단계)

    private LocalDateTime canceledAt;     // 포기 · 취소 시각 (7단계)

    private LocalDateTime createdAt;

    /**
     * 자리를 잡아 둔 예약(PENDING) 만들기
     *
     * @param schedule   회차 (unitPrice 는 schedule 의 투어 가격을 복사)
     * @param member     회원 (getReferenceById 로 만든 "id만 있는 대리 객체"여도 됨 → SELECT 없음)
     * @param now        Clock 으로 만든 지금 (규칙 R22)
     * @param holdMinutes 자리를 잡아 두는 시간 (10분)
     */
    public static Reservation hold(TourSchedule schedule, Member member, int headcount,
                                   String requestKey, LocalDateTime now, long holdMinutes) {
        Reservation r = new Reservation();
        r.schedule = schedule;
        r.member = member;
        r.headcount = headcount;
        r.unitPrice = schedule.getTour().getPrice();   // ★ 지금 가격을 "복사" → 나중에 가격이 바뀌어도 이 예약은 그대로
        r.amount = r.unitPrice * headcount;
        r.status = ReservationStatus.PENDING;          // 무료 투어 없음 → 항상 PENDING 으로 시작
        r.requestKey = requestKey;
        r.expiresAt = now.plusMinutes(holdMinutes);
        r.createdAt = now;
        return r;
    }
}
