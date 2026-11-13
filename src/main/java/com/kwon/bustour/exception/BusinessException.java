package com.kwon.bustour.exception;

import lombok.Getter;

/**
 * 규칙에 맞지 않는 요청 (매진, 마감, 중복 …) → ErrorCode 하나를 들고 던진다
 *
 * ★ RuntimeException 을 상속하는 이유
 *   1. 메서드마다 throws 를 쓰지 않아도 됨 (언체크 예외)
 *   2. @Transactional 은 기본으로 "RuntimeException 이 나가면 롤백"
 *      → 예외를 던지는 순간, 그 트랜잭션에서 이미 줄인 자리(UPDATE)도 같이 되돌아간다.
 *        (체크 예외(Exception 상속)는 기본으로 롤백하지 않아서 자리가 줄어든 채 커밋될 수 있음)
 *
 * ★ 문장 두 가지
 *   new BusinessException(SOLD_OUT)               → "남은 자리가 부족합니다."           (ErrorCode 기본 문장)
 *   new BusinessException(SOLD_OUT, "… 2석 …")    → "남은 자리가 부족합니다. (지금 남은 자리 2석)" (상황에 맞는 문장)
 *   code(SOLD_OUT)는 같으므로 화면은 code 로 처리를 나누고, 문장은 보여 주기만 하면 된다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
