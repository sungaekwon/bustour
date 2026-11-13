// =====================================================================
// 01 동시 예약 스파이크 — 회원 500명이 정원 100석 회차(1번)를 "동시에" 1명씩 예약
//   기대: 예약 성공 정확히 100, 나머지 400 = SOLD_OUT, 500(서버 오류) 0
//   실행: k6 run k6/01-reserve-spike.js            (VUS 기본 500)
//   끝나고 05_k6_data.sql 5번 점검 쿼리 → 회차 1: remaining 0, 예약인원 100
// =====================================================================
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { login, postJson, uuid } from './lib.js';

const VUS = Number(__ENV.VUS || 500);
const ok = new Counter('reserve_ok');
const soldOut = new Counter('reserve_sold_out');
const other = new Counter('reserve_other');

export const options = {
  setupTimeout: '300s',                          // 500명 로그인에 시간이 걸림
  scenarios: {
    spike: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '60s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],              // 응답 자체 실패(연결 · 5xx)는 1% 미만
    reserve_other: ['count==0'],                 // 예상 못 한 결과 0
  },
};

// setup: 테스트 전에 한 번 → 회원 500명 로그인 결과(쿠키 · 토큰) 배열을 모든 VU 에 나눠 줌
export function setup() {
  const users = [];
  for (let i = 1; i <= VUS; i++) users.push(login(i));
  return { users };
}

export default function (data) {
  const user = data.users[__VU - 1];            // VU 1번 = 회원 1번
  const res = postJson(user, '/api/reservations',
    { scheduleId: 1, headcount: 1, requestKey: uuid() }, { name: 'reserve' });
  const code = res.json('code');
  if (code === 'RESERVED') ok.add(1);
  else if (code === 'SOLD_OUT') soldOut.add(1);
  else other.add(1);
  check(res, { '201 또는 409': (r) => r.status === 201 || r.status === 409 });
}
