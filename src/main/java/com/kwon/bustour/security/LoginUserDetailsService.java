package com.kwon.bustour.security;

import com.kwon.bustour.domain.Member;
import com.kwon.bustour.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인할 때 시큐리티가 부르는 "회원 찾기"
 *
 * ★ 로그인 흐름 (POST /login 은 컨트롤러가 아니라 시큐리티 필터가 처리)
 *   ① 폼에서 phone, password 를 받음
 *   ② loadUserByUsername(phone) 호출 → 여기서 DB 조회 → LoginUser 돌려줌
 *   ③ 시큐리티가 PasswordEncoder 로 "입력한 비밀번호" vs "LoginUser.getPassword()(해시)" 비교
 *   ④ 맞으면: 세션 ID를 새로 바꾸고(세션 고정 공격 방지) 세션에 LoginUser 저장 → / 로 이동
 *      틀리면: /login?error 로 이동
 *
 * ★ 비밀번호 비교는 여기서 하지 않는다 (③ 시큐리티가 함)
 * ★ "아이디 없음"과 "비밀번호 틀림"을 화면에서 구분하지 않는다
 *   → 구분하면 "이 번호는 가입되어 있다"는 정보가 새어 나감 (계정 존재 여부 노출)
 */
@Service
@RequiredArgsConstructor
public class LoginUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 로그인 화면에 하이픈을 넣어 입력해도 찾을 수 있게, 가입할 때와 같은 방법으로 정리
        String phone = Member.normalizePhone(username);
        Member member = memberRepository.findByPhone(phone)
                .orElseThrow(() -> new UsernameNotFoundException("회원 없음: " + phone));  // 로그에만
        return new LoginUser(member);
    }
}
