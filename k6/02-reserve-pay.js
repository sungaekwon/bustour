// =====================================================================
// 02 예약 + 결제 흐름 부하 — 초당 RATE 번 "예약 → 가짜 PG 결제 확정 → 취소(환불)"를 반복
//   회차 2번(5000석), 가짜 PG 승인 300ms (application-k6.yml)
//   실행: k6 run -e RATE=50 -e DURATION=30s k6/02-reserve-pay.js   (RATE 50 → 100 → 200 → 400)
//   ★ 실험 A: -e SCHEDULES=2,3 → 요청을 회차 2 · 3 에 번갈아 보냄 (한 행에 몰리던 락을 두 행으로 나눔)
//     k6 run -e RATE=200 -e DURATION=30s -e SCHEDULES=2,3 k6/02-reserve-pay.js
//     천장(흐름 약 96/s)이 거의 2배로 오르면 → "한 행(핫스팟)의 락 대기"가 병목
//     조금만 오르면 → 행이 아니라 커밋 디스크 기록 · 커넥션 풀처럼 "모두가 같이 쓰는 것"이 병목
//   ★ 1인 1예약 규칙 때문에 마지막에 취소까지 해서 회원을 "비워" 둔다 → 회원 500명을 돌려 써도 다시 예약 가능
//     (회차가 10일 뒤라 출발 3일 전 취소 규칙에 걸리지 않음)
//   보는 것: http_req_duration p95 (reserve / confirm 따로), 초당 처리량, 오류율, dropped_iterations
// =====================================================================
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { loginBatch, postJson, uuid } from './lib.js';   // [추가]2026.11.14권성애 login → loginBatch

// [추가]2026.11.14권성애 — 보류했던 개선 ③: 409 를 "정상 응답"으로 등록
//   k6 는 기본으로 400 이상을 전부 실패(http_req_failed)로 센다
//   그런데 409(SOLD_OUT 매진 · DUPLICATE 중복)는 서버가 규칙대로 정확히 거절한 "정상 업무 응답"
//   → 409 를 빼야 http_req_failed 에 진짜 실패(503 · 500 · 연결 오류)만 남는다
//   ※ 02 에서 409(같은 회원 중복)는 여전히 pay_fail 로 따로 센다 → 숫자가 사라지는 게 아니라 "서버 오류"와 분리될 뿐
//   200 조회 · 201 예약 생성 · 302 로그인 성공(리다이렉트) · 409 업무 거절
http.setResponseCallback(http.expectedStatuses(200, 201, 302, 409));

const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '30s';
const USERS = Number(__ENV.USERS || 500);
// 예약할 회차 목록 (기본: 회차 2 하나 = 지금까지와 같은 측정)
const SCHEDULES = (__ENV.SCHEDULES || '2').split(',').map(Number);
const paid = new Counter('pay_ok');
const fail = new Counter('pay_fail');

export const options = {
  setupTimeout: '300s',
  // [추가]2026.11.14권성애 — http.batch 를 한 번에 몇 개까지 동시에 보낼지 (기본 batch 20 · 같은 주소 6개)
  //   loginBatch 가 50명씩 보내므로 50 으로 올림 (lib.js 의 LOGIN_BATCH 와 같게)
  batch: 50,
  batchPerHost: 50,
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',         // 응답이 느려져도 초당 RATE 번 "시작" (서버 한계가 보임)
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 50, maxVUs: 500,
    },
  },
  thresholds: {
    'http_req_duration{name:reserve}': ['p(95)<500'],
    'http_req_duration{name:confirm}': ['p(95)<1000'],   // 가짜 PG 300ms 포함
    'http_req_duration{name:cancel}': ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  // [추가]2026.11.14권성애 — 한 명씩 → 50명씩 묶음 로그인
  // const users = [];
  // for (let i = 1; i <= USERS; i++) users.push(login(i));
  const users = loginBatch(1, USERS);
  return { users };
}

export default function (data) {
  // 전체 반복 번호로 회원을 돌려 씀 (같은 회원이 두 번 오면 409 DUPLICATE_RESERVATION → 실패로 셈)
  const n = exec.scenario.iterationInTest;
  const user = data.users[n % data.users.length];

  const r = postJson(user, '/api/reservations',
    { scheduleId: SCHEDULES[n % SCHEDULES.length], headcount: 1, requestKey: uuid() }, { name: 'reserve' });
  if (r.status !== 201 && r.status !== 200) { fail.add(1); return; }
  const id = r.json('data.reservationId');
  const amount = r.json('data.amount');

  const c = postJson(user, '/api/payments/confirm',
    { reservationId: id, paymentKey: 'k6_' + uuid().replace(/-/g, ''), amount }, { name: 'confirm' });
  if (!check(c, { '결제 200': (x) => x.status === 200 })) { fail.add(1); return; }
  paid.add(1);

  const x = postJson(user, `/api/reservations/${id}/cancel`, {}, { name: 'cancel' });
  check(x, { '취소 200': (y) => y.status === 200 });
}
