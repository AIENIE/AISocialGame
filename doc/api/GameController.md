# GameController 接口说明

基址：`/api/games`

## GET /
- **用途**：获取可用游戏列表（用于首页/大厅）。
- **响应 200**
```json
[
  {
    "id": "werewolf",
    "name": "狼人杀",
    "description": "...",
    "coverUrl": "Moon",
    "tags": ["逻辑推理","社交"],
    "minPlayers": 6,
    "maxPlayers": 12,
    "status": "ACTIVE",
    "onlineCount": 1240,
    "configSchema": [ { "id": "playerCount", "type": "select", ... } ]
  }
]
```

## GET /{id}
- **用途**：获取指定游戏详情与配置 schema。
- **响应 200**：`Game` 对象。
- **错误**：404 游戏不存在。


## 当前 v2 定义（2026-09-22）

/games 及 /games/{id} 从注册的 GameRuleSet.definition 获取权威元数据：ruleVersion、engineBacked、minPlayers/maxPlayers、configSchema、阶段及角色。RoomService 创建与开局使用相同定义作配置校验，旧规则路由保留。前端本地资源只负责翻译与展示，加载错误不能用旧目录配置创建房间。

公共配置增加 aiDifficulty（select：1 简单、2 娱乐、3 进阶，默认2），独立于 personaId，不扩大信息权限或改变合法动作。海龟汤题目选项增加 random，开局解析成一次性的具体题目 ID/版本；目录不返回私密事实。无新增公共玩法配置端点。
