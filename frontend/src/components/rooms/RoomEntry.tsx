import { useEffect, useState, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DataState } from "@/components/DataState";
import { useAuth } from "@/hooks/useAuth";
import { roomApi, getApiErrorCode, getApiErrorMessage } from "@/services/api";
import { localizeErrorMessage } from "@/i18n/errors";
import { gameName } from "@/i18n/gameTexts";
import type { RoomEntry } from "@/types";

export function RoomNumber({ code }: { code?: string }) {
  const { t } = useTranslation();
  if (!code) return null;
  return <Button variant="ghost" size="sm" aria-label={t("rooms.copy")} onClick={async () => {
    try { await navigator.clipboard.writeText(code); toast.success(t("rooms.copied")); }
    catch { toast.error(t("rooms.copyFailed")); }
  }}>{t("rooms.code")}: {code}</Button>;
}

export function RoomExpired({ gameId }: { gameId?: string }) {
  const { t } = useTranslation();
  return <div role="alert" className="mx-auto max-w-xl space-y-4 rounded-lg border p-6">
    <h2 className="text-lg font-semibold">{t("rooms.expired")}</h2><p>{t("rooms.expiredHelp")}</p>
    <Button asChild><Link to={gameId ? `/game/${gameId}` : "/"}>{t("roomList.back")}</Link></Button>
  </div>;
}

export function RoomJoin({ room, onJoined }: { room: RoomEntry; onJoined?: () => void }) {
  const { t } = useTranslation();
  const { displayName } = useAuth();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const join = useMutation({
    mutationFn: () => roomApi.join(room.gameId, room.id, displayName, room.passwordRequired ? password : undefined),
    onSuccess: async data => {
      client.setQueryData(["room", room.id], data);
      client.setQueryData(["room-entry", room.gameId, room.id], { ...room, joined: true });
      setOpen(false); setPassword("");
      await client.invalidateQueries({ queryKey: ["rooms"] });
      onJoined?.();
      navigate(`/room/${room.gameId}/${room.id}`);
    },
  });
  if (getApiErrorCode(join.error) === "ROOM_EXPIRED") return <RoomExpired gameId={room.gameId} />;
  const enter = () => {
    if (room.joined) { navigate(`/room/${room.gameId}/${room.id}`); return; }
    if (room.status === "PLAYING") { navigate(`/spectate/${room.gameId}/${room.id}`); return; }
    if (room.passwordRequired) { join.reset(); setOpen(true); } else join.mutate();
  };
  const unavailable = !room.joined && (room.status === "PLAYING" ? room.isPrivate : room.seatCount >= room.maxPlayers);
  return <div className="space-y-3">
    <Button className="h-auto min-h-10 w-full whitespace-normal" onClick={enter} disabled={unavailable || join.isPending}>
      {join.isPending ? t("rooms.joining") : room.joined ? t("rooms.return") : unavailable ? t(room.status === "PLAYING" ? "rooms.privatePlaying" : "rooms.full") : t(room.status === "PLAYING" ? "roomList.watchNow" : "roomList.joinNow")}
    </Button>
    {join.error && !open && <p role="alert">{localizeErrorMessage(getApiErrorMessage(join.error, ""), "lobby.joinFailed", getApiErrorCode(join.error))}</p>}
    <Dialog open={open} onOpenChange={value => { if (!join.isPending) { setOpen(value); setPassword(""); join.reset(); } }}>
      <DialogContent><DialogHeader><DialogTitle>{room.name}</DialogTitle><DialogDescription>{t("rooms.passwordHelp")}</DialogDescription></DialogHeader>
        <form className="space-y-4" onSubmit={e => { e.preventDefault(); join.mutate(); }}>
          <Label htmlFor="room-password">{t("rooms.password")}</Label><Input id="room-password" type="password" autoComplete="off" value={password} maxLength={64} onChange={e => setPassword(e.target.value)} autoFocus />
          {join.error && <p role="alert">{localizeErrorMessage(getApiErrorMessage(join.error, ""), "lobby.joinFailed", getApiErrorCode(join.error))}</p>}
          <div className="flex gap-2"><Button type="button" variant="outline" disabled={join.isPending} onClick={() => { setOpen(false); setPassword(""); join.reset(); }}>{t("rooms.cancel")}</Button>
            <Button type="submit" disabled={join.isPending || password.trim().length < 4}>{t(join.isPending ? "rooms.joining" : "roomList.joinNow")}</Button></div>
        </form>
      </DialogContent>
    </Dialog>
  </div>;
}

export function RoomSearch() {
  const { t } = useTranslation();
  const [code, setCode] = useState("");
  const [invalid, setInvalid] = useState(false);
  const search = useMutation({ mutationFn: (value: string) => roomApi.search(value) });
  return <section className="space-y-3 rounded-xl border bg-white p-5" aria-label={t("rooms.search")}>
    <h2 className="text-lg font-semibold">{t("rooms.search")}</h2><p className="text-sm text-muted-foreground">{t("rooms.searchHelp")}</p>
    <form className="flex flex-wrap gap-2" onSubmit={e => { e.preventDefault(); const valid = /^[1-9]\d{5}$/.test(code.trim()); setInvalid(!valid); if (valid) search.mutate(code.trim()); }}>
      <Label className="sr-only" htmlFor="room-code">{t("rooms.code")}</Label><Input id="room-code" className="w-48" inputMode="numeric" maxLength={6} value={code} onChange={e => { setCode(e.target.value); setInvalid(false); search.reset(); }} />
      <Button type="submit" disabled={search.isPending}>{t(search.isPending ? "rooms.searching" : "rooms.search")}</Button>
    </form>
    {invalid && <p role="alert">{t("rooms.invalidCode")}</p>}
    {search.error && (getApiErrorCode(search.error) === "ROOM_EXPIRED" ? <RoomExpired /> : <DataState error={search.error} onRetry={() => search.mutate(code.trim())} />)}
    {search.data && !search.isPending && <div className="max-w-md space-y-3 rounded-lg border p-4" key={search.data.id}>
      <h3 className="font-semibold">{search.data.name}</h3><p>{gameName(search.data.gameId)} · {t(search.data.isPrivate ? "roomList.tag.private" : "roomList.tag.public")} · {search.data.seatCount} / {search.data.maxPlayers}</p>
      <RoomNumber code={search.data.roomCode} /><RoomJoin room={search.data} />
    </div>}
  </section>;
}

/** The business room is mounted only after discovery and admission succeed. */
export function RoomEntryGate({ children, spectator = false }: { children: ReactNode; spectator?: boolean }) {
  const { gameId = "", roomId = "" } = useParams();
  const { t } = useTranslation();
  const [expired, setExpired] = useState(false);
  const client = useQueryClient();
  const entry = useQuery({ queryKey: ["room-entry", gameId, roomId], queryFn: ({ signal }) => roomApi.entry(gameId, roomId, signal), enabled: !expired, retry: false, refetchInterval: query => expired || getApiErrorCode(query.state.error) === "ROOM_EXPIRED" ? false : 30000, refetchOnWindowFocus: query => getApiErrorCode(query.state.error) !== "ROOM_EXPIRED", refetchOnReconnect: query => getApiErrorCode(query.state.error) !== "ROOM_EXPIRED" });
  useEffect(() => { setExpired(false); }, [gameId, roomId]);
  useEffect(() => {
    const listener = (event: Event) => { if ((event as CustomEvent<string>).detail === roomId) setExpired(true); };
    window.addEventListener("room-expired", listener);
    return () => window.removeEventListener("room-expired", listener);
  }, [roomId]);
  const terminal = expired || getApiErrorCode(entry.error) === "ROOM_EXPIRED";
  useEffect(() => {
    if (!terminal) return;
    const keys = [["room", roomId], ["game-state", roomId], ["spectator-state", gameId, roomId], ["room-timeline", gameId, roomId]];
    for (const queryKey of keys) {
      void client.cancelQueries({ queryKey });
      client.removeQueries({ queryKey });
    }
    if (expired) void client.cancelQueries({ queryKey: ["room-entry", gameId, roomId] });
  }, [terminal, expired, gameId, roomId, client]);
  const { refetch } = entry;
  useEffect(() => {
    if (terminal || !entry.data?.expiresAt) return;
    const delay = new Date(entry.data.expiresAt).getTime() - Date.now();
    if (!Number.isFinite(delay)) return;
    const timer = window.setTimeout(() => { void refetch(); }, Math.max(0, delay));
    return () => window.clearTimeout(timer);
  }, [entry.data?.expiresAt, refetch, terminal]);
  if (terminal) return <RoomExpired gameId={gameId} />;
  if (entry.error || !entry.data) return <DataState loading={entry.isPending} error={entry.error} onRetry={() => void entry.refetch()} />;
  if (entry.data.joined || spectator && !entry.data.isPrivate) return <>{children}</>;
  return <div className="mx-auto max-w-lg space-y-4 rounded-xl border bg-white p-6">
    <h1 className="text-xl font-semibold">{entry.data.name}</h1><RoomNumber code={entry.data.roomCode} />
    <p>{t("rooms.waitRule")}</p><RoomJoin room={entry.data} onJoined={() => void entry.refetch()} />
    <Button variant="ghost" asChild><Link to={`/game/${gameId}`}>{t("roomList.back")}</Link></Button>
  </div>;
}
