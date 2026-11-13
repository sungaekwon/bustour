package com.kwon.bustour.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 회원가입 폼 (화면의 입력칸 ↔ 이 객체)
 *
 * ★ 화면 → 객체
 *   <input th:field="*{phone}"> 의 값이 POST 될 때 스프링이 setPhone(...) 으로 넣어 준다.
 *   그래서 이 클래스는 기본 생성자 + setter 가 필요하다 (@Setter).
 *
 * ★ @Valid 검사 (컨트롤러에서 @Valid 를 붙이면 아래 조건을 자동 검사 → 실패하면 BindingResult 에 담김)
 *   화면에서 막는 것(required 등)은 사용자 편의용, 진짜 검사는 서버의 이 조건들이다.
 */
@Getter
@Setter
public class SignupForm {

    @NotBlank(message = "이름을 입력해 주세요.")
    @Size(max = 50, message = "이름은 50자까지 입력할 수 있습니다.")
    private String name;

    // 010-1234-5678, 01012345678 둘 다 허용 (저장할 때 숫자만 남김)
    @NotBlank(message = "휴대폰 번호를 입력해 주세요.")
    @Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$",
             message = "휴대폰 번호 형식이 아닙니다. 예) 010-1234-5678")
    private String phone;

    // ★ 영문 + 숫자를 꼭 포함, 8~64자, 키보드의 영문 · 숫자 · 특수문자만 (\x21-\x7E)
    //   BCrypt 는 앞의 72바이트까지만 쓰므로(한글은 1자에 3바이트) 한 글자 = 1바이트인 문자만 허용해서
    //   64자 = 64바이트 < 72 로 잘림이 생기지 않게 함
    //   (?=.*[A-Za-z]) : 어딘가에 영문이 있어야 함 / (?=.*\d) : 숫자가 있어야 함
    @NotBlank(message = "비밀번호를 입력해 주세요.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
             message = "비밀번호는 영문과 숫자를 포함해 8~64자로 입력해 주세요. (공백 · 한글 불가)")
    private String password;

    @NotBlank(message = "비밀번호를 한 번 더 입력해 주세요.")
    private String passwordConfirm;       // 두 값이 같은지는 서비스 · 컨트롤러에서 비교
}
