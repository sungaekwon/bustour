// =====================================================================
// 01 동시 예약 스파이크 — 회원 500명이 정원 100석 회차(1번)를 "동시에" 1명씩 예약
//   기대: 예약 성공 정확히 100, 나머지 400 = SOLD_OUT, 500(서버 오류) 0
//   실행: k6 run k6/01-reserve-spike.js            (VUS 기본 500)
//   끝나고 05_k6_data.sql 5번 점검 쿼리 → 회차 1: remaining 0, 예약인원 100
// =====================================================================
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { loginBatch, postJson, uuid } from './lib.js';   // [추가]2026.11.14권성애 login → loginBatch

// [추가]2026.11.14권성애 — 보류했던 개선 ③: 409 를 "정상 응답"으로 등록
//   k6 는 기본으로 400 이상을 전부 실패(http_req_failed)로 센다
//   그런데 409(SOLD_OUT 매진 · DUPLICATE 중복)는 서버가 규칙대로 정확히 거절한 "정상 업무 응답"
//   → 409 를 빼야 http_req_failed 에 진짜 실패(503 · 500 · 연결 오류)만 남는다
//   200 조회 · 201 예약 생성 · 302 로그인 성공(리다이렉트) · 409 업무 거절
http.setResponseCallback(http.expectedStatuses(200, 201, 302, 409));

const VUS = Number(__ENV.VUS || 500);
const ok = new Counter('reserve_ok');
const soldOut = new Counter('reserve_sold_out');
const other = new Counter('reserve_other');

export const options = {
  setupTimeout: '300s',                          // 500명 로그인에 시간이 걸림
  // [추가]2026.11.14권성애 — http.batch 를 한 번에 몇 개까지 동시에 보낼지 (기본 batch 20 · 같은 주소 6개)
  //   loginBatch 가 50명씩 보내므로 50 으로 올림 (lib.js 의 LOGIN_BATCH 와 같게)
  batch: 50,
  batchPerHost: 50,
  scenarios: {
    spike: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '60s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],              // 응답 자체 실패(연결 · 5xx)는 1% 미만 ([추가] 이제 409 는 빠짐 → 이 기준이 진짜로 맞게 됨)
    reserve_other: ['count==0'],                 // 예상 못 한 결과 0
  },
};

// setup: 테스트 전에 한 번 → 회원 500명 로그인 결과(쿠키 · 토큰) 배열을 모든 VU 에 나눠 줌
export function setup() {
  // [추가]2026.11.14권성애 — 한 명씩 → 50명씩 묶음 로그인
  // const users = [];
  // for (let i = 1; i <= VUS; i++) users.push(login(i));
  const users = loginBatch(1, VUS);
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
