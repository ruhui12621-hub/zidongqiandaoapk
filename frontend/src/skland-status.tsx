import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "./skland-status.css";

declare global {
  interface Window {
    sklandTokenAcquired?: boolean;
    setSklandTokenState?: (acquired: boolean) => void;
    AndroidSkland?: { back: () => void };
  }
}

// Install the native-to-React state endpoint before React mounts, so the
// first status update cannot be lost to a page-load/render timing race.
window.sklandTokenAcquired = false;
window.setSklandTokenState = (acquired: boolean) => {
  window.sklandTokenAcquired = acquired;
  window.dispatchEvent(new CustomEvent("skland-token-state", { detail: acquired }));
};

function SklandStatus() {
  const [acquired, setAcquired] = useState(Boolean(window.sklandTokenAcquired));

  useEffect(() => {
    const update = (event: Event) => {
      setAcquired(Boolean((event as CustomEvent<boolean>).detail));
    };
    window.addEventListener("skland-token-state", update);
    return () => window.removeEventListener("skland-token-state", update);
  }, []);

  return (
    <main className="skland-status" aria-label="森空岛登录状态">
      <div className={`skland-status__label ${acquired ? "is-ready" : ""}`} role="status" aria-live="polite">
        <span className="skland-status__dot" />
        {acquired ? "Token 已获取" : "Token 未获取"}
      </div>
      <button className="skland-status__back" type="button" onClick={() => window.AndroidSkland?.back()}>
        返回
      </button>
    </main>
  );
}

createRoot(document.getElementById("skland-status-root")!).render(<SklandStatus />);
