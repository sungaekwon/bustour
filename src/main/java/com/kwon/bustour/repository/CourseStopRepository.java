package com.kwon.bustour.repository;

import com.kwon.bustour.domain.CourseStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * course_stop 조회
 */
public interface CourseStopRepository extends JpaRepository<CourseStop, Long> {

    /**
     * 여러 투어의 정류장을 "한 번에" 가져오기 (메인 3단 화면용)
     *
     *   Tour_IdIn(ids)            → WHERE tour_id IN (1, 2, 3)
     *   OrderByTour_IdAscSeqAsc   → ORDER BY tour_id, seq
     *
     * ★ 왜 한 번에?
     *   투어마다 따로 부르면 투어 수만큼 SELECT가 늘어난다 (투어 3개 → 3번, 10개 → 10번 = N+1 모양).
     *   IN으로 한 번에 가져와서 자바에서 투어별로 나누면, 투어가 몇 개든 SELECT는 1번.
     *   인덱스 UNIQUE(tour_id, seq) 순서와 정렬이 같아서 정렬도 인덱스로 끝난다.
     */
    List<CourseStop> findByTour_IdInOrderByTour_IdAscSeqAsc(Collection<Long> tourIds);
}
