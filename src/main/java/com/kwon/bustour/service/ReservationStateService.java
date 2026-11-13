package com.kwon.bustour.service;

import com.kwon.bustour.domain.Reservation;
import com.kwon.bustour.domain.ReservationStatus;
import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import com.kwon.bustour.repository.ReservationRepository;
import com.kwon.bustour.repository.TourScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * =====================================================================
 * 결제 전(PENDING) 예약의 자리 돌려주기: 만료 · 포기 (7단계)
 *
 * ★ 한 트랜잭션 안의 순서 (예약 · 취소와 같은 "회차 먼저 → 예약 나중")
 *   ① 예약을 그냥 읽기 (락 없음) : 회차 번호 · 인원만 알면 됨. PENDING 이 아니면 여기서 바로 끝 (빠른 거르기)
 *   ② 회차 remaining + n         : 회차 행에 X 락
 *   ③ 예약 조건부 UPDATE         : "아직 PENDING 일 때만" → 예약 행에 X 락
 *   ④ ③이 0행 = 그 사이 결제 · 만료 · 포기가 먼저 끝남 → 롤백해서 ②도 취소 (자리가 두 번 돌아오지 않게)
 *
 * ★ 왜 ②를 ③보다 먼저? (설계계획 5쪽 데드락 대응 (나))
 *   예약(reserve)은 "회차 X → 예약 INSERT(유니크 항목 락)" 순서다.
 *   만료가 "예약 X → 회차 X" 순서면, 같은 회원이 만료 순간에 같은 회차를 다시 예약할 때
 *   서로 상대가 쥔 락을 기다리는 데드락(1213)이 생긴다. 모든 흐름의 락 순서를 "회차 → 예약"으로 맞추면 생기지 않는다.
 *
 * ★ ①의 상태 확인만으로 끝내지 않는 이유
 *   ①은 잠그지 않는 읽기라 ②~③ 사이에 결제가 끝날 수 있다. 진짜 판단은 ③의 조건부 UPDATE 가 최신 값으로 한다.
 *   ①은 "이미 끝난 예약 때문에 회차에 락을 거는 일"을 줄이는 용도.
 * =====================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationStateService {

    private final ReservationRepository reservationRepository;
    private final TourScheduleRepository scheduleRepository;
    private final Clock clock;

    /**
     * 만료 1건 (스케줄러가 한 건씩 부름 → 한 건 = 한 트랜잭션)
     *
     * @param now 스케줄러가 정한 "지금" (Clock, 규칙 R22). 테스트에서는 미래 시각을 넣어 만료를 만든다
     * @return true = 이번에 만료시킴 / false = 이미 결제 · 포기 · 만료됨 (아무것도 안 바뀜)
     */
    @Transactional
    public boolean expire(Long reservationId, LocalDateTime now) {
        // ① 그냥 읽기
        Reservation r = reservationRepository.findById(reservationId).orElse(null);
        if (r == null || r.getStatus() != ReservationStatus.PENDING) {
            return false;
        }
        // r.getSchedule().getId() : LAZY 대리 객체여도 id 는 알고 있어서 SELECT 가 나가지 않음
        Long scheduleId = r.getSchedule().getId();

        // ② 회차 먼저 (X 락)
        int restored = scheduleRepository.increaseRemaining(scheduleId, r.getHeadcount());
        // ③ 예약 조건부 UPDATE
        int changed = reservationRepository.expireIfPending(reservationId, now,
                ReservationStatus.PENDING, ReservationStatus.EXPIRED);

        if (changed == 0) {
            // ④ 그 사이 결제가 확정됐거나(R11) 이미 처리됨 → ②도 되돌려야 한다
            //   setRollbackOnly : 예외를 던지지 않고 "이 트랜잭션은 커밋하지 말고 롤백"으로 표시
            //   (스케줄러가 예외 로그로 시끄러워지지 않게. 정상적으로 있을 수 있는 경우라서)
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        if (restored == 0) {
            // 예약은 살아 있었는데 자리를 돌려줄 수 없다 = 정원보다 많아짐 = 어딘가 버그 → 롤백하고 알림
            throw new IllegalStateException("자리 반환 실패 (정원 초과): reservation=" + reservationId);
        }
        log.info("HOLD 만료: 예약 {} → EXPIRED, 회차 {} 에 {}석 반환", reservationId, scheduleId, r.getHeadcount());
        return true;
    }

    /**
     * 포기하기 (결제 전에 사용자가 직접 자리를 돌려줌) → CANCELED
     *
     * @throws BusinessException RESERVATION_NOT_FOUND(남의 것 · 없음), NOT_ABANDONABLE(이미 결제 · 만료 · 포기)
     */
    @Transactional
    public void abandon(Long memberId, Long reservationId) {
        LocalDateTime now = LocalDateTime.now(clock);

        // ① 내 예약인지 (규칙 R19) + 상태 빠른 거르기
        Reservation r = reservationRepository.findMine(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (r.getStatus() != ReservationStatus.PENDING) {
            throw new BusinessException(ErrorCode.NOT_ABANDONABLE);
        }

        // ② 회차 먼저 → ③ 예약
        int restored = scheduleRepository.increaseRemaining(r.getSchedule().getId(), r.getHeadcount());
        int changed = reservationRepository.abandonIfPending(reservationId, memberId, now,
                ReservationStatus.PENDING, ReservationStatus.CANCELED);

        if (changed == 0) {
            // 그 사이 결제 · 만료가 먼저 끝남 → 예외 = 롤백 → ②의 자리 반환도 취소
            throw new BusinessException(ErrorCode.NOT_ABANDONABLE);
        }
        if (restored == 0) {
            throw new IllegalStateException("자리 반환 실패 (정원 초과): reservation=" + reservationId);
        }
    }
}
