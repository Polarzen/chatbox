# Chat Box Windows 完整卸载

Windows 0.1.24 增加独立的 **Uninstall Chat Box** 入口。它会卸载这台电脑上的 Chat Box 程序，并清除启动卸载工具的 Windows 账户所保存的 Chat Box 本机数据。

## 使用方法

### 已安装 Windows 0.1.24

1. 在开始菜单搜索并打开 **Uninstall Chat Box**，也可双击安装目录里的 `app\uninstall\uninstall.cmd`。
2. 阅读清理说明，确认永久删除本机数据。取消确认不会启动卸载或清理。
3. 若曾选择外置共享数据库，工具会显示它的完整路径，再单独询问是否一并删除。其他独立服务仍需要这个数据库时，请选择保留。
4. 按 Windows Installer 的提示授权卸载，等待完成结果。工具会先停止共享服务及登录自启动，再卸载程序并清除数据。

本机历史、已保存密钥和设备绑定删除后不能恢复。卸载工具会核对安装包的产品身份及实际程序路径；它只结束对应安装下、属于当前 Windows 用户的 Chat Box 进程。

### 已安装旧版，或程序已经卸载

从 [Windows 0.1.24 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.24) 下载 `Chatbox-Uninstall-0.1.24.zip`，**完整解压**到一个文件夹，再双击 `uninstall.cmd`。请保持三个脚本在同一目录。独立工具会通过 Chat Box 固定的 MSI 产品系列标识查找已安装程序；找不到安装登记时，会清理当前账户已知位置中的剩余本机数据。

独立工具适用于已采用安全卸载逻辑的 0.1.2 及以后版本。若仍安装 0.1.1，工具会停止并提示先完成[旧卸载器安全升级处理](https://github.com/Polarzen/chatbox/blob/v0.1.24/desktop/README.md#从-011-升级)。

## 清理范围

| 项目 | 完整卸载行为 |
| --- | --- |
| MSI 安装的程序和自带运行时 | 交给 Windows Installer 按文件清单移除；安装目录中的其他文件保留 |
| 开始菜单入口、通知链接协议及 MSI 登记的注册表值 | 随 MSI 卸载移除，并检查登记值是否仍存在 |
| `%LOCALAPPDATA%\Chatbox` | 删除本机会话、迁移备份、草稿、应用保存的附件副本、图片和语音缓存、设置及加密密钥文件 |
| `%USERPROFILE%\.chatbox` | 清除旧的后备数据目录 |
| `%USERPROFILE%\.polarzen-chatbox-tmp` | 清除单实例激活记录 |
| `%LOCALAPPDATA%\Polarzen\Chatbox\device-runtime` | 停止共享后清除服务脚本、默认共享数据库、设备绑定和服务凭据 |
| 当前用户启动文件夹中的 `Polarzen Chatbox Device Host.lnk` | 核对它确实指向本机 Chat Box 共享运行时后删除 |
| 用户选用的外置共享数据库 | 仅在单独确认后删除该数据库及对应的 `-wal`、`-shm`、`-journal`、`.serve.lock` 文件；不删除其父目录 |

上表中的用户目录由 Windows 的当前账户目录接口定位。其他 Windows 账户的数据、用户选择的原始图片/音频文件、AstrBot 及 Launcher、Python、外部 ngrok/代理安装和配置不在清理范围内。本机卸载也不会向 AstrBot 服务端或安卓手机发送删除历史的请求。

## 与普通卸载和升级的区别

- **完整卸载入口**：明确确认后，移除程序并清除上述本机数据。
- **Windows 设置里的普通卸载**：按 MSI 清单移除程序，保留运行后生成的用户数据。
- **覆盖升级**：沿用原安装目录和用户数据，不运行完整卸载脚本。
- **直接删除文件夹或桌面图标**：不会执行 MSI 卸载或本机数据清理。

因此，想保留会话继续升级时，直接运行新 MSI；想永久移除本机 Chat Box 时，使用 **Uninstall Chat Box**。

## 没有显示完成时

工具遇到正在使用的数据锁、无法确定归属的自启动项、指向其他位置的目录联接/符号链接或 Windows Installer 错误时，会停止并说明原因，不会扩大删除范围。共享服务有请求正在结束时会等待正常关闭；超时后保留数据并报告未完成。

卸载失败时，窗口会提供本次临时诊断目录，里面有卸载日志和重试脚本。Windows Installer 要求重启时会明确提示，不能将其视为已经完成所有文件移除。独立 ZIP 可在排除错误后再次用于清理；普通成功卸载会移除工具自己建立的临时工作目录。

## 命令行检查和自动化

在独立 ZIP 解压后的文件夹，或安装目录的 `app\uninstall` 文件夹中，可先仅显示待处理范围：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Uninstall-ChatBox.ps1 -WhatIf
```

只有明确需要无人值守永久清除时才使用 `-Yes`。它默认保留外置共享数据库，并在结果中列出保留路径；连外置数据库也要清除时需再显式提供 `-DeleteExternalDatabase`。两个参数都不会关闭路径归属、目录联接、占用锁和 MSI 身份检查。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Uninstall-ChatBox.ps1 -Yes
```

退出码 `0` 表示选定范围清理完成，`2` 表示在确认时取消，`3010` 表示 Windows Installer 要求重启，其他非零退出码表示未完成。该工具是应用卸载与数据删除工具，不是磁盘数据覆写或 Windows 系统日志擦除工具。
