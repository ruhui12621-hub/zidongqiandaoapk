import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "./skland-status.css";

declare global {
  interface Window {
    setSklandTokenState?: (acquired: boolean) => void;
    AndroidSkland?: { back: () => void };
  }
}

function SklandStatus() {
  const [acquired, setAcquired] = useState(false);

  useEffect(() => {
    window.setSklandTokenState = setAcquired;
    return () => { delete window.setSklandTokenState; };
  }, []);

  return (
    <main className="skland-status" aria-label="森空岛登录状态">
      <div className={`skland-status__label ${acquired ? "is-ready" : ""}`} role="status" aria-live="polite">
        <span className="skland-status__dot" />
        {acquired ? "Token已获取" : "Token未获取"}
      </div>
      <button className="skland-status__back" type="button" onClick={() => window.AndroidSkland?.back()}>
        返回
      </button>
    </main>
  );
}

createRoot(document.getElementById("skland-status-root")!).render(<SklandStatus />);
