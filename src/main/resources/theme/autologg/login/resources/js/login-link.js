// Waiting page of the e-mail login link (login-link-sent.ftl).
//
// The e-mailed link may be opened in another browser or on another device, so
// this page asks the server whether it was confirmed and then finishes the
// login here, in the window (or app sheet) that started it. The code field
// submits on its own once all digits are in, e.g. after autofill from Mail.

const root = document.getElementById("al-login-link");

if (root) {
  const statusUrl = root.dataset.statusUrl;
  const codeLength = Number(root.dataset.codeLength || 6);
  const expiresAt = Date.now() + Number(root.dataset.expiresIn || 0) * 1000;
  const status = document.getElementById("al-login-link-status");
  const checkForm = document.getElementById("kc-login-link-check-form");
  const codeForm = document.getElementById("kc-login-link-code-form");
  const codeInput = document.getElementById("loginLinkCode");
  const resend = document.getElementById("kc-login-link-resend");

  let leaving = false;
  let timer;

  const submit = (form) => {
    if (leaving || !form) {
      return;
    }
    leaving = true;
    clearTimeout(timer);
    if (form.requestSubmit) {
      form.requestSubmit();
    } else {
      form.submit();
    }
  };

  const showState = (state) => {
    if (!status || status.dataset.state === state) {
      return;
    }
    status.dataset.state = state;
    if (state === "expired" || state === "denied") {
      // Number and code are of no use any more; resend stays.
      root.classList.add("al-ended");
    }
    const text = status.dataset["text" + state.charAt(0).toUpperCase() + state.slice(1)];
    const label = status.querySelector(".al-status-text");
    if (text && label) {
      label.textContent = text;
    }
  };

  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(poll, document.hidden ? 8000 : 3000);
  };

  async function poll() {
    if (leaving) {
      return;
    }
    if (Date.now() > expiresAt) {
      showState("expired");
      return;
    }
    try {
      const response = await fetch(statusUrl, {
        credentials: "same-origin",
        cache: "no-store",
        headers: { Accept: "application/json" },
      });
      if (response.ok) {
        const { state } = await response.json();
        if (state === "confirmed") {
          showState("confirmed");
          submit(checkForm);
          return;
        }
        if (state === "expired" || state === "denied") {
          showState(state);
          return;
        }
        if (state === "gone") {
          // The login was finished or restarted elsewhere; nothing to wait for.
          return;
        }
      }
    } catch (e) {
      // Offline or a hiccup on the way: just ask again.
    }
    schedule();
  }

  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && !leaving && status && status.dataset.state === "waiting") {
      // Back from the mail app: ask right away instead of waiting for the timer.
      clearTimeout(timer);
      poll();
    }
  });

  if (codeForm) {
    codeForm.addEventListener("submit", () => {
      leaving = true;
      clearTimeout(timer);
    });
  }

  if (codeInput && codeForm) {
    codeInput.addEventListener("input", () => {
      const digits = codeInput.value.replace(/\D/g, "");
      if (digits.length === codeLength) {
        submit(codeForm);
      }
    });
  }

  if (resend) {
    let wait = Number(resend.dataset.wait || 0);
    const label = resend.dataset.label || resend.textContent;
    const labelWait = resend.dataset.labelWait || label;
    const tick = () => {
      if (wait > 0) {
        resend.disabled = true;
        resend.textContent = labelWait.replace("{0}", Math.floor(wait / 60) + ":" + String(wait % 60).padStart(2, "0"));
        wait -= 1;
        setTimeout(tick, 1000);
      } else {
        resend.disabled = false;
        resend.textContent = label;
      }
    };
    tick();
  }

  schedule();
}
