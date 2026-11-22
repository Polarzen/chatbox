# Chatbox Windows 桌面版

Windows 桌面版使用 AstrBot OpenAPI 收发文字和图片。每个服务配置可以保留多个会话，桌面端和 Android 各自保存本机历史。

## 安装与构建

从 [Chatbox 0.1.5 MSI](https://github.com/Polarzen/chatbox/releases/download/v0.1.5/Chatbox-0.1.5.msi) 和 [SHA256SUMS.txt](https://github.com/Polarzen/chatbox/releases/download/v0.1.5/SHA256SUMS.txt) 下载，或查看 [v0.1.5 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.5)。用 PowerShell 运行 `Get-FileHash .\Chatbox-0.1.5.msi -Algorithm SHA256`，与校验文件比对。退出 Chatbox 后，将 MSI 安装到原目录。0.1.4 修复了 0.1.3 升级时 JVM 文件漏装、导致应用无法启动的问题。0.1.1 用户先阅读下方的旧卸载器升级说明。[0.1.5 增量验收报告](../docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md)

开发验证用的 CI 产物可在 [Windows 验证工作流](https://github.com/Polarzen/chatbox/actions/workflows/desktop-windows.yml) 的成功运行中下载，产物名为 `Chatbox-Windows-MSI`，保留 7 天。CI 下载不能替代正式 Release 或实际升级验收。

如需自行构建，在 Windows 安装 JDK 17 和 WiX Toolset 3.x，然后在仓库根目录运行下列命令。

    .\gradlew.bat -p desktop test desktopSmoke
    .\gradlew.bat -p desktop packageMsi

MSI 生成于 `desktop/build/compose/binaries/main/msi/`。从源码启动可运行 `.\gradlew.bat -p desktop run`。

## 连接和发送

从「配置」选择正在运行的 AstrBot Launcher 实例，或填写自行部署服务的 HTTPS 地址。手动 HTTP 地址仅支持本机 `127.0.0.1`。在密码框填写 AstrBot OpenAPI Key 并点「连接 / 验证」。常规聊天使用仅含 `chat` 和 `file` 权限的 Key：`chat` 用于文字，`file` 用于图片上传和读取回复图片。选图后可直接发送，也可填写图片说明。

连接后，「当前 Key」显示已验证 Key 的公开标记（标准 Key 显示前缀，不显示完整密钥）。点击「密钥信息」可查看标记，并从对话框打开当前服务的 AstrBot WebUI OpenAPI 密钥设置；在密钥列表中按名称、前缀、创建时间、状态和权限范围核对正在使用的 Key。`chat:admin` 会按服务端 `admins_id` 将对应身份授予全局管理员权限；如确需管理能力，应使用专用 Key，不要与日常聊天 Key 共用。

桌面客户端可接收、显示服务端回复中的图片；生成图片由服务端图像提供方或插件决定，例如 [Chatbox Draw 插件说明](../integrations/astrbot_plugin_chatbox_draw/README.md)。详见 [0.1.5 增量验收报告](../docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md)。

Chatbox 按实例 UUID 找回上次选择的 Launcher 实例，确认当前回环地址后使用已保存的凭据重连。旧版按完整 URL 查找本地历史，地址大小写或端口变化可能使旧对话从界面消失。新版向 `/api/v1/chat/sessions` 核对原用户名和会话 UUID，匹配的旧会话可继续聊天，其余旧记录保留供离线打开。可在「会话」中切换对话，点「新会话」创建对话。

如果上传因权限不足被拒绝，消息会显示缺少的权限，更新 Key 后可点「重试这条」。如果回复中断，服务端可能已经收件。退出时仍在等待回复的消息会在重开后标为「上次回复未确认」。Chatbox 保留记录，由用户确认是否重发。

## 本机数据与密钥

聊天记录保存在 `%LOCALAPPDATA%\Chatbox\history.json`，历史与备份均为明文。迁移旧版历史前，Chatbox 会在同一目录保留 `history.json.pre-migration.bak`。若同名备份已存在且内容不同，另存为 `history.json.<UUID>.pre-migration.bak`。

Launcher 和 HTTPS 配置的 Key 通过验证后，使用当前 Windows 用户的 DPAPI 加密保存在 `%LOCALAPPDATA%\Chatbox\credentials`。在同一配置的密码框输入新 Key 并验证即可更换，点「清除」可删除保存的 Key。无法核实实例身份的手动本机 HTTP 地址仅在当前窗口使用输入的 Key。

## 从 0.1.1 升级

0.1.1 的卸载程序可能递归清理整个安装目录。从 0.1.2 起，新 MSI 已移除自身的递归清理动作，但升级仍会调用已安装的旧版卸载程序。检测到 0.1.1 的旧清理设置时，新安装包会阻止升级。若安装目录与源码、下载文件或其他数据共用，请保留现有安装，避免直接卸载旧版。管理员需先备份共享目录，将旧清理目标重定向到独立空目录，并核实它不是重解析点（包括符号链接和目录联接），再按安装器提示继续升级。

## 历史验收记录

0.1.4 及更早版本的 Windows 验收记录见 [历史验收报告](../docs/WINDOWS_V1_ACCEPTANCE.md)；0.1.5 见 [增量验收报告](../docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md)。

[官方 AstrBot OpenAPI 文档](https://docs.astrbot.app/dev/openapi.html)
