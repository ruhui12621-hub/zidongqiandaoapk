import { ArrowLeft } from "lucide-react";

type CredentialStatusBarProps = {
  acquired: boolean;
  label: string;
  ariaLabel: string;
  layout: "login" | "skland";
  onBack: () => void;
};

export default function CredentialStatusBar({
  acquired,
  label,
  ariaLabel,
  layout,
  onBack
}: CredentialStatusBarProps) {
  return (
    <main className={`credential-status-bar credential-status-bar--${layout}`} aria-label={ariaLabel}>
      <div className={`credential-status-bar__label ${acquired ? "is-ready" : ""}`} role="status" aria-live="polite">
        <span className="credential-status-bar__dot" />
        {label}
      </div>
      <button className="credential-status-bar__back" type="button" onClick={onBack}>
        <ArrowLeft size={16} aria-hidden="true" />
        返回
      </button>
    </main>
  );
}
