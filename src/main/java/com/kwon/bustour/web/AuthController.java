package com.kwon.bustour.web;

import com.kwon.bustour.dto.SignupForm;
import com.kwon.bustour.security.LoginUser;
import com.kwon.bustour.service.DuplicatePhoneException;
import com.kwon.bustour.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 로그인 화면 · 회원가입
 *   GET  /login  → 로그인 화면 (POST /login 은 시큐리티 필터가 처리하므로 여기 없음)
 *   GET  /signup → 회원가입 화면
 *   POST /signup → 가입 처리 → 성공하면 /login?signup
 *
 * ★ @AuthenticationPrincipal LoginUser loginUser
 *   시큐리티가 세션에서 로그인 정보를 꺼내 넣어 줌. 로그인 안 했으면 null.
 *   (서블릿의 (LoginUser) req.getSession().getAttribute("loginUser") 와 같은 역할)
 */
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    @GetMapping("/login")
    public String loginForm(@AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser != null) {
            return "redirect:/";              // 이미 로그인했으면 메인으로
        }
        return "member/login";
    }

    @GetMapping("/signup")
    public String signupForm(@AuthenticationPrincipal LoginUser loginUser, Model model) {
        if (loginUser != null) {
            return "redirect:/";
        }
        // th:object="${form}" 이 쓸 빈 폼 객체 (없으면 화면에서 오류)
        model.addAttribute("form", new SignupForm());
        return "member/signup";
    }

    /**
     * @Valid                : SignupForm 의 @NotBlank, @Pattern … 조건 검사
     * @ModelAttribute("form") : 입력값을 SignupForm 에 담고, 화면에 다시 보여 줄 때도 "form" 이름으로
     * BindingResult         : 검사 결과 (★ 반드시 @Valid 객체 "바로 뒤"에 와야 함. 아니면 400 오류)
     *
     * 오류가 있으면 같은 화면을 다시 보여 줌 → 입력했던 값은 그대로, 칸 아래에 오류 문장
     */
    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("form") SignupForm form,
                         BindingResult bindingResult) {

        // 비밀번호 확인: 두 칸이 같은지 (어노테이션 하나로는 두 필드를 비교하기 어려워서 직접)
        if (form.getPassword() != null && !form.getPassword().equals(form.getPasswordConfirm())) {
            // rejectValue(필드, 오류코드, 기본 문장) → 화면의 th:errors="*{passwordConfirm}" 에 나옴
            bindingResult.rejectValue("passwordConfirm", "mismatch", "비밀번호가 서로 다릅니다.");
        }
        if (bindingResult.hasErrors()) {
            return "member/signup";
        }

        try {
            memberService.signUp(form);
        } catch (DuplicatePhoneException e) {
            bindingResult.rejectValue("phone", "duplicate", e.getMessage());
            return "member/signup";
        }

        // ★ PRG 패턴 (Post → Redirect → Get)
        //   POST 결과 화면을 바로 보여 주면 새로고침할 때 POST 가 다시 전송된다(중복 가입 시도)
        //   → 리다이렉트로 GET 화면으로 보내면 새로고침해도 GET 만 다시 감
        return "redirect:/login?signup";
    }
}
