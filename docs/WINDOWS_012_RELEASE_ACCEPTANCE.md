# Chatbox 0.1.12 Windows 发布验收

## 0.1.11 基线

本轮从已发布的 v0.1.11 接续。PR #7 已合并到 `main`，提交为 `e1b0cb4`，修复了顶栏跨越 639/640 dp 布局切换点时的崩溃。[0.1.11 Release](https://github.com/Polarzen/chatbox/releases/tag/v0.1.11) · [PR #7](https://github.com/Polarzen/chatbox/pull/7) · [合并后 CI](https://github.com/Polarzen/chatbox/actions/runs/45207040358)

## 0.1.12 安装版验收

使用同一份候选 MSI 从原目录的 0.1.11 直接升级，安装程序退出码为 0。通过已注册的开始菜单快捷方式启动 0.1.12。实际安装的可执行文件及 `app`、`runtime` 内共 179 个文件与该 MSI 的管理安装解包结果逐一匹配。日常配置目录的 67 个文件内容保持一致。

| 项目 | 观察结果 | 验证方式 |
| --- | --- | --- |
| A · 截图与文本粘贴 | 两次用 Win+Shift+S 分别截取带 ALPHA、BETA 字样的合成图，在输入框按 Ctrl+V 后出现对应预览。可继续用中文键盘输入、粘贴多行文字和替换选区。在搜索框或改名框粘贴时，附件列表保持原样。 | 安装版、Windows 截图工具 |
| B · 两张图的草稿恢复 | 正常退出后从开始菜单冷启动，恢复多行说明及末尾字符，预览仍按 ALPHA、BETA 排列。移除一张再重开，只剩保留的图片。 | 安装版冷启动 |
| C · 会话和配置隔离 | 同一配置下的同名会话以及跨配置同 ID 的会话各自恢复自己的草稿，改名后归属一致。使用隔离数据在保存完成后强制退出，重开后仍恢复最近保存的内容。 | 安装版、隔离数据 |
| D · 缺失外部图片 | 移走 `upload-a.png` 后，重开显示缺失文件名，保留文字和另一张 JPEG。发送被阻止，上传和聊天提交均为 0 次。重选 PNG 后它仍排在 JPEG 前，发送成功后外部原文件的字节内容保持一致。 | 安装版、隔离服务 |
| E · 主动发送 | 编辑、搜索、退出、恢复均产生 0 次上传、0 次聊天。主动发送后有 2 次 PNG 上传和 1 次聊天提交，消息段依次为 `plain`、`image`、`image`。等待延迟回复时另写的草稿在回复结束和重启后均保留，两张已发送图片可在历史中查看。 | 安装版、隔离服务请求计数 |
| F · 窄窗口回归 | 开启置顶、深色主题与 75% 不透明度，核对原生 TOPMOST 状态及 alpha 值 191。保持外观菜单打开并调整窗口大小，展开、收起侧栏后均可使用输入框、预览及搜索。 | 安装版。[紧凑窗口](evidence/windows-012/appearance-compact.png) · [0.1.10 窗口验收](WINDOWS_010_RELEASE_ACCEPTANCE.md) |
| G · 同一回复的两段语音 | 同一条流式回复中的 8 秒单声道、65 秒双声道语音分别显示 `0:08` 和 `1:05`。播放中、手动停止和自然结束后各自时长可见。离线冷启动后，从缓存恢复相同标签。 | 安装版、隔离服务。[助手语音时长](evidence/windows-012/reply-durations.png) · [离线重开](evidence/windows-012/reply-durations-offline.png) |
| H · WAV 边界与缓存 | 不足 1 秒的样本显示“<1 秒”，带元数据块的样本显示 `0:02`。损坏和缺失缓存保留提示，各自重试同标识的附件后，分别显示 `0:03` 和 `0:04`。实测机器具备输出设备。无输出设备分支另由 Compose 界面测试注入设备异常，缓存时长仍可见。 | 安装版、隔离样本、界面自动化。[重试后时长](evidence/windows-012/reply-duration-retry.png) |
| I · 真实麦克风与 AstrBot | 取消录音生成 0 个 WAV，另一次录音的 `0:04` 草稿可离线恢复试听。最终由用户亲自录制中文短语并试听确认清晰。正常退出、离线冷启动后，说明文字与 `0:05` 的录音均恢复，可试听和手动停止。主动发送前上传及聊天提交均为 0 次。发送后离线重开历史，语音可播放至自然结束，时长仍为 `0:05`，新增 POST 为 0 次。 | 安装版、真实麦克风、实际 AstrBot。[早期录音草稿](evidence/windows-012/mic-draft-offline.png) · [最终录音待发](evidence/windows-012/live-mic-pending.png) · [发送后离线历史](evidence/windows-012/live-mic-sent-history.png) |
| J · 纯语音、混合发送与失败恢复 | 录音中切换会话后，原会话保留 18,500 个采样帧，新会话的待发区为空，麦克风可再次使用。同一段经用户确认的真实录音分别以附文字、纯语音和图文加语音发送，三次服务端收件均与原 WAV 一致。无麦克风、上传失败和提交结果不确定由隔离测试覆盖。 | 安装版、真实麦克风、实际 AstrBot 及隔离故障测试。[真实录音请求与收件记录](evidence/windows-012/live-mic-receipt.json) |

同一组合成截图在输入区、冷启动后的草稿和已发送历史中的三个状态，分别见[粘贴后](evidence/windows-012/paste-pending.png) · [退出重开](evidence/windows-012/paste-restored.png) · [主动发送后的历史](evidence/windows-012/paste-sent-history.png)。

## 实际 AstrBot 语音收件

通过安装版界面向 AstrBot v4.29.4 主动发送同一段真实麦克风录音，每种组合各使用一个独立测试会话。三次提交各生成 1 条用户消息。服务端实际接收的请求数与消息段如下。[真实录音服务端收件记录](evidence/windows-012/live-mic-receipt.json)

| 消息内容 | 文件上传 POST | 聊天 POST | 服务端消息段顺序 |
| --- | ---: | ---: | --- |
| 说明文字和 WAV | 1 | 1 | `plain`、`record` |
| 仅 WAV | 1 | 1 | `record` |
| 说明文字、截图和 WAV | 2 | 1 | `plain`、`image`、`record` |

三次服务端收件中的 WAV 均为 166,044 字节，单声道 16 位 PCM，16,000 Hz、83,000 帧，时长为 5.1875 秒。三份 SHA-256 均与本地录音相同，为 `af3489b74183897d08eb7c30a7e0958e215e0a963363cb5aeab58ca1b89f1b4f`。混合消息的 PNG 也与原件一致。实际助手回复的缓存 WAV 为 32,000 Hz、261,120 帧，时长 8.16 秒，离线历史显示 `0:08`。实例的语音转文字功能已关闭。[本人语音与助手回复](evidence/windows-012/live-mic-sent-history.png)

此前独立使用 Windows SAPI 离线合成 WAV，按相同三种组合在各自独立的测试身份下实测，每组的文件上传数、聊天提交数和消息段顺序与上表一致。服务端三份 WAV 均可读取，SHA-256 与原件一致，为 `1a77cf665281b0a5c740d47e84a97d708a04716726c9d99741e03b672788b429`。每份为单声道 16 位 PCM，采样率 22,050 Hz，共 62,384 帧，时长约 2.829 秒。混合消息中的截图也与原件逐字节一致。本组合成纯语音请求得到无法解析语音的回复。[服务端请求与附件核对记录](evidence/windows-012/astrbot-receipts.json)

合成语音在待发区和本人消息气泡中均显示 `0:02`，离线重开、播放至自然结束后仍显示相同时长。助手回复中的两个独立时长见 G 项。[待发录音](evidence/windows-012/voice-pending.png) · [本人语音气泡](evidence/windows-012/voice-sent-history.png) · [助手语音气泡](evidence/windows-012/reply-durations.png)

## 构建与交付

在 Windows 上使用 JDK 17，从代码提交 `ac271697ece1aced7f35c74cde6ff97c07fef7a0` 运行 `.\gradlew.bat -p desktop --no-daemon test desktopSmoke createDistributable packageMsi` 成功。114 项测试全部通过，失败、错误和跳过均为 0。[PR #8](https://github.com/Polarzen/chatbox/pull/8)

| 安装包 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `Chatbox-0.1.12.msi` | 57,769,984 | `866aefe8f3e6c07b3ffc8d9c896a35a867940aeca0d7c02747863c0d2e2a499e` |

自动化测试使用隔离 HTTP 服务核对上传的附件字节、消息段及请求次数。两张图片产生 2 次上传、1 次聊天提交；纯 WAV 产生 1 次上传、1 次聊天提交；图片、WAV 和文字同发产生 2 次上传、1 次聊天提交。其余回归覆盖输入法组合文字、无麦克风、上传失败、聊天提交结果不确定，以及无音频输出设备时显示缓存语音时长。

代码提交的 Windows 和 Android 回归作业均通过。[代码 CI](https://github.com/Polarzen/chatbox/actions/runs/45214110245) · [验收证据提交 CI](https://github.com/Polarzen/chatbox/actions/runs/45218350765)

上表的 MSI 已从原目录升级安装并完成 A–J 验收。v0.1.12 的发布资产可按上表的字节数和 SHA-256 核对。[v0.1.12 发布位置](https://github.com/Polarzen/chatbox/releases/tag/v0.1.12)
