package com.kwon.bustour.security;

import com.kwon.bustour.domain.Member;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * 로그인한 사람 정보 (스프링 시큐리티가 세션에 보관하는 객체)
 *
 * ★ UserDetails = 시큐리티가 "로그인한 사용자"로 다루는 약속(인터페이스)
 *   getUsername() : 아이디 (= 휴대폰 번호)
 *   getPassword() : 암호화된 비밀번호 (로그인할 때 입력값과 비교)
 *   getAuthorities() : 권한 목록 (ROLE_USER / ROLE_ADMIN)
 *
 * ★ 엔티티(Member)를 그대로 세션에 넣지 않는 이유
 *   1. 세션은 요청이 끝나도 남아 있는데, 엔티티는 트랜잭션 밖에서 다루면 지연 로딩 오류 등이 생길 수 있다.
 *   2. 12단계에서 세션을 Redis 에 저장하면 객체를 직렬화(바이트로 변환)해야 한다
 *      → 필요한 값만 가진 작고 단순한 객체가 안전 (UserDetails 는 Serializable 을 이미 상속)
 *
 * ★ 컨트롤러에서 꺼내 쓰기: 매개변수에 @AuthenticationPrincipal LoginUser loginUser
 *   → 로그인 안 했으면 null
 */
@Getter
public class LoginUser implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;  // 직렬화 버전 (클래스가 바뀌면 올림)

    private final Long memberId;      // 예약할 때 member_id 로 씀 (규칙 R19: 내 예약만)
    private final String name;        // 헤더에 "○○○님"
    private final String phone;
    private final String passwordHash;
    private final String role;        // "USER" / "ADMIN"

    public LoginUser(Member m) {
        this.memberId = m.getId();
        this.name = m.getName();
        this.phone = m.getPhone();
        this.passwordHash = m.getPasswordHash();
        this.role = m.getRole().name();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 시큐리티 규칙: 역할은 "ROLE_" + 이름
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return phone;
    }

    // 계정 만료 · 잠김 · 비밀번호 만료 · 사용 가능 여부: 이 프로젝트에서는 쓰지 않음 → 항상 true
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
