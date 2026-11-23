# chatbox Windows 桌面版

Windows 桌面版使用 AstrBot OpenAPI 收发文字和图片，也可播放服务端返回的 WAV 语音附件。每个服务配置可以保留多个会话，桌面端和 Android 各自保存本机历史。

## 安装与构建

Windows 0.1.10 安装包为 `Chatbox-0.1.10.msi`。[下载 MSI](https://github.com/Polarzen/chatbox/releases/download/v0.1.10/Chatbox-0.1.10.msi) · [下载 SHA256SUMS.txt](https://github.com/Polarzen/chatbox/releases/download/v0.1.10/SHA256SUMS.txt) · [v0.1.10 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.10)

将 MSI 和 `SHA256SUMS.txt` 下载到同一目录。在该目录打开 PowerShell，运行 `Get-FileHash .\Chatbox-0.1.10.msi -Algorithm SHA256`，确认输出的哈希值与 `SHA256SUMS.txt` 中该 MSI 对应的值一致。从 0.1.5 至 0.1.9 升级时先退出 Chatbox，再运行 MSI 并沿用原安装目录。安装完成后从开始菜单打开 Chatbox。0.1.1 用户先阅读[旧卸载器升级说明](#从-011-升级)。

如需自行构建，在 Windows 安装 JDK 17 和 WiX Toolset 3.x，然后在仓库根目录运行下列命令。

    .\gradlew.bat -p desktop test desktopSmoke
    .\gradlew.bat -p desktop packageMsi

MSI 生成于 `desktop/build/compose/binaries/main/msi/`。从源码启动可运行 `.\gradlew.bat -p desktop run`。

## 连接和发送

主窗口直接打开上次的会话。点击右上角的连接状态进入「连接设置」，选择运行中的 Launcher 实例，或填写自行部署的 HTTPS 地址。手动 HTTP 仅支持本机 `127.0.0.1`。

首次使用时，点击「添加密钥」，填写本地名称和 AstrBot OpenAPI Key。已配置多把密钥时，点开顶栏密钥菜单，再点目标名称即可验证并切换。验证失败时可以继续使用原先已连接的密钥。「管理密钥」提供改名、权限信息和移除操作。名称用于本机识别，AstrBot WebUI 的 OpenAPI 设置列出服务端授予的权限。普通文字聊天需要 `chat`，图片上传与下载还需要 `file`；运行管理员命令需要服务端登记的管理员身份和 `chat:admin`。

把资源管理器中的 PNG 或 JPEG 拖到聊天区或输入区，或点击「＋」选图。发送前可以逐张移除图片、补充说明。按 Enter 发送，按 Shift+Enter 换行。回复气泡显示图片上传进度、等待服务器响应和等待首段回复的时间。

桌面客户端可接收、显示服务端回复中的图片；生成图片由服务端图像提供方或插件决定，例如 [Chatbox Draw 插件说明](../integrations/astrbot_plugin_chatbox_draw/README.md)。详见 [0.1.5 增量验收报告](../docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md)。

长回复逐段显示时，聊天区会跟随最新内容。向上滚动可停在原处阅读，点击「回到最新」或滚回底部即可继续跟随。

如果回复包含支持的 WAV 语音附件，下载后可点击「播放语音」，播放期间可点击「停止播放」。点击消息下的「复制正文」可将整段文字复制到 Windows 剪贴板。只需部分文字时，可选中文字后按 Ctrl+C。

Chatbox 按实例 UUID 找回上次选择的 Launcher 实例，确认当前回环地址后使用已保存的凭据重连。旧版按完整 URL 查找本地历史，地址大小写或端口变化可能使旧对话从界面消失。新版向 `/api/v1/chat/sessions` 核对原用户名和会话 UUID，匹配的旧会话可继续聊天，其余旧记录保留供离线打开。

新建的会话排在左栏顶部。点击会话标题原位切换，长列表可用细滚动条或鼠标滚轮浏览。滚动条的鼠标操作区域宽 14 dp，列表无需滚动时自动隐藏滚动条。展开行末的「⋯」，选择「删除会话」并确认，即可删除该服务配置下的一段本机聊天记录。窄窗口可从顶栏展开历史栏。

打开会话行末的「⋯」，选择「重命名」即可保存自定义名称。在同一对话框点击「恢复默认名称」，旧会话会沿用原有标题。没有旧标题时，取第一条非空提问作为名称，纯图片会话显示「图片会话」，空会话显示「新会话」。

如果上传因权限不足被拒绝，消息会显示缺少的权限，更新 Key 后可点「重试这条」。如果回复中断，服务端可能已经收件。退出时仍在等待回复的消息会在重开后标为「上次回复未确认」。Chatbox 保留记录，由用户确认是否重发。

## 搜索本机聊天记录

按 Ctrl+K，或点击顶栏放大镜「搜索聊天记录」，输入标题或正文中的词语。搜索覆盖当前服务档案下所有本机会话，英文匹配忽略大小写。要查另一个服务档案，打开「连接设置」，在「已保存的配置」中选中目标档案，再返回主窗口搜索。

每处匹配单独列出，显示会话名称和高亮片段。来源标为「会话标题」，或注明轮次和发言方，例如「第 2 轮 · 助手」。点击标题结果即可打开对应会话。点击用户或助手消息的结果会跳到那处文字并短暂高亮，同一段中多次出现的词也可分别定位。点击「清空」可换关键词，按 Esc 或点击「取消」可返回聊天。

## 窗口置顶与外观

点击顶栏图钉「窗口置顶」，Chatbox 会保持在其他普通窗口上方。点击另一个窗口后，仍可在其中输入。再次点击图钉「取消置顶」，窗口恢复普通前后顺序。

点击顶栏「窗口外观」，选择「浅色（Light）」或「深色（Dark）」。拖动「窗口不透明度」滑块可即时预览整个窗口的变化，范围为 50% 至 100%，松开即可保存。重启时会沿用本机保存的主题、不透明度和置顶状态。「恢复 100%」让窗口完全不透明。如需一起恢复浅色、100% 不透明度并取消置顶，点击「恢复默认外观」。

## 本机数据与密钥

聊天记录保存在 `%LOCALAPPDATA%\Chatbox\history.json`，历史与备份均为明文。迁移旧版历史前，Chatbox 会在同一目录保留 `history.json.pre-migration.bak`。若同名备份已存在且内容不同，另存为 `history.json.<UUID>.pre-migration.bak`。

密钥值使用当前 Windows 用户的 DPAPI 保存在 `%LOCALAPPDATA%\Chatbox\credentials`。每份服务配置独立保存密钥名称、内部引用和活动项。0.1.5 已保存的单把 Key 升级后显示为「原有密钥」，可直接改名。移除密钥只影响本机；需要撤销服务端授权时，请在 AstrBot WebUI 操作。

## 从 0.1.1 升级

0.1.1 的卸载程序可能递归清理整个安装目录。从 0.1.2 起，新 MSI 已移除自身的递归清理动作，但升级仍会调用已安装的旧版卸载程序。检测到 0.1.1 的旧清理设置时，新安装包会阻止升级。若安装目录与源码、下载文件或其他数据共用，请保留现有安装，避免直接卸载旧版。管理员需先备份共享目录，将旧清理目标重定向到独立空目录，并核实它不是重解析点（包括符号链接和目录联接），再按安装器提示继续升级。

## 发布与验收记录

0.1.10 的本机历史搜索、窗口置顶与外观设置见[0.1.10 验收记录](../docs/WINDOWS_010_RELEASE_ACCEPTANCE.md)。

0.1.9 的长回复跟随与会话重命名验收见[0.1.9 验收记录](../docs/WINDOWS_019_RELEASE_ACCEPTANCE.md)。0.1.8 增加了 WAV 语音播放和消息正文复制。[0.1.8 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.8)

0.1.7 发布时，托管 Windows 已验证从 0.1.5 原目录升级，日常机器已验证从 0.1.6 原目录升级。安装版现场验收覆盖会话列表、冷启动恢复和资源管理器混合拖放。[0.1.7 验收记录](../docs/WINDOWS_017_RELEASE_ACCEPTANCE.md) · [0.1.5 历史发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.5)

0.1.4 修复了从 0.1.3 升级时 JVM 文件漏装导致应用无法启动的问题。[0.1.4 及更早版本验收报告](../docs/WINDOWS_V1_ACCEPTANCE.md) · [0.1.5 增量验收报告](../docs/CHATBOX_015_IMAGE_GENERATION_ACCEPTANCE.md) · [0.1.6 候选版验收记录](../docs/WINDOWS_016_RELEASE_ACCEPTANCE.md)

[官方 AstrBot OpenAPI 文档](https://docs.astrbot.app/dev/openapi.html)
