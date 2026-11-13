package com.kwon.bustour.web;

import com.kwon.bustour.security.LoginUser;
import com.kwon.bustour.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 내 예약 화면 (GET /my/reservations)
 *
 * ★ 로그인 확인은 여기서 하지 않는다
 *   SecurityConfig 의 anyRequest().authenticated() 가 이 주소를 막아서,
 *   로그인 안 한 사람은 로그인 화면으로 갔다가 로그인 후 여기로 돌아온다.
 *   → 이 메서드에 들어왔다면 loginUser 는 null 이 아니다.
 *
 * ★ 규칙 R19: 주소에 회원 번호를 받지 않는다 (/my/reservations?memberId=2 같은 것 없음)
 *   "내가 누구인지"는 로그인 정보(loginUser.getMemberId())로만 정한다.
 */
@Controller
@RequiredArgsConstructor
public class MyReservationController {

    private final ReservationService reservationService;

    @GetMapping("/my/reservations")
    public String myReservations(@AuthenticationPrincipal LoginUser loginUser, Model model) {
        model.addAttribute("reservations", reservationService.getMyReservations(loginUser.getMemberId()));
        return "reservation/my";           // templates/reservation/my.html
    }
}
