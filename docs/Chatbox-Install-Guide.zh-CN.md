# Chat Box v0.1.23 安装指南

在要使用 Chat Box 的设备上打开对应链接。本次发布包含 Windows 0.1.23 和 Android 0.1.23。两个安装包也可在 [v0.1.23 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.23) 的 **Assets（附件）** 中找到。

| 设备 | 下载安装包 |
| --- | --- |
| Windows x64 电脑 | [Chatbox-0.1.23.msi](https://github.com/Polarzen/chatbox/releases/download/v0.1.23/Chatbox-0.1.23.msi) |
| Android 8.0 及以上手机 | [Chatbox-0.1.23.apk](https://github.com/Polarzen/chatbox/releases/download/v0.1.23/Chatbox-0.1.23.apk) |

如果下载时看到 **404**，请先在当前浏览器登录有权访问此私有仓库的 GitHub 账号，再打开链接。

## Windows

**如果旧版是 0.1.1，请先阅读[旧卸载器升级说明](https://github.com/Polarzen/chatbox/blob/v0.1.23/desktop/README.md#从-011-升级)。** 卸载或覆盖升级都可能触发旧卸载器清理整个安装目录。

1. **升级前备份。** 右键点击任务栏通知区域的应用图标，从托盘菜单退出。在文件资源管理器地址栏输入 `%LOCALAPPDATA%\Chatbox`，把整个文件夹复制到其他位置。使用过独立共享服务的用户也请按[共享数据备份说明](https://github.com/Polarzen/chatbox/blob/v0.1.23/docs/DEVICE_SYNC_GUIDE.md)保存原数据库。首次安装可直接进行下一步。
2. 双击下载的 `Chatbox-0.1.23.msi`，按安装向导完成安装。首次安装可使用默认目录，覆盖升级时请选择旧版的安装目录。
3. 打开 Windows **开始菜单**，搜索 **Chat Box** 并启动。

## Android

1. **已有旧版时保留现有应用。** 先另存重要消息正文和原始附件，尤其是只在旧版远端缓存中可见的内容。依赖 0.1.20 手环原型功能的用户请保留原安装。[手环原型版本说明](https://github.com/Polarzen/chatbox/blob/v0.1.23/docs/CHAT_WINDOW_CONTEXT.md)
2. 在手机浏览器的下载记录或文件管理器的「下载」中，点开 `Chatbox-0.1.23.apk`。如果系统询问是否允许此来源安装应用，按提示仅为当前浏览器或文件管理器开启「允许来自此来源的应用」，返回安装界面。
3. 首次安装点「安装」。覆盖升级点「更新」或「安装」，需要与旧版签名一致的 APK。若系统提示签名不匹配，请保留现有应用及其数据，改用与旧版签名匹配的安装包。完成后点「打开」，也可在手机应用列表找到 **Chat Box**。

## 打开后怎样开始聊天？

收发消息需要连接能访问的 AstrBot 服务。若由他人管理服务，可向管理员索取服务地址和有 `chat` 权限的 OpenAPI 密钥。

- **只用电脑聊天**时，在首次打开的「找到 AstrBot」界面选择运行中的 Launcher 实例或输入服务地址，添加并验证密钥，然后点「仅在本机使用」。以后可在聊天窗口打开「设置」，点「已连接」或「未连接」进入连接设置。
- **只用手机聊天**时，在欢迎页点「新建本机会话」。打开聊天页的「更多操作 → 连接设置」，点「新增 HTTPS 服务」填写 AstrBot 地址，再点「新增命名密钥」保存密钥，验证后回到聊天页发送消息。
- **在两台设备之间接着聊**需要电脑有 Python 3.10 或以上版本。在电脑「连接设置」中点「连接我的手机 · 设备共享设置」，验证 AstrBot，选择手机连接方式，按提示开启共享并生成配对码。手机在欢迎页点「连接我的电脑」扫码，核对共享范围后确认连接。已进入应用时，可从「最近对话 → 设备设置 → 连接我的电脑」进入。[配对步骤与连接方式](https://github.com/Polarzen/chatbox/blob/v0.1.23/docs/DEVICE_SYNC_GUIDE.md#首次连接)

## 核对安装包（可选）

在安装前，可用发布页的 [SHA256SUMS.txt](https://github.com/Polarzen/chatbox/releases/download/v0.1.23/SHA256SUMS.txt) 核对下载是否完整。在 Windows 上，打开保存安装包的文件夹，在地址栏输入 `powershell` 并按回车。只运行与你已下载文件对应的命令。

| 已下载到电脑的文件 | PowerShell 命令 |
| --- | --- |
| Windows MSI | `Get-FileHash .\Chatbox-0.1.23.msi -Algorithm SHA256` |
| Android APK | `Get-FileHash .\Chatbox-0.1.23.apk -Algorithm SHA256` |

将输出的 `Hash` 与清单中**同名文件**的 SHA-256 值逐字核对，字母大小写可忽略。如果不同，请重新下载并核对一致后再安装。
