package com.kwon.bustour.repository;

import java.time.LocalDateTime;

/**
 * 회차의 "최신 값" 몇 개만 담는 그릇 (인터페이스 프로젝션)
 *
 * ★ 프로젝션 = 엔티티 전체가 아니라 필요한 컬럼만 받는 방법
 *   SQL 의 별칭(AS status, AS startAt …)과 getter 이름(getStatus, getStartAt …)이 같으면
 *   스프링 데이터 JPA 가 이 인터페이스의 구현 객체를 알아서 만들어 값을 넣어 준다.
 *
 * ★ 엔티티(TourSchedule)로 받지 않은 이유 = JPA 1차 캐시 함정
 *   같은 트랜잭션에서 이미 읽은 엔티티(같은 id)를 다시 조회하면, SQL 은 나가더라도
 *   Hibernate 는 "이미 가지고 있는 객체"를 돌려주고 그 값을 새 값으로 덮어쓰지 않는다.
 *   → FOR SHARE 로 최신 값을 읽어도 옛 값이 담긴 객체를 받게 됨
 *   → 엔티티가 아닌 값(프로젝션)으로 받으면 1차 캐시와 상관없이 DB가 돌려준 값 그대로
 */
public interface ScheduleLatestView {
    String getStatus();            // "OPEN" / "CLOSED"
    LocalDateTime getStartAt();
    Integer getRemaining();
}
