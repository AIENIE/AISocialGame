import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";

interface Props {
  redeeming: boolean;
  onRedeem: (code: string) => Promise<void>;
}

const RedeemCard = ({ redeeming, onRedeem }: Props) => {
  const { t } = useTranslation();
  const [code, setCode] = useState("");

  const submit = async () => {
    const value = code.trim();
    if (!value) {
      return;
    }
    try { await onRedeem(value); } catch { return; }
    setCode("");
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-lg">{t("wallet.redeemTitle")}</CardTitle>
      </CardHeader>
      <CardContent className="flex gap-2">
        <Input value={code} onChange={(e) => setCode(e.target.value)} placeholder={t("wallet.redeemPlaceholder")} />
        <Button disabled={redeeming || !code.trim()} onClick={() => void submit()}>
          {t("wallet.redeem")}
        </Button>
      </CardContent>
    </Card>
  );
};

export default RedeemCard;
