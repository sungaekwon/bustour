// =====================================================================
// k6 공통: 로그인해서 세션 쿠키 + CSRF 토큰 받기 (setup 에서 회원 수만큼)
//
// ★ 우리 앱은 세션 로그인 + CSRF 라서, API 를 부르려면 두 가지가 필요하다
//   1) JSESSIONID 쿠키 : 로그인한 세션
//   2) X-CSRF-TOKEN    : POST 할 때 헤더 (메인 화면 <meta name="_csrf"> 에서 꺼냄)
// ★ 흐름: GET /login(폼의 _csrf) → POST /login(302) → 새 JSESSIONID(로그인하면 세션 ID 가 바뀜)
//        → GET / (meta 의 새 CSRF 토큰)
// =====================================================================
import http from 'k6/http';

export const BASE = __ENV.BASE || 'http://localhost:8282';
export const PASSWORD = 'test1234!';

// [추가]2026.11.14권성애 — 묶음 로그인 한 번에 몇 명씩 (01 · 02 의 options.batchPerHost 와 같게)
export const LOGIN_BATCH = 50;

export function phoneOf(i) {                       // 1 → 01088880001
  return '0108888' + String(i).padStart(4, '0');
}

export function login(i) {
  const jar = http.cookieJar();
  jar.clear(BASE);                                  // 회원마다 새 세션
  const page = http.get(`${BASE}/login`);
  const formCsrf = (page.body.match(/name="_csrf"\s+value="([^"]+)"/) || [])[1];
  const res = http.post(`${BASE}/login`,
    { phone: phoneOf(i), password: PASSWORD, _csrf: formCsrf },
    { redirects: 0 });
  const sid = res.cookies.JSESSIONID ? res.cookies.JSESSIONID[0].value : null;
  if (res.status !== 302 || !sid) {
    throw new Error(`로그인 실패: 회원 ${i}, status ${res.status}`);
  }
  const home = http.get(`${BASE}/`, { headers: { Cookie: `JSESSIONID=${sid}` } });
  const csrf = (home.body.match(/<meta name="_csrf" content="([^"]+)"/) || [])[1];
  return { sid, csrf };
}

// =====================================================================
// [추가]2026.11.14권성애 — 묶음 로그인 (11단계, 보류했던 속도 개선 ②)
//   login(i) 는 한 명씩 3번 왕복(GET /login → POST /login → GET /) → 500명이면 1500번을 "차례로"
//   loginBatch 는 size 명(기본 50)을 http.batch 로 "동시에" 보낸다 → 3번 왕복 × (500/50 = 10묶음)
//   서버(인터넷 너머)에서는 왕복 시간이 길어서 차이가 특히 크다
//
// ★ 회원마다 쿠키 통(CookieJar)을 따로 쓰는 이유
//   setup 은 VU 하나라 기본 쿠키 통도 하나 → 50명이 같은 통을 쓰면 서로의 JSESSIONID 를 덮어써서
//   "로그인 화면의 CSRF 토큰"과 "보내는 세션"이 어긋난다(403). 그래서 한 명당 new http.CookieJar()
//
// 사용: const users = loginBatch(1, 500);   → [{ sid, csrf }, ...]  (users[0] = 회원 1번)
// =====================================================================
export function loginBatch(from, to, size = LOGIN_BATCH) {
  const users = [];
  for (let start = from; start <= to; start += size) {
    const end = Math.min(start + size - 1, to);
    const ids = [];
    for (let i = start; i <= end; i++) ids.push(i);
    const jars = ids.map(() => new http.CookieJar());          // 회원마다 새 쿠키 통

    // ① 로그인 화면 (폼 안의 _csrf 토큰 + 비로그인 세션 쿠키)
    //   http.batch([ [메서드, 주소, 본문, 옵션], ... ]) → 응답 배열 (보낸 순서 그대로)
    const pages = http.batch(ids.map((i, k) =>
      ['GET', `${BASE}/login`, null, { jar: jars[k], tags: { name: 'setup_login_page' } }]));

    // ② 로그인 (302 + 새 JSESSIONID 가 와야 성공)
    const posts = http.batch(ids.map((i, k) => {
      const formCsrf = (pages[k].body.match(/name="_csrf"\s+value="([^"]+)"/) || [])[1];
      return ['POST', `${BASE}/login`,
        { phone: phoneOf(i), password: PASSWORD, _csrf: formCsrf },   // 객체 = 폼 형식으로 전송
        { jar: jars[k], redirects: 0, tags: { name: 'setup_login' } }];
    }));
    const sids = posts.map((res, k) => {
      const sid = res.cookies.JSESSIONID ? res.cookies.JSESSIONID[0].value : null;
      if (res.status !== 302 || !sid) {
        throw new Error(`로그인 실패: 회원 ${ids[k]}, status ${res.status}`);
      }
      return sid;
    });

    // ③ 메인 화면에서 로그인 후 CSRF 토큰 꺼내기 (<meta name="_csrf">)
    //   쿠키는 헤더로 직접 넣고, 쿠키 통은 빈 새것 → 쿠키가 두 번 붙지 않게
    const homes = http.batch(ids.map((i, k) =>
      ['GET', `${BASE}/`, null,
        { headers: { Cookie: `JSESSIONID=${sids[k]}` }, jar: new http.CookieJar(), tags: { name: 'setup_home' } }]));
    homes.forEach((home, k) => {
      const csrf = (home.body.match(/<meta name="_csrf" content="([^"]+)"/) || [])[1];
      users.push({ sid: sids[k], csrf });
    });
  }
  return users;
}

/** 로그인한 회원으로 JSON POST */
export function postJson(user, path, body, tags) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), {
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': user.csrf,
      Cookie: `JSESSIONID=${user.sid}`,
    },
    tags,
  });
}

export function uuid() {                            // 요청 키 (멱등성)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
