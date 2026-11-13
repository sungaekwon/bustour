package com.kwon.bustour.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 기상청 클라이언트의 계산 부분 단위 테스트 (8단계) — 실제 API 를 부르지 않음
 *   latestBase : 지금 시각 → 쓸 수 있는 가장 최근 발표 시각
 *   parse      : 응답 글자 → 예보 목록 (오류 응답 처리 포함)
 */
class KmaWeatherClientTest {

    @Test
    @DisplayName("발표 시각 고르기: 발표 15분 뒤부터 사용, 새벽 2시 15분 전이면 어제 23시")
    void latestBase() {
        assertEquals(LocalDateTime.of(2026, 11, 10, 14, 0),
                KmaWeatherClient.latestBase(LocalDateTime.of(2026, 11, 10, 14, 20)));
        assertEquals(LocalDateTime.of(2026, 11, 10, 11, 0),
                KmaWeatherClient.latestBase(LocalDateTime.of(2026, 11, 10, 14, 10)));   // 14시 발표분은 아직
        assertEquals(LocalDateTime.of(2026, 11, 9, 23, 0),
                KmaWeatherClient.latestBase(LocalDateTime.of(2026, 11, 10, 2, 5)));
    }

    @Test
    @DisplayName("정상 JSON → 예보 목록")
    void parseJson() {
        String json = """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
                 "body":{"dataType":"JSON","items":{"item":[
                   {"baseDate":"20261110","baseTime":"1400","category":"TMP",
                    "fcstDate":"20261110","fcstTime":"1500","fcstValue":"18","nx":98,"ny":76}
                 ]},"pageNo":1,"numOfRows":1500,"totalCount":1}}}
                """;
        List<ForecastItem> items = KmaWeatherClient.parse(json);

        assertEquals(1, items.size());
        assertEquals(new ForecastItem(LocalDate.of(2026, 11, 10), LocalTime.of(15, 0), "TMP", "18"), items.get(0));
    }

    @Test
    @DisplayName("키 오류(XML) · 결과 코드 오류 → 예외 (WeatherService 가 잡아서 빈 결과로)")
    void parseErrors() {
        assertThrows(IllegalStateException.class, () -> KmaWeatherClient.parse(
                "<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR"
                        + "</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>"));
        assertThrows(IllegalStateException.class, () -> KmaWeatherClient.parse(
                "{\"response\":{\"header\":{\"resultCode\":\"03\",\"resultMsg\":\"NO_DATA\"}}}"));
    }
}
