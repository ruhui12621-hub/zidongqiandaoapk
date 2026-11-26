import React, { useEffect, useState } from "react";
import CredentialStatusBar from "./CredentialStatusBar";
import { createRoot } from "react-dom/client";
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
    <CredentialStatusBar
      acquired={acquired}
      label={acquired ? "Cookie 已获取" : "Cookie 未获取"}
      ariaLabel="HoYoLAB 登录状态"
      layout="login"
      onBack={() => window.LoginUi?.back()}
    />
  );
}

createRoot(document.getElementById("login-status-root")!).render(<LoginStatus />);
