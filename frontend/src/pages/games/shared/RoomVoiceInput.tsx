import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { LoaderCircle, Mic, Square, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { gameplayApi } from "@/services/api";
import { getApiErrorCode } from "@/services/apiError";
import { useRoomText, type RoomTextKey } from "./roomText";
import { recordVoice, supportsVoiceRecording } from "./voiceRecording";

type PendingRecording = { abort: AbortController; stop?: () => void };
type VoiceStatus = "idle" | "permission" | "recording" | "transcribing";

function transcriptionError(error: unknown): RoomTextKey {
  switch (getApiErrorCode(error)) {
    case "PHASE_CHANGED": return "phaseChanged";
    case "VOICE_AUDIO_INVALID": return "voiceCaptureFailed";
    case "VOICE_BUSY": return "voiceBusy";
    case "VOICE_TIMEOUT": return "voiceTimeout";
    default:
      return (error as { code?: string })?.code === "ECONNABORTED" ? "voiceTimeout" : "voiceUnavailable";
  }
}

export function RoomVoiceInput({ gameId, roomId, phaseToken, actionKey, endsAt, disabled, onTranscript }: {
  gameId: string;
  roomId: string;
  phaseToken: string;
  actionKey: string;
  endsAt?: string;
  disabled: boolean;
  onTranscript: (text: string) => void;
}) {
  const copy = useRoomText();
  const active = useRef<PendingRecording>();
  const [status, setStatus] = useState<VoiceStatus>("idle");
  const [message, setMessage] = useState<RoomTextKey>();
  const [expiredAt, setExpiredAt] = useState<number>();
  const deadline = endsAt ? new Date(endsAt).getTime() : NaN;
  const currentDeadline = useRef(deadline);
  const expired = Number.isFinite(deadline) && (expiredAt === deadline || Date.now() >= deadline);
  const detach = useCallback(() => {
    const previous = active.current;
    active.current = undefined;
    previous?.abort.abort();
  }, []);
  const cancel = useCallback(() => {
    detach();
    setStatus("idle");
    setMessage(undefined);
  }, [detach]);
  useLayoutEffect(() => detach, [detach]);
  useLayoutEffect(() => {
    if (disabled) cancel();
  }, [disabled, cancel]);
  useLayoutEffect(() => {
    currentDeadline.current = deadline;
    if (!Number.isFinite(deadline)) return;
    const timer = window.setTimeout(() => {
      cancel();
      setExpiredAt(deadline);
    }, Math.max(0, deadline - Date.now()));
    return () => window.clearTimeout(timer);
  }, [deadline, cancel]);
  useEffect(() => {
    const stopIfHidden = () => { if (document.hidden && active.current) cancel(); };
    document.addEventListener("visibilitychange", stopIfHidden);
    window.addEventListener("pagehide", cancel);
    return () => {
      document.removeEventListener("visibilitychange", stopIfHidden);
      window.removeEventListener("pagehide", cancel);
    };
  }, [cancel]);

  const start = async () => {
    if (active.current || disabled || expired || (Number.isFinite(deadline) && Date.now() >= deadline)) return;
    if (!supportsVoiceRecording()) {
      setMessage("voiceUnsupported");
      return;
    }
    const attempt: PendingRecording = { abort: new AbortController() };
    active.current = attempt;
    const isCurrent = () => active.current === attempt;
    const canUseRecording = () => isCurrent() && !(Date.now() >= currentDeadline.current);
    const signal = attempt.abort.signal;
    setMessage(undefined);
    setStatus("permission");
    try {
      const audio = await recordVoice(signal, stop => {
        if (!canUseRecording()) return;
        attempt.stop = stop;
        setStatus("recording");
      });
      if (!canUseRecording()) return;
      if (!audio) {
        setMessage("voiceEmpty");
        return;
      }
      setStatus("transcribing");
      try {
        const { text } = await gameplayApi.transcribe(gameId, roomId, phaseToken, actionKey, audio, signal);
        if (!canUseRecording()) return;
        if (text.trim()) onTranscript(text.trim());
        else setMessage("voiceEmpty");
      } catch (error) {
        if (isCurrent()) setMessage(transcriptionError(error));
      }
    } catch (error) {
      if (!isCurrent()) return;
      const name = (error as { name?: string })?.name;
      setMessage(name === "NotAllowedError" || name === "SecurityError" ? "voicePermissionDenied"
        : name === "NotFoundError" ? "voiceNoMicrophone" : "voiceCaptureFailed");
    } finally {
      if (isCurrent()) {
        detach();
        setStatus("idle");
      }
    }
  };

  const statusText = status === "permission" ? copy("voicePermission")
    : status === "recording" ? copy("voiceRecording") : status === "transcribing" ? copy("voiceTranscribing") : "";
  return <div className="flex min-w-0 flex-wrap items-center gap-1">
    {status === "idle" && <Button type="button" size="icon" variant="outline" className="h-9 w-9 shrink-0"
      disabled={disabled || expired} aria-label={copy("voiceStart")} title={copy("voiceStart")} onClick={() => void start()}>
      <Mic className="h-4 w-4" aria-hidden />
    </Button>}
    {status === "recording" && <Button type="button" size="icon" variant="outline" className="h-9 w-9 shrink-0"
      aria-label={copy("voiceStop")} title={copy("voiceStop")} onClick={() => {
        active.current?.stop?.();
        setStatus("transcribing");
      }}>
      <Square className="h-4 w-4 text-destructive" aria-hidden />
    </Button>}
    {status !== "idle" && <Button type="button" size="icon" variant="ghost" className="h-9 w-9 shrink-0"
      aria-label={copy("voiceCancel")} title={copy("voiceCancel")} onClick={cancel}>
      <X className="h-4 w-4" aria-hidden />
    </Button>}
    {statusText && <span role="status" className="inline-flex items-center gap-1 text-xs text-muted-foreground">
      {status === "transcribing" && <LoaderCircle className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" aria-hidden />}
      {statusText}
    </span>}
    {message && <span role="alert" className="text-xs text-amber-800">{copy(message)}</span>}
  </div>;
}
