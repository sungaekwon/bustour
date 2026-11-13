/* =====================================================================
   payment-checkout.js : 결제 화면의 가짜 결제창 (7단계)
   위치: src/main/resources/static/js/payment-checkout.js

   하는 일
     버튼을 누르면 → 결제 번호(paymentKey)를 만들고 → POST /api/payments/confirm (fetch + CSRF)
     → 성공: 내 예약으로 이동 (?paid) / 실패: 서버 문장 보여 주기

   ★ 결제 번호를 "결제 시도 1번에 1개"로 만드는 이유 (멱등성)
     진짜 PG 는 결제창이 끝날 때 결제 번호를 하나 준다. 같은 결제를 확정 요청만 다시 보내면(네트워크 끊김 · 503)
     같은 번호로 보내야 서버가 "같은 결제"로 알아보고 두 번 확정하지 않는다.
     → 응답을 못 받았거나 붐빔(503)이면 같은 번호로 다시, 카드 거절이면 새 결제창 = 새 번호.

   ★ 금액은 화면의 data-amount 를 보내지만, 서버는 예약 금액과 "비교"만 한다 (믿지 않음)

   ★ (토스) 아래 "토스 결제" 부분: bustour.pg.type=toss 일 때 화면에 #toss-pay 가 생긴다
   ===================================================================== */

const fakePg = document.getElementById('fake-pg');

// 결제할 수 없는 화면(이미 결제 · 만료)에는 가짜 결제창이 없음 → 아무것도 안 함
if (fakePg) {
  const csrfToken = document.querySelector('meta[name="_csrf"]').content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
  const reservationId = Number(fakePg.dataset.reservationId);
  const amount = Number(fakePg.dataset.amount);
  const buttons = fakePg.querySelectorAll('[data-pay]');
  const message = document.getElementById('pay-message');

  // 지금 진행 중인 결제 시도의 결제 번호 (다시 보낼 때 재사용)
  let currentKey = null;

  buttons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      const kind = btn.dataset.pay;                          // approve / decline / tamper
      // 새 결제 시도면 새 번호. 카드 거절 버튼은 가짜 PG 가 거절하도록 앞부분을 다르게
      if (currentKey === null || kind === 'decline') {
        currentKey = (kind === 'decline' ? 'fake_decline_' : 'fake_') + randomId();
      }
      // 금액 조작 테스트: 1,000원 적게 보내 봄 → 서버가 거절해야 정상
      const sendAmount = kind === 'tamper' ? amount - 1000 : amount;
      confirmPayment(currentKey, sendAmount);
    });
  });

  async function confirmPayment(paymentKey, sendAmount) {
    setLoading(true);
    hideMessage();
    try {
      const resp = await fetch('/api/payments/confirm', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          [csrfHeader]: csrfToken
        },
        body: JSON.stringify({ reservationId: reservationId, paymentKey: paymentKey, amount: sendAmount })
      });
      if (resp.status === 401) {
        location.href = '/login';
        return;
      }
      const json = await resp.json();

      if (json.success) {
        // PAID(이번에 확정) · PAID_ALREADY(같은 결제 번호로 이미 확정) → 둘 다 성공
        location.href = '/my/reservations?paid';
        return;
      }

      switch (json.code) {
        case 'PAYMENT_DECLINED':      // 카드 거절 → 돈이 안 나감. 다시 결제하면 새 결제 번호
        case 'AMOUNT_MISMATCH':       // 금액이 다름 → 이 결제 시도는 버림 (새로 결제하면 새 번호)
        case 'PAYMENT_CANCELED_SERVER_BUSY':   // (9단계) 서버가 붐벼 이번 결제는 취소(환불)됨 → 예약은 그대로, 새 번호로 다시
          currentKey = null;
          showMessage(json.message, false);
          setLoading(false);
          return;
        case 'SERVER_BUSY':           // 503 붐빔 · 502 PG 연결 실패 → 같은 결제 번호로 다시 눌러도 안전
        case 'PG_ERROR':
          showMessage(json.message, false);
          setLoading(false);
          return;
        default:
          // 시간 지남 · 이미 결제 · 보상됨(자동 취소) · 보상 실패 … → 이 화면에서 더 할 수 있는 게 없음
          showMessage(json.message + ' (내 예약에서 확인해 주세요)', false);
          // 버튼은 막아 둔 채로
      }
    } catch (e) {
      // 응답을 못 받음 → 서버에선 확정됐을 수도 있음 → 같은 결제 번호로 다시 보내면 서버가 알아봄 (멱등성)
      console.error('[결제] 요청 실패', e);
      showMessage('서버에 연결하지 못했습니다. 같은 버튼을 다시 눌러 주세요. (두 번 결제되지 않습니다)', false);
      setLoading(false);
    }
  }

  function setLoading(on) {
    buttons.forEach(function (b) { b.disabled = on; });
  }

  function showMessage(text, ok) {
    message.textContent = text;
    message.className = 'alert ' + (ok ? 'alert-ok' : 'alert-error');
    message.hidden = false;
  }

  function hideMessage() {
    message.hidden = true;
  }

  // 결제 번호 뒷부분 (영문 · 숫자만 → 서버의 @Pattern 과 맞춤)
  function randomId() {
    if (window.crypto && crypto.randomUUID) {
      return crypto.randomUUID().replace(/-/g, '');
    }
    // http + IP 주소처럼 randomUUID 가 없는 환경 대비
    return Date.now().toString(36) + Math.random().toString(36).slice(2);
  }
}


/* ---------- (토스) 토스 결제창 띄우기 — 결제창(구버전) SDK v2, API 개별 연동 키(test_ck_) ----------
   ① TossPayments(클라이언트 키) → ② payment({customerKey}) → ③ requestPayment({…})
   → 카드 인증이 끝나면 토스가 브라우저를 successUrl 로 보낸다 (?paymentKey=…&orderId=…&amount=…)
   → 서버(PaymentController.tossSuccess)가 금액 · 주문번호를 비교하고 승인 API 를 불러 확정한다.
   ★ 여기서는 승인하지 않는다. 브라우저는 시크릿 키를 모르고, 몰라야 한다.
   ★ 토스 문서는 새 연동에 "결제창형 결제(widgets)"를 권하지만, 그건 결제위젯 연동 키(gck)용이다.
     우리 키(ck/sk)에 맞는 것은 payment() 결제창이다.                                              */
const tossBox = document.getElementById('toss-pay');

if (tossBox) {
  const tossBtn = document.getElementById('toss-pay-btn');
  const tossMsg = document.getElementById('toss-message');

  tossBtn.addEventListener('click', async function () {
    tossBtn.disabled = true;
    tossMsg.hidden = true;
    const id = tossBox.dataset.reservationId;
    try {
      // TossPayments : checkout.html 의 <script src="https://js.tosspayments.com/v2/standard"> 가 만들어 둔 함수
      const tossPayments = TossPayments(tossBox.dataset.clientKey);
      // customerKey : 구매자 구분 값. 비회원처럼 따로 관리하지 않을 때는 ANONYMOUS
      const payment = tossPayments.payment({ customerKey: TossPayments.ANONYMOUS });

      await payment.requestPayment({
        method: 'CARD',                                         // 카드 결제
        amount: { currency: 'KRW', value: Number(tossBox.dataset.amount) },   // 숫자 하나가 아니라 { 통화, 값 }
        orderId: tossBox.dataset.orderId,                       // 서버가 만든 주문번호 (BT12-a1b2c3d4)
        orderName: tossBox.dataset.orderName,                   // 결제창에 보일 이름 ("해운대 야경 투어 2명")
        successUrl: location.origin + '/payments/' + id + '/toss/success',
        failUrl: location.origin + '/payments/' + id + '/toss/fail'
      });
      // 성공하면 브라우저가 successUrl 로 이동하므로 이 아래는 보통 실행되지 않음
    } catch (e) {
      // 결제창을 닫음(USER_CANCEL) · 설정 오류 등 → 다시 누를 수 있게
      console.error('[토스] 결제창 오류', e);
      tossMsg.textContent = e.code === 'USER_CANCEL'
        ? '결제창을 닫았습니다. 결제 시간 안에 다시 결제할 수 있어요.'
        : '결제창을 열지 못했습니다. (' + (e.message || e.code || '알 수 없는 오류') + ')';
      tossMsg.hidden = false;
      tossBtn.disabled = false;
    }
  });
}
