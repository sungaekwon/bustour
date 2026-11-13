package com.kwon.bustour.service;

import com.kwon.bustour.domain.ReservationStatus;
import com.kwon.bustour.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * =====================================================================
 * HOLD 만료 스케줄러 (7단계, 규칙 R10) — 1분마다 "결제 시간이 지난 PENDING"의 자리를 돌려준다
 *
 * ★ @Scheduled(fixedDelay = …) : 앞 실행이 "끝난 뒤" 60초 쉬고 다시 실행
 *   (fixedRate 는 시작 시각 기준이라 앞 실행이 길어지면 겹쳐 돌 수 있음 → fixedDelay 가 안전)
 *   이 어노테이션은 SchedulingConfig 의 @EnableScheduling 이 켜져 있을 때만 동작한다.
 *   JUnit 에서는 꺼 두고(application-test.yml), 테스트가 expireDueHolds() 를 직접 부른다.
 *
 * ★ 한 건 = 한 트랜잭션 (ReservationStateService.expire)
 *   100건을 한 트랜잭션으로 묶으면, 한 건이 실패할 때 전부 롤백되고, 끝날 때까지 회차 락을 오래 쥔다.
 *
 * ★ 만료 직전에 결제가 들어와도 안전 (규칙 R11)
 *   만료: WHERE status='PENDING' AND expires_at <= 지금 / 확정: WHERE status='PENDING' AND expires_at > 지금
 *   같은 예약 행에 X 락이 걸려 줄을 서므로, 먼저 끝난 쪽만 바뀌고 나중 쪽은 0행.
 *   결제 쪽이 지면 PaymentService 가 보상(PG 취소)한다.
 *
 * ★ 서버가 2대가 되면(12단계) 두 서버의 스케줄러가 같은 예약을 동시에 만료하려 할 수 있다.
 *   조건부 UPDATE 라서 한쪽만 1행 → 자리가 두 번 돌아오지 않는다 (중복 실행돼도 결과는 안전, 일만 두 번).
 * =====================================================================
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpiryScheduler {

    /** 한 번에 처리할 최대 건수 (밀려 있어도 다음 실행에서 이어서) */
    private static final int BATCH_SIZE = 100;

    private final ReservationRepository reservationRepository;
    private final ReservationStateService stateService;
    private final Clock clock;

    /** 1분마다 (앱 시작 10초 뒤부터). 간격은 application.yml 의 bustour.scheduler.expire-delay-ms */
    @Scheduled(initialDelay = 10_000, fixedDelayString = "${bustour.scheduler.expire-delay-ms:60000}")
    public void run() {
        int expired = expireDueHolds(LocalDateTime.now(clock));
        if (expired > 0) {
            log.info("HOLD 만료 처리: {}건", expired);
        }
    }

    /**
     * 지금 기준으로 만료된 PENDING 을 최대 100건 처리
     * @return 실제로 만료시킨 건수 (그 사이 결제된 건은 세지 않음)
     */
    public int expireDueHolds(LocalDateTime now) {
        // 대상 id 만 먼저 (인덱스 idx_status_expires). 이 SELECT 는 리포지토리 기본 읽기 트랜잭션으로 금방 끝남
        List<Long> ids = reservationRepository.findExpiredPendingIds(
                ReservationStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE));

        int count = 0;
        for (Long id : ids) {
            try {
                if (stateService.expire(id, now)) {      // 한 건 = 한 트랜잭션
                    count++;
                }
            } catch (RuntimeException e) {
                // 한 건이 실패해도(락 대기 초과 등) 나머지는 계속. 실패한 건은 다음 실행(1분 뒤)에 다시 대상이 됨
                log.warn("HOLD 만료 실패: reservation={}, {}", id, e.getMessage());
            }
        }
        return count;
    }
}
