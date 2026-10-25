import { useEffect, useMemo, useState } from "react";
import { Timer } from "lucide-react";

interface CountdownTimerProps {
  phaseEndsAt?: string;
  expiredLabel?: string;
  className?: string;
}

export const CountdownTimer = ({ phaseEndsAt, expiredLabel, className }: CountdownTimerProps) => {
  const [timeLeft, setTimeLeft] = useState(0);

  useEffect(() => {
    if (!phaseEndsAt) {
      setTimeLeft(0);
      return;
    }

    const update = () => {
      const seconds = (new Date(phaseEndsAt).getTime() - Date.now()) / 1000;
      const diff = Math.max(0, expiredLabel ? Math.ceil(seconds) : Math.floor(seconds));
      setTimeLeft(diff);
    };

    update();
    const timer = window.setInterval(update, 1000);
    const expiryTimer = expiredLabel ? window.setTimeout(update, Math.max(0, new Date(phaseEndsAt).getTime() - Date.now())) : undefined;
    return () => { clearInterval(timer); window.clearTimeout(expiryTimer); };
  }, [phaseEndsAt, expiredLabel]);

  const style = useMemo(() => {
    if (timeLeft <= 5) {
      return { color: "text-red-600", bg: "bg-red-50 border-red-200", pulse: "animate-pulse" };
    }
    if (timeLeft <= 10) {
      return { color: "text-amber-600", bg: "bg-amber-50 border-amber-200", pulse: "animate-pulse" };
    }
    return { color: "text-emerald-600", bg: "bg-emerald-50 border-emerald-200", pulse: "" };
  }, [timeLeft]);

  const showExpired = !!expiredLabel && !!phaseEndsAt && Date.now() >= new Date(phaseEndsAt).getTime();
  if (timeLeft <= 0 && !showExpired) {
    return null;
  }

  return (
    <div className={`inline-flex items-center gap-1 rounded-full border px-3 py-1 text-sm font-mono ${style.color} ${style.bg} ${style.pulse} ${className || ""}`}>
      <Timer className="h-4 w-4" />
      <span>{showExpired ? expiredLabel : `${timeLeft}s`}</span>
    </div>
  );
};
