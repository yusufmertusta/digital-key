importScripts("config.js");

// Sayfa adresini içerik betiğinin mesajından değil, tarayıcının doğruladığı sender.url'den alırız;
// böylece sayfa kendi alan adı hakkında yalan söyleyemez.
chrome.runtime.onMessage.addListener((msg, sender) => {
  if (!msg || msg.type !== "login_form" || !sender.url) return;
  if (sender.frameId !== 0) return;
  if (!["user", "pass", "both"].includes(msg.stage)) return;

  let u;
  try {
    u = new URL(sender.url);
  } catch (e) {
    return;
  }
  if (u.protocol !== "https:") return;

  fetch(AGENT_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Token": TOKEN },
    body: JSON.stringify({ host: u.hostname, stage: msg.stage }),
  }).catch(() => {
    /* ajan çalışmıyor olabilir */
  });
});
