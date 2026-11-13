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
 * payment_log 테이블 (결제 기록, 7단계) — 추가만 하고 고치거나 지우지 않는다
 *
 * ★ 왜 payment 와 따로 두나?
 *   "승인은 됐는데 확정 실패 → 보상(PG 취소)"이면 payment 행이 아예 생기지 않는다.
 *   그 보상마저 실패하면 사람이 PG 관리자 화면에서 직접 취소해야 하는데, 그때 필요한 결제 번호가
 *   어딘가에 남아 있어야 한다 → payment 없이도 남는 기록 테이블 (규칙 R18)
 *
 * ★ 저장은 PaymentLogService (REQUIRES_NEW = 별도 트랜잭션)로 → 바깥이 롤백돼도 기록은 남는다
 * ★ 카드 번호 같은 결제 수단 정보는 절대 저장하지 않는다 (message 에도)
 */
@Entity
@Table(name = "payment_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    private String paymentKey;            // NULL 가능 (금액 조작처럼 결제 번호와 상관없는 기록)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private PaymentLogEvent event;

    private String message;               // 실패 이유 등 (최대 500자)

    private LocalDateTime createdAt;

    public static PaymentLog of(Reservation reservation, String paymentKey, PaymentLogEvent event,
                                String message, LocalDateTime now) {
        PaymentLog log = new PaymentLog();
        log.reservation = reservation;
        log.paymentKey = paymentKey;
        log.event = event;
        // 500자 컬럼 → 넘치면 잘라서 저장 (기록 저장이 길이 때문에 실패하면 안 되므로)
        log.message = (message != null && message.length() > 500) ? message.substring(0, 500) : message;
        log.createdAt = now;
        return log;
    }
}
