package com.kwon.bustour.service;

import com.kwon.bustour.domain.CourseStop;
import com.kwon.bustour.domain.ScheduleStatus;
import com.kwon.bustour.domain.Tour;
import com.kwon.bustour.domain.TourSchedule;
import com.kwon.bustour.domain.TourStatus;
import com.kwon.bustour.repository.CourseStopRepository;
import com.kwon.bustour.repository.TourRepository;
import com.kwon.bustour.repository.TourScheduleRepository;
import com.kwon.bustour.dto.CourseStopView;
import com.kwon.bustour.dto.ScheduleDay;
import com.kwon.bustour.dto.ScheduleView;
import com.kwon.bustour.dto.TourCard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 투어 조회 서비스 (메인 3단 화면)
 *
 * ★ SELECT는 투어 수와 상관없이 3번
 *   ① 판매 중인 투어            : WHERE status = 'ON_SALE'
 *   ② 그 투어들의 정류장 한 번에 : WHERE tour_id IN (…)
 *   ③ 그 투어들의 회차 한 번에   : WHERE tour_id IN (…) AND start_at > ?
 *   → 자바에서 투어 id 로 나눠 담는다 (groupingBy)
 *   (투어마다 따로 조회하면 1 + 투어 수 × 2 번 = 투어가 늘수록 쿼리도 늘어나는 N+1 모양)
 *
 * ★ @Transactional(readOnly = true): 메서드가 끝나면 트랜잭션 · 커넥션도 끝 (open-in-view: false)
 *   → 화면에는 엔티티가 아니라 DTO(TourCard)만 넘긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TourService {

    /** 예약 마감: 출발 1시간 전 (결정 사항, 규칙 R2) */
    private static final long BOOKING_CLOSE_HOURS = 1;
    /** 처음에 바로 보여 줄 날짜 수 (나머지는 "날짜 더 보기") */
    private static final int FIRST_DAYS = 7;
    /** 칸 색 개수 (common.css 의 theme-1 ~ theme-3) */
    private static final int THEME_COUNT = 3;

    private final TourRepository tourRepository;
    private final CourseStopRepository courseStopRepository;
    private final TourScheduleRepository tourScheduleRepository;
    private final Clock clock;                       // 규칙 R22: "지금"은 이 시계로

    /** 메인 화면: 판매 중인 투어 전부 (코스 + 출발 시간까지) */
    public List<TourCard> getMainTours() {
        // ① 판매 중인 투어
        List<Tour> tours = tourRepository.findByStatusOrderByIdAsc(TourStatus.ON_SALE);
        if (tours.isEmpty()) {
            return List.of();                        // 빈 목록 (IN () 은 SQL 오류가 나므로 먼저 확인)
        }
        List<Long> tourIds = tours.stream().map(Tour::getId).toList();

        // ② 정류장 한 번에 → 투어 id 별로 나누기
        //    groupingBy(기준, 담을 Map 종류, 값 모으는 방법)
        //    LinkedHashMap : 넣은 순서(= SQL 정렬 순서)를 지킴 (HashMap은 순서가 섞임)
        //    s.getTour().getId() : LAZY 프록시라도 id는 이미 알고 있어서 tour 테이블 SELECT가 나가지 않음
        Map<Long, List<CourseStopView>> stopsByTour = courseStopRepository
                .findByTour_IdInOrderByTour_IdAscSeqAsc(tourIds)
                .stream()
                .collect(Collectors.groupingBy(
                        s -> s.getTour().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(this::toStopView, Collectors.toList())));

        // ③ 회차 한 번에 (출발 1시간 전까지만 = 예약 마감 전) → 투어 id 별로 나누기
        LocalDateTime cutoff = LocalDateTime.now(clock).plusHours(BOOKING_CLOSE_HOURS);
        Map<Long, List<TourSchedule>> schedulesByTour = tourScheduleRepository
                .findByTour_IdInAndStartAtAfterOrderByStartAtAsc(tourIds, cutoff)
                .stream()
                .collect(Collectors.groupingBy(
                        s -> s.getTour().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        // ④ 투어마다 한 칸(TourCard)으로 조립
        List<TourCard> cards = new ArrayList<>();
        for (int i = 0; i < tours.size(); i++) {
            Tour t = tours.get(i);
            // getOrDefault : 정류장이나 회차가 하나도 없는 투어면 빈 목록
            List<ScheduleDay> days = toDays(schedulesByTour.getOrDefault(t.getId(), List.of()));
            // 처음 7일 / 나머지 로 자르기 (subList: 리스트의 일부 구간)
            int split = Math.min(FIRST_DAYS, days.size());
            cards.add(new TourCard(
                    t.getId(), t.getName(), t.getDescription(), t.getPrice(),
                    toDurationText(t.getDurationMin()),
                    "theme-" + (i % THEME_COUNT + 1),          // 1번째 칸 theme-1, 2번째 theme-2 …
                    stopsByTour.getOrDefault(t.getId(), List.of()),
                    days.subList(0, split),
                    days.subList(split, days.size())));
        }
        return cards;
    }

    /**
     * 예전 주소 /tours/{id} 로 들어왔을 때: 판매 중인 투어인지만 확인
     * @throws TourNotFoundException 없거나 숨김 → 404
     */
    public void checkOnSale(Long tourId) {
        tourRepository.findByIdAndStatus(tourId, TourStatus.ON_SALE)
                .orElseThrow(() -> new TourNotFoundException(tourId));
    }

    /** 회차 목록(출발 시각 순) → 날짜별 묶음 */
    private List<ScheduleDay> toDays(List<TourSchedule> schedules) {
        Map<LocalDate, List<ScheduleView>> byDate = schedules.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getStartAt().toLocalDate(),   // 날짜만 (시간 빼고)
                        LinkedHashMap::new,                  // 날짜 순서 유지
                        Collectors.mapping(this::toScheduleView, Collectors.toList())));
        return byDate.entrySet().stream()
                .map(e -> new ScheduleDay(e.getKey(), e.getValue()))
                .toList();
    }

    private CourseStopView toStopView(CourseStop s) {
        return new CourseStopView(s.getSeq(), s.getPlaceName(), s.getDescription());
    }

    /** 회차 → 시간 버튼 하나. 상태 글자는 여기서 정해서 화면은 보여 주기만 */
    private ScheduleView toScheduleView(TourSchedule s) {
        String statusText;
        if (s.getStatus() == ScheduleStatus.CLOSED) {
            statusText = "운영 중지";
        } else if (s.isSoldOut()) {
            statusText = "매진";
        } else {
            statusText = s.getRemaining() + "석";
        }
        return new ScheduleView(s.getId(), s.getStartAt(), s.getCapacity(),
                s.getRemaining(), statusText, s.isBookable());
    }

    /** 210분 → "약 3시간 30분", 180분 → "약 3시간" */
    private String toDurationText(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? "약 " + h + "시간" : "약 " + h + "시간 " + m + "분";
    }
}
