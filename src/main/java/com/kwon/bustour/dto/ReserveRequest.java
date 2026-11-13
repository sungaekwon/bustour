package com.kwon.bustour.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 예약 요청 JSON (모달 → POST /api/reservations)
 *
 *   {"scheduleId": 12, "headcount": 2, "requestKey": "3f1c…-…"}
 *
 * ★ JSON → 객체
 *   컨트롤러의 @RequestBody 가 JSON 을 이 클래스로 바꿔 준다 (Jackson 라이브러리).
 *   기본 생성자 + setter(또는 필드 접근)가 필요해서 @Setter.
 *
 * ★ memberId 는 받지 않는다 (규칙 R19)
 *   "누가 예약하나"는 화면이 보낸 값이 아니라 서버의 로그인 정보(LoginUser)로 정한다.
 *   받으면 남의 회원 번호를 넣어 남의 이름으로 예약할 수 있게 된다.
 *
 * ★ Integer(감싼 타입)를 쓴 이유: 값이 빠졌을 때 int 는 0 이 되어 "0명"과 "안 보냄"을 구분 못 함
 *   Integer 는 null → @NotNull 로 "인원을 선택해 주세요"
 */
@Getter
@Setter
public class ReserveRequest {

    @NotNull(message = "회차를 선택해 주세요.")
    private Long scheduleId;

    @NotNull(message = "인원을 선택해 주세요.")
    @Min(value = 1, message = "인원은 1명 이상이어야 합니다.")
    @Max(value = 10, message = "한 번에 최대 10명까지 예약할 수 있습니다.")
    private Integer headcount;

    // UUID 모양 (8-4-4-4-12, 16진수) 만 허용 → 이상한 값이 DB 까지 가지 않게
    @NotBlank(message = "잘못된 요청입니다. 화면을 새로고침해 주세요.")
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
             message = "잘못된 요청입니다. 화면을 새로고침해 주세요.")
    private String requestKey;
}
