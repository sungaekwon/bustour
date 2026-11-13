package com.kwon.bustour.service;

import com.kwon.bustour.dto.DayWeather;
import com.kwon.bustour.weather.ForecastItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 날씨 요약 계산 단위 테스트 (8단계) — 스프링 · DB 없이 바로 실행 (빠름)
 *   WeatherService.summarize 가 static 이라 객체 없이 부를 수 있다 (같은 패키지라 package-private 접근 가능)
 */
class WeatherSummaryTest {

    private final LocalDate d = LocalDate.of(2026, 11, 12);

    private ForecastItem at(int hour, String category, String value) {
        return new ForecastItem(d, LocalTime.of(hour, 0), category, value);
    }

    @Test
    @DisplayName("TMN · TMX 가 있으면 그 값, 하늘은 12시 상태")
    void minMaxAndSky() {
        Map<LocalDate, DayWeather> m = WeatherService.summarize(List.of(
                at(6, "TMN", "8.6"), at(15, "TMX", "17.5"),
                at(9, "SKY", "4"), at(12, "SKY", "3"), at(12, "POP", "30"), at(18, "POP", "60")));

        DayWeather w = m.get(d);
        assertEquals("구름많음", w.getSky());                 // 12시에 가장 가까운 SKY
        assertEquals(9, w.getMinTemp());                      // 8.6 → 반올림 9
        assertEquals(18, w.getMaxTemp());                     // 17.5 → 18
        assertEquals(60, w.getPop());                         // 그날 가장 높은 강수확률
    }

    @Test
    @DisplayName("낮에 비가 있으면 하늘 대신 '비', TMN · TMX 가 없으면 시간별 기온으로")
    void rainAndTmpFallback() {
        DayWeather w = WeatherService.summarize(List.of(
                at(12, "SKY", "1"), at(15, "PTY", "1"),
                at(13, "TMP", "14"), at(16, "TMP", "16"), at(20, "TMP", "12"))).get(d);

        assertEquals("비", w.getSky());
        assertEquals(12, w.getMinTemp());
        assertEquals(16, w.getMaxTemp());
        assertEquals("비 12~16° 강수0%", w.getSummary());
    }
}
