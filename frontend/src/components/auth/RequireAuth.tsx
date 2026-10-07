import { Outlet, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/hooks/useAuth";
import { Button } from "@/components/ui/button";

export function RequireAuth() {
  const { t } = useTranslation();
  const location = useLocation();
  const { user, loading, status, refreshUser, redirectToSsoLogin } = useAuth();
  if (loading) return <p role="status" className="p-6">{t("auth.checking")}</p>;
  if (status === "error") return <div role="alert" className="space-y-4 p-6"><p>{t("auth.failed")}</p><Button onClick={() => void refreshUser()}>{t("data.retry")}</Button></div>;
  if (!user) return <section className="mx-auto max-w-lg space-y-4 rounded-xl border p-8">
    <h1 className="text-xl font-semibold">{t("auth.required")}</h1><p className="text-muted-foreground">{t("auth.description")}</p>
    <Button onClick={() => void redirectToSsoLogin(`${location.pathname}${location.search}${location.hash}`)}>{t("common.goLogin")}</Button>
  </section>;
  return <Outlet />;
}
