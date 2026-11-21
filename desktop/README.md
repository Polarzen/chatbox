# Chatbox Windows 桌面版

本项目在 AstrBot 之上提供独立的 Compose Desktop 聊天界面，无需 QQ、NapCat 或 OneBot。首版只支持文字、同一地址的单会话续聊和本机历史；同时只运行一个桌面实例，以免并发改写本地历史。Android 与桌面端共享官方 AstrBot SSE 网络实现，不同步本机历史。

## 构建

在仓库根目录用 JDK 17，Windows PowerShell 运行：

    .\gradlew.bat -p desktop test desktopSmoke
    .\gradlew.bat -p desktop run
    .\gradlew.bat -p desktop packageMsi

打 MSI 还需要 WiX Toolset 3.x。产物在 `desktop/build/compose/binaries/main/msi/`。Linux 源码验证可运行 `./gradlew -p desktop test desktopSmoke`；Windows 安装包需要 Windows 环境构建。

## 连接与首测

请输入电脑可访问、证书受信任的 AstrBot HTTPS 地址和仅授予聊天权限的 OpenAPI 密钥。密钥只留在进程内存；每次启动重输。历史保存至 Windows 的 `%LOCALAPPDATA%\Chatbox\history.json`，无额外加密。请勿提交真实记录、凭据或私人日志。

先验证文字回复，再重启应用、重新输入密钥，确认历史可读且同一服务端会话能继续。网络中断可能发生在服务已收件之后，应用不自动重发。QQ 人格或插件规则是否适用于独立会话，需要用自己的 AstrBot 实例确认。

测试使用生成的本地 HTTPS 证书与模拟 SSE，不连接真实 AstrBot；Windows CI 对打包应用执行历史与 SSE 自检，不代表真机界面交互或真实 AstrBot 已验收。

[官方 AstrBot OpenAPI](https://docs.astrbot.app/dev/openapi.html)
