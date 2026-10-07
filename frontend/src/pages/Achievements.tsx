import { useTranslation } from "react-i18next";
const Achievements = () => {
  const { t } = useTranslation();
  return <section className="space-y-3 p-6"><h1 className="text-xl font-semibold">{t("data.unavailable")}</h1><p className="text-muted-foreground">{t("data.unavailableDesc")}</p></section>;
};
export default Achievements;
