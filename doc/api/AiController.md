# AiController

## 简介

- 职责：封装 AI 网关能力（模型、对话、SSE 流式输出、向量、OCR）。
- 鉴权要求：`/models` 无需鉴权；其余接口需 `X-Auth-Token`。
- 基础路径：`/api/ai`
- 计费说明：
  - chat/embeddings 成功后按 token 扣减本地专属积分并写入本地账本 `CONSUME` 流水。
  - 扣减口径：`chat = promptTokens + completionTokens`，`embeddings = promptTokens`。
  - 余额不足返回 `400`（`专属积分不足，请先充值或兑换`）。

## 接口列表

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | /api/ai/models | 查询可用模型 |
| POST | /api/ai/chat | 非流式对话 |
| POST | /api/ai/chat/stream | SSE 流式对话 |
| POST | /api/ai/embeddings | 生成文本向量 |
| POST | /api/ai/ocr | 图片/文档 OCR 解析 |

## 接口详情

### GET /api/ai/models - 查询模型列表

**用途**：返回 ai-service 可用模型清单。

**鉴权**：无需鉴权

**请求参数**

Path params：无

Query params：无

Body：无

**返回值**

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| id | Long | 模型 ID | `1` |
| displayName | String | 模型名 | `"gpt-4o-mini"` |
| provider | String | 提供方 | `"openai"` |
| inputRate | Double | 输入费率 | `0.15` |
| outputRate | Double | 输出费率 | `0.6` |
| type | String | 模型类型 | `"MODEL_TYPE_TEXT"` |

**示例请求**

```bash
curl -X GET "http://localhost:11031/api/ai/models"
```

**示例响应**

```json
[
  {
    "id": 1,
    "displayName": "gpt-4o-mini",
    "provider": "openai",
    "inputRate": 0.15,
    "outputRate": 0.6,
    "type": "MODEL_TYPE_TEXT"
  }
]
```

**错误码/常见错误**

| 错误码 | 说明 |
|--------|------|
| 502 | AI 服务调用失败 |

### POST /api/ai/chat - 非流式对话

**用途**：调用 ChatCompletions 获取完整回答。

**鉴权**：需要 `X-Auth-Token`

**请求参数**

Path params：无

Query params：无

Body：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| model | String | 否 | 指定模型 | `"gpt-4o-mini"` |
| messages | Array | 是 | 对话消息列表 | `[{...}]` |

**请求限制**

- `messages` 最多 20 条。
- 单条 `role` 最多 32 字符。
- 单条 `content` 最多 4000 字符。

**返回值**

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| content | String | 回复文本 | `"你好"` |
| modelKey | String | 实际使用模型 | `"gpt-4o-mini"` |
| promptTokens | Long | 输入 token 数 | `10` |
| completionTokens | Long | 输出 token 数 | `8` |

**示例请求**

```bash
curl -X POST "http://localhost:11031/api/ai/chat" \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token: <token>" \
  -d '{
    "messages": [{"role":"user","content":"你好"}]
  }'
```

**示例响应**

```json
{
  "content": "你好，有什么可以帮你？",
  "modelKey": "gpt-4o-mini",
  "promptTokens": 10,
  "completionTokens": 8
}
```

**错误码/常见错误**

| 错误码 | 说明 |
|--------|------|
| 401 | 未登录 |
| 400 | 专属积分不足，请先充值或兑换 |
| 502 | AI 服务调用失败 |

### POST /api/ai/chat/stream - SSE 流式对话

**用途**：基于 gRPC Unary 结果分块推送，前端可流式渲染。后端使用受控线程池和单用户并发限制保护 SSE 资源。

**鉴权**：需要 `X-Auth-Token`

**请求参数**

Path params：无

Query params：无

Body：与 `/api/ai/chat` 一致

**返回值**

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| content | String | 当前分块内容 | `"你"` |
| done | Boolean | 是否结束 | `false` |
| modelKey | String | 完成事件返回 | `"gpt-4o-mini"` |
| promptTokens | Long | 完成事件返回 | `10` |
| completionTokens | Long | 完成事件返回 | `8` |

**示例请求**

```bash
curl -N -X POST "http://localhost:11031/api/ai/chat/stream" \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token: <token>" \
  -d '{
    "messages": [{"role":"user","content":"你好"}]
  }'
```

**示例响应**

```text
data: {"content":"你","done":false}
data: {"content":"好","done":false}
data: {"content":"","done":true,"modelKey":"gpt-4o-mini","promptTokens":10,"completionTokens":8}
```

**错误码/常见错误**

| 错误码 | 说明 |
|--------|------|
| 401 | 未登录 |
| 429 | 流式请求过多或服务繁忙 |
| 400 | 专属积分不足，请先充值或兑换 |
| 502 | AI 服务调用失败 |

### POST /api/ai/embeddings - 生成向量

**用途**：调用 Embeddings 接口返回向量结果。

**鉴权**：需要 `X-Auth-Token`

**请求参数**

Path params：无

Query params：无

Body：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| input | String[] | 是 | 输入文本数组 | `["文本A","文本B"]` |
| model | String | 否 | 模型名 | `"text-embedding-3-large"` |
| normalize | Boolean | 否 | 是否归一化，默认 true | `true` |

**返回值**

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| modelKey | String | 模型标识 | `"text-embedding-3-large"` |
| dimensions | int | 维度 | `1536` |
| embeddings | float[][] | 向量数组 | `[[0.1,0.2],[0.3,0.4]]` |
| promptTokens | Long | 输入 token 数 | `24` |

**示例请求**

```bash
curl -X POST "http://localhost:11031/api/ai/embeddings" \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token: <token>" \
  -d '{
    "input": ["文本A", "文本B"],
    "normalize": true
  }'
```

**示例响应**

```json
{
  "modelKey": "text-embedding-3-large",
  "dimensions": 1536,
  "embeddings": [[0.1, 0.2], [0.3, 0.4]],
  "promptTokens": 24
}
```

**错误码/常见错误**

| 错误码 | 说明 |
|--------|------|
| 401 | 未登录 |
| 400 | input 为空 |
| 400 | 专属积分不足，请先充值或兑换 |

### POST /api/ai/ocr - OCR 解析

**用途**：图片或文档 OCR 解析，支持 TEXT/MARKDOWN/JSON 输出。

**鉴权**：需要 `X-Auth-Token`

**请求参数**

Path params：无

Query params：无

Body：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| imageUrl | String | 否 | 图片 URL（三选一） | `"https://example.com/a.png"` |
| imageBase64 | String | 否 | Base64 图片（三选一） | `"base64..."` |
| documentUrl | String | 否 | 文档 URL（三选一） | `"https://example.com/a.pdf"` |
| model | String | 否 | 模型名 | `"ocr-model"` |
| pages | String | 否 | PDF 页码范围 | `"1-3"` |
| outputType | String | 否 | TEXT/MARKDOWN/JSON | `"TEXT"` |

**返回值**

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| requestId | String | 请求 ID | `"req-001"` |
| modelKey | String | 模型标识 | `"ocr-model"` |
| outputType | String | 输出类型 | `"TEXT"` |
| content | String | 解析内容 | `"识别文本"` |
| rawJson | String | 原始 JSON（可选） | `"{...}"` |

**示例请求**

```bash
curl -X POST "http://localhost:11031/api/ai/ocr" \
  -H "Content-Type: application/json" \
  -H "X-Auth-Token: <token>" \
  -d '{
    "imageUrl": "https://example.com/a.png",
    "outputType": "TEXT"
  }'
```

**示例响应**

```json
{
  "requestId": "req-001",
  "modelKey": "ocr-model",
  "outputType": "TEXT",
  "content": "识别文本",
  "rawJson": ""
}
```

**错误码/常见错误**

| 错误码 | 说明 |
|--------|------|
| 401 | 未登录 |
| 502 | AI 服务调用失败 |
