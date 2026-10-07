import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { walletApi, getApiErrorMessage } from "@/services/api";
import { localizeErrorMessage } from "@/i18n/errors";
import { useAuth } from "@/hooks/useAuth";
import type { User } from "@/types";
import { DataState } from "@/components/DataState";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import BalanceOverview from "./BalanceOverview";
import CheckinCard from "./CheckinCard";
import RedeemCard from "./RedeemCard";
import ExchangeCard from "./ExchangeCard";
import UsageRecordList from "./UsageRecordList";
import LedgerEntryList from "./LedgerEntryList";
const PAGE_SIZE = 5;
const WalletPanel = ({ initialBalance }: { initialBalance?: User["balance"] }) => {
  const { t } = useTranslation();
  const { user, updateBalance } = useAuth();
  const client = useQueryClient();
  const [usagePage, setUsagePage] = useState(1);
  const [ledgerPage, setLedgerPage] = useState(1);
  const balance = useQuery({ queryKey: ["wallet", user?.id, "balance"], queryFn: walletApi.getBalance, initialData: initialBalance });
  const status = useQuery({ queryKey: ["wallet", user?.id, "checkin"], queryFn: walletApi.getCheckinStatus });
  const usage = useQuery({ queryKey: ["wallet", user?.id, "usage", usagePage], queryFn: () => walletApi.getUsageRecords(usagePage, PAGE_SIZE) });
  const ledger = useQuery({ queryKey: ["wallet", user?.id, "ledger", ledgerPage], queryFn: () => walletApi.getLedger(ledgerPage, PAGE_SIZE) });
  const redemptions = useQuery({ queryKey: ["wallet", user?.id, "redemptions"], queryFn: () => walletApi.getRedemptionHistory(1, PAGE_SIZE) });
  const exchanges = useQuery({ queryKey: ["wallet", user?.id, "exchanges"], queryFn: () => walletApi.getExchangeHistory(1, PAGE_SIZE) });
  useEffect(() => { if (balance.error) updateBalance(undefined); else if (balance.data) updateBalance(balance.data); }, [balance.data, balance.error, updateBalance]);
  const refresh = () => client.invalidateQueries({ queryKey: ["wallet", user?.id] });
  const fail = (error: unknown, key: string) => toast.error(localizeErrorMessage(getApiErrorMessage(error, t(key)), key));
  const checkin = useMutation({ mutationFn: walletApi.checkin, onSuccess: result => { toast.success(t(result.alreadyCheckedIn ? "wallet.checkinToday" : "wallet.checkinSuccess")); void refresh(); }, onError: error => fail(error, "wallet.checkinFailed") });
  const redeem = useMutation({ mutationFn: walletApi.redeemCode, onSuccess: result => {
    if (!result.success) { toast.error(localizeErrorMessage(result.errorMessage, "wallet.redeemFailed")); return; }
    toast.success(t("wallet.redeemSuccess", { count: result.tokensGranted })); void refresh();
  }, onError: error => fail(error, "wallet.redeemFailed") });
  const exchange = useMutation({ mutationFn: ({ amount, requestId }: { amount: number; requestId: string }) => walletApi.exchangePublicToProject(amount, requestId),
    onSuccess: result => { toast.success(t("wallet.exchangeSuccess", { count: result.exchangedTokens })); void refresh(); }, onError: error => fail(error, "wallet.redeemFailed") });
  return <div className="space-y-4">
    {balance.isPending || balance.error || !balance.data ? <DataState loading={balance.isPending} error={balance.error} onRetry={() => void balance.refetch()} /> : <BalanceOverview totalTokens={balance.data.totalTokens} projectPermanentTokens={balance.data.projectPermanentTokens} projectTempTokens={balance.data.projectTempTokens} />}
    {status.isPending || status.error || !status.data ? <DataState loading={status.isPending} error={status.error} onRetry={() => void status.refetch()} /> : <CheckinCard status={status.data} checking={checkin.isPending} onCheckin={() => checkin.mutate()} />}
    <RedeemCard redeeming={redeem.isPending} onRedeem={async code => { await redeem.mutateAsync(code); }} />
    <ExchangeCard exchanging={exchange.isPending} onExchange={async (amount, requestId) => { await exchange.mutateAsync({ amount, requestId }); }} />
    {usage.isPending || usage.error || !usage.data ? <DataState loading={usage.isPending} error={usage.error} onRetry={() => void usage.refetch()} /> : <UsageRecordList title={t("wallet.usageTitle")} records={usage.data.items} page={usagePage} hasMore={usagePage * PAGE_SIZE < usage.data.total} loading={usage.isFetching} onPrev={() => setUsagePage(Math.max(1, usagePage - 1))} onNext={() => setUsagePage(usagePage + 1)} />}
    {ledger.isPending || ledger.error || !ledger.data ? <DataState loading={ledger.isPending} error={ledger.error} onRetry={() => void ledger.refetch()} /> : <LedgerEntryList entries={ledger.data.items} page={ledgerPage} hasMore={ledgerPage * PAGE_SIZE < ledger.data.total} loading={ledger.isFetching} onPrev={() => setLedgerPage(Math.max(1, ledgerPage - 1))} onNext={() => setLedgerPage(ledgerPage + 1)} />}
    <Card><CardHeader><CardTitle>{t("wallet.redeemHistoryTitle")}</CardTitle></CardHeader><CardContent className="space-y-2">
      {redemptions.isPending || redemptions.error || !redemptions.data?.items.length ? <DataState loading={redemptions.isPending} error={redemptions.error} empty onRetry={() => void redemptions.refetch()} /> : redemptions.data.items.map(item => <div key={`${item.code}-${item.redeemedAt}`} className="rounded-lg border p-3 text-sm"><div>{item.code}</div><div className="text-xs text-muted-foreground">+{item.tokensGranted} / {item.creditType}{item.redeemedAt && ` · ${new Date(item.redeemedAt).toLocaleString()}`}</div></div>)}
    </CardContent></Card>
    <Card><CardHeader><CardTitle>{t("wallet.exchangeHistoryTitle")}</CardTitle></CardHeader><CardContent className="space-y-2">
      {exchanges.isPending || exchanges.error || !exchanges.data?.items.length ? <DataState loading={exchanges.isPending} error={exchanges.error} empty onRetry={() => void exchanges.refetch()} /> : exchanges.data.items.map(item => <div key={item.requestId} className="rounded-lg border p-3 text-sm space-y-1"><div>{t("wallet.exchangeRequest", { id: item.requestId })}</div><div>{t("wallet.exchangeAmount", { count: item.exchangedTokens })}</div><div>{t("wallet.publicTokens", { before: item.publicBefore, after: item.publicAfter })}</div><div>{t("wallet.projectTokens", { before: item.projectPermanentBefore, after: item.projectPermanentAfter })}</div>{item.createdAt && <div className="text-xs text-muted-foreground">{new Date(item.createdAt).toLocaleString()}</div>}</div>)}
    </CardContent></Card>
  </div>;
};
export default WalletPanel;
