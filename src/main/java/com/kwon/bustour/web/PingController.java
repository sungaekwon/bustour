package com.kwon.bustour.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2단계 확인용: 앱이 떴는지 + DB에 연결되는지 + 앱과 DB의 시각이 같은지
 *
 * URL: GET http://localhost:8282/ping
 *
 * 응답 예 (성공)
 * {"success":true,"code":"OK","message":"앱과 DB가 모두 정상입니다.",
 *  "database":"bustour","mysqlVersion":"8.0.xx",
 *  "appNow":"2026-11-06 14:00:00","dbNow":"2026-11-06 14:00:00","diffMinutes":0,
 *  "dbSessionTimeZone":"SYSTEM","dbUtcOffset":"+09:00"}
 *
 * ★ diffMinutes 가 540(= 9시간)처럼 크게 나오면?
 *   DB가 UTC로 돌고 있다는 뜻. 이 상태에서 SQL에 NOW()를 쓰면 HOLD 만료 판단이 9시간 틀어진다.
 *   그래서 규칙 R22: "지금"은 항상 앱의 Clock으로 만들어 SQL에 파라미터로 넘긴다.
 *   (로컬 MySQL은 보통 0, 서버의 관리형 DB에서 다시 열어 보면 차이가 보일 수 있다)
 *
 * ※ 나중에 시큐리티를 넣으면 /ping 은 permitAll(로그인 없이 허용)에 넣어야 한다.
 */
@RestController                   // return 하는 값 = 응답 본문(JSON). (@Controller + @ResponseBody)
@RequiredArgsConstructor          // Lombok: final 필드를 받는 생성자를 자동 생성 → 스프링이 그 생성자로 주입
public class PingController {

    // JdbcTemplate: SQL을 직접 실행하는 스프링 도구 (data-jpa 스타터에 들어 있어 자동으로 만들어져 있음)
    private final JdbcTemplate jdbcTemplate;
    // TimeConfig에서 만든 Asia/Seoul 시계
    private final Clock clock;

    // 날짜를 "2026-11-06 14:00:00" 모양의 글자로 바꾸는 규칙
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/ping")          // GET /ping 요청을 이 메서드가 받음 (서블릿의 @WebServlet + doGet)
    public ResponseEntity<Map<String, Object>> ping() {
        // LinkedHashMap: 넣은 순서대로 JSON에 나오게 (HashMap은 순서가 섞임)
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            // ① 지금 연결된 스키마 이름과 MySQL 버전 → 이게 되면 DB 연결 성공
            String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
            String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);

            // ② 앱의 지금 vs DB의 지금
            //    DB 시각은 글자로 받아서(DATE_FORMAT) 드라이버의 시간대 변환이 끼어들지 않게 함
            LocalDateTime appNow = LocalDateTime.now(clock);
            String dbNowText = jdbcTemplate.queryForObject(
                    "SELECT DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s')", String.class);
            LocalDateTime dbNow = LocalDateTime.parse(dbNowText, FMT);
            long diffMinutes = Math.abs(Duration.between(appNow, dbNow).toMinutes());

            // ③ DB의 시간대 설정 (SYSTEM = DB 서버 OS의 시간대를 따름)
            String sessionTz = jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class);
            // DB의 "지금"이 UTC보다 몇 시간 빠른지 (한국 = +09:00, 서버 DB가 UTC면 +00:00)
            //
            // ※ 처음에는 @@system_time_zone(OS의 시간대 이름)을 보여 줬는데, Windows MySQL은 이 값을
            //   "대한민국 표준시"처럼 OS 한글 인코딩(CP949)으로 돌려줘서 UTF-8 JSON에서 글자가 깨졌다.
            //   → 이름 대신 숫자(시차)로 보여 주면 인코딩 문제가 없고, 비교하기도 더 쉽다.
            String dbUtcOffset = jdbcTemplate.queryForObject(
                    "SELECT TIME_FORMAT(TIMEDIFF(NOW(), UTC_TIMESTAMP()), '%H:%i')", String.class);

            body.put("success", true);
            body.put("code", "OK");
            body.put("message", diffMinutes <= 1
                    ? "앱과 DB가 모두 정상입니다."
                    : "앱과 DB의 시각이 다릅니다. SQL에서 NOW()를 쓰면 안 되는 이유입니다(R22).");
            body.put("database", database);
            body.put("mysqlVersion", version);
            // LocalDateTime을 그대로 넣지 않고 글자로 바꿔 넣음 → JSON 날짜 형식 설정을 신경 쓸 필요 없음
            body.put("appNow", appNow.format(FMT));
            body.put("dbNow", dbNowText);
            body.put("diffMinutes", diffMinutes);
            body.put("dbSessionTimeZone", sessionTz);
            // 음수(UTC보다 느림)면 이미 "-"가 붙어 옴 → 양수일 때만 "+"를 붙임
            body.put("dbUtcOffset", dbUtcOffset.startsWith("-") ? dbUtcOffset : "+" + dbUtcOffset);  // 예: +09:00
            return ResponseEntity.ok(body);                       // 200

        } catch (Exception e) {
            // 예외 메시지는 응답에 넣지 않음 (DB 주소·계정 같은 내부 정보 노출 방지)
            // 자세한 원인은 인텔리제이 콘솔(Run 창)의 빨간 로그에서 확인
            body.put("success", false);
            body.put("code", "DB_ERROR");
            body.put("message", "DB에 연결할 수 없습니다. 환경변수와 MySQL을 확인하세요.");
            return ResponseEntity.status(500).body(body);         // 500
        }
    }
}