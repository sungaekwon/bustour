package com.kwon.bustour.service;

import com.kwon.bustour.domain.PaymentLog;
import com.kwon.bustour.domain.PaymentLogEvent;
import com.kwon.bustour.repository.PaymentLogRepository;
import com.kwon.bustour.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * =====================================================================
 * 결제 기록 남기기 (7단계, 규칙 R18)
 *
 * ★ REQUIRES_NEW = "부른 쪽 트랜잭션과 상관없이 새 트랜잭션을 열고, 끝나면 바로 커밋"
 *   보상 실패 같은 기록은 "그 뒤에 무슨 일이 생겨도" 남아야 한다.
 *   부른 쪽 트랜잭션에 합쳐지면(기본 REQUIRED) 부른 쪽이 롤백될 때 기록도 같이 사라진다.
 *
 *   예) 바깥 트랜잭션 ─────────────── 롤백 ✘
 *         └ REQUIRES_NEW (기록 INSERT) ─ 커밋 ✔  ← 바깥과 별개라 남음
 *
 * ★ 별도 클래스인 이유: 같은 클래스 안에서 this.write() 로 부르면 프록시를 거치지 않아
 *   @Transactional(REQUIRES_NEW) 가 무시된다 (자기 호출 함정).
 *
 * ★ 기록 저장이 실패해도 원래 처리(결제 · 보상)를 망치면 안 된다 → writeSafely 는 예외를 삼키고 로그만
 * =====================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentLogService {

    private final PaymentLogRepository paymentLogRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long reservationId, String paymentKey, PaymentLogEvent event, String message) {
        // getReferenceById: SELECT 없이 id 만 아는 대리 객체 → INSERT 에 reservation_id 만 있으면 됨
        paymentLogRepository.save(PaymentLog.of(reservationRepository.getReferenceById(reservationId),
                paymentKey, event, message, LocalDateTime.now(clock)));
    }
}
