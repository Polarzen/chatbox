# AstrBot 4.29.4 普通账号会话接口核对

## 日常实例

本机只读查询了当前目标实例 OpenAPI 密钥的权限及有效数量。有效且未撤销的密钥共一把，含 `chat:admin` 和 `config:edit_admin`。Chatbox 的命名密钥元数据只有一个条目，未记录权限。没有解密凭据、读取个人聊天或创建测试会话。

日常实例收到的验收 HTTP 请求为 **0 次**。在该实例上验证两轮对话仍需独立的普通 `chat` 凭据。

## 同机隔离实例的普通账号实测

独立目录和数据库加载了本机安装的 AstrBot 4.29.4 服务端代码，测试 Key 只有 `chat` 权限。只使用随机生成的用户名和会话编号。本机 Ollama 只有嵌入模型，因此上游采用回环 OpenAI 兼容测试服务。它根据每次实际收到的 `messages` 计算回复，只在当前问题是追问且此前的用户消息包含代号时才回答“青松47”。

| 对同一合成会话的操作 | 实际结果 |
| --- | --- |
| 发送“本轮代号是青松47” | HTTP 200，SSE 的 `plain` 事件为“记住了。”，随后收到 `message_saved` 和 `end`。 |
| 列表查询带 `username`、`platform_id=webchat`、`page_size=1` | HTTP 200，两页均为 `status=ok`、`total=1`。第一页只有刚创建的会话，第二页为空。 |
| 使用测试 Key 查询该会话详情 | HTTP 200，正文为 `{"status":"error","message":"Permission denied","data":null}`（摘录）。调用方需要检查正文状态。 |
| 复用原会话询问“刚才的代号是什么？只回答代号。” | HTTP 200，SSE 的 `plain` 事件为“青松47”。测试上游在第二轮收到了第一轮用户消息和本轮问题。 |

原生接口验收发出 2 次聊天 POST 和 3 次读取。上游实际收到了 3 次模型请求，分别用于两轮聊天正文和一次新会话标题。标题请求的系统提示明确要求生成会话标题。

**第 1 页。** 以下是实际响应，替换了测试会话和用户名。标题字段是 `display_name`，`is_group` 返回整数，两个时间字段均为带 UTC 偏移的字符串。

```json
{
  "status": "ok",
  "message": null,
  "data": {
    "sessions": [{
      "session_id": "<测试会话>",
      "platform_id": "webchat",
      "creator": "<测试用户>",
      "display_name": "代号测试",
      "is_group": 0,
      "created_at": "2026-11-24T04:14:11.375009+00:00",
      "updated_at": "2026-11-24T04:14:11.534854+00:00"
    }],
    "page": 1,
    "page_size": 1,
    "total": 1
  }
}
```

**第 2 页。** `total` 仍为 1，表示符合查询条件的会话总数。

```json
{"status":"ok","message":null,"data":{"sessions":[],"page":2,"page_size":1,"total":1}}
```

本机 4.29.4 的 `get_platform_sessions_by_creator_paginated` 先按 `updated_at` 降序排列，时间相同时按 `session_id` 降序，再应用偏移量和页长。这条排序规则经源码核对，本次实测仅创建了一个会话。[SQLite 查询实现](https://github.com/AstrBotDevs/AstrBot/blob/v4.29.4/astrbot/core/db/sqlite.py)

## 共享服务承接原会话

通过 `shared_service/service.py` 的 `init`、`bootstrap`、`serve` 命令启动独立服务。两个 HTTP 客户端分别使用主人和受邀设备凭据。创建房间前，服务用原测试用户名、`platform_id=webchat`、`page=1`、`page_size=100` 核对原会话，发出 1 次列表 GET。核对成功后主人导入以上两轮问答，受邀客户端随后读到了这两轮的相同正文。

1. 受邀客户端发送“刚才的代号是什么？只回答代号。”，主人读取到已完成的回复“青松47”。
2. 主人发送“请再说一遍。刚才的代号是什么？只回答代号。”，受邀客户端也读取到“青松47”。两次发送都先返回 HTTP 202。
3. 主人使用相同请求编号和问题重发最后一轮，得到 HTTP 200 和原轮次。两个客户端最终读到完全相同的四轮历史。共享阶段的模型上游恰好收到 **2 次**流式聊天请求，两次都含最初的代号；重复发送没有产生额外模型请求。

最终一次完整运行中，AstrBot 的 HTTP 访问记录累计有 **4 次聊天 POST、3 次列表 GET 和 1 次详情 GET**。3 次列表请求均只查询测试用户的 WebChat 会话，其中 2 次用于验证分页，1 次用于创建前核对。模型上游累计有 **5 次请求**，即 4 次聊天和 1 次自动标题。本次共享联通使用本机 HTTP 客户端和真实共享服务。设备界面、外网 HTTPS 和日常实例属于另外的验收环境。

## 本机安装源码确认的接口行为

| 方法与路径 | 请求和响应 |
| --- | --- |
| `POST /api/v1/chat` | OpenAPI 请求体指定 `username` 和 `session_id`。服务端将新会话绑定到该用户名，复用已有会话时检查创建者。成功建立流后返回 `text/event-stream`。 |
| `GET /api/v1/chat/sessions` | OpenAPI 使用查询参数 `username` 过滤创建者。可传 `platform_id=webchat`、`page`、`page_size`，列表页码从 1 开始，单页最多 100 条。响应的 `data` 包含 `sessions`、`page`、`page_size` 和 `total`。 |
| `GET /api/v1/chat/sessions/{session_id}` | 服务端按认证身份核对创建者。OpenAPI 列表里的合成用户名和详情路由认证身份可能不同。创建者不匹配时服务代码返回 `Permission denied`。成功时 `data` 含 `session`、`history`、`page`、`page_size`、`total` 和 `has_more` 等字段。历史第一页是最近的记录，单页最多 1000 条。 |

成功读取详情时的字段来自服务端源码。上面的隔离实测记录了普通测试身份实际收到的拒绝响应。

源码入口见 [OpenAPI 路由](https://github.com/AstrBotDevs/AstrBot/blob/v4.29.4/astrbot/dashboard/api/open_api.py)、[OpenAPI 服务](https://github.com/AstrBotDevs/AstrBot/blob/v4.29.4/astrbot/dashboard/services/open_api_service.py)、[会话详情路由](https://github.com/AstrBotDevs/AstrBot/blob/v4.29.4/astrbot/dashboard/api/chat.py) 和 [会话服务](https://github.com/AstrBotDevs/AstrBot/blob/v4.29.4/astrbot/dashboard/services/chat_service.py)。
