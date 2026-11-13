package com.kwon.bustour.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 규칙 R22: "시각의 기준은 하나" — 앱의 모든 '지금'은 이 Clock에서 만든다.
 *
 * ★ 왜 LocalDateTime.now() 를 바로 쓰지 않나?
 *   1. 서버(리눅스, 도커)는 기본 시간대가 UTC인 경우가 많다.
 *      LocalDateTime.now() 는 "서버 시간대"의 지금 → 한국 시각보다 9시간 늦게 나올 수 있다.
 *      Clock에 Asia/Seoul을 박아 두면 서버가 어디든 한국 시각.
 *   2. 테스트에서 시각을 바꿔 끼울 수 있다.
 *      예) "HOLD 10분이 지났을 때"를 테스트하려고 진짜 10분을 기다릴 필요 없이
 *          Clock.fixed(...)로 '11분 뒤'를 만들어 넣으면 된다.
 *
 * ★ 왜 SQL 안에서 NOW() 를 쓰지 않나?
 *   NOW()는 "DB 서버 시간대"의 지금이다. 관리형 DB(RDS, Lightsail DB)는 기본이 UTC인 경우가 많다.
 *   앱(한국 시각)과 DB(UTC)가 섞이면 expires_at 비교가 9시간 어긋난다.
 *   → /ping 화면에서 appNow와 dbNow를 나란히 보여 주니 직접 확인해 보자.
 *
 * 사용법 (서비스에서):
 *   private final Clock clock;                       // 생성자 주입
 *   LocalDateTime now = LocalDateTime.now(clock);    // ★ 괄호 안에 clock
 */
@Configuration       // 설정 클래스: 안의 @Bean 메서드가 돌려주는 객체를 스프링이 보관(빈 등록)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
