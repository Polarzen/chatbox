# chatbox Windows 桌面版

Windows 桌面版使用 AstrBot OpenAPI 收发文字和图片。每个服务配置可以保留多个会话，桌面端和 Android 各自保存本机历史。

## 安装与构建

本轮 Windows 候选版是 **0.1.6**。退出正在运行的 Chatbox，双击交付的 `Chatbox-0.1.6.msi`，升级时沿用原安装目录。安装完成后从开始菜单打开 Chatbox。下方可下载已发布的 0.1.5。[0.1.6 候选版验收记录](../docs/WINDOWS_016_RELEASE_ACCEPTANCE.md)

从 [Chatbox 0.1.5 MSI](https://github.com/Polarzen/chatbox/releases/download/v0.1.5/Chatbox-0.1.5.msi) 和 [SHA256SUMS.txt](https://github.com/Polarzen/chatbox/releases/download/v0.1.5/SHA256SUMS.txt) 下载，或查看 [v0.1.5 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.5)。用 PowerShell 运行 `Get-FileHash .\Chatbox-0.1.5.msi -Algorithm SHA256`，与校验文件比对。退出 Chatbox 后，将 MSI 安装到原目录。0.1.4 修复了 0.1.3 升级时 JVM 文件漏装、导致应用无法启动的问题。0.1.1 用户先阅读下方的旧卸载器升级说明。[0.1.5 增量验收报告](../docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md)

开发验证用的 CI 产物可在 [Windows 验证工作流](https://github.com/Polarzen/chatbox/actions/workflows/desktop-windows.yml) 的成功运行中下载，产物名为 `Chatbox-Windows-MSI`，保留 7 天。CI 下载不能替代正式 Release 或实际升级验收。

如需自行构建，在 Windows 安装 JDK 17 和 WiX Toolset 3.x，然后在仓库根目录运行下列命令。

    .\gradlew.bat -p desktop test desktopSmoke
    .\gradlew.bat -p desktop packageMsi

MSI 生成于 `desktop/build/compose/binaries/main/msi/`。从源码启动可运行 `.\gradlew.bat -p desktop run`。

## 连接和发送

0.1.6 的主窗口直接打开上次的会话。点击右上角的连接状态进入「连接设置」，选择运行中的 Launcher 实例，或填写自行部署的 HTTPS 地址。手动 HTTP 仅支持本机 `127.0.0.1`。

首次使用时，点击「添加密钥」，填写本地名称和 AstrBot OpenAPI Key。已配置多把密钥时，点开顶栏密钥菜单，再点目标名称即可验证并切换。验证失败时可以继续使用原先已连接的密钥。「管理密钥」提供改名、权限信息和移除操作。名称用于本机识别，AstrBot WebUI 的 OpenAPI 设置列出服务端授予的权限。普通文字聊天需要 `chat`，图片上传与下载还需要 `file`；运行管理员命令需要服务端登记的管理员身份和 `chat:admin`。

把资源管理器中的 PNG 或 JPEG 拖到聊天区或输入区，或点击「＋」选图。发送前可以逐张移除图片、补充说明。按 Enter 发送，按 Shift+Enter 换行。回复气泡显示图片上传进度、等待服务器响应和等待首段回复的时间。

桌面客户端可接收、显示服务端回复中的图片；生成图片由服务端图像提供方或插件决定，例如 [Chatbox Draw 插件说明](../integrations/astrbot_plugin_chatbox_draw/README.md)。详见 [0.1.5 增量验收报告](../docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md)。

Chatbox 按实例 UUID 找回上次选择的 Launcher 实例，确认当前回环地址后使用已保存的凭据重连。旧版按完整 URL 查找本地历史，地址大小写或端口变化可能使旧对话从界面消失。新版向 `/api/v1/chat/sessions` 核对原用户名和会话 UUID，匹配的旧会话可继续聊天，其余旧记录保留供离线打开。

新建的会话排在左栏顶部。点击会话标题原位切换，长列表可用 ▲、▼、滑块或滚轮浏览。展开行末的「⋯」，选择「删除会话」并确认，即可删除该服务配置下的一段本机聊天记录。窄窗口可从顶栏展开历史栏。

如果上传因权限不足被拒绝，消息会显示缺少的权限，更新 Key 后可点「重试这条」。如果回复中断，服务端可能已经收件。退出时仍在等待回复的消息会在重开后标为「上次回复未确认」。Chatbox 保留记录，由用户确认是否重发。

## 本机数据与密钥

聊天记录保存在 `%LOCALAPPDATA%\Chatbox\history.json`，历史与备份均为明文。迁移旧版历史前，Chatbox 会在同一目录保留 `history.json.pre-migration.bak`。若同名备份已存在且内容不同，另存为 `history.json.<UUID>.pre-migration.bak`。

密钥值使用当前 Windows 用户的 DPAPI 保存在 `%LOCALAPPDATA%\Chatbox\credentials`。每份服务配置独立保存密钥名称、内部引用和活动项。0.1.5 已保存的单把 Key 升级后显示为「原有密钥」，可直接改名。移除密钥只影响本机；需要撤销服务端授权时，请在 AstrBot WebUI 操作。

## 从 0.1.1 升级

0.1.1 的卸载程序可能递归清理整个安装目录。从 0.1.2 起，新 MSI 已移除自身的递归清理动作，但升级仍会调用已安装的旧版卸载程序。检测到 0.1.1 的旧清理设置时，新安装包会阻止升级。若安装目录与源码、下载文件或其他数据共用，请保留现有安装，避免直接卸载旧版。管理员需先备份共享目录，将旧清理目标重定向到独立空目录，并核实它不是重解析点（包括符号链接和目录联接），再按安装器提示继续升级。

## 历史验收记录

0.1.4 及更早版本的 Windows 验收记录见 [历史验收报告](../docs/WINDOWS_V1_ACCEPTANCE.md)；0.1.5 见 [增量验收报告](../docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md)。

[官方 AstrBot OpenAPI 文档](https://docs.astrbot.app/dev/openapi.html)
