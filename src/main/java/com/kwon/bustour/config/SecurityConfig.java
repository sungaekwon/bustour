package com.kwon.bustour.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 스프링 시큐리티 설정 (Spring Security 7 / 람다 방식)
 *
 * ★ 시큐리티는 컨트롤러보다 "앞"에 있는 필터 묶음(필터 체인)이다.
 *   요청 → [CSRF 검사] → [세션에서 로그인 정보 꺼내기] → [주소별 권한 검사] → … → 컨트롤러
 *   서블릿 프로젝트에서 직접 만든 LoginCheckFilter, CsrfFilter 를 시큐리티가 대신 해 준다.
 *
 * ★ 이 파일에서 정하는 것
 *   1. 주소별로 누가 볼 수 있나 (permitAll / authenticated / hasRole)
 *   2. 로그인 화면 · 처리 주소 · 성공/실패 후 이동
 *   3. 로그아웃
 *   4. /api/** 는 로그인 안 했을 때 로그인 화면(HTML) 대신 401 JSON
 *   5. 비밀번호 암호화 방식
 *
 * ※ 옛 블로그의 WebSecurityConfigurerAdapter(상속 방식)는 없어졌다 → @Bean SecurityFilterChain 으로 만든다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // ---------- 1. 주소별 권한 (위에서부터 순서대로 검사, 먼저 맞는 규칙이 적용) ----------
            .authorizeHttpRequests(auth -> auth
                // 로그인 없이 볼 수 있는 곳
                .requestMatchers("/", "/tours/**", "/ping",
                                 "/login", "/signup",
                                 "/css/**", "/js/**", "/img/**").permitAll()
                // /error : 오류 화면(404 등). 막으면 "없는 주소"가 404 대신 로그인 화면으로 가 버린다
                .requestMatchers("/error").permitAll()
                // [추가]2026.11.22권성애 Actuator 수치 — 관리 포트(9282)에도 시큐리티가 걸려 로그인 화면으로 가는 것 방지
                //   8282 에는 Actuator 가 없어(404) 밖으로 새는 것 없음
                .requestMatchers("/actuator/health", "/actuator/metrics/**").permitAll()
                // 관리자만
                .requestMatchers("/admin/**").hasRole("ADMIN")     // = 권한 "ROLE_ADMIN"
                // 나머지 전부: 로그인 필요 (예: /my/reservations, 6단계의 /api/reservations)

                .anyRequest().authenticated()
            )

            // ---------- 2. 폼 로그인 ----------
            .formLogin(form -> form
                .loginPage("/login")                 // 로그인 화면 = AuthController 의 GET /login
                .loginProcessingUrl("/login")        // POST /login 은 시큐리티 필터가 처리 (컨트롤러 불필요)
                .usernameParameter("phone")          // 아이디 입력칸 name (기본값 username → phone 으로)
                .passwordParameter("password")
                // 로그인 성공: 로그인이 필요한 주소에서 끌려왔으면 그 주소로, 아니면 / 로
                // (두 번째 false = "항상 / 로" 가 아니라는 뜻)
                .defaultSuccessUrl("/", false)
                .failureUrl("/login?error")          // 실패: 화면에 "번호 또는 비밀번호가 맞지 않습니다"
                .permitAll()
            )

            // ---------- 3. 로그아웃 ----------
            // POST /logout (CSRF 토큰 필요) → 세션 무효화 + 로그인 정보 삭제 → /?logout
            // GET 으로는 안 됨: 남의 사이트가 <img src="/logout"> 로 몰래 로그아웃시키는 것을 막기 위해
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/?logout")
            )

            // ---------- 4. 로그인 안 했을 때 ----------
            // 화면 주소: 로그인 화면으로 이동 (기본 동작)
            // /api/** : fetch 로 부르는 곳이라 HTML 로그인 화면을 받으면 자바스크립트가 처리할 수 없음 → 401 JSON
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    (req, resp, e) -> {
                        resp.setStatus(401);
                        resp.setContentType("application/json;charset=UTF-8");
                        resp.getWriter().write(
                            "{\"success\":false,\"code\":\"LOGIN_REQUIRED\",\"message\":\"로그인이 필요합니다.\"}");
                    },
                    // 이 규칙을 적용할 요청: 주소가 /api/ 로 시작
                    req -> req.getRequestURI().startsWith(req.getContextPath() + "/api/")
                )
                // ---------- 5. 거절(403) 일 때 (6단계) ----------
                // 로그인은 했지만 막힌 경우. /api/** 에서는 대부분 "CSRF 토큰이 없거나 틀림"
                //   (예: 다른 탭에서 로그아웃 → 다시 로그인해서 세션 · 토큰이 바뀜, 화면은 옛 토큰을 들고 있음)
                // 기본 동작은 빈 403 이라 자바스크립트가 이유를 모름 → /api/** 만 JSON 으로
                //
                // ★ defaultAccessDeniedHandlerFor(…) 가 아니라 accessDeniedHandler(…) 로 "하나"를 지정한 이유
                //   CSRF 필터는 여기서 직접 지정한 핸들러 하나만 가져다 쓴다.
                //   주소별로 나누는 defaultAccessDeniedHandlerFor 는 CSRF 실패에 적용되지 않을 수 있어서,
                //   핸들러 안에서 주소를 보고 직접 나눈다.
                .accessDeniedHandler((req, resp, e) -> {
                    if (req.getRequestURI().startsWith(req.getContextPath() + "/api/")) {
                        resp.setStatus(403);
                        resp.setContentType("application/json;charset=UTF-8");
                        resp.getWriter().write(
                            "{\"success\":false,\"code\":\"FORBIDDEN\",\"message\":\"요청이 거절되었습니다. 화면을 새로고침한 뒤 다시 시도해 주세요.\"}");
                    } else {
                        resp.sendError(403);        // 화면 주소: 기본 동작 (오류 화면)
                    }
                })
            );

        // ★ CSRF 는 따로 쓰지 않아도 "켜져" 있다 (기본값)
        //   - 타임리프 th:action 폼: 숨은 _csrf 값이 자동으로 들어감
        //   - fetch: base.html 의 <meta name="_csrf"> 값을 헤더로 보내야 함 (6단계)
        // ★ 세션 고정 공격 방지도 기본값: 로그인 성공 시 세션 ID 를 새로 바꿈

        return http.build();
    }

    /**
     * 비밀번호 암호화
     * createDelegatingPasswordEncoder() : 저장 값 앞의 {bcrypt} 표시를 보고 방식을 고르는 인코더
     *   encode("test1234!") → "{bcrypt}$2a$10$…"  (샘플 데이터와 같은 모양)
     *   나중에 더 강한 방식으로 바꿔도 예전 {bcrypt} 회원은 그대로 로그인 가능
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
