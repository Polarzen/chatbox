# Chatbox · 小鲸鱼

独立的个人 AI 助手安卓客户端。无需 QQ、NapCat 或 OneBot，后端仍采用 AstrBot。当前为首版源码原型，未完成真实手机和真实服务器验收。原生界面采用 Kotlin + Jetpack Compose。

## 首版范围

- 中文单会话界面，逐步显示文字回复；同一服务地址复用同一会话 ID。
- 手机本地保存聊天记录，关闭应用后可继续。暂不拉取服务端历史，也不会自动迁移 QQ 历史。
- 连接故障会提示，且不会自动重发。手动重发需要确认，因为上一条消息可能已到达服务器。
- 使用 AstrBot 官方 OpenAPI `POST /api/v1/chat`，按 v4.29.4 上游结构处理文字 SSE，跳过工具内部及推理事件。语音、通知、多个会话和 Codex 审批留待后续。

## 构建与配置

使用 JDK 17 或 21、Android SDK Platform 35、Build Tools 35.0.0。运行：

    ./gradlew testDebugUnitTest assembleDebug

Windows 下用 `gradlew.bat`。如果构建通过，调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

1. 在 AstrBot WebUI 的 OpenAPI 设置中创建仅含聊天权限的 Key，不授予管理员权限。
2. 打开 Chatbox，输入手机可访问且证书受信任的 HTTPS 服务地址和 Key。Key 只保留在当前界面内存，关闭重开后需重输。模型密钥仍留在服务端。
3. 发送一条文字并确认实时回复。关闭并重新打开应用、重输 Key，再验证是否能继续原会话。应单独验收「小鲸鱼」人格是否随服务器当前配置生效，不预设 QQ 人格或插件规则会自动适用。

聊天记录存于手机应用私有空间，未另行加密，并禁用 Android 备份。不要提交真实聊天记录、令牌或含隐私的日志；不建议为了测试直接暴露 AstrBot 管理面板。

[官方 HTTP API](https://docs.astrbot.app/dev/openapi.html) · [v4.29.4 服务端事件实现](https://github.com/AstrBotDevs/AstrBot/blob/v4.29.4/astrbot/dashboard/services/chat_service.py)
