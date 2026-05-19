# RoomController 接口说明

## 简介
- 职责：房间创建、查询、入座与 AI 补位。
- 鉴权要求：创建、入座、添加 AI 均需要 `X-Auth-Token`。
- 基础路径：`/api/games/{gameId}/rooms`

## 接口列表

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/` | 按玩法查询房间列表 |
| POST | `/` | 创建房间 |
| GET | `/{roomId}` | 查询房间详情 |
| POST | `/{roomId}/join` | 玩家入座 |
| POST | `/{roomId}/ai` | 添加 AI 补位 |

## 接口详情

### GET `/` - 房间列表

**用途**：按玩法分页返回房间列表。默认只返回等待中房间，避免老房间无限累积导致大厅响应变慢。

**Query**

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| page | Integer | 否 | 1 | 页码，从 1 开始 |
| size | Integer | 否 | 30 | 每页数量，最大 100 |
| status | String | 否 | WAITING | `WAITING` / `PLAYING` |

**返回值**

`PagedResponse<RoomResponse>`

**返回值（核心字段）**

| 字段 | 类型 | 说明 |
|---|---|---|
| items | Array | 房间列表 |
| page | Integer | 当前页 |
| size | Integer | 每页数量 |
| total | Long | 总数 |
| id | String | 房间 ID |
| status | String | WAITING/PLAYING |
| maxPlayers | Integer | 最大人数 |
| seatCount | Integer | 当前座位数 |
| seats | Array | 座位列表 |

### POST `/` - 创建房间

**用途**：创建新房间。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| roomName | String | 是 | 房间名 |
| isPrivate | Boolean | 是 | 是否私密房间 |
| password | String | 条件必填 | 私密房间密码，私密房必须提供，长度 4-64 位 |
| commMode | String | 否 | 沟通模式 |
| config | Object | 否 | 玩法配置 |

**返回值**
- 201：`RoomResponse`

### GET `/{roomId}` - 房间详情

**用途**：返回房间基础信息和座位信息。

### POST `/{roomId}/join` - 玩家入座

**用途**：登录用户入座，并返回 `selfPlayerId` 供前端展示当前席位。

**Headers**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| X-Auth-Token | String | 是 | 登录 token |

**Body**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| displayName | String | 是 | 前端显示名；后端以登录用户昵称为准 |
| password | String | 条件必填 | 私密房间加入密码 |

**返回值**
- 200：`RoomResponse`（包含 `selfPlayerId`）

**错误码/常见错误**

| 错误码 | 说明 |
|---|---|
| 400 | 房间已满、请求参数错误 |
| 401 | 未登录 |
| 403 | 私密房间密码错误 |

**WS 联动**
- 成功后推送座位事件到 `/topic/room/{roomId}/seat`：
  - 人类入座：`type=JOIN`

### POST `/{roomId}/ai` - 添加 AI

**用途**：按 `personaId` 增加一个 AI 座位。

**鉴权与权限**：必须登录，且调用者必须是房主，房间必须处于 `WAITING`。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| personaId | String | 是 | AI 人设 ID |

**返回值**
- 200：`RoomResponse`

**错误码/常见错误**

| 错误码 | 说明 |
|---|---|
| 401 | 未登录 |
| 403 | 非房主 |
| 400 | 房间已满、房间状态不允许、AI 人设不存在 |

**WS 联动**
- 成功后推送座位事件到 `/topic/room/{roomId}/seat`：
  - AI 补位：`type=AI_ADDED`
