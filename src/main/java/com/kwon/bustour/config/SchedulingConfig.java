package com.kwon.bustour.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 켜기 (7단계: HOLD 만료) — @Scheduled 메서드가 정해진 간격으로 실행되게 한다
 *
 * ★ BustourApplication 에 바로 붙이지 않고 따로 둔 이유
 *   JUnit 에서는 스케줄러가 제멋대로 돌면 테스트 결과가 흔들린다 (테스트가 만든 PENDING 을 몰래 만료시킴).
 *   bustour.scheduler.enabled=false 면 이 설정 클래스가 통째로 빠져서 @Scheduled 가 동작하지 않는다.
 *   → 테스트는 HoldExpiryScheduler.expireDueHolds(now) 를 직접 불러서 "원하는 시각"으로 확인한다.
 *
 *   matchIfMissing = true : 설정이 없으면 켠다 (앱을 그냥 실행하면 스케줄러가 돈다)
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bustour.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
