package com.kwon.bustour.service;

import com.kwon.bustour.domain.Member;
import com.kwon.bustour.dto.SignupForm;
import com.kwon.bustour.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 회원가입
 *
 * ★ 중복 가입 막기는 두 겹 (규칙 R9)
 *   1겹 existsByPhone : 대부분은 여기서 걸림 → 친절한 오류 문장
 *   2겹 UNIQUE(phone) : 같은 번호로 "동시에" 두 번 가입하면 둘 다 1겹을 통과할 수 있다 (확인 후 행동 문제)
 *                       → DB가 두 번째 INSERT 를 거절(1062) → DataIntegrityViolationException → 같은 오류로 바꿈
 *   = 예약의 "1인 1예약"과 같은 원리: 진짜 방어선은 DB 제약
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;     // SecurityConfig 의 빈 (BCrypt)
    private final Clock clock;

    @Transactional
    public Long signUp(SignupForm form) {
        String phone = Member.normalizePhone(form.getPhone());

        // 1겹: 먼저 확인
        if (memberRepository.existsByPhone(phone)) {
            throw new DuplicatePhoneException();
        }

        // 비밀번호 암호화: "test1234!" → "{bcrypt}$2a$10$…" (같은 비밀번호라도 매번 다른 값 = salt)
        String hash = passwordEncoder.encode(form.getPassword());
        Member member = Member.signUp(form.getName().trim(), phone, hash, LocalDateTime.now(clock));

        try {
            // saveAndFlush : INSERT 를 "지금 바로" 실행 → UNIQUE 위반을 이 try 안에서 잡을 수 있음
            //   (save 만 하면 INSERT 가 커밋 직전으로 미뤄져서 메서드 밖에서 예외가 남)
            return memberRepository.saveAndFlush(member).getId();
        } catch (DataIntegrityViolationException e) {
            // 2겹: 동시에 같은 번호로 가입한 경우
            throw new DuplicatePhoneException();
        }
    }
}
