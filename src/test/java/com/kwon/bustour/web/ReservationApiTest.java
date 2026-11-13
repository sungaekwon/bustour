package com.kwon.bustour.web;

import com.kwon.bustour.domain.Member;
import com.kwon.bustour.repository.MemberRepository;
import com.kwon.bustour.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static com.kwon.bustour.support.TestDataHelper.cleanAll;
import static com.kwon.bustour.support.TestDataHelper.daysLater;
import static com.kwon.bustour.support.TestDataHelper.insertMembers;
import static com.kwon.bustour.support.TestDataHelper.insertSchedule;
import static com.kwon.bustour.support.TestDataHelper.insertTour;
import static com.kwon.bustour.support.TestDataHelper.remaining;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 6단계 테스트: 예약 API + 시큐리티 + 오류 JSON + 내 예약 화면
 *
 * ★ MockMvc = 서버(톰캣)를 띄우지 않고 "가짜 HTTP 요청"을 컨트롤러까지 보내 보는 도구
 *   시큐리티 필터(로그인 · CSRF 검사)도 같이 거친다 (.apply(springSecurity()))
 *
 * ★ 시큐리티 테스트 도구 (spring-security-test)
 *   .with(user(loginUser)) : 이 사용자로 로그인한 것처럼
 *   .with(csrf())          : 올바른 CSRF 토큰을 넣은 것처럼
 *
 * ★ 5단계 테스트(서비스)와 다른 점
 *   5단계: 서비스를 직접 불러서 "동시성 · 규칙"을 확인
 *   6단계: HTTP 로 불러서 "상태 코드 · JSON 모양 · 로그인 · CSRF"를 확인
 * =====================================================================
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservationApiTest {

    private static final long SCHEDULE_ID = 1L;

    @Autowired
    WebApplicationContext context;     // 스프링 전체(컨트롤러 · 필터) → MockMvc 를 만들 때 씀

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MemberRepository memberRepository;

    MockMvc mvc;
    LoginUser member1;
    LoginUser member2;

    @BeforeEach
    void setUp() {
        cleanAll(jdbc);
        insertTour(jdbc);
        insertMembers(jdbc, 2);
        insertSchedule(jdbc, SCHEDULE_ID, 5, daysLater(3), "OPEN");

        // 시큐리티 필터까지 포함한 MockMvc
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // 로그인 사용자 객체 (실제 로그인 때와 같은 LoginUser → 컨트롤러의 @AuthenticationPrincipal 로 들어감)
        Member m1 = memberRepository.findById(1L).orElseThrow();
        Member m2 = memberRepository.findById(2L).orElseThrow();
        member1 = new LoginUser(m1);
        member2 = new LoginUser(m2);
    }

    /** 요청 JSON 만들기 */
    private String json(long scheduleId, int headcount, String key) {
        return "{\"scheduleId\":" + scheduleId + ",\"headcount\":" + headcount + ",\"requestKey\":\"" + key + "\"}";
    }

    // =====================================================================
    // 1. 시큐리티: 로그인 · CSRF
    // =====================================================================

    @Test
    @DisplayName("로그인 안 하고 예약 → 401 + LOGIN_REQUIRED JSON (로그인 화면 HTML 이 아님)")
    void notLoggedIn_401() throws Exception {
        mvc.perform(post("/api/reservations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 1, UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("CSRF 토큰 없이 예약 → 403 + FORBIDDEN JSON, 자리 그대로")
    void noCsrf_403() throws Exception {
        mvc.perform(post("/api/reservations").with(user(member1))      // csrf() 없음
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 1, UUID.randomUUID().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertEquals(5, remaining(jdbc, SCHEDULE_ID));
    }

    // =====================================================================
    // 2. 성공 · 멱등성
    // =====================================================================

    @Test
    @DisplayName("정상 예약 → 201 RESERVED, 같은 요청 키로 다시 → 200 ALREADY_RESERVED (같은 예약 번호)")
    void reserve_201_then_200() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 2, key)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("RESERVED"))
                .andExpect(jsonPath("$.data.amount").value(20000))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 2, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ALREADY_RESERVED"));

        assertEquals(3, remaining(jdbc, SCHEDULE_ID));                 // 2명만 줄어듦 (두 번째는 처리 안 함)
        System.out.println("[첫 응답] " + first);
    }

    // =====================================================================
    // 3. 오류 JSON (ApiExceptionHandler)
    // =====================================================================

    @Test
    @DisplayName("인원 11명 → 400 INVALID_INPUT + '최대 10명' 문장 (@Valid)")
    void headcount11_400() throws Exception {
        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 11, UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(containsString("10명")));
    }

    @Test
    @DisplayName("요청 키 모양이 UUID 가 아님 → 400 INVALID_INPUT")
    void badRequestKey_400() throws Exception {
        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 1, "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("JSON 이 깨짐 → 400 INVALID_INPUT")
    void brokenJson_400() throws Exception {
        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\": 1, \"headcount\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("남은 5자리에 6명 → 409 SOLD_OUT + '5석' 안내")
    void soldOut_409() throws Exception {
        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 6, UUID.randomUUID().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.message").value(containsString("5석")));
    }

    @Test
    @DisplayName("같은 회차 두 번째 예약(다른 키) → 409 DUPLICATE_RESERVATION")
    void duplicate_409() throws Exception {
        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 1, UUID.randomUUID().toString())))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SCHEDULE_ID, 1, UUID.randomUUID().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESERVATION"));
    }

    @Test
    @DisplayName("없는 회차 → 404 SCHEDULE_NOT_FOUND")
    void noSchedule_404() throws Exception {
        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(999, 1, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }

    // =====================================================================
    // 4. 내 예약 화면 (R19 내 것만)
    // =====================================================================

    @Test
    @DisplayName("로그인 안 하고 내 예약 → 로그인 화면으로 이동")
    void myReservations_notLoggedIn_redirect() throws Exception {
        mvc.perform(get("/my/reservations"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("내 예약 화면에는 내 예약만 (남의 예약번호가 보이지 않음)")
    void myReservations_onlyMine() throws Exception {
        // 1번 회원 예약 1건, 2번 회원 예약 1건
        mvc.perform(post("/api/reservations").with(user(member1)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(SCHEDULE_ID, 1, UUID.randomUUID().toString())));
        mvc.perform(post("/api/reservations").with(user(member2)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(SCHEDULE_ID, 1, UUID.randomUUID().toString())));

        Long id1 = jdbc.queryForObject("SELECT id FROM reservation WHERE member_id = 1", Long.class);
        Long id2 = jdbc.queryForObject("SELECT id FROM reservation WHERE member_id = 2", Long.class);

        String html = mvc.perform(get("/my/reservations").with(user(member1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("예약번호 " + id1)))
                .andReturn().getResponse().getContentAsString();

        // 2번 회원의 예약번호는 1번 회원 화면에 없어야 함
        assertEquals(false, html.contains("예약번호 " + id2 + "<"), "남의 예약이 보임");
    }
}
