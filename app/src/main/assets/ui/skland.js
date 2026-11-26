(function () {
  "use strict";
  const byId = id => document.getElementById(id);
  window.updateSklandState = function (raw) {
    let state = raw;
    if (typeof raw === "string") { try { state = JSON.parse(raw); } catch (_) { return; } }
    if (!state || typeof state !== "object") return;
    const acquired = state.tokenSaved === true;
    const status = byId("status");
    if (status) status.textContent = acquired ? "Cookie已获取" : "Cookie未获取";
  };
})();
