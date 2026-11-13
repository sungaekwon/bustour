/* =====================================================================
   my-reservations.js : 내 예약 화면의 "남은 결제 시간" 줄이기
   - <span class="countdown" data-remain="534"> 의 534(초)를 1초마다 다시 계산해서 "08:54" 로 보여 줌
   - 0이 되면 "시간 지남"으로 바꾸고, 3초 뒤 화면을 새로고침 → 서버가 계산한 최신 상태를 다시 보여 줌

   ★ 6단계 버그 수정: "1초마다 1씩 빼기" → "마감 시각까지 남은 시간을 매번 다시 계산"
     [버그] 탭이 뒤에 있거나 창을 내리면 브라우저가 setInterval 을 늦춘다 (크롬: 최대 1분에 1번)
            → "1씩 빼기"는 실행된 횟수만큼만 줄어서, 화면 숫자가 실제보다 느리게 줄어든다
            → 화면엔 4분 남았는데 실제로는 이미 만료 → 새로고침하면 둘 다 "결제 시간 지남"
     [해결] 처음에 마감 시각(deadline)을 한 번 정해 두고, 매번 "deadline - 지금"을 계산한다
            → 타이머가 몇 번 건너뛰어도, 다음에 실행되는 순간 정확한 값이 나온다

   ★ PC 시계가 틀려도 괜찮은 이유
     deadline = (PC 시계 지금) + (서버가 계산한 남은 초)
     remain   = deadline - (PC 시계 지금)
     → PC 시계가 5분 틀려 있어도 두 번 다 같은 시계라 "차이"는 정확하다 (시각이 아니라 흐른 시간만 사용)

   ★ 이건 "보여 주기"일 뿐. 진짜 만료 처리는 서버의 스케줄러와 결제 확정 조건(expires_at > 지금)이 한다 (7단계)

   ★ (7단계) 버튼 동작: 포기하기 · 취소하기 (아래 "버튼" 부분)
     결제 화면(payment/checkout.html)도 남은 시간 표시에 이 파일을 함께 쓴다 (버튼이 없으면 그 부분은 아무 일도 안 함)
   ===================================================================== */

// 초 → "mm:ss"   예) 534 → "08:54"
function formatRemain(sec) {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  // padStart(2, '0') : 한 자리면 앞에 0 붙이기 (8 → "08")
  return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
}

// 화면의 카운트다운 하나하나를 { el, deadline } 으로 기억
// Date.now() = 1970년부터 지난 밀리초(ms). 1초 = 1000ms
const items = [];
document.querySelectorAll('.countdown').forEach(function (el) {
  const remainSec = Number(el.dataset.remain);        // data-remain="534" → 534
  items.push({
    el: el,
    deadline: Date.now() + remainSec * 1000,          // ★ 마감 시각은 처음에 한 번만 정함
    done: false                                       // 이미 "시간 지남" 처리했는지
  });
});

let reloadReserved = false;   // 새로고침을 이미 예약했는지 (여러 개가 동시에 끝나도 1번만)

// 모든 카운트다운을 "지금 시각" 기준으로 다시 그림
function render() {
  const now = Date.now();
  items.forEach(function (item) {
    if (item.done) return;

    // Math.ceil : 올림 (0.3초 남았으면 1초로 보여 줌 → 0:00 이 되는 순간 = 진짜 끝)
    const remain = Math.ceil((item.deadline - now) / 1000);

    if (remain <= 0) {
      item.done = true;
      item.el.textContent = '시간 지남';
      // 3초 뒤 새로고침 → 서버가 "결제 시간 지남"으로 다시 그려 줌
      //   (서버도 행마다 자기 expires_at 으로 판단 → 아직 남은 예약은 그대로 남은 시간이 나옴)
      if (!reloadReserved) {
        reloadReserved = true;
        setTimeout(function () { location.reload(); }, 3000);
      }
      return;
    }
    item.el.textContent = formatRemain(remain);
  });
}

if (items.length > 0) {
  render();                          // 처음 한 번 바로 그림 (1초 기다리지 않게)
  setInterval(render, 1000);         // 1초마다 다시 계산 (늦게 불려도 값은 정확)

  // 다른 탭에 있다가 돌아오는 순간 바로 다시 계산
  //   visibilitychange : 탭이 보이게/안 보이게 바뀔 때 발생. document.hidden=false → 지금 보이는 상태
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) render();
  });
}


/* ---------- (7단계) 버튼: 포기하기 · 취소하기 ---------- */
// CSRF 토큰 (base.html 의 meta). POST 는 이 헤더가 없으면 시큐리티가 403 으로 거절
const myCsrfToken = document.querySelector('meta[name="_csrf"]').content;
const myCsrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
const myMessage = document.getElementById('my-message');   // 내 예약 화면에만 있음 (결제 화면엔 없음 → null)

// data-action 이 붙은 버튼들 (내 예약 화면에만 있음)
document.querySelectorAll('[data-action]').forEach(function (btn) {
  btn.addEventListener('click', async function () {
    const id = btn.dataset.id;
    const action = btn.dataset.action;                       // "abandon" 또는 "cancel"

    // 한 번 더 묻기 (되돌릴 수 없는 일이라서)
    const question = action === 'abandon'
      ? '이 예약을 포기할까요? 잡아 둔 자리는 다른 분께 돌아갑니다.'
      : '이 예약을 취소할까요? 결제 금액 ' + Number(btn.dataset.amount).toLocaleString('ko-KR') + '원이 전액 환불됩니다.';
    if (!window.confirm(question)) {
      return;
    }

    btn.disabled = true;                                     // 두 번 누르기 방지 (서버도 조건부 UPDATE 로 한 번만 처리)
    try {
      const resp = await fetch('/api/reservations/' + id + '/' + action, {
        method: 'POST',
        headers: { [myCsrfHeader]: myCsrfToken }             // 본문이 없어서 Content-Type 은 필요 없음
      });
      if (resp.status === 401) {                             // 로그인이 풀림
        location.href = '/login';
        return;
      }
      const json = await resp.json();
      if (json.success) {
        // 새로고침 + 안내 문장 (?abandoned / ?canceled → my.html 의 th:if)
        location.href = '/my/reservations?' + (action === 'abandon' ? 'abandoned' : 'canceled');
        return;
      }
      // 실패: 서버 문장 보여 주기 (이미 결제됨 · 취소 마감 · 환불 실패 …)
      showMyMessage(json.message);
      // 상태가 바뀐 경우가 많으므로(이미 결제 · 만료) 다시 누를 수 있게 하지 않고 새로고침을 권함
      if (json.code === 'REFUND_FAILED' || json.code === 'SERVER_BUSY' || json.code === 'CANCEL_RETRY_NEEDED') {
        btn.disabled = false;                                // 이 셋은 다시 누르면 될 수 있음 (CANCEL_RETRY_NEEDED = 환불은 됨, 취소만 다시)
      }
    } catch (e) {
      console.error('[내 예약] 요청 실패', e);
      showMyMessage('서버에 연결하지 못했습니다. 잠시 후 다시 눌러 주세요.');
      btn.disabled = false;
    }
  });
});

function showMyMessage(text) {
  if (!myMessage) return;
  myMessage.textContent = text;
  myMessage.hidden = false;
  window.scrollTo({ top: 0, behavior: 'smooth' });          // 표 아래에서 눌러도 문장이 보이게 위로
}
