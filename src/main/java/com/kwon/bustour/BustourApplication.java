package com.kwon.bustour;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 앱 시작점. 이 main()을 실행하면 내장 톰캣이 8282에서 뜬다.
 *
 * ★ @SpringBootApplication 이 하는 일 (3개를 합친 것)
 *   1. @Configuration           : 이 클래스도 설정 클래스
 *   2. @EnableAutoConfiguration : build.gradle에 넣은 라이브러리를 보고 자동 설정
 *                                 (MySQL 드라이버 + data-jpa → DataSource, HikariCP, JPA를 알아서 만듦)
 *   3. @ComponentScan           : 이 클래스가 있는 패키지(com.kwon.bustour)와 "그 아래"를 뒤져서
 *                                 @Controller, @Service 등을 찾아 빈으로 등록
 *
 *   → 그래서 컨트롤러는 반드시 com.kwon.bustour 아래 패키지에 있어야 한다.
 *     (com.kwon.other 에 두면 못 찾아서 404)
 *
 * ※ HOLD 만료 스케줄러(7단계)의 @EnableScheduling 은 config/SchedulingConfig 에 따로 두었다
 *   (JUnit 에서 끌 수 있게)
 */
@SpringBootApplication
public class BustourApplication {

    public static void main(String[] args) {
        SpringApplication.run(BustourApplication.class, args);
    }
}
