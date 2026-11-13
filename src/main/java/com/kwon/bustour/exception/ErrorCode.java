package com.kwon.bustour.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 오류 종류 한곳에 모으기: 코드 이름 + HTTP 상태 + 사용자에게 보여 줄 문장
 *
 * ★ 6단계에서 @RestControllerAdvice 가 BusinessException 을 잡아
 *   {"success":false,"code":"SOLD_OUT","message":"남은 자리가 부족합니다."} 로 응답한다.
 *   → 화면(자바스크립트)은 code 를 보고 다르게 처리할 수 있다 (예: DUPLICATE_RESERVATION → "이어서 결제" 안내)
 *
 * ★ 409 Conflict : "요청은 맞는데 지금 상태와 충돌" (매진, 중복 예약 …)
 *   400 Bad Request : "요청 자체가 잘못됨" (인원 0명 …)
 *   404 Not Found : 없음
 */
@Getter
public enum ErrorCode {

    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다."),
    INVALID_HEADCOUNT(HttpStatus.BAD_REQUEST, "인원은 1명부터 10명까지 선택할 수 있습니다."),

    SOLD_OUT(HttpStatus.CONFLICT, "남은 자리가 부족합니다."),
    BOOKING_CLOSED(HttpStatus.CONFLICT, "예약이 마감된 회차입니다. (출발 1시간 전 마감)"),
    SCHEDULE_CLOSED(HttpStatus.CONFLICT, "운영하지 않는 회차입니다."),

    DUPLICATE_RESERVATION(HttpStatus.CONFLICT, "이미 이 회차에 예약(또는 결제 대기)이 있습니다."),
    REQUEST_KEY_CONFLICT(HttpStatus.CONFLICT, "잘못된 요청입니다. 화면을 새로고침한 뒤 다시 시도해 주세요."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다. 잠시 후 내 예약에서 확인해 주세요."),

    // ----- 7단계: 결제 · 포기 · 취소 -----
    // 404: 없거나 "남의 예약" (규칙 R19: 남의 예약이 있다는 것조차 알려 주지 않음)
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    NOT_PAYABLE(HttpStatus.CONFLICT, "결제할 수 없는 예약입니다. (만료 또는 취소됨)"),
    ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제가 끝난 예약입니다."),
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "결제 시간(10분)이 지났습니다. 다시 예약해 주세요."),
    // 400: 화면이 보낸 금액 ≠ 예약할 때 서버가 계산해 둔 금액 (규칙 R15, 조작 의심 → 기록)
    AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 예약 금액과 다릅니다. 내 예약에서 다시 결제해 주세요."),
    // 토스가 돌려준 주문번호 ≠ 이 예약의 주문번호 (다른 예약의 결제를 끼워 넣으려는 시도)
    ORDER_MISMATCH(HttpStatus.BAD_REQUEST, "주문 정보가 맞지 않습니다. 내 예약에서 다시 결제해 주세요."),
    PAYMENT_DECLINED(HttpStatus.BAD_REQUEST, "결제가 거절되었습니다. 다른 결제 수단으로 다시 시도해 주세요."),
    // 502 Bad Gateway: 우리 서버가 아니라 "바깥 서버(PG)"가 실패
    PG_ERROR(HttpStatus.BAD_GATEWAY, "결제사 연결에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    // 보상 성공: 승인은 됐지만 확정할 수 없어서 PG 취소로 되돌림 (돈은 돌려줌)
    PAYMENT_CANCELED_BY_SYSTEM(HttpStatus.CONFLICT, "결제 시간이 지나 결제를 자동으로 취소했습니다. (결제 금액은 환불됩니다)"),
    // (9단계 부하테스트로 발견) PG 승인 뒤 DB 가 붐벼 확정을 못 함 → 보상(PG 취소). 예약은 결제 대기 그대로라 다시 결제 가능
    PAYMENT_CANCELED_SERVER_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "결제 처리 중 서버가 붐벼 결제를 자동으로 취소했습니다. (결제 금액은 환불됩니다) 잠시 후 다시 결제해 주세요."),
    DUPLICATE_PAYMENT_REFUNDED(HttpStatus.CONFLICT, "이미 결제된 예약이라 이번 결제는 자동으로 취소했습니다. (중복 결제 금액은 환불됩니다)"),
    // 보상 실패: 돈은 나갔는데 예약은 확정 못 함 → payment_log 에 남기고 사람이 확인 (규칙 R18)
    COMPENSATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "결제 처리 중 문제가 생겼습니다. 결제 금액은 확인 후 환불해 드립니다. (고객센터 문의)"),

    NOT_ABANDONABLE(HttpStatus.CONFLICT, "이미 결제 · 만료 · 취소된 예약이라 포기할 수 없습니다."),
    NOT_CANCELABLE(HttpStatus.CONFLICT, "결제가 끝난(확정된) 예약만 취소할 수 있습니다."),
    CANCEL_CLOSED(HttpStatus.CONFLICT, "취소는 출발 3일 전까지만 할 수 있습니다."),
    ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 예약입니다."),
    // (9단계 부하테스트로 발견) PG 환불은 됐는데 DB 취소를 못 함 → 다시 누르면 맞춰짐 (PG 취소는 멱등)
    CANCEL_RETRY_NEEDED(HttpStatus.SERVICE_UNAVAILABLE, "환불은 완료됐지만 예약 취소 처리가 끝나지 않았습니다. 잠시 후 '취소하기'를 한 번 더 눌러 주세요."),
    REFUND_FAILED(HttpStatus.BAD_GATEWAY, "환불 요청에 실패했습니다. 예약은 그대로 유지됩니다. 잠시 후 다시 시도해 주세요."),

    // ----- 6단계: 공통 -----
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    // 503 Service Unavailable: 락 대기 3초 초과 · 커넥션 대기 초과 · 데드락 → "지금 붐빔, 같은 요청으로 다시 시도 가능"
    SERVER_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "지금 예약이 몰려 있습니다. 잠시 후 다시 눌러 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
