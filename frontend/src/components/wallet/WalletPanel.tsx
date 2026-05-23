import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { walletApi } from "@/services/api";
import { useAuth } from "@/hooks/useAuth";
import { CheckinStatusResponse, ExchangeHistoryRecord, LedgerEntry, RedemptionRecord, UsageRecord, User } from "@/types";
import BalanceOverview from "./BalanceOverview";
import CheckinCard from "./CheckinCard";
import RedeemCard from "./RedeemCard";
import ExchangeCard from "./ExchangeCard";
import UsageRecordList from "./UsageRecordList";
import LedgerEntryList from "./LedgerEntryList";

interface Props {
  initialBalance?: User["balance"];
}

const PAGE_SIZE = 5;

const WalletPanel = ({ initialBalance }: Props) => {
  const { updateBalance } = useAuth();
  const [balance, setBalance] = useState<User["balance"] | undefined>(initialBalance);
  const [checkinStatus, setCheckinStatus] = useState<CheckinStatusResponse | null>(null);
  const [usageRecords, setUsageRecords] = useState<UsageRecord[]>([]);
  const [usagePage, setUsagePage] = useState(1);
  const [usageTotal, setUsageTotal] = useState(0);
  const [ledgerEntries, setLedgerEntries] = useState<LedgerEntry[]>([]);
  const [ledgerPage, setLedgerPage] = useState(1);
  const [ledgerTotal, setLedgerTotal] = useState(0);
  const [redemptions, setRedemptions] = useState<RedemptionRecord[]>([]);
  const [exchangeHistory, setExchangeHistory] = useState<ExchangeHistoryRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [checking, setChecking] = useState(false);
  const [redeeming, setRedeeming] = useState(false);
  const [exchanging, setExchanging] = useState(false);

  const applyBalance = (nextBalance?: User["balance"]) => {
    setBalance(nextBalance);
    if (nextBalance) {
      updateBalance(nextBalance);
    }
  };

  const loadUsage = async (page: number) => {
    const data = await walletApi.getUsageRecords(page, PAGE_SIZE);
    setUsageRecords(data.items);
    setUsagePage(data.page);
    setUsageTotal(data.total);
  };

  const loadLedger = async (page: number) => {
    const data = await walletApi.getLedger(page, PAGE_SIZE);
    setLedgerEntries(data.items);
    setLedgerPage(data.page);
    setLedgerTotal(data.total);
  };

  const loadInitial = async () => {
    setLoading(true);
    try {
      const [balanceData, statusData, redemptionData, exchangeData] = await Promise.all([
        walletApi.getBalance(),
        walletApi.getCheckinStatus(),
        walletApi.getRedemptionHistory(1, PAGE_SIZE),
        walletApi.getExchangeHistory(1, PAGE_SIZE),
      ]);
      applyBalance(balanceData);
      setCheckinStatus(statusData);
      setRedemptions(redemptionData.items);
      setExchangeHistory(exchangeData.items);
      await Promise.all([loadUsage(1), loadLedger(1)]);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "钱包数据加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadInitial();
  }, []);

  const onCheckin = async () => {
    setChecking(true);
    try {
      const result = await walletApi.checkin();
      applyBalance(result.balance);
      setCheckinStatus((prev) => ({
        checkedInToday: true,
        lastCheckinDate: prev?.lastCheckinDate,
        tokensGrantedToday: result.tokensGranted,
      }));
      toast.success(result.alreadyCheckedIn ? "今日已签到" : "签到成功");
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "签到失败");
    } finally {
      setChecking(false);
    }
  };

  const onRedeem = async (code: string) => {
    setRedeeming(true);
    try {
      const result = await walletApi.redeemCode(code);
      if (!result.success) {
        toast.error(result.errorMessage || "兑换失败");
        return;
      }
      applyBalance(result.balance);
      toast.success(`兑换成功，到账 ${result.tokensGranted} 积分`);
      const redemptionData = await walletApi.getRedemptionHistory(1, PAGE_SIZE);
      setRedemptions(redemptionData.items);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "兑换失败");
    } finally {
      setRedeeming(false);
    }
  };

  const onExchange = async (amount: number, requestId: string) => {
    setExchanging(true);
    try {
      const result = await walletApi.exchangePublicToProject(amount, requestId);
      applyBalance(result.balance);
      toast.success(`兑换成功，到账 ${result.exchangedTokens} 专属积分`);
      const [history] = await Promise.all([
        walletApi.getExchangeHistory(1, PAGE_SIZE),
        loadLedger(1),
      ]);
      setExchangeHistory(history.items);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "兑换失败");
    } finally {
      setExchanging(false);
    }
  };

  const usageHasMore = useMemo(() => usagePage * PAGE_SIZE < usageTotal, [usagePage, usageTotal]);
  const ledgerHasMore = useMemo(() => ledgerPage * PAGE_SIZE < ledgerTotal, [ledgerPage, ledgerTotal]);

  return (
    <div className="space-y-4">
      <BalanceOverview
        totalTokens={balance?.totalTokens ?? 0}
        projectPermanentTokens={balance?.projectPermanentTokens ?? 0}
        projectTempTokens={balance?.projectTempTokens ?? 0}
      />
      <CheckinCard status={checkinStatus} checking={checking} onCheckin={onCheckin} />
      <RedeemCard redeeming={redeeming} onRedeem={onRedeem} />
      <ExchangeCard exchanging={exchanging} onExchange={onExchange} />
      <UsageRecordList
        title="消费记录"
        records={usageRecords}
        page={usagePage}
        hasMore={usageHasMore}
        loading={loading}
        onPrev={() => void loadUsage(Math.max(1, usagePage - 1))}
        onNext={() => void loadUsage(usagePage + 1)}
      />
      <LedgerEntryList
        entries={ledgerEntries}
        page={ledgerPage}
        hasMore={ledgerHasMore}
        loading={loading}
        onPrev={() => void loadLedger(Math.max(1, ledgerPage - 1))}
        onNext={() => void loadLedger(ledgerPage + 1)}
      />
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-lg">兑换历史</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {!redemptions.length && <div className="text-muted-foreground">暂无兑换记录</div>}
          {redemptions.map((item) => (
            <div key={`${item.code}-${item.redeemedAt}`} className="rounded-lg border p-3">
              <div className="font-medium">{item.code}</div>
              <div className="text-xs text-muted-foreground">
                +{item.tokensGranted} / {item.creditType}
                {item.redeemedAt ? ` · ${new Date(item.redeemedAt).toLocaleString()}` : ""}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-lg">通用积分兑换记录</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {!exchangeHistory.length && <div className="text-muted-foreground">暂无兑换记录</div>}
          {exchangeHistory.map((item) => (
            <div key={`${item.requestId}-${item.createdAt}`} className="rounded-lg border p-3 space-y-1">
              <div className="font-medium">请求号：{item.requestId}</div>
              <div className="text-xs text-muted-foreground">
                兑换数量：{item.exchangedTokens}
                {item.createdAt ? ` · ${new Date(item.createdAt).toLocaleString()}` : ""}
              </div>
              <div className="text-xs">
                通用积分：{item.publicBefore} → {item.publicAfter}
              </div>
              <div className="text-xs">
                项目永久积分：{item.projectPermanentBefore} → {item.projectPermanentAfter}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
};

export default WalletPanel;
