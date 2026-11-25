# Chat Box 0.1.24 — Windows 完整卸载

Windows 版新增 **Uninstall Chat Box**，用于卸载程序并永久清除当前 Windows 用户的本机数据。

## 使用方法

- 安装本版后，从开始菜单打开 **Uninstall Chat Box**，或运行安装目录中的 `app\uninstall\uninstall.cmd`。
- 也可以下载 **Chatbox-Uninstall-0.1.24.zip**，完整解压后运行 `uninstall.cmd`。独立卸载包会核对当前 Chat Box MSI 的产品身份，不需要先安装一个新的 Chat Box。
- 执行前会明确提示删除范围。会话历史和已保存的密钥删除后无法恢复，需要保留的内容请先另存。

## 清理范围

- 通过 Windows Installer 移除该安装的程序文件、快捷方式和 MSI 管理的注册表登记。
- 清除当前 Windows 用户的会话历史、迁移备份、草稿、附件副本、回复媒体缓存、设置及已保存的密钥。
- 停止 Chat Box 管理的本机共享服务，关闭其登录自启，并清除共享运行时和本机设备绑定数据。
- 若使用了外置共享数据库，会单独显示实际路径并询问是否一并删除；选择保留时会在结果中列明。删除仅限数据库及其辅助文件，不递归删除所在目录。

原始附件、其他 Windows 用户的数据、AstrBot 服务端会话、安卓手机的本地记录，以及独立部署的 AstrBot、Python 和代理工具不在清理范围。Windows 0.1.1 的旧卸载器有额外保护限制，需先按项目旧版升级说明处理。

普通 MSI 覆盖升级不会执行完整数据清理。清理过程中遇到仍在使用的数据、不明路径或 MSI 卸载失败，会报告未完成情况，不会把它当作完整卸载成功。

## 本次下载

- `Chatbox-0.1.24.msi`：Windows 安装包，内含完整卸载入口。
- `Chatbox-Uninstall-0.1.24.zip`：独立完整卸载工具与使用说明。
- `Chatbox-Install-Guide.zh-CN.md`：安装与卸载说明。
- `SHA256SUMS.txt`：上述下载文件的 SHA-256 校验值。

**本次仅发布 Windows 0.1.24，没有新的 Android APK。** Android 继续使用 [v0.1.23 的 Chatbox-0.1.23.apk](https://github.com/Polarzen/chatbox/releases/download/v0.1.23/Chatbox-0.1.23.apk)。

发布流水线要求 Windows 构建、启动与升级验证、隔离安装及完整卸载检查，以及 Android 回归构建全部成功后才创建本 Release。
