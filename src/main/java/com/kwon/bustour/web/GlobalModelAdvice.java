package com.kwon.bustour.web;

import com.kwon.bustour.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 모든 화면에 공통으로 넘기는 값
 *
 * ★ @ControllerAdvice + @ModelAttribute
 *   모든 컨트롤러의 화면을 그리기 전에 이 메서드가 먼저 실행되고, 돌려준 값이 model 에 들어간다.
 *   → 헤더(fragments/header.html)에서 ${loginName} 으로 "○○○님 / 로그인" 을 바꿔 보여 줄 수 있다.
 *   → 컨트롤러마다 model.addAttribute("loginName", …) 을 쓰지 않아도 됨
 *
 * ※ 타임리프 시큐리티 태그(sec:authorize)를 쓰는 방법도 있지만, 추가 라이브러리의 버전을
 *   시큐리티 버전과 맞춰야 해서 이 방법을 택했다.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    /** 로그인한 사람 이름 (로그인 안 했으면 null) */
    @ModelAttribute("loginName")
    public String loginName(@AuthenticationPrincipal LoginUser loginUser) {
        return loginUser == null ? null : loginUser.getName();
    }
}
