import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface Props {
  exchanging: boolean;
  onExchange: (amount: number, requestId: string) => Promise<void>;
}

const ExchangeCard = ({ exchanging, onExchange }: Props) => {
  const [amount, setAmount] = useState("");
  const [pendingRequestId, setPendingRequestId] = useState("");

  const submit = async () => {
    const parsed = Number(amount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      return;
    }
    const requestId = pendingRequestId || createRequestId();
    setPendingRequestId(requestId);
    await onExchange(parsed, requestId);
    setAmount("");
    setPendingRequestId("");
  };

  const createRequestId = () => {
    if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
      return `aisocialgame:exchange:${crypto.randomUUID()}`;
    }
    return `aisocialgame:exchange:${Date.now()}:${Math.random().toString(36).slice(2)}`;
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-lg">通用积分兑换专属积分</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <Input
          placeholder="输入兑换数量（1:1）"
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^\d]/g, ""))}
        />
        <Button
          disabled={exchanging || !amount || Number(amount) <= 0}
          onClick={() => void submit()}
          className="w-full"
        >
          {exchanging ? "兑换中..." : "立即兑换"}
        </Button>
      </CardContent>
    </Card>
  );
};

export default ExchangeCard;
