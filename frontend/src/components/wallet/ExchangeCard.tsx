import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface Props {
  exchanging: boolean;
  onExchange: (amount: number, requestId: string) => Promise<void>;
}

const ExchangeCard = ({ exchanging, onExchange }: Props) => {
  const { t } = useTranslation();
  const [amount, setAmount] = useState("");
  const [pendingRequestId, setPendingRequestId] = useState("");

  const submit = async () => {
    const parsed = Number(amount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      return;
    }
    const requestId = pendingRequestId || createRequestId();
    setPendingRequestId(requestId);
    try { await onExchange(parsed, requestId); }
    catch { return; } // Keep the same request ID for a retry of an uncertain exchange.
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
        <CardTitle className="text-lg">{t("wallet.exchangeTitle")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <Input
          placeholder={t("wallet.exchangePlaceholder")}
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^\d]/g, ""))}
        />
        <Button
          disabled={exchanging || !amount || Number(amount) <= 0}
          onClick={() => void submit()}
          className="w-full"
        >
          {exchanging ? t("wallet.exchanging") : t("wallet.exchangeNow")}
        </Button>
      </CardContent>
    </Card>
  );
};

export default ExchangeCard;
