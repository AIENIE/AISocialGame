import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { MessageSquare, Heart, Share2, PenSquare, Flame, Hash } from "lucide-react";
import { toast } from "sonner";
import { useAuth } from "@/hooks/useAuth";
import { communityApi, getApiErrorMessage } from "@/services/api";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CommunityPost } from "@/types";

const Community = () => {
  const { displayName, avatar } = useAuth();
  const [input, setInput] = useState("");
  const [publishError, setPublishError] = useState("");
  const queryClient = useQueryClient();

  const { data: posts = [], isLoading } = useQuery<CommunityPost[]>({
    queryKey: ["community", "posts"],
    queryFn: communityApi.list,
  });

  const publishMutation = useMutation({
    mutationFn: () => communityApi.create(input.trim(), [], displayName),
    onSuccess: () => {
      setInput("");
      setPublishError("");
      queryClient.invalidateQueries({ queryKey: ["community", "posts"] });
      toast.success("发布成功");
    },
    onError: (error: unknown) => {
      const message = getApiErrorMessage(error, "发布失败，请稍后重试");
      setPublishError(message);
      toast.error(message);
    },
  });

  const likeMutation = useMutation({
    mutationFn: (id: string) => communityApi.like(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["community", "posts"] }),
  });

  const publish = () => {
    const value = input.trim();
    if (!value) {
      toast.error("请输入内容后再发布");
      return;
    }
    setPublishError("");
    publishMutation.mutate();
  };

  const hotTopics = useMemo(() => {
    const tagCount: Record<string, number> = {};
    posts.forEach((p) => p.tags.forEach((t) => { tagCount[t] = (tagCount[t] || 0) + 1; }));
    return Object.entries(tagCount)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 4)
      .map(([title, count], idx) => ({ id: idx, title, views: `${count}条讨论` }));
  }, [posts]);

  return (
    <div className="max-w-6xl mx-auto grid grid-cols-1 lg:grid-cols-4 gap-8">
      <div className="hidden lg:block space-y-6">
        <Card className="border-slate-200 shadow-sm">
          <CardContent className="p-4 space-y-2">
            <Button variant="ghost" className="w-full justify-start font-bold text-blue-600 bg-blue-50">
              <MessageSquare className="mr-2 h-4 w-4" /> 综合讨论
            </Button>
            <Button variant="ghost" className="w-full justify-start text-slate-600">
              <Flame className="mr-2 h-4 w-4" /> 最新动态
            </Button>
            <Button variant="ghost" className="w-full justify-start text-slate-600">
              <Hash className="mr-2 h-4 w-4" /> 话题榜
            </Button>
          </CardContent>
        </Card>
      </div>

      <div className="lg:col-span-2 space-y-6">
        <Card className="border-slate-200 shadow-sm">
          <CardContent className="p-4">
            <div className="flex gap-3 md:gap-4">
              <Avatar className="h-8 w-8 md:h-10 md:w-10">
                <AvatarImage src={avatar} />
                <AvatarFallback>ME</AvatarFallback>
              </Avatar>
              <div className="flex-1 space-y-3">
                <Input
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  placeholder="分享你的游戏趣事..."
                  className="bg-slate-50 border-slate-200 text-sm"
                />
                <div className="flex justify-between items-center">
                  <div className="flex gap-2 text-slate-400 text-sm">
                    <Button variant="ghost" size="sm" className="h-8 px-2 text-xs md:text-sm"><Hash className="h-3 w-3 md:h-4 md:w-4 mr-1" /> 话题</Button>
                  </div>
                  <Button data-testid="community-publish-btn" size="sm" className="bg-blue-600 hover:bg-blue-700 h-8 text-xs md:text-sm" onClick={publish} disabled={publishMutation.isPending}>
                    <PenSquare className="h-3 w-3 md:h-4 md:w-4 mr-2" /> 发布
                  </Button>
                </div>
                {publishError && <div className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{publishError}</div>}
              </div>
            </div>
          </CardContent>
        </Card>

        <Tabs defaultValue="recommend" className="w-full">
          <TabsList className="w-full justify-start bg-transparent border-b rounded-none h-auto p-0 mb-4 overflow-x-auto">
            <TabsTrigger value="recommend" className="data-[state=active]:bg-transparent data-[state=active]:shadow-none data-[state=active]:border-b-2 data-[state=active]:border-blue-600 rounded-none px-4 py-2 text-sm">推荐</TabsTrigger>
            <TabsTrigger value="latest" className="data-[state=active]:bg-transparent data-[state=active]:shadow-none data-[state=active]:border-b-2 data-[state=active]:border-blue-600 rounded-none px-4 py-2 text-sm">最新</TabsTrigger>
          </TabsList>

          <TabsContent value="recommend" className="space-y-4">
            {isLoading && <div className="text-slate-500 text-sm">正在加载社区内容...</div>}
            {!isLoading && posts.length === 0 && <div className="text-slate-500 text-sm">还没有人发帖，来当第一个分享的玩家吧！</div>}
            {posts.map(post => (
              <Card key={post.id} className="border-slate-200 shadow-sm hover:shadow-md transition-shadow">
                <CardHeader className="p-4 pb-2 flex flex-row items-start gap-3 md:gap-4 space-y-0">
                  <Avatar className="h-8 w-8 md:h-10 md:w-10">
                    <AvatarImage src={post.avatar} />
                    <AvatarFallback>{post.authorName[0]}</AvatarFallback>
                  </Avatar>
                  <div className="flex-1">
                    <div className="flex justify-between items-start">
                      <div>
                        <h4 className="font-bold text-sm">{post.authorName}</h4>
                        <p className="text-xs text-slate-500">{new Date(post.createdAt).toLocaleString()}</p>
                      </div>
                      <Button variant="ghost" size="icon" className="h-8 w-8 text-slate-400">
                        <Share2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="p-4 pt-2 space-y-3 md:space-y-4">
                  <p className="text-slate-800 leading-relaxed text-sm md:text-base whitespace-pre-line">{post.content}</p>
                  <div className="flex gap-2 flex-wrap">
                    {post.tags.map(tag => (
                      <Badge key={tag} variant="secondary" className="bg-blue-50 text-blue-600 hover:bg-blue-100 border-blue-100 text-xs">
                        #{tag}
                      </Badge>
                    ))}
                  </div>
                  <div className="flex items-center gap-6 pt-2 border-t border-slate-100">
                    <Button variant="ghost" size="sm" className="text-slate-500 hover:text-red-500 px-0 h-8 text-xs md:text-sm" onClick={() => likeMutation.mutate(post.id)}>
                      <Heart className="h-3 w-3 md:h-4 md:w-4 mr-1.5" /> {post.likes}
                    </Button>
                    <div className="text-xs text-slate-500 flex items-center gap-1">
                      <MessageSquare className="h-3 w-3 md:h-4 md:w-4" /> {post.comments}
                    </div>
                  </div>
                </CardContent>
              </Card>
            ))}
          </TabsContent>
        </Tabs>
      </div>

      <div className="hidden lg:block space-y-6">
        <Card className="border-slate-200 shadow-sm bg-slate-50/50">
          <CardHeader className="pb-3">
            <CardTitle className="text-base font-bold flex items-center">
              <Flame className="h-4 w-4 text-red-500 mr-2" /> 热门话题
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {hotTopics.length === 0 && <div className="text-slate-500 text-sm">暂无热门话题</div>}
            {hotTopics.map((topic, index) => (
              <div key={topic.id} className="flex items-center justify-between group">
                <div className="flex items-center gap-3 overflow-hidden">
                  <span className={`text-sm font-bold w-4 text-center ${index < 3 ? 'text-red-500' : 'text-slate-400'}`}>
                    {index + 1}
                  </span>
                  <span className="text-sm text-slate-700 truncate group-hover:text-blue-600 transition-colors">
                    {topic.title}
                  </span>
                </div>
                <span className="text-xs text-slate-400 whitespace-nowrap">{topic.views}</span>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>
    </div>
  );
};

export default Community;
