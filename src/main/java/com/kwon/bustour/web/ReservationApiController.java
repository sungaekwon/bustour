package com.kwon.bustour.web;

import com.kwon.bustour.dto.ReservationResult;
import com.kwon.bustour.dto.ReserveRequest;
import com.kwon.bustour.security.LoginUser;
import com.kwon.bustour.service.PaymentService;
import com.kwon.bustour.service.ReservationService;
import com.kwon.bustour.service.ReservationStateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 예약 API (모달의 "예약하고 결제" → fetch)
 *
 *   POST /api/reservations
 *   요청: {"scheduleId":12,"headcount":2,"requestKey":"…"}  + 헤더 X-CSRF-TOKEN
 *
 *   201 Created : 새로 예약함 (자리 잡음, PENDING)
 *   200 OK      : 같은 요청 키로 이미 만든 예약 → 처음 결과 그대로 (더블클릭 · 재전송)
 *   400 / 409 / 503 … : ApiExceptionHandler 가 {"success":false,"code":…} 로 응답
 *   401         : 로그인 안 함 (SecurityConfig 의 /api/** 규칙)
 *   403         : CSRF 토큰 없음 · 틀림 (SecurityConfig)
 *
 * (7단계) POST /api/reservations/{id}/abandon : 포기하기 (결제 전, 자리 반환)
 * (7단계) POST /api/reservations/{id}/cancel  : 취소하기 (결제 후, 출발 3일 전까지 전액 환불)
 *   ★ 주소의 {id} 는 믿지 않는다: 서비스가 "로그인한 회원의 예약인지"를 SQL 조건(member_id)으로 확인 (규칙 R19)
 *     남의 예약 번호를 넣으면 404 (남의 예약이 있다는 것조차 알려 주지 않음)
 *
 * ★ 컨트롤러는 얇게: 입력 검사(@Valid) → 서비스 호출 → JSON 만들기. 판단은 서비스가.
 * ★ 성공 JSON 도 실패와 같은 모양(success, code, message)으로 → 화면 코드가 단순해짐
 */
@RestController
@RequiredArgsConstructor
public class ReservationApiController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReservationService reservationService;
    private final ReservationStateService stateService;
    private final PaymentService paymentService;
    private final Clock clock;

    /**
     * @param loginUser 시큐리티가 세션에서 꺼내 줌. /api/** 는 로그인해야 들어오므로 null 이 아님
     * @param req       @RequestBody: 요청 본문 JSON → ReserveRequest / @Valid: 그 안의 조건 검사
     *                  (검사 실패 → MethodArgumentNotValidException → ApiExceptionHandler 가 400)
     */
    @PostMapping("/api/reservations")
    public ResponseEntity<Map<String, Object>> reserve(@AuthenticationPrincipal LoginUser loginUser,
                                                       @Valid @RequestBody ReserveRequest req) {

        ReservationResult r = reservationService.reserve(
                loginUser.getMemberId(),                 // ★ 화면이 보낸 값이 아니라 로그인 정보 (R19)
                req.getScheduleId(), req.getHeadcount(), req.getRequestKey());

        // ★ 남은 결제 시간을 "초"로 계산해서 보냄
        //   만료 시각만 보내면 화면이 사용자 PC 시계로 계산하는데, PC 시계가 틀리면 남은 시간이 틀어진다.
        //   서버가 "지금부터 몇 초 남음"을 알려 주면 PC 시계와 상관없이 정확하다.
        long remainSeconds = Math.max(0,
                Duration.between(LocalDateTime.now(clock), r.getExpiresAt()).getSeconds());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", r.getReservationId());
        data.put("headcount", r.getHeadcount());
        data.put("amount", r.getAmount());
        data.put("status", r.getStatus());
        data.put("expiresAt", r.getExpiresAt().format(FMT));    // 날짜는 글자로 (JSON 날짜 설정을 신경 쓰지 않게)
        data.put("remainSeconds", remainSeconds);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("code", r.isCreated() ? "RESERVED" : "ALREADY_RESERVED");
        body.put("message", "자리를 잡았습니다. 10분 안에 결제해 주세요.");
        body.put("data", data);

        // 새로 만들었으면 201, 같은 요청의 재전송이면 200
        return ResponseEntity.status(r.isCreated() ? 201 : 200).body(body);
    }

    /** 포기하기 (7단계): PENDING → CANCELED + 자리 반환 */
    @PostMapping("/api/reservations/{id}/abandon")
    public ResponseEntity<Map<String, Object>> abandon(@AuthenticationPrincipal LoginUser loginUser,
                                                       @PathVariable("id") Long reservationId) {
        stateService.abandon(loginUser.getMemberId(), reservationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("code", "ABANDONED");
        body.put("message", "예약을 포기했습니다. 잡아 둔 자리는 다른 분께 돌아갑니다.");
        return ResponseEntity.ok(body);
    }

    /** 취소하기 (7단계): CONFIRMED → CANCELED + PG 전액 환불 + 자리 반환 (출발 3일 전까지) */
    @PostMapping("/api/reservations/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@AuthenticationPrincipal LoginUser loginUser,
                                                      @PathVariable("id") Long reservationId) {
        int refund = paymentService.cancel(loginUser.getMemberId(), reservationId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", reservationId);
        data.put("refundAmount", refund);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("code", "CANCELED");
        body.put("message", String.format("예약을 취소했습니다. 결제 금액 %,d원이 환불됩니다.", refund));
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
