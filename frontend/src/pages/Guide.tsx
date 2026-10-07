import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { BookOpen, Sparkles } from "lucide-react";
import { gameName } from "@/i18n/gameTexts";

const Guide = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [selectedGame, setSelectedGame] = useState("werewolf");

  const tutorialSteps = [
    t("guide.tutorialSteps.0"),
    t("guide.tutorialSteps.1"),
    t("guide.tutorialSteps.2"),
    t("data.settlementGuide"),
  ];

  const roleGuide = [
    { game: gameName("werewolf"), role: t("guide.role.seer"), desc: t("guide.role.seer.desc") },
    { game: gameName("werewolf"), role: t("guide.role.witch"), desc: t("guide.role.witch.desc") },
    { game: gameName("undercover"), role: t("guide.role.civilian"), desc: t("guide.role.civilian.desc") },
    { game: gameName("undercover"), role: t("guide.role.undercover"), desc: t("guide.role.undercover.desc") },
  ];

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{t("guide.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("guide.subtitle")}</p>
      </div>

      <Tabs defaultValue="tutorial" className="space-y-4">
        <TabsList className="grid w-full grid-cols-3">
          <TabsTrigger value="tutorial">{t("guide.tab.tutorial")}</TabsTrigger>
          <TabsTrigger value="rules">{t("guide.tab.rules")}</TabsTrigger>
          <TabsTrigger value="practice">{t("data.createGame")}</TabsTrigger>
        </TabsList>

        <TabsContent value="tutorial">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("guide.tutorialTitle")}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {tutorialSteps.map((step, index) => (
                <div key={step} className="rounded-md border bg-slate-50 px-3 py-2 text-sm">
                  <span className="mr-2 font-semibold text-slate-500">#{index + 1}</span>
                  {step}
                </div>
              ))}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="rules">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <BookOpen className="h-4 w-4" />
                {t("guide.roleTitle")}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {roleGuide.map((item) => (
                <div key={`${item.game}-${item.role}`} className="rounded-md border px-3 py-2">
                  <div className="mb-1 flex items-center gap-2">
                    <Badge variant="secondary">{item.game}</Badge>
                    <span className="font-medium">{item.role}</span>
                  </div>
                  <p className="text-sm text-muted-foreground">{item.desc}</p>
                </div>
              ))}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="practice">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("data.createGame")}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <p className="text-sm text-muted-foreground">{t("data.createGameDesc")}</p>
              <div className="flex gap-2">
                <Button variant={selectedGame === "werewolf" ? "default" : "outline"} onClick={() => setSelectedGame("werewolf")}>
                  {gameName("werewolf")}
                </Button>
                <Button variant={selectedGame === "undercover" ? "default" : "outline"} onClick={() => setSelectedGame("undercover")}>
                  {gameName("undercover")}
                </Button>
              </div>
              <Button onClick={() => navigate(selectedGame === "werewolf" ? "/create/werewolf" : "/create/undercover")}>
                <Sparkles className="mr-2 h-4 w-4" />
                {t("data.createGame")}
              </Button>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default Guide;
