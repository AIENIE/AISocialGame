import { useMemo, useState } from "react";
import { Outlet, Link, useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import {
  Bell,
  BookOpen,
  Coins,
  Gamepad2,
  Home,
  LayoutGrid,
  MoreHorizontal,
  PlayCircle,
  Shield,
  Trophy,
  User,
  Users,
  Zap,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { LanguageMenuItems, LanguageSelector } from "@/i18n/LanguageSelector";
import { useAuth } from "@/hooks/useAuth";
import { gameApi } from "@/services/api";
import { friendApi } from "@/services/v2Social";
import { FriendPanel } from "@/components/social/FriendPanel";
import { QuickMatchDialog } from "@/components/social/QuickMatchDialog";

const MainLayout = () => {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout, displayName, redirectToSsoLogin } = useAuth();
  const [friendOpen, setFriendOpen] = useState(false);
  const [quickMatchOpen, setQuickMatchOpen] = useState(false);
  const userKey = useMemo(() => user?.id || `guest:${displayName}`, [user?.id, displayName]);

  const { data: games = [] } = useQuery({
    queryKey: ["games"],
    queryFn: gameApi.list,
  });

  const requestCount = friendApi.getPanelData(userKey).requests.length;
  const onlineTotal = games.reduce((acc, item) => acc + (item.onlineCount || 0), 0);
  const isGameRoute = /^\/(room|spectate)\//.test(location.pathname);

  if (/^\/room\/(undercover|werewolf|turtle_soup)\//.test(location.pathname)) {
    return <main className="room-route"><Outlet /></main>;
  }

  const isActive = (path: string) => {
    return location.pathname.startsWith(path) ? "text-primary" : "text-muted-foreground";
  };

  const isMobileActive = (path: string) => {
    return location.pathname.startsWith(path) ? "text-blue-600" : "text-slate-400";
  };

  return (
    <div className="min-h-screen bg-background flex flex-col pb-16 md:pb-0">
      {/* Header (Desktop & Mobile Top Bar) */}
      <header className="sticky top-0 z-50 w-full border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="container flex h-14 md:h-16 items-center justify-between px-4">
          {/* Logo Area */}
          <div className="flex items-center gap-8">
            <Link to="/" className="flex items-center gap-2 font-bold text-lg md:text-xl">
              <div className="bg-primary text-primary-foreground p-1.5 rounded-lg">
                <Gamepad2 className="h-4 w-4 md:h-5 md:w-5" />
              </div>
              <span>Nexus<span className="text-primary">Play</span></span>
            </Link>

            {/* Desktop Nav */}
            <nav className="hidden md:flex items-center gap-6 text-sm font-medium">
              <Link to="/" className={`transition-colors hover:text-primary ${isActive("/")}`}>
                {t("nav.home")}
              </Link>
              <Link to="/community" className={`transition-colors hover:text-primary ${isActive("/community")}`}>
                {t("nav.community")}
              </Link>
              <Link to="/ai-chat" className={`transition-colors hover:text-primary ${isActive("/ai-chat")}`}>
                {t("nav.aiChat")}
              </Link>
              <Link to="/rankings" className={`transition-colors hover:text-primary ${isActive("/rankings")}`}>
                {t("nav.rankings")}
              </Link>
              <Link to="/achievements" className={`transition-colors hover:text-primary ${isActive("/achievements")}`}>
                {t("nav.achievements")}
              </Link>
              <Link to="/replays" className={`transition-colors hover:text-primary ${isActive("/replays")}`}>
                {t("nav.replays")}
              </Link>
              <Link to="/guide" className={`transition-colors hover:text-primary ${isActive("/guide")}`}>
                {t("nav.guide")}
              </Link>
            </nav>
          </div>

          {/* Right Side Actions */}
          <div className="flex items-center gap-1.5 md:gap-4">
            <Button size="sm" className="hidden md:inline-flex" onClick={() => setQuickMatchOpen(true)}>
              <Zap className="mr-1 h-4 w-4" />
              {t("common.quickStart")}
            </Button>

            {/* Desktop quick actions (收起于移动端「更多」菜单) */}
            <div className="hidden md:flex items-center gap-2 md:gap-4">
              <Button variant="outline" size="icon" className="relative" onClick={() => setFriendOpen(true)}>
                <Users className="h-4 w-4" />
                {requestCount > 0 && <span className="absolute -right-1 -top-1 rounded-full bg-red-500 px-1 text-[10px] text-white">{requestCount}</span>}
              </Button>

              <Button variant="outline" size="icon" aria-label={t("header.notifications")}>
                <Bell className="h-4 w-4" />
              </Button>

              <LanguageSelector />
            </div>

            {/* Wallet pill — 所有尺寸可见 */}
            <button
              type="button"
              onClick={() => navigate("/profile?tab=wallet")}
              aria-label={t("header.wallet")}
              className="flex items-center gap-1.5 bg-secondary/50 px-2 py-1 md:px-3 md:py-1.5 rounded-full text-xs md:text-sm font-medium hover:bg-secondary transition-colors"
            >
              <Coins className="h-3 w-3 md:h-4 md:w-4 text-yellow-500" />
              <span>{user?.coins ?? 0}</span>
            </button>

            {user ? (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" className="relative h-8 w-8 rounded-full">
                    <Avatar className="h-7 w-7 md:h-8 md:w-8">
                      <AvatarImage src={user.avatar} alt={displayName} />
                      <AvatarFallback>{displayName.slice(0, 2)}</AvatarFallback>
                    </Avatar>
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent className="w-56" align="end" forceMount>
                  <DropdownMenuLabel className="font-normal">
                    <div className="flex flex-col space-y-1">
                      <p className="text-sm font-medium leading-none">{displayName}</p>
                      <p className="text-xs leading-none text-muted-foreground">
                        uid: {user.id.substring(0, 6)}
                      </p>
                    </div>
                  </DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem asChild>
                    <Link to="/profile" className="cursor-pointer w-full flex items-center">
                      <User className="mr-2 h-4 w-4" />
                      {t("user.profile")}
                    </Link>
                  </DropdownMenuItem>
                  <DropdownMenuItem>
                    <LayoutGrid className="mr-2 h-4 w-4" />
                    {t("user.myRooms")}
                  </DropdownMenuItem>
                  <DropdownMenuItem asChild>
                    <Link to="/admin" className="cursor-pointer w-full flex items-center">
                      <Shield className="mr-2 h-4 w-4" />
                      {t("user.admin")}
                    </Link>
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem className="text-red-600" onClick={logout}>
                    {t("user.logout")}
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            ) : (
              <Button variant="outline" onClick={() => void redirectToSsoLogin()}>
                {t("common.login")}
              </Button>
            )}

            {/* Mobile overflow menu：好友 / 通知 / 语言选择收纳于此 */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="icon" className="relative md:hidden" aria-label={t("header.more")}>
                  <MoreHorizontal className="h-4 w-4" />
                  {requestCount > 0 && <span className="absolute -right-1 -top-1 rounded-full bg-red-500 px-1 text-[10px] text-white">{requestCount}</span>}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-60">
                <DropdownMenuItem onClick={() => setFriendOpen(true)}>
                  <Users className="mr-2 h-4 w-4" />
                  <span className="flex-1">{t("friend.title")}</span>
                  {requestCount > 0 && <span className="ml-auto min-w-5 rounded-full bg-red-500 px-1.5 text-center text-[10px] font-medium text-white">{requestCount}</span>}
                </DropdownMenuItem>
                <DropdownMenuItem>
                  <Bell className="mr-2 h-4 w-4" />
                  <span className="flex-1">{t("header.notifications")}</span>
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuLabel className="font-normal text-xs text-muted-foreground">
                  {t("i18n.selectLanguage")}
                </DropdownMenuLabel>
                <LanguageMenuItems />
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
        <div className="border-t bg-slate-50/80">
          <div className="container flex flex-wrap items-center gap-x-4 gap-y-0.5 px-4 py-1 text-xs text-muted-foreground">
            <span>{t("header.onlinePlayers", { count: onlineTotal })}</span>
            <span>{t("header.friendRequests", { count: requestCount })}</span>
            <span>{t("header.inviteNotices", { count: 0 })}</span>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="page-enter flex-1 container py-4 md:py-6 px-4">
        <Outlet />
      </main>

      {/* Mobile Bottom Navigation (Scheme 1A) */}
      <div className={`md:hidden fixed bottom-0 left-0 right-0 bg-white border-t border-slate-200 z-50 pb-safe ${isGameRoute ? "hidden" : ""}`}>
        <div className="grid grid-cols-5 h-16">
          <Link to="/" className={`flex flex-col items-center justify-center gap-1 ${isMobileActive("/")}`}>
            <Home className="h-6 w-6" />
            <span className="text-[10px] font-medium">{t("mobile.home")}</span>
          </Link>
          <Link to="/rankings" className={`flex flex-col items-center justify-center gap-1 ${isMobileActive("/rankings")}`}>
            <Trophy className="h-6 w-6" />
            <span className="text-[10px] font-medium">{t("mobile.rankings")}</span>
          </Link>
          <button className="flex flex-col items-center justify-center gap-1 text-slate-400" onClick={() => setQuickMatchOpen(true)}>
            <Zap className="h-6 w-6" />
            <span className="text-[10px] font-medium">{t("mobile.quickMatch")}</span>
          </button>
          <Link to="/replays" className={`flex flex-col items-center justify-center gap-1 ${isMobileActive("/replays")}`}>
            <PlayCircle className="h-6 w-6" />
            <span className="text-[10px] font-medium">{t("mobile.replays")}</span>
          </Link>
          <Link to="/guide" className={`flex flex-col items-center justify-center gap-1 ${isMobileActive("/guide")}`}>
            <BookOpen className="h-6 w-6" />
            <span className="text-[10px] font-medium">{t("mobile.guide")}</span>
          </Link>
        </div>
      </div>

      <FriendPanel open={friendOpen} onOpenChange={setFriendOpen} userKey={userKey} />
      <QuickMatchDialog open={quickMatchOpen} onOpenChange={setQuickMatchOpen} displayName={displayName} />
    </div>
  );
};

export default MainLayout;
