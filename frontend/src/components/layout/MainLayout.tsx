import { useState } from "react";
import { Outlet, Link, useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { BookOpen, Coins, Gamepad2, Home, PlayCircle, Shield, Trophy, User, Zap } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { LanguageSelector } from "@/i18n/LanguageSelector";
import { useAuth } from "@/hooks/useAuth";
import { QuickMatchDialog } from "@/components/social/QuickMatchDialog";

const MainLayout = () => {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const { user, loading, logout, displayName, redirectToSsoLogin } = useAuth();
  const [quickMatchOpen, setQuickMatchOpen] = useState(false);
  const privateNav = [{ path: "/community", label: "nav.community" }, { path: "/ai-chat", label: "nav.aiChat" }, { path: "/rankings", label: "nav.rankings" }, { path: "/replays", label: "nav.replays" }];
  const nav = [{ path: "/", label: "nav.home" }, ...(user ? privateNav : []), { path: "/guide", label: "nav.guide" }];
  const active = (path: string) => (path === "/" ? location.pathname === "/" : location.pathname.startsWith(path)) ? "text-primary" : "text-muted-foreground";
  if (user && /^\/room\/(undercover|werewolf|turtle_soup)\//.test(location.pathname)) return <main className="room-route"><Outlet /></main>;
  return <div className="min-h-screen bg-background flex flex-col pb-16 md:pb-0">
    <header className="sticky top-0 z-50 border-b bg-background/95 backdrop-blur">
      <div className="container flex h-14 md:h-16 items-center justify-between gap-3 px-4">
        <div className="flex items-center gap-8">
          <Link to="/" className="flex items-center gap-2 font-bold text-lg"><Gamepad2 className="h-5 w-5 text-primary" /><span>Nexus<span className="text-primary">Play</span></span></Link>
          <nav className="hidden md:flex items-center gap-5 text-sm font-medium">{nav.map(item => <Link key={item.path} to={item.path} className={active(item.path)}>{t(item.label)}</Link>)}</nav>
        </div>
        <div className="flex items-center gap-2 md:gap-4">
          {user && <Button size="sm" className="hidden md:inline-flex" onClick={() => setQuickMatchOpen(true)}><Zap className="mr-1 h-4 w-4" />{t("common.quickStart")}</Button>}
          <LanguageSelector />
          {user && <button type="button" onClick={() => navigate("/profile?tab=wallet")} aria-label={t("header.wallet")} className="flex items-center gap-1.5 rounded-full bg-secondary/50 px-2 py-1 text-xs md:text-sm">
            <Coins className="h-4 w-4 text-yellow-500" /><span>{user.balanceAvailable && typeof user.coins === "number" ? user.coins : t("header.wallet")}</span>
          </button>}
          {user ? <DropdownMenu>
            <DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={t("user.profile")}><Avatar className="h-8 w-8"><AvatarImage src={user.avatar} alt={displayName} /><AvatarFallback>{displayName.slice(0, 2)}</AvatarFallback></Avatar></Button></DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuLabel>{displayName}</DropdownMenuLabel><DropdownMenuSeparator />
              <DropdownMenuItem asChild><Link to="/profile"><User className="mr-2 h-4 w-4" />{t("user.profile")}</Link></DropdownMenuItem>
              {privateNav.map(item => <DropdownMenuItem key={item.path} asChild><Link to={item.path}>{t(item.label)}</Link></DropdownMenuItem>)}
              <DropdownMenuItem asChild><a href="/admin"><Shield className="mr-2 h-4 w-4" />{t("user.admin")}</a></DropdownMenuItem>
              <DropdownMenuSeparator /><DropdownMenuItem onClick={() => void logout()}>{t("user.logout")}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu> : <Button variant="outline" disabled={loading} onClick={() => void redirectToSsoLogin()}>{t("common.login")}</Button>}
        </div>
      </div>
    </header>
    <main className="page-enter flex-1 container py-4 md:py-6 px-4"><Outlet /></main>
    <nav className="md:hidden fixed bottom-0 inset-x-0 border-t bg-background z-50 pb-safe">
      <div className={`grid ${user ? "grid-cols-5" : "grid-cols-2"} h-16`}>
        <Link to="/" className={`flex flex-col items-center justify-center gap-1 ${active("/")}`}><Home className="h-5 w-5" /><span className="text-xs">{t("mobile.home")}</span></Link>
        {user && <><Link to="/rankings" className={`flex flex-col items-center justify-center gap-1 ${active("/rankings")}`}><Trophy className="h-5 w-5" /><span className="text-xs">{t("mobile.rankings")}</span></Link>
          <button onClick={() => setQuickMatchOpen(true)} className="flex flex-col items-center justify-center gap-1"><Zap className="h-5 w-5" /><span className="text-xs">{t("mobile.quickMatch")}</span></button>
          <Link to="/replays" className={`flex flex-col items-center justify-center gap-1 ${active("/replays")}`}><PlayCircle className="h-5 w-5" /><span className="text-xs">{t("mobile.replays")}</span></Link></>}
        <Link to="/guide" className={`flex flex-col items-center justify-center gap-1 ${active("/guide")}`}><BookOpen className="h-5 w-5" /><span className="text-xs">{t("mobile.guide")}</span></Link>
      </div>
    </nav>
    {user && quickMatchOpen && <QuickMatchDialog open={quickMatchOpen} onOpenChange={setQuickMatchOpen} displayName={displayName} />}
  </div>;
};
export default MainLayout;
