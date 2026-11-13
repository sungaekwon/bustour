package com.kwon.bustour.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * member 테이블 (회원)
 *
 * ★ 비밀번호는 절대 그대로 저장하지 않는다 → passwordHash 에 BCrypt 결과만 (규칙 R20)
 * ★ 휴대폰 번호는 숫자만 저장 (01012345678) → normalizePhone() 으로 정리한 뒤 저장 · 조회
 *   "010-1234-5678" 과 "01012345678" 이 섞이면 UNIQUE(phone) 이 다른 값으로 봐서 중복 가입이 된다.
 * ★ created_at 은 DB의 DEFAULT 가 아니라 앱의 Clock 으로 넣는다 (규칙 R22)
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)     // JPA용 빈 생성자 (밖에서는 못 씀)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String phone;                 // 숫자만, UNIQUE (로그인 아이디)

    private String passwordHash;          // → password_hash  ({bcrypt}$2a$10$… 68자)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private MemberRole role;

    private LocalDateTime createdAt;      // → created_at

    /**
     * 회원가입용 생성 메서드 (정적 팩토리)
     * ★ new Member() + setter 대신 이 메서드로만 만든다
     *   → "가입할 때 꼭 필요한 값"을 빠뜨릴 수 없고, 역할은 항상 USER 로 시작
     *
     * @param passwordHash 이미 암호화된 비밀번호 (서비스에서 PasswordEncoder 로 만든 값)
     * @param now          Clock 으로 만든 지금 (가입 시각)
     */
    public static Member signUp(String name, String phone, String passwordHash, LocalDateTime now) {
        Member m = new Member();
        m.name = name;
        m.phone = normalizePhone(phone);
        m.passwordHash = passwordHash;
        m.role = MemberRole.USER;
        m.createdAt = now;
        return m;
    }

    /**
     * 휴대폰 번호 정리: 숫자가 아닌 글자(하이픈, 공백 …)를 모두 지움
     *   "010-1234-5678" → "01012345678",  " 010 1234 5678 " → "01012345678"
     * 가입할 때도, 로그인할 때도 같은 방법으로 정리해야 같은 회원으로 찾을 수 있다.
     */
    public static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("[^0-9]", "");    // 정규식 [^0-9] = 숫자가 아닌 글자 하나
    }
}
