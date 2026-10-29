# AdminBillingController 接口说明

基址：`/api/admin/billing`  
鉴权：全部接口要求 HttpOnly 管理员会话 cookie

## GET `/balance`

- 用途：查询用户余额（本地专属积分 + 通用积分快照）
- Query：`userId` (long, required)
- 响应：`BalanceView`

## GET `/ledger`

- 用途：查询本地积分流水
- Query
  - `userId` (long, required)
  - `page` (int, optional, default `1`)
  - `size` (int, optional, default `20`)
- 响应：`AdminLedgerPageResponse`

## POST `/adjust`

- 用途：客服补发/扣回积分
- Body
  - `userId` (long, required)
  - `deltaTemp` (long, optional，可负)
  - `deltaPermanent` (long, optional，可负)
  - `reason` (string, required)
  - `requestId` (string, optional，幂等键)
- 响应：`BalanceView`

## POST `/reversal`

- 用途：按原始 `requestId` 冲正一笔流水
- Body
  - `userId` (long, required)
  - `originalRequestId` (string, required)
  - `reason` (string, required)
- 响应：`BalanceView`

## POST `/redeem-codes`

- 用途：创建本项目兑换码（用于发放本地专属积分）
- Body
  - `code` (string, optional，留空自动生成)
  - `tokens` (long, required，>0)
  - `creditType` (string, optional，默认 `CREDIT_TYPE_PERMANENT`)
  - `maxRedemptions` (int, optional，>0，留空表示不限)
  - `validFrom` (Instant, optional)
  - `validUntil` (Instant, optional，必须晚于 `validFrom`)
  - `active` (boolean, optional，默认 `true`)
- 响应：`AdminRedeemCodeResponse`
