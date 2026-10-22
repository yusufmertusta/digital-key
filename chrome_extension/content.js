(() => {
  if (window.top !== window) return;

  const USER_SELECTOR = [
    'input[type="email"]',
    'input[autocomplete="username"]',
    'input[autocomplete="email"]',
    'input[name="identifier"]',
    'input[name*="user" i]',
    'input[name*="email" i]',
    'input[name*="login" i]',
    'input[id*="user" i]',
    'input[id*="email" i]',
    'input[id*="login" i]',
  ].join(",");

  const isVisible = (el) => {
    if (el.disabled) return false;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    const s = getComputedStyle(el);
    return s.visibility !== "hidden" && s.display !== "none";
  };

  const findPassword = () =>
    Array.from(document.querySelectorAll('input[type="password"]')).find(isVisible);

  const findUser = () =>
    Array.from(document.querySelectorAll(USER_SELECTOR)).find(
      (el) =>
        isVisible(el) &&
        !["password", "hidden", "checkbox", "radio", "submit", "button"].includes(el.type)
    );

  let lastStage = "";
  let lastTime = 0;
  let timer = null;

  function scan() {
    const pass = findPassword();
    const user = findUser();

    let stage = null;
    let target = null;
    if (pass && user) {
      stage = "both";
      target = user;
    } else if (pass) {
      stage = "pass";
      target = pass;
    } else if (user) {
      stage = "user";
      target = user;
    }
    if (!stage) return;

    // Aynı aşamayı kısa sürede tekrar tekrar bildirme.
    const now = Date.now();
    if (stage === lastStage && now - lastTime < 20000) return;
    lastStage = stage;
    lastTime = now;

    // Odak hiçbir alanda değilse, yazılacak alana odaklan (tuşlar odaktaki alana gider).
    const ae = document.activeElement;
    if (!ae || ae === document.body || ae.tagName === "BODY") {
      try {
        target.focus();
      } catch (e) {
        /* yoksay */
      }
    }

    chrome.runtime.sendMessage({ type: "login_form", stage });
  }

  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(scan, 600);
  };

  // Google gibi iki adımlı sayfalarda alanlar sonradan değişir.
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true,
  });
  window.addEventListener("load", schedule);
  schedule();
})();
