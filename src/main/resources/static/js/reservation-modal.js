/* =====================================================================
   reservation-modal.js : 메인 화면의 예약 모달
   위치: src/main/resources/static/js/reservation-modal.js → 주소 /js/reservation-modal.js

   하는 일
     1. 시간 버튼(.time-btn)을 누르면 → 버튼의 data-* 값으로 모달을 채우고 연다
     2. 인원을 바꾸면 → 합계를 다시 계산
     3. 모달을 열 때마다 요청 키(request_key)를 새로 1번 만든다
     4. (6단계) "예약하고 결제" → POST /api/reservations (fetch + CSRF 헤더)
        → 성공: 결과 화면 + 남은 결제 시간 / 실패: 서버가 준 code 에 따라 안내
   5. (7단계) 결과 화면의 "결제하기" → /payments/{예약번호} 결제 화면

   ★ 화면의 값(남은 자리, 합계)은 "안내용"
     진짜 확인은 서버가 한다 (원자적 UPDATE, 금액 스냅샷, 로그인 확인)
   ===================================================================== */

// 한 번에 예약할 수 있는 최대 인원 (서버의 @Max(10) · DB CHECK 와 같은 값)
const MAX_HEADCOUNT = 10;

// ----- 모달 안의 요소들 (id 로 찾기) -----
const modal = document.getElementById('reserve-modal');
const elFormView = document.getElementById('m-form-view');
const elDoneView = document.getElementById('m-done-view');
const elTour = document.getElementById('m-tour');
const elTime = document.getElementById('m-time');
const elRemaining = document.getElementById('m-remaining');
const elHeadcount = document.getElementById('m-headcount');
const elTotal = document.getElementById('m-total');
const elScheduleId = document.getElementById('m-schedule-id');
const elRequestKey = document.getElementById('m-request-key');
const elSubmit = document.getElementById('m-submit');
const elLogin = document.getElementById('m-login');
const elLoginNote = document.getElementById('m-login-note');
const elError = document.getElementById('m-error');
const elDoneSummary = document.getElementById('m-done-summary');
const elDoneAmount = document.getElementById('m-done-amount');
const elDoneRemain = document.getElementById('m-done-remain');
const elPayLink = document.getElementById('m-pay-link');      // 7단계: 결과 화면의 "결제하기" (결제 화면 주소)

// ----- CSRF 토큰 (base.html 의 <meta name="_csrf">, <meta name="_csrf_header">) -----
// 시큐리티는 POST 요청에 이 토큰이 없으면 403 으로 거절한다 (다른 사이트가 몰래 보내는 요청 막기)
// 폼(th:action)은 자동으로 들어가지만, fetch 는 직접 헤더에 넣어야 한다.
const csrfToken = document.querySelector('meta[name="_csrf"]').content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;   // 보통 "X-CSRF-TOKEN"

// 지금 모달에 열린 회차의 1인 가격 (합계 계산용)
let currentPrice = 0;
// 예약을 한 번이라도 보냈으면 → 모달을 닫을 때 새로고침해서 남은 자리 숫자를 최신으로
let needReload = false;
// 결과 화면의 남은 시간 타이머 (모달을 닫으면 멈춤)
let doneTimer = null;

// 로그인했는지: <dialog data-logged-in="true|false"> (서버가 넣어 줌)
const loggedIn = modal.dataset.loggedIn === 'true';
elSubmit.hidden = !loggedIn;
elLogin.hidden = loggedIn;
elLoginNote.hidden = loggedIn;


/* ---------- 1. 시간 버튼 → 모달 열기 ---------- */
document.querySelectorAll('.time-btn').forEach(function (btn) {
  btn.addEventListener('click', function () {
    // data-schedule-id="3" → btn.dataset.scheduleId (하이픈-이름 → 카멜이름)
    const data = btn.dataset;
    currentPrice = Number(data.price);
    const remaining = Number(data.remaining);

    elTour.textContent = data.tourName;
    elTime.textContent = data.startText + ' 출발';
    elRemaining.textContent = remaining;
    elScheduleId.value = data.scheduleId;

    fillHeadcountOptions(Math.min(remaining, MAX_HEADCOUNT));
    updateTotal();

    // 모달 색 = 그 투어 칸 색
    modal.className = 'modal ' + data.theme;

    // 입력 화면으로 초기화 (이전에 열었던 결과 · 오류 지우기)
    showFormView();
    hideError();

    // ★ 요청 키: 모달을 열 때 1번 만든다
    //   이 모달 안에서 몇 번을 다시 눌러도(더블클릭, 503 후 재시도) 같은 키 → 서버는 예약 1건만
    //   닫고 다시 열면 = 새 예약 시도 → 새 키
    elRequestKey.value = createRequestKey();

    modal.showModal();
  });
});


/* ---------- 2. 인원 → 합계 ---------- */
elHeadcount.addEventListener('change', updateTotal);

function fillHeadcountOptions(max) {
  elHeadcount.innerHTML = '';
  for (let i = 1; i <= max; i++) {
    const opt = document.createElement('option');
    opt.value = i;
    opt.textContent = i + '명';
    elHeadcount.appendChild(opt);
  }
}

function updateTotal() {
  elTotal.textContent = won(currentPrice * Number(elHeadcount.value));
}


/* ---------- 3. 예약하고 결제 → 서버로 보내기 (6단계) ---------- */
elSubmit.addEventListener('click', async function () {
  hideError();
  // ★ 보내는 동안 버튼을 막아 둠 (더블클릭 1차 방어. 2차 방어는 같은 요청 키 → 서버 멱등성)
  setLoading(true);
  needReload = true;

  try {
    // fetch(주소, 설정) : 자바스크립트로 서버에 요청 보내기 (jQuery 의 $.ajax 와 같은 역할)
    // await : 응답이 올 때까지 기다림 (그동안 화면은 멈추지 않음)
    const resp = await fetch('/api/reservations', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',     // 본문이 JSON 이라고 알림 (@RequestBody 가 읽음)
        [csrfHeader]: csrfToken                 // ★ CSRF 헤더 (대괄호 = 변수 값을 헤더 이름으로)
      },
      // JSON.stringify : 자바스크립트 객체 → JSON 글자
      body: JSON.stringify({
        scheduleId: Number(elScheduleId.value),
        headcount: Number(elHeadcount.value),
        requestKey: elRequestKey.value
      })
    });

    // 로그인이 풀렸으면 (세션 만료) → 로그인 화면으로
    if (resp.status === 401) {
      location.href = '/login';
      return;
    }

    const json = await resp.json();             // 응답 JSON → 자바스크립트 객체

    if (json.success) {
      showDoneView(json.data);                  // 201 새로 예약 / 200 같은 요청의 처음 결과 → 둘 다 성공 화면
      return;
    }

    // ----- 실패: code 별 안내 -----
    switch (json.code) {
      case 'DUPLICATE_RESERVATION':
        // 이미 이 회차에 예약(결제 대기)이 있음 → 내 예약에서 이어서 결제 / 포기하기
        showError(json.message + ' 내 예약에서 이어서 결제하거나 포기할 수 있어요.', '/my/reservations', '내 예약 보기');
        break;
      case 'SERVER_BUSY':
        // 503: 붐빔 → 같은 요청 키 그대로 다시 눌러도 안전 (예약이 2건이 되지 않음)
        showError(json.message);
        setLoading(false);                      // 다시 누를 수 있게
        return;
      default:
        // SOLD_OUT, BOOKING_CLOSED, SCHEDULE_CLOSED, INVALID_INPUT, FORBIDDEN …
        showError(json.message);
    }
    // 같은 내용으로 다시 보내도 결과가 같으므로 버튼은 막아 둠 (인원을 바꾸면 다시 열림)
    elSubmit.disabled = true;
    elSubmit.textContent = '예약하고 결제';

  } catch (e) {
    // 네트워크가 끊김 · 서버가 꺼짐 → 같은 요청 키로 다시 시도 가능
    console.error('[예약 모달] 요청 실패', e);
    showError('서버에 연결하지 못했습니다. 잠시 후 다시 눌러 주세요.');
    setLoading(false);
  }
});

// 인원을 바꾸면 버튼을 다시 열어 줌 (매진 안내 후 인원을 줄여서 다시 시도)
// ★ 인원이 바뀌면 "다른 요청"이므로 요청 키도 새로 (같은 키로 다른 내용을 보내면 서버는 처음 결과를 돌려줌)
elHeadcount.addEventListener('change', function () {
  elSubmit.disabled = false;
  elRequestKey.value = createRequestKey();
  hideError();
});


/* ---------- 4. 모달 닫기 ---------- */
// 배경(어두운 부분)을 누르면 닫기
modal.addEventListener('click', function (e) {
  if (e.target === modal) {
    modal.close();
  }
});

// 닫힐 때(취소 · X · Esc · 배경): 타이머 멈추고, 예약을 보냈었다면 새로고침 → 남은 자리 최신으로
modal.addEventListener('close', function () {
  if (doneTimer) {
    clearInterval(doneTimer);
    doneTimer = null;
  }
  if (needReload) {
    location.reload();
  }
});


/* ---------- 화면 바꾸기 도우미 ---------- */
function showFormView() {
  elFormView.hidden = false;
  elDoneView.hidden = true;
  setLoading(false);
}

function setLoading(loading) {
  elSubmit.disabled = loading;
  elSubmit.textContent = loading ? '처리 중…' : '예약하고 결제';
}

/**
 * 성공 화면: 요약 + 금액 + 남은 결제 시간
 *
 * ★ 6단계 버그 수정 (my-reservations.js 와 같은 방식)
 *   예전: 1초마다 remain -= 1 → 탭이 뒤에 있으면 브라우저가 타이머를 늦춰서 실제보다 느리게 줄어듦
 *   지금: 마감 시각(deadline)을 한 번 정하고, 매번 "deadline - 지금"을 계산 → 늦게 불려도 정확
 */
function showDoneView(data) {
  elFormView.hidden = true;
  elDoneView.hidden = false;
  elDoneSummary.textContent = elTour.textContent + ' · ' + elTime.textContent + ' · ' + data.headcount + '명';
  elDoneAmount.textContent = won(data.amount);
  elPayLink.href = '/payments/' + data.reservationId;   // 7단계: 예약 번호로 결제 화면 주소 만들기

  // 마감 시각 = PC 시계 지금 + 서버가 계산한 남은 초 (PC 시계가 틀려도 "차이"만 쓰므로 정확)
  const deadline = Date.now() + data.remainSeconds * 1000;

  function tick() {
    const remain = Math.ceil((deadline - Date.now()) / 1000);   // 올림: 0:00 이 되는 순간 = 진짜 끝
    if (remain <= 0) {
      clearInterval(doneTimer);
      elDoneRemain.textContent = '시간 지남';
      return;
    }
    elDoneRemain.textContent = mmss(remain);
  }

  doneTimer = setInterval(tick, 1000);  // 모달을 닫으면 기존 코드가 clearInterval(doneTimer) 로 멈춤
  tick();                               // 처음 한 번 바로 그림 (이미 0이면 위 타이머도 바로 멈춤)
}

/** 오류 문장 보여 주기 (linkHref 가 있으면 문장 뒤에 링크도) */
function showError(message, linkHref, linkText) {
  elError.textContent = message;            // textContent: 글자로만 넣음 (서버 문장에 태그가 있어도 실행 안 됨 = XSS 방지)
  if (linkHref) {
    const a = document.createElement('a');
    a.href = linkHref;
    a.textContent = ' ' + linkText;
    elError.appendChild(a);
  }
  elError.hidden = false;
}

function hideError() {
  elError.hidden = true;
  elError.textContent = '';
}

function won(n) {
  return n.toLocaleString('ko-KR') + '원';  // 24000 → "24,000원"
}

function mmss(sec) {
  return String(Math.floor(sec / 60)).padStart(2, '0') + ':' + String(sec % 60).padStart(2, '0');
}


/* ---------- 요청 키 만들기 (UUID 36자) ---------- */
function createRequestKey() {
  // crypto.randomUUID() 는 "안전한 주소"(https 또는 localhost)에서만 있다 → 없으면 직접 만듦
  if (window.crypto && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const b = crypto.getRandomValues(new Uint8Array(16));   // http 에서도 되는 진짜 난수
  b[6] = (b[6] & 0x0f) | 0x40;                            // UUID 버전 4
  b[8] = (b[8] & 0x3f) | 0x80;                            // UUID 변형
  const hex = Array.from(b, function (x) { return x.toString(16).padStart(2, '0'); }).join('');
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-'
       + hex.slice(16, 20) + '-' + hex.slice(20);
}
