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
  onConnected?: () => void;
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

export const useGameSocket = (options: UseGameSocketOptions) => {
  const { roomId, playerId, token } = options;
  const callbacks = useRef(options);
  callbacks.current = options;
  const wsRef = useRef<WebSocket | null>(null);
  const [connected, setConnected] = useState(false);
  const [showReconnectAction, setShowReconnectAction] = useState(false);
  const [nonce, setNonce] = useState(0);
  const [online, setOnline] = useState(() => navigator.onLine);

  useEffect(() => {
    const offline = () => { setConnected(false); setOnline(false); };
    const restored = () => setOnline(true);
    window.addEventListener("offline", offline);
    window.addEventListener("online", restored);
    return () => {
      window.removeEventListener("offline", offline);
      window.removeEventListener("online", restored);
    };
  }, []);

  useEffect(() => {
    if (!roomId || !playerId || !token || !online) return;
    let disposed = false;
    let generation = 0;
    let failures = 0;
    let retryTimer: number | undefined;
    let noticeTimer: number | undefined;
    let heartbeatTimer: number | undefined;
    let handshakeTimer: number | undefined;
    let syncTimer: number | undefined;
    const clearConnectionTimers = () => {
      window.clearInterval(heartbeatTimer);
      window.clearTimeout(handshakeTimer);
      window.clearTimeout(syncTimer);
    };

    const connect = () => {
      if (disposed) return;
      const currentGeneration = ++generation;
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      const ws = new WebSocket(`${protocol}://${window.location.host}/ws`);
      wsRef.current = ws;
      let buffer = "";
      let lastReceived = Date.now();
      let lastSent = Date.now();
      let acknowledged = false;
      let synchronized = false;
      const syncNonce = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${currentGeneration}`;
      const current = () => !disposed && currentGeneration === generation && wsRef.current === ws;
      const send = (frame: string) => { ws.send(frame); lastSent = Date.now(); };
      handshakeTimer = window.setTimeout(() => { if (current() && !acknowledged) ws.close(); }, 10_000);

      ws.onopen = () => {
        if (!current()) return;
        setConnected(false);
        send(buildFrame("CONNECT", {
          "accept-version": "1.2", host: window.location.host,
          Authorization: `Bearer ${token}`, "heart-beat": "10000,10000",
        }));
      };

      ws.onmessage = (event) => {
        if (!current()) return;
        lastReceived = Date.now();
        buffer += String(event.data || "");
        buffer = buffer.replace(/^[\r\n]+/, "");
        const end = buffer.lastIndexOf("\u0000");
        if (end < 0) return;
        const frames = parseFrames(buffer.slice(0, end + 1));
        buffer = buffer.slice(end + 1);
        for (const frame of frames) {
          if (frame.command === "ERROR") { ws.close(); return; }
          if (frame.command === "CONNECTED" && !acknowledged) {
            acknowledged = true;
            window.clearTimeout(handshakeTimer);
            for (const topic of ["state", "seat", "chat"]) {
              send(buildFrame("SUBSCRIBE", { id: `${topic}-${roomId}`, destination: `/topic/room/${roomId}/${topic}` }));
            }
            send(buildFrame("SUBSCRIBE", { id: `private-${roomId}`, destination: "/user/queue/private" }));
            send(buildFrame("SEND", { destination: `/app/room/${roomId}/sync`, "content-type": "application/json" },
              JSON.stringify({ nonce: syncNonce })));
            syncTimer = window.setTimeout(() => { if (current() && !synchronized) ws.close(); }, 10_000);
            const [serverSend, serverReceive] = (frame.headers["heart-beat"] || "0,0").split(",").map(Number);
            const sendEvery = Number.isFinite(serverReceive) && serverReceive > 0 ? Math.max(10_000, serverReceive) : 0;
            const receiveEvery = Number.isFinite(serverSend) && serverSend > 0 ? Math.max(10_000, serverSend) : 0;
            heartbeatTimer = window.setInterval(() => {
              if (!current() || ws.readyState !== WebSocket.OPEN) return;
              if (receiveEvery && Date.now() - lastReceived > receiveEvery * 2.5) { ws.close(); return; }
              if (sendEvery && Date.now() - lastSent >= sendEvery) send("\n");
            }, 1000);
            continue;
          }
          if (frame.command !== "MESSAGE") continue;
          const destination = frame.headers.destination || "";
          if (destination.endsWith("/state")) callbacks.current.onStateChange?.(parseJson(frame.body, { type: "STATE_SYNC", phase: "", round: 0 }));
          else if (destination.endsWith("/seat")) callbacks.current.onSeatChange?.(parseJson(frame.body, { type: "UNKNOWN", seat: null }));
          else if (destination.endsWith("/chat")) callbacks.current.onChat?.(parseJson(frame.body, { id: "", roomId, senderId: "", senderName: "", type: "TEXT", content: "", timestamp: Date.now() }));
          else if (destination.includes("/queue/private")) {
            const privateEvent = parseJson<PrivateEvent>(frame.body, { type: "UNKNOWN", payload: {} });
            if (privateEvent.type === "SYNC_READY") {
              if (!synchronized && privateEvent.payload?.roomId === roomId && privateEvent.payload?.nonce === syncNonce) {
                synchronized = true;
                window.clearTimeout(syncTimer);
                failures = 0;
                setConnected(true);
                setShowReconnectAction(false);
                window.clearTimeout(noticeTimer); noticeTimer = undefined;
                callbacks.current.onConnected?.();
              }
              continue;
            }
            callbacks.current.onPrivate?.(privateEvent);
          }
        }
      };

      ws.onclose = () => {
        if (!current()) return;
        ++generation; // Repeated or late events from this socket are now inert.
        clearConnectionTimers();
        setConnected(false);
        if (noticeTimer === undefined) noticeTimer = window.setTimeout(() => { if (!disposed) setShowReconnectAction(true); }, 30_000);
        const delay = Math.min(30_000, 1000 * 2 ** Math.min(failures++, 5) * (0.75 + Math.random() * 0.5));
        retryTimer = window.setTimeout(connect, delay);
      };
      ws.onerror = () => { if (current()) ws.close(); };
    };
    connect();
    return () => {
      disposed = true;
      ++generation;
      clearConnectionTimers();
      window.clearTimeout(retryTimer);
      window.clearTimeout(noticeTimer);
      wsRef.current?.close();
      wsRef.current = null;
      setConnected(false);
      setShowReconnectAction(false);
    };
  }, [roomId, playerId, token, nonce, online]);

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
    setShowReconnectAction(false);
    setNonce((current) => current + 1);
  };

  return {
    connected,
    showReconnectAction,
    reconnect,
    sendChat,
  };
};
