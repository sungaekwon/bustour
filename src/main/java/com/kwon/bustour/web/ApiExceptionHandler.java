package com.kwon.bustour.web;

import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * =====================================================================
 * API 오류를 한곳에서 JSON 으로 바꾸기
 *   {"success":false,"code":"SOLD_OUT","message":"매진되었습니다."}
 *
 * ★ @RestControllerAdvice(annotations = RestController.class)
 *   @RestController 가 붙은 컨트롤러(API)에서 나온 예외만 여기서 처리한다.
 *   화면용 @Controller 의 예외(TourNotFoundException 등)는 그대로 404.html 같은 오류 화면으로 간다.
 *   → API 는 JSON, 화면은 HTML 로 오류 모양이 섞이지 않게
 *
 * ★ 서블릿 프로젝트와 비교
 *   서블릿: doPost 안에서 try-catch 로 경우마다 resp.setStatus(409) + JSON 직접 작성
 *   부트:   서비스는 예외만 던지고, 여기 한 곳에서 예외 종류별로 상태 코드와 JSON 을 정함
 * =====================================================================
 */
@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {

    /** 규칙 위반 (매진, 마감, 중복 예약 …) → ErrorCode 에 정해 둔 상태 코드 (409, 400, 404) */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus()).body(body(code.name(), e.getMessage()));
    }

    /** @Valid 검사 실패 (인원 11명, 요청 키 모양이 이상함 …) → 400 + 첫 번째 오류 문장 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldError();      // 여러 개면 첫 번째만 보여 줌
        String msg = first != null ? first.getDefaultMessage() : ErrorCode.INVALID_INPUT.getMessage();
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(body(ErrorCode.INVALID_INPUT.name(), msg));
    }

    /** JSON 모양이 깨졌거나 숫자 자리에 글자 ("headcount":"two") → 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(body(ErrorCode.INVALID_INPUT.name(), ErrorCode.INVALID_INPUT.getMessage()));
    }

    /** (7단계) 주소의 숫자 자리에 글자 (/api/reservations/abc/cancel) → 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(body(ErrorCode.INVALID_INPUT.name(), ErrorCode.INVALID_INPUT.getMessage()));
    }

    /**
     * ★ 붐빔 → 503 (같은 요청 키로 다시 눌러도 안전 = 멱등성 덕분)
     *   PessimisticLockingFailureException : 락 대기 3초 초과(1205), 데드락(1213)
     *   QueryTimeoutException              : 쿼리 시간 초과
     *   CannotCreateTransactionException   : 커넥션 풀에서 3초 안에 커넥션을 못 받음 (트랜잭션 시작 실패)
     *   이 실패들은 모두 롤백되어 자리가 줄어든 채 남지 않는다.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class,
                       QueryTimeoutException.class,
                       CannotCreateTransactionException.class})
    public ResponseEntity<Map<String, Object>> busy(Exception e) {
        log.warn("예약 붐빔 (503): {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.SERVER_BUSY.getStatus())
                .body(body(ErrorCode.SERVER_BUSY.name(), ErrorCode.SERVER_BUSY.getMessage()));
    }

    /** 그 밖의 예상 못 한 오류 → 500. 원인은 로그에만 남기고 사용자에게는 일반 문장 (내부 정보 노출 방지) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unknown(Exception e) {
        log.error("API 처리 중 예상 못 한 오류", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(body(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    /** 실패 응답 모양 (성공 응답과 같은 키: success, code, message) */
    private Map<String, Object> body(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
