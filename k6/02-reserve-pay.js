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
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { login, postJson, uuid } from './lib.js';

const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '30s';
const USERS = Number(__ENV.USERS || 500);
// 예약할 회차 목록 (기본: 회차 2 하나 = 지금까지와 같은 측정)
const SCHEDULES = (__ENV.SCHEDULES || '2').split(',').map(Number);
const paid = new Counter('pay_ok');
const fail = new Counter('pay_fail');

export const options = {
  setupTimeout: '300s',
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
  const users = [];
  for (let i = 1; i <= USERS; i++) users.push(login(i));
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
