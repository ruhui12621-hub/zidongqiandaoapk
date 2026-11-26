import React, { useEffect, useState } from "react";
import CredentialStatusBar from "./CredentialStatusBar";
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
    <CredentialStatusBar
      acquired={acquired}
      label={acquired ? "Token 已获取" : "Token 未获取"}
      ariaLabel="森空岛登录状态"
      layout="skland"
      onBack={() => window.AndroidSkland?.back()}
    />
  );
}

createRoot(document.getElementById("skland-status-root")!).render(<SklandStatus />);
