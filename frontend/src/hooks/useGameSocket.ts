import { useEffect, useMemo, useRef, useState } from "react";
import { ChatMessage, GameStateEvent, PrivateEvent, SeatEvent } from "@/types";

interface UseGameSocketOptions {
  roomId?: string;
  playerId?: string | null;
  token?: string | null;
  onStateChange?: (event: GameStateEvent) => void;
  onPrivate?: (event: PrivateEvent) => void;
  onSeatChange?: (event: SeatEvent) => void;
  onChat?: (event: ChatMessage) => void;
}

interface ParsedFrame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

const parseJson = <T,>(text: string, fallback: T): T => {
  try {
    return JSON.parse(text) as T;
  } catch {
    return fallback;
  }
};

const buildFrame = (command: string, headers: Record<string, string>, body = "") => {
  const head = Object.entries(headers)
    .map(([k, v]) => `${k}:${v}`)
    .join("\n");
  return `${command}\n${head}\n\n${body}\u0000`;
};

const parseFrames = (payload: string): ParsedFrame[] => {
  return payload
    .split("\u0000")
    .map((segment) => segment.trim())
    .filter(Boolean)
    .map((segment) => {
      const [rawCommand, ...rest] = segment.split("\n");
      const command = rawCommand.trim();
      const emptyLineIndex = rest.findIndex((line) => line.trim() === "");
      const headerLines = emptyLineIndex >= 0 ? rest.slice(0, emptyLineIndex) : rest;
      const bodyLines = emptyLineIndex >= 0 ? rest.slice(emptyLineIndex + 1) : [];
      const headers: Record<string, string> = {};
      headerLines.forEach((line) => {
        const idx = line.indexOf(":");
        if (idx > 0) {
          headers[line.slice(0, idx)] = line.slice(idx + 1);
        }
      });
      return {
        command,
        headers,
        body: bodyLines.join("\n"),
      };
    });
};

export const useGameSocket = ({
  roomId,
  playerId,
  token,
  onStateChange,
  onPrivate,
  onSeatChange,
  onChat,
}: UseGameSocketOptions) => {
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const reconnectNoticeRef = useRef<number | null>(null);
  const disconnectedSinceRef = useRef<number | null>(null);
  const shouldReconnectRef = useRef(true);
  const [connected, setConnected] = useState(false);
  const [showReconnectAction, setShowReconnectAction] = useState(false);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    if (!roomId || !playerId) {
      return;
    }

    const connect = () => {
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      const ws = new WebSocket(`${protocol}://${window.location.host}/ws`);
      wsRef.current = ws;

      ws.onopen = () => {
        setConnected(false);
        ws.send(
          buildFrame("CONNECT", {
            "accept-version": "1.2",
            host: window.location.host,
            Authorization: token ? `Bearer ${token}` : "",
            "X-Room-Id": roomId,
            "heart-beat": "10000,10000",
          })
        );
      };

      ws.onmessage = (event) => {
        const frames = parseFrames(String(event.data || ""));
        frames.forEach((frame) => {
          if (frame.command === "CONNECTED") {
            setConnected(true);
            disconnectedSinceRef.current = null;
            setShowReconnectAction(false);
            if (reconnectNoticeRef.current) {
              clearTimeout(reconnectNoticeRef.current);
              reconnectNoticeRef.current = null;
            }
            ws.send(buildFrame("SUBSCRIBE", { id: `state-${roomId}`, destination: `/topic/room/${roomId}/state` }));
            ws.send(buildFrame("SUBSCRIBE", { id: `seat-${roomId}`, destination: `/topic/room/${roomId}/seat` }));
            ws.send(buildFrame("SUBSCRIBE", { id: `chat-${roomId}`, destination: `/topic/room/${roomId}/chat` }));
            ws.send(buildFrame("SUBSCRIBE", { id: `private-${roomId}`, destination: "/user/queue/private" }));
            return;
          }
          if (frame.command !== "MESSAGE") {
            return;
          }

          const destination = frame.headers.destination || "";
          if (destination.endsWith("/state")) {
            onStateChange?.(parseJson(frame.body, { type: "STATE_SYNC", phase: "", round: 0 }));
            return;
          }
          if (destination.endsWith("/seat")) {
            onSeatChange?.(parseJson(frame.body, { type: "UNKNOWN", seat: null as any }));
            return;
          }
          if (destination.endsWith("/chat")) {
            onChat?.(parseJson(frame.body, { id: "", roomId, senderId: "", senderName: "", type: "TEXT", content: "", timestamp: Date.now() }));
            return;
          }
          if (destination.includes("/queue/private")) {
            onPrivate?.(parseJson(frame.body, { type: "UNKNOWN", payload: {} }));
          }
        });
      };

      ws.onclose = () => {
        setConnected(false);
        if (!disconnectedSinceRef.current) {
          disconnectedSinceRef.current = Date.now();
        }
        if (!reconnectNoticeRef.current) {
          reconnectNoticeRef.current = window.setTimeout(() => {
            if (disconnectedSinceRef.current && Date.now() - disconnectedSinceRef.current >= 30000) {
              setShowReconnectAction(true);
            }
          }, 30000);
        }
        if (!shouldReconnectRef.current) {
          return;
        }
        reconnectTimerRef.current = window.setTimeout(connect, 3000);
      };

      ws.onerror = () => {
        setConnected(false);
      };
    };

    shouldReconnectRef.current = true;
    connect();

    return () => {
      shouldReconnectRef.current = false;
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
      }
      if (reconnectNoticeRef.current) {
        clearTimeout(reconnectNoticeRef.current);
        reconnectNoticeRef.current = null;
      }
      disconnectedSinceRef.current = null;
      setConnected(false);
      setShowReconnectAction(false);
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [roomId, playerId, token, nonce]);

  const sendChat = useMemo(() => {
    return (type: "TEXT" | "EMOJI" | "QUICK_PHRASE", content: string) => {
      const ws = wsRef.current;
      if (!ws || ws.readyState !== WebSocket.OPEN || !connected || !roomId) {
        return false;
      }
      ws.send(
        buildFrame(
          "SEND",
          {
            destination: `/app/room/${roomId}/chat`,
            "content-type": "application/json",
          },
          JSON.stringify({ type, content })
        )
      );
      return true;
    };
  }, [roomId, connected]);

  const reconnect = () => {
    disconnectedSinceRef.current = Date.now();
    setShowReconnectAction(false);
    wsRef.current?.close();
    setNonce((current) => current + 1);
  };

  return {
    connected,
    showReconnectAction,
    reconnect,
    sendChat,
  };
};
