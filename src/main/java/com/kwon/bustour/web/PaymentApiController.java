package com.kwon.bustour.web;

import com.kwon.bustour.dto.ConfirmPaymentRequest;
import com.kwon.bustour.dto.PaymentResult;
import com.kwon.bustour.security.LoginUser;
import com.kwon.bustour.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 결제 확정 API (7단계) — 결제 화면의 가짜 결제창 → fetch
 *
 *   POST /api/payments/confirm
 *   요청: {"reservationId":12,"paymentKey":"fake_…","amount":20000}  + 헤더 X-CSRF-TOKEN
 *
 *   200 PAID         : 이번 요청으로 확정
 *   200 PAID_ALREADY : 같은 결제 번호로 이미 확정돼 있음 (더블클릭 · 재전송 → 처음 결과, 멱등성)
 *   400 AMOUNT_MISMATCH, PAYMENT_DECLINED / 404 RESERVATION_NOT_FOUND
 *   409 PAYMENT_EXPIRED, ALREADY_PAID, PAYMENT_CANCELED_BY_SYSTEM(보상됨), DUPLICATE_PAYMENT_REFUNDED(보상됨) …
 *   500 COMPENSATION_FAILED (보상 실패 → 기록됨) / 502 PG_ERROR
 *
 * ★ 이 API 는 가짜 결제창(bustour.pg.type=fake)이 쓴다.
 *   토스는 결제창이 successUrl(GET /payments/{id}/toss/success)로 돌려보내므로 PaymentController 가
 *   같은 PaymentService.confirm 을 부른다. → 확정 · 보상 로직은 한 곳(PaymentService)뿐이다.
 */
@RestController
@RequiredArgsConstructor
public class PaymentApiController {

    private final PaymentService paymentService;

    @PostMapping("/api/payments/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@AuthenticationPrincipal LoginUser loginUser,
                                                       @Valid @RequestBody ConfirmPaymentRequest req) {
        PaymentResult r = paymentService.confirm(loginUser.getMemberId(),   // ★ 로그인 정보 (R19)
                req.getReservationId(), req.getPaymentKey(), req.getAmount());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", r.reservationId());
        data.put("amount", r.amount());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("code", r.created() ? "PAID" : "PAID_ALREADY");
        body.put("message", "결제가 완료되어 예약이 확정되었습니다.");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
