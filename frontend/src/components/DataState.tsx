import { isAxiosError } from "axios";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { HttpApiError } from "@/services/apiError";

const errorStatus = (error: unknown) => isAxiosError(error) ? error.response?.status : error instanceof HttpApiError ? error.status : undefined;

export function DataState({ loading, error, onRetry, empty = false }: { loading?: boolean; error?: unknown; onRetry?: () => void; empty?: boolean }) {
  const { t } = useTranslation();
  const status = errorStatus(error);
  const key = loading ? "data.loading" : error ? status === 403 ? "data.denied" : status === 404 ? "data.notFound" : "data.failed" : empty ? "data.empty" : "data.notFound";
  return <div className="space-y-3 rounded-lg border p-6 text-sm text-muted-foreground" role={error ? "alert" : "status"}>
    <p>{t(key)}</p>
    {!!error && onRetry && <Button variant="outline" onClick={onRetry}>{t("data.retry")}</Button>}
  </div>;
}
