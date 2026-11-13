package com.kwon.bustour.service;

/**
 * 이미 가입된 휴대폰 번호 (규칙 R9)
 * 컨트롤러가 잡아서 회원가입 화면의 휴대폰 칸 아래에 오류 문장을 보여 준다.
 */
public class DuplicatePhoneException extends RuntimeException {

    public DuplicatePhoneException() {
        super("이미 가입된 휴대폰 번호입니다.");
    }
}
