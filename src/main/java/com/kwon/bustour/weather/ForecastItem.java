package com.kwon.bustour.weather;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 기상청 단기예보 한 줄 (8단계) — "어느 날 몇 시의 무엇(category)이 얼마(value)"
 *
 *   예) 2026-11-12 15:00 TMP 18   → 11월 12일 15시 기온 18℃
 *
 * ★ 자주 쓰는 category (기상청 코드)
 *   TMP 1시간 기온(℃) · TMN 하루 최저(℃) · TMX 하루 최고(℃) · POP 강수확률(%)
 *   SKY 하늘상태 1 맑음 / 3 구름많음 / 4 흐림
 *   PTY 강수형태 0 없음 / 1 비 / 2 비·눈 / 3 눈 / 4 소나기
 */
public record ForecastItem(LocalDate date, LocalTime time, String category, String value) {
}
