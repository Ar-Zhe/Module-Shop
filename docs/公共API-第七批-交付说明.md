# 公共 API · 第七批交付说明（HTTP / REST，可选）

## 一、本批交付

| 交付物 | 说明 |
| --- | --- |
| `shop/http/ShopHttpApi` | 基于 JDK `com.sun.net.httpserver` 的 REST 服务（**零新增依赖**） |
| `shop/http/MiniJson` | 极简 JSON 解析/序列化（~230 行，避免引入 Gson/Jackson） |
| `Config` 新增 `[http_api]` 段 | `enabled / bind / port / token / allowWrite / rateLimit` |
| `ShopServerEvents` | `ServerStartedEvent` 启动、`ServerStoppingEvent` 停止 |
| 9 个端点 | health / balance / mail / history / mall + add / take / reward / mail(POST) |

## 二、安全设计（按最初要求逐条落实）

| 要求 | 实现 |
| --- | --- |
| 独立设计、默认不启用 | `httpApiEnabled=false`；关闭时不占用端口 |
| 鉴权 | `Authorization: Bearer <token>`，**常量时间比较**；token 为空 → **拒绝启动** |
| 最小权限 | 默认 `httpApiAllowWrite=false` → 写端点 403 |
| 网络监听范围 | 默认 `127.0.0.1`；绑非回环时 WARN 提示 |
| 请求验证 | 请求体上限 64KB、JSON 解析失败降级为空对象、UUID/物品 id 逐项校验 |
| 速率限制 | 每 IP 每分钟令牌桶（默认 60，`0` = 不限） |
| 审计日志 | `config/module_shop/http-audit.log`：时间/IP/方法/路径/状态/耗时 |
| 防重放 | 写操作**强制 requestId**，与存档幂等表联动，重复直接 `DUPLICATE_REQUEST` |
| 线程 | HTTP 线程只做编解码，全部业务 `server.execute(...)` 到主线程（5 秒超时） |

## 三、实测证据（真服务器 + Invoke-RestMethod，token=<测试用令牌>）

```
GET  /api/v1/health
  -> {"ok":true,"version":"1.0.0","write":true,"thread":false}
GET  /api/v1/health（无 token）
  -> HTTP 401 ✔
GET  /api/v1/players/{uuid}/balance
  -> {"ok":true,"unlimited":true,"balance":1384,"maxBalance":0,"currency":"星币"}
GET  /api/v1/players/{uuid}/mail
  -> {"ok":true,"unclaimed":1,"total":1}
GET  /api/v1/players/{uuid}/history?limit=2
  -> {"ok":true,"items":[{...kubejs -1 -> 1384（记账测试）},{...external 2 -> 1384（桥接测试）}]}
POST /api/v1/players/{uuid}/add  {"amount":10,"requestId":"http-1"}
  -> {"ok":true,"credited":10}            （余额 1384 → 1394）
POST 同一 requestId 再发一次
  -> HTTP 400（DUPLICATE_REQUEST，**未重复加钱**）✔
POST /take {"amount":99999999,...}
  -> HTTP 400（INSUFFICIENT_FUNDS，**未扣款**）✔
POST /reward {"requestId":"http-reward-1","money":5,"items":[{"id":"minecraft:diamond","count":2}]}
  -> {"ok":true}                          （余额 → 1399）
POST /mail {"title":"HTTP 邮件",...}
  -> {"ok":true,"mailId":"api-256233110475400"}
GET  /balance -> balance=1399 ；GET /mail -> unclaimed=3
```

审计日志（尾 8 行）实测正常：
```
2026-10-26 19:58:01 127.0.0.1 POST /api/v1/players/…/add    -> 200 (2ms)
2026-10-26 19:58:01 127.0.0.1 POST /api/v1/players/…/add    -> 400 (0ms)   ← 重复 requestId
2026-10-26 19:58:01 127.0.0.1 POST /api/v1/players/…/take   -> 400 (1ms)   ← 余额不足
2026-10-26 19:58:01 127.0.0.1 POST /api/v1/players/…/reward -> 200 (9ms)
2026-10-26 19:58:01 127.0.0.1 POST /api/v1/players/…/mail   -> 200 (1ms)
```
服务端 `Done (5.014s)`，`[shop-http] HTTP API 已启动：http://127.0.0.1:8765/api/v1/`，模组报错 0 条。
测试后已把配置**复原为默认关闭**。

## 四、已知限制

| 限制 | 说明 |
| --- | --- |
| 状态码粒度 | 业务失败统一 4xx，**以响应体 `error` 字段为准**（`INSUFFICIENT_FUNDS` / `DUPLICATE_REQUEST` / `PRICE_CHANGED`…） |
| 无 HTTPS | 本地回环/内网用；要上公网请在前面挂反向代理（Nginx/Caddy）做 TLS + IP 白名单 |
| 无 OAuth/多用户 | 单一共享 token；多调用方请各自部署独立 token（后续可做） |
| 写端点有限 | 只开放 add/take/reward/mail；购买/拍卖/求购仍只走 Java API |
| 依赖 JDK HttpServer | 仅适合低并发；高并发场景请用反向代理前置 |