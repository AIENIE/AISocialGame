import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Send } from "lucide-react";
import type { ChatMessage } from "@/types";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";

interface ChatPanelProps {
  messages: ChatMessage[];
  myPlayerId?: string;
  onSend: (type: "TEXT" | "EMOJI" | "QUICK_PHRASE", content: string) => void | boolean;
  readOnly?: boolean;
  disabled?: boolean;
  className?: string;
}

export const ChatPanel = ({ messages, myPlayerId, onSend, readOnly, disabled, className = "" }: ChatPanelProps) => {
  const { t } = useTranslation();
  const [input, setInput] = useState("");
  const scroll = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const [unread, setUnread] = useState(false);
  useEffect(() => {
    if (following.current && scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight;
    else setUnread(true);
  }, [messages]);
  const send = () => {
    if (disabled || readOnly || !input.trim()) return;
    if (onSend("TEXT", input.trim()) !== false) setInput("");
  };
  return <section className={`room-chat-panel ${className}`} aria-label={t("game.chat.title")} data-testid="room-chat-panel">
    <h2 className="border-b px-4 py-2 text-sm font-medium">{t("game.chat.title")}</h2>
    <div ref={scroll} className="room-chat-messages" onScroll={() => {
      if (!scroll.current) return;
      following.current = scroll.current.scrollHeight - scroll.current.scrollTop - scroll.current.clientHeight < 40;
      if (following.current) setUnread(false);
    }}>
      {messages.slice(-100).map(message => <div key={message.id} className={`room-message ${message.senderId === myPlayerId ? "room-message-mine" : ""}`}>
        <Avatar className="h-7 w-7 shrink-0"><AvatarImage src={message.senderAvatar} alt="" /><AvatarFallback>{message.senderName?.[0] || "?"}</AvatarFallback></Avatar>
        <div className="room-message-body"><div className="room-message-byline">{message.senderName}</div><p className="room-bubble whitespace-pre-wrap break-words">{message.content}</p></div>
      </div>)}
      {!messages.length && <p className="py-6 text-center text-sm text-muted-foreground">{t("game.chat.empty")}</p>}
    </div>
    {unread && <Button size="sm" variant="ghost" onClick={() => { following.current = true; setUnread(false); if (scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight; }}>↓ {t("game.live")}</Button>}
    {!readOnly && <form className="room-chat-composer" onSubmit={event => { event.preventDefault(); send(); }}>
      <Input value={input} maxLength={1000} aria-label={t("game.chat.placeholder")} onChange={event => setInput(event.target.value)} disabled={disabled} placeholder={t("game.chat.placeholder")} />
      <Button type="submit" size="icon" disabled={disabled || !input.trim()} aria-label={t("game.submitSpeak")}><Send className="h-4 w-4" /></Button>
    </form>}
  </section>;
};
