package com.kwon.bustour.web;

import com.kwon.bustour.service.TourService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 예전 투어 주소 → 메인으로 보내기 (화면은 메인 하나로 합침)
 *   GET /tours       → /#tours        (메인의 투어 3단 위치)
 *   GET /tours/{id}  → /#tour-{id}    (메인의 그 투어 칸 위치)
 *
 * ★ "redirect:주소" : 화면 이름 대신 이렇게 돌려주면 스프링이 302 응답 + Location 헤더를 보낸다
 *   → 브라우저가 그 주소로 다시 요청 (서블릿의 resp.sendRedirect(...) 와 같음)
 *   # 뒤(조각 식별자)는 서버로 가지 않고, 브라우저가 페이지에서 id="tour-1" 인 곳으로 스크롤한다.
 */
@Controller
@RequiredArgsConstructor
public class TourController {

    private final TourService tourService;

    @GetMapping("/tours")
    public String list() {
        return "redirect:/#tours";
    }

    @GetMapping("/tours/{id}")
    public String detail(@PathVariable Long id) {
        tourService.checkOnSale(id);              // 숨김 · 없는 투어 → TourNotFoundException → 404 화면
        return "redirect:/#tour-" + id;
    }
}
