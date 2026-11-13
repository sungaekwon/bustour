package com.kwon.bustour.pg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * =====================================================================
 * 가짜 PG (7단계) — 진짜 돈 없이 결제 흐름 · 보상 · 동시성을 확인
 *
 * ★ 언제 쓰이나: application.yml 의 bustour.pg.type 이 fake 이거나 없을 때 (기본값)
 *   @ConditionalOnProperty : 설정 값이 맞을 때만 이 빈을 만든다
 *
 * ★ 화면에서 결과 고르기 (결제 화면의 가짜 결제창 버튼)
 *   paymentKey 가 "fake_decline_" 로 시작 → 카드 거절 (PgDeclinedException)
 *   그 밖의 paymentKey                    → 승인
 *
 * ★ 진짜 PG 처럼 흉내 내는 것
 *   1. 지연: 승인에 approve-delay-ms 만큼 걸림 (기본 300ms) → 외부 호출이 느리다는 걸 체감
 *   2. 멱등성: 같은 paymentKey 로 다시 승인하면 처음 결과를 그대로 / 이미 취소된 결제를 다시 취소하면 성공
 *
 * ★ JUnit 에서 상황 만들기 (테스트 전용 메서드)
 *   setApproveHook(…)  : 승인 "도중"에 끼워 넣을 일 → 예) 그 사이에 만료 스케줄러 실행 (만료 vs 결제 경합)
 *   setCancelFails(true): 취소가 실패하게 → 보상 실패(COMPENSATION_FAILED) 기록 확인
 *   cancelCount() 등    : PG 가 실제로 몇 번 불렸나 → "더블클릭인데 보상 취소가 불리지 않았다" 증명
 *
 * ★ 메모리(Map)에 저장 → 앱을 다시 켜면 비워진다 (가짜라서 괜찮음)
 *   ConcurrentHashMap : 여러 요청 스레드가 동시에 넣고 읽어도 안전한 Map
 * =====================================================================
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "bustour.pg.type", havingValue = "fake", matchIfMissing = true)
public class FakePgClient implements PgClient {

    /** 화면의 "카드 거절" 버튼이 만드는 결제 번호의 앞부분 */
    public static final String DECLINE_PREFIX = "fake_decline_";

    private final Clock clock;
    private volatile long approveDelayMs;       // volatile: 테스트 스레드가 바꾼 값을 요청 스레드가 바로 보게

    /** 승인된 결제: paymentKey → 승인 결과 */
    private final Map<String, PgApproval> approved = new ConcurrentHashMap<>();
    /** 취소된 결제 번호들 */
    private final Set<String> canceled = ConcurrentHashMap.newKeySet();

    // ----- 테스트에서 확인하는 숫자 (AtomicInteger: 여러 스레드가 동시에 +1 해도 안 틀림) -----
    private final AtomicInteger approveCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();

    // ----- 테스트에서 상황을 만드는 장치 -----
    private final AtomicBoolean cancelFails = new AtomicBoolean(false);
    private volatile Runnable approveHook = null;

    public FakePgClient(Clock clock,
                        @Value("${bustour.pg.fake.approve-delay-ms:300}") long approveDelayMs) {
        this.clock = clock;
        this.approveDelayMs = approveDelayMs;
    }

    @Override
    public PgApproval approve(String paymentKey, String orderId, int amount) {
        approveCalls.incrementAndGet();
        sleep(approveDelayMs);                                 // 외부 호출이 걸리는 시간 흉내

        if (paymentKey.startsWith(DECLINE_PREFIX)) {
            throw new PgDeclinedException("카드사에서 승인을 거절했습니다. (가짜 PG: 카드 거절)");
        }

        // ★ 멱등성: 같은 결제 번호면 처음 승인 결과 그대로 (돈은 한 번만 나감)
        //   computeIfAbsent : 없을 때만 새로 만들어 넣고, 있으면 있던 값을 돌려줌 (동시에 불려도 한 번만 만듦)
        PgApproval result = approved.computeIfAbsent(paymentKey,
                key -> new PgApproval(key, orderId, amount, LocalDateTime.now(clock)));

        Runnable hook = approveHook;
        if (hook != null) {
            hook.run();                                        // (테스트) 승인 도중에 다른 일이 일어난 상황
        }
        log.info("[가짜 PG] 승인 paymentKey={}, amount={}", paymentKey, amount);
        return result;
    }

    @Override
    public void cancel(String paymentKey, String reason) {
        cancelCalls.incrementAndGet();
        if (cancelFails.get()) {
            throw new PgException("가짜 PG: 취소 실패 (테스트로 만든 상황)");
        }
        if (!approved.containsKey(paymentKey)) {
            throw new PgException("가짜 PG: 승인된 적 없는 결제 번호 " + paymentKey);
        }
        // 이미 취소됐어도 성공으로 봄 (멱등성) — Set.add 는 처음일 때만 true
        boolean first = canceled.add(paymentKey);
        log.info("[가짜 PG] 취소 paymentKey={}, reason={}, 처음취소={}", paymentKey, reason, first);
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();                // 끼어들기 표시를 되살림 (삼키지 않기)
            throw new PgException("가짜 PG: 대기 중 중단됨", e);
        }
    }

    // =================== 아래는 테스트 전용 ===================

    /** 테스트마다 처음 상태로 */
    public void reset() {
        approved.clear();
        canceled.clear();
        approveCalls.set(0);
        cancelCalls.set(0);
        cancelFails.set(false);
        approveHook = null;
    }

    public void setApproveDelayMs(long ms) {
        this.approveDelayMs = ms;
    }

    public void setApproveHook(Runnable hook) {
        this.approveHook = hook;
    }

    public void setCancelFails(boolean fails) {
        cancelFails.set(fails);
    }

    public int approveCalls() {
        return approveCalls.get();
    }

    public int cancelCalls() {
        return cancelCalls.get();
    }

    /** 실제로 취소된(환불된) 결제 수 — 같은 결제를 두 번 취소해도 1 */
    public int canceledCount() {
        return canceled.size();
    }

    public boolean isCanceled(String paymentKey) {
        return canceled.contains(paymentKey);
    }
}
