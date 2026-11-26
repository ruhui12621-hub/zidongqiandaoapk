(function () {
  "use strict";
  const byId = id => document.getElementById(id);
  // The compact footer intentionally omits subtitle and login-actions.
  window.updateSklandState = function (raw) {
    let state = raw;
    if (typeof raw === "string") {
      try { state = JSON.parse(raw); } catch (_) { return; }
    }
    if (!state || typeof state !== "object") return;
    const status = byId("status"), hint = byId("hint");
    const pill = byId("pill"), message = byId("message");
    const nextStatus = state.status || "等待检测";
    const checking = /检测中/.test(nextStatus), tokenSaved = !!state.tokenSaved;
    if (status) {
      if (status.textContent !== nextStatus) status.textContent = nextStatus;
      status.classList.toggle("ok", tokenSaved);
      status.classList.toggle("bad", !tokenSaved && /失败|未登录/.test(nextStatus));
    }
    if (hint) {
      const next = tokenSaved ? "Token 已在本机加密保存" : "登录后自动检测并加密保存";
      if (hint.textContent !== next) hint.textContent = next;
    }
    if (pill) {
      const next = tokenSaved ? "已保存" : checking ? "检测中" : "未登录";
      if (pill.textContent !== next) pill.textContent = next;
      const nextClass = "pill" + (tokenSaved ? " ok" : checking ? " warn" : "");
      if (pill.className !== nextClass) pill.className = nextClass;
    }
    if (message) {
      const next = state.message || "暂无操作记录";
      if (message.textContent !== next) message.textContent = next;
    }
  };
})();