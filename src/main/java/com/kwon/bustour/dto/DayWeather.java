package com.kwon.bustour.dto;

import lombok.Getter;

import java.time.LocalDate;

/**
 * 하루 날씨 요약 (8단계) — 메인 화면 날짜 아래 한 줄: "맑음 9~18° 강수20%"
 *
 *   sky     : 하늘 (비가 오는 시간이 있으면 "비" · "눈" …, 아니면 낮 12시의 맑음 · 구름많음 · 흐림)
 *   minTemp : 최저 기온 (TMN, 없으면 그날 TMP 중 가장 낮은 값)  — 예보에 없으면 null
 *   maxTemp : 최고 기온 (TMX, 없으면 TMP 중 가장 높은 값)
 *   pop     : 그날 가장 높은 강수확률(%)
 */
@Getter
public class DayWeather {

    private final LocalDate date;
    private final String sky;
    private final Integer minTemp;
    private final Integer maxTemp;
    private final int pop;
    /** (A안) 그림 파일 이름: sun · partly · cloud · rain · snow · shower → /img/weather/{icon}.svg */
    private final String icon;
    /** (A안) true = 중기예보(4일 뒤부터, 오전 · 오후 단위라 덜 자세함) */
    private final boolean mid;

    public DayWeather(LocalDate date, String sky, Integer minTemp, Integer maxTemp, int pop) {
        this(date, sky, minTemp, maxTemp, pop, false);
    }

    public DayWeather(LocalDate date, String sky, Integer minTemp, Integer maxTemp, int pop, boolean mid) {
        this.date = date;
        this.sky = sky;
        this.minTemp = minTemp;
        this.maxTemp = maxTemp;
        this.pop = pop;
        this.icon = iconOf(sky);
        this.mid = mid;
    }

    /**
     * 하늘 글자 → 그림 이름 (단기 "비" · "구름많음", 중기 "구름많고 비" · "흐리고 눈" 모두 처리)
     *   비 · 눈이 들어 있으면 그것을 먼저 (우산이 필요한지가 가장 중요해서)
     */
    public static String iconOf(String sky) {
        if (sky == null) return "cloud";
        if (sky.contains("소나기")) return "shower";
        if (sky.contains("눈")) return "snow";
        if (sky.contains("비")) return "rain";
        if (sky.contains("흐")) return "cloud";
        if (sky.contains("구름")) return "partly";
        if (sky.contains("맑")) return "sun";
        return "cloud";
    }

    /** (A안) 기온 한 줄 "9° / 18°" (없으면 "-") */
    public String getTempText() {
        return (minTemp != null && maxTemp != null) ? minTemp + "° / " + maxTemp + "°" : "-";
    }

    /** 화면에 보일 한 줄 (타임리프에서 ${w.summary}) */
    public String getSummary() {
        StringBuilder sb = new StringBuilder(sky);
        if (minTemp != null && maxTemp != null) {
            sb.append(' ').append(minTemp).append('~').append(maxTemp).append('°');
        }
        sb.append(" 강수").append(pop).append('%');
        return sb.toString();
    }
}
