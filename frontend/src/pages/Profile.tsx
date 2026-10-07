import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { PlayCircle } from "lucide-react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/hooks/useAuth";
import WalletPanel from "@/components/wallet/WalletPanel";

const Profile = () => {
  const { t } = useTranslation();
  const { user, displayName, avatar, logout } = useAuth();
  if (!user) return null;
  return <div className="max-w-4xl mx-auto space-y-6">
    <Card><CardHeader><div className="flex items-center gap-4">
      <Avatar className="h-16 w-16"><AvatarImage src={avatar} /><AvatarFallback>{displayName.slice(0, 2)}</AvatarFallback></Avatar>
      <div className="flex-1 min-w-0"><h1 className="text-2xl font-bold truncate">{displayName}</h1><p className="text-xs text-muted-foreground break-all">UID: {user.id}</p></div>
      <Button variant="outline" onClick={() => void logout()}>{t("user.logout")}</Button>
    </div></CardHeader></Card>
    <WalletPanel initialBalance={user.balanceAvailable ? user.balance : undefined} />
    <Card><CardHeader><CardTitle>{t("profile.replaysTitle")}</CardTitle></CardHeader><CardContent><Button asChild><Link to="/replays"><PlayCircle className="mr-2 h-4 w-4" />{t("profile.replayCenter")}</Link></Button></CardContent></Card>
  </div>;
};
export default Profile;
