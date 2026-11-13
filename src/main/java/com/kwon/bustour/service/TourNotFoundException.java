package com.kwon.bustour.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 투어가 없거나 숨김일 때 → 404
 *
 * @ResponseStatus(NOT_FOUND) : 이 예외가 컨트롤러 밖으로 던져지면 스프링이 응답 코드를 404로 만든다.
 *   → 스프링 부트가 templates/error/404.html 을 찾아서 보여 준다 (파일 이름 = 상태 코드)
 *
 * ※ 5단계(예외 처리)에서 @ControllerAdvice 로 오류 처리를 한곳에 모을 때 정리한다.
 * ※ RuntimeException 을 상속: 메서드마다 throws 를 쓰지 않아도 됨 (언체크 예외)
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class TourNotFoundException extends RuntimeException {

    public TourNotFoundException(Long tourId) {
        super("투어를 찾을 수 없습니다. id=" + tourId);   // 로그에만 남는 메시지 (화면에는 404.html)
    }
}
