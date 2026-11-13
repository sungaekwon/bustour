package com.kwon.bustour.repository;

import com.kwon.bustour.domain.ScheduleStatus;
import com.kwon.bustour.domain.TourSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * tour_schedule 조회 · 자리 차감
 */
public interface TourScheduleRepository extends JpaRepository<TourSchedule, Long> {

    /**
     * 여러 투어의 회차 중 출발 시각이 cutoff 보다 뒤인 것을 "한 번에", 출발 시각 순으로 (메인 화면)
     *   → SELECT … FROM tour_schedule WHERE tour_id IN (1,2,3) AND start_at > ? ORDER BY start_at
     */
    List<TourSchedule> findByTour_IdInAndStartAtAfterOrderByStartAtAsc(Collection<Long> tourIds,
                                                                      LocalDateTime cutoff);

    /**
     * 회차 + 투어를 한 번에 (예약할 때 가격 스냅샷용)
     *   JOIN FETCH : tour 를 같이 가져와서 schedule.getTour().getPrice() 를 해도 SELECT 가 또 나가지 않음
     *   ※ 이건 "잠그지 않는" 보통 SELECT (InnoDB 일관된 읽기) → 락 순서(R21)에 영향 없음
     */
    @Query("SELECT s FROM TourSchedule s JOIN FETCH s.tour WHERE s.id = :id")
    Optional<TourSchedule> findWithTourById(@Param("id") Long id);

    /**
     * ★ 원자적 UPDATE: 자리 차감 (규칙 R1 · R2, 설계 노트 "확인과 차감을 한 문장으로")
     *
     *   UPDATE tour_schedule
     *      SET remaining = remaining - :n
     *    WHERE id = :id
     *      AND remaining >= :n        ← R1 정원 초과 금지
     *      AND status = 'OPEN'        ← R2 운영 중지 아님
     *      AND start_at > :cutoff     ← R2 예약 마감(출발 1시간 전) 전  (:cutoff = Clock 의 지금 + 1시간, R22)
     *
     *   돌려주는 값 = 바뀐 행 수 → 1 이면 성공(자리 잡음), 0 이면 실패(조건 중 하나가 안 맞음)
     *
     * ★ 왜 "읽고 → 확인하고 → 저장" 이 아니라 한 문장인가
     *   SELECT 로 남은 자리를 읽고 자바에서 빼면, 두 요청이 같은 값(예: 1)을 읽고 둘 다 성공하는 문제(갱신 손실)가 생긴다.
     *   UPDATE 한 문장은 그 행에 X 락을 걸고 "최신 값"으로 조건을 다시 확인하므로, 동시에 와도 줄을 서서 한 명씩 처리된다.
     *   두 번째 요청은 첫 번째가 커밋할 때까지 기다렸다가 remaining = 0 을 보고 0행이 된다.
     *
     * ★ @Modifying : SELECT 가 아니라 UPDATE/DELETE 쿼리라는 표시 (없으면 실행 오류)
     *   clearAutomatically 를 쓰지 않은 이유: 이 회차 엔티티를 이 트랜잭션에서 remaining 용도로 다시 읽지 않는다.
     *   (서비스가 미리 읽어 둔 schedule 의 remaining 은 "예전 값"이므로 화면 표시 등에 쓰지 않는다)
     */
    /**
     * ★ 방법 B: 자리 차감(decreaseRemaining)이 0행일 때 "이유"를 알려고 최신 값 읽기
     *
     *   SELECT status, start_at, remaining FROM tour_schedule WHERE id = ? FOR SHARE
     *
     * ★ 왜 FOR SHARE 인가 (질문 PDF: 스냅샷 vs 최신 값)
     *   MySQL 기본(REPEATABLE READ)의 보통 SELECT 는 트랜잭션의 "처음 읽은 시점 스냅샷"을 보여 준다.
     *   그래서 0행 뒤에 그냥 다시 SELECT 하면 옛 값(예: 남은 자리 1)이 나온다.
     *   FOR SHARE / FOR UPDATE 가 붙은 "잠그는 읽기"는 스냅샷이 아니라 "지금 커밋된 최신 값"을 읽는다 (현재 읽기).
     *
     * ★ 락을 기다리지 않나?
     *   바로 앞의 UPDATE 가 0행이어도 InnoDB(REPEATABLE READ)는 id 로 찾은 그 행에 X 락을 걸고 커밋까지 쥐고 있다.
     *   내가 이미 X 락을 쥔 행이라 FOR SHARE(공유 락)는 기다리지 않고 바로 통과한다.
     *   그리고 실패한 요청에서만 실행되므로 성공 요청의 속도에는 영향이 없다.
     *
     * nativeQuery = true : JPQL 이 아니라 MySQL SQL 을 그대로 실행 (FOR SHARE 를 눈에 보이게 쓰려고)
     * AS startAt         : ScheduleLatestView.getStartAt() 과 이름을 맞춤
     */
    @Query(value = """
                   SELECT status AS status, start_at AS startAt, remaining AS remaining
                     FROM tour_schedule
                    WHERE id = :id
                      FOR SHARE
                   """, nativeQuery = true)
    Optional<ScheduleLatestView> findLatestForShare(@Param("id") Long scheduleId);

    @Modifying
    @Query("""
           UPDATE TourSchedule s
              SET s.remaining = s.remaining - :n
            WHERE s.id = :id
              AND s.remaining >= :n
              AND s.status = :open
              AND s.startAt > :cutoff
           """)
    int decreaseRemaining(@Param("id") Long scheduleId,
                          @Param("n") int headcount,
                          @Param("open") ScheduleStatus open,
                          @Param("cutoff") LocalDateTime cutoff);

    /**
     * ★ 자리 돌려주기 (7단계: 만료 · 포기 · 취소)
     *
     *   UPDATE tour_schedule SET remaining = remaining + ? WHERE id = ? AND remaining + ? <= capacity
     *
     *   remaining = remaining + :n : 계산을 DB 가 함 → 동시에 여러 건이 돌려줘도 갱신 손실 없음 (R23)
     *   remaining + :n <= capacity : 정원보다 많아지면 버그 → 0행으로 막고 서비스가 롤백 (CHECK 와 이중)
     *
     * ★ 락 순서 "회차 먼저 → 예약 나중" (설계계획 5쪽 대응 (나))
     *   예약(reserve)이 회차(X) → 예약 INSERT 순서이므로, 만료 · 포기 · 취소도 이 UPDATE 를 먼저 실행한다.
     *   모든 흐름이 같은 순서로 락을 잡으면 서로 반대로 기다리는 데드락(1213)이 생기지 않는다.
     */
    @Modifying
    @Query("""
           UPDATE TourSchedule s
              SET s.remaining = s.remaining + :n
            WHERE s.id = :id
              AND s.remaining + :n <= s.capacity
           """)
    int increaseRemaining(@Param("id") Long scheduleId, @Param("n") int headcount);
}
