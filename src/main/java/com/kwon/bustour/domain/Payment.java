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
 * payment 테이블 (결제, 7단계) — 예약 1건 : 승인된 결제 1건
 *
 * ★ 두 UNIQUE 가 "한 번만 확정"을 DB 에서 보장한다 (규칙 R16)
 *   uk_payment_key         : 같은 결제 번호로 두 번 확정 금지 (더블클릭 · 재전송 · 웹훅 두 번)
 *   uk_payment_reservation : 예약 1건에 결제 1건 (두 탭에서 각각 결제해도 1건만 남음)
 *
 * ★ 예약(reservation)과 @ManyToOne 인 이유
 *   DB 는 reservation_id UNIQUE 라 사실상 1:1 이지만, @OneToOne 은 반대쪽(Reservation)에서 읽을 때
 *   지연 로딩이 안 되는 함정이 있어서, 외래키를 가진 쪽만 단방향 @ManyToOne 으로 둔다.
 *
 * ★ setter 없음. 만들 때는 approved(...) 로만, 환불 표시는 조건부 UPDATE(PaymentRepository.markCanceled)로만.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    private String paymentKey;            // PG 가 준 결제 번호 (UNIQUE)

    private String orderId;               // 우리가 PG 에 보낸 주문 번호 ("BT12-a1b2c3d4")

    private int amount;                   // 승인된 금액 (= reservation.amount)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)       // DB 컬럼이 VARCHAR → Hibernate 가 MySQL ENUM 으로 착각하지 않게
    private PaymentStatus status;

    private LocalDateTime approvedAt;     // PG 승인 시각

    private LocalDateTime canceledAt;     // 환불 시각

    /** 승인된 결제 만들기 (예약 확정 UPDATE 가 1행일 때만 부름) */
    public static Payment approved(Reservation reservation, String paymentKey, String orderId,
                                   int amount, LocalDateTime approvedAt) {
        Payment p = new Payment();
        p.reservation = reservation;
        p.paymentKey = paymentKey;
        p.orderId = orderId;
        p.amount = amount;
        p.status = PaymentStatus.APPROVED;
        p.approvedAt = approvedAt;
        return p;
    }
}
