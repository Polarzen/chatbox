# chatbox

Chatbox 是使用 AstrBot 的个人 AI 助手客户端，无需 QQ、NapCat 或 OneBot。Windows 和 Android 各自保存本机历史。[Windows 安装、连接与升级说明](desktop/README.md)

## Windows 下载

Windows 0.1.9 安装包为 `Chatbox-0.1.9.msi`（55,517,184 字节）。[下载 MSI](https://github.com/Polarzen/chatbox/releases/download/v0.1.9/Chatbox-0.1.9.msi) · [下载 SHA256SUMS.txt](https://github.com/Polarzen/chatbox/releases/download/v0.1.9/SHA256SUMS.txt) · [v0.1.9 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.9)

在 PowerShell 中运行 `Get-FileHash .\Chatbox-0.1.9.msi -Algorithm SHA256`，确认结果与 `SHA256SUMS.txt` 中的 `69af357970b78ca85ad58b0195a05ae5770636c18f45f26b4204f6f4ebbcf2e9` 一致。从 0.1.5、0.1.6、0.1.7 或 0.1.8 升级时先退出 Chatbox，再运行 MSI 并沿用原安装目录。0.1.1 用户先阅读[旧卸载器升级说明](desktop/README.md#从-011-升级)。

## Windows 0.1.9

长回复逐段显示时，聊天区会跟随最新内容。向上滚动可停在原处阅读，点击「回到最新」或滚回底部即可继续跟随。

打开会话行末的「⋯」，选择「重命名」即可保存自定义名称。在同一对话框点击「恢复默认名称」，旧会话会沿用原有标题。没有旧标题时，取第一条非空提问作为名称，纯图片会话显示「图片会话」，空会话显示「新会话」。[0.1.9 验收记录](docs/WINDOWS_019_RELEASE_ACCEPTANCE.md)

## Windows 0.1.8

服务端返回支持的 WAV 语音附件时，可在回复气泡中点击「播放语音」或「停止播放」。点击「复制正文」可复制整段消息，也可选中部分文字后按 Ctrl+C。

长会话列表使用细滚动条，鼠标操作区域宽 14 dp。列表无需滚动时自动隐藏滚动条。点击会话标题可原位切换，在行末菜单可删除会话。还可拖入多张图片，在回复气泡中查看上传与等待进度，或从顶栏切换 Launcher 实例、自部署服务和命名密钥。

0.1.7 发布时，托管 Windows 已验证从 0.1.5 原目录升级，日常机器已验证从 0.1.6 原目录升级。安装版现场验收覆盖会话列表、冷启动恢复和资源管理器混合拖放。[0.1.7 验收记录](docs/WINDOWS_017_RELEASE_ACCEPTANCE.md) · [0.1.5 历史发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.5)

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
