package com.kwon.bustour.web;

import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import com.kwon.bustour.security.LoginUser;
import com.kwon.bustour.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * =====================================================================
 * 결제 화면 (로그인 필요) — 7단계
 *
 *   GET /payments/{id}               결제 화면 (가짜 결제창 또는 토스 결제 버튼)
 *   GET /payments/{id}/toss/success  (토스) 결제창 인증 성공 → 여기서 승인 · 확정 → 내 예약으로
 *   GET /payments/{id}/toss/fail     (토스) 결제창 실패 · 취소 → 안내 화면
 *
 * ★ 어느 결제창을 보여 줄지: bustour.pg.type (fake / toss) — TossPgClient · FakePgClient 를 고르는 값과 같음
 *
 * ★ 토스 흐름 (결제창, API 개별 연동 키)
 *   ① 결제 화면에서 "토스페이먼츠로 결제" → 토스 결제창 (클라이언트 키, 브라우저)
 *   ② 카드 인증 성공 → 토스가 브라우저를 successUrl 로 보냄: ?paymentKey=…&orderId=…&amount=…
 *      ★ 이 순간에는 아직 돈이 나가지 않았다 ("인증"만 됨). 서버가 승인 API 를 불러야 결제 완료.
 *   ③ 이 컨트롤러가 PaymentService.confirm 호출 (금액 · 주문번호 비교 → 토스 승인 → 확정 → 실패 시 보상)
 *   ④ 성공 → 내 예약(?paid) / 실패 → 안내 화면
 *
 * ★ 확정을 GET 에서 하는 이유와 안전한 이유
 *   토스는 successUrl 로 "브라우저를 이동"시키므로 GET 으로 들어온다. 여기서 바로 확정하면 자바스크립트가 필요 없다.
 *   새로고침으로 같은 요청이 다시 와도 같은 paymentKey → "이미 확정됨"으로 처리되어 두 번 결제되지 않는다 (멱등성).
 *   금액 · 주문번호 · 내 예약인지는 서버가 다시 확인한다 (주소창의 값을 믿지 않음).
 * =====================================================================
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Value("${bustour.pg.type:fake}")
    private String pgType;

    /** 토스 클라이언트 키 (test_ck_…) — 브라우저에 보내도 되는 키. 시크릿 키는 절대 화면에 넣지 않는다 */
    @Value("${bustour.pg.toss.client-key:}")
    private String tossClientKey;

    @GetMapping("/payments/{id}")
    public String checkout(@AuthenticationPrincipal LoginUser loginUser,
                           @PathVariable("id") Long reservationId, Model model) {
        try {
            model.addAttribute("checkout", paymentService.checkout(loginUser.getMemberId(), reservationId));
        } catch (BusinessException e) {
            throw notFoundOr(e);
        }
        model.addAttribute("pgType", pgType);
        model.addAttribute("tossClientKey", tossClientKey);
        return "payment/checkout";                // templates/payment/checkout.html
    }

    /**
     * (토스) 결제창 인증 성공 → 승인 · 확정
     *   토스가 붙여 주는 값: paymentKey, orderId, amount (+ paymentType)
     */
    @GetMapping("/payments/{id}/toss/success")
    public String tossSuccess(@AuthenticationPrincipal LoginUser loginUser,
                              @PathVariable("id") Long reservationId,
                              @RequestParam("paymentKey") String paymentKey,
                              @RequestParam("orderId") String orderId,
                              @RequestParam("amount") int amount,
                              Model model) {
        // 결제 번호 모양 확인 (API 의 @Pattern 과 같은 규칙: 로그 · SQL 에 이상한 글자가 들어가지 않게)
        if (paymentKey.length() > 200 || !paymentKey.matches("^[A-Za-z0-9_-]+$")) {
            return result(model, reservationId, "결제를 완료하지 못했습니다.",
                    ErrorCode.INVALID_INPUT.getMessage(), false);
        }
        try {
            paymentService.confirm(loginUser.getMemberId(), reservationId, paymentKey, amount, orderId);
            return "redirect:/my/reservations?paid";             // PRG: 새로고침해도 다시 확정하지 않게 이동
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.RESERVATION_NOT_FOUND) {
                throw notFoundOr(e);
            }
            // 거절 · PG 연결 실패는 예약이 PENDING 그대로 → 다시 결제 가능. 나머지(시간 지남 · 보상됨 …)는 불가
            boolean retry = e.getErrorCode() == ErrorCode.PAYMENT_DECLINED
                    || e.getErrorCode() == ErrorCode.PG_ERROR;
            return result(model, reservationId, "결제를 완료하지 못했습니다.", e.getMessage(), retry);
        }
    }

    /**
     * (토스) 결제창 실패 · 사용자가 닫음 → 승인 API 를 부르지 않는다 (돈이 나가지 않음)
     *   토스가 붙여 주는 값: code, message, orderId
     */
    @GetMapping("/payments/{id}/toss/fail")
    public String tossFail(@PathVariable("id") Long reservationId,
                           @RequestParam(value = "code", required = false) String code,
                           @RequestParam(value = "message", required = false) String message,
                           Model model) {
        log.info("[토스] 결제창 실패: reservation={}, code={}, message={}", reservationId, code, message);
        String text = "PAY_PROCESS_CANCELED".equals(code)
                ? "결제를 취소하셨습니다. 결제 시간 안에 다시 결제할 수 있어요."
                : (message != null ? message : "결제가 진행되지 않았습니다.");
        return result(model, reservationId, "결제가 진행되지 않았습니다.", text, true);
    }

    /** 결과 안내 화면 (templates/payment/result.html) */
    private String result(Model model, Long reservationId, String title, String message, boolean retry) {
        model.addAttribute("resultTitle", title);
        model.addAttribute("resultMessage", message);
        model.addAttribute("retryId", retry ? reservationId : null);   // 다시 결제 버튼 (null 이면 숨김)
        return "payment/result";
    }

    /** 없거나 남의 예약 → 404 화면, 그 밖의 오류는 그대로 */
    private RuntimeException notFoundOr(BusinessException e) {
        if (e.getErrorCode() == ErrorCode.RESERVATION_NOT_FOUND) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return e;
    }
}
