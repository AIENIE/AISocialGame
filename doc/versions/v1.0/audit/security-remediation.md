# AISocialGame v1.0 安全审计整改记录

- 整改日期：2026-05-19
- 输入报告：`doc/versions/v1.0/audit/security-audit.md`
- 整改范围：代码、配置模板、部署脚本、测试与项目文档

## 已处理项

| 审计问题 | 整改结果 |
|---|---|
| 仓库配置包含外部服务凭据 | `env.txt` 改为无敏感值模板；真实值改由未入库 `env.local`、系统环境或 CI/CD secret 注入 |
| 默认管理员/数据库弱口令 | 移除运行默认弱口令；非 test profile 启动和部署脚本会 fail-fast 校验 |
| MySQL/gRPC 明文默认 | MySQL 默认启用 TLS 参数；gRPC 默认 TLS；明文 gRPC 必须显式放行 |
| WebSocket 任意 origin | WS 与 HTTP CORS 共用白名单，不再允许任意 origin |
| 私密房密码不校验 | 私密房密码 BCrypt 存储；加入时必须校验密码 |
| 添加 AI 缺少权限 | 添加 AI 要求登录房主且房间处于等待中 |
| 游客身份可冒用 | 对局、观战状态和 WS 均要求登录用户身份，不再信任客户端玩家 ID |
| 未登录 AI chat 消耗系统身份 | `/api/ai/chat` 统一要求登录 |
| token 长期 localStorage | 用户 token 与管理员 token 改为 `sessionStorage` |
| 原始异常消息外泄 | 通用异常返回固定文案，内部细节写服务端日志 |

## 剩余外部动作

- 轮换 user-service 内部 token、pay-service JWT、ai-service HMAC caller/secret。
- 确认外部 MySQL 与 user/pay/ai gRPC 服务支持 TLS。
- 如果测试服短期必须使用明文 gRPC，部署时设置 `APP_SECURITY_ALLOW_PLAINTEXT_GRPC=true`，并在运维记录中说明网络隔离边界。

## 验证命令

```bash
cd backend && mvn test
cd frontend && pnpm build
git diff --check
rg -n "JWT header marker" . --glob '!doc/versions/v1.0/audit/security-audit.md'
rg -n "setAllowedOriginPatterns\\(\"\\*\"\\)|legacy player identity header" backend frontend doc --glob '!doc/versions/v1.0/audit/security-audit.md'
```
