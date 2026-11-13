package com.kwon.bustour.repository;

import com.kwon.bustour.domain.Tour;
import com.kwon.bustour.domain.TourStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * tour 조회
 *
 * ★ JpaRepository<엔티티, 기본키 타입> 을 상속만 하면
 *   save, findById, findAll, delete … 를 스프링이 구현해 준다 (클래스를 안 만들어도 됨).
 *
 * ★ 메서드 이름으로 쿼리 만들기 (쿼리 메서드)
 *   findByStatusOrderByIdAsc(status)
 *     → SELECT * FROM tour WHERE status = ? ORDER BY id ASC
 *   이름 규칙을 틀리면 앱이 뜰 때 오류가 나서 바로 알 수 있다.
 */
public interface TourRepository extends JpaRepository<Tour, Long> {

    /** 판매 중인 투어 목록 (메인 · 투어 목록) */
    List<Tour> findByStatusOrderByIdAsc(TourStatus status);

    /**
     * 판매 중인 투어 1개 (상세)
     * findById 를 쓰지 않는 이유: 숨김 투어를 주소(/tours/4)로 직접 열어도 404가 나오게
     * Optional : 없을 수도 있다는 표시 → 서비스에서 orElseThrow 로 404 처리
     */
    Optional<Tour> findByIdAndStatus(Long id, TourStatus status);
}
