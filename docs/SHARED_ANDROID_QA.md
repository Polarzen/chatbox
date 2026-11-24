# Android 共享会话模拟器验证

2026-11-24 在 Windows 上使用独立的 Google Android Emulator 37.3.10，运行 Android 8.0（API 26）AOSP x86_64 镜像。启动模拟器前，`adb devices -l` 的设备列表为空，未取得 Redmi 或 HyperOS 的真机结果。

## 最终安装包复验

产品源码版本为 `6f18d86`。Android 0.1.16（versionCode 16）的 APK SHA-256 为 `26ccf90b5b1e0a99d34e7fbdb8983ec89b00af0c3f157a36abed62fc45a82ca4`，在同一 AVD 上覆盖安装成功。Windows 界面来自 MSI 解包目录的 `Chatbox.exe`，EXE SHA-256 为 `5e58990c7e2ab56b0512c6efafed494c67611ef2b40219036cf6d78a5ebfb30b`。此次启动了最终版本的共享服务，使用新的 SQLite 数据库；开始时查询得到 0 个房间、0 条记录。

| 场景 | 操作与观察 |
| --- | --- |
| Android 加入空房间 | 隔离服务通过正常 API 创建空房间。手机从最终 APK 的加入表单提交一次性码，显示「已同步」，通过输入框发送 `Phone A1 final`。主人端 API 读到相同问题、回复及 `complete` 状态。 |
| 掉线期间产生两轮对话 | 仅移除 AVD 的测试端口映射，等手机实际显示连接错误；主人端分别通过服务 API 发送 `Service B1 offline` 和 `Service B2 offline`，均获 HTTP 202。恢复映射后，手机在未点击刷新的情况下显示两轮提问及回复，服务端序号为 2、3，状态均为 `complete`。 |
| 手机进程重开 | 通过 `am force-stop` 结束进程后，从正常入口重开。手机恢复同一房间，并显示 `Service B2 offline`。 |
| 最终 APK → MSI 客户端 | 使用成品里的 `DesktopStore`、`DesktopSharedStorage` 和 Windows DPAPI，给全新隔离档案预置该房间的主人绑定。从 MSI 解包目录启动客户端，界面显示「已同步」和前三轮记录。随后从 Android 界面发送 `Phone to MSI live`。Windows 应用保持打开，界面自动新增同一提问和回复「片段完成〔图片〕」。 |

Windows 有一个来自 ADB 的系统防火墙权限弹窗。复验期间保留了系统设置；使用 Computer Use 只读核对了弹窗下方的 Chatbox 记录区域。直接在该 MSI 窗口输入和发送的动作尚待核验。上面两条掉线消息的发送端是主机上的共享服务 API。此次上游仍为测试用的 AstrBot SSE 服务。

## 候选 APK

通过 `adb -s emulator-5586 install -r app-debug.apk` 安装 0.1.16（versionCode 16），使用 `am start -W` 启动应用，再通过 ADB 输入操作客户端界面。UI Automator 读取前台实际显示的文字。此轮 APK 的 SHA-256 为 `7bb00073e03cf83030e7536d9a1516e7171a49ecb141a9db731c1ffd46336cdf`。

共享服务使用项目中的 Python 实现、独立 SQLite 数据库和仓库内的 AstrBot SSE 测试服务。主机端以房间主人的凭据通过 HTTP API 操作。Android 通过 `https://chatbox.home.arpa:8443` 连接，模拟器的用户 CA 存储信任临时测试 CA，服务端证书包含该域名。域名解析和 ADB 反向端口映射仅在测试设备上配置。

| 操作 | 观察结果 |
| --- | --- |
| 在 Android 上打开「跨设备共享会话」→「加入已有会话」，输入服务地址和一次性加入码 | 显示「已同步」、导入的问题 `QA imported history` 和回复 `QA imported answer`。 |
| 主机端在同一房间发送 `QA host to Android`，保持 Android 共享界面在前台 | 未操作 Android 界面。随后读取界面时已显示新提问和回复「片段完成〔图片〕」。 |
| 在 Android 共享界面输入 `QA Android to host`，点击「发送」 | Android 显示提问及回复。主机通过服务 API 读取到相同问题和回复，序号为 3，状态为 `complete`。 |
| 按 Home 将应用切到后台，主机端发送另一条问题，然后返回应用 | 共享界面自动显示后台期间产生的问题和回复。 |
| 通过 `am force-stop` 结束进程，再次从正常入口启动 | 应用恢复共享房间并显示「已同步」和先前的提问及回复。 |

这一轮覆盖 Android 客户端与真实共享服务的通信。主机端使用 API，AstrBot 上游为测试服务。Redmi 局域网路由、真机证书安装、HyperOS 后台行为及生产 AstrBot 均未在这一轮验证。
