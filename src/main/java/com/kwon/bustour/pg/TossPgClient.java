package com.kwon.bustour.pg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * =====================================================================
 * 토스페이먼츠 PG (7단계 마지막) — API 개별 연동 키(test_ck_ / test_sk_) 사용
 *
 * ★ 언제 쓰이나: application.yml 의 bustour.pg.type 이 toss 일 때만 (환경변수 PG_TYPE=toss)
 *   PaymentService 는 PgClient 만 알기 때문에, 가짜 PG → 토스로 바꿔도 서비스 코드는 그대로다.
 *
 * ★ 이 클래스가 부르는 토스 API (모두 서버 → 토스, 시크릿 키 사용)
 *   POST /v1/payments/confirm              결제 승인  {paymentKey, orderId, amount}
 *   GET  /v1/payments/{paymentKey}         결제 조회  (승인됐는지 모를 때 확인)
 *   POST /v1/payments/{paymentKey}/cancel  결제 취소  {cancelReason}  (보상 · 고객 환불)
 *
 * ★ 인증: Authorization: Basic base64("시크릿키:")
 *   토스는 시크릿 키를 "아이디"로 쓰고 비밀번호는 없다 → 끝에 콜론(:)을 붙여 "비밀번호 없음"을 나타낸다.
 *   시크릿 키는 환경변수 TOSS_SECRET_KEY 로만 받는다 (코드 · yml · GitHub 에 쓰지 않음).
 *   클라이언트 키(test_ck_)는 브라우저용이라 이 클래스에서는 쓰지 않는다 (PaymentController → 결제 화면).
 *
 * ★ 멱등성 (PgClient 약속): 같은 결제에 다시 불러도 결과가 같게
 *   승인: ALREADY_PROCESSED_PAYMENT(이미 처리된 결제) → 결제 조회해서 DONE 이면 승인 결과로 돌려줌
 *   취소: ALREADY_CANCELED_PAYMENT(이미 취소된 결제)  → 성공으로 봄
 *
 * ★ "승인됐는지 모름" 처리 (시간 초과 · 연결 끊김)
 *   토스는 승인했는데 응답만 못 받았을 수 있다 → 그냥 실패로 끝내면 "돈은 나갔는데 예약은 PENDING".
 *   → 결제 조회 API 로 한 번 더 확인해서 DONE 이면 승인으로 처리한다.
 *
 * ★ 이 클래스는 트랜잭션 밖에서만 불린다 (규칙 R17) → 응답을 기다리는 동안 DB 커넥션 · 락을 쥐지 않음
 * =====================================================================
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "bustour.pg.type", havingValue = "toss")
public class TossPgClient implements PgClient {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 우리 설정이 잘못된 경우의 오류 코드 → 카드 거절이 아니라 "서버 문제"로 처리 (사용자 탓이 아님) */
    private static final Set<String> CONFIG_ERRORS = Set.of(
            "UNAUTHORIZED_KEY", "INVALID_API_KEY", "FORBIDDEN_REQUEST", "INVALID_REQUEST");

    private final RestClient restClient;

    public TossPgClient(@Value("${bustour.pg.toss.secret-key:}") String secretKey,
                        @Value("${bustour.pg.toss.base-url:https://api.tosspayments.com}") String baseUrl) {

        // 키가 없으면 앱이 아예 뜨지 않게 (결제 순간에 알게 되는 것보다 시작할 때 알아야 고치기 쉬움)
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("bustour.pg.type=toss 인데 환경변수 TOSS_SECRET_KEY 가 없습니다.");
        }
        // ★ 안전장치: 이 프로젝트는 테스트 키만 쓴다. 실수로 라이브 키를 넣으면 진짜 돈이 나가므로 시작을 막는다
        if (!secretKey.startsWith("test_")) {
            throw new IllegalStateException("토스 시크릿 키가 테스트 키(test_)가 아닙니다. 이 프로젝트는 테스트 키만 사용합니다.");
        }

        String basic = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));   // ★ 끝에 콜론

        // 시간 제한: 연결 3초, 응답 30초 (무한 대기 방지. 트랜잭션 밖이라 기다리는 동안 DB 는 막히지 않음)
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));

        // RestClient : 스프링 6.1+ 의 HTTP 호출 도구 (예전 RestTemplate 의 새 버전)
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("[토스 PG] 사용 (테스트 키)");
    }

    // =====================================================================
    // 승인
    // =====================================================================

    @Override
    public PgApproval approve(String paymentKey, String orderId, int amount) {
        try {
            Map<String, Object> res = send(restClient.post()
                    .uri("/v1/payments/confirm")
                    .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount)));
            return toApproval(res);

        } catch (TossApiException e) {
            if ("ALREADY_PROCESSED_PAYMENT".equals(e.code)) {
                // 같은 결제를 다시 승인 요청 (더블클릭 · 재시도) → 조회해서 처음 승인 결과를 돌려줌
                return findApproved(paymentKey);
            }
            if (e.status >= 500 || CONFIG_ERRORS.contains(e.code)) {
                throw new PgException("토스 승인 실패 [" + e.code + "] " + e.getMessage());
            }
            // 그 밖의 4xx = 카드 거절 · 한도 초과 · 결제 정보 불일치 등 → 돈이 안 나감
            throw new PgDeclinedException(e.getMessage());

        } catch (RestClientException e) {
            // 시간 초과 · 연결 끊김 = 승인됐는지 모름 → 조회로 확인
            log.warn("[토스 PG] 승인 응답 없음 → 결제 조회로 확인: paymentKey={}, {}", paymentKey, e.getMessage());
            try {
                return findApproved(paymentKey);
            } catch (RuntimeException queryFail) {
                throw new PgException("토스 연결 실패: " + e.getMessage(), e);
            }
        }
    }

    /** 결제 조회 → 승인 완료(DONE)면 승인 결과, 아니면 실패 */
    private PgApproval findApproved(String paymentKey) {
        Map<String, Object> res;
        try {
            res = send(restClient.get().uri("/v1/payments/{paymentKey}", paymentKey));
        } catch (TossApiException e) {
            throw new PgException("토스 결제 조회 실패 [" + e.code + "] " + e.getMessage());
        }
        String status = String.valueOf(res.get("status"));
        if (!"DONE".equals(status)) {
            throw new PgException("토스 결제가 승인 상태가 아님: " + status);
        }
        return toApproval(res);
    }

    /**
     * 토스 응답(JSON → Map) → 우리 승인 결과
     *   totalAmount : 승인 금액 (숫자 → Number 로 받아 int 로)
     *   approvedAt  : "2026-11-10T14:40:12+09:00" 처럼 시간대가 붙은 시각 → 한국 시각 LocalDateTime 으로
     */
    private PgApproval toApproval(Map<String, Object> res) {
        int total = ((Number) res.get("totalAmount")).intValue();
        Object approvedAt = res.get("approvedAt");
        LocalDateTime at = approvedAt != null
                ? OffsetDateTime.parse(approvedAt.toString()).atZoneSameInstant(SEOUL).toLocalDateTime()
                : LocalDateTime.now(SEOUL);
        return new PgApproval(String.valueOf(res.get("paymentKey")), String.valueOf(res.get("orderId")), total, at);
    }

    // =====================================================================
    // 취소 (보상 · 고객 환불) — 전액
    // =====================================================================

    @Override
    public void cancel(String paymentKey, String reason) {
        // 토스의 취소 사유는 최대 200자
        String cancelReason = reason.length() > 200 ? reason.substring(0, 200) : reason;
        try {
            send(restClient.post()
                    .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .body(Map.of("cancelReason", cancelReason)));
            log.info("[토스 PG] 취소 paymentKey={}, reason={}", paymentKey, cancelReason);
        } catch (TossApiException e) {
            if ("ALREADY_CANCELED_PAYMENT".equals(e.code)) {
                return;                                    // 이미 취소됨 = 원하는 결과 (멱등성)
            }
            throw new PgException("토스 취소 실패 [" + e.code + "] " + e.getMessage());
        } catch (RestClientException e) {
            throw new PgException("토스 연결 실패 (취소): " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // 공통: 보내고 받기
    // =====================================================================

    /**
     * 요청을 보내고 응답 JSON 을 Map 으로
     *   2xx  → Map
     *   4xx/5xx → TossApiException (토스가 준 {"code":"…","message":"…"} 를 담음)
     *   연결 실패 · 시간 초과 → RestClientException (ResourceAccessException) 그대로
     *
     * ★ ParameterizedTypeReference : Map<String, Object> 처럼 "제네릭이 붙은 타입"으로 받을 때 쓰는 도구
     *   (Map.class 로는 안의 타입 정보가 사라지므로)
     */
    private Map<String, Object> send(RestClient.RequestHeadersSpec<?> request) {
        try {
            Map<String, Object> body = request.retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return body != null ? body : Map.of();
        } catch (RestClientResponseException e) {
            // 토스 오류 응답 본문: {"code":"REJECT_CARD_COMPANY","message":"카드사에서 거절했습니다…"}
            Map<?, ?> err = null;
            try {
                err = e.getResponseBodyAs(Map.class);
            } catch (RuntimeException ignore) {
                // 본문이 JSON 이 아님 (게이트웨이 오류 페이지 등)
            }
            String code = err != null ? String.valueOf(err.get("code")) : "UNKNOWN";
            String message = err != null ? String.valueOf(err.get("message")) : e.getStatusText();
            throw new TossApiException(e.getStatusCode().value(), code, message);
        }
    }

    /** 토스가 오류로 응답함 (HTTP 상태 + 토스 오류 코드) — 이 클래스 안에서만 씀 */
    private static class TossApiException extends RuntimeException {
        private final int status;
        private final String code;

        TossApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
