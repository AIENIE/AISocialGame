import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Loader2, WifiOff } from "lucide-react";

interface ConnectionStatusBarProps {
  connected: boolean;
  showReconnectAction: boolean;
  onReconnect: () => void;
}

export const ConnectionStatusBar = ({ connected, showReconnectAction, onReconnect }: ConnectionStatusBarProps) => {
  const { t } = useTranslation();
  if (connected) {
    return null;
  }

  return (
    <div role="status" className="relative z-[60] flex shrink-0 items-center justify-center gap-3 bg-amber-500 px-4 py-2 text-sm text-white">
      {showReconnectAction ? (
        <>
          <WifiOff className="h-4 w-4" />
          <span>{t("game.conn.disconnected")}</span>
          <Button size="sm" variant="secondary" className="h-6 text-xs" onClick={onReconnect}>
            {t("game.conn.reconnect")}
          </Button>
        </>
      ) : (
        <>
          <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" />
          <span>{t("game.conn.auto")}</span>
        </>
      )}
    </div>
  );
};
