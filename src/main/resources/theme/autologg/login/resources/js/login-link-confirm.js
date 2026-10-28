// Page the e-mailed link opens. Confirming happens in the background, so the tab keeps a single
// history entry: browsers let a page close a tab like that (Chromium since 88, Firefox), and the
// tab disappears once the login is confirmed. Where closing is not allowed (iOS Safari) the page
// says it can be closed. Without JavaScript the form posts as usual.

const form = document.getElementById("kc-login-link-confirm-form");

const showClosingThenClose = () => {
  const hint = document.getElementById("al-confirmed-hint");
  const closingText = hint && hint.dataset.textClosing;
  const defaultText = hint && hint.textContent;
  if (hint && closingText) {
    hint.textContent = closingText;
  }
  setTimeout(() => {
    window.close();
    // Still here: the browser did not let the page close the tab.
    setTimeout(() => {
      if (hint && defaultText) {
        hint.textContent = defaultText;
      }
    }, 400);
  }, 1500);
};

if (form && window.fetch && window.DOMParser) {
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = form.querySelector("button");
    if (button) {
      button.disabled = true;
    }
    let response;
    try {
      response = await fetch(form.action, {
        method: "POST",
        body: new URLSearchParams(new FormData(form)),
        credentials: "same-origin",
        headers: { Accept: "text/html" },
      });
    } catch (e) {
      // Nothing reached the server: post the form the usual way.
      form.submit();
      return;
    }
    const next = new DOMParser().parseFromString(await response.text(), "text/html");
    const card = document.querySelector(".al-card");
    const nextCard = next.querySelector(".al-card");
    if (card && nextCard) {
      card.replaceWith(document.adoptNode(nextCard));
    } else {
      document.body.replaceWith(document.adoptNode(next.body));
    }
    if (next.title) {
      document.title = next.title;
    }
    if (document.querySelector('.al-link-confirm[data-state="done"]')) {
      showClosingThenClose();
    }
  });
}
