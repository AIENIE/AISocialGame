import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Heart, PenSquare } from "lucide-react";
import { communityApi, getApiErrorMessage, getApiErrorCode } from "@/services/api";
import { localizeErrorMessage } from "@/i18n/errors";
import { useAuth } from "@/hooks/useAuth";
import { DataState } from "@/components/DataState";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";

const Community = () => {
  const { t } = useTranslation();
  const { user, avatar, displayName } = useAuth();
  const [input, setInput] = useState("");
  const [publishError, setPublishError] = useState("");
  const client = useQueryClient();
  const posts = useQuery({ queryKey: ["community", "posts"], queryFn: communityApi.list });
  const publish = useMutation({ mutationFn: () => communityApi.create(input.trim(), []),
    onSuccess: () => { setInput(""); setPublishError(""); void client.invalidateQueries({ queryKey: ["community", "posts"] }); toast.success(t("community.publishSuccess")); },
    onError: error => { const message = localizeErrorMessage(getApiErrorMessage(error, t("community.publishFailed")), "community.publishFailed", getApiErrorCode(error)); setPublishError(message); },
  });
  const like = useMutation({ mutationFn: communityApi.like,
    onSuccess: () => { void client.invalidateQueries({ queryKey: ["community", "posts"] }); },
    onError: () => toast.error(t("data.failed")),
  });
  if (!user) return null;
  return <div className="mx-auto max-w-3xl space-y-6">
    <h1 className="text-2xl font-bold">{t("nav.community")}</h1>
    <Card><CardContent className="p-4"><form className="flex gap-3" onSubmit={event => { event.preventDefault(); if (input.trim()) { setPublishError(""); publish.mutate(); } }}>
      <Avatar><AvatarImage src={avatar} /><AvatarFallback>{displayName.slice(0, 1)}</AvatarFallback></Avatar>
      <div className="flex-1 space-y-3"><Input aria-label={t("community.placeholder")} maxLength={1024} value={input} onChange={event => setInput(event.target.value)} placeholder={t("community.placeholder")} />
        <Button data-testid="community-publish-btn" disabled={publish.isPending || !input.trim()} type="submit"><PenSquare className="mr-2 h-4 w-4" />{t("community.publish")}</Button>
        {publishError && <p role="alert" className="text-sm text-red-600">{publishError}</p>}
      </div>
    </form></CardContent></Card>
    {(posts.isPending || posts.error || !posts.data?.length) && <DataState loading={posts.isPending} error={posts.error} empty={!posts.data?.length} onRetry={() => void posts.refetch()} />}
    {!posts.isPending && !posts.error && posts.data?.map(post => <Card key={post.id}><CardHeader className="flex-row gap-3 space-y-0">
      <Avatar><AvatarImage src={post.avatar} /><AvatarFallback>{post.authorName.slice(0, 1)}</AvatarFallback></Avatar><div><h2 className="font-semibold">{post.authorName}</h2>{post.createdAt && <p className="text-xs text-muted-foreground">{new Date(post.createdAt).toLocaleString()}</p>}</div>
    </CardHeader><CardContent className="space-y-3"><p className="whitespace-pre-line">{post.content}</p><div className="flex flex-wrap gap-2">{post.tags.map(tag => <Badge key={tag} variant="secondary">#{tag}</Badge>)}</div>
      <Button variant="ghost" size="sm" disabled={like.isPending} onClick={() => like.mutate(post.id)}><Heart className="mr-2 h-4 w-4" />{post.likes}</Button>
    </CardContent></Card>)}
  </div>;
};
export default Community;
