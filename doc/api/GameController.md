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
