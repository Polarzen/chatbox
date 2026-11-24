# chatbox

0.1.18 支持为手机一次授权一个 PC 档案，从「最近对话 → 来自电脑」阅读多段旧聊天并双向续聊。[升级、配对与验收步骤](docs/PC_HISTORY_GUIDE.md)

Chatbox 是使用 AstrBot 的个人 AI 助手客户端，无需 QQ、NapCat 或 OneBot。默认聊天记录保存在本机。0.1.16 跨端测试版可以把选定的会话接到个人共享服务，让 Windows 和 Android 接续同一段 AstrBot 对话。[跨端测试版安装、配对和验收步骤](docs/SHARED_CHAT_GUIDE.md) · [Windows 安装与升级说明](desktop/README.md)

## Windows 下载

Windows 0.1.15 安装包为 `Chatbox-0.1.15.msi`。[下载 MSI](https://github.com/Polarzen/chatbox/releases/download/v0.1.15/Chatbox-0.1.15.msi) · [下载 SHA256SUMS.txt](https://github.com/Polarzen/chatbox/releases/download/v0.1.15/SHA256SUMS.txt) · [v0.1.15 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.15)

将 MSI 和 `SHA256SUMS.txt` 下载到同一目录。在该目录打开 PowerShell，运行 `Get-FileHash .\Chatbox-0.1.15.msi -Algorithm SHA256`，确认输出的哈希值与 `SHA256SUMS.txt` 中该 MSI 对应的值一致。从 0.1.5 至 0.1.14 升级时先退出 Chatbox，再运行 MSI 并沿用原安装目录。0.1.1 用户先阅读[旧卸载器升级说明](desktop/README.md#从-011-升级)。

## Windows 0.1.15

首次使用时，全局快捷键默认关闭。若要用 Ctrl+Alt+J 唤起 Chatbox，打开「桌面设置」，开启「全局快捷键」并保存。升级会沿用已保存的开关和自定义组合键。关闭快捷键后，仍可从托盘或开始菜单唤回窗口。

后台回复提醒独立设置，默认开启。Chatbox 按回复完成时的前后台状态判断是否提醒。若在图片或 WAV 仍在下载时关闭「后台回复完成时提醒」并保存，尚未交给 Windows 的提醒会撤销。重新开启后只提醒新取得提醒资格的回复。图片和语音仍会正常缓存。[桌面设置说明](desktop/README.md#windows-0115) · [0.1.15 验收记录](docs/WINDOWS_015_RELEASE_ACCEPTANCE.md)

## Windows 0.1.14

按 Ctrl+Alt+J 可从其他应用唤起 Chatbox，再按一次可收起当前窗口。用托盘菜单可以显示窗口或退出程序。窗口右上角的关闭按钮默认退出程序，在「桌面设置」可改为收起到托盘，也可更改全局快捷键或关闭后台回复提醒。点击回复通知可跳到对应轮次。[桌面操作说明](desktop/README.md#windows-0114) · [0.1.14 验收记录](docs/WINDOWS_014_RELEASE_ACCEPTANCE.md)

日常使用的上一版 0.1.13 已支持在本机 Launcher 会话发送 WhaleTask 命令，例如 `/task help` 和 `/task list`。0.1.14 继续支持这些命令，以及下文的截图粘贴、会话草稿和语音功能。

## Windows 0.1.12

按 Win+Shift+S 截图，再点击聊天输入框并按 Ctrl+V。截图会出现在待发区，可以继续粘贴图片、输入说明或移除附件。正常退出后，打开同一服务配置下的原会话，即可继续编辑文字、图片和已停止录制的语音。若外部图片已移动，请重新选择或移除缺失项。

点击输入区的麦克风开始录音，点击「停止」后可查看 WAV 总时长并试听，也可以移除录音重录。点击发送或使用发送快捷键，可单独发送语音，也可与文字、图片一起发送。自己发出的语音和助手回复中的每段 WAV 都在播放按钮旁显示总时长。

[0.1.12 验收记录](docs/WINDOWS_012_RELEASE_ACCEPTANCE.md)

0.1.11 修复了调整窗口大小或切换侧栏时顶栏可能导致应用崩溃的问题。

## Windows 0.1.10

按 Ctrl+K 或点击顶栏放大镜「搜索聊天记录」，即可搜索当前服务档案中所有会话的标题、提问和回复。每个结果显示会话名称、来源和命中片段。点击标题结果打开会话，点击消息结果跳到对应轮次并短暂高亮匹配文字。有多个服务档案时，先切换到要查找的档案。

顶栏图钉「窗口置顶」让 Chatbox 保持在普通窗口上方，同时可继续在其他窗口输入。再次点击「取消置顶」即可恢复普通窗口的前后顺序。在「窗口外观」中选择「浅色（Light）」或「深色（Dark）」，也可将整个窗口的不透明度调至 50% 至 100%。「恢复 100%」让窗口完全不透明，「恢复默认外观」同时恢复浅色、100% 不透明度并取消置顶。[0.1.10 验收记录](docs/WINDOWS_010_RELEASE_ACCEPTANCE.md)

## Windows 0.1.9

长回复逐段显示时，聊天区会跟随最新内容。向上滚动可停在原处阅读，点击「回到最新」或滚回底部即可继续跟随。

打开会话行末的「⋯」，选择「重命名」即可保存自定义名称。在同一对话框点击「恢复默认名称」，旧会话会沿用原有标题。没有旧标题时，取第一条非空提问作为名称，纯图片会话显示「图片会话」，空会话显示「新会话」。[0.1.9 验收记录](docs/WINDOWS_019_RELEASE_ACCEPTANCE.md)

## Windows 0.1.8

服务端返回支持的 WAV 语音附件时，可在回复气泡中点击「播放语音」或「停止播放」。点击「复制正文」可复制整段消息，也可选中部分文字后按 Ctrl+C。

长会话列表使用细滚动条，鼠标操作区域宽 14 dp。列表无需滚动时自动隐藏滚动条。点击会话标题可原位切换，在行末菜单可删除会话。还可拖入多张图片，在回复气泡中查看上传与等待进度，或从顶栏切换 Launcher 实例、自部署服务和命名密钥。

0.1.7 发布时，托管 Windows 已验证从 0.1.5 原目录升级，日常机器已验证从 0.1.6 原目录升级。安装版现场验收覆盖会话列表、冷启动恢复和资源管理器混合拖放。[0.1.7 验收记录](docs/WINDOWS_017_RELEASE_ACCEPTANCE.md) · [0.1.5 历史发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.5)

## Android 0.1.17 候选版

Android 8.0 及以上可安装。手机从「连接设置」添加可信 HTTPS 服务、命名密钥并验证。会话、草稿和下载的附件保存在应用私有空间，命名密钥由 Android Keystore 保护。首次启动会迁移旧单会话记录。竖屏点会话按钮打开「最近对话」；在聊天页可搜索、选图和录音。

手机直连 AstrBot 时，文字需要 `chat` 权限，收发附件还需要 `file` 权限。共享房间通过个人共享服务接续文字与历史附件提示，安装与配对方式见[跨端指南](docs/SHARED_CHAT_GUIDE.md)。本地 Launcher 的 WhaleTask 命令使用 Windows 端登录入口。

### 手环心率

手机可通过蓝牙直连手环。佩戴手环、开启心率广播，在聊天页点击「心率」→「扫描设备」，按系统提示授予蓝牙扫描权限，然后选择手环。在 Android 11 及更早版本上，蓝牙扫描需要位置权限。手机收到 BPM 后可以关闭面板继续聊天，全程无需电脑。

连接期间，发送普通消息或重发时，Chatbox 会把最近 10 秒内的最新心率和接收时间自动发给当前 AstrBot。仅佩戴手环不会触发网络请求。点击「停止」即可停止读取；断线或读数过期后，后续消息只发送原文。已经随消息发出的心率可能保存在服务端会话或助手的回复中。

### 构建

使用 JDK 17、Android SDK Platform 35 和相应 Build Tools，在仓库根目录执行以下命令。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Android 调试安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。安装、覆盖升级和测试账户见[0.1.17 初验说明](docs/ANDROID_PARITY_QA.md)。[功能与界面对照](docs/ANDROID_PC_PARITY.md) · [验收记录](docs/ANDROID_PARITY_ACCEPTANCE.md)

[官方 HTTP API](https://docs.astrbot.app/dev/openapi.html)
