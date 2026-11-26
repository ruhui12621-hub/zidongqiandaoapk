import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { ArrowLeft } from "lucide-react";
import "./login-status.css";

declare global {
  interface Window {
    loginCookieAcquired?: boolean;
    setLoginCookieState?: (acquired: boolean) => void;
    LoginUi?: { back: () => void };
  }
}

window.loginCookieAcquired = false;
window.setLoginCookieState = (acquired: boolean) => {
  window.loginCookieAcquired = acquired;
  window.dispatchEvent(new CustomEvent("login-cookie-state", { detail: acquired }));
};

function LoginStatus() {
  const [acquired, setAcquired] = useState(Boolean(window.loginCookieAcquired));

  useEffect(() => {
    const update = (event: Event) => {
      setAcquired(Boolean((event as CustomEvent<boolean>).detail));
    };
    window.addEventListener("login-cookie-state", update);
    return () => window.removeEventListener("login-cookie-state", update);
  }, []);

  return (
    <main className="login-status" aria-label="登录状态">
      <div className={`login-status__label ${acquired ? "is-ready" : ""}`} role="status" aria-live="polite">
        <span className="login-status__dot" />
        {acquired ? "Cookie 已获取" : "Cookie 未获取"}
      </div>
      <button className="login-status__back" type="button" onClick={() => window.LoginUi?.back()}>
        <ArrowLeft size={16} aria-hidden="true" />
        返回
      </button>
    </main>
  );
}

createRoot(document.getElementById("login-status-root")!).render(<LoginStatus />);
