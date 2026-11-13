package com.kwon.bustour.repository;

import com.kwon.bustour.domain.PaymentLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * payment_log 저장 (7단계) — 추가만 한다. 저장은 PaymentLogService(REQUIRES_NEW)를 거친다.
 */
public interface PaymentLogRepository extends JpaRepository<PaymentLog, Long> {
}
