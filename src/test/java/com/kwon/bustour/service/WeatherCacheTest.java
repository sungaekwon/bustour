package com.kwon.bustour.service;

import com.kwon.bustour.dto.DayWeather;
import com.kwon.bustour.weather.ForecastItem;
import com.kwon.bustour.weather.KmaWeatherClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * =====================================================================
 * 8단계 완료 기준 "캐시 적중 시 API 호출 0" 증명
 *
 * ★ @MockitoBean : 스프링 빈 KmaWeatherClient 를 "가짜(Mockito mock)"로 바꿔 끼운다
 *   진짜 기상청을 부르지 않고, 몇 번 불렸는지 셀 수 있다 (verify(client, times(1)))
 *   when(…).thenReturn(…) : "이렇게 부르면 이 값을 돌려줘"
 *
 * ★ 테스트마다 캐시를 비운다 (CacheManager) — 앞 테스트의 캐시가 남아 있으면 결과가 섞임
 * =====================================================================
 */
@SpringBootTest
@ActiveProfiles("test")
class WeatherCacheTest {

    @MockitoBean
    KmaWeatherClient kmaClient;

    @Autowired
    WeatherService weatherService;

    @Autowired
    CacheManager cacheManager;

    private final LocalDate day = LocalDate.of(2026, 11, 12);

    @BeforeEach
    void clearCache() {
        cacheManager.getCache(WeatherService.CACHE_NAME).clear();
    }

    private List<ForecastItem> sample() {
        return List.of(
                new ForecastItem(day, LocalTime.of(6, 0), "TMN", "9.0"),
                new ForecastItem(day, LocalTime.of(12, 0), "SKY", "1"),
                new ForecastItem(day, LocalTime.of(12, 0), "PTY", "0"),
                new ForecastItem(day, LocalTime.of(12, 0), "POP", "20"),
                new ForecastItem(day, LocalTime.of(15, 0), "TMX", "18.0"));
    }

    @Test
    @DisplayName("① ★ 두 번째 호출부터는 캐시 → 기상청 API 호출은 1번뿐")
    void secondCall_hitsCache() {
        when(kmaClient.fetch(anyInt(), anyInt(), any())).thenReturn(sample());

        Map<LocalDate, DayWeather> first = weatherService.getBusanForecast();
        for (int i = 0; i < 10; i++) {
            weatherService.getBusanForecast();                // 메인 화면을 10번 더 연 것
        }

        verify(kmaClient, times(1)).fetch(anyInt(), anyInt(), any());   // ★ 11번 불렀지만 API 는 1번
        assertEquals("맑음 9~18° 강수20%", first.get(day).getSummary());
    }

    @Test
    @DisplayName("② 기상청 오류 → 빈 결과, 캐시에 넣지 않음 → 다음 요청에서 다시 시도")
    void failure_notCached() {
        when(kmaClient.fetch(anyInt(), anyInt(), any()))
                .thenThrow(new IllegalStateException("기상청 오류 03 NO_DATA"))   // 첫 번째: 실패
                .thenReturn(sample());                                        // 두 번째: 성공

        assertTrue(weatherService.getBusanForecast().isEmpty());              // 실패해도 예외가 아니라 빈 결과
        assertEquals(1, weatherService.getBusanForecast().size());            // 캐시에 실패가 안 남아서 다시 호출

        verify(kmaClient, times(2)).fetch(anyInt(), anyInt(), any());
        weatherService.getBusanForecast();                                    // 이제는 캐시
        verify(kmaClient, times(2)).fetch(anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("③ 캐시를 비우면 다시 기상청을 부른다 (1시간 만료 뒤와 같은 상황)")
    void evicted_callsAgain() {
        when(kmaClient.fetch(anyInt(), anyInt(), any())).thenReturn(sample());
        weatherService.getBusanForecast();
        cacheManager.getCache(WeatherService.CACHE_NAME).clear();             // expireAfterWrite=1h 가 지난 것처럼
        weatherService.getBusanForecast();

        verify(kmaClient, times(2)).fetch(anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("④ 화면을 한 번도 안 열면 기상청을 부르지 않는다 (앱 시작만으로는 호출 0)")
    void noCall_untilUsed() {
        verify(kmaClient, never()).fetch(anyInt(), anyInt(), any());
    }
}
