# Chatbox

Chatbox 是连接 AstrBot 的 Windows 与 Android 聊天客户端。两端配对一次，即可从「最近对话」查看历史、新建会话并继续聊天，也可选择仅在本机使用。

## 下载稳定版 0.1.19

[Windows 安装包（MSI）](https://github.com/Polarzen/chatbox/releases/download/v0.1.19/Chatbox-0.1.19.msi) · [Android 安装包（APK）](https://github.com/Polarzen/chatbox/releases/download/v0.1.19/Chatbox-0.1.19.apk) · [SHA-256 校验文件](https://github.com/Polarzen/chatbox/releases/download/v0.1.19/SHA256SUMS.txt) · [发布说明](https://github.com/Polarzen/chatbox/releases/tag/v0.1.19)

首次连接时，在电脑端验证 AstrBot，选择手机连接方式，再用手机扫码配对。共享服务需要 Python 3.10 或更高版本，可复用 AstrBot Launcher 的环境。[安装、首次连接与恢复指南](docs/DEVICE_SYNC_GUIDE.md)

升级前退出 Windows Chatbox 并备份历史，随后沿用原目录安装。Android 使用同签名 APK 覆盖安装。只保存在旧版远端缓存中的重要正文，请在升级前另存。Windows 0.1.1 用户请先阅读[旧卸载器升级说明](desktop/README.md#从-011-升级)。[Windows 安装说明](desktop/README.md) · [实机验收记录](docs/DEVICE_SYNC_ACCEPTANCE.md)

## 0.1.20 手环历史数据原型（已暂停）

功能分支保留了 Android 的 Health Connect 读取、电脑共享服务和 AstrBot 查询接口，以及可分别开关的运动总结和晨间摘要。真实手环记录的全自动链路尚未通过验收，0.1.20 暂缓正式发布。

恢复开发的前提是验证系统数据源能自动提供真实手环记录，或获得官方接入批准。后续优先核对睡眠，其次是步数和全天历史心率。现有原型的历史心率查询只覆盖运动时段。[原型配置与复验指南](docs/WEARABLE_GUIDE.md) · [验证记录](docs/WEARABLE_ACCEPTANCE.md)

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
