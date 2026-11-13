package com.kwon.bustour.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 결제 확정 요청 (POST /api/payments/confirm) — 7단계
 *   {"reservationId":12,"paymentKey":"fake_…","amount":20000}
 *
 * ★ amount 를 받는 이유 (믿으려고가 아니라 "비교하려고")
 *   토스 흐름에서는 결제창이 끝나면 화면이 paymentKey · orderId · amount 를 받아 서버로 보낸다.
 *   서버는 이 amount 를 그대로 쓰지 않고, 예약할 때 저장해 둔 reservation.amount 와 비교만 한다 (규칙 R15).
 *   다르면 누군가 화면에서 금액을 바꾼 것 → 승인하지 않고 payment_log 에 AMOUNT_MISMATCH 기록.
 *
 * ★ 회원 번호는 받지 않는다 → 로그인 정보에서 (규칙 R19)
 */
@Getter
@Setter
@NoArgsConstructor
public class ConfirmPaymentRequest {

    @NotNull(message = "예약 번호가 없습니다.")
    private Long reservationId;

    // 결제 번호: 영문 · 숫자 · - · _ 만 (로그 · SQL 에 이상한 글자가 들어가지 않게), 최대 200자 (컬럼 크기)
    @NotBlank(message = "결제 번호가 없습니다.")
    @Size(max = 200, message = "결제 번호가 너무 깁니다.")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "결제 번호 형식이 올바르지 않습니다.")
    private String paymentKey;

    @NotNull(message = "결제 금액이 없습니다.")
    @Positive(message = "결제 금액이 올바르지 않습니다.")
    private Integer amount;
}
