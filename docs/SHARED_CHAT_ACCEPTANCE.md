# 跨端文字聊天 0.1.16 初验记录

产品源码为 `6f18d86a861b9cc66bd3090a45e60d7c6ece910f`，分支为 `feature/android-windows-shared-chat`，主线基线为 `b6163a89fe812eb4b24c2eb04b341a32ce54395b`。Android 测试包为 versionCode 16、Android Debug 签名。Windows 包的产品版本为 0.1.16。本次在 Windows 11 上完成构建。

## 同步经过哪里

Windows 和 Android 均使用普通客户端界面。选择共享后，设备凭据只授权一个个人共享服务的一个房间；上游聊天密钥留在服务端。创建时按原用户名、WebChat 会话和实际 AstrBot 实例核对已有会话。加入设备时使用十分钟有效的一次性加入码。Android Keystore 与 Windows DPAPI 分别保护可重启恢复的设备绑定。

共享服务读取 AstrBot 的 SSE，将用户可见的提问、累计回复和终态更新到 SQLite。客户端按轮次序号分页读取，使用稳定请求编号合并，发送端与对端都能看到更新。查历史、打开页面和重连不会调用生成接口。旧历史范围限于创建端当前选中会话保存的正文和附件提示；其它设备独有、已清理或未保存的轮次存在缺口。使用步骤见[安装与配对指南](SHARED_CHAT_GUIDE.md)。

## 已验证

| 环境 | 实际结果 |
| --- | --- |
| Android 构建 | `:app:testDebugUnitTest :app:assembleDebug` 完成。2 项测试通过，0 项跳过；`apksigner verify` 确认交付 APK 的 v2 签名有效。 |
| Windows 构建 | Windows 11 25H2（26200.9457）上执行 `-p desktop test desktopSmoke createDistributable packageMsi`。154 项通过，16 项跳过，0 项失败。跳过项为 14 项需专用 fixture 的旧 UI 验收、1 项窗口前台条件测试和 1 项音频设备测试。MSI 的 ProductVersion 为 0.1.16；用 `msiexec /a` 实际解包后运行其中的 `Chatbox.exe --smoke-test`，成功写出验证标记。 |
| 服务回归 | Python 16 项通过，包括 10 项 HTTP 集成测试及 6 项 Launcher 测试。10 项 HTTP 测试合计收到 14 次测试上游聊天 POST，其中 13 次进入生成、1 次被上游明确拒绝。覆盖双方并发、请求去重、掉线补页、重复文本、进程崩溃、未决轮次和权限撤销。 |
| Windows 界面与可控上游 | 两项 Compose UI 测试通过。验证普通窗口导入当前会话和图片提示、重开、档案隔离及两席自动同步。「青松47」追问成功，已送达但响应丢失时生成一次，按原编号手动重试也仅生成一次。 |
| 同机隔离的真实 AstrBot 4.29.4 | 仅含 `chat` 的测试密钥可以发消息、列出会话，但读取该 Chatbox 会话的详情返回业务错误 `Permission denied`。两端接续后，测试模型在实际输入中收到先前的代号并正确回答。整轮 4 次正文生成和 1 次自动标题，重复提交没有额外生成。[脱敏接口样例](SHARED_CHAT_API.md) |
| HTTPS 交付包 | 从 ZIP 解压实际服务及 Caddy 2.11.9，用域名 `chatbox.home.arpa` 校验 SAN，经 TLS 1.3 成功建立并加入空房间。此次连接走 Windows 回环。 |
| Android 模拟器 | Google Android Emulator 37.3.10，Android 8.0 / API 26 x86_64。实际 APK 通过受信 HTTPS 加入、收发、后台返回和强制结束后恢复。[设备记录](SHARED_ANDROID_QA.md) |
| 同一房间中的最终 APK 与 MSI | 从 Android 界面发送后，MSI 解包启动的 Windows 客户端自动显示相同提问和完整回复。Windows 端使用隔离档案预置主人绑定。手机掉线期间的另两轮由主机服务 API 发出；恢复后手机自动补齐且进程重开后仍在。Windows 安全中心的 ADB 防火墙提示挡住了从该 MSI 窗口点击发送的最后一步；Windows 界面发送已在上述 Compose UI 验收中执行。 |

## 现场待验

本机未连接 Redmi Turbo 3。最终 MSI 窗口直接发送到手机、Android 16、HyperOS 3 的证书安装、局域网 Wi-Fi、后台策略以及现场 PC 整机重启后的组合行为，留给瑶使用同一组文件复验。日常 AstrBot 当时唯一有效的密钥带管理权限，因此对该实例的真实聊天请求数为零。隔离实例使用了真实 AstrBot 后端和受控模型。

请核对交付目录中的 `SHA256SUMS.txt`，按[两轮初验](SHARED_CHAT_GUIDE.md#两轮初验)开始。初验通过后再安排 Polarzen 复验。若版本改变，按新文件的哈希复验受影响项目。
