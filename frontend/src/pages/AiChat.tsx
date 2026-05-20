import { FormEvent, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { aiApi, getApiErrorMessage } from "@/services/api";
import { AiMessage } from "@/types";
import { useAuth } from "@/hooks/useAuth";
import { toast } from "sonner";

const AiChat = () => {
  const { user, redirectToSsoLogin } = useAuth();
  const [messages, setMessages] = useState<AiMessage[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const content = input.trim();
    if (!content || sending) {
      return;
    }
    const nextMessages: AiMessage[] = [...messages, { role: "user", content }];
    setMessages([...nextMessages, { role: "assistant", content: "" }]);
    setInput("");
    setSending(true);
    try {
      await aiApi.chatStream(
        nextMessages,
        undefined,
        (chunk) => {
          setMessages((prev) => {
            const cloned = [...prev];
            const last = cloned[cloned.length - 1];
            if (!last || last.role !== "assistant") {
              cloned.push({ role: "assistant", content: chunk });
            } else {
              cloned[cloned.length - 1] = { ...last, content: `${last.content}${chunk}` };
            }
            return cloned;
          });
        },
        () => {},
      );
    } catch (error: any) {
      toast.error(getApiErrorMessage(error, "AI 调用失败"));
      setMessages(nextMessages);
    } finally {
      setSending(false);
    }
  };

  if (!user) {
    return (
      <Card className="max-w-3xl mx-auto">
        <CardHeader>
          <CardTitle>AI 对话</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="text-sm text-muted-foreground">请先登录后再使用 AI 流式对话。</div>
          <Button onClick={() => void redirectToSsoLogin()}>前往登录</Button>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="max-w-4xl mx-auto space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>AI 流式对话</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="max-h-[420px] overflow-auto rounded-lg border bg-slate-50 p-3 space-y-3">
            {!messages.length && <div className="text-sm text-muted-foreground">输入消息开始对话</div>}
            {messages.map((message, index) => (
              <div key={`${message.role}-${index}`} className={message.role === "user" ? "text-right" : "text-left"}>
                <div
                  className={`inline-block max-w-[90%] rounded-lg px-3 py-2 text-sm ${
                    message.role === "user" ? "bg-blue-600 text-white" : "bg-white border"
                  }`}
                >
                  {message.content || (sending && index === messages.length - 1 ? "..." : "")}
                </div>
              </div>
            ))}
          </div>
          <form onSubmit={submit} className="space-y-2">
            <Textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              rows={3}
              placeholder="输入问题，按“发送”后将通过 SSE 逐字返回"
            />
            <div className="flex justify-end">
              <Button type="submit" disabled={sending || !input.trim()}>
                {sending ? "生成中..." : "发送"}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
};

export default AiChat;
