# AiController 接口说明

基址为 `/api/ai`，以下接口均要求现行用户会话。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/models` | 读取当前调用者可见的模型目录 |
| POST | `/chat` | 提交聊天并返回实际模型结果 |
| POST | `/chat/stream` | 通过 SSE 传递实际聊天结果的内容片段与结束事件 |

聊天请求包括可选 `model`（最多 128 字符）及必填 `messages`（1 至 20 条）；每条消息使用当前 `AiMessageRequest` 的角色和内容约束。响应字段为 `content`、`modelKey`、`promptTokens`、`completionTokens`。SSE 结束事件包含模型和用量，其余事件通过 `content` 传递片段。

调用使用现行金额制预算、余额校验及实际费用结算；token 字段记录用量，不作为旧 token 扣费规则。未知调用结果保留原始身份并按现行机制查询，错误通过失败响应呈现。SSE 沿用当前超时、并发限制与取消处理。

文本向量和 OCR 空壳接口已退役。项目内不再提供相关请求 DTO 或 gRPC 包装；共享服务的完整 proto 契约副本继续保留。

实际入口与字段定义见 `backend/src/main/java/com/aisocialgame/controller/AiController.java` 和 `backend/src/main/java/com/aisocialgame/dto/`。
