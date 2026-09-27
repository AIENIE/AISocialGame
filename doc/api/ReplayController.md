# ReplayController API

更新时间：2026-09-22。基础路径 `/api/replays`；旧新规则版本共用授权，不由 ruleVersion 放宽权限。

## GET /api/replays

公开归档分页，返回 `PagedResponse<ReplayArchiveView>`（items/page/size/total）。

| 参数 | 含义 |
|---|---|
| gameId | 可选玩法 ID |
| playerId | 可选参与者 ID，仅依据归档公开参与快照过滤，不赋予该玩家视角 |
| from / to | 可选 ISO LocalDateTime，按 finishedAt 闭区间；from > to 返回 400；沿用服务端旧 LocalDateTime 约定，不擅自偏移历史时区 |
| page / size | 默认 0/20；负页归零，size 限制1～100 |

先过滤再分页，以 finishedAt DESC、id DESC 稳定排序。

## GET /api/replays/my

必须通过现有用户认证；未登录 401。只返回当前用户参与的归档，支持 gameId/from/to/page/size，不接受冒用 playerId。不能用公开列表或本地缓存替代认证失败。

## GET /api/replays/{archiveId}

返回公开摘要 `ReplayArchiveView`，不存在 404。字段保留 id/roomId/gameId/roomName/winner/playerCount/totalRounds/durationSeconds/eventCount/summary/startedAt/finishedAt/createdAt/aiQualitySummary，增加 `players` 公开投影：playerId、displayName、seatNumber、ai、personaId。无完整原始快照、role、word、内部记忆或 Prompt。

新归档 aiQualitySummary 使用 instanceId 关联 trace，包含 traceCount/fallbackCount/association=INSTANCE_ID/legacyUnattributed。最后一项只是房间内无法归属的旧样本数量，不属于当前局指标，不可据此计算本局 fallback。

## GET /api/replays/{archiveId}/events

| 参数 | 含义 |
|---|---|
| viewMode | PUBLIC（默认）或 PLAYER；普通接口请求 GOD 返回403，未知值400 |
| viewerPlayerId | 兼容旧参数；如提供必须等于当前认证用户，否则403；权限永远取认证身份 |

PUBLIC 仅公开事件。PLAYER 需登录且参与本局，返回公开事件及 visibleToPlayerIds 包含本人者；非参与者403。事件 ID、seq 顺序、原始可见性保留。

响应 `ReplayDetailResponse`：archive/viewMode/events/availableViews。未登录或非参与者可用 PUBLIC，本局参与者可用 PUBLIC/PLAYER。播放器按事件位置呈现最终身份，不提前渲染后续揭示。401/403禁止旧查询缓存或本地回放降级。

## 管理端独立入口

`GET /api/admin/replays/{archiveId}/events`，必须通过现有 `@CurrentAdmin` 管理员会话认证。返回 GOD 完整事件及 availableViews=PUBLIC/GOD；不接受普通用户身份代替管理员。AI 质检通过 quality.instanceId/eventIds 定位归档与事件；秘密、完整依据、记忆及诊断只允许管理端查看。
