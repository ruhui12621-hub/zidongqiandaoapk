(function () {
  "use strict";
  const byId = id => document.getElementById(id);

  // The footer exposes only the confirmed token state.
  // A failed/pending check must never be treated as a successful acquisition.
  window.updateSklandState = function (raw) {
    let state = raw;
    if (typeof raw === "string") {
      try { state = JSON.parse(raw); } catch (_) { return; }
    }
    if (!state || typeof state !== "object") return;

    const acquired = state.tokenSaved === true;
    const label = acquired ? "已获取" : "未获取";
    const status = byId("status");
    const pill = byId("pill");

    if (status) {
      if (status.textContent !== label) status.textContent = label;
      status.classList.toggle("ok", acquired);
      status.classList.remove("bad");
    }
    if (pill) {
      if (pill.textContent !== label) pill.textContent = label;
      pill.className = "pill" + (acquired ? " ok" : "");
    }
  };
})();
