package com.kwon.bustour.pg;

/**
 * PG 호출 실패 (연결 실패 · 시간 초과 · 취소 실패 …) — 7단계
 *
 * ★ BusinessException 과 따로 둔 이유
 *   PG 실패는 "보상할지, 기록을 남길지"를 서비스가 상황마다 다르게 판단해야 한다.
 *   그래서 바로 화면 오류로 바꾸지 않고, PaymentService 가 잡아서 알맞은 ErrorCode 로 바꾼다.
 */
public class PgException extends RuntimeException {

    public PgException(String message) {
        super(message);
    }

    public PgException(String message, Throwable cause) {
        super(message, cause);
    }
}
