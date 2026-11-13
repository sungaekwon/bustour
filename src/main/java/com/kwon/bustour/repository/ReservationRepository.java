package com.kwon.bustour.repository;

import com.kwon.bustour.domain.Reservation;
import com.kwon.bustour.domain.ReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * reservation 조회 · 저장
 *
 * ★ 7단계: 상태 변경은 모두 "조건부 UPDATE" — WHERE 에 "지금 이 상태일 때만"을 넣는다
 *   확정 · 만료 · 포기 · 취소가 동시에 와도 같은 행에 X 락이 걸려 줄을 서고,
 *   먼저 끝난 쪽이 상태를 바꾸면 나중 쪽은 조건이 안 맞아 0행 → "한쪽만 성공" (규칙 R11 · R12)
 *
 *   PENDING ──확정(confirmIfPayable)──→ CONFIRMED ──취소(cancelIfConfirmed)──→ CANCELED
 *      ├──만료(expireIfPending)──→ EXPIRED
 *      └──포기(abandonIfPending)──→ CANCELED
 *
 * ★ 조건부 UPDATE 들에 clearAutomatically 를 붙이지 않은 이유
 *   서비스는 UPDATE 뒤에 그 예약 엔티티의 status 를 다시 읽어서 판단하지 않는다 (바뀐 행 수 0/1 로만 판단).
 *   화면에 보여 줄 최신 상태가 필요하면 새 트랜잭션에서 다시 조회한다.
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 멱등성: 이 요청 키로 이미 만든 예약이 있나 (UNIQUE 인덱스 uk_request_key) */
    Optional<Reservation> findByRequestKey(String requestKey);

    /**
     * 내 예약 목록 (최신순) — 회차 · 투어까지 한 번에 (6단계)
     *
     *   SELECT r.*, s.*, t.* FROM reservation r
     *     JOIN tour_schedule s ON s.id = r.schedule_id
     *     JOIN tour t          ON t.id = s.tour_id
     *    WHERE r.member_id = ?            ← ★ 규칙 R19: 내 것만 (회원 번호는 로그인 정보에서)
     *    ORDER BY r.created_at DESC       ← 인덱스 idx_member_created(member_id, created_at) 를 탐
     *
     * ★ JOIN FETCH : 예약마다 회차 · 투어를 따로 SELECT 하지 않고 한 번에 (N+1 방지)
     *   그냥 JOIN 이면 조건에만 쓰이고, 나중에 r.getSchedule().getTour() 할 때 또 SELECT 가 나간다.
     */
    @Query("""
           SELECT r FROM Reservation r
             JOIN FETCH r.schedule s
             JOIN FETCH s.tour
            WHERE r.member.id = :memberId
            ORDER BY r.createdAt DESC
           """)
    List<Reservation> findMyReservations(@Param("memberId") Long memberId);

    /**
     * 내 예약 1건 (회차 · 투어까지) — 결제 화면 · 결제 확정 · 취소에서 사용 (7단계)
     *
     * ★ 규칙 R19: id 만으로 찾지 않고 member_id 까지 조건에 넣는다
     *   남의 예약 번호를 주소에 넣어도 "없음(404)"이 된다 → 남의 예약이 있는지조차 알려 주지 않음
     */
    @Query("""
           SELECT r FROM Reservation r
             JOIN FETCH r.schedule s
             JOIN FETCH s.tour
            WHERE r.id = :id AND r.member.id = :memberId
           """)
    Optional<Reservation> findMine(@Param("id") Long reservationId, @Param("memberId") Long memberId);

    /**
     * 만료 대상 id 목록 (스케줄러) — 인덱스 idx_status_expires(status, expires_at) 를 탐
     *   SELECT id FROM reservation WHERE status = 'PENDING' AND expires_at <= ? ORDER BY expires_at LIMIT 100
     * ★ 엔티티가 아니라 id 만: 한 건씩 "새 트랜잭션"에서 처리하므로 여기서 객체를 들고 있을 필요가 없다
     * ★ Pageable 로 LIMIT: 밀린 만료가 많아도 한 번에 100건씩 (트랜잭션 · 메모리를 작게)
     */
    @Query("""
           SELECT r.id FROM Reservation r
            WHERE r.status = :pending AND r.expiresAt <= :now
            ORDER BY r.expiresAt
           """)
    List<Long> findExpiredPendingIds(@Param("pending") ReservationStatus pending,
                                     @Param("now") LocalDateTime now,
                                     Pageable limit);

    /**
     * ★ 결제 확정: PENDING 이고 아직 만료 전일 때만 → CONFIRMED (규칙 R11)
     *
     *   UPDATE reservation SET status = 'CONFIRMED', confirmed_at = ?
     *    WHERE id = ? AND member_id = ? AND status = 'PENDING' AND expires_at > ?
     *
     *   expires_at > :now 가 핵심: 스케줄러가 아직 안 돌아서 PENDING 으로 남아 있어도, 시간이 지났으면 확정 안 됨
     *   → 0행이면 서비스가 보상(PG 취소)을 한다
     */
    @Modifying
    @Query("""
           UPDATE Reservation r
              SET r.status = :confirmed, r.confirmedAt = :now
            WHERE r.id = :id AND r.member.id = :memberId
              AND r.status = :pending AND r.expiresAt > :now
           """)
    int confirmIfPayable(@Param("id") Long reservationId, @Param("memberId") Long memberId,
                         @Param("now") LocalDateTime now,
                         @Param("pending") ReservationStatus pending,
                         @Param("confirmed") ReservationStatus confirmed);

    /**
     * 만료: PENDING 이고 만료 시각이 지났을 때만 → EXPIRED (스케줄러, 규칙 R10)
     *   그 사이 결제가 확정됐으면 status 가 CONFIRMED 라서 0행 → 만료하지 않음 (규칙 R11)
     */
    @Modifying
    @Query("""
           UPDATE Reservation r
              SET r.status = :expired
            WHERE r.id = :id AND r.status = :pending AND r.expiresAt <= :now
           """)
    int expireIfPending(@Param("id") Long reservationId, @Param("now") LocalDateTime now,
                        @Param("pending") ReservationStatus pending,
                        @Param("expired") ReservationStatus expired);

    /**
     * 포기하기: 내 예약이고 PENDING 일 때만 → CANCELED (결제 전에 자리를 돌려줌)
     */
    @Modifying
    @Query("""
           UPDATE Reservation r
              SET r.status = :canceled, r.canceledAt = :now
            WHERE r.id = :id AND r.member.id = :memberId AND r.status = :pending
           """)
    int abandonIfPending(@Param("id") Long reservationId, @Param("memberId") Long memberId,
                         @Param("now") LocalDateTime now,
                         @Param("pending") ReservationStatus pending,
                         @Param("canceled") ReservationStatus canceled);

    /**
     * 취소(환불): 내 예약이고 CONFIRMED 일 때만 → CANCELED (규칙 R12: 취소는 한 번만)
     *   두 번 눌러도 두 번째는 이미 CANCELED 라서 0행 → 409 ALREADY_CANCELED, 자리도 두 번 돌아오지 않음
     */
    @Modifying
    @Query("""
           UPDATE Reservation r
              SET r.status = :canceled, r.canceledAt = :now
            WHERE r.id = :id AND r.member.id = :memberId AND r.status = :confirmed
           """)
    int cancelIfConfirmed(@Param("id") Long reservationId, @Param("memberId") Long memberId,
                          @Param("now") LocalDateTime now,
                          @Param("confirmed") ReservationStatus confirmed,
                          @Param("canceled") ReservationStatus canceled);
}
