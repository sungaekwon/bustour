package com.kwon.bustour.pg;

/**
 * =====================================================================
 * PG(결제 대행사) 호출 창구 — 7단계
 *
 * ★ 왜 인터페이스인가
 *   서비스는 "승인해 줘 / 취소해 줘"만 알고, 상대가 가짜 PG 인지 토스인지 모른다.
 *     지금        : FakePgClient  (지연 · 거절 · 취소 실패를 마음대로 만들 수 있음 → JUnit 으로 보상 흐름 증명)
 *     7단계 마지막: TossPgClient  (토스 테스트 키, 클라이언트 키 + 시크릿 키) → 이 인터페이스만 구현해서 바꿔 끼움
 *   application.yml 의 bustour.pg.type 으로 어느 쪽을 쓸지 고른다.
 *
 * ★ 두 메서드 모두 "같은 결제 번호로 다시 불러도 결과가 같아야" 한다 (멱등성)
 *   approve : 이미 승인된 paymentKey 면 그 승인 결과를 그대로 돌려줌
 *             (토스는 이때 ALREADY_PROCESSED_PAYMENT 오류를 주므로, 토스 구현에서는 결제 조회로 바꿔 처리)
 *   cancel  : 이미 취소된 결제면 성공으로 봄
 *             (토스는 ALREADY_CANCELED_PAYMENT 오류 → 성공으로 바꿔 처리)
 *   → 더블클릭 · 재시도가 와도 PG 쪽에서 돈이 두 번 움직이지 않는다.
 *
 * ★ 이 메서드들은 DB 트랜잭션 밖에서 부른다 (규칙 R17)
 *   외부 호출은 느리고(수백 ms ~ 수 초) 언제 끝날지 모른다. 트랜잭션 안에서 부르면 그동안
 *   DB 커넥션과 행 락을 쥐고 있어서, 다른 예약 · 결제가 줄줄이 기다린다 (커넥션 풀 10개가 금방 바닥남).
 * =====================================================================
 */
public interface PgClient {

    /**
     * 결제 승인 요청
     *
     * @param paymentKey PG 가 결제창에서 준 결제 번호
     * @param orderId    우리 주문 번호
     * @param amount     승인할 금액 (서버가 예약 금액과 비교한 뒤의 값)
     * @return 승인 결과
     * @throws PgDeclinedException 카드 거절 · 잔액 부족 등 (돈이 나가지 않음)
     * @throws PgException         PG 연결 실패 · 시간 초과 등
     */
    PgApproval approve(String paymentKey, String orderId, int amount);

    /**
     * 결제 취소 (전액) — 보상 · 고객 취소 환불에 사용
     *
     * @throws PgException 취소 실패 (보상이면 payment_log 에 COMPENSATION_FAILED → 사람이 확인)
     */
    void cancel(String paymentKey, String reason);
}
