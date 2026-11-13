package com.kwon.bustour.service;

import com.kwon.bustour.domain.Member;
import com.kwon.bustour.domain.Reservation;
import com.kwon.bustour.domain.ReservationStatus;
import com.kwon.bustour.domain.ScheduleStatus;
import com.kwon.bustour.domain.TourSchedule;
import com.kwon.bustour.dto.MyReservationView;
import com.kwon.bustour.dto.ReservationResult;
import com.kwon.bustour.exception.BusinessException;
import com.kwon.bustour.exception.ErrorCode;
import com.kwon.bustour.repository.MemberRepository;
import com.kwon.bustour.repository.ReservationRepository;
import com.kwon.bustour.repository.ScheduleLatestView;
import com.kwon.bustour.repository.TourScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * =====================================================================
 * 예약 서비스 (5단계 핵심): 자리를 잡고 PENDING 예약을 만든다
 *
 * ★ 한 트랜잭션 안의 순서 (이 순서 자체가 설계다)
 *   ① 같은 요청 키로 이미 만든 예약이 있나?  → 있으면 그 결과를 그대로 (멱등성, R6)
 *   ② 회차 + 가격 읽기 (보통 SELECT, 잠그지 않음)
 *   ③ 자리 차감 = 원자적 UPDATE (회차 행에 X 락)      → 0행이면 매진/마감/중지 (R1 · R2)
 *        → 0행일 때만 FOR SHARE 로 최신 값을 읽어 이유를 고름 (방법 B)
 *   ④ 예약 INSERT (외래키 때문에 회차 행에 S 락 → 이미 X 를 쥐고 있어서 통과)
 *        → UNIQUE 위반이면 예외 → 트랜잭션 롤백 → ③에서 줄인 자리도 되돌아감 (R5 · R6)
 *   ⑤ 커밋 → 락 풀림 → 기다리던 다음 요청이 최신 remaining 으로 ③을 실행
 *
 * ★ R21 "UPDATE 먼저, INSERT 나중"
 *   INSERT 를 먼저 하면 두 요청이 회차 행에 S 락(외래키)을 나눠 쥔 채 서로의 X 락(UPDATE)을 기다려 데드락(1213).
 *   UPDATE 를 먼저 하면 두 번째 요청은 ③에서 아무 락도 없이 줄을 서므로 데드락이 생기지 않는다.
 *
 * ★ synchronized 를 쓰지 않는 이유
 *   1. 앱이 2대가 되면(12단계) 서로의 자바 락을 모른다 → DB 락만 모든 서버에 공통
 *   2. @Transactional + synchronized 함정: 메서드가 끝나 락이 풀린 "뒤에" 커밋되므로 그 사이에 다른 스레드가 옛 값을 읽음
 *      (concurrency-boot 실험의 함정 ①)
 * =====================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    /** 자리를 잡아 두는 시간 (HOLD 10분) */
    public static final long HOLD_MINUTES = 10;
    /** 예약 마감: 출발 1시간 전 */
    public static final long BOOKING_CLOSE_HOURS = 1;
    /** 한 번에 최대 인원 */
    public static final int MAX_HEADCOUNT = 10;
    /** 취소 마감: 출발 3일 전까지 (전액 환불, 규칙 R13) — 7단계 */
    public static final long CANCEL_CLOSE_DAYS = 3;

    private final TourScheduleRepository scheduleRepository;
    private final ReservationRepository reservationRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;

    /**
     * 예약 (자리 잡기 → PENDING)
     *
     * @param memberId   로그인한 회원 (컨트롤러가 LoginUser 에서 꺼내 줌. 화면이 보낸 값을 믿지 않음)
     * @param scheduleId 회차
     * @param headcount  인원 1~10
     * @param requestKey 모달을 열 때 만든 UUID (같은 키 = 같은 요청)
     * @throws BusinessException SOLD_OUT, BOOKING_CLOSED, SCHEDULE_CLOSED, DUPLICATE_RESERVATION …
     */
    @Transactional
    public ReservationResult reserve(Long memberId, Long scheduleId, int headcount, String requestKey) {

        // ⓪ 입력 확인 (컨트롤러의 @Min/@Max 와 DB CHECK 사이의 한 겹 더)
        if (headcount < 1 || headcount > MAX_HEADCOUNT) {
            throw new BusinessException(ErrorCode.INVALID_HEADCOUNT);
        }

        // ① 멱등성: 이 요청 키로 이미 만든 예약이 있으면 "처음 결과"를 그대로 돌려줌
        //    (더블클릭, 네트워크 재전송 → 예약 2건이 아니라 같은 1건)
        Optional<Reservation> existing = reservationRepository.findByRequestKey(requestKey);
        if (existing.isPresent()) {
            Reservation r = existing.get();
            // 남의 요청 키를 들고 온 경우 (키가 새어 나갔거나 조작) → 남의 예약 정보를 돌려주지 않음
            if (!r.getMember().getId().equals(memberId)) {
                throw new BusinessException(ErrorCode.REQUEST_KEY_CONFLICT);
            }
            return ReservationResult.of(r, false);
        }

        // ② 회차 + 투어 가격 읽기 (잠그지 않는 SELECT)
        TourSchedule schedule = scheduleRepository.findWithTourById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));

        // ③ 원자적 UPDATE: 확인과 차감을 한 문장으로
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.plusHours(BOOKING_CLOSE_HOURS);
        int updated = scheduleRepository.decreaseRemaining(scheduleId, headcount, ScheduleStatus.OPEN, cutoff);
        if (updated == 0) {
            throw failReason(scheduleId, headcount, cutoff);   // 실패한 요청만 최신 값을 한 번 더 읽음 (방법 B)
        }

        // ④ 예약 INSERT
        //    getReferenceById : SELECT 없이 "id 만 아는 대리 객체" → INSERT 에 member_id 만 있으면 되므로 충분
        Member memberRef = memberRepository.getReferenceById(memberId);
        Reservation reservation = Reservation.hold(schedule, memberRef, headcount, requestKey, now, HOLD_MINUTES);
        try {
            // saveAndFlush : INSERT 를 "지금" 실행 → UNIQUE 위반을 이 try 안에서 잡음
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // 예외를 던지면 트랜잭션 롤백 → ③에서 줄인 자리가 되돌아간다
            throw new BusinessException(reasonOfDuplicate(e));
        }

        return ReservationResult.of(reservation, true);
    }

    /**
     * 내 예약 목록 (6단계: /my/reservations)
     *
     * ★ 규칙 R19 "내 예약만": memberId 는 컨트롤러가 로그인 정보에서 꺼내 준 값
     * ★ SELECT 1번 (예약 + 회차 + 투어 JOIN FETCH) → 예약이 몇 개든 쿼리 수가 늘지 않음
     */
    @Transactional(readOnly = true)
    public List<MyReservationView> getMyReservations(Long memberId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return reservationRepository.findMyReservations(memberId).stream()
                .map(r -> toMyView(r, now))
                .toList();
    }

    /**
     * 예약 → 화면 한 줄. 상태 글자는 여기서 정한다 (화면은 판단하지 않음)
     *
     * ★ "PENDING 인데 만료 시각이 지남" 을 따로 보여 주는 이유
     *   만료 스케줄러(HoldExpiryScheduler)는 1분마다 돌아서, 만료 직후 잠깐은 DB 에 아직 PENDING 으로 남아 있다.
     *   그 사이 화면에 "결제 대기"라고 보여 주면 결제할 수 있는 것처럼 보이므로, 시각으로 한 번 더 판단한다.
     *   (결제 확정도 "expires_at > 지금" 조건이 있어서 실제로 결제되지는 않는다)
     */
    private MyReservationView toMyView(Reservation r, LocalDateTime now) {
        String statusText;
        String badge;
        boolean pending = false;
        long remainSeconds = 0;
        boolean cancelable = false;
        LocalDateTime cancelUntil = null;

        if (r.getStatus() == ReservationStatus.PENDING && r.getExpiresAt().isAfter(now)) {
            statusText = "결제 대기";
            badge = "badge-wait";
            pending = true;
            remainSeconds = Duration.between(now, r.getExpiresAt()).getSeconds();
        } else if (r.getStatus() == ReservationStatus.PENDING) {
            statusText = "결제 시간 지남";
            badge = "badge-no";
        } else if (r.getStatus() == ReservationStatus.CONFIRMED) {
            statusText = "예약 확정";
            badge = "badge-ok";
            // 7단계: 취소 마감 = 출발 3일 전. 그 전이면 "취소하기" 버튼 (진짜 확인은 PaymentService.cancel 이 다시 함)
            cancelUntil = r.getSchedule().getStartAt().minusDays(CANCEL_CLOSE_DAYS);
            cancelable = now.isBefore(cancelUntil);
        } else if (r.getStatus() == ReservationStatus.EXPIRED) {
            statusText = "만료";
            badge = "badge-no";
        } else {
            // CANCELED 는 두 가지: 결제 전에 포기 / 결제 후 취소(환불) → confirmed_at 이 있으면 결제됐던 예약
            statusText = r.getConfirmedAt() != null ? "취소 · 환불" : "포기";
            badge = "badge-no";
        }

        return new MyReservationView(r.getId(), r.getSchedule().getTour().getName(),
                r.getSchedule().getStartAt(), r.getHeadcount(), r.getAmount(),
                statusText, badge, pending, remainSeconds, cancelable, cancelUntil, r.getCreatedAt());
    }

    /**
     * ③이 0행일 때 이유 고르기 (방법 B: 최신 값으로 판단)
     *
     * ★ 함정: ②에서 읽은 schedule 객체나, 보통 SELECT 로 다시 읽은 값은 "옛 값"이다
     *   1. REPEATABLE READ 의 보통 SELECT 는 트랜잭션에서 처음 읽은 시점의 스냅샷을 보여 준다.
     *   2. JPA 로 같은 엔티티를 다시 조회하면 1차 캐시의 객체를 돌려줘서 값이 안 바뀐다.
     *   → FOR SHARE(잠그는 읽기 = 최신 값) + 프로젝션(엔티티가 아닌 값)으로 읽는다.
     *     (TourScheduleRepository.findLatestForShare 주석 참고)
     *
     * 판단 순서: 운영 중지 → 예약 마감 → 자리 부족 (먼저 맞는 이유 하나)
     */
    private BusinessException failReason(Long scheduleId, int headcount, LocalDateTime cutoff) {
        ScheduleLatestView latest = scheduleRepository.findLatestForShare(scheduleId).orElse(null);
        if (latest == null) {
            return new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
        if (!ScheduleStatus.OPEN.name().equals(latest.getStatus())) {
            return new BusinessException(ErrorCode.SCHEDULE_CLOSED);
        }
        if (!latest.getStartAt().isAfter(cutoff)) {
            return new BusinessException(ErrorCode.BOOKING_CLOSED);
        }
        if (latest.getRemaining() < headcount) {
            // 최신 값이라 남은 자리 숫자까지 정확하게 안내할 수 있다 (방법 A 로는 불가능했던 부분)
            String msg = latest.getRemaining() == 0
                    ? "매진되었습니다."
                    : ErrorCode.SOLD_OUT.getMessage() + " (지금 남은 자리 " + latest.getRemaining() + "석)";
            return new BusinessException(ErrorCode.SOLD_OUT, msg);
        }
        // 여기까지 왔다 = UPDATE 순간엔 부족했는데, 그 사이 누가 취소해서 지금은 자리가 있음 (아주 드묾)
        // 이 요청은 실패로 끝내고 다시 시도하게 안내 (자동으로 다시 UPDATE 하지 않음: 단순하게)
        return new BusinessException(ErrorCode.SOLD_OUT, "방금 자리가 바뀌었습니다. 다시 시도해 주세요.");
    }

    /**
     * ④에서 UNIQUE 위반이 났을 때, 어느 제약인지 보고 이유 고르기
     *   MySQL 오류 문장 예: Duplicate entry '12-1-1' for key 'reservation.uk_active_member'
     *
     *   uk_active_member : 이미 이 회차에 살아 있는 예약이 있음 (R5)
     *   uk_request_key   : 같은 요청 키가 "동시에" 두 번 들어와 둘 다 ①을 통과한 경우 (R6)
     *                      먼저 들어온 쪽이 예약을 만들었으므로, 이쪽은 실패로 끝내고
     *                      화면이 다시 조회하면(①) 먼저 만든 결과를 받는다.
     */
    private ErrorCode reasonOfDuplicate(DataIntegrityViolationException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("uk_request_key")) {
            return ErrorCode.DUPLICATE_REQUEST;
        }
        if (msg.contains("uk_active_member")) {
            return ErrorCode.DUPLICATE_RESERVATION;
        }
        // 예상 못 한 제약 (CHECK 등) → 그대로 던져서 로그로 확인
        log.error("예약 INSERT 실패 (예상 못 한 제약): {}", msg);
        throw e;
    }
}
