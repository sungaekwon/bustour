package com.kwon.bustour.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 켜기 (8단계) — @Cacheable 이 동작하게 한다
 *
 * ★ 캐시 종류는 application.yml 의 spring.cache.type: caffeine
 *   Caffeine = 우리 앱 메모리 안의 캐시 (로컬 캐시). 빠르지만 앱이 2대가 되면 각자 따로 가진다.
 *   → 11단계(앱 2대)에서 Redis 캐시로 바꾼다. 서비스 코드(@Cacheable)는 그대로, 설정만 바뀐다.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
