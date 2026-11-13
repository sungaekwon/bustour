package com.kwon.bustour.service;

import com.kwon.bustour.dto.DayWeather;
import com.kwon.bustour.weather.ForecastItem;
import com.kwon.bustour.weather.KmaWeatherClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * =====================================================================
 * 부산 날씨 (8단계) — 기상청 단기예보를 "날짜별 한 줄 요약"으로 만들고 캐시한다
 *
 * ★ @Cacheable("busanWeather") : 이 메서드의 결과를 캐시에 보관
 *   첫 호출 → 메서드 실행(기상청 API 호출) → 결과를 캐시에 저장
 *   다음 호출 → 메서드를 실행하지 않고 캐시의 결과를 바로 돌려줌 = API 호출 0번 (8단계 완료 기준)
 *   캐시 종류 · 유지 시간은 application.yml 의 spring.cache (Caffeine, 1시간 뒤 버림)
 *
 * ★ 왜 캐시가 필요한가
 *   메인 화면은 가장 많이 열리는 화면이다. 열 때마다 기상청을 부르면
 *   ① 화면이 느려지고(외부 호출 수백 ms) ② 공공 API 하루 호출 한도(개발 계정 10,000번)를 금방 쓰고
 *   ③ 기상청이 느리거나 죽으면 우리 메인 화면도 같이 느려진다.
 *   예보는 3시간마다 바뀌므로 1시간 동안 같은 값을 써도 문제없다.
 *
 * ★ unless = "#result.isEmpty()" : 결과가 비었으면(키 없음 · 기상청 오류) 캐시에 넣지 않는다
 *   → 오류 결과가 1시간 동안 고정되지 않고, 다음 요청에서 다시 시도
 *
 * ★ 실패해도 메인 화면은 떠야 한다 → 예외를 잡고 빈 Map (날씨 칸만 빔)
 *
 * ★ (A안) 7일: 오늘 ~ 3, 4일 뒤는 단기예보, 그 뒤는 중기예보로 채운다 (기상청 호출 = 단기 1 + 중기 2, 캐시 1시간)
 *
 * ★ 캐시 주의 (자기 호출 함정): @Cacheable 도 @Transactional 처럼 프록시로 동작한다.
 *   같은 클래스 안에서 this.getBusanForecast() 로 부르면 캐시가 무시된다 → 컨트롤러(다른 빈)에서 부른다.
 * =====================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    public static final String CACHE_NAME = "busanWeather";

    private final KmaWeatherClient kmaClient;
    private final Clock clock;

    /** 기상청 격자 좌표 (부산 기본값). @Value 는 생성자가 아니라 필드에 → final 이 아님 */
    @Value("${bustour.weather.nx:98}")
    private int nx;
    @Value("${bustour.weather.ny:76}")
    private int ny;
    /** (A안) 중기예보 지역 코드: 육상(하늘 · 강수) 부산 · 울산 · 경남 / 기온 부산 */
    @Value("${bustour.weather.mid-land-reg-id:11H20000}")
    private String midLandRegId;
    @Value("${bustour.weather.mid-ta-reg-id:11H20201}")
    private String midTaRegId;

    /** 화면에 보여 줄 날 수 (오늘 포함) */
    private static final int DAYS = 7;

    /**
     * 날짜 → 그날 날씨 요약 (오늘부터 예보가 있는 날까지, 보통 3~4일)
     */
    @Cacheable(cacheNames = CACHE_NAME, unless = "#result.isEmpty()")
    public Map<LocalDate, DayWeather> getBusanForecast() {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<LocalDate, DayWeather> all = new TreeMap<>();

        // ① 단기예보: 오늘 ~ 3, 4일 뒤 (시간별이라 정확)
        try {
            all.putAll(summarize(kmaClient.fetch(nx, ny, now)));
        } catch (RuntimeException e) {
            log.warn("[기상청] 단기예보를 불러오지 못함: {}", e.getMessage());
        }
        // ② (A안) 중기예보: 단기예보에 없는 날만 채움 (4일 뒤 ~). 실패해도 단기예보는 그대로 보여 줌
        try {
            addMid(all, KmaWeatherClient.latestMidBase(now));
        } catch (RuntimeException e) {
            log.warn("[기상청] 중기예보를 불러오지 못함: {}", e.getMessage());
        }

        // 앞에서부터 7일만 (TreeMap = 날짜 순)
        Map<LocalDate, DayWeather> week = new TreeMap<>();
        all.entrySet().stream().limit(DAYS).forEach(e -> week.put(e.getKey(), e.getValue()));
        return week;
    }

    /**
     * (A안) 중기예보로 빈 날 채우기
     *   wf{n}Am / wf{n}Pm : n일 뒤 오전 / 오후 하늘 ("구름많고 비")   rnSt{n}Am / Pm : 강수확률
     *   taMin{n} / taMax{n} : 최저 / 최고 기온     (n = 발표일 기준 며칠 뒤, 보통 4 ~ 10)
     */
    private void addMid(Map<LocalDate, DayWeather> all, LocalDateTime base) {
        Map<String, Object> land = kmaClient.fetchMid("getMidLandFcst", midLandRegId, base);
        if (land == null || land.isEmpty()) {
            return;
        }
        Map<String, Object> ta = kmaClient.fetchMid("getMidTa", midTaRegId, base);
        for (int n = 3; n <= 7; n++) {
            LocalDate date = base.toLocalDate().plusDays(n);
            if (all.containsKey(date)) {
                continue;                                      // 단기예보가 있으면 그쪽이 더 정확
            }
            String am = text(land.get("wf" + n + "Am"));
            String pm = text(land.get("wf" + n + "Pm"));
            if (am == null && pm == null) {
                continue;                                      // 그 날짜 값이 없음
            }
            // 오전 · 오후 중 비 · 눈이 있는 쪽을 대표로 (우산 필요 여부가 중요), 없으면 오후
            String sky = (am != null && (am.contains("비") || am.contains("눈"))) ? am : (pm != null ? pm : am);
            int pop = Math.max(num(land.get("rnSt" + n + "Am")), num(land.get("rnSt" + n + "Pm")));
            Integer min = ta == null ? null : numOrNull(ta.get("taMin" + n));
            Integer max = ta == null ? null : numOrNull(ta.get("taMax" + n));
            all.put(date, new DayWeather(date, sky, min, max, pop, true));
        }
    }

    private static String text(Object v) {
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v);
    }

    private static int num(Object v) {
        Integer n = numOrNull(v);
        return n == null ? 0 : n;
    }

    private static Integer numOrNull(Object v) {
        if (v == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 예보 목록 → 날짜별 요약 (static: 스프링 없이 단위 테스트할 수 있게)
     *   TreeMap : 날짜 순서로 정렬된 Map
     */
    static Map<LocalDate, DayWeather> summarize(List<ForecastItem> items) {
        Map<LocalDate, List<ForecastItem>> byDate = items.stream()
                .collect(Collectors.groupingBy(ForecastItem::date, TreeMap::new, Collectors.toList()));

        Map<LocalDate, DayWeather> result = new TreeMap<>();
        byDate.forEach((date, list) -> result.put(date, summarizeDay(date, list)));
        return result;
    }

    private static DayWeather summarizeDay(LocalDate date, List<ForecastItem> list) {
        // 최저 · 최고: TMN · TMX 가 있으면 그 값, 없으면(오늘 오후 등) 시간별 기온 TMP 의 최소 · 최대
        Integer min = number(list, "TMN").map(d -> (int) Math.round(d))
                .orElseGet(() -> values(list, "TMP").stream().min(Double::compare).map(d -> (int) Math.round(d)).orElse(null));
        Integer max = number(list, "TMX").map(d -> (int) Math.round(d))
                .orElseGet(() -> values(list, "TMP").stream().max(Double::compare).map(d -> (int) Math.round(d)).orElse(null));
        int pop = values(list, "POP").stream().mapToInt(Double::intValue).max().orElse(0);

        // 하늘: 낮(06~21시)에 비 · 눈이 있으면 그것, 없으면 12시(없으면 가장 이른 시각)의 하늘상태
        String sky = list.stream()
                .filter(i -> i.category().equals("PTY") && !i.value().equals("0"))
                .filter(i -> !i.time().isBefore(LocalTime.of(6, 0)) && !i.time().isAfter(LocalTime.of(21, 0)))
                .findFirst()
                .map(i -> ptyText(i.value()))
                .orElseGet(() -> list.stream()
                        .filter(i -> i.category().equals("SKY"))
                        .min((a, b) -> Integer.compare(distanceToNoon(a.time()), distanceToNoon(b.time())))
                        .map(i -> skyText(i.value()))
                        .orElse("-"));

        return new DayWeather(date, sky, min, max, pop);
    }

    private static int distanceToNoon(LocalTime t) {
        return Math.abs(t.getHour() - 12);
    }

    private static Optional<Double> number(List<ForecastItem> list, String category) {
        return values(list, category).stream().findFirst();
    }

    /** 숫자로 읽히는 값만 (예보 값에 "강수없음" 같은 글자가 섞이는 항목도 있어서) */
    private static List<Double> values(List<ForecastItem> list, String category) {
        return list.stream()
                .filter(i -> i.category().equals(category))
                .map(ForecastItem::value)
                .filter(v -> v.matches("-?\\d+(\\.\\d+)?"))
                .map(Double::parseDouble)
                .toList();
    }

    private static String skyText(String code) {
        return switch (code) {
            case "1" -> "맑음";
            case "3" -> "구름많음";
            case "4" -> "흐림";
            default -> "-";
        };
    }

    private static String ptyText(String code) {
        return switch (code) {
            case "1" -> "비";
            case "2" -> "비/눈";
            case "3" -> "눈";
            case "4" -> "소나기";
            default -> "-";
        };
    }
}
