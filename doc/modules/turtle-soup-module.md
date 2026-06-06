# 海龟汤玩法模块

> 更新时间：2026-06-06

## 模块目标

M5 将海龟汤作为第三个可玩玩法接入平台，验证 GameEngine 插件化架构可以支持合作式语言推理游戏。当前实现以规则闭环保证稳定可验收，AI 用于玩家追问增强和主持体验补充，AI 失败时不影响主流程。

## 核心行为

- 玩法 ID：`turtle_soup`。
- 阶段：`QUESTIONING`、`SETTLEMENT`。
- 房间支持 1-6 名玩家，AI 玩家通过现有 `RoomService.addAi` 加入。
- 开局后玩家只看到题名、汤面、问题上限、问答历史和已确认线索。
- 汤底只保存在服务端 `GameState.data.solution`，结算前不返回给玩家端。
- 玩家通过统一 `/action` 提交：
  - `ASK_QUESTION`：向 AI 主持提出是/否问题。
  - `SUBMIT_SOLUTION`：提交最终汤底解答。
- 开启 `aiAssist` 且房间内存在 AI 玩家时，真人提问后 AI 玩家会自动追问一个题库推荐问题；重复问题会跳过。

## 规则与安全

- 主持判断采用本地规则：关键线索命中返回“是”，排除项返回“否”，接近项返回“接近但不准确”，其他返回“不重要”。
- 正确解答需要命中每道题定义的核心关键词组。
- 仍复用 M4 safety gate 拦截危险内容、隐私和 Prompt Injection。
- 海龟汤解答语境下允许玩家说“汤底是...”；其他玩法仍将“汤底是”等隐藏信息表达作为泄露风险处理。

## 回放与结算

- 事件流记录 `GAME_START`、`TURTLE_SOUP_QUESTION`、`TURTLE_SOUP_SOLUTION_SUBMITTED`、`TURTLE_SOUP_SOLVED`、`GAME_END`。
- 结算后生成 `game_archives`，`/replays` 与 `/replay/{archiveId}` 可播放服务端回放。
- 胜负标记：
  - `SOLVED`：玩家提交正确汤底。
  - `FAILED`：问题次数用完仍未解开。

## 入口实现

- 后端：`backend/src/main/java/com/aisocialgame/engine/TurtleSoupGameEngine.java`
- 前端：`frontend/src/pages/games/TurtleSoupRoom.tsx`
- 验收：`frontend/tests/acceptance-real.spec.ts`
