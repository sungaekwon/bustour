package com.kwon.bustour.pg;

import java.time.LocalDateTime;

/**
 * PG 승인 결과 (7단계)
 *
 * ★ record : 값만 담는 불변 클래스를 한 줄로 (Java 16+)
 *   필드 · 생성자 · getter(paymentKey(), amount() …) · equals · toString 을 컴파일러가 만들어 준다.
 *
 * @param paymentKey PG 결제 번호
 * @param orderId    우리 주문 번호
 * @param amount     PG 가 실제로 승인한 금액 (요청 금액과 다르면 이상 → 서비스가 확인)
 * @param approvedAt PG 승인 시각
 */
public record PgApproval(String paymentKey, String orderId, int amount, LocalDateTime approvedAt) {
}
