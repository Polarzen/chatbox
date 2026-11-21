# Chatbox Windows 桌面版

Chatbox 0.1.3 使用 AstrBot OpenAPI 收发文字和图片。每个服务配置可以保留多个会话，桌面端和 Android 各自保存本机历史。

## 安装与构建

0.1.3 仍是候选草稿，实际 Windows 提权升级待验。进度见 [Windows 0.1.3 安装验收记录](../docs/WINDOWS_V1_ACCEPTANCE.md)。正式版本发布后，从 [GitHub 正式版本索引](https://github.com/Polarzen/chatbox/releases) 下载 MSI 与 `SHA256SUMS.txt`，核对后运行 `Chatbox-0.1.3.msi`。已有 0.1.2 时，退出客户端并将 0.1.3 安装到原目录即可升级。从 0.1.1 升级请先阅读下方说明。

开发构建可在 [Windows 验证工作流](https://github.com/Polarzen/chatbox/actions/workflows/desktop-windows.yml) 的成功运行中下载，产物名为 `Chatbox-Windows-MSI`，保留 7 天。

如需自行构建，在 Windows 安装 JDK 17 和 WiX Toolset 3.x，然后在仓库根目录运行下列命令。

    .\gradlew.bat -p desktop test desktopSmoke
    .\gradlew.bat -p desktop packageMsi

安装包生成于 `desktop/build/compose/binaries/main/msi/Chatbox-0.1.3.msi`。从源码启动可运行 `.\gradlew.bat -p desktop run`。

## 连接和发送

从「配置」选择正在运行的 AstrBot Launcher 实例，或填写自行部署服务的 HTTPS 地址。手动 HTTP 地址仅支持本机 `127.0.0.1`。在密码框填写 AstrBot OpenAPI Key 并点「连接 / 验证」。收发文字需要 `chat` 权限，发送 PNG 或 JPEG 还需要 `file` 权限。选图后可直接发送，也可填写图片说明。

Chatbox 按实例 UUID 找回上次选择的 Launcher 实例，确认当前回环地址后使用已保存的凭据重连。旧版按完整 URL 查找本地历史，地址大小写或端口变化可能使旧对话从界面消失。新版向 `/api/v1/chat/sessions` 核对原用户名和会话 UUID，匹配的旧会话可继续聊天，其余旧记录保留供离线打开。可在「会话」中切换对话，点「新会话」创建对话。

如果上传因权限不足被拒绝，消息会显示缺少的权限，更新 Key 后可点「重试这条」。如果回复中断，服务端可能已经收件。退出时仍在等待回复的消息会在重开后标为「上次回复未确认」。Chatbox 保留记录，由用户确认是否重发。

## 本机数据与密钥

聊天记录保存在 `%LOCALAPPDATA%\Chatbox\history.json`，历史与备份均为明文。迁移旧版历史前，Chatbox 会在同一目录保留 `history.json.pre-migration.bak`。若同名备份已存在且内容不同，另存为 `history.json.<UUID>.pre-migration.bak`。

Launcher 和 HTTPS 配置的 Key 通过验证后，使用当前 Windows 用户的 DPAPI 加密保存在 `%LOCALAPPDATA%\Chatbox\credentials`。在同一配置的密码框输入新 Key 并验证即可更换，点「清除」可删除保存的 Key。无法核实实例身份的手动本机 HTTP 地址仅在当前窗口使用输入的 Key。

## 从 0.1.1 升级

0.1.1 的卸载程序可能递归清理整个安装目录。从 0.1.2 起，新 MSI 已移除自身的递归清理动作，但升级仍会调用已安装的旧版卸载程序。检测到 0.1.1 的旧清理设置时，新安装包会阻止升级。若安装目录与源码、下载文件或其他数据共用，请保留现有安装，避免直接卸载旧版。管理员需先备份共享目录，将旧清理目标重定向到独立空目录，并核实它不是重解析点（包括符号链接和目录联接），再按安装器提示继续升级。

## 0.1.2 开发阶段实测

安装版恢复了 4 个旧会话、8 条记录。完全退出再打开后，使用已保存的 Key 继续收发。通过 Launcher 正常重启实例，端口从 `18759` 变为 `20493`，后续回复仍带有原服务端会话 UUID。

AstrBot 在同一会话分别记录了纯 PNG 的 `image` 组件，以及 JPEG 图文消息的 `plain` 和 `image` 组件。两份服务端附件均可读取，SHA-256 与原图一致。早期普通文本请求曾经超时，当时用内置命令 `/sid` 验证了收发。

后续在同一份本地安装包上完成了三轮普通模型对话，重开并从 `20493` 重连到 `21056` 后仍能答出上文的船名和余票。两张未在文字中透露答案的图片均得到正确回答。纯 PNG 为红色圆形、149，JPEG 图文为蓝色三角形、882。重开后仍可看到图片记录和回答。正式安装包的实测结果见 Release 随包报告。

15 项桌面测试和启动冒烟检查通过。本地安装验收使用的 MSI 的 SHA-256 为 `AE12A3F653E279B7007544C40AA51CB3A6F88EA4BD37858C072F33C2F849D292`。CI 单独构建安装包。

[官方 AstrBot OpenAPI 文档](https://docs.astrbot.app/dev/openapi.html)
