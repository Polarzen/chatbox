# Chatbox · 小鲸鱼

Chatbox 是使用 AstrBot 的个人 AI 助手客户端，无需 QQ、NapCat 或 OneBot。Windows 和 Android 各自保存本机历史。[Windows 安装、连接与升级说明](desktop/README.md)

Windows 最新版 0.1.5 可从 [Chatbox 0.1.5 MSI](https://github.com/Polarzen/chatbox/releases/download/v0.1.5/Chatbox-0.1.5.msi) 和 [SHA256SUMS.txt](https://github.com/Polarzen/chatbox/releases/download/v0.1.5/SHA256SUMS.txt) 下载，或查看 [v0.1.5 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.5)。在 PowerShell 中用 `Get-FileHash .\Chatbox-0.1.5.msi -Algorithm SHA256` 与校验文件比对；退出 Chatbox 后，将 MSI 安装到原目录。Windows 0.1.4 修复了 0.1.3 升级时漏装 Java 运行时文件、导致应用无法启动的问题。0.1.1 用户先阅读[旧卸载器升级说明](desktop/README.md#从-011-升级)。[Windows 0.1.4 历史验收记录](docs/WINDOWS_V1_ACCEPTANCE.md) · [0.1.5 增量验收报告](docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md)

首次连接时，选择正在运行的 AstrBot Launcher 实例，输入同时具有 `chat` 和 `file` 权限的 OpenAPI Key，并点击一次「连接 / 验证」即可收发文字和图片。桌面端接收并显示服务端回复的图片；图片生成由 AstrBot 服务端的图像服务或插件提供。

以下为 Kotlin + Jetpack Compose 实现的 Android 首版原型，仍待真实手机与服务端联合验收。

## Android 客户端范围

- 中文单会话界面，逐步显示文字回复；同一服务地址复用同一会话 ID。
- 手机本地保存聊天记录，关闭应用后可继续。暂不拉取服务端历史，也不会自动迁移 QQ 历史。
- 连接故障会提示，且不会自动重发。手动重发需要确认，因为上一条消息可能已到达服务器。
- 使用 AstrBot 官方 OpenAPI `POST /api/v1/chat`，按 v4.29.4 上游结构处理文字 SSE，跳过工具内部及推理事件。语音、通知、多个会话和 Codex 审批留待后续。

## Android 构建与配置

使用 JDK 17 或 21、Android SDK Platform 35、Build Tools 35.0.0。运行：

    ./gradlew testDebugUnitTest assembleDebug

Windows 下用 `gradlew.bat`。如果构建通过，调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

1. 在 AstrBot WebUI 的 OpenAPI 设置中创建仅含聊天权限的 Key，不授予管理员权限。
2. 打开 Chatbox，输入手机可访问且证书受信任的 HTTPS 服务地址和 Key。Key 只保留在当前界面内存，关闭重开后需重输。模型密钥仍留在服务端。
3. 发送一条文字并确认实时回复。关闭并重新打开应用、重输 Key，再验证是否能继续原会话。应单独验收「小鲸鱼」人格是否随服务器当前配置生效，不预设 QQ 人格或插件规则会自动适用。

聊天记录存于手机应用私有空间，未另行加密，并禁用 Android 备份。不要提交真实聊天记录、令牌或含隐私的日志；不建议为了测试直接暴露 AstrBot 管理面板。

[官方 HTTP API](https://docs.astrbot.app/dev/openapi.html) · [v4.29.4 服务端事件实现](https://github.com/AstrBotDevs/AstrBot/blob/v4.29.4/astrbot/dashboard/services/chat_service.py)
