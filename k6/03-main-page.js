// =====================================================================
// 03 메인 화면 조회 부하 — 캐시(8단계) 효과 확인
//   실행: k6 run -e RATE=200 -e DURATION=30s k6/03-main-page.js
//   보는 것: p95 와, 앱 콘솔의 "[기상청] … 호출" 줄 수 (캐시면 측정 내내 0 ~ 1번)
// =====================================================================
import http from 'k6/http';
import { check } from 'k6';
import { BASE } from './lib.js';

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200), timeUnit: '1s', duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 50, maxVUs: 300,
    },
  },
  thresholds: { http_req_duration: ['p(95)<300'], http_req_failed: ['rate<0.01'] },
};

export default function () {
  const res = http.get(`${BASE}/`);
  check(res, { '200': (r) => r.status === 200 });
}
