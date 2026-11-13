package com.kwon.bustour.web;

import com.kwon.bustour.dto.TourCard;
import com.kwon.bustour.service.TourService;
import com.kwon.bustour.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 메인 화면 = 투어 3단 화면 (코스 + 출발 시간 + 예약 모달)
 *
 * ★ @Controller vs @RestController
 *   @RestController (PingController) : return 값 = 응답 본문 (JSON 글자 그대로)
 *   @Controller     (여기)           : return 값 = "화면 이름" → templates/index.html 을 그려서 보냄
 *
 * ★ Model = 서블릿의 req.setAttribute(...) 와 같은 역할
 *   model.addAttribute("tours", 값) → 화면에서 ${tours}
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final TourService tourService;
    private final WeatherService weatherService;          // 8단계: 날짜 아래 부산 날씨 (캐시)

    @GetMapping("/")
    public String home(Model model) {
        List<TourCard> tours = tourService.getMainTours();
        model.addAttribute("tours", tours);

        // ★ 3칸의 "출발 시간"을 같은 줄에서 시작하게 하려고, 가장 긴 코스의 정류장 수를 넘긴다.
        //   화면(index.html)에서 이 값을 CSS 변수 --course-rows 로 넣으면
        //   모든 칸의 코스 목록 높이가 "정류장 수 × 한 줄 높이"로 같아진다 (짧은 코스는 아래가 빈칸).
        //   mapToInt(...).max() : 숫자 중 가장 큰 값 / orElse(0) : 투어가 하나도 없으면 0
        int maxStops = tours.stream()
                .mapToInt(t -> t.getStops().size())
                .max()
                .orElse(0);
        model.addAttribute("maxStops", maxStops);

        // ★ 8단계: 날짜 → 날씨 요약. 캐시에 있으면 기상청을 부르지 않는다 (WeatherService 의 @Cacheable)
        //   예보가 있는 날(오늘 ~ 3, 4일 뒤)만 들어 있고, 실패하면 빈 Map → 화면은 날씨 칸만 빔
        model.addAttribute("weather", weatherService.getBusanForecast());

        return "index";     // classpath:/templates/ + index + .html  (앞에 / 없이)
    }
}
