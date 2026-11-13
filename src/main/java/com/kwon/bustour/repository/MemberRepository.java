package com.kwon.bustour.repository;

import com.kwon.bustour.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * member 조회 · 저장
 *   findByPhone   → SELECT … FROM member WHERE phone = ?   (UNIQUE 인덱스 uk_member_phone 사용)
 *   existsByPhone → SELECT 1 … WHERE phone = ? LIMIT 1    (있는지만 확인, 행 전체를 안 읽음)
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPhone(String phone);

    boolean existsByPhone(String phone);
}
