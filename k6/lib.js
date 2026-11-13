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
