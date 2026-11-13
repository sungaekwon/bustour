package com.kwon.bustour.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * tour 테이블 (투어 종류)
 *
 * ★ 엔티티 = "테이블 한 행"을 자바 객체로 표현한 것
 *   - 필드 이름 → 컬럼 이름은 스프링 부트가 자동으로 바꿔 준다 (카멜 → 스네이크)
 *       durationMin → duration_min,  imageKey → image_key
 *     그래서 이름이 규칙대로면 @Column(name = ...) 을 안 써도 된다.
 *   - ddl-auto: validate → 앱이 뜰 때 "이 필드에 맞는 컬럼이 테이블에 있나"를 검사한다.
 *     이름이나 타입이 다르면 앱이 시작되지 않는다 (오타를 바로 알 수 있음).
 *
 * ★ 연관관계: Tour 에는 List<CourseStop>, List<TourSchedule> 을 두지 않았다 (단방향)
 *   - 정류장 · 회차는 필요할 때 리포지토리로 따로 조회한다.
 *   - 양방향으로 두면 편해 보이지만, 화면에서 tour.schedules 를 꺼내다가
 *     지연 로딩 오류(open-in-view: false)나 N+1이 생기기 쉽다.
 *
 * ★ setter 없음: 값은 DB(샘플 SQL)에서 넣고, 이 단계에서는 읽기만 한다.
 */
@Entity                                   // JPA가 관리하는 엔티티
@Table(name = "tour")                     // 테이블 이름 (클래스 이름과 같으면 생략 가능하지만 명확하게)
@Getter                                   // Lombok: 모든 필드의 getXxx() 생성 → 서비스에서 DTO로 옮길 때 씀
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// ↑ JPA는 객체를 만들 때 "매개변수 없는 생성자"가 꼭 필요하다.
//   protected로 두면 JPA(같은 패키지·하위 클래스 취급)는 쓸 수 있고, 다른 코드가 new Tour()로 빈 객체를 만드는 건 막는다.
public class Tour {

    @Id                                                   // 기본키 (PRIMARY KEY)
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // AUTO_INCREMENT: 번호는 DB가 매김
    private Long id;

    private String name;

    // DB 타입이 TEXT 라서 명시. 안 쓰면 JPA는 VARCHAR(255)를 기대해서 validate에서 "타입이 다르다" 오류가 날 수 있다.
    @Column(columnDefinition = "TEXT")
    private String description;

    private int price;                    // 1인 가격 (원). NOT NULL 컬럼이라 기본형 int

    private int durationMin;              // → duration_min

    private String imageKey;              // → image_key (S3 키, 13단계에서 사용)

    // EnumType.STRING : DB에 'ON_SALE' 글자로 저장 (ORDINAL은 0, 1 숫자 → enum 순서를 바꾸면 데이터가 틀어짐)
    // @JdbcTypeCode(VARCHAR) : DB 컬럼이 VARCHAR + CHECK 이므로 VARCHAR로 다루라고 알려 줌
    //   (Hibernate 최신 버전은 MySQL에서 enum을 MySQL ENUM 타입으로 기대해서, 없으면 validate 오류가 날 수 있다)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private TourStatus status;

    /** 판매 중인가 (목록 · 상세에 보여도 되나) */
    public boolean isOnSale() {
        return status == TourStatus.ON_SALE;
    }
}
