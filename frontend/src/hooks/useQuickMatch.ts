import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { quickMatchApi } from "@/services/v2Social";
import { getApiErrorMessage } from "@/services/apiError";
import { localizeErrorMessage } from "@/i18n/errors";

export function useQuickMatch() {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const inFlight = useRef(false);
  const [matching, setMatching] = useState(false);

  const start = async (gameId: string, displayName: string, onEntered?: () => void) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setMatching(true);
    try {
      const result = await quickMatchApi.start(gameId, displayName);
      const notice = result.failure
        ? t("quickMatch.roomKept", { reason: localizeErrorMessage(result.failure, "index.quickMatchFailed") }) : "";
      if (notice) toast.warning(notice);
      else if (!result.autoStarted) toast.info(t("quickMatch.waitForHost"));
      onEntered?.();
      navigate(`/room/${gameId}/${result.roomId}`, { state: notice ? { quickStartNotice: notice } : null });
    } catch (error) {
      toast.error(localizeErrorMessage(getApiErrorMessage(error, ""), "index.quickMatchFailed"));
    } finally {
      inFlight.current = false;
      setMatching(false);
    }
  };
  return { matching, start };
}
