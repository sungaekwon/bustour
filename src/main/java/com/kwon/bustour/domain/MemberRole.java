package com.kwon.bustour.domain;

/**
 * 회원 역할 (member.role 컬럼)
 *   USER  : 일반 회원
 *   ADMIN : 관리자 (/admin/** 접근)
 *
 * ★ 스프링 시큐리티는 역할 이름 앞에 "ROLE_" 을 붙여서 비교한다.
 *   DB에는 'USER' → 로그인할 때 권한 "ROLE_USER" 로 만들어 줌 (security/LoginUser)
 *   설정에서는 hasRole("ADMIN") 처럼 ROLE_ 없이 씀 (시큐리티가 알아서 붙임)
 */
public enum MemberRole {
    USER,
    ADMIN
}
