package com.kwon.bustour.weather;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================
 * 기상청 단기예보 API 호출 (8단계) — 공공데이터포털 "기상청_단기예보 ((구)_동네예보) 조회서비스"
 *
 *   GET https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst
 *       ?serviceKey=…&pageNo=1&numOfRows=1500&dataType=JSON
 *       &base_date=20261110&base_time=1400&nx=98&ny=76
 *
 * ★ base_date · base_time = "몇 시 발표분을 달라"
 *   단기예보는 하루 8번 발표: 02 · 05 · 08 · 11 · 14 · 17 · 20 · 23시, API 에는 발표 약 10분 뒤에 올라온다.
 *   → 지금 시각에서 "15분 이상 지난 가장 최근 발표 시각"을 고른다 (latestBase). 새벽 2시 15분 전이면 어제 23시.
 * ★ nx · ny = 기상청 격자 좌표 (위도 · 경도가 아님). 부산 기본값 98, 76 (application.yml 에서 바꿈)
 *
 * ★ 인증키 (환경변수 KMA_SERVICE_KEY) — 공공데이터포털 마이페이지의 "일반 인증키(Decoding)"를 넣는다
 *   키에 + / = 같은 글자가 들어 있다. 그대로 주소에 붙이면 + 가 공백으로 바뀌어
 *   SERVICE_KEY_IS_NOT_REGISTERED_ERROR 가 난다 → URLEncoder 로 직접 인코딩하고, 주소를 다시 인코딩하지 않게 URI 로 넘긴다.
 *   키가 없으면 호출하지 않고 빈 목록 (날씨 칸만 비고 화면은 정상).
 *
 * ★ 오류일 때 XML 이 올 수 있다 (dataType=JSON 이어도 키 오류 등은 XML 로 응답)
 *   → 글자로 받은 뒤 { 로 시작하는지 보고 JSON 으로 읽는다.
 *   JsonParserFactory : 스프링 부트가 주는 JSON → Map 변환 도구 (Jackson 버전을 신경 쓰지 않아도 됨)
 * =====================================================================
 */
@Slf4j
@Component
public class KmaWeatherClient {

    private static final String URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";
    /** (A안) 중기예보: getMidLandFcst(하늘 · 강수확률) / getMidTa(기온) — 같은 인증키, 활용신청은 따로 */
    private static final String MID_URL = "https://apis.data.go.kr/1360000/MidFcstInfoService/";
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HHmm");
    /** 발표 시각 (시) */
    private static final int[] BASE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};

    private final String serviceKey;
    private final RestClient restClient;

    public KmaWeatherClient(@Value("${bustour.weather.service-key:}") String serviceKey) {
        this.serviceKey = serviceKey;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));   // 메인 화면이 기다리므로 짧게 (캐시가 비었을 때만 기다림)
        this.restClient = RestClient.builder().requestFactory(factory).build();
        if (!isEnabled()) {
            log.warn("[기상청] 환경변수 KMA_SERVICE_KEY 가 없어서 날씨를 불러오지 않습니다.");
        }
    }

    public boolean isEnabled() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /**
     * 예보 가져오기 (실패하면 예외 → WeatherService 가 잡아서 빈 결과로)
     *
     * @param now 한국 시각 (Clock, 규칙 R22)
     */
    public List<ForecastItem> fetch(int nx, int ny, LocalDateTime now) {
        if (!isEnabled()) {
            return List.of();
        }
        LocalDateTime base = latestBase(now);
        String url = URL
                + "?serviceKey=" + URLEncoder.encode(serviceKey, StandardCharsets.UTF_8)   // ★ 키는 직접 인코딩
                + "&pageNo=1&numOfRows=1500&dataType=JSON"
                + "&base_date=" + base.format(YMD)
                + "&base_time=" + base.format(HM)
                + "&nx=" + nx + "&ny=" + ny;

        long start = System.currentTimeMillis();
        // URI.create : 이미 인코딩된 주소 그대로 (RestClient 가 한 번 더 인코딩하지 않게)
        String text = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        log.info("[기상청] 단기예보 호출 base={} {} ({}ms)", base.format(YMD), base.format(HM),
                System.currentTimeMillis() - start);      // ★ 캐시가 맞으면 이 줄이 안 찍힌다 (8단계 확인 포인트)

        return parse(text);
    }

    /**
     * (A안) 중기예보 1건 (지역 하나의 4 ~ 10일 뒤 예보가 한 줄에 다 들어 있음)
     *
     * @param operation getMidLandFcst(wf4Am · wf4Pm · rnSt4Am …) 또는 getMidTa(taMin4 · taMax4 …)
     * @param regId     지역 코드 (육상: 부산 · 울산 · 경남 11H20000 / 기온: 부산 11H20201)
     * @param base      발표 시각 (latestMidBase: 06시 · 18시)
     * @return 예보 값 Map (키 예: "wf5Pm" → "구름많고 비"), 키가 없으면 빈 Map
     */
    public Map<String, Object> fetchMid(String operation, String regId, LocalDateTime base) {
        if (!isEnabled()) {
            return Map.of();
        }
        String url = MID_URL + operation
                + "?serviceKey=" + URLEncoder.encode(serviceKey, StandardCharsets.UTF_8)
                + "&pageNo=1&numOfRows=10&dataType=JSON"
                + "&regId=" + regId
                + "&tmFc=" + base.format(YMD) + base.format(HM);       // 예) 202611100600
        String text = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        log.info("[기상청] 중기예보 호출 {} tmFc={}{}", operation, base.format(YMD), base.format(HM));
        return parseMid(text);
    }

    /** (A안) 중기예보 발표 시각: 06시 · 18시 (30분 여유). 새벽 06:30 전이면 어제 18시 */
    public static LocalDateTime latestMidBase(LocalDateTime now) {
        LocalDateTime t = now.minusMinutes(30);
        if (t.getHour() >= 18) return t.toLocalDate().atTime(18, 0);
        if (t.getHour() >= 6) return t.toLocalDate().atTime(6, 0);
        return t.toLocalDate().minusDays(1).atTime(18, 0);
    }

    /** (A안) 중기예보 응답 → 첫 번째 item Map */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseMid(String text) {
        if (text == null || !text.trim().startsWith("{")) {
            throw new IllegalStateException("기상청 중기예보 응답이 JSON 이 아님 (활용신청 · 키 확인)");
        }
        Map<String, Object> response = (Map<String, Object>) JsonParserFactory.getJsonParser().parseMap(text).get("response");
        Map<String, Object> header = (Map<String, Object>) response.get("header");
        if (!"00".equals(String.valueOf(header.get("resultCode")))) {
            throw new IllegalStateException("기상청 중기예보 오류 " + header.get("resultCode") + " " + header.get("resultMsg"));
        }
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) response.get("body")).get("items");
        List<Map<String, Object>> list = (List<Map<String, Object>>) items.get("item");
        return list.isEmpty() ? Map.of() : list.get(0);
    }

    /** 지금 기준으로 쓸 수 있는 가장 최근 발표 시각 (발표 + 15분이 지난 것) */
    static LocalDateTime latestBase(LocalDateTime now) {
        LocalDateTime t = now.minusMinutes(15);
        for (int i = BASE_HOURS.length - 1; i >= 0; i--) {
            if (t.getHour() >= BASE_HOURS[i]) {
                return t.toLocalDate().atTime(BASE_HOURS[i], 0);
            }
        }
        return t.toLocalDate().minusDays(1).atTime(23, 0);   // 새벽 02:15 전 → 어제 23시 발표분
    }

    /** 응답 글자 → 예보 목록 */
    @SuppressWarnings("unchecked")
    static List<ForecastItem> parse(String text) {
        if (text == null || !text.trim().startsWith("{")) {
            // 키 오류 등은 XML: <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
            throw new IllegalStateException("기상청 응답이 JSON 이 아님: "
                    + (text == null ? "null" : text.substring(0, Math.min(200, text.length()))));
        }
        Map<String, Object> root = JsonParserFactory.getJsonParser().parseMap(text);
        Map<String, Object> response = (Map<String, Object>) root.get("response");
        Map<String, Object> header = (Map<String, Object>) response.get("header");
        String resultCode = String.valueOf(header.get("resultCode"));
        if (!"00".equals(resultCode)) {
            throw new IllegalStateException("기상청 오류 " + resultCode + " " + header.get("resultMsg"));
        }
        Map<String, Object> body = (Map<String, Object>) response.get("body");
        Map<String, Object> items = (Map<String, Object>) body.get("items");
        List<Map<String, Object>> list = (List<Map<String, Object>>) items.get("item");

        List<ForecastItem> result = new ArrayList<>();
        for (Map<String, Object> m : list) {
            result.add(new ForecastItem(
                    LocalDate.parse(String.valueOf(m.get("fcstDate")), YMD),
                    LocalTime.parse(String.valueOf(m.get("fcstTime")), HM),
                    String.valueOf(m.get("category")),
                    String.valueOf(m.get("fcstValue"))));
        }
        return result;
    }
}
